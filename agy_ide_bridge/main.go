package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"runtime/debug"
	"strconv"
	"strings"
	"syscall"
	"time"

	"gemini-server/pkg/cloudcode"
	"gemini-server/pkg/config"
	"gemini-server/pkg/handlers"
	"gemini-server/pkg/hub"
	"gemini-server/pkg/security"
	"gemini-server/pkg/ws"
)

func printUsage() {
	fmt.Println(`antiGem Go IDE Server & AGY Hub Supervisor

Usage:
  go run main.go [flags]

Flags:
  -u, --unpatch             Unpatch AGY binary back to standard header and exit (do not start server)
  --bin <path>              Custom path to AGY binary (for --unpatch or custom setups)
  -t, --token <token>       12-character security token for API & AGY CSRF obfuscation
  -f, --force, --f          Force start AGY Hub automatically without prompting
  -l, --logs                Enable dumping request/response protos to dump_logs/ directory (disabled by default)
  -p, --port <port>         Port for the Go IDE Server (default: 1234)
  --hub-port <port>         Port for the AGY Hub RPC server (default: 1235)
  --cloudcode-port <port>   Port for the CloudCode reverse proxy server (default: 1236)
  --tz-offset <seconds>     Timezone offset in seconds (e.g. 20700 for UTC+05:45)
  --tz <location>           Timezone location name (e.g. Asia/Kathmandu)
  --no-hub                  Skip launching AGY Hub (run IDE server only)
  -d, --dir <path>          Custom workspace directory
  -h, --help                Show help documentation`)
}

// statusRecorder intercepts HTTP status code and response body for logging and error reporting
type statusRecorder struct {
	http.ResponseWriter
	statusCode   int
	responseBody bytes.Buffer
	wroteHeader  bool
}

func (r *statusRecorder) WriteHeader(code int) {
	if !r.wroteHeader {
		r.statusCode = code
		r.wroteHeader = true
		r.ResponseWriter.WriteHeader(code)
	}
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if !r.wroteHeader {
		r.WriteHeader(http.StatusOK)
	}
	if r.responseBody.Len() < 4096 {
		r.responseBody.Write(b)
	}
	return r.ResponseWriter.Write(b)
}

// formatHumanCrash converts raw runtime panics and stacks into human-readable diagnostics
func formatHumanCrash(p interface{}, rawStack []byte) (reason string, location string) {
	reason = fmt.Sprintf("%v", p)
	switch {
	case strings.Contains(reason, "invalid memory address") || strings.Contains(reason, "nil pointer"):
		reason = "Nil Pointer / Missing Object (tried to access data that was not initialized)"
	case strings.Contains(reason, "index out of range"):
		reason = "Array/Slice Index Out Of Range (tried to read beyond array length)"
	case strings.Contains(reason, "slice bounds out of range"):
		reason = "Slice Bounds Out Of Range"
	case strings.Contains(reason, "concurrent map"):
		reason = "Concurrent Map Access Collision"
	}

	lines := strings.Split(string(rawStack), "\n")
	for i := 0; i < len(lines); i++ {
		line := strings.TrimSpace(lines[i])
		if (strings.Contains(line, "gemini-server/pkg/") || strings.Contains(line, "gemini-server/main.go")) && !strings.Contains(line, "recoveryHandler") {
			if i+1 < len(lines) && strings.Contains(lines[i+1], ".go:") {
				fileLoc := strings.TrimSpace(lines[i+1])
				if idx := strings.Index(fileLoc, " +0x"); idx != -1 {
					fileLoc = fileLoc[:idx]
				}
				funcName := line
				if idx := strings.LastIndex(funcName, "/"); idx != -1 {
					funcName = funcName[idx+1:]
				}
				return reason, fmt.Sprintf("%s (in %s)", fileLoc, funcName)
			}
			return reason, line
		}
	}
	return reason, "Unknown internal code location"
}

func main() {
	cfg := config.LoadConfig()

	var unpatchOnly bool
	var proxyOnly bool
	var enableLogs bool
	var customAgyBin string
	var forceStart bool
	var skipHub bool
	var cliToken string
	var tzOffsetSec int
	var tzName string
	hubPort := "1235"

	// Parse command line arguments
	for i := 1; i < len(os.Args); i++ {
		arg := os.Args[i]
		switch {
		case arg == "-u" || arg == "--unpatch":
			unpatchOnly = true
		case arg == "--proxy" || arg == "--proxy-only":
			proxyOnly = true
		case arg == "-l" || arg == "--logs" || arg == "--dump-logs" || arg == "--log":
			enableLogs = true
		case strings.HasPrefix(arg, "--bin="):
			customAgyBin = strings.TrimPrefix(arg, "--bin=")
		case arg == "--bin" || arg == "--agy-bin":
			if i+1 < len(os.Args) {
				customAgyBin = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--token="):
			cliToken = strings.TrimPrefix(arg, "--token=")
		case arg == "-t" || arg == "--token":
			if i+1 < len(os.Args) {
				cliToken = os.Args[i+1]
				i++
			}
		case arg == "-f" || arg == "--f" || arg == "--force" || arg == "-force":
			forceStart = true
		case arg == "--no-hub" || arg == "-n" || arg == "--skip-hub":
			skipHub = true
		case strings.HasPrefix(arg, "--port="):
			cfg.Port = strings.TrimPrefix(arg, "--port=")
		case arg == "-p" || arg == "--port":
			if i+1 < len(os.Args) {
				cfg.Port = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--hub-port="):
			hubPort = strings.TrimPrefix(arg, "--hub-port=")
		case arg == "--hub-port":
			if i+1 < len(os.Args) {
				hubPort = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--cloudcode-port="):
			cfg.CloudCodePort = strings.TrimPrefix(arg, "--cloudcode-port=")
		case arg == "--cloudcode-port" || arg == "-cp" || arg == "--cloud-code-port":
			if i+1 < len(os.Args) {
				cfg.CloudCodePort = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--tz-offset="):
			if val, err := strconv.Atoi(strings.TrimPrefix(arg, "--tz-offset=")); err == nil {
				tzOffsetSec = val
			}
		case arg == "--tz-offset":
			if i+1 < len(os.Args) {
				if val, err := strconv.Atoi(os.Args[i+1]); err == nil {
					tzOffsetSec = val
				}
				i++
			}
		case strings.HasPrefix(arg, "--tz="):
			tzName = strings.TrimPrefix(arg, "--tz=")
		case arg == "--tz":
			if i+1 < len(os.Args) {
				tzName = os.Args[i+1]
				i++
			}
		case strings.HasPrefix(arg, "--dir="):
			cfg.WorkspaceDir = strings.TrimPrefix(arg, "--dir=")
		case arg == "-d" || arg == "--dir" || arg == "--workspace":
			if i+1 < len(os.Args) {
				cfg.WorkspaceDir = os.Args[i+1]
				i++
			}
		case arg == "-h" || arg == "--help":
			printUsage()
			os.Exit(0)
		}
	}

	if unpatchOnly {
		resolvedBin, err := hub.ResolveAgyBinaryInteractive(customAgyBin, true)
		if err != nil {
			fmt.Printf("  \033[1;31m✗ Unpatch Failed:\033[0m %v\n", err)
			os.Exit(1)
		}
		patchTarget := hub.ResolvePatchTarget(resolvedBin)
		fmt.Println("\033[1;36m============================================================\033[0m")
		fmt.Println("\033[1;33m  ⚡ antiGem AGY Binary Header Unpatcher\033[0m")
		fmt.Println("\033[1;36m============================================================\033[0m")
		fmt.Printf("  \033[1m• Target Binary:\033[0m  %s\n", patchTarget)

		restoredHeader, elapsed, err := security.UnpatchAgyHeader(patchTarget)
		if err != nil {
			fmt.Printf("  \033[1;31m✗ Unpatch Failed:\033[0m %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("  \033[1;32m✓ Unpatch Succeeded:\033[0m Header restored to standard '%s' in %v\n", restoredHeader, elapsed)
		fmt.Println("\033[1;36m============================================================\033[0m")
		os.Exit(0)
	}

	if proxyOnly {
		fmt.Println("\033[1;36m============================================================\033[0m")
		fmt.Println("\033[1;32m  ⚡ antiGem CloudCode Reverse Proxy Standalone Server\033[0m")
		fmt.Println("\033[1;36m============================================================\033[0m")
		fmt.Printf("  \033[1m• Listening Address:\033[0m  http://0.0.0.0:%s\n", cfg.CloudCodePort)
		fmt.Printf("  \033[1m• Target Upstream:\033[0m    %s\n", cfg.CloudCodeUpstreamHost)
		fmt.Printf("  \033[1m• Local Time:\033[0m         %s\n", time.Now().Format("2006-01-02 15:04:05 MST"))
		fmt.Println("\033[1;36m============================================================\033[0m")
		fmt.Printf(" \033[32m🚀 CloudCode Proxy running at http://0.0.0.0:%s (Forwarding to %s, Logging: %v)\033[0m\n\n", cfg.CloudCodePort, cfg.CloudCodeUpstreamHost, enableLogs)

		ccProxy := cloudcode.NewProxyServer(cfg.CloudCodePort, cfg.CloudCodeUpstreamHost, enableLogs)
		if err := ccProxy.Start(); err != nil {
			log.Fatalf("Fatal: failed to start CloudCode proxy: %v", err)
		}

		stopChan := make(chan os.Signal, 1)
		signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)
		<-stopChan

		fmt.Println("\n🛑 Shutting down CloudCode proxy gracefully...")
		ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
		defer cancel()
		_ = ccProxy.Shutdown(ctx)
		fmt.Println("✅ CloudCode proxy stopped.")
		os.Exit(0)
	}

	// Configure local timezone if offset or name is specified (or in environment)
	if tzOffsetSec == 0 {
		if envOff := os.Getenv("TZ_OFFSET"); envOff != "" {
			if val, err := strconv.Atoi(envOff); err == nil {
				tzOffsetSec = val
			}
		}
	}
	if tzOffsetSec != 0 {
		time.Local = time.FixedZone("Local", tzOffsetSec)
	} else if tzName != "" {
		if loc, err := time.LoadLocation(tzName); err == nil {
			time.Local = loc
		}
	}

	secToken := security.ResolveToken(cliToken)
	framedHeader := security.BuildFramedHeader(secToken)

	resolvedAgyBin, err := hub.ResolveAgyBinaryInteractive(customAgyBin, forceStart || skipHub)
	if customAgyBin != "" && err != nil {
		fmt.Printf(" \033[1;31m✗ Fatal Error:\033[0m %v\n", err)
		os.Exit(1)
	}
	if err != nil && !skipHub {
		fmt.Printf(" \033[31m[!] AGY Discovery Warning:\033[0m %v\n", err)
	}

	// Stylized Banner
	fmt.Println("\033[1;36m============================================================\033[0m")
	fmt.Println("\033[1;32m  ⚡ antiGem Go IDE Server & AGY Hub Supervisor\033[0m")
	fmt.Println("\033[1;36m============================================================\033[0m")
	fmt.Printf("  \033[1m• IDE Server Port:\033[0m  http://0.0.0.0:%s\n", cfg.Port)
	fmt.Printf("  \033[1m• Projects Dir:\033[0m     %s\n", cfg.ProjectsBaseDir)
	fmt.Printf("  \033[1m• Target Hub Port:\033[0m  %s\n", hubPort)
	fmt.Printf("  \033[1m• CloudCode Proxy:\033[0m  http://0.0.0.0:%s -> %s\n", cfg.CloudCodePort, cfg.CloudCodeUpstreamHost)
	if resolvedAgyBin != "" {
		fmt.Printf("  \033[1m• AGY Binary:\033[0m       %s\n", resolvedAgyBin)
	}
	fmt.Printf("  \033[1;33m• Security Token:\033[0m   %s\n", secToken)
	fmt.Printf("  \033[1;36m• Framed Header:\033[0m    %s\n", framedHeader)
	fmt.Printf("  \033[1m• Local Time:\033[0m       %s\n", time.Now().Format("2006-01-02 15:04:05 MST"))
	fmt.Println("\033[1;36m============================================================\033[0m")

	var hubMgr *hub.HubManager

	wsHub := ws.NewHub(cfg)
	h := handlers.NewHandler(cfg, hubMgr)
	h.SetHub(wsHub)

	mux := http.NewServeMux()

	// REST Endpoints
	mux.HandleFunc("/api/health", h.HealthHandler)
	mux.HandleFunc("/api/status", h.StatusHandler)
	mux.HandleFunc("/api/shutdown", h.ShutdownHandler)
	mux.HandleFunc("/api/auth/login-url", h.GetLoginURLHandler)
	mux.HandleFunc("/api/auth/start-login", h.StartLoginHandler)
	mux.HandleFunc("/api/models", h.ModelsHandler)
	mux.HandleFunc("/api/models/refresh", h.ModelsHandler)
	mux.HandleFunc("/api/quotas", h.QuotasHandler)
	mux.HandleFunc("/api/quotas/refresh", h.QuotasHandler)
	mux.HandleFunc("/api/instances", h.InstancesHandler)
	mux.HandleFunc("/api/abort", h.AbortHandler)
	mux.HandleFunc("/api/conversations", h.ConversationsHandler)
	mux.HandleFunc("/api/system-prompt", h.SystemPromptHandler)
	mux.HandleFunc("/api/projects", h.ProjectsHandler)
	mux.HandleFunc("/api/projects/add", h.ProjectsAddHandler)
	mux.HandleFunc("/api/projects/remove", h.ProjectsRemoveHandler)
	mux.HandleFunc("/api/projects/create", h.CreateProjectHandler)
	mux.HandleFunc("/api/fs/browse", h.FsBrowseHandler)
	mux.HandleFunc("/api/fs/mkdir", h.FsMkdirHandler)
	mux.HandleFunc("/api/tree", h.FileTreeHandler)
	mux.HandleFunc("/api/file/read", h.FileReadHandler)
	mux.HandleFunc("/api/file/save", h.FileSaveHandler)
	mux.HandleFunc("/api/file/patch", h.FilePatchHandler)
	mux.HandleFunc("/api/file/create", h.FileCreateHandler)
	mux.HandleFunc("/api/file/delete", h.FileDeleteHandler)
	mux.HandleFunc("/api/file/rename", h.FileRenameHandler)
	mux.HandleFunc("/api/file/copy", h.FileCopyHandler)
	mux.HandleFunc("/api/search", h.FileSearchHandler)
	mux.HandleFunc("/api/upload", h.UploadHandler)
	mux.HandleFunc("/api/mcp/config", h.McpConfigHandler)

	// Git Source Control Endpoints
	mux.HandleFunc("/api/git/status", h.GitStatusHandler)
	mux.HandleFunc("/api/git/init", h.GitInitHandler)
	mux.HandleFunc("/api/git/config", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			h.GitSetConfigHandler(w, r)
		} else {
			h.GitGetConfigHandler(w, r)
		}
	})
	mux.HandleFunc("/api/git/branches", h.GitBranchesHandler)
	mux.HandleFunc("/api/git/checkout", h.GitCheckoutHandler)
	mux.HandleFunc("/api/git/branch/create", h.GitCheckoutHandler)
	mux.HandleFunc("/api/git/stage", h.GitStageHandler)
	mux.HandleFunc("/api/git/unstage", h.GitUnstageHandler)
	mux.HandleFunc("/api/git/discard", h.GitDiscardHandler)
	mux.HandleFunc("/api/git/commit", h.GitCommitHandler)
	mux.HandleFunc("/api/git/push", h.GitPushHandler)
	mux.HandleFunc("/api/git/pull", h.GitPullHandler)
	mux.HandleFunc("/api/git/stash", h.GitStashHandler)
	mux.HandleFunc("/api/git/stash/pop", h.GitStashPopHandler)
	mux.HandleFunc("/api/git/diff", h.GitDiffHandler)
	mux.HandleFunc("/api/git/log", h.GitLogHandler)
	mux.HandleFunc("/api/git/commit/details", h.GitCommitDetailsHandler)
	mux.HandleFunc("/api/git/commit/diff", h.GitCommitFileDiffHandler)
	mux.HandleFunc("/api/git/commit/content", h.GitCommitFileContentHandler)

	// Sub-resource endpoints
	mux.HandleFunc("/api/instances/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/terminate") {
			h.TerminateInstanceHandler(w, r)
		} else {
			h.InstancesHandler(w, r)
		}
	})

	mux.HandleFunc("/api/conversations/", func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if strings.HasSuffix(path, "/system-prompt") {
			h.SystemPromptHandler(w, r)
		} else if strings.HasSuffix(path, "/title") {
			h.UpdateConversationTitleHandler(w, r)
		} else if strings.HasSuffix(path, "/warm") {
			h.PrewarmConversationHandler(w, r)
		} else {
			switch r.Method {
			case http.MethodGet:
				h.ConversationMessagesHandler(w, r)
			case http.MethodPatch, http.MethodPost:
				h.UpdateConversationTitleHandler(w, r)
			case http.MethodDelete:
				h.DeleteConversationHandler(w, r)
			default:
				http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			}
		}
	})

	// Static File Serving
	mux.Handle("/artifacts/", h.ArtifactsFileServer())
	mux.Handle("/attachments/", h.AttachmentsFileServer())

	// WebSocket Endpoint
	mux.HandleFunc("/ws", wsHub.ServeWS)

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.ToLower(r.Header.Get("Upgrade")) == "websocket" {
			wsHub.ServeWS(w, r)
			return
		}
		if r.URL.Path == "/" {
			w.Header().Set("Content-Type", "text/plain")
			_, _ = w.Write([]byte(fmt.Sprintf("antiGem IDE Server running on :%s\n", cfg.Port)))
			return
		}
		http.NotFound(w, r)
	})

	corsHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, "+framedHeader)
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		security.AuthMiddleware(secToken, mux).ServeHTTP(w, r)
	})

	// Global Panic Recovery & Error Logging Middleware:
	// 1. Prevents any API handler panic or crash from bringing down the Go server
	// 2. Formats and prints truncated request payload, query params, and error details to stdout/log stream
	recoveryHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Bypass recorder for WebSocket upgrade requests so Gorilla websocket can hijack directly
		if strings.ToLower(r.Header.Get("Upgrade")) == "websocket" || r.URL.Path == "/ws" {
			defer func() {
				if p := recover(); p != nil {
					log.Printf("\033[1;31m[WS CRASH / PANIC RECOVERED] %s: %v\033[0m\n", r.URL.Path, p)
					debug.PrintStack()
				}
			}()
			corsHandler.ServeHTTP(w, r)
			return
		}

		var reqPayload string
		if r.Body != nil && !strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/form-data") {
			bodyBytes, err := io.ReadAll(r.Body)
			if err == nil && len(bodyBytes) > 0 {
				r.Body = io.NopCloser(bytes.NewReader(bodyBytes))
				trimmed := strings.TrimSpace(string(bodyBytes))
				if len(trimmed) > 300 {
					reqPayload = trimmed[:300] + "... (truncated)"
				} else {
					reqPayload = trimmed
				}
			}
		}

		rec := &statusRecorder{
			ResponseWriter: w,
			statusCode:     http.StatusOK,
		}

		defer func() {
			if p := recover(); p != nil {
				rawStack := debug.Stack()
				reason, codeLoc := formatHumanCrash(p, rawStack)

				log.Printf("\033[1;31m═══════════════════════════════════════════════════════════════\033[0m\n")
				log.Printf("\033[1;31m[API CRASH RECOVERED SAFELY] %s %s\033[0m\n", r.Method, r.URL.Path)
				if r.URL.RawQuery != "" {
					log.Printf("  \033[1;33m• Query:\033[0m    %s\n", r.URL.RawQuery)
				}
				if reqPayload != "" {
					log.Printf("  \033[1;33m• Payload:\033[0m  %s\n", reqPayload)
				}
				log.Printf("  \033[1;31m• Reason:\033[0m   %s\n", reason)
				log.Printf("  \033[1;36m• Code Line:\033[0m %s\n", codeLoc)
				log.Printf("\033[1;31m═══════════════════════════════════════════════════════════════\033[0m\n")

				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusInternalServerError)
				_ = json.NewEncoder(w).Encode(map[string]interface{}{
					"error":    fmt.Sprintf("Internal Server Error: %s", reason),
					"location": codeLoc,
					"success":  false,
				})
			}
		}()

		corsHandler.ServeHTTP(rec, r)

		// Log API errors (status >= 400 or response payload containing "error" / "success":false)
		respStr := rec.responseBody.String()
		isErrorResp := rec.statusCode >= 400 || (strings.Contains(respStr, `"error"`) && strings.Contains(respStr, `"success":false`))
		if isErrorResp {
			log.Printf("\033[1;33m[API ERROR %d] %s %s\033[0m\n", rec.statusCode, r.Method, r.URL.Path)
			if r.URL.RawQuery != "" {
				log.Printf("  \033[33m• Query:\033[0m   %s\n", r.URL.RawQuery)
			}
			if reqPayload != "" {
				log.Printf("  \033[33m• Payload:\033[0m %s\n", reqPayload)
			}
			if len(respStr) > 0 {
				respSnippet := strings.TrimSpace(respStr)
				if len(respSnippet) > 300 {
					respSnippet = respSnippet[:300] + "... (truncated)"
				}
				log.Printf("  \033[31m• Error Response:\033[0m %s\n", respSnippet)
			}
		}
	})

	server := &http.Server{
		Addr:         "0.0.0.0:" + cfg.Port,
		Handler:      recoveryHandler,
		ReadTimeout:  60 * time.Second,
		WriteTimeout: 60 * time.Second,
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	h.OnShutdown = func() {
		log.Println("🛑 Received /api/shutdown request. Initiating graceful shutdown...")
		select {
		case stopChan <- syscall.SIGTERM:
		default:
		}
	}

	go func() {
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Server error: %v", err)
		}
	}()

	fmt.Printf(" \033[32m🚀 antiGem IDE Server running at http://0.0.0.0:%s\033[0m\n\n", cfg.Port)

	// Determine if AGY Hub should be started
	shouldStartHub := false
	if !skipHub {
		if forceStart {
			fmt.Println(" \033[33m⚡ Force flag (-f) detected: auto-starting AGY Hub...\033[0m")
			shouldStartHub = true
		} else {
			// Interactive user prompt
			fmt.Print(" \033[1;33m? Do you want to start AGY Hub server (port " + hubPort + ")? [Y/n]: \033[0m")
			reader := bufio.NewReader(os.Stdin)
			input, err := reader.ReadString('\n')
			if err == nil {
				trimmed := strings.ToLower(strings.TrimSpace(input))
				if trimmed == "" || trimmed == "y" || trimmed == "yes" {
					shouldStartHub = true
				} else {
					fmt.Println(" \033[90mℹ Skipping AGY Hub. Running IDE Server only.\033[0m")
				}
			} else {
				// Non-interactive fallback (e.g. piped or redirected stdin) -> default to starting hub
				shouldStartHub = true
			}
		}
	}

	// Start CloudCode reverse proxy service on port 1236
	ccProxy := cloudcode.NewProxyServer(cfg.CloudCodePort, cfg.CloudCodeUpstreamHost, enableLogs)
	if err := ccProxy.Start(); err != nil {
		log.Printf(" \033[31m[!] Warning starting CloudCode Proxy:\033[0m %v\n", err)
	}

	// Always initialize HubManager so background monitoring and status updates work continuously
	hubMgr = hub.NewHubManager(hubPort, cfg.WorkspaceDir, cfg.AppDataDir, secToken, resolvedAgyBin)
	hubMgr.CloudCodePort = cfg.CloudCodePort
	h.HubManager = hubMgr
	wsHub.StatusProv = hubMgr
	wsHub.HubPort = hubPort
	hubMgr.OnLoginURL = h.HandleLoginURL
	hubMgr.OnStatusChange = func(status string, csrfToken string, errorMsg string, logs []string) {
		wsHub.BroadcastHubStatus(status, hubPort, csrfToken, errorMsg, logs)
	}
	hubMgr.StartContinuousMonitor()

	if shouldStartHub {
		if err := hubMgr.Start(); err != nil {
			fmt.Printf(" \033[31m[!] Warning starting AGY Hub:\033[0m %v\n", err)
		}
	}

	<-stopChan
	fmt.Println("\n🛑 Shutting down antiGem server gracefully...")

	// 1. Close all active WebSocket client connections immediately
	wsHub.Close()

	// 2. Stop AGY Hub process group and continuous background monitor
	if hubMgr != nil {
		hubMgr.Stop()
	}

	// 3. Stop CloudCode Proxy
	if ccProxy != nil {
		ctxCC, cancelCC := context.WithTimeout(context.Background(), 1*time.Second)
		_ = ccProxy.Shutdown(ctxCC)
		cancelCC()
	}

	// 4. Close the HTTP listener without hanging on lingering connections
	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
	_ = server.Close()

	fmt.Println("✅ antiGem Go server stopped.")
	os.Exit(0)
}
