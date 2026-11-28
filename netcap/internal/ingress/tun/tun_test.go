package tun_test

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"

	"antigem/netcap/internal/dnsx"
	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/ingress/tun"
	"antigem/netcap/internal/service"
)

// phoneStack is the "app side": a second gVisor stack on the other end of the socket pair.
func phoneStack(t *testing.T, fd int) *stack.Stack {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4},
	})
	ep, err := fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: 1500})
	if err != nil {
		t.Fatal(err)
	}
	if e := s.CreateNIC(1, ep); e != nil {
		t.Fatal(e)
	}
	addr := tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: tcpip.AddrFrom4([4]byte{10, 111, 222, 1}).WithPrefix()}
	if e := s.AddProtocolAddress(1, addr, stack.AddressProperties{}); e != nil {
		t.Fatal(e)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return s
}

func TestTunnel(t *testing.T) {
	// the real server
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"hello":"`+r.Host+`"}`)
	}))
	defer srv.Close()
	realAddr := strings.TrimPrefix(srv.URL, "https://")
	// a fake DNS server answering test.example → 198.51.100.7
	dnsConn, _ := net.ListenPacket("udp", "127.0.0.1:0")
	defer dnsConn.Close()
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := dnsConn.ReadFrom(buf)
			if err != nil {
				return
			}
			var q dnsmessage.Message
			if q.Unpack(buf[:n]) != nil {
				continue
			}
			a := dnsmessage.Message{Header: dnsmessage.Header{ID: q.ID, Response: true}, Questions: q.Questions,
				Answers: []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
					Body: &dnsmessage.AResource{A: [4]byte{198, 51, 100, 7}}}}}
			out, _ := a.Pack()
			dnsConn.WriteTo(out, from)
		}
	}()

	dialer := &net.Dialer{}
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		if strings.HasPrefix(addr, "198.51.100.7:") {
			addr = realAddr // the "internet" is the local test server
		}
		return dialer.DialContext(ctx, network, addr)
	}
	svc, err := service.New(service.Options{DataDir: t.TempDir(), Dial: dial})
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Close()
	st := svc.Settings()
	st.InsecureUpstream = []string{"test.example"}
	svc.SetSettings(st)
	svc.StartCapture(service.StartOptions{})

	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	names := dnsx.NewNames(100)
	tn, err := tun.Start(ctx, tun.Config{
		FD: fds[0], MTU: 1500, Engine: svc.Engine, Sink: svc, Decide: svc.Decide, Dial: dial,
		Owner: func(string, netip.AddrPort, netip.AddrPort) (int, string) { return 10123, "com.test.app" },
		DNS: &dnsx.Forwarder{Names: names, Record: svc.RecordDNS,
			Upstreams: func() []netip.AddrPort {
				return []netip.AddrPort{netip.MustParseAddrPort(dnsConn.LocalAddr().String())}
			}},
		FakeDNS:   []netip.Addr{netip.MustParseAddr("10.111.222.53")},
		BlockQUIC: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer tn.Close()
	phone := phoneStack(t, fds[1])
	defer phone.Close()

	// 1. DNS through the tunnel's DNS address
	uc, err := gonet.DialUDP(phone, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 111, 222, 53}), Port: 53}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 7, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("test.example."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	uc.SetDeadline(time.Now().Add(5 * time.Second))
	uc.Write(q)
	buf := make([]byte, 1500)
	n, err := uc.Read(buf)
	if err != nil {
		t.Fatalf("DNS: %v", err)
	}
	var ans dnsmessage.Message
	if ans.Unpack(buf[:n]) != nil || len(ans.Answers) != 1 {
		t.Fatalf("DNS answer %v", ans)
	}
	uc.Close()

	// 2. HTTPS to the answered IP, decrypted by the engine
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM(svc.CA.CertPEM)
	tr := &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool}, DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		return gonet.DialContextTCP(ctx, phone, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 51, 100, 7}), Port: 443}, ipv4.ProtocolNumber)
	}, ForceAttemptHTTP2: true}
	resp, err := (&http.Client{Transport: tr, Timeout: 10 * time.Second}).Get("https://test.example/api")
	if err != nil {
		time.Sleep(200 * time.Millisecond)
		r, _ := svc.List(service.ListOptions{Limit: 20, Secrets: true})
		var all []string
		svc.Current().Store.Scan("1", nil, true, func(f *flow.Flow) bool { all = append(all, f.URL()+" "+f.TLS+" err="+f.Error); return true })
		t.Fatalf("HTTPS through the tunnel: %v\n%s\n%v", err, r.Text(service.ListOptions{}), all)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != `{"hello":"test.example"}` {
		t.Fatalf("body %q", body)
	}

	// 3. QUIC is refused at once with "port unreachable" (Android's kernel turns it into ECONNREFUSED for the app;
	// gVisor's test client does not report it, so count the ICMP packets instead)
	qc, err := gonet.DialUDP(phone, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 51, 100, 7}), Port: 443}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	qc.Write([]byte("quic hello"))
	for i := 0; i < 100 && phone.Stats().ICMP.V4.PacketsReceived.DstUnreachable.Value() == 0; i++ {
		time.Sleep(10 * time.Millisecond)
	}
	if tn.Unreachable() != 1 || phone.Stats().ICMP.V4.PacketsReceived.DstUnreachable.Value() != 1 {
		t.Fatalf("QUIC should be refused: sent %d", tn.Unreachable())
	}
	qc.Close()

	// what was recorded
	var got *flow.Flow
	for i := 0; i < 100 && got == nil; i++ {
		svc.Current().Store.Flush()
		svc.Current().Store.Scan("done = 1", nil, true, func(f *flow.Flow) bool { got = f; return false })
		time.Sleep(20 * time.Millisecond)
	}
	if got == nil || got.App != "com.test.app" || got.Host != "test.example" || got.Path != "/api" || got.TLS != flow.TLSDecrypted || got.HTTPVersion != "1.1" {
		t.Fatalf("flow: %+v", got)
	}
	dns, _ := svc.DNSText("", "", 10)
	if !strings.Contains(dns, "test.example → 198.51.100.7") {
		t.Fatalf("dns: %s", dns)
	}
}
