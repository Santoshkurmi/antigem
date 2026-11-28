package service

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/intercept"
	"antigem/netcap/internal/query"
	"antigem/netcap/internal/script"
	"antigem/netcap/internal/store"
	"antigem/netcap/internal/view"
)

// ---- the scripts' host (script.Host) ----

type scriptHost struct{ s *Service }

func (h scriptHost) Var(name string) (string, bool) { return h.s.Rules.Var(name) }
func (h scriptHost) SetVar(name, value string)      { h.s.Rules.SetVar(name, value) }
func (h scriptHost) DeleteVar(name string)          { h.s.Rules.DeleteVar(name) }
func (h scriptHost) Vars() map[string]string        { return h.s.Rules.Vars() }
func (h scriptHost) OutDir() string                 { return filepath.Join(h.s.Current().Dir, "out") }

func (h scriptHost) Finding(f script.Finding) {
	h.s.Current().Store.AddFinding(store.Finding{Script: f.Script, Severity: f.Severity, Title: f.Title, Detail: f.Detail, Flow: f.Flow})
}

func (h scriptHost) Query(filter string, o script.QueryOptions) (any, error) { return h.s.scriptQuery(filter, o) }

func (h scriptHost) Send(ctx context.Context, r script.SendRequest) (*script.SendResponse, error) {
	return h.s.scriptSend(ctx, r)
}

func (s *Service) startScripts() {
	s.Scripts = script.New(script.Options{Dir: filepath.Join(s.opts.DataDir, "scripts"), Host: scriptHost{s}, Logf: s.opts.Logf})
	s.Scripts.SetSessionDir(filepath.Join(s.Current().Dir, "scripts"))
	s.completeCh = make(chan completeItem, 2048)
	go s.completeLoop()
}

func (s *Service) scriptsSessionChanged() {
	if s.Scripts != nil {
		s.Scripts.SetSessionDir(filepath.Join(s.Current().Dir, "scripts"))
	}
}

// ---- onRequest / onResponse ----

func liveGetter(lb *liveBody, get func() []byte) func() ([]byte, error) {
	return func() ([]byte, error) {
		d := get()
		if lb.err != nil {
			return nil, lb.err
		}
		return d, nil
	}
}

func (s *Service) runScriptsRequest(ctx context.Context, f *flow.Flow, req *http.Request, lb *liveBody, get func() []byte,
	bodies hookBodies, list []*script.Script) (*http.Response, error) {
	if len(list) == 0 {
		return nil, nil
	}
	ex := &script.Exchange{Flow: f, Req: req, ReqBody: liveGetter(lb, get), Ctx: ctx}
	for _, sc := range list {
		if !sc.Matches(f, bodies) {
			continue
		}
		res, err := sc.RunHTTP("request", ex, nil)
		if err != nil {
			continue // the script logged it; the flow goes on unchanged
		}
		if resp, err := s.applyScriptRequest(ctx, f, req, lb, get, res); resp != nil || err != nil {
			return resp, err
		}
	}
	return nil, nil
}

func (s *Service) applyScriptRequest(ctx context.Context, f *flow.Flow, req *http.Request, lb *liveBody, get func() []byte,
	r *script.Result) (*http.Response, error) {
	for _, t := range r.Tags {
		f.AddTag(t)
	}
	if r.Note != "" {
		f.Note = strings.TrimSpace(f.Note + " " + r.Note)
	}
	if !r.Traffic() {
		return nil, nil
	}
	mark(f, "script:"+r.Script)
	if r.Delay > 0 {
		if err := sleepCtx(ctx, r.Delay); err != nil {
			return nil, err
		}
	}
	if r.Drop {
		return nil, intercept.ErrBlock
	}
	if r.Respond != nil {
		mark(f, "mocked")
		return newResp(req, r.Respond.Status, r.Respond.Headers, r.Respond.Body), nil
	}
	origCE := req.Header.Get("Content-Encoding")
	if r.Header != nil {
		req.Header = r.Header
		if _, ok := req.Header["User-Agent"]; !ok {
			req.Header["User-Agent"] = []string{""} // removed: keep Go from adding its own
		}
	}
	if r.Host != "" {
		req.Host = r.Host
	}
	if r.Method != "" {
		req.Method = r.Method
	}
	if r.Path != nil {
		if u, err := url.Parse(*r.Path); err == nil {
			req.URL.Path, req.URL.RawPath = u.Path, u.RawPath
			if u.RawQuery != "" && r.RawQuery == nil {
				req.URL.RawQuery = u.RawQuery
			}
		}
	}
	if r.RawQuery != nil {
		req.URL.RawQuery = *r.RawQuery
	}
	if r.BodySet {
		get()
		if lb.tooBig {
			return nil, lb.err
		}
		lb.data, lb.replaced = r.Body, true
		lb.decoded = req.Header.Get("Content-Encoding") == origCE // the script didn't encode it itself: send it plain
	}
	mark(f, "modified")
	if r.Redirect != "" {
		if lb.read {
			setReqBody(req, lb)
		}
		mark(f, "mapped")
		return s.remoteResp(ctx, f, req, r.Redirect)
	}
	return nil, nil
}

func (s *Service) runScriptsResponse(ctx context.Context, f *flow.Flow, req *http.Request, resp *http.Response, lb *liveBody,
	get func() []byte, bodies hookBodies, list []*script.Script) (*http.Response, error) {
	if len(list) == 0 {
		return nil, nil
	}
	ex := &script.Exchange{Flow: f, Req: req, Resp: resp, ResBody: liveGetter(lb, get), Ctx: ctx}
	if v, ok := s.reqBodies.Load(f); ok {
		b := v.([]byte)
		ex.ReqBody = func() ([]byte, error) { return b, nil }
	}
	for _, sc := range list {
		f.Status = resp.StatusCode
		f.ResHeaders = flow.FromHTTP(resp.Header, "")
		f.Res.ContentType = resp.Header.Get("Content-Type")
		if !sc.Matches(f, bodies) {
			continue
		}
		res, err := sc.RunHTTP("response", ex, nil)
		if err != nil {
			continue
		}
		r2, err := s.applyScriptResponse(ctx, f, req, resp, lb, get, res)
		if err != nil || r2 != nil {
			return r2, err
		}
	}
	return nil, nil
}

func (s *Service) applyScriptResponse(ctx context.Context, f *flow.Flow, req *http.Request, resp *http.Response, lb *liveBody,
	get func() []byte, r *script.Result) (*http.Response, error) {
	for _, t := range r.Tags {
		f.AddTag(t)
	}
	if r.Note != "" {
		f.Note = strings.TrimSpace(f.Note + " " + r.Note)
	}
	if !r.Traffic() {
		return nil, nil
	}
	mark(f, "script:"+r.Script)
	if r.Delay > 0 {
		if err := sleepCtx(ctx, r.Delay); err != nil {
			return nil, err
		}
	}
	if r.Drop {
		return nil, intercept.ErrBlock
	}
	if r.Respond != nil {
		mark(f, "mocked")
		return newResp(req, r.Respond.Status, r.Respond.Headers, r.Respond.Body), nil
	}
	origCE := resp.Header.Get("Content-Encoding")
	if r.Header != nil {
		resp.Header = r.Header
	}
	if r.Status != 0 {
		resp.StatusCode = r.Status
		resp.Status = fmt.Sprintf("%d %s", r.Status, http.StatusText(r.Status))
	}
	if r.BodySet {
		get()
		if lb.tooBig {
			return nil, lb.err
		}
		lb.data, lb.replaced = r.Body, true
		lb.decoded = resp.Header.Get("Content-Encoding") == origCE
	}
	mark(f, "modified")
	return nil, nil
}

// wantsReqBodyLater: an onResponse script may read the request body (kept from the request phase).
func (s *Service) wantsReqBodyLater() bool { return s.Scripts != nil && len(s.Scripts.For("response")) > 0 }

// ---- WebSocket messages ----

// WebSocket gives the engine a message hook when a script (or an external hook) wants this WebSocket's messages.
func (s *Service) WebSocket(f *flow.Flow, req *http.Request) intercept.WSHook {
	var scripts []*script.Script
	probe := f.Clone()
	probe.Kind = flow.KindWS
	if s.Scripts != nil {
		for _, sc := range s.Scripts.For("message") {
			if sc.Matches(probe, nil) {
				scripts = append(scripts, sc)
			}
		}
	}
	hooks := s.extFor("message", probe, nil)
	if len(scripts) == 0 && len(hooks) == 0 {
		return nil
	}
	var mu sync.Mutex
	return func(m intercept.WSMessage) intercept.WSAction {
		act := intercept.WSAction{}
		cur := script.Message{Dir: m.Dir, Type: m.Type, Data: m.Data}
		for _, sc := range scripts {
			res, err := sc.RunMessage(f, cur, m.Index, nil)
			if err != nil {
				continue
			}
			mu.Lock()
			for _, t := range res.Tags {
				f.AddTag(t)
			}
			if res.Note != "" {
				f.Note = strings.TrimSpace(f.Note + " " + res.Note)
			}
			if res.Changed || res.Drop || len(res.Extra) > 0 || len(res.Replies) > 0 {
				mark(f, "script:"+sc.Name)
			}
			mu.Unlock()
			for _, x := range res.Extra {
				act.Extra = append(act.Extra, intercept.WSMessage{Dir: x.Dir, Type: x.Type, Data: x.Data})
			}
			for _, x := range res.Replies {
				act.Replies = append(act.Replies, intercept.WSMessage{Dir: x.Dir, Type: x.Type, Data: x.Data})
			}
			if res.Drop {
				act.Drop = true
				return act
			}
			if res.Changed {
				act.Changed = true
				cur.Data, cur.Type = res.Data, res.Type
			}
		}
		for _, h := range hooks {
			d, ok := h.message(f, cur, m.Index)
			if !ok {
				continue
			}
			if d.drop {
				act.Drop = true
				return act
			}
			if d.changed {
				act.Changed = true
				cur.Data, cur.Type = d.data, d.typ
			}
		}
		act.Data, act.Type = cur.Data, cur.Type
		return act
	}
}

// ---- onComplete ----

type completeItem struct {
	sess *Session
	f    *flow.Flow
}

func (s *Service) wantComplete() bool {
	return (s.Scripts != nil && len(s.Scripts.For("complete")) > 0) || len(s.extList("complete")) > 0
}

func (s *Service) completeLoop() {
	for it := range s.completeCh {
		bodies := bodySource{s: s, sess: it.sess}
		get := func(side string) ([]byte, error) {
			b, _, err := s.Body(it.sess, it.f, side, false, 8<<20)
			return b, err
		}
		changed := false
		for _, sc := range s.Scripts.For("complete") {
			if hasFlag(it.f, "script-sent:"+sc.Name) || !sc.Matches(it.f, bodies) {
				continue
			}
			res, err := sc.RunComplete(context.Background(), it.f, get, nil)
			if err != nil {
				continue
			}
			for _, t := range res.Tags {
				it.f.AddTag(t)
				changed = true
			}
			if res.Note != "" {
				it.f.Note = strings.TrimSpace(it.f.Note + " " + res.Note)
				changed = true
			}
		}
		if changed {
			it.sess.Store.Put(it.f)
		}
		for _, h := range s.extFor("complete", it.f, bodies) {
			h.complete(it.sess, it.f)
		}
	}
}

// ---- ctx.query ----

// flowRow is one row of ctx.query (keys in this order for readable output).
type flowRow struct {
	ID      string   `json:"id"`
	Start   string   `json:"start"`
	Kind    string   `json:"kind"`
	App     string   `json:"app,omitempty"`
	Method  string   `json:"method,omitempty"`
	URL     string   `json:"url"`
	Host    string   `json:"host"`
	Path    string   `json:"path,omitempty"`
	Status  int      `json:"status"`
	Type    string   `json:"type,omitempty"`
	ReqSize int64    `json:"req_size"`
	Size    int64    `json:"size"`
	Ms      float64  `json:"ms"`
	Tags    []string `json:"tags,omitempty"`
	Error   string   `json:"error,omitempty"`
}

func flowSummary(f *flow.Flow) flowRow {
	return flowRow{ID: FID(f.ID), Start: f.Start.Format(time.RFC3339Nano), Kind: string(f.Kind), App: f.App, Method: f.Method,
		URL: f.URL(), Host: f.Host, Path: f.Path, Status: f.Status, Type: f.Res.Type, ReqSize: f.Req.Size, Size: f.Res.Size,
		Ms: f.Timings.Total, Tags: f.Tags, Error: f.Error}
}

type groupRow struct {
	Key      string         `json:"key"`
	Count    int            `json:"count"`
	Errors   int            `json:"errors"`
	AvgMs    float64        `json:"avg_ms"`
	MaxMs    float64        `json:"max_ms"`
	Statuses map[string]int `json:"statuses"`
}

func (s *Service) scriptQuery(filter string, o script.QueryOptions) (any, error) {
	fl, err := query.Parse(filter)
	if err != nil {
		return nil, err
	}
	sess := s.Current()
	sess.Store.Flush()
	limit := o.Limit
	if limit <= 0 {
		limit = 50
	}
	if limit > 1000 {
		limit = 1000
	}
	where, args := fl.SQL()
	bodies := bodySource{s: s, sess: sess}
	scanned := 0
	if o.GroupBy == "" {
		out := []flowRow{}
		err := sess.Store.Scan(where, args, false, func(f *flow.Flow) bool {
			scanned++
			if fl.Match(f, bodies) {
				out = append(out, flowSummary(f))
			}
			return len(out) < limit && scanned < 20000
		})
		return out, err
	}
	keyOf := map[string]func(*flow.Flow) []string{
		"endpoint": func(f *flow.Flow) []string { return []string{f.Endpoint} },
		"host":     func(f *flow.Flow) []string { return []string{f.Host} },
		"app":      func(f *flow.Flow) []string { return []string{f.App} },
		"status":   func(f *flow.Flow) []string { return []string{strconv.Itoa(f.Status)} },
		"type":     func(f *flow.Flow) []string { return []string{f.Res.Type} },
		"kind":     func(f *flow.Flow) []string { return []string{string(f.Kind)} },
		"method":   func(f *flow.Flow) []string { return []string{f.Method} },
		"path":     func(f *flow.Flow) []string { return []string{f.Path} },
		"tag":      func(f *flow.Flow) []string { return f.Tags },
	}[o.GroupBy]
	if keyOf == nil {
		return nil, fmt.Errorf("group_by: endpoint, host, app, status, type, kind, method, path or tag")
	}
	type group struct {
		key            string
		count, errors  int
		total, slowest float64
		statuses       map[string]int
	}
	groups := map[string]*group{}
	err = sess.Store.Scan(where, args, false, func(f *flow.Flow) bool {
		scanned++
		if !fl.Match(f, bodies) {
			return scanned < 20000
		}
		for _, k := range keyOf(f) {
			g := groups[k]
			if g == nil {
				g = &group{key: k, statuses: map[string]int{}}
				groups[k] = g
			}
			g.count++
			if f.Error != "" || f.Status >= 400 {
				g.errors++
			}
			g.total += f.Timings.Total
			g.slowest = max(g.slowest, f.Timings.Total)
			g.statuses[strconv.Itoa(f.Status)]++
		}
		return scanned < 20000
	})
	list := make([]*group, 0, len(groups))
	for _, g := range groups {
		list = append(list, g)
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].count != list[j].count {
			return list[i].count > list[j].count
		}
		return list[i].key < list[j].key
	})
	if len(list) > limit {
		list = list[:limit]
	}
	out := make([]groupRow, 0, len(list))
	for _, g := range list {
		out = append(out, groupRow{Key: g.key, Count: g.count, Errors: g.errors, AvgMs: float64(int(g.total/float64(g.count)*10)) / 10,
			MaxMs: g.slowest, Statuses: g.statuses})
	}
	return out, err
}

// ---- ctx.send ----

func (s *Service) scriptSend(ctx context.Context, r script.SendRequest) (*script.SendResponse, error) {
	req, err := http.NewRequestWithContext(ctx, r.Method, r.URL, bytes.NewReader(r.Body))
	if err != nil {
		return nil, err
	}
	for k, v := range r.Headers {
		if strings.EqualFold(k, "Host") {
			req.Host = v
			continue
		}
		req.Header.Set(k, v)
	}
	if _, ok := req.Header["User-Agent"]; !ok {
		req.Header["User-Agent"] = []string{""}
	}
	req.ContentLength = int64(len(r.Body))
	if !s.limit.allow(strings.ToLower(req.URL.Hostname())) {
		return nil, fmt.Errorf("more than 30 requests a minute to %s (the same limit as net_send)", req.URL.Hostname())
	}
	timeout := r.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req = req.WithContext(ctx)
	sess := s.Current()
	f := &flow.Flow{ID: sess.Store.NewID(), Kind: flow.KindHTTP, App: "netcap (script " + r.Script + ")", Start: time.Now(),
		Scheme: req.URL.Scheme, Host: req.URL.Hostname(), Port: portNum(req.URL), Method: req.Method, Path: req.URL.EscapedPath(),
		Query: req.URL.RawQuery, Flags: []string{"sent", "script-sent:" + r.Script}}
	if f.Path == "" {
		f.Path = "/"
	}
	var body bytes.Buffer
	resp, err := s.roundTripRecorded(sess, req, f, false, &body)
	if err != nil {
		return nil, err
	}
	return &script.SendResponse{Status: resp.StatusCode, Headers: resp.Header, Body: body.Bytes(), Flow: f.ID}, nil
}

// roundTripRecorded sends req, records it as flow f (like captured traffic) and copies the answer's body into keep
// (when given, up to 8 MB).
func (s *Service) roundTripRecorded(sess *Session, req *http.Request, f *flow.Flow, follow bool, keep *bytes.Buffer) (*http.Response, error) {
	hdr := req.Header.Clone()
	if v, ok := hdr["User-Agent"]; ok && len(v) == 1 && v[0] == "" {
		delete(hdr, "User-Agent")
	}
	f.ReqHeaders = flow.FromHTTP(hdr, req.URL.Host)
	f.Req.ContentType, f.Req.Encoding = req.Header.Get("Content-Type"), req.Header.Get("Content-Encoding")
	s.active.Store(f, sess)
	reqCap := s.Capture(f, "req")
	if req.Body != nil && req.Body != http.NoBody {
		req.Body = io.NopCloser(io.TeeReader(req.Body, reqCap))
	}
	client := s.client()
	if follow {
		c := *client
		c.CheckRedirect = nil
		client = &c
	}
	resp, err := client.Do(req)
	finishCap := func(b *flow.Body, c intercept.Capture) {
		r := c.Done()
		b.Hash, b.Size, b.Stored, b.Truncated = r.Hash, r.Size, r.Stored, r.Size > r.Stored
		b.Type = shortTypeOf(b.ContentType, b.Encoding, r.Sample, b.Size)
	}
	if err != nil {
		f.Error = "send: " + err.Error()
		finishCap(&f.Req, reqCap)
		f.End = time.Now()
		f.Timings.Total = float64(f.End.Sub(f.Start).Microseconds()) / 1000
		s.Finish(f)
		return nil, fmt.Errorf("%s (recorded as %s)", err, FID(f.ID))
	}
	f.Timings.TTFB = float64(time.Since(f.Start).Microseconds()) / 1000
	f.Status = resp.StatusCode
	f.HTTPVersion = strconv.Itoa(resp.ProtoMajor)
	if resp.ProtoMajor == 1 {
		f.HTTPVersion = fmt.Sprintf("1.%d", resp.ProtoMinor)
	}
	if req.URL.Scheme == "https" {
		f.TLS = flow.TLSDecrypted
	} else {
		f.TLS = flow.TLSPlain
	}
	f.ResHeaders = flow.FromHTTP(resp.Header, "")
	f.Res.ContentType, f.Res.Encoding = resp.Header.Get("Content-Type"), resp.Header.Get("Content-Encoding")
	resCap := s.Capture(f, "res")
	var w io.Writer = resCap
	var raw bytes.Buffer
	if keep != nil {
		w = io.MultiWriter(resCap, &limitWriter{w: &raw, n: 8 << 20})
	}
	_, rerr := io.Copy(w, resp.Body)
	resp.Body.Close()
	if rerr != nil {
		f.Error = "reading the answer: " + rerr.Error()
	}
	f.ResTrailers = flow.FromHTTP(resp.Trailer, "")
	finishCap(&f.Req, reqCap)
	finishCap(&f.Res, resCap)
	f.End = time.Now()
	f.Timings.Total = float64(f.End.Sub(f.Start).Microseconds()) / 1000
	s.Finish(f)
	if keep != nil {
		data := raw.Bytes()
		if enc := resp.Header.Get("Content-Encoding"); enc != "" {
			if d, err := view.Decode(data, enc); err == nil {
				data = d
			}
		}
		keep.Write(data)
	}
	return resp, nil
}

type limitWriter struct {
	w io.Writer
	n int
}

func (l *limitWriter) Write(p []byte) (int, error) {
	if l.n <= 0 {
		return len(p), nil
	}
	k := min(len(p), l.n)
	l.n -= k
	_, _ = l.w.Write(p[:k])
	return len(p), nil
}

// ---- texts for MCP ----

// ScriptsText lists the scripts.
func (s *Service) ScriptsText() string {
	list := s.Scripts.All()
	var b strings.Builder
	if len(list) == 0 {
		fmt.Fprintf(&b, "no scripts. Write a .js file into %s (all sessions) or %s (this session), or net_script action=load code=… name=…\n",
			s.Scripts.Dir(), s.Scripts.SessionDir())
	}
	for _, sc := range list {
		state := "on "
		switch {
		case sc.Err != "":
			state = "ERR"
		case !sc.Enabled():
			state = "off"
		}
		where := "global"
		if sc.Session {
			where = "session"
		}
		fmt.Fprintf(&b, "%s %s (%s, %s)", state, sc.Name, filepath.Base(sc.File), where)
		if sc.Err != "" {
			fmt.Fprintf(&b, " · %s\n", sc.Err)
			continue
		}
		hooks := []string{}
		for _, k := range []string{"request", "response", "message", "complete"} {
			if sc.Hooks[k] {
				hooks = append(hooks, k)
			}
		}
		if len(hooks) > 0 {
			b.WriteString(" · hooks " + strings.Join(hooks, ","))
		}
		if sc.Match != "" {
			b.WriteString(" · match `" + sc.Match + "`")
		}
		if len(sc.Commands) > 0 {
			b.WriteString(" · commands " + strings.Join(sc.Commands, ","))
		}
		if len(sc.Every) > 0 {
			var e []string
			for _, d := range sc.Every {
				e = append(e, d.String())
			}
			b.WriteString(" · every " + strings.Join(e, ","))
		}
		st := sc.Stats()
		fmt.Fprintf(&b, " · %d calls", st.Calls)
		if st.Calls > 0 {
			fmt.Fprintf(&b, " avg %.2fms max %.1fms", st.AvgMs, st.MaxMs)
		}
		if st.Errors > 0 {
			fmt.Fprintf(&b, " · %d errors (last: %s)", st.Errors, Cut(st.LastError, 160))
		}
		if st.Busy > 0 {
			fmt.Fprintf(&b, " · %d skipped (busy)", st.Busy)
		}
		if r := sc.OffReason(); r != "" {
			b.WriteString(" · turned off by the engine: " + Cut(r, 160))
		}
		for _, w := range sc.Warnings {
			b.WriteString("\n    warning: " + w)
		}
		b.WriteByte('\n')
	}
	if hl := s.HooksText(); hl != "" {
		b.WriteString(hl)
	}
	return b.String()
}

// ScriptText describes one script after loading it.
func ScriptText(sc *script.Script) string {
	if sc.Err != "" {
		return "error: " + sc.Err + "\n(the file is saved; fix it and it reloads by itself)"
	}
	var b strings.Builder
	state := "on"
	if !sc.Enabled() {
		state = "off (net_script action=enable)"
	}
	fmt.Fprintf(&b, "%s loaded, %s · file %s", sc.Name, state, sc.File)
	var hooks []string
	for _, k := range []string{"request", "response", "message", "complete"} {
		if sc.Hooks[k] {
			hooks = append(hooks, k)
		}
	}
	if len(hooks) > 0 {
		b.WriteString("\nhooks: " + strings.Join(hooks, ", "))
		if sc.Match != "" {
			b.WriteString(" on `" + sc.Match + "`")
		}
		fmt.Fprintf(&b, " · limit %s per traffic hook", sc.Timeout)
	}
	if len(sc.Commands) > 0 {
		b.WriteString("\ncommands: " + strings.Join(sc.Commands, ", ") + " (net_script action=call name=" + sc.Name + "." + sc.Commands[0] + ")")
	}
	for _, w := range sc.Warnings {
		b.WriteString("\nwarning: " + w)
	}
	return b.String()
}

// TestScript runs a script's hooks on stored flows without changing traffic or saving anything.
func (s *Service) TestScript(sc *script.Script, filter string, limit int, sessID string) (string, error) {
	if sc.Err != "" {
		return "", errors.New(sc.Err)
	}
	sess, err := s.Session(sessID)
	if err != nil {
		return "", err
	}
	fl := sc.Filter()
	if filter != "" {
		if fl, err = query.Parse(filter); err != nil {
			return "", err
		}
	}
	if fl == nil {
		fl = query.MustParse("")
	}
	if limit <= 0 {
		limit = 5
	}
	sess.Store.Flush()
	bodies := bodySource{s: s, sess: sess}
	var flows []*flow.Flow
	where, args := fl.SQL()
	scanned := 0
	_ = sess.Store.Scan(where, args, false, func(f *flow.Flow) bool {
		scanned++
		if (f.Kind == flow.KindHTTP || f.Kind == flow.KindGRPC || sc.Hooks["complete"]) && fl.Match(f, bodies) && sc.Matches(f, bodies) {
			flows = append(flows, f)
		}
		return len(flows) < limit && scanned < 20000
	})
	if len(flows) == 0 {
		return "no stored flow matches the script's @match" + map[bool]string{true: " and the filter", false: ""}[filter != ""] + ": nothing to test on", nil
	}
	var b strings.Builder
	fmt.Fprintf(&b, "test of %s on %d stored flows (nothing was changed, sent or saved):\n", sc.Name, len(flows))
	for _, f := range flows {
		sb := script.NewSandbox(sc)
		fmt.Fprintf(&b, "%s %s %s → %d\n", FID(f.ID), f.Method, Cut(f.URL(), 90), f.Status)
		var lines []string
		reqBody, _, _ := s.Body(sess, f, "req", false, 8<<20)
		resBody, _, _ := s.Body(sess, f, "res", false, 8<<20)
		if sc.Hooks["request"] && (f.Kind == flow.KindHTTP || f.Kind == flow.KindGRPC) {
			req := storedRequest(f)
			ex := &script.Exchange{Flow: f, Req: req, ReqBody: func() ([]byte, error) { return reqBody, nil }, Ctx: context.Background()}
			if res, err := sc.RunHTTP("request", ex, sb); err != nil {
				lines = append(lines, "onRequest error: "+err.Error())
			} else {
				lines = append(lines, changesLine("onRequest", res.Changes))
			}
		}
		if sc.Hooks["response"] && (f.Kind == flow.KindHTTP || f.Kind == flow.KindGRPC) {
			req := storedRequest(f)
			resp := &http.Response{StatusCode: f.Status, Header: headerOf(f.ResHeaders), ContentLength: f.Res.Size}
			ex := &script.Exchange{Flow: f, Req: req, Resp: resp, ReqBody: func() ([]byte, error) { return reqBody, nil },
				ResBody: func() ([]byte, error) { return resBody, nil }, Ctx: context.Background()}
			if res, err := sc.RunHTTP("response", ex, sb); err != nil {
				lines = append(lines, "onResponse error: "+err.Error())
			} else {
				lines = append(lines, changesLine("onResponse", res.Changes))
			}
		}
		if sc.Hooks["complete"] {
			get := func(side string) ([]byte, error) {
				if side == "req" {
					return reqBody, nil
				}
				return resBody, nil
			}
			if res, err := sc.RunComplete(context.Background(), f, get, sb); err != nil {
				lines = append(lines, "onComplete error: "+err.Error())
			} else {
				lines = append(lines, changesLine("onComplete", res.Changes))
			}
		}
		if sc.Hooks["message"] && f.Kind == flow.KindWS {
			msgs, _ := sess.Store.Messages(f.ID, 0, 5)
			for _, m := range msgs {
				if m.Type != "text" && m.Type != "binary" {
					continue
				}
				res, err := sc.RunMessage(f, script.Message{Dir: m.Dir, Type: m.Type, Data: m.Data}, m.Index, sb)
				if err != nil {
					lines = append(lines, fmt.Sprintf("onMessage m%d error: %s", m.Index, err))
				} else {
					lines = append(lines, changesLine(fmt.Sprintf("onMessage m%d", m.Index), res.Changes))
				}
			}
		}
		for _, fd := range sb.Findings {
			lines = append(lines, fmt.Sprintf("finding [%s] %s", fd.Severity, fd.Title))
		}
		for _, n := range sb.Notes {
			lines = append(lines, n)
		}
		for _, l := range lines {
			b.WriteString("  " + Cut(l, 400) + "\n")
		}
	}
	return b.String(), nil
}

func changesLine(hook string, changes []string) string {
	if len(changes) == 0 {
		return hook + ": no changes"
	}
	return hook + ": " + strings.Join(changes, "; ")
}

func headerOf(h flow.Headers) http.Header {
	out := http.Header{}
	for _, kv := range h {
		if strings.HasPrefix(kv[0], ":") {
			continue
		}
		out.Add(kv[0], kv[1])
	}
	return out
}

func storedRequest(f *flow.Flow) *http.Request {
	u, err := url.Parse(f.URL())
	if err != nil {
		u = &url.URL{Scheme: f.Scheme, Host: f.Host, Path: f.Path, RawQuery: f.Query}
	}
	req := &http.Request{Method: f.Method, URL: u, Header: headerOf(f.ReqHeaders), Host: f.Host, ContentLength: f.Req.Size}
	req.Header.Del("Host")
	return req
}

// FindingsText lists findings.
func (s *Service) FindingsText(sessID, scriptName, severity string, limit int) (string, error) {
	sess, err := s.Session(sessID)
	if err != nil {
		return "", err
	}
	list, err := sess.Store.Findings(scriptName, severity, limit)
	if err != nil {
		return "", err
	}
	if len(list) == 0 {
		return "no findings (scripts report them with ctx.finding({severity, title, detail, flow}))", nil
	}
	var b strings.Builder
	for _, f := range list {
		fmt.Fprintf(&b, "#%d %s [%s] %s", f.ID, f.Time.Format("15:04:05"), f.Severity, Cut(f.Title, 160))
		if f.Flow > 0 {
			b.WriteString(" · " + FID(f.Flow))
		}
		if f.Script != "" {
			b.WriteString(" · " + f.Script)
		}
		if f.Detail != "" {
			b.WriteString(" · " + Cut(strings.ReplaceAll(f.Detail, "\n", " "), 100))
		}
		b.WriteByte('\n')
	}
	return b.String(), nil
}

// FindingText shows one finding whole.
func (s *Service) FindingText(sessID, id string) (string, error) {
	sess, err := s.Session(sessID)
	if err != nil {
		return "", err
	}
	n, err := strconv.ParseInt(strings.TrimPrefix(strings.TrimSpace(id), "#"), 10, 64)
	if err != nil {
		return "", fmt.Errorf("id is a number, e.g. 3")
	}
	f, err := sess.Store.GetFinding(n)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "#%d [%s] %s\ntime %s", f.ID, f.Severity, f.Title, f.Time.Format("2006-01-02 15:04:05"))
	if f.Script != "" {
		b.WriteString(" · script " + f.Script)
	}
	if f.Flow > 0 {
		b.WriteString(" · flow " + FID(f.Flow))
	}
	if f.Detail != "" {
		b.WriteString("\n" + Cut(f.Detail, 4000))
	}
	return b.String(), nil
}
