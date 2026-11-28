package tlsx

import (
	"encoding/json"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// Untrusted keeps the hosts whose app refused the AntiGem certificate. Their later connections are relayed without
// decryption so the app keeps working. Saved to a file so it survives engine restarts.
type Untrusted struct {
	path string
	mu   sync.Mutex
	m    map[string]UntrustedHost
}

// UntrustedHost is one entry.
type UntrustedHost struct {
	App    string    `json:"app"`
	Host   string    `json:"host"`
	Since  time.Time `json:"since"`
	Reason string    `json:"reason"`
}

// LoadUntrusted reads the list from path (missing file = empty list).
func LoadUntrusted(path string) *Untrusted {
	u := &Untrusted{path: path, m: map[string]UntrustedHost{}}
	if b, err := os.ReadFile(path); err == nil {
		var list []UntrustedHost
		if json.Unmarshal(b, &list) == nil {
			for _, h := range list {
				u.m[key(h.App, h.Host)] = h
			}
		}
	}
	return u
}

func key(app, host string) string { return app + "|" + strings.ToLower(host) }

// Is reports whether app refused the certificate for host.
func (u *Untrusted) Is(app, host string) bool {
	u.mu.Lock()
	defer u.mu.Unlock()
	_, ok := u.m[key(app, host)]
	return ok
}

// Mark records that app refused the certificate for host.
func (u *Untrusted) Mark(app, host, reason string) {
	u.mu.Lock()
	if _, ok := u.m[key(app, host)]; ok {
		u.mu.Unlock()
		return
	}
	u.m[key(app, host)] = UntrustedHost{App: app, Host: strings.ToLower(host), Since: time.Now(), Reason: reason}
	u.mu.Unlock()
	u.save()
}

// Clear removes host (all apps; "" = everything). Returns how many entries were removed.
func (u *Untrusted) Clear(host string) int {
	u.mu.Lock()
	n := 0
	for k, h := range u.m {
		if host == "" || strings.EqualFold(h.Host, host) {
			delete(u.m, k)
			n++
		}
	}
	u.mu.Unlock()
	u.save()
	return n
}

// List returns all entries sorted by host.
func (u *Untrusted) List() []UntrustedHost {
	u.mu.Lock()
	defer u.mu.Unlock()
	out := make([]UntrustedHost, 0, len(u.m))
	for _, h := range u.m {
		out = append(out, h)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Host < out[j].Host })
	return out
}

func (u *Untrusted) save() {
	if u.path == "" {
		return
	}
	b, err := json.MarshalIndent(u.List(), "", "  ")
	if err != nil {
		return
	}
	tmp := u.path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, u.path)
	}
}
