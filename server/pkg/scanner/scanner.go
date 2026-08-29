package scanner

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"gemini-server/pkg/models"
)

var frontmatterRegex = regexp.MustCompile(`(?s)---\s*(.*?)\s*---`)

func parseFrontmatter(fm string) (string, string) {
	name := ""
	var descLines []string
	inDesc := false

	lines := strings.Split(fm, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "name:") {
			name = strings.TrimSpace(strings.TrimPrefix(trimmed, "name:"))
			inDesc = false
		} else if strings.HasPrefix(trimmed, "description:") {
			d := strings.TrimSpace(strings.TrimPrefix(trimmed, "description:"))
			d = strings.TrimPrefix(d, ">-")
			d = strings.TrimPrefix(d, ">")
			d = strings.TrimSpace(d)
			if d != "" {
				descLines = append(descLines, d)
			}
			inDesc = true
		} else if inDesc {
			if strings.Contains(line, ":") && !strings.HasPrefix(line, " ") && !strings.HasPrefix(line, "\t") {
				inDesc = false
			} else if trimmed != "" {
				descLines = append(descLines, trimmed)
			}
		}
	}
	return name, strings.Join(descLines, " ")
}

// ScanSkills recursively inspects a directory for SKILL.md files and parses YAML frontmatter.
func ScanSkills(baseDir string) []models.SkillInfo {
	var skills []models.SkillInfo
	if _, err := os.Stat(baseDir); os.IsNotExist(err) {
		return skills
	}

	entries, err := os.ReadDir(baseDir)
	if err != nil {
		return skills
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		skillPath := filepath.Join(baseDir, entry.Name(), "SKILL.md")
		data, err := os.ReadFile(skillPath)
		if err != nil {
			continue
		}

		name := entry.Name()
		description := ""

		if match := frontmatterRegex.FindSubmatch(data); len(match) > 1 {
			parsedName, parsedDesc := parseFrontmatter(string(match[1]))
			if parsedName != "" {
				name = parsedName
			}
			description = parsedDesc
		}

		skills = append(skills, models.SkillInfo{
			Name:        name,
			Path:        skillPath,
			Description: description,
		})
	}
	return skills
}

// ScanWorkspaceRules extracts GEMINI.md and .gemini/rules/*.md rules.
func ScanWorkspaceRules(wsDir string) string {
	if wsDir == "" {
		return ""
	}
	var rules []string

	geminiMd := filepath.Join(wsDir, "GEMINI.md")
	if data, err := os.ReadFile(geminiMd); err == nil {
		rules = append(rules, fmt.Sprintf("[Workspace Rule: GEMINI.md]\n%s", strings.TrimSpace(string(data))))
	}

	rulesDir := filepath.Join(wsDir, ".gemini", "rules")
	if entries, err := os.ReadDir(rulesDir); err == nil {
		for _, e := range entries {
			if !e.IsDir() && strings.HasSuffix(e.Name(), ".md") {
				if d, err := os.ReadFile(filepath.Join(rulesDir, e.Name())); err == nil {
					rules = append(rules, fmt.Sprintf("[Rule: %s]\n%s", e.Name(), strings.TrimSpace(string(d))))
				}
			}
		}
	}

	return strings.Join(rules, "\n\n")
}

// GetToolDeclarations returns the full OpenAPI JSON schemas for all 15 Antigravity tools.
func GetToolDeclarations() []map[string]interface{} {
	return []map[string]interface{}{
		{
			"name":        "view_file",
			"description": "View the contents of a file from the local filesystem. Supports text files and binary files (image, pdf, video, audio).",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"AbsolutePath":  map[string]string{"type": "string", "description": "Path to file to view. Must be an absolute path."},
					"StartLine":     map[string]string{"type": "integer", "description": "Optional start line (1-indexed)."},
					"EndLine":       map[string]string{"type": "integer", "description": "Optional end line (1-indexed)."},
					"ContentOffset": map[string]string{"type": "integer", "description": "Optional byte offset for truncated content."},
					"toolAction":    map[string]string{"type": "string"},
					"toolSummary":   map[string]string{"type": "string"},
				},
				"required": []string{"AbsolutePath", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "replace_file_content",
			"description": "Edit an existing file by replacing a single contiguous block of text with new content.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"TargetFile":         map[string]string{"type": "string", "description": "Target file absolute path."},
					"TargetContent":      map[string]string{"type": "string", "description": "The exact string to be replaced."},
					"ReplacementContent": map[string]string{"type": "string", "description": "The replacement content."},
					"StartLine":          map[string]string{"type": "integer"},
					"EndLine":            map[string]string{"type": "integer"},
					"Instruction":        map[string]string{"type": "string"},
					"Description":        map[string]string{"type": "string"},
					"AllowMultiple":      map[string]string{"type": "boolean"},
					"toolAction":         map[string]string{"type": "string"},
					"toolSummary":        map[string]string{"type": "string"},
				},
				"required": []string{"TargetFile", "Instruction", "Description", "AllowMultiple", "TargetContent", "ReplacementContent", "StartLine", "EndLine", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "write_to_file",
			"description": "Create new files or overwrite existing files.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"TargetFile":  map[string]string{"type": "string"},
					"Overwrite":   map[string]string{"type": "boolean"},
					"CodeContent": map[string]string{"type": "string"},
					"Description": map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"TargetFile", "Overwrite", "CodeContent", "Description", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "run_command",
			"description": "PROPOSE a command to run on behalf of the user. Operating System: linux. Shell: bash.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"CommandLine":       map[string]string{"type": "string"},
					"Cwd":               map[string]string{"type": "string"},
					"WaitMsBeforeAsync": map[string]string{"type": "integer"},
					"toolAction":        map[string]string{"type": "string"},
					"toolSummary":       map[string]string{"type": "string"},
				},
				"required": []string{"Cwd", "WaitMsBeforeAsync", "CommandLine", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "grep_search",
			"description": "Use ripgrep to find exact pattern matches within files or directories.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"SearchPath":  map[string]string{"type": "string"},
					"Query":       map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"SearchPath", "Query", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "find_by_name",
			"description": "Search for files and subdirectories within a specified directory using fd.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"SearchDirectory": map[string]string{"type": "string"},
					"Pattern":         map[string]string{"type": "string"},
					"toolAction":      map[string]string{"type": "string"},
					"toolSummary":     map[string]string{"type": "string"},
				},
				"required": []string{"SearchDirectory", "Pattern", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "list_dir",
			"description": "List the contents of a directory, including recursive child counts and byte sizes.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"DirectoryPath": map[string]string{"type": "string"},
					"toolAction":    map[string]string{"type": "string"},
					"toolSummary":   map[string]string{"type": "string"},
				},
				"required": []string{"DirectoryPath", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "ask_question",
			"description": "Render interactive multiple-choice question modal to clarify requirements or preferences.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"questions":   map[string]string{"type": "array"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"questions", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "invoke_subagent",
			"description": "Invokes one or more subagents by name with isolated prompt contexts.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"Subagents":   map[string]string{"type": "array"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"Subagents", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "define_subagent",
			"description": "Defines a new type of subagent that can be invoked via invoke_subagent.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"name":        map[string]string{"type": "string"},
					"description": map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"name", "description", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "manage_subagents",
			"description": "Manage existing subagents (list, kill, kill_all).",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"Action":      map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"Action", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "schedule",
			"description": "Schedule a one-shot timer or recurring cron job.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"Prompt":      map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"Prompt", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "search_web",
			"description": "Performs a web search for documentation, packages, or real-time internet search results.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"query":       map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"query", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "read_url_content",
			"description": "Fetch content from a URL via HTTP request and convert HTML to markdown.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"Url":         map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"Url", "toolSummary", "toolAction"},
			},
		},
		{
			"name":        "generate_image",
			"description": "Generate an image or UI asset based on text prompt.",
			"parameters": map[string]interface{}{
				"type": "object",
				"properties": map[string]interface{}{
					"Prompt":      map[string]string{"type": "string"},
					"ImageName":   map[string]string{"type": "string"},
					"toolAction":  map[string]string{"type": "string"},
					"toolSummary": map[string]string{"type": "string"},
				},
				"required": []string{"Prompt", "ImageName", "toolSummary", "toolAction"},
			},
		},
	}
}

// CompileSystemPrompt dynamically synthesizes the full active system prompt.
func CompileSystemPrompt(conversationID, workspaceDir, appDataDir string) string {
	builtinSkills := ScanSkills(filepath.Join(appDataDir, "builtin", "skills"))
	wsSkills := ScanSkills(filepath.Join(workspaceDir, ".gemini", "skills"))
	allSkills := append(builtinSkills, wsSkills...)

	var skillLines []string
	for _, s := range allSkills {
		skillLines = append(skillLines, fmt.Sprintf("- %s (%s): %s", s.Name, s.Path, s.Description))
	}
	skillsText := strings.Join(skillLines, "\n")
	if len(allSkills) == 0 {
		skillsText = "No active skills detected."
	}

	wsRules := ScanWorkspaceRules(workspaceDir)
	rulesBlock := ""
	if wsRules != "" {
		rulesBlock = fmt.Sprintf("<workspace_rules>\n%s\n</workspace_rules>\n\n", wsRules)
	}

	brainDir := filepath.Join(appDataDir, "brain", conversationID)
	toolSchemasJSON, _ := json.MarshalIndent(GetToolDeclarations(), "", "  ")

	return fmt.Sprintf(`<identity>
You are Antigravity, a powerful agentic AI coding assistant designed by the Google Deepmind team working on Advanced Agentic Coding.
You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.
The USER will send you requests, which you must always prioritize addressing. User requests are enclosed within <USER_REQUEST> tags.
</identity>

<user_information>
The USER's OS version is linux.
The user has active workspace:
%s -> %s
App Data Directory: %s
Conversation ID: %s
</user_information>

<skills>
You can use specialized 'skills' to help you with complex tasks.
Available skills:
%s
</skills>

<subagents>
Available subagents:
- self: Subagent that inherits the parent agent's full configuration including tools, system prompt, and model.
- research: Research subagent with read-only tools for exploring the codebase, searching the web, and reading files.
</subagents>

<messaging>
You are connected to a messaging system where you may receive messages from: agents, background tasks, user-queued messages.
</messaging>

<conversation_transcript>
Conversation transcripts are stored locally under: <appDataDir>/brain/<conversation-id>/.system_generated/logs/transcript.jsonl
</conversation_transcript>

<artifacts>
Artifact Directory Path: %s
</artifacts>

<slash_commands>
Available slash commands you can recommend to the user:
- /goal, /schedule, /browser, /plan, /grill-me, /teamwork-preview, /learn
</slash_commands>

<guidelines>
- Maintain documentation integrity. Preserve all existing comments and docstrings.
</guidelines>

%s<communication_style>
- Keep your responses concise.
- Format your responses in github-style markdown.
- Create clickable links for all files and code symbols.
</communication_style>

<tool_declarations>
%s
</tool_declarations>`,
		workspaceDir, workspaceDir, appDataDir, conversationID,
		skillsText,
		brainDir,
		rulesBlock,
		string(toolSchemasJSON),
	)
}
