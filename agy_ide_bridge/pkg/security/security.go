package security

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	Prefix        = "x-ag"
	Suffix        = "_9qx"
	TokenLen      = 12
	HeaderLen     = 20
	DefaultHeader = "x-codeium-csrf-token"
	charset       = "0123456789abcdefghijklmnopqrstuvwxyz"
)

// GenerateToken generates a cryptographically secure 12-character alphanumeric token.
func GenerateToken() string {
	b := make([]byte, TokenLen)
	for i := 0; i < TokenLen; i++ {
		num, err := rand.Int(rand.Reader, big.NewInt(int64(len(charset))))
		if err != nil {
			b[i] = charset[(time.Now().UnixNano()+int64(i))%int64(len(charset))]
		} else {
			b[i] = charset[num.Int64()]
		}
	}
	return string(b)
}

// BuildFramedHeader constructs the 20-byte HTTP header name from a 12-byte token.
func BuildFramedHeader(token string) string {
	token = strings.TrimSpace(token)
	if len(token) > TokenLen {
		token = token[:TokenLen]
	} else if len(token) < TokenLen {
		token = fmt.Sprintf("%-12s", token)
	}
	return Prefix + token + Suffix
}

// getTokenFilePath returns ~/.antigem_bridge/csrf_token.txt
func getTokenFilePath() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = "/data/data/com.termux/files/home"
	}
	return filepath.Join(home, ".antigem_bridge", "csrf_token.txt")
}

// ResolveToken determines the active token from CLI argument, file, or new generation.
func ResolveToken(cliToken string) string {
	cliToken = strings.TrimSpace(cliToken)
	tokenFile := getTokenFilePath()

	// the folder holds the token: only the owner may list or enter it (also fixes one made 0755 by older versions)
	_ = os.MkdirAll(filepath.Dir(tokenFile), 0700)
	_ = os.Chmod(filepath.Dir(tokenFile), 0700)

	if len(cliToken) == TokenLen {
		writeTokenFile(tokenFile, cliToken)
		return cliToken
	}

	// Try reading saved token file
	if data, err := os.ReadFile(tokenFile); err == nil {
		saved := strings.TrimSpace(string(data))
		if len(saved) == TokenLen {
			return saved
		}
	}

	// Generate and persist new token
	newToken := GenerateToken()
	writeTokenFile(tokenFile, newToken)
	return newToken
}

// writeTokenFile saves the token 0600. os.WriteFile keeps the mode of a file that already exists, so chmod after.
func writeTokenFile(path, token string) {
	_ = os.WriteFile(path, []byte(token), 0600)
	_ = os.Chmod(path, 0600)
}

// MaskSecret shows only the start of a secret ("abcd…"), for logs other people may read.
func MaskSecret(s string) string {
	if len(s) <= 4 {
		return "…"
	}
	return s[:4] + "…"
}

// PatchAgyHeader rotates the CSRF header in the target binary in-place to the 20-byte framed header.
func PatchAgyHeader(binaryPath string, token string) (string, time.Duration, error) {
	t0 := time.Now()
	if binaryPath == "" {
		return "", 0, errors.New("empty binary path")
	}

	// Resolve symlinks if binary is a symlink
	realPath, err := filepath.EvalSymlinks(binaryPath)
	if err == nil {
		binaryPath = realPath
	}

	fi, err := os.Stat(binaryPath)
	if err != nil {
		return "", 0, fmt.Errorf("binary not found: %w", err)
	}
	fileSize := int(fi.Size())
	if fileSize < HeaderLen {
		return "", 0, errors.New("binary file too small")
	}

	newHeaderStr := BuildFramedHeader(token)
	newHeader := []byte(newHeaderStr)
	prefixBytes := []byte(Prefix)
	suffixBytes := []byte(Suffix)

	// Already patched for this token: nothing to write. Checked read-only first because Linux refuses to open a file
	// for writing while a process runs it ("text file busy"): an agy hub still exiting after a restart, or agy open in
	// a terminal, made the hub fail to start although the file needed no change.
	if alreadyPatched(binaryPath, fileSize, newHeader) {
		return newHeaderStr, time.Since(t0), nil
	}

	file, err := openForPatch(binaryPath)
	if err != nil {
		return "", 0, fmt.Errorf("failed to open binary for writing: %w", err)
	}
	defer file.Close()

	// Memory-mapped in-place scan and replace
	data, mmapErr := syscall.Mmap(int(file.Fd()), 0, fileSize, syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if mmapErr == nil {
		defer syscall.Munmap(data)

		var oldHeader []byte
		pos := 0
		for {
			idx := bytes.Index(data[pos:], prefixBytes)
			if idx == -1 {
				break
			}
			absIdx := pos + idx
			if absIdx+HeaderLen <= len(data) && bytes.Equal(data[absIdx+16:absIdx+20], suffixBytes) {
				oldHeader = make([]byte, HeaderLen)
				copy(oldHeader, data[absIdx:absIdx+HeaderLen])
				break
			}
			pos = absIdx + 1
		}

		if oldHeader == nil {
			if bytes.Contains(data, []byte(DefaultHeader)) {
				oldHeader = []byte(DefaultHeader)
			}
		}

		if oldHeader == nil {
			if bytes.Contains(data, newHeader) {
				// Already patched with the current active token
				return newHeaderStr, time.Since(t0), nil
			}
			return "", 0, errors.New("cannot locate CSRF header anchor in AGY binary")
		}

		if bytes.Equal(oldHeader, newHeader) {
			return newHeaderStr, time.Since(t0), nil
		}

		// Replace all occurrences in-place
		pos = 0
		for {
			idx := bytes.Index(data[pos:], oldHeader)
			if idx == -1 {
				break
			}
			absIdx := pos + idx
			copy(data[absIdx:absIdx+HeaderLen], newHeader)
			pos = absIdx + HeaderLen
		}

		return newHeaderStr, time.Since(t0), nil
	}

	// Fallback without mmap
	buf, err := os.ReadFile(binaryPath)
	if err != nil {
		return "", 0, fmt.Errorf("failed to read binary: %w", err)
	}

	var oldHeader []byte
	pos := 0
	for {
		idx := bytes.Index(buf[pos:], prefixBytes)
		if idx == -1 {
			break
		}
		absIdx := pos + idx
		if absIdx+HeaderLen <= len(buf) && bytes.Equal(buf[absIdx+16:absIdx+20], suffixBytes) {
			oldHeader = make([]byte, HeaderLen)
			copy(oldHeader, buf[absIdx:absIdx+HeaderLen])
			break
		}
		pos = absIdx + 1
	}

	if oldHeader == nil && bytes.Contains(buf, []byte(DefaultHeader)) {
		oldHeader = []byte(DefaultHeader)
	}

	if oldHeader == nil {
		if bytes.Contains(buf, newHeader) {
			return newHeaderStr, time.Since(t0), nil
		}
		return "", 0, errors.New("cannot locate CSRF header anchor in AGY binary")
	}

	if !bytes.Equal(oldHeader, newHeader) {
		buf = bytes.ReplaceAll(buf, oldHeader, newHeader)
		if err := os.WriteFile(binaryPath, buf, fi.Mode().Perm()); err != nil {
			return "", 0, fmt.Errorf("failed to write patched binary: %w", err)
		}
	}

	return newHeaderStr, time.Since(t0), nil
}

// alreadyPatched reports whether the file at path (size bytes) holds newHeader and neither agy's standard header nor
// another token's framed one, reading it only.
func alreadyPatched(path string, size int, newHeader []byte) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	data, err := syscall.Mmap(int(f.Fd()), 0, size, syscall.PROT_READ, syscall.MAP_SHARED)
	if err != nil {
		return false
	}
	defer syscall.Munmap(data)
	if bytes.Contains(data, []byte(DefaultHeader)) {
		return false
	}
	prefixBytes, suffixBytes := []byte(Prefix), []byte(Suffix)
	found := false
	for pos := 0; ; {
		idx := bytes.Index(data[pos:], prefixBytes)
		if idx == -1 {
			break
		}
		at := pos + idx
		if at+HeaderLen <= len(data) && bytes.Equal(data[at+HeaderLen-len(Suffix):at+HeaderLen], suffixBytes) {
			if !bytes.Equal(data[at:at+HeaderLen], newHeader) {
				return false
			}
			found = true
		}
		pos = at + 1
	}
	return found
}

// patchBusyWait: how long a write to agy waits while it is busy (an agy being stopped can take a few seconds to exit).
const patchBusyWait = 5 * time.Second

// openForPatch opens the file for writing; while Linux refuses because a process runs it, it tries again for a moment.
func openForPatch(path string) (*os.File, error) {
	deadline := time.Now().Add(patchBusyWait)
	for {
		f, err := os.OpenFile(path, os.O_RDWR, 0755)
		if err == nil || !errors.Is(err, syscall.ETXTBSY) || time.Now().After(deadline) {
			return f, err
		}
		time.Sleep(250 * time.Millisecond)
	}
}

// UnpatchAgyHeader restores any framed 20-byte security header back to the standard DefaultHeader (x-codeium-csrf-token).
func UnpatchAgyHeader(binaryPath string) (string, time.Duration, error) {
	t0 := time.Now()
	if binaryPath == "" {
		return "", 0, errors.New("empty binary path")
	}

	// Resolve symlinks if binary is a symlink
	realPath, err := filepath.EvalSymlinks(binaryPath)
	if err == nil {
		binaryPath = realPath
	}

	fi, err := os.Stat(binaryPath)
	if err != nil {
		return "", 0, fmt.Errorf("binary not found: %w", err)
	}
	fileSize := int(fi.Size())
	if fileSize < HeaderLen {
		return "", 0, errors.New("binary file too small")
	}

	defaultHeaderBytes := []byte(DefaultHeader)
	prefixBytes := []byte(Prefix)
	suffixBytes := []byte(Suffix)

	file, err := os.OpenFile(binaryPath, os.O_RDWR, 0755)
	if err != nil {
		return "", 0, fmt.Errorf("failed to open binary for writing: %w", err)
	}
	defer file.Close()

	// Memory-mapped in-place scan and replace
	data, mmapErr := syscall.Mmap(int(file.Fd()), 0, fileSize, syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if mmapErr == nil {
		defer syscall.Munmap(data)

		replacedCount := 0
		pos := 0
		for {
			idx := bytes.Index(data[pos:], prefixBytes)
			if idx == -1 {
				break
			}
			absIdx := pos + idx
			if absIdx+HeaderLen <= len(data) && bytes.Equal(data[absIdx+16:absIdx+20], suffixBytes) {
				copy(data[absIdx:absIdx+HeaderLen], defaultHeaderBytes)
				replacedCount++
				pos = absIdx + HeaderLen
			} else {
				pos = absIdx + 1
			}
		}

		if replacedCount == 0 {
			if bytes.Contains(data, defaultHeaderBytes) {
				return DefaultHeader, time.Since(t0), nil
			}
			return "", 0, errors.New("neither patched framed header nor default header found in binary")
		}

		return DefaultHeader, time.Since(t0), nil
	}

	// Fallback without mmap
	buf, err := os.ReadFile(binaryPath)
	if err != nil {
		return "", 0, fmt.Errorf("failed to read binary: %w", err)
	}

	replacedCount := 0
	pos := 0
	for {
		idx := bytes.Index(buf[pos:], prefixBytes)
		if idx == -1 {
			break
		}
		absIdx := pos + idx
		if absIdx+HeaderLen <= len(buf) && bytes.Equal(buf[absIdx+16:absIdx+20], suffixBytes) {
			copy(buf[absIdx:absIdx+HeaderLen], defaultHeaderBytes)
			replacedCount++
			pos = absIdx + HeaderLen
		} else {
			pos = absIdx + 1
		}
	}

	if replacedCount == 0 {
		if bytes.Contains(buf, defaultHeaderBytes) {
			return DefaultHeader, time.Since(t0), nil
		}
		return "", 0, errors.New("neither patched framed header nor default header found in binary")
	}

	if err := os.WriteFile(binaryPath, buf, fi.Mode().Perm()); err != nil {
		return "", 0, fmt.Errorf("failed to write unpatched binary: %w", err)
	}

	return DefaultHeader, time.Since(t0), nil
}

// AuthMiddleware enforces the 20-byte framed token header on API endpoints.
func AuthMiddleware(token string, next http.Handler) http.Handler {
	expectedHeaderName := http.CanonicalHeaderKey(BuildFramedHeader(token))
	rawHeaderName := BuildFramedHeader(token)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Allow health/ping endpoints without auth: exact paths only, and never a WebSocket upgrade (a WebSocket on an
		// exempt path would skip the token)
		path := r.URL.Path
		if (path == "/api/health" || path == "/health" || path == "/ping") && r.Header.Get("Upgrade") == "" {
			next.ServeHTTP(w, r)
			return
		}

		// 1. Check framed header presence (canonical or raw)
		if r.Header.Get(expectedHeaderName) != "" || r.Header.Get(rawHeaderName) != "" {
			next.ServeHTTP(w, r)
			return
		}

		// 2. Case-insensitive header loop fallback
		for k, v := range r.Header {
			if strings.EqualFold(k, rawHeaderName) && len(v) > 0 && v[0] != "" {
				next.ServeHTTP(w, r)
				return
			}
		}

		// The token is never accepted in the URL: URLs end up in proxy access logs, browser history and referrers.

		ip := clientIP(r)
		if authFailures.blocked(ip) {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Retry-After", "300")
			w.WriteHeader(http.StatusTooManyRequests)
			w.Write([]byte(`{"error":"too_many_attempts","message":"Too many unauthorized requests. Try again later."}`))
			return
		}
		authFailures.fail(ip)

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized","message":"Missing or invalid AntiGem security token header"}`))
	})
}

// failureLimiter blocks an address after too many unauthorized requests (the bridge may be on the internet).
type failureLimiter struct {
	mu      sync.Mutex
	entries map[string]*failureEntry
}

type failureEntry struct {
	count int
	first time.Time
	until time.Time
}

const (
	maxAuthFailures = 30
	failureWindow   = 5 * time.Minute
	blockDuration   = 5 * time.Minute
)

var authFailures = &failureLimiter{entries: make(map[string]*failureEntry)}

func (l *failureLimiter) blocked(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	e := l.entries[ip]
	return e != nil && time.Now().Before(e.until)
}

func (l *failureLimiter) fail(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	e := l.entries[ip]
	if e == nil || now.Sub(e.first) > failureWindow {
		e = &failureEntry{first: now}
		l.entries[ip] = e
	}
	e.count++
	if e.count >= maxAuthFailures {
		e.until = now.Add(blockDuration)
		e.count = 0
		e.first = now
	}
	if len(l.entries) > 4096 {
		for k, v := range l.entries {
			if now.Sub(v.first) > failureWindow && now.After(v.until) {
				delete(l.entries, k)
			}
		}
	}
}

// clientIP is the caller's address; behind a reverse proxy on this machine it is the forwarded one.
// TrustProxyHeaders: X-Forwarded-For from loopback names the real client (a reverse proxy on a server). Off with
// --no-proxy-headers (the phone): there every app reaches the bridge on loopback and could change the header on each
// try to get around the failure limit.
var TrustProxyHeaders = true

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if ip := net.ParseIP(host); TrustProxyHeaders && ip != nil && ip.IsLoopback() {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			if i := strings.Index(xff, ","); i >= 0 {
				xff = xff[:i]
			}
			return strings.TrimSpace(xff)
		}
	}
	return host
}

// GuardResponse is the last line of defence against the secret leaving this process in a response: whatever a handler
// does, a response header whose value contains the token or the framed header name is removed before it is sent.
func GuardResponse(token string, next http.Handler) http.Handler {
	if strings.TrimSpace(token) == "" {
		return next
	}
	secrets := []string{strings.ToLower(token), strings.ToLower(BuildFramedHeader(token))}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		next.ServeHTTP(&guardWriter{ResponseWriter: w, secrets: secrets}, r)
	})
}

type guardWriter struct {
	http.ResponseWriter
	secrets []string
	sent    bool
}

func (g *guardWriter) scrub() {
	if g.sent {
		return
	}
	g.sent = true
	h := g.ResponseWriter.Header()
	for name, values := range h {
	check:
		for _, v := range values {
			lv := strings.ToLower(v)
			for _, s := range g.secrets {
				if s != "" && strings.Contains(lv, s) {
					h.Del(name)
					break check
				}
			}
		}
	}
}

func (g *guardWriter) WriteHeader(code int) {
	g.scrub()
	g.ResponseWriter.WriteHeader(code)
}

func (g *guardWriter) Write(b []byte) (int, error) {
	g.scrub()
	return g.ResponseWriter.Write(b)
}

func (g *guardWriter) Flush() {
	g.scrub()
	if f, ok := g.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// Hijack keeps WebSocket upgrades working.
func (g *guardWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	if hj, ok := g.ResponseWriter.(http.Hijacker); ok {
		return hj.Hijack()
	}
	return nil, nil, errors.New("hijacking not supported")
}

func (g *guardWriter) Unwrap() http.ResponseWriter { return g.ResponseWriter }
