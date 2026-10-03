package openrouter

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"

	"gemini-server/pkg/proto/prediction"
	"google.golang.org/protobuf/encoding/protojson"
)

var (
	EnumToOpenRouterModel = make(map[string]string)
	OpenRouterModelToEnum = make(map[string]string)
	enumMapMu             sync.RWMutex
)

// RegisterOpenRouterEnum registers a mapping between a proto placeholder enum and an OpenRouter model ID
func RegisterOpenRouterEnum(enumName, openRouterModelID string) {
	enumMapMu.Lock()
	defer enumMapMu.Unlock()
	EnumToOpenRouterModel[enumName] = openRouterModelID
	OpenRouterModelToEnum[openRouterModelID] = enumName
}

// ResolveOpenRouterModel returns the clean OpenRouter model ID from either key or enum name
func ResolveOpenRouterModel(rawModel string) (string, bool) {
	enumMapMu.RLock()
	defer enumMapMu.RUnlock()
	if strings.HasPrefix(rawModel, "openrouter/") {
		return strings.TrimPrefix(rawModel, "openrouter/"), true
	}
	if orID, exists := EnumToOpenRouterModel[rawModel]; exists {
		return orID, true
	}
	return "", false
}

// CheckIfOpenRouterInBytes checks if raw JSON contains any known OpenRouter model key or enum
func CheckIfOpenRouterInBytes(bodyBytes []byte) bool {
	if strings.Contains(string(bodyBytes), "\"openrouter/") {
		return true
	}
	enumMapMu.RLock()
	defer enumMapMu.RUnlock()
	for enumName := range EnumToOpenRouterModel {
		if strings.Contains(string(bodyBytes), "\""+enumName+"\"") {
			return true
		}
	}
	return false
}

// IncomingAGYRequest is a dynamic representation of Gemini / CloudCode GenerateContentRequest
type IncomingAGYRequest struct {
	Model       string                 `json:"model"`
	Project     string                 `json:"project"`
	RequestID   string                 `json:"requestId"`
	RequestType string                 `json:"requestType"`
	UserAgent   string                 `json:"userAgent"`
	Request     IncomingPredictionBody `json:"request"`
}

type IncomingPredictionBody struct {
	Contents          []IncomingContent `json:"contents"`
	SystemInstruction *IncomingContent  `json:"systemInstruction,omitempty"`
	GenerationConfig  *GenerationConfig `json:"generationConfig,omitempty"`
}

type IncomingContent struct {
	Role  string         `json:"role,omitempty"`
	Parts []IncomingPart `json:"parts"`
}

type IncomingPart struct {
	Text             string `json:"text,omitempty"`
	Thought          bool   `json:"thought,omitempty"`
	ThoughtSignature string `json:"thoughtSignature,omitempty"`
}

type GenerationConfig struct {
	Temperature     *float32 `json:"temperature,omitempty"`
	MaxOutputTokens *int     `json:"maxOutputTokens,omitempty"`
	TopP            *float32 `json:"topP,omitempty"`
	TopK            *int     `json:"topK,omitempty"`
}

// ConvertAGYToOpenRouterRequest converts an incoming AGY JSON request payload into OpenRouter ChatCompletionRequest
func ConvertAGYToOpenRouterRequest(rawJSON []byte) (*ChatCompletionRequest, error) {
	var agyReq IncomingAGYRequest
	if err := json.Unmarshal(rawJSON, &agyReq); err != nil {
		return nil, fmt.Errorf("failed to parse AGY request payload: %w", err)
	}

	rawModel := agyReq.Model
	cleanModel, isOR := ResolveOpenRouterModel(rawModel)
	if !isOR {
		cleanModel = strings.TrimPrefix(rawModel, "openrouter/")
	}

	var messages []ChatMessage

	// 1. Add system instruction if present
	if agyReq.Request.SystemInstruction != nil {
		var sysText strings.Builder
		for _, part := range agyReq.Request.SystemInstruction.Parts {
			sysText.WriteString(part.Text)
		}
		if sysText.Len() > 0 {
			messages = append(messages, ChatMessage{
				Role:    "system",
				Content: sysText.String(),
			})
		}
	}

	// 2. Add chat contents
	for _, content := range agyReq.Request.Contents {
		role := strings.ToLower(content.Role)
		if role == "model" {
			role = "assistant"
		} else if role == "" {
			role = "user"
		}

		var msgText strings.Builder
		for _, part := range content.Parts {
			// Skip old thought signatures when passing history back
			if part.ThoughtSignature != "" {
				continue
			}
			msgText.WriteString(part.Text)
		}

		if msgText.Len() > 0 {
			messages = append(messages, ChatMessage{
				Role:    role,
				Content: msgText.String(),
			})
		}
	}

	if len(messages) == 0 {
		messages = append(messages, ChatMessage{
			Role:    "user",
			Content: "Hello",
		})
	}

	out := &ChatCompletionRequest{
		Model:    cleanModel,
		Messages: messages,
		Stream:   true,
	}

	if agyReq.Request.GenerationConfig != nil {
		out.Temperature = agyReq.Request.GenerationConfig.Temperature
		out.MaxTokens = agyReq.Request.GenerationConfig.MaxOutputTokens
		out.TopP = agyReq.Request.GenerationConfig.TopP
	}

	return out, nil
}

// FormatAGYSSEEvent formats response candidates into standard AGY SSE string: `data: {"response": ...}\r\n\r\n`
func FormatAGYSSEEvent(responseObj map[string]interface{}) string {
	wrapper := map[string]interface{}{
		"response": responseObj,
		"metadata": map[string]interface{}{},
	}
	b, err := json.Marshal(wrapper)
	if err != nil {
		return ""
	}
	return fmt.Sprintf("data: %s\r\n\r\n", string(b))
}

// BuildReasoningSSEChunk creates an AGY SSE event containing live thought text
func BuildReasoningSSEChunk(reasoningText string, modelVersion string) string {
	return FormatAGYSSEEvent(map[string]interface{}{
		"candidates": []map[string]interface{}{
			{
				"content": map[string]interface{}{
					"role": "model",
					"parts": []map[string]interface{}{
						{
							"thought": true,
							"text":    reasoningText,
						},
					},
				},
			},
		},
		"modelVersion": modelVersion,
	})
}

// BuildContentSSEChunk creates an AGY SSE event containing live streaming response text
func BuildContentSSEChunk(contentText string, modelVersion string) string {
	return FormatAGYSSEEvent(map[string]interface{}{
		"candidates": []map[string]interface{}{
			{
				"content": map[string]interface{}{
					"role": "model",
					"parts": []map[string]interface{}{
						{
							"text": contentText,
						},
					},
				},
			},
		},
		"modelVersion": modelVersion,
	})
}

// BuildCompleteSSEChunk creates the final completion event with finishReason and token usage
func BuildCompleteSSEChunk(modelVersion string, promptTokens, completionTokens, totalTokens int) string {
	return FormatAGYSSEEvent(map[string]interface{}{
		"candidates": []map[string]interface{}{
			{
				"content": map[string]interface{}{
					"role": "model",
					"parts": []map[string]interface{}{
						{
							"thoughtSignature": "openrouter_done",
							"text":             "",
						},
					},
				},
				"finishReason": "STOP",
			},
		},
		"usageMetadata": map[string]interface{}{
			"promptTokenCount":     promptTokens,
			"candidatesTokenCount": completionTokens,
			"totalTokenCount":      totalTokens,
		},
		"modelVersion": modelVersion,
	})
}

// MergeOpenRouterModelsIntoJSON injects OpenRouter free models into FetchAvailableModels response using typed protobuf structs
func MergeOpenRouterModelsIntoJSON(rawUpstreamJSON []byte, openRouterModels []ModelInfo) ([]byte, error) {
	var resp prediction.FetchAvailableModelsResponse
	unmarshalOpts := protojson.UnmarshalOptions{
		DiscardUnknown: true,
	}
	if err := unmarshalOpts.Unmarshal(rawUpstreamJSON, &resp); err != nil {
		return rawUpstreamJSON, fmt.Errorf("failed to unmarshal upstream proto json: %w", err)
	}

	if resp.Models == nil {
		resp.Models = make(map[string]*prediction.ModelDetails)
	}

	// 1. Discover all Model enums already used by Google CloudCode in upstream response
	usedEnums := make(map[prediction.Model]bool)
	for _, details := range resp.Models {
		if details != nil && details.Model != prediction.Model_MODEL_UNSPECIFIED {
			usedEnums[details.Model] = true
		}
	}

	// 2. Iterate backwards through defined placeholder enums in proto
	// From MODEL_PLACEHOLDER_M650 down to MODEL_PLACEHOLDER_M0
	var openRouterModelIDs []string
	currentEnumVal := int32(prediction.Model_MODEL_PLACEHOLDER_M650)
	minEnumVal := int32(prediction.Model_MODEL_PLACEHOLDER_M0)

	for _, m := range openRouterModels {
		fullKey := "openrouter/" + m.ID
		openRouterModelIDs = append(openRouterModelIDs, fullKey)

		// Find the next unused placeholder enum
		for currentEnumVal >= minEnumVal {
			modelEnum := prediction.Model(currentEnumVal)
			currentEnumVal--

			if !usedEnums[modelEnum] {
				usedEnums[modelEnum] = true
				enumName := modelEnum.String()
				RegisterOpenRouterEnum(enumName, m.ID)

				maxTokens := int32(m.ContextLength)
				if maxTokens <= 0 {
					maxTokens = 131072
				}
				maxOutputTokens := maxTokens / 2
				if maxOutputTokens > 65536 {
					maxOutputTokens = 65536
				}

				supportsThinking := strings.Contains(strings.ToLower(m.ID), "r1") ||
					strings.Contains(strings.ToLower(m.ID), "reasoning") ||
					strings.Contains(strings.ToLower(m.ID), "thinking") ||
					strings.Contains(strings.ToLower(m.Name), "r1") ||
					strings.Contains(strings.ToLower(m.Name), "thinking")

				tagDesc := "Free Model"
				if strings.Contains(m.ID, ":free") {
					tagDesc = "Free Community Model"
				}

				resp.Models[fullKey] = &prediction.ModelDetails{
					DisplayName:       m.Name,
					Recommended:       true,
					SupportsImages:    strings.Contains(m.Architecture.Modality, "image"),
					SupportsThinking:  supportsThinking,
					ThinkingBudget:    4000,
					MinThinkingBudget: 32,
					MaxTokens:         maxTokens,
					MaxOutputTokens:   maxOutputTokens,
					TagTitle:          "OpenRouter",
					TagDescription:    tagDesc,
					Model:             modelEnum,
					ApiProvider:       prediction.APIProvider_API_PROVIDER_OPENAI_VERTEX,
					ModelProvider:     prediction.ModelProvider_MODEL_PROVIDER_OPENAI,
					SupportedMimeTypes: map[string]bool{
						"text/plain":        true,
						"text/markdown":     true,
						"text/javascript":   true,
						"text/x-typescript": true,
						"text/x-python":     true,
						"application/json":  true,
					},
				}
				break
			}
		}
	}

	// 3. Add OpenRouter sort category
	if len(openRouterModelIDs) > 0 {
		orSort := &prediction.ModelSort{
			DisplayName: "OpenRouter (Free)",
			Groups: []*prediction.ModelGroup{
				{
					ModelIds: openRouterModelIDs,
				},
			},
		}
		resp.AgentModelSorts = append([]*prediction.ModelSort{orSort}, resp.AgentModelSorts...)
	}

	marshalOpts := protojson.MarshalOptions{
		UseProtoNames:   false,
		EmitUnpopulated: false,
	}
	return marshalOpts.Marshal(&resp)
}
