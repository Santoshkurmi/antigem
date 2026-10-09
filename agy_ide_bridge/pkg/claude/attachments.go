package claude

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Attachments of Claude chats are saved as files under one root (per chat folder) and the message tells Claude
// their paths, so it reads them with its own tools (any file type, not just what fits inline) and the app can show
// and open them again when the chat is reopened. Every claude process gets the root as an --add-dir.

const maxAttachmentBytes = 64 << 20

var unsafeNameChars = regexp.MustCompile(`[^A-Za-z0-9._ -]+`)

func (m *Manager) attachmentsRoot() string {
	return filepath.Join(m.HomeDir, ".antigem", "claude-attachments")
}

// handleAttachments: POST saves one file for a chat; GET /attachments/raw?path= returns a saved file.
func (m *Manager) handleAttachments(w http.ResponseWriter, r *http.Request, rest string) {
	switch {
	case rest == "" && r.Method == http.MethodPost:
		m.saveAttachment(w, r)
	case rest == "raw" && r.Method == http.MethodGet:
		m.serveAttachment(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown attachments endpoint"})
	}
}

func (m *Manager) saveAttachment(w http.ResponseWriter, r *http.Request) {
	var req struct {
		SessionID string `json:"session_id"`
		Name      string `json:"name"`
		MimeType  string `json:"mime_type"`
		Data      string `json:"data"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxAttachmentBytes*4/3+4096)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "bad request: " + err.Error()})
		return
	}
	if !sessionIDRe.MatchString(req.SessionID) {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid session id"})
		return
	}
	data, err := base64.StdEncoding.DecodeString(req.Data)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "data is not base64"})
		return
	}
	dir := filepath.Join(m.attachmentsRoot(), req.SessionID)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	path := uniquePath(dir, cleanFileName(req.Name))
	if err := os.WriteFile(path, data, 0o600); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": true, "path": path, "name": filepath.Base(path), "size": len(data), "mime_type": req.MimeType,
	})
}

func (m *Manager) serveAttachment(w http.ResponseWriter, r *http.Request) {
	root := m.attachmentsRoot()
	path := filepath.Clean(r.URL.Query().Get("path"))
	if !strings.HasPrefix(path, root+string(os.PathSeparator)) {
		writeJSON(w, http.StatusForbidden, map[string]interface{}{"success": false, "error": "not an attachment"})
		return
	}
	if _, err := os.Stat(path); err != nil {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "attachment no longer exists"})
		return
	}
	http.ServeFile(w, r, path)
}

// removeAttachments deletes a chat's saved attachments (the chat is deleted).
func (m *Manager) removeAttachments(sessionID string) {
	if sessionIDRe.MatchString(sessionID) {
		_ = os.RemoveAll(filepath.Join(m.attachmentsRoot(), sessionID))
	}
}

// cleanFileName keeps a readable, path-safe name (no separators, no parentheses that the message format uses).
func cleanFileName(name string) string {
	base := filepath.Base(strings.TrimSpace(name))
	base = unsafeNameChars.ReplaceAllString(base, "_")
	base = strings.Trim(base, ". ")
	if base == "" {
		base = "attachment"
	}
	if len(base) > 120 {
		ext := filepath.Ext(base)
		base = base[:120-len(ext)] + ext
	}
	return base
}

func uniquePath(dir, name string) string {
	path := filepath.Join(dir, name)
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 2; ; i++ {
		if _, err := os.Stat(path); os.IsNotExist(err) {
			return path
		}
		path = filepath.Join(dir, fmt.Sprintf("%s-%d%s", stem, i, ext))
	}
}
