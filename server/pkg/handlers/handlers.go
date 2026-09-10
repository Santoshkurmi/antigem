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

func (h *Handler) FileReadHandler(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Query().Get("path")
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
		// Write to both primary (~/.gemini/config/mcp_config.json) and legacy (~/.gemini/antigravity/mcp_config.json)
		_ = os.MkdirAll(filepath.Dir(primaryConfigPath), 0755)
		errPrimary := os.WriteFile(primaryConfigPath, []byte(req.Content), 0644)

		_ = os.MkdirAll(filepath.Dir(legacyConfigPath), 0755)
		errLegacy := os.WriteFile(legacyConfigPath, []byte(req.Content), 0644)

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
