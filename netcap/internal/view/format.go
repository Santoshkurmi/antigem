package view

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"compress/zlib"
	"encoding/json"
	"errors"
	"io"
	"strings"

	"github.com/andybalholm/brotli"
	"github.com/klauspost/compress/zstd"
)

// Encode applies Content-Encoding (gzip, deflate, br, zstd; several = in the listed order), the opposite of Decode.
func Encode(data []byte, encoding string) ([]byte, error) {
	out := data
	for _, e := range strings.Split(strings.ToLower(encoding), ",") {
		var buf bytes.Buffer
		var w io.WriteCloser
		switch strings.TrimSpace(e) {
		case "", "identity", "none":
			continue
		case "gzip", "x-gzip":
			w = gzip.NewWriter(&buf)
		case "deflate":
			w = zlib.NewWriter(&buf)
		case "br":
			w = brotli.NewWriter(&buf)
		case "zstd":
			zw, err := zstd.NewWriter(&buf, zstd.WithEncoderConcurrency(1))
			if err != nil {
				return data, err
			}
			w = zw
		default:
			return data, errors.New("unknown content-encoding " + e)
		}
		if _, err := w.Write(out); err != nil {
			return data, err
		}
		if err := w.Close(); err != nil {
			return data, err
		}
		out = buf.Bytes()
	}
	return out, nil
}

// HasPretty reports whether PrettyTo formats a type (others are shown as they are).
func HasPretty(typ string) bool {
	switch typ {
	case "json", "xml", "html", "js", "css", "form":
		return true
	}
	return false
}

// PrettyTo writes a formatted copy of a body to w, streaming where it can (big minified JSON, JS, HTML): JSON gets
// a 2-space indent (the same output as json.Indent), XML and HTML a tag per line, JS and CSS a statement per line,
// form bodies a field per line. ok=false: the type has no pretty form (nothing was written).
func PrettyTo(w io.Writer, b []byte, typ string) (ok bool, err error) {
	bw := bufio.NewWriterSize(w, 64<<10)
	switch typ {
	case "json":
		if json.Valid(b) {
			prettyJSON(bw, b)
		} else {
			bw.WriteString(Pretty(b, "json")) // NDJSON or broken JSON: line by line
		}
	case "form":
		bw.WriteString(Pretty(b, "form"))
	case "xml", "html":
		prettyMarkup(bw, b, typ == "html")
	case "js":
		prettyCode(bw, b, true)
	case "css":
		prettyCode(bw, b, false)
	default:
		return false, nil
	}
	return true, bw.Flush()
}

func newline(w *bufio.Writer, depth int) {
	w.WriteByte('\n')
	for i := 0; i < depth; i++ {
		w.WriteString("  ")
	}
}

// prettyJSON indents valid JSON like json.Indent(dst, b, "", "  ") without holding the output in memory.
func prettyJSON(w *bufio.Writer, b []byte) {
	depth := 0
	needIndent := false // after { or [: the newline waits, so empty objects stay {} and []
	for i := 0; i < len(b); {
		c := b[i]
		if c == ' ' || c == '\t' || c == '\r' || c == '\n' {
			i++
			continue
		}
		if needIndent && c != '}' && c != ']' {
			needIndent = false
			depth++
			newline(w, depth)
		}
		switch c {
		case '"':
			j := stringEnd(b, i)
			w.Write(b[i:j])
			i = j
			continue
		case '{', '[':
			w.WriteByte(c)
			needIndent = true
		case '}', ']':
			if needIndent {
				needIndent = false
			} else {
				depth--
				newline(w, depth)
			}
			w.WriteByte(c)
		case ',':
			w.WriteByte(',')
			newline(w, depth)
		case ':':
			w.WriteString(": ")
		default: // number, true, false, null
			j := i
			for j < len(b) && !isJSONDelim(b[j]) {
				j++
			}
			w.Write(b[i:j])
			i = j
			continue
		}
		i++
	}
}

func isJSONDelim(c byte) bool {
	switch c {
	case ',', ':', '{', '}', '[', ']', '"', ' ', '\t', '\r', '\n':
		return true
	}
	return false
}

// stringEnd returns the index after the string that starts with the quote at b[i].
func stringEnd(b []byte, i int) int {
	q := b[i]
	for j := i + 1; j < len(b); j++ {
		switch b[j] {
		case '\\':
			j++
		case q:
			return j + 1
		}
	}
	return len(b)
}

var voidTags = map[string]bool{"area": true, "base": true, "br": true, "col": true, "embed": true, "hr": true, "img": true,
	"input": true, "link": true, "meta": true, "param": true, "source": true, "track": true, "wbr": true}

// prettyMarkup puts every tag of XML or HTML on its own line, indented by depth. Only whitespace between tags
// changes; <tag>short text</tag> stays on one line; script, style, pre and textarea content is kept as it is.
func prettyMarkup(w *bufio.Writer, b []byte, html bool) {
	depth := 0
	first := true
	line := func(d int) {
		if first {
			first = false
			for i := 0; i < d; i++ {
				w.WriteString("  ")
			}
			return
		}
		newline(w, d)
	}
	i := 0
	for i < len(b) {
		if b[i] != '<' {
			j := bytes.IndexByte(b[i:], '<')
			if j < 0 {
				j = len(b) - i
			}
			if t := bytes.TrimSpace(b[i : i+j]); len(t) > 0 {
				line(depth)
				w.Write(t)
			}
			i += j
			continue
		}
		end := tagEnd(b, i)
		tag := b[i:end]
		name, closing, selfClose := tagName(tag)
		switch {
		case closing:
			depth = max(0, depth-1)
			line(depth)
			w.Write(tag)
		case name == "" || selfClose || (html && voidTags[name]): // comment, doctype, <?xml?>, <br>, <x/>
			line(depth)
			w.Write(tag)
		default:
			line(depth)
			w.Write(tag)
			if html && (name == "script" || name == "style" || name == "pre" || name == "textarea") {
				// raw text up to the closing tag
				k := indexFold(b[end:], "</"+name)
				if k < 0 {
					k = len(b) - end
				}
				if body := b[end : end+k]; len(bytes.TrimSpace(body)) > 0 {
					if name == "pre" || name == "textarea" {
						w.Write(body)
					} else {
						line(depth + 1)
						w.Write(bytes.TrimSpace(body))
					}
				}
				i = end + k
				if i < len(b) {
					ce := tagEnd(b, i)
					line(depth)
					w.Write(b[i:ce])
					i = ce
				}
				continue
			}
			// <name>short text</name> on one line
			if k := bytes.IndexByte(b[end:], '<'); k >= 0 && k <= 120 && bytes.IndexByte(b[end:end+k], '\n') < 0 {
				ce := tagEnd(b, end+k)
				if cn, cl, _ := tagName(b[end+k : ce]); cl && cn == name {
					w.Write(bytes.TrimSpace(b[end : end+k]))
					w.Write(b[end+k : ce])
					i = ce
					continue
				}
			}
			depth++
		}
		i = end
	}
}

// tagEnd returns the index after the tag (or comment, CDATA, doctype) that starts at b[i] == '<'.
func tagEnd(b []byte, i int) int {
	rest := b[i:]
	for _, p := range [][2]string{{"<!--", "-->"}, {"<![CDATA[", "]]>"}} {
		if bytes.HasPrefix(rest, []byte(p[0])) {
			if k := bytes.Index(rest[len(p[0]):], []byte(p[1])); k >= 0 {
				return i + len(p[0]) + k + len(p[1])
			}
			return len(b)
		}
	}
	var q byte
	for j := i + 1; j < len(b); j++ {
		c := b[j]
		switch {
		case q != 0:
			if c == q {
				q = 0
			}
		case c == '"' || c == '\'':
			q = c
		case c == '>':
			return j + 1
		}
	}
	return len(b)
}

// tagName: the lowercase name of a tag; "" for comments, doctypes and processing instructions.
func tagName(tag []byte) (name string, closing, selfClose bool) {
	if len(tag) < 2 || tag[1] == '!' || tag[1] == '?' {
		return "", false, false
	}
	t := tag[1:]
	if t[0] == '/' {
		closing = true
		t = t[1:]
	}
	j := 0
	for j < len(t) && t[j] != ' ' && t[j] != '>' && t[j] != '/' && t[j] != '\t' && t[j] != '\n' && t[j] != '\r' {
		j++
	}
	selfClose = bytes.HasSuffix(tag, []byte("/>"))
	return strings.ToLower(string(t[:j])), closing, selfClose
}

func indexFold(b []byte, s string) int {
	n := len(s)
	for i := 0; i+n <= len(b); i++ {
		if b[i] == '<' && strings.EqualFold(string(b[i:i+n]), s) {
			return i
		}
	}
	return -1
}

// prettyCode lays out JS (js=true) or CSS: a line per statement and per block, indented by braces. Strings,
// template literals, comments and regex literals are copied as they are. It only changes whitespace.
func prettyCode(w *bufio.Writer, b []byte, js bool) {
	depth, parens := 0, 0
	atStart := true // nothing written on the current line yet
	var last byte   // the last significant byte written
	lastWord := ""  // the last identifier written (a regex may follow "return")
	write := func(p []byte) {
		if atStart {
			for i := 0; i < depth; i++ {
				w.WriteString("  ")
			}
			atStart = false
		}
		w.Write(p)
		last = p[len(p)-1]
		lastWord = ""
	}
	nl := func() {
		if !atStart {
			w.WriteByte('\n')
			atStart = true
		}
	}
	space := false // whitespace was skipped: one space goes before the next token on the same line
	for i := 0; i < len(b); {
		c := b[i]
		switch {
		case c == ' ' || c == '\t' || c == '\r':
			space = true
			i++
			continue
		case c == '\n':
			nl()
			space = false
			i++
			continue
		}
		if space && !atStart {
			w.WriteByte(' ')
		}
		space = false
		switch {
		case c == '"' || c == '\'' || (js && c == '`'):
			j := stringEnd(b, i)
			write(b[i:j])
			i = j
		case c == '/' && i+1 < len(b) && b[i+1] == '/' && js:
			j := bytes.IndexByte(b[i:], '\n')
			if j < 0 {
				j = len(b) - i
			}
			write(b[i : i+j])
			nl()
			i += j
		case c == '/' && i+1 < len(b) && b[i+1] == '*':
			j := bytes.Index(b[i+2:], []byte("*/"))
			end := len(b)
			if j >= 0 {
				end = i + 2 + j + 2
			}
			write(b[i:end])
			i = end
		case c == '/' && js && (regexAllowed(last) || regexWords[lastWord]):
			j := regexEnd(b, i)
			write(b[i:j])
			i = j
		case c == '{':
			if !atStart && (last == ')' || isWordByte(last)) {
				w.WriteByte(' ')
			}
			write(b[i : i+1])
			depth++
			nl()
			i++
		case c == '}':
			nl()
			depth = max(0, depth-1)
			write(b[i : i+1])
			i++
			// keep "}," "})" "};" "} else" on the same line
			k := i
			for k < len(b) && (b[k] == ' ' || b[k] == '\t') {
				k++
			}
			if k < len(b) && !strings.ContainsRune("),;.]", rune(b[k])) && !bytes.HasPrefix(b[k:], []byte("else")) &&
				!bytes.HasPrefix(b[k:], []byte("catch")) && !bytes.HasPrefix(b[k:], []byte("finally")) && !bytes.HasPrefix(b[k:], []byte("while")) {
				nl()
			} else if k < len(b) && b[k] != ' ' && (b[k] == 'e' || b[k] == 'c' || b[k] == 'f' || b[k] == 'w') {
				space = true
			}
		case c == ';':
			write(b[i : i+1])
			if parens == 0 {
				nl()
			}
			i++
		case c == '(' || c == '[':
			parens++
			write(b[i : i+1])
			i++
		case c == ')' || c == ']':
			parens = max(0, parens-1)
			write(b[i : i+1])
			i++
		default:
			j := i + 1
			for j < len(b) && !isCodeDelim(b[j]) {
				j++
			}
			write(b[i:j])
			lastWord = string(b[i:j])
			i = j
		}
	}
}

var regexWords = map[string]bool{"return": true, "typeof": true, "case": true, "in": true, "of": true, "delete": true,
	"void": true, "throw": true, "new": true, "else": true, "do": true}

func isWordByte(c byte) bool {
	return c == '_' || c == '$' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
}

func isCodeDelim(c byte) bool {
	switch c {
	case ' ', '\t', '\r', '\n', '"', '\'', '`', '/', '{', '}', ';', '(', ')', '[', ']':
		return true
	}
	return false
}

// regexAllowed: after this byte a '/' starts a regex literal, not a division.
func regexAllowed(last byte) bool {
	if last == 0 {
		return true
	}
	return strings.ContainsRune("(,=:[!&|?{};+-*%<>~^", rune(last))
}

func regexEnd(b []byte, i int) int {
	class := false
	for j := i + 1; j < len(b); j++ {
		switch b[j] {
		case '\\':
			j++
		case '[':
			class = true
		case ']':
			class = false
		case '\n':
			return j // not a regex after all: stop at the line end
		case '/':
			if !class {
				j++
				for j < len(b) && (b[j] >= 'a' && b[j] <= 'z') {
					j++
				}
				return j
			}
		}
	}
	return len(b)
}
