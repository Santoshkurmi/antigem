// Package rules is the no-code way to change traffic: block, mock, serve a local file, send to another URL, rewrite
// headers / status / query / body / JSON, delay or throttle, tag, copy values into variables, pause (breakpoint).
// Rules are JSON (rules.json), matched with the filter language, applied in priority order.
package rules

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"

	"antigem/netcap/internal/query"
)

// Action of a rule.
type Action struct {
	Type string `json:"type"` // block, mock, map_local, map_remote, rewrite, delay, tag, breakpoint, set_var, passthrough, ignore

	// block: 0 = reset the connection, else answer with this status
	// mock: the status to answer (default 200)
	Status int `json:"status,omitempty"`
	// mock / map_local
	Headers     map[string]string `json:"headers,omitempty"`
	Body        string            `json:"body,omitempty"`
	JSON        any               `json:"json,omitempty"`
	File        string            `json:"file,omitempty"` // map_local: a file, or a folder (+ the request path)
	ContentType string            `json:"content_type,omitempty"`
	// map_remote: scheme://host[:port][/prefix]; the request path and query are appended unless keep_path is false
	URL      string `json:"url,omitempty"`
	KeepPath *bool  `json:"keep_path,omitempty"`
	// rewrite
	SetHeaders    map[string]string `json:"set_headers,omitempty"`
	RemoveHeaders []string          `json:"remove_headers,omitempty"`
	SetStatus     int               `json:"set_status,omitempty"`
	SetQuery      map[string]string `json:"set_query,omitempty"`
	RemoveQuery   []string          `json:"remove_query,omitempty"`
	Replace       []Replace         `json:"replace,omitempty"`
	JSONSet       map[string]any    `json:"json_set,omitempty"`
	JSONDelete    []string          `json:"json_delete,omitempty"`
	SetMethod     string            `json:"set_method,omitempty"`
	ClearBody     bool              `json:"clear_body,omitempty"`
	// delay
	Ms   int `json:"ms,omitempty"`
	KBps int `json:"kbps,omitempty"` // throttle the response body
	// tag
	Tag string `json:"tag,omitempty"`
	// set_var: var = value taken from: res.json:.path, req.json:.path, res.header:Name, req.header:Name,
	// res.body:~regex(with group), query:key
	Var  string `json:"var,omitempty"`
	From string `json:"from,omitempty"`
	// breakpoint: auto-continue after this many seconds (default 30)
	TimeoutS int `json:"timeout_s,omitempty"`
}

// Replace is one find/replace in a body.
type Replace struct {
	Find  string `json:"find"`
	With  string `json:"with"`
	Regex bool   `json:"regex,omitempty"`
}

// Rule is one rule.
type Rule struct {
	ID       string `json:"id"`
	Name     string `json:"name,omitempty"`
	Enabled  bool   `json:"enabled"`
	Match    string `json:"match"`
	Phase    string `json:"phase,omitempty"` // request or response
	Action   Action `json:"action"`
	Priority int    `json:"priority,omitempty"`
	Hits     int64  `json:"hits"`

	filter *query.Filter
	hits   atomic.Int64
	re     []*regexp.Regexp
}

// Filter is the compiled match.
func (r *Rule) Filter() *query.Filter { return r.filter }

// Hit counts a use.
func (r *Rule) Hit() { r.hits.Add(1) }

// Regexps of the replace list (compiled).
func (r *Rule) Regexps() []*regexp.Regexp { return r.re }

var types = map[string]string{ // type → default phase
	"block": "request", "mock": "request", "map_local": "request", "map_remote": "request", "rewrite": "request",
	"delay": "request", "tag": "response", "breakpoint": "request", "set_var": "response", "passthrough": "connect", "ignore": "connect",
}

// Validate checks a rule and compiles its parts.
func (r *Rule) Validate() error {
	def, ok := types[r.Action.Type]
	if !ok {
		return fmt.Errorf("action type must be one of: block, mock, map_local, map_remote, rewrite, delay, tag, breakpoint, set_var, passthrough, ignore")
	}
	if r.Phase == "" {
		r.Phase = def
	}
	if def == "connect" {
		r.Phase = "connect"
	} else if r.Phase != "request" && r.Phase != "response" {
		return fmt.Errorf("phase must be request or response")
	}
	f, err := query.Parse(r.Match)
	if err != nil {
		return fmt.Errorf("match: %w", err)
	}
	r.filter = f
	a := r.Action
	switch a.Type {
	case "map_remote":
		if !strings.HasPrefix(a.URL, "http://") && !strings.HasPrefix(a.URL, "https://") {
			return errors.New("map_remote needs url: http(s)://host[:port][/prefix]")
		}
		if r.Phase != "request" {
			return errors.New("map_remote works in the request phase")
		}
	case "map_local":
		if a.File == "" {
			return errors.New("map_local needs file (a file, or a folder)")
		}
	case "set_var":
		if a.Var == "" || a.From == "" {
			return errors.New("set_var needs var and from (res.json:.path, res.header:Name, req.header:Name, res.body:~regex(group), query:key)")
		}
	case "delay":
		if a.Ms <= 0 && a.KBps <= 0 {
			return errors.New("delay needs ms and/or kbps")
		}
	case "tag":
		if a.Tag == "" {
			return errors.New("tag needs tag")
		}
	}
	r.re = nil
	for _, rp := range a.Replace {
		if rp.Regex {
			re, err := regexp.Compile(rp.Find)
			if err != nil {
				return fmt.Errorf("replace: %w", err)
			}
			r.re = append(r.re, re)
		} else {
			r.re = append(r.re, nil)
		}
	}
	return nil
}

// Set is the rule list, saved to a file.
type Set struct {
	path    string
	mu      sync.RWMutex
	list    []*Rule
	next    int
	vars    map[string]string
	enabled atomic.Int32
}

// EnabledCount is how many rules are on (a cheap check for every request).
func (s *Set) EnabledCount() int { return int(s.enabled.Load()) }

// Load reads rules from path (missing = none).
func Load(path string) (*Set, error) {
	s := &Set{path: path, vars: map[string]string{}}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return s, err
	}
	var file struct {
		Rules []*Rule           `json:"rules"`
		Vars  map[string]string `json:"vars"`
	}
	if err := json.Unmarshal(b, &file); err != nil {
		return s, fmt.Errorf("%s: %w", path, err)
	}
	for _, r := range file.Rules {
		if r.Validate() == nil {
			r.hits.Store(r.Hits)
			s.list = append(s.list, r)
		}
	}
	if file.Vars != nil {
		s.vars = file.Vars
	}
	s.sort()
	for _, r := range s.list {
		if n, err := strconv.Atoi(strings.TrimPrefix(r.ID, "r")); err == nil && n > s.next {
			s.next = n
		}
	}
	return s, nil
}

func (s *Set) sort() {
	sort.SliceStable(s.list, func(i, j int) bool { return s.list[i].Priority > s.list[j].Priority })
	n := 0
	for _, r := range s.list {
		if r.Enabled {
			n++
		}
	}
	s.enabled.Store(int32(n))
}

// Save writes the rules (with hit counts) and variables.
func (s *Set) Save() error {
	s.mu.RLock()
	for _, r := range s.list {
		r.Hits = r.hits.Load()
	}
	b, err := json.MarshalIndent(map[string]any{"rules": s.list, "vars": s.vars}, "", "  ")
	s.mu.RUnlock()
	if err != nil || s.path == "" {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// Add validates and adds a rule (a new id when it has none).
func (s *Set) Add(r *Rule) (*Rule, error) {
	if err := r.Validate(); err != nil {
		return nil, err
	}
	s.mu.Lock()
	if r.ID == "" {
		s.next++
		r.ID = "r" + strconv.Itoa(s.next)
	}
	for _, x := range s.list {
		if x.ID == r.ID {
			s.mu.Unlock()
			return nil, fmt.Errorf("rule %s exists (use update)", r.ID)
		}
	}
	s.list = append(s.list, r)
	s.sort()
	s.mu.Unlock()
	return r, s.Save()
}

// Update replaces a rule.
func (s *Set) Update(r *Rule) error {
	if err := r.Validate(); err != nil {
		return err
	}
	s.mu.Lock()
	found := false
	for i, x := range s.list {
		if x.ID == r.ID {
			r.hits.Store(x.hits.Load())
			s.list[i] = r
			found = true
		}
	}
	s.sort()
	s.mu.Unlock()
	if !found {
		return fmt.Errorf("no rule %s", r.ID)
	}
	return s.Save()
}

// Remove deletes a rule.
func (s *Set) Remove(id string) error {
	s.mu.Lock()
	out := s.list[:0]
	found := false
	for _, x := range s.list {
		if x.ID == id {
			found = true
			continue
		}
		out = append(out, x)
	}
	s.list = out
	s.sort()
	s.mu.Unlock()
	if !found {
		return fmt.Errorf("no rule %s", id)
	}
	return s.Save()
}

// Enable turns a rule on or off.
func (s *Set) Enable(id string, on bool) error {
	s.mu.Lock()
	found := false
	for _, x := range s.list {
		if x.ID == id {
			x.Enabled = on
			found = true
		}
	}
	s.sort()
	s.mu.Unlock()
	if !found {
		return fmt.Errorf("no rule %s", id)
	}
	return s.Save()
}

// Get returns a rule.
func (s *Set) Get(id string) *Rule {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, x := range s.list {
		if x.ID == id {
			return x
		}
	}
	return nil
}

// All returns the rules in order.
func (s *Set) All() []*Rule {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, r := range s.list {
		r.Hits = r.hits.Load()
	}
	return append([]*Rule(nil), s.list...)
}

// Phase returns the enabled rules of one phase, in order.
func (s *Set) Phase(phase string) []*Rule {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out []*Rule
	for _, r := range s.list {
		if r.Enabled && r.Phase == phase {
			out = append(out, r)
		}
	}
	return out
}

// Var returns a variable.
func (s *Set) Var(name string) (string, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	v, ok := s.vars[name]
	return v, ok
}

// SetVar sets a variable (saved).
func (s *Set) SetVar(name, value string) {
	s.mu.Lock()
	changed := s.vars[name] != value
	s.vars[name] = value
	s.mu.Unlock()
	if changed {
		_ = s.Save()
	}
}

// DeleteVar removes a variable (saved).
func (s *Set) DeleteVar(name string) {
	s.mu.Lock()
	_, had := s.vars[name]
	delete(s.vars, name)
	s.mu.Unlock()
	if had {
		_ = s.Save()
	}
}

// Vars returns all variables.
func (s *Set) Vars() map[string]string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := map[string]string{}
	for k, v := range s.vars {
		out[k] = v
	}
	return out
}

var varRe = regexp.MustCompile(`\{\{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*\}\}`)

// Expand replaces {{name}} with variables (unknown ones stay).
func (s *Set) Expand(text string) string {
	if !strings.Contains(text, "{{") {
		return text
	}
	return varRe.ReplaceAllStringFunc(text, func(m string) string {
		name := varRe.FindStringSubmatch(m)[1]
		if v, ok := s.Var(name); ok {
			return v
		}
		return m
	})
}

// ExpandAny expands strings inside a JSON value.
func (s *Set) ExpandAny(v any) any {
	switch x := v.(type) {
	case string:
		return s.Expand(x)
	case map[string]any:
		out := map[string]any{}
		for k, val := range x {
			out[k] = s.ExpandAny(val)
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, val := range x {
			out[i] = s.ExpandAny(val)
		}
		return out
	}
	return v
}

// EditJSON applies json_set / json_delete to a JSON body. ok=false: the body is not JSON.
func EditJSON(body []byte, set map[string]any, deletes []string, expand func(any) any) ([]byte, bool, error) {
	var doc any
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if err := dec.Decode(&doc); err != nil {
		if len(bytes.TrimSpace(body)) == 0 {
			doc = map[string]any{}
		} else {
			return body, false, nil
		}
	}
	keys := make([]string, 0, len(set))
	for k := range set {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var err error
	for _, k := range keys {
		v := set[k]
		if expand != nil {
			v = expand(v)
		}
		if doc, err = jsonSet(doc, k, v); err != nil {
			return body, true, err
		}
	}
	for _, k := range deletes {
		if doc, err = jsonDelete(doc, k); err != nil {
			return body, true, err
		}
	}
	out, err := json.Marshal(doc)
	return out, true, err
}

// GetJSON reads a path from a JSON body.
func GetJSON(body []byte, path string) (any, bool) {
	var doc any
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if dec.Decode(&doc) != nil {
		return nil, false
	}
	return jsonGet(doc, path)
}
