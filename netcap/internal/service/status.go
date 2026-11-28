package service

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"net/url"
	"strconv"
	"strings"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/view"
)

// Status is a short state report.
func (s *Service) Status() string {
	var b strings.Builder
	c := s.CaptureInfo()
	sess := s.Current()
	st := sess.Store.Stats()
	switch {
	case c.On && c.Paused:
		b.WriteString("capture: PAUSED (traffic flows, nothing is recorded)")
	case c.On:
		b.WriteString("capture: ON")
		if !c.Since.IsZero() {
			b.WriteString(" since " + c.Since.Format("15:04:05"))
		}
	default:
		b.WriteString("capture: OFF")
	}
	if len(c.Apps) > 0 {
		b.WriteString(" · apps: " + strings.Join(c.Apps, ", "))
	} else if s.opts.Platform == nil {
		b.WriteString(" · everything sent through the proxy")
	}
	fmt.Fprintf(&b, "\nsession %s %q · %d flows · %s on disk", sess.ID, sess.Name, st.Flows, Size(sess.Store.DiskBytes()))
	if !st.Last.IsZero() {
		b.WriteString(" · last at " + st.Last.Format("15:04:05"))
	}
	if st.Dropped > 0 {
		fmt.Fprintf(&b, " · %d messages not recorded (too fast)", st.Dropped)
	}
	b.WriteString("\ncert: ")
	if s.opts.Platform != nil {
		b.WriteString(s.opts.Platform.CertStatus())
	} else {
		b.WriteString("PC mode: trust " + s.CertPath() + " in the browser/system/tool you send through the proxy")
	}
	if un := s.Untrusted.List(); len(un) > 0 {
		var hosts []string
		for i, h := range un {
			if i >= 5 {
				hosts = append(hosts, fmt.Sprintf("+%d more", len(un)-i))
				break
			}
			hosts = append(hosts, h.Host)
		}
		fmt.Fprintf(&b, "\nnot decrypted (the app refused the certificate): %s · net_capture action=reset_untrusted to try again", strings.Join(hosts, ", "))
	}
	if s.opts.ProxyAddr != "" {
		fmt.Fprintf(&b, "\nproxy port: %s (HTTP and SOCKS5, for tools that take a proxy setting)", s.opts.ProxyAddr)
	}
	if c.Mirror != "" {
		fmt.Fprintf(&b, "\nfiles for AI: %s/files (filter %q)", sess.Dir, c.Mirror)
	}
	set := s.Settings()
	if set.ShowSecretsToAI {
		b.WriteString("\nsecrets: shown to AI (the user turned hiding off)")
	} else {
		b.WriteString("\nsecrets: hidden from AI ([hidden #xxxx]; same token = same #xxxx)")
	}
	return b.String()
}

// RecordDNS stores a DNS query of a captured app in the active session.
func (s *Service) RecordDNS(d flow.DNS) {
	c := s.CaptureInfo()
	if !c.On || c.Paused {
		return
	}
	s.Current().Store.PutDNS(d)
}

// DNSText lists recorded DNS queries (newest first), optionally only names containing name.
func (s *Service) DNSText(sessID, name string, limit int) (string, error) {
	sess, err := s.Session(sessID)
	if err != nil {
		return "", err
	}
	sess.Store.Flush()
	list, err := sess.Store.DNSList(name, limit)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%d DNS queries (newest first)\n", len(list))
	for _, d := range list {
		ans := strings.Join(d.Answers, " ")
		if ans == "" {
			ans = d.RCode
		}
		app := ""
		if d.App != "" {
			app = " [" + d.App + "]"
		}
		fmt.Fprintf(&b, "%s%s %-5s %s → %s %s\n", d.Time.Format("15:04:05"), app, d.Type, d.Name, Cut(ans, 120), Dur(d.Duration))
	}
	return b.String(), nil
}

// CertInfo describes the CA.
func (s *Service) CertInfo() string {
	var b strings.Builder
	fmt.Fprintf(&b, "CA: %s\nSHA-256: %s\nAndroid file name: %s\ncertificate file: %s\nexpires: %s",
		s.CA.Cert.Subject.CommonName, s.CA.Fingerprint(), s.CA.AndroidName(), s.CertPath(), s.CA.Cert.NotAfter.Format("2006-01-02"))
	if s.opts.Platform != nil {
		b.WriteString("\nstatus: " + s.opts.Platform.CertStatus())
	}
	return b.String()
}

// Decode is the decoder tool: base64, url, hex, jwt, gzip, protobuf, timestamp, html (and encode for the first three).
func Decode(input, as string, encode bool) (string, error) {
	as = strings.ToLower(strings.TrimSpace(as))
	in := strings.TrimSpace(input)
	if encode {
		switch as {
		case "base64":
			return base64.StdEncoding.EncodeToString([]byte(input)), nil
		case "base64url":
			return base64.RawURLEncoding.EncodeToString([]byte(input)), nil
		case "url":
			return url.QueryEscape(input), nil
		case "hex":
			return hex.EncodeToString([]byte(input)), nil
		case "sha256":
			sum := sha256.Sum256([]byte(input))
			return hex.EncodeToString(sum[:]), nil
		}
		return "", fmt.Errorf("encode as base64, base64url, url, hex or sha256")
	}
	if as == "" || as == "auto" {
		as = guessEncoding(in)
	}
	switch as {
	case "base64", "base64url":
		b, err := decodeB64(in)
		if err != nil {
			return "", err
		}
		return describeBytes(b), nil
	case "url":
		s, err := url.QueryUnescape(in)
		if err != nil {
			return "", err
		}
		return s, nil
	case "hex":
		b, err := hex.DecodeString(strings.ReplaceAll(strings.ReplaceAll(in, " ", ""), ":", ""))
		if err != nil {
			return "", err
		}
		return describeBytes(b), nil
	case "jwt":
		return decodeJWT(in)
	case "gzip", "deflate", "br", "zstd":
		b, err := decodeB64(in)
		if err != nil {
			if b, err = hex.DecodeString(in); err != nil {
				return "", fmt.Errorf("give compressed data as base64 or hex")
			}
		}
		d, err := view.Decode(b, as)
		if err != nil {
			return "", err
		}
		return describeBytes(d), nil
	case "protobuf":
		b, err := decodeB64(in)
		if err != nil {
			if b, err = hex.DecodeString(in); err != nil {
				return "", fmt.Errorf("give protobuf data as base64 or hex")
			}
		}
		return view.Protobuf(b, 200), nil
	case "timestamp":
		n, err := strconv.ParseFloat(in, 64)
		if err != nil {
			t, err := time.Parse(time.RFC3339, in)
			if err != nil {
				return "", fmt.Errorf("give a unix time (seconds or milliseconds) or an RFC 3339 date")
			}
			return fmt.Sprintf("unix %d (ms %d)", t.Unix(), t.UnixMilli()), nil
		}
		var t time.Time
		switch {
		case n > 1e17:
			t = time.Unix(0, int64(n))
		case n > 1e14:
			t = time.UnixMicro(int64(n))
		case n > 1e11:
			t = time.UnixMilli(int64(n))
		default:
			t = time.Unix(int64(n), 0)
		}
		return t.UTC().Format(time.RFC3339Nano) + " (" + time.Since(t).Round(time.Second).String() + " ago)", nil
	case "html":
		return html.UnescapeString(in), nil
	}
	return "", fmt.Errorf("as: auto, base64, base64url, url, hex, jwt, gzip, deflate, br, zstd, protobuf, timestamp, html")
}

func guessEncoding(s string) string {
	switch {
	case strings.Count(s, ".") == 2 && strings.HasPrefix(s, "eyJ"):
		return "jwt"
	case strings.Contains(s, "%"):
		return "url"
	case isHex(s):
		return "hex"
	case strings.Contains(s, "&") && strings.Contains(s, ";"):
		return "html"
	}
	if _, err := strconv.ParseFloat(s, 64); err == nil && len(s) >= 9 {
		return "timestamp"
	}
	return "base64"
}

func isHex(s string) bool {
	if len(s) < 2 || len(s)%2 != 0 {
		return false
	}
	for _, c := range s {
		if !strings.ContainsRune("0123456789abcdefABCDEF", c) {
			return false
		}
	}
	return true
}

func decodeB64(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.RawURLEncoding} {
		if b, err := enc.DecodeString(s); err == nil {
			return b, nil
		}
	}
	return nil, fmt.Errorf("not base64")
}

func describeBytes(b []byte) string {
	typ := view.ShortType("", b)
	if view.IsText(typ) {
		return view.Pretty(b, typ)
	}
	return fmt.Sprintf("%d bytes (%s):\n%s", len(b), typ, view.Hex(b, 0, min(len(b), 512)))
}

func decodeJWT(tok string) (string, error) {
	parts := strings.Split(strings.TrimPrefix(strings.TrimSpace(tok), "Bearer "), ".")
	if len(parts) < 2 {
		return "", fmt.Errorf("a JWT has 3 parts separated by dots")
	}
	var b strings.Builder
	for i, name := range []string{"header", "claims"} {
		raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[i], "="))
		if err != nil {
			return "", fmt.Errorf("%s: %w", name, err)
		}
		b.WriteString(name + ": " + view.Pretty(raw, "json") + "\n")
		if name == "claims" {
			var claims map[string]any
			if json.Unmarshal(raw, &claims) == nil {
				for _, k := range []string{"iat", "nbf", "exp"} {
					if v, ok := claims[k].(float64); ok {
						t := time.Unix(int64(v), 0).UTC()
						fmt.Fprintf(&b, "%s = %s", k, t.Format(time.RFC3339))
						if k == "exp" {
							if time.Now().After(t) {
								b.WriteString(" (expired)")
							} else {
								b.WriteString(" (valid for " + time.Until(t).Round(time.Second).String() + ")")
							}
						}
						b.WriteString("\n")
					}
				}
			}
		}
	}
	b.WriteString("signature: not checked")
	return b.String(), nil
}
