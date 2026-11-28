// Package script runs JavaScript inside the engine: hooks on requests, responses, WebSocket messages and finished
// flows, timers and commands the AI can call. goja runs the code (pure Go, ES2015+ without real async I/O); every
// hook has a time limit, and a script that keeps failing is turned off.
//
// Scripts are files: <data>/scripts/*.js for all sessions, <session>/scripts/*.js for one session. Header lines at
// the top say when a script runs:
//
//	// @name     login-debug
//	// @match    host:api.myapp.dev
//	// @hooks    request, response, complete
//	// @body     response
//	// @timeout  20ms
package script

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dop251/goja"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
)

// Time limits per kind of call.
const (
	TrafficLimit  = 20 * time.Millisecond // onRequest, onResponse, onMessage (default; @timeout changes it)
	CompleteLimit = 500 * time.Millisecond
	CommandLimit  = 5 * time.Second
	EveryLimit    = time.Second
	LoadLimit     = time.Second // the top level of a script
	MaxFailures   = 20          // in a row: the script is turned off
	maxTraffic    = 2 * time.Second
	maxWorkers    = 8
)

// Finding is something a script (or the engine about a script) reports for the AI or the user.
type Finding struct {
	Script   string `json:"script"`
	Severity string `json:"severity"` // info, warn, error
	Title    string `json:"title"`
	Detail   string `json:"detail,omitempty"`
	Flow     int64  `json:"flow,omitempty"`
}

// QueryOptions of ctx.query.
type QueryOptions struct {
	Limit   int
	GroupBy string
}

// SendRequest is ctx.send's argument.
type SendRequest struct {
	Method  string
	URL     string
	Headers map[string]string
	Body    []byte
	Timeout time.Duration
	Script  string
}

// SendResponse is ctx.send's answer.
type SendResponse struct {
	Status  int
	Headers map[string][]string
	Body    []byte
	Flow    int64
}

// Host is what the engine gives scripts (the service layer implements it).
type Host interface {
	Var(name string) (string, bool)
	SetVar(name, value string)
	DeleteVar(name string)
	Vars() map[string]string
	Finding(Finding)
	OutDir() string
	Query(filter string, o QueryOptions) (any, error)
	Send(ctx context.Context, r SendRequest) (*SendResponse, error)
}

// Options to make a Manager.
type Options struct {
	Dir  string // global scripts folder
	Host Host
	Logf func(string, ...any)
}

// Manager loads, watches and runs the scripts.
type Manager struct {
	opts    Options
	mu      sync.Mutex // loading
	sessDir string
	scripts map[string]*Script // by file path
	state   map[string]bool    // enabled flags by name (saved)
	snap    atomic.Pointer[snapshot]
	watch   *watcher
	closed  chan struct{}
}

type snapshot struct {
	all                                   []*Script // sorted: priority, name
	request, response, message, complete []*Script // enabled, loaded, with that hook
}

// New makes a manager and loads the scripts of dir (created when missing).
func New(o Options) *Manager {
	if o.Logf == nil {
		o.Logf = func(string, ...any) {}
	}
	_ = os.MkdirAll(o.Dir, 0o700)
	m := &Manager{opts: o, scripts: map[string]*Script{}, state: map[string]bool{}, closed: make(chan struct{})}
	if b, err := os.ReadFile(filepath.Join(o.Dir, ".state.json")); err == nil {
		_ = json.Unmarshal(b, &m.state)
	}
	m.snap.Store(&snapshot{})
	m.Reload()
	m.watch = newWatcher(m.dirs, func() { m.Reload() })
	return m
}

// Close stops timers and the watcher and saves the stores.
func (m *Manager) Close() {
	select {
	case <-m.closed:
		return
	default:
	}
	close(m.closed)
	if m.watch != nil {
		m.watch.close()
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, s := range m.scripts {
		s.stop()
	}
}

// Dir is the global scripts folder.
func (m *Manager) Dir() string { return m.opts.Dir }

// SessionDir is the active session's scripts folder ("" = none).
func (m *Manager) SessionDir() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sessDir
}

// SetSessionDir switches the session folder (the active session changed) and reloads.
func (m *Manager) SetSessionDir(dir string) {
	if dir != "" {
		_ = os.MkdirAll(dir, 0o700)
	}
	m.mu.Lock()
	changed := m.sessDir != dir
	m.sessDir = dir
	m.mu.Unlock()
	if changed {
		m.Reload()
		if m.watch != nil {
			m.watch.reset()
		}
	}
}

func (m *Manager) dirs() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	d := []string{m.opts.Dir}
	if m.sessDir != "" {
		d = append(d, m.sessDir)
	}
	return d
}

// For returns the enabled scripts that have a hook (request, response, message, complete), in order.
func (m *Manager) For(hook string) []*Script {
	sn := m.snap.Load()
	switch hook {
	case "request":
		return sn.request
	case "response":
		return sn.response
	case "message":
		return sn.message
	case "complete":
		return sn.complete
	}
	return nil
}

// TrafficActive: some enabled script changes requests, responses or messages (the engine then calls the hooks).
func (m *Manager) TrafficActive() bool {
	sn := m.snap.Load()
	return len(sn.request) > 0 || len(sn.response) > 0 || len(sn.message) > 0
}

// All returns every script (also off or broken ones), in order.
func (m *Manager) All() []*Script { return m.snap.Load().all }

// Get returns a script by name.
func (m *Manager) Get(name string) *Script {
	for _, s := range m.All() {
		if strings.EqualFold(s.Name, name) {
			return s
		}
	}
	return nil
}

// Reload reads the folders again: new and changed files are compiled, removed ones stop. Unchanged scripts keep
// running (with their stats and logs).
func (m *Manager) Reload() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.reloadLocked("")
}

// ReloadFile forces one file to be read again (net_script reload name), even when unchanged.
func (m *Manager) ReloadFile(path string) []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.reloadLocked(path)
}

func (m *Manager) reloadLocked(force string) []string {
	var msgs []string
	seen := map[string]bool{}
	var files []string
	dirs := []string{m.opts.Dir}
	if m.sessDir != "" {
		dirs = append(dirs, m.sessDir)
	}
	for _, d := range dirs {
		entries, _ := os.ReadDir(d)
		for _, e := range entries {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".js") || strings.HasPrefix(e.Name(), ".") {
				continue
			}
			files = append(files, filepath.Join(d, e.Name()))
		}
	}
	names := map[string]string{} // name → file, to find duplicates
	next := map[string]*Script{}
	for _, p := range files {
		seen[p] = true
		src, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		sum := sha256.Sum256(src)
		hash := hex.EncodeToString(sum[:8])
		old := m.scripts[p]
		if old != nil && old.hash == hash && p != force && old.dupOf == "" {
			next[p] = old
			names[strings.ToLower(old.Name)] = p
			continue
		}
		if old != nil && old.store != nil {
			old.store.flush() // the new version reads the saved store
		}
		s := m.compile(p, string(src), hash)
		if other, dup := names[strings.ToLower(s.Name)]; dup {
			s.Err = fmt.Sprintf("the name %q is already used by %s: give this script another // @name", s.Name, filepath.Base(other))
			s.dupOf = other
		} else {
			names[strings.ToLower(s.Name)] = p
		}
		if old != nil {
			old.stop()
			s.calls.Store(0)
			s.logs = old.logs // keep the log across edits
		}
		if s.Err != "" {
			s.logf("load", "error: %s", s.Err)
			msgs = append(msgs, s.Name+": "+s.Err)
		} else {
			s.logf("load", "loaded (%s)", s.describeHooks())
			msgs = append(msgs, s.Name+": loaded")
		}
		next[p] = s
	}
	for p, old := range m.scripts {
		if !seen[p] {
			old.stop()
			msgs = append(msgs, old.Name+": removed")
		}
	}
	m.scripts = next
	for _, s := range next {
		on, ok := m.state[strings.ToLower(s.Name)]
		if !ok {
			on = s.defaultOn
		}
		s.setEnabled(on)
	}
	m.publish()
	return msgs
}

func (m *Manager) publish() {
	sn := &snapshot{}
	for _, s := range m.scripts {
		sn.all = append(sn.all, s)
	}
	sort.Slice(sn.all, func(i, j int) bool {
		if sn.all[i].Priority != sn.all[j].Priority {
			return sn.all[i].Priority > sn.all[j].Priority
		}
		return sn.all[i].Name < sn.all[j].Name
	})
	for _, s := range sn.all {
		if !s.Running() {
			continue
		}
		if s.Hooks["request"] {
			sn.request = append(sn.request, s)
		}
		if s.Hooks["response"] {
			sn.response = append(sn.response, s)
		}
		if s.Hooks["message"] {
			sn.message = append(sn.message, s)
		}
		if s.Hooks["complete"] {
			sn.complete = append(sn.complete, s)
		}
	}
	m.snap.Store(sn)
}

// Enable turns a script on or off (saved).
func (m *Manager) Enable(name string, on bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	var s *Script
	for _, x := range m.scripts {
		if strings.EqualFold(x.Name, name) {
			s = x
		}
	}
	if s == nil {
		return fmt.Errorf("no script %q", name)
	}
	if on && s.Err != "" {
		return fmt.Errorf("%s doesn't load: %s", s.Name, s.Err)
	}
	m.state[strings.ToLower(s.Name)] = on
	m.saveState()
	if on {
		s.failRun.Store(0)
		s.offReason.Store("")
	}
	s.setEnabled(on)
	m.publish()
	return nil
}

// turnOff is called when a script failed too often.
func (m *Manager) turnOff(s *Script, why string) {
	m.mu.Lock()
	if !s.Enabled() {
		m.mu.Unlock()
		return
	}
	s.offReason.Store(why)
	s.setEnabled(false)
	m.publish()
	m.mu.Unlock()
	s.logf("engine", "turned off: %s", why)
	m.opts.Host.Finding(Finding{Script: s.Name, Severity: "warn", Title: "script " + s.Name + " was turned off",
		Detail: why + " (fix it, then net_script action=enable name=" + s.Name + ")"})
}

func (m *Manager) saveState() {
	b, _ := json.MarshalIndent(m.state, "", "  ")
	_ = os.WriteFile(filepath.Join(m.opts.Dir, ".state.json"), b, 0o600)
}

// Remove deletes a script file.
func (m *Manager) Remove(name string) error {
	s := m.Get(name)
	if s == nil {
		return fmt.Errorf("no script %q", name)
	}
	if err := os.Remove(s.File); err != nil {
		return err
	}
	m.mu.Lock()
	delete(m.state, strings.ToLower(s.Name))
	m.saveState()
	m.mu.Unlock()
	m.Reload()
	return nil
}

// Save writes a script file into the global (or the session's) folder and loads it. name "" = the @name line or
// "script". Returns the script (it may carry a load error).
func (m *Manager) Save(name, code string, session bool) (*Script, error) {
	if strings.TrimSpace(code) == "" {
		return nil, errors.New("the script is empty")
	}
	if name == "" {
		h := parseHeader(code)
		name = h["name"]
	}
	if name == "" {
		name = "script"
	}
	file := safeName(name)
	dir := m.opts.Dir
	if session {
		if dir = m.SessionDir(); dir == "" {
			return nil, errors.New("no active session folder")
		}
	}
	p := filepath.Join(dir, file+".js")
	if err := os.WriteFile(p, []byte(code), 0o600); err != nil {
		return nil, err
	}
	m.ReloadFile(p)
	for _, s := range m.All() {
		if s.File == p {
			return s, nil
		}
	}
	return nil, errors.New("the script was written but not loaded")
}

func safeName(name string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(name) {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r == '-', r == '_':
			b.WriteRune(r)
		case r == ' ' || r == '.':
			b.WriteByte('-')
		}
	}
	if b.Len() == 0 {
		return "script"
	}
	return b.String()
}

// ---- one script ----

// Script is one loaded file.
type Script struct {
	m        *Manager
	Name     string
	File     string
	Session  bool
	Match    string
	Hooks    map[string]bool // request, response, message, complete
	Body     map[string]bool // req, res: read whole before the hook starts
	Timeout  time.Duration   // traffic hooks
	Workers  int
	Priority int
	Err      string // load error (the script doesn't run)
	Warnings []string
	Commands []string
	Every    []time.Duration

	filter    *query.Filter
	prog      *goja.Program
	hash      string
	dupOf     string
	defaultOn bool

	enabled   atomic.Bool
	offReason atomic.Value // string: why the engine turned it off

	pool    chan *worker
	poolMu  sync.Mutex
	created int
	first   *worker // the worker that ran the top level at load (put into the pool)

	timersStop chan struct{}
	timersMu   sync.Mutex

	calls   atomic.Int64
	errs    atomic.Int64
	busy    atomic.Int64 // skipped: no free worker
	failRun atomic.Int64 // failures in a row
	totalNs atomic.Int64
	maxNs   atomic.Int64
	lastErr atomic.Value // string

	logs  *ring
	store *kv

	findMu  sync.Mutex
	findWin time.Time
	findN   int
}

// Enabled reports whether the script is switched on.
func (s *Script) Enabled() bool { return s.enabled.Load() }

// Running: on and loaded.
func (s *Script) Running() bool { return s.Enabled() && s.Err == "" }

// OffReason is why the engine turned the script off ("" = it didn't).
func (s *Script) OffReason() string { v, _ := s.offReason.Load().(string); return v }

// Matches reports whether the @match filter takes this flow.
func (s *Script) Matches(f *flow.Flow, b query.Bodies) bool { return s.filter == nil || s.filter.Match(f, b) }

// Filter is the compiled @match.
func (s *Script) Filter() *query.Filter { return s.filter }

// Stats for lists.
type Stats struct {
	Calls, Errors, Busy int64
	AvgMs, MaxMs        float64
	LastError           string
}

// Stats returns counters.
func (s *Script) Stats() Stats {
	st := Stats{Calls: s.calls.Load(), Errors: s.errs.Load(), Busy: s.busy.Load()}
	if st.Calls > 0 {
		st.AvgMs = float64(s.totalNs.Load()) / float64(st.Calls) / 1e6
	}
	st.MaxMs = float64(s.maxNs.Load()) / 1e6
	st.LastError, _ = s.lastErr.Load().(string)
	return st
}

// Logs returns the last n log lines.
func (s *Script) Logs(n int) []string { return s.logs.last(n) }

// ClearLogs empties the log.
func (s *Script) ClearLogs() { s.logs.clear() }

func (s *Script) logf(where, format string, args ...any) {
	s.logs.add(time.Now().Format("15:04:05.000") + " [" + where + "] " + fmt.Sprintf(format, args...))
}

func (s *Script) describeHooks() string {
	var h []string
	for _, k := range []string{"request", "response", "message", "complete"} {
		if s.Hooks[k] {
			h = append(h, k)
		}
	}
	if len(s.Commands) > 0 {
		h = append(h, "commands: "+strings.Join(s.Commands, ", "))
	}
	if len(s.Every) > 0 {
		var e []string
		for _, d := range s.Every {
			e = append(e, d.String())
		}
		h = append(h, "every "+strings.Join(e, ", "))
	}
	if len(h) == 0 {
		return "no hooks"
	}
	return strings.Join(h, ", ")
}

func (s *Script) setEnabled(on bool) {
	was := s.enabled.Swap(on && s.Err == "")
	if on && s.Err == "" && !was {
		s.startTimers()
	}
	if !(on && s.Err == "") && was {
		s.stopTimers()
	}
}

func (s *Script) stop() {
	s.enabled.Store(false)
	s.stopTimers()
	if s.store != nil {
		s.store.flush()
	}
}

func (s *Script) failed(err error, where string, flowID int64) {
	s.errs.Add(1)
	msg := err.Error()
	if flowID > 0 {
		msg = fmt.Sprintf("%s (f%d): %s", where, flowID, msg)
	} else {
		msg = where + ": " + msg
	}
	s.lastErr.Store(msg)
	s.logf(where, "error: %s", err.Error())
	if n := s.failRun.Add(1); n >= MaxFailures {
		s.m.turnOff(s, fmt.Sprintf("%d errors in a row, the last: %s", n, msg))
	}
}

func (s *Script) succeeded(d time.Duration) {
	s.failRun.Store(0)
	s.totalNs.Add(int64(d))
	for {
		cur := s.maxNs.Load()
		if int64(d) <= cur || s.maxNs.CompareAndSwap(cur, int64(d)) {
			break
		}
	}
}

// ---- loading ----

// parseHeader reads the // @key value lines at the top of a script.
func parseHeader(src string) map[string]string {
	h := map[string]string{}
	for _, line := range strings.Split(src, "\n") {
		t := strings.TrimSpace(line)
		if t == "" {
			continue
		}
		if !strings.HasPrefix(t, "//") {
			break
		}
		t = strings.TrimSpace(strings.TrimPrefix(t, "//"))
		if !strings.HasPrefix(t, "@") {
			continue
		}
		k, v, _ := strings.Cut(t[1:], " ")
		v = strings.TrimSpace(v)
		if i := strings.Index(v, "  ("); i > 0 { // "@body response   (comment)"
			v = strings.TrimSpace(v[:i])
		}
		h[strings.ToLower(strings.TrimSpace(k))] = v
	}
	return h
}

func splitList(v string) []string {
	var out []string
	for _, x := range strings.FieldsFunc(v, func(r rune) bool { return r == ',' || r == ' ' || r == '|' }) {
		if x = strings.ToLower(strings.TrimSpace(x)); x != "" {
			out = append(out, x)
		}
	}
	return out
}

var hookFuncs = map[string]string{"request": "onRequest", "response": "onResponse", "message": "onMessage", "complete": "onComplete"}

func (m *Manager) compile(path, src, hash string) *Script {
	h := parseHeader(src)
	base := strings.TrimSuffix(filepath.Base(path), ".js")
	s := &Script{m: m, Name: base, File: path, Session: filepath.Dir(path) != filepath.Clean(m.opts.Dir), Hooks: map[string]bool{},
		Body: map[string]bool{}, Timeout: TrafficLimit, Workers: 4, hash: hash, defaultOn: true, logs: newRing(300)}
	if n := strings.TrimSpace(h["name"]); n != "" {
		s.Name = n
	}
	s.store = openKV(filepath.Join(m.opts.Dir, ".store", safeName(s.Name)+".json"))
	bad := func(format string, args ...any) *Script {
		s.Err = fmt.Sprintf(format, args...)
		return s
	}
	if v, ok := h["match"]; ok {
		s.Match = v
		f, err := query.Parse(v)
		if err != nil {
			return bad("@match: %v", err)
		}
		s.filter = f
	}
	if v := h["timeout"]; v != "" {
		d, err := parseDur(v)
		if err != nil || d <= 0 {
			return bad("@timeout: use a duration like 20ms or 1s")
		}
		if d > maxTraffic {
			d = maxTraffic
			s.Warnings = append(s.Warnings, "@timeout is at most "+maxTraffic.String()+" (it holds up the app's traffic)")
		}
		s.Timeout = d
	}
	if v := h["workers"]; v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			return bad("@workers: a number from 1 to %d", maxWorkers)
		}
		s.Workers = min(n, maxWorkers)
	}
	if v := h["priority"]; v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			return bad("@priority: a number (higher runs first)")
		}
		s.Priority = n
	}
	if v := strings.ToLower(h["enabled"]); v == "false" || v == "no" || v == "off" {
		s.defaultOn = false
	}
	for _, b := range splitList(h["body"]) {
		switch b {
		case "request", "req":
			s.Body["req"] = true
		case "response", "res":
			s.Body["res"] = true
		case "both", "all":
			s.Body["req"], s.Body["res"] = true, true
		case "none":
		default:
			return bad("@body: request, response, both or none")
		}
	}
	prog, err := goja.Compile(filepath.Base(path), src, false)
	if err != nil {
		return bad("%s", cleanErr(err))
	}
	s.prog = prog
	s.pool = make(chan *worker, s.Workers)
	w, err := s.newWorker()
	if err != nil {
		return bad("%s", cleanErr(err))
	}
	s.first = w
	s.created = 1
	s.pool <- w
	defined := map[string]bool{}
	for k, fn := range hookFuncs {
		if w.fns[k] != nil {
			defined[k] = true
		} else if w.vm.Get(fn) != nil && !goja.IsUndefined(w.vm.Get(fn)) {
			s.Warnings = append(s.Warnings, fn+" is not a function")
		}
	}
	if v, ok := h["hooks"]; ok {
		for _, k := range splitList(v) {
			k = strings.TrimPrefix(k, "on")
			if _, known := hookFuncs[k]; !known {
				return bad("@hooks: request, response, message, complete (got %q)", k)
			}
			if !defined[k] {
				s.Warnings = append(s.Warnings, "@hooks names "+k+" but there is no function "+hookFuncs[k])
				continue
			}
			s.Hooks[k] = true
		}
		for k := range defined {
			if !s.Hooks[k] {
				s.Warnings = append(s.Warnings, hookFuncs[k]+" is defined but not in @hooks, so it doesn't run")
			}
		}
	} else {
		s.Hooks = defined
	}
	s.Commands = append([]string(nil), w.commandNames...)
	for _, e := range w.everies {
		s.Every = append(s.Every, e.every)
	}
	if s.Hooks["message"] && s.filter != nil && s.filter.NeedsBody() {
		s.Warnings = append(s.Warnings, "@match with body terms never matches WebSocket messages")
	}
	return s
}

func parseDur(v string) (time.Duration, error) {
	v = strings.TrimSpace(v)
	if n, err := strconv.Atoi(v); err == nil {
		return time.Duration(n) * time.Millisecond, nil
	}
	return time.ParseDuration(v)
}

// cleanErr makes goja's error text short, with the script's line: "TypeError: … at onRequest (x.js:3:5)".
func cleanErr(err error) string {
	var ex *goja.Exception
	if errors.As(err, &ex) {
		msg := ""
		if v := ex.Value(); v != nil {
			msg = v.String()
		}
		for _, fr := range ex.Stack() {
			pos := fr.Position()
			if pos.Line == 0 || fr.SrcName() == "" || fr.SrcName() == "<native>" {
				continue
			}
			where := fmt.Sprintf("%s:%d:%d", fr.SrcName(), pos.Line, pos.Column)
			if fn := fr.FuncName(); fn != "" {
				where = fn + " (" + where + ")"
			}
			return strings.TrimSpace(msg) + " at " + where
		}
		if msg != "" {
			return strings.TrimSpace(msg)
		}
		return strings.TrimSpace(ex.Error())
	}
	return strings.TrimSpace(err.Error())
}

// ---- timers ----

func (s *Script) startTimers() {
	s.timersMu.Lock()
	defer s.timersMu.Unlock()
	if s.timersStop != nil || len(s.Every) == 0 {
		return
	}
	stop := make(chan struct{})
	s.timersStop = stop
	for i, d := range s.Every {
		go func(i int, d time.Duration) {
			t := time.NewTicker(d)
			defer t.Stop()
			for {
				select {
				case <-stop:
					return
				case <-s.m.closed:
					return
				case <-t.C:
					s.runEvery(i)
				}
			}
		}(i, d)
	}
}

func (s *Script) stopTimers() {
	s.timersMu.Lock()
	defer s.timersMu.Unlock()
	if s.timersStop != nil {
		close(s.timersStop)
		s.timersStop = nil
	}
}

// ---- ring log ----

type ring struct {
	mu    sync.Mutex
	lines []string
	next  int
	full  bool
}

func newRing(n int) *ring { return &ring{lines: make([]string, n)} }

func (r *ring) add(line string) {
	if len(line) > 2000 {
		line = line[:2000] + "…"
	}
	r.mu.Lock()
	r.lines[r.next] = line
	r.next = (r.next + 1) % len(r.lines)
	if r.next == 0 {
		r.full = true
	}
	r.mu.Unlock()
}

func (r *ring) last(n int) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var all []string
	if r.full {
		all = append(all, r.lines[r.next:]...)
	}
	all = append(all, r.lines[:r.next]...)
	if n > 0 && len(all) > n {
		all = all[len(all)-n:]
	}
	return all
}

func (r *ring) clear() {
	r.mu.Lock()
	r.next, r.full = 0, false
	r.mu.Unlock()
}

// ---- ctx.store: per script, kept across restarts ----

type kv struct {
	mu    sync.Mutex
	path  string
	data  map[string]any
	dirty bool
	timer *time.Timer
}

func openKV(path string) *kv {
	k := &kv{path: path, data: map[string]any{}}
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, &k.data)
	}
	return k
}

func (k *kv) get(key string) (any, bool) {
	k.mu.Lock()
	defer k.mu.Unlock()
	v, ok := k.data[key]
	return v, ok
}

func (k *kv) keys() []string {
	k.mu.Lock()
	defer k.mu.Unlock()
	out := make([]string, 0, len(k.data))
	for key := range k.data {
		out = append(out, key)
	}
	sort.Strings(out)
	return out
}

func (k *kv) set(key string, v any) {
	k.mu.Lock()
	if v == nil {
		delete(k.data, key)
	} else {
		k.data[key] = v
	}
	k.dirty = true
	if k.timer == nil {
		k.timer = time.AfterFunc(time.Second, k.flush)
	}
	k.mu.Unlock()
}

func (k *kv) snapshot() map[string]any {
	k.mu.Lock()
	defer k.mu.Unlock()
	b, _ := json.Marshal(k.data)
	out := map[string]any{}
	_ = json.Unmarshal(b, &out)
	return out
}

func (k *kv) flush() {
	k.mu.Lock()
	k.timer = nil
	if !k.dirty {
		k.mu.Unlock()
		return
	}
	k.dirty = false
	b, err := json.Marshal(k.data)
	k.mu.Unlock()
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(k.path), 0o700)
	tmp := k.path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, k.path)
	}
}
