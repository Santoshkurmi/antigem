package security

import (
	"bytes"
	"crypto/rand"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strings"
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

	if len(cliToken) == TokenLen {
		_ = os.MkdirAll(filepath.Dir(tokenFile), 0755)
		_ = os.WriteFile(tokenFile, []byte(cliToken), 0600)
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
	_ = os.MkdirAll(filepath.Dir(tokenFile), 0755)
	_ = os.WriteFile(tokenFile, []byte(newToken), 0600)
	return newToken
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

	file, err := os.OpenFile(binaryPath, os.O_RDWR, 0755)
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

// AuthMiddleware enforces the 20-byte framed token header on API endpoints.
func AuthMiddleware(token string, next http.Handler) http.Handler {
	expectedHeaderName := http.CanonicalHeaderKey(BuildFramedHeader(token))
	rawHeaderName := BuildFramedHeader(token)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Allow health/ping endpoints without auth
		path := r.URL.Path
		if path == "/api/health" || path == "/health" || path == "/ping" {
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

		// 3. Also check query param ?token= (for WebSocket handshake if headers cannot be set)
		if r.URL.Query().Get("token") == token {
			next.ServeHTTP(w, r)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized","message":"Missing or invalid AntiGem security token header"}`))
	})
}
