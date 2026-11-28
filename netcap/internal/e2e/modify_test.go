package e2e

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func call(t *testing.T, e *env, tool string, args map[string]any) string {
	t.Helper()
	out, err := e.mcp.Call(context.Background(), tool, args)
	if err != nil {
		t.Fatalf("%s: %v", tool, err)
	}
	return out
}

func addRule(t *testing.T, e *env, rule string) {
	t.Helper()
	var r map[string]any
	if err := json.Unmarshal([]byte(rule), &r); err != nil {
		t.Fatal(err)
	}
	out := call(t, e, "net_rules", map[string]any{"action": "add", "rule": r})
	if !strings.Contains(out, "added") {
		t.Fatalf("add rule: %s", out)
	}
}

func get(t *testing.T, c *http.Client, url string) (*http.Response, string) {
	t.Helper()
	resp, err := c.Get(url)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	return resp, string(b)
}

func TestRules(t *testing.T) {
	e := setup(t)
	var lastAuth, lastQuery, lastBody string
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastAuth, lastQuery = r.Header.Get("Authorization"), r.URL.RawQuery
		b, _ := io.ReadAll(r.Body)
		lastBody = string(b)
		switch r.URL.Path {
		case "/config":
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Content-Encoding", "gzip")
			gz := gzip.NewWriter(w)
			io.WriteString(gz, `{"features":{"new_ui":false,"beta":true},"version":3}`)
			gz.Close()
		case "/login":
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, `{"token":"tok-123","user":{"id":7}}`)
		default:
			fmt.Fprintf(w, "real %s", r.URL.Path)
		}
	}))
	defer srv.Close()
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "dev server got %s?%s", r.URL.Path, r.URL.RawQuery)
	}))
	defer other.Close()
	c := e.client(true)
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "local.json"), []byte(`{"from":"file"}`), 0o644)

	addRule(t, e, `{"match":"path:/mock","action":{"type":"mock","status":201,"json":{"mocked":true,"user":"{{user}}"}}}`)
	addRule(t, e, `{"match":"path:/config","phase":"response","action":{"type":"rewrite","json_set":{".features.new_ui":true},"json_delete":[".features.beta"],"set_headers":{"X-Changed":"yes"}}}`)
	addRule(t, e, `{"match":"path:/blocked","action":{"type":"block","status":403}}`)
	addRule(t, e, `{"match":"path:/reset","action":{"type":"block"}}`)
	addRule(t, e, `{"match":"path:/local","action":{"type":"map_local","file":"`+filepath.Join(dir, "local.json")+`"}}`)
	addRule(t, e, `{"match":"path:/api/*","action":{"type":"map_remote","url":"`+other.URL+`/v2"}}`)
	addRule(t, e, `{"match":"path:/login","action":{"type":"set_var","var":"token","from":"res.json:.token"}}`)
	addRule(t, e, `{"match":"path:/me","action":{"type":"rewrite","set_headers":{"Authorization":"Bearer {{token}}"},"set_query":{"debug":"1"}}}`)
	addRule(t, e, `{"match":"path:/slow","action":{"type":"delay","ms":300}}`)
	call(t, e, "net_rules", map[string]any{"action": "set_var", "var": "user", "value": "ana"})

	resp, body := get(t, c, srv.URL+"/mock")
	if resp.StatusCode != 201 || body != `{"mocked":true,"user":"ana"}` {
		t.Fatalf("mock: %d %s", resp.StatusCode, body)
	}
	resp, body = get(t, c, srv.URL+"/config")
	if resp.Header.Get("X-Changed") != "yes" || body != `{"features":{"new_ui":true},"version":3}` {
		t.Fatalf("rewrite json (gzip from the server): %v %s", resp.Header, body)
	}
	resp, body = get(t, c, srv.URL+"/blocked")
	if resp.StatusCode != 403 || !strings.Contains(body, "blocked by rule") {
		t.Fatalf("block status: %d %s", resp.StatusCode, body)
	}
	if _, err := c.Get(srv.URL + "/reset"); err == nil {
		t.Fatal("block without status should reset the stream")
	}
	_, body = get(t, c, srv.URL+"/local")
	if body != `{"from":"file"}` {
		t.Fatalf("map_local: %s", body)
	}
	_, body = get(t, c, srv.URL+"/api/items?x=1")
	if body != "dev server got /v2/api/items?x=1" {
		t.Fatalf("map_remote: %s", body)
	}
	get(t, c, srv.URL+"/login")
	time.Sleep(50 * time.Millisecond)
	get(t, c, srv.URL+"/me?a=b")
	if lastAuth != "Bearer tok-123" || !strings.Contains(lastQuery, "debug=1") || !strings.Contains(lastQuery, "a=b") {
		t.Fatalf("set_var + {{token}}: auth=%q query=%q", lastAuth, lastQuery)
	}
	t0 := time.Now()
	get(t, c, srv.URL+"/slow")
	if time.Since(t0) < 300*time.Millisecond {
		t.Fatal("delay rule did not wait")
	}
	e.flows(t, 9)
	list := call(t, e, "net_rules", nil)
	if !strings.Contains(list, "{{token}} = [hidden") {
		t.Fatalf("a token variable should be hidden from the AI:\n%s", list)
	}
	mocked := call(t, e, "net_list", map[string]any{"filter": "flag:mocked"})
	if !strings.Contains(mocked, "/mock") || !strings.Contains(mocked, "/local") {
		t.Fatalf("mocked flows should carry the flag:\n%s", mocked)
	}
	test := call(t, e, "net_rules", map[string]any{"action": "test", "rule": map[string]any{"match": "path:/config", "action": map[string]any{"type": "tag", "tag": "x"}}})
	if !strings.Contains(test, "matches 1") {
		t.Fatalf("rule test: %s", test)
	}
	// a POST body rewrite in the request phase
	addRule(t, e, `{"match":"path:/submit","action":{"type":"rewrite","json_set":{".device.os":"android"},"replace":[{"find":"hunter2","with":"changed"}]}}`)
	r2, err := c.Post(srv.URL+"/submit", "application/json", strings.NewReader(`{"password":"hunter2","device":{}}`))
	if err != nil {
		t.Fatal(err)
	}
	r2.Body.Close()
	if lastBody != `{"device":{"os":"android"},"password":"changed"}` {
		t.Fatalf("request body rewrite: %s", lastBody)
	}
}

func TestBreakpointAndSend(t *testing.T) {
	e := setup(t)
	var got string
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		got = r.Method + " " + r.URL.RequestURI() + " " + string(b) + " " + r.Header.Get("X-Test")
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"echo":%q,"n":1}`, string(b))
	}))
	defer srv.Close()
	c := e.client(true)
	addRule(t, e, `{"match":"path:/edit","action":{"type":"breakpoint","timeout_s":10}}`)
	done := make(chan string, 1)
	go func() {
		resp, err := c.Post(srv.URL+"/edit?a=1", "application/json", strings.NewReader(`{"amount":5}`))
		if err != nil {
			done <- "error " + err.Error()
			return
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		done <- string(b)
	}()
	var list string
	for i := 0; i < 100; i++ {
		list = call(t, e, "net_intercept", nil)
		if strings.HasPrefix(list, "x") {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !strings.HasPrefix(list, "x1 request POST") {
		t.Fatalf("paused list: %s", list)
	}
	detail := call(t, e, "net_intercept", map[string]any{"action": "get", "id": "x1"})
	if !strings.Contains(detail, `"amount": 5`) {
		t.Fatalf("paused detail: %s", detail)
	}
	call(t, e, "net_intercept", map[string]any{"action": "resume", "id": "x1", "json_set": map[string]any{".amount": 500}, "set_headers": map[string]any{"X-Test": "edited"}})
	res := <-done
	if got != `POST /edit?a=1 {"amount":500} edited` || !strings.Contains(res, `\"amount\":500`) {
		t.Fatalf("breakpoint edit: server got %q, app got %q", got, res)
	}
	// respond instead of the server
	go func() {
		resp, err := c.Get(srv.URL + "/edit")
		if err != nil {
			done <- "error " + err.Error()
			return
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		done <- fmt.Sprintf("%d %s", resp.StatusCode, b)
	}()
	for i := 0; i < 100 && !strings.HasPrefix(call(t, e, "net_intercept", nil), "x2"); i++ {
		time.Sleep(20 * time.Millisecond)
	}
	call(t, e, "net_intercept", map[string]any{"action": "respond", "id": "x2", "status": 418, "json": map[string]any{"teapot": true}})
	if res := <-done; res != `418 {"teapot":true}` {
		t.Fatalf("respond: %s", res)
	}
	call(t, e, "net_rules", map[string]any{"action": "disable", "id": "r1"})
	fs := e.flows(t, 2)
	// send the first one again with a change
	out := call(t, e, "net_send", map[string]any{"from": fmt.Sprintf("f%d", fs[0].ID), "json_set": map[string]any{".amount": 7}, "query": map[string]any{"a": "2"}})
	if !strings.Contains(out, "resent from") || got != `POST /edit?a=2 {"amount":7} edited` {
		t.Fatalf("send: server got %q\n%s", got, out)
	}
	raw := "GET /raw?q=1 HTTP/1.1\nHost: " + strings.TrimPrefix(srv.URL, "https://") + "\nX-Test: raw\n\n"
	out = call(t, e, "net_send", map[string]any{"raw": raw})
	if got != "GET /raw?q=1  raw" || !strings.Contains(out, "→ 200") {
		t.Fatalf("raw send: %q\n%s", got, out)
	}
}
