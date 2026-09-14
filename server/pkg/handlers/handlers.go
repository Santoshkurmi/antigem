package handlers

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/config"
	"gemini-server/pkg/hub"
	"gemini-server/pkg/models"
	"gemini-server/pkg/scanner"
	"gemini-server/pkg/transcript"
)

var (
	startTime       = time.Now()
	sanitizeNameReg = regexp.MustCompile(`[^a-zA-Z0-9_-]`)
)

type Broadcaster interface {
	Broadcast(msg interface{})
}

type Handler struct {
	Cfg        *config.Config
	HubManager *hub.HubManager
	Hub        Broadcaster

	lastLoginURL   string
	lastLoginURLMu sync.RWMutex
	lastLoginTime  time.Time
}

func NewHandler(cfg *config.Config, hubMgr *hub.HubManager) *Handler {
	return &Handler{
		Cfg:        cfg,
		HubManager: hubMgr,
	}
}

func (h *Handler) SetHub(hub Broadcaster) {
	h.Hub = hub
}

func (h *Handler) NotifyGitChanged(project string) {
	if h.Hub != nil {
		h.Hub.Broadcast(map[string]interface{}{
			"type":    "git_status_changed",
			"project": project,
		})
	}
}

func (h *Handler) HealthHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":    "ok",
		"timestamp": time.Now().UTC().Format(time.RFC3339),
		"version":   "2.0.0",
	})
}

func (h *Handler) StatusHandler(w http.ResponseWriter, r *http.Request) {
	hubActive := false
	hubPort := "8090"
	if h.HubManager != nil {
		hubActive = h.HubManager.IsRunning()
		hubPort = h.HubManager.HubPort
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":    "ok",
		"uptime":    time.Since(startTime).Seconds(),
		"timestamp": time.Now().UTC().Format(time.RFC3339),
		"version":   "2.0.0",
		"hub": map[string]interface{}{
			"active":  hubActive,
			"port":    hubPort,
			"address": fmt.Sprintf("http://127.0.0.1:%s", hubPort),
		},
		"pool": map[string]interface{}{
			"activeSessions": 0,
			"instances":      []interface{}{},
		},
	})
}

func (h *Handler) ModelsHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]interface{}{"models": []interface{}{}})
}

func (h *Handler) QuotasHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]interface{}{})
}

func (h *Handler) InstancesHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"instances": []interface{}{},
	})
}

func (h *Handler) TerminateInstanceHandler(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(r.URL.Path, "/")
	convID := ""
	if len(parts) >= 4 {
		convID = parts[3]
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":         "terminated",
		"conversationId": convID,
	})
}

type convWithModTime struct {
	summary models.ConversationSummary
	modTime time.Time
}

func (h *Handler) ConversationsHandler(w http.ResponseWriter, r *http.Request) {
	searchQuery := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	if searchQuery == "" {
		searchQuery = strings.ToLower(strings.TrimSpace(r.URL.Query().Get("query")))
	}

	page := 1
	if pStr := r.URL.Query().Get("page"); pStr != "" {
		if pVal, err := strconv.Atoi(pStr); err == nil && pVal > 0 {
			page = pVal
		}
	}

	limit := 30
	if lStr := r.URL.Query().Get("limit"); lStr != "" {
		if lVal, err := strconv.Atoi(lStr); err == nil && lVal >= 0 {
			limit = lVal
		}
	}

	var results []convWithModTime
	seen := make(map[string]bool)

	entries, err := os.ReadDir(h.Cfg.BrainDir)
	if err == nil {
		for _, e := range entries {
			if !e.IsDir() {
				continue
			}
			convID := e.Name()
			if seen[convID] {
				continue
			}

			transcriptPath := filepath.Join(h.Cfg.BrainDir, convID, ".system_generated", "logs", "transcript.jsonl")
			stat, err := os.Stat(transcriptPath)
			// Skip directories that do not contain a valid transcript.jsonl
			if err != nil || stat.Size() == 0 {
				continue
			}

			seen[convID] = true
			title := convID
			if len(title) > 8 {
				title = title[:8]
			}
			firstPrompt := ""

			titleFile := filepath.Join(h.Cfg.BrainDir, convID, "custom_title.txt")
			if tData, err := os.ReadFile(titleFile); err == nil && len(tData) > 0 {
				tStr := strings.TrimSpace(string(tData))
				if tStr != "" {
					title = tStr
				}
			} else {
				// Read first USER_INPUT step to extract title
				if file, err := os.Open(transcriptPath); err == nil {
					sc := bufio.NewScanner(file)
					buf := make([]byte, 64*1024)
					sc.Buffer(buf, 1024*1024)
					for sc.Scan() {
						line := strings.TrimSpace(sc.Text())
						if line == "" {
							continue
						}
						var step struct {
							Type    string `json:"type"`
							Content string `json:"content"`
						}
						if err := json.Unmarshal([]byte(line), &step); err == nil && step.Type == "USER_INPUT" && step.Content != "" {
							cleanPrompt := transcript.ExtractPromptText(step.Content)
							if cleanPrompt != "" {
								firstPrompt = cleanPrompt
								if len(cleanPrompt) > 36 {
									title = cleanPrompt[:36] + "..."
								} else {
									title = cleanPrompt
								}
								break
							}
						}
					}
					file.Close()
				}
			}

			// Filter by search query if specified
			if searchQuery != "" {
				matchesID := strings.Contains(strings.ToLower(convID), searchQuery)
				matchesTitle := strings.Contains(strings.ToLower(title), searchQuery)
				matchesPrompt := strings.Contains(strings.ToLower(firstPrompt), searchQuery)
				if !matchesID && !matchesTitle && !matchesPrompt {
					continue
				}
			}

			isRunning := false

			results = append(results, convWithModTime{
				summary: models.ConversationSummary{
					ID:           convID,
					Title:        title,
					CreatedAt:    stat.ModTime().UTC().Format(time.RFC3339),
					StepsCount:   1,
					IsRunning:    isRunning,
					LastActivity: stat.ModTime().UTC().Format(time.RFC3339),
				},
				modTime: stat.ModTime(),
			})
		}
	}

	// Sort descending by most recently updated
	sort.Slice(results, func(i, j int) bool {
		return results[i].modTime.After(results[j].modTime)
	})

	total := len(results)
	var paged []models.ConversationSummary

	if limit > 0 {
		start := (page - 1) * limit
		if start < total {
			end := start + limit
			if end > total {
				end = total
			}
			for i := start; i < end; i++ {
				paged = append(paged, results[i].summary)
			}
		}
	} else {
		for _, r := range results {
			paged = append(paged, r.summary)
		}
	}

	hasMore := limit > 0 && ((page * limit) < total)

	writeJSON(w, http.StatusOK, models.ConversationListResponse{
		Conversations: paged,
		Total:         total,
		Page:          page,
		Limit:         limit,
		HasMore:       hasMore,
	})
}

func (h *Handler) ConversationMessagesHandler(w http.ResponseWriter, r *http.Request) {
	convID := strings.TrimPrefix(r.URL.Path, "/api/conversations/")

	transcriptPath := filepath.Join(h.Cfg.BrainDir, convID, ".system_generated", "logs", "transcript.jsonl")
	if _, err := os.Stat(transcriptPath); os.IsNotExist(err) {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"error": "Conversation not found", "messages": []models.ChatMessage{}})
		return
	}

	msgs, err := transcript.ParseTranscript(transcriptPath, convID, h.Cfg.Port)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"error": err.Error(), "messages": []models.ChatMessage{}})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{"id": convID, "messages": msgs})
}

func (h *Handler) UpdateConversationTitleHandler(w http.ResponseWriter, r *http.Request) {
	convID := strings.TrimPrefix(r.URL.Path, "/api/conversations/")
	convID = strings.TrimSuffix(convID, "/title")

	var req struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Title == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "title is required"})
		return
	}

	convDir := filepath.Join(h.Cfg.BrainDir, convID)
	_ = os.MkdirAll(convDir, 0755)
	titleFile := filepath.Join(convDir, "custom_title.txt")
	_ = os.WriteFile(titleFile, []byte(strings.TrimSpace(req.Title)), 0644)
	fmt.Printf("[Conversations] Saved custom title for %s: \"%s\"\n", convID, req.Title)

	writeJSON(w, http.StatusOK, map[string]interface{}{"status": "ok", "id": convID, "title": req.Title})
}

func (h *Handler) DeleteConversationHandler(w http.ResponseWriter, r *http.Request) {
	convID := strings.TrimPrefix(r.URL.Path, "/api/conversations/")

	convDir := filepath.Join(h.Cfg.BrainDir, convID)
	_ = os.RemoveAll(convDir)
	fmt.Printf("[Conversations] Deleted brain directory on disk for %s\n", convID)

	writeJSON(w, http.StatusOK, map[string]interface{}{"status": "deleted", "id": convID})
}

func (h *Handler) PrewarmConversationHandler(w http.ResponseWriter, r *http.Request) {
	convID := strings.TrimPrefix(r.URL.Path, "/api/conversations/")
	convID = strings.TrimSuffix(convID, "/warm")
	if convID == "new" {
		convID = ""
	}

	var req struct {
		Model        string `json:"model"`
		WorkspaceDir string `json:"workspaceDir"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	targetModel := req.Model
	if targetModel == "" {
		targetModel = "gemini-3.7-flash-high"
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":         "ready",
		"model":          targetModel,
		"conversationId": convID,
		"workspaceDir":   req.WorkspaceDir,
	})
}

func (h *Handler) AbortHandler(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ConversationID string `json:"conversationId"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":         "aborted",
		"conversationId": req.ConversationID,
	})
}

func (h *Handler) SystemPromptHandler(w http.ResponseWriter, r *http.Request) {
	convID := r.URL.Query().Get("conversationId")
	if convID == "" {
		convID = strings.TrimPrefix(r.URL.Path, "/api/conversations/")
		convID = strings.TrimSuffix(convID, "/system-prompt")
	}
	if convID == "" || convID == "/api/system-prompt" {
		convID = "active"
	}
	wsDir := r.URL.Query().Get("workspaceDir")
	if wsDir == "" {
		wsDir = h.Cfg.WorkspaceDir
	}

	prompt := scanner.CompileSystemPrompt(convID, wsDir, h.Cfg.AppDataDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"conversationId": convID,
		"systemPrompt":   prompt,
	})
}

func (h *Handler) ProjectsHandler(w http.ResponseWriter, r *http.Request) {
	var projects []models.ProjectSummary
	entries, err := os.ReadDir(h.Cfg.ProjectsBaseDir)
	if err == nil {
		for _, e := range entries {
			if e.IsDir() {
				projects = append(projects, models.ProjectSummary{
					Name: e.Name(),
					Path: filepath.Join(h.Cfg.ProjectsBaseDir, e.Name()),
				})
			}
		}
	}
	if len(projects) == 0 {
		projects = append(projects, models.ProjectSummary{
			Name: "gemini",
			Path: h.Cfg.WorkspaceDir,
		})
	}
	writeJSON(w, http.StatusOK, projects)
}

func (h *Handler) CreateProjectHandler(w http.ResponseWriter, r *http.Request) {
	var req models.CreateProjectReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	projectName := strings.TrimSpace(req.Name)
	if projectName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Project name required"})
		return
	}

	targetDir := filepath.Join(h.Cfg.ProjectsBaseDir, projectName)
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	switch req.Template {
	case "python":
		_ = os.WriteFile(filepath.Join(targetDir, "main.py"), []byte("def main():\n    print(\"Hello from Python IDE!\")\n\nif __name__ == '__main__':\n    main()\n"), 0644)
		_ = os.WriteFile(filepath.Join(targetDir, "README.md"), []byte("# "+projectName+"\n\nPython project created in antiGem IDE.\n"), 0644)
	case "node":
		_ = os.WriteFile(filepath.Join(targetDir, "index.js"), []byte("console.log('Hello from Node.js IDE!');\n"), 0644)
		_ = os.WriteFile(filepath.Join(targetDir, "package.json"), []byte(fmt.Sprintf("{\n  \"name\": \"%s\",\n  \"version\": \"1.0.0\",\n  \"main\": \"index.js\"\n}\n", projectName)), 0644)
	case "web":
		_ = os.WriteFile(filepath.Join(targetDir, "index.html"), []byte("<!DOCTYPE html>\n<html>\n<head>\n  <title>"+projectName+"</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>Hello Web IDE!</h1>\n  <script src=\"app.js\"></script>\n</body>\n</html>"), 0644)
		_ = os.WriteFile(filepath.Join(targetDir, "style.css"), []byte("body { font-family: sans-serif; background: #121212; color: #fff; padding: 20px; }\n"), 0644)
		_ = os.WriteFile(filepath.Join(targetDir, "app.js"), []byte("console.log('Web App Ready');\n"), 0644)
	case "cpp":
		_ = os.WriteFile(filepath.Join(targetDir, "main.cpp"), []byte("#include <iostream>\n\nint main() {\n    std::cout << \"Hello from C++ IDE!\" << std::endl;\n    return 0;\n}\n"), 0644)
	case "kotlin":
		_ = os.WriteFile(filepath.Join(targetDir, "Main.kt"), []byte("fun main() {\n    println(\"Hello from Kotlin IDE!\")\n}\n"), 0644)
	default:
		_ = os.WriteFile(filepath.Join(targetDir, "README.md"), []byte("# "+projectName+"\n"), 0644)
	}

	writeJSON(w, http.StatusOK, models.ProjectSummary{Name: projectName, Path: targetDir})
}

// FsBrowseHandler handles directory browsing starting from user home.
func (h *Handler) FsBrowseHandler(w http.ResponseWriter, r *http.Request) {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		homeDir = h.Cfg.ProjectsBaseDir
	}

	dir := strings.TrimSpace(r.URL.Query().Get("dir"))
	if dir == "" || dir == "~" {
		dir = homeDir
	} else if strings.HasPrefix(dir, "~/") {
		dir = filepath.Join(homeDir, strings.TrimPrefix(dir, "~/"))
	}
	dir = filepath.Clean(dir)

	entries, err := os.ReadDir(dir)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	var subdirs []models.ProjectSummary
	for _, e := range entries {
		if e.IsDir() {
			name := e.Name()
			if strings.HasPrefix(name, ".") {
				continue // skip hidden folders
			}
			subdirs = append(subdirs, models.ProjectSummary{
				Name: name,
				Path: filepath.Join(dir, name),
			})
		}
	}

	sort.Slice(subdirs, func(i, j int) bool {
		return strings.ToLower(subdirs[i].Name) < strings.ToLower(subdirs[j].Name)
	})

	parent := filepath.Dir(dir)
	if parent == dir {
		parent = ""
	}

	writeJSON(w, http.StatusOK, models.FsBrowseResult{
		CurrentPath: dir,
		ParentPath:  parent,
		HomePath:    homeDir,
		Directories: subdirs,
	})
}

// FsMkdirHandler creates a directory on the filesystem.
func (h *Handler) FsMkdirHandler(w http.ResponseWriter, r *http.Request) {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		homeDir = h.Cfg.ProjectsBaseDir
	}

	var req models.MkdirReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	targetPath := strings.TrimSpace(req.Path)
	if targetPath == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Path is required"})
		return
	}
	if strings.HasPrefix(targetPath, "~/") {
		targetPath = filepath.Join(homeDir, strings.TrimPrefix(targetPath, "~/"))
	}
	targetPath = filepath.Clean(targetPath)

	if err := os.MkdirAll(targetPath, 0755); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": true,
		"path":    targetPath,
		"name":    filepath.Base(targetPath),
	})
}

// FileTreeHandler recursively lists all files and folders without arbitrary depth limits.
func (h *Handler) FileTreeHandler(w http.ResponseWriter, r *http.Request) {
	dir := r.URL.Query().Get("dir")
	if dir == "" {
		dir = h.Cfg.ProjectsBaseDir
	}

	tree, err := buildRecursiveFileTree(dir, 0, 25)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	if tree == nil {
		tree = []models.FileNode{}
	}
	writeJSON(w, http.StatusOK, tree)
}

func buildRecursiveFileTree(dir string, currentDepth int, maxDepth int) ([]models.FileNode, error) {
	if currentDepth > maxDepth {
		return nil, nil
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}

	var nodes []models.FileNode
	for _, entry := range entries {
		name := entry.Name()
		// Ignore hidden files and build output directories
		if strings.HasPrefix(name, ".") || name == "node_modules" || name == "build" || name == "target" || name == "dist" || name == ".gradle" || name == ".idea" {
			continue
		}

		fullPath := filepath.Join(dir, name)
		info, _ := entry.Info()
		var size int64
		if info != nil {
			size = info.Size()
		}

		node := models.FileNode{
			Name:  name,
			Path:  fullPath,
			IsDir: entry.IsDir(),
			Size:  size,
		}

		if entry.IsDir() {
			children, _ := buildRecursiveFileTree(fullPath, currentDepth+1, maxDepth)
			node.Children = children
		}

		nodes = append(nodes, node)
	}

	sort.Slice(nodes, func(i, j int) bool {
		if nodes[i].IsDir != nodes[j].IsDir {
			return nodes[i].IsDir
		}
		return strings.ToLower(nodes[i].Name) < strings.ToLower(nodes[j].Name)
	})

	return nodes, nil
}

func expandHome(p string) string {
	if strings.HasPrefix(p, "~/") || p == "~" {
		home, err := os.UserHomeDir()
		if err == nil {
			if p == "~" {
				return home
			}
			return filepath.Join(home, p[2:])
		}
	}
	return p
}

func (h *Handler) FileReadHandler(w http.ResponseWriter, r *http.Request) {
	path := expandHome(r.URL.Query().Get("path"))
	if path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "path parameter required"})
		return
	}

	content, err := os.ReadFile(path)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".svg":
		w.Header().Set("Content-Type", "image/svg+xml")
	case ".png":
		w.Header().Set("Content-Type", "image/png")
	case ".jpg", ".jpeg":
		w.Header().Set("Content-Type", "image/jpeg")
	case ".webp":
		w.Header().Set("Content-Type", "image/webp")
	case ".gif":
		w.Header().Set("Content-Type", "image/gif")
	case ".json":
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
	case ".html", ".htm":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
	default:
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Write(content)
}

func (h *Handler) FileSaveHandler(w http.ResponseWriter, r *http.Request) {
	var req models.FileSaveReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "valid path and content required"})
		return
	}
	req.Path = expandHome(req.Path)

	_ = os.MkdirAll(filepath.Dir(req.Path), 0755)
	tmpPath := req.Path + ".tmp"
	if err := os.WriteFile(tmpPath, []byte(req.Content), 0644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	if err := os.Rename(tmpPath, req.Path); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	h.NotifyGitChanged(req.Path)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": req.Path})
}

func (h *Handler) FilePatchHandler(w http.ResponseWriter, r *http.Request) {
	var req models.FilePatchReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "valid path and patch parameters required"})
		return
	}
	req.Path = expandHome(req.Path)

	raw, err := os.ReadFile(req.Path)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	lines := strings.Split(string(raw), "\n")
	startIdx := req.StartLine - 1
	endIdx := req.EndLine

	if startIdx < 0 {
		startIdx = 0
	}
	if endIdx > len(lines) {
		endIdx = len(lines)
	}

	var updated []string
	updated = append(updated, lines[:startIdx]...)
	updated = append(updated, req.Replacement)
	if endIdx < len(lines) {
		updated = append(updated, lines[endIdx:]...)
	}

	newContent := strings.Join(updated, "\n")
	tmpPath := req.Path + ".tmp"
	if err := os.WriteFile(tmpPath, []byte(newContent), 0644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	if err := os.Rename(tmpPath, req.Path); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	h.NotifyGitChanged(req.Path)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": req.Path})
}

func (h *Handler) FileCreateHandler(w http.ResponseWriter, r *http.Request) {
	var req models.FileOpReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "path required"})
		return
	}
	req.Path = expandHome(req.Path)

	var err error
	if req.IsDir {
		err = os.MkdirAll(req.Path, 0755)
	} else {
		_ = os.MkdirAll(filepath.Dir(req.Path), 0755)
		err = os.WriteFile(req.Path, []byte(""), 0644)
	}

	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(req.Path)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": req.Path})
}

func (h *Handler) FileDeleteHandler(w http.ResponseWriter, r *http.Request) {
	var req models.FileOpReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "path required"})
		return
	}
	req.Path = expandHome(req.Path)

	if err := os.RemoveAll(req.Path); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(req.Path)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": req.Path})
}

func (h *Handler) FileRenameHandler(w http.ResponseWriter, r *http.Request) {
	var req models.FileOpReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Path == "" || req.NewPath == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "path and newPath required"})
		return
	}
	req.Path = expandHome(req.Path)
	req.NewPath = expandHome(req.NewPath)

	if err := os.Rename(req.Path, req.NewPath); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(req.NewPath)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": req.NewPath})
}

func (h *Handler) FileSearchHandler(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	dir := r.URL.Query().Get("dir")
	if dir == "" {
		dir = h.Cfg.ProjectsBaseDir
	}

	cmd := exec.Command("rg", "-n", "--no-heading", query, dir)
	output, _ := cmd.Output()

	var matches []models.SearchMatch
	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) == 3 {
			lineNum := 0
			fmt.Sscanf(parts[1], "%d", &lineNum)
			matches = append(matches, models.SearchMatch{
				Path:       parts[0],
				LineNumber: lineNum,
				LineText:   strings.TrimSpace(parts[2]),
			})
		}
	}

	if matches == nil {
		matches = []models.SearchMatch{}
	}
	writeJSON(w, http.StatusOK, matches)
}

func (h *Handler) UploadHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req models.UploadRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Filename == "" || req.Base64Data == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "filename and base64Data are required"})
		return
	}

	wsDir := req.ProjectPath
	if wsDir == "" || !dirExists(wsDir) {
		wsDir = h.Cfg.WorkspaceDir
	}

	targetDir := filepath.Join(wsDir, ".gemini", "attachments")
	_ = os.MkdirAll(targetDir, 0755)

	ext := filepath.Ext(req.Filename)
	base := strings.TrimSuffix(req.Filename, ext)
	base = sanitizeNameReg.ReplaceAllString(base, "_")
	uniqueName := fmt.Sprintf("%s_%d%s", base, time.Now().UnixMilli(), ext)
	targetPath := filepath.Join(targetDir, uniqueName)

	buffer, err := base64.StdEncoding.DecodeString(req.Base64Data)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid base64 payload"})
		return
	}

	if err := os.WriteFile(targetPath, buffer, 0644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	lowerExt := strings.ToLower(ext)
	isImage := lowerExt == ".jpg" || lowerExt == ".jpeg" || lowerExt == ".png" || lowerExt == ".gif" || lowerExt == ".webp" || lowerExt == ".svg"

	fmt.Printf("[UPLOAD] Saved attachment: %s (%d bytes) at %s\n", uniqueName, len(buffer), targetPath)

	writeJSON(w, http.StatusOK, models.ChatAttachment{
		ID:        fmt.Sprintf("att_%d", time.Now().UnixMilli()),
		Name:      req.Filename,
		SavedName: uniqueName,
		Path:      targetPath,
		IsImage:   isImage,
		Size:      len(buffer),
		URL:       fmt.Sprintf("http://127.0.0.1:%s/attachments/%s", h.Cfg.Port, uniqueName),
	})
}

func (h *Handler) ArtifactsFileServer() http.Handler {
	return http.StripPrefix("/artifacts/", http.FileServer(http.Dir(h.Cfg.BrainDir)))
}

func (h *Handler) AttachmentsFileServer() http.Handler {
	targetDir := filepath.Join(h.Cfg.WorkspaceDir, ".gemini", "attachments")
	return http.StripPrefix("/attachments/", http.FileServer(http.Dir(targetDir)))
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func (h *Handler) McpConfigHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == "OPTIONS" {
		w.WriteHeader(http.StatusOK)
		return
	}

	ensureLocalToolsScript(h.Cfg.HomeDir)

	primaryConfigPath := filepath.Join(h.Cfg.HomeDir, ".gemini", "config", "mcp_config.json")
	legacyConfigPath := filepath.Join(h.Cfg.HomeDir, ".gemini", "antigravity", "mcp_config.json")

	if r.Method == "GET" {
		content, err := os.ReadFile(primaryConfigPath)
		chosenPath := primaryConfigPath
		if err != nil || len(strings.TrimSpace(string(content))) == 0 {
			legacyContent, err2 := os.ReadFile(legacyConfigPath)
			if err2 == nil && len(strings.TrimSpace(string(legacyContent))) > 0 {
				content = legacyContent
				chosenPath = legacyConfigPath
			} else if err != nil {
				content = []byte("{\n  \"mcpServers\": {}\n}")
			}
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"path":    chosenPath,
			"content": string(content),
		})
		return
	}

	if r.Method == "POST" {
		var req struct {
			Content string `json:"content"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
			return
		}
		saveContent := injectTermuxEnvIfNeeded(req.Content)

		// Write to both primary (~/.gemini/config/mcp_config.json) and legacy (~/.gemini/antigravity/mcp_config.json)
		_ = os.MkdirAll(filepath.Dir(primaryConfigPath), 0755)
		errPrimary := os.WriteFile(primaryConfigPath, []byte(saveContent), 0644)

		_ = os.MkdirAll(filepath.Dir(legacyConfigPath), 0755)
		errLegacy := os.WriteFile(legacyConfigPath, []byte(saveContent), 0644)

		if errPrimary != nil && errLegacy != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": errPrimary.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"success": true,
			"path":    primaryConfigPath,
		})
		return
	}

	w.WriteHeader(http.StatusMethodNotAllowed)
}

func injectTermuxEnvIfNeeded(rawContent string) string {
	libTermuxExec := "/data/data/com.termux/files/usr/lib/libtermux-exec.so"
	if _, err := os.Stat(libTermuxExec); err != nil {
		return rawContent
	}
	var root map[string]interface{}
	if err := json.Unmarshal([]byte(rawContent), &root); err != nil {
		return rawContent
	}
	servers, ok := root["mcpServers"].(map[string]interface{})
	if !ok {
		return rawContent
	}
	for _, sVal := range servers {
		sMap, ok := sVal.(map[string]interface{})
		if !ok {
			continue
		}
		if _, hasCmd := sMap["command"]; hasCmd {
			envMap, ok := sMap["env"].(map[string]interface{})
			if !ok || envMap == nil {
				envMap = make(map[string]interface{})
			}
			if _, hasPreload := envMap["LD_PRELOAD"]; !hasPreload {
				envMap["LD_PRELOAD"] = libTermuxExec
			}
			if _, hasPath := envMap["PATH"]; !hasPath {
				envMap["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin"
			}
			sMap["env"] = envMap
		}
	}
	out, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return rawContent
	}
	return string(out)
}

func ensureLocalToolsScript(homeDir string) {
	scriptPath := filepath.Join(homeDir, ".gemini", "local_tools.py")
	if _, err := os.Stat(scriptPath); err == nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(scriptPath), 0755)
	scriptContent := `#!/usr/bin/env python3
import sys
import json
import datetime
import sqlite3
import math

def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            continue

        method = req.get("method")
        msg_id = req.get("id")

        if method == "initialize":
            res = {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "local_tools", "version": "1.0.0"}
            }
        elif method == "tools/list":
            res = {
                "tools": [
                    {
                        "name": "calc_math",
                        "description": "Calculate mathematical expression (e.g. 15 * 4 + 2, 2**10, sqrt, sin, cos)",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "expression": {
                                    "type": "string",
                                    "description": "Math expression to evaluate"
                                }
                            },
                            "required": ["expression"]
                        }
                    },
                    {
                        "name": "get_system_time",
                        "description": "Get current local date, time, and timezone from the device",
                        "inputSchema": {
                            "type": "object",
                            "properties": {}
                        }
                    },
                    {
                        "name": "sqlite_query",
                        "description": "Execute an SQL query against a SQLite database file (or in-memory if path is :memory:)",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "SQL query to execute (e.g. SELECT, CREATE TABLE, INSERT)"
                                },
                                "db_path": {
                                    "type": "string",
                                    "description": "Path to sqlite db file or :memory: (default :memory:)"
                                }
                            },
                            "required": ["query"]
                        }
                    }
                ]
            }
        elif method == "tools/call":
            name = req.get("params", {}).get("name")
            args = req.get("params", {}).get("arguments", {})
            if name == "calc_math":
                expr = args.get("expression", "0")
                try:
                    safe_env = {
                        "__builtins__": {},
                        "math": math,
                        "abs": abs,
                        "round": round,
                        "min": min,
                        "max": max,
                        "pow": pow
                    }
                    val = eval(expr, safe_env)
                    res = {"content": [{"type": "text", "text": str(val)}]}
                except Exception as e:
                    res = {"content": [{"type": "text", "text": f"Error: {e}"}], "isError": True}
            elif name == "get_system_time":
                now_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                res = {"content": [{"type": "text", "text": f"Device local time: {now_str}"}]}
            elif name == "sqlite_query":
                query = args.get("query", "")
                db_path = args.get("db_path", ":memory:")
                try:
                    conn = sqlite3.connect(db_path)
                    cur = conn.cursor()
                    cur.execute(query)
                    if query.strip().upper().startswith("SELECT") or query.strip().upper().startswith("PRAGMA"):
                        rows = cur.fetchall()
                        cols = [d[0] for d in cur.description] if cur.description else []
                        conn.close()
                        res = {"content": [{"type": "text", "text": json.dumps({"columns": cols, "rows": rows}, indent=2)}]}
                    else:
                        conn.commit()
                        affected = cur.rowcount
                        conn.close()
                        res = {"content": [{"type": "text", "text": f"Query executed successfully. Rows affected: {affected}"}]}
                except Exception as e:
                    res = {"content": [{"type": "text", "text": f"SQLite error: {e}"}], "isError": True}
            else:
                res = {"content": [{"type": "text", "text": f"Unknown tool: {name}"}], "isError": True}
        elif msg_id is not None:
            res = {}
        else:
            continue

        if msg_id is not None:
            out = {"jsonrpc": "2.0", "id": msg_id, "result": res}
            sys.stdout.write(json.dumps(out) + "\n")
            sys.stdout.flush()

if __name__ == "__main__":
    main()
`
	_ = os.WriteFile(scriptPath, []byte(scriptContent), 0755)
}

// HandleLoginURL captures a login URL from AGY hub output and broadcasts it to clients.
func (h *Handler) HandleLoginURL(url string) {
	h.lastLoginURLMu.Lock()
	h.lastLoginURL = url
	h.lastLoginTime = time.Now()
	h.lastLoginURLMu.Unlock()

	if h.Hub != nil {
		h.Hub.Broadcast(map[string]interface{}{
			"type":      "auth_login_url",
			"url":       url,
			"timestamp": time.Now().UTC().Format(time.RFC3339),
		})
	}
}

// GetLoginURLHandler returns the latest detected Google/AGY login URL.
func (h *Handler) GetLoginURLHandler(w http.ResponseWriter, r *http.Request) {
	h.lastLoginURLMu.RLock()
	url := h.lastLoginURL
	t := h.lastLoginTime
	h.lastLoginURLMu.RUnlock()

	// Consider URL expired after 5 minutes
	if time.Since(t) > 5*time.Minute {
		url = ""
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"loginUrl":  url,
		"timestamp": t.UTC().Format(time.RFC3339),
		"active":    url != "",
	})
}

// StartLoginHandler sends a Login request to the AGY Hub RPC server.
func (h *Handler) StartLoginHandler(w http.ResponseWriter, r *http.Request) {
	hubPort := "8090"
	if h.HubManager != nil && h.HubManager.HubPort != "" {
		hubPort = h.HubManager.HubPort
	}

	h.lastLoginURLMu.Lock()
	h.lastLoginURL = ""
	h.lastLoginURLMu.Unlock()

	hubURL := fmt.Sprintf("http://127.0.0.1:%s/exa.language_server_pb.LanguageServerService/Login", hubPort)
	req, err := http.NewRequest("POST", hubURL, strings.NewReader(`{"isGcpTos":false}`))
	if err == nil {
		req.Header.Set("Content-Type", "application/json")
		client := &http.Client{Timeout: 5 * time.Second}
		resp, postErr := client.Do(req)
		if postErr == nil {
			_ = resp.Body.Close()
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": true,
		"message": "Login initiated on AGY Hub",
	})
}

// UserProfileHandler returns the user profile name and email directly from the agy OAuth token.
func (h *Handler) UserProfileHandler(w http.ResponseWriter, r *http.Request) {
	home, _ := os.UserHomeDir()
	fullName, email, picture := fetchAgyUserProfile(home)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"isLoggedIn":        fullName != "" || email != "",
		"fullName":          fullName,
		"email":             email,
		"profilePictureUrl": picture,
	})
}

func fetchAgyUserProfile(homeDir string) (name, email, picture string) {
	tokenPath := filepath.Join(homeDir, ".gemini", "antigravity-cli", "antigravity-oauth-token")
	data, err := os.ReadFile(tokenPath)
	if err != nil || len(data) == 0 {
		return "", "", ""
	}

	var parsed struct {
		Token struct {
			AccessToken string `json:"access_token"`
		} `json:"token"`
		IDToken string `json:"id_token"`
	}
	if err := json.Unmarshal(data, &parsed); err != nil {
		return "", "", ""
	}

	// 1. Extract directly from id_token JWT (instant, offline, exact user profile)
	if parsed.IDToken != "" {
		parts := strings.Split(parsed.IDToken, ".")
		if len(parts) >= 2 {
			payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
			if err != nil {
				payloadBytes, err = base64.URLEncoding.DecodeString(parts[1])
			}
			if err == nil {
				var claims struct {
					Name    string `json:"name"`
					Email   string `json:"email"`
					Picture string `json:"picture"`
				}
				if json.Unmarshal(payloadBytes, &claims) == nil && (claims.Name != "" || claims.Email != "") {
					return claims.Name, claims.Email, claims.Picture
				}
			}
		}
	}

	// 2. Fallback: query Google UserInfo with existing access token as-is (DO NOT REFRESH)
	if parsed.Token.AccessToken != "" {
		req, err := http.NewRequest("GET", "https://www.googleapis.com/oauth2/v1/userinfo", nil)
		if err == nil {
			req.Header.Set("Authorization", "Bearer "+parsed.Token.AccessToken)
			client := &http.Client{Timeout: 4 * time.Second}
			if resp, err := client.Do(req); err == nil {
				defer resp.Body.Close()
				if resp.StatusCode == http.StatusOK {
					var info struct {
						Name    string `json:"name"`
						Email   string `json:"email"`
						Picture string `json:"picture"`
					}
					if json.NewDecoder(resp.Body).Decode(&info) == nil {
						return info.Name, info.Email, info.Picture
					}
				}
			}
		}
	}

	return "", "", ""
}
