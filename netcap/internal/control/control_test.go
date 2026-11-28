package control

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"testing"
	"time"

	"antigem/netcap/internal/service"
)

// fakeApp plays the AntiGem app's side of the channel (what ui/data/netcap/NetcapControl.kt must do).
type fakeApp struct {
	t    *testing.T
	conn *net.UnixConn
	r    *bufio.Reader
}

func (a *fakeApp) read() map[string]any {
	a.t.Helper()
	_ = a.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	line, err := a.r.ReadBytes('\n')
	if err != nil {
		a.t.Fatalf("app read: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(line, &m); err != nil {
		a.t.Fatal(err)
	}
	return m
}

func (a *fakeApp) write(m map[string]any, fd int) {
	a.t.Helper()
	b, _ := json.Marshal(m)
	b = append(b, '\n')
	var oob []byte
	if fd >= 0 {
		oob = syscall.UnixRights(fd)
	}
	if _, _, err := a.conn.WriteMsgUnix(b, oob, nil); err != nil {
		a.t.Fatal(err)
	}
}

func TestControlChannel(t *testing.T) {
	name := fmt.Sprintf("netcap-test-%d", rand.Int63())
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: "@" + name, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	type dialRes struct {
		c   *Client
		err error
	}
	done := make(chan dialRes, 1)
	go func() {
		c, err := Dial(context.Background(), name, "secret-token", "test")
		done <- dialRes{c, err}
	}()
	conn, err := ln.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	app := &fakeApp{t: t, conn: conn, r: bufio.NewReader(conn)}
	hello := app.read()
	if hello["type"] != "hello" || hello["token"] != "secret-token" {
		t.Fatalf("hello: %v", hello)
	}
	app.write(map[string]any{"type": "ca_none"}, -1)
	caNew := app.read()
	if caNew["type"] != "ca_new" || !strings.Contains(caNew["key_pem"].(string), "PRIVATE KEY") {
		t.Fatalf("ca_new: %v", caNew)
	}
	res := <-done
	if res.err != nil {
		t.Fatal(res.err)
	}
	c := res.c
	svc, err := service.New(service.Options{DataDir: t.TempDir(), CA: c.CA(), Platform: c})
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Close()
	c.Attach(svc)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go c.Run(ctx)

	// an agent starts a capture: the engine asks the app, the app agrees and hands over the tunnel
	errc := make(chan error, 1)
	go func() {
		errc <- svc.StartCapture(service.StartOptions{Apps: []string{"com.a", "com.b"}, ByAgent: true})
	}()
	req := app.read()
	if req["type"] != "start_capture" || req["by_agent"] != true {
		t.Fatalf("start_capture: %v", req)
	}
	app.write(map[string]any{"type": "reply", "id": req["id"]}, -1)
	if err := <-errc; err != nil {
		t.Fatal(err)
	}
	fds, _ := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	defer syscall.Close(fds[1])
	app.write(map[string]any{"type": "tun", "id": 900, "mtu": 1500, "apps": []string{"com.a", "com.b"}, "dns": []string{"192.168.1.1"}}, fds[0])
	syscall.Close(fds[0]) // the engine has its own copy now
	var rep map[string]any
	for {
		rep = app.read()
		if rep["type"] == "reply" {
			break
		}
	}
	if rep["id"].(float64) != 900 || rep["error"] != nil {
		t.Fatalf("tun reply: %v", rep)
	}
	if ci := svc.CaptureInfo(); !ci.On || len(ci.Apps) != 2 {
		t.Fatalf("capture: %+v", ci)
	}
	if ups := c.upstreams(); len(ups) != 1 || ups[0].String() != "192.168.1.1:53" {
		t.Fatalf("dns servers: %v", ups)
	}

	// two apps captured: owner lookups go to the app
	go func() {
		for {
			m := app.read()
			switch m["type"] {
			case "owner":
				app.write(map[string]any{"type": "reply", "id": m["id"], "uid": 10234, "app": "com.b"}, -1)
			case "cert_status":
				app.write(map[string]any{"type": "reply", "id": m["id"], "text": "system ✓"}, -1)
				return
			}
		}
	}()
	src, dst := mustAP("10.111.222.1:4444"), mustAP("1.2.3.4:443")
	if uid, a := c.lookupOwner("tcp", src, dst); uid != 10234 || a != "com.b" {
		t.Fatalf("owner: %d %s", uid, a)
	}
	if s := c.CertStatus(); s != "system ✓" {
		t.Fatalf("cert status: %s", s)
	}
}

func mustAP(s string) netip.AddrPort { return netip.MustParseAddrPort(s) }
