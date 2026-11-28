// End-to-end tests: real clients → proxy port → engine → local test servers, then check what was recorded.
package e2e

import (
	"bufio"
	"compress/gzip"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
	"golang.org/x/net/websocket"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/ingress/proxy"
	"antigem/netcap/internal/mcp"
	"antigem/netcap/internal/service"
)

type env struct {
	svc   *service.Service
	proxy string
	ca    *x509.CertPool
	mcp   *mcp.Server
}

func setup(t *testing.T) *env {
	t.Helper()
	svc, err := service.New(service.Options{DataDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	st := svc.Settings()
	st.InsecureUpstream = []string{"127.0.0.1"} // the test servers have self-signed certificates
	if err := svc.SetSettings(st); err != nil {
		t.Fatal(err)
	}
	if err := svc.StartCapture(service.StartOptions{}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	px := &proxy.Server{Engine: svc.Engine, Addr: "127.0.0.1:0", CertPEM: svc.CA.CertPEM}
	if err := px.Listen(); err != nil {
		t.Fatal(err)
	}
	go px.Serve(ctx)
	t.Cleanup(func() { cancel(); svc.Close() })
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM(svc.CA.CertPEM)
	return &env{svc: svc, proxy: px.Addr, ca: pool, mcp: mcp.New(svc)}
}

func (e *env) client(h2 bool) *http.Client {
	pu, _ := url.Parse("http://" + e.proxy)
	tr := &http.Transport{Proxy: http.ProxyURL(pu), TLSClientConfig: &tls.Config{RootCAs: e.ca}, ForceAttemptHTTP2: h2, DisableCompression: true}
	return &http.Client{Transport: tr, Timeout: 10 * time.Second}
}

// flows waits until n flows are finished and returns them, oldest first.
func (e *env) flows(t *testing.T, n int) []*flow.Flow {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		sess := e.svc.Current()
		sess.Store.Flush()
		var out []*flow.Flow
		sess.Store.Scan("done = 1", nil, true, func(f *flow.Flow) bool { out = append(out, f); return true })
		if len(out) >= n || time.Now().After(deadline) {
			if len(out) < n {
				t.Fatalf("want %d flows, have %d", n, len(out))
			}
			return out
		}
		time.Sleep(30 * time.Millisecond)
	}
}

func TestH1ChunkedGzipKeepAlive(t *testing.T) {
	e := setup(t)
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Encoding", "gzip")
		gz := gzip.NewWriter(w)
		fmt.Fprintf(gz, `{"path":%q,"items":[1,2,3]}`, r.URL.Path)
		gz.Flush()
		w.(http.Flusher).Flush() // chunked
		gz.Close()
	}))
	defer srv.Close()
	c := e.client(false)
	for _, p := range []string{"/a", "/b"} {
		resp, err := c.Get(srv.URL + p)
		if err != nil {
			t.Fatal(err)
		}
		zr, _ := gzip.NewReader(resp.Body)
		b, _ := io.ReadAll(zr)
		resp.Body.Close()
		if !strings.Contains(string(b), p) {
			t.Fatalf("body %q", b)
		}
	}
	fs := e.flows(t, 2)
	f := fs[1]
	if f.Status != 200 || f.HTTPVersion != "1.1" || f.Res.Encoding != "gzip" || f.Res.Type != "json" || f.TLS != flow.TLSDecrypted {
		t.Fatalf("flow: %+v", f)
	}
	body, _, err := e.svc.Body(e.svc.Current(), f, "res", false, 0)
	if err != nil || string(body) != `{"path":"/b","items":[1,2,3]}` {
		t.Fatalf("decoded body %q %v", body, err)
	}
	if fs[0].Timings.Connect == 0 || fs[1].Timings.Connect != 0 {
		t.Fatalf("connect timing should be on the first request only: %v %v", fs[0].Timings, fs[1].Timings)
	}
}

func TestPlainHTTPProxyAndPost(t *testing.T) {
	e := setup(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "text/plain")
		w.Write(append([]byte("got "), b...))
	}))
	defer srv.Close()
	resp, err := e.client(false).Post(srv.URL+"/submit?x=1", "application/x-www-form-urlencoded", strings.NewReader("user=bob&password=hunter2"))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(b) != "got user=bob&password=hunter2" {
		t.Fatalf("%q", b)
	}
	f := e.flows(t, 1)[0]
	if f.TLS != flow.TLSPlain || f.Method != "POST" || f.Req.Type != "form" || f.Query != "x=1" {
		t.Fatalf("%+v", f)
	}
	out, err := e.mcp.Call(context.Background(), "net_get", map[string]any{"id": "f1"})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out, "hunter2") || !strings.Contains(out, "user") {
		t.Fatalf("net_get should hide the password:\n%s", out)
	}
}

func TestH2StreamingGRPCTrailers(t *testing.T) {
	e := setup(t)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		w.Header().Set("Content-Type", "application/grpc")
		w.Header().Set("Trailer", "Grpc-Status")
		w.WriteHeader(200)
		msg := []byte{0x0a, 0x05, 'h', 'e', 'l', 'l', 'o', 0x10, 0x2a} // field 1 "hello", field 2 = 42
		frame := make([]byte, 5)
		binary.BigEndian.PutUint32(frame[1:], uint32(len(msg)))
		w.Write(append(frame, msg...))
		w.(http.Flusher).Flush()
		w.Header().Set("Grpc-Status", "0")
	}))
	srv.EnableHTTP2 = true
	srv.StartTLS()
	defer srv.Close()
	req, _ := http.NewRequest("POST", srv.URL+"/pkg.Greeter/SayHello", strings.NewReader("\x00\x00\x00\x00\x00"))
	req.Header.Set("Content-Type", "application/grpc")
	req.Header.Set("Te", "trailers")
	resp, err := e.client(true).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.ProtoMajor != 2 || resp.Trailer.Get("Grpc-Status") != "0" {
		t.Fatalf("proto %d trailer %v", resp.ProtoMajor, resp.Trailer)
	}
	f := e.flows(t, 1)[0]
	if f.Kind != flow.KindGRPC || f.HTTPVersion != "2" || !f.HasTag("grpc-status:0") {
		t.Fatalf("%+v", f)
	}
	out, _ := e.mcp.Call(context.Background(), "net_get", map[string]any{"id": "f1", "view": "protobuf"})
	if !strings.Contains(out, `1: "hello"`) || !strings.Contains(out, "2: 42") {
		t.Fatalf("protobuf view:\n%s", out)
	}
}

// dialConnect opens a tunnel through the proxy with CONNECT.
func dialConnect(t *testing.T, proxyAddr, target string) net.Conn {
	t.Helper()
	c, err := net.Dial("tcp", proxyAddr)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Fprintf(c, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", target, target)
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT: %v %v", resp, err)
	}
	return c
}

func TestWebSocket(t *testing.T) {
	e := setup(t)
	srv := httptest.NewTLSServer(websocket.Handler(func(ws *websocket.Conn) {
		var msg string
		for websocket.Message.Receive(ws, &msg) == nil {
			websocket.Message.Send(ws, "echo:"+msg)
		}
	}))
	defer srv.Close()
	addr := strings.TrimPrefix(srv.URL, "https://")
	raw := dialConnect(t, e.proxy, addr)
	tc := tls.Client(raw, &tls.Config{RootCAs: e.ca, ServerName: "127.0.0.1"})
	cfg, _ := websocket.NewConfig("wss://"+addr+"/chat", "https://"+addr)
	ws, err := websocket.NewClient(cfg, tc)
	if err != nil {
		t.Fatal(err)
	}
	for _, m := range []string{"one", "two"} {
		websocket.Message.Send(ws, m)
		var got string
		if err := websocket.Message.Receive(ws, &got); err != nil || got != "echo:"+m {
			t.Fatalf("%q %v", got, err)
		}
	}
	ws.Close()
	f := e.flows(t, 1)[0]
	if f.Kind != flow.KindWS || f.Status != 101 || f.Messages < 4 {
		t.Fatalf("%+v", f)
	}
	out, _ := e.mcp.Call(context.Background(), "net_ws", map[string]any{"id": "f1"})
	if !strings.Contains(out, "one") || !strings.Contains(out, "echo:two") {
		t.Fatalf("net_ws:\n%s", out)
	}
}

func TestH2CPriorKnowledge(t *testing.T) {
	e := setup(t)
	srv := httptest.NewServer(h2c.NewHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "proto=%s", r.Proto)
	}), &http2.Server{}))
	defer srv.Close()
	addr := strings.TrimPrefix(srv.URL, "http://")
	tr := &http2.Transport{AllowHTTP: true, DialTLSContext: func(ctx context.Context, n, a string, _ *tls.Config) (net.Conn, error) {
		return dialConnect(t, e.proxy, addr), nil
	}}
	resp, err := (&http.Client{Transport: tr, Timeout: 10 * time.Second}).Get(srv.URL + "/x")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(b) != "proto=HTTP/2.0" {
		t.Fatalf("%q", b)
	}
	f := e.flows(t, 1)[0]
	if f.HTTPVersion != "2" || f.TLS != flow.TLSPlain || f.Path != "/x" {
		t.Fatalf("%+v", f)
	}
}

func TestRawServerFirst(t *testing.T) {
	e := setup(t)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		c.Write([]byte("220 hello\r\n"))
		line, _ := bufio.NewReader(c).ReadString('\n')
		c.Write([]byte("250 " + line))
	}()
	c := dialConnect(t, e.proxy, ln.Addr().String())
	br := bufio.NewReader(c)
	greet, _ := br.ReadString('\n')
	c.Write([]byte("HELO me\r\n"))
	reply, _ := br.ReadString('\n')
	c.Close()
	if greet != "220 hello\r\n" || reply != "250 HELO me\r\n" {
		t.Fatalf("%q %q", greet, reply)
	}
	f := e.flows(t, 1)[0]
	if f.Kind != flow.KindTCP || f.BytesIn == 0 || f.BytesOut == 0 || f.Messages < 2 {
		t.Fatalf("%+v", f)
	}
}

func TestUntrustedThenPassthrough(t *testing.T) {
	e := setup(t)
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) }))
	defer srv.Close()
	// a client that does not trust the AntiGem CA
	pu, _ := url.Parse("http://" + e.proxy)
	strict := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu), TLSClientConfig: &tls.Config{RootCAs: x509.NewCertPool()}}, Timeout: 5 * time.Second}
	if _, err := strict.Get(srv.URL); err == nil {
		t.Fatal("the first request should fail: the client does not trust our CA")
	}
	f := e.flows(t, 1)[0]
	if f.TLS != flow.TLSUntrusted {
		t.Fatalf("%+v", f)
	}
	// next time the host passes through: a client that trusts the real server works again
	real := x509.NewCertPool()
	real.AddCert(srv.Certificate())
	ok := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu), TLSClientConfig: &tls.Config{RootCAs: real}}, Timeout: 5 * time.Second}
	resp, err := ok.Get(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	ok.CloseIdleConnections() // a passthrough flow is finished when its connection closes
	fs := e.flows(t, 2)
	if fs[1].Kind != flow.KindTLS || fs[1].TLS != flow.TLSUntrusted || fs[1].BytesIn == 0 {
		t.Fatalf("%+v", fs[1])
	}
	out, _ := e.mcp.Call(context.Background(), "net_status", nil)
	if !strings.Contains(out, "not decrypted") {
		t.Fatalf("status should list the host:\n%s", out)
	}
}

func TestMCPListSummarizeWait(t *testing.T) {
	e := setup(t)
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"id":%q}`, strings.TrimPrefix(r.URL.Path, "/users/"))
	}))
	defer srv.Close()
	c := e.client(true)
	for i := 1; i <= 5; i++ {
		resp, err := c.Get(fmt.Sprintf("%s/users/%d", srv.URL, i))
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
	}
	e.flows(t, 5)
	ctx := context.Background()
	list, _ := e.mcp.Call(ctx, "net_list", map[string]any{"filter": "path:/users/*"})
	if !strings.Contains(list, "×5") {
		t.Fatalf("repeats should collapse:\n%s", list)
	}
	sum, _ := e.mcp.Call(ctx, "net_summarize", nil)
	if !strings.Contains(sum, "GET /users/{n}") || !strings.Contains(sum, "×5") {
		t.Fatalf("summary:\n%s", sum)
	}
	vals, _ := e.mcp.Call(ctx, "net_summarize", map[string]any{"values": "res.json:.id"})
	if !strings.Contains(vals, "5 distinct values") {
		t.Fatalf("values:\n%s", vals)
	}
	done := make(chan string)
	go func() {
		out, _ := e.mcp.Call(ctx, "net_wait", map[string]any{"filter": "path:/users/99", "timeout": 5})
		done <- out
	}()
	time.Sleep(200 * time.Millisecond)
	resp, err := c.Get(srv.URL + "/users/99")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if out := <-done; !strings.Contains(out, "/users/99") {
		t.Fatalf("wait: %s", out)
	}
	exp, _ := e.mcp.Call(ctx, "net_export", map[string]any{"filter": "path:/users/1", "format": "folder"})
	if !strings.Contains(exp, "1 flows written") {
		t.Fatalf("export: %s", exp)
	}
}
