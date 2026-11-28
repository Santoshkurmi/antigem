package service

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/rules"
	"antigem/netcap/internal/view"
)

// ---- WebSocket messages and raw chunks ----

// UIMessage is one message of a WebSocket or raw flow (a 4 KB preview in lists).
type UIMessage struct {
	Index      int    `json:"index"`
	Dir        string `json:"dir"`
	Time       int64  `json:"time"` // unix ms
	Type       string `json:"type"`
	Size       int    `json:"size"`
	Truncated  bool   `json:"truncated,omitempty"` // only the first part was stored
	Compressed bool   `json:"compressed,omitempty"`
	Binary     bool   `json:"binary"`
	Text       string `json:"text,omitempty"`        // UTF-8 text (the preview in lists)
	Hex        string `json:"hex,omitempty"`         // lists, binary messages: the first 64 bytes as hex
	DataBase64 string `json:"data_base64,omitempty"` // one message, binary: all stored bytes
}

func uiMessage(m flow.Message, whole bool) UIMessage {
	u := UIMessage{Index: m.Index, Dir: m.Dir, Time: m.Time.UnixMilli(), Type: m.Type, Size: m.Size, Truncated: m.Truncated,
		Compressed: m.Compressed}
	u.Binary = !(m.Type == "text" || (len(m.Data) > 0 && isText(m.Data)))
	switch {
	case !u.Binary && whole:
		u.Text = view.ToText(m.Data)
	case !u.Binary:
		u.Text = cutBytes(view.ToText(m.Data), 4096)
	case whole:
		u.DataBase64 = base64.StdEncoding.EncodeToString(m.Data)
	default:
		u.Hex = hex.EncodeToString(m.Data[:min(len(m.Data), 64)])
	}
	return u
}

// UIMessages lists messages from index from (limit default 200, at most 1000) and how many there are.
func (s *Service) UIMessages(session, id string, from, limit int) ([]UIMessage, int, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, 0, err
	}
	if limit <= 0 {
		limit = 200
	}
	limit = min(limit, 1000)
	sess.Store.Flush()
	msgs, err := sess.Store.Messages(f.ID, max(0, from), limit)
	if err != nil {
		return nil, 0, err
	}
	out := make([]UIMessage, 0, len(msgs))
	for _, m := range msgs {
		out = append(out, uiMessage(m, false))
	}
	return out, sess.Store.MessageCount(f.ID), nil
}

// UIMessageOne returns one whole message.
func (s *Service) UIMessageOne(session, id string, index int) (*UIMessage, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, err
	}
	sess.Store.Flush()
	msgs, err := sess.Store.Messages(f.ID, index, 1)
	if err != nil {
		return nil, err
	}
	if len(msgs) == 0 || msgs[0].Index != index {
		return nil, fmt.Errorf("no message %s.m%d", FID(f.ID), index)
	}
	m := uiMessage(msgs[0], true)
	return &m, nil
}

// ---- curl and raw HTTP ----

// UICurl returns a flow as a curl command (secrets shown: the GUI is the user).
func (s *Service) UICurl(session, id string) (string, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return "", err
	}
	return s.curl(sess, f, true), nil
}

// UIRaw returns one side as raw HTTP text: the first line, the headers and the decoded body when it is text and at
// most 1 MB.
func (s *Service) UIRaw(session, id, side string) (string, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	h := f.ReqHeaders
	if side == "res" {
		h = f.ResHeaders
		if f.Status == 0 {
			return "(no response) " + f.Error + "\n", nil
		}
		fmt.Fprintf(&b, "HTTP/%s %d\n", nonEmpty(f.HTTPVersion, "1.1"), f.Status)
	} else {
		target := f.Path
		if f.Query != "" {
			target += "?" + f.Query
		}
		fmt.Fprintf(&b, "%s %s HTTP/%s\n", nonEmpty(f.Method, "GET"), target, nonEmpty(f.HTTPVersion, "1.1"))
	}
	for _, kv := range h {
		fmt.Fprintf(&b, "%s: %s\n", kv[0], kv[1])
	}
	b.WriteString("\n")
	body := bodyOf(f, side)
	if body.Hash == "" {
		return b.String(), nil
	}
	err = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		switch {
		case len(d.data) > 1<<20:
			fmt.Fprintf(&b, "(%s body, %s: too big to show here)\n", nonEmpty(body.Type, "binary"), Size(int64(len(d.data))))
		case view.IsText(body.Type) || (textType(body.Type) && isText(d.data)):
			b.WriteString(view.ToText(d.data))
		default:
			fmt.Fprintf(&b, "(%s body, %s)\n", nonEmpty(body.Type, "binary"), Size(int64(len(d.data))))
		}
		return nil
	})
	return b.String(), err
}

// ---- rules ----

// UIRules returns the rules (with live hit counts) and the variables.
func (s *Service) UIRules() ([]*rules.Rule, map[string]string) {
	return s.Rules.Snapshot(), s.Rules.Vars()
}

// UISaveRule adds a rule (no id) or replaces one. hasEnabled: the JSON named "enabled" (a new rule is on unless it
// says otherwise; an update keeps the rule's state unless it says).
func (s *Service) UISaveRule(r *rules.Rule, hasEnabled bool) (*rules.Rule, error) {
	if r.ID == "" {
		if !hasEnabled {
			r.Enabled = true
		}
		added, err := s.Rules.Add(r)
		if err != nil {
			return nil, err
		}
		return s.ruleCopy(added.ID), nil
	}
	if !hasEnabled {
		old := s.Rules.Get(r.ID)
		if old == nil {
			return nil, fmt.Errorf("no rule %s", r.ID)
		}
		for _, x := range s.Rules.Snapshot() {
			if x.ID == r.ID {
				r.Enabled = x.Enabled
			}
		}
	}
	if err := s.Rules.Update(r); err != nil {
		return nil, err
	}
	return s.ruleCopy(r.ID), nil
}

func (s *Service) ruleCopy(id string) *rules.Rule {
	for _, r := range s.Rules.Snapshot() {
		if r.ID == id {
			return r
		}
	}
	return nil
}

// BreakNext adds a breakpoint rule that removes itself after count hits (default 1).
func (s *Service) BreakNext(match, phase string, count, timeoutS int) (*rules.Rule, error) {
	if count <= 0 {
		count = 1
	}
	if phase == "" {
		phase = "request"
	}
	name := "break next"
	if count > 1 {
		name = fmt.Sprintf("break next %d", count)
	}
	r := &rules.Rule{Name: name, Enabled: true, Match: match, Phase: phase, Once: count,
		Action: rules.Action{Type: "breakpoint", TimeoutS: timeoutS}}
	added, err := s.Rules.Add(r)
	if err != nil {
		return nil, err
	}
	return s.ruleCopy(added.ID), nil
}

// ---- scripts ----

// UIScript is one script for the scripts screen.
type UIScript struct {
	Name      string   `json:"name"`
	File      string   `json:"file"`
	Session   bool     `json:"session"` // in the session's folder (else global)
	Enabled   bool     `json:"enabled"`
	Error     string   `json:"error,omitempty"` // load error: the script doesn't run
	Match     string   `json:"match,omitempty"`
	Hooks     []string `json:"hooks"`
	Priority  int      `json:"priority"`
	Calls     int64    `json:"calls"`
	Fails     int64    `json:"fails"`
	Skipped   int64    `json:"skipped"` // all workers were busy
	Commands  []string `json:"commands"`
	AvgMs     float64  `json:"avg_ms"`
	MaxMs     float64  `json:"max_ms"`
	LastError string   `json:"last_error,omitempty"`
	OffReason string   `json:"off_reason,omitempty"` // the engine turned it off (failed too often)
	Warnings  []string `json:"warnings,omitempty"`
	TimeoutMs float64  `json:"timeout_ms"` // traffic hooks
	Workers   int      `json:"workers"`
}

// UIScripts lists the scripts.
func (s *Service) UIScripts() []UIScript {
	out := []UIScript{}
	if s.Scripts == nil {
		return out
	}
	for _, sc := range s.Scripts.All() {
		st := sc.Stats()
		u := UIScript{Name: sc.Name, File: sc.File, Session: sc.Session, Enabled: sc.Enabled(), Error: sc.Err, Match: sc.Match,
			Hooks: []string{}, Priority: sc.Priority, Calls: st.Calls, Fails: st.Errors, Skipped: st.Busy,
			Commands: append([]string{}, sc.Commands...), AvgMs: st.AvgMs, MaxMs: st.MaxMs, LastError: st.LastError,
			OffReason: sc.OffReason(), Warnings: sc.Warnings, TimeoutMs: float64(sc.Timeout.Microseconds()) / 1000, Workers: sc.Workers}
		for _, k := range []string{"request", "response", "message", "complete"} {
			if sc.Hooks[k] {
				u.Hooks = append(u.Hooks, k)
			}
		}
		out = append(out, u)
	}
	return out
}

// UINewScript writes a script file (the loader picks it up) and returns its path and load error.
func (s *Service) UINewScript(name, code string, session bool) (string, string, error) {
	if s.Scripts == nil {
		return "", "", errors.New("scripts are not running")
	}
	sc, err := s.Scripts.Save(name, code, session)
	if err != nil {
		return "", "", err
	}
	return sc.File, sc.Err, nil
}

// UIScriptLogs returns the last lines of a script's log.
func (s *Service) UIScriptLogs(name string, lines int) ([]string, error) {
	if s.Scripts == nil {
		return nil, errors.New("scripts are not running")
	}
	sc := s.Scripts.Get(name)
	if sc == nil {
		return nil, fmt.Errorf("no script %q", name)
	}
	if lines <= 0 {
		lines = 200
	}
	out := sc.Logs(min(lines, 2000))
	if out == nil {
		out = []string{}
	}
	return out, nil
}

// ---- breakpoints ----

// UIResolve answers a paused flow from the GUI. Replace (continue with changes) gives whole values: the header
// list replaces all headers, the body replaces the body (any size; body_file is streamed). Without Replace the flow
// continues unchanged.
type UIResolve struct {
	ID      string `json:"id"`
	Action  string `json:"action"` // continue (default), drop, respond
	Replace *struct {
		Method     *string       `json:"method"`
		URL        *string       `json:"url"`
		Status     int           `json:"status"`
		Headers    *flow.Headers `json:"headers"`
		BodyText   *string       `json:"body_text"`
		BodyBase64 *string       `json:"body_base64"`
		BodyFile   *string       `json:"body_file"`
	} `json:"replace"`
	Respond *struct {
		Status     int          `json:"status"`
		Headers    flow.Headers `json:"headers"`
		BodyText   *string      `json:"body_text"`
		BodyBase64 *string      `json:"body_base64"`
	} `json:"respond"`
}

// ResolvePaused carries out a UIResolve.
func (s *Service) ResolvePaused(r UIResolve) error {
	d := Decision{Action: r.Action}
	switch r.Action {
	case "", "continue":
		d.Action = "continue"
		if x := r.Replace; x != nil {
			p := Patch{Status: x.Status, Headers: x.Headers}
			if x.Method != nil {
				p.Method = strings.ToUpper(strings.TrimSpace(*x.Method))
			}
			if x.URL != nil {
				p.URL = strings.TrimSpace(*x.URL)
			}
			switch {
			case x.BodyFile != nil:
				if st, err := os.Stat(*x.BodyFile); err != nil || st.IsDir() {
					return fmt.Errorf("body_file %s is not a readable file", *x.BodyFile)
				}
				p.BodyFile = *x.BodyFile
			case x.BodyBase64 != nil:
				data, err := base64.StdEncoding.DecodeString(strings.TrimSpace(*x.BodyBase64))
				if err != nil {
					return fmt.Errorf("body_base64: %w", err)
				}
				p.Body, p.BodySet = data, true
			case x.BodyText != nil:
				p.Body, p.BodySet, p.BodyPlain = []byte(*x.BodyText), true, true
			}
			d.Patch = p
		}
	case "drop":
	case "respond":
		a := &Answer{Status: 200}
		if x := r.Respond; x != nil {
			a.Status, a.Headers = x.Status, x.Headers
			switch {
			case x.BodyBase64 != nil:
				data, err := base64.StdEncoding.DecodeString(strings.TrimSpace(*x.BodyBase64))
				if err != nil {
					return fmt.Errorf("body_base64: %w", err)
				}
				a.Body = data
			case x.BodyText != nil:
				a.Body, a.BodyPlain = []byte(*x.BodyText), true
			}
		}
		d.Answer = a
	default:
		return errors.New("action is continue, drop or respond")
	}
	return s.Resolve(r.ID, d)
}
