package service

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
	"antigem/netcap/internal/rules"
	"antigem/netcap/internal/script"
	"antigem/netcap/internal/store"
)

// External hooks are separate programs (any language) that see traffic as JSON lines over a WebSocket at
// /v1/hooks; `netcap hook -- cmd` connects one for a command's stdin/stdout. observe hooks never hold up traffic;
// modify hooks get the request or response and answer within their timeout (default 200 ms), else it goes on
// unchanged.

// HookOptions of an external hook.
type HookOptions struct {
	Name    string
	Filter  string
	Mode    string   // observe (default) or modify
	Phases  []string // request, response, message, complete
	Timeout time.Duration
	Bodies  bool // send bodies (default true)
}

type extHook struct {
	s       *Service
	id      string
	opts    HookOptions
	filter  *query.Filter
	phases  map[string]bool
	out     chan []byte
	since   time.Time
	logs    []string
	logMu   sync.Mutex
	mu      sync.Mutex
	waiting map[string]chan hookReply
	next    atomic.Int64
	sent    atomic.Int64
	answers atomic.Int64
	late    atomic.Int64
	dropped atomic.Int64
	closed  chan struct{}
}

type hooksList struct {
	mu   sync.Mutex
	list atomic.Pointer[[]*extHook]
	next int
}

func (s *Service) extList(phase string) []*extHook {
	p := s.hooks.list.Load()
	if p == nil {
		return nil
	}
	var out []*extHook
	for _, h := range *p {
		if h.phases[phase] {
			out = append(out, h)
		}
	}
	return out
}

// extFor returns the hooks of a phase whose filter takes f.
func (s *Service) extFor(phase string, f *flow.Flow, b query.Bodies) []*extHook {
	var out []*extHook
	for _, h := range s.extList(phase) {
		if h.filter.Match(f, b) {
			out = append(out, h)
		}
	}
	return out
}

func (s *Service) extTrafficActive() bool {
	p := s.hooks.list.Load()
	if p == nil {
		return false
	}
	for _, h := range *p {
		if h.phases["request"] || h.phases["response"] || h.phases["message"] {
			return true
		}
	}
	return false
}

// ParseHookOptions reads the options from a query string (the WebSocket URL).
func ParseHookOptions(get func(string) string) (HookOptions, error) {
	o := HookOptions{Name: get("name"), Filter: get("filter"), Mode: strings.ToLower(get("mode")), Bodies: get("body") != "0" && get("body") != "false"}
	if o.Mode == "" {
		o.Mode = "observe"
	}
	if o.Mode != "observe" && o.Mode != "modify" {
		return o, errors.New("mode is observe or modify")
	}
	for _, p := range strings.FieldsFunc(get("phases"), func(r rune) bool { return r == ',' || r == ' ' }) {
		p = strings.ToLower(strings.TrimSpace(p))
		switch p {
		case "request", "response", "message", "complete":
			o.Phases = append(o.Phases, p)
		default:
			return o, fmt.Errorf("phases: request, response, message, complete (got %q)", p)
		}
	}
	if len(o.Phases) == 0 {
		if o.Mode == "modify" {
			o.Phases = []string{"request", "response"}
		} else {
			o.Phases = []string{"complete"}
		}
	}
	o.Timeout = 200 * time.Millisecond
	if t := get("timeout"); t != "" {
		d, err := time.ParseDuration(t)
		if err != nil {
			if n, err2 := strconv.Atoi(t); err2 == nil {
				d = time.Duration(n) * time.Millisecond
			} else {
				return o, errors.New("timeout: a duration like 200ms")
			}
		}
		o.Timeout = min(max(d, 10*time.Millisecond), 10*time.Second)
	}
	return o, nil
}

// RunHook serves one external hook until recv fails (the hook went away). recv and send move one JSON message.
func (s *Service) RunHook(ctx context.Context, o HookOptions, recv func() ([]byte, error), send func([]byte) error) error {
	f, err := query.Parse(o.Filter)
	if err != nil {
		return fmt.Errorf("filter: %w", err)
	}
	h := &extHook{s: s, opts: o, filter: f, phases: map[string]bool{}, out: make(chan []byte, 1024), since: time.Now(),
		waiting: map[string]chan hookReply{}, closed: make(chan struct{})}
	for _, p := range o.Phases {
		h.phases[p] = true
	}
	s.hooks.mu.Lock()
	s.hooks.next++
	h.id = "k" + strconv.Itoa(s.hooks.next)
	if h.opts.Name == "" {
		h.opts.Name = h.id
	}
	var list []*extHook
	if p := s.hooks.list.Load(); p != nil {
		list = append(list, *p...)
	}
	list = append(list, h)
	s.hooks.list.Store(&list)
	s.hooks.mu.Unlock()
	defer func() {
		s.hooks.mu.Lock()
		var keep []*extHook
		if p := s.hooks.list.Load(); p != nil {
			for _, x := range *p {
				if x != h {
					keep = append(keep, x)
				}
			}
		}
		s.hooks.list.Store(&keep)
		s.hooks.mu.Unlock()
		close(h.closed)
	}()
	hello, _ := json.Marshal(map[string]any{"type": "hello", "hook": h.id, "name": h.opts.Name, "mode": o.Mode, "phases": o.Phases,
		"filter": o.Filter, "timeout_ms": o.Timeout.Milliseconds()})
	if err := send(hello); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case b := <-h.out:
				if send(b) != nil {
					cancel()
					return
				}
			}
		}
	}()
	for {
		b, err := recv()
		if err != nil {
			return nil
		}
		var r hookReply
		if json.Unmarshal(b, &r) != nil {
			h.log("bad line from the hook: " + Cut(string(b), 120))
			continue
		}
		switch r.Type {
		case "log":
			h.log(r.Text)
			continue
		case "finding":
			fl, _, _ := ParseID(r.Flow)
			s.Current().Store.AddFinding(store.Finding{Script: "hook:" + h.opts.Name, Severity: normSeverity(r.Severity), Title: Cut(r.Title, 300),
				Detail: Cut(r.Detail, 4000), Flow: fl})
			continue
		}
		if r.ID == "" {
			continue
		}
		h.mu.Lock()
		ch := h.waiting[r.ID]
		delete(h.waiting, r.ID)
		h.mu.Unlock()
		if ch == nil {
			h.late.Add(1)
			continue
		}
		h.answers.Add(1)
		ch <- r
	}
}

func normSeverity(s string) string {
	switch strings.ToLower(s) {
	case "warn", "warning", "medium":
		return "warn"
	case "error", "high", "critical":
		return "error"
	}
	return "info"
}

func (h *extHook) log(text string) {
	h.logMu.Lock()
	defer h.logMu.Unlock()
	h.logs = append(h.logs, time.Now().Format("15:04:05")+" "+Cut(text, 500))
	if len(h.logs) > 100 {
		h.logs = h.logs[len(h.logs)-100:]
	}
}

// hookReply is a line from the hook: an answer ({"id","action",…}) or a log / finding.
type hookReply struct {
	ID       string         `json:"id"`
	Type     string         `json:"type"`
	Action   string         `json:"action"`
	Patch    Patch          `json:"patch"`
	Response *hookResponse  `json:"response"`
	Data     *string        `json:"data"`
	DataB64  string         `json:"data_base64"`
	Tag      string         `json:"tag"`
	Note     string         `json:"note"`
	Text     string         `json:"text"`
	Severity string         `json:"severity"`
	Title    string         `json:"title"`
	Detail   string         `json:"detail"`
	Flow     string         `json:"flow"`
}

type hookResponse struct {
	Status  int               `json:"status"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
	JSON    any               `json:"json"`
}

type hookSide struct {
	Status      int          `json:"status,omitempty"`
	Headers     flow.Headers `json:"headers"`
	Body        string       `json:"body,omitempty"`
	BodyB64     string       `json:"body_base64,omitempty"`
	BodySize    int          `json:"body_size,omitempty"`
	BodyOmitted string       `json:"body_omitted,omitempty"`
}

type hookMsg struct {
	Type     string    `json:"type"`
	ID       string    `json:"id,omitempty"`
	Flow     string    `json:"flow"`
	App      string    `json:"app,omitempty"`
	Method   string    `json:"method,omitempty"`
	URL      string    `json:"url,omitempty"`
	Host     string    `json:"host,omitempty"`
	Path     string    `json:"path,omitempty"`
	Request  *hookSide `json:"request,omitempty"`
	Response *hookSide `json:"response,omitempty"`
	Status   int       `json:"status,omitempty"`
	Error    string    `json:"error,omitempty"`
	TimeMs   float64   `json:"time_ms,omitempty"`
	Tags     []string  `json:"tags,omitempty"`
	Dir      string    `json:"dir,omitempty"`
	MsgType  string    `json:"msg_type,omitempty"`
	Data     string    `json:"data,omitempty"`
	DataB64  string    `json:"data_base64,omitempty"`
	Index    *int      `json:"index,omitempty"`
}

const hookBodyMax = 1 << 20

func putBody(side *hookSide, b []byte, with bool) {
	side.BodySize = len(b)
	switch {
	case len(b) == 0:
	case !with:
		side.BodyOmitted = "bodies are off for this hook"
	case len(b) > hookBodyMax:
		side.BodyOmitted = "bigger than 1 MB"
	case utf8.Valid(b):
		side.Body = string(b)
	default:
		side.BodyB64 = base64.StdEncoding.EncodeToString(b)
	}
}

// ask sends a message and waits for the answer (modify mode). ok=false: no answer in time, or observe mode.
func (h *extHook) ask(m *hookMsg) (hookReply, bool) {
	wait := h.opts.Mode == "modify" && m.Type != "complete"
	if wait {
		m.ID = h.id + "." + strconv.FormatInt(h.next.Add(1), 10)
	}
	b, err := json.Marshal(m)
	if err != nil {
		return hookReply{}, false
	}
	var ch chan hookReply
	if wait {
		ch = make(chan hookReply, 1)
		h.mu.Lock()
		h.waiting[m.ID] = ch
		h.mu.Unlock()
	}
	select {
	case h.out <- b:
		h.sent.Add(1)
	default:
		h.dropped.Add(1)
		if wait {
			h.mu.Lock()
			delete(h.waiting, m.ID)
			h.mu.Unlock()
		}
		return hookReply{}, false
	}
	if !wait {
		return hookReply{}, false
	}
	t := time.NewTimer(h.opts.Timeout)
	defer t.Stop()
	select {
	case r := <-ch:
		return r, true
	case <-t.C:
	case <-h.closed:
	}
	h.mu.Lock()
	delete(h.waiting, m.ID)
	h.mu.Unlock()
	return hookReply{}, false
}

func (h *extHook) baseMsg(typ string, f *flow.Flow) *hookMsg {
	return &hookMsg{Type: typ, Flow: FID(f.ID), App: f.App, Method: f.Method, URL: f.URL(), Host: f.Host, Path: f.Path}
}

func (r hookReply) decision() Decision {
	d := Decision{Action: "continue", Patch: r.Patch}
	switch r.Action {
	case "drop":
		d.Action = "drop"
	case "respond":
		d.Action = "respond"
		m := &rules.Action{Type: "mock", Status: 200}
		if r.Response != nil {
			if r.Response.Status != 0 {
				m.Status = r.Response.Status
			}
			m.Headers, m.Body, m.JSON = r.Response.Headers, r.Response.Body, r.Response.JSON
		}
		d.Mock = m
	case "modify":
	default:
		d.Patch = Patch{}
	}
	return d
}

func (s *Service) runExtRequest(ctx context.Context, f *flow.Flow, req *http.Request, lb *liveBody, get func() []byte,
	bodies hookBodies, list []*extHook) (*http.Response, error) {
	for _, h := range list {
		if !h.filter.Match(f, bodies) {
			continue
		}
		m := h.baseMsg("request", f)
		m.Method = req.Method
		side := &hookSide{Headers: flow.FromHTTP(req.Header, req.Host)}
		if h.opts.Bodies && req.Body != nil && req.Body != http.NoBody {
			putBody(side, get(), true)
		}
		m.Request = side
		r, ok := h.ask(m)
		if !ok {
			continue
		}
		applyTagNote(f, r)
		d := r.decision()
		if d.Action == "continue" && d.Patch.Empty() {
			continue
		}
		mark(f, "hook:"+h.opts.Name)
		resp, err := s.applyDecision(ctx, f, "request", req, nil, lb, get, d)
		if err != nil || resp != nil {
			return resp, err
		}
	}
	return nil, nil
}

func (s *Service) runExtResponse(ctx context.Context, f *flow.Flow, req *http.Request, resp *http.Response, lb *liveBody,
	get func() []byte, bodies hookBodies, list []*extHook) (*http.Response, error) {
	for _, h := range list {
		f.Status = resp.StatusCode
		f.ResHeaders = flow.FromHTTP(resp.Header, "")
		if !h.filter.Match(f, bodies) {
			continue
		}
		m := h.baseMsg("response", f)
		m.Request = &hookSide{Headers: flow.FromHTTP(req.Header, req.Host)}
		side := &hookSide{Status: resp.StatusCode, Headers: flow.FromHTTP(resp.Header, "")}
		if h.opts.Bodies {
			putBody(side, get(), true)
		}
		m.Response = side
		r, ok := h.ask(m)
		if !ok {
			continue
		}
		applyTagNote(f, r)
		d := r.decision()
		if d.Action == "continue" && d.Patch.Empty() {
			continue
		}
		mark(f, "hook:"+h.opts.Name)
		r2, err := s.applyDecision(ctx, f, "response", req, resp, lb, get, d)
		if err != nil || r2 != nil {
			return r2, err
		}
	}
	return nil, nil
}

func applyTagNote(f *flow.Flow, r hookReply) {
	if r.Tag != "" {
		f.AddTag(r.Tag)
	}
	if r.Note != "" {
		f.Note = strings.TrimSpace(f.Note + " " + r.Note)
	}
}

type msgDecision struct {
	drop, changed bool
	typ           string
	data          []byte
}

func (h *extHook) message(f *flow.Flow, m script.Message, index int) (msgDecision, bool) {
	msg := h.baseMsg("message", f)
	msg.Dir, msg.MsgType, msg.Index = m.Dir, m.Type, &index
	if m.Type == "text" && utf8.Valid(m.Data) {
		msg.Data = string(m.Data)
	} else {
		msg.DataB64 = base64.StdEncoding.EncodeToString(m.Data)
	}
	r, ok := h.ask(msg)
	if !ok {
		return msgDecision{}, false
	}
	switch r.Action {
	case "drop":
		return msgDecision{drop: true}, true
	case "modify":
		if r.Data != nil {
			return msgDecision{changed: true, typ: "text", data: []byte(*r.Data)}, true
		}
		if r.DataB64 != "" {
			if b, err := base64.StdEncoding.DecodeString(r.DataB64); err == nil {
				return msgDecision{changed: true, typ: "binary", data: b}, true
			}
		}
	}
	return msgDecision{}, true
}

func (h *extHook) complete(sess *Session, f *flow.Flow) {
	m := h.baseMsg("complete", f)
	m.Status, m.Error, m.TimeMs, m.Tags = f.Status, f.Error, f.Timings.Total, f.Tags
	if f.Kind == flow.KindHTTP || f.Kind == flow.KindGRPC {
		req := &hookSide{Headers: f.ReqHeaders}
		res := &hookSide{Status: f.Status, Headers: f.ResHeaders}
		if h.opts.Bodies {
			if b, _, err := h.s.Body(sess, f, "req", false, hookBodyMax+1); err == nil {
				putBody(req, b, true)
			}
			if b, _, err := h.s.Body(sess, f, "res", false, hookBodyMax+1); err == nil {
				putBody(res, b, true)
			}
		}
		m.Request, m.Response = req, res
	}
	h.ask(m)
}

// HooksText lists the connected external hooks.
func (s *Service) HooksText() string {
	p := s.hooks.list.Load()
	if p == nil || len(*p) == 0 {
		return ""
	}
	var b strings.Builder
	b.WriteString("external hooks:\n")
	for _, h := range *p {
		fmt.Fprintf(&b, "%s %s · %s · phases %s", h.id, h.opts.Name, h.opts.Mode, strings.Join(h.opts.Phases, ","))
		if h.opts.Filter != "" {
			b.WriteString(" · filter `" + h.opts.Filter + "`")
		}
		fmt.Fprintf(&b, " · since %s · %d sent, %d answers", h.since.Format("15:04:05"), h.sent.Load(), h.answers.Load())
		if n := h.late.Load(); n > 0 {
			fmt.Fprintf(&b, ", %d too late (over %s)", n, h.opts.Timeout)
		}
		if n := h.dropped.Load(); n > 0 {
			fmt.Fprintf(&b, ", %d dropped (the hook reads too slowly)", n)
		}
		b.WriteByte('\n')
		h.logMu.Lock()
		for _, l := range h.logs[max(0, len(h.logs)-5):] {
			b.WriteString("    " + l + "\n")
		}
		h.logMu.Unlock()
	}
	return b.String()
}
