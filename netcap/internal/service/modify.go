package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/intercept"
	"antigem/netcap/internal/redact"
	"antigem/netcap/internal/rules"
	"antigem/netcap/internal/view"
)

const editMax = 8 << 20 // bodies up to this size can be changed by rules and breakpoints

// ---- hooks (intercept.Hooks) ----

// Active: a rule, a script with a traffic hook or an external hook is on (the engine then calls the hooks).
func (s *Service) Active() bool {
	return (s.Rules != nil && s.Rules.EnabledCount() > 0) || (s.Scripts != nil && s.Scripts.TrafficActive()) || s.extTrafficActive()
}

func mark(f *flow.Flow, flags ...string) {
	for _, fl := range flags {
		if !hasFlag(f, fl) {
			f.Flags = append(f.Flags, fl)
		}
	}
}

func hasFlag(f *flow.Flow, flag string) bool {
	for _, x := range f.Flags {
		if x == flag {
			return true
		}
	}
	return false
}

// liveBody reads a request or response body once (keeping the original bytes) and lets rules change it.
type liveBody struct {
	read     bool
	raw      []byte        // as received (content-encoded)
	data     []byte        // decoded; after an edit: what is sent
	rest     io.ReadCloser // tooBig: the part not read yet
	tooBig   bool
	err      error
	replaced bool
	decoded  bool // data is the decoded form of raw
}

func (b *liveBody) load(rc io.ReadCloser, encoding string) ([]byte, error) {
	if b.read {
		if b.tooBig {
			return nil, b.err
		}
		return b.data, b.err
	}
	b.read = true
	if rc == nil || rc == http.NoBody {
		return nil, nil
	}
	raw, err := io.ReadAll(io.LimitReader(rc, editMax+1))
	if err != nil {
		b.err = err
		rc.Close()
		return nil, err
	}
	b.raw = raw
	if len(raw) > editMax {
		b.tooBig = true
		b.rest = rc // the rest still goes to the other side
		b.err = fmt.Errorf("body bigger than %s: too big to change", Size(editMax))
		return nil, b.err
	}
	rc.Close()
	b.data = raw
	if encoding != "" {
		if d, err := view.Decode(raw, encoding); err == nil {
			b.data, b.decoded = d, true
		}
	}
	return b.data, nil
}

// reader gives the body to send on: the original bytes, or the changed ones.
func (b *liveBody) reader() io.ReadCloser {
	switch {
	case b.tooBig:
		return struct {
			io.Reader
			io.Closer
		}{io.MultiReader(bytes.NewReader(b.raw), b.rest), b.rest}
	case b.replaced:
		return io.NopCloser(bytes.NewReader(b.data))
	}
	return io.NopCloser(bytes.NewReader(b.raw))
}

func (b *liveBody) size() int64 {
	switch {
	case b.tooBig:
		return -1
	case b.replaced:
		return int64(len(b.data))
	}
	return int64(len(b.raw))
}

// reqHookBodies gives filters the request body (and the stored response body is not there yet).
type hookBodies struct {
	req func() []byte
	res func() []byte
}

func (h hookBodies) Body(_ *flow.Flow, side string) []byte {
	if side == "req" && h.req != nil {
		return h.req()
	}
	if side == "res" && h.res != nil {
		return h.res()
	}
	return nil
}

// Request applies request-phase rules, then onRequest scripts, then external hooks.
func (s *Service) Request(ctx context.Context, f *flow.Flow, req *http.Request) (*http.Response, error) {
	list := s.Rules.Phase("request")
	scripts := s.Scripts.For("request")
	ext := s.extList("request")
	if len(list) == 0 && len(scripts) == 0 && len(ext) == 0 {
		return nil, nil
	}
	lb := &liveBody{}
	get := func() []byte {
		wasRead := lb.read
		d, _ := lb.load(req.Body, req.Header.Get("Content-Encoding"))
		if !wasRead && lb.read { // read now: the request gets the bytes back
			req.Body = lb.reader()
		}
		return d
	}
	bodies := hookBodies{req: get}
	defer func() {
		if lb.read {
			setReqBody(req, lb)
			if !lb.tooBig && s.wantsReqBodyLater() {
				s.reqBodies.Store(f, lb.data)
			}
		}
	}()
	for _, r := range list {
		if !r.Filter().Match(f, bodies) {
			continue
		}
		r.Hit()
		mark(f, "rule:"+r.ID)
		a := r.Action
		switch a.Type {
		case "block":
			if a.Status == 0 {
				return nil, intercept.ErrBlock
			}
			mark(f, "mocked")
			return textResp(req, a.Status, "blocked by rule "+r.ID), nil
		case "mock":
			mark(f, "mocked")
			return s.mockResp(req, a), nil
		case "map_local":
			mark(f, "mocked")
			return s.localResp(req, a), nil
		case "map_remote":
			mark(f, "mapped")
			return s.remoteResp(ctx, f, req, s.mapURL(req, a))
		case "rewrite":
			if err := s.rewriteRequest(req, lb, get, r); err != nil {
				return nil, err
			}
			mark(f, "modified")
		case "delay":
			if err := sleepCtx(ctx, time.Duration(a.Ms)*time.Millisecond); err != nil {
				return nil, err
			}
		case "tag":
			f.AddTag(a.Tag)
		case "set_var":
			s.setVar(a, req, nil, get, nil)
		case "breakpoint":
			resp, err := s.pause(ctx, f, r, "request", req, nil, lb, get)
			if err != nil || resp != nil {
				return resp, err
			}
		}
	}
	if resp, err := s.runScriptsRequest(ctx, f, req, lb, get, bodies, scripts); resp != nil || err != nil {
		return resp, err
	}
	return s.runExtRequest(ctx, f, req, lb, get, bodies, ext)
}

func setReqBody(req *http.Request, lb *liveBody) {
	req.Body = lb.reader()
	if lb.tooBig {
		return
	}
	req.ContentLength = lb.size()
	if lb.replaced {
		if lb.decoded {
			req.Header.Del("Content-Encoding") // the changed body goes out decoded
		}
		req.Header.Set("Content-Length", strconv.FormatInt(lb.size(), 10))
		req.TransferEncoding = nil
	}
}

// Response applies response-phase rules, then onResponse scripts, then external hooks.
func (s *Service) Response(ctx context.Context, f *flow.Flow, req *http.Request, resp *http.Response) (*http.Response, error) {
	list := s.Rules.Phase("response")
	scripts := s.Scripts.For("response")
	ext := s.extList("response")
	if len(list) == 0 && len(scripts) == 0 && len(ext) == 0 {
		return resp, nil
	}
	lb := &liveBody{}
	get := func() []byte {
		wasRead := lb.read
		d, _ := lb.load(resp.Body, resp.Header.Get("Content-Encoding"))
		if !wasRead && lb.read {
			resp.Body = lb.reader()
		}
		return d
	}
	bodies := hookBodies{res: get}
	cur := resp
	for _, r := range list {
		f.Status = cur.StatusCode
		f.ResHeaders = flow.FromHTTP(cur.Header, "")
		f.Res.ContentType = cur.Header.Get("Content-Type")
		if !r.Filter().Match(f, bodies) {
			continue
		}
		r.Hit()
		mark(f, "rule:"+r.ID)
		a := r.Action
		switch a.Type {
		case "block":
			if a.Status == 0 {
				return nil, intercept.ErrBlock
			}
			mark(f, "mocked")
			return textResp(req, a.Status, "blocked by rule "+r.ID), nil
		case "mock":
			mark(f, "mocked")
			return s.mockResp(req, a), nil
		case "map_local":
			mark(f, "mocked")
			return s.localResp(req, a), nil
		case "rewrite":
			if err := s.rewriteResponse(cur, lb, get, r); err != nil {
				return nil, err
			}
			mark(f, "modified")
		case "delay":
			if a.Ms > 0 {
				if err := sleepCtx(ctx, time.Duration(a.Ms)*time.Millisecond); err != nil {
					return nil, err
				}
			}
			if a.KBps > 0 {
				cur.Body = &throttled{r: cur.Body, bps: a.KBps * 1024, start: time.Now()}
			}
		case "tag":
			f.AddTag(a.Tag)
		case "set_var":
			s.setVar(a, req, cur, nil, get)
		case "breakpoint":
			r2, err := s.pause(ctx, f, r, "response", req, cur, lb, get)
			if err != nil {
				return nil, err
			}
			if r2 != nil {
				return r2, nil
			}
		}
	}
	if r2, err := s.runScriptsResponse(ctx, f, req, cur, lb, get, bodies, scripts); err != nil || r2 != nil {
		return r2, err
	}
	if r2, err := s.runExtResponse(ctx, f, req, cur, lb, get, bodies, ext); err != nil || r2 != nil {
		return r2, err
	}
	if lb.read && cur == resp {
		setResBody(cur, lb)
	}
	return cur, nil
}

func setResBody(resp *http.Response, lb *liveBody) {
	if t, ok := resp.Body.(*throttled); ok { // keep a throttle around the new body
		t.r = lb.reader()
	} else {
		resp.Body = lb.reader()
	}
	if lb.tooBig {
		return
	}
	resp.ContentLength = lb.size()
	resp.TransferEncoding = nil
	resp.Header.Set("Content-Length", strconv.FormatInt(lb.size(), 10))
	if lb.replaced && lb.decoded {
		resp.Header.Del("Content-Encoding")
	}
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

type throttled struct {
	r     io.ReadCloser
	bps   int
	start time.Time
	n     int64
}

func (t *throttled) Read(p []byte) (int, error) {
	if len(p) > t.bps/10+1 {
		p = p[:t.bps/10+1]
	}
	n, err := t.r.Read(p)
	t.n += int64(n)
	want := time.Duration(float64(t.n) / float64(t.bps) * float64(time.Second))
	if d := want - time.Since(t.start); d > 0 {
		time.Sleep(d)
	}
	return n, err
}

func (t *throttled) Close() error { return t.r.Close() }

func textResp(req *http.Request, status int, text string) *http.Response {
	return newResp(req, status, map[string]string{"Content-Type": "text/plain; charset=utf-8"}, []byte(text))
}

func newResp(req *http.Request, status int, headers map[string]string, body []byte) *http.Response {
	if status == 0 {
		status = 200
	}
	h := http.Header{}
	for k, v := range headers {
		h.Set(k, v)
	}
	h.Set("Content-Length", strconv.Itoa(len(body)))
	h.Set("X-Netcap", "mock")
	return &http.Response{StatusCode: status, Status: fmt.Sprintf("%d %s", status, http.StatusText(status)), Proto: "HTTP/1.1",
		ProtoMajor: 1, ProtoMinor: 1, Header: h, Body: io.NopCloser(bytes.NewReader(body)), ContentLength: int64(len(body)), Request: req}
}

func (s *Service) mockResp(req *http.Request, a rules.Action) *http.Response {
	headers := map[string]string{}
	for k, v := range a.Headers {
		headers[k] = s.Rules.Expand(v)
	}
	var body []byte
	switch {
	case a.JSON != nil:
		body, _ = json.Marshal(s.Rules.ExpandAny(a.JSON))
		if _, ok := headers["Content-Type"]; !ok {
			headers["Content-Type"] = "application/json"
		}
	case a.File != "":
		if b, err := os.ReadFile(a.File); err == nil {
			body = b
		} else {
			return textResp(req, 500, "netcap mock: "+err.Error())
		}
	default:
		body = []byte(s.Rules.Expand(a.Body))
	}
	if a.ContentType != "" {
		headers["Content-Type"] = a.ContentType
	}
	if _, ok := headers["Content-Type"]; !ok && len(body) > 0 {
		headers["Content-Type"] = "text/plain; charset=utf-8"
	}
	return newResp(req, a.Status, headers, body)
}

// localResp serves a file, or a file inside a folder by the request path.
func (s *Service) localResp(req *http.Request, a rules.Action) *http.Response {
	p := a.File
	if st, err := os.Stat(p); err == nil && st.IsDir() {
		rel := filepath.Clean("/" + req.URL.Path)
		p = filepath.Join(p, rel)
		if st2, err := os.Stat(p); err == nil && st2.IsDir() {
			p = filepath.Join(p, "index.html")
		}
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return textResp(req, 404, "netcap map_local: "+err.Error())
	}
	ct := a.ContentType
	if ct == "" {
		ct = mime.TypeByExtension(filepath.Ext(p))
	}
	if ct == "" {
		ct = http.DetectContentType(b)
	}
	headers := map[string]string{"Content-Type": ct}
	for k, v := range a.Headers {
		headers[k] = s.Rules.Expand(v)
	}
	return newResp(req, a.Status, headers, b)
}

func (s *Service) mapURL(req *http.Request, a rules.Action) string {
	base := strings.TrimRight(s.Rules.Expand(a.URL), "/")
	if a.KeepPath != nil && !*a.KeepPath {
		return base
	}
	u := base + req.URL.EscapedPath()
	if req.URL.RawQuery != "" {
		u += "?" + req.URL.RawQuery
	}
	return u
}

// remoteResp sends req to another URL (map_remote, a breakpoint edit of the host) and returns the answer.
func (s *Service) remoteResp(ctx context.Context, f *flow.Flow, req *http.Request, to string) (*http.Response, error) {
	u, err := url.Parse(to)
	if err != nil {
		return nil, err
	}
	out := req.Clone(ctx)
	out.URL = u
	out.Host = u.Host
	out.RequestURI = ""
	out.Header.Del("Host")
	if v, ok := out.Header["User-Agent"]; ok && len(v) == 1 && v[0] == "" {
		delete(out.Header, "User-Agent")
	}
	f.Note = strings.TrimSpace(f.Note + " sent to " + to)
	resp, err := s.client().Do(out)
	if err != nil {
		return nil, fmt.Errorf("sending to %s: %w", to, err)
	}
	return resp, nil
}

func (s *Service) rewriteRequest(req *http.Request, lb *liveBody, get func() []byte, r *rules.Rule) error {
	a := r.Action
	if a.SetMethod != "" {
		req.Method = strings.ToUpper(a.SetMethod)
	}
	for k, v := range a.SetHeaders {
		if strings.EqualFold(k, "Host") {
			req.Host = s.Rules.Expand(v)
			continue
		}
		req.Header.Set(k, s.Rules.Expand(v))
	}
	for _, k := range a.RemoveHeaders {
		req.Header.Del(k)
	}
	if len(a.SetQuery) > 0 || len(a.RemoveQuery) > 0 {
		q := req.URL.Query()
		for k, v := range a.SetQuery {
			q.Set(k, s.Rules.Expand(v))
		}
		for _, k := range a.RemoveQuery {
			q.Del(k)
		}
		req.URL.RawQuery = q.Encode()
	}
	return editBody(lb, get, r, s.Rules)
}

func (s *Service) rewriteResponse(resp *http.Response, lb *liveBody, get func() []byte, r *rules.Rule) error {
	a := r.Action
	if a.SetStatus != 0 {
		resp.StatusCode = a.SetStatus
		resp.Status = fmt.Sprintf("%d %s", a.SetStatus, http.StatusText(a.SetStatus))
	}
	for k, v := range a.SetHeaders {
		resp.Header.Set(k, s.Rules.Expand(v))
	}
	for _, k := range a.RemoveHeaders {
		resp.Header.Del(k)
	}
	return editBody(lb, get, r, s.Rules)
}

func editBody(lb *liveBody, get func() []byte, r *rules.Rule, set *rules.Set) error {
	a := r.Action
	if len(a.Replace) == 0 && len(a.JSONSet) == 0 && len(a.JSONDelete) == 0 && a.Body == "" && a.JSON == nil && !a.ClearBody {
		return nil
	}
	data := get()
	if lb.tooBig {
		return nil // too big to change: headers and status still apply
	}
	if lb.err != nil {
		return lb.err
	}
	switch {
	case a.ClearBody:
		data = nil
	case a.JSON != nil:
		data, _ = json.Marshal(set.ExpandAny(a.JSON))
	case a.Body != "":
		data = []byte(set.Expand(a.Body))
	}
	for i, rp := range a.Replace {
		with := set.Expand(rp.With)
		if re := r.Regexps()[i]; re != nil {
			data = re.ReplaceAll(data, []byte(with))
		} else {
			data = bytes.ReplaceAll(data, []byte(rp.Find), []byte(with))
		}
	}
	if len(a.JSONSet) > 0 || len(a.JSONDelete) > 0 {
		out, isJSON, err := rules.EditJSON(data, a.JSONSet, a.JSONDelete, set.ExpandAny)
		if err != nil {
			return err
		}
		if isJSON {
			data = out
		}
	}
	lb.data = data
	lb.replaced = true
	return nil
}

// setVar copies a value into a variable: res.json:.path, req.json:.path, res.header:Name, req.header:Name,
// res.body:~regex (first group), req.body:~regex, query:key.
func (s *Service) setVar(a rules.Action, req *http.Request, resp *http.Response, reqBody, resBody func() []byte) {
	src, arg, _ := strings.Cut(a.From, ":")
	var val string
	switch src {
	case "res.json", "req.json":
		get := resBody
		if src == "req.json" {
			get = reqBody
		}
		if get == nil {
			return
		}
		if v, ok := rules.GetJSON(get(), arg); ok {
			if s2, isStr := v.(string); isStr {
				val = s2
			} else {
				b, _ := json.Marshal(v)
				val = string(b)
			}
		}
	case "res.header":
		if resp != nil {
			val = resp.Header.Get(arg)
		}
	case "req.header":
		val = req.Header.Get(arg)
	case "query":
		val = req.URL.Query().Get(arg)
	case "res.body", "req.body":
		get := resBody
		if src == "req.body" {
			get = reqBody
		}
		if get == nil {
			return
		}
		re, err := regexp.Compile(strings.TrimPrefix(arg, "~"))
		if err != nil {
			return
		}
		if m := re.FindSubmatch(get()); len(m) > 1 {
			val = string(m[1])
		} else if len(m) == 1 {
			val = string(m[0])
		}
	}
	if val != "" {
		s.Rules.SetVar(a.Var, val)
	}
}

// ---- breakpoints ----

// Paused is a flow stopped by a breakpoint, waiting for net_intercept (or the GUI).
type Paused struct {
	ID       string        `json:"id"`
	Rule     string        `json:"rule"`
	Phase    string        `json:"phase"`
	FlowID   int64         `json:"flow"`
	Session  string        `json:"session"`
	Method   string        `json:"method"`
	URL      string        `json:"url"`
	Status   int           `json:"status,omitempty"`
	Headers  flow.Headers  `json:"headers"`
	Body     []byte        `json:"-"`
	BodyType string        `json:"body_type,omitempty"`
	Since    time.Time     `json:"since"`
	Timeout  time.Duration `json:"-"`
	decide   chan Decision
}

// Decision for a paused flow.
type Decision struct {
	Action string        // continue (default), drop, respond
	Patch  Patch         // continue: changes
	Mock   *rules.Action // respond: the answer (status, headers, body, json)
}

// Patch changes a request (net_send, breakpoint resume) or a response (breakpoint resume).
type Patch struct {
	Method        string            `json:"method,omitempty"`
	URL           string            `json:"url,omitempty"`
	SetHeaders    map[string]string `json:"set_headers,omitempty"`
	RemoveHeaders []string          `json:"remove_headers,omitempty"`
	Query         map[string]string `json:"query,omitempty"`
	BodyText      *string           `json:"body_text,omitempty"`
	JSONSet       map[string]any    `json:"json_set,omitempty"`
	JSONDelete    []string          `json:"json_delete,omitempty"`
	Replace       []rules.Replace   `json:"replace,omitempty"`
	Status        int               `json:"status,omitempty"`
}

// Empty reports whether the patch changes nothing.
func (p Patch) Empty() bool {
	return p.Method == "" && p.URL == "" && len(p.SetHeaders) == 0 && len(p.RemoveHeaders) == 0 && len(p.Query) == 0 &&
		p.BodyText == nil && len(p.JSONSet) == 0 && len(p.JSONDelete) == 0 && len(p.Replace) == 0 && p.Status == 0
}

func (p Patch) asRule() (*rules.Rule, error) {
	a := rules.Action{Type: "rewrite", SetHeaders: p.SetHeaders, RemoveHeaders: p.RemoveHeaders, SetQuery: p.Query,
		Replace: p.Replace, JSONSet: p.JSONSet, JSONDelete: p.JSONDelete, SetMethod: p.Method, SetStatus: p.Status}
	if p.BodyText != nil {
		a.Body = *p.BodyText
		a.ClearBody = a.Body == ""
	}
	r := &rules.Rule{ID: "patch", Enabled: true, Action: a}
	if err := r.Validate(); err != nil {
		return nil, err
	}
	return r, nil
}

type pausedSet struct {
	mu   sync.Mutex
	m    map[string]*Paused
	next int
}

func (s *Service) pause(ctx context.Context, f *flow.Flow, r *rules.Rule, phase string, req *http.Request, resp *http.Response,
	lb *liveBody, get func() []byte) (*http.Response, error) {
	body := get()
	p := &Paused{Rule: r.ID, Phase: phase, FlowID: f.ID, Session: s.sessOf(f).ID, Method: req.Method, URL: f.URL(),
		Since: time.Now(), decide: make(chan Decision, 1)}
	if phase == "response" {
		p.Status = resp.StatusCode
		p.Headers = flow.FromHTTP(resp.Header, "")
		p.BodyType = view.ShortType(resp.Header.Get("Content-Type"), body)
	} else {
		p.Headers = flow.FromHTTP(req.Header, req.Host)
		p.BodyType = view.ShortType(req.Header.Get("Content-Type"), body)
	}
	if len(body) > 1<<20 {
		p.Body = body[:1<<20]
	} else {
		p.Body = body
	}
	timeout := time.Duration(r.Action.TimeoutS) * time.Second
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	p.Timeout = timeout
	s.paused.mu.Lock()
	if s.paused.m == nil {
		s.paused.m = map[string]*Paused{}
	}
	s.paused.next++
	p.ID = "x" + strconv.Itoa(s.paused.next)
	s.paused.m[p.ID] = p
	s.paused.mu.Unlock()
	defer func() {
		s.paused.mu.Lock()
		delete(s.paused.m, p.ID)
		s.paused.mu.Unlock()
	}()
	mark(f, "paused")
	s.Hub.Publish(Event{Session: p.Session, Flow: f.Clone(), Paused: p.ID})
	t := time.NewTimer(timeout)
	defer t.Stop()
	var d Decision
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-t.C:
		f.Note = strings.TrimSpace(f.Note + " breakpoint " + p.ID + " continued after " + timeout.String())
		return nil, nil
	case d = <-p.decide:
	}
	return s.applyDecision(ctx, f, phase, req, resp, lb, get, d)
}

// applyDecision carries out what a breakpoint's resolver or an external hook decided.
func (s *Service) applyDecision(ctx context.Context, f *flow.Flow, phase string, req *http.Request, resp *http.Response,
	lb *liveBody, get func() []byte, d Decision) (*http.Response, error) {
	switch d.Action {
	case "drop":
		return nil, intercept.ErrBlock
	case "respond":
		mark(f, "mocked")
		a := rules.Action{Type: "mock", Status: 200}
		if d.Mock != nil {
			a = *d.Mock
		}
		return s.mockResp(req, a), nil
	}
	if d.Patch.Empty() {
		return nil, nil
	}
	if phase == "request" && d.Patch.URL != "" {
		if u, err := url.Parse(d.Patch.URL); err == nil && (u.Host != req.URL.Host && u.Host != "" || u.Scheme != "" && u.Scheme != req.URL.Scheme) {
			mark(f, "mapped")
			return s.remoteResp(ctx, f, req, d.Patch.URL)
		} else if err == nil {
			req.URL.Path, req.URL.RawPath, req.URL.RawQuery = u.Path, u.RawPath, u.RawQuery
		}
	}
	pr, err := d.Patch.asRule()
	if err != nil {
		return nil, err
	}
	if phase == "request" {
		if err := s.rewriteRequest(req, lb, get, pr); err != nil {
			return nil, err
		}
	} else if err := s.rewriteResponse(resp, lb, get, pr); err != nil {
		return nil, err
	}
	mark(f, "modified")
	return nil, nil
}

// PausedList lists the paused flows.
func (s *Service) PausedList() []*Paused {
	s.paused.mu.Lock()
	defer s.paused.mu.Unlock()
	out := make([]*Paused, 0, len(s.paused.m))
	for _, p := range s.paused.m {
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Since.Before(out[j].Since) })
	return out
}

// Resolve answers a paused flow.
func (s *Service) Resolve(id string, d Decision) error {
	s.paused.mu.Lock()
	p := s.paused.m[id]
	s.paused.mu.Unlock()
	if p == nil {
		return fmt.Errorf("no paused item %s (it may have continued after its timeout)", id)
	}
	select {
	case p.decide <- d:
		return nil
	default:
		return fmt.Errorf("%s was already answered", id)
	}
}

// PausedText shows the queue, or one item with its body.
func (s *Service) PausedText(id string, secrets bool) (string, error) {
	list := s.PausedList()
	if id == "" {
		if len(list) == 0 {
			return "nothing paused (breakpoint rules: net_rules action=add with type breakpoint)", nil
		}
		var b strings.Builder
		for _, p := range list {
			left := time.Until(p.Since.Add(p.Timeout)).Round(time.Second)
			what := p.Method + " " + Cut(p.URL, 90)
			if p.Phase == "response" {
				what += fmt.Sprintf(" → %d", p.Status)
			}
			fmt.Fprintf(&b, "%s %s %s (%s, rule %s) · auto-continue in %s\n", p.ID, p.Phase, what, FID(p.FlowID), p.Rule, left)
		}
		return b.String(), nil
	}
	for _, p := range list {
		if p.ID != id {
			continue
		}
		var b strings.Builder
		fmt.Fprintf(&b, "%s paused at %s: %s %s", p.ID, p.Phase, p.Method, p.URL)
		if p.Phase == "response" {
			fmt.Fprintf(&b, " → %d", p.Status)
		}
		fmt.Fprintf(&b, "\nheaders:\n%s", HeaderBlock(p.Headers, secrets))
		body := p.Body
		if !secrets {
			body = redact.Body(body, p.BodyType)
		}
		if len(body) > 0 {
			fmt.Fprintf(&b, "body (%s, %s):\n%s\n", p.BodyType, Size(int64(len(p.Body))), Cut(view.Pretty(body, p.BodyType), 3000))
		}
		fmt.Fprintf(&b, "auto-continue in %s", time.Until(p.Since.Add(p.Timeout)).Round(time.Second))
		return b.String(), nil
	}
	return "", fmt.Errorf("no paused item %s", id)
}

// ---- rules for connections (passthrough / ignore) ----

func (s *Service) connectRule(m intercept.Meta, host string) (passthrough, ignore bool) {
	if s.Rules == nil || s.Rules.EnabledCount() == 0 {
		return false, false
	}
	pseudo := &flow.Flow{App: m.App, Host: host, Kind: flow.KindTLS}
	for _, r := range s.Rules.Phase("connect") {
		if r.Filter().Match(pseudo, nil) {
			r.Hit()
			switch r.Action.Type {
			case "passthrough":
				passthrough = true
			case "ignore":
				ignore = true
			}
		}
	}
	return
}

// ---- rules text for MCP ----

// RulesText lists the rules.
func (s *Service) RulesText() string {
	list := s.Rules.All()
	if len(list) == 0 {
		return "no rules (net_rules action=add rule={...})"
	}
	var b strings.Builder
	for _, r := range list {
		on := "on "
		if !r.Enabled {
			on = "off"
		}
		a, _ := json.Marshal(r.Action)
		name := r.Name
		if name != "" {
			name = " " + strconv.Quote(name)
		}
		fmt.Fprintf(&b, "%s %s%s · %s · match `%s` · %s · %d hits\n", r.ID, on, name, r.Phase, r.Match, a, r.Hits)
	}
	if vars := s.Rules.Vars(); len(vars) > 0 {
		keys := make([]string, 0, len(vars))
		for k := range vars {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		b.WriteString("variables: ")
		for i, k := range keys {
			if i > 0 {
				b.WriteString(" · ")
			}
			v := vars[k]
			if !s.Settings().ShowSecretsToAI && redact.SensitiveKey(k) {
				v = redact.Hide(v)
			}
			b.WriteString("{{" + k + "}} = " + Cut(v, 60))
		}
		b.WriteByte('\n')
	}
	return b.String()
}

// TestRule shows which stored flows a rule would match.
func (s *Service) TestRule(r *rules.Rule, sessID string, limit int) (string, error) {
	if err := r.Validate(); err != nil {
		return "", err
	}
	sess, err := s.Session(sessID)
	if err != nil {
		return "", err
	}
	sess.Store.Flush()
	bodies := bodySource{s: s, sess: sess}
	if limit <= 0 {
		limit = 10
	}
	var hits []*flow.Flow
	total, scanned := 0, 0
	where, args := r.Filter().SQL()
	_ = sess.Store.Scan(where, args, false, func(f *flow.Flow) bool {
		scanned++
		if r.Filter().Match(f, bodies) {
			total++
			if len(hits) < limit {
				hits = append(hits, f)
			}
		}
		return scanned < 20000
	})
	var b strings.Builder
	fmt.Fprintf(&b, "rule (%s, %s phase) matches %d of the stored flows", r.Action.Type, r.Phase, total)
	if total > 0 {
		b.WriteString(", e.g.:\n")
		for _, f := range hits {
			b.WriteString(RowText(f, 1, false, s.Settings().ShowSecretsToAI) + "\n")
		}
	}
	return b.String(), nil
}
