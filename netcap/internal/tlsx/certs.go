package tlsx

import (
	"container/list"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
)

func sha256Sum(b []byte) [32]byte { return sha256.Sum256(b) }

// Certs makes and caches the per-host certificates. All of them share one key (making a key is the slow part), the
// way mitmproxy does it.
type Certs struct {
	ca      *CA
	leafKey *ecdsa.PrivateKey
	max     int

	mu    sync.Mutex
	items map[string]*list.Element
	order *list.List
}

type certItem struct {
	key  string
	cert *tls.Certificate
}

// NewCerts makes a certificate cache for ca holding up to max certificates.
func NewCerts(ca *CA, max int) (*Certs, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	if max <= 0 {
		max = 2000
	}
	return &Certs{ca: ca, leafKey: k, max: max, items: map[string]*list.Element{}, order: list.New()}, nil
}

// Get returns a certificate for the given DNS names and IPs (the first name, or IP, is the common name).
func (c *Certs) Get(names []string, ips []net.IP) (*tls.Certificate, error) {
	names = cleanNames(names)
	key := strings.Join(names, ",")
	for _, ip := range ips {
		key += "|" + ip.String()
	}
	c.mu.Lock()
	if el, ok := c.items[key]; ok {
		c.order.MoveToFront(el)
		cert := el.Value.(*certItem).cert
		c.mu.Unlock()
		return cert, nil
	}
	c.mu.Unlock()

	cert, err := c.make(names, ips)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.items[key]; ok { // made twice at the same time: keep the first
		return el.Value.(*certItem).cert, nil
	}
	c.items[key] = c.order.PushFront(&certItem{key: key, cert: cert})
	for c.order.Len() > c.max {
		last := c.order.Back()
		c.order.Remove(last)
		delete(c.items, last.Value.(*certItem).key)
	}
	return cert, nil
}

func (c *Certs) make(names []string, ips []net.IP) (*tls.Certificate, error) {
	serial, err := randSerial()
	if err != nil {
		return nil, err
	}
	cn := ""
	if len(names) > 0 {
		cn = names[0]
	} else if len(ips) > 0 {
		cn = ips[0].String()
	}
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber:   serial,
		Subject:        pkix.Name{CommonName: cn, Organization: []string{"AntiGem Network Interceptor"}},
		NotBefore:      now.Add(-48 * time.Hour),
		NotAfter:       now.Add(300 * 24 * time.Hour), // clients reject server certificates valid longer than 398 days
		KeyUsage:       x509.KeyUsageDigitalSignature,
		ExtKeyUsage:    []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:       names,
		IPAddresses:    ips,
		AuthorityKeyId: c.ca.Cert.SubjectKeyId,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, c.ca.Cert, &c.leafKey.PublicKey, c.ca.Key)
	if err != nil {
		return nil, err
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, err
	}
	return &tls.Certificate{Certificate: [][]byte{der}, PrivateKey: c.leafKey, Leaf: leaf}, nil
}

// cleanNames lowercases, drops empty and duplicate names and IP strings, keeps the first name first.
func cleanNames(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, n := range in {
		n = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(n), "."))
		if n == "" || seen[n] || net.ParseIP(n) != nil {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	if len(out) > 1 {
		rest := out[1:]
		sort.Strings(rest)
	}
	return out
}
