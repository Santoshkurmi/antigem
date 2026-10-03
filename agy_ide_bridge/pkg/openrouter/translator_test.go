package openrouter

import (
	"encoding/json"
	"testing"
)

func TestMergeOpenRouterModelsIntoJSON(t *testing.T) {
	// Sample upstream JSON response with some models already using placeholders
	rawJSON := `{
		"models": {
			"gemini-2.5-pro": {
				"displayName": "Gemini 2.5 Pro",
				"model": "MODEL_PLACEHOLDER_M322"
			},
			"gemini-2.5-flash": {
				"displayName": "Gemini 2.5 Flash",
				"model": "MODEL_PLACEHOLDER_M50"
			}
		},
		"agentModelSorts": []
	}`

	mockORModels := []ModelInfo{
		{
			ID:            "google/gemma-4-31b-it:free",
			Name:          "Google: Gemma 4 31B (free)",
			ContextLength: 131072,
		},
		{
			ID:            "nvidia/nemotron-3.5-lightning:free",
			Name:          "NVIDIA: Nemotron 3.5 Lightning (free)",
			ContextLength: 262144,
		},
	}

	mergedBytes, err := MergeOpenRouterModelsIntoJSON([]byte(rawJSON), mockORModels)
	if err != nil {
		t.Fatalf("MergeOpenRouterModelsIntoJSON failed: %v", err)
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal(mergedBytes, &parsed); err != nil {
		t.Fatalf("Failed to parse merged output as JSON: %v", err)
	}

	models, ok := parsed["models"].(map[string]interface{})
	if !ok {
		t.Fatalf("Missing 'models' map in output")
	}

	// Verify OpenRouter models exist
	gemma, ok := models["openrouter/google/gemma-4-31b-it:free"].(map[string]interface{})
	if !ok {
		t.Fatalf("Gemma model missing from merged output")
	}
	if gemma["model"] != "MODEL_PLACEHOLDER_M650" {
		t.Errorf("Expected Gemma to have MODEL_PLACEHOLDER_M650, got %v", gemma["model"])
	}

	nemotron, ok := models["openrouter/nvidia/nemotron-3.5-lightning:free"].(map[string]interface{})
	if !ok {
		t.Fatalf("Nemotron model missing from merged output")
	}
	if nemotron["model"] != "MODEL_PLACEHOLDER_M649" {
		t.Errorf("Expected Nemotron to have MODEL_PLACEHOLDER_M649, got %v", nemotron["model"])
	}

	// Verify registry works
	resolvedGemma, ok := ResolveOpenRouterModel("MODEL_PLACEHOLDER_M650")
	if !ok || resolvedGemma != "google/gemma-4-31b-it:free" {
		t.Errorf("ResolveOpenRouterModel failed for M650: %v, %v", resolvedGemma, ok)
	}

	resolvedNemotron, ok := ResolveOpenRouterModel("MODEL_PLACEHOLDER_M649")
	if !ok || resolvedNemotron != "nvidia/nemotron-3.5-lightning:free" {
		t.Errorf("ResolveOpenRouterModel failed for M649: %v, %v", resolvedNemotron, ok)
	}
}
