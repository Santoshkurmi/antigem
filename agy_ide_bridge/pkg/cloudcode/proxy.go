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
	"/v1internal:generateContent":           true,
	"/v1internal:streamGenerateContent":     true,
	"/v1internal:countTokens":               true,
	"/v1internal:retrieveUserQuota":         true,
	"/v1internal:fetchAvailableModels":      true,
	"/v1internal:retrieveUserQuotaSummary":  true,

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
type ProxyServer struct {
	Port          string
	UpstreamHost  string
	EnableLogging bool
	server        *http.Server
	proxy         *httputil.ReverseProxy
	mu            sync.Mutex
}

type contextKey string

const reqLoggerKey contextKey = "cloudcode_req_logger"

// NewProxyServer creates a new CloudCode proxy instance
func NewProxyServer(port, upstreamHost string, enableLogging bool) *ProxyServer {
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
		proxy:         proxy,
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

	if isKnown {
		log.Printf("\033[1;32m[CloudCode Proxy]\033[0m [%s] \033[1;36m--> Incoming:\033[0m %s %s (Proto: %s, Remote: %s)",
			now, r.Method, reqPath, r.Proto, r.RemoteAddr)
	} else {
		log.Printf("\033[1;31m[CloudCode Proxy] ⚠️  [UNEXPECTED ROUTE]\033[0m [%s] %s \033[1;31m%s\033[0m (Remote: %s)",
			now, r.Method, reqPath, r.RemoteAddr)
	}

	// Capture request body for typed proto dumping only when logging is enabled
	var reqLogger *RequestLogger
	if p.EnableLogging {
		var bodyBytes []byte
		if r.Body != nil {
			var err error
			bodyBytes, err = io.ReadAll(r.Body)
			if err == nil {
				r.Body = io.NopCloser(bytes.NewReader(bodyBytes))
			}
		}

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
