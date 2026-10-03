package openrouter

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	DefaultBaseURL = "https://openrouter.ai/api/v1"
)

// Client interacts with the OpenRouter API
type Client struct {
	APIKey     string
	BaseURL    string
	HTTPClient *http.Client
}

// NewClient creates a new OpenRouter client
func NewClient(apiKey string) *Client {
	return &Client{
		APIKey:  strings.TrimSpace(apiKey),
		BaseURL: DefaultBaseURL,
		HTTPClient: &http.Client{
			Timeout: 0, // Streaming requests need no timeout
		},
	}
}

// IsEnabled returns true if the client has an API key configured
func (c *Client) IsEnabled() bool {
	return c != nil && c.APIKey != ""
}

// FetchFreeModels retrieves all free community models directly from OpenRouter in real-time (no cache)
func (c *Client) FetchFreeModels(ctx context.Context) ([]ModelInfo, error) {
	reqCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, c.BaseURL+"/models", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create models request: %w", err)
	}

	if c.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.APIKey)
	}

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to execute models request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("openrouter models API error (status %d): %s", resp.StatusCode, string(body))
	}

	var modelsResp ModelsResponse
	if err := json.NewDecoder(resp.Body).Decode(&modelsResp); err != nil {
		return nil, fmt.Errorf("failed to decode models response: %w", err)
	}

	var freeModels []ModelInfo
	for _, m := range modelsResp.Data {
		promptStr := fmt.Sprintf("%v", m.Pricing.Prompt)
		complStr := fmt.Sprintf("%v", m.Pricing.Completion)
		isPromptZero := promptStr == "0" || promptStr == "0.0" || promptStr == "<nil>"
		isComplZero := complStr == "0" || complStr == "0.0" || complStr == "<nil>"

		isFree := strings.HasSuffix(m.ID, ":free") || (isPromptZero && isComplZero)
		if isFree {
			freeModels = append(freeModels, m)
		}
	}

	return freeModels, nil
}

// StreamChatCompletion sends a chat completion request to OpenRouter and returns a reader of SSE lines
func (c *Client) StreamChatCompletion(ctx context.Context, chatReq ChatCompletionRequest) (io.ReadCloser, error) {
	chatReq.Stream = true
	reqBytes, err := json.Marshal(chatReq)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal chat request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/chat/completions", bytes.NewReader(reqBytes))
	if err != nil {
		return nil, fmt.Errorf("failed to create chat request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.APIKey)
	req.Header.Set("HTTP-Referer", "https://antigravity.google.com")
	req.Header.Set("X-Title", "antiGem IDE")

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("openrouter request failed: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		return nil, fmt.Errorf("openrouter chat API error (status %d): %s", resp.StatusCode, string(body))
	}

	return resp.Body, nil
}

// ParseSSEStream reads lines from OpenRouter SSE response and sends parsed ChatCompletionChunk objects to a channel
func ParseSSEStream(r io.Reader, chunkCh chan<- ChatCompletionChunk, errCh chan<- error) {
	defer close(chunkCh)
	defer close(errCh)

	scanner := bufio.NewScanner(r)
	// Support large buffers for huge reasoning traces
	buf := make([]byte, 64*1024)
	scanner.Buffer(buf, 10*1024*1024)

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, ":") {
			// Skip heartbeat or comment lines like ": OPENROUTER PROCESSING"
			continue
		}

		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimPrefix(line, "data: ")
			if data == "[DONE]" {
				return
			}

			var chunk ChatCompletionChunk
			if err := json.Unmarshal([]byte(data), &chunk); err != nil {
				// Ignore json decode errors on malformed lines
				continue
			}
			chunkCh <- chunk
		}
	}

	if err := scanner.Err(); err != nil && err != io.EOF {
		errCh <- err
	}
}

// FetchKeyInfo retrieves API key metadata and limits directly from OpenRouter in real-time (no cache)
func (c *Client) FetchKeyInfo(ctx context.Context) (*KeyInfoResponse, error) {
	if !c.IsEnabled() {
		return nil, fmt.Errorf("openrouter client is not enabled")
	}

	reqCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, c.BaseURL+"/key", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create key info request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+c.APIKey)

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to execute key info request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("openrouter key API error (status %d): %s", resp.StatusCode, string(body))
	}

	var keyResp KeyInfoResponse
	if err := json.NewDecoder(resp.Body).Decode(&keyResp); err != nil {
		return nil, fmt.Errorf("failed to decode key info response: %w", err)
	}

	return &keyResp, nil
}

