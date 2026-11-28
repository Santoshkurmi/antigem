package script

import (
	"bytes"
	"compress/gzip"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"hash"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dop251/goja"
)

// Sandbox is a test run (net_script test): variables and the store change only here, findings and files are
// collected instead of saved, nothing is sent.
type Sandbox struct {
	mu       sync.Mutex
	Vars     map[string]*string // nil value = deleted
	Store    map[string]any
	Findings []Finding
	Notes    []string
}

// NewSandbox starts a test run.
func NewSandbox(s *Script) *Sandbox {
	sb := &Sandbox{Vars: map[string]*string{}}
	if s != nil && s.store != nil {
		sb.Store = s.store.snapshot()
	}
	return sb
}

func (sb *Sandbox) note(format string, args ...any) {
	sb.mu.Lock()
	sb.Notes = append(sb.Notes, fmt.Sprintf(format, args...))
	sb.mu.Unlock()
}

// installGlobals sets console, command(), every() and builds ctx.
func (w *worker) installGlobals() {
	vm := w.vm
	console := vm.NewObject()
	for _, lvl := range []string{"log", "info", "debug", "warn", "error"} {
		lvl := lvl
		_ = console.Set(lvl, func(c goja.FunctionCall) goja.Value {
			prefix := ""
			if lvl == "warn" || lvl == "error" {
				prefix = lvl + ": "
			}
			w.log(prefix + w.joinArgs(c.Arguments))
			return goja.Undefined()
		})
	}
	_ = vm.Set("console", console)
	_ = vm.Set("command", func(name string, fn goja.Value) {
		if !w.loading {
			w.throw("command() works only at the top level of a script")
		}
		f, ok := goja.AssertFunction(fn)
		if !ok {
			w.throw("command(name, fn): fn must be a function")
		}
		name = strings.TrimSpace(name)
		if name == "" {
			w.throw("command(name, fn): give a name")
		}
		if _, dup := w.commands[name]; !dup {
			w.commandNames = append(w.commandNames, name)
		}
		w.commands[name] = f
	})
	_ = vm.Set("every", func(interval goja.Value, fn goja.Value) {
		if !w.loading {
			w.throw("every() works only at the top level of a script")
		}
		f, ok := goja.AssertFunction(fn)
		if !ok {
			w.throw("every(interval, fn): fn must be a function")
		}
		var d time.Duration
		if n, isNum := interval.Export().(int64); isNum {
			d = time.Duration(n) * time.Millisecond
		} else if x, isF := interval.Export().(float64); isF {
			d = time.Duration(x) * time.Millisecond
		} else {
			var err error
			if d, err = time.ParseDuration(interval.String()); err != nil {
				w.throw("every: use an interval like \"30s\" or \"5m\"")
			}
		}
		if d < time.Second {
			w.throw("every: the interval is at least 1s")
		}
		w.everies = append(w.everies, everyFn{every: d, fn: f})
	})
	w.ctxObj = w.makeCtx()
}

func (w *worker) joinArgs(args []goja.Value) string {
	parts := make([]string, 0, len(args))
	for _, a := range args {
		if o, ok := a.(*goja.Object); ok && o.ClassName() != "Function" && o.ClassName() != "Error" {
			if out, err := w.jsonString(goja.Undefined(), a); err == nil && out != nil && !goja.IsUndefined(out) {
				parts = append(parts, out.String())
				continue
			}
		}
		parts = append(parts, a.String())
	}
	return strings.Join(parts, " ")
}

func (w *worker) log(text string) {
	if w.cur != nil && w.cur.test != nil {
		w.cur.test.note("log: %s", text)
		return
	}
	where := "script"
	if w.cur != nil {
		where = hookName(w.cur.kind)
		if w.cur.flowID > 0 {
			where += " f" + strconv.FormatInt(w.cur.flowID, 10)
		}
	}
	w.s.logf(where, "%s", text)
}

func (w *worker) host() Host { return w.s.m.opts.Host }

func (w *worker) test() *Sandbox {
	if w.cur == nil {
		return nil
	}
	return w.cur.test
}

func (w *worker) makeCtx() *goja.Object {
	vm := w.vm
	c := vm.NewObject()
	_ = c.Set("script", w.s.Name)
	_ = c.Set("vars", vm.NewDynamicObject(&varsObj{w: w}))
	_ = c.Set("store", vm.NewDynamicObject(&storeObj{w: w}))
	_ = c.Set("log", func(call goja.FunctionCall) goja.Value {
		w.log(w.joinArgs(call.Arguments))
		return goja.Undefined()
	})
	_ = c.Set("finding", func(v goja.Value) goja.Value {
		f := w.finding(v)
		if sb := w.test(); sb != nil {
			sb.mu.Lock()
			sb.Findings = append(sb.Findings, f)
			sb.mu.Unlock()
			return goja.Undefined()
		}
		if !w.s.allowFinding() {
			return goja.Undefined()
		}
		w.io(func() { w.host().Finding(f) })
		return goja.Undefined()
	})
	files := vm.NewObject()
	_ = files.Set("write", func(name string, data goja.Value) string { return w.writeFile(name, data, false) })
	_ = files.Set("append", func(name string, data goja.Value) string { return w.writeFile(name, data, true) })
	_ = files.Set("read", func(name string) string {
		b, _ := w.readFile(name)
		return string(b)
	})
	_ = c.Set("files", files)
	_ = c.Set("query", func(filter string, opts goja.Value) goja.Value {
		if w.cur != nil && w.cur.traffic {
			w.throw("ctx.query can't be used in %s (it would hold up the app's traffic): use it in onComplete, every() or a command", hookName(w.cur.kind))
		}
		o := QueryOptions{Limit: 50}
		if opts != nil && !goja.IsUndefined(opts) && !goja.IsNull(opts) {
			ob := opts.ToObject(vm)
			if v := ob.Get("limit"); v != nil && !goja.IsUndefined(v) {
				o.Limit = int(v.ToInteger())
			}
			if v := ob.Get("group_by"); v != nil && !goja.IsUndefined(v) {
				o.GroupBy = v.String()
			} else if v := ob.Get("groupBy"); v != nil && !goja.IsUndefined(v) {
				o.GroupBy = v.String()
			}
		}
		var res any
		var err error
		w.io(func() { res, err = w.host().Query(filter, o) })
		if err != nil {
			w.throw("ctx.query: %v", err)
		}
		return w.fromGo(res)
	})
	_ = c.Set("send", func(v goja.Value) goja.Value { return w.send(v) })
	_ = c.Set("util", w.utilObj())
	return c
}

// ---- ctx.vars: the rules' variables ({{name}}) ----

type varsObj struct{ w *worker }

func (o *varsObj) Get(key string) goja.Value {
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		v, ok := sb.Vars[key]
		sb.mu.Unlock()
		if ok {
			if v == nil {
				return nil
			}
			return o.w.vm.ToValue(*v)
		}
	}
	if v, ok := o.w.host().Var(key); ok {
		return o.w.vm.ToValue(v)
	}
	return nil
}

func (o *varsObj) Set(key string, val goja.Value) bool {
	if val == nil || goja.IsUndefined(val) || goja.IsNull(val) {
		return o.Delete(key)
	}
	s := val.String()
	if ob, ok := val.(*goja.Object); ok && ob.ClassName() != "String" {
		if out, err := o.w.jsonString(goja.Undefined(), val); err == nil && out != nil {
			s = out.String()
		}
	}
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		sb.Vars[key] = &s
		sb.mu.Unlock()
		sb.note("ctx.vars.%s = %q", key, cut(s, 80))
		return true
	}
	o.w.host().SetVar(key, s)
	return true
}

func (o *varsObj) Has(key string) bool { return o.Get(key) != nil }

func (o *varsObj) Delete(key string) bool {
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		sb.Vars[key] = nil
		sb.mu.Unlock()
		return true
	}
	o.w.host().DeleteVar(key)
	return true
}

func (o *varsObj) Keys() []string {
	m := o.w.host().Vars()
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		for k, v := range sb.Vars {
			if v == nil {
				delete(m, k)
			} else {
				m[k] = *v
			}
		}
		sb.mu.Unlock()
	}
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// ---- ctx.store: the script's own data, kept across restarts (assign whole values) ----

type storeObj struct{ w *worker }

func (o *storeObj) Get(key string) goja.Value {
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		v, ok := sb.Store[key]
		sb.mu.Unlock()
		if !ok {
			return nil
		}
		return o.w.fromGo(v)
	}
	v, ok := o.w.s.store.get(key)
	if !ok {
		return nil
	}
	return o.w.fromGo(v)
}

func (o *storeObj) Set(key string, val goja.Value) bool {
	v, err := o.w.toGo(val)
	if err != nil {
		o.w.throw("ctx.store.%s: %v", key, err)
	}
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		if v == nil {
			delete(sb.Store, key)
		} else {
			sb.Store[key] = v
		}
		sb.mu.Unlock()
		return true
	}
	o.w.s.store.set(key, v)
	return true
}

func (o *storeObj) Has(key string) bool { return o.Get(key) != nil }

func (o *storeObj) Delete(key string) bool {
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		delete(sb.Store, key)
		sb.mu.Unlock()
		return true
	}
	o.w.s.store.set(key, nil)
	return true
}

func (o *storeObj) Keys() []string {
	if sb := o.w.test(); sb != nil {
		sb.mu.Lock()
		defer sb.mu.Unlock()
		keys := make([]string, 0, len(sb.Store))
		for k := range sb.Store {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		return keys
	}
	return o.w.s.store.keys()
}

// ---- findings ----

func (w *worker) finding(v goja.Value) Finding {
	f := Finding{Script: w.s.Name, Severity: "info"}
	if w.cur != nil {
		f.Flow = w.cur.flowID
	}
	if v == nil || goja.IsUndefined(v) {
		w.throw("ctx.finding({severity, title, detail, flow})")
	}
	if _, isStr := v.Export().(string); isStr {
		f.Title = v.String()
		return f
	}
	o := v.ToObject(w.vm)
	str := func(k string) string {
		x := o.Get(k)
		if x == nil || goja.IsUndefined(x) || goja.IsNull(x) {
			return ""
		}
		if ob, ok := x.(*goja.Object); ok && ob.ClassName() == "Object" {
			if out, err := w.jsonString(goja.Undefined(), x); err == nil {
				return out.String()
			}
		}
		return x.String()
	}
	f.Title = cut(str("title"), 300)
	f.Detail = cut(str("detail"), 4000)
	if f.Title == "" {
		w.throw("ctx.finding needs a title")
	}
	switch strings.ToLower(str("severity")) {
	case "", "info", "low", "note":
		f.Severity = "info"
	case "warn", "warning", "medium":
		f.Severity = "warn"
	case "error", "high", "critical", "bad":
		f.Severity = "error"
	default:
		f.Severity = "info"
	}
	if fl := o.Get("flow"); fl != nil && !goja.IsUndefined(fl) && !goja.IsNull(fl) {
		switch x := fl.Export().(type) {
		case int64:
			f.Flow = x
		case float64:
			f.Flow = int64(x)
		case string:
			if n, err := strconv.ParseInt(strings.TrimPrefix(strings.ToLower(x), "f"), 10, 64); err == nil {
				f.Flow = n
			}
		case map[string]any: // the f object
			if n, ok := x["num"].(int64); ok {
				f.Flow = n
			}
		}
	}
	return f
}

// allowFinding: at most 120 findings a minute per script (a script reporting every flow fills the list).
func (s *Script) allowFinding() bool {
	s.findMu.Lock()
	defer s.findMu.Unlock()
	now := time.Now()
	if now.Sub(s.findWin) > time.Minute {
		s.findWin, s.findN = now, 0
	}
	s.findN++
	if s.findN == 121 {
		s.logf("engine", "more than 120 findings in a minute: the rest of this minute is dropped")
	}
	return s.findN <= 120
}

// ---- files ----

func (w *worker) writeFile(name string, data goja.Value, appendTo bool) string {
	out := w.host().OutDir()
	p, ok := inside(out, name)
	if !ok || out == "" {
		w.throw("ctx.files: give a file name inside the session's out/ folder")
	}
	var b []byte
	if o, isObj := data.(*goja.Object); isObj && o.ClassName() != "ArrayBuffer" && o.ClassName() != "Uint8Array" && o.ClassName() != "String" {
		s, err := w.jsonString(goja.Undefined(), data, goja.Null(), w.vm.ToValue(2))
		if err != nil {
			w.throw("ctx.files: %s", cleanErr(err))
		}
		b = []byte(s.String())
	} else {
		b = w.toBytes(data)
	}
	if len(b) > 50<<20 {
		w.throw("ctx.files: more than 50 MB")
	}
	if sb := w.test(); sb != nil {
		verb := "write"
		if appendTo {
			verb = "append"
		}
		sb.note("would %s %s (%d bytes)", verb, p, len(b))
		return p
	}
	var err error
	w.io(func() {
		if err = os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
			return
		}
		if appendTo {
			var f *os.File
			if f, err = os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err != nil {
				return
			}
			_, err = f.Write(b)
			if cerr := f.Close(); err == nil {
				err = cerr
			}
			return
		}
		err = os.WriteFile(p, b, 0o600)
	})
	if err != nil {
		w.throw("ctx.files: %v", err)
	}
	return p
}

// ---- ctx.send ----

func (w *worker) send(v goja.Value) goja.Value {
	if w.cur != nil && w.cur.traffic {
		w.throw("ctx.send can't be used in %s (it would hold up the app's traffic): use it in onComplete, every() or a command", hookName(w.cur.kind))
	}
	r := SendRequest{Method: "GET", Headers: map[string]string{}, Timeout: 10 * time.Second, Script: w.s.Name}
	if s, ok := v.Export().(string); ok {
		r.URL = s
	} else if v != nil && !goja.IsUndefined(v) {
		o := v.ToObject(w.vm)
		if x := o.Get("url"); x != nil && !goja.IsUndefined(x) {
			r.URL = x.String()
		}
		if x := o.Get("method"); x != nil && !goja.IsUndefined(x) {
			r.Method = strings.ToUpper(x.String())
		}
		if x := o.Get("headers"); x != nil && !goja.IsUndefined(x) && !goja.IsNull(x) {
			ho := x.ToObject(w.vm)
			for _, k := range ho.Keys() {
				r.Headers[k] = ho.Get(k).String()
			}
		}
		switch {
		case has(o, "json"):
			out, err := w.jsonString(goja.Undefined(), o.Get("json"))
			if err != nil {
				w.throw("ctx.send json: %s", cleanErr(err))
			}
			r.Body = []byte(out.String())
			if !hasHeader(r.Headers, "Content-Type") {
				r.Headers["Content-Type"] = "application/json"
			}
		case has(o, "body"):
			r.Body = w.toBytes(o.Get("body"))
		case has(o, "text"):
			r.Body = []byte(o.Get("text").String())
		}
		if x := o.Get("timeout"); x != nil && !goja.IsUndefined(x) {
			if ms := x.ToInteger(); ms > 0 {
				r.Timeout = min(time.Duration(ms)*time.Millisecond, 30*time.Second)
			}
		}
	}
	if !strings.HasPrefix(r.URL, "http://") && !strings.HasPrefix(r.URL, "https://") {
		w.throw("ctx.send needs a full url (http:// or https://)")
	}
	if sb := w.test(); sb != nil {
		sb.note("would send %s %s", r.Method, r.URL)
		return w.sendResult(&SendResponse{Status: 0}, true)
	}
	var resp *SendResponse
	var err error
	gctx := w.cur.gctx
	w.io(func() { resp, err = w.host().Send(gctx, r) })
	if err != nil {
		w.throw("ctx.send: %v", err)
	}
	return w.sendResult(resp, false)
}

func (w *worker) sendResult(r *SendResponse, test bool) goja.Value {
	vm := w.vm
	o := vm.NewObject()
	_ = o.Set("status", r.Status)
	_ = o.Set("ok", r.Status >= 200 && r.Status < 300)
	if r.Flow > 0 {
		_ = o.Set("flow", "f"+strconv.FormatInt(r.Flow, 10))
	}
	if test {
		_ = o.Set("test", true)
	}
	h := vm.NewObject()
	_ = h.Set("get", func(name string) goja.Value {
		for k, v := range r.Headers {
			if strings.EqualFold(k, name) && len(v) > 0 {
				return vm.ToValue(v[0])
			}
		}
		return goja.Null()
	})
	_ = o.Set("headers", h)
	_ = o.Set("text", func() string { return string(r.Body) })
	_ = o.Set("bytes", func() goja.Value { return vm.ToValue(vm.NewArrayBuffer(append([]byte(nil), r.Body...))) })
	_ = o.Set("json", func() goja.Value {
		if len(bytes.TrimSpace(r.Body)) == 0 {
			return goja.Null()
		}
		v, err := w.jsonParse(goja.Undefined(), vm.ToValue(string(r.Body)))
		if err != nil {
			w.throw("the answer is not JSON: %s", cleanErr(err))
		}
		return v
	})
	return o
}

// ---- ctx.util ----

func (w *worker) utilObj() *goja.Object {
	vm := w.vm
	u := vm.NewObject()
	codec := func(enc func([]byte) string, dec func(string) ([]byte, error)) *goja.Object {
		o := vm.NewObject()
		_ = o.Set("encode", func(v goja.Value) string { return enc(w.toBytes(v)) })
		_ = o.Set("decode", func(s string) string {
			b, err := dec(strings.TrimSpace(s))
			if err != nil {
				w.throw("decode: %v", err)
			}
			return string(b)
		})
		_ = o.Set("decodeBytes", func(s string) goja.Value {
			b, err := dec(strings.TrimSpace(s))
			if err != nil {
				w.throw("decode: %v", err)
			}
			return vm.ToValue(vm.NewArrayBuffer(b))
		})
		return o
	}
	_ = u.Set("base64", codec(base64.StdEncoding.EncodeToString, func(s string) ([]byte, error) {
		if b, err := base64.StdEncoding.DecodeString(s); err == nil {
			return b, nil
		}
		return base64.RawStdEncoding.DecodeString(strings.TrimRight(s, "="))
	}))
	_ = u.Set("base64url", codec(base64.RawURLEncoding.EncodeToString, func(s string) ([]byte, error) {
		return base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
	}))
	_ = u.Set("hex", codec(hex.EncodeToString, hex.DecodeString))
	_ = u.Set("url", codec(func(b []byte) string { return url.QueryEscape(string(b)) }, func(s string) ([]byte, error) {
		x, err := url.QueryUnescape(s)
		return []byte(x), err
	}))
	out := func(sum []byte, enc goja.Value) string {
		switch strings.ToLower(enc.String()) {
		case "base64":
			return base64.StdEncoding.EncodeToString(sum)
		case "base64url":
			return base64.RawURLEncoding.EncodeToString(sum)
		}
		return hex.EncodeToString(sum)
	}
	hashes := map[string]func() hash.Hash{"md5": md5.New, "sha1": sha1.New, "sha256": sha256.New, "sha512": sha512.New}
	for name, h := range hashes {
		h := h
		_ = u.Set(name, func(c goja.FunctionCall) goja.Value {
			x := h()
			x.Write(w.toBytes(c.Argument(0)))
			return vm.ToValue(out(x.Sum(nil), c.Argument(1)))
		})
	}
	_ = u.Set("hmac", func(c goja.FunctionCall) goja.Value {
		h, ok := hashes[strings.ToLower(strings.ReplaceAll(c.Argument(0).String(), "-", ""))]
		if !ok {
			w.throw("hmac(algorithm, key, data[, \"hex\"|\"base64\"]): algorithm md5, sha1, sha256 or sha512")
		}
		m := hmac.New(h, w.toBytes(c.Argument(1)))
		m.Write(w.toBytes(c.Argument(2)))
		return vm.ToValue(out(m.Sum(nil), c.Argument(3)))
	})
	_ = u.Set("uuid", func() string {
		b := make([]byte, 16)
		_, _ = rand.Read(b)
		b[6] = b[6]&0x0f | 0x40
		b[8] = b[8]&0x3f | 0x80
		return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
	})
	_ = u.Set("now", func() int64 { return time.Now().UnixMilli() })
	_ = u.Set("jwtDecode", func(tok string) goja.Value {
		parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(tok, "Bearer ")), ".")
		if len(parts) < 2 {
			w.throw("jwtDecode: not a JWT (header.payload.signature)")
		}
		dec := func(s string) any {
			b, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
			if err != nil {
				w.throw("jwtDecode: %v", err)
			}
			var v any
			if err := json.Unmarshal(b, &v); err != nil {
				w.throw("jwtDecode: %v", err)
			}
			return v
		}
		return w.fromGo(map[string]any{"header": dec(parts[0]), "payload": dec(parts[1])})
	})
	_ = u.Set("gzip", func(v goja.Value) goja.Value {
		var buf bytes.Buffer
		zw := gzip.NewWriter(&buf)
		_, _ = zw.Write(w.toBytes(v))
		_ = zw.Close()
		return vm.ToValue(vm.NewArrayBuffer(buf.Bytes()))
	})
	_ = u.Set("gunzip", func(v goja.Value) string {
		zr, err := gzip.NewReader(bytes.NewReader(w.toBytes(v)))
		if err != nil {
			w.throw("gunzip: %v", err)
		}
		b, err := io.ReadAll(io.LimitReader(zr, 64<<20))
		if err != nil {
			w.throw("gunzip: %v", err)
		}
		return string(b)
	})
	_ = u.Set("text", func(v goja.Value) string { return string(w.toBytes(v)) })
	_ = u.Set("bytes", func(s string) goja.Value { return vm.ToValue(vm.NewArrayBuffer([]byte(s))) })
	return u
}

func cut(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
