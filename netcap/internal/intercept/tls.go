package intercept

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"strings"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/sniff"
	"antigem/netcap/internal/tlsx"
)

func (e *Engine) handleTLS(ctx context.Context, pc *sniff.Conn, t *Target, m Meta) {
	_ = pc.SetReadDeadline(time.Now().Add(10 * time.Second))
	hello, err := tlsx.PeekClientHello(pc)
	_ = pc.SetReadDeadline(time.Time{})
	if err != nil {
		if e.cfg.Decide(m, t.Host).Record {
			e.relayRaw(ctx, pc, t, m)
		} else {
			e.relayPlain(ctx, pc, t)
		}
		return
	}
	host := hello.SNI
	if host == "" {
		host = t.Host
	}
	d := e.cfg.Decide(m, host)
	if !d.Record {
		e.relayPlain(ctx, pc, t)
		return
	}
	if t.Port == 853 { // DNS over TLS (Android Private DNS): keep the system resolver's own encryption
		d.Decrypt = false
	}
	if !d.Decrypt {
		e.passthrough(ctx, pc, t, m, hello, host, flow.TLSPassthrough, "")
		return
	}
	if e.cfg.Untrusted != nil && e.cfg.Untrusted.Is(m.App, host) {
		e.passthrough(ctx, pc, t, m, hello, host, flow.TLSUntrusted, "")
		return
	}
	ci := &connInfo{scheme: "https", t: *t, m: m, sni: hello.SNI, tlsState: flow.TLSDecrypted}
	ci.t.Host = host
	ci.t.Pre = nil

	// connect to the real server first: its certificate names and ALPN decide ours
	t0 := time.Now()
	raw, err := e.dialTarget(ctx, t)
	if err != nil {
		e.failFlow(ci, hello, flow.TLSFailed, "connect "+t.Addr+": "+err.Error())
		return
	}
	ci.connectMs = ms(time.Since(t0))
	ci.server = raw.RemoteAddr().String()
	serverName := hello.SNI
	if serverName == "" {
		serverName = host
	}
	upConf := &tls.Config{
		ServerName: serverName,
		NextProtos: offerALPN(hello.ALPN),
		RootCAs:    e.cfg.RootCAs,
		MinVersion: tls.VersionTLS10,
	}
	if e.cfg.InsecureUpstream != nil && e.cfg.InsecureUpstream(host) {
		upConf.InsecureSkipVerify = true
	}
	up := tls.Client(raw, upConf)
	t1 := time.Now()
	hctx, cancel := context.WithTimeout(ctx, handshakeMax)
	err = up.HandshakeContext(hctx)
	cancel()
	if err != nil {
		raw.Close()
		msg := "server TLS handshake: " + err.Error()
		var verr *tls.CertificateVerificationError
		if errors.As(err, &verr) {
			msg = "the server's certificate is not valid (" + err.Error() + "); the app was not connected"
		}
		e.failFlow(ci, hello, flow.TLSFailed, msg)
		return
	}
	st := up.ConnectionState()
	alpn := st.NegotiatedProtocol
	var names []string
	var ips []net.IP
	if hello.SNI != "" {
		names = append(names, hello.SNI)
	}
	var leaf = st.PeerCertificates[0]
	names = append(names, leaf.DNSNames...)
	if hello.SNI == "" {
		if ip := net.ParseIP(hostOnly(t.Addr)); ip != nil {
			ips = append(ips, ip)
		} else if host != "" {
			names = append([]string{host}, names...)
		}
	}
	ips = append(ips, leaf.IPAddresses...)
	cert, err := e.cfg.Certs.Get(names, ips)
	if err != nil {
		up.Close()
		e.failFlow(ci, hello, flow.TLSFailed, "making certificate: "+err.Error())
		return
	}
	srvConf := &tls.Config{Certificates: []tls.Certificate{*cert}, MinVersion: tls.VersionTLS10}
	if alpn != "" {
		srvConf.NextProtos = []string{alpn}
	}
	cli := tls.Server(pc, srvConf)
	hctx, cancel = context.WithTimeout(ctx, handshakeMax)
	err = cli.HandshakeContext(hctx)
	cancel()
	if err != nil {
		up.Close()
		if untrustedErr(err) && e.cfg.Untrusted != nil {
			e.cfg.Untrusted.Mark(m.App, host, err.Error())
			e.failFlow(ci, hello, flow.TLSUntrusted, "the app did not accept the AntiGem certificate ("+err.Error()+
				"); from now on this host passes through without decryption")
			return
		}
		e.failFlow(ci, hello, flow.TLSFailed, "app TLS handshake: "+err.Error())
		return
	}
	ci.tlsMs = ms(time.Since(t1))
	ci.tls = &flow.TLSInfo{
		Version:       tls.VersionName(st.Version),
		Cipher:        tls.CipherSuiteName(st.CipherSuite),
		ALPN:          alpn,
		SNI:           hello.SNI,
		ClientALPN:    hello.ALPN,
		ServerSubject: leaf.Subject.String(),
		ServerIssuer:  leaf.Issuer.String(),
		ServerExpires: leaf.NotAfter.UTC().Format("2006-01-02"),
	}
	if alpn == "h2" {
		e.serveH2(ctx, cli, up, ci)
		return
	}
	e.serveH1(ctx, sniff.NewConn(cli), ci, up, nil)
}

// offerALPN keeps the protocols the engine can speak, in the client's order.
func offerALPN(client []string) []string {
	var out []string
	for _, p := range client {
		if p == "h2" || p == "http/1.1" || p == "http/1.0" {
			out = append(out, p)
		}
	}
	return out
}

// untrustedErr: the client rejected our certificate (alert) or hung up right after seeing it.
func untrustedErr(err error) bool {
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}
	s := err.Error()
	// Go's names of the TLS alerts a client sends when it rejects a certificate (Android's Conscrypt sends
	// "unknown certificate", others "unknown certificate authority" or "bad certificate")
	for _, k := range []string{"unknown certificate", "bad certificate", "unsupported certificate", "revoked certificate",
		"expired certificate", "access denied", "connection reset by peer", "broken pipe", "EOF"} {
		if strings.Contains(s, k) {
			return true
		}
	}
	return false
}

// failFlow records a connection whose TLS could not be set up.
func (e *Engine) failFlow(ci *connInfo, hello *tlsx.ClientHello, state, msg string) {
	f := e.cfg.Sink.NewFlow(flow.KindTLS, ci.m)
	f.Host, f.Port, f.TLS, f.Error = ci.t.Host, ci.t.Port, state, msg
	f.Server = ci.server
	if f.Server == "" {
		f.Server = ci.t.Addr
	}
	f.TLSInfo = &flow.TLSInfo{SNI: hello.SNI, ClientALPN: hello.ALPN}
	f.Timings.Connect = ci.connectMs
	e.finish(f, f.Start)
}

// passthrough relays an encrypted connection without decrypting it and records it as one flow.
func (e *Engine) passthrough(ctx context.Context, pc *sniff.Conn, t *Target, m Meta, hello *tlsx.ClientHello, host, state, note string) {
	f := e.cfg.Sink.NewFlow(flow.KindTLS, m)
	f.Host, f.Port, f.TLS = host, t.Port, state
	f.TLSInfo = &flow.TLSInfo{SNI: hello.SNI, ClientALPN: hello.ALPN}
	f.Note = note
	t0 := time.Now()
	up, err := e.dialTarget(ctx, t)
	if err != nil {
		f.Error = "connect " + t.Addr + ": " + err.Error()
		e.finish(f, f.Start)
		return
	}
	defer up.Close()
	f.Timings.Connect = ms(time.Since(t0))
	f.Server = up.RemoteAddr().String()
	e.cfg.Sink.Update(f)
	out, in := pipe(pc, up, nil, nil)
	f.BytesOut, f.BytesIn = out, in
	f.Req.Size, f.Res.Size = out, in
	e.finish(f, f.Start)
}
