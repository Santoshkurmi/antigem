package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/rules"
	"antigem/netcap/internal/service"
)

// The native GUI's JSON API under /v1/ui/. Errors are {"error": "…"} with a 4xx/5xx status. Read routes take
// session= ("" = the active session).

func (a *API) uiRoutes(mux *http.ServeMux) {
	routes := map[string]http.HandlerFunc{
		"/v1/ui/live":           a.uiLive,
		"/v1/ui/state":          a.get(a.uiStateRoute),
		"/v1/ui/rows":           a.get(a.uiRows),
		"/v1/ui/groups":         a.get(a.uiGroups),
		"/v1/ui/sessions":       a.uiSessions,
		"/v1/ui/flow":           a.get(a.uiFlow),
		"/v1/ui/body":           a.uiBody,
		"/v1/ui/messages":       a.get(a.uiMessages),
		"/v1/ui/message":        a.get(a.uiMessage),
		"/v1/ui/mark":           a.post(a.uiMark),
		"/v1/ui/delete":         a.post(a.uiDelete),
		"/v1/ui/send":           a.post(a.uiSend),
		"/v1/ui/curl":           a.get(a.uiCurl),
		"/v1/ui/raw":            a.get(a.uiRaw),
		"/v1/ui/rules":          a.uiRules,
		"/v1/ui/rules/enable":   a.post(a.uiRuleEnable),
		"/v1/ui/rules/remove":   a.post(a.uiRuleRemove),
		"/v1/ui/rules/test":     a.post(a.uiRuleTest),
		"/v1/ui/vars":           a.post(a.uiVars),
		"/v1/ui/paused":         a.get(a.uiPaused),
		"/v1/ui/paused/body":    a.uiPausedBody,
		"/v1/ui/paused/resolve": a.post(a.uiResolve),
		"/v1/ui/paused/extend":  a.post(a.uiExtend),
		"/v1/ui/breaknext":      a.post(a.uiBreakNext),
		"/v1/ui/scripts":        a.get(a.uiScripts),
		"/v1/ui/scripts/enable": a.post(a.uiScriptEnable),
		"/v1/ui/scripts/remove": a.post(a.uiScriptRemove),
		"/v1/ui/scripts/new":    a.post(a.uiScriptNew),
		"/v1/ui/scripts/logs":   a.get(a.uiScriptLogs),
		"/v1/ui/capture/pause":  a.post(a.uiCapturePause),
	}
	for path, h := range routes {
		mux.HandleFunc(path, a.authF(h))
	}
}

// ---- helpers ----

type jsonHandler func(r *http.Request) (any, error)

// httpError carries a status code.
type httpError struct {
	code int
	err  error
}

func (e httpError) Error() string { return e.err.Error() }

func bad(format string, args ...any) error {
	return httpError{http.StatusBadRequest, fmt.Errorf(format, args...)}
}

func writeErr(w http.ResponseWriter, err error) {
	code := http.StatusBadRequest
	var he httpError
	switch {
	case errors.As(err, &he):
		code = he.code
	case strings.HasPrefix(err.Error(), "no "):
		code = http.StatusNotFound
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
}

func (a *API) serve(w http.ResponseWriter, r *http.Request, h jsonHandler) {
	v, err := h(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, v)
}

func (a *API) get(h jsonHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			writeErr(w, httpError{http.StatusMethodNotAllowed, errors.New("use GET")})
			return
		}
		a.serve(w, r, h)
	}
}

func (a *API) post(h jsonHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeErr(w, httpError{http.StatusMethodNotAllowed, errors.New("use POST")})
			return
		}
		a.serve(w, r, h)
	}
}

// readJSON decodes the request body (at most max bytes) into v.
func readJSON(r *http.Request, v any, max int64) error {
	body, err := io.ReadAll(io.LimitReader(r.Body, max+1))
	if err != nil {
		return bad("reading the body: %v", err)
	}
	if int64(len(body)) > max {
		return httpError{http.StatusRequestEntityTooLarge, fmt.Errorf("body bigger than %d MB (use body_file)", max>>20)}
	}
	if len(strings.TrimSpace(string(body))) == 0 {
		return nil
	}
	if err := json.Unmarshal(body, v); err != nil {
		return bad("body must be a JSON object: %v", err)
	}
	return nil
}

const smallBody, bigBody = 4 << 20, 256 << 20

func qInt(r *http.Request, name string, def int) int {
	if v := r.URL.Query().Get(name); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func qBool(r *http.Request, name string) bool {
	switch strings.ToLower(r.URL.Query().Get(name)) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}

func qSession(r *http.Request) string { return r.URL.Query().Get("session") }

func qSide(r *http.Request) (string, error) {
	switch side := r.URL.Query().Get("side"); side {
	case "", "res":
		return "res", nil
	case "req":
		return "req", nil
	default:
		return "", bad("side is req or res")
	}
}

// flexID is a flow id given as 12 or "f12".
type flexID string

func (f *flexID) UnmarshalJSON(b []byte) error {
	var v any
	if err := json.Unmarshal(b, &v); err != nil {
		return err
	}
	switch x := v.(type) {
	case nil:
		*f = ""
	case float64:
		*f = flexID("f" + strconv.FormatInt(int64(x), 10))
	case string:
		*f = flexID(x)
	default:
		return fmt.Errorf("a flow id is a number or \"f12\"")
	}
	return nil
}

func idsOf(list []flexID) ([]int64, error) {
	out := make([]int64, 0, len(list))
	for _, x := range list {
		id, _, err := service.ParseID(string(x))
		if err != nil {
			return nil, bad("%v", err)
		}
		out = append(out, id)
	}
	return out, nil
}

// ---- live ----

type sseWriter struct {
	w       http.ResponseWriter
	fl      http.Flusher
	started bool
}

func (s *sseWriter) start() {
	if !s.started {
		s.started = true
		h := s.w.Header()
		h.Set("Content-Type", "text/event-stream")
		h.Set("Cache-Control", "no-cache")
		h.Set("X-Accel-Buffering", "no")
		s.w.WriteHeader(http.StatusOK)
	}
}

func (s *sseWriter) Event(name string, data []byte) error {
	s.start()
	if _, err := fmt.Fprintf(s.w, "event: %s\ndata: %s\n\n", name, data); err != nil {
		return err
	}
	s.fl.Flush()
	return nil
}

func (s *sseWriter) Comment(text string) error {
	s.start()
	if _, err := fmt.Fprintf(s.w, ": %s\n\n", text); err != nil {
		return err
	}
	s.fl.Flush()
	return nil
}

// uiLive: GET /v1/ui/live?session=&q= (server-sent events: state, then batch events).
func (a *API) uiLive(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		writeErr(w, httpError{http.StatusInternalServerError, errors.New("streaming not supported")})
		return
	}
	sw := &sseWriter{w: w, fl: fl}
	err := a.Svc.Live(r.Context(), qSession(r), r.URL.Query().Get("q"), sw)
	if err != nil && !sw.started {
		writeErr(w, err)
	}
}

func (a *API) uiStateRoute(r *http.Request) (any, error) { return a.Svc.UIState(qSession(r)) }

// ---- lists ----

func (a *API) uiRows(r *http.Request) (any, error) {
	q := r.URL.Query()
	return a.Svc.UIRows(qSession(r), q.Get("q"), q.Get("cursor"), q.Get("sort"), qInt(r, "limit", 200))
}

func (a *API) uiGroups(r *http.Request) (any, error) {
	q := r.URL.Query()
	groups, err := a.Svc.UIGroups(qSession(r), q.Get("by"), q.Get("q"), qInt(r, "limit", 0))
	if err != nil {
		return nil, err
	}
	return map[string]any{"groups": groups}, nil
}

func (a *API) uiSessions(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		list := a.Svc.Sessions()
		if list == nil {
			list = []service.SessionInfo{}
		}
		writeJSON(w, list)
	case http.MethodPost:
		a.serve(w, r, func(r *http.Request) (any, error) {
			var in struct{ Action, ID, Name string }
			if err := readJSON(r, &in, smallBody); err != nil {
				return nil, err
			}
			info, err := a.Svc.SessionAction(in.Action, in.ID, in.Name)
			if err != nil {
				return nil, err
			}
			return map[string]any{"ok": true, "session": info}, nil
		})
	default:
		writeErr(w, httpError{http.StatusMethodNotAllowed, errors.New("use GET or POST")})
	}
}

// ---- one flow ----

func (a *API) uiFlow(r *http.Request) (any, error) {
	return a.Svc.UIFlow(qSession(r), r.URL.Query().Get("id"))
}

// uiBody: GET /v1/ui/body?id=&side=req|res&view=lines|hex|search|tree|file|raw&…
func (a *API) uiBody(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeErr(w, httpError{http.StatusMethodNotAllowed, errors.New("use GET")})
		return
	}
	q := r.URL.Query()
	sess, id := qSession(r), q.Get("id")
	side, err := qSide(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	view := q.Get("view")
	if view == "raw" {
		data, ct, err := a.Svc.BodyRaw(sess, id, side)
		if err != nil {
			writeErr(w, err)
			return
		}
		w.Header().Set("Content-Type", ct)
		w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		w.Write(data)
		return
	}
	a.serve(w, r, func(r *http.Request) (any, error) {
		switch view {
		case "", "lines":
			return a.Svc.BodyLines(sess, id, side, qInt(r, "from", 0), qInt(r, "count", 200), qBool(r, "pretty"))
		case "hex":
			return a.Svc.BodyHex(sess, id, side, int64(qInt(r, "offset", 0)), int64(qInt(r, "length", 4096)))
		case "search":
			return a.Svc.BodySearch(sess, id, side, q.Get("find"), qBool(r, "regex"), qBool(r, "case"), qBool(r, "pretty"), qInt(r, "max", 2000))
		case "tree":
			return a.Svc.BodyTree(sess, id, side, q.Get("path"), qInt(r, "from", 0), qInt(r, "count", 200))
		case "file":
			path, size, err := a.Svc.BodyToFile(sess, id, side, qBool(r, "pretty"))
			if err != nil {
				return nil, err
			}
			return map[string]any{"path": path, "size": size}, nil
		}
		return nil, bad("view is lines, hex, search, tree, file or raw")
	})
}

func (a *API) uiMessages(r *http.Request) (any, error) {
	msgs, total, err := a.Svc.UIMessages(qSession(r), r.URL.Query().Get("id"), qInt(r, "from", 0), qInt(r, "limit", 200))
	if err != nil {
		return nil, err
	}
	return map[string]any{"messages": msgs, "total": total}, nil
}

func (a *API) uiMessage(r *http.Request) (any, error) {
	return a.Svc.UIMessageOne(qSession(r), r.URL.Query().Get("id"), qInt(r, "index", 0))
}

func (a *API) uiCurl(r *http.Request) (any, error) {
	c, err := a.Svc.UICurl(qSession(r), r.URL.Query().Get("id"))
	if err != nil {
		return nil, err
	}
	return map[string]string{"curl": c}, nil
}

func (a *API) uiRaw(r *http.Request) (any, error) {
	side, err := qSide(r)
	if err != nil {
		return nil, err
	}
	text, err := a.Svc.UIRaw(qSession(r), r.URL.Query().Get("id"), side)
	if err != nil {
		return nil, err
	}
	return map[string]string{"text": text}, nil
}

// ---- actions on flows ----

func (a *API) uiMark(r *http.Request) (any, error) {
	var in struct {
		Session string   `json:"session"`
		IDs     []flexID `json:"ids"`
		service.UIMark
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	ids, err := idsOf(in.IDs)
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return nil, bad("give ids")
	}
	n, err := a.Svc.MarkFlows(nonEmpty(in.Session, qSession(r)), ids, in.UIMark)
	if err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "changed": n}, nil
}

func (a *API) uiDelete(r *http.Request) (any, error) {
	var in struct {
		Session string   `json:"session"`
		IDs     []flexID `json:"ids"`
		All     bool     `json:"all"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	ids, err := idsOf(in.IDs)
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 && !in.All {
		return nil, bad("give ids, or all: true")
	}
	n, err := a.Svc.DeleteFlows(nonEmpty(in.Session, qSession(r)), ids, in.All)
	if err != nil {
		return nil, err
	}
	return map[string]any{"deleted": n}, nil
}

func (a *API) uiSend(r *http.Request) (any, error) {
	var in struct {
		Session         string        `json:"session"`
		From            flexID        `json:"from"`
		Method          *string       `json:"method"`
		URL             *string       `json:"url"`
		Headers         *flow.Headers `json:"headers"`
		BodyText        *string       `json:"body_text"`
		BodyBase64      *string       `json:"body_base64"`
		BodyFile        *string       `json:"body_file"`
		FollowRedirects bool          `json:"follow_redirects"`
		TimeoutMs       int           `json:"timeout_ms"`
		Repeat          struct {
			Count       int `json:"count"`
			IntervalMs  int `json:"interval_ms"`
			Concurrency int `json:"concurrency"`
		} `json:"repeat"`
	}
	if err := readJSON(r, &in, bigBody); err != nil {
		return nil, err
	}
	// the sends go on when the GUI stops waiting (each flow shows up on the live list)
	ctx := context.WithoutCancel(r.Context())
	return a.Svc.SendUI(ctx, service.UISend{Session: nonEmpty(in.Session, qSession(r)), From: string(in.From), Method: in.Method,
		URL: in.URL, Headers: in.Headers, BodyText: in.BodyText, BodyBase64: in.BodyBase64, BodyFile: in.BodyFile,
		FollowRedirects: in.FollowRedirects, Timeout: time.Duration(in.TimeoutMs) * time.Millisecond, Count: in.Repeat.Count,
		Interval: time.Duration(in.Repeat.IntervalMs) * time.Millisecond, Concurrency: in.Repeat.Concurrency})
}

// ---- rules ----

// ruleOf decodes {"rule": {…}} and reports whether the rule named "enabled".
func ruleOf(r *http.Request) (*rules.Rule, bool, string, error) {
	var in struct {
		Rule    json.RawMessage `json:"rule"`
		Session string          `json:"session"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, false, "", err
	}
	if len(in.Rule) == 0 || string(in.Rule) == "null" {
		return nil, false, "", bad("give rule (a JSON object)")
	}
	var rule rules.Rule
	if err := json.Unmarshal(in.Rule, &rule); err != nil {
		return nil, false, "", bad("rule: %v", err)
	}
	var fields map[string]json.RawMessage
	_ = json.Unmarshal(in.Rule, &fields)
	_, has := fields["enabled"]
	return &rule, has, in.Session, nil
}

func (a *API) uiRules(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		list, vars := a.Svc.UIRules()
		writeJSON(w, map[string]any{"rules": list, "vars": vars})
	case http.MethodPost:
		a.serve(w, r, func(r *http.Request) (any, error) {
			rule, hasEnabled, _, err := ruleOf(r)
			if err != nil {
				return nil, err
			}
			saved, err := a.Svc.UISaveRule(rule, hasEnabled)
			if err != nil {
				return nil, err
			}
			return map[string]any{"rule": saved}, nil
		})
	default:
		writeErr(w, httpError{http.StatusMethodNotAllowed, errors.New("use GET or POST")})
	}
}

func (a *API) uiRuleEnable(r *http.Request) (any, error) {
	var in struct {
		ID string `json:"id"`
		On bool   `json:"on"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	if err := a.Svc.Rules.Enable(in.ID, in.On); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

func (a *API) uiRuleRemove(r *http.Request) (any, error) {
	var in struct {
		ID string `json:"id"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	if err := a.Svc.Rules.Remove(in.ID); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

func (a *API) uiRuleTest(r *http.Request) (any, error) {
	rule, _, sess, err := ruleOf(r)
	if err != nil {
		return nil, err
	}
	text, err := a.Svc.TestRule(rule, nonEmpty(sess, qSession(r)), 10)
	if err != nil {
		return nil, err
	}
	return map[string]string{"text": text}, nil
}

func (a *API) uiVars(r *http.Request) (any, error) {
	var in struct {
		Name   string `json:"name"`
		Value  string `json:"value"`
		Delete bool   `json:"delete"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	if strings.TrimSpace(in.Name) == "" {
		return nil, bad("give name")
	}
	if in.Delete {
		a.Svc.Rules.DeleteVar(in.Name)
	} else {
		a.Svc.Rules.SetVar(in.Name, in.Value)
	}
	return map[string]any{"ok": true, "vars": a.Svc.Rules.Vars()}, nil
}

// ---- breakpoints ----

func (a *API) uiPaused(r *http.Request) (any, error) {
	return map[string]any{"paused": a.Svc.UIPaused(false)}, nil
}

// uiPausedBody: GET /v1/ui/paused/body?id=x3 → the paused flow's whole decoded body.
func (a *API) uiPausedBody(w http.ResponseWriter, r *http.Request) {
	data, ct, err := a.Svc.PausedBody(r.URL.Query().Get("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	if ct == "" {
		ct = "application/octet-stream"
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Write(data)
}

func (a *API) uiResolve(r *http.Request) (any, error) {
	var in service.UIResolve
	if err := readJSON(r, &in, bigBody); err != nil {
		return nil, err
	}
	if err := a.Svc.ResolvePaused(in); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

func (a *API) uiExtend(r *http.Request) (any, error) {
	var in struct {
		ID      string `json:"id"`
		Seconds int    `json:"seconds"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	if in.Seconds == 0 {
		in.Seconds = 60
	}
	left, err := a.Svc.ExtendPaused(in.ID, in.Seconds)
	if err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "remaining_ms": left}, nil
}

func (a *API) uiBreakNext(r *http.Request) (any, error) {
	var in struct {
		Match    string `json:"match"`
		Phase    string `json:"phase"`
		Count    int    `json:"count"`
		TimeoutS int    `json:"timeout_s"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	rule, err := a.Svc.BreakNext(in.Match, in.Phase, in.Count, in.TimeoutS)
	if err != nil {
		return nil, err
	}
	return map[string]any{"rule": rule}, nil
}

// ---- scripts ----

func (a *API) uiScripts(r *http.Request) (any, error) {
	return map[string]any{"scripts": a.Svc.UIScripts()}, nil
}

func (a *API) uiScriptEnable(r *http.Request) (any, error) {
	var in struct {
		Name string `json:"name"`
		On   bool   `json:"on"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	if err := a.Svc.Scripts.Enable(in.Name, in.On); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

func (a *API) uiScriptRemove(r *http.Request) (any, error) {
	var in struct {
		Name string `json:"name"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	if err := a.Svc.Scripts.Remove(in.Name); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

func (a *API) uiScriptNew(r *http.Request) (any, error) {
	var in struct {
		Name    string `json:"name"`
		Code    string `json:"code"`
		Session bool   `json:"session"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	file, loadErr, err := a.Svc.UINewScript(in.Name, in.Code, in.Session)
	if err != nil {
		return nil, err
	}
	out := map[string]any{"file": file}
	if loadErr != "" {
		out["error"] = loadErr
	}
	return out, nil
}

func (a *API) uiScriptLogs(r *http.Request) (any, error) {
	lines, err := a.Svc.UIScriptLogs(r.URL.Query().Get("name"), qInt(r, "lines", 200))
	if err != nil {
		return nil, err
	}
	return map[string]any{"lines": lines}, nil
}

// ---- capture ----

func (a *API) uiCapturePause(r *http.Request) (any, error) {
	var in struct {
		On bool `json:"on"`
	}
	if err := readJSON(r, &in, smallBody); err != nil {
		return nil, err
	}
	a.Svc.Pause(in.On)
	return map[string]any{"ok": true, "capture": a.Svc.CaptureInfo()}, nil
}

func nonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}
