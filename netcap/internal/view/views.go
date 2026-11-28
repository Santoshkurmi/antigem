package view

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/itchyny/gojq"
)

// Pretty formats JSON (2-space indent) and form bodies; other text is returned as is.
func Pretty(b []byte, typ string) string {
	switch typ {
	case "json":
		var out bytes.Buffer
		if json.Indent(&out, b, "", "  ") == nil {
			return out.String()
		}
		// NDJSON or broken JSON: indent line by line where possible
		var lines []string
		for _, l := range bytes.Split(b, []byte("\n")) {
			var o bytes.Buffer
			if json.Indent(&o, l, "", "  ") == nil {
				lines = append(lines, o.String())
			} else {
				lines = append(lines, string(l))
			}
		}
		return strings.Join(lines, "\n")
	case "form":
		if vals, err := url.ParseQuery(string(b)); err == nil && !bytes.Contains(b, []byte("\n")) {
			keys := make([]string, 0, len(vals))
			for k := range vals {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			var sb strings.Builder
			for _, k := range keys {
				for _, v := range vals[k] {
					fmt.Fprintf(&sb, "%s = %s\n", k, v)
				}
			}
			return sb.String()
		}
	}
	return ToText(b)
}

// ToText returns b as valid UTF-8 (bad bytes replaced).
func ToText(b []byte) string {
	if utf8.Valid(b) {
		return string(b)
	}
	return strings.ToValidUTF8(string(b), "�")
}

// Outline describes the structure of a JSON (or form) body in few lines: keys, types, array sizes, short samples.
func Outline(b []byte, typ string, maxLines int) string {
	if maxLines <= 0 {
		maxLines = 60
	}
	switch typ {
	case "json":
		var v any
		dec := json.NewDecoder(bytes.NewReader(b))
		dec.UseNumber()
		if err := dec.Decode(&v); err != nil {
			return "(not valid JSON: " + err.Error() + ")\n" + firstLines(ToText(b), 5)
		}
		o := &outliner{max: maxLines}
		o.value("", v, 0)
		if dec.More() {
			o.line(0, "… more JSON values follow (NDJSON)")
		}
		if o.cut {
			o.lines = append(o.lines, fmt.Sprintf("… (outline cut at %d lines)", maxLines))
		}
		return strings.Join(o.lines, "\n")
	case "form":
		return Pretty(b, "form")
	}
	return firstLines(ToText(b), 10)
}

type outliner struct {
	lines []string
	max   int
	cut   bool
}

func (o *outliner) line(depth int, s string) {
	if len(o.lines) >= o.max {
		o.cut = true
		return
	}
	o.lines = append(o.lines, strings.Repeat("  ", depth)+s)
}

func (o *outliner) value(key string, v any, depth int) {
	prefix := ""
	if key != "" {
		prefix = key + ": "
	}
	switch x := v.(type) {
	case map[string]any:
		if len(x) == 0 {
			o.line(depth, prefix+"{}")
			return
		}
		if depth >= 6 {
			o.line(depth, prefix+fmt.Sprintf("{…%d keys}", len(x)))
			return
		}
		if s := inlineObject(x); s != "" && len(prefix)+len(s) < 100 {
			o.line(depth, prefix+s)
			return
		}
		o.line(depth, prefix+"{")
		keys := sortedKeys(x)
		for i, k := range keys {
			if i >= 40 {
				o.line(depth+1, fmt.Sprintf("… %d more keys", len(keys)-i))
				break
			}
			o.value(k, x[k], depth+1)
		}
		o.line(depth, "}")
	case []any:
		if len(x) == 0 {
			o.line(depth, prefix+"[]")
			return
		}
		// merge the keys of the first elements, so optional fields show up
		if first, ok := x[0].(map[string]any); ok {
			merged := map[string]any{}
			for i := 0; i < len(x) && i < 5; i++ {
				if m, ok := x[i].(map[string]any); ok {
					for k, val := range m {
						if _, seen := merged[k]; !seen {
							merged[k] = val
						}
					}
				}
			}
			_ = first
			if s := inlineObject(merged); s != "" && len(prefix)+len(s) < 100 {
				o.line(depth, prefix+fmt.Sprintf("[%d] of %s", len(x), s))
				return
			}
			o.line(depth, prefix+fmt.Sprintf("[%d] of {", len(x)))
			keys := sortedKeys(merged)
			for i, k := range keys {
				if i >= 40 {
					o.line(depth+1, fmt.Sprintf("… %d more keys", len(keys)-i))
					break
				}
				o.value(k, merged[k], depth+1)
			}
			o.line(depth, "}")
			return
		}
		o.line(depth, prefix+fmt.Sprintf("[%d] of %s", len(x), scalar(x[0])))
	default:
		o.line(depth, prefix+scalar(x))
	}
}

func inlineObject(m map[string]any) string {
	if len(m) > 6 {
		return ""
	}
	var parts []string
	for _, k := range sortedKeys(m) {
		switch v := m[k].(type) {
		case map[string]any:
			if len(v) > 0 {
				return ""
			}
			parts = append(parts, k+": {}")
		case []any:
			if len(v) > 0 {
				if _, ok := v[0].(map[string]any); ok {
					return ""
				}
				parts = append(parts, fmt.Sprintf("%s: [%d] of %s", k, len(v), typeName(v[0])))
			} else {
				parts = append(parts, k+": []")
			}
		default:
			parts = append(parts, k+": "+typeName(v))
		}
	}
	return "{" + strings.Join(parts, ", ") + "}"
}

func typeName(v any) string {
	switch v.(type) {
	case nil:
		return "null"
	case bool:
		return "bool"
	case json.Number, float64:
		return "number"
	case string:
		return "string"
	case []any:
		return "array"
	case map[string]any:
		return "object"
	}
	return fmt.Sprintf("%T", v)
}

func scalar(v any) string {
	switch x := v.(type) {
	case nil:
		return "null"
	case bool:
		return "bool " + strconv.FormatBool(x)
	case json.Number:
		return "number " + x.String()
	case float64:
		return "number " + strconv.FormatFloat(x, 'g', -1, 64)
	case string:
		s := x
		if utf8.RuneCountInString(s) > 32 {
			r := []rune(s)
			s = string(r[:32]) + "…"
		}
		return fmt.Sprintf("string %q", s)
	case map[string]any:
		return fmt.Sprintf("{…%d keys}", len(x))
	case []any:
		return fmt.Sprintf("[%d]", len(x))
	}
	return fmt.Sprint(v)
}

func sortedKeys(m map[string]any) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func firstLines(s string, n int) string {
	lines := strings.SplitN(s, "\n", n+1)
	if len(lines) > n {
		return strings.Join(lines[:n], "\n") + "\n…"
	}
	return s
}

// JQ runs a jq expression on a JSON body and returns each result as compact JSON on its own line.
func JQ(b []byte, expr string, maxResults int) (string, error) {
	q, err := gojq.Parse(expr)
	if err != nil {
		return "", fmt.Errorf("jq: %w", err)
	}
	code, err := gojq.Compile(q)
	if err != nil {
		return "", fmt.Errorf("jq: %w", err)
	}
	var v any
	if err := json.Unmarshal(b, &v); err != nil {
		return "", fmt.Errorf("body is not JSON: %w", err)
	}
	if maxResults <= 0 {
		maxResults = 200
	}
	var out []string
	iter := code.Run(v)
	for i := 0; ; i++ {
		r, ok := iter.Next()
		if !ok {
			break
		}
		if e, isErr := r.(error); isErr {
			return strings.Join(out, "\n"), fmt.Errorf("jq: %w", e)
		}
		if i >= maxResults {
			out = append(out, fmt.Sprintf("… more results (cut at %d)", maxResults))
			break
		}
		j, _ := json.Marshal(r)
		out = append(out, string(j))
	}
	return strings.Join(out, "\n"), nil
}

// JQTruthy runs expr and reports whether its first result is neither false nor null (for filters).
func JQTruthy(v any, code *gojq.Code) bool {
	iter := code.Run(v)
	r, ok := iter.Next()
	if !ok {
		return false
	}
	switch x := r.(type) {
	case error:
		return false
	case nil:
		return false
	case bool:
		return x
	}
	return true
}

// Grep returns the lines matching pattern (a /regex/ or plain text, case-insensitive) with ctx lines around them.
func Grep(text, pattern string, ctx, maxMatches int) (string, int, error) {
	re, err := Pattern(pattern)
	if err != nil {
		return "", 0, err
	}
	if maxMatches <= 0 {
		maxMatches = 30
	}
	lines := strings.Split(text, "\n")
	if len(lines) == 1 && len(text) > 400 { // one long line (minified JSON): show windows around each match
		var out []string
		locs := re.FindAllStringIndex(text, maxMatches+1)
		for i, l := range locs {
			if i >= maxMatches {
				out = append(out, "… more matches")
				break
			}
			from, to := max(0, l[0]-120), min(len(text), l[1]+120)
			out = append(out, fmt.Sprintf("@%d: …%s…", l[0], text[from:to]))
		}
		return strings.Join(out, "\n"), len(locs), nil
	}
	var out []string
	matches := 0
	last := -1
	for i, l := range lines {
		if !re.MatchString(l) {
			continue
		}
		matches++
		if matches > maxMatches {
			out = append(out, "… more matches")
			break
		}
		from, to := max(0, i-ctx), min(len(lines)-1, i+ctx)
		if last >= 0 && from > last+1 {
			out = append(out, "--")
		}
		for j := max(from, last+1); j <= to; j++ {
			mark := " "
			if j == i {
				mark = ">"
			}
			l := lines[j]
			if len(l) > 300 {
				l = l[:300] + "…"
			}
			out = append(out, fmt.Sprintf("%s%5d: %s", mark, j+1, l))
		}
		last = to
	}
	return strings.Join(out, "\n"), matches, nil
}

// Pattern compiles "/regex/" as a regular expression and anything else as case-insensitive plain text.
func Pattern(p string) (*regexp.Regexp, error) {
	if len(p) >= 2 && strings.HasPrefix(p, "/") && strings.HasSuffix(p, "/") {
		return regexp.Compile(p[1 : len(p)-1])
	}
	return regexp.Compile("(?i)" + regexp.QuoteMeta(p))
}

// Hex dumps b[from:from+n] like hexdump -C.
func Hex(b []byte, from, n int) string {
	if from < 0 {
		from = 0
	}
	if from > len(b) {
		from = len(b)
	}
	if n <= 0 || from+n > len(b) {
		n = len(b) - from
	}
	var sb strings.Builder
	for off := from; off < from+n; off += 16 {
		end := min(off+16, from+n)
		fmt.Fprintf(&sb, "%08x  ", off)
		for i := off; i < off+16; i++ {
			if i < end {
				fmt.Fprintf(&sb, "%02x ", b[i])
			} else {
				sb.WriteString("   ")
			}
			if i == off+7 {
				sb.WriteByte(' ')
			}
		}
		sb.WriteString(" |")
		for i := off; i < end; i++ {
			c := b[i]
			if c < 0x20 || c > 0x7e {
				c = '.'
			}
			sb.WriteByte(c)
		}
		sb.WriteString("|\n")
	}
	return sb.String()
}
