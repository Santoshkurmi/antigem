package e2e

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/websocket"

	"antigem/netcap/internal/httpapi"
	"antigem/netcap/internal/rules"
	"antigem/netcap/internal/service"
)

// ---- helpers: the GUI's JSON API over real HTTP ----

type uiEnv struct {
	*env
	base string
}

const uiToken = "ui-token"

func setupUI(t *testing.T) *uiEnv {
	t.Helper()
	e := setup(t)
	api := &httpapi.API{Svc: e.svc, MCP: e.mcp, Token: uiToken}
	srv := httptest.NewServer(api.Handler())
	t.Cleanup(srv.Close)
	return &uiEnv{env: e, base: srv.URL}
}

// do calls a route and decodes the JSON answer into out (when given); returns the status.
func (u *uiEnv) do(t *testing.T, method, path string, in, out any) int {
	t.Helper()
	var body io.Reader
	if in != nil {
		b, _ := json.Marshal(in)
		body = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, u.base+path, body)
	req.Header.Set("X-Netcap-Token", uiToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			t.Fatalf("%s %s: %d %s: %v", method, path, resp.StatusCode, data, err)
		}
	}
	return resp.StatusCode
}

func (u *uiEnv) get(t *testing.T, path string, out any) {
	t.Helper()
	if code := u.do(t, "GET", path, nil, out); code != 200 {
		t.Fatalf("GET %s: %d %+v", path, code, out)
	}
}

func (u *uiEnv) post(t *testing.T, path string, in, out any) {
	t.Helper()
	if code := u.do(t, "POST", path, in, out); code != 200 {
		t.Fatalf("POST %s: %d %+v", path, code, out)
	}
}

func (u *uiEnv) raw(t *testing.T, path string) []byte {
	t.Helper()
	req, _ := http.NewRequest("GET", u.base+path, nil)
	req.Header.Set("X-Netcap-Token", uiToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		t.Fatalf("GET %s: %d %s", path, resp.StatusCode, b)
	}
	return b
}

type sseEvent struct {
	name string
	data []byte
}

// live opens /v1/ui/live and returns its events (the first one, the state, already read).
func (u *uiEnv) live(t *testing.T, query string) (<-chan sseEvent, service.UIState) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, "GET", u.base+"/v1/ui/live?"+query, nil)
	req.Header.Set("X-Netcap-Token", uiToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("live: %d %s", resp.StatusCode, b)
	}
	t.Cleanup(func() { cancel(); resp.Body.Close() })
	ch := make(chan sseEvent, 10000)
	go func() {
		defer close(ch)
		sc := bufio.NewScanner(resp.Body)
		sc.Buffer(make([]byte, 1<<20), 64<<20)
		var ev sseEvent
		for sc.Scan() {
			line := sc.Text()
			switch {
			case strings.HasPrefix(line, "event: "):
				ev.name = line[len("event: "):]
			case strings.HasPrefix(line, "data: "):
				ev.data = []byte(line[len("data: "):])
			case line == "":
				if ev.name != "" {
					ch <- ev
				}
				ev = sseEvent{}
			}
		}
	}()
	first := <-ch
	if first.name != "state" {
		t.Fatalf("first live event %q", first.name)
	}
	var st service.UIState
	if err := json.Unmarshal(first.data, &st); err != nil {
		t.Fatal(err)
	}
	return ch, st
}

// waitBatch reads batches until ok returns true (every batch is checked: one row per id).
func waitBatch(t *testing.T, ch <-chan sseEvent, what string, ok func(b service.LiveBatch) bool) service.LiveBatch {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case ev, open := <-ch:
			if !open {
				t.Fatalf("live stream closed waiting for %s", what)
			}
			if ev.name != "batch" {
				continue
			}
			var b service.LiveBatch
			if err := json.Unmarshal(ev.data, &b); err != nil {
				t.Fatal(err)
			}
			seen := map[int64]bool{}
			for _, r := range b.Rows {
				if seen[r.ID] {
					t.Fatalf("batch %d has f%d twice (rows must be coalesced)", b.Seq, r.ID)
				}
				seen[r.ID] = true
			}
			if ok(b) {
				return b
			}
		case <-deadline:
			t.Fatalf("no live batch with %s", what)
		}
	}
}

func rowOf(b service.LiveBatch, pred func(service.UIRow) bool) *service.UIRow {
	for i := range b.Rows {
		if pred(b.Rows[i]) {
			return &b.Rows[i]
		}
	}
	return nil
}

// ---- tests ----

func TestUIRowsAndLive(t *testing.T) {
	u := setupUI(t)
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/slow" {
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			time.Sleep(600 * time.Millisecond)
		}
		fmt.Fprintf(w, `{"path":%q}`, r.URL.Path)
	}))
	defer srv.Close()
	all, st := u.live(t, "")
	if !st.Capture.On || st.Session.ID != "s1" || st.EngineVersion == "" {
		t.Fatalf("state: %+v", st)
	}
	onlySlow, _ := u.live(t, "q="+url.QueryEscape("path:/slow"))
	c := u.client(false)
	done := make(chan struct{})
	go func() {
		get(t, c, srv.URL+"/slow")
		close(done)
	}()
	b := waitBatch(t, all, "a running /slow", func(b service.LiveBatch) bool {
		return rowOf(b, func(r service.UIRow) bool { return r.Path == "/slow" && !r.Done }) != nil
	})
	r := rowOf(b, func(r service.UIRow) bool { return r.Path == "/slow" })
	if r.Method != "GET" || r.Host != "127.0.0.1" || r.Start == 0 || r.Kind != "http" {
		t.Fatalf("running row: %+v", r)
	}
	<-done
	get(t, c, srv.URL+"/fast")
	waitBatch(t, all, "a finished /slow", func(b service.LiveBatch) bool {
		return rowOf(b, func(r service.UIRow) bool { return r.Path == "/slow" && r.Done && r.Status == 200 && r.Ms >= 500 }) != nil
	})
	waitBatch(t, onlySlow, "the finished /slow on the filtered stream", func(b service.LiveBatch) bool {
		if rowOf(b, func(r service.UIRow) bool { return r.Path != "/slow" }) != nil {
			t.Fatalf("filtered stream got another flow: %+v", b.Rows)
		}
		return rowOf(b, func(r service.UIRow) bool { return r.Done }) != nil
	})
	u.flows(t, 2)

	var rows service.UIRowsResult
	u.get(t, "/v1/ui/rows", &rows)
	if rows.Total != 2 || len(rows.Rows) != 2 || rows.Rows[0].Path != "/fast" || rows.Session != "s1" {
		t.Fatalf("rows: %+v", rows)
	}
	u.get(t, "/v1/ui/rows?limit=1&sort=oldest", &rows)
	if len(rows.Rows) != 1 || rows.Rows[0].Path != "/slow" || rows.Next == "" {
		t.Fatalf("rows page: %+v", rows)
	}
	u.get(t, "/v1/ui/rows?sort=oldest&cursor="+rows.Next, &rows)
	if len(rows.Rows) != 1 || rows.Rows[0].Path != "/fast" {
		t.Fatalf("rows next page: %+v", rows)
	}
	for q, want := range map[string]int{"path:/slow": 1, "slow": 1, "status:200": 2, "-host:127.0.0.1": 0} {
		u.get(t, "/v1/ui/rows?q="+url.QueryEscape(q), &rows)
		if rows.Total != want || len(rows.Rows) != want {
			t.Fatalf("rows q=%s: %+v", q, rows)
		}
	}
	var groups struct{ Groups []service.UIGroup }
	u.get(t, "/v1/ui/groups?by=host", &groups)
	if len(groups.Groups) != 1 || groups.Groups[0].Count != 2 || groups.Groups[0].Statuses["200"] != 2 || groups.Groups[0].MaxMs < 500 {
		t.Fatalf("groups: %+v", groups)
	}
	var e struct{ Error string }
	if code := u.do(t, "GET", "/v1/ui/rows?q="+url.QueryEscape("nosuch:x"), nil, &e); code != 400 || e.Error == "" {
		t.Fatalf("bad filter: %d %+v", code, e)
	}
	if code := u.do(t, "GET", "/v1/ui/rows?session=s99", nil, &e); code != 404 {
		t.Fatalf("missing session: %d %+v", code, e)
	}
	// the capture pause shows in the state
	u.post(t, "/v1/ui/capture/pause", map[string]any{"on": true}, nil)
	waitBatch(t, all, "the paused capture", func(b service.LiveBatch) bool { return b.State != nil && b.State.Capture.Paused })
	u.post(t, "/v1/ui/capture/pause", map[string]any{"on": false}, nil)
}

// bigJSON is about 3 MB of minified JSON on one line.
func bigJSON() []byte {
	var b bytes.Buffer
	b.WriteString(`{"items":[`)
	for i := 0; i < 35000; i++ {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, `{"id":%d,"name":"item-%d","tags":["a","b"],"nested":{"x":%d,"y":"zz"}}`, i, i, i)
	}
	b.WriteString(`]}`)
	return b.Bytes()
}

func TestUIBodyViews(t *testing.T) {
	u := setupUI(t)
	body := bigJSON()
	png := []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR")
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/img" {
			w.Header().Set("Content-Type", "image/png")
			w.Write(png)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Encoding", "gzip")
		gz := gzip.NewWriter(w)
		gz.Write(body)
		gz.Close()
	}))
	defer srv.Close()
	c := u.client(true)
	resp, err := c.Get(srv.URL + "/big")
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	get(t, c, srv.URL+"/img")
	u.flows(t, 2)
	var indented bytes.Buffer
	json.Indent(&indented, body, "", "  ")
	pretty := strings.Split(indented.String(), "\n")
	wantLines := (len(body) + 1999) / 2000

	var detail service.UIFlowDetail
	u.get(t, "/v1/ui/flow?id=f1", &detail)
	rb := detail.ResBody
	if !rb.Present || rb.DecodedSize != int64(len(body)) || rb.Encoding != "gzip" || rb.Type != "json" || !rb.Text || rb.Image ||
		rb.Lines != wantLines || rb.PrettyLines != len(pretty) || detail.Flow.ID != 1 || detail.ReqBody.Present || detail.ReqBody.Lines != -1 {
		t.Fatalf("flow detail: %+v (want %d lines, %d pretty)", detail, wantLines, len(pretty))
	}
	var img service.UIFlowDetail
	u.get(t, "/v1/ui/flow?id=f2", &img)
	if !img.ResBody.Image || img.ResBody.Text || img.ResBody.Lines != -1 || img.ResBody.PrettyLines != -1 {
		t.Fatalf("image detail: %+v", img.ResBody)
	}

	// one 3 MB line is split into 2000-character display lines
	var page service.UILines
	u.get(t, "/v1/ui/body?id=f1&view=lines&from=0&count=3", &page)
	if page.Total != wantLines || !page.Wrapped || len(page.Lines) != 3 || len(page.Lines[0]) != 2000 ||
		strings.Join(page.Lines, "") != string(body[:6000]) || fmt.Sprint(page.Cont) != "[1 2]" {
		t.Fatalf("lines page: total %d wrapped %v cont %v", page.Total, page.Wrapped, page.Cont)
	}
	u.get(t, fmt.Sprintf("/v1/ui/body?id=f1&from=%d&count=10", wantLines-1), &page)
	if len(page.Lines) != 1 || page.Lines[0] != string(body[(wantLines-1)*2000:]) {
		t.Fatalf("last line: %q", page.Lines)
	}
	u.get(t, "/v1/ui/body?id=f1&view=lines&pretty=1&from=100&count=50", &page)
	if page.Total != len(pretty) || page.Wrapped || strings.Join(page.Lines, "\n") != strings.Join(pretty[100:150], "\n") {
		t.Fatalf("pretty page: total %d %q", page.Total, page.Lines[:3])
	}
	u.get(t, fmt.Sprintf("/v1/ui/body?id=f1&pretty=1&from=%d&count=5", len(pretty)-2), &page)
	if strings.Join(page.Lines, "\n") != strings.Join(pretty[len(pretty)-2:], "\n") {
		t.Fatalf("pretty end: %q", page.Lines)
	}

	// search: line and column in the same display lines
	var found service.UISearch
	idx := bytes.Index(body, []byte("item-34999"))
	u.get(t, "/v1/ui/body?id=f1&view=search&find=item-34999", &found)
	if found.Total != 1 || found.Matches[0] != (service.UIMatch{Line: idx / 2000, Col: idx % 2000, Len: 10}) {
		t.Fatalf("search: %+v (at %d)", found, idx)
	}
	across := string(body[3990:4010]) // crosses the split between display lines 1 and 2
	u.get(t, "/v1/ui/body?id=f1&view=search&case=1&find="+url.QueryEscape(across), &found)
	hasAcross := false
	for _, m := range found.Matches {
		hasAcross = hasAcross || m == (service.UIMatch{Line: 1, Col: 1990, Len: 20})
	}
	if !hasAcross {
		t.Fatalf("search across a split: %d matches", found.Total)
	}
	u.get(t, "/v1/ui/body?id=f1&view=search&regex=1&find="+url.QueryEscape(`"id":3499\d,`), &found)
	if found.Total != 10 || found.Cut {
		t.Fatalf("regex search: %+v", found)
	}
	u.get(t, "/v1/ui/body?id=f1&view=search&find=ITEM-34999", &found)
	if found.Total != 1 {
		t.Fatalf("search ignoring case: %+v", found)
	}
	u.get(t, "/v1/ui/body?id=f1&view=search&case=1&find=ITEM-34999", &found)
	if found.Total != 0 {
		t.Fatalf("search with case: %+v", found)
	}
	u.get(t, "/v1/ui/body?id=f1&view=search&pretty=1&find=item-34999", &found)
	m := found.Matches[0]
	if found.Total != 1 || !strings.Contains(pretty[m.Line], "item-34999") || pretty[m.Line][m.Col:m.Col+m.Len] != "item-34999" {
		t.Fatalf("pretty search: %+v", found)
	}
	u.get(t, "/v1/ui/body?id=f1&view=search&find=item-&max=5", &found)
	if found.Total != 35000 || len(found.Matches) != 5 || !found.Cut {
		t.Fatalf("search max: total %d, %d matches, cut %v", found.Total, len(found.Matches), found.Cut)
	}

	// tree
	var tree service.UITree
	u.get(t, "/v1/ui/body?id=f1&view=tree", &tree)
	if tree.Kind != "object" || tree.Count != 1 || tree.Children[0].Key != "items" || tree.Children[0].Kind != "array" ||
		tree.Children[0].Count != 35000 || tree.Children[0].Path != ".items" {
		t.Fatalf("tree root: %+v", tree)
	}
	u.get(t, "/v1/ui/body?id=f1&view=tree&path=.items&from=34998&count=5", &tree)
	if tree.Count != 35000 || len(tree.Children) != 2 || tree.Children[1].Key != "[34999]" || tree.Children[1].Path != ".items[34999]" ||
		tree.Children[1].Count != 4 || !strings.HasPrefix(tree.Children[1].Preview, `{"id":34999,"name":"item-34999"`) {
		t.Fatalf("tree items: %+v", tree)
	}
	u.get(t, "/v1/ui/body?id=f1&view=tree&path="+url.QueryEscape(".items[34999]"), &tree)
	if len(tree.Children) != 4 || tree.Children[1].Key != "name" || tree.Children[1].Preview != "item-34999" || tree.Children[1].Kind != "string" ||
		tree.Children[3].Path != ".items[34999].nested" || tree.Children[0].Preview != "34999" {
		t.Fatalf("tree item: %+v", tree)
	}
	u.get(t, "/v1/ui/body?id=f1&view=tree&path="+url.QueryEscape(".items[7].tags"), &tree)
	if tree.Kind != "array" || tree.Count != 2 || tree.Children[1].Preview != "b" {
		t.Fatalf("tree tags: %+v", tree)
	}

	// hex, file, raw
	var hx service.UIHex
	u.get(t, "/v1/ui/body?id=f1&view=hex&offset=10&length=16", &hx)
	if d, _ := base64.StdEncoding.DecodeString(hx.DataBase64); hx.Size != int64(len(body)) || hx.Offset != 10 || string(d) != string(body[10:26]) {
		t.Fatalf("hex: %+v", hx)
	}
	var file struct {
		Path string
		Size int64
	}
	u.get(t, "/v1/ui/body?id=f1&view=file", &file)
	if b, err := os.ReadFile(file.Path); err != nil || !bytes.Equal(b, body) || !strings.HasSuffix(file.Path, filepath.Join("out", "ui", "f1.res.json")) {
		t.Fatalf("file view: %+v %v", file, err)
	}
	u.get(t, "/v1/ui/body?id=f1&view=file&pretty=1", &file)
	if b, err := os.ReadFile(file.Path); err != nil || strings.TrimSpace(string(b)) != strings.TrimSpace(indented.String()) {
		t.Fatalf("pretty file view: %+v %v", file, err)
	}
	u.get(t, "/v1/ui/body?id=f2&view=file", &file)
	if !strings.HasSuffix(file.Path, "f2.res.png") || file.Size != int64(len(png)) {
		t.Fatalf("image file: %+v", file)
	}
	if b := u.raw(t, "/v1/ui/body?id=f1&view=raw"); !bytes.Equal(b, body) {
		t.Fatalf("raw view: %d bytes", len(b))
	}
	var e struct{ Error string }
	if code := u.do(t, "GET", "/v1/ui/body?id=f2&view=tree", nil, &e); code != 400 || !strings.Contains(e.Error, "not one valid JSON") {
		t.Fatalf("tree of an image: %d %+v", code, e)
	}
}

func TestUIMarkDelete(t *testing.T) {
	u := setupUI(t)
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/slow" {
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			time.Sleep(500 * time.Millisecond)
		}
		io.WriteString(w, "ok")
	}))
	defer srv.Close()
	c := u.client(false)
	for _, p := range []string{"/a", "/b", "/c"} {
		get(t, c, srv.URL+p)
	}
	u.flows(t, 3)
	ch, _ := u.live(t, "")
	u.post(t, "/v1/ui/mark", map[string]any{"ids": []any{1, "f2"}, "pinned": true, "color": "red"}, nil)
	waitBatch(t, ch, "the marked rows", func(b service.LiveBatch) bool {
		return len(b.Rows) == 2 && b.Rows[0].Pinned && b.Rows[1].Color == "red"
	})
	u.post(t, "/v1/ui/mark", map[string]any{"ids": []any{1}, "note": "hello"}, nil)
	b := waitBatch(t, ch, "the note", func(b service.LiveBatch) bool { return len(b.Rows) == 1 })
	if r := b.Rows[0]; r.Note != "hello" || !r.Pinned || r.Color != "red" {
		t.Fatalf("only the note should change: %+v", r)
	}
	var rows service.UIRowsResult
	for q, want := range map[string]int{"pinned:true": 2, "color:red": 2, "-pinned:true": 1, "color:blue": 0, "note:hello": 1} {
		u.get(t, "/v1/ui/rows?q="+url.QueryEscape(q), &rows)
		if rows.Total != want {
			t.Fatalf("q=%s: %+v", q, rows)
		}
	}
	var f struct {
		Pinned bool
		Color  string
	}
	u.get(t, "/v1/flows/f2", &f)
	if !f.Pinned || f.Color != "red" {
		t.Fatalf("stored flow: %+v", f)
	}
	// a running flow keeps its mark when the engine writes it again
	done := make(chan struct{})
	go func() {
		get(t, c, srv.URL+"/slow")
		close(done)
	}()
	waitBatch(t, ch, "the running flow", func(b service.LiveBatch) bool {
		return rowOf(b, func(r service.UIRow) bool { return r.ID == 4 }) != nil
	})
	u.post(t, "/v1/ui/mark", map[string]any{"ids": []any{4}, "pinned": true}, nil)
	<-done
	waitBatch(t, ch, "the finished, still pinned flow", func(b service.LiveBatch) bool {
		r := rowOf(b, func(r service.UIRow) bool { return r.ID == 4 && r.Done })
		if r != nil && !r.Pinned {
			t.Fatalf("the engine's last write lost the pin: %+v", r)
		}
		return r != nil
	})
	var del struct{ Deleted int64 }
	u.post(t, "/v1/ui/delete", map[string]any{"ids": []any{3}}, &del)
	if del.Deleted != 1 {
		t.Fatalf("delete: %+v", del)
	}
	waitBatch(t, ch, "removed f3", func(b service.LiveBatch) bool { return fmt.Sprint(b.Removed) == "[3]" })
	u.get(t, "/v1/ui/rows", &rows)
	if rows.Total != 3 {
		t.Fatalf("after delete: %+v", rows)
	}
	u.post(t, "/v1/ui/delete", map[string]any{"all": true}, &del)
	if del.Deleted != 3 {
		t.Fatalf("delete all: %+v", del)
	}
	waitBatch(t, ch, "gap after clearing", func(b service.LiveBatch) bool { return b.Gap })
	u.get(t, "/v1/ui/rows", &rows)
	if rows.Total != 0 || len(rows.Rows) != 0 {
		t.Fatalf("after clear: %+v", rows)
	}
	get(t, c, srv.URL+"/after")
	u.get(t, "/v1/ui/rows", &rows)
	if len(rows.Rows) != 1 || rows.Rows[0].ID != 5 {
		t.Fatalf("new flows after clear: %+v", rows)
	}
}

type echoed struct {
	Method, URI, Body string
	Header            http.Header
}

func uiEchoServer(t *testing.T) (*httptest.Server, func() []echoed) {
	var mu sync.Mutex
	var got []echoed
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		got = append(got, echoed{r.Method, r.URL.RequestURI(), string(b), r.Header.Clone()})
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"len":%d}`, len(b))
	}))
	t.Cleanup(srv.Close)
	return srv, func() []echoed {
		mu.Lock()
		defer mu.Unlock()
		return append([]echoed(nil), got...)
	}
}

func TestUISend(t *testing.T) {
	u := setupUI(t)
	srv, seen := uiEchoServer(t)
	var res service.UISendResult
	u.post(t, "/v1/ui/send", map[string]any{"method": "post", "url": srv.URL + "/echo?x=1",
		"headers": [][2]string{{"Content-Type", "text/plain"}, {"X-A", "1"}, {"X-A", "2"}}, "body_text": "hello"}, &res)
	last := func() echoed { s := seen(); return s[len(s)-1] }
	if len(res.IDs) != 1 || len(res.Errors) != 0 {
		t.Fatalf("fresh send: %+v", res)
	}
	if g := last(); g.Method != "POST" || g.URI != "/echo?x=1" || g.Body != "hello" || fmt.Sprint(g.Header["X-A"]) != "[1 2]" {
		t.Fatalf("server got %+v", g)
	}
	first := res.IDs[0]
	// from alone = the same request again; from as a number works too
	u.post(t, "/v1/ui/send", map[string]any{"from": first}, &res)
	if g := last(); len(res.IDs) != 1 || g.Body != "hello" || fmt.Sprint(g.Header["X-A"]) != "[1 2]" || g.URI != "/echo?x=1" {
		t.Fatalf("resend: %+v %+v", res, g)
	}
	var row service.UIFlowDetail
	u.get(t, fmt.Sprintf("/v1/ui/flow?id=f%d", res.IDs[0]), &row)
	if row.Flow.ResentFrom != first || !strings.Contains(strings.Join(row.Flow.Flags, ","), "sent") {
		t.Fatalf("resent flow: %+v", row.Flow)
	}
	u.post(t, "/v1/ui/send", map[string]any{"from": fmt.Sprintf("f%d", first), "repeat": map[string]any{"count": 3, "interval_ms": 10, "concurrency": 2}}, &res)
	if len(res.IDs) != 3 || len(res.Errors) != 0 {
		t.Fatalf("repeat: %+v", res)
	}
	// explicit empty headers and body
	u.post(t, "/v1/ui/send", map[string]any{"from": first, "headers": [][2]string{}, "body_text": ""}, &res)
	if g := last(); g.Body != "" || g.Header.Get("X-A") != "" || g.Method != "POST" {
		t.Fatalf("empty headers and body: %+v", g)
	}
	// a body file bigger than the 8 MB edit limit, streamed
	big := bytes.Repeat([]byte("0123456789abcdef"), 9<<20/16)
	path := filepath.Join(t.TempDir(), "big.bin")
	os.WriteFile(path, big, 0o600)
	u.post(t, "/v1/ui/send", map[string]any{"method": "PUT", "url": srv.URL + "/upload", "body_file": path}, &res)
	if g := last(); len(res.Errors) != 0 || g.Method != "PUT" || len(g.Body) != len(big) {
		t.Fatalf("body_file: %+v (server got %d bytes)", res, len(g.Body))
	}
	// the user's sends don't count against the AI's 30 a minute
	u.post(t, "/v1/ui/send", map[string]any{"url": srv.URL + "/many", "repeat": map[string]any{"count": 40, "concurrency": 8}}, &res)
	if len(res.IDs) != 40 || len(res.Errors) != 0 {
		t.Fatalf("40 sends: %d ids, errors %v", len(res.IDs), res.Errors)
	}
	// a failed send is recorded too
	dead := httptest.NewServer(http.NotFoundHandler())
	deadURL := dead.URL
	dead.Close()
	u.post(t, "/v1/ui/send", map[string]any{"url": deadURL + "/x", "timeout_ms": 2000}, &res)
	if len(res.IDs) != 1 || len(res.Errors) != 1 {
		t.Fatalf("failed send: %+v", res)
	}
	var e struct{ Error string }
	if code := u.do(t, "POST", "/v1/ui/send", map[string]any{"url": "ftp://x"}, &e); code != 400 || e.Error == "" {
		t.Fatalf("bad url: %d %+v", code, e)
	}
	// curl and raw text
	var cu struct{ Curl string }
	u.get(t, fmt.Sprintf("/v1/ui/curl?id=f%d", first), &cu)
	if !strings.HasPrefix(cu.Curl, "curl -X POST") || !strings.Contains(cu.Curl, "--data-raw 'hello'") {
		t.Fatalf("curl: %s", cu.Curl)
	}
	var raw struct{ Text string }
	u.get(t, fmt.Sprintf("/v1/ui/raw?id=f%d&side=req", first), &raw)
	if !strings.HasPrefix(raw.Text, "POST /echo?x=1 HTTP/") || !strings.HasSuffix(raw.Text, "\n\nhello") {
		t.Fatalf("raw req: %q", raw.Text)
	}
	u.get(t, fmt.Sprintf("/v1/ui/raw?id=f%d&side=res", first), &raw)
	if !strings.HasPrefix(raw.Text, "HTTP/") || !strings.HasSuffix(raw.Text, `{"len":5}`) {
		t.Fatalf("raw res: %q", raw.Text)
	}
}

func TestUIRulesAndScripts(t *testing.T) {
	u := setupUI(t)
	var saved struct{ Rule *rules.Rule }
	u.post(t, "/v1/ui/rules", map[string]any{"rule": map[string]any{"match": "path:/x", "action": map[string]any{"type": "tag", "tag": "t"}}}, &saved)
	if saved.Rule.ID != "r1" || !saved.Rule.Enabled || saved.Rule.Phase != "response" {
		t.Fatalf("add: %+v", saved.Rule)
	}
	u.post(t, "/v1/ui/rules/enable", map[string]any{"id": "r1", "on": false}, nil)
	u.post(t, "/v1/ui/rules", map[string]any{"rule": map[string]any{"id": "r1", "match": "path:/y", "action": map[string]any{"type": "tag", "tag": "t2"}}}, &saved)
	if saved.Rule.Match != "path:/y" || saved.Rule.Enabled {
		t.Fatalf("an update without enabled keeps the rule off: %+v", saved.Rule)
	}
	u.post(t, "/v1/ui/rules", map[string]any{"rule": map[string]any{"id": "r1", "enabled": true, "match": "path:/y", "action": map[string]any{"type": "tag", "tag": "t2"}}}, &saved)
	var list struct {
		Rules []*rules.Rule
		Vars  map[string]string
	}
	u.get(t, "/v1/ui/rules", &list)
	if len(list.Rules) != 1 || !list.Rules[0].Enabled || list.Rules[0].Action.Tag != "t2" {
		t.Fatalf("list: %+v", list)
	}
	var e struct{ Error string }
	if code := u.do(t, "POST", "/v1/ui/rules", map[string]any{"rule": map[string]any{"match": "x", "action": map[string]any{"type": "nope"}}}, &e); code != 400 || !strings.Contains(e.Error, "action type") {
		t.Fatalf("invalid rule: %d %+v", code, e)
	}
	var test struct{ Text string }
	u.post(t, "/v1/ui/rules/test", map[string]any{"rule": map[string]any{"match": "path:/y", "action": map[string]any{"type": "block"}}}, &test)
	if !strings.Contains(test.Text, "matches 0") {
		t.Fatalf("test: %+v", test)
	}
	var vars struct{ Vars map[string]string }
	u.post(t, "/v1/ui/vars", map[string]any{"name": "token", "value": "abc"}, &vars)
	if vars.Vars["token"] != "abc" {
		t.Fatalf("vars: %+v", vars)
	}
	u.get(t, "/v1/ui/rules", &list)
	if list.Vars["token"] != "abc" {
		t.Fatalf("the GUI sees variables unhidden: %+v", list.Vars)
	}
	var after struct{ Vars map[string]string }
	u.post(t, "/v1/ui/vars", map[string]any{"name": "token", "delete": true}, &after)
	if _, ok := after.Vars["token"]; ok {
		t.Fatalf("var delete: %+v", after)
	}
	u.post(t, "/v1/ui/rules/remove", map[string]any{"id": "r1"}, nil)
	u.get(t, "/v1/ui/rules", &list)
	if len(list.Rules) != 0 {
		t.Fatalf("remove: %+v", list)
	}

	// scripts
	var made struct{ File, Error string }
	u.post(t, "/v1/ui/scripts/new", map[string]any{"name": "uit", "code": "// @name uit\n// @match host:127.0.0.1\nfunction onComplete(f, ctx) { ctx.log('done ' + f.path) }\ncommand('hi', function () { return 1 })\n"}, &made)
	if made.Error != "" || !strings.HasSuffix(made.File, "uit.js") {
		t.Fatalf("new script: %+v", made)
	}
	if _, err := os.Stat(made.File); err != nil {
		t.Fatal(err)
	}
	var scripts struct{ Scripts []service.UIScript }
	u.get(t, "/v1/ui/scripts", &scripts)
	if len(scripts.Scripts) != 1 || scripts.Scripts[0].Name != "uit" || fmt.Sprint(scripts.Scripts[0].Hooks) != "[complete]" ||
		!scripts.Scripts[0].Enabled || scripts.Scripts[0].Match != "host:127.0.0.1" || fmt.Sprint(scripts.Scripts[0].Commands) != "[hi]" {
		t.Fatalf("scripts: %+v", scripts)
	}
	st := service.UIState{}
	u.get(t, "/v1/ui/state", &st)
	if st.ScriptsOn != 1 {
		t.Fatalf("state: %+v", st)
	}
	u.post(t, "/v1/ui/scripts/enable", map[string]any{"name": "uit", "on": false}, nil)
	u.get(t, "/v1/ui/scripts", &scripts)
	if scripts.Scripts[0].Enabled {
		t.Fatalf("disable: %+v", scripts)
	}
	var logs struct{ Lines []string }
	u.get(t, "/v1/ui/scripts/logs?name=uit", &logs)
	if logs.Lines == nil {
		t.Fatal("logs should be a list")
	}
	var bad struct{ File, Error string }
	u.post(t, "/v1/ui/scripts/new", map[string]any{"name": "broken", "code": "function ("}, &bad)
	if bad.Error == "" {
		t.Fatalf("a syntax error comes back: %+v", bad)
	}
	u.post(t, "/v1/ui/scripts/remove", map[string]any{"name": "uit"}, nil)
	u.get(t, "/v1/ui/scripts", &scripts)
	if len(scripts.Scripts) != 1 || scripts.Scripts[0].Name != "broken" {
		t.Fatalf("after remove: %+v", scripts)
	}
}

// waitPaused polls the paused list until it has n items.
func (u *uiEnv) waitPaused(t *testing.T, n int) []service.UIPaused {
	t.Helper()
	var p struct{ Paused []service.UIPaused }
	for i := 0; i < 200; i++ {
		u.get(t, "/v1/ui/paused", &p)
		if len(p.Paused) == n {
			return p.Paused
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("want %d paused, have %+v", n, p.Paused)
	return nil
}

func TestUIBreakpoints(t *testing.T) {
	u := setupUI(t)
	srv, seen := uiEchoServer(t)
	last := func() echoed { s := seen(); return s[len(s)-1] }
	c := u.client(false)
	ch, _ := u.live(t, "")
	var rule struct{ Rule *rules.Rule }
	u.post(t, "/v1/ui/breaknext", map[string]any{"match": "path:/bp", "phase": "request", "count": 1}, &rule)
	if rule.Rule.Once != 1 || rule.Rule.Action.Type != "breakpoint" || !rule.Rule.Enabled {
		t.Fatalf("breaknext: %+v", rule.Rule)
	}
	answer := make(chan string, 1)
	post := func(path, body string) {
		req, _ := http.NewRequest("POST", srv.URL+path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Old", "1")
		resp, err := c.Do(req)
		if err != nil {
			answer <- "error " + err.Error()
			return
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		answer <- fmt.Sprintf("%d %s", resp.StatusCode, b)
	}
	go post("/bp", `{"a":1}`)
	p := u.waitPaused(t, 1)[0]
	if p.ID != "x1" || p.Phase != "request" || p.Method != "POST" || p.BodyText == nil || *p.BodyText != `{"a":1}` || p.BodySize != 7 ||
		p.TimeoutS != 30 || p.RemainingMs > 30000 || p.RemainingMs < 20000 || p.Session != "s1" {
		t.Fatalf("paused: %+v", p)
	}
	b := waitBatch(t, ch, "the paused list and row", func(b service.LiveBatch) bool {
		return b.Paused != nil && len(*b.Paused) == 1 && rowOf(b, func(r service.UIRow) bool { return r.Paused == "x1" }) != nil
	})
	_ = b
	var ext struct {
		RemainingMs int64 `json:"remaining_ms"`
	}
	u.post(t, "/v1/ui/paused/extend", map[string]any{"id": "x1", "seconds": 60}, &ext)
	if ext.RemainingMs < 80000 {
		t.Fatalf("extend: %+v", ext)
	}
	if p = u.waitPaused(t, 1)[0]; p.TimeoutS != 90 {
		t.Fatalf("after extend: %+v", p)
	}
	u.post(t, "/v1/ui/paused/resolve", map[string]any{"id": "x1", "action": "continue", "replace": map[string]any{
		"headers": [][2]string{{"Content-Type", "application/json"}, {"X-New", "yes"}}, "body_text": `{"b":22}`}}, nil)
	if a := <-answer; a != `200 {"len":8}` {
		t.Fatalf("app got %s", a)
	}
	if g := last(); g.Header.Get("X-Old") != "" || g.Header.Get("X-New") != "yes" || g.Body != `{"b":22}` {
		t.Fatalf("server got %+v", g)
	}
	var list struct{ Rules []*rules.Rule }
	u.get(t, "/v1/ui/rules", &list)
	if len(list.Rules) != 0 {
		t.Fatalf("the one-hit breakpoint should be gone: %+v", list.Rules)
	}
	waitBatch(t, ch, "the empty paused list", func(b service.LiveBatch) bool { return b.Paused != nil && len(*b.Paused) == 0 })

	// a body bigger than the 8 MB edit limit, replaced from a file; the app's connection stays usable
	u.post(t, "/v1/ui/breaknext", map[string]any{"match": "path:/big", "count": 1}, nil)
	go post("/big", strings.Repeat("x", 9<<20))
	p = u.waitPaused(t, 1)[0]
	if !p.BodyCut || p.BodyText != nil || p.BodyBase64 != "" {
		t.Fatalf("too big to show: %+v", p)
	}
	file := filepath.Join(t.TempDir(), "new.json")
	os.WriteFile(file, []byte(`{"from":"file"}`), 0o600)
	u.post(t, "/v1/ui/paused/resolve", map[string]any{"id": p.ID, "replace": map[string]any{"body_file": file}}, nil)
	if a := <-answer; a != `200 {"len":15}` || last().Body != `{"from":"file"}` {
		t.Fatalf("big body replaced: app %s, server %q", a, last().Body)
	}
	go post("/after", "z")
	if a := <-answer; a != `200 {"len":1}` {
		t.Fatalf("the next request on the same connection: %s", a)
	}

	// response phase: a new status, headers and body
	u.post(t, "/v1/ui/rules", map[string]any{"rule": map[string]any{"match": "path:/res", "phase": "response", "action": map[string]any{"type": "breakpoint"}}}, nil)
	go post("/res", "q")
	p = u.waitPaused(t, 1)[0]
	if p.Phase != "response" || p.Status != 200 || p.BodyText == nil || *p.BodyText != `{"len":1}` {
		t.Fatalf("paused response: %+v", p)
	}
	if body := u.raw(t, "/v1/ui/paused/body?id="+p.ID); string(body) != `{"len":1}` {
		t.Fatalf("paused body: %s", body)
	}
	u.post(t, "/v1/ui/paused/resolve", map[string]any{"id": p.ID, "replace": map[string]any{"status": 201,
		"headers": [][2]string{{"Content-Type", "text/plain"}, {"X-R", "1"}}, "body_base64": base64.StdEncoding.EncodeToString([]byte("new body"))}}, nil)
	if a := <-answer; a != "201 new body" {
		t.Fatalf("replaced response: %s", a)
	}
	// respond with a whole header list
	pu, _ := url.Parse("http://" + u.proxy)
	tc := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu), TLSClientConfig: &tls.Config{RootCAs: u.ca}}, Timeout: 10 * time.Second}
	got := make(chan *http.Response, 1)
	go func() {
		resp, err := tc.Get(srv.URL + "/res")
		if err != nil {
			t.Error(err)
		}
		got <- resp
	}()
	p = u.waitPaused(t, 1)[0]
	u.post(t, "/v1/ui/paused/resolve", map[string]any{"id": p.ID, "action": "respond", "respond": map[string]any{"status": 418,
		"headers": [][2]string{{"X-T", "a"}, {"X-T", "b"}}, "body_text": "teapot"}}, nil)
	resp := <-got
	rb, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 418 || fmt.Sprint(resp.Header["X-T"]) != "[a b]" || string(rb) != "teapot" {
		t.Fatalf("respond: %d %v %s", resp.StatusCode, resp.Header, rb)
	}
	var e struct{ Error string }
	if code := u.do(t, "POST", "/v1/ui/paused/resolve", map[string]any{"id": "x99"}, &e); code != 404 {
		t.Fatalf("unknown paused item: %d %+v", code, e)
	}
}

func TestUISessionsAndMessages(t *testing.T) {
	u := setupUI(t)
	srv := httptest.NewTLSServer(websocket.Handler(func(ws *websocket.Conn) {
		var msg string
		for websocket.Message.Receive(ws, &msg) == nil {
			websocket.Message.Send(ws, "echo:"+msg)
		}
	}))
	defer srv.Close()
	addr := strings.TrimPrefix(srv.URL, "https://")
	raw := dialConnect(t, u.proxy, addr)
	tc := tls.Client(raw, &tls.Config{RootCAs: u.ca, ServerName: "127.0.0.1"})
	cfg, _ := websocket.NewConfig("wss://"+addr+"/chat", "https://"+addr)
	ws, err := websocket.NewClient(cfg, tc)
	if err != nil {
		t.Fatal(err)
	}
	for _, m := range []string{"one", "two"} {
		websocket.Message.Send(ws, m)
		var got string
		websocket.Message.Receive(ws, &got)
	}
	ws.Close()
	u.flows(t, 1)
	var msgs struct {
		Messages []service.UIMessage
		Total    int
	}
	u.get(t, "/v1/ui/messages?id=f1", &msgs)
	if msgs.Total < 4 || len(msgs.Messages) != msgs.Total || msgs.Messages[0].Text != "one" || msgs.Messages[1].Text != "echo:one" ||
		msgs.Messages[0].Binary || msgs.Messages[0].Dir != "out" {
		t.Fatalf("messages: %+v", msgs)
	}
	var one service.UIMessage
	u.get(t, "/v1/ui/message?id=f1&index=1", &one)
	if one.Text != "echo:one" || one.Dir != "in" {
		t.Fatalf("message: %+v", one)
	}

	ch, _ := u.live(t, "")
	var list []service.SessionInfo
	u.get(t, "/v1/ui/sessions", &list)
	if len(list) != 1 || list[0].ID != "s1" || !list[0].Active || list[0].Flows != 1 {
		t.Fatalf("sessions: %+v", list)
	}
	var made struct {
		OK      bool
		Session service.SessionInfo
	}
	u.post(t, "/v1/ui/sessions", map[string]any{"action": "new", "name": "two"}, &made)
	if !made.OK || made.Session.ID != "s2" || !made.Session.Active || made.Session.Name != "two" {
		t.Fatalf("new session: %+v", made)
	}
	waitBatch(t, ch, "the state of the new session", func(b service.LiveBatch) bool {
		return b.State != nil && b.State.Session.ID == "s2" && b.Session == "s2"
	})
	var rows service.UIRowsResult
	u.get(t, "/v1/ui/rows?session=s1", &rows)
	if rows.Total != 1 || rows.Session != "s1" {
		t.Fatalf("rows of the old session: %+v", rows)
	}
	u.post(t, "/v1/ui/sessions", map[string]any{"action": "rename", "id": "s1", "name": "first"}, &made)
	if made.Session.Name != "first" || made.Session.Active {
		t.Fatalf("rename: %+v", made)
	}
	u.post(t, "/v1/ui/sessions", map[string]any{"action": "switch", "id": "s1"}, &made)
	u.post(t, "/v1/ui/sessions", map[string]any{"action": "delete", "id": "s2"}, &made)
	u.get(t, "/v1/ui/sessions", &list)
	if len(list) != 1 || list[0].Name != "first" {
		t.Fatalf("after delete: %+v", list)
	}
	if code := u.do(t, "GET", "/v1/ui/sessions", nil, nil); code != 200 {
		t.Fatal(code)
	}
	req, _ := http.NewRequest("GET", u.base+"/v1/ui/rows", nil)
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 401 {
		t.Fatalf("no token: %v %v", resp, err)
	}
}
