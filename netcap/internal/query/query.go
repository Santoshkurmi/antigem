// Package query is the filter language used everywhere: MCP filters, rule matches, script @match lines and the
// GUI search box. Example: `app:com.myapp host:*.myapp.dev status:4xx -type:image since:10m`.
//
// Terms separated by spaces are ANDed; `OR` (or `|`) between terms; `-term` negates; parentheses group; a bare word
// means "the URL contains it". The simple terms of a top-level AND also become SQL, so the database does most work.
package query

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/itchyny/gojq"

	"antigem/netcap/internal/flow"
)

// Bodies gives a flow's decoded body text for body:/json: terms (nil when not available).
type Bodies interface {
	Body(f *flow.Flow, side string) []byte // side "req" or "res"
}

// JSCompiler compiles js:"…" terms (a JavaScript expression on the flow f). The script package sets it.
var JSCompiler func(expr string) (func(f *flow.Flow, b Bodies) bool, error)

// Filter is a parsed filter.
type Filter struct {
	src      string
	root     node
	sqlParts []string
	sqlArgs  []any
	// what the filter talks about (for defaults such as hiding noise)
	mentions map[string]bool
}

// Fields lists the filter fields (for help texts and errors).
var Fields = []string{"app", "host", "method", "path", "url", "status", "type", "req.type", "kind", "http", "tls",
	"size", "req.size", "res.size", "time", "since", "until", "after", "before", "id", "tag", "note", "error",
	"req.header.NAME", "res.header.NAME", "header.NAME", "body", "req.body", "res.body", "req.json", "res.json",
	"noise", "flag"}

// Parse parses a filter. An empty string matches everything.
func Parse(src string) (*Filter, error) {
	f := &Filter{src: src, mentions: map[string]bool{}}
	toks, err := lex(src)
	if err != nil {
		return nil, err
	}
	p := &parser{toks: toks, f: f}
	if len(toks) == 0 {
		f.root = all{}
		return f, nil
	}
	n, err := p.or()
	if err != nil {
		return nil, err
	}
	if p.pos < len(p.toks) {
		return nil, fmt.Errorf("unexpected %q", p.toks[p.pos].text)
	}
	f.root = n
	f.pushdown()
	return f, nil
}

// MustParse is Parse that panics (for constant filters).
func MustParse(src string) *Filter {
	f, err := Parse(src)
	if err != nil {
		panic(err)
	}
	return f
}

// String returns the source text.
func (f *Filter) String() string { return f.src }

// Mentions reports whether the filter uses a field (for example "noise" or "type").
func (f *Filter) Mentions(field string) bool { return f.mentions[field] }

// NeedsBody reports whether matching reads bodies (slower; the caller limits how many flows it scans).
func (f *Filter) NeedsBody() bool {
	return f.mentions["body"] || f.mentions["req.body"] || f.mentions["res.body"] || f.mentions["req.json"] || f.mentions["res.json"] ||
		f.mentions["js.body"]
}

// SQL returns a WHERE clause (without "WHERE"; "1" when nothing could be pushed down) and its arguments. Rows that
// pass it must still be checked with Match.
func (f *Filter) SQL() (string, []any) {
	if len(f.sqlParts) == 0 {
		return "1", nil
	}
	return strings.Join(f.sqlParts, " AND "), f.sqlArgs
}

// SQLOnly reports whether SQL() alone decides the filter (every term was pushed down), so a count needs no scan.
func (f *Filter) SQLOnly() bool {
	switch r := f.root.(type) {
	case all:
		return true
	case *pred:
		return r.sql != ""
	case and:
		for _, n := range r {
			if p, ok := n.(*pred); !ok || p.sql == "" {
				return false
			}
		}
		return true
	}
	return false
}

// Match reports whether a flow matches. bodies may be nil (body terms then never match).
func (f *Filter) Match(fl *flow.Flow, bodies Bodies) bool {
	return f.root.eval(&ctx{f: fl, b: bodies, now: time.Now()})
}

type ctx struct {
	f    *flow.Flow
	b    Bodies
	now  time.Time
	body map[string][]byte
}

func (c *ctx) bodyOf(side string) []byte {
	if c.b == nil {
		return nil
	}
	if c.body == nil {
		c.body = map[string][]byte{}
	}
	if b, ok := c.body[side]; ok {
		return b
	}
	b := c.b.Body(c.f, side)
	c.body[side] = b
	return b
}

type node interface{ eval(*ctx) bool }

type all struct{}

func (all) eval(*ctx) bool { return true }

type and []node

func (a and) eval(c *ctx) bool {
	for _, n := range a {
		if !n.eval(c) {
			return false
		}
	}
	return true
}

type or []node

func (o or) eval(c *ctx) bool {
	for _, n := range o {
		if n.eval(c) {
			return true
		}
	}
	return false
}

type not struct{ n node }

func (n not) eval(c *ctx) bool { return !n.n.eval(c) }

type pred struct {
	fn  func(*ctx) bool
	sql string
	arg []any
}

func (p *pred) eval(c *ctx) bool { return p.fn(c) }

// ---- lexer ----

type tokKind int

const (
	tTerm tokKind = iota
	tOpen
	tClose
	tOr
	tNot
)

type token struct {
	kind  tokKind
	text  string // whole term text
	field string
	op    string // ":", ">", "<", ">=", "<=", "=", "!=" or "" (bare word)
	value string
	quote bool // value was "quoted"
}

func lex(s string) ([]token, error) {
	var toks []token
	i := 0
	for i < len(s) {
		c := s[i]
		switch {
		case c == ' ' || c == '\t' || c == '\n' || c == '\r':
			i++
		case c == '(':
			toks = append(toks, token{kind: tOpen, text: "("})
			i++
		case c == ')':
			toks = append(toks, token{kind: tClose, text: ")"})
			i++
		case c == '|':
			toks = append(toks, token{kind: tOr, text: "|"})
			i++
		case c == '-' && i+1 < len(s) && s[i+1] != ' ' && (i == 0 || s[i-1] == ' ' || s[i-1] == '('):
			toks = append(toks, token{kind: tNot, text: "-"})
			i++
		default:
			t, n, err := lexTerm(s[i:])
			if err != nil {
				return nil, err
			}
			if t.op == "" && !t.quote && (t.value == "OR" || t.value == "or") {
				toks = append(toks, token{kind: tOr, text: t.value})
			} else if t.op == "" && !t.quote && (t.value == "AND" || t.value == "and") {
				// AND is the default
			} else {
				toks = append(toks, t)
			}
			i += n
		}
	}
	return toks, nil
}

func lexTerm(s string) (token, int, error) {
	// field part: letters, digits, '.', '_', '-' up to an operator
	j := 0
	for j < len(s) && (unicode.IsLetter(rune(s[j])) || unicode.IsDigit(rune(s[j])) || s[j] == '.' || s[j] == '_' || s[j] == '-') {
		j++
	}
	field := strings.ToLower(s[:j])
	op := ""
	if j > 0 && j < len(s) && s[j] == ':' && strings.HasPrefix(s[j+1:], "//") {
		field = "" // http://… or https://… is a URL, not a field
	} else if j > 0 && j < len(s) && s[j] == ':' && !isKnownField(field) && fieldLike.MatchString(field) {
		return token{}, 0, fmt.Errorf("unknown field %q (fields: %s; a bare word searches the URL)", field, strings.Join(Fields, ", "))
	}
	if field != "" && j > 0 && j < len(s) && isKnownField(field) {
		for _, o := range []string{">=", "<=", "!=", ":", ">", "<", "="} {
			if strings.HasPrefix(s[j:], o) {
				op = o
				break
			}
		}
	}
	if op == "" {
		v, n, q, err := lexValue(s)
		return token{kind: tTerm, text: s[:n], value: v, quote: q}, n, err
	}
	v, n, q, err := lexValue(s[j+len(op):])
	if err != nil {
		return token{}, 0, err
	}
	total := j + len(op) + n
	return token{kind: tTerm, text: s[:total], field: field, op: op, value: v, quote: q}, total, nil
}

// lexValue reads "quoted", ~"quoted regex" or a plain word up to a space or an unmatched ')'.
func lexValue(s string) (string, int, bool, error) {
	if s == "" {
		return "", 0, false, nil
	}
	prefix := ""
	body := s
	if strings.HasPrefix(s, "~\"") {
		prefix, body = "~", s[1:]
	}
	if body[0] == '"' {
		var b strings.Builder
		for i := 1; i < len(body); i++ {
			if body[i] == '\\' && i+1 < len(body) {
				if prefix == "~" && body[i+1] != '"' { // keep regex escapes such as \d
					b.WriteByte('\\')
				}
				b.WriteByte(body[i+1])
				i++
				continue
			}
			if body[i] == '"' {
				return prefix + b.String(), len(prefix) + i + 1, true, nil
			}
			b.WriteByte(body[i])
		}
		return "", 0, false, fmt.Errorf("missing closing quote in %s", s)
	}
	i := 0
	depth := 0
	for i < len(s) && s[i] != ' ' && s[i] != '\t' && s[i] != '\n' {
		if s[i] == '(' {
			depth++
		} else if s[i] == ')' {
			if depth == 0 {
				break
			}
			depth--
		}
		i++
	}
	return s[:i], i, false, nil
}

var fieldLike = regexp.MustCompile(`^[a-z][a-z_]*(\.[a-z_]+)*$`)

func isKnownField(f string) bool {
	switch f {
	case "app", "host", "method", "path", "url", "status", "type", "req.type", "res.type", "kind", "http", "tls",
		"size", "req.size", "res.size", "time", "since", "until", "after", "before", "id", "tag", "note", "error",
		"header", "body", "req.body", "res.body", "req.json", "res.json", "noise", "flag", "js", "uid", "port", "scheme",
		"pinned", "color":
		return true
	}
	return strings.HasPrefix(f, "req.header.") || strings.HasPrefix(f, "res.header.") || strings.HasPrefix(f, "header.")
}

// ---- parser ----

type parser struct {
	toks []token
	pos  int
	f    *Filter
}

func (p *parser) peek() *token {
	if p.pos < len(p.toks) {
		return &p.toks[p.pos]
	}
	return nil
}

func (p *parser) or() (node, error) {
	first, err := p.and()
	if err != nil {
		return nil, err
	}
	nodes := []node{first}
	for t := p.peek(); t != nil && t.kind == tOr; t = p.peek() {
		p.pos++
		n, err := p.and()
		if err != nil {
			return nil, err
		}
		nodes = append(nodes, n)
	}
	if len(nodes) == 1 {
		return first, nil
	}
	return or(nodes), nil
}

func (p *parser) and() (node, error) {
	var nodes []node
	for t := p.peek(); t != nil && t.kind != tOr && t.kind != tClose; t = p.peek() {
		n, err := p.unary()
		if err != nil {
			return nil, err
		}
		nodes = append(nodes, n)
	}
	switch len(nodes) {
	case 0:
		return nil, fmt.Errorf("empty expression in %q", p.f.src)
	case 1:
		return nodes[0], nil
	}
	return and(nodes), nil
}

func (p *parser) unary() (node, error) {
	t := p.peek()
	switch t.kind {
	case tNot:
		p.pos++
		if p.peek() == nil {
			return nil, fmt.Errorf("nothing after '-'")
		}
		n, err := p.unary()
		if err != nil {
			return nil, err
		}
		return not{n}, nil
	case tOpen:
		p.pos++
		n, err := p.or()
		if err != nil {
			return nil, err
		}
		if c := p.peek(); c == nil || c.kind != tClose {
			return nil, fmt.Errorf("missing ')'")
		}
		p.pos++
		return n, nil
	case tTerm:
		p.pos++
		return p.term(t)
	}
	return nil, fmt.Errorf("unexpected %q", t.text)
}

// pushdown turns the plain terms of a top-level AND into SQL.
func (f *Filter) pushdown() {
	var terms []node
	switch r := f.root.(type) {
	case and:
		terms = r
	default:
		terms = []node{r}
	}
	for _, t := range terms {
		if p, ok := t.(*pred); ok && p.sql != "" {
			f.sqlParts = append(f.sqlParts, p.sql)
			f.sqlArgs = append(f.sqlArgs, p.arg...)
		}
	}
}

// ---- terms ----

func (p *parser) term(t *token) (node, error) {
	f := p.f
	if t.op == "" { // bare word: URL contains
		v := t.value
		f.mentions["url"] = true
		if re, ok, err := regexOf("url", v); ok {
			if err != nil {
				return nil, err
			}
			return &pred{fn: func(c *ctx) bool { return re.MatchString(c.f.URL()) }}, nil
		}
		lv := strings.ToLower(v)
		return &pred{fn: func(c *ctx) bool { return strings.Contains(strings.ToLower(c.f.URL()), lv) }}, nil
	}
	field, op, v := t.field, t.op, t.value
	f.mentions[field] = true
	switch {
	case strings.HasPrefix(field, "req.header.") || strings.HasPrefix(field, "res.header.") || strings.HasPrefix(field, "header."):
		f.mentions["header"] = true
		return headerTerm(field, op, v)
	}
	switch field {
	case "app":
		return globTerm(op, v, "app", "app", func(fl *flow.Flow) string { return fl.App })
	case "host":
		return globTerm(op, v, "host", "host", func(fl *flow.Flow) string { return fl.Host })
	case "path":
		return globTerm(op, v, "path", "path", func(fl *flow.Flow) string { return fl.Path })
	case "scheme":
		return eqTerm(op, v, "scheme", func(fl *flow.Flow) string { return fl.Scheme })
	case "method":
		return eqTerm(op, v, "method", func(fl *flow.Flow) string { return fl.Method })
	case "kind":
		return eqTerm(op, v, "kind", func(fl *flow.Flow) string { return string(fl.Kind) })
	case "type", "res.type":
		f.mentions["type"] = true
		return eqTerm(op, v, "res_type", func(fl *flow.Flow) string { return fl.Res.Type })
	case "req.type":
		return eqTerm(op, v, "req_type", func(fl *flow.Flow) string { return fl.Req.Type })
	case "tls":
		return eqTerm(op, v, "tls", func(fl *flow.Flow) string { return fl.TLS })
	case "http":
		v = strings.TrimPrefix(strings.ToLower(v), "h")
		if v == "1" {
			v = "1.1"
		}
		return eqTerm(op, v, "http", func(fl *flow.Flow) string { return fl.HTTPVersion })
	case "url":
		if re, ok, err := regexOf("url", v); ok {
			if err != nil {
				return nil, err
			}
			return &pred{fn: func(c *ctx) bool { return re.MatchString(c.f.URL()) }}, nil
		}
		lv := strings.ToLower(v)
		return &pred{fn: func(c *ctx) bool { return strings.Contains(strings.ToLower(c.f.URL()), lv) }}, nil
	case "status":
		return statusTerm(op, v)
	case "size", "res.size":
		return numTerm(op, v, "res_size", parseSize, func(fl *flow.Flow) float64 { return float64(fl.Res.Size) })
	case "req.size":
		return numTerm(op, v, "req_size", parseSize, func(fl *flow.Flow) float64 { return float64(fl.Req.Size) })
	case "time":
		return numTerm(op, v, "duration_ms", parseMillis, func(fl *flow.Flow) float64 { return fl.Timings.Total })
	case "id", "after", "before":
		id, err := parseID(v)
		if err != nil {
			return nil, err
		}
		switch field {
		case "after":
			op = ">"
		case "before":
			op = "<"
		}
		return numTerm(op, strconv.FormatInt(id, 10), "id", func(s string) (float64, error) { return strconv.ParseFloat(s, 64) },
			func(fl *flow.Flow) float64 { return float64(fl.ID) })
	case "uid":
		return numTerm(op, v, "uid", func(s string) (float64, error) { return strconv.ParseFloat(s, 64) },
			func(fl *flow.Flow) float64 { return float64(fl.UID) })
	case "port":
		return numTerm(op, v, "port", func(s string) (float64, error) { return strconv.ParseFloat(s, 64) },
			func(fl *flow.Flow) float64 { return float64(fl.Port) })
	case "since", "until":
		at, err := parseTime(v, time.Now())
		if err != nil {
			return nil, err
		}
		us := at.UnixMicro()
		if field == "since" {
			return &pred{fn: func(c *ctx) bool { return !c.f.Start.Before(at) }, sql: "start_us >= ?", arg: []any{us}}, nil
		}
		return &pred{fn: func(c *ctx) bool { return c.f.Start.Before(at) }, sql: "start_us < ?", arg: []any{us}}, nil
	case "tag":
		lv := strings.ToLower(v)
		return &pred{fn: func(c *ctx) bool { return c.f.HasTag(lv) }, sql: "tags LIKE ?", arg: []any{"%," + escapeLike(lv) + ",%"}}, nil
	case "flag":
		lv := strings.ToLower(v)
		return &pred{fn: func(c *ctx) bool {
			for _, x := range c.f.Flags {
				if strings.EqualFold(x, lv) || strings.HasPrefix(strings.ToLower(x), lv+":") {
					return true
				}
			}
			return false
		}}, nil
	case "note":
		lv := strings.ToLower(v)
		return &pred{fn: func(c *ctx) bool { return strings.Contains(strings.ToLower(c.f.Note), lv) }}, nil
	case "error":
		lv := strings.ToLower(v)
		if lv == "any" || lv == "true" || lv == "yes" {
			return &pred{fn: func(c *ctx) bool { return c.f.Error != "" }, sql: "error != ''"}, nil
		}
		if lv == "none" || lv == "false" || lv == "no" {
			return &pred{fn: func(c *ctx) bool { return c.f.Error == "" }, sql: "error = ''"}, nil
		}
		return &pred{fn: func(c *ctx) bool { return strings.Contains(strings.ToLower(c.f.Error), lv) }}, nil
	case "noise":
		want := !(strings.EqualFold(v, "false") || v == "0" || strings.EqualFold(v, "no"))
		return &pred{fn: func(c *ctx) bool { return IsNoise(c.f) == want }}, nil
	case "pinned":
		want := !(strings.EqualFold(v, "false") || v == "0" || strings.EqualFold(v, "no"))
		return &pred{fn: func(c *ctx) bool { return c.f.Pinned == want }}, nil
	case "color":
		lv := strings.ToLower(strings.TrimSpace(v))
		switch lv {
		case "any", "true", "yes":
			return &pred{fn: func(c *ctx) bool { return c.f.Color != "" }}, nil
		case "none", "false", "no":
			return &pred{fn: func(c *ctx) bool { return c.f.Color == "" }}, nil
		}
		alts := strings.Split(lv, ",")
		n := node(&pred{fn: func(c *ctx) bool {
			for _, a := range alts {
				if strings.EqualFold(c.f.Color, strings.TrimSpace(a)) {
					return true
				}
			}
			return false
		}})
		if op == "!=" {
			n = not{n}
		}
		return n, nil
	case "body", "req.body", "res.body":
		return bodyTerm(field, v)
	case "req.json", "res.json":
		q, err := gojq.Parse(v)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", field, err)
		}
		code, err := gojq.Compile(q)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", field, err)
		}
		side := strings.TrimSuffix(field, ".json")
		return &pred{fn: func(c *ctx) bool {
			b := c.bodyOf(side)
			if len(b) == 0 {
				return false
			}
			return jqTruthy(b, code)
		}}, nil
	case "js":
		if JSCompiler == nil {
			return nil, fmt.Errorf("js: filters need the script engine")
		}
		fn, err := JSCompiler(v)
		if err != nil {
			return nil, err
		}
		if strings.Contains(v, "text(") || strings.Contains(v, "json(") || strings.Contains(v, "bytes(") {
			p.f.mentions["js.body"] = true
		}
		return &pred{fn: func(c *ctx) bool { return fn(c.f, c.b) }}, nil
	}
	return nil, fmt.Errorf("unknown field %q (fields: %s)", field, strings.Join(Fields, ", "))
}

// IsNoise reports whether a flow is static noise (images, fonts, media, CSS) that lists hide unless asked.
func IsNoise(f *flow.Flow) bool {
	switch f.Res.Type {
	case "image", "font", "media", "css":
		return true
	}
	return false
}

// regexOf compiles v when it asks for a regular expression: ~expr always, or /expr/ when expr holds regex
// characters and the field is not one whose values start with a slash anyway (path, host, app).
func regexOf(field, v string) (*regexp.Regexp, bool, error) {
	var src string
	switch {
	case strings.HasPrefix(v, "~") && len(v) > 1:
		src = v[1:]
	case field != "path" && field != "host" && field != "app" && len(v) >= 3 && v[0] == '/' && v[len(v)-1] == '/' &&
		strings.ContainsAny(v[1:len(v)-1], `\.*+?[](){}|^$`):
		src = v[1 : len(v)-1]
	default:
		return nil, false, nil
	}
	re, err := regexp.Compile(src)
	if err != nil {
		return nil, true, fmt.Errorf("bad regex %s: %w", v, err)
	}
	return re, true, nil
}

func escapeLike(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return r.Replace(s)
}

// globTerm: `*` and `?` wildcards, case-insensitive; without wildcards an exact match. Comma = alternatives.
func globTerm(op, v, field, col string, get func(*flow.Flow) string) (node, error) {
	if op != ":" && op != "=" && op != "!=" {
		return nil, fmt.Errorf("%s takes ':' (got %q)", col, op)
	}
	if re, ok, err := regexOf(field, v); ok {
		if err != nil {
			return nil, err
		}
		n := node(&pred{fn: func(c *ctx) bool { return re.MatchString(get(c.f)) }})
		if op == "!=" {
			n = not{n}
		}
		return n, nil
	}
	alts := strings.Split(v, ",")
	var res []*regexp.Regexp
	var likes []string
	for _, a := range alts {
		a = strings.ToLower(strings.TrimSpace(a))
		if a == "" {
			continue
		}
		res = append(res, regexp.MustCompile("^"+globToRegex(a)+"$"))
		likes = append(likes, globToLike(a))
	}
	if len(res) == 0 {
		return nil, fmt.Errorf("%s: empty value", col)
	}
	sqlParts := make([]string, len(likes))
	args := make([]any, len(likes))
	for i, l := range likes {
		sqlParts[i] = "lower(" + col + ") LIKE ? ESCAPE '\\'"
		args[i] = l
	}
	sql := "(" + strings.Join(sqlParts, " OR ") + ")"
	p := &pred{fn: func(c *ctx) bool {
		s := strings.ToLower(get(c.f))
		for _, re := range res {
			if re.MatchString(s) {
				return true
			}
		}
		return false
	}, sql: sql, arg: args}
	if op == "!=" {
		return not{p}, nil
	}
	return p, nil
}

func globToRegex(g string) string {
	var b strings.Builder
	for _, r := range g {
		switch r {
		case '*':
			b.WriteString(".*")
		case '?':
			b.WriteString(".")
		default:
			b.WriteString(regexp.QuoteMeta(string(r)))
		}
	}
	return b.String()
}

func globToLike(g string) string {
	var b strings.Builder
	for _, r := range g {
		switch r {
		case '*':
			b.WriteByte('%')
		case '?':
			b.WriteByte('_')
		case '%', '_', '\\':
			b.WriteByte('\\')
			b.WriteRune(r)
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// eqTerm: case-insensitive equality; comma = alternatives.
func eqTerm(op, v, col string, get func(*flow.Flow) string) (node, error) {
	if op != ":" && op != "=" && op != "!=" {
		return nil, fmt.Errorf("%s takes ':' (got %q)", col, op)
	}
	var alts []string
	for _, a := range strings.Split(v, ",") {
		if a = strings.ToLower(strings.TrimSpace(a)); a != "" {
			alts = append(alts, a)
		}
	}
	if len(alts) == 0 {
		return nil, fmt.Errorf("%s: empty value", col)
	}
	ph := strings.TrimSuffix(strings.Repeat("?,", len(alts)), ",")
	args := make([]any, len(alts))
	for i, a := range alts {
		args[i] = a
	}
	p := &pred{fn: func(c *ctx) bool {
		s := strings.ToLower(get(c.f))
		for _, a := range alts {
			if s == a {
				return true
			}
		}
		return false
	}, sql: "lower(" + col + ") IN (" + ph + ")", arg: args}
	if op == "!=" {
		return not{p}, nil
	}
	return p, nil
}

// statusTerm: 404, 4xx, 200-299, >=400.
func statusTerm(op, v string) (node, error) {
	for _, o := range []string{">=", "<=", ">", "<"} { // status:>=400 means status>=400
		if strings.HasPrefix(v, o) {
			op, v = o, v[len(o):]
			break
		}
	}
	lo, hi := 0, 0
	lv := strings.ToLower(v)
	switch {
	case len(lv) == 3 && strings.HasSuffix(lv, "xx") && lv[0] >= '1' && lv[0] <= '5':
		d := int(lv[0]-'0') * 100
		lo, hi = d, d+99
	case strings.Contains(lv, "-"):
		a, b, _ := strings.Cut(lv, "-")
		x, err1 := strconv.Atoi(a)
		y, err2 := strconv.Atoi(b)
		if err1 != nil || err2 != nil {
			return nil, fmt.Errorf("bad status range %q", v)
		}
		lo, hi = x, y
	default:
		n, err := strconv.Atoi(lv)
		if err != nil {
			return nil, fmt.Errorf("bad status %q (use 404, 4xx or 200-299)", v)
		}
		switch op {
		case ">":
			lo, hi = n+1, 999
		case ">=":
			lo, hi = n, 999
		case "<":
			lo, hi = 1, n-1
		case "<=":
			lo, hi = 1, n
		default:
			lo, hi = n, n
		}
	}
	p := &pred{fn: func(c *ctx) bool { return c.f.Status >= lo && c.f.Status <= hi },
		sql: "status BETWEEN ? AND ?", arg: []any{lo, hi}}
	if op == "!=" {
		return not{p}, nil
	}
	return p, nil
}

func numTerm(op, v, col string, parse func(string) (float64, error), get func(*flow.Flow) float64) (node, error) {
	n, err := parse(v)
	if err != nil {
		return nil, err
	}
	var fn func(float64) bool
	var sqlOp string
	switch op {
	case ">":
		fn, sqlOp = func(x float64) bool { return x > n }, ">"
	case ">=":
		fn, sqlOp = func(x float64) bool { return x >= n }, ">="
	case "<":
		fn, sqlOp = func(x float64) bool { return x < n }, "<"
	case "<=":
		fn, sqlOp = func(x float64) bool { return x <= n }, "<="
	case "!=":
		fn, sqlOp = func(x float64) bool { return x != n }, "!="
	default:
		fn, sqlOp = func(x float64) bool { return x == n }, "="
	}
	return &pred{fn: func(c *ctx) bool { return fn(get(c.f)) }, sql: col + " " + sqlOp + " ?", arg: []any{n}}, nil
}

func parseSize(s string) (float64, error) {
	ls := strings.ToLower(strings.TrimSpace(s))
	mult := 1.0
	switch {
	case strings.HasSuffix(ls, "kb"), strings.HasSuffix(ls, "k"):
		mult = 1024
		ls = strings.TrimRight(ls, "kb")
	case strings.HasSuffix(ls, "mb"), strings.HasSuffix(ls, "m"):
		mult = 1024 * 1024
		ls = strings.TrimRight(ls, "mb")
	case strings.HasSuffix(ls, "gb"), strings.HasSuffix(ls, "g"):
		mult = 1024 * 1024 * 1024
		ls = strings.TrimRight(ls, "gb")
	case strings.HasSuffix(ls, "b"):
		ls = strings.TrimSuffix(ls, "b")
	}
	n, err := strconv.ParseFloat(ls, 64)
	if err != nil {
		return 0, fmt.Errorf("bad size %q (use 512, 10k, 2m)", s)
	}
	return n * mult, nil
}

func parseMillis(s string) (float64, error) {
	ls := strings.ToLower(strings.TrimSpace(s))
	if n, err := strconv.ParseFloat(ls, 64); err == nil { // a plain number is milliseconds
		return n, nil
	}
	if d, err := time.ParseDuration(ls); err == nil {
		return float64(d) / float64(time.Millisecond), nil
	}
	return 0, fmt.Errorf("bad duration %q (use 500ms, 2s)", s)
}

func parseID(s string) (int64, error) {
	s = strings.TrimPrefix(strings.ToLower(strings.TrimSpace(s)), "f")
	if i := strings.IndexByte(s, '.'); i >= 0 { // f12.m3 → 12
		s = s[:i]
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("bad flow id %q (use f123)", s)
	}
	return n, nil
}

// parseTime: "10m" (ago), "2h", "1d", "12:30" (today), RFC 3339.
func parseTime(s string, now time.Time) (time.Time, error) {
	ls := strings.ToLower(strings.TrimSpace(s))
	if strings.HasSuffix(ls, "d") {
		if n, err := strconv.Atoi(strings.TrimSuffix(ls, "d")); err == nil {
			return now.Add(-time.Duration(n) * 24 * time.Hour), nil
		}
	}
	if d, err := time.ParseDuration(ls); err == nil {
		return now.Add(-d), nil
	}
	for _, layout := range []string{"15:04", "15:04:05"} {
		if t, err := time.ParseInLocation(layout, ls, now.Location()); err == nil {
			return time.Date(now.Year(), now.Month(), now.Day(), t.Hour(), t.Minute(), t.Second(), 0, now.Location()), nil
		}
	}
	for _, layout := range []string{time.RFC3339, "2006-01-02T15:04", "2006-01-02"} {
		if t, err := time.ParseInLocation(layout, s, now.Location()); err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("bad time %q (use 10m, 2h, 1d, 12:30 or 2026-11-28T12:30)", s)
}

func headerTerm(field, op, v string) (node, error) {
	var sides []string
	var name string
	switch {
	case strings.HasPrefix(field, "req.header."):
		sides, name = []string{"req"}, strings.TrimPrefix(field, "req.header.")
	case strings.HasPrefix(field, "res.header."):
		sides, name = []string{"res"}, strings.TrimPrefix(field, "res.header.")
	default:
		sides, name = []string{"req", "res"}, strings.TrimPrefix(field, "header.")
	}
	get := func(f *flow.Flow, side string) flow.Headers {
		if side == "req" {
			return f.ReqHeaders
		}
		return f.ResHeaders
	}
	lv := strings.ToLower(v)
	var match func(string) bool
	switch {
	case lv == "exists" || lv == "*" || lv == "":
		match = func(string) bool { return true }
	case lv == "missing":
		return &pred{fn: func(c *ctx) bool {
			for _, s := range sides {
				if get(c.f, s).Has(name) {
					return false
				}
			}
			return true
		}}, nil
	case strings.HasPrefix(v, "~") || (len(v) >= 3 && v[0] == '/' && v[len(v)-1] == '/'):
		re, ok, err := regexOf("header", v)
		if err != nil {
			return nil, err
		}
		if !ok {
			match = func(s string) bool { return strings.Contains(strings.ToLower(s), lv) }
			break
		}
		match = re.MatchString
	case strings.ContainsAny(lv, "*?"):
		re := regexp.MustCompile("^" + globToRegex(lv) + "$")
		match = func(s string) bool { return re.MatchString(strings.ToLower(s)) }
	default:
		match = func(s string) bool { return strings.Contains(strings.ToLower(s), lv) }
	}
	n := node(&pred{fn: func(c *ctx) bool {
		for _, s := range sides {
			for _, kv := range get(c.f, s) {
				if strings.EqualFold(kv[0], name) && match(kv[1]) {
					return true
				}
			}
		}
		return false
	}})
	if op == "!=" {
		n = not{n}
	}
	return n, nil
}

func bodyTerm(field, v string) (node, error) {
	sides := []string{"req", "res"}
	if field == "req.body" {
		sides = []string{"req"}
	} else if field == "res.body" {
		sides = []string{"res"}
	}
	var match func([]byte) bool
	if re, ok, err := regexOf("body", v); ok {
		if err != nil {
			return nil, err
		}
		match = re.Match
	} else {
		re := regexp.MustCompile("(?i)" + regexp.QuoteMeta(v))
		match = re.Match
	}
	return &pred{fn: func(c *ctx) bool {
		for _, s := range sides {
			if b := c.bodyOf(s); len(b) > 0 && match(b) {
				return true
			}
		}
		return false
	}}, nil
}
