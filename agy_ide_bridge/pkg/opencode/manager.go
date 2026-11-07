// Package opencode runs OpenCode (https://opencode.ai) for the app.
//
// One `opencode serve` process serves every OpenCode chat (unlike Claude Code, which needs a process per chat). The
// bridge starts it on demand on a loopback port with a random password, restarts it when it exits, and relays the
// app's requests to its v2 HTTP API (/api/opencode/x/... → /api/...), including the server-sent event stream. The
// app never sees the password; the bridge adds it to every relayed request.
//
// Custom providers (any OpenAI- or Anthropic-compatible endpoint) are kept by the bridge and handed to OpenCode as an
// extra config file (OPENCODE_CONFIG), so the user's own ~/.config/opencode config is never rewritten.
package opencode

import (
	"bufio"
	"context"
	"crypto/rand"
	"debug/elf"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
)

// startTimeout: how long a cold `opencode serve` may take to answer (it is a ~250 MB Bun binary; a phone is slow).
const startTimeout = 45 * time.Second

// Manager owns the OpenCode server process and serves /api/opencode/.
type Manager struct {
	HomeDir      string
	WorkspaceDir string
	// BinPath is the --opencode-bin flag ("" = look it up).
	BinPath string

	mu        sync.Mutex
	cmd       *exec.Cmd
	exited    chan struct{}
	port      int
	password  string
	starting  chan struct{} // closed when the current start attempt is over
	startErr  error
	startedAt time.Time
	logs      []string

	verMu      sync.Mutex
	verBin     string
	verModTime time.Time
	version    string

	provMu sync.Mutex

	jobMu sync.Mutex
	job   *installJob
}

func NewManager(homeDir, workspaceDir, binPath string) *Manager {
	binPath = strings.TrimSpace(binPath)
	// the app passes paths like "~/.opencode/bin/opencode" quoted, so the shell does not expand the tilde
	if binPath == "~" || strings.HasPrefix(binPath, "~/") {
		binPath = filepath.Join(homeDir, strings.TrimPrefix(binPath, "~"))
	}
	return &Manager{HomeDir: homeDir, WorkspaceDir: workspaceDir, BinPath: binPath}
}

// dataDir holds the bridge's OpenCode files (custom providers and the generated config).
func (m *Manager) dataDir() string { return filepath.Join(m.HomeDir, ".antigem_bridge", "opencode") }

func (m *Manager) configPath() string { return filepath.Join(m.dataDir(), "opencode.json") }

// ResolveBinary finds the opencode executable: the flag, $OPENCODE_BIN, PATH, then the installer's usual folders
// (the official install script puts it in ~/.opencode/bin, which is often not on the bridge's PATH).
func (m *Manager) ResolveBinary() (string, error) {
	candidates := []string{}
	if m.BinPath != "" {
		candidates = append(candidates, m.BinPath)
	}
	if env := os.Getenv("OPENCODE_BIN"); env != "" {
		candidates = append(candidates, env)
	}
	if p, err := exec.LookPath("opencode"); err == nil {
		candidates = append(candidates, p)
	}
	candidates = append(candidates,
		filepath.Join(m.HomeDir, ".opencode", "bin", "opencode"),
		filepath.Join(m.HomeDir, ".local", "bin", "opencode"),
		filepath.Join(m.HomeDir, ".bun", "bin", "opencode"),
		filepath.Join(m.HomeDir, ".npm-global", "bin", "opencode"),
		"/usr/local/bin/opencode",
		"/usr/bin/opencode",
	)
	for _, c := range candidates {
		if !strings.Contains(c, "/") {
			if p, err := exec.LookPath(c); err == nil {
				return p, nil
			}
			continue
		}
		if fi, err := os.Stat(c); err == nil && !fi.IsDir() {
			return c, nil
		}
	}
	return "", errors.New("opencode not found (install it with `curl -fsSL https://opencode.ai/v2/install | bash`, or pass --opencode-bin)")
}

// termuxPrefix is Termux's $PREFIX (…/files/usr), or "" when not on Android.
func (m *Manager) termuxPrefix() string {
	if runtime.GOOS != "android" {
		return ""
	}
	if p := os.Getenv("PREFIX"); p != "" {
		return p
	}
	return filepath.Join(filepath.Dir(m.HomeDir), "usr")
}

// glibcInterp reports whether bin is an ELF executable that asks for the glibc loader (the official Linux build).
func glibcInterp(bin string) bool {
	f, err := elf.Open(bin)
	if err != nil {
		return false
	}
	defer f.Close()
	for _, p := range f.Progs {
		if p.Type == elf.PT_INTERP {
			buf := make([]byte, p.Filesz)
			if _, err := p.ReadAt(buf, 0); err == nil {
				return strings.Contains(string(buf), "ld-linux")
			}
		}
	}
	return false
}

// command builds the exec.Cmd for bin. On Android (Termux) the official OpenCode build needs glibc, which Android
// does not have: like the app's Claude Code launcher, it is started through the glibc loader the app bundles
// ($PREFIX/glibc/lib), without termux-exec preloaded (a bionic library cannot load into a glibc process).
func (m *Manager) command(ctx context.Context, bin string, args ...string) (*exec.Cmd, error) {
	prefix := m.termuxPrefix()
	var cmd *exec.Cmd
	if prefix != "" && glibcInterp(bin) {
		lib := filepath.Join(prefix, "glibc", "lib")
		loader := filepath.Join(lib, "ld-linux-aarch64.so.1")
		if runtime.GOARCH == "amd64" {
			loader = filepath.Join(lib, "ld-linux-x86-64.so.2")
		}
		if _, err := os.Stat(loader); err != nil {
			return nil, fmt.Errorf("opencode needs glibc, but the app's glibc runtime is missing (%s): reopen the app to restore it", loader)
		}
		cmd = exec.CommandContext(ctx, loader, append([]string{"--library-path", lib, bin}, args...)...)
	} else {
		cmd = exec.CommandContext(ctx, bin, args...)
	}
	env := make([]string, 0, len(os.Environ())+4)
	hasTmp := false
	for _, e := range os.Environ() {
		if prefix != "" && strings.HasPrefix(e, "LD_PRELOAD=") {
			continue
		}
		if strings.HasPrefix(e, "TMPDIR=") && len(e) > len("TMPDIR=") {
			hasTmp = true
		}
		env = append(env, e)
	}
	// Android has no /tmp; OpenCode keeps its temp files in $TMPDIR
	if !hasTmp && prefix != "" {
		env = append(env, "TMPDIR="+filepath.Join(prefix, "tmp"))
	}
	cmd.Env = env
	return cmd, nil
}

// Version runs `opencode --version` (cached until the binary changes).
func (m *Manager) Version(bin string) string {
	fi, err := os.Stat(bin)
	if err != nil {
		return ""
	}
	m.verMu.Lock()
	defer m.verMu.Unlock()
	if m.verBin == bin && m.verModTime.Equal(fi.ModTime()) && m.version != "" {
		return m.version
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd, err := m.command(ctx, bin, "--version")
	if err != nil {
		return ""
	}
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	v := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(string(out)), "opencode"))
	m.verBin, m.verModTime, m.version = bin, fi.ModTime(), strings.TrimSpace(v)
	return m.version
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

// running reports a live process (caller holds mu).
func (m *Manager) runningLocked() bool {
	if m.cmd == nil || m.exited == nil {
		return false
	}
	select {
	case <-m.exited:
		return false
	default:
		return true
	}
}

// Running reports whether the server process is up.
func (m *Manager) Running() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.runningLocked() && m.starting == nil
}

func (m *Manager) addLog(line string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	// the password is printed at start; it never leaves the bridge
	if strings.Contains(line, "password") {
		return
	}
	m.logs = append(m.logs, line)
	if len(m.logs) > 200 {
		m.logs = m.logs[len(m.logs)-200:]
	}
}

func (m *Manager) recentLogs(n int) []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.logs) <= n {
		return append([]string(nil), m.logs...)
	}
	return append([]string(nil), m.logs[len(m.logs)-n:]...)
}

// Ensure starts the server when it is not running and waits until it answers.
func (m *Manager) Ensure(ctx context.Context) error {
	for {
		m.mu.Lock()
		if m.starting != nil {
			wait := m.starting
			m.mu.Unlock()
			select {
			case <-wait:
			case <-ctx.Done():
				return ctx.Err()
			}
			m.mu.Lock()
			err := m.startErr
			up := m.runningLocked()
			m.mu.Unlock()
			if up {
				return nil
			}
			if err != nil {
				return err
			}
			continue
		}
		if m.runningLocked() {
			m.mu.Unlock()
			return nil
		}
		done := make(chan struct{})
		m.starting = done
		m.mu.Unlock()

		err := m.start()

		m.mu.Lock()
		m.startErr = err
		m.starting = nil
		m.mu.Unlock()
		close(done)
		return err
	}
}

func (m *Manager) start() error {
	bin, err := m.ResolveBinary()
	if err != nil {
		return err
	}
	if err := m.writeConfig(); err != nil {
		return fmt.Errorf("writing the OpenCode config: %w", err)
	}
	port, err := freePort()
	if err != nil {
		return err
	}
	password := randomHex(24)
	cmd, err := m.command(context.Background(), bin, "serve", "--hostname", "127.0.0.1", "--port", fmt.Sprint(port))
	if err != nil {
		return err
	}
	dir := m.WorkspaceDir
	if fi, err := os.Stat(dir); dir == "" || err != nil || !fi.IsDir() {
		dir = m.HomeDir
	}
	cmd.Dir = dir
	cmd.Env = append(cmd.Env,
		"OPENCODE_SERVER_PASSWORD="+password,
		"OPENCODE_CONFIG="+m.configPath(),
		"OPENCODE_DISABLE_AUTOUPDATE=1",
	)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("starting opencode: %w", err)
	}
	exited := make(chan struct{})
	go func() {
		sc := bufio.NewScanner(out)
		sc.Buffer(make([]byte, 64<<10), 1<<20)
		for sc.Scan() {
			m.addLog(sc.Text())
		}
	}()
	go func() {
		_ = cmd.Wait()
		close(exited)
	}()

	m.mu.Lock()
	m.cmd, m.exited, m.port, m.password, m.startedAt = cmd, exited, port, password, time.Now()
	m.mu.Unlock()

	deadline := time.Now().Add(startTimeout)
	client := &http.Client{Timeout: 3 * time.Second}
	for time.Now().Before(deadline) {
		select {
		case <-exited:
			return fmt.Errorf("opencode exited while starting: %s", strings.Join(m.recentLogs(6), " | "))
		default:
		}
		req, _ := http.NewRequest(http.MethodGet, fmt.Sprintf("http://127.0.0.1:%d/api/info", port), nil)
		req.SetBasicAuth("opencode", password)
		if resp, err := client.Do(req); err == nil {
			_, _ = io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return nil
			}
		}
		time.Sleep(300 * time.Millisecond)
	}
	m.Stop()
	return errors.New("opencode did not start in time")
}

// Stop ends the server and everything it started (shells, MCP servers).
func (m *Manager) Stop() {
	m.mu.Lock()
	cmd, exited := m.cmd, m.exited
	m.mu.Unlock()
	if cmd == nil || cmd.Process == nil {
		return
	}
	pgid := cmd.Process.Pid
	_ = syscall.Kill(-pgid, syscall.SIGTERM)
	select {
	case <-exited:
	case <-time.After(3 * time.Second):
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
		<-exited
	}
}

// StopAll is called when the bridge shuts down.
func (m *Manager) StopAll() { m.Stop() }

func (m *Manager) target() (*url.URL, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return &url.URL{Scheme: "http", Host: fmt.Sprintf("127.0.0.1:%d", m.port)}, m.password
}

// Call makes one request to the OpenCode API (path starts with /api/), starting the server if needed.
func (m *Manager) Call(ctx context.Context, method, path string, body interface{}, out interface{}) error {
	if err := m.Ensure(ctx); err != nil {
		return err
	}
	base, password := m.target()
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = strings.NewReader(string(b))
	}
	req, err := http.NewRequestWithContext(ctx, method, base.String()+path, rd)
	if err != nil {
		return err
	}
	req.SetBasicAuth("opencode", password)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if resp.StatusCode >= 300 {
		return fmt.Errorf("opencode %s %s: HTTP %d %s", method, path, resp.StatusCode, strings.TrimSpace(string(data)))
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

// ServeHTTP routes /api/opencode/.
//
//	GET  /api/opencode/status             installed? version, binary, server running, recent log lines
//	POST /api/opencode/start              start the server (no-op when running)
//	POST /api/opencode/restart            restart the server (reloads providers and config)
//	GET|POST /api/opencode/custom-providers, PUT|DELETE /api/opencode/custom-providers/{id}
//	POST /api/opencode/oauth-callback {"url"}    finishes a browser sign-in that came back to 127.0.0.1 (server mode)
//	GET  /api/opencode/credentials       saved accounts per provider (active or not), without their secrets
//	GET  /api/opencode/mcp; PUT|DELETE /api/opencode/mcp/{name}   MCP servers kept in the bridge's OpenCode config
//	GET  /api/opencode/cli/job; POST /api/opencode/cli/{install|update}   install or update OpenCode (~/.opencode/bin)
//	POST /api/opencode/custom-providers/fetch-models   {"kind","base_url","api_key"|"id"} → model ids of an endpoint
//	*    /api/opencode/x/{path}           relayed to OpenCode's /api/{path} (SSE included: /x/event)
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/opencode")
	switch {
	case path == "/x" || strings.HasPrefix(path, "/x/"):
		m.proxy(w, r, strings.TrimPrefix(path, "/x"))
	case path == "/status" && r.Method == http.MethodGet:
		m.handleStatus(w, r)
	case path == "/start" && r.Method == http.MethodPost:
		if err := m.Ensure(r.Context()); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case path == "/restart" && r.Method == http.MethodPost:
		m.Stop()
		if err := m.Ensure(r.Context()); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case path == "/oauth-callback" && r.Method == http.MethodPost:
		m.handleOAuthCallback(w, r)
	case path == "/credentials" && r.Method == http.MethodGet:
		m.handleCredentials(w, r)
	case path == "/mcp" || strings.HasPrefix(path, "/mcp/"):
		m.handleMcp(w, r, strings.Trim(strings.TrimPrefix(path, "/mcp"), "/"))
	case path == "/cli/job" || strings.HasPrefix(path, "/cli/"):
		m.handleInstall(w, r, strings.TrimPrefix(path, "/cli/"))
	case path == "/custom-providers/fetch-models" && r.Method == http.MethodPost:
		m.handleFetchModels(w, r)
	case path == "/custom-providers" || strings.HasPrefix(path, "/custom-providers/"):
		m.handleCustomProviders(w, r, strings.Trim(strings.TrimPrefix(path, "/custom-providers"), "/"))
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown opencode endpoint"})
	}
}

func (m *Manager) handleStatus(w http.ResponseWriter, r *http.Request) {
	resp := map[string]interface{}{"success": true}
	bin, err := m.ResolveBinary()
	if err != nil {
		resp["installed"] = false
		resp["error"] = err.Error()
		writeJSON(w, http.StatusOK, resp)
		return
	}
	resp["installed"] = true
	resp["binary"] = bin
	resp["version"] = m.Version(bin)
	m.mu.Lock()
	running := m.runningLocked() && m.starting == nil
	starting := m.starting != nil
	if m.startErr != nil && !running {
		resp["error"] = m.startErr.Error()
	}
	if running {
		resp["uptime_seconds"] = int(time.Since(m.startedAt).Seconds())
	}
	m.mu.Unlock()
	resp["running"] = running
	resp["starting"] = starting
	resp["logs"] = m.recentLogs(20)
	writeJSON(w, http.StatusOK, resp)
}

// proxy relays one request to the OpenCode server, adding its password. Streams (the event stream) are flushed as
// they come.
func (m *Manager) proxy(w http.ResponseWriter, r *http.Request, rest string) {
	if err := m.Ensure(r.Context()); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]interface{}{"success": false, "error": err.Error(), "opencode_unavailable": true})
		return
	}
	base, password := m.target()
	rp := &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.Out.URL.Scheme = base.Scheme
			pr.Out.URL.Host = base.Host
			pr.Out.URL.Path = "/api" + rest
			pr.Out.URL.RawPath = ""
			pr.Out.Host = base.Host
			// none of the bridge's own headers (its secret token header included) reach OpenCode
			for name := range pr.Out.Header {
				ln := strings.ToLower(name)
				if strings.HasPrefix(ln, "x-ag") || ln == "authorization" || ln == "cookie" || ln == "origin" || ln == "referer" {
					pr.Out.Header.Del(name)
				}
			}
			pr.Out.SetBasicAuth("opencode", password)
		},
		FlushInterval: -1,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			if errors.Is(err, context.Canceled) {
				return
			}
			writeJSON(w, http.StatusBadGateway, map[string]interface{}{"success": false, "error": "OpenCode did not answer: " + err.Error(), "opencode_unavailable": true})
		},
	}
	rp.ServeHTTP(w, r)
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
