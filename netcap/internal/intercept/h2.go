package intercept

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/http2"

	"antigem/netcap/internal/flow"
)

var quietLog = log.New(io.Discard, "", 0)

// serveH2 serves an HTTP/2 client connection; every stream becomes one flow. up is the connected upstream (TLS with
// h2), or nil for cleartext h2c, where the engine dials ci.t.Addr itself.
func (e *Engine) serveH2(ctx context.Context, cli net.Conn, up net.Conn, ci *connInfo) {
	u := &h2Up{e: e, ci: ci}
	if up != nil {
		if cc, err := e.h2t.NewClientConn(up); err == nil {
			u.cc = cc
			u.all = append(u.all, cc)
		} else {
			up.Close()
		}
	}
	defer u.close()
	srv := &http2.Server{IdleTimeout: 5 * time.Minute, MaxConcurrentStreams: 250}
	srv.ServeConn(cli, &http2.ServeConnOpts{
		Context:    ctx,
		BaseConfig: &http.Server{ErrorLog: quietLog},
		Handler:    http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { e.h2Stream(ctx, w, r, u) }),
	})
}

type h2Up struct {
	e   *Engine
	ci  *connInfo
	mu  sync.Mutex
	cc  *http2.ClientConn
	all []*http2.ClientConn
}

func (u *h2Up) get(ctx context.Context, fresh bool) (*http2.ClientConn, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if !fresh && u.cc != nil && u.cc.CanTakeNewRequest() {
		return u.cc, nil
	}
	c, err := u.e.cfg.Dial(ctx, "tcp", u.ci.t.Addr)
	if err != nil {
		return nil, err
	}
	if u.ci.scheme == "https" {
		name := u.ci.sni
		if name == "" {
			name = u.ci.t.Host
		}
		conf := &tls.Config{ServerName: name, NextProtos: []string{"h2"}, RootCAs: u.e.cfg.RootCAs, MinVersion: tls.VersionTLS12}
		if u.e.cfg.InsecureUpstream != nil && u.e.cfg.InsecureUpstream(u.ci.t.Host) {
			conf.InsecureSkipVerify = true
		}
		tc := tls.Client(c, conf)
		hctx, cancel := context.WithTimeout(ctx, handshakeMax)
		err := tc.HandshakeContext(hctx)
		cancel()
		if err != nil {
			c.Close()
			return nil, err
		}
		if tc.ConnectionState().NegotiatedProtocol != "h2" {
			tc.Close()
			return nil, errors.New("the server no longer offers HTTP/2")
		}
		c = tc
	}
	cc, err := u.e.h2t.NewClientConn(c)
	if err != nil {
		c.Close()
		return nil, err
	}
	u.cc = cc
	u.all = append(u.all, cc)
	return cc, nil
}

func (u *h2Up) close() {
	u.mu.Lock()
	defer u.mu.Unlock()
	for _, cc := range u.all {
		cc.Close()
	}
}

func (e *Engine) h2Stream(ctx context.Context, w http.ResponseWriter, r *http.Request, u *h2Up) {
	ci := u.ci
	start := time.Now()
	f := e.cfg.Sink.NewFlow(flow.KindHTTP, ci.m)
	ci.fill(f)
	host := hostOnly(r.Host)
	if host == "" {
		host = ci.t.Host
	}
	f.Host = host
	f.Method = r.Method
	f.Path = r.URL.EscapedPath()
	if f.Path == "" {
		f.Path = "/"
	}
	f.Query = r.URL.RawQuery
	f.HTTPVersion = "2"
	f.ReqHeaders = flow.FromHTTP(r.Header, r.Host)
	if len(f.ReqHeaders) > 0 && f.ReqHeaders[0][0] == "Host" {
		f.ReqHeaders[0][0] = ":authority"
	}
	f.Req.ContentType = r.Header.Get("Content-Type")
	f.Req.Encoding = r.Header.Get("Content-Encoding")
	if strings.HasPrefix(f.Req.ContentType, "application/grpc") {
		f.Kind = flow.KindGRPC
	}
	if r.Method == http.MethodConnect {
		f.Error = "WebSocket over HTTP/2 (extended CONNECT) is not supported yet"
		f.Status = http.StatusNotImplemented
		w.WriteHeader(http.StatusNotImplemented)
		e.finish(f, start)
		return
	}
	var reqCap Capture
	var body io.ReadCloser = http.NoBody
	if r.Body != nil && r.Body != http.NoBody {
		reqCap = e.cfg.Sink.Capture(f, "req")
		body = &teeBody{r: r.Body, w: reqCap}
	}
	out, err := http.NewRequestWithContext(r.Context(), r.Method, ci.scheme+"://"+r.Host+r.URL.RequestURI(), body)
	if err != nil {
		f.Error = "bad request: " + err.Error()
		w.WriteHeader(http.StatusBadRequest)
		e.finish(f, start)
		return
	}
	out.Header = r.Header.Clone()
	if _, ok := out.Header["User-Agent"]; !ok {
		out.Header["User-Agent"] = []string{""}
	}
	out.ContentLength = r.ContentLength
	out.Host = r.Host
	out.Trailer = r.Trailer
	var mock *http.Response
	if e.hooksOn() {
		r2, herr := e.cfg.Hooks.Request(ctx, f, out)
		if herr != nil {
			if errors.Is(herr, ErrBlock) {
				f.Error = "blocked by a rule (stream reset)"
				e.finish(f, start)
				panic(http.ErrAbortHandler) // resets this stream only
			}
			f.Error = "rule: " + herr.Error()
			f.Status = http.StatusBadGateway
			w.WriteHeader(http.StatusBadGateway)
			io.WriteString(w, "netcap: "+herr.Error())
			e.finish(f, start)
			return
		}
		afterRequestHook(f, out)
		mock = r2
		if out.Body != nil && out.Body != http.NoBody && out.Body != body {
			// the hook read (and maybe replaced) the body: capture what is sent, not what the hook read
			if reqCap != nil {
				reqCap.Done()
			}
			reqCap = e.cfg.Sink.Capture(f, "req")
			out.Body = &teeBody{r: out.Body, w: reqCap}
		}
	}
	e.cfg.Sink.Update(f)

	var resp *http.Response
	if mock != nil {
		if r.Body != nil {
			io.Copy(io.Discard, out.Body)
		}
		resp = mock
	} else {
		cc, gerr := u.get(ctx, false)
		err = gerr
		if err == nil {
			resp, err = cc.RoundTrip(out)
			if err != nil && reqCap == nil && retryableH2(err) {
				if cc, err2 := u.get(ctx, true); err2 == nil {
					resp, err = cc.RoundTrip(out)
				}
			}
		}
	}
	if err != nil {
		f.Error = "upstream: " + err.Error()
		f.Status = http.StatusBadGateway
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusBadGateway)
		io.WriteString(w, "netcap: "+f.Error)
		finishBody(&f.Req, reqCap)
		e.finish(f, start)
		return
	}
	if mock == nil && e.hooksOn() {
		f.Status = resp.StatusCode
		f.ResHeaders = flow.FromHTTP(resp.Header, "")
		r2, herr := e.cfg.Hooks.Response(ctx, f, out, resp)
		if herr != nil {
			resp.Body.Close()
			finishBody(&f.Req, reqCap)
			if errors.Is(herr, ErrBlock) {
				f.Error = "blocked by a rule (stream reset)"
				e.finish(f, start)
				panic(http.ErrAbortHandler)
			}
			f.Error = "rule: " + herr.Error()
			f.Status = http.StatusBadGateway
			w.WriteHeader(http.StatusBadGateway)
			io.WriteString(w, "netcap: "+herr.Error())
			e.finish(f, start)
			return
		}
		if r2 != nil && r2 != resp {
			resp.Body.Close()
			resp = r2
		}
	}
	defer resp.Body.Close()
	f.Timings.TTFB = ms(time.Since(start))
	f.Status = resp.StatusCode
	f.ResHeaders = flow.FromHTTP(resp.Header, "")
	f.Res.ContentType = resp.Header.Get("Content-Type")
	f.Res.Encoding = resp.Header.Get("Content-Encoding")
	e.cfg.Sink.Update(f) // the response headers are in (the GUI shows the status while the body streams)

	h := w.Header()
	for k, vv := range resp.Header {
		h[k] = vv
	}
	if _, ok := resp.Header["Content-Type"]; !ok {
		h["Content-Type"] = nil // no content sniffing by the server
	}
	if _, ok := resp.Header["Date"]; !ok {
		h["Date"] = nil
	}
	if resp.ContentLength >= 0 {
		h.Set("Content-Length", strconv.FormatInt(resp.ContentLength, 10))
	}
	w.WriteHeader(resp.StatusCode)
	fl, _ := w.(http.Flusher)
	// a stream (unknown length, server-sent events, gRPC) goes out chunk by chunk; a body of known length is sent
	// together with its headers (one TLS record and packet instead of three for a small answer)
	ct := resp.Header.Get("Content-Type")
	streaming := resp.ContentLength < 0 || strings.HasPrefix(ct, "text/event-stream") || strings.HasPrefix(ct, "application/grpc")
	if fl != nil && streaming {
		fl.Flush()
	}
	resCap := e.cfg.Sink.Capture(f, "res")
	bp := bufPool.Get().(*[]byte)
	buf := *bp
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			resCap.Write(buf[:n])
			if _, werr := w.Write(buf[:n]); werr != nil {
				f.Error = "app closed the stream during the response"
				break
			}
			if fl != nil && streaming {
				fl.Flush()
			}
		}
		if rerr != nil {
			if !errors.Is(rerr, io.EOF) {
				f.Error = "server stopped during the response: " + rerr.Error()
			}
			break
		}
	}
	bufPool.Put(bp)
	for k, vv := range resp.Trailer {
		for _, v := range vv {
			h.Add(http.TrailerPrefix+k, v)
		}
	}
	f.ResTrailers = flow.FromHTTP(resp.Trailer, "")
	f.ReqTrailers = flow.FromHTTP(r.Trailer, "")
	finishBody(&f.Req, reqCap)
	finishBody(&f.Res, resCap)
	if f.Kind == flow.KindGRPC {
		if s := resp.Trailer.Get("Grpc-Status"); s != "" {
			f.AddTag("grpc-status:" + s)
		} else if s := resp.Header.Get("Grpc-Status"); s != "" {
			f.AddTag("grpc-status:" + s)
		}
	}
	e.finish(f, start)
}

func retryableH2(err error) bool {
	s := err.Error()
	return strings.Contains(s, "client connection lost") || strings.Contains(s, "connection is closed") ||
		strings.Contains(s, "GOAWAY") || errors.Is(err, http2.ErrNoCachedConn)
}
