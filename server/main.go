package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"gemini-server/pkg/config"
	"gemini-server/pkg/handlers"
	"gemini-server/pkg/models_discovery"
	"gemini-server/pkg/quota"
	"gemini-server/pkg/session"
	"gemini-server/pkg/ws"
)

func main() {
	cfg := config.LoadConfig()

	var hub *ws.Hub

	onResultHook := func() {
		time.Sleep(600 * time.Millisecond)
		updatedQuotas := quota.FetchQuotaSummary(cfg.TokenFile, true)
		if hub != nil && updatedQuotas != nil {
			hub.Broadcast(map[string]interface{}{
				"type": "quota_update",
				"data": updatedQuotas,
			})
		}
	}

	pool := session.NewSessionPoolManager(cfg.WorkspaceDir, 5, onResultHook)
	h := handlers.NewHandler(cfg, pool)
	hub = ws.NewHub(cfg, pool)

	mux := http.NewServeMux()

	// REST Endpoints
	mux.HandleFunc("/api/health", h.HealthHandler)
	mux.HandleFunc("/api/status", h.StatusHandler)
	mux.HandleFunc("/api/models", h.ModelsHandler)
	mux.HandleFunc("/api/models/refresh", h.ModelsHandler)
	mux.HandleFunc("/api/quotas", h.QuotasHandler)
	mux.HandleFunc("/api/quotas/refresh", h.QuotasHandler)
	mux.HandleFunc("/api/instances", h.InstancesHandler)
	mux.HandleFunc("/api/abort", h.AbortHandler)
	mux.HandleFunc("/api/conversations", h.ConversationsHandler)
	mux.HandleFunc("/api/system-prompt", h.SystemPromptHandler)
	mux.HandleFunc("/api/projects", h.ProjectsHandler)
	mux.HandleFunc("/api/projects/create", h.CreateProjectHandler)
	mux.HandleFunc("/api/tree", h.FileTreeHandler)
	mux.HandleFunc("/api/file/read", h.FileReadHandler)
	mux.HandleFunc("/api/file/save", h.FileSaveHandler)
	mux.HandleFunc("/api/file/patch", h.FilePatchHandler)
	mux.HandleFunc("/api/file/create", h.FileCreateHandler)
	mux.HandleFunc("/api/file/delete", h.FileDeleteHandler)
	mux.HandleFunc("/api/file/rename", h.FileRenameHandler)
	mux.HandleFunc("/api/search", h.FileSearchHandler)
	mux.HandleFunc("/api/upload", h.UploadHandler)

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
	mux.HandleFunc("/ws", hub.ServeWS)

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.ToLower(r.Header.Get("Upgrade")) == "websocket" {
			hub.ServeWS(w, r)
			return
		}
		if r.URL.Path == "/" {
			w.Header().Set("Content-Type", "text/plain")
			_, _ = w.Write([]byte(fmt.Sprintf("Antigravity Bridge Daemon (Go High-Performance Engine) running on :%s\n", cfg.Port)))
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

	server := &http.Server{
		Addr:         "0.0.0.0:" + cfg.Port,
		Handler:      corsHandler,
		ReadTimeout:  60 * time.Second,
		WriteTimeout: 60 * time.Second,
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		fmt.Printf("🚀 Antigravity Go Bridge Server running at http://localhost:%s\n", cfg.Port)
		// 1. Refresh dynamic models from agy in background
		go models_discovery.FetchAvailableModels(false)
		// 2. Pre-warm default session immediately
		pool.Prewarm("gemini-3.7-flash-high", "", cfg.WorkspaceDir)

		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Server error: %v", err)
		}
	}()

	<-stopChan
	fmt.Println("\n🛑 Shutting down Go server gracefully...")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
	pool.AbortSession("")
	fmt.Println("✅ Go server stopped.")
}
