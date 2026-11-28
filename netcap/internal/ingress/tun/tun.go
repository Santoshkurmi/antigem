// Package tun is the VPN ingress: a gVisor TCP/IP stack on the tunnel file descriptor from Android's VpnService.
// Every TCP connection of a captured app becomes a normal net.Conn for the engine; DNS is forwarded and recorded;
// QUIC (UDP 443) is refused so apps fall back to TCP; other UDP is relayed and recorded.
package tun

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/netip"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"

	"antigem/netcap/internal/dnsx"
	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/intercept"
)

// Config of the tunnel ingress.
type Config struct {
	FD        int
	MTU       uint32
	Engine    *intercept.Engine
	Sink      intercept.Sink // for UDP flows
	Decide    func(m intercept.Meta, host string) intercept.Decision
	Owner     func(proto string, src, dst netip.AddrPort) (uid int, app string)
	DNS       *dnsx.Forwarder
	FakeDNS   []netip.Addr // the tunnel's own DNS addresses (queries to them go to the phone's DNS servers)
	BlockQUIC bool
	Dial      func(ctx context.Context, network, addr string) (net.Conn, error)
	Logf      func(string, ...any)
}

// Tun is a running tunnel ingress.
type Tun struct {
	cfg    Config
	s      *stack.Stack
	ctx    context.Context
	cancel context.CancelFunc
}

const nicID = 1

// Start runs the stack on cfg.FD until Close.
func Start(parent context.Context, cfg Config) (*Tun, error) {
	if cfg.MTU == 0 {
		cfg.MTU = 1500
	}
	if cfg.Dial == nil {
		d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
		cfg.Dial = d.DialContext
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
	})
	// TX checksums must be real: the kernel checks packets written to the tunnel. RX ones come from the kernel.
	ep, err := fdbased.New(&fdbased.Options{FDs: []int{cfg.FD}, MTU: cfg.MTU, PacketDispatchMode: fdbased.Readv,
		RXChecksumOffload: true})
	if err != nil {
		s.Close()
		return nil, fmt.Errorf("tunnel endpoint: %w", err)
	}
	// the NIC starts disabled: packets must not arrive before the handlers below are in place
	if e := s.CreateNICWithOptions(nicID, ep, stack.NICOptions{Disabled: true}); e != nil {
		s.Close()
		return nil, fmt.Errorf("create NIC: %s", e)
	}
	// accept packets for every address (the app talks to the whole internet through us)
	s.SetPromiscuousMode(nicID, true)
	s.SetSpoofing(nicID, true)
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: nicID}, {Destination: header.IPv6EmptySubnet, NIC: nicID}})
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	moderate := tcpip.TCPModerateReceiveBufferOption(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate)

	ctx, cancel := context.WithCancel(parent)
	t := &Tun{cfg: cfg, s: s, ctx: ctx, cancel: cancel}
	tf := tcp.NewForwarder(s, 0, 2048, t.onTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tf.HandlePacket)
	uf := udp.NewForwarder(s, t.onUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, uf.HandlePacket)
	if e := s.EnableNIC(nicID); e != nil {
		cancel()
		s.Close()
		return nil, fmt.Errorf("enable NIC: %s", e)
	}
	return t, nil
}

// Unreachable is how many "port unreachable" answers the stack sent (QUIC refusals).
func (t *Tun) Unreachable() uint64 {
	return t.s.Stats().ICMP.V4.PacketsSent.DstUnreachable.Value() + t.s.Stats().ICMP.V6.PacketsSent.DstUnreachable.Value()
}

// Close stops the stack (the app closes the tunnel descriptor).
func (t *Tun) Close() {
	t.cancel()
	t.s.Close()
	t.s.Wait()
}

func addrOf(a tcpip.Address) netip.Addr {
	if a.Len() == 4 {
		return netip.AddrFrom4(a.As4())
	}
	return netip.AddrFrom16(a.As16())
}

func (t *Tun) isFake(a netip.Addr) bool {
	for _, f := range t.cfg.FakeDNS {
		if f == a.Unmap() {
			return true
		}
	}
	return false
}

func (t *Tun) meta(proto string, src, dst netip.AddrPort) intercept.Meta {
	m := intercept.Meta{Client: src.String(), Ingress: "tun"}
	if t.cfg.Owner != nil {
		m.UID, m.App = t.cfg.Owner(proto, src, dst)
	}
	return m
}

func (t *Tun) onTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := netip.AddrPortFrom(addrOf(id.LocalAddress), id.LocalPort)
	src := netip.AddrPortFrom(addrOf(id.RemoteAddress), id.RemotePort)
	dialAddr := dst.String()
	if t.isFake(dst.Addr()) {
		ups := t.upstreamDNS()
		if dst.Port() != 53 || len(ups) == 0 {
			r.Complete(true)
			return
		}
		dialAddr = ups[0].String() // DNS over TCP to the tunnel's DNS address
	}
	// connect first: when the server can't be reached the app gets a reset at once and can try another address
	dctx, cancel := context.WithTimeout(t.ctx, 10*time.Second)
	t0 := time.Now()
	up, err := t.cfg.Dial(dctx, "tcp", dialAddr)
	cancel()
	if err != nil {
		r.Complete(true)
		t.connectFailed(src, dst, dialAddr, err, time.Since(t0))
		return
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		up.Close()
		r.Complete(true)
		return
	}
	r.Complete(false)
	ep.SocketOptions().SetKeepAlive(true)
	conn := gonet.NewTCPConn(&wq, ep)
	host := dst.Addr().String()
	if t.cfg.DNS != nil {
		if n := t.cfg.DNS.Names.Get(dst.Addr()); n != "" {
			host = n
		}
	}
	m := t.meta("tcp", src, dst)
	t.cfg.Engine.HandleTCP(t.ctx, conn, intercept.Target{Addr: dialAddr, Host: host, Port: int(dst.Port()), Pre: up}, m)
}

// connectFailed records a connection the engine could not open (the app got a reset), so it shows up in the list.
func (t *Tun) connectFailed(src, dst netip.AddrPort, dialAddr string, err error, took time.Duration) {
	log.Printf("tun: connect %s for %s failed after %s: %v", dialAddr, src, took.Round(time.Millisecond), err)
	host := dst.Addr().String()
	if t.cfg.DNS != nil {
		if n := t.cfg.DNS.Names.Get(dst.Addr()); n != "" {
			host = n
		}
	}
	m := t.meta("tcp", src, dst)
	if t.cfg.Sink == nil || t.cfg.Decide == nil || !t.cfg.Decide(m, host).Record {
		return
	}
	f := t.cfg.Sink.NewFlow(flow.KindTCP, m)
	f.Host, f.Port, f.Server = host, int(dst.Port()), dialAddr
	f.Error = fmt.Sprintf("connect %s: %v (the app got a connection reset)", dialAddr, err)
	f.Timings.Connect = float64(took.Microseconds()) / 1000
	f.End = time.Now()
	f.Timings.Total = f.Timings.Connect
	f.Done = true
	t.cfg.Sink.Finish(f)
}

func (t *Tun) upstreamDNS() []netip.AddrPort {
	if t.cfg.DNS != nil && t.cfg.DNS.Upstreams != nil {
		return t.cfg.DNS.Upstreams()
	}
	return nil
}

func (t *Tun) onUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := netip.AddrPortFrom(addrOf(id.LocalAddress), id.LocalPort)
	src := netip.AddrPortFrom(addrOf(id.RemoteAddress), id.RemotePort)
	if t.cfg.BlockQUIC && dst.Port() == 443 {
		return false // "port unreachable": the app falls back to TCP (HTTP/2) right away
	}
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		return false
	}
	conn := gonet.NewUDPConn(&wq, ep)
	if dst.Port() == 53 && t.cfg.DNS != nil {
		go t.serveDNS(conn, src, dst)
	} else {
		go t.relayUDP(conn, src, dst)
	}
	return true
}

func (t *Tun) serveDNS(conn *gonet.UDPConn, src, dst netip.AddrPort) {
	defer conn.Close()
	fake := t.isFake(dst.Addr())
	m := t.meta("udp", src, dst)
	buf := make([]byte, 64<<10)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		n, err := conn.Read(buf)
		if err != nil {
			return
		}
		q := append([]byte(nil), buf[:n]...)
		go func() {
			reply, err := t.cfg.DNS.Exchange(t.ctx, q, dst, fake, m.App)
			if err == nil {
				conn.Write(reply)
			}
		}()
	}
}

const udpKeep = 256 << 10

func (t *Tun) relayUDP(conn *gonet.UDPConn, src, dst netip.AddrPort) {
	defer conn.Close()
	m := t.meta("udp", src, dst)
	host := dst.Addr().String()
	if t.cfg.DNS != nil {
		if n := t.cfg.DNS.Names.Get(dst.Addr()); n != "" {
			host = n
		}
	}
	up, err := t.cfg.Dial(t.ctx, "udp", dst.String())
	if err != nil {
		return
	}
	defer up.Close()
	var f *flow.Flow
	if t.cfg.Decide != nil && t.cfg.Decide(m, host).Record && t.cfg.Sink != nil {
		f = t.cfg.Sink.NewFlow(flow.KindUDP, m)
		f.Host, f.Port, f.Server = host, int(dst.Port()), dst.String()
		t.cfg.Sink.Update(f)
	}
	var mu sync.Mutex
	idx, kept := 0, 0
	record := func(dir string, b []byte) {
		if f == nil {
			return
		}
		mu.Lock()
		defer mu.Unlock()
		if dir == "out" {
			f.BytesOut += int64(len(b))
		} else {
			f.BytesIn += int64(len(b))
		}
		if kept >= udpKeep {
			return
		}
		kept += len(b)
		t.cfg.Sink.Message(f, flow.Message{FlowID: f.ID, Index: idx, Dir: dir, Time: time.Now(), Type: "chunk", Size: len(b), Data: append([]byte(nil), b...)})
		idx++
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 64<<10)
		for {
			_ = up.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, err := up.Read(buf)
			if err != nil {
				conn.Close()
				return
			}
			record("in", buf[:n])
			if _, err := conn.Write(buf[:n]); err != nil {
				return
			}
		}
	}()
	buf := make([]byte, 64<<10)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := conn.Read(buf)
		if err != nil {
			break
		}
		record("out", buf[:n])
		if _, err := up.Write(buf[:n]); err != nil {
			break
		}
	}
	up.Close()
	<-done
	if f != nil {
		mu.Lock()
		f.Messages = idx
		f.Req.Size, f.Res.Size = f.BytesOut, f.BytesIn
		f.End = time.Now()
		f.Timings.Total = float64(f.End.Sub(f.Start).Microseconds()) / 1000
		f.Done = true
		mu.Unlock()
		t.cfg.Sink.Finish(f)
	}
}
