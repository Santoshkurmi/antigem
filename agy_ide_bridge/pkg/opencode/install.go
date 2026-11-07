package opencode

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha512"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

// Installing OpenCode from the app. The official v2 installer (https://opencode.ai/v2/install) downloads the npm
// package @opencode/cli-<target> and puts its binary in ~/.opencode/bin; this does the same in Go, so it also works
// on Termux (no bash script assumptions) and the sha512 of the download is checked against the registry's.

const latestURL = "https://opencode.ai/update/api/latest/cli/npm"

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

// installTarget is OpenCode's platform name (linux-arm64, linux-x64-baseline, linux-x64-musl…). Termux gets the glibc
// build: the bridge starts it through the app's bundled glibc loader.
func installTarget() (string, error) {
	var arch string
	switch runtime.GOARCH {
	case "arm64":
		arch = "arm64"
	case "amd64":
		arch = "x64"
	default:
		return "", fmt.Errorf("OpenCode has no build for %s", runtime.GOARCH)
	}
	switch runtime.GOOS {
	case "linux", "android":
	case "darwin":
		return "darwin-" + arch, nil
	default:
		return "", fmt.Errorf("installing OpenCode from the app is not supported on %s", runtime.GOOS)
	}
	target := "linux-" + arch
	if arch == "x64" {
		// the regular x64 build needs AVX2
		if data, err := os.ReadFile("/proc/cpuinfo"); err == nil && !strings.Contains(string(data), " avx2") {
			target += "-baseline"
		}
	}
	if runtime.GOOS == "linux" {
		if _, err := os.Stat("/etc/alpine-release"); err == nil {
			target += "-musl"
		} else if out, err := exec.Command("ldd", "--version").CombinedOutput(); err == nil || len(out) > 0 {
			if strings.Contains(strings.ToLower(string(out)), "musl") {
				target += "-musl"
			}
		}
	}
	return target, nil
}

func (m *Manager) installDir() string { return filepath.Join(m.HomeDir, ".opencode", "bin") }

func getJSON(ctx context.Context, u string, out interface{}) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d from %s", resp.StatusCode, u)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(out)
}

// install downloads the latest OpenCode into ~/.opencode/bin (an update replaces the binary; a running server is
// restarted on the next request).
func (m *Manager) install(ctx context.Context, j *installJob, update bool) error {
	target, err := installTarget()
	if err != nil {
		return err
	}
	j.logf("Platform: %s", target)

	var latest struct {
		Version  string `json:"version"`
		Metadata struct {
			Package string `json:"package"`
		} `json:"metadata"`
		Package string `json:"package"`
	}
	if err := getJSON(ctx, latestURL, &latest); err != nil {
		return fmt.Errorf("could not get the latest version: %w", err)
	}
	pkg := latest.Metadata.Package
	if pkg == "" {
		pkg = latest.Package
	}
	if pkg == "" {
		pkg = "@opencode/cli"
	}
	if latest.Version == "" {
		return errors.New("the version service gave no version")
	}
	j.logf("Latest version: %s", latest.Version)

	if update {
		if bin, err := m.ResolveBinary(); err == nil {
			if cur := strings.TrimPrefix(m.Version(bin), "v"); cur == latest.Version {
				j.logf("OpenCode %s is already the latest version.", cur)
				return nil
			} else if cur != "" {
				j.logf("Installed: %s", cur)
			}
		}
	}

	name := strings.TrimSuffix(pkg, "/cli") + "/cli-" + target
	var meta struct {
		Dist struct {
			Tarball   string `json:"tarball"`
			Integrity string `json:"integrity"`
		} `json:"dist"`
	}
	metaURL := "https://registry.npmjs.org/" + strings.Replace(url.PathEscape(name), "%40", "@", 1) + "/" + latest.Version
	if err := getJSON(ctx, metaURL, &meta); err != nil {
		return fmt.Errorf("no OpenCode %s build for %s: %w", latest.Version, target, err)
	}
	if meta.Dist.Tarball == "" {
		return errors.New("the registry gave no download")
	}
	j.logf("Downloading %s", meta.Dist.Tarball)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, meta.Dist.Tarball, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download: HTTP %d", resp.StatusCode)
	}
	if err := os.MkdirAll(m.installDir(), 0700); err != nil {
		return err
	}
	tgz, err := os.CreateTemp(m.installDir(), ".download-*.tgz")
	if err != nil {
		return err
	}
	defer os.Remove(tgz.Name())
	defer tgz.Close()
	h := sha512.New()
	var done int64
	total := resp.ContentLength
	buf := make([]byte, 256<<10)
	lastReport := time.Now()
	for {
		n, rerr := resp.Body.Read(buf)
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
	if want := meta.Dist.Integrity; strings.HasPrefix(want, "sha512-") {
		if got := "sha512-" + base64.StdEncoding.EncodeToString(h.Sum(nil)); got != want {
			return errors.New("the download does not match the registry's checksum")
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
	dest := filepath.Join(m.installDir(), "opencode")
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
		if hdr.Name != "package/bin/opencode" {
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
		return errors.New("the package has no opencode binary")
	}
	// a running server keeps its (old, now unlinked) binary until it restarts
	if err := os.Rename(tmp, dest); err != nil {
		os.Remove(tmp)
		return err
	}
	m.verMu.Lock()
	m.version = ""
	m.verMu.Unlock()
	v := m.Version(dest)
	if v == "" {
		return fmt.Errorf("installed %s, but it does not start on this device", dest)
	}
	j.logf("Installed OpenCode %s at %s", v, dest)
	if m.Running() {
		j.logf("Restarting the OpenCode server…")
		m.Stop()
	}
	return nil
}

// handleInstall: GET /api/opencode/cli/job; POST /api/opencode/cli/{install|update}.
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
	if action != "install" && action != "update" {
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
