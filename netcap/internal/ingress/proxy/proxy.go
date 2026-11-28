// Package proxy is the proxy-port ingress: an HTTP proxy (CONNECT and plain absolute-URL requests) and SOCKS5 on
// one port. Used on a PC, by terminal tools on the phone, and by tests.
package proxy

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"antigem/netcap/internal/intercept"
	"antigem/netcap/internal/sniff"
)

// Server listens on Addr.
type Server struct {
	Engine  *intercept.Engine
	Addr    string
	CertPEM []byte // served at http://<proxy>/ca.crt for easy install
	Logf    func(string, ...any)
	ln      net.Listener
}

// Listen opens the port (separate from Serve so callers know it worked).
func (s *Server) Listen() error {
	ln, err := net.Listen("tcp", s.Addr)
	if err != nil {
		return err
	}
	s.ln = ln
	s.Addr = ln.Addr().String()
	return nil
}

// Serve accepts connections until ctx ends.
func (s *Server) Serve(ctx context.Context) error {
	if s.ln == nil {
		if err := s.Listen(); err != nil {
			return err
		}
	}
	go func() {
		<-ctx.Done()
		s.ln.Close()
	}()
	for {
		c, err := s.ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			return err
		}
		go s.handle(ctx, c)
	}
}

func (s *Server) handle(ctx context.Context, c net.Conn) {
	pc := sniff.NewConn(c)
	_ = pc.SetReadDeadline(time.Now().Add(30 * time.Second))
	b, err := pc.Peek(1)
	if err != nil {
		c.Close()
		return
	}
	meta := intercept.Meta{Client: c.RemoteAddr().String(), Ingress: "proxy"}
	if b[0] == 0x05 {
		s.socks5(ctx, pc, meta)
		return
	}
	req, err := http.ReadRequest(pc.R)
	if err != nil {
		c.Close()
		return
	}
	_ = pc.SetReadDeadline(time.Time{})
	switch {
	case req.Method == http.MethodConnect:
		host, portStr, err := net.SplitHostPort(req.RequestURI)
		if err != nil {
			host, portStr = req.RequestURI, "443"
		}
		port, _ := strconv.Atoi(portStr)
		if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			c.Close()
			return
		}
		s.Engine.HandleTCP(ctx, pc, intercept.Target{Addr: net.JoinHostPort(host, portStr), Host: strings.Trim(host, "[]"), Port: port}, meta)
	case req.URL.IsAbs():
		s.Engine.ServeProxyHTTP(ctx, pc, req, meta)
	default:
		s.local(c, req)
	}
}

// local answers requests made to the proxy port itself: the CA certificate and a short help page.
func (s *Server) local(c net.Conn, req *http.Request) {
	defer c.Close()
	if strings.HasPrefix(req.URL.Path, "/ca") && len(s.CertPEM) > 0 {
		fmt.Fprintf(c, "HTTP/1.1 200 OK\r\nContent-Type: application/x-x509-ca-cert\r\nContent-Disposition: attachment; filename=\"antigem-netcap-ca.crt\"\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", len(s.CertPEM))
		c.Write(s.CertPEM)
		return
	}
	page := "AntiGem Network Interceptor proxy.\n\nUse this address as an HTTP or SOCKS5 proxy.\n" +
		"To see HTTPS, trust the CA certificate: http://" + req.Host + "/ca.crt\n"
	fmt.Fprintf(c, "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(page), page)
}

func (s *Server) socks5(ctx context.Context, pc *sniff.Conn, meta intercept.Meta) {
	fail := func() { pc.Close() }
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(pc, hdr); err != nil || hdr[0] != 5 {
		fail()
		return
	}
	if _, err := io.ReadFull(pc, make([]byte, hdr[1])); err != nil {
		fail()
		return
	}
	if _, err := pc.Write([]byte{5, 0}); err != nil { // no authentication
		fail()
		return
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(pc, req); err != nil || req[0] != 5 {
		fail()
		return
	}
	reply := func(code byte) { pc.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0}) }
	var host string
	switch req[3] {
	case 1:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(pc, ip); err != nil {
			fail()
			return
		}
		host = net.IP(ip).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(pc, l); err != nil {
			fail()
			return
		}
		name := make([]byte, l[0])
		if _, err := io.ReadFull(pc, name); err != nil {
			fail()
			return
		}
		host = string(name)
	case 4:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(pc, ip); err != nil {
			fail()
			return
		}
		host = net.IP(ip).String()
	default:
		reply(8)
		fail()
		return
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(pc, pb); err != nil {
		fail()
		return
	}
	port := int(binary.BigEndian.Uint16(pb))
	if req[1] != 1 { // only CONNECT
		reply(7)
		fail()
		return
	}
	reply(0)
	_ = pc.SetReadDeadline(time.Time{})
	s.Engine.HandleTCP(ctx, pc, intercept.Target{Addr: net.JoinHostPort(host, strconv.Itoa(port)), Host: host, Port: port}, meta)
}
