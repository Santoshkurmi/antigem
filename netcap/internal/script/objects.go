package script

import (
	"encoding/json"
	"fmt"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/dop251/goja"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/view"
)

// stage is one hook call's view of a flow: reads see the changes made so far, writes are kept here and returned
// as a Result.
type stage struct {
	w          *worker
	phase      string // request, response, complete, message, filter
	f          *flow.Flow
	ex         *Exchange
	bodies     func(side string) ([]byte, error) // complete, filter: stored bodies
	res        Result
	hdr        http.Header // request phase: the request headers, response phase: the response headers (a copy)
	hdrChanged bool
	host       string
	ro         map[string]http.Header
	bodyCache  map[string][]byte
}

func newStage(w *worker, phase string, f *flow.Flow) *stage {
	st := &stage{w: w, phase: phase, f: f, ro: map[string]http.Header{}, bodyCache: map[string][]byte{}}
	if w.s != nil {
		st.res.Script = w.s.Name
	}
	return st
}

func (st *stage) prepare() {
	switch st.phase {
	case "request":
		st.hdr = st.ex.Req.Header.Clone()
		st.host = st.ex.Req.Host
	case "response":
		st.hdr = st.ex.Resp.Header.Clone()
	}
}

func (st *stage) result() *Result {
	if st.hdrChanged {
		st.res.Header = st.hdr
	}
	return &st.res
}

func (st *stage) writable(side string) bool {
	return (st.phase == "request" && side == "req") || (st.phase == "response" && side == "res")
}

func (st *stage) must(side, what string) {
	if !st.writable(side) {
		st.w.throw("%s can't be changed in %s (only f.req in onRequest and f.res in onResponse)", what, hookName(st.phase))
	}
}

func (st *stage) mustTraffic(what string) {
	if st.phase != "request" && st.phase != "response" {
		st.w.throw("%s works only in onRequest and onResponse", what)
	}
}

func (st *stage) change(format string, args ...any) {
	st.res.Changes = append(st.res.Changes, fmt.Sprintf(format, args...))
}

func sideName(side string) string {
	if side == "req" {
		return "request"
	}
	return "response"
}

// headerOf returns the headers of a side (the live copy for the side that may change).
func (st *stage) headerOf(side string) http.Header {
	if st.writable(side) {
		return st.hdr
	}
	if st.ex != nil && side == "req" && st.ex.Req != nil {
		return st.ex.Req.Header
	}
	if h, ok := st.ro[side]; ok {
		return h
	}
	src := st.f.ResHeaders
	if side == "req" {
		src = st.f.ReqHeaders
	}
	h := http.Header{}
	for _, kv := range src {
		h.Add(kv[0], kv[1])
	}
	st.ro[side] = h
	return h
}

func (st *stage) body(side string) []byte {
	if st.writable(side) && st.res.BodySet {
		return st.res.Body
	}
	if b, ok := st.bodyCache[side]; ok {
		return b
	}
	var b []byte
	var err error
	st.w.io(func() {
		switch {
		case st.ex != nil && side == "req" && st.ex.ReqBody != nil:
			b, err = st.ex.ReqBody()
		case st.ex != nil && side == "res" && st.ex.ResBody != nil:
			b, err = st.ex.ResBody()
		case st.ex == nil && st.bodies != nil:
			b, err = st.bodies(side)
		}
	})
	if err != nil {
		st.w.throw("%s body: %v", sideName(side), err)
	}
	st.bodyCache[side] = b
	return b
}

func (st *stage) setBody(side string, b []byte, what string) {
	st.must(side, "f."+side+" body")
	st.body(side) // read the original first: too big ones can't change, and the old bytes must be consumed
	st.res.Body, st.res.BodySet = b, true
	st.change("%s body set by %s (%d bytes)", sideName(side), what, len(b))
}

// object builds the f object.
func (st *stage) object() *goja.Object {
	vm := st.w.vm
	f := st.f
	o := vm.NewObject()
	set := func(k string, v any) { _ = o.Set(k, v) }
	method, path, rawQuery, status := f.Method, f.Path, f.Query, f.Status
	u := f.URL()
	if st.ex != nil && st.ex.Req != nil {
		r := st.ex.Req
		method, path, rawQuery = r.Method, r.URL.EscapedPath(), r.URL.RawQuery
		if path == "" {
			path = "/"
		}
		c := *f
		c.Path, c.Query = path, rawQuery
		u = c.URL()
	}
	if st.ex != nil && st.ex.Resp != nil {
		status = st.ex.Resp.StatusCode
	}
	set("id", "f"+strconv.FormatInt(f.ID, 10))
	set("num", f.ID)
	set("app", f.App)
	set("kind", string(f.Kind))
	set("method", method)
	set("scheme", f.Scheme)
	set("host", f.Host)
	set("port", f.Port)
	set("path", path)
	set("query", rawQuery)
	set("url", u)
	set("http", f.HTTPVersion)
	set("tls", f.TLS)
	set("status", status)
	set("error", f.Error)
	set("time", f.Timings.Total)
	set("ttfb", f.Timings.TTFB)
	set("start", f.Start.UnixMilli())
	set("tags", vm.NewArray(anys(f.Tags)...))
	set("flags", vm.NewArray(anys(f.Flags)...))
	params := vm.NewObject()
	if q, err := url.ParseQuery(rawQuery); err == nil {
		for k, v := range q {
			if len(v) > 0 {
				_ = params.Set(k, v[0])
			}
		}
	}
	set("params", params)
	set("req", st.side("req"))
	if st.phase != "request" {
		set("res", st.side("res"))
	}
	set("tag", func(name string) {
		if st.phase == "filter" {
			st.w.throw("f.tag doesn't work in filters")
		}
		name = strings.TrimSpace(name)
		if name != "" {
			st.res.Tags = append(st.res.Tags, name)
			st.change("tag %s", name)
		}
	})
	set("note", func(text string) {
		if st.phase == "filter" {
			st.w.throw("f.note doesn't work in filters")
		}
		st.res.Note = strings.TrimSpace(st.res.Note + " " + text)
		st.change("note %q", text)
	})
	set("respond", func(v goja.Value) {
		st.mustTraffic("f.respond")
		st.res.Respond = st.w.response(v)
		st.change("answered with %d (%d bytes) instead of the server", st.res.Respond.Status, len(st.res.Respond.Body))
	})
	set("drop", func() {
		st.mustTraffic("f.drop")
		st.res.Drop = true
		st.change("dropped")
	})
	set("delay", func(ms int64) {
		st.mustTraffic("f.delay")
		if ms < 0 {
			ms = 0
		}
		if ms > 60000 {
			ms = 60000
		}
		st.res.Delay = time.Duration(ms) * time.Millisecond
		st.change("delayed %d ms", ms)
	})
	set("redirect", func(to string) {
		if st.phase != "request" {
			st.w.throw("f.redirect works only in onRequest")
		}
		if !strings.HasPrefix(to, "http://") && !strings.HasPrefix(to, "https://") {
			st.w.throw("f.redirect needs a full URL (http:// or https://)")
		}
		st.res.Redirect = to
		st.change("sent to %s instead", to)
	})
	return o
}

func anys(list []string) []any {
	out := make([]any, len(list))
	for i, s := range list {
		out[i] = s
	}
	return out
}

func (st *stage) side(side string) *goja.Object {
	vm := st.w.vm
	o := vm.NewObject()
	set := func(k string, v any) { _ = o.Set(k, v) }
	ct := st.headerOf(side).Get("Content-Type")
	set("headers", st.headersObj(side))
	set("contentType", ct)
	b := &st.f.Res
	if side == "req" {
		b = &st.f.Req
	}
	size := b.Size
	typ := b.Type
	if st.ex != nil && (st.phase == "request" || st.phase == "response") && (side == "req" || st.phase == "response") {
		size = -1
		if side == "req" && st.ex.Req != nil {
			size = st.ex.Req.ContentLength
		} else if side == "res" && st.ex.Resp != nil {
			size = st.ex.Resp.ContentLength
		}
		typ = view.ShortType(ct, nil)
	}
	set("size", size)
	set("type", typ)
	set("text", func() string { return string(st.body(side)) })
	set("bytes", func() goja.Value { return vm.ToValue(vm.NewArrayBuffer(append([]byte(nil), st.body(side)...))) })
	set("json", func() goja.Value {
		data := st.body(side)
		if len(strings.TrimSpace(string(data))) == 0 {
			return goja.Null()
		}
		v, err := st.w.jsonParse(goja.Undefined(), vm.ToValue(string(data)))
		if err != nil {
			st.w.throw("%s body is not JSON: %s", sideName(side), cleanErr(err))
		}
		return v
	})
	set("setText", func(s string) { st.setBody(side, []byte(s), "setText") })
	set("setBytes", func(v goja.Value) { st.setBody(side, st.w.toBytes(v), "setBytes") })
	set("setJson", func(v goja.Value) {
		out, err := st.w.jsonString(goja.Undefined(), v)
		if err != nil {
			st.w.throw("setJson: %s", cleanErr(err))
		}
		st.setBody(side, []byte(out.String()), "setJson")
	})
	if side == "req" {
		method := st.f.Method
		if st.ex != nil && st.ex.Req != nil {
			method = st.ex.Req.Method
		}
		set("method", method)
		set("setMethod", func(m string) {
			st.must("req", "the method")
			st.res.Method = strings.ToUpper(strings.TrimSpace(m))
			st.change("method → %s", st.res.Method)
		})
		set("setPath", func(p string) {
			st.must("req", "the path")
			if !strings.HasPrefix(p, "/") {
				st.w.throw("setPath needs a path that starts with /")
			}
			st.res.Path = &p
			st.change("path → %s", p)
		})
		curQuery := func() url.Values {
			raw := ""
			if st.res.RawQuery != nil {
				raw = *st.res.RawQuery
			} else if st.ex != nil && st.ex.Req != nil {
				raw = st.ex.Req.URL.RawQuery
			}
			q, _ := url.ParseQuery(raw)
			return q
		}
		set("setQuery", func(k, v string) {
			st.must("req", "the query")
			q := curQuery()
			q.Set(k, v)
			enc := q.Encode()
			st.res.RawQuery = &enc
			st.change("query %s=%s", k, v)
		})
		set("removeQuery", func(k string) {
			st.must("req", "the query")
			q := curQuery()
			q.Del(k)
			enc := q.Encode()
			st.res.RawQuery = &enc
			st.change("query %s removed", k)
		})
	} else {
		status := st.f.Status
		if st.ex != nil && st.ex.Resp != nil {
			status = st.ex.Resp.StatusCode
		}
		set("status", status)
		set("setStatus", func(n int) {
			st.must("res", "the status")
			if n < 100 || n > 999 {
				st.w.throw("setStatus needs a status from 100 to 999")
			}
			st.res.Status = n
			st.change("status → %d", n)
		})
	}
	return o
}

func (st *stage) headersObj(side string) *goja.Object {
	vm := st.w.vm
	o := vm.NewObject()
	isHost := func(name string) bool { return side == "req" && strings.EqualFold(name, "host") }
	host := func() string {
		if st.writable("req") {
			return st.host
		}
		if st.ex != nil && st.ex.Req != nil {
			return st.ex.Req.Host
		}
		if v := st.headerOf("req").Get(":authority"); v != "" {
			return v
		}
		if v := st.headerOf("req").Get("Host"); v != "" {
			return v
		}
		return st.f.Host
	}
	_ = o.Set("get", func(name string) goja.Value {
		if isHost(name) {
			return vm.ToValue(host())
		}
		v := st.headerOf(side).Values(name)
		if len(v) == 0 {
			return goja.Null()
		}
		return vm.ToValue(v[0])
	})
	_ = o.Set("has", func(name string) bool {
		if isHost(name) {
			return host() != ""
		}
		return len(st.headerOf(side).Values(name)) > 0
	})
	_ = o.Set("all", func(c goja.FunctionCall) goja.Value {
		h := st.headerOf(side)
		if name := c.Argument(0); !goja.IsUndefined(name) {
			return vm.ToValue(anys(h.Values(name.String())))
		}
		keys := make([]string, 0, len(h))
		for k := range h {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		var pairs []any
		for _, k := range keys {
			for _, v := range h[k] {
				pairs = append(pairs, []any{k, v})
			}
		}
		return vm.ToValue(pairs)
	})
	_ = o.Set("names", func() goja.Value {
		h := st.headerOf(side)
		keys := make([]string, 0, len(h))
		for k := range h {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		return vm.ToValue(anys(keys))
	})
	write := func(name, value string, add bool) {
		st.must(side, "f."+side+".headers")
		name = strings.TrimSpace(name)
		if name == "" {
			st.w.throw("a header needs a name")
		}
		if isHost(name) {
			st.host, st.res.Host = value, value
			st.change("request Host → %s", value)
			return
		}
		if add {
			st.hdr.Add(name, value)
		} else {
			st.hdr.Set(name, value)
		}
		st.hdrChanged = true
		st.change("%s header %s: %s", sideName(side), name, value)
	}
	_ = o.Set("set", func(name, value string) { write(name, value, false) })
	_ = o.Set("add", func(name, value string) { write(name, value, true) })
	remove := func(name string) {
		st.must(side, "f."+side+".headers")
		st.hdr.Del(name)
		st.hdrChanged = true
		st.change("%s header %s removed", sideName(side), name)
	}
	_ = o.Set("remove", remove)
	_ = o.Set("delete", remove)
	return o
}

// response reads f.respond's argument: {status, headers, json | text | body | file}.
func (w *worker) response(v goja.Value) *Response {
	r := &Response{Status: 200, Headers: map[string]string{}}
	if v == nil || goja.IsUndefined(v) || goja.IsNull(v) {
		return r
	}
	obj := v.ToObject(w.vm)
	if s := obj.Get("status"); s != nil && !goja.IsUndefined(s) {
		r.Status = int(s.ToInteger())
		if r.Status < 100 || r.Status > 999 {
			w.throw("respond: status from 100 to 999")
		}
	}
	if h := obj.Get("headers"); h != nil && !goja.IsUndefined(h) && !goja.IsNull(h) {
		ho := h.ToObject(w.vm)
		for _, k := range ho.Keys() {
			r.Headers[k] = ho.Get(k).String()
		}
	}
	ct := ""
	switch {
	case has(obj, "json"):
		out, err := w.jsonString(goja.Undefined(), obj.Get("json"))
		if err != nil {
			w.throw("respond json: %s", cleanErr(err))
		}
		r.Body, ct = []byte(out.String()), "application/json"
	case has(obj, "text"):
		r.Body, ct = []byte(obj.Get("text").String()), "text/plain; charset=utf-8"
	case has(obj, "body"):
		r.Body = w.toBytes(obj.Get("body"))
		ct = "application/octet-stream"
		if _, isStr := obj.Get("body").Export().(string); isStr {
			ct = "text/plain; charset=utf-8"
		}
	case has(obj, "file"):
		data, path := w.readFile(obj.Get("file").String())
		r.Body = data
		if ct = mime.TypeByExtension(filepath.Ext(path)); ct == "" {
			ct = http.DetectContentType(data)
		}
	}
	if !hasHeader(r.Headers, "Content-Type") && ct != "" && len(r.Body) > 0 {
		r.Headers["Content-Type"] = ct
	}
	return r
}

func has(o *goja.Object, k string) bool {
	v := o.Get(k)
	return v != nil && !goja.IsUndefined(v)
}

func hasHeader(h map[string]string, name string) bool {
	for k := range h {
		if strings.EqualFold(k, name) {
			return true
		}
	}
	return false
}

// toBytes accepts a string, an ArrayBuffer, a typed array or an array of numbers.
func (w *worker) toBytes(v goja.Value) []byte {
	if v == nil || goja.IsUndefined(v) || goja.IsNull(v) {
		return nil
	}
	switch x := v.Export().(type) {
	case string:
		return []byte(x)
	case goja.ArrayBuffer:
		return append([]byte(nil), x.Bytes()...)
	case []byte:
		return append([]byte(nil), x...)
	case []any:
		out := make([]byte, len(x))
		for i, n := range x {
			switch k := n.(type) {
			case int64:
				out[i] = byte(k)
			case float64:
				out[i] = byte(k)
			}
		}
		return out
	}
	return []byte(v.String())
}

// fromGo makes a fresh JavaScript value from JSON-like Go data.
func (w *worker) fromGo(v any) goja.Value {
	if v == nil {
		return goja.Undefined()
	}
	switch x := v.(type) {
	case string, bool, int, int64, float64:
		return w.vm.ToValue(x)
	}
	b, err := json.Marshal(v)
	if err != nil {
		return w.vm.ToValue(fmt.Sprint(v))
	}
	out, err := w.jsonParse(goja.Undefined(), w.vm.ToValue(string(b)))
	if err != nil {
		return goja.Undefined()
	}
	return out
}

// toGo makes JSON-like Go data from a JavaScript value (functions and cycles are dropped by JSON.stringify).
func (w *worker) toGo(v goja.Value) (any, error) {
	if v == nil || goja.IsUndefined(v) {
		return nil, nil
	}
	out, err := w.jsonString(goja.Undefined(), v)
	if err != nil {
		return nil, fmt.Errorf("%s", cleanErr(err))
	}
	if out == nil || goja.IsUndefined(out) {
		return nil, nil
	}
	var x any
	if err := json.Unmarshal([]byte(out.String()), &x); err != nil {
		return nil, err
	}
	return x, nil
}

// messageObject builds m for onMessage.
func (w *worker) messageObject(st *stage, m Message, index int, res *MsgResult) *goja.Object {
	vm := w.vm
	o := vm.NewObject()
	cur := func() []byte {
		if res.Changed {
			return res.Data
		}
		return m.Data
	}
	curType := func() string {
		if res.Changed && res.Type != "" {
			return res.Type
		}
		return m.Type
	}
	_ = o.Set("dir", m.Dir)
	_ = o.Set("index", index)
	_ = o.Set("size", len(m.Data))
	_ = o.DefineAccessorProperty("type", vm.ToValue(func(goja.FunctionCall) goja.Value { return vm.ToValue(curType()) }), nil, goja.FLAG_TRUE, goja.FLAG_TRUE)
	setText := func(s string) {
		res.Changed, res.Data, res.Type = true, []byte(s), "text"
		res.Changes = append(res.Changes, fmt.Sprintf("message changed (%d bytes)", len(s)))
	}
	_ = o.DefineAccessorProperty("text",
		vm.ToValue(func(goja.FunctionCall) goja.Value { return vm.ToValue(string(cur())) }),
		vm.ToValue(func(c goja.FunctionCall) goja.Value { setText(c.Argument(0).String()); return goja.Undefined() }),
		goja.FLAG_TRUE, goja.FLAG_TRUE)
	_ = o.Set("bytes", func() goja.Value { return vm.ToValue(vm.NewArrayBuffer(append([]byte(nil), cur()...))) })
	_ = o.Set("json", func() goja.Value {
		v, err := w.jsonParse(goja.Undefined(), vm.ToValue(string(cur())))
		if err != nil {
			w.throw("the message is not JSON: %s", cleanErr(err))
		}
		return v
	})
	_ = o.Set("setText", setText)
	_ = o.Set("setJson", func(v goja.Value) {
		out, err := w.jsonString(goja.Undefined(), v)
		if err != nil {
			w.throw("setJson: %s", cleanErr(err))
		}
		setText(out.String())
	})
	_ = o.Set("setBytes", func(v goja.Value) {
		b := w.toBytes(v)
		res.Changed, res.Data, res.Type = true, b, "binary"
		res.Changes = append(res.Changes, fmt.Sprintf("message changed (%d bytes, binary)", len(b)))
	})
	_ = o.Set("drop", func() {
		res.Drop = true
		res.Changes = append(res.Changes, "message dropped")
	})
	msg := func(v goja.Value, dir string) Message {
		typ := "text"
		if _, ok := v.Export().(string); !ok {
			typ = "binary"
		}
		return Message{Dir: dir, Type: typ, Data: w.toBytes(v)}
	}
	other := "in"
	if m.Dir == "in" {
		other = "out"
	}
	_ = o.Set("sendExtra", func(v goja.Value) {
		res.Extra = append(res.Extra, msg(v, m.Dir))
		res.Changes = append(res.Changes, "extra message sent")
	})
	_ = o.Set("reply", func(v goja.Value) {
		res.Replies = append(res.Replies, msg(v, other))
		res.Changes = append(res.Changes, "reply sent back")
	})
	_ = o.Set("flow", st.object())
	return o
}

// readFile reads a file a script may read: in its own folder, or in the session's out/ folder.
func (w *worker) readFile(name string) ([]byte, string) {
	if w.s == nil {
		w.throw("files can't be read here")
	}
	var data []byte
	var path string
	var err error
	w.io(func() {
		for _, dir := range []string{filepath.Dir(w.s.File), w.s.m.opts.Host.OutDir()} {
			p, ok := inside(dir, name)
			if !ok {
				continue
			}
			var st os.FileInfo
			if st, err = os.Stat(p); err != nil || st.IsDir() {
				continue
			}
			if st.Size() > 20<<20 {
				err = fmt.Errorf("%s is bigger than 20 MB", name)
				return
			}
			data, err = os.ReadFile(p)
			path = p
			return
		}
		if err == nil {
			err = fmt.Errorf("no file %s in the script's folder or the session's out/ folder", name)
		}
	})
	if err != nil {
		w.throw("%v", err)
	}
	return data, path
}

// inside joins dir and a relative name, refusing names that leave dir.
func inside(dir, name string) (string, bool) {
	if dir == "" || name == "" {
		return "", false
	}
	if filepath.IsAbs(name) {
		rel, err := filepath.Rel(dir, filepath.Clean(name))
		if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return "", false
		}
		return filepath.Join(dir, rel), true
	}
	p := filepath.Join(dir, filepath.Clean("/"+name))
	return p, true
}
