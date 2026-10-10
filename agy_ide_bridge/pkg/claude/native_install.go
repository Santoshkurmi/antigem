package claude

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

// On Android (Termux) the official Claude Code binary is built for glibc, so it cannot run itself: the official
// install.sh fails there, and `claude install` / `claude update` would treat the glibc loader as their own
// executable. The bridge therefore installs and updates it like install.sh does (same download, same checksum),
// but only places the binary in ~/.local/share/claude/versions/<version>. The app's wrapper (outside of everything
// Claude's own updater touches) always starts the newest version found there through the bundled glibc loader.

const nativeReleasesURL = "https://downloads.claude.ai/claude-code-releases"

// nativeVersionsKept: each version is ~250 MB; older ones are removed after an install.
const nativeVersionsKept = 2

var nativeVersionRe = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+$`)

func (m *Manager) nativeVersionsDir() string {
	return filepath.Join(m.HomeDir, ".local", "share", "claude", "versions")
}

// hasNativeVersion: a downloaded Claude Code version is in the versions folder.
func (m *Manager) hasNativeVersion() bool {
	entries, _ := os.ReadDir(m.nativeVersionsDir())
	for _, e := range entries {
		if !e.IsDir() && nativeVersionRe.MatchString(e.Name()) {
			return true
		}
	}
	return false
}

func nativePlatform() (string, error) {
	switch runtime.GOARCH {
	case "arm64":
		return "linux-arm64", nil
	case "amd64":
		return "linux-x64", nil
	}
	return "", fmt.Errorf("unsupported architecture %s", runtime.GOARCH)
}

// nativeInstall downloads the latest Claude Code release into the versions folder (nothing to do when it is
// already there) and keeps the newest few versions.
func (m *Manager) nativeInstall(log io.Writer) error {
	platform, err := nativePlatform()
	if err != nil {
		return err
	}
	small := &http.Client{Timeout: 30 * time.Second}

	fmt.Fprintln(log, "Checking the latest Claude Code release…")
	verBytes, err := httpGet(small, nativeReleasesURL+"/latest", 1<<10)
	if err != nil {
		return fmt.Errorf("cannot get the latest version: %w", err)
	}
	version := strings.TrimSpace(string(verBytes))
	if !nativeVersionRe.MatchString(version) {
		return fmt.Errorf("unexpected version answer %q", version)
	}
	dir := m.nativeVersionsDir()
	target := filepath.Join(dir, version)
	if fi, err := os.Stat(target); err == nil && fi.Size() > 0 {
		fmt.Fprintf(log, "Claude Code %s is already installed (latest).\n", version)
		m.pruneNativeVersions(log)
		return nil
	}

	manifestBytes, err := httpGet(small, nativeReleasesURL+"/"+version+"/manifest.json", 1<<20)
	if err != nil {
		return fmt.Errorf("cannot get the release manifest: %w", err)
	}
	var manifest struct {
		Platforms map[string]struct {
			Checksum string `json:"checksum"`
			Size     int64  `json:"size"`
		} `json:"platforms"`
	}
	if err := json.Unmarshal(manifestBytes, &manifest); err != nil {
		return fmt.Errorf("invalid release manifest: %w", err)
	}
	entry, ok := manifest.Platforms[platform]
	if !ok || len(entry.Checksum) != 64 {
		return fmt.Errorf("platform %s not found in the %s manifest", platform, version)
	}

	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	// a hidden name: the wrapper only starts files named like a version
	tmp := filepath.Join(dir, "."+version+".download")
	defer os.Remove(tmp)
	fmt.Fprintf(log, "Downloading Claude Code %s (%s, %d MB)…\n", version, platform, entry.Size>>20)
	if err := downloadVerified(nativeReleasesURL+"/"+version+"/"+platform+"/claude", tmp, entry.Size, entry.Checksum, log); err != nil {
		return err
	}
	if err := os.Chmod(tmp, 0o755); err != nil {
		return err
	}
	if err := os.Rename(tmp, target); err != nil {
		return err
	}
	fmt.Fprintf(log, "Installed Claude Code %s (checksum verified).\n", version)
	m.pruneNativeVersions(log)
	return nil
}

func httpGet(client *http.Client, url string, limit int64) ([]byte, error) {
	resp, err := client.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s: HTTP %d", url, resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, limit))
}

func downloadVerified(url, dest string, size int64, checksum string, log io.Writer) error {
	client := &http.Client{Timeout: 60 * time.Minute}
	resp, err := client.Get(url)
	if err != nil {
		return fmt.Errorf("download failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download failed: HTTP %d", resp.StatusCode)
	}
	f, err := os.OpenFile(dest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	h := sha256.New()
	pw := &progressWriter{total: size, log: log}
	_, copyErr := io.Copy(io.MultiWriter(f, h, pw), resp.Body)
	closeErr := f.Close()
	if copyErr != nil {
		return fmt.Errorf("download failed: %w", copyErr)
	}
	if closeErr != nil {
		return closeErr
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != checksum {
		return fmt.Errorf("checksum mismatch (got %s, expected %s)", got, checksum)
	}
	return nil
}

type progressWriter struct {
	total, done int64
	lastPct     int64
	log         io.Writer
}

func (p *progressWriter) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	if p.total > 0 {
		if pct := p.done * 100 / p.total; pct >= p.lastPct+10 {
			p.lastPct = pct - pct%10
			fmt.Fprintf(p.log, "  %d%% (%d of %d MB)\n", p.lastPct, p.done>>20, p.total>>20)
		}
	}
	return len(b), nil
}

// pruneNativeVersions removes all but the newest versions (Claude's own cleanup cannot be relied on here).
func (m *Manager) pruneNativeVersions(log io.Writer) {
	entries, err := os.ReadDir(m.nativeVersionsDir())
	if err != nil {
		return
	}
	var versions []string
	for _, e := range entries {
		if !e.IsDir() && nativeVersionRe.MatchString(e.Name()) {
			versions = append(versions, e.Name())
		}
	}
	sort.Slice(versions, func(i, j int) bool { return versionLess(versions[j], versions[i]) })
	for _, old := range versions[min(len(versions), nativeVersionsKept):] {
		if os.Remove(filepath.Join(m.nativeVersionsDir(), old)) == nil {
			fmt.Fprintf(log, "Removed old version %s.\n", old)
		}
	}
}

func versionLess(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) && i < len(pb); i++ {
		x, _ := strconv.Atoi(pa[i])
		y, _ := strconv.Atoi(pb[i])
		if x != y {
			return x < y
		}
	}
	return len(pa) < len(pb)
}
