package service

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/view"
)

// Full replacements of a paused request or response: the GUI's breakpoint editor sends the whole header list and
// the whole body (any size, also from a file) instead of a patch.

// headersOf turns a header list into http.Header. Framing headers (Content-Length, Transfer-Encoding) and pseudo
// headers are left out (the engine sets the framing); Host / :authority goes to host when given.
func headersOf(list flow.Headers, host *string) http.Header {
	h := http.Header{}
	for _, kv := range list {
		k := strings.TrimSpace(kv[0])
		lk := strings.ToLower(k)
		switch {
		case k == "":
			continue
		case lk == "host" || lk == ":authority":
			if host != nil {
				*host = strings.TrimSpace(kv[1])
			}
			continue
		case strings.HasPrefix(k, ":"), lk == "content-length", lk == "transfer-encoding":
			continue
		}
		h.Add(k, kv[1])
	}
	return h
}

// patchBody opens the new body of a patch: bytes (text is encoded to match contentEncoding) or a file.
func patchBody(p Patch, contentEncoding string) (io.ReadCloser, int64, []byte, error) {
	if p.BodyFile != "" {
		f, err := os.Open(p.BodyFile)
		if err != nil {
			return nil, 0, nil, fmt.Errorf("body_file: %w", err)
		}
		st, err := f.Stat()
		if err != nil || st.IsDir() {
			f.Close()
			return nil, 0, nil, fmt.Errorf("body_file %s is not a file", p.BodyFile)
		}
		return f, st.Size(), nil, nil
	}
	data := p.Body
	if p.BodyPlain && contentEncoding != "" {
		enc, err := view.Encode(data, contentEncoding)
		if err != nil {
			return nil, 0, nil, fmt.Errorf("can't encode the body as %q (send it as body_base64, already encoded): %w", contentEncoding, err)
		}
		data = enc
	}
	return io.NopCloser(bytes.NewReader(data)), int64(len(data)), data, nil
}

// replaceRequest puts the patch's whole header list and body into a paused request.
func replaceRequest(req *http.Request, lb *liveBody, p Patch) error {
	if p.Headers != nil {
		host := ""
		h := headersOf(*p.Headers, &host)
		if _, ok := h["User-Agent"]; !ok {
			h["User-Agent"] = []string{""} // keep Go from adding its own
		}
		req.Header = h
		if host != "" {
			req.Host = host
		}
	}
	if !p.BodySet && p.BodyFile == "" {
		return nil
	}
	rc, size, data, err := patchBody(p, req.Header.Get("Content-Encoding"))
	if err != nil {
		return err
	}
	lb.replaceAll(rc, size, data)
	setReqBody(req, lb)
	return nil
}

func (s *Service) rewriteStatus(resp *http.Response, p Patch) error {
	if p.Status != 0 {
		if p.Status < 100 || p.Status > 999 {
			return fmt.Errorf("bad status %d", p.Status)
		}
		resp.StatusCode = p.Status
		resp.Status = fmt.Sprintf("%d %s", p.Status, http.StatusText(p.Status))
	}
	return nil
}

// replaceResponse builds the answer with the patch's body (and header list) in place of a paused response.
func replaceResponse(req *http.Request, resp *http.Response, p Patch) (*http.Response, error) {
	h := resp.Header.Clone()
	if p.Headers != nil {
		h = headersOf(*p.Headers, nil)
	}
	rc, size, _, err := patchBody(p, h.Get("Content-Encoding"))
	if err != nil {
		return nil, err
	}
	h.Del("Transfer-Encoding")
	h.Set("Content-Length", strconv.FormatInt(size, 10))
	return &http.Response{StatusCode: resp.StatusCode, Status: resp.Status, Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
		Header: h, Body: rc, ContentLength: size, Request: req}, nil
}

// answerResp is a response given whole (breakpoint "respond" from the GUI).
func answerResp(req *http.Request, a *Answer) *http.Response {
	status := a.Status
	if status == 0 {
		status = 200
	}
	h := headersOf(a.Headers, nil)
	body := a.Body
	if a.BodyPlain {
		if ce := h.Get("Content-Encoding"); ce != "" {
			if enc, err := view.Encode(body, ce); err == nil {
				body = enc
			} else {
				h.Del("Content-Encoding")
			}
		}
	}
	h.Set("Content-Length", strconv.Itoa(len(body)))
	h.Set("X-Netcap", "mock")
	return &http.Response{StatusCode: status, Status: fmt.Sprintf("%d %s", status, http.StatusText(status)), Proto: "HTTP/1.1",
		ProtoMajor: 1, ProtoMinor: 1, Header: h, Body: io.NopCloser(bytes.NewReader(body)), ContentLength: int64(len(body)), Request: req}
}
