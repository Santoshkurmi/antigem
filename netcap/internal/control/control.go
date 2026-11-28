// Package control is the engine's side of the channel to the AntiGem app on Android: JSON lines over an abstract
// Unix socket the app listens on. The app hands over the VPN tunnel (as a file descriptor), the CA key (it keeps
// it encrypted with the Android Keystore), settings, and answers questions only Android can answer: which app owns a
// connection, starting the VPN, installing the certificate with root.
package control

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"antigem/netcap/internal/dnsx"
	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/ingress/tun"
	"antigem/netcap/internal/service"
	"antigem/netcap/internal/tlsx"
)

// Tunnel addresses the app gives the VPN interface; queries to the DNS ones go to the phone's DNS servers.
var FakeDNS = []netip.Addr{netip.MustParseAddr("10.111.222.53"), netip.MustParseAddr("fd00:a9e:c0de::53")}

// Client is the engine end of the control channel.
type Client struct {
	conn    *net.UnixConn
	token   string
	version string
	ca      *tlsx.CA

	wmu     sync.Mutex
	nextID  atomic.Int64
	pmu     sync.Mutex
	pending map[int64]chan map[string]any

	svc   *service.Service
	names *dnsx.Names

	tmu        sync.Mutex
	tun        *tun.Tun
	tunFD      int
	apps       []string
	dnsServers []netip.AddrPort

	omu   sync.Mutex
	owner map[string]ownerEntry

	fdmu sync.Mutex
	fds  []int

	r      *bufio.Reader
	cancel context.CancelFunc
}

type ownerEntry struct {
	uid int
	app string
	at  time.Time
}

// Dial connects to the app's socket (abstract name), says hello and receives (or creates) the CA.
func Dial(ctx context.Context, name, token, version string) (*Client, error) {
	addr := &net.UnixAddr{Name: "@" + strings.TrimPrefix(name, "@"), Net: "unix"}
	conn, err := net.DialUnix("unix", nil, addr)
	if err != nil {
		return nil, err
	}
	c := &Client{conn: conn, token: token, version: version, pending: map[int64]chan map[string]any{}, tunFD: -1,
		owner: map[string]ownerEntry{}, names: dnsx.NewNames(50000)}
	c.r = bufio.NewReaderSize(readerFunc(c.readMsg), 64<<10)
	if err := c.send(map[string]any{"type": "hello", "token": token, "version": version, "pid": os.Getpid()}); err != nil {
		conn.Close()
		return nil, err
	}
	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	msg, err := c.readLine()
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("no answer from the app: %w", err)
	}
	switch msg["type"] {
	case "ca":
		ca, err := tlsx.LoadCA([]byte(str(msg["cert_pem"])), []byte(str(msg["key_pem"])))
		if err != nil {
			conn.Close()
			return nil, fmt.Errorf("CA from the app: %w", err)
		}
		c.ca = ca
	case "ca_none":
		ca, err := tlsx.NewCA()
		if err != nil {
			conn.Close()
			return nil, err
		}
		c.ca = ca
		if err := c.send(map[string]any{"type": "ca_new", "cert_pem": string(ca.CertPEM), "key_pem": string(ca.KeyPEM)}); err != nil {
			conn.Close()
			return nil, err
		}
	default:
		conn.Close()
		return nil, fmt.Errorf("unexpected first message %v", msg["type"])
	}
	return c, nil
}

// CA returns the CA the app gave (or the new one).
func (c *Client) CA() *tlsx.CA { return c.ca }

// Attach connects the service.
func (c *Client) Attach(svc *service.Service) { c.svc = svc }

// Routes adds nothing to the HTTP API for now.
func (c *Client) Routes() map[string]http.HandlerFunc { return nil }

// Name of the platform.
func (c *Client) Name() string { return "android" }

type readerFunc func([]byte) (int, error)

func (f readerFunc) Read(p []byte) (int, error) { return f(p) }

// readMsg reads bytes and keeps any file descriptors that came with them.
func (c *Client) readMsg(p []byte) (int, error) {
	oob := make([]byte, syscall.CmsgSpace(4*4))
	n, oobn, _, _, err := c.conn.ReadMsgUnix(p, oob)
	if n < 0 { // a closed socket can report -1
		n = 0
	}
	if oobn > 0 {
		if msgs, perr := syscall.ParseSocketControlMessage(oob[:oobn]); perr == nil {
			for _, m := range msgs {
				if fds, ferr := syscall.ParseUnixRights(&m); ferr == nil {
					c.fdmu.Lock()
					c.fds = append(c.fds, fds...)
					c.fdmu.Unlock()
				}
			}
		}
	}
	return n, err
}

func (c *Client) takeFD() (int, bool) {
	c.fdmu.Lock()
	defer c.fdmu.Unlock()
	if len(c.fds) == 0 {
		return -1, false
	}
	fd := c.fds[0]
	c.fds = c.fds[1:]
	return fd, true
}

func (c *Client) readLine() (map[string]any, error) {
	line, err := c.r.ReadBytes('\n')
	if err != nil {
		return nil, err
	}
	var m map[string]any
	if err := json.Unmarshal(line, &m); err != nil {
		return nil, fmt.Errorf("bad message: %w", err)
	}
	return m, nil
}

func (c *Client) send(m map[string]any) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	c.wmu.Lock()
	defer c.wmu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_, err = c.conn.Write(append(b, '\n'))
	return err
}

// request sends a message with an id and waits for the app's reply.
func (c *Client) request(m map[string]any, timeout time.Duration) (map[string]any, error) {
	id := c.nextID.Add(1)
	m["id"] = id
	ch := make(chan map[string]any, 1)
	c.pmu.Lock()
	c.pending[id] = ch
	c.pmu.Unlock()
	defer func() {
		c.pmu.Lock()
		delete(c.pending, id)
		c.pmu.Unlock()
	}()
	if err := c.send(m); err != nil {
		return nil, err
	}
	select {
	case r := <-ch:
		if e := str(r["error"]); e != "" {
			return r, errors.New(e)
		}
		return r, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("the app did not answer %v in %s", m["type"], timeout)
	}
}

// Run reads messages until the app goes away; then the engine exits (it lives only as long as the app's channel).
func (c *Client) Run(ctx context.Context) {
	ctx, c.cancel = context.WithCancel(ctx)
	defer c.stopTun()
	go func() {
		<-ctx.Done()
		c.conn.Close()
	}()
	go func() {
		tick := time.NewTicker(2 * time.Second)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				c.sendStats()
			}
		}
	}()
	for {
		msg, err := c.readLine()
		if err != nil {
			if ctx.Err() == nil {
				// the app closed the channel (it stopped or died): end the engine
				p, _ := os.FindProcess(os.Getpid())
				p.Signal(syscall.SIGTERM)
			}
			return
		}
		switch str(msg["type"]) {
		case "reply":
			id := int64(num(msg["id"]))
			c.pmu.Lock()
			ch := c.pending[id]
			c.pmu.Unlock()
			if ch != nil {
				ch <- msg
			}
		case "tun":
			c.startTun(ctx, msg)
		case "tun_stop":
			c.stopTun()
			if c.svc != nil {
				c.svc.CaptureStopped()
			}
		case "settings":
			if c.svc != nil {
				b, _ := json.Marshal(msg["settings"])
				var st service.Settings
				if json.Unmarshal(b, &st) == nil {
					_ = c.svc.SetSettings(st)
				}
			}
		case "dns_servers":
			c.setDNS(msg["servers"])
		case "ping":
			_ = c.send(map[string]any{"type": "reply", "id": msg["id"]})
		}
	}
}

func (c *Client) setDNS(v any) {
	var out []netip.AddrPort
	if list, ok := v.([]any); ok {
		for _, x := range list {
			s := str(x)
			if ap, err := netip.ParseAddrPort(s); err == nil {
				out = append(out, ap)
			} else if a, err := netip.ParseAddr(s); err == nil {
				out = append(out, netip.AddrPortFrom(a, 53))
			}
		}
	}
	c.tmu.Lock()
	c.dnsServers = out
	c.tmu.Unlock()
}

func (c *Client) upstreams() []netip.AddrPort {
	c.tmu.Lock()
	defer c.tmu.Unlock()
	return append([]netip.AddrPort(nil), c.dnsServers...)
}

func (c *Client) startTun(ctx context.Context, msg map[string]any) {
	fd, ok := c.takeFD()
	if !ok {
		_ = c.send(map[string]any{"type": "reply", "id": msg["id"], "error": "no tunnel descriptor came with the message"})
		return
	}
	c.stopTun()
	var apps []string
	if list, ok := msg["apps"].([]any); ok {
		for _, a := range list {
			if s := str(a); s != "" {
				apps = append(apps, s)
			}
		}
	}
	c.setDNS(msg["dns"])
	mtu := uint32(num(msg["mtu"]))
	t, err := tun.Start(ctx, tun.Config{
		FD:        fd,
		MTU:       mtu,
		Engine:    c.svc.Engine,
		Sink:      c.svc,
		Decide:    c.svc.Decide,
		Owner:     c.lookupOwner,
		DNS:       &dnsx.Forwarder{Names: c.names, Upstreams: c.upstreams, Record: c.recordDNS},
		FakeDNS:   FakeDNS,
		BlockQUIC: true,
	})
	if err != nil {
		syscall.Close(fd)
		_ = c.send(map[string]any{"type": "reply", "id": msg["id"], "error": err.Error()})
		return
	}
	c.tmu.Lock()
	c.tun = t
	c.tunFD = fd
	c.apps = apps
	c.tmu.Unlock()
	c.omu.Lock()
	c.owner = map[string]ownerEntry{}
	c.omu.Unlock()
	c.svc.SetCaptureApps(true, apps)
	_ = c.send(map[string]any{"type": "reply", "id": msg["id"]})
}

func (c *Client) stopTun() {
	c.tmu.Lock()
	t, fd := c.tun, c.tunFD
	c.tun, c.tunFD = nil, -1
	c.tmu.Unlock()
	if t != nil {
		t.Close()
		if fd >= 0 {
			syscall.Close(fd)
		}
	}
}

func (c *Client) recordDNS(d flow.DNS) {
	if c.svc != nil {
		c.svc.RecordDNS(d)
	}
}

// lookupOwner asks the app which app made a connection (skipped when only one app is captured).
func (c *Client) lookupOwner(proto string, src, dst netip.AddrPort) (int, string) {
	c.tmu.Lock()
	apps := c.apps
	c.tmu.Unlock()
	if len(apps) == 1 {
		return 0, apps[0]
	}
	key := proto + "|" + src.String()
	c.omu.Lock()
	if e, ok := c.owner[key]; ok && time.Since(e.at) < 30*time.Second {
		c.omu.Unlock()
		return e.uid, e.app
	}
	c.omu.Unlock()
	r, err := c.request(map[string]any{"type": "owner", "proto": proto, "src": src.String(), "dst": dst.String()}, 2*time.Second)
	if err != nil {
		return 0, ""
	}
	e := ownerEntry{uid: int(num(r["uid"])), app: str(r["app"]), at: time.Now()}
	c.omu.Lock()
	if len(c.owner) > 20000 {
		c.owner = map[string]ownerEntry{}
	}
	c.owner[key] = e
	c.omu.Unlock()
	return e.uid, e.app
}

func (c *Client) sendStats() {
	if c.svc == nil || !c.svc.CaptureInfo().On {
		return
	}
	sess := c.svc.Current()
	// LastID, not a COUNT over the table: this runs every 2 s while capturing
	_ = c.send(map[string]any{"type": "stats", "flows": sess.Store.LastID(), "session": sess.ID, "capture": c.svc.CaptureInfo()})
}

// ---- service.Platform ----

// StartCapture asks the app to start the VPN for apps (the app may ask the user first).
func (c *Client) StartCapture(apps []string, byAgent bool) error {
	_, err := c.request(map[string]any{"type": "start_capture", "apps": apps, "by_agent": byAgent}, 90*time.Second)
	return err
}

// StopCapture asks the app to stop the VPN.
func (c *Client) StopCapture() error {
	_, err := c.request(map[string]any{"type": "stop_capture"}, 15*time.Second)
	c.stopTun()
	return err
}

// CertStatus asks the app whether the phone trusts the CA.
func (c *Client) CertStatus() string {
	r, err := c.request(map[string]any{"type": "cert_status"}, 15*time.Second)
	if err != nil {
		return "unknown (" + err.Error() + ")"
	}
	return str(r["text"])
}

// InstallCert asks the app to install the CA as a system certificate (root).
func (c *Client) InstallCert() (string, error) {
	r, err := c.request(map[string]any{"type": "cert_install"}, 90*time.Second)
	if err != nil {
		return "", err
	}
	return str(r["text"]), nil
}

// RemoveCert asks the app to remove the system certificate.
func (c *Client) RemoveCert() (string, error) {
	r, err := c.request(map[string]any{"type": "cert_remove"}, 60*time.Second)
	if err != nil {
		return "", err
	}
	return str(r["text"]), nil
}

func str(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case nil:
		return ""
	}
	return fmt.Sprint(v)
}

func num(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case json.Number:
		f, _ := x.Float64()
		return f
	}
	return 0
}
