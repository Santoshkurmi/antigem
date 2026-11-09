package codex

import (
	"archive/tar"
	"bufio"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

// Installing Codex from the app:
//   - native Termux: the Termux package (`pkg install codex`), a normal Android build kept up to date by Termux;
//   - elsewhere (Linux, proot Ubuntu): the official static Linux build from the latest GitHub release, checked
//     against the release's sha256, into ~/.local/bin/codex. A copy managed by Codex's own installer (a link into
//     ~/.codex/packages) is updated with `codex update` instead.

const latestReleaseURL = "https://api.github.com/repos/openai/codex/releases/latest"

// installJob is the one install / update that can run at a time; the app polls it.
type installJob struct {
	mu       sync.Mutex
	Kind     string
	Running  bool
	ExitCode *int
	Log      strings.Builder
	Started  time.Time
}

func (j *installJob) logf(format string, a ...interface{}) {
	j.mu.Lock()
	defer j.mu.Unlock()
	fmt.Fprintf(&j.Log, format+"\n", a...)
}

func (j *installJob) snapshot() map[string]interface{} {
	j.mu.Lock()
	defer j.mu.Unlock()
	return map[string]interface{}{"kind": j.Kind, "running": j.Running, "exit_code": j.ExitCode, "log": j.Log.String(), "started": j.Started}
}

func (m *Manager) install(ctx context.Context, j *installJob, update bool) error {
	if m.termuxNative() {
		if err := m.installTermuxPackage(ctx, j); err != nil {
			return err
		}
		m.afterInstall(j)
		return nil
	}
	if update {
		if bin, err := m.ResolveBinary(); err == nil {
			if real, err := filepath.EvalSymlinks(bin); err == nil && strings.Contains(real, filepath.Join(".codex", "packages")) {
				if err := m.runLogged(ctx, j, bin, "update"); err != nil {
					return err
				}
				m.afterInstall(j)
				return nil
			}
		}
	}
	return m.installRelease(ctx, j, update)
}

// runLogged runs a command and copies its output into the job log line by line.
func (m *Manager) runLogged(ctx context.Context, j *installJob, bin string, args ...string) error {
	j.logf("$ %s %s", filepath.Base(bin), strings.Join(args, " "))
	cmd := m.command(ctx, bin, args...)
	cmd.Env = append(cmd.Env, "DEBIAN_FRONTEND=noninteractive")
	out, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return err
	}
	sc := bufio.NewScanner(out)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		j.logf("%s", sc.Text())
	}
	if err := cmd.Wait(); err != nil {
		return fmt.Errorf("%s failed: %w", filepath.Base(bin), err)
	}
	return nil
}

func (m *Manager) installTermuxPackage(ctx context.Context, j *installJob) error {
	bin := filepath.Join(m.termuxPrefix(), "bin", "pkg")
	if _, err := os.Stat(bin); err != nil {
		bin = filepath.Join(m.termuxPrefix(), "bin", "apt-get")
		if err := m.runLogged(ctx, j, bin, "update"); err != nil {
			return err
		}
	}
	// installing an installed package upgrades it to the newest version
	return m.runLogged(ctx, j, bin, "install", "-y", "codex")
}

// afterInstall forgets the cached version and restarts a running app-server on its next use.
func (m *Manager) afterInstall(j *installJob) {
	m.verMu.Lock()
	m.version = ""
	m.verMu.Unlock()
	if bin, err := m.ResolveBinary(); err == nil {
		if v := m.Version(bin); v != "" {
			j.logf("Codex %s at %s", v, bin)
		}
	}
	if m.Running() {
		j.logf("Restarting the Codex server…")
		m.Stop()
	}
}

func releaseTarget() (string, error) {
	switch runtime.GOARCH {
	case "arm64":
		return "aarch64-unknown-linux-musl", nil
	case "amd64":
		return "x86_64-unknown-linux-musl", nil
	}
	return "", fmt.Errorf("Codex has no Linux build for %s", runtime.GOARCH)
}

func (m *Manager) installRelease(ctx context.Context, j *installJob, update bool) error {
	target, err := releaseTarget()
	if err != nil {
		return err
	}
	j.logf("Platform: %s", target)
	var rel struct {
		TagName string `json:"tag_name"`
		Assets  []struct {
			Name   string `json:"name"`
			URL    string `json:"browser_download_url"`
			Digest string `json:"digest"`
			Size   int64  `json:"size"`
		} `json:"assets"`
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, latestReleaseURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("could not get the latest release: %w", err)
	}
	err = json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(&rel)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || err != nil {
		return fmt.Errorf("could not get the latest release (HTTP %d)", resp.StatusCode)
	}
	version := strings.TrimPrefix(rel.TagName, "rust-v")
	j.logf("Latest version: %s", version)
	if update {
		if bin, err := m.ResolveBinary(); err == nil {
			if cur := m.Version(bin); cur == version {
				j.logf("Codex %s is already the latest version.", cur)
				return nil
			} else if cur != "" {
				j.logf("Installed: %s", cur)
			}
		}
	}
	name := "codex-" + target + ".tar.gz"
	var url, digest string
	for _, a := range rel.Assets {
		if a.Name == name {
			url, digest = a.URL, a.Digest
		}
	}
	if url == "" {
		return fmt.Errorf("the release has no %s", name)
	}
	j.logf("Downloading %s", url)
	dlReq, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	dl, err := http.DefaultClient.Do(dlReq)
	if err != nil {
		return err
	}
	defer dl.Body.Close()
	if dl.StatusCode != http.StatusOK {
		return fmt.Errorf("download: HTTP %d", dl.StatusCode)
	}
	destDir := filepath.Join(m.HomeDir, ".local", "bin")
	if err := os.MkdirAll(destDir, 0755); err != nil {
		return err
	}
	tgz, err := os.CreateTemp(destDir, ".codex-download-*.tgz")
	if err != nil {
		return err
	}
	defer os.Remove(tgz.Name())
	defer tgz.Close()
	h := sha256.New()
	var done int64
	total := dl.ContentLength
	buf := make([]byte, 256<<10)
	lastReport := time.Now()
	for {
		n, rerr := dl.Body.Read(buf)
		if n > 0 {
			if _, werr := tgz.Write(buf[:n]); werr != nil {
				return werr
			}
			h.Write(buf[:n])
			done += int64(n)
			if time.Since(lastReport) > 2*time.Second {
				lastReport = time.Now()
				if total > 0 {
					j.logf("  %d / %d MB", done>>20, total>>20)
				} else {
					j.logf("  %d MB", done>>20)
				}
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return fmt.Errorf("download: %w", rerr)
		}
	}
	j.logf("Downloaded %d MB", done>>20)
	if want := strings.TrimPrefix(digest, "sha256:"); want != "" && want != digest {
		if got := hex.EncodeToString(h.Sum(nil)); got != want {
			return errors.New("the download does not match the release's checksum")
		}
		j.logf("Checksum OK")
	}

	if _, err := tgz.Seek(0, io.SeekStart); err != nil {
		return err
	}
	gz, err := gzip.NewReader(tgz)
	if err != nil {
		return err
	}
	tr := tar.NewReader(gz)
	dest := filepath.Join(destDir, "codex")
	tmp := dest + ".new"
	found := false
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if filepath.Base(hdr.Name) != "codex-"+target {
			continue
		}
		f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0755)
		if err != nil {
			return err
		}
		if _, err := io.Copy(f, tr); err != nil {
			f.Close()
			os.Remove(tmp)
			return err
		}
		f.Close()
		found = true
		break
	}
	if !found {
		return errors.New("the download has no codex binary")
	}
	// a running app-server keeps its (old, now unlinked) binary until it restarts
	if err := os.Rename(tmp, dest); err != nil {
		os.Remove(tmp)
		return err
	}
	m.verMu.Lock()
	m.version = ""
	m.verMu.Unlock()
	if v := m.Version(dest); v == "" {
		return fmt.Errorf("installed %s, but it does not start on this device", dest)
	}
	m.afterInstall(j)
	return nil
}

// handleInstall: GET /api/codex/cli/job; POST /api/codex/cli/{install|update}.
func (m *Manager) handleInstall(w http.ResponseWriter, r *http.Request, action string) {
	m.jobMu.Lock()
	job := m.job
	m.jobMu.Unlock()
	if r.Method == http.MethodGet {
		var snap interface{}
		if job != nil {
			snap = job.snapshot()
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": snap})
		return
	}
	if r.Method != http.MethodPost || (action != "install" && action != "update") {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown cli action"})
		return
	}
	if job != nil {
		job.mu.Lock()
		running := job.Running
		job.mu.Unlock()
		if running {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "an install or update is already running"})
			return
		}
	}
	nj := &installJob{Kind: action, Running: true, Started: time.Now()}
	m.jobMu.Lock()
	m.job = nj
	m.jobMu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
		defer cancel()
		code := 0
		if err := m.install(ctx, nj, action == "update"); err != nil {
			nj.logf("Error: %v", err)
			code = 1
		}
		nj.mu.Lock()
		nj.Running = false
		nj.ExitCode = &code
		nj.mu.Unlock()
	}()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": nj.snapshot()})
}
