// Package dnsx forwards the DNS queries of captured apps, records them, and remembers which name each answered IP
// belongs to (connections without SNI then still get a host name).
package dnsx

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"

	"antigem/netcap/internal/flow"
)

// Names maps IP addresses to the last name they were an answer for.
type Names struct {
	mu  sync.Mutex
	m   map[netip.Addr]string
	max int
}

// NewNames makes a map holding up to max addresses.
func NewNames(max int) *Names {
	if max <= 0 {
		max = 50000
	}
	return &Names{m: map[netip.Addr]string{}, max: max}
}

// Put remembers that ip answered name.
func (n *Names) Put(ip netip.Addr, name string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.m) >= n.max {
		for k := range n.m { // drop some at random (map order); simple and good enough
			delete(n.m, k)
			if len(n.m) < n.max*9/10 {
				break
			}
		}
	}
	n.m[ip.Unmap()] = name
}

// Get returns the name for ip ("" when unknown).
func (n *Names) Get(ip netip.Addr) string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.m[ip.Unmap()]
}

// Forwarder sends queries upstream and records them.
type Forwarder struct {
	Names     *Names
	Upstreams func() []netip.AddrPort // the phone's own DNS servers (for queries to the tunnel's DNS address)
	Record    func(flow.DNS)
	Dial      func(ctx context.Context, network, addr string) (net.Conn, error)
	Timeout   time.Duration
}

var fallback = []netip.AddrPort{netip.MustParseAddrPort("8.8.8.8:53"), netip.MustParseAddrPort("1.1.1.1:53")}

// Exchange answers one query. dst is where the app sent it; fake means dst is the tunnel's own DNS address, so the
// query goes to the phone's DNS servers instead.
func (f *Forwarder) Exchange(ctx context.Context, query []byte, dst netip.AddrPort, fake bool, app string) ([]byte, error) {
	start := time.Now()
	var q dnsmessage.Message
	parsed := q.Unpack(query) == nil && len(q.Questions) > 0
	targets := []netip.AddrPort{dst}
	if fake {
		targets = nil
		if f.Upstreams != nil {
			targets = f.Upstreams()
		}
		if len(targets) == 0 {
			targets = fallback
		}
	}
	timeout := f.Timeout
	if timeout <= 0 {
		timeout = 4 * time.Second
	}
	var reply []byte
	var err error
	for _, t := range targets {
		reply, err = f.ask(ctx, query, t, timeout)
		if err == nil {
			break
		}
	}
	if parsed && f.Record != nil {
		rec := flow.DNS{Time: start, App: app, Name: strings.TrimSuffix(q.Questions[0].Name.String(), "."),
			Type: strings.TrimPrefix(q.Questions[0].Type.String(), "Type"), Duration: float64(time.Since(start).Microseconds()) / 1000}
		if err != nil {
			rec.RCode = "timeout"
		} else {
			var a dnsmessage.Message
			if a.Unpack(reply) == nil {
				rec.RCode = strings.TrimPrefix(a.RCode.String(), "RCode")
				for _, ans := range a.Answers {
					switch b := ans.Body.(type) {
					case *dnsmessage.AResource:
						ip := netip.AddrFrom4(b.A)
						rec.Answers = append(rec.Answers, ip.String())
						f.Names.Put(ip, rec.Name)
					case *dnsmessage.AAAAResource:
						ip := netip.AddrFrom16(b.AAAA)
						rec.Answers = append(rec.Answers, ip.String())
						f.Names.Put(ip, rec.Name)
					case *dnsmessage.CNAMEResource:
						rec.Answers = append(rec.Answers, "cname:"+strings.TrimSuffix(b.CNAME.String(), "."))
					}
				}
			}
		}
		f.Record(rec)
	}
	return reply, err
}

func (f *Forwarder) ask(ctx context.Context, query []byte, to netip.AddrPort, timeout time.Duration) ([]byte, error) {
	dial := f.Dial
	if dial == nil {
		d := &net.Dialer{}
		dial = d.DialContext
	}
	cctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	c, err := dial(cctx, "udp", to.String())
	if err != nil {
		return nil, err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(timeout))
	if _, err := c.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 64<<10)
	n, err := c.Read(buf)
	if err != nil {
		return nil, err
	}
	if n < 12 {
		return nil, errors.New("short DNS reply")
	}
	return buf[:n], nil
}
