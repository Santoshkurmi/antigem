package cloudcode

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/proto/cloudcode"
	"gemini-server/pkg/proto/jetski"
	"gemini-server/pkg/proto/prediction"

	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

// DumpLogDir is the directory where request/response .jsonl dumps are stored
var DumpLogDir = filepath.Join(".", "dump_logs")

func init() {
	_ = os.MkdirAll(DumpLogDir, 0755)
}

// ExtractMethod extracts the method name from either gRPC or Google REST URL paths
func ExtractMethod(reqPath string) string {
	if idx := strings.Index(reqPath, "?"); idx != -1 {
		reqPath = reqPath[:idx]
	}
	if idx := strings.LastIndex(reqPath, ":"); idx != -1 {
		return strings.Trim(reqPath[idx+1:], "/")
	}
	if idx := strings.LastIndex(reqPath, "/"); idx != -1 {
		return reqPath[idx+1:]
	}
	return reqPath
}

// ProtoFactory returns empty proto messages for a given method
type ProtoFactory struct {
	NewRequest  func() proto.Message
	NewResponse func() proto.Message
}

// MethodRegistry maps method names (lowercased) to their typed Request & Response proto constructors
var MethodRegistry = map[string]ProtoFactory{
	// Prediction Service
	"streamgeneratecontent": {
		NewRequest:  func() proto.Message { return &prediction.GenerateContentRequest{} },
		NewResponse: func() proto.Message { return &prediction.GenerateContentResponse{} },
	},
	"generatecontent": {
		NewRequest:  func() proto.Message { return &prediction.GenerateContentRequest{} },
		NewResponse: func() proto.Message { return &prediction.GenerateContentResponse{} },
	},
	"retrieveuserquotasummary": {
		NewRequest:  func() proto.Message { return &prediction.RetrieveUserQuotaSummaryRequest{} },
		NewResponse: func() proto.Message { return &prediction.RetrieveUserQuotaSummaryResponse{} },
	},
	"retrieveuserquota": {
		NewRequest:  func() proto.Message { return &prediction.RetrieveUserQuotaRequest{} },
		NewResponse: func() proto.Message { return &prediction.RetrieveUserQuotaResponse{} },
	},
	"fetchavailablemodels": {
		NewRequest:  func() proto.Message { return &prediction.FetchAvailableModelsRequest{} },
		NewResponse: func() proto.Message { return &prediction.FetchAvailableModelsResponse{} },
	},
	"counttokens": {
		NewRequest:  func() proto.Message { return &prediction.CountTokensRequest{} },
		NewResponse: func() proto.Message { return &prediction.CountTokensResponse{} },
	},

	// CloudCode Service
	"loadcodeassist": {
		NewRequest:  func() proto.Message { return &cloudcode.LoadCodeAssistRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.LoadCodeAssistResponse{} },
	},
	"listexperiments": {
		NewRequest:  func() proto.Message { return &cloudcode.ListExperimentsRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.ListExperimentsResponse{} },
	},
	"generatecode": {
		NewRequest:  func() proto.Message { return &cloudcode.GenerateCodeRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.GenerateCodeResponse{} },
	},
	"completecode": {
		NewRequest:  func() proto.Message { return &cloudcode.CompleteCodeRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.CompleteCodeResponse{} },
	},
	"transformcode": {
		NewRequest:  func() proto.Message { return &cloudcode.TransformCodeRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.TransformCodeResponse{} },
	},
	"searchsnippets": {
		NewRequest:  func() proto.Message { return &cloudcode.SearchSnippetsRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.SearchSnippetsResponse{} },
	},
	"fetchcodecustomizationstate": {
		NewRequest:  func() proto.Message { return &cloudcode.FetchCodeCustomizationStateRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.FetchCodeCustomizationStateResponse{} },
	},
	"generatechat": {
		NewRequest:  func() proto.Message { return &cloudcode.GenerateChatRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.GenerateChatResponse{} },
	},
	"streamgeneratechat": {
		NewRequest:  func() proto.Message { return &cloudcode.GenerateChatRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.GenerateChatResponse{} },
	},
	"internalatomicagenticchat": {
		NewRequest:  func() proto.Message { return &cloudcode.InternalAtomicAgenticChatRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.InternalAtomicAgenticChatResponse{} },
	},
	"migratedatabasecode": {
		NewRequest:  func() proto.Message { return &cloudcode.MigrateDatabaseCodeRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.MigrateDatabaseCodeResponse{} },
	},
	"listcloudaicompanionprojects": {
		NewRequest:  func() proto.Message { return &cloudcode.ListCloudAICompanionProjectsRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.ListCloudAICompanionProjectsResponse{} },
	},
	"listagents": {
		NewRequest:  func() proto.Message { return &cloudcode.ListAgentsRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.ListAgentsResponse{} },
	},
	"listremoterepositories": {
		NewRequest:  func() proto.Message { return &cloudcode.ListRemoteRepositoriesRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.ListRemoteRepositoriesResponse{} },
	},
	"listmodelconfigs": {
		NewRequest:  func() proto.Message { return &cloudcode.ListModelConfigsRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.ListModelConfigsResponse{} },
	},
	"onboarduser": {
		NewRequest:  func() proto.Message { return &cloudcode.OnboardUserRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.Operation{} },
	},
	"onboarduserbackgroundtasks": {
		NewRequest:  func() proto.Message { return &cloudcode.OnboardUserBackgroundTasksRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.Empty{} },
	},
	"recordsmartchoicesfeedback": {
		NewRequest:  func() proto.Message { return &cloudcode.RecordSmartchoicesFeedbackRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.RecordSmartchoicesFeedbackResponse{} },
	},
	"recordcodeassistmetrics": {
		NewRequest:  func() proto.Message { return &cloudcode.RecordCodeAssistMetricsRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.Empty{} },
	},
	"recordclientevent": {
		NewRequest:  func() proto.Message { return &cloudcode.RecordClientEventRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.Empty{} },
	},
	"getcodeassistglobalusersetting": {
		NewRequest:  func() proto.Message { return &cloudcode.GetCodeAssistGlobalUserSettingRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.CodeAssistGlobalUserSettingResponse{} },
	},
	"setcodeassistglobalusersetting": {
		NewRequest:  func() proto.Message { return &cloudcode.SetCodeAssistGlobalUserSettingRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.CodeAssistGlobalUserSettingResponse{} },
	},
	"fetchadmincontrols": {
		NewRequest:  func() proto.Message { return &cloudcode.FetchAdminControlsRequest{} },
		NewResponse: func() proto.Message { return &cloudcode.FetchAdminControlsResponse{} },
	},

	// Jetski Service
	"fetchuserinfo": {
		NewRequest:  func() proto.Message { return &jetski.FetchUserInfoRequest{} },
		NewResponse: func() proto.Message { return &jetski.FetchUserInfoResponse{} },
	},
	"setusersettings": {
		NewRequest:  func() proto.Message { return &jetski.SetUserSettingsRequest{} },
		NewResponse: func() proto.Message { return &jetski.SetUserSettingsResponse{} },
	},
	"writetrajectoryacls": {
		NewRequest:  func() proto.Message { return &jetski.WriteTrajectoryACLsRequest{} },
		NewResponse: func() proto.Message { return &jetski.WriteTrajectoryACLsResponse{} },
	},
	"recordtrajectoryanalytics": {
		NewRequest:  func() proto.Message { return &jetski.RecordTrajectoryAnalyticsRequest{} },
		NewResponse: func() proto.Message { return &jetski.RecordTrajectoryAnalyticsResponse{} },
	},
	"listcascadenuxes": {
		NewRequest:  func() proto.Message { return &jetski.ListCascadeNuxesRequest{} },
		NewResponse: func() proto.Message { return &jetski.ListCascadeNuxesResponse{} },
	},
	"listwebdocsoptions": {
		NewRequest:  func() proto.Message { return &jetski.ListWebDocsOptionsRequest{} },
		NewResponse: func() proto.Message { return &jetski.ListWebDocsOptionsResponse{} },
	},
	"listagentplugins": {
		NewRequest:  func() proto.Message { return &jetski.ListAgentPluginsRequest{} },
		NewResponse: func() proto.Message { return &jetski.ListAgentPluginsResponse{} },
	},
	"listbuildwithgoogleplugins": {
		NewRequest:  func() proto.Message { return &jetski.ListBuildWithGooglePluginsRequest{} },
		NewResponse: func() proto.Message { return &jetski.ListBuildWithGooglePluginsResponse{} },
	},
	"getagentplugin": {
		NewRequest:  func() proto.Message { return &jetski.GetAgentPluginRequest{} },
		NewResponse: func() proto.Message { return &jetski.AgentPlugin{} },
	},
	"tabchat": {
		NewRequest:  func() proto.Message { return &jetski.TabChatRequest{} },
		NewResponse: func() proto.Message { return &jetski.TabChatResponse{} },
	},
	"gethealth": {
		NewRequest:  func() proto.Message { return &jetski.GetHealthRequest{} },
		NewResponse: func() proto.Message { return &jetski.Health{} },
	},
}

// DecompressGzipIfNeeded checks for gzip magic bytes (0x1f 0x8b) and decompresses the slice
func DecompressGzipIfNeeded(data []byte) ([]byte, error) {
	if len(data) >= 2 && data[0] == 0x1f && data[1] == 0x8b {
		gz, err := gzip.NewReader(bytes.NewReader(data))
		if err != nil {
			return data, err
		}
		defer gz.Close()
		return io.ReadAll(gz)
	}
	return data, nil
}

// DecodeProto attempts to unmarshal bytes into target proto message via JSON, gRPC framing, or raw wire protobuf
func DecodeProto(data []byte, target proto.Message) error {
	if len(data) == 0 || target == nil {
		return nil
	}

	// Decompress if gzip
	decompressed, err := DecompressGzipIfNeeded(data)
	if err == nil {
		data = decompressed
	}

	// Check for 5-byte gRPC frame prefix: [compressed_flag: 1 byte][length: 4 bytes big endian]
	if len(data) >= 5 && (data[0] == 0 || data[0] == 1) {
		msgLen := binary.BigEndian.Uint32(data[1:5])
		if int(msgLen) == len(data)-5 {
			frameData := data[5:]
			if data[0] == 1 { // gRPC compressed frame
				if decomp, err := DecompressGzipIfNeeded(frameData); err == nil {
					frameData = decomp
				}
			}
			return proto.Unmarshal(frameData, target)
		}
	}

	trimmed := strings.TrimSpace(string(data))
	if (strings.HasPrefix(trimmed, "{") && strings.HasSuffix(trimmed, "}")) ||
		(strings.HasPrefix(trimmed, "[") && strings.HasSuffix(trimmed, "]")) {
		unmarshaler := protojson.UnmarshalOptions{
			DiscardUnknown: true,
		}
		// If array with single item, unmarshal directly if possible
		if strings.HasPrefix(trimmed, "[") {
			var rawList []json.RawMessage
			if jsonErr := json.Unmarshal([]byte(trimmed), &rawList); jsonErr == nil && len(rawList) > 0 {
				return unmarshaler.Unmarshal(rawList[0], target)
			}
		}
		return unmarshaler.Unmarshal([]byte(trimmed), target)
	}

	// Try binary protobuf unmarshal
	return proto.Unmarshal(data, target)
}

// RequestLogger handles per-call .jsonl file dumping with streaming gzip/JSON/gRPC support
type RequestLogger struct {
	MethodName   string
	LogFilePath  string
	file         *os.File
	mu           sync.Mutex
	chunkIndex   int
	bytesWritten int64
	startTime    time.Time
	pipeReader   *io.PipeReader
	pipeWriter   *io.PipeWriter
	doneCh       chan struct{}
	closeOnce    sync.Once
	isGzip       bool
}

// NewRequestLogger initializes a per-request file in dump_logs/ and starts streaming consumer
func NewRequestLogger(rawPath string) *RequestLogger {
	methodName := ExtractMethod(rawPath)
	timestamp := time.Now().Format("20060102_150405_000")
	safeMethod := strings.ReplaceAll(methodName, ":", "_")
	safeMethod = strings.ReplaceAll(safeMethod, "/", "_")
	if safeMethod == "" {
		safeMethod = "unknown"
	}

	filename := fmt.Sprintf("%s_%s.jsonl", timestamp, safeMethod)
	filePath := filepath.Join(DumpLogDir, filename)

	f, err := os.OpenFile(filePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		log.Printf("\033[31m[Dump Logger] Failed to create log file '%s': %v\033[0m", filePath, err)
		return nil
	}

	pr, pw := io.Pipe()
	l := &RequestLogger{
		MethodName:  strings.ToLower(methodName),
		LogFilePath: filePath,
		file:        f,
		startTime:   time.Now(),
		pipeReader:  pr,
		pipeWriter:  pw,
		doneCh:      make(chan struct{}),
	}

	go l.consumeResponseStream()
	return l
}

// SetResponseInfo informs logger of response status and headers (e.g. Content-Encoding: gzip)
func (l *RequestLogger) SetResponseInfo(statusCode int, headers http.Header) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if strings.Contains(strings.ToLower(headers.Get("Content-Encoding")), "gzip") {
		l.isGzip = true
	}
}

func protoToMap(msg proto.Message) any {
	if msg == nil {
		return nil
	}
	raw := protojson.Format(msg)
	var m any
	if err := json.Unmarshal([]byte(raw), &m); err == nil {
		return m
	}
	return json.RawMessage(raw)
}

// LogRequest writes the initial request header and parsed proto body
func (l *RequestLogger) LogRequest(httpMethod, path string, headers map[string][]string, bodyBytes []byte) {
	if l == nil || l.file == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	entry := map[string]interface{}{
		"timestamp": time.Now().Format(time.RFC3339Nano),
		"type":      "REQUEST",
		"method":    httpMethod,
		"path":      path,
		"headers":   headers,
	}

	if decomp, err := DecompressGzipIfNeeded(bodyBytes); err == nil {
		bodyBytes = decomp
	}

	factory, hasFactory := MethodRegistry[l.MethodName]
	if hasFactory && len(bodyBytes) > 0 {
		reqProto := factory.NewRequest()
		if err := DecodeProto(bodyBytes, reqProto); err == nil {
			entry["parsed_proto"] = protoToMap(reqProto)
			entry["proto_type"] = fmt.Sprintf("%T", reqProto)
		} else {
			entry["proto_decode_error"] = err.Error()
			entry["raw_body"] = string(bodyBytes)
		}
	} else if len(bodyBytes) > 0 {
		entry["raw_body"] = string(bodyBytes)
	}

	data, _ := json.MarshalIndent(entry, "", "  ")
	_, _ = l.file.Write(append(data, []byte("\n\n")...))
}

// WriteRawResponseBytes sends raw response bytes into the streaming parser pipeline
func (l *RequestLogger) WriteRawResponseBytes(b []byte) {
	if l == nil || l.pipeWriter == nil || len(b) == 0 {
		return
	}
	l.mu.Lock()
	l.bytesWritten += int64(len(b))
	l.mu.Unlock()

	_, _ = l.pipeWriter.Write(b)
}

// consumeResponseStream processes incoming response stream, handles gzip decompression, and parses proto chunks
func (l *RequestLogger) consumeResponseStream() {
	defer close(l.doneCh)
	defer l.pipeReader.Close()

	var streamReader io.Reader = l.pipeReader

	// Read stream bytes with a buffer to peek and detect gzip if not declared in header
	buf := make([]byte, 2048)
	n, err := streamReader.Read(buf)
	if n == 0 || err != nil {
		return
	}

	initialBytes := buf[:n]
	isGzipStream := l.isGzip || (len(initialBytes) >= 2 && initialBytes[0] == 0x1f && initialBytes[1] == 0x8b)

	combinedReader := io.MultiReader(bytes.NewReader(initialBytes), l.pipeReader)
	if isGzipStream {
		gz, gzErr := gzip.NewReader(combinedReader)
		if gzErr == nil {
			defer gz.Close()
			streamReader = gz
		} else {
			streamReader = combinedReader
		}
	} else {
		streamReader = combinedReader
	}

	// Now parse stream messages (JSON array, unary JSON, gRPC frames, or raw)
	l.parseMessagesFromReader(streamReader)
}

// parseMessagesFromReader parses proto messages from decompressed stream
func (l *RequestLogger) parseMessagesFromReader(r io.Reader) {
	factory, hasFactory := MethodRegistry[l.MethodName]
	unmarshaler := protojson.UnmarshalOptions{DiscardUnknown: true}

	// Read first non-whitespace byte to determine stream format
	bufReader := bytes.NewBuffer(nil)
	b := make([]byte, 1)
	var firstByte byte
	for {
		n, err := r.Read(b)
		if n > 0 {
			if b[0] != ' ' && b[0] != '\t' && b[0] != '\r' && b[0] != '\n' {
				firstByte = b[0]
				bufReader.WriteByte(firstByte)
				break
			}
		}
		if err != nil {
			break
		}
	}

	combined := io.MultiReader(bufReader, r)

	if firstByte == '[' {
		// Streaming JSON array (e.g. [ { "candidates": ... }, { ... } ])
		dec := json.NewDecoder(combined)
		if _, err := dec.Token(); err == nil {
			for dec.More() {
				var raw json.RawMessage
				if err := dec.Decode(&raw); err == nil {
					l.logParsedResponse(raw, factory, hasFactory, unmarshaler)
				}
			}
		}
		return
	} else if firstByte == '{' {
		// Unary JSON object or JSON lines stream
		dec := json.NewDecoder(combined)
		for dec.More() {
			var raw json.RawMessage
			if err := dec.Decode(&raw); err == nil {
				l.logParsedResponse(raw, factory, hasFactory, unmarshaler)
			}
		}
		return
	}

	// Binary gRPC frames or raw payload
	data, _ := io.ReadAll(combined)
	if len(data) == 0 {
		return
	}

	// Check if multiple gRPC frames: [flag: 1B][length: 4B]
	if len(data) >= 5 && (data[0] == 0 || data[0] == 1) {
		offset := 0
		hasFrames := false
		for offset+5 <= len(data) {
			msgLen := int(binary.BigEndian.Uint32(data[offset+1 : offset+5]))
			if offset+5+msgLen > len(data) {
				break
			}
			frameData := data[offset : offset+5+msgLen]
			l.logParsedResponse(frameData, factory, hasFactory, unmarshaler)
			offset += 5 + msgLen
			hasFrames = true
		}
		if hasFrames && offset == len(data) {
			return
		}
	}

	l.logParsedResponse(data, factory, hasFactory, unmarshaler)
}

func (l *RequestLogger) logParsedResponse(rawBytes []byte, factory ProtoFactory, hasFactory bool, unmarshaler protojson.UnmarshalOptions) {
	l.mu.Lock()
	defer l.mu.Unlock()

	l.chunkIndex++
	entry := map[string]interface{}{
		"timestamp":   time.Now().Format(time.RFC3339Nano),
		"type":        "RESPONSE_CHUNK",
		"chunk_index": l.chunkIndex,
		"chunk_bytes": len(rawBytes),
	}

	if hasFactory {
		respProto := factory.NewResponse()
		if err := DecodeProto(rawBytes, respProto); err == nil {
			entry["parsed_proto"] = protoToMap(respProto)
			entry["proto_type"] = fmt.Sprintf("%T", respProto)
		} else {
			entry["proto_decode_error"] = err.Error()
			entry["raw_chunk"] = string(rawBytes)
		}
	} else {
		entry["raw_chunk"] = string(rawBytes)
	}

	data, _ := json.MarshalIndent(entry, "", "  ")
	if l.file != nil {
		_, _ = l.file.Write(append(data, []byte("\n\n")...))
	}
}

// LogComplete writes completion summary and closes the file
func (l *RequestLogger) LogComplete(statusCode int) {
	if l == nil {
		return
	}

	l.closeOnce.Do(func() {
		if l.pipeWriter != nil {
			_ = l.pipeWriter.Close()
		}
		// Wait for consumer to finish writing chunks
		<-l.doneCh

		l.mu.Lock()
		defer l.mu.Unlock()

		if l.file != nil {
			elapsed := time.Since(l.startTime)
			entry := map[string]interface{}{
				"timestamp":    time.Now().Format(time.RFC3339Nano),
				"type":         "RESPONSE_COMPLETE",
				"status_code":  statusCode,
				"total_chunks": l.chunkIndex,
				"total_bytes":  l.bytesWritten,
				"elapsed_ms":   elapsed.Milliseconds(),
			}

			data, _ := json.MarshalIndent(entry, "", "  ")
			_, _ = l.file.Write(append(data, []byte("\n\n")...))
			_ = l.file.Close()
			l.file = nil
		}
	})
}

// StreamInspectReader wraps response body stream to log chunks in real-time without buffering delay
type StreamInspectReader struct {
	reader io.ReadCloser
	logger *RequestLogger
}

func NewStreamInspectReader(r io.ReadCloser, logger *RequestLogger) *StreamInspectReader {
	return &StreamInspectReader{
		reader: r,
		logger: logger,
	}
}

func (s *StreamInspectReader) Read(p []byte) (int, error) {
	n, err := s.reader.Read(p)
	if n > 0 && s.logger != nil {
		s.logger.WriteRawResponseBytes(p[:n])
	}
	return n, err
}

func (s *StreamInspectReader) Close() error {
	if s.logger != nil {
		s.logger.LogComplete(200)
	}
	return s.reader.Close()
}

