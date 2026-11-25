package handlers

import (
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// ---------- /api/fs/site ----------

// siteMime covers the files a web page loads, so a module script or stylesheet is never refused for a missing type
// (the system's mime table can be empty on Android).
var siteMime = map[string]string{
	".html": "text/html; charset=utf-8", ".htm": "text/html; charset=utf-8",
	".css": "text/css; charset=utf-8",
	".js":  "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".cjs": "text/javascript; charset=utf-8",
	".json": "application/json; charset=utf-8", ".map": "application/json; charset=utf-8", ".webmanifest": "application/manifest+json",
	".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif",
	".webp": "image/webp", ".avif": "image/avif", ".ico": "image/x-icon", ".bmp": "image/bmp",
	".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf", ".otf": "font/otf",
	".mp3": "audio/mpeg", ".wav": "audio/wav", ".ogg": "audio/ogg", ".m4a": "audio/mp4",
	".mp4": "video/mp4", ".webm": "video/webm", ".mov": "video/quicktime",
	".wasm": "application/wasm", ".txt": "text/plain; charset=utf-8", ".md": "text/plain; charset=utf-8",
	".xml": "application/xml", ".csv": "text/csv; charset=utf-8", ".glsl": "text/plain; charset=utf-8",
	".glb": "model/gltf-binary", ".gltf": "model/gltf+json",
}

// FsSiteHandler serves one file of a web page the chat shows (an html_app block that names an .html file on disk):
// ?root= is the page's folder, ?path= the file inside it, relative ("" = index.html). Nothing outside the folder is
// served: not through "..", not through a symlink that leads out, and no hidden files or folders (.env, .git).
// Every answer carries X-Antigem-Site, so the app can tell "no such file" from a bridge without this route.
func (h *Handler) FsSiteHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Antigem-Site", "1")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		fsFail(w, http.StatusMethodNotAllowed, "use GET")
		return
	}
	q := r.URL.Query()
	root := fsResolve(h, q.Get("root"))
	if root == "/" || root == fsHome(h) {
		fsFail(w, http.StatusForbidden, "a page's folder can't be / or home: put the page in its own folder")
		return
	}
	realRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		fsFail(w, http.StatusNotFound, "folder not found")
		return
	}
	if st, err := os.Stat(realRoot); err != nil || !st.IsDir() {
		fsFail(w, http.StatusNotFound, "folder not found")
		return
	}

	rel := strings.TrimLeft(strings.ReplaceAll(q.Get("path"), "\\", "/"), "/")
	if rel == "" || strings.HasSuffix(rel, "/") {
		rel += "index.html"
	}
	for _, part := range strings.Split(rel, "/") {
		if part == ".." || strings.HasPrefix(part, ".") {
			fsFail(w, http.StatusForbidden, "not served: hidden or outside the page's folder")
			return
		}
	}
	target, err := filepath.EvalSymlinks(filepath.Join(realRoot, filepath.FromSlash(rel)))
	if err != nil {
		fsFail(w, http.StatusNotFound, "file not found")
		return
	}
	if !strings.HasPrefix(target, realRoot+string(filepath.Separator)) {
		fsFail(w, http.StatusForbidden, "not served: outside the page's folder")
		return
	}
	f, err := os.Open(target)
	if err != nil {
		fsFail(w, http.StatusNotFound, "file not found")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.IsDir() {
		fsFail(w, http.StatusNotFound, "not a file")
		return
	}
	ext := strings.ToLower(filepath.Ext(target))
	ct := siteMime[ext]
	if ct == "" {
		ct = mime.TypeByExtension(ext)
	}
	if ct == "" {
		ct = "application/octet-stream"
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, filepath.Base(target), info.ModTime(), f)
}
