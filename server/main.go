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
	"strings"
	"syscall"
	"time"

	"gemini-server/pkg/config"
	"gemini-server/pkg/handlers"
	"gemini-server/pkg/hub"
	"gemini-server/pkg/ws"
)

func printUsage() {
	fmt.Println(`antiGem Go IDE Server & AGY Hub Supervisor

Usage:
  go run main.go [flags]

Flags:
  -f, --force, --f       Force start AGY Hub automatically without prompting
  -p, --port <port>      Port for the Go IDE Server (default: 8080)
  --hub-port <port>      Port for the AGY Hub RPC server (default: 8090)
  --no-hub               Skip launching AGY Hub (run IDE server only)
  -d, --dir <path>       Custom workspace directory
  -h, --help             Show help documentation`)
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

	var forceStart bool
	var skipHub bool
	hubPort := "8090"

	// Parse command line arguments
	for i := 1; i < len(os.Args); i++ {
		arg := os.Args[i]
		switch {
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

	// Stylized Banner
	fmt.Println("\033[1;36m============================================================\033[0m")
	fmt.Println("\033[1;32m  ⚡ antiGem Go IDE Server & AGY Hub Supervisor\033[0m")
	fmt.Println("\033[1;36m============================================================\033[0m")
	fmt.Printf("  \033[1m• IDE Server Port:\033[0m  http://0.0.0.0:%s\n", cfg.Port)
	fmt.Printf("  \033[1m• Projects Dir:\033[0m     %s\n", cfg.ProjectsBaseDir)
	fmt.Printf("  \033[1m• Target Hub Port:\033[0m  %s\n", hubPort)
	fmt.Println("\033[1;36m============================================================\033[0m")

	var hubMgr *hub.HubManager

	wsHub := ws.NewHub(cfg)
	h := handlers.NewHandler(cfg, hubMgr)
	h.SetHub(wsHub)

	mux := http.NewServeMux()

	// REST Endpoints
	mux.HandleFunc("/api/health", h.HealthHandler)
	mux.HandleFunc("/api/status", h.StatusHandler)
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
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		mux.ServeHTTP(w, r)
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
			bodyBytes, err := io.ReadAll(io.LimitReader(r.Body, 4096))
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

	if shouldStartHub {
		hubMgr = hub.NewHubManager(hubPort, cfg.WorkspaceDir, cfg.AppDataDir)
		h.HubManager = hubMgr
		wsHub.StatusProv = hubMgr
		wsHub.HubPort = hubPort
		hubMgr.OnLoginURL = h.HandleLoginURL
		hubMgr.OnStatusChange = func(status string, errorMsg string, logs []string) {
			wsHub.BroadcastHubStatus(status, hubPort, errorMsg, logs)
		}
		if err := hubMgr.Start(); err != nil {
			fmt.Printf(" \033[31m[!] Warning starting AGY Hub:\033[0m %v\n", err)
		}
	}

	<-stopChan
	fmt.Println("\n🛑 Shutting down antiGem server gracefully...")

	if hubMgr != nil {
		hubMgr.Stop()
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
	fmt.Println("✅ antiGem Go server stopped.")
}
