// Package service is the one place where every use case lives (capture, sessions, listing, reading, summaries,
// export). The MCP server, the HTTP API and the CLI are thin layers on top of it.
package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"antigem/netcap/internal/analyze"
	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/intercept"
	"antigem/netcap/internal/query"
	"antigem/netcap/internal/rules"
	"antigem/netcap/internal/script"
	"antigem/netcap/internal/store"
	"antigem/netcap/internal/tlsx"
)

// Platform is what the device side provides (Android: the app over the control socket). Nil on a PC.
type Platform interface {
	Name() string
	StartCapture(apps []string, byAgent bool) error
	StopCapture() error
	CertStatus() string
	InstallCert() (string, error)
	RemoveCert() (string, error)
}

// Settings are the user's settings (config.json). The AI can't change them through MCP.
type Settings struct {
	BodyCapMB          int      `json:"body_cap_mb"`
	SessionCapMB       int      `json:"session_cap_mb"`
	ShowSecretsToAI    bool     `json:"show_secrets_to_ai"`
	NeverDecrypt       []string `json:"never_decrypt"`
	InsecureUpstream   []string `json:"insecure_upstream"` // hosts with self-signed certificates (your dev servers)
	ToolGroups         []string `json:"tool_groups,omitempty"`
	AgentsStartCapture bool     `json:"agents_start_capture"`
}

// Options to start the service.
type Options struct {
	DataDir   string
	ProxyAddr string
	APIAddr   string
	CA        *tlsx.CA // given by the platform (Android keeps the key encrypted); nil = load or create in DataDir/ca
	Platform  Platform
	Logf      func(string, ...any)
	// Dial opens upstream connections (nil = direct). Tests map addresses; later: an upstream proxy.
	Dial func(ctx context.Context, network, addr string) (net.Conn, error)
	// Version of the engine (shown by the GUI).
	Version string
}

// CaptureState is the current capture.
type CaptureState struct {
	On           bool      `json:"on"`
	Paused       bool      `json:"paused"`
	Apps         []string  `json:"apps,omitempty"`
	Since        time.Time `json:"since,omitempty"`
	DecryptOnly  []string  `json:"decrypt_only,omitempty"`
	NeverDecrypt []string  `json:"never_decrypt,omitempty"`
	Ignore       []string  `json:"ignore,omitempty"`
	Mirror       string    `json:"mirror,omitempty"` // filter of flows written to files ("" = off)
	ByAgent      bool      `json:"by_agent,omitempty"`
}

// Service is the engine's core.
type Service struct {
	opts      Options
	CA        *tlsx.CA
	Certs     *tlsx.Certs
	Untrusted *tlsx.Untrusted
	Engine    *intercept.Engine
	Hub       *Hub

	mu       sync.RWMutex
	settings Settings
	capture  CaptureState
	mirrorF  *query.Filter
	sess     *Session
	opened   map[string]*Session

	active   sync.Map // *flow.Flow → *Session
	mirrorCh chan mirrorItem
	started  time.Time

	Rules      *rules.Set
	Scripts    *script.Manager
	reqBodies  sync.Map // *flow.Flow → request body read in the request phase (for onResponse scripts)
	completeCh chan completeItem
	hooks      hooksList
	paused     pausedSet
	limit      limiter
	clientOnce sync.Once
	httpc      *http.Client

	live    liveHub   // the GUI's live list (ui_live.go)
	docs    *docCache // decoded bodies the GUI pages through (ui_body.go)
	uiState stateCache
}

// Session is one capture project.
type Session struct {
	ID      string       `json:"id"`
	Name    string       `json:"name"`
	Created time.Time    `json:"created"`
	Dir     string       `json:"-"`
	Store   *store.Store `json:"-"`
}

// New starts the service (not the listeners).
func New(opts Options) (*Service, error) {
	if opts.Logf == nil {
		opts.Logf = func(string, ...any) {}
	}
	if err := os.MkdirAll(filepath.Join(opts.DataDir, "sessions"), 0o700); err != nil {
		return nil, err
	}
	s := &Service{opts: opts, Hub: NewHub(), opened: map[string]*Session{}, mirrorCh: make(chan mirrorItem, 1024), started: time.Now()}
	s.docs = newDocCache(filepath.Join(opts.DataDir, "tmp", "view"), 64<<20)
	s.settings = s.loadSettings()
	ca := opts.CA
	if ca == nil {
		var err error
		if ca, err = loadOrCreateCA(filepath.Join(opts.DataDir, "ca")); err != nil {
			return nil, fmt.Errorf("CA: %w", err)
		}
	}
	s.CA = ca
	certs, err := tlsx.NewCerts(ca, 2000)
	if err != nil {
		return nil, err
	}
	s.Certs = certs
	s.Untrusted = tlsx.LoadUntrusted(filepath.Join(opts.DataDir, "untrusted.json"))
	rs, err := rules.Load(filepath.Join(opts.DataDir, "rules.json"))
	if err != nil {
		opts.Logf("rules: %v", err)
	}
	s.Rules = rs
	if err := s.openActive(); err != nil {
		return nil, err
	}
	s.startScripts()
	s.Engine = intercept.New(intercept.Config{
		Certs:     certs,
		Untrusted: s.Untrusted,
		Sink:      s,
		Decide:    s.decide,
		InsecureUpstream: func(host string) bool {
			s.mu.RLock()
			defer s.mu.RUnlock()
			return matchHosts(s.settings.InsecureUpstream, host)
		},
		Logf:  opts.Logf,
		Dial:  opts.Dial,
		Hooks: s,
	})
	go s.mirrorLoop()
	return s, nil
}

// Close flushes and closes all sessions.
func (s *Service) Close() {
	if s.Scripts != nil {
		s.Scripts.Close()
	}
	s.docs.closeAll()
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, sess := range s.opened {
		sess.Store.Close()
	}
	s.opened = map[string]*Session{}
}

// DataDir is the engine's folder.
func (s *Service) DataDir() string { return s.opts.DataDir }

// Options returns the start options.
func (s *Service) Options() Options { return s.opts }

// Settings returns a copy of the settings.
func (s *Service) Settings() Settings {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.settings
}

// SetSettings replaces the settings (the app's settings page, the user's CLI) and saves them.
func (s *Service) SetSettings(st Settings) error {
	s.mu.Lock()
	s.settings = st
	s.mu.Unlock()
	b, _ := json.MarshalIndent(st, "", "  ")
	return os.WriteFile(filepath.Join(s.opts.DataDir, "config.json"), b, 0o600)
}

func (s *Service) loadSettings() Settings {
	st := Settings{BodyCapMB: 20, SessionCapMB: 500, AgentsStartCapture: true}
	if b, err := os.ReadFile(filepath.Join(s.opts.DataDir, "config.json")); err == nil {
		_ = json.Unmarshal(b, &st)
	}
	if st.BodyCapMB <= 0 {
		st.BodyCapMB = 20
	}
	if st.SessionCapMB <= 0 {
		st.SessionCapMB = 500
	}
	return st
}

func loadOrCreateCA(dir string) (*tlsx.CA, error) {
	certPath, keyPath := filepath.Join(dir, "ca.crt"), filepath.Join(dir, "ca.key")
	if c, err := os.ReadFile(certPath); err == nil {
		k, err := os.ReadFile(keyPath)
		if err != nil {
			return nil, err
		}
		return tlsx.LoadCA(c, k)
	}
	ca, err := tlsx.NewCA()
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	if err := os.WriteFile(keyPath, ca.KeyPEM, 0o600); err != nil {
		return nil, err
	}
	if err := os.WriteFile(certPath, ca.CertPEM, 0o644); err != nil {
		return nil, err
	}
	return ca, nil
}

// CertPath is where the public CA certificate is (written if missing).
func (s *Service) CertPath() string {
	p := filepath.Join(s.opts.DataDir, "ca", "ca.crt")
	if _, err := os.Stat(p); err != nil {
		_ = os.MkdirAll(filepath.Dir(p), 0o700)
		_ = os.WriteFile(p, s.CA.CertPEM, 0o644)
	}
	return p
}

// ---- capture ----

// CaptureInfo returns the capture state.
func (s *Service) CaptureInfo() CaptureState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	c := s.capture
	c.Apps = append([]string(nil), c.Apps...)
	return c
}

// StartOptions for a capture.
type StartOptions struct {
	Apps         []string
	DecryptOnly  []string
	NeverDecrypt []string
	Ignore       []string
	Mirror       string
	NewSession   string
	ByAgent      bool
}

// StartCapture starts recording (Android: asks the app to start the VPN for the apps).
func (s *Service) StartCapture(o StartOptions) error {
	var mf *query.Filter
	if o.Mirror != "" && o.Mirror != "off" {
		src := o.Mirror
		if src == "all" || src == "on" {
			src = ""
		}
		f, err := query.Parse(src)
		if err != nil {
			return fmt.Errorf("mirror filter: %w", err)
		}
		mf = f
	}
	if o.NewSession != "" {
		if _, err := s.NewSession(o.NewSession); err != nil {
			return err
		}
	}
	if s.opts.Platform != nil {
		if len(o.Apps) == 0 {
			return errors.New("name the apps to capture (package names, for example com.example.app)")
		}
		if err := s.opts.Platform.StartCapture(o.Apps, o.ByAgent); err != nil {
			return err
		}
	}
	s.mu.Lock()
	s.capture = CaptureState{On: true, Apps: o.Apps, Since: time.Now(), DecryptOnly: o.DecryptOnly,
		NeverDecrypt: o.NeverDecrypt, Ignore: o.Ignore, Mirror: o.Mirror, ByAgent: o.ByAgent}
	s.mirrorF = mf
	s.mu.Unlock()
	return nil
}

// StopCapture stops recording.
func (s *Service) StopCapture() error {
	s.mu.Lock()
	s.capture.On = false
	s.capture.Paused = false
	s.mirrorF = nil
	s.mu.Unlock()
	if s.opts.Platform != nil {
		return s.opts.Platform.StopCapture()
	}
	return nil
}

// CaptureStopped is called when the platform ended the capture itself (VPN revoked).
func (s *Service) CaptureStopped() {
	s.mu.Lock()
	s.capture.On = false
	s.mu.Unlock()
}

// SetCaptureApps updates the captured apps (the app started the VPN from its own screen).
func (s *Service) SetCaptureApps(on bool, apps []string) {
	s.mu.Lock()
	if on && !s.capture.On {
		s.capture = CaptureState{On: true, Apps: apps, Since: time.Now()}
	} else {
		s.capture.On = on
		s.capture.Apps = apps
	}
	s.mu.Unlock()
}

// Pause keeps traffic flowing but records nothing.
func (s *Service) Pause(p bool) {
	s.mu.Lock()
	s.capture.Paused = p
	s.mu.Unlock()
}

// Decide tells the ingress whether to record (and decrypt) a new connection.
func (s *Service) Decide(m intercept.Meta, host string) intercept.Decision { return s.decide(m, host) }

func (s *Service) decide(m intercept.Meta, host string) intercept.Decision {
	s.mu.RLock()
	c := s.capture
	never := s.settings.NeverDecrypt
	s.mu.RUnlock()
	if !c.On || c.Paused {
		return intercept.Decision{}
	}
	if len(c.Apps) > 0 && m.App != "" && !contains(c.Apps, m.App) {
		return intercept.Decision{}
	}
	if matchHosts(c.Ignore, host) {
		return intercept.Decision{}
	}
	pass, ign := s.connectRule(m, host)
	if ign {
		return intercept.Decision{}
	}
	d := intercept.Decision{Record: true, Decrypt: !pass}
	if len(c.DecryptOnly) > 0 && !matchHosts(c.DecryptOnly, host) {
		d.Decrypt = false
	}
	if matchHosts(c.NeverDecrypt, host) || matchHosts(never, host) {
		d.Decrypt = false
	}
	return d
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if strings.EqualFold(x, s) {
			return true
		}
	}
	return false
}

// matchHosts: exact names or *.example.com (which also matches example.com).
func matchHosts(globs []string, host string) bool {
	h := strings.ToLower(strings.TrimSuffix(host, "."))
	for _, g := range globs {
		g = strings.ToLower(strings.TrimSpace(g))
		switch {
		case g == "":
		case g == "*":
			return true
		case strings.HasPrefix(g, "*."):
			if h == g[2:] || strings.HasSuffix(h, g[1:]) {
				return true
			}
		case g == h:
			return true
		}
	}
	return false
}

// ---- Sink (intercept.Sink) ----

// NewFlow starts a flow in the active session.
func (s *Service) NewFlow(kind flow.Kind, m intercept.Meta) *flow.Flow {
	sess := s.Current()
	f := &flow.Flow{ID: sess.Store.NewID(), Kind: kind, App: m.App, UID: m.UID, Client: m.Client, Start: time.Now()}
	s.active.Store(f, sess)
	return f
}

func (s *Service) sessOf(f *flow.Flow) *Session {
	if v, ok := s.active.Load(f); ok {
		return v.(*Session)
	}
	return s.Current()
}

// Update writes the flow's current state.
func (s *Service) Update(f *flow.Flow) {
	f.Endpoint = analyze.EndpointKey(f)
	sess := s.sessOf(f)
	if c := sess.Store.Put(f); c != nil {
		s.live.flow(s, sess.ID, c, false)
	}
}

// Finish writes the finished flow and tells listeners.
func (s *Service) Finish(f *flow.Flow) {
	sess := s.sessOf(f)
	s.active.Delete(f)
	f.Done = true
	f.Endpoint = analyze.EndpointKey(f)
	if stored := sess.Store.Put(f); stored != nil {
		s.live.flow(s, sess.ID, stored, false)
	}
	c := f.Clone()
	s.Hub.Publish(Event{Session: sess.ID, Flow: c})
	s.reqBodies.Delete(f)
	if s.completeCh != nil && s.wantComplete() {
		select {
		case s.completeCh <- completeItem{sess: sess, f: f.Clone()}:
		default: // the scripts fall behind: this flow skips onComplete
		}
	}
	s.mu.RLock()
	mf := s.mirrorF
	s.mu.RUnlock()
	if mf != nil && mf.Match(c, nil) {
		select {
		case s.mirrorCh <- mirrorItem{sess: sess, f: c}:
		default:
		}
	}
}

// Message records a WebSocket message or raw chunk.
func (s *Service) Message(f *flow.Flow, m flow.Message) { s.sessOf(f).Store.PutMessage(m) }

// Capture starts capturing a body.
func (s *Service) Capture(f *flow.Flow, side string) intercept.Capture {
	s.mu.RLock()
	limit := int64(s.settings.BodyCapMB) << 20
	s.mu.RUnlock()
	return &bodyCapture{w: s.sessOf(f).Store.Blobs.NewWriter(limit)}
}

type bodyCapture struct {
	mu     sync.Mutex
	w      *store.Writer
	sample []byte
	res    *intercept.CaptureResult
}

func (c *bodyCapture) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.res != nil {
		return len(p), nil
	}
	if len(c.sample) < 512 {
		c.sample = append(c.sample, p[:min(len(p), 512-len(c.sample))]...)
	}
	return c.w.Write(p)
}

func (c *bodyCapture) Done() intercept.CaptureResult {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.res == nil {
		r := c.w.Close()
		c.res = &intercept.CaptureResult{Hash: r.Hash, Size: r.Size, Stored: r.Stored, Sample: c.sample}
	}
	return *c.res
}

// ---- sessions ----

type state struct {
	Active string `json:"active"`
}

func (s *Service) sessionsDir() string { return filepath.Join(s.opts.DataDir, "sessions") }

func (s *Service) openActive() error {
	var st state
	if b, err := os.ReadFile(filepath.Join(s.opts.DataDir, "state.json")); err == nil {
		_ = json.Unmarshal(b, &st)
	}
	if st.Active != "" {
		if sess, err := s.open(st.Active); err == nil {
			s.sess = sess
			return nil
		}
	}
	list := s.listMeta()
	if len(list) > 0 {
		if sess, err := s.open(list[len(list)-1].ID); err == nil {
			s.sess = sess
			return s.saveState()
		}
	}
	sess, err := s.create("first session")
	if err != nil {
		return err
	}
	s.sess = sess
	return s.saveState()
}

func (s *Service) saveState() error {
	b, _ := json.Marshal(state{Active: s.sess.ID})
	return os.WriteFile(filepath.Join(s.opts.DataDir, "state.json"), b, 0o600)
}

func (s *Service) open(id string) (*Session, error) {
	if sess, ok := s.opened[id]; ok {
		return sess, nil
	}
	dir := filepath.Join(s.sessionsDir(), id)
	b, err := os.ReadFile(filepath.Join(dir, "session.json"))
	if err != nil {
		return nil, fmt.Errorf("no session %s", id)
	}
	var sess Session
	if err := json.Unmarshal(b, &sess); err != nil {
		return nil, err
	}
	st, err := store.Open(dir)
	if err != nil {
		return nil, err
	}
	sess.Dir, sess.Store = dir, st
	s.opened[id] = &sess
	return &sess, nil
}

func (s *Service) create(name string) (*Session, error) {
	n := 1
	for _, m := range s.listMeta() {
		if k, err := strconv.Atoi(strings.TrimPrefix(m.ID, "s")); err == nil && k >= n {
			n = k + 1
		}
	}
	id := "s" + strconv.Itoa(n)
	dir := filepath.Join(s.sessionsDir(), id)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	sess := &Session{ID: id, Name: name, Created: time.Now()}
	b, _ := json.MarshalIndent(sess, "", "  ")
	if err := os.WriteFile(filepath.Join(dir, "session.json"), b, 0o600); err != nil {
		return nil, err
	}
	return s.open(id)
}

func (s *Service) listMeta() []Session {
	entries, _ := os.ReadDir(s.sessionsDir())
	var out []Session
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		b, err := os.ReadFile(filepath.Join(s.sessionsDir(), e.Name(), "session.json"))
		if err != nil {
			continue
		}
		var m Session
		if json.Unmarshal(b, &m) == nil && m.ID != "" {
			out = append(out, m)
		}
	}
	sort.Slice(out, func(i, j int) bool { return sessNum(out[i].ID) < sessNum(out[j].ID) })
	return out
}

func sessNum(id string) int {
	n, _ := strconv.Atoi(strings.TrimPrefix(id, "s"))
	return n
}

// Current is the active session.
func (s *Service) Current() *Session {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.sess
}

// Session returns a session by id ("" = active).
func (s *Service) Session(id string) (*Session, error) {
	if id == "" {
		return s.Current(), nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.open(id)
}

// NewSession creates a session and makes it active.
func (s *Service) NewSession(name string) (*Session, error) {
	sess, err := s.newSession(name)
	if err == nil {
		s.scriptsSessionChanged()
	}
	return sess, err
}

func (s *Service) newSession(name string) (*Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if strings.TrimSpace(name) == "" {
		name = time.Now().Format("2006-01-02 15:04")
	}
	sess, err := s.create(name)
	if err != nil {
		return nil, err
	}
	s.sess = sess
	return sess, s.saveState()
}

// SwitchSession makes another session active.
func (s *Service) SwitchSession(id string) (*Session, error) {
	sess, err := s.switchSession(id)
	if err == nil {
		s.scriptsSessionChanged()
	}
	return sess, err
}

func (s *Service) switchSession(id string) (*Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, err := s.open(id)
	if err != nil {
		return nil, err
	}
	s.sess = sess
	return sess, s.saveState()
}

// RenameSession renames a session.
func (s *Service) RenameSession(id, name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, err := s.open(id)
	if err != nil {
		return err
	}
	sess.Name = name
	b, _ := json.MarshalIndent(sess, "", "  ")
	return os.WriteFile(filepath.Join(sess.Dir, "session.json"), b, 0o600)
}

// DeleteSession deletes a session that is not active.
func (s *Service) DeleteSession(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sess != nil && s.sess.ID == id {
		return errors.New("that is the active session: switch to or create another one first")
	}
	if sess, ok := s.opened[id]; ok {
		sess.Store.Close()
		delete(s.opened, id)
	}
	dir := filepath.Join(s.sessionsDir(), id)
	if _, err := os.Stat(filepath.Join(dir, "session.json")); err != nil {
		return fmt.Errorf("no session %s", id)
	}
	return os.RemoveAll(dir)
}

// SessionInfo is one row of the session list.
type SessionInfo struct {
	Session
	Active bool  `json:"active"`
	Flows  int   `json:"flows"`
	Disk   int64 `json:"disk_bytes"`
}

// Sessions lists all sessions.
func (s *Service) Sessions() []SessionInfo {
	cur := s.Current()
	var out []SessionInfo
	for _, m := range s.listMeta() {
		info := SessionInfo{Session: m, Active: cur != nil && cur.ID == m.ID}
		if sess, err := s.Session(m.ID); err == nil {
			info.Flows = sess.Store.Stats().Flows
			info.Disk = sess.Store.DiskBytes()
		}
		out = append(out, info)
	}
	return out
}
