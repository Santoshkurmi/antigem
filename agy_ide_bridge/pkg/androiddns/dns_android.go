package androiddns

import (
	"bufio"
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
)

// Android has no /etc/resolv.conf, so Go's own resolver (CGO is off) falls back to [::1]:53 and every lookup
// fails. Use the nameservers of the Termux prefix instead, or public ones as a last resort.
func init() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	var files []string
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		files = append(files, filepath.Join(prefix, "etc", "resolv.conf"))
	}
	files = append(files, "/data/data/com.termux/files/usr/etc/resolv.conf")
	var servers []string
	for _, f := range files {
		if servers = readNameservers(f); len(servers) > 0 {
			break
		}
	}
	if len(servers) == 0 {
		servers = []string{"8.8.8.8:53", "1.1.1.1:53"}
	}
	var next uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			start := int(atomic.AddUint32(&next, 1))
			var lastErr error
			for i := range servers {
				c, err := d.DialContext(ctx, network, servers[(start+i)%len(servers)])
				if err == nil {
					return c, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func readNameservers(path string) []string {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) >= 2 && fields[0] == "nameserver" && net.ParseIP(fields[1]) != nil {
			out = append(out, net.JoinHostPort(fields[1], "53"))
		}
	}
	return out
}
