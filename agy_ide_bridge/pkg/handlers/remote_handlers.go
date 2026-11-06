package handlers

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"gemini-server/pkg/buildinfo"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
)

// publicBaseURL rebuilds the address the client used to reach this bridge, so URLs handed back to a
// remote app (attachments, artifacts) work through nginx/Apache/TLS and are not stuck on 127.0.0.1.
func publicBaseURL(r *http.Request, fallbackPort string) string {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	if v := firstHeaderValue(r.Header.Get("X-Forwarded-Proto")); v == "https" || v == "http" {
		scheme = v
	}
	host := firstHeaderValue(r.Header.Get("X-Forwarded-Host"))
	if host == "" {
		host = r.Host
	}
	if host == "" {
		host = "127.0.0.1:" + fallbackPort
	}
	return scheme + "://" + host
}

func firstHeaderValue(v string) string {
	if i := strings.Index(v, ","); i >= 0 {
		v = v[:i]
	}
	return strings.ToLower(strings.TrimSpace(v))
}

// ServerInfoHandler tells a (possibly remote) app what machine it is talking to, so the app can pick
// the right binaries, show the real home directory and know whether the bridge is behind a proxy.
func (h *Handler) ServerInfoHandler(w http.ResponseWriter, r *http.Request) {
	hostname, _ := os.Hostname()
	hubPort := ""
	if h.HubManager != nil {
		hubPort = h.HubManager.HubPort
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"version":      buildinfo.Version(),
		"os":           runtime.GOOS,
		"arch":         runtime.GOARCH,
		"hostname":     hostname,
		"pid":          os.Getpid(),
		"home":         h.Cfg.HomeDir,
		"workspaceDir": h.Cfg.WorkspaceDir,
		"bind":         h.Cfg.Bind,
		"port":         h.Cfg.Port,
		"hubPort":      hubPort,
		"uptime":       time.Since(startTime).Seconds(),
		"viaProxy":     r.Header.Get("X-Forwarded-For") != "" || r.Header.Get("X-Forwarded-Proto") != "",
		"publicBase":   publicBaseURL(r, h.Cfg.Port),
	})
}

// ForwardLoginCallbackHandler finishes the Google sign-in of a hub that runs on a remote machine.
//
// The sign-in redirects the browser to http://localhost:<port>/... . On a phone that address is the
// phone, not the server running the hub. The app lets the user copy that final address and sends it
// here; the bridge replays it against the hub's own localhost callback server.
func (h *Handler) ForwardLoginCallbackHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "POST required"})
		return
	}
	var req struct {
		URL string `json:"url"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.URL) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "Missing url"})
		return
	}

	// Only while a sign-in is in progress (the login URL is announced when the user taps Sign in).
	h.lastLoginURLMu.RLock()
	started := h.lastLoginTime
	h.lastLoginURLMu.RUnlock()
	if started.IsZero() || time.Since(started) > 15*time.Minute {
		writeJSON(w, http.StatusConflict, map[string]interface{}{"success": false, "error": "No sign-in is in progress. Tap Sign in again."})
		return
	}

	target, err := url.Parse(strings.TrimSpace(req.URL))
	if err != nil || target.Scheme != "http" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "Paste the full address starting with http://localhost"})
		return
	}
	hostName := strings.ToLower(target.Hostname())
	if hostName != "localhost" && hostName != "127.0.0.1" && hostName != "::1" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "Only localhost callback addresses can be forwarded"})
		return
	}
	if target.Port() == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "The callback address has no port"})
		return
	}

	// Always dial the loopback of THIS machine, whatever host name was pasted.
	callback := *target
	callback.Host = net.JoinHostPort("127.0.0.1", target.Port())

	client := &http.Client{
		Timeout:       20 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	resp, err := client.Get(callback.String())
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]interface{}{
			"success": false,
			"error":   fmt.Sprintf("The hub's sign-in listener did not answer (%v). Tap Sign in again and paste the new address quickly.", err),
		})
		return
	}
	defer resp.Body.Close()
	snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	ok := resp.StatusCode < 500
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": ok,
		"status":  resp.StatusCode,
		"message": strings.TrimSpace(string(snippet)),
	})
}

var cloneNameRe = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

// GitCloneHandler clones a public https repository into the projects folder of THIS machine and streams git's output
// line by line, ending with `ANTIGEM_CLONED_PATH=<dir>` (success) or `ANTIGEM_EXIT=<code>`. It is what the app's
// "Clone repository" does when the bridge runs on a server and not on the phone.
func (h *Handler) GitCloneHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "POST required"})
		return
	}
	var req struct {
		URL  string `json:"url"`
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "Invalid request body"})
		return
	}
	repo := strings.TrimSpace(req.URL)
	name := strings.TrimSpace(req.Name)
	if !strings.HasPrefix(repo, "https://") {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "Only public https:// URLs are supported"})
		return
	}
	if name == "" {
		name = strings.TrimSuffix(path.Base(strings.TrimRight(repo, "/")), ".git")
	}
	if name == "" || name == "." || name == ".." || !cloneNameRe.MatchString(name) {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "Invalid project name (use letters, numbers, '.', '_' or '-')"})
		return
	}
	target := filepath.Join(h.Cfg.ProjectsBaseDir, name)

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("X-Accel-Buffering", "no") // nginx: do not buffer the live log
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	say := func(line string) {
		_, _ = fmt.Fprintln(w, line)
		if flusher != nil {
			flusher.Flush()
		}
	}

	say("Repository : " + repo)
	say("Target     : " + target)
	if _, err := exec.LookPath("git"); err != nil {
		say("❌ Error: git is not installed on the server.")
		say("ANTIGEM_EXIT=1")
		return
	}
	if fi, err := os.Stat(target); err == nil {
		if !fi.IsDir() {
			say("❌ Error: '" + target + "' already exists and is not a folder.")
			say("ANTIGEM_EXIT=1")
			return
		}
		entries, _ := os.ReadDir(target)
		if len(entries) > 0 {
			say("❌ Error: Folder '" + target + "' already exists and is not empty. Choose another project name.")
			say("ANTIGEM_EXIT=1")
			return
		}
		_ = os.Remove(target)
	}
	_ = os.MkdirAll(h.Cfg.ProjectsBaseDir, 0755)

	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "git", "clone", "--progress", repo, target)
	cmd.Env = append(os.Environ(), "GIT_TERMINAL_PROMPT=0") // public repos only: fail instead of asking for a password
	pipe, err := cmd.StdoutPipe()
	if err != nil {
		say("❌ Error: " + err.Error())
		say("ANTIGEM_EXIT=1")
		return
	}
	cmd.Stderr = cmd.Stdout
	say("[git] git clone --progress " + repo + " " + target)
	if err := cmd.Start(); err != nil {
		say("❌ Error: " + err.Error())
		say("ANTIGEM_EXIT=1")
		return
	}
	// git draws progress with carriage returns: split them into lines
	scanner := bufio.NewScanner(pipe)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	scanner.Split(func(data []byte, atEOF bool) (int, []byte, error) {
		for i, b := range data {
			if b == '\n' || b == '\r' {
				return i + 1, data[:i], nil
			}
		}
		if atEOF && len(data) > 0 {
			return len(data), data, nil
		}
		return 0, nil, nil
	})
	for scanner.Scan() {
		if line := strings.TrimSpace(scanner.Text()); line != "" {
			say(line)
		}
	}
	if err := cmd.Wait(); err != nil {
		code := 1
		if ee, ok := err.(*exec.ExitError); ok {
			code = ee.ExitCode()
		}
		say(fmt.Sprintf("❌ Error: git clone failed (exit %d).", code))
		say(fmt.Sprintf("ANTIGEM_EXIT=%d", code))
		return
	}
	say("✅ Cloned into " + target)
	say("ANTIGEM_CLONED_PATH=" + target)
}
