// Package dsh runs DeepSeek Harness (https://github.com/deepseek-ai/deepseek-harness, npm @deepseek-ai/dsh) for the app.
//
// One `dsh --profile sdk` process serves every dsh chat (session). It speaks the harness SDK protocol — JSON-RPC 2.0,
// one message per line on stdin/stdout — and never opens a port. The bridge is its only client:
//
//   - the app calls SDK methods through POST /api/dsh/rpc (session/query, session/working-directory/set, …);
//   - every notification (session.event, session.status, subagent.*) and server→client request (approval/request,
//     user-question/ask) goes into one sequence-numbered buffer the app follows over /api/dsh/ws (?since= replays);
//   - approvals and questions wait in the bridge until a client answers them (POST /api/dsh/requests/{key}/reply).
//
// The SDK has no way to stop a turn, switch a session's model, list models or change keys at runtime; a companion
// plugin the bridge loads into dsh (runtime.go) offers those on a Unix socket. Chats are listed and read from dsh's
// own files (~/.dsh), so the sidebar works before the ~15 s dsh start.
//
// On Android the official Node.js build (glibc) runs through the app's bundled glibc loader (see runtime.go).
package dsh

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	// startTimeout: dsh loads ~100 plugins before answering `initialize` (15–30 s on a phone).
	startTimeout      = 3 * time.Minute
	maxBufferedEvents = 20000
	maxBufferedBytes  = 48 << 20
)

// server→client requests the app answers; any other is declined right away so nothing hangs.
var appHandledRequests = map[string]bool{
	"approval/request":  true,
	"user-question/ask": true,
}

// methods that address one existing session: a chat saved on disk but not loaded in this dsh process is restored
// (session/resume) first, or dsh would start a new empty session under the same id.
var needsLoadedSession = map[string]bool{
	"session/query":                 true,
	"session/prompt":                true,
	"session/wait":                  true,
	"session/working-directory/get": true,
	"session/working-directory/set": true,
}

var bridgeOwnedMethods = map[string]bool{"initialize": true, "shutdown": true}

// session-log events the app never shows (system prompt, tool schemas, request headers): not relayed.
var droppedEvents = map[string]bool{
	"request/header":    true,
	"request/context":   true,
	"system/message":    true,
	"developer/message": true,
}

// RPCError is a JSON-RPC error answered by dsh.
type RPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func (e *RPCError) Error() string { return fmt.Sprintf("dsh: %s (code %d)", e.Message, e.Code) }

type rpcResult struct {
	result json.RawMessage
	err    error
}

type wireMessage struct {
	ID     json.RawMessage `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *RPCError       `json:"error,omitempty"`
}

// PendingRequest is a server→client request (approval, question) waiting for an answer.
type PendingRequest struct {
	Key       string          `json:"key"`
	Method    string          `json:"method"`
	Params    json.RawMessage `json:"params"`
	SessionID string          `json:"session_id,omitempty"`
	Seq       int64           `json:"seq"`
	Received  time.Time       `json:"received"`
	rawID     json.RawMessage
	gen       int64
	requestID string // user-question/ask's own id (its cancellation names it)
}

type event struct {
	seq   int64
	frame []byte
}

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
	route   string // provider/model it was initialized with

	loadedMu sync.Mutex
	loaded   map[string]bool // sessions owned by this process (created or resumed)
}

// Manager owns the dsh process and serves /api/dsh/.
type Manager struct {
	HomeDir      string
	WorkspaceDir string
	// BinPath is the --dsh-bin flag: an existing `dsh` executable (desktop installs) instead of the app's runtime.
	BinPath string

	l    layout
	cfg  configStore
	meta metaStore

	mu       sync.Mutex
	proc     *process
	gen      int64
	starting chan struct{}
	startErr error
	initInfo json.RawMessage
	logs     []string

	evMu       sync.Mutex
	events     []event
	evBytes    int
	nextSeq    int64
	subs       map[*subscriber]struct{}
	pending    map[string]*PendingRequest
	pendingIDs map[string]string // gen:rawID → key
	reqCounter int64
	statuses   map[string]string // sessionId → running | idle

	jobMu sync.Mutex
	job   *installJob

	ctlID   atomic.Int64
	headers headerCache
}

func NewManager(homeDir, workspaceDir, binPath string) *Manager {
	binPath = strings.TrimSpace(binPath)
	if binPath == "~" || strings.HasPrefix(binPath, "~/") {
		binPath = filepath.Join(homeDir, strings.TrimPrefix(binPath, "~"))
	}
	root := filepath.Join(homeDir, ".local", "share", "dsh")
	return &Manager{
		HomeDir:      homeDir,
		WorkspaceDir: workspaceDir,
		BinPath:      binPath,
		l:            layout{root: root},
		cfg:          configStore{path: filepath.Join(root, "config.json")},
		meta:         metaStore{path: filepath.Join(root, "meta.json")},
		subs:         make(map[*subscriber]struct{}),
		pending:      make(map[string]*PendingRequest),
		pendingIDs:   make(map[string]string),
		statuses:     make(map[string]string),
		headers:      headerCache{m: map[string]sessionHeader{}},
	}
}

// dshHome is dsh's own data folder (sessions, skills, storages).
func (m *Manager) dshHome() string {
	if h := os.Getenv("DSH_HOME"); h != "" {
		return h
	}
	return filepath.Join(m.HomeDir, ".dsh")
}

// Installed reports whether a runnable dsh is in place.
func (m *Manager) Installed() bool {
	if m.BinPath != "" {
		_, err := os.Stat(m.BinPath)
		return err == nil
	}
	return m.l.installedVersion() != "" && m.nodeBinary() != ""
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

func (p *process) isLoaded(id string) bool {
	p.loadedMu.Lock()
	defer p.loadedMu.Unlock()
	return p.loaded[id]
}

func (p *process) markLoaded(id string) {
	p.loadedMu.Lock()
	p.loaded[id] = true
	p.loadedMu.Unlock()
}

func (m *Manager) liveProc() *process {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.proc != nil && m.proc.alive() && m.starting == nil {
		return m.proc
	}
	return nil
}

// Running reports whether dsh is up and initialized.
func (m *Manager) Running() bool { return m.liveProc() != nil }

func (m *Manager) addLog(line string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logs = append(m.logs, line)
	if len(m.logs) > 300 {
		m.logs = m.logs[len(m.logs)-300:]
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

// Ensure starts dsh when it is not running and waits until it is initialized.
func (m *Manager) Ensure(ctx context.Context) (*process, error) {
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
		m.broadcastState("starting", "")

		p, err := m.start()

		m.mu.Lock()
		m.startErr = err
		m.starting = nil
		m.mu.Unlock()
		close(done)
		if err != nil {
			m.broadcastState("exited", err.Error())
			return nil, err
		}
		m.broadcastState("spawned", "")
		go m.followStream(p)
		return p, nil
	}
}

func (m *Manager) command() (*exec.Cmd, error) {
	args := []string{"--profile", "sdk", "--patch", m.l.patchFile()}
	if m.BinPath != "" {
		cmd := exec.Command(m.BinPath, args...)
		cmd.Env = os.Environ()
		return cmd, nil
	}
	if m.l.installedVersion() == "" {
		return nil, errors.New("DeepSeek Harness is not installed (install it in the app: Settings > DeepSeek Harness)")
	}
	return m.nodeCommand(context.Background(), false, append([]string{"--expose-internals", binJS(m.l.current())}, args...)...)
}

func (m *Manager) start() (*process, error) {
	if err := m.writeRuntimeFiles(); err != nil {
		return nil, fmt.Errorf("preparing dsh: %w", err)
	}
	cfg := m.cfg.load()
	if cfg.Provider == "" || cfg.Model == "" {
		return nil, errors.New("choose a provider and model for DeepSeek Harness in Settings")
	}
	cmd, err := m.command()
	if err != nil {
		return nil, err
	}
	dir := m.WorkspaceDir
	if fi, err := os.Stat(dir); dir == "" || err != nil || !fi.IsDir() {
		dir = m.HomeDir
	}
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	_ = os.Remove(m.l.controlSocket())
	cmd.Env = append(cmd.Env, "ANTIGEM_DSH_CONTROL="+m.l.controlSocket())
	for k, v := range cfg.Keys {
		if v != "" {
			cmd.Env = append(cmd.Env, k+"="+v)
		}
	}
	if m.termuxPrefix() == "" {
		cmd.Env = append(cmd.Env, "DSH_PERMISSION_MODE="+m.presets()[cfg.Permission])
	}
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
		return nil, fmt.Errorf("starting dsh: %w", err)
	}

	m.mu.Lock()
	m.gen++
	p := &process{gen: m.gen, cmd: cmd, stdin: stdin, exited: make(chan struct{}), calls: make(map[int64]chan rpcResult),
		started: time.Now(), loaded: map[string]bool{}, route: cfg.Provider + "/" + cfg.Model}
	m.proc = p
	m.mu.Unlock()

	go func() {
		sc := bufio.NewScanner(stderr)
		sc.Buffer(make([]byte, 64<<10), 1<<20)
		for sc.Scan() {
			if t := strings.TrimSpace(sc.Text()); t != "" {
				m.addLog(t)
			}
		}
	}()
	readerDone := make(chan struct{})
	go func() {
		defer close(readerDone)
		m.readLoop(p, stdout)
	}()
	go func() {
		<-readerDone
		_ = cmd.Wait()
		close(p.exited)
		m.onExit(p)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), startTimeout)
	defer cancel()
	params := map[string]interface{}{
		"cwd":              dir,
		"provider":         cfg.Provider,
		"model":            cfg.Model,
		"maxTokens":        cfg.MaxTokens,
		"approvalRequests": true,
		"capabilities":     map[string]interface{}{"userQuestions": true},
	}
	if cfg.ReasoningEffort != "" {
		params["reasoningEffort"] = cfg.ReasoningEffort
	}
	info, err := m.call(ctx, p, "initialize", params)
	if err != nil {
		m.stopProcess(p)
		if logs := m.recentLogs(6); len(logs) > 0 {
			return nil, fmt.Errorf("dsh did not start: %v (%s)", err, strings.Join(logs, " | "))
		}
		return nil, fmt.Errorf("dsh did not start: %w", err)
	}
	m.mu.Lock()
	m.initInfo = info
	m.mu.Unlock()
	return p, nil
}

func (m *Manager) onExit(p *process) {
	p.mu.Lock()
	calls := p.calls
	p.calls = map[int64]chan rpcResult{}
	p.mu.Unlock()
	for _, ch := range calls {
		ch <- rpcResult{err: errors.New("dsh exited")}
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
	m.statuses = make(map[string]string)
	m.evMu.Unlock()

	errText := ""
	if st := p.cmd.ProcessState; st != nil && !st.Success() {
		errText = st.String()
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
	// a clean shutdown flushes session persistence
	if p.alive() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_, _ = m.call(ctx, p, "shutdown", nil)
		cancel()
	}
	_ = p.stdin.Close()
	select {
	case <-p.exited:
		return
	case <-time.After(3 * time.Second):
	}
	pgid := p.cmd.Process.Pid
	_ = syscall.Kill(-pgid, syscall.SIGTERM)
	select {
	case <-p.exited:
	case <-time.After(4 * time.Second):
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
		<-p.exited
	}
}

// Stop ends dsh and everything it started (shells, MCP servers). Running turns end with it.
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
		return errors.New("dsh is not running")
	}
	_, err = p.stdin.Write(append(b, '\n'))
	return err
}

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
	msg := map[string]interface{}{"jsonrpc": "2.0", "id": id, "method": method}
	if params != nil {
		msg["params"] = params
	}
	if err := m.write(p, msg); err != nil {
		return nil, err
	}
	select {
	case r := <-ch:
		return r.result, r.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Call makes one SDK request, starting dsh if needed and restoring a saved session the method addresses first.
func (m *Manager) Call(ctx context.Context, method string, params json.RawMessage) (json.RawMessage, error) {
	p, err := m.Ensure(ctx)
	if err != nil {
		return nil, err
	}
	var head struct {
		SessionID string `json:"sessionId"`
	}
	_ = json.Unmarshal(params, &head)
	if head.SessionID != "" && (needsLoadedSession[method] || method == "session/resume") {
		if err := m.ensureLoaded(ctx, p, head.SessionID, method == "session/resume"); err != nil {
			return nil, err
		}
		if method == "session/resume" {
			return json.RawMessage("{}"), nil
		}
	}
	var prm interface{}
	if len(params) > 0 && string(params) != "null" {
		prm = params
	}
	return m.call(ctx, p, method, prm)
}

// ensureLoaded restores a saved session into p before it is used (a no-op for sessions p already owns, and for new
// ids, which dsh creates on first use).
func (m *Manager) ensureLoaded(ctx context.Context, p *process, sessionID string, explicit bool) error {
	if p.isLoaded(sessionID) {
		return nil
	}
	if m.sessionDir(sessionID) == "" {
		if explicit {
			return errors.New("this chat no longer exists in dsh")
		}
		p.markLoaded(sessionID) // new session
		return nil
	}
	if _, err := m.call(ctx, p, "session/resume", map[string]interface{}{"sessionId": sessionID}); err != nil {
		var rpcErr *RPCError
		// "already owned" (a subagent or schedule restored it) means it is loaded
		if !errors.As(err, &rpcErr) || !strings.Contains(strings.ToLower(rpcErr.Message), "already") {
			return err
		}
	}
	p.markLoaded(sessionID)
	return nil
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

func (m *Manager) onServerRequest(p *process, msg wireMessage, line []byte) {
	if !appHandledRequests[msg.Method] {
		_ = m.write(p, map[string]interface{}{"jsonrpc": "2.0", "id": msg.ID, "error": map[string]interface{}{"code": -32601, "message": "not supported by AntiGem: " + msg.Method}})
		return
	}
	var prm struct {
		SessionID string `json:"sessionId"`
		RequestID string `json:"requestId"`
	}
	_ = json.Unmarshal(msg.Params, &prm)
	m.evMu.Lock()
	m.reqCounter++
	key := fmt.Sprintf("r%d", m.reqCounter)
	req := &PendingRequest{Key: key, Method: msg.Method, Params: msg.Params, SessionID: prm.SessionID, Received: time.Now(),
		rawID: msg.ID, gen: p.gen, requestID: prm.RequestID}
	m.pending[key] = req
	m.pendingIDs[fmt.Sprintf("%d:%s", p.gen, string(msg.ID))] = key
	req.Seq = m.appendLocked(line, key)
	m.evMu.Unlock()
}

func (m *Manager) onNotification(p *process, msg wireMessage, line []byte) {
	key := ""
	switch msg.Method {
	case "session.event":
		var prm struct {
			SessionID string `json:"sessionId"`
			Event     struct {
				Type string `json:"type"`
			} `json:"event"`
		}
		if json.Unmarshal(msg.Params, &prm) == nil {
			if droppedEvents[prm.Event.Type] {
				return
			}
			if prm.Event.Type == "assistant/message" || prm.Event.Type == "assistant/attempt" {
				line = stripStream(line)
			}
		}
	case "session.status":
		var prm struct {
			SessionID string `json:"sessionId"`
			Status    string `json:"status"`
		}
		if json.Unmarshal(msg.Params, &prm) == nil && prm.SessionID != "" {
			m.evMu.Lock()
			m.statuses[prm.SessionID] = prm.Status
			m.evMu.Unlock()
			p.markLoaded(prm.SessionID)
		}
	case "subagent.started":
		var prm struct {
			ChildSessionID string `json:"childSessionId"`
		}
		if json.Unmarshal(msg.Params, &prm) == nil && prm.ChildSessionID != "" {
			p.markLoaded(prm.ChildSessionID)
		}
	case "user-question.cancelled":
		var prm struct {
			RequestID string `json:"requestId"`
		}
		_ = json.Unmarshal(msg.Params, &prm)
		m.evMu.Lock()
		for k, r := range m.pending {
			if r.gen == p.gen && r.requestID != "" && r.requestID == prm.RequestID {
				key = k
				delete(m.pending, k)
				delete(m.pendingIDs, fmt.Sprintf("%d:%s", r.gen, string(r.rawID)))
			}
		}
		m.evMu.Unlock()
	}
	m.evMu.Lock()
	m.appendLocked(line, key)
	m.evMu.Unlock()
}

// stripStream drops event.data.stream (the raw timed model stream, often larger than the message) from a
// session.event notification line.
func stripStream(line []byte) []byte {
	var root map[string]json.RawMessage
	if json.Unmarshal(line, &root) != nil {
		return line
	}
	var params map[string]json.RawMessage
	if json.Unmarshal(root["params"], &params) != nil {
		return line
	}
	var ev map[string]json.RawMessage
	if json.Unmarshal(params["event"], &ev) != nil {
		return line
	}
	var data map[string]json.RawMessage
	if json.Unmarshal(ev["data"], &data) != nil {
		return line
	}
	if _, ok := data["stream"]; !ok {
		return line
	}
	delete(data, "stream")
	ev["data"], _ = json.Marshal(data)
	params["event"], _ = json.Marshal(ev)
	root["params"], _ = json.Marshal(params)
	out, err := json.Marshal(root)
	if err != nil {
		return line
	}
	return out
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
		return errors.New("dsh restarted; the request is gone")
	}
	msg := map[string]interface{}{"jsonrpc": "2.0", "id": req.rawID}
	if rpcErr != nil {
		msg["error"] = rpcErr
	} else {
		if len(result) == 0 {
			result = json.RawMessage("{}")
		}
		msg["result"] = result
	}
	// tell every app that the request is settled (another device may show it)
	resolved, _ := json.Marshal(map[string]interface{}{"method": "antigem/request-resolved", "params": map[string]string{"key": key}})
	m.evMu.Lock()
	m.appendLocked(resolved, key)
	m.evMu.Unlock()
	return m.write(p, msg)
}

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

func (m *Manager) sessionStatuses() map[string]string {
	m.evMu.Lock()
	defer m.evMu.Unlock()
	out := make(map[string]string, len(m.statuses))
	for k, v := range m.statuses {
		out[k] = v
	}
	return out
}

// --- live typing -------------------------------------------------------------------------------

// followStream subscribes to the companion plugin's live typing (text/reasoning deltas, which the SDK protocol does
// not carry) and relays every batch to the app as a live-only frame for as long as p runs.
func (m *Manager) followStream(p *process) {
	for attempt := 0; attempt < 30 && p.alive(); attempt++ {
		conn, err := net.DialTimeout("unix", m.l.controlSocket(), 3*time.Second)
		if err != nil {
			time.Sleep(time.Second)
			continue
		}
		stop := make(chan struct{})
		go func() {
			select {
			case <-p.exited:
			case <-stop:
			}
			_ = conn.Close()
		}()
		if _, err := conn.Write([]byte(`{"id":0,"method":"subscribe"}` + "\n")); err == nil {
			rd := bufio.NewReaderSize(conn, 1<<20)
			for {
				line, err := rd.ReadBytes('\n')
				if len(line) > 0 {
					m.relayStream(line)
				}
				if err != nil {
					break
				}
			}
		}
		close(stop)
		if !p.alive() {
			return
		}
		time.Sleep(time.Second)
	}
}

// relayStream forwards one {"stream":[…]} batch as {"type":"bridge_live","data":{"method":"antigem/stream",
// "params":{"items":[…]}}}. Live only: not buffered or replayed (the session's assistant/message, which follows
// every model step, carries the final text).
func (m *Manager) relayStream(line []byte) {
	var batch struct {
		Stream json.RawMessage `json:"stream"`
	}
	if json.Unmarshal(line, &batch) != nil || len(batch.Stream) == 0 {
		return
	}
	frame, err := json.Marshal(map[string]interface{}{
		"type": "bridge_live",
		"data": map[string]interface{}{"method": "antigem/stream", "params": map[string]json.RawMessage{"items": batch.Stream}},
	})
	if err != nil {
		return
	}
	m.evMu.Lock()
	for sub := range m.subs {
		sub.send(frame)
	}
	m.evMu.Unlock()
}

// --- companion plugin (control socket) ---------------------------------------------------------

// control calls the companion plugin inside the running dsh.
func (m *Manager) control(ctx context.Context, method string, params interface{}) (json.RawMessage, error) {
	if m.liveProc() == nil {
		return nil, errors.New("dsh is not running")
	}
	d := net.Dialer{Timeout: 5 * time.Second}
	conn, err := d.DialContext(ctx, "unix", m.l.controlSocket())
	if err != nil {
		return nil, fmt.Errorf("dsh control channel unavailable: %w", err)
	}
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	} else {
		_ = conn.SetDeadline(time.Now().Add(60 * time.Second))
	}
	id := m.ctlID.Add(1)
	b, _ := json.Marshal(map[string]interface{}{"id": id, "method": method, "params": params})
	if _, err := conn.Write(append(b, '\n')); err != nil {
		return nil, err
	}
	rd := bufio.NewReaderSize(conn, 1<<20)
	line, err := rd.ReadBytes('\n')
	if err != nil {
		return nil, fmt.Errorf("dsh control channel: %w", err)
	}
	var reply struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(line, &reply); err != nil {
		return nil, err
	}
	if reply.Error != nil {
		return nil, errors.New(reply.Error.Message)
	}
	return reply.Result, nil
}
