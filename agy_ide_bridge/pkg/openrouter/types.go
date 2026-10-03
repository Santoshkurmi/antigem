package openrouter

// ModelInfo represents a model returned by OpenRouter /api/v1/models
type ModelInfo struct {
	ID            string       `json:"id"`
	Name          string       `json:"name"`
	Description   string       `json:"description,omitempty"`
	ContextLength int          `json:"context_length"`
	Pricing       ModelPricing `json:"pricing"`
	Architecture  struct {
		Modality     string      `json:"modality,omitempty"`
		Tokenizer    string      `json:"tokenizer,omitempty"`
		InstructType interface{} `json:"instruct_type,omitempty"`
	} `json:"architecture,omitempty"`
}

type ModelPricing struct {
	Prompt     interface{} `json:"prompt,omitempty"`
	Completion interface{} `json:"completion,omitempty"`
	Request    interface{} `json:"request,omitempty"`
	Image      interface{} `json:"image,omitempty"`
}

type ModelsResponse struct {
	Data []ModelInfo `json:"data"`
}

// ChatMessage represents an OpenAI-compatible message
type ChatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// ChatCompletionRequest is the payload sent to OpenRouter /api/v1/chat/completions
type ChatCompletionRequest struct {
	Model       string        `json:"model"`
	Messages    []ChatMessage `json:"messages"`
	Stream      bool          `json:"stream"`
	Temperature *float32      `json:"temperature,omitempty"`
	MaxTokens   *int          `json:"max_tokens,omitempty"`
	TopP        *float32      `json:"top_p,omitempty"`
}

// ChatCompletionChunk represents an SSE delta chunk from OpenRouter
type ChatCompletionChunk struct {
	ID      string        `json:"id"`
	Model   string        `json:"model"`
	Choices []ChunkChoice `json:"choices"`
	Usage   *ChunkUsage   `json:"usage,omitempty"`
	Error   *ChunkError   `json:"error,omitempty"`
}

type ChunkChoice struct {
	Index        int        `json:"index"`
	Delta        ChunkDelta `json:"delta"`
	FinishReason *string    `json:"finish_reason"`
}

type ChunkDelta struct {
	Role             string             `json:"role,omitempty"`
	Content          string             `json:"content,omitempty"`
	Reasoning        string             `json:"reasoning,omitempty"`
	ReasoningDetails []ReasoningDetails `json:"reasoning_details,omitempty"`
}

type ReasoningDetails struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

type ChunkUsage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

type ChunkError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// KeyInfoResponse represents response from OpenRouter GET /api/v1/key
type KeyInfoResponse struct {
	Data KeyInfoData `json:"data"`
}

type FreeModelDailyRequests struct {
	Used      int `json:"used"`
	Limit     int `json:"limit"`
	Remaining int `json:"remaining"`
}

type KeyInfoData struct {
	Label                  string                  `json:"label,omitempty"`
	Usage                  float64                 `json:"usage,omitempty"`
	UsageDaily             float64                 `json:"usage_daily,omitempty"`
	UsageWeekly            float64                 `json:"usage_weekly,omitempty"`
	UsageMonthly           float64                 `json:"usage_monthly,omitempty"`
	Limit                  *float64                `json:"limit,omitempty"`
	IsFreeTier             bool                    `json:"is_free_tier,omitempty"`
	ExpiresAt              string                  `json:"expires_at,omitempty"`
	FreeModelDailyRequests *FreeModelDailyRequests `json:"free_model_daily_requests,omitempty"`
	RateLimit              RateLimitInfo           `json:"rate_limit,omitempty"`
}

type RateLimitInfo struct {
	Requests int    `json:"requests,omitempty"`
	Interval string `json:"interval,omitempty"`
}
