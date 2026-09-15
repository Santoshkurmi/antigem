package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

type ProjectItem struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

type CreateProjectReq struct {
	Name     string `json:"name"`
	Template string `json:"template"` // python, node, web, kotlin, cpp, blank
}

type FileNode struct {
	Name     string     `json:"name"`
	Path     string     `json:"path"`
	IsDir    bool       `json:"isDir"`
	Size     int64      `json:"size"`
	Children []FileNode `json:"children,omitempty"`
}

type FileSaveReq struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

type FilePatchReq struct {
	Path        string `json:"path"`
	StartLine   int    `json:"startLine"`
	EndLine     int    `json:"endLine"`
	Replacement string `json:"replacement"`
}

type FileOpReq struct {
	Path    string `json:"path"`
	NewPath string `json:"newPath,omitempty"`
	IsDir   bool   `json:"isDir,omitempty"`
}

type SearchMatch struct {
	Path       string `json:"path"`
	LineNumber int    `json:"lineNumber"`
	LineText   string `json:"lineText"`
}

type FsBrowseResult struct {
	CurrentPath string        `json:"currentPath"`
	ParentPath  string        `json:"parentPath"`
	HomePath    string        `json:"homePath"`
	Directories []ProjectItem `json:"directories"`
}

type MkdirReq struct {
	Path string `json:"path"`
}

func main() {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		homeDir = "/data/data/com.termux/files/home"
	}
	projectsDir := filepath.Join(homeDir, "projects")
	_ = os.MkdirAll(projectsDir, 0755)

	// Enable CORS for webview/localhost connections
	corsMiddleware := func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
			if r.Method == "OPTIONS" {
				w.WriteHeader(http.StatusOK)
				return
			}
			next(w, r)
		}
	}

	// 1. Healthcheck
	http.HandleFunc("/api/health", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"status":"ok","version":"1.0.0"}`))
	}))

	// 1.1 Auth Login URL placeholder
	http.HandleFunc("/api/auth/login-url", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"loginUrl":"","active":false}`))
	}))

	// 2. List Projects
	http.HandleFunc("/api/projects", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		entries, err := os.ReadDir(projectsDir)
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		var projects []ProjectItem
		for _, entry := range entries {
			if entry.IsDir() {
				projects = append(projects, ProjectItem{
					Name: entry.Name(),
					Path: filepath.Join(projectsDir, entry.Name()),
				})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(projects)
	}))

	// 2.1 Filesystem Directory Browser (starting from ~)
	http.HandleFunc("/api/fs/browse", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		dir := strings.TrimSpace(r.URL.Query().Get("dir"))
		if dir == "" || dir == "~" {
			dir = homeDir
		} else if strings.HasPrefix(dir, "~/") {
			dir = filepath.Join(homeDir, strings.TrimPrefix(dir, "~/"))
		}
		dir = filepath.Clean(dir)

		entries, err := os.ReadDir(dir)
		if err != nil {
			http.Error(w, fmt.Sprintf("Failed to read directory: %v", err), 500)
			return
		}

		var subdirs []ProjectItem
		for _, e := range entries {
			if e.IsDir() {
				name := e.Name()
				if strings.HasPrefix(name, ".") {
					continue // hide hidden folders by default
				}
				subdirs = append(subdirs, ProjectItem{
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

		resp := FsBrowseResult{
			CurrentPath: dir,
			ParentPath:  parent,
			HomePath:    homeDir,
			Directories: subdirs,
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}))

	// 2.2 Create Directory
	http.HandleFunc("/api/fs/mkdir", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var req MkdirReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		targetPath := strings.TrimSpace(req.Path)
		if targetPath == "" {
			http.Error(w, "Path is required", 400)
			return
		}
		if strings.HasPrefix(targetPath, "~/") {
			targetPath = filepath.Join(homeDir, strings.TrimPrefix(targetPath, "~/"))
		}
		targetPath = filepath.Clean(targetPath)

		if err := os.MkdirAll(targetPath, 0755); err != nil {
			http.Error(w, fmt.Sprintf("Failed to create directory: %v", err), 500)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"success": true,
			"path":    targetPath,
			"name":    filepath.Base(targetPath),
		})
	}))

	// 3. Create Project from Template
	http.HandleFunc("/api/projects/create", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var req CreateProjectReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}

		projectName := strings.TrimSpace(req.Name)
		if projectName == "" {
			http.Error(w, "Project name required", 400)
			return
		}

		targetDir := filepath.Join(projectsDir, projectName)
		if err := os.MkdirAll(targetDir, 0755); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}

		// Starter Templates
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
		default:
			_ = os.WriteFile(filepath.Join(targetDir, "README.md"), []byte("# "+projectName+"\n"), 0644)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(ProjectItem{Name: projectName, Path: targetDir})
	}))

	// 4. File Tree API
	http.HandleFunc("/api/tree", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		dir := r.URL.Query().Get("dir")
		if dir == "" {
			dir = projectsDir
		}

		tree, err := buildFileTree(dir, 0, 4) // max depth 4
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(tree)
	}))

	// 5. Read File
	http.HandleFunc("/api/file/read", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Query().Get("path")
		content, err := os.ReadFile(path)
		if err != nil {
			http.Error(w, err.Error(), 500)
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
		default:
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		}
		w.Write(content)
	}))

	// 6. Atomic Save File
	http.HandleFunc("/api/file/save", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var req FileSaveReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}

		tmpPath := req.Path + ".tmp"
		if err := os.WriteFile(tmpPath, []byte(req.Content), 0644); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		if err := os.Rename(tmpPath, req.Path); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"success":true}`))
	}))

	// 7. Line Patch File
	http.HandleFunc("/api/file/patch", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var req FilePatchReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}

		raw, err := os.ReadFile(req.Path)
		if err != nil {
			http.Error(w, err.Error(), 500)
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
			http.Error(w, err.Error(), 500)
			return
		}
		if err := os.Rename(tmpPath, req.Path); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"success":true}`))
	}))

	// 8. Create File / Folder
	http.HandleFunc("/api/file/create", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var req FileOpReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
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
			http.Error(w, err.Error(), 500)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"success":true}`))
	}))

	// 9. Delete File / Folder
	http.HandleFunc("/api/file/delete", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var req FileOpReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}

		if err := os.RemoveAll(req.Path); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"success":true}`))
	}))

	// 10. Rename File / Folder
	http.HandleFunc("/api/file/rename", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var req FileOpReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}

		if err := os.Rename(req.Path, req.NewPath); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"success":true}`))
	}))

	// 11. Ripgrep Search Engine
	http.HandleFunc("/api/search", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query().Get("q")
		dir := r.URL.Query().Get("dir")
		if dir == "" {
			dir = projectsDir
		}

		cmd := exec.Command("rg", "-n", "--no-heading", query, dir)
		output, err := cmd.Output()

		var matches []SearchMatch
		if err == nil {
			lines := strings.Split(string(output), "\n")
			for _, line := range lines {
				parts := strings.SplitN(line, ":", 3)
				if len(parts) == 3 {
					lineNum := 0
					fmt.Sscanf(parts[1], "%d", &lineNum)
					matches = append(matches, SearchMatch{
						Path:       parts[0],
						LineNumber: lineNum,
						LineText:   strings.TrimSpace(parts[2]),
					})
				}
			}
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(matches)
	}))

	port := "9090"
	fmt.Printf("⚡ antiGem Go IDE Daemon running on 127.0.0.1:%s\n", port)
	if err := http.ListenAndServe("127.0.0.1:"+port, nil); err != nil {
		fmt.Printf("Error starting server: %v\n", err)
	}
}

func buildFileTree(dir string, currentDepth int, maxDepth int) ([]FileNode, error) {
	if currentDepth > maxDepth {
		return nil, nil
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}

	var nodes []FileNode
	for _, entry := range entries {
		name := entry.Name()
		// Ignore hidden files / git metadata
		if strings.HasPrefix(name, ".") || name == "node_modules" || name == "build" || name == "target" {
			continue
		}

		fullPath := filepath.Join(dir, name)
		info, _ := entry.Info()
		var size int64
		if info != nil {
			size = info.Size()
		}

		node := FileNode{
			Name:  name,
			Path:  fullPath,
			IsDir: entry.IsDir(),
			Size:  size,
		}

		if entry.IsDir() {
			children, _ := buildFileTree(fullPath, currentDepth+1, maxDepth)
			node.Children = children
		}

		nodes = append(nodes, node)
	}

	// Sort directories first, then files alphabetically
	sort.Slice(nodes, func(i, j int) bool {
		if nodes[i].IsDir != nodes[j].IsDir {
			return nodes[i].IsDir
		}
		return nodes[i].Name < nodes[j].Name
	})

	return nodes, nil
}
