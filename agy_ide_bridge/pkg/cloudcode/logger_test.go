package cloudcode

import (
	"bytes"
	"compress/gzip"
	"net/http"
	"os"
	"strings"
	"testing"
)

func TestProtoDecodingAndLogging(t *testing.T) {
	reqJSON := []byte(`{"model": "gemini-2.5-pro"}`)

	logger := NewRequestLogger("/v1internal:generateContent")
	if logger == nil {
		t.Fatalf("Expected logger to be initialized")
	}

	defer func() {
		_ = os.Remove(logger.LogFilePath)
	}()

	logger.LogRequest("POST", "/v1internal:generateContent", map[string][]string{"Content-Type": {"application/json"}}, reqJSON)

	respJSON := []byte(`{"finishReason": 1}`)
	logger.WriteRawResponseBytes(respJSON)
	logger.LogComplete(200)

	content, err := os.ReadFile(logger.LogFilePath)
	if err != nil {
		t.Fatalf("Failed to read log file: %v", err)
	}

	blocks := strings.Split(strings.TrimSpace(string(content)), "\n\n")
	if len(blocks) != 3 {
		t.Errorf("Expected 3 formatted JSON blocks in dump, got %d: %s", len(blocks), string(content))
	}

	if !strings.Contains(blocks[0], `"type": "REQUEST"`) || !strings.Contains(blocks[0], "prediction.GenerateContentRequest") {
		t.Errorf("Block 1 did not contain expected REQUEST and proto type: %s", blocks[0])
	}
	if !strings.Contains(blocks[1], `"type": "RESPONSE_CHUNK"`) || !strings.Contains(blocks[1], "prediction.GenerateContentResponse") {
		t.Errorf("Block 2 did not contain expected RESPONSE_CHUNK and proto type: %s", blocks[1])
	}
	if !strings.Contains(blocks[2], `"type": "RESPONSE_COMPLETE"`) {
		t.Errorf("Block 3 did not contain expected RESPONSE_COMPLETE: %s", blocks[2])
	}
}

func TestGzipDecompressionLogging(t *testing.T) {
	logger := NewRequestLogger("/v1internal:fetchAvailableModels")
	if logger == nil {
		t.Fatalf("Expected logger to be initialized")
	}
	defer func() {
		_ = os.Remove(logger.LogFilePath)
	}()

	logger.SetResponseInfo(200, http.Header{"Content-Encoding": []string{"gzip"}})

	// Compress a JSON response
	jsonPayload := `{"availableModels": []}`
	var gzBuf bytes.Buffer
	gw := gzip.NewWriter(&gzBuf)
	_, _ = gw.Write([]byte(jsonPayload))
	_ = gw.Close()

	logger.WriteRawResponseBytes(gzBuf.Bytes())
	logger.LogComplete(200)

	content, err := os.ReadFile(logger.LogFilePath)
	if err != nil {
		t.Fatalf("Failed to read log file: %v", err)
	}

	if !strings.Contains(string(content), "prediction.FetchAvailableModelsResponse") || !strings.Contains(string(content), `"parsed_proto"`) {
		t.Errorf("Expected gzip response to be parsed into FetchAvailableModelsResponse, log content: %s", string(content))
	}
}

func TestStreamInspectReader(t *testing.T) {
	logger := NewRequestLogger("/v1internal:streamGenerateContent")
	if logger == nil {
		t.Fatalf("Expected logger to be initialized")
	}
	defer func() {
		_ = os.Remove(logger.LogFilePath)
	}()

	rawReader := ioNopCloser(strings.NewReader(`[{"finishReason": 1}]`))
	streamReader := NewStreamInspectReader(rawReader, logger)

	buf := make([]byte, 10)
	_, _ = streamReader.Read(buf)
	_, _ = streamReader.Read(buf)
	_, _ = streamReader.Read(buf)
	_ = streamReader.Close()

	if _, err := os.Stat(logger.LogFilePath); os.IsNotExist(err) {
		t.Errorf("Expected log file to exist at %s", logger.LogFilePath)
	}
}

type nopCloser struct {
	*strings.Reader
}

func (nopCloser) Close() error { return nil }

func ioNopCloser(r *strings.Reader) nopCloser {
	return nopCloser{r}
}

