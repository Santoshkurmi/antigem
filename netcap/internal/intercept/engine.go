// Package intercept is the core of the engine: it takes one client connection (from the VPN tunnel or the proxy
// port), finds out what it speaks, decrypts TLS with the AntiGem CA when allowed, forwards everything to the real
// server and reports what it saw to a Sink as flows.
package intercept

import (
	"context"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/http2"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/sniff"
	"antigem/netcap/internal/tlsx"
	"antigem/netcap/internal/view"
)

// Meta describes where a connection comes from.
type Meta struct {
	App     string // package name (Android) or "" when unknown
	UID     int
	Client  string // client address
	Ingress string // "tun" or "proxy"
}

// Target is where the client wanted to go.
type Target struct {
	Addr string // host:port or ip:port to dial
	Host string // best known name (CONNECT/SOCKS host, DNS answer); may be an IP
	Port int
	Pre  net.Conn // already connected to Addr (the VPN ingress connects before accepting the app's connection)
}

// dialTarget returns t.Pre the first time (taking it), else dials t.Addr.
func (e *Engine) dialTarget(ctx context.Context, t *Target) (net.Conn, error) {
	if t.Pre != nil {
		c := t.Pre
		t.Pre = nil
		return c, nil
	}
	return e.cfg.Dial(ctx, "tcp", t.Addr)
}

// Decision for a new connection.
type Decision struct {
	Record  bool // false: relay untouched, record nothing
	Decrypt bool // false: TLS passes through encrypted (recorded as one "tls" flow)
}

// CaptureResult is what a finished body capture reports.
type CaptureResult struct {
	Hash   string
	Size   int64
	Stored int64
	Sample []byte // first bytes, for type detection
}

// Capture receives a body while it streams. Write must never fail.
type Capture interface {
	io.Writer
	Done() CaptureResult // idempotent
}

// Sink receives the flows (the service layer implements it).
type Sink interface {
	NewFlow(kind flow.Kind, m Meta) *flow.Flow
	Update(f *flow.Flow)
	Finish(f *flow.Flow)
	Message(f *flow.Flow, m flow.Message)
	Capture(f *flow.Flow, side string) Capture
}

// ErrBlock from a hook: drop the request (the connection or HTTP/2 stream is reset).
var ErrBlock = errors.New("blocked by a rule")

// Hooks change traffic (rules, breakpoints). Active is checked first, so the hot path costs nothing without rules.
type Hooks interface {
	Active() bool
	// Request runs before the upstream: it may change req, or return a response to send instead.
	Request(ctx context.Context, f *flow.Flow, req *http.Request) (*http.Response, error)
	// Response runs after the upstream answered: it returns the response to send (the same or a changed one).
	Response(ctx context.Context, f *flow.Flow, req *http.Request, resp *http.Response) (*http.Response, error)
	// WebSocket is asked before a WebSocket upgrade goes out: nil = forward the messages byte for byte; else every
	// message goes through the hook (permessage-deflate is then taken out of the handshake).
	WebSocket(f *flow.Flow, req *http.Request) WSHook
}

// Config of the engine.
type Config struct {
	Certs            *tlsx.Certs
	Untrusted        *tlsx.Untrusted
	Sink             Sink
	Dial             func(ctx context.Context, network, addr string) (net.Conn, error)
	Decide           func(m Meta, host string) Decision
	RootCAs          *x509.CertPool // nil = system roots
	InsecureUpstream func(host string) bool
	Hooks            Hooks
	Logf             func(format string, args ...any)
}

func (e *Engine) hooksOn() bool { return e.cfg.Hooks != nil && e.cfg.Hooks.Active() }

// afterRequestHook records what the request looks like after the hooks changed it.
func afterRequestHook(f *flow.Flow, req *http.Request) {
	if !hasFlag(f, "modified") {
		return
	}
	f.Method = req.Method
	f.Path = req.URL.EscapedPath()
	if f.Path == "" {
		f.Path = "/"
	}
	f.Query = req.URL.RawQuery
	h := req.Header.Clone()
	if v, ok := h["User-Agent"]; ok && len(v) == 1 && v[0] == "" {
		delete(h, "User-Agent")
	}
	f.ReqHeaders = flow.FromHTTP(h, req.Host)
	f.Req.ContentType = req.Header.Get("Content-Type")
	f.Req.Encoding = req.Header.Get("Content-Encoding")
}

func hasFlag(f *flow.Flow, flag string) bool {
	for _, x := range f.Flags {
		if x == flag {
			return true
		}
	}
	return false
}

// Engine handles connections.
type Engine struct {
	cfg Config
	h2t *http2.Transport
}

const (
	sniffWait    = 400 * time.Millisecond // no byte from the client by then: the server speaks first
	h1Idle       = 2 * time.Minute
	handshakeMax = 15 * time.Second
)

// New makes an engine.
func New(cfg Config) *Engine {
	if cfg.Dial == nil {
		d := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
		cfg.Dial = d.DialContext
	}
	if cfg.Decide == nil {
		cfg.Decide = func(Meta, string) Decision { return Decision{Record: true, Decrypt: true} }
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	return &Engine{cfg: cfg, h2t: &http2.Transport{
		DisableCompression: true, // never add Accept-Encoding or decompress: forward bytes as they are
		AllowHTTP:          true,
		ReadIdleTimeout:    60 * time.Second,
		PingTimeout:        15 * time.Second,
	}}
}

// HandleTCP serves one client connection to t. It closes c when done.
func (e *Engine) HandleTCP(ctx context.Context, c net.Conn, t Target, m Meta) {
	defer c.Close()
	defer func() {
		if t.Pre != nil {
			t.Pre.Close()
		}
	}()
	pc := sniff.NewConn(c)
	proto := sniff.Detect(pc, sniffWait)
	if proto == sniff.TLS {
		e.handleTLS(ctx, pc, &t, m)
		return
	}
	d := e.cfg.Decide(m, t.Host)
	if !d.Record {
		e.relayPlain(ctx, pc, &t)
		return
	}
	ci := &connInfo{scheme: "http", t: t, m: m, tlsState: flow.TLSPlain}
	ci.t.Pre = nil
	switch proto {
	case sniff.HTTP1:
		var first net.Conn
		if t.Pre != nil {
			first, t.Pre = t.Pre, nil
		}
		e.serveH1(ctx, pc, ci, first, nil)
	case sniff.H2C:
		var first net.Conn
		if t.Pre != nil {
			first, t.Pre = t.Pre, nil
		}
		e.serveH2(ctx, pc, first, ci)
	default:
		e.relayRaw(ctx, pc, &t, m)
	}
}

// ServeProxyHTTP serves a plain-HTTP explicit-proxy connection whose first request (absolute URL) was already read.
func (e *Engine) ServeProxyHTTP(ctx context.Context, c *sniff.Conn, first *http.Request, m Meta) {
	defer c.Close()
	host := first.URL.Hostname()
	port := portOf(first.URL.Port(), 80)
	t := Target{Addr: net.JoinHostPort(host, strconv.Itoa(port)), Host: host, Port: port}
	ci := &connInfo{scheme: "http", t: t, m: m, tlsState: flow.TLSPlain, noRecord: !e.cfg.Decide(m, host).Record}
	e.serveH1(ctx, c, ci, nil, first)
}

// connInfo is what all requests on one client connection share.
type connInfo struct {
	scheme    string
	t         Target
	m         Meta
	sni       string
	tlsState  string
	tls       *flow.TLSInfo
	connectMs float64
	tlsMs     float64
	server    string
	noRecord  bool

	mu   sync.Mutex
	used bool
}

// fill gives a new flow the connection's data; the first request also gets the connect and TLS timings.
func (ci *connInfo) fill(f *flow.Flow) {
	f.Scheme = ci.scheme
	f.Port = ci.t.Port
	f.TLS = ci.tlsState
	f.Server = ci.server
	if f.Server == "" {
		f.Server = ci.t.Addr
	}
	ci.mu.Lock()
	if ci.tls != nil {
		t := *ci.tls
		f.TLSInfo = &t
	}
	if !ci.used {
		f.Timings.Connect = ci.connectMs
		f.Timings.TLS = ci.tlsMs
		ci.used = true
	}
	ci.mu.Unlock()
}

func (e *Engine) finish(f *flow.Flow, start time.Time) {
	f.End = time.Now()
	f.Timings.Total = ms(f.End.Sub(start))
	f.Done = true
	e.cfg.Sink.Finish(f)
}

func finishBody(b *flow.Body, c Capture) {
	if c == nil {
		return
	}
	r := c.Done()
	b.Hash, b.Size, b.Stored = r.Hash, r.Size, r.Stored
	b.Truncated = r.Size > r.Stored
	if b.Size == 0 && b.ContentType == "" {
		return
	}
	sample := r.Sample
	if b.Encoding != "" && len(sample) > 0 {
		if d, err := view.Decode(sample, b.Encoding); err == nil {
			sample = d
		}
	}
	b.Type = view.ShortType(b.ContentType, sample)
}

func ms(d time.Duration) float64 { return float64(d.Microseconds()) / 1000 }

func portOf(s string, def int) int {
	if n, err := strconv.Atoi(s); err == nil && n > 0 {
		return n
	}
	return def
}

func hostOnly(hostport string) string {
	if h, _, err := net.SplitHostPort(hostport); err == nil {
		return strings.Trim(h, "[]")
	}
	return strings.Trim(hostport, "[]")
}

// relayPlain copies both ways without looking (not recorded).
func (e *Engine) relayPlain(ctx context.Context, c net.Conn, t *Target) {
	up, err := e.dialTarget(ctx, t)
	if err != nil {
		return
	}
	defer up.Close()
	pipe(c, up, nil, nil)
}

// pipe copies a→b and b→a until both ends finish; obsAB/obsBA see the bytes (may be nil).
func pipe(a, b net.Conn, obsAB, obsBA func([]byte)) (ab, ba int64) {
	done := make(chan struct{})
	go func() {
		ab, _ = copyObserve(b, a, obsAB)
		closeWrite(b)
		close(done)
	}()
	ba, _ = copyObserve(a, b, obsBA)
	closeWrite(a)
	select {
	case <-done:
	case <-time.After(2 * time.Minute): // the other side never finished: give up
		a.Close()
		b.Close()
		<-done
	}
	return
}

var bufPool = sync.Pool{New: func() any { b := make([]byte, 32<<10); return &b }}

// copyObserve copies src to dst; obs (if set) sees every chunk.
func copyObserve(dst io.Writer, src io.Reader, obs func([]byte)) (int64, error) {
	bp := bufPool.Get().(*[]byte)
	defer bufPool.Put(bp)
	buf := *bp
	var total int64
	for {
		n, err := src.Read(buf)
		if n > 0 {
			if obs != nil {
				obs(buf[:n])
			}
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return total, werr
			}
			total += int64(n)
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return total, nil
			}
			return total, err
		}
	}
}

type closeWriter interface{ CloseWrite() error }

func closeWrite(c net.Conn) {
	if pc, ok := c.(*sniff.Conn); ok {
		c = pc.Conn
	}
	if cw, ok := c.(closeWriter); ok {
		if cw.CloseWrite() == nil {
			return
		}
	}
	c.Close()
}

// teeBody copies what is read from a body into a capture.
type teeBody struct {
	r io.ReadCloser
	w Capture
	n int64
}

func (t *teeBody) Read(p []byte) (int, error) {
	n, err := t.r.Read(p)
	if n > 0 {
		t.w.Write(p[:n])
		t.n += int64(n)
	}
	return n, err
}

func (t *teeBody) Close() error { return t.r.Close() }

func hasToken(values []string, token string) bool {
	for _, v := range values {
		for _, p := range strings.Split(v, ",") {
			if strings.EqualFold(strings.TrimSpace(p), token) {
				return true
			}
		}
	}
	return false
}

func isWebSocket(h http.Header) bool {
	return hasToken(h["Connection"], "upgrade") && strings.EqualFold(h.Get("Upgrade"), "websocket")
}
