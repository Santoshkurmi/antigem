package handlers

import (
	"bytes"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// The app's diagnostics for a failed Antigravity voice call: the agy hub's own view of it. The app alone sees only
// how its connection ended (e.g. a bare EOFException); the hub writes why it refused or dropped a call to its log
// ("[voice-direct] /voice/ws rejected: … reason=…", "[voice-rap] …"), and a crash shows in its output.

type hubLogVoiceLines struct {
	File       string   `json:"file"`
	ModifiedMs int64    `json:"modified_ms"`
	Lines      []string `json:"lines"`
}

const (
	hubLogTailBytes   = 4 << 20 // read at most the last 4 MB of a log
	hubLogLineMaxLen  = 1500
	hubLogDefaultTake = 30
)

// AgyHubDiagHandler: GET /api/agy/hub/diag?voice=N → the hub process's state (running, started, last exit, crash
// output, last output lines) and the last N voice lines of its two newest log files (two: when the hub restarted,
// the failed call is in the older one).
func (h *Handler) AgyHubDiagHandler(w http.ResponseWriter, r *http.Request) {
	take := hubLogDefaultTake
	if v, err := strconv.Atoi(r.URL.Query().Get("voice")); err == nil && v > 0 && v <= 200 {
		take = v
	}
	resp := map[string]interface{}{"success": true}
	if h.HubManager != nil {
		resp["hub"] = h.HubManager.Diagnostics()
	}
	appData := ""
	if h.HubManager != nil && h.HubManager.AppDataDir != "" {
		appData = h.HubManager.AppDataDir
	} else if h.Cfg != nil {
		appData = h.Cfg.AppDataDir
	}
	if appData != "" {
		logDir := filepath.Join(appData, "log")
		resp["log_dir"] = logDir
		files, err := hubVoiceLogLines(logDir, 2, take)
		if err != nil {
			resp["log_error"] = err.Error()
		}
		resp["voice_log"] = files
	}
	writeJSON(w, http.StatusOK, resp)
}

func hubVoiceLogLines(dir string, files, take int) ([]hubLogVoiceLines, error) {
	matches, err := filepath.Glob(filepath.Join(dir, "cli-*.log"))
	if err != nil {
		return nil, err
	}
	type entry struct {
		path string
		mod  int64
	}
	var list []entry
	for _, p := range matches {
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
			list = append(list, entry{p, fi.ModTime().UnixMilli()})
		}
	}
	sort.Slice(list, func(i, j int) bool { return list[i].mod > list[j].mod })
	if len(list) > files {
		list = list[:files]
	}
	out := []hubLogVoiceLines{}
	for _, e := range list {
		lines, err := voiceLinesOf(e.path, take)
		if err != nil {
			lines = []string{"(could not read: " + err.Error() + ")"}
		}
		out = append(out, hubLogVoiceLines{File: filepath.Base(e.path), ModifiedMs: e.mod, Lines: lines})
	}
	return out, nil
}

// voiceLinesOf returns the last [take] lines of the log at [path] about voice calls.
func voiceLinesOf(path string, take int) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return nil, err
	}
	start := int64(0)
	if fi.Size() > hubLogTailBytes {
		start = fi.Size() - hubLogTailBytes
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return nil, err
	}
	data, err := io.ReadAll(f)
	if err != nil {
		return nil, err
	}
	if start > 0 {
		// the first line was cut by the seek
		if i := bytes.IndexByte(data, '\n'); i >= 0 {
			data = data[i+1:]
		}
	}
	var picked []string
	for _, raw := range strings.Split(string(data), "\n") {
		line := strings.TrimRight(raw, "\r")
		if !strings.Contains(line, "[voice") && !strings.Contains(line, "/voice/") {
			continue
		}
		if len(line) > hubLogLineMaxLen {
			line = line[:hubLogLineMaxLen] + "…"
		}
		picked = append(picked, line)
	}
	if len(picked) > take {
		picked = picked[len(picked)-take:]
	}
	return picked, nil
}
