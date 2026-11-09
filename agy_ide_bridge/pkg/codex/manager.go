// Package codex runs OpenAI Codex CLI (https://github.com/openai/codex) for the app.
//
// One `codex app-server` process serves every Codex chat (thread). It speaks JSON-RPC over its stdin/stdout — the
// same protocol the official VSCode extension uses — and never opens a port, so nothing but the bridge can talk to
// it. The bridge is its only client and stays connected for as long as the process lives:
//
//   - the app calls protocol methods through POST /api/codex/rpc (the bridge forwards them and returns the result);
//   - every notification and server→client request (approvals, questions) goes into one sequence-numbered buffer that
//     the app follows over the /api/codex/ws WebSocket and replays with ?since= after a reconnect;
//   - approvals wait in the bridge until a client answers them (POST /api/codex/requests/{key}/reply), so a phone
//     that was in the background still sees them.
//
// app-server closes a thread that is idle and has no subscriber, and only sends a thread's events to connections
// subscribed to it. The bridge keeps track of the threads it is subscribed to and re-subscribes (thread/resume)
// before starting a turn on one it is not.
package codex

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	// startTimeout: how long a cold app-server may take to answer `initialize` on a phone.
	startTimeout = 45 * time.Second
	// buffer limits for the event replay (whichever is hit first drops the oldest events)
	maxBufferedEvents = 20000
	maxBufferedBytes  = 48 << 20
	// idleUnsubscribe: threads idle this long with no pending request are released (app-server then unloads them;
	// the bridge re-subscribes when the chat is used again).
	idleUnsubscribe = 30 * time.Minute
)

// clientVersion is reported to app-server in `initialize`.
var clientVersion = "1.0.0"

// notifications the app never needs; app-server does not send them to the bridge at all.
var optOutNotifications = []string{
	"turn/moderationMetadata",
	"rawResponseItem/completed",
	"rawResponse/completed",
}

// server→client requests the app answers; any other one is declined by the bridge right away, so nothing hangs.
var appHandledRequests = map[string]bool{
	"item/commandExecution/requestApproval": true,
	"item/fileChange/requestApproval":       true,
	"item/permissions/requestApproval":      true,
	"item/tool/requestUserInput":            true,
	"mcpServer/elicitation/request":         true,
	"applyPatchApproval":                    true,
	"execCommandApproval":                   true,
}

// methods that need the bridge subscribed to the thread, or its events never reach the app.
var needsSubscription = map[string]bool{
	"turn/start":             true,
	"thread/queue/add":       true,
	"thread/queue/start":     true,
	"thread/compact/start":   true,
	"thread/shellCommand":    true,
	"review/start":           true,
	"thread/settings/update": true,
}

// methods the bridge owns; the app may not send them.
var bridgeOwnedMethods = map[string]bool{
	"initialize":  true,
	"initialized": true,
}

// RPCError is a JSON-RPC error answered by app-server.
type RPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func (e *RPCError) Error() string { return fmt.Sprintf("codex: %s (code %d)", e.Message, e.Code) }

type rpcResult struct {
	result json.RawMessage
	err    error
}

// wireMessage is any JSON-RPC message on app-server's stdout.
type wireMessage struct {
	ID     json.RawMessage `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *RPCError       `json:"error,omitempty"`
}

// PendingRequest is a server→client request (approval, question) waiting for an answer.
type PendingRequest struct {
	Key      string          `json:"key"`
	Method   string          `json:"method"`
	Params   json.RawMessage `json:"params"`
	ThreadID string          `json:"thread_id,omitempty"`
	Seq      int64           `json:"seq"`
	Received time.Time       `json:"received"`
	rawID    json.RawMessage
	gen      int64
}

type event struct {
	seq   int64
	frame []byte
}

// process is one running app-server.
type process struct {
	gen     int64
	cmd     *exec.Cmd
	stdin   io.WriteCloser
	writeMu sync.Mutex
	exited  chan struct{}

	mu      sync.Mutex
	nextID  int64
	calls   map[int64]chan rpcResult
	started time.Time
}

// Manager owns the Codex app-server process and serves /api/codex/.
type Manager struct {
	HomeDir      string
	WorkspaceDir string
	// BinPath is the --codex-bin flag ("" = look it up).
	BinPath string

	mu       sync.Mutex
	proc     *process
	gen      int64
	starting chan struct{} // closed when the current start attempt is over
	startErr error
	initInfo json.RawMessage
	logs     []string

	// event buffer + subscribers (evMu)
	evMu       sync.Mutex
	events     []event
	evBytes    int
	nextSeq    int64
	subs       map[*subscriber]struct{}
	pending    map[string]*PendingRequest // key → request
	pendingIDs map[string]string          // gen:rawID → key
	reqCounter int64
	threads    map[string]time.Time // subscribed thread id → last activity

	verMu      sync.Mutex
	verBin     string
	verModTime time.Time
	version    string

	jobMu sync.Mutex
	job   *installJob

	reaperOnce sync.Once
}

func NewManager(homeDir, workspaceDir, binPath string) *Manager {
	binPath = strings.TrimSpace(binPath)
	// the app passes paths like "~/.local/bin/codex" quoted, so the shell does not expand the tilde
	if binPath == "~" || strings.HasPrefix(binPath, "~/") {
		binPath = filepath.Join(homeDir, strings.TrimPrefix(binPath, "~"))
	}
	return &Manager{
		HomeDir:      homeDir,
		WorkspaceDir: workspaceDir,
		BinPath:      binPath,
		subs:         make(map[*subscriber]struct{}),
		pending:      make(map[string]*PendingRequest),
		pendingIDs:   make(map[string]string),
		threads:      make(map[string]time.Time),
	}
}

// --- binary ------------------------------------------------------------------------------------

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

// termuxNative reports a native Termux environment (not proot Ubuntu, which has its own /etc/os-release), where
// Codex comes from the Termux package repository (`pkg install codex`).
func (m *Manager) termuxNative() bool {
	prefix := m.termuxPrefix()
	if prefix == "" {
		return false
	}
	if _, err := os.Stat("/etc/os-release"); err == nil {
		return false
	}
	_, err := os.Stat(filepath.Join(prefix, "bin", "apt-get"))
	return err == nil
}

// ResolveBinary finds the codex executable: the flag, $CODEX_BIN, PATH, then the usual install folders.
func (m *Manager) ResolveBinary() (string, error) {
	candidates := []string{}
	if m.BinPath != "" {
		candidates = append(candidates, m.BinPath)
	}
	if env := os.Getenv("CODEX_BIN"); env != "" {
		candidates = append(candidates, env)
	}
	if p, err := exec.LookPath("codex"); err == nil {
		candidates = append(candidates, p)
	}
	if prefix := m.termuxPrefix(); prefix != "" {
		candidates = append(candidates, filepath.Join(prefix, "bin", "codex"))
	}
	candidates = append(candidates,
		filepath.Join(m.HomeDir, ".local", "bin", "codex"),
		filepath.Join(m.HomeDir, ".codex", "packages", "standalone", "current", "bin", "codex"),
		filepath.Join(m.HomeDir, ".npm-global", "bin", "codex"),
		"/usr/local/bin/codex",
		"/usr/bin/codex",
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
	if m.termuxNative() {
		return "", errors.New("codex not found (install it with `pkg install codex`, or pass --codex-bin)")
	}
	return "", errors.New("codex not found (install it from the app, or pass --codex-bin)")
}

// command builds the exec.Cmd for bin. The Termux package is a normal Android build (it relies on termux-exec,
// which stays preloaded, to run /bin/bash); the official Linux build is static, so it runs in proot as is.
func (m *Manager) command(ctx context.Context, bin string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, bin, args...)
	env := os.Environ()
	hasTmp := false
	for _, e := range env {
		if strings.HasPrefix(e, "TMPDIR=") && len(e) > len("TMPDIR=") {
			hasTmp = true
		}
	}
	// Android has no /tmp
	if prefix := m.termuxPrefix(); !hasTmp && prefix != "" {
		env = append(env, "TMPDIR="+filepath.Join(prefix, "tmp"))
	}
	cmd.Env = env
	return cmd
}

// Version runs `codex --version` (cached until the binary changes).
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
	out, err := m.command(ctx, bin, "--version").Output()
	if err != nil {
		return ""
	}
	v := strings.TrimSpace(string(out))
	v = strings.TrimSpace(strings.TrimPrefix(v, "codex-cli"))
	m.verBin, m.verModTime, m.version = bin, fi.ModTime(), v
	return m.version
}

// --- process lifecycle -------------------------------------------------------------------------

func (p *process) alive() bool {
	select {
	case <-p.exited:
		return false
	default:
		return true
	}
}

func (m *Manager) liveProc() *process {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.proc != nil && m.proc.alive() && m.starting == nil {
		return m.proc
	}
	return nil
}

// Running reports whether app-server is up and initialized.
func (m *Manager) Running() bool { return m.liveProc() != nil }

func (m *Manager) addLog(line string) {
	m.mu.Lock()
	defer m.mu.Unlock()
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

// Ensure starts app-server when it is not running and waits until it is initialized.
func (m *Manager) Ensure(ctx context.Context) (*process, error) {
	m.reaperOnce.Do(func() { go m.reaper() })
	for {
		m.mu.Lock()
		if m.starting != nil {
			wait := m.starting
			m.mu.Unlock()
			select {
			case <-wait:
			case <-ctx.Done():
				return nil, ctx.Err()
			}
			m.mu.Lock()
			err, p := m.startErr, m.proc
			m.mu.Unlock()
			if p != nil && p.alive() {
				return p, nil
			}
			if err != nil {
				return nil, err
			}
			continue
		}
		if m.proc != nil && m.proc.alive() {
			p := m.proc
			m.mu.Unlock()
			return p, nil
		}
		done := make(chan struct{})
		m.starting = done
		m.mu.Unlock()

		p, err := m.start()

		m.mu.Lock()
		m.startErr = err
		m.starting = nil
		m.mu.Unlock()
		close(done)
		if err != nil {
			return nil, err
		}
		m.broadcastState("spawned", "")
		return p, nil
	}
}

func (m *Manager) start() (*process, error) {
	bin, err := m.ResolveBinary()
	if err != nil {
		return nil, err
	}
	cmd := m.command(context.Background(), bin, "app-server", "--listen", "stdio://")
	dir := m.WorkspaceDir
	if fi, err := os.Stat(dir); dir == "" || err != nil || !fi.IsDir() {
		dir = m.HomeDir
	}
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("starting codex: %w", err)
	}

	m.mu.Lock()
	m.gen++
	p := &process{gen: m.gen, cmd: cmd, stdin: stdin, exited: make(chan struct{}), calls: make(map[int64]chan rpcResult), started: time.Now()}
	m.proc = p
	m.mu.Unlock()

	go func() {
		sc := bufio.NewScanner(stderr)
		sc.Buffer(make([]byte, 64<<10), 1<<20)
		for sc.Scan() {
			m.addLog(sc.Text())
		}
	}()
	readerDone := make(chan struct{})
	go func() {
		defer close(readerDone)
		m.readLoop(p, stdout)
	}()
	go func() {
		// Wait closes the pipes, so it may only run once everything on stdout has been read
		<-readerDone
		_ = cmd.Wait()
		close(p.exited)
		m.onExit(p)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), startTimeout)
	defer cancel()
	info, err := m.call(ctx, p, "initialize", map[string]interface{}{
		"clientInfo": map[string]interface{}{"name": "antigem", "title": "AntiGem", "version": clientVersion},
		"capabilities": map[string]interface{}{
			"experimentalApi":           true,
			"requestAttestation":        false,
			"optOutNotificationMethods": optOutNotifications,
		},
	})
	if err != nil {
		m.stopProcess(p)
		if logs := m.recentLogs(6); len(logs) > 0 {
			return nil, fmt.Errorf("codex app-server did not start: %v (%s)", err, strings.Join(logs, " | "))
		}
		return nil, fmt.Errorf("codex app-server did not start: %w", err)
	}
	if err := m.write(p, map[string]interface{}{"method": "initialized"}); err != nil {
		m.stopProcess(p)
		return nil, err
	}
	m.mu.Lock()
	m.initInfo = info
	m.mu.Unlock()
	return p, nil
}

// onExit fails the calls in flight and drops what belonged to the dead process (its pending requests can no longer
// be answered, and none of its threads are subscribed any more).
func (m *Manager) onExit(p *process) {
	p.mu.Lock()
	calls := p.calls
	p.calls = map[int64]chan rpcResult{}
	p.mu.Unlock()
	for _, ch := range calls {
		ch <- rpcResult{err: errors.New("codex app-server exited")}
	}
	m.evMu.Lock()
	for k, r := range m.pending {
		if r.gen == p.gen {
			delete(m.pending, k)
		}
	}
	for k := range m.pendingIDs {
		if strings.HasPrefix(k, fmt.Sprintf("%d:", p.gen)) {
			delete(m.pendingIDs, k)
		}
	}
	m.threads = make(map[string]time.Time)
	m.evMu.Unlock()

	errText := ""
	if code := p.cmd.ProcessState; code != nil && !code.Success() {
		errText = code.String()
		if logs := m.recentLogs(4); len(logs) > 0 {
			errText += ": " + strings.Join(logs, " | ")
		}
	}
	m.broadcastState("exited", errText)
}

func (m *Manager) stopProcess(p *process) {
	if p == nil || p.cmd.Process == nil {
		return
	}
	_ = p.stdin.Close()
	select {
	case <-p.exited:
		return
	case <-time.After(1500 * time.Millisecond):
	}
	pgid := p.cmd.Process.Pid
	_ = syscall.Kill(-pgid, syscall.SIGTERM)
	select {
	case <-p.exited:
	case <-time.After(3 * time.Second):
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
		<-p.exited
	}
}

// Stop ends app-server and everything it started (shells, MCP servers). Running turns end with it.
func (m *Manager) Stop() {
	m.mu.Lock()
	p := m.proc
	m.mu.Unlock()
	m.stopProcess(p)
}

// StopAll is called when the bridge shuts down.
func (m *Manager) StopAll() { m.Stop() }

// --- JSON-RPC ----------------------------------------------------------------------------------

func (m *Manager) write(p *process, v interface{}) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	if !p.alive() {
		return errors.New("codex app-server is not running")
	}
	_, err = p.stdin.Write(append(b, '\n'))
	return err
}

// call sends one request to p and waits for its answer.
func (m *Manager) call(ctx context.Context, p *process, method string, params interface{}) (json.RawMessage, error) {
	ch := make(chan rpcResult, 1)
	p.mu.Lock()
	p.nextID++
	id := p.nextID
	p.calls[id] = ch
	p.mu.Unlock()
	defer func() {
		p.mu.Lock()
		delete(p.calls, id)
		p.mu.Unlock()
	}()
	msg := map[string]interface{}{"id": id, "method": method}
	if params != nil {
		msg["params"] = params
	}
	if err := m.write(p, msg); err != nil {
		return nil, err
	}
	select {
	case r := <-ch:
		if r.err == nil {
			m.trackResult(method, r.result)
		}
		return r.result, r.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Call makes one app-server request, starting it if needed, and re-subscribing to the thread first when the
// method needs the thread's events.
func (m *Manager) Call(ctx context.Context, method string, params json.RawMessage) (json.RawMessage, error) {
	p, err := m.Ensure(ctx)
	if err != nil {
		return nil, err
	}
	var head struct {
		ThreadID string `json:"threadId"`
	}
	_ = json.Unmarshal(params, &head)
	if needsSubscription[method] && head.ThreadID != "" && !m.subscribed(head.ThreadID) {
		if _, err := m.call(ctx, p, "thread/resume", map[string]interface{}{"threadId": head.ThreadID, "excludeTurns": true}); err != nil {
			return nil, err
		}
	}
	var prm interface{}
	if len(params) > 0 && string(params) != "null" {
		prm = params
	}
	res, err := m.call(ctx, p, method, prm)
	if err == nil && head.ThreadID != "" {
		switch method {
		// these end the bridge's subscription (app-server unloads the thread once it is idle)
		// thread/revert reloads the thread from disk: subscribe again before its next turn
		case "thread/unsubscribe", "thread/archive", "thread/delete", "thread/revert":
			m.evMu.Lock()
			delete(m.threads, head.ThreadID)
			m.evMu.Unlock()
		}
	}
	return res, err
}

func (m *Manager) readLoop(p *process, r io.Reader) {
	rd := bufio.NewReaderSize(r, 1<<20)
	for {
		line, err := rd.ReadBytes('\n')
		if len(line) > 0 {
			m.handleLine(p, line)
		}
		if err != nil {
			return
		}
	}
}

func (m *Manager) handleLine(p *process, line []byte) {
	line = []byte(strings.TrimSpace(string(line)))
	if len(line) == 0 {
		return
	}
	var msg wireMessage
	if err := json.Unmarshal(line, &msg); err != nil {
		m.addLog("non-JSON output: " + truncate(string(line), 300))
		return
	}
	hasID := len(msg.ID) > 0 && string(msg.ID) != "null"
	switch {
	case msg.Method != "" && hasID:
		m.onServerRequest(p, msg, line)
	case msg.Method != "":
		m.onNotification(p, msg, line)
	case hasID:
		var id int64
		if json.Unmarshal(msg.ID, &id) != nil {
			return
		}
		p.mu.Lock()
		ch := p.calls[id]
		p.mu.Unlock()
		if ch == nil {
			return
		}
		if msg.Error != nil {
			ch <- rpcResult{err: msg.Error}
		} else {
			ch <- rpcResult{result: msg.Result}
		}
	}
}

func threadIDOf(params json.RawMessage) string {
	var head struct {
		ThreadID string `json:"threadId"`
		Thread   *struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if json.Unmarshal(params, &head) != nil {
		return ""
	}
	if head.ThreadID != "" {
		return head.ThreadID
	}
	if head.Thread != nil {
		return head.Thread.ID
	}
	return ""
}

func (m *Manager) onServerRequest(p *process, msg wireMessage, line []byte) {
	if !appHandledRequests[msg.Method] {
		_ = m.write(p, map[string]interface{}{"id": msg.ID, "error": map[string]interface{}{"code": -32601, "message": "not supported by AntiGem: " + msg.Method}})
		return
	}
	tid := threadIDOf(msg.Params)
	m.evMu.Lock()
	m.reqCounter++
	key := fmt.Sprintf("r%d", m.reqCounter)
	req := &PendingRequest{Key: key, Method: msg.Method, Params: msg.Params, ThreadID: tid, Received: time.Now(), rawID: msg.ID, gen: p.gen}
	m.pending[key] = req
	m.pendingIDs[fmt.Sprintf("%d:%s", p.gen, string(msg.ID))] = key
	if tid != "" {
		m.threads[tid] = time.Now()
	}
	req.Seq = m.appendLocked(line, key)
	m.evMu.Unlock()
}

func (m *Manager) onNotification(p *process, msg wireMessage, line []byte) {
	tid := threadIDOf(msg.Params)
	m.evMu.Lock()
	defer m.evMu.Unlock()
	key := ""
	switch msg.Method {
	case "serverRequest/resolved":
		var prm struct {
			RequestID json.RawMessage `json:"requestId"`
		}
		_ = json.Unmarshal(msg.Params, &prm)
		idKey := fmt.Sprintf("%d:%s", p.gen, string(prm.RequestID))
		if k, ok := m.pendingIDs[idKey]; ok {
			key = k
			delete(m.pendingIDs, idKey)
			delete(m.pending, k)
		}
	case "thread/started":
		if tid != "" {
			m.threads[tid] = time.Now()
		}
	case "thread/closed", "thread/deleted":
		delete(m.threads, tid)
	}
	if tid != "" {
		if _, ok := m.threads[tid]; ok {
			m.threads[tid] = time.Now()
		}
	}
	m.appendLocked(line, key)
}

// trackResult notes the threads a successful call subscribed the bridge to (or released).
func (m *Manager) trackResult(method string, result json.RawMessage) {
	switch method {
	case "thread/start", "thread/resume", "thread/fork":
		if tid := threadIDOf(result); tid != "" {
			m.evMu.Lock()
			m.threads[tid] = time.Now()
			m.evMu.Unlock()
		}
	}
}

func (m *Manager) subscribed(threadID string) bool {
	m.evMu.Lock()
	defer m.evMu.Unlock()
	_, ok := m.threads[threadID]
	return ok
}

// ReplyRequest answers a pending server→client request (exactly one of result / rpcErr).
func (m *Manager) ReplyRequest(key string, result json.RawMessage, rpcErr *RPCError) error {
	m.evMu.Lock()
	req, ok := m.pending[key]
	if ok {
		delete(m.pending, key)
		delete(m.pendingIDs, fmt.Sprintf("%d:%s", req.gen, string(req.rawID)))
	}
	m.evMu.Unlock()
	if !ok {
		return errors.New("this request was already answered or has expired")
	}
	m.mu.Lock()
	p := m.proc
	m.mu.Unlock()
	if p == nil || p.gen != req.gen || !p.alive() {
		return errors.New("codex app-server restarted; the request is gone")
	}
	msg := map[string]interface{}{"id": req.rawID}
	if rpcErr != nil {
		msg["error"] = rpcErr
	} else {
		if len(result) == 0 {
			result = json.RawMessage("{}")
		}
		msg["result"] = result
	}
	return m.write(p, msg)
}

// PendingRequests lists the server→client requests still waiting for an answer, oldest first.
func (m *Manager) PendingRequests() []*PendingRequest {
	m.evMu.Lock()
	defer m.evMu.Unlock()
	return m.pendingLocked()
}

func (m *Manager) pendingLocked() []*PendingRequest {
	out := make([]*PendingRequest, 0, len(m.pending))
	for _, r := range m.pending {
		out = append(out, r)
	}
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].Seq < out[j-1].Seq; j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

// reaper releases threads that have been idle for a long time, so a long-running bridge does not keep every chat
// it ever opened loaded. A thread with a pending request is never released.
func (m *Manager) reaper() {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for range t.C {
		p := m.liveProc()
		if p == nil {
			continue
		}
		cutoff := time.Now().Add(-idleUnsubscribe)
		var idle []string
		m.evMu.Lock()
		busy := map[string]bool{}
		for _, r := range m.pending {
			busy[r.ThreadID] = true
		}
		for tid, last := range m.threads {
			if last.Before(cutoff) && !busy[tid] {
				idle = append(idle, tid)
			}
		}
		m.evMu.Unlock()
		for _, tid := range idle {
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			// read the live status first: a turn may be running without events for a while (a long command). A
			// thread with no message yet is kept too: app-server saves a thread only with its first turn, so an
			// empty one would be gone once released.
			res, err := m.call(ctx, p, "thread/read", map[string]interface{}{"threadId": tid})
			if err == nil {
				var r struct {
					Thread struct {
						Preview string `json:"preview"`
						Status  struct {
							Type string `json:"type"`
						} `json:"status"`
					} `json:"thread"`
				}
				if json.Unmarshal(res, &r) == nil && (r.Thread.Status.Type == "active" || r.Thread.Preview == "") {
					m.evMu.Lock()
					m.threads[tid] = time.Now()
					m.evMu.Unlock()
					cancel()
					continue
				}
			}
			if _, err := m.call(ctx, p, "thread/unsubscribe", map[string]interface{}{"threadId": tid}); err == nil {
				m.evMu.Lock()
				delete(m.threads, tid)
				m.evMu.Unlock()
			}
			cancel()
		}
	}
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
