package models_discovery

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/models"
)

var (
	ansiRegex     = regexp.MustCompile(`\x1B\[[0-9;]*[a-zA-Z]`)
	brailleRegex  = regexp.MustCompile(`[\x{2800}-\x{28FF}]`)
	spacesRegex   = regexp.MustCompile(`\s{2,}|\t+`)
	cacheMu       sync.RWMutex
	cachedModels  []models.AiModel
	isFetching    bool
)

// FetchAvailableModels discovers models dynamically from "agy models" CLI.
func FetchAvailableModels(force bool) []models.AiModel {
	cacheMu.RLock()
	if !force && len(cachedModels) > 0 {
		defer cacheMu.RUnlock()
		return cachedModels
	}
	cacheMu.RUnlock()

	cacheMu.Lock()
	if isFetching {
		cacheMu.Unlock()
		if len(cachedModels) > 0 {
			return cachedModels
		}
		return fallbackModels()
	}
	isFetching = true
	cacheMu.Unlock()

	defer func() {
		cacheMu.Lock()
		isFetching = false
		cacheMu.Unlock()
	}()

	fmt.Println("[Models] Querying dynamic model list via agy models CLI...")
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	cmd := exec.CommandContext(ctx, "agy", "models")
	cmd.Env = append(os.Environ(), "NO_COLOR=1", "AGY_AUTO_UPDATE=0")

	outBytes, err := cmd.Output()
	if err != nil || len(outBytes) == 0 {
		fmt.Printf("[Models] agy models CLI call failed: %v\n", err)
		return fallbackModels()
	}

	cleaned := string(outBytes)
	cleaned = ansiRegex.ReplaceAllString(cleaned, "")
	cleaned = brailleRegex.ReplaceAllString(cleaned, "")
	cleaned = strings.ReplaceAll(cleaned, "Fetching available models...", "")
	cleaned = strings.ReplaceAll(cleaned, "\r", "\n")

	lines := strings.Split(cleaned, "\n")
	var discovered []models.AiModel
	seen := make(map[string]bool)

	for _, line := range lines {
		clean := strings.TrimSpace(line)
		if clean == "" || strings.HasPrefix(clean, "Report") || strings.HasPrefix(clean, "➜") || strings.Contains(clean, "Available models") {
			continue
		}

		parts := spacesRegex.Split(clean, -1)
		if len(parts) >= 1 {
			id := strings.TrimSpace(parts[0])
			name := id
			if len(parts) >= 2 && strings.TrimSpace(parts[1]) != "" {
				name = strings.TrimSpace(parts[1])
			}

			if id == "" || len(id) < 3 || strings.Contains(id, " ") || seen[id] {
				continue
			}
			seen[id] = true

			lower := strings.ToLower(id)
			family := "OTHER"
			provider := "OpenAI"
			maxTokens := 131072

			if strings.Contains(lower, "gemini") {
				family = "GEMINI"
				provider = "Google"
				maxTokens = 1048576
			} else if strings.Contains(lower, "claude") {
				family = "CLAUDE"
				provider = "Anthropic"
				maxTokens = 250000
			}

			thinking := strings.Contains(lower, "high") ||
				strings.Contains(lower, "medium") ||
				strings.Contains(lower, "low") ||
				strings.Contains(lower, "thinking")

			discovered = append(discovered, models.AiModel{
				ID:               id,
				DisplayName:      name,
				Family:           family,
				Provider:         provider,
				MaxTokens:        maxTokens,
				SupportsThinking: thinking,
				Recommended:      true,
				IsDefault:        id == "gemini-3.7-flash-high",
			})
		}
	}

	if len(discovered) > 0 {
		cacheMu.Lock()
		cachedModels = discovered
		cacheMu.Unlock()
		fmt.Printf("[Models] Successfully loaded and categorized %d models.\n", len(discovered))
		return discovered
	}

	return fallbackModels()
}

func fallbackModels() []models.AiModel {
	cacheMu.RLock()
	if len(cachedModels) > 0 {
		defer cacheMu.RUnlock()
		return cachedModels
	}
	cacheMu.RUnlock()

	fallback := []models.AiModel{
		{ID: "gemini-3.7-flash-high", DisplayName: "Gemini 3.7 Flash (High Thinking)", Family: "GEMINI", Provider: "Google", MaxTokens: 1048576, SupportsThinking: true, Recommended: true, IsDefault: true},
		{ID: "gemini-3.7-flash-medium", DisplayName: "Gemini 3.7 Flash (Medium Thinking)", Family: "GEMINI", Provider: "Google", MaxTokens: 1048576, SupportsThinking: true, Recommended: true},
		{ID: "gemini-3.7-flash-low", DisplayName: "Gemini 3.7 Flash (Low Thinking)", Family: "GEMINI", Provider: "Google", MaxTokens: 1048576, SupportsThinking: true, Recommended: true},
		{ID: "claude-sonnet-4-5-thinking", DisplayName: "Claude 3.7 Sonnet (Thinking)", Family: "CLAUDE", Provider: "Anthropic", MaxTokens: 250000, SupportsThinking: true, Recommended: true},
		{ID: "claude-sonnet-4-5", DisplayName: "Claude 3.7 Sonnet", Family: "CLAUDE", Provider: "Anthropic", MaxTokens: 250000, SupportsThinking: false, Recommended: true},
		{ID: "gemini-2.5-pro", DisplayName: "Gemini 2.5 Pro", Family: "GEMINI", Provider: "Google", MaxTokens: 1048576, SupportsThinking: true, Recommended: true},
	}

	cacheMu.Lock()
	cachedModels = fallback
	cacheMu.Unlock()
	return fallback
}
