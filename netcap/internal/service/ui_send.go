package service

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/view"
)

// UISend is the GUI's request composer: a whole request, not a patch. With From, every field left out comes from
// that stored flow (its raw request body too, any size), so {from} alone sends it again exactly. The user's own
// sends skip the AI's per-host rate limit; Repeat is capped at 1000 sends, 16 at once.
type UISend struct {
	Session         string
	From            string        // flow id ("f12" or "12"); "" = a fresh request
	Method          *string       //
	URL             *string       //
	Headers         *flow.Headers // the whole list; an empty list = no headers
	BodyText        *string       // the body as text (encoded to match a Content-Encoding header)
	BodyBase64      *string       // or bytes, sent as they are
	BodyFile        *string       // or a file, streamed
	FollowRedirects bool
	Timeout         time.Duration
	Count           int
	Interval        time.Duration
	Concurrency     int
}

// UISendResult lists the recorded flows (also failed sends) and the errors.
type UISendResult struct {
	IDs    []int64  `json:"ids"`
	Errors []string `json:"errors"`
}

type sendPlan struct {
	method, url, host string
	header            http.Header
	body              func() (io.ReadCloser, int64, error) // opened again for every send
	from              *flow.Flow
	modified          bool
}

// SendUI sends the composer's request (Count times) and returns when all sends finished.
func (s *Service) SendUI(ctx context.Context, o UISend) (*UISendResult, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return nil, err
	}
	p, err := s.planSend(sess, o)
	if err != nil {
		return nil, err
	}
	if o.Timeout <= 0 {
		o.Timeout = 30 * time.Second
	}
	o.Timeout = min(o.Timeout, 10*time.Minute)
	count := min(max(o.Count, 1), 1000)
	conc := min(max(o.Concurrency, 1), 16)
	interval := max(o.Interval, 0)
	ctx, cancel := context.WithTimeout(ctx, o.Timeout*time.Duration(count)+interval*time.Duration(count))
	defer cancel()
	res := &UISendResult{IDs: []int64{}, Errors: []string{}}
	ids := make([]int64, count)
	errs := make([]error, count)
	sem := make(chan struct{}, conc)
	var wg sync.WaitGroup
	for i := 0; i < count; i++ {
		if i > 0 && interval > 0 {
			if sleepCtx(ctx, interval) != nil {
				errs[i] = ctx.Err()
				break
			}
		}
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
			errs[i] = ctx.Err()
		}
		if errs[i] != nil {
			break
		}
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			defer func() { <-sem }()
			ids[i], errs[i] = s.sendOnce(ctx, sess, p, o)
		}(i)
	}
	wg.Wait()
	for i := range ids {
		if ids[i] != 0 {
			res.IDs = append(res.IDs, ids[i])
		}
		if errs[i] != nil {
			res.Errors = append(res.Errors, errs[i].Error())
		}
	}
	return res, nil
}

// planSend works out the request once (defaults from the stored flow, then the composer's fields).
func (s *Service) planSend(sess *Session, o UISend) (*sendPlan, error) {
	p := &sendPlan{header: http.Header{}, body: func() (io.ReadCloser, int64, error) { return http.NoBody, 0, nil }}
	if o.From != "" {
		id, _, err := ParseID(o.From)
		if err != nil {
			return nil, err
		}
		f, err := sess.Store.Get(id)
		if err != nil {
			return nil, err
		}
		if f.Kind != flow.KindHTTP && f.Kind != flow.KindGRPC {
			return nil, fmt.Errorf("%s is a %s flow: only HTTP requests can be sent again", FID(id), f.Kind)
		}
		p.from, p.method, p.url = f, f.Method, f.URL()
		p.header = headersOf(f.ReqHeaders, &p.host)
		for k := range p.header {
			if hopHeaders[strings.ToLower(k)] {
				delete(p.header, k)
			}
		}
		if f.Req.Hash != "" && o.BodyText == nil && o.BodyBase64 == nil && o.BodyFile == nil {
			if f.Req.Truncated {
				return nil, fmt.Errorf("%s's request body was only stored in part (%s of %s): it can't be sent again", FID(id), Size(f.Req.Stored), Size(f.Req.Size))
			}
			hash, size := f.Req.Hash, f.Req.Stored
			p.body = func() (io.ReadCloser, int64, error) {
				r, err := sess.Store.Blobs.Open(hash)
				return r, size, err
			}
		}
	}
	if o.Method != nil {
		p.method = strings.ToUpper(strings.TrimSpace(*o.Method))
		p.modified = true
	}
	if p.method == "" {
		p.method = http.MethodGet
	}
	if o.URL != nil {
		p.url = strings.TrimSpace(*o.URL)
		p.host = "" // the Host header follows the new URL (unless the headers name one)
		p.modified = true
	}
	u, err := url.Parse(p.url)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, fmt.Errorf("url must be http(s)://host/…, not %q", p.url)
	}
	if o.Headers != nil {
		p.host = ""
		p.header = headersOf(*o.Headers, &p.host)
		p.modified = true
	}
	if _, ok := p.header["User-Agent"]; !ok {
		p.header["User-Agent"] = []string{""} // keep Go from adding its own
	}
	switch {
	case o.BodyFile != nil:
		path := *o.BodyFile
		st, err := os.Stat(path)
		if err != nil || st.IsDir() {
			return nil, fmt.Errorf("body_file %s is not a readable file", path)
		}
		p.body = func() (io.ReadCloser, int64, error) {
			f, err := os.Open(path)
			if err != nil {
				return nil, 0, err
			}
			st, err := f.Stat()
			if err != nil {
				f.Close()
				return nil, 0, err
			}
			return f, st.Size(), nil
		}
		p.modified = true
	case o.BodyBase64 != nil:
		data, err := base64.StdEncoding.DecodeString(strings.TrimSpace(*o.BodyBase64))
		if err != nil {
			return nil, fmt.Errorf("body_base64: %w", err)
		}
		p.body = bytesBody(data)
		p.modified = true
	case o.BodyText != nil:
		data := []byte(*o.BodyText)
		if ce := p.header.Get("Content-Encoding"); ce != "" && len(data) > 0 {
			enc, err := view.Encode(data, ce)
			if err != nil {
				return nil, fmt.Errorf("can't encode body_text as %q (send body_base64, already encoded): %w", ce, err)
			}
			data = enc
		}
		p.body = bytesBody(data)
		p.modified = true
	}
	return p, nil
}

func bytesBody(data []byte) func() (io.ReadCloser, int64, error) {
	return func() (io.ReadCloser, int64, error) {
		return io.NopCloser(bytes.NewReader(data)), int64(len(data)), nil
	}
}

// sendOnce sends the planned request and records it as a flow (returned even when the send failed).
func (s *Service) sendOnce(ctx context.Context, sess *Session, p *sendPlan, o UISend) (int64, error) {
	ctx, cancel := context.WithTimeout(ctx, o.Timeout)
	defer cancel()
	body, size, err := p.body()
	if err != nil {
		return 0, err
	}
	var rb io.Reader = body
	if size == 0 {
		body.Close()
		rb = nil
	}
	req, err := http.NewRequestWithContext(ctx, p.method, p.url, rb)
	if err != nil {
		if rb != nil {
			body.Close()
		}
		return 0, err
	}
	if rb != nil {
		req.Body = body
	}
	req.ContentLength = size
	req.Header = p.header.Clone()
	if p.host != "" {
		req.Host = p.host
	}
	f := &flow.Flow{ID: sess.Store.NewID(), Kind: flow.KindHTTP, App: "netcap (sent)", Start: time.Now(), Scheme: req.URL.Scheme,
		Host: req.URL.Hostname(), Port: portNum(req.URL), Method: req.Method, Path: req.URL.EscapedPath(), Query: req.URL.RawQuery,
		Flags: []string{"sent"}}
	if f.Path == "" {
		f.Path = "/"
	}
	if p.from != nil {
		f.ResentFrom, f.App = p.from.ID, p.from.App
	}
	if p.modified && p.from != nil {
		f.Flags = append(f.Flags, "modified")
	}
	if _, err := s.roundTripRecorded(sess, req, f, o.FollowRedirects, nil); err != nil {
		return f.ID, err
	}
	return f.ID, nil
}
