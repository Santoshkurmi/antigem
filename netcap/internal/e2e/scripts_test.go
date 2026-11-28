package e2e

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/websocket"

	"antigem/netcap/internal/hookclient"
	"antigem/netcap/internal/httpapi"
)

func loadScript(t *testing.T, e *env, name, code string) string {
	t.Helper()
	out := call(t, e, "net_script", map[string]any{"action": "load", "name": name, "code": code})
	if strings.HasPrefix(out, "error") {
		t.Fatalf("load %s: %s", name, out)
	}
	return out
}

// echoServer answers with what it got; /login gives a token, /config JSON, /fail a 500.
func echoServer(t *testing.T, seen func(r *http.Request, body string)) *httptest.Server {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if seen != nil {
			seen(r, string(b))
		}
		switch r.URL.Path {
		case "/login":
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, `{"token":"tok-9","user":{"id":7,"role":"user"}}`)
		case "/config":
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, `{"features":{"new_ui":false},"debug":false,"items":[1,2,3]}`)
		case "/fail":
			w.WriteHeader(500)
			io.WriteString(w, "boom")
		default:
			w.Header().Set("Content-Type", "text/plain")
			fmt.Fprintf(w, "real %s %s %s", r.Method, r.URL.RequestURI(), b)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestScriptsChangeTraffic(t *testing.T) {
	for _, h2 := range []bool{false, true} {
		t.Run(map[bool]string{false: "h1", true: "h2"}[h2], func(t *testing.T) {
			e := setup(t)
			var mu sync.Mutex
			var lastAuth, lastBody, lastQuery, lastPath string
			srv := echoServer(t, func(r *http.Request, body string) {
				mu.Lock()
				lastAuth, lastBody, lastQuery, lastPath = r.Header.Get("Authorization"), body, r.URL.RawQuery, r.URL.Path
				mu.Unlock()
			})
			c := e.client(h2)
			out := loadScript(t, e, "login", `// @name login-debug
// @match host:127.0.0.1
// @body response

function onRequest(f, ctx) {
  if (ctx.vars.token) f.req.headers.set("Authorization", "Bearer " + ctx.vars.token)
  if (f.path === "/submit") {
    const body = f.req.json()
    body.device = { os: "android" }
    f.req.setJson(body)
    f.req.setQuery("v", "2")
  }
  if (f.path === "/old") f.req.setPath("/new")
  if (f.path === "/mock") f.respond({ status: 201, json: { mocked: true, user: ctx.vars.user || null } })
  if (f.path === "/drop") f.drop()
}

function onResponse(f, ctx) {
  if (f.path === "/login" && f.res.status === 200) {
    ctx.vars.token = f.res.json().token
    f.tag("login-ok")
  }
  if (f.path === "/config") {
    const c = f.res.json()
    c.features.new_ui = true
    f.res.setJson(c)
    f.res.headers.set("X-Script", "yes")
    f.res.setStatus(203)
  }
}
`)
			if !strings.Contains(out, "hooks: request, response") {
				t.Fatalf("load: %s", out)
			}
			_, body := get(t, c, srv.URL+"/login")
			if !strings.Contains(body, "tok-9") {
				t.Fatalf("login: %s", body)
			}
			get(t, c, srv.URL+"/me")
			mu.Lock()
			if lastAuth != "Bearer tok-9" {
				t.Fatalf("token from ctx.vars: %q", lastAuth)
			}
			mu.Unlock()
			resp, body := get(t, c, srv.URL+"/config")
			if resp.StatusCode != 203 || resp.Header.Get("X-Script") != "yes" || body != `{"features":{"new_ui":true},"debug":false,"items":[1,2,3]}` {
				t.Fatalf("response change: %d %v %s", resp.StatusCode, resp.Header, body)
			}
			r2, err := c.Post(srv.URL+"/submit?a=1", "application/json", strings.NewReader(`{"password":"x"}`))
			if err != nil {
				t.Fatal(err)
			}
			io.ReadAll(r2.Body)
			r2.Body.Close()
			mu.Lock()
			if lastBody != `{"password":"x","device":{"os":"android"}}` || lastQuery != "a=1&v=2" {
				t.Fatalf("request body/query: %q %q", lastBody, lastQuery)
			}
			mu.Unlock()
			_, body = get(t, c, srv.URL+"/old?x=1")
			if body != "real GET /new?x=1 " || lastPath != "/new" {
				t.Fatalf("setPath: %q", body)
			}
			resp, body = get(t, c, srv.URL+"/mock")
			if resp.StatusCode != 201 || body != `{"mocked":true,"user":null}` {
				t.Fatalf("respond: %d %s", resp.StatusCode, body)
			}
			if _, err := c.Get(srv.URL + "/drop"); err == nil {
				t.Fatal("f.drop should reset the request")
			}
			fs := e.flows(t, 7)
			var submit, login string
			for _, f := range fs {
				if f.Path == "/submit" {
					b, _, _ := e.svc.Body(e.svc.Current(), f, "req", false, 0)
					submit = string(b)
					if !strings.Contains(strings.Join(f.Flags, ","), "script:login-debug") {
						t.Fatalf("flags of a changed flow: %v", f.Flags)
					}
				}
				if f.Path == "/login" {
					login = strings.Join(f.Tags, ",")
				}
			}
			if submit != `{"password":"x","device":{"os":"android"}}` {
				t.Fatalf("recorded request body should be what was sent: %q", submit)
			}
			if login != "login-ok" {
				t.Fatalf("tag: %q", login)
			}
			list := call(t, e, "net_script", nil)
			if !strings.Contains(list, "on  login-debug") || strings.Contains(list, "errors") {
				t.Fatalf("list:\n%s", list)
			}
		})
	}
}

func TestScriptCompleteCommandFindings(t *testing.T) {
	e := setup(t)
	srv := echoServer(t, nil)
	c := e.client(true)
	loadScript(t, e, "watch", `// @name watch
function onComplete(f, ctx) {
  ctx.store.count = (ctx.store.count || 0) + 1
  if (f.status >= 500) {
    ctx.finding({ severity: "warn", title: "Server error on " + f.path, detail: f.res.text(), flow: f })
    f.tag("server-error")
  }
  if (f.path === "/config" && f.res.json().items.length === 3) ctx.log("config has", f.res.json().items.length, "items")
}

command("slow", function (args, ctx) {
  return ctx.query("host:127.0.0.1", { group_by: "path", limit: args.limit || 10 })
})

command("count", function (args, ctx) { return { count: ctx.store.count, vars: Object.keys(ctx.vars).length } })

command("save", function (args, ctx) { return ctx.files.write("report/summary.json", { n: ctx.store.count }) })

command("ticks", function (args, ctx) { return ctx.store.ticks || 0 })
every("1s", function (ctx) { ctx.store.ticks = (ctx.store.ticks || 0) + 1 })
`)
	get(t, c, srv.URL+"/config")
	get(t, c, srv.URL+"/fail")
	get(t, c, srv.URL+"/config")
	e.flows(t, 3)
	var out string
	for i := 0; i < 100; i++ {
		out = call(t, e, "net_findings", nil)
		if strings.Contains(out, "Server error on /fail") {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !strings.Contains(out, "[warn] Server error on /fail · f2 · watch") {
		t.Fatalf("findings:\n%s", out)
	}
	if got := call(t, e, "net_findings", map[string]any{"action": "get", "id": "1"}); !strings.Contains(got, "boom") {
		t.Fatalf("finding detail: %s", got)
	}
	time.Sleep(100 * time.Millisecond)
	if out := call(t, e, "net_list", map[string]any{"filter": "tag:server-error"}); !strings.Contains(out, "/fail") {
		t.Fatalf("onComplete tag: %s", out)
	}
	out = call(t, e, "net_script", map[string]any{"action": "call", "name": "watch.slow", "args": map[string]any{"limit": 5}})
	if !strings.Contains(out, `"key":"/config","count":2`) {
		t.Fatalf("command with ctx.query: %s", out)
	}
	if out = call(t, e, "net_script", map[string]any{"action": "call", "name": "watch.count"}); !strings.Contains(out, `"count":3`) {
		t.Fatalf("ctx.store across workers: %s", out)
	}
	out = call(t, e, "net_script", map[string]any{"action": "call", "name": "watch.save"})
	if b, err := os.ReadFile(out); err != nil || !strings.Contains(string(b), `"n": 3`) {
		t.Fatalf("files.write: %s %v %s", out, err, b)
	}
	if out := call(t, e, "net_script", map[string]any{"action": "logs", "name": "watch"}); !strings.Contains(out, "config has 3 items") {
		t.Fatalf("logs: %s", out)
	}
	time.Sleep(1300 * time.Millisecond)
	if out := call(t, e, "net_script", map[string]any{"action": "call", "name": "watch.ticks"}); out == "0" || out == "null" {
		t.Fatalf("every(): %s", out)
	}
	if _, err := e.mcp.Call(context.Background(), "net_script", map[string]any{"action": "call", "name": "watch.nope"}); err == nil ||
		!strings.Contains(err.Error(), "commands: slow, count, save, ticks") {
		t.Fatalf("unknown command: %v", err)
	}
}

func TestScriptTimeoutAndErrors(t *testing.T) {
	e := setup(t)
	srv := echoServer(t, nil)
	c := e.client(false)
	loadScript(t, e, "slow", `// @name slow
// @timeout 20ms
function onRequest(f, ctx) {
  f.req.headers.set("X-Never", "1")
  while (true) {}
}`)
	for i := 0; i < 21; i++ {
		t0 := time.Now()
		resp, body := get(t, c, srv.URL+"/x")
		if resp.StatusCode != 200 || body != "real GET /x " {
			t.Fatalf("a stopped hook must leave the flow unchanged: %d %q", resp.StatusCode, body)
		}
		if d := time.Since(t0); d > 400*time.Millisecond {
			t.Fatalf("the hook held the request %s", d)
		}
	}
	list := call(t, e, "net_script", nil)
	if !strings.Contains(list, "off slow") || !strings.Contains(list, "took longer than 20ms") || !strings.Contains(list, "turned off by the engine") {
		t.Fatalf("after 20 failures the script turns off:\n%s", list)
	}
	if out := call(t, e, "net_findings", nil); !strings.Contains(out, "script slow was turned off") {
		t.Fatalf("finding about turning off: %s", out)
	}
	// a syntax error comes back with its line
	out := call(t, e, "net_script", map[string]any{"action": "load", "name": "broken", "code": "// @name broken\nfunction onRequest(f, ctx) {\n  let x = = 1\n}\n"})
	if !strings.Contains(out, "error") || !strings.Contains(out, "broken.js:3") {
		t.Fatalf("syntax error with line: %s", out)
	}
	// a runtime error is logged with the line, the flow goes on
	loadScript(t, e, "thrower", "// @name thrower\nfunction onResponse(f, ctx) {\n  f.res.json().nothing.here = 1\n}\n")
	resp, body := get(t, c, srv.URL+"/y")
	if resp.StatusCode != 200 || body != "real GET /y " {
		t.Fatalf("runtime error: %d %s", resp.StatusCode, body)
	}
	if logs := call(t, e, "net_script", map[string]any{"action": "logs", "name": "thrower"}); !strings.Contains(logs, "thrower.js:3") {
		t.Fatalf("runtime error line: %s", logs)
	}
	// pending Promise
	loadScript(t, e, "async", "// @name async\nasync function onRequest(f, ctx) { await new Promise(function () {}) }\n")
	get(t, c, srv.URL+"/z")
	if logs := call(t, e, "net_script", map[string]any{"action": "logs", "name": "async"}); !strings.Contains(logs, "still pending") {
		t.Fatalf("async: %s", logs)
	}
}

func TestScriptHotReloadAndFilter(t *testing.T) {
	e := setup(t)
	srv := echoServer(t, nil)
	c := e.client(true)
	dir := e.svc.Scripts.Dir()
	p := filepath.Join(dir, "tagger.js")
	write := func(tag string) {
		os.WriteFile(p, []byte("// @match path:/config\nfunction onResponse(f, ctx) { f.res.headers.set('X-Tag', '"+tag+"') }\n"), 0o600)
	}
	waitHeader := func(want string) {
		t.Helper()
		var got string
		for i := 0; i < 100; i++ {
			resp, _ := get(t, c, srv.URL+"/config")
			if got = resp.Header.Get("X-Tag"); got == want {
				return
			}
			time.Sleep(30 * time.Millisecond)
		}
		t.Fatalf("hot reload: X-Tag %q, want %q\n%s", got, want, call(t, e, "net_script", nil))
	}
	write("one")
	waitHeader("one")
	write("two")
	waitHeader("two")
	call(t, e, "net_script", map[string]any{"action": "disable", "name": "tagger"})
	if resp, _ := get(t, c, srv.URL+"/config"); resp.Header.Get("X-Tag") != "" {
		t.Fatal("a disabled script still runs")
	}
	os.Remove(p)
	// js: filters
	get(t, c, srv.URL+"/fail")
	e.flows(t, 3)
	out := call(t, e, "net_list", map[string]any{"filter": `js:"f.status >= 500 && f.path.startsWith('/fa')"`})
	if !strings.Contains(out, "/fail") || strings.Contains(out, "/config") {
		t.Fatalf("js filter: %s", out)
	}
	out = call(t, e, "net_list", map[string]any{"filter": `js:"f.res.json().items.length === 3"`})
	if !strings.Contains(out, "/config") || strings.Contains(out, "/fail") {
		t.Fatalf("js filter with a body: %s", out)
	}
	if _, err := e.mcp.Call(context.Background(), "net_list", map[string]any{"filter": `js:"f.status >"`}); err == nil {
		t.Fatal("a js syntax error should fail the filter")
	}
	// net_script test on stored flows changes nothing
	loadScript(t, e, "tester", `// @name tester
// @match path:/config
// @enabled false
function onResponse(f, ctx) { const c = f.res.json(); c.debug = true; f.res.setJson(c); ctx.vars.seen = "yes"; ctx.finding("saw config") }`)
	out = call(t, e, "net_script", map[string]any{"action": "test", "name": "tester"})
	if !strings.Contains(out, "onResponse: response body set by setJson") || !strings.Contains(out, "finding [info] saw config") || !strings.Contains(out, `ctx.vars.seen = "yes"`) {
		t.Fatalf("test:\n%s", out)
	}
	if v, ok := e.svc.Rules.Var("seen"); ok {
		t.Fatalf("a test must not set variables: %q", v)
	}
	if f := call(t, e, "net_findings", nil); !strings.HasPrefix(f, "no findings") {
		t.Fatalf("a test must not save findings: %s", f)
	}
}

func TestScriptWebSocket(t *testing.T) {
	e := setup(t)
	var ext string
	srv := httptest.NewTLSServer(websocket.Server{
		Handshake: func(cfg *websocket.Config, r *http.Request) error { ext = r.Header.Get("Sec-WebSocket-Extensions"); return nil },
		Handler: func(ws *websocket.Conn) {
			var msg string
			for websocket.Message.Receive(ws, &msg) == nil {
				websocket.Message.Send(ws, "echo:"+msg)
			}
		},
	})
	defer srv.Close()
	loadScript(t, e, "ws", `// @name ws
// @match path:/chat
function onMessage(m, ctx) {
  if (m.dir === "out" && m.text === "secret") { m.drop(); m.reply("not sent"); return }
  if (m.dir === "out") m.setText(m.text.toUpperCase())
  if (m.dir === "in" && m.text.indexOf("TWO") >= 0) m.sendExtra("extra after two")
}`)
	addr := strings.TrimPrefix(srv.URL, "https://")
	raw := dialConnect(t, e.proxy, addr)
	tc := tls.Client(raw, &tls.Config{RootCAs: e.ca, ServerName: "127.0.0.1"})
	cfg, _ := websocket.NewConfig("wss://"+addr+"/chat", "https://"+addr)
	cfg.Header = http.Header{"Sec-WebSocket-Extensions": {"permessage-deflate; client_max_window_bits"}}
	ws, err := websocket.NewClient(cfg, tc)
	if err != nil {
		t.Fatal(err)
	}
	recv := func() string {
		var got string
		ws.SetReadDeadline(time.Now().Add(3 * time.Second))
		if err := websocket.Message.Receive(ws, &got); err != nil {
			t.Fatal(err)
		}
		return got
	}
	websocket.Message.Send(ws, "one")
	if got := recv(); got != "echo:ONE" {
		t.Fatalf("changed out message: %q", got)
	}
	websocket.Message.Send(ws, "two")
	if a, b := recv(), recv(); a != "echo:TWO" || b != "extra after two" {
		t.Fatalf("extra: %q %q", a, b)
	}
	websocket.Message.Send(ws, "secret")
	if got := recv(); got != "not sent" {
		t.Fatalf("drop + reply: %q", got)
	}
	ws.Close()
	if ext != "" {
		t.Fatalf("permessage-deflate should be taken out of the handshake: %q", ext)
	}
	f := e.flows(t, 1)[0]
	flags := strings.Join(f.Flags, ",")
	if !strings.Contains(flags, "modified") || !strings.Contains(flags, "messages-dropped") {
		t.Fatalf("flags: %s", flags)
	}
	out := call(t, e, "net_ws", map[string]any{"id": "f1"})
	if !strings.Contains(out, "ONE") || !strings.Contains(out, "dropped") || !strings.Contains(out, "not sent") {
		t.Fatalf("net_ws:\n%s", out)
	}
}

func TestExternalHook(t *testing.T) {
	e := setup(t)
	var mu sync.Mutex
	var hookHeader string
	srv := echoServer(t, func(r *http.Request, body string) {
		mu.Lock()
		hookHeader = r.Header.Get("X-Hook")
		mu.Unlock()
	})
	api := &httpapi.API{Svc: e.svc, MCP: e.mcp, Token: "tok"}
	hs := httptest.NewServer(api.Handler())
	defer hs.Close()
	bin := filepath.Join(t.TempDir(), "hook-example")
	if out, err := exec.Command("go", "build", "-o", bin, "antigem/netcap/cmd/netcap-hook-example").CombinedOutput(); err != nil {
		t.Fatalf("build the example hook: %v %s", err, out)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	apiAddr := strings.TrimPrefix(hs.URL, "http://")
	done := make(chan error, 2)
	go func() {
		done <- hookclient.Run(ctx, hookclient.Options{API: apiAddr, Token: "tok", Name: "example", Mode: "modify",
			Filter: "host:127.0.0.1", Timeout: "2s"}, []string{bin})
	}()
	go func() {
		done <- hookclient.Run(ctx, hookclient.Options{API: apiAddr, Token: "tok", Name: "watcher", Mode: "observe"}, []string{bin})
	}()
	for i := 0; i < 200 && strings.Count(e.svc.HooksText(), "since") < 2; i++ {
		time.Sleep(20 * time.Millisecond)
	}
	if strings.Count(e.svc.HooksText(), "since") < 2 {
		t.Fatalf("hooks did not connect: %s", e.svc.HooksText())
	}
	c := e.client(true)
	resp, body := get(t, c, srv.URL+"/config")
	mu.Lock()
	h := hookHeader
	mu.Unlock()
	if h != "example" {
		t.Fatalf("modify hook header: %q", h)
	}
	if body != `{"debug":true,"features":{"new_ui":false},"items":[1,2,3]}` || resp.StatusCode != 200 {
		t.Fatalf("modify hook response: %s", body)
	}
	get(t, c, srv.URL+"/fail")
	var out string
	for i := 0; i < 100; i++ {
		if out = call(t, e, "net_findings", nil); strings.Contains(out, "server error 500") {
			break
		}
		time.Sleep(30 * time.Millisecond)
	}
	if !strings.Contains(out, "server error 500 on GET /fail") || !strings.Contains(out, "hook:watcher") {
		t.Fatalf("observe hook finding: %s", out)
	}
	list := call(t, e, "net_script", nil)
	if !strings.Contains(list, "example · modify") || !strings.Contains(list, "example hook ready") {
		t.Fatalf("hooks in the list:\n%s", list)
	}
	fs := e.flows(t, 2)
	if !strings.Contains(strings.Join(fs[0].Flags, ","), "hook:example") || !fs[0].HasTag("hook-debug") {
		t.Fatalf("hook flags/tags: %v %v", fs[0].Flags, fs[0].Tags)
	}
	cancel()
	<-done
	<-done
	for i := 0; i < 100 && e.svc.HooksText() != ""; i++ {
		time.Sleep(20 * time.Millisecond)
	}
	if e.svc.HooksText() != "" {
		t.Fatal("hooks should go away when their command ends")
	}
	// with no hook the traffic goes on as before
	if _, body := get(t, c, srv.URL+"/config"); !strings.Contains(body, `"debug":false`) {
		t.Fatalf("after the hook: %s", body)
	}
}
