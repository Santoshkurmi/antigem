package claude

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// --- settings.json --------------------------------------------------------------------------------

func (m *Manager) settingsPath() string { return filepath.Join(m.HomeDir, ".claude", "settings.json") }

// defaultPermissionMode is the mode new Claude processes start in: settings.json `permissions.defaultMode` when the
// user set one, otherwise Auto. Bypass is never returned (the app does not skip permission checks).
func (m *Manager) defaultPermissionMode() string {
	var s struct {
		Permissions struct {
			DefaultMode string `json:"defaultMode"`
		} `json:"permissions"`
	}
	if raw, err := os.ReadFile(m.settingsPath()); err == nil && json.Unmarshal(raw, &s) == nil {
		switch mode := s.Permissions.DefaultMode; mode {
		case "", "bypassPermissions":
		default:
			return mode
		}
	}
	return "auto"
}

// handleSandbox reports whether Claude Code's command sandbox can run here (bubblewrap + socat, and bubblewrap
// actually able to start a container, which proot usually is not) and whether settings.json enables it.
func (m *Manager) handleSandbox(w http.ResponseWriter, r *http.Request) {
	resp := map[string]interface{}{"success": true, "available": false}
	var s struct {
		Sandbox struct {
			Enabled bool `json:"enabled"`
		} `json:"sandbox"`
	}
	if raw, err := os.ReadFile(m.settingsPath()); err == nil && json.Unmarshal(raw, &s) == nil {
		resp["enabled"] = s.Sandbox.Enabled
	}
	bwrap, err := exec.LookPath("bwrap")
	if err != nil {
		resp["reason"] = "bubblewrap (bwrap) is not installed"
		writeJSON(w, http.StatusOK, resp)
		return
	}
	if _, err := exec.LookPath("socat"); err != nil {
		resp["reason"] = "socat is not installed"
		writeJSON(w, http.StatusOK, resp)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, bwrap, "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--unshare-net", "true").CombinedOutput()
	if err != nil {
		reason := strings.TrimSpace(string(out))
		if reason == "" {
			reason = err.Error()
		}
		resp["reason"] = "bubblewrap cannot create a sandbox here: " + reason
		writeJSON(w, http.StatusOK, resp)
		return
	}
	resp["available"] = true
	writeJSON(w, http.StatusOK, resp)
}

func (m *Manager) handleSettings(w http.ResponseWriter, r *http.Request) {
	path := m.settingsPath()
	switch r.Method {
	case http.MethodGet:
		settings := map[string]interface{}{}
		if data, err := os.ReadFile(path); err == nil && len(bytes.TrimSpace(data)) > 0 {
			if err := json.Unmarshal(data, &settings); err != nil {
				writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "path": path, "error": "settings.json is not valid JSON: " + err.Error()})
				return
			}
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": path, "settings": settings})
	case http.MethodPut:
		var body struct {
			Settings map[string]interface{} `json:"settings"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Settings == nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "settings object required"})
			return
		}
		if err := writeJSONFile(path, body.Settings); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		m.invalidateCaches()
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": path})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "GET or PUT"})
	}
}

func writeJSONFile(path string, v interface{}) error {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	if old, err := os.ReadFile(path); err == nil {
		_ = os.WriteFile(path+".bak", old, 0600)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, append(data, '\n'), 0600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// --- CLAUDE.md memory -----------------------------------------------------------------------------

func (m *Manager) memoryPath(scope, cwd string) (string, error) {
	switch scope {
	case "", "user":
		return filepath.Join(m.HomeDir, ".claude", "CLAUDE.md"), nil
	case "project":
		if cwd == "" || !filepath.IsAbs(cwd) {
			return "", fmt.Errorf("project memory needs an absolute cwd")
		}
		if fi, err := os.Stat(cwd); err != nil || !fi.IsDir() {
			return "", fmt.Errorf("project folder not found: %s", cwd)
		}
		return filepath.Join(cwd, "CLAUDE.md"), nil
	}
	return "", fmt.Errorf("unknown scope %q", scope)
}

func (m *Manager) handleMemory(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	path, err := m.memoryPath(q.Get("scope"), q.Get("cwd"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	switch r.Method {
	case http.MethodGet:
		data, err := os.ReadFile(path)
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": path, "exists": err == nil, "content": string(data)})
	case http.MethodPut:
		var body struct {
			Content string `json:"content"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "content required"})
			return
		}
		_ = os.MkdirAll(filepath.Dir(path), 0755)
		if err := os.WriteFile(path, []byte(body.Content), 0644); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": path})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "GET or PUT"})
	}
}

// --- MCP servers ----------------------------------------------------------------------------------

type mcpAddRequest struct {
	Name      string            `json:"name"`
	Scope     string            `json:"scope"`     // local | user | project
	Transport string            `json:"transport"` // stdio | http | sse
	Command   string            `json:"command"`
	Args      []string          `json:"args"`
	Env       map[string]string `json:"env"`
	URL       string            `json:"url"`
	Headers   map[string]string `json:"headers"`
	Cwd       string            `json:"cwd"`
}

var safeNameRe = regexp.MustCompile(`^[A-Za-z0-9_.@:/-]+$`)

func (m *Manager) handleMcp(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	switch r.Method {
	case http.MethodGet:
		resps, err := m.oneShot(q.Get("cwd"), 60*time.Second, map[string]interface{}{"subtype": "mcp_status"})
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		var status struct {
			McpServers json.RawMessage `json:"mcpServers"`
		}
		_ = json.Unmarshal(resps[0], &status)
		if status.McpServers == nil {
			status.McpServers = json.RawMessage("[]")
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "servers": status.McpServers})
	case http.MethodPost:
		var req mcpAddRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || !safeNameRe.MatchString(req.Name) {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "valid name required"})
			return
		}
		scope := req.Scope
		if scope == "" {
			scope = "user"
		}
		transport := req.Transport
		if transport == "" {
			transport = "stdio"
		}
		args := []string{"mcp", "add", "--scope", scope, "--transport", transport}
		if transport == "stdio" {
			if strings.TrimSpace(req.Command) == "" {
				writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "command required"})
				return
			}
			for _, k := range sortedKeys(req.Env) {
				args = append(args, "--env", k+"="+req.Env[k])
			}
			args = append(args, "--", req.Name, req.Command)
			args = append(args, req.Args...)
		} else {
			if strings.TrimSpace(req.URL) == "" {
				writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "url required"})
				return
			}
			for _, k := range sortedKeys(req.Headers) {
				args = append(args, "--header", k+": "+req.Headers[k])
			}
			args = append(args, "--", req.Name, req.URL)
		}
		m.runAndReply(w, req.Cwd, 60*time.Second, args...)
	case http.MethodDelete:
		name := q.Get("name")
		if !safeNameRe.MatchString(name) {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "valid name required"})
			return
		}
		args := []string{"mcp", "remove"}
		if s := q.Get("scope"); s != "" {
			args = append(args, "--scope", s)
		}
		args = append(args, "--", name)
		m.runAndReply(w, q.Get("cwd"), 60*time.Second, args...)
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "GET, POST or DELETE"})
	}
}

func sortedKeys(mp map[string]string) []string {
	keys := make([]string, 0, len(mp))
	for k := range mp {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// runAndReply runs `claude <args>` in cwd and replies with its combined output.
func (m *Manager) runAndReply(w http.ResponseWriter, cwd string, timeout time.Duration, args ...string) {
	out, err := m.runCLIIn(cwd, timeout, args...)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": strings.TrimSpace(string(out) + "\n" + err.Error()), "output": string(out)})
		return
	}
	m.invalidateCaches()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "output": string(out)})
}

func (m *Manager) runCLIIn(cwd string, timeout time.Duration, args ...string) ([]byte, error) {
	bin, err := m.ResolveBinary()
	if err != nil {
		return nil, err
	}
	if fi, err := os.Stat(cwd); cwd == "" || err != nil || !fi.IsDir() {
		cwd = m.HomeDir
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Dir = cwd
	cmd.Env = os.Environ()
	cmd.Stdin = nil
	return cmd.CombinedOutput()
}

// --- plugins & marketplaces -----------------------------------------------------------------------

func (m *Manager) handlePlugins(w http.ResponseWriter, r *http.Request, action string) {
	switch {
	case action == "" && r.Method == http.MethodGet:
		out, err := m.runCLIIn("", 120*time.Second, "plugin", "list", "--json", "--available")
		var plugins json.RawMessage
		if err == nil && json.Valid(bytes.TrimSpace(out)) {
			plugins = bytes.TrimSpace(out)
		} else {
			plugins = json.RawMessage(`{"installed":[],"available":[]}`)
		}
		mout, merr := m.runCLIIn("", 60*time.Second, "plugin", "marketplace", "list", "--json")
		var markets json.RawMessage = json.RawMessage("[]")
		if merr == nil && json.Valid(bytes.TrimSpace(mout)) {
			markets = bytes.TrimSpace(mout)
		}
		resp := map[string]interface{}{"success": err == nil, "plugins": plugins, "marketplaces": markets}
		if err != nil {
			resp["error"] = strings.TrimSpace(string(out) + "\n" + err.Error())
		}
		writeJSON(w, http.StatusOK, resp)
	case r.Method == http.MethodPost:
		var body struct {
			Plugin string `json:"plugin"`
			Scope  string `json:"scope"`
			Source string `json:"source"`
			Name   string `json:"name"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		var args []string
		switch action {
		case "install", "uninstall", "enable", "disable", "update":
			if !safeNameRe.MatchString(body.Plugin) {
				writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "plugin id required"})
				return
			}
			args = []string{"plugin", action}
			if body.Scope != "" && action != "update" {
				args = append(args, "--scope", body.Scope)
			}
			if action == "install" || action == "uninstall" {
				args = append(args, "--yes")
			}
			args = append(args, body.Plugin)
		case "marketplace-add":
			if strings.TrimSpace(body.Source) == "" {
				writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "source required"})
				return
			}
			args = []string{"plugin", "marketplace", "add", strings.TrimSpace(body.Source)}
		case "marketplace-remove", "marketplace-update":
			args = []string{"plugin", "marketplace", strings.TrimPrefix(action, "marketplace-")}
			if body.Name != "" {
				if !safeNameRe.MatchString(body.Name) {
					writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid marketplace name"})
					return
				}
				args = append(args, body.Name)
			}
		default:
			writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown plugin action"})
			return
		}
		m.runAndReply(w, "", 300*time.Second, args...)
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown plugin endpoint"})
	}
}

// --- CLI install / update -------------------------------------------------------------------------

type cliJob struct {
	mu       sync.Mutex
	Kind     string    `json:"kind"`
	Running  bool      `json:"running"`
	ExitCode *int      `json:"exit_code,omitempty"`
	Log      string    `json:"log"`
	Started  time.Time `json:"started"`
}

func (j *cliJob) Write(p []byte) (int, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.Log += string(p)
	if len(j.Log) > 64<<10 {
		j.Log = j.Log[len(j.Log)-64<<10:]
	}
	return len(p), nil
}

func (j *cliJob) snapshot() map[string]interface{} {
	j.mu.Lock()
	defer j.mu.Unlock()
	return map[string]interface{}{"kind": j.Kind, "running": j.Running, "exit_code": j.ExitCode, "log": j.Log, "started": j.Started}
}

func (m *Manager) handleCLIJob(w http.ResponseWriter, r *http.Request, action string) {
	m.jobMu.Lock()
	job := m.job
	m.jobMu.Unlock()
	if r.Method == http.MethodGet {
		if job == nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": nil})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": job.snapshot()})
		return
	}
	if job != nil {
		job.mu.Lock()
		running := job.Running
		job.mu.Unlock()
		if running {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "another install/update is running"})
			return
		}
	}
	var cmd *exec.Cmd
	switch action {
	case "install":
		cmd = exec.Command("bash", "-lc", "curl -fsSL https://claude.ai/install.sh | bash")
	case "update":
		bin, err := m.ResolveBinary()
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		cmd = exec.Command(bin, "update")
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown cli action"})
		return
	}
	cmd.Dir = m.HomeDir
	cmd.Env = os.Environ()
	nj := &cliJob{Kind: action, Running: true, Started: time.Now()}
	cmd.Stdout = nj
	cmd.Stderr = nj
	if err := cmd.Start(); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	m.jobMu.Lock()
	m.job = nj
	m.jobMu.Unlock()
	go func() {
		_ = cmd.Wait()
		code := 0
		if cmd.ProcessState != nil {
			code = cmd.ProcessState.ExitCode()
		}
		nj.mu.Lock()
		nj.Running = false
		nj.ExitCode = &code
		nj.mu.Unlock()
		m.invalidateCaches()
	}()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": nj.snapshot()})
}
