package cloudcode

import (
	"bytes"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/openrouter"
	"gemini-server/pkg/proto/prediction"
	"google.golang.org/protobuf/encoding/protojson"
)

// KnownRoutes contains all valid URL paths for v1internal services (CloudCode, PredictionService, JetskiService)
var KnownRoutes = map[string]bool{
	// CloudCode methods
	"/v1internal:generateCode":                   true,
	"/v1internal:completeCode":                   true,
	"/v1internal:transformCode":                  true,
	"/v1internal:searchSnippets":                 true,
	"/v1internal:fetchCodeCustomizationState":    true,
	"/v1internal:generateChat":                   true,
	"/v1internal:streamGenerateChat":             true,
	"/v1internal:internalAtomicAgenticChat":      true,
	"/v1internal:migrateDatabaseCode":            true,
	"/v1internal:listExperiments":                true,
	"/v1internal:loadCodeAssist":                 true,
	"/v1internal:listCloudAICompanionProjects":   true,
	"/v1internal:listAgents":                     true,
	"/v1internal:listRemoteRepositories":         true,
	"/v1internal:listModelConfigs":               true,
	"/v1internal:onboardUser":                    true,
	"/v1internal:onboardUserBackgroundTasks":     true,
	"/v1internal:recordSmartchoicesFeedback":     true,
	"/v1internal:recordCodeAssistMetrics":        true,
	"/v1internal:recordClientEvent":              true,
	"/v1internal:getCodeAssistGlobalUserSetting": true,
	"/v1internal:setCodeAssistGlobalUserSetting": true,
	"/v1internal:fetchAdminControls":             true,

	// PredictionService methods
	"/v1internal:generateContent":          true,
	"/v1internal:streamGenerateContent":    true,
	"/v1internal:countTokens":              true,
	"/v1internal:retrieveUserQuota":        true,
	"/v1internal:fetchAvailableModels":     true,
	"/v1internal:retrieveUserQuotaSummary": true,

	// JetskiService methods
	"/v1internal:listAgentPlugins":               true,
	"/v1internal:listBuildWithGooglePlugins":     true,
	"/v1internal:getAgentPlugin":                 true,
	"/v1internal:listCascadeNuxes":               true,
	"/v1internal:listWebDocsOptions":             true,
	"/v1internal:rewriteUri":                     true,
	"/v1internal:fetchUserInfo":                  true,
	"/v1internal:setUserSettings":                true,
	"/v1internal:tabChat":                        true,
	"/v1internal:checkUrlDenylist":               true,
	"/v1internal:checkUrlAntivirus":              true,
	"/v1internal:getHealth":                      true,
	"/v1internal:recordTrajectoryAnalytics":      true,
	"/v1internal:writeTrajectoryACLs":            true,
	"/v1internal:fetchFromTrawlerCache":          true,
	"/v1internal:registerInteraction":            true,
	"/v1internal:battleModeOverrides":            true,
	"/v1internal:battleModeAutoTrigger":          true,
	"/v1internal:uploadPerformanceProfile":       true,
	"/v1internal:provisionConversationBundleDir": true,
	"/v1internal:getBundleWriteMint":             true,
}

var KnownRoutesLower = func() map[string]bool {
	m := make(map[string]bool)
	for k := range KnownRoutes {
		m[strings.ToLower(k)] = true
	}
	return m
}()

const (
	DefaultUpstreamHost = "https://daily-cloudcode-pa.googleapis.com"
)

// responseTracker wraps http.ResponseWriter to track response status and written byte size
type responseTracker struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int64
	wroteHeader  bool
}

func (w *responseTracker) WriteHeader(statusCode int) {
	if !w.wroteHeader {
		w.statusCode = statusCode
		w.wroteHeader = true
		w.ResponseWriter.WriteHeader(statusCode)
	}
}

func (w *responseTracker) Write(b []byte) (int, error) {
	if !w.wroteHeader {
		w.WriteHeader(http.StatusOK)
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytesWritten += int64(n)
	return n, err
}

// Flush supports streaming if the underlying ResponseWriter is a Flusher
func (w *responseTracker) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// ProxyServer manages the CloudCode reverse proxy service
// ProxyServer manages the CloudCode reverse proxy service
type ProxyServer struct {
	Port          string
	UpstreamHost  string
	EnableLogging bool
	Verbose       bool
	OpenRouter    *openrouter.Client
	server        *http.Server
	proxy         *httputil.ReverseProxy
	transport     *http.Transport
	mu            sync.Mutex
}

type contextKey string

const reqLoggerKey contextKey = "cloudcode_req_logger"

// NewProxyServer creates a new CloudCode proxy instance
func NewProxyServer(port, upstreamHost string, enableLogging, verbose bool, orClient *openrouter.Client) *ProxyServer {
	if port == "" {
		port = "1236"
	}
	if upstreamHost == "" {
		upstreamHost = DefaultUpstreamHost
	}

	targetURL, err := url.Parse(upstreamHost)
	if err != nil {
		log.Fatalf("[CloudCode Proxy] Invalid upstream host '%s': %v", upstreamHost, err)
	}

	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		TLSClientConfig:       &tls.Config{InsecureSkipVerify: false},
		MaxIdleConns:          200,
		MaxIdleConnsPerHost:   100,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		ResponseHeaderTimeout: 0, // No timeout for streaming
	}

	proxy := httputil.NewSingleHostReverseProxy(targetURL)
	proxy.Transport = transport
	// FlushInterval: -1 flushes each write immediately, crucial for streaming gRPC & SSE
	proxy.FlushInterval = -1

	origDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		origDirector(req)
		req.Host = targetURL.Host
		req.URL.Scheme = targetURL.Scheme
		req.URL.Host = targetURL.Host
	}

	proxy.ModifyResponse = func(resp *http.Response) error {
		if enableLogging {
			if reqLogger, ok := resp.Request.Context().Value(reqLoggerKey).(*RequestLogger); ok && reqLogger != nil {
				reqLogger.SetResponseInfo(resp.StatusCode, resp.Header)
				resp.Body = NewStreamInspectReader(resp.Body, reqLogger)
			}
		}
		return nil
	}

	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Printf("\033[1;31m[CloudCode Proxy] ❌ Upstream error for %s %s: %v\033[0m", r.Method, r.URL.Path, err)
		http.Error(w, fmt.Sprintf("CloudCode Proxy Error: %v", err), http.StatusBadGateway)
	}

	p := &ProxyServer{
		Port:          port,
		UpstreamHost:  upstreamHost,
		EnableLogging: enableLogging,
		Verbose:       verbose,
		OpenRouter:    orClient,
		proxy:         proxy,
		transport:     transport,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", p.handleRequest)

	p.server = &http.Server{
		Addr:         "0.0.0.0:" + port,
		Handler:      mux,
		ReadTimeout:  0, // No timeout for indefinite gRPC streaming
		WriteTimeout: 0, // No timeout for indefinite gRPC streaming
		IdleTimeout:  0, // No timeout
	}

	return p
}

func (p *ProxyServer) handleRequest(w http.ResponseWriter, r *http.Request) {
	reqPath := r.URL.Path
	now := time.Now().Format("2006-01-02 15:04:05")
	start := time.Now()

	isKnown := KnownRoutesLower[strings.ToLower(reqPath)]

	if p.Verbose {
		if isKnown {
			log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;36m--> Incoming:\033[0m %s %s (Proto: %s, Remote: %s)",
				now, r.Method, reqPath, r.Proto, r.RemoteAddr)
		} else {
			log.Printf("\033[1;31m[CloudCode Proxy] ⚠️  [UNEXPECTED ROUTE]\033[0m [%s] %s \033[1;31m%s\033[0m (Remote: %s)",
				now, r.Method, reqPath, r.RemoteAddr)
		}
	} else if !isKnown {
		log.Printf("\033[1;31m[CloudCode Proxy] ⚠️  [UNEXPECTED ROUTE]\033[0m [%s] %s \033[1;31m%s\033[0m (Remote: %s)",
			now, r.Method, reqPath, r.RemoteAddr)
	}

	// Capture request body
	var bodyBytes []byte
	if r.Body != nil {
		var err error
		bodyBytes, err = io.ReadAll(r.Body)
		if err == nil {
			r.Body = io.NopCloser(bytes.NewReader(bodyBytes))
		}
	}

	// Route 1: Intercept fetchAvailableModels to inject OpenRouter free models
	if p.OpenRouter != nil && p.OpenRouter.IsEnabled() && strings.EqualFold(reqPath, "/v1internal:fetchAvailableModels") {
		p.handleFetchAvailableModels(w, r, bodyBytes, start, isKnown)
		return
	}

	// Route 2: Intercept retrieveUserQuotaSummary to inject OpenRouter quota info
	if p.OpenRouter != nil && p.OpenRouter.IsEnabled() && strings.EqualFold(reqPath, "/v1internal:retrieveUserQuotaSummary") {
		p.handleRetrieveUserQuotaSummary(w, r, bodyBytes, start, isKnown)
		return
	}

	// Route 3: Intercept chat/content generation for OpenRouter models
	if p.isOpenRouterGenerationRequest(reqPath, bodyBytes) {
		p.handleOpenRouterStream(w, r, bodyBytes, start, isKnown)
		return
	}

	// Route 4: Intercept analytics / trajectory tracking to block sending telemetry to Google servers
	if strings.EqualFold(reqPath, "/v1internal:recordTrajectoryAnalytics") ||
		strings.EqualFold(reqPath, "/v1internal:recordCodeAssistMetrics") ||
		strings.EqualFold(reqPath, "/v1internal:recordClientEvent") ||
		strings.EqualFold(reqPath, "/v1internal:recordSmartchoicesFeedback") {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("{}"))
		if p.Verbose {
			elapsed := time.Since(start)
			completeNow := time.Now().Format("2006-01-02 15:04:05")
			log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;32m<-- Intercepted Analytics (Blocked from Upstream):\033[0m %s %s -> Status: \033[1m200\033[0m (%v elapsed)",
				completeNow, r.Method, reqPath, elapsed)
		}
		return
	}

	// Standard Transparent Pass-Through to Google
	var reqLogger *RequestLogger
	if p.EnableLogging {
		reqLogger = NewRequestLogger(reqPath)
		if reqLogger != nil {
			reqLogger.LogRequest(r.Method, reqPath, r.Header, bodyBytes)
			r = r.WithContext(context.WithValue(r.Context(), reqLoggerKey, reqLogger))
		}
	}

	tracker := &responseTracker{
		ResponseWriter: w,
		statusCode:     http.StatusOK,
	}

	p.proxy.ServeHTTP(tracker, r)

	elapsed := time.Since(start)
	completeNow := time.Now().Format("2006-01-02 15:04:05")

	dumpPath := "none"
	if reqLogger != nil {
		reqLogger.LogComplete(tracker.statusCode)
		dumpPath = reqLogger.LogFilePath
	}

	if p.Verbose || !isKnown || tracker.statusCode >= 400 {
		if isKnown {
			if dumpPath != "none" {
				log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;32m<-- Completed:\033[0m %s %s -> Status: \033[1m%d\033[0m (%v elapsed, %d bytes) [Dump: %s]",
					completeNow, r.Method, reqPath, tracker.statusCode, elapsed, tracker.bytesWritten, dumpPath)
			} else {
				log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;32m<-- Completed:\033[0m %s %s -> Status: \033[1m%d\033[0m (%v elapsed, %d bytes)",
					completeNow, r.Method, reqPath, tracker.statusCode, elapsed, tracker.bytesWritten)
			}
		} else {
			if dumpPath != "none" {
				log.Printf("\033[1;33m[CloudCode Proxy]\033[0m [%s] \033[1;33m<-- Completed (Unexpected):\033[0m %s %s -> Status: %d (%v elapsed, %d bytes) [Dump: %s]",
					completeNow, r.Method, reqPath, tracker.statusCode, elapsed, tracker.bytesWritten, dumpPath)
			} else {
				log.Printf("\033[1;33m[CloudCode Proxy]\033[0m [%s] \033[1;33m<-- Completed (Unexpected):\033[0m %s %s -> Status: %d (%v elapsed, %d bytes)",
					completeNow, r.Method, reqPath, tracker.statusCode, elapsed, tracker.bytesWritten)
			}
		}
	}
}

// isOpenRouterGenerationRequest checks if the request is targeting an openrouter/ model or registered placeholder enum
func (p *ProxyServer) isOpenRouterGenerationRequest(reqPath string, bodyBytes []byte) bool {
	if p.OpenRouter == nil || !p.OpenRouter.IsEnabled() {
		return false
	}
	pathLower := strings.ToLower(reqPath)
	if !strings.Contains(pathLower, "generatecontent") &&
		!strings.Contains(pathLower, "generatechat") &&
		!strings.Contains(pathLower, "streamgenerate") {
		return false
	}
	return openrouter.CheckIfOpenRouterInBytes(bodyBytes)
}

// handleRetrieveUserQuotaSummary fetches quota summary from Google and OpenRouter concurrently and merges them
func (p *ProxyServer) handleRetrieveUserQuotaSummary(w http.ResponseWriter, r *http.Request, bodyBytes []byte, start time.Time, isKnown bool) {
	reqPath := r.URL.Path

	var wg sync.WaitGroup
	var resp *http.Response
	var upErr error
	var respBody []byte
	var keyInfo *openrouter.KeyInfoResponse

	wg.Add(2)

	// 1. Fetch upstream quota summary from Google
	go func() {
		defer wg.Done()
		upstreamURL := p.UpstreamHost + reqPath
		upReq, err := http.NewRequestWithContext(r.Context(), r.Method, upstreamURL, bytes.NewReader(bodyBytes))
		if err != nil {
			upErr = fmt.Errorf("failed to create upstream request: %w", err)
			return
		}
		for k, vv := range r.Header {
			for _, v := range vv {
				upReq.Header.Add(k, v)
			}
		}
		upReq.Host = "daily-cloudcode-pa.googleapis.com"

		resp, upErr = p.transport.RoundTrip(upReq)
		if upErr != nil {
			log.Printf("\033[31m[CloudCode Proxy] Upstream retrieveUserQuotaSummary failed: %v\033[0m", upErr)
			return
		}
		defer resp.Body.Close()

		respBody, upErr = io.ReadAll(resp.Body)
		if upErr != nil {
			return
		}

		if decomp, err := DecompressGzipIfNeeded(respBody); err == nil {
			respBody = decomp
		}
	}()

	// 2. Fetch OpenRouter Key Info concurrently in real-time (no cache)
	go func() {
		defer wg.Done()
		if p.OpenRouter != nil && p.OpenRouter.IsEnabled() {
			var orErr error
			keyInfo, orErr = p.OpenRouter.FetchKeyInfo(r.Context())
			if orErr != nil && p.Verbose {
				log.Printf("\033[33m[CloudCode Proxy] Warning: Failed to fetch OpenRouter key info: %v\033[0m", orErr)
			}
		}
	}()

	wg.Wait()

	if upErr != nil {
		http.Error(w, fmt.Sprintf("Upstream Error: %v", upErr), http.StatusBadGateway)
		return
	}

	// 3. Inject OpenRouter Quota Group using typed protobuf (only if live key info returned)
	mergedBody := respBody
	var quotaResp prediction.RetrieveUserQuotaSummaryResponse
	unmarshalOpts := protojson.UnmarshalOptions{DiscardUnknown: true}
	if err := unmarshalOpts.Unmarshal(respBody, &quotaResp); err == nil {
		if keyInfo != nil {
			var orDesc string
			remainingFraction := 1.0

			if keyInfo.Data.FreeModelDailyRequests != nil {
				f := keyInfo.Data.FreeModelDailyRequests
				if f.Limit > 0 {
					remainingFraction = float64(f.Remaining) / float64(f.Limit)
				}
				orDesc = fmt.Sprintf("Free Tier: %d/%d requests (%d remaining)", f.Used, f.Limit, f.Remaining)
			} else if keyInfo.Data.Limit != nil {
				orDesc = fmt.Sprintf("Usage: $%.4f / $%.2f", keyInfo.Data.Usage, *keyInfo.Data.Limit)
			} else {
				orDesc = fmt.Sprintf("Account usage: $%.4f", keyInfo.Data.Usage)
			}

			orGroup := &prediction.QuotaSummaryGroup{
				DisplayName: "OpenRouter",
				Description: "OpenRouter Quota",
				Buckets: []*prediction.QuotaSummaryBucket{
					{
						BucketId:          "openrouter_daily",
						DisplayName:       "Free Tier Daily Requests",
						Window:            "24h",
						RemainingFraction: float32(remainingFraction),
						Description:       orDesc,
					},
				},
			}

			quotaResp.Groups = append(quotaResp.Groups, orGroup)

			marshalOpts := protojson.MarshalOptions{UseProtoNames: false, EmitUnpopulated: false}
			if marshaled, err := marshalOpts.Marshal(&quotaResp); err == nil {
				mergedBody = marshaled
			}
		}
	}

	// 4. Return merged JSON
	for k, vv := range resp.Header {
		if strings.EqualFold(k, "Content-Length") || strings.EqualFold(k, "Content-Encoding") {
			continue
		}
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(mergedBody)))
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(mergedBody)

	if p.Verbose {
		elapsed := time.Since(start)
		completeNow := time.Now().Format("2006-01-02 15:04:05")
		log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;32m<-- Completed (Quota Summary Merged):\033[0m %s %s -> Status: \033[1m%d\033[0m (%v elapsed)",
			completeNow, r.Method, reqPath, resp.StatusCode, elapsed)
	}
}

// handleFetchAvailableModels fetches models from Google and OpenRouter concurrently and merges them
func (p *ProxyServer) handleFetchAvailableModels(w http.ResponseWriter, r *http.Request, bodyBytes []byte, start time.Time, isKnown bool) {
	reqPath := r.URL.Path

	var wg sync.WaitGroup
	var resp *http.Response
	var upErr error
	var respBody []byte
	var freeModels []openrouter.ModelInfo

	wg.Add(2)

	// 1. Fetch upstream models from Google
	go func() {
		defer wg.Done()
		upstreamURL := p.UpstreamHost + reqPath
		upReq, err := http.NewRequestWithContext(r.Context(), r.Method, upstreamURL, bytes.NewReader(bodyBytes))
		if err != nil {
			upErr = fmt.Errorf("failed to create upstream request: %w", err)
			return
		}
		for k, vv := range r.Header {
			for _, v := range vv {
				upReq.Header.Add(k, v)
			}
		}
		upReq.Host = "daily-cloudcode-pa.googleapis.com"

		resp, upErr = p.transport.RoundTrip(upReq)
		if upErr != nil {
			log.Printf("\033[31m[CloudCode Proxy] Upstream fetchAvailableModels failed: %v\033[0m", upErr)
			return
		}
		defer resp.Body.Close()

		respBody, upErr = io.ReadAll(resp.Body)
		if upErr != nil {
			return
		}

		if decomp, err := DecompressGzipIfNeeded(respBody); err == nil {
			respBody = decomp
		}
	}()

	// 2. Fetch OpenRouter free models concurrently in real-time (no cache)
	go func() {
		defer wg.Done()
		if p.OpenRouter != nil && p.OpenRouter.IsEnabled() {
			var orErr error
			freeModels, orErr = p.OpenRouter.FetchFreeModels(r.Context())
			if orErr != nil && p.Verbose {
				log.Printf("\033[33m[CloudCode Proxy] Warning: Failed to fetch OpenRouter free models: %v\033[0m", orErr)
			}
		}
	}()

	wg.Wait()

	if upErr != nil && len(freeModels) == 0 {
		http.Error(w, fmt.Sprintf("Upstream Error: %v", upErr), http.StatusBadGateway)
		return
	}

	// 3. Merge models
	baseJSON := respBody
	finalStatus := http.StatusOK
	if resp != nil {
		finalStatus = resp.StatusCode
	}
	if (resp == nil || resp.StatusCode != http.StatusOK) && len(freeModels) > 0 {
		baseJSON = []byte(`{"models":{}, "agentModelSorts":[]}`)
		finalStatus = http.StatusOK
	}

	mergedBody := baseJSON
	if len(freeModels) > 0 {
		merged, err := openrouter.MergeOpenRouterModelsIntoJSON(baseJSON, freeModels)
		if err == nil {
			mergedBody = merged
		} else {
			log.Printf("\033[33m[CloudCode Proxy] Warning: Failed to merge OpenRouter models: %v\033[0m", err)
		}
	}

	// 4. Return merged JSON
	if resp != nil {
		for k, vv := range resp.Header {
			if strings.EqualFold(k, "Content-Length") || strings.EqualFold(k, "Content-Encoding") {
				continue
			}
			for _, v := range vv {
				w.Header().Add(k, v)
			}
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(mergedBody)))
	w.WriteHeader(finalStatus)
	_, _ = w.Write(mergedBody)

	if p.Verbose {
		elapsed := time.Since(start)
		completeNow := time.Now().Format("2006-01-02 15:04:05")
		log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;32m<-- Completed (OpenRouter Merged):\033[0m %s %s -> Status: \033[1m%d\033[0m (%v elapsed, %d OpenRouter models injected)",
			completeNow, r.Method, reqPath, finalStatus, elapsed, len(freeModels))
	}
}

// handleOpenRouterStream translates AGY requests to OpenRouter and streams SSE chunks back to client
func (p *ProxyServer) handleOpenRouterStream(w http.ResponseWriter, r *http.Request, bodyBytes []byte, start time.Time, isKnown bool) {
	reqPath := r.URL.Path

	chatReq, err := openrouter.ConvertAGYToOpenRouterRequest(bodyBytes)
	if err != nil {
		log.Printf("\033[31m[CloudCode Proxy] Failed to convert AGY request to OpenRouter: %v\033[0m", err)
		http.Error(w, fmt.Sprintf("OpenRouter Conversion Error: %v", err), http.StatusBadRequest)
		return
	}

	if p.Verbose {
		log.Printf("\033[1;35m[CloudCode Proxy] 🔀 Routing to OpenRouter model: %s (Messages: %d)\033[0m", chatReq.Model, len(chatReq.Messages))
	}

	streamReader, err := p.OpenRouter.StreamChatCompletion(r.Context(), *chatReq)
	if err != nil {
		log.Printf("\033[31m[CloudCode Proxy] OpenRouter streaming error: %v\033[0m", err)
		http.Error(w, fmt.Sprintf("OpenRouter Stream Error: %v", err), http.StatusBadGateway)
		return
	}
	defer streamReader.Close()

	// Set SSE response headers
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	flusher, isFlusher := w.(http.Flusher)

	chunkCh := make(chan openrouter.ChatCompletionChunk, 100)
	errCh := make(chan error, 1)

	go openrouter.ParseSSEStream(streamReader, chunkCh, errCh)

	var totalPromptTokens, totalCompletionTokens, totalTokens int
	var bytesWritten int64

	for chunk := range chunkCh {
		if chunk.Usage != nil {
			totalPromptTokens = chunk.Usage.PromptTokens
			totalCompletionTokens = chunk.Usage.CompletionTokens
			totalTokens = chunk.Usage.TotalTokens
		}

		for _, choice := range chunk.Choices {
			// 1. Streaming reasoning/thought tokens (e.g. DeepSeek R1)
			if choice.Delta.Reasoning != "" {
				sseEvent := openrouter.BuildReasoningSSEChunk(choice.Delta.Reasoning, chatReq.Model)
				n, _ := w.Write([]byte(sseEvent))
				bytesWritten += int64(n)
				if isFlusher {
					flusher.Flush()
				}
			}

			// 2. Streaming content tokens
			if choice.Delta.Content != "" {
				sseEvent := openrouter.BuildContentSSEChunk(choice.Delta.Content, chatReq.Model)
				n, _ := w.Write([]byte(sseEvent))
				bytesWritten += int64(n)
				if isFlusher {
					flusher.Flush()
				}
			}
		}
	}

	// 3. Final completion event
	finalEvent := openrouter.BuildCompleteSSEChunk(chatReq.Model, totalPromptTokens, totalCompletionTokens, totalTokens)
	n, _ := w.Write([]byte(finalEvent))
	bytesWritten += int64(n)
	if isFlusher {
		flusher.Flush()
	}

	elapsed := time.Since(start)
	completeNow := time.Now().Format("2006-01-02 15:04:05")
	log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;32m<-- Completed (OpenRouter Stream):\033[0m %s %s (%s) -> Status: \033[1m200\033[0m (%v elapsed, %d bytes)",
		completeNow, r.Method, reqPath, chatReq.Model, elapsed, bytesWritten)
}

// Start runs the CloudCode proxy listener asynchronously
func (p *ProxyServer) Start() error {
	p.mu.Lock()
	srv := p.server
	p.mu.Unlock()

	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("\033[1;31m[CloudCode Proxy] Server error: %v\033[0m", err)
		}
	}()
	return nil
}

// Shutdown gracefully stops the proxy server
func (p *ProxyServer) Shutdown(ctx context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.server != nil {
		return p.server.Shutdown(ctx)
	}
	return nil
}
