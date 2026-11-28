package service

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"antigem/netcap/internal/flow"
)

// client sends requests the engine makes itself (net_send, map_remote): no compression added, no redirects,
// HTTP/2 when the server offers it, self-signed certificates only for the user's dev servers.
func (s *Service) client() *http.Client {
	s.clientOnce.Do(func() {
		d := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
		dial := d.DialContext
		if s.opts.Dial != nil {
			dial = s.opts.Dial
		}
		tr := &http.Transport{
			DialContext:         dial,
			DisableCompression:  true,
			ForceAttemptHTTP2:   true,
			MaxIdleConnsPerHost: 8,
			IdleConnTimeout:     90 * time.Second,
			TLSHandshakeTimeout: 15 * time.Second,
			Proxy:               nil,
		}
		tr.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, _, _ := net.SplitHostPort(addr)
			raw, err := dial(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			conf := &tls.Config{ServerName: host, NextProtos: []string{"h2", "http/1.1"}}
			s.mu.RLock()
			conf.InsecureSkipVerify = matchHosts(s.settings.InsecureUpstream, host)
			s.mu.RUnlock()
			tc := tls.Client(raw, conf)
			if err := tc.HandshakeContext(ctx); err != nil {
				raw.Close()
				return nil, err
			}
			return tc, nil
		}
		s.httpc = &http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	})
	return s.httpc
}

// SendOptions for Send.
type SendOptions struct {
	Session         string
	From            string // a flow id to send again
	Raw             string // or a raw HTTP request
	Scheme          string // for a raw request with a relative URL (default https)
	Patch           Patch
	FollowRedirects bool
	Timeout         time.Duration
	Secrets         bool
}

type limiter struct {
	mu   sync.Mutex
	hits map[string][]time.Time
}

// allow: at most 30 sends a minute per host (protects servers and the user's account).
func (l *limiter) allow(host string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.hits == nil {
		l.hits = map[string][]time.Time{}
	}
	now := time.Now()
	keep := l.hits[host][:0]
	for _, t := range l.hits[host] {
		if now.Sub(t) < time.Minute {
			keep = append(keep, t)
		}
	}
	if len(keep) >= 30 {
		l.hits[host] = keep
		return false
	}
	l.hits[host] = append(keep, now)
	return true
}

var hopHeaders = map[string]bool{"host": true, ":authority": true, "content-length": true, "connection": true,
	"transfer-encoding": true, "keep-alive": true, "proxy-connection": true, "upgrade": true, "te": false}

// Send sends one request (a stored flow again, or raw HTTP) with optional changes, records it and shows the answer.
func (s *Service) Send(ctx context.Context, o SendOptions) (string, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return "", err
	}
	var req *http.Request
	var from *flow.Flow
	switch {
	case o.From != "":
		id, _, err := ParseID(o.From)
		if err != nil {
			return "", err
		}
		if from, err = sess.Store.Get(id); err != nil {
			return "", err
		}
		if from.Kind != flow.KindHTTP && from.Kind != flow.KindGRPC {
			return "", fmt.Errorf("%s is a %s flow: only HTTP requests can be sent again", FID(id), from.Kind)
		}
		var body []byte
		if from.Req.Hash != "" {
			if from.Req.Truncated {
				return "", fmt.Errorf("%s's request body was only stored in part (%s of %s): it can't be sent again", FID(id), Size(from.Req.Stored), Size(from.Req.Size))
			}
			if body, _, err = s.Body(sess, from, "req", true, 0); err != nil {
				return "", err
			}
		}
		req, err = http.NewRequestWithContext(ctx, from.Method, from.URL(), bytes.NewReader(body))
		if err != nil {
			return "", err
		}
		for _, kv := range from.ReqHeaders {
			if hopHeaders[strings.ToLower(kv[0])] {
				continue
			}
			req.Header.Add(kv[0], kv[1])
		}
		req.ContentLength = int64(len(body))
	case o.Raw != "":
		raw := strings.ReplaceAll(strings.TrimLeft(o.Raw, "\r\n"), "\r\n", "\n")
		head, body, _ := strings.Cut(raw, "\n\n")
		head = strings.ReplaceAll(head, "\n", "\r\n") + "\r\n\r\n"
		r, err := http.ReadRequest(bufio.NewReader(strings.NewReader(head)))
		if err != nil {
			return "", fmt.Errorf("raw request: %w", err)
		}
		scheme := o.Scheme
		if scheme == "" {
			scheme = "https"
		}
		u := r.URL
		if !u.IsAbs() {
			u, err = url.Parse(scheme + "://" + r.Host + r.RequestURI)
			if err != nil {
				return "", err
			}
		}
		req, err = http.NewRequestWithContext(ctx, r.Method, u.String(), strings.NewReader(body))
		if err != nil {
			return "", err
		}
		for k, vv := range r.Header {
			if hopHeaders[strings.ToLower(k)] {
				continue
			}
			req.Header[k] = vv
		}
		req.ContentLength = int64(len(body))
	default:
		return "", errors.New("give from (a flow id) or raw (an HTTP request)")
	}
	if _, ok := req.Header["User-Agent"]; !ok {
		req.Header["User-Agent"] = []string{""}
	}
	// changes
	if !o.Patch.Empty() {
		if o.Patch.URL != "" {
			u, err := url.Parse(o.Patch.URL)
			if err != nil {
				return "", fmt.Errorf("url: %w", err)
			}
			if !u.IsAbs() {
				u = req.URL.ResolveReference(u)
			}
			req.URL, req.Host = u, u.Host
		}
		pr, err := o.Patch.asRule()
		if err != nil {
			return "", err
		}
		lb := &liveBody{}
		get := func() []byte {
			wasRead := lb.read
			d, _ := lb.load(req.Body, req.Header.Get("Content-Encoding"))
			if !wasRead && lb.read {
				req.Body = lb.reader()
			}
			return d
		}
		if err := s.rewriteRequest(req, lb, get, pr); err != nil {
			return "", err
		}
		if lb.read {
			setReqBody(req, lb)
		}
	}
	if !s.limit.allow(strings.ToLower(req.URL.Hostname())) {
		return "", fmt.Errorf("more than 30 requests a minute to %s: wait a moment (this protects the server and your account)", req.URL.Hostname())
	}
	if o.Timeout <= 0 {
		o.Timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, o.Timeout)
	defer cancel()
	req = req.WithContext(ctx)

	// recorded like captured traffic, marked as sent by netcap
	f := &flow.Flow{ID: sess.Store.NewID(), Kind: flow.KindHTTP, App: "netcap (sent)", Start: time.Now(), Scheme: req.URL.Scheme,
		Host: req.URL.Hostname(), Port: portNum(req.URL), Method: req.Method, Path: req.URL.EscapedPath(), Query: req.URL.RawQuery}
	if f.Path == "" {
		f.Path = "/"
	}
	if from != nil {
		f.ResentFrom = from.ID
		f.App = from.App
		f.Flags = append(f.Flags, "sent")
	} else {
		f.Flags = append(f.Flags, "sent")
	}
	if !o.Patch.Empty() {
		f.Flags = append(f.Flags, "modified")
	}
	if _, err := s.roundTripRecorded(sess, req, f, o.FollowRedirects, nil); err != nil {
		return "", err
	}
	return s.Get(GetOptions{Session: sess.ID, ID: FID(f.ID), Secrets: o.Secrets})
}

func portNum(u *url.URL) int {
	if p, err := strconv.Atoi(u.Port()); err == nil {
		return p
	}
	if u.Scheme == "http" {
		return 80
	}
	return 443
}
