package transcript

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"gemini-server/pkg/models"
)

var (
	userRequestRegex    = regexp.MustCompile(`(?s)<USER_REQUEST>(.*?)</USER_REQUEST>`)
	contextSummaryRegex = regexp.MustCompile(`(?s)<CONTEXT_SUMMARY>(.*?)</CONTEXT_SUMMARY>`)
	metaRegex           = regexp.MustCompile(`(?s)<ADDITIONAL_METADATA>(.*?)</ADDITIONAL_METADATA>`)
	settingsRegex       = regexp.MustCompile(`(?s)<USER_SETTINGS_CHANGE>(.*?)</USER_SETTINGS_CHANGE>`)
	allTagsRegex        = regexp.MustCompile(`<[^>]+>`)
	imageOutputRegex    = regexp.MustCompile(`(?i)Generated image is saved at\s+([^\s]+\.(?:jpg|png|webp|jpeg))`)
)

// ExtractPromptText removes internal system tags and returns clean user request text.
func ExtractPromptText(content string) string {
	if content == "" {
		return ""
	}
	if match := userRequestRegex.FindStringSubmatch(content); len(match) > 1 {
		return strings.TrimSpace(match[1])
	}
	cleaned := contextSummaryRegex.ReplaceAllString(content, "")
	cleaned = metaRegex.ReplaceAllString(cleaned, "")
	cleaned = settingsRegex.ReplaceAllString(cleaned, "")
	return strings.TrimSpace(allTagsRegex.ReplaceAllString(cleaned, ""))
}

// ExtractContextSummary extracts compacted context summary.
func ExtractContextSummary(content string) string {
	if match := contextSummaryRegex.FindStringSubmatch(content); len(match) > 1 {
		return strings.TrimSpace(match[1])
	}
	return ""
}

// ExtractSystemMetadata extracts XML metadata into structured object.
func ExtractSystemMetadata(content string) *models.SystemMetadata {
	meta := &models.SystemMetadata{}
	hasData := false

	if match := metaRegex.FindStringSubmatch(content); len(match) > 1 {
		meta.AdditionalMetadata = strings.TrimSpace(match[1])
		hasData = true
	}
	if match := settingsRegex.FindStringSubmatch(content); len(match) > 1 {
		meta.UserSettingsChange = strings.TrimSpace(match[1])
		hasData = true
	}

	if hasData {
		return meta
	}
	return nil
}

// FormatToolCommand converts tool name and arguments into a concise terminal preview.
func FormatToolCommand(name string, args map[string]interface{}) string {
	if len(args) == 0 {
		return name
	}
	if cmd, ok := args["CommandLine"].(string); ok && cmd != "" {
		return cmd
	}
	if path, ok := args["AbsolutePath"].(string); ok && path != "" {
		return path
	}
	if dir, ok := args["DirectoryPath"].(string); ok && dir != "" {
		return dir
	}
	if q, ok := args["Query"].(string); ok {
		if sp, ok2 := args["SearchPath"].(string); ok2 {
			return fmt.Sprintf("%s in %s", q, sp)
		}
		return q
	}
	if q, ok := args["query"].(string); ok {
		return q
	}
	if url, ok := args["Url"].(string); ok {
		return url
	}
	if tf, ok := args["TargetFile"].(string); ok {
		return tf
	}
	b, _ := json.Marshal(args)
	return string(b)
}

type rawStep struct {
	StepIndex       *int                   `json:"step_index"`
	Source          string                 `json:"source"`
	Type            string                 `json:"type"`
	Status          string                 `json:"status"`
	CreatedAt       string                 `json:"created_at"`
	Content         string                 `json:"content"`
	Thinking        string                 `json:"thinking"`
	DurationSeconds *float64               `json:"duration_seconds"`
	ToolCalls       []rawToolCall          `json:"tool_calls"`
	Usage           *rawUsage              `json:"usage"`
}

type rawToolCall struct {
	Name string                 `json:"name"`
	Args map[string]interface{} `json:"args"`
}

type rawUsage struct {
	InputTokens    int `json:"input_tokens"`
	OutputTokens   int `json:"output_tokens"`
	ThinkingTokens int `json:"thinking_tokens"`
	CacheRead      int `json:"cache_read_tokens"`
	TotalTokens    int `json:"total_tokens"`
}

// ParseTranscript parses a transcript.jsonl file into clean ChatMessage slice.
func ParseTranscript(transcriptPath, convID string, port string) ([]models.ChatMessage, error) {
	file, err := os.Open(transcriptPath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var messages []models.ChatMessage
	var currentAgentTurn *models.ChatMessage
	var lastPendingTool *models.ToolCall
	var pendingCheckpointSummary string

	flushAgentTurn := func() {
		if currentAgentTurn != nil && (strings.TrimSpace(currentAgentTurn.Content) != "" || len(currentAgentTurn.ToolCalls) > 0) {
			hasGenerateImage := false
			for _, tc := range currentAgentTurn.ToolCalls {
				if tc.Name == "generate_image" {
					hasGenerateImage = true
					break
				}
			}
			if hasGenerateImage {
				convBrain := filepath.Dir(filepath.Dir(transcriptPath))
				if entries, err := os.ReadDir(convBrain); err == nil {
					for _, e := range entries {
						if !e.IsDir() && (strings.HasSuffix(e.Name(), ".jpg") || strings.HasSuffix(e.Name(), ".png") || strings.HasSuffix(e.Name(), ".webp")) {
							if !strings.Contains(currentAgentTurn.Content, e.Name()) {
								imgName := strings.TrimSuffix(e.Name(), filepath.Ext(e.Name()))
								currentAgentTurn.Content += fmt.Sprintf("\n\n![%s](http://127.0.0.1:%s/artifacts/%s/%s)\n", imgName, port, convID, e.Name())
							}
						}
					}
				}
			}

			currentAgentTurn.Content = strings.TrimSpace(currentAgentTurn.Content)
			messages = append(messages, *currentAgentTurn)
		}
		currentAgentTurn = nil
		lastPendingTool = nil
	}

	scanner := bufio.NewScanner(file)
	buf := make([]byte, 1024*1024)
	scanner.Buffer(buf, 10*1024*1024)

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		var step rawStep
		if err := json.Unmarshal([]byte(line), &step); err != nil {
			continue
		}

		switch step.Type {
		case "CHECKPOINT":
			flushAgentTurn()
			pendingCheckpointSummary = step.Content

		case "USER_INPUT":
			flushAgentTurn()
			raw := step.Content
			cleanPrompt := ExtractPromptText(raw)
			summary := ExtractContextSummary(raw)
			if summary == "" {
				summary = pendingCheckpointSummary
			}
			pendingCheckpointSummary = ""

			userMsg := models.ChatMessage{
				Role:           "user",
				Content:        cleanPrompt,
				RawContent:     raw,
				StepIndex:      step.StepIndex,
				ContextSummary: summary,
				SystemMetadata: ExtractSystemMetadata(raw),
				CreatedAt:      step.CreatedAt,
			}
			messages = append(messages, userMsg)

		case "PLANNER_RESPONSE":
			if currentAgentTurn == nil {
				var tokenUsage *models.TokenUsage
				if step.Usage != nil {
					tokenUsage = &models.TokenUsage{
						InputTokens:     step.Usage.InputTokens,
						OutputTokens:    step.Usage.OutputTokens,
						ThinkingTokens:  step.Usage.ThinkingTokens,
						CacheReadTokens: step.Usage.CacheRead,
						TotalTokens:     step.Usage.TotalTokens,
					}
				}

				currentAgentTurn = &models.ChatMessage{
					Role:            "agent",
					Content:         "",
					Thinking:        strings.TrimSpace(step.Thinking),
					StepIndex:       step.StepIndex,
					DurationSeconds: step.DurationSeconds,
					TokenUsage:      tokenUsage,
					CreatedAt:       step.CreatedAt,
				}
			}

			if strings.TrimSpace(step.Thinking) != "" {
				currentAgentTurn.Content += fmt.Sprintf("\n<!-- thought -->\n%s\n<!-- /thought -->\n", strings.TrimSpace(step.Thinking))
				if currentAgentTurn.Thinking == "" {
					currentAgentTurn.Thinking = strings.TrimSpace(step.Thinking)
				}
			}

			if len(step.ToolCalls) > 0 {
				for _, tc := range step.ToolCalls {
					toolIdx := len(currentAgentTurn.ToolCalls)
					toolID := fmt.Sprintf("tc_%s_%d_%d", convID, len(messages), toolIdx)
					cmd := FormatToolCommand(tc.Name, tc.Args)
					toolObj := models.ToolCall{
						ID:      toolID,
						Name:    tc.Name,
						Command: cmd,
						Status:  "SUCCESS",
					}
					currentAgentTurn.ToolCalls = append(currentAgentTurn.ToolCalls, toolObj)
					lastPendingTool = &currentAgentTurn.ToolCalls[len(currentAgentTurn.ToolCalls)-1]
					currentAgentTurn.Content += fmt.Sprintf("\n<!-- tool_call:%s -->\n", toolID)
				}
			}

			if strings.TrimSpace(step.Content) != "" {
				currentAgentTurn.Content += fmt.Sprintf("\n%s\n", strings.TrimSpace(step.Content))
			}

		case "GENERIC":
			if lastPendingTool != nil && step.Content != "" {
				lastPendingTool.Output = step.Content
			}
			if match := imageOutputRegex.FindStringSubmatch(step.Content); len(match) > 1 && currentAgentTurn != nil {
				fullPath := strings.TrimSuffix(match[1], ".")
				fileName := filepath.Base(fullPath)
				if !strings.Contains(currentAgentTurn.Content, fileName) {
					imgName := strings.TrimSuffix(fileName, filepath.Ext(fileName))
					currentAgentTurn.Content += fmt.Sprintf("\n\n![%s](http://127.0.0.1:%s/artifacts/%s/%s)\n", imgName, port, convID, fileName)
				}
			}
			lastPendingTool = nil
		}
	}

	flushAgentTurn()
	return messages, nil
}
