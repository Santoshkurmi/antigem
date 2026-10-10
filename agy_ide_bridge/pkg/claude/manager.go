// Package claude runs Claude Code CLI sessions for the app.
//
// Each conversation gets one long-lived `claude` process speaking the stream-json protocol over stdin/stdout
// (see CLAUDE_CLI_PROTOCOL.md). The app attaches to a session over a WebSocket; the bridge only relays NDJSON
// lines, buffers the current process output for re-attaching, and spawns/reaps processes.
package claude

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	maxBufferedLines = 20000
	maxBufferedBytes = 64 << 20
	idleReapAfter    = 15 * time.Minute
	stderrTailBytes  = 4096
)

// baseArgs are the flags the VS Code extension uses (minus IDE-only ones), plus readable thinking summaries.
var baseArgs = []string{
	"--output-format", "stream-json",
	"--verbose",
	"--input-format", "stream-json",
	"--permission-prompt-tool", "stdio",
	"--include-partial-messages",
	"--replay-user-messages",
	"--setting-sources=user,project,local",
	"--thinking-display", "summarized",
}

// SpawnOptions are applied the next time the session's process is started.
type SpawnOptions struct {
	Cwd            string `json:"cwd,omitempty"`
	Model          string `json:"model,omitempty"`
	PermissionMode string `json:"permission_mode,omitempty"`
	Effort         string `json:"effort,omitempty"`
	ForkFrom       string `json:"fork_from,omitempty"`
	// ResumeSessionAt (with ForkFrom) cuts the forked history after this message uuid (edit / regenerate).
	ResumeSessionAt string `json:"resume_session_at,omitempty"`
	// Thinking is "on" or "off".
	Thinking string `json:"thinking,omitempty"`
}

// Manager owns all Claude sessions of this bridge.
type Manager struct {
	HomeDir    string
	DefaultCwd string
	BinPath    string

	mu       sync.Mutex
	sessions map[string]*Session

	infoMu     sync.Mutex
	infoCache  json.RawMessage
	infoAt     time.Time
	usageCache cachedValue

	logins loginRegistry

	jobMu sync.Mutex
	job   *cliJob
}

func NewManager(homeDir, defaultCwd, customBin string) *Manager {
	m := &Manager{
		HomeDir:    homeDir,
		DefaultCwd: defaultCwd,
		BinPath:    customBin,
		sessions:   make(map[string]*Session),
	}
	go m.reapLoop()
	return m
}

// ResolveBinary finds the `claude` executable.
func (m *Manager) ResolveBinary() (string, error) {
	candidates := []string{}
	if m.BinPath != "" {
		candidates = append(candidates, m.BinPath)
	}
	if env := os.Getenv("CLAUDE_BIN"); env != "" {
		candidates = append(candidates, env)
	}
	if p, err := exec.LookPath("claude"); err == nil {
		candidates = append(candidates, p)
	}
	candidates = append(candidates,
		filepath.Join(m.HomeDir, ".local", "bin", "claude"),
		filepath.Join(m.HomeDir, ".claude", "local", "claude"),
		"/usr/local/bin/claude",
		"/usr/bin/claude",
	)
	for _, c := range candidates {
		if fi, err := os.Stat(c); err == nil && !fi.IsDir() {
			return c, nil
		}
	}
	return "", errors.New("claude CLI not found (install Claude Code or set CLAUDE_BIN)")
}

// ProjectsDir is ~/.claude/projects.
func (m *Manager) ProjectsDir() string {
	return filepath.Join(m.HomeDir, ".claude", "projects")
}

// Session returns the session for id, creating an idle (not spawned) one if needed.
func (m *Manager) Session(id string) *Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.sessions[id]
	if !ok {
		s = &Session{ID: id, mgr: m, subs: make(map[*subscriber]struct{}), lastActivity: time.Now()}
		m.sessions[id] = s
	}
	return s
}

// Existing returns the session for id only if it already exists.
func (m *Manager) Existing(id string) *Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sessions[id]
}

// Remove kills and forgets a session.
func (m *Manager) Remove(id string) {
	m.mu.Lock()
	s := m.sessions[id]
	delete(m.sessions, id)
	m.mu.Unlock()
	if s != nil {
		s.Kill()
	}
}

// StopAll kills every running process (bridge shutdown).
func (m *Manager) StopAll() {
	m.mu.Lock()
	all := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		all = append(all, s)
	}
	m.mu.Unlock()
	for _, s := range all {
		s.Kill()
	}
}

// Snapshot reports live state per session id (used by the session list).
func (m *Manager) Snapshot() map[string]SessionState {
	m.mu.Lock()
	all := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		all = append(all, s)
	}
	m.mu.Unlock()
	out := make(map[string]SessionState, len(all))
	for _, s := range all {
		out[s.ID] = s.State()
	}
	return out
}

func (m *Manager) reapLoop() {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for range t.C {
		m.mu.Lock()
		var idle []*Session
		for _, s := range m.sessions {
			s.mu.Lock()
			if s.cmd != nil && len(s.subs) == 0 && s.pendingTurns == 0 && time.Since(s.lastActivity) > idleReapAfter {
				idle = append(idle, s)
			}
			s.mu.Unlock()
		}
		m.mu.Unlock()
		for _, s := range idle {
			log.Printf("[claude] reaping idle session %s", s.ID)
			s.Kill()
		}
	}
}

// SessionState is what the app needs to know about a session's process.
type SessionState struct {
	Live         bool  `json:"live"`
	Busy         bool  `json:"busy"`
	PendingTurns int   `json:"pending_turns"`
	BufferStart  int64 `json:"buffer_start_seq"`
	NextSeq      int64 `json:"next_seq"`
	// HistoryOffset is the transcript size when the live process started; history before it comes from the
	// transcript file, everything after it from the WebSocket buffer.
	HistoryOffset int64 `json:"history_offset"`
}

// Session is one conversation and (when spawned) its claude process.
type Session struct {
	ID  string
	mgr *Manager

	mu            sync.Mutex
	cmd           *exec.Cmd
	stdin         io.WriteCloser
	opts          SpawnOptions
	lines         [][]byte
	bufferedBytes int
	baseSeq       int64
	historyOffset int64
	pendingTurns  int
	stderrTail    []byte
	subs          map[*subscriber]struct{}
	lastActivity  time.Time
	generation    int
	// historyCut is the rewind point the live process was started at: its transcript still ends with the old
	// branch until the first new prompt is written, so history is cut there while the process runs.
	historyCut *string
	// rewindPending: the rewind is cleared once this process gets its first prompt
	rewindPending bool
}

// HistoryCut is the rewind point of the live process, if it was started at one.
func (s *Session) HistoryCut() *string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.historyCut
}

func (s *Session) State() SessionState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return SessionState{
		Live:          s.cmd != nil,
		Busy:          s.pendingTurns > 0,
		PendingTurns:  s.pendingTurns,
		BufferStart:   s.baseSeq,
		NextSeq:       s.baseSeq + int64(len(s.lines)),
		HistoryOffset: s.historyOffset,
	}
}

// Configure merges spawn options (used for the next spawn only).
func (s *Session) Configure(o SpawnOptions) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if o.Cwd != "" {
		s.opts.Cwd = o.Cwd
	}
	// "default" clears a value chosen before (the CLI then uses settings.json / its own default)
	if o.Model == "default" {
		s.opts.Model = ""
	} else if o.Model != "" {
		s.opts.Model = o.Model
	}
	if o.PermissionMode != "" {
		s.opts.PermissionMode = o.PermissionMode
	}
	if o.Effort == "default" {
		s.opts.Effort = ""
	} else if o.Effort != "" {
		s.opts.Effort = o.Effort
	}
	if o.ForkFrom != "" {
		s.opts.ForkFrom = o.ForkFrom
	}
	if o.ResumeSessionAt != "" {
		s.opts.ResumeSessionAt = o.ResumeSessionAt
	}
	if o.Thinking != "" {
		s.opts.Thinking = o.Thinking
	}
}

// Write sends one NDJSON line to the process, spawning it first if needed.
func (s *Session) Write(line []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.cmd == nil {
		if err := s.spawnLocked(); err != nil {
			return err
		}
	}
	var head struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(line, &head) == nil && head.Type == "user" {
		s.pendingTurns++
		if s.rewindPending {
			// the new prompt starts the new branch: from now on the transcript itself ends there
			s.rewindPending = false
			s.mgr.clearRewind(s.ID)
		}
	}
	s.lastActivity = time.Now()
	if _, err := s.stdin.Write(append(append([]byte{}, line...), '\n')); err != nil {
		return fmt.Errorf("write to claude: %w", err)
	}
	return nil
}

func (s *Session) spawnLocked() error {
	bin, err := s.mgr.ResolveBinary()
	if err != nil {
		return err
	}
	args := append([]string{}, baseArgs...)
	if s.opts.Model != "" {
		args = append(args, "--model", s.opts.Model)
	}
	// Bypass (skip every permission check) is never used: the app runs Claude in Auto unless the user chose a
	// default mode in settings.json (Auto falls back to Manual on models without it)
	mode := s.opts.PermissionMode
	if mode == "" {
		mode = s.mgr.defaultPermissionMode()
	}
	if mode == "bypassPermissions" {
		mode = "auto"
	}
	args = append(args, "--permission-mode", mode)
	if s.opts.Effort != "" {
		args = append(args, "--effort", s.opts.Effort)
	}
	switch s.opts.Thinking {
	case "off":
		args = append(args, "--thinking", "disabled")
	case "on":
		args = append(args, "--max-thinking-tokens", "31999")
	}
	// attachments of every chat live under one folder Claude may read without asking
	if root := s.mgr.attachmentsRoot(); os.MkdirAll(root, 0o700) == nil {
		args = append(args, "--add-dir", root)
	}

	transcript := s.mgr.FindTranscript(s.ID)
	rewindAt, rewinding := s.mgr.rewindPoint(s.ID)
	if rewinding && rewindAt == "" && transcript != "" {
		// the first prompt was edited: nothing is kept, the chat starts over under the same id
		_ = os.RemoveAll(strings.TrimSuffix(transcript, ".jsonl"))
		_ = os.Remove(transcript)
		transcript = ""
	}
	s.historyOffset = 0
	switch {
	case transcript != "":
		args = append(args, "--resume="+s.ID)
		if rewinding {
			args = append(args, "--resume-session-at="+rewindAt)
		}
		if fi, err := os.Stat(transcript); err == nil {
			s.historyOffset = fi.Size()
		}
	case s.opts.ForkFrom != "" && s.mgr.FindTranscript(s.opts.ForkFrom) != "":
		args = append(args, "--resume="+s.opts.ForkFrom, "--fork-session", "--session-id="+s.ID)
		if s.opts.ResumeSessionAt != "" {
			args = append(args, "--resume-session-at="+s.opts.ResumeSessionAt)
		}
	default:
		args = append(args, "--session-id="+s.ID)
	}

	cwd := s.opts.Cwd
	if cwd == "" && transcript != "" {
		cwd = transcriptCwd(transcript)
	}
	if fi, err := os.Stat(cwd); cwd == "" || err != nil || !fi.IsDir() {
		cwd = s.mgr.DefaultCwd
	}

	cmd := exec.Command(bin, args...)
	cmd.Dir = cwd
	cmd.Env = os.Environ()
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start claude: %w", err)
	}
	log.Printf("[claude] spawned session %s (pid %d) in %s: %s", s.ID, cmd.Process.Pid, cwd, strings.Join(args, " "))

	s.cmd = cmd
	s.stdin = stdin
	s.generation++
	s.baseSeq += int64(len(s.lines))
	s.lines = nil
	s.bufferedBytes = 0
	s.pendingTurns = 0
	s.stderrTail = nil
	s.historyCut = nil
	s.rewindPending = rewinding
	if rewinding {
		s.historyCut = &rewindAt
	}
	gen := s.generation

	go s.readStderr(stderr)
	go s.readStdout(stdout, gen)
	go s.wait(cmd, gen)

	s.broadcastLocked(stateMessage(s.stateLocked(), "spawned", nil, ""))
	return nil
}

func (s *Session) readStdout(r io.Reader, gen int) {
	br := bufio.NewReaderSize(r, 1<<20)
	for {
		line, err := br.ReadBytes('\n')
		if len(line) > 0 {
			line = []byte(strings.TrimRight(string(line), "\r\n"))
			if len(line) > 0 && json.Valid(line) {
				s.appendLine(line, gen)
			}
		}
		if err != nil {
			return
		}
	}
}

func (s *Session) appendLine(line []byte, gen int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if gen != s.generation {
		return
	}
	var head struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(line, &head) == nil && head.Type == "result" && s.pendingTurns > 0 {
		s.pendingTurns--
	}
	seq := s.baseSeq + int64(len(s.lines))
	s.lines = append(s.lines, line)
	s.bufferedBytes += len(line)
	for (len(s.lines) > maxBufferedLines || s.bufferedBytes > maxBufferedBytes) && len(s.lines) > 1 {
		s.bufferedBytes -= len(s.lines[0])
		s.lines = s.lines[1:]
		s.baseSeq++
	}
	s.lastActivity = time.Now()
	s.broadcastLocked(lineMessage(seq, line))
}

func (s *Session) readStderr(r io.Reader) {
	buf := make([]byte, 4096)
	for {
		n, err := r.Read(buf)
		if n > 0 {
			s.mu.Lock()
			s.stderrTail = append(s.stderrTail, buf[:n]...)
			if len(s.stderrTail) > stderrTailBytes {
				s.stderrTail = s.stderrTail[len(s.stderrTail)-stderrTailBytes:]
			}
			s.mu.Unlock()
		}
		if err != nil {
			return
		}
	}
}

func (s *Session) wait(cmd *exec.Cmd, gen int) {
	err := cmd.Wait()
	s.mu.Lock()
	defer s.mu.Unlock()
	if gen != s.generation {
		return
	}
	code := 0
	if cmd.ProcessState != nil {
		code = cmd.ProcessState.ExitCode()
	}
	errText := strings.TrimSpace(string(s.stderrTail))
	if err != nil && errText == "" {
		errText = err.Error()
	}
	log.Printf("[claude] session %s exited (code %d)", s.ID, code)
	s.cmd = nil
	s.stdin = nil
	s.pendingTurns = 0
	// Everything this process produced is now in the transcript file; drop the buffer so a later attach
	// does not replay it on top of the history.
	s.baseSeq += int64(len(s.lines))
	s.lines = nil
	s.bufferedBytes = 0
	s.historyOffset = 0
	s.historyCut = nil
	s.rewindPending = false
	s.broadcastLocked(stateMessage(s.stateLocked(), "exited", &code, errText))
}

// Kill terminates the process (if any).
func (s *Session) Kill() {
	s.mu.Lock()
	cmd, stdin := s.cmd, s.stdin
	s.mu.Unlock()
	if stdin != nil {
		_ = stdin.Close()
	}
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Signal(os.Interrupt)
		go func() {
			time.Sleep(3 * time.Second)
			_ = cmd.Process.Kill()
		}()
	}
}

// KillAndWait terminates the process and waits (up to timeout) until it has exited, so it can no longer write
// to its transcript.
func (s *Session) KillAndWait(timeout time.Duration) {
	s.Kill()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		live := s.cmd != nil
		s.mu.Unlock()
		if !live {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func (s *Session) stateLocked() SessionState {
	return SessionState{
		Live:          s.cmd != nil,
		Busy:          s.pendingTurns > 0,
		PendingTurns:  s.pendingTurns,
		BufferStart:   s.baseSeq,
		NextSeq:       s.baseSeq + int64(len(s.lines)),
		HistoryOffset: s.historyOffset,
	}
}

func (s *Session) broadcastLocked(msg []byte) {
	for sub := range s.subs {
		sub.send(msg)
	}
}

// --- bridge → app frames ---------------------------------------------------------------------

func lineMessage(seq int64, line []byte) []byte {
	b, _ := json.Marshal(struct {
		Type string          `json:"type"`
		Seq  int64           `json:"seq"`
		Data json.RawMessage `json:"data"`
	}{"bridge_line", seq, line})
	return b
}

func stateMessage(st SessionState, event string, exitCode *int, errText string) []byte {
	b, _ := json.Marshal(struct {
		Type     string       `json:"type"`
		Event    string       `json:"event"`
		State    SessionState `json:"state"`
		ExitCode *int         `json:"exit_code,omitempty"`
		Error    string       `json:"error,omitempty"`
	}{"bridge_state", event, st, exitCode, errText})
	return b
}

func errorMessage(text string) []byte {
	b, _ := json.Marshal(struct {
		Type  string `json:"type"`
		Error string `json:"error"`
	}{"bridge_error", text})
	return b
}
