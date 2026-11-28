package intercept

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"sort"
	"strconv"
	"strings"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/sniff"
)

// serveH1 serves HTTP/1.x requests on one client connection. first (https) is the already connected upstream
// connection; firstReq is a request that was already read (explicit proxy).
func (e *Engine) serveH1(ctx context.Context, c *sniff.Conn, ci *connInfo, first net.Conn, firstReq *http.Request) {
	pool := &h1Pool{e: e, ci: ci, conns: map[string]*upConn{}}
	if first != nil {
		pool.add(ci.t.Addr, first)
	}
	defer pool.closeAll()
	bw := bufio.NewWriterSize(c, 32<<10)
	for {
		req := firstReq
		firstReq = nil
		if req == nil {
			_ = c.SetReadDeadline(time.Now().Add(h1Idle))
			r, err := http.ReadRequest(c.R)
			_ = c.SetReadDeadline(time.Time{})
			if err != nil {
				return
			}
			req = r
		}
		if !e.roundTripH1(ctx, c, bw, req, ci, pool) {
			return
		}
	}
}

func (e *Engine) roundTripH1(ctx context.Context, c *sniff.Conn, bw *bufio.Writer, req *http.Request, ci *connInfo, pool *h1Pool) bool {
	scheme, addr, host, port := ci.scheme, ci.t.Addr, hostOnly(req.Host), ci.t.Port
	if req.URL.IsAbs() { // explicit proxy: every request names its own server
		scheme = req.URL.Scheme
		host = req.URL.Hostname()
		port = portOf(req.URL.Port(), 80)
		addr = net.JoinHostPort(host, strconv.Itoa(port))
		req.Header.Del("Proxy-Connection")
		req.Header.Del("Proxy-Authorization")
		if req.Host == "" {
			req.Host = req.URL.Host
		}
	}
	if host == "" {
		host = ci.t.Host
	}
	if req.Host == "" {
		req.Host = host
	}
	start := time.Now()
	var f *flow.Flow
	if !ci.noRecord {
		f = e.cfg.Sink.NewFlow(flow.KindHTTP, ci.m)
		ci.fill(f)
		f.Scheme, f.Host, f.Port = scheme, host, port
		f.Method = req.Method
		f.Path = req.URL.EscapedPath()
		if f.Path == "" {
			f.Path = "/"
		}
		f.Query = req.URL.RawQuery
		f.HTTPVersion = fmt.Sprintf("%d.%d", req.ProtoMajor, req.ProtoMinor)
		f.ReqHeaders = flow.FromHTTP(req.Header, req.Host)
		f.Req.ContentType = req.Header.Get("Content-Type")
		f.Req.Encoding = req.Header.Get("Content-Encoding")
		if addr != ci.t.Addr {
			f.Server = addr
		}
	}
	if hasToken(req.Header["Expect"], "100-continue") {
		bw.WriteString("HTTP/1.1 100 Continue\r\n\r\n")
		bw.Flush()
		req.Header.Del("Expect")
	}
	ws := isWebSocket(req.Header)
	// rules and breakpoints: may change the request or answer it themselves
	var mock *http.Response
	if f != nil && e.hooksOn() {
		r, herr := e.cfg.Hooks.Request(ctx, f, req)
		if herr != nil {
			if errors.Is(herr, ErrBlock) {
				f.Error = "blocked by a rule (connection closed)"
			} else {
				f.Error = "rule: " + herr.Error()
				f.Status = http.StatusBadGateway
				writeError(bw, http.StatusBadGateway, "netcap: "+herr.Error())
			}
			e.finish(f, start)
			return false
		}
		afterRequestHook(f, req)
		mock = r
	}
	var wsHook WSHook
	if ws && f != nil && mock == nil && e.hooksOn() {
		if wsHook = e.cfg.Hooks.WebSocket(f, req); wsHook != nil {
			req.Header.Del("Sec-WebSocket-Extensions") // messages must be plain to be changed
		}
	}
	var reqCap Capture
	if f != nil && req.Body != nil && req.Body != http.NoBody {
		reqCap = e.cfg.Sink.Capture(f, "req")
		req.Body = &teeBody{r: req.Body, w: reqCap}
	}
	if f != nil {
		e.cfg.Sink.Update(f)
	}
	if _, ok := req.Header["User-Agent"]; !ok {
		req.Header["User-Agent"] = []string{""} // keep Go from adding its own
	}
	req.RequestURI = ""
	var resp *http.Response
	var uc *upConn
	var err error
	if mock != nil {
		if req.Body != nil {
			io.Copy(io.Discard, req.Body) // the app sent a body: read it, so the connection stays usable
			req.Body.Close()
		}
		resp = mock
		if resp.Request == nil {
			resp.Request = req
		}
	} else {
		resp, uc, err = pool.roundTrip(ctx, scheme, addr, host, req)
	}
	if err != nil {
		writeError(bw, http.StatusBadGateway, "netcap: upstream error: "+err.Error())
		if f != nil {
			f.Status = http.StatusBadGateway
			f.Error = "upstream: " + err.Error()
			finishBody(&f.Req, reqCap)
			e.finish(f, start)
		}
		return false
	}
	if f != nil && mock == nil && e.hooksOn() && resp.StatusCode != http.StatusSwitchingProtocols {
		f.Status = resp.StatusCode
		f.ResHeaders = flow.FromHTTP(resp.Header, "")
		r2, herr := e.cfg.Hooks.Response(ctx, f, req, resp)
		if herr != nil {
			resp.Body.Close()
			uc.conn.Close()
			pool.forget(uc)
			if errors.Is(herr, ErrBlock) {
				f.Error = "blocked by a rule (connection closed)"
			} else {
				f.Error = "rule: " + herr.Error()
				f.Status = http.StatusBadGateway
				writeError(bw, http.StatusBadGateway, "netcap: "+herr.Error())
			}
			finishBody(&f.Req, reqCap)
			e.finish(f, start)
			return false
		}
		if r2 != resp && r2 != nil { // replaced: the server's answer is not used further
			resp.Body.Close()
			uc.conn.Close()
			pool.forget(uc)
			uc = nil
			resp = r2
			if resp.Request == nil {
				resp.Request = req
			}
		}
	}
	defer resp.Body.Close()
	if f != nil {
		f.Timings.TTFB = ms(time.Since(start))
		f.Status = resp.StatusCode
		f.ResHeaders = flow.FromHTTP(resp.Header, "")
		f.Res.ContentType = resp.Header.Get("Content-Type")
		f.Res.Encoding = resp.Header.Get("Content-Encoding")
		if resp.StatusCode != http.StatusSwitchingProtocols {
			e.cfg.Sink.Update(f) // the response headers are in (the GUI shows the status while the body streams)
		}
	}
	if resp.StatusCode == http.StatusSwitchingProtocols && uc != nil {
		if err := writeHead(bw, resp, req.Method, false, false); err != nil {
			return false
		}
		bw.Flush()
		pool.forget(uc)
		defer uc.conn.Close()
		if f == nil {
			pipe(c, &readerConn{Conn: uc.conn, r: uc.br}, nil, nil)
			return false
		}
		finishBody(&f.Req, reqCap)
		if ws {
			f.Kind = flow.KindWS
			e.cfg.Sink.Update(f)
			e.relayWS(f, c, uc, resp.Header, wsHook)
		} else {
			f.AddTag("upgrade:" + strings.ToLower(resp.Header.Get("Upgrade")))
			e.cfg.Sink.Update(f)
			out, in := pipe(c, &readerConn{Conn: uc.conn, r: uc.br}, nil, nil)
			f.BytesOut, f.BytesIn = out, in
		}
		e.finish(f, start)
		return false
	}
	var resCap Capture
	if f != nil {
		resCap = e.cfg.Sink.Capture(f, "res")
		resp.Body = &teeBody{r: resp.Body, w: resCap}
	}
	rerr, werr := writeResponse(bw, resp, req)
	if (rerr != nil || werr != nil) && uc != nil {
		uc.conn.Close()
		pool.forget(uc)
	}
	if f != nil {
		finishBody(&f.Req, reqCap)
		finishBody(&f.Res, resCap)
		f.ResTrailers = flow.FromHTTP(resp.Trailer, "")
		switch {
		case rerr != nil:
			f.Error = "server stopped during the response: " + rerr.Error()
		case werr != nil:
			f.Error = "app closed the connection during the response: " + werr.Error()
		}
		e.finish(f, start)
	}
	return rerr == nil && werr == nil && !req.Close && !resp.Close
}

// writeError answers the client with a short plain-text error.
func writeError(bw *bufio.Writer, code int, msg string) {
	fmt.Fprintf(bw, "HTTP/1.1 %d %s\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
		code, http.StatusText(code), len(msg), msg)
	bw.Flush()
}

// writeHead writes the status line and headers; framing headers are set from the body mode.
func writeHead(bw *bufio.Writer, resp *http.Response, method string, chunked, setLength bool) error {
	text := strings.TrimSpace(strings.TrimPrefix(resp.Status, strconv.Itoa(resp.StatusCode)))
	if text == "" {
		text = http.StatusText(resp.StatusCode)
	}
	fmt.Fprintf(bw, "HTTP/1.1 %03d %s\r\n", resp.StatusCode, text)
	h := resp.Header.Clone()
	h.Del("Transfer-Encoding")
	if chunked {
		h.Del("Content-Length")
		h.Set("Transfer-Encoding", "chunked")
		if len(resp.Trailer) > 0 {
			keys := make([]string, 0, len(resp.Trailer))
			for k := range resp.Trailer {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			h.Set("Trailer", strings.Join(keys, ", "))
		}
	} else if setLength {
		h.Set("Content-Length", strconv.FormatInt(resp.ContentLength, 10))
	}
	if err := h.Write(bw); err != nil {
		return err
	}
	_, err := bw.WriteString("\r\n")
	return err
}

// writeResponse writes resp to the client, flushing after every chunk read (streams, server-sent events).
// rerr = reading the body from the server failed, werr = writing to the client failed.
func writeResponse(bw *bufio.Writer, resp *http.Response, req *http.Request) (rerr, werr error) {
	code := resp.StatusCode
	noBody := req.Method == http.MethodHead || code == 204 || code == 304 || (code >= 100 && code < 200)
	if noBody {
		if err := writeHead(bw, resp, req.Method, false, false); err != nil {
			return nil, err
		}
		return nil, bw.Flush()
	}
	chunkedIn := len(resp.TransferEncoding) > 0 && resp.TransferEncoding[0] == "chunked"
	switch {
	case resp.ContentLength >= 0 && !chunkedIn:
		if err := writeHead(bw, resp, req.Method, false, true); err != nil {
			return nil, err
		}
		return copyFlush(bw, bw, resp.Body)
	case req.ProtoAtLeast(1, 1):
		if err := writeHead(bw, resp, req.Method, true, false); err != nil {
			return nil, err
		}
		cw := httputil.NewChunkedWriter(bw)
		if rerr, werr = copyFlush(cw, bw, resp.Body); werr != nil {
			return rerr, werr
		}
		if rerr != nil { // the server broke off: don't send a clean end, the client must see it failed
			return rerr, nil
		}
		if err := cw.Close(); err != nil {
			return nil, err
		}
		if err := resp.Trailer.Write(bw); err != nil {
			return nil, err
		}
		bw.WriteString("\r\n")
		return nil, bw.Flush()
	default: // HTTP/1.0 client: body ends when the connection closes
		resp.Close = true
		resp.Header.Set("Connection", "close")
		if err := writeHead(bw, resp, req.Method, false, false); err != nil {
			return nil, err
		}
		return copyFlush(bw, bw, resp.Body)
	}
}

// copyFlush copies src to dst and flushes bw after every read, so streamed answers reach the app at once.
func copyFlush(dst io.Writer, bw *bufio.Writer, src io.Reader) (rerr, werr error) {
	bp := bufPool.Get().(*[]byte)
	defer bufPool.Put(bp)
	buf := *bp
	for {
		n, err := src.Read(buf)
		if n > 0 {
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return nil, werr
			}
			if werr := bw.Flush(); werr != nil {
				return nil, werr
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil, bw.Flush()
			}
			return err, bw.Flush()
		}
	}
}

// ---- upstream HTTP/1 connections of one client connection ----

type upConn struct {
	conn net.Conn
	br   *bufio.Reader
	addr string
	used bool
}

type h1Pool struct {
	e     *Engine
	ci    *connInfo
	conns map[string]*upConn
}

func (p *h1Pool) add(addr string, c net.Conn) *upConn {
	uc := &upConn{conn: c, br: bufio.NewReaderSize(c, 32<<10), addr: addr}
	p.conns[addr] = uc
	return uc
}

func (p *h1Pool) forget(uc *upConn) {
	if p.conns[uc.addr] == uc {
		delete(p.conns, uc.addr)
	}
}

func (p *h1Pool) closeAll() {
	for _, uc := range p.conns {
		uc.conn.Close()
	}
}

func (p *h1Pool) dial(ctx context.Context, scheme, addr, host string) (net.Conn, error) {
	c, err := p.e.cfg.Dial(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	if scheme != "https" {
		return c, nil
	}
	name := p.ci.sni
	if name == "" {
		name = host
	}
	conf := &tls.Config{ServerName: name, NextProtos: []string{"http/1.1"}, RootCAs: p.e.cfg.RootCAs, MinVersion: tls.VersionTLS10}
	if p.e.cfg.InsecureUpstream != nil && p.e.cfg.InsecureUpstream(host) {
		conf.InsecureSkipVerify = true
	}
	tc := tls.Client(c, conf)
	hctx, cancel := context.WithTimeout(ctx, handshakeMax)
	defer cancel()
	if err := tc.HandshakeContext(hctx); err != nil {
		c.Close()
		return nil, err
	}
	return tc, nil
}

func (p *h1Pool) roundTrip(ctx context.Context, scheme, addr, host string, req *http.Request) (*http.Response, *upConn, error) {
	for attempt := 0; ; attempt++ {
		uc := p.conns[addr]
		fresh := uc == nil
		if fresh {
			c, err := p.dial(ctx, scheme, addr, host)
			if err != nil {
				return nil, nil, err
			}
			uc = p.add(addr, c)
		}
		reused := uc.used
		uc.used = true
		err := req.Write(uc.conn)
		var resp *http.Response
		if err == nil {
			resp, err = readResponse(uc.br, req)
		}
		if err == nil {
			return resp, uc, nil
		}
		uc.conn.Close()
		p.forget(uc)
		// a kept-alive connection the server had already closed: try once more on a new one if the body is unread
		if attempt == 0 && reused && bodyUnread(req) {
			continue
		}
		return nil, nil, err
	}
}

// bodyUnread: only requests without a body can be sent again (Request.Write closes the body, even on failure).
func bodyUnread(req *http.Request) bool {
	return req.Body == nil || req.Body == http.NoBody
}

// readResponse reads the response, skipping 1xx answers other than 101.
func readResponse(br *bufio.Reader, req *http.Request) (*http.Response, error) {
	for {
		resp, err := http.ReadResponse(br, req)
		if err != nil {
			return nil, err
		}
		if resp.StatusCode >= 100 && resp.StatusCode < 200 && resp.StatusCode != http.StatusSwitchingProtocols {
			resp.Body.Close()
			continue
		}
		return resp, nil
	}
}

// readerConn reads through a bufio.Reader that may hold bytes already read from Conn.
type readerConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *readerConn) Read(p []byte) (int, error) { return c.r.Read(p) }
