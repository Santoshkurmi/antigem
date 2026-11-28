// Package sniff tells the protocol of a new connection from its first bytes, without consuming them.
package sniff

import (
	"bufio"
	"bytes"
	"errors"
	"net"
	"os"
	"time"
)

// Proto is the detected protocol.
type Proto int

const (
	Unknown     Proto = iota
	TLS               // TLS ClientHello
	HTTP1             // HTTP/1.x request line
	H2C               // HTTP/2 prior-knowledge preface
	ServerFirst       // the client sent nothing: the server speaks first (SMTP, SSH, databases…)
)

func (p Proto) String() string {
	switch p {
	case TLS:
		return "tls"
	case HTTP1:
		return "http1"
	case H2C:
		return "h2c"
	case ServerFirst:
		return "server-first"
	}
	return "unknown"
}

// Conn is a net.Conn whose first bytes can be looked at before they are read.
type Conn struct {
	net.Conn
	R *bufio.Reader
}

// NewConn wraps c (if c already is a *Conn it is returned as is).
func NewConn(c net.Conn) *Conn {
	if pc, ok := c.(*Conn); ok {
		return pc
	}
	return &Conn{Conn: c, R: bufio.NewReaderSize(c, 64<<10)}
}

// WithReader wraps c with an existing reader that may already hold buffered bytes of c.
func WithReader(c net.Conn, r *bufio.Reader) *Conn { return &Conn{Conn: c, R: r} }

func (c *Conn) Read(b []byte) (int, error) { return c.R.Read(b) }

// Peek returns the next n bytes without consuming them.
func (c *Conn) Peek(n int) ([]byte, error) { return c.R.Peek(n) }

var methods = [][]byte{
	[]byte("GET "), []byte("POST "), []byte("PUT "), []byte("HEAD "), []byte("DELETE "), []byte("OPTIONS "),
	[]byte("PATCH "), []byte("CONNECT "), []byte("TRACE "), []byte("PROPFIND "), []byte("PROPPATCH "),
	[]byte("MKCOL "), []byte("COPY "), []byte("MOVE "), []byte("LOCK "), []byte("UNLOCK "), []byte("REPORT "),
	[]byte("SEARCH "), []byte("PURGE "),
}

var h2Preface = []byte("PRI * HTTP/2.0\r\n")

// Detect waits up to firstByte for the client's first byte (none = ServerFirst), then looks at what arrived.
func Detect(c *Conn, firstByte time.Duration) Proto {
	_ = c.SetReadDeadline(time.Now().Add(firstByte))
	_, err := c.Peek(1)
	if err != nil {
		_ = c.SetReadDeadline(time.Time{})
		if errors.Is(err, os.ErrDeadlineExceeded) || isTimeout(err) {
			return ServerFirst
		}
		return Unknown
	}
	// the rest of the first line normally arrives at once; give it a moment if it was split
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	defer c.SetReadDeadline(time.Time{})
	b, _ := c.Peek(1)
	if b[0] == 0x16 {
		if h, err := c.Peek(3); err == nil && h[1] == 0x03 {
			return TLS
		}
		return Unknown
	}
	n := max(c.R.Buffered(), 1)
	for {
		have, _ := c.Peek(min(n, len(h2Preface)))
		if p := classify(have); p != Unknown {
			return p
		}
		if len(have) >= len(h2Preface) || !startOfKnown(have) {
			return Unknown
		}
		if _, err := c.Peek(len(have) + 1); err != nil { // waits for more bytes (up to the deadline)
			return Unknown
		}
		n = c.R.Buffered()
	}
}

func classify(b []byte) Proto {
	if bytes.HasPrefix(b, h2Preface) {
		return H2C
	}
	for _, m := range methods {
		if bytes.HasPrefix(b, m) {
			return HTTP1
		}
	}
	return Unknown
}

// startOfKnown reports whether b could still become a method or the HTTP/2 preface.
func startOfKnown(b []byte) bool {
	if bytes.HasPrefix(h2Preface, b) {
		return true
	}
	for _, m := range methods {
		if bytes.HasPrefix(m, b) {
			return true
		}
	}
	return false
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
