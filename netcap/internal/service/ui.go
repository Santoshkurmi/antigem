package service

import (
	"encoding/base64"
	"errors"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
)

// The native GUI's use cases (JSON shapes for /v1/ui/…). The GUI is the user: nothing here hides secrets.

// UIRow is one compact list item.
type UIRow struct {
	ID         int64     `json:"id"`
	Kind       flow.Kind `json:"kind"`
	App        string    `json:"app,omitempty"`
	Start      int64     `json:"start"` // unix ms
	Method     string    `json:"method,omitempty"`
	Scheme     string    `json:"scheme,omitempty"`
	Host       string    `json:"host,omitempty"`
	Port       int       `json:"port,omitempty"`
	Path       string    `json:"path,omitempty"`
	Query      string    `json:"query,omitempty"`
	HTTP       string    `json:"http,omitempty"`
	Status     int       `json:"status,omitempty"`
	ReqType    string    `json:"req_type,omitempty"`
	ResType    string    `json:"res_type,omitempty"`
	ReqSize    int64     `json:"req_size,omitempty"`
	ResSize    int64     `json:"res_size,omitempty"`
	Ms         float64   `json:"ms,omitempty"`
	TLS        string    `json:"tls,omitempty"`
	Error      string    `json:"error,omitempty"`
	Done       bool      `json:"done"`
	Flags      []string  `json:"flags,omitempty"`
	Tags       []string  `json:"tags,omitempty"`
	Messages   int       `json:"messages,omitempty"`
	Pinned     bool      `json:"pinned,omitempty"`
	Color      string    `json:"color,omitempty"`
	Note       string    `json:"note,omitempty"`
	ResentFrom int64     `json:"resent_from,omitempty"`
	Paused     string    `json:"paused,omitempty"` // the paused item (x3) while a breakpoint holds the flow
}

func uiRow(f *flow.Flow, paused string) UIRow {
	ms := f.Timings.Total
	if !f.Done {
		ms = float64(time.Since(f.Start).Microseconds()) / 1000
	}
	return UIRow{ID: f.ID, Kind: f.Kind, App: f.App, Start: f.Start.UnixMilli(), Method: f.Method, Scheme: f.Scheme, Host: f.Host,
		Port: f.Port, Path: f.Path, Query: cutBytes(f.Query, 500), HTTP: f.HTTPVersion, Status: f.Status, ReqType: f.Req.Type,
		ResType: f.Res.Type, ReqSize: f.Req.Size, ResSize: f.Res.Size, Ms: math.Round(ms*10) / 10, TLS: f.TLS, Error: f.Error,
		Done: f.Done, Flags: f.Flags, Tags: f.Tags, Messages: f.Messages, Pinned: f.Pinned, Color: f.Color, Note: cutBytes(f.Note, 500),
		ResentFrom: f.ResentFrom, Paused: paused}
}

// cutBytes cuts s to at most n bytes on a UTF-8 boundary.
func cutBytes(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}

type pausedKey struct {
	session string
	flow    int64
}

// pausedByFlow maps flows held by a breakpoint to their paused item.
func (s *Service) pausedByFlow() map[pausedKey]string {
	list := s.PausedList()
	m := make(map[pausedKey]string, len(list))
	for _, p := range list {
		m[pausedKey{p.Session, p.FlowID}] = p.ID
	}
	return m
}

// ---- lists ----

// UIRowsResult of UIRows.
type UIRowsResult struct {
	Session string  `json:"session"`
	Total   int     `json:"total"` // flows matching q (-1 on a cursor page when counting needs a scan)
	Rows    []UIRow `json:"rows"`
	Next    string  `json:"next"`
	Scanned int     `json:"scanned"`
	ScanCut bool    `json:"scan_cut"`
}

// UIRows lists flows for the GUI: everything (noise too), running flows included, newest first by default.
func (s *Service) UIRows(session, q, cursor, sortBy string, limit int) (*UIRowsResult, error) {
	switch sortBy {
	case "", "newest", "oldest", "slowest", "largest":
	default:
		return nil, fmt.Errorf("sort is newest, oldest, slowest or largest")
	}
	if limit <= 0 {
		limit = 200
	}
	limit = min(limit, 1000)
	flt, err := query.Parse(q)
	if err != nil {
		return nil, err
	}
	sqlOnly := flt.SQLOnly()
	res, err := s.List(ListOptions{Session: session, Filter: q, Limit: limit, Cursor: cursor, Sort: sortBy, Noise: true, Secrets: true,
		MaxLimit: 1000, CountAll: !sqlOnly && cursor == ""})
	if err != nil {
		return nil, err
	}
	out := &UIRowsResult{Session: res.Session.ID, Rows: make([]UIRow, 0, len(res.Rows)), Next: res.Next, Scanned: res.Scanned,
		ScanCut: res.ScanCut || res.CountCut}
	switch {
	case sqlOnly:
		where, args := flt.SQL()
		out.Total = res.Session.Store.Count(where, args...)
	case cursor == "" || sortBy == "slowest" || sortBy == "largest":
		out.Total = res.Matched
	default:
		out.Total = -1
	}
	paused := s.pausedByFlow()
	for _, r := range res.Rows {
		out.Rows = append(out.Rows, uiRow(r.Flow, paused[pausedKey{res.Session.ID, r.Flow.ID}]))
	}
	return out, nil
}

// UIGroup is one group of UIGroups.
type UIGroup struct {
	Key      string         `json:"key"`
	Count    int            `json:"count"`
	Errors   int            `json:"errors"` // failed or status >= 400
	Last     int64          `json:"last"`   // unix ms of the newest flow
	AvgMs    float64        `json:"avg_ms"`
	MaxMs    float64        `json:"max_ms"`
	Statuses map[string]int `json:"statuses"` // "0" = no response
}

// UIGroups groups flows by host, app, endpoint, status, type, kind, method, path or tag (most flows first).
func (s *Service) UIGroups(session, by, q string, limit int) ([]UIGroup, error) {
	switch by {
	case "":
		by = "host"
	case "host", "app", "endpoint", "status", "type", "kind", "method", "path", "tag":
	default:
		return nil, fmt.Errorf("by is host, app, endpoint, status, type, kind, method, path or tag")
	}
	sess, err := s.Session(session)
	if err != nil {
		return nil, err
	}
	flt, err := query.Parse(q)
	if err != nil {
		return nil, err
	}
	if limit <= 0 {
		limit = 500
	}
	limit = min(limit, 5000)
	sess.Store.Flush()
	where, args := flt.SQL()
	bodies := bodySource{s: s, sess: sess}
	scanMax := 50000
	if flt.NeedsBody() {
		scanMax = 3000
	}
	type acc struct {
		g     UIGroup
		total float64
	}
	groups := map[string]*acc{}
	scanned := 0
	err = sess.Store.Scan(where, args, false, func(f *flow.Flow) bool {
		scanned++
		if scanned > scanMax {
			return false
		}
		if !flt.Match(f, bodies) {
			return true
		}
		var keys []string
		switch by {
		case "method":
			keys = []string{f.Method}
		case "path":
			keys = []string{f.Path}
		case "tag":
			keys = f.Tags
			if len(keys) == 0 {
				keys = []string{"(no tag)"}
			}
		default:
			k, _ := groupKey(f, by)
			keys = []string{k}
		}
		for _, k := range keys {
			a := groups[k]
			if a == nil {
				a = &acc{g: UIGroup{Key: k, Statuses: map[string]int{}}}
				groups[k] = a
			}
			a.g.Count++
			if f.Error != "" || f.Status >= 400 {
				a.g.Errors++
			}
			a.g.Last = max(a.g.Last, f.Start.UnixMilli())
			a.total += f.Timings.Total
			a.g.MaxMs = max(a.g.MaxMs, f.Timings.Total)
			a.g.Statuses[strconv.Itoa(f.Status)]++
		}
		return true
	})
	if err != nil {
		return nil, err
	}
	out := make([]UIGroup, 0, len(groups))
	for _, a := range groups {
		a.g.AvgMs = math.Round(a.total/float64(a.g.Count)*10) / 10
		a.g.MaxMs = math.Round(a.g.MaxMs*10) / 10
		out = append(out, a.g)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Count != out[j].Count {
			return out[i].Count > out[j].Count
		}
		return out[i].Key < out[j].Key
	})
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// ---- state ----

// UIState is what the GUI's header shows; the live stream sends it again when it changes.
type UIState struct {
	Capture       CaptureState   `json:"capture"`
	Session       UISessionState `json:"session"`
	Paused        int            `json:"paused"`
	RulesOn       int            `json:"rules_on"`
	ScriptsOn     int            `json:"scripts_on"`
	Findings      int            `json:"findings"`
	EngineVersion string         `json:"engine_version"`
}

// UISessionState is the session part of UIState.
type UISessionState struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Flows int    `json:"flows"`
	Disk  int64  `json:"disk_bytes"`
}

// stateCache keeps the counts that cost a query (flows, findings: every second; disk use: every 5 s).
type stateCache struct {
	mu sync.Mutex
	m  map[string]*sessCounts
}

type sessCounts struct {
	at, diskAt      time.Time
	flows, findings int
	disk            int64
}

func (c *stateCache) get(sess *Session) sessCounts {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil {
		c.m = map[string]*sessCounts{}
	}
	e := c.m[sess.ID]
	if e == nil {
		e = &sessCounts{}
		c.m[sess.ID] = e
	}
	now := time.Now()
	if now.Sub(e.at) >= time.Second {
		e.flows, e.findings = sess.Store.Counts()
		e.at = now
	}
	if now.Sub(e.diskAt) >= 5*time.Second {
		e.disk = sess.Store.DiskBytes()
		e.diskAt = now
	}
	return *e
}

func (c *stateCache) invalidate(id string) {
	c.mu.Lock()
	if e := c.m[id]; e != nil {
		e.at, e.diskAt = time.Time{}, time.Time{}
	}
	c.mu.Unlock()
}

// UIState returns the state for a session ("" = the active one).
func (s *Service) UIState(session string) (UIState, error) {
	sess, err := s.Session(session)
	if err != nil {
		return UIState{}, err
	}
	c := s.uiState.get(sess)
	st := UIState{Capture: s.CaptureInfo(), Session: UISessionState{ID: sess.ID, Name: sess.Name, Flows: c.flows, Disk: c.disk},
		Paused: len(s.PausedList()), RulesOn: s.Rules.EnabledCount(), Findings: c.findings, EngineVersion: s.opts.Version}
	if st.EngineVersion == "" {
		st.EngineVersion = "dev"
	}
	if s.Scripts != nil {
		for _, sc := range s.Scripts.All() {
			if sc.Running() {
				st.ScriptsOn++
			}
		}
	}
	return st, nil
}

// ---- paused items ----

// UIPaused is one flow held by a breakpoint.
type UIPaused struct {
	ID          string       `json:"id"`
	Rule        string       `json:"rule"`
	Phase       string       `json:"phase"`
	Flow        int64        `json:"flow"`
	Session     string       `json:"session"`
	Method      string       `json:"method"`
	URL         string       `json:"url"`
	Status      int          `json:"status,omitempty"`
	Headers     flow.Headers `json:"headers"`
	BodyText    *string      `json:"body_text,omitempty"`   // the decoded body when it is UTF-8 text
	BodyBase64  string       `json:"body_base64,omitempty"` // else the decoded body
	BodyCut     bool         `json:"body_cut,omitempty"`    // the body is not inline (big, or too big to read): /v1/ui/paused/body
	BodySize    int64        `json:"body_size"`             // -1 = unknown (too big to read)
	BodyType    string       `json:"body_type,omitempty"`
	Since       int64        `json:"since"` // unix ms
	TimeoutS    int          `json:"timeout_s"`
	RemainingMs int64        `json:"remaining_ms"`
}

const pausedInlineMax = 256 << 10

// UIPaused lists the paused flows (bodies inline up to 256 KB; all of them when full is set and they were read).
func (s *Service) UIPaused(full bool) []UIPaused {
	list := s.PausedList()
	out := make([]UIPaused, 0, len(list))
	for _, p := range list {
		it := UIPaused{ID: p.ID, Rule: p.Rule, Phase: p.Phase, Flow: p.FlowID, Session: p.Session, Method: p.Method, URL: p.URL,
			Status: p.Status, Headers: p.Headers, BodySize: p.size, BodyType: p.BodyType, Since: p.Since.UnixMilli(),
			TimeoutS: int(p.total().Seconds()), RemainingMs: max(0, p.left().Milliseconds())}
		if it.Headers == nil {
			it.Headers = flow.Headers{}
		}
		switch {
		case p.tooBig || (!full && len(p.full) > pausedInlineMax):
			it.BodyCut = true
		case len(p.full) > 0 && isText(p.full):
			t := string(p.full)
			it.BodyText = &t
		case len(p.full) > 0:
			it.BodyBase64 = base64.StdEncoding.EncodeToString(p.full)
		default:
			t := ""
			it.BodyText = &t
		}
		out = append(out, it)
	}
	return out
}

// PausedBody returns a paused flow's whole decoded body and its content type.
func (s *Service) PausedBody(id string) ([]byte, string, error) {
	for _, p := range s.PausedList() {
		if p.ID == id {
			if p.tooBig {
				return nil, "", fmt.Errorf("%s's body is bigger than %s: it was not read (replace it whole with body_file)", id, Size(editMax))
			}
			return p.full, p.Headers.Get("Content-Type"), nil
		}
	}
	return nil, "", fmt.Errorf("no paused item %s", id)
}

// ExtendPaused gives a paused flow more time before it continues by itself (at most 1 hour in all).
func (s *Service) ExtendPaused(id string, seconds int) (int64, error) {
	if seconds <= 0 {
		return 0, errors.New("seconds must be more than 0")
	}
	for _, p := range s.PausedList() {
		if p.ID == id {
			add := time.Duration(seconds) * time.Second
			if p.total()+add > time.Hour+p.Timeout {
				add = max(0, time.Hour+p.Timeout-p.total())
			}
			p.extra.Add(int64(add))
			s.live.pausedChanged()
			return max(0, p.left().Milliseconds()), nil
		}
	}
	return 0, fmt.Errorf("no paused item %s (it may have continued after its timeout)", id)
}

// isText: valid UTF-8 without many control characters.
func isText(b []byte) bool {
	if !utf8.Valid(b) {
		return false
	}
	bad := 0
	for _, c := range b[:min(len(b), 64<<10)] {
		if c < 0x20 && c != '\n' && c != '\r' && c != '\t' {
			bad++
		}
	}
	return bad*20 < min(len(b), 64<<10)+1
}

// ---- marks, delete ----

// UIMark changes the GUI's marks of flows; nil fields stay as they are.
type UIMark struct {
	Pinned *bool   `json:"pinned"`
	Color  *string `json:"color"`
	Note   *string `json:"note"`
}

// MarkFlows pins, colors or notes flows (also running ones) and sends the changed rows to the live list.
func (s *Service) MarkFlows(session string, ids []int64, m UIMark) (int, error) {
	sess, err := s.Session(session)
	if err != nil {
		return 0, err
	}
	edit := func(f *flow.Flow) {
		if m.Pinned != nil {
			f.Pinned = *m.Pinned
		}
		if m.Color != nil {
			f.Color = strings.TrimSpace(*m.Color)
		}
		if m.Note != nil {
			f.Note = *m.Note
		}
	}
	n := 0
	var firstErr error
	for _, id := range ids {
		c, err := sess.Store.Edit(id, edit)
		if err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		n++
		s.live.flow(s, sess.ID, c, true)
	}
	if n == 0 && firstErr != nil {
		return 0, firstErr
	}
	return n, nil
}

// DeleteFlows deletes flows (all = the whole session's) and tells the live list.
func (s *Service) DeleteFlows(session string, ids []int64, all bool) (int64, error) {
	sess, err := s.Session(session)
	if err != nil {
		return 0, err
	}
	defer s.uiState.invalidate(sess.ID)
	if all {
		n, err := sess.Store.Clear()
		s.live.reset(s, sess.ID)
		return n, err
	}
	n, err := sess.Store.Delete(ids)
	s.live.removed(s, sess.ID, ids)
	return n, err
}

// ---- sessions ----

// SessionAction creates, switches, renames or deletes a session and returns it.
func (s *Service) SessionAction(action, id, name string) (*SessionInfo, error) {
	var target string
	switch action {
	case "new":
		sess, err := s.NewSession(name)
		if err != nil {
			return nil, err
		}
		target = sess.ID
	case "switch":
		sess, err := s.SwitchSession(id)
		if err != nil {
			return nil, err
		}
		target = sess.ID
	case "rename":
		if strings.TrimSpace(name) == "" {
			return nil, errors.New("give name")
		}
		if err := s.RenameSession(id, name); err != nil {
			return nil, err
		}
		target = id
	case "delete":
		if err := s.DeleteSession(id); err != nil {
			return nil, err
		}
		s.uiState.invalidate(id)
		return &SessionInfo{Session: Session{ID: id}}, nil
	default:
		return nil, errors.New("action is new, switch, rename or delete")
	}
	for _, info := range s.Sessions() {
		if info.ID == target {
			return &info, nil
		}
	}
	return nil, fmt.Errorf("no session %s", target)
}
