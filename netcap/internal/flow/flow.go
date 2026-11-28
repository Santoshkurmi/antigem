// Package flow holds the data model shared by the engine, the store and the front ends: one Flow per HTTP request,
// WebSocket, gRPC call or other connection, and the Messages inside WebSocket and raw flows.
package flow

import (
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Kind of a flow.
type Kind string

const (
	KindHTTP Kind = "http"
	KindWS   Kind = "ws"
	KindGRPC Kind = "grpc"
	KindTCP  Kind = "tcp"
	KindUDP  Kind = "udp"
	KindTLS  Kind = "tls" // an encrypted connection that was not decrypted (passthrough, or the handshake failed)
)

// TLS state of a flow.
const (
	TLSPlain       = "plain"
	TLSDecrypted   = "decrypted"
	TLSPassthrough = "passthrough"
	TLSUntrusted   = "untrusted" // the app refused the AntiGem certificate
	TLSFailed      = "failed"
)

// Headers keeps names, values and order as seen (HTTP/2 names are lowercase).
type Headers [][2]string

// Get returns the first value of name (case-insensitive).
func (h Headers) Get(name string) string {
	for _, kv := range h {
		if strings.EqualFold(kv[0], name) {
			return kv[1]
		}
	}
	return ""
}

// Has reports whether name is present (case-insensitive).
func (h Headers) Has(name string) bool {
	for _, kv := range h {
		if strings.EqualFold(kv[0], name) {
			return true
		}
	}
	return false
}

// FromHTTP converts a Go header map. Go keeps no order, so names are sorted for stable output; pseudo-like "Host"
// comes first when given.
func FromHTTP(h http.Header, host string) Headers {
	out := make(Headers, 0, len(h)+1)
	if host != "" {
		out = append(out, [2]string{"Host", host})
	}
	keys := make([]string, 0, len(h))
	for k := range h {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		for _, v := range h[k] {
			out = append(out, [2]string{k, v})
		}
	}
	return out
}

// Body describes a captured request or response body. The bytes live in the session's blob store.
type Body struct {
	Hash        string `json:"hash,omitempty"`      // blob id (sha256 of the stored bytes); empty = no body stored
	Size        int64  `json:"size"`                // bytes seen on the wire (content-encoded, transfer-decoded)
	Stored      int64  `json:"stored,omitempty"`    // bytes kept (≤ Size)
	Truncated   bool   `json:"truncated,omitempty"` // Size > Stored
	ContentType string `json:"content_type,omitempty"`
	Encoding    string `json:"encoding,omitempty"` // Content-Encoding
	Type        string `json:"type,omitempty"`     // short type: json, html, image, …
}

// TLSInfo of a decrypted or passed-through connection.
type TLSInfo struct {
	Version       string   `json:"version,omitempty"`
	Cipher        string   `json:"cipher,omitempty"`
	ALPN          string   `json:"alpn,omitempty"`
	SNI           string   `json:"sni,omitempty"`
	ClientALPN    []string `json:"client_alpn,omitempty"`
	ServerSubject string   `json:"server_subject,omitempty"`
	ServerIssuer  string   `json:"server_issuer,omitempty"`
	ServerExpires string   `json:"server_expires,omitempty"`
}

// Timings in milliseconds; zero = not measured (for example connect/TLS only on a connection's first request).
type Timings struct {
	Connect float64 `json:"connect,omitempty"`
	TLS     float64 `json:"tls,omitempty"`
	TTFB    float64 `json:"ttfb,omitempty"` // request sent → response headers
	Total   float64 `json:"total,omitempty"`
}

// Flow is one request/response, WebSocket, gRPC call or connection.
type Flow struct {
	ID          int64     `json:"id"`
	Kind        Kind      `json:"kind"`
	App         string    `json:"app,omitempty"`
	UID         int       `json:"uid,omitempty"`
	Start       time.Time `json:"start"`
	End         time.Time `json:"end,omitempty"`
	Client      string    `json:"client,omitempty"`
	Server      string    `json:"server,omitempty"`
	Scheme      string    `json:"scheme,omitempty"`
	Host        string    `json:"host,omitempty"`
	Port        int       `json:"port,omitempty"`
	Method      string    `json:"method,omitempty"`
	Path        string    `json:"path,omitempty"`
	Query       string    `json:"query,omitempty"`
	HTTPVersion string    `json:"http,omitempty"`
	Status      int       `json:"status,omitempty"`
	ReqHeaders  Headers   `json:"req_headers,omitempty"`
	ResHeaders  Headers   `json:"res_headers,omitempty"`
	ReqTrailers Headers   `json:"req_trailers,omitempty"`
	ResTrailers Headers   `json:"res_trailers,omitempty"`
	Req         Body      `json:"req"`
	Res         Body      `json:"res"`
	TLS         string    `json:"tls,omitempty"`
	TLSInfo     *TLSInfo  `json:"tls_info,omitempty"`
	Timings     Timings   `json:"timings"`
	Error       string    `json:"error,omitempty"`
	Tags        []string  `json:"tags,omitempty"`
	Note        string    `json:"note,omitempty"`
	Endpoint    string    `json:"endpoint,omitempty"` // templated "METHOD host/path" for grouping
	Messages    int       `json:"messages,omitempty"`
	BytesOut    int64     `json:"bytes_out,omitempty"` // client → server (tcp/tls/ws)
	BytesIn     int64     `json:"bytes_in,omitempty"`  // server → client
	Flags       []string  `json:"flags,omitempty"`     // modified, mocked, …
	ResentFrom  int64     `json:"resent_from,omitempty"`
	Done        bool      `json:"done"`
}

// Clone returns a copy that does not share slices with f (the engine keeps changing f while the store writes it).
func (f *Flow) Clone() *Flow {
	c := *f
	c.ReqHeaders = append(Headers(nil), f.ReqHeaders...)
	c.ResHeaders = append(Headers(nil), f.ResHeaders...)
	c.ReqTrailers = append(Headers(nil), f.ReqTrailers...)
	c.ResTrailers = append(Headers(nil), f.ResTrailers...)
	c.Tags = append([]string(nil), f.Tags...)
	c.Flags = append([]string(nil), f.Flags...)
	if f.TLSInfo != nil {
		t := *f.TLSInfo
		t.ClientALPN = append([]string(nil), f.TLSInfo.ClientALPN...)
		c.TLSInfo = &t
	}
	return &c
}

// URL of an HTTP flow (scheme://host[:port]/path?query); for other kinds host:port.
func (f *Flow) URL() string {
	if f.Scheme == "" {
		return f.Host + ":" + strconv.Itoa(f.Port)
	}
	var b strings.Builder
	b.WriteString(f.Scheme)
	b.WriteString("://")
	b.WriteString(f.Host)
	if f.Port != 0 && !((f.Scheme == "https" && f.Port == 443) || (f.Scheme == "http" && f.Port == 80)) {
		b.WriteString(":")
		b.WriteString(strconv.Itoa(f.Port))
	}
	b.WriteString(f.Path)
	if f.Query != "" {
		b.WriteString("?")
		b.WriteString(f.Query)
	}
	return b.String()
}

// HasTag reports whether the flow carries tag t.
func (f *Flow) HasTag(t string) bool {
	for _, x := range f.Tags {
		if strings.EqualFold(x, t) {
			return true
		}
	}
	return false
}

// AddTag adds t once.
func (f *Flow) AddTag(t string) {
	if !f.HasTag(t) {
		f.Tags = append(f.Tags, t)
	}
}

// Message is one WebSocket message or one chunk of a raw connection.
type Message struct {
	FlowID     int64     `json:"flow"`
	Index      int       `json:"index"`
	Dir        string    `json:"dir"` // "out" = app → server, "in" = server → app
	Time       time.Time `json:"time"`
	Type       string    `json:"type"` // text, binary, close, chunk
	Size       int       `json:"size"` // full payload size
	Data       []byte    `json:"-"`    // payload, possibly cut (Truncated)
	Truncated  bool      `json:"truncated,omitempty"`
	Compressed bool      `json:"compressed,omitempty"` // was permessage-deflate on the wire (Data is inflated)
}

// DNS is one DNS query with its answer.
type DNS struct {
	ID       int64     `json:"id"`
	Time     time.Time `json:"time"`
	App      string    `json:"app,omitempty"`
	Name     string    `json:"name"`
	Type     string    `json:"type"`
	Answers  []string  `json:"answers,omitempty"`
	RCode    string    `json:"rcode,omitempty"`
	Duration float64   `json:"ms,omitempty"`
}
