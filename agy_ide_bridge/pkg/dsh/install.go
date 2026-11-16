package dsh

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
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	npmPackage = "@deepseek-ai/dsh"
	// nodeMajor: dsh needs Node >= 24.9; the newest 24.x release is installed.
	nodeMajor = 24
	// dshVersionsKept: each version is ~900 MB of node_modules, so only the previous one is kept for a rollback.
	dshVersionsKept = 2
)

var (
	nodeDistURL      = "https://nodejs.org/dist"
	npmRegistryURL   = "https://registry.npmjs.org"
	versionDirNameRe = regexp.MustCompile(`^[0-9A-Za-z.+-]+$`)
)

// installJob is one install/update/remove run; the app polls it (GET /cli/job).
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
	// keep the log bounded (npm can be chatty)
	if j.Log.Len() > 256<<10 {
		s := j.Log.String()
		j.Log.Reset()
		j.Log.WriteString("…\n" + s[len(s)-128<<10:])
	}
}

func (j *installJob) snapshot() map[string]interface{} {
	j.mu.Lock()
	defer j.mu.Unlock()
	return map[string]interface{}{"kind": j.Kind, "running": j.Running, "exit_code": j.ExitCode, "log": j.Log.String(), "started": j.Started}
}

// nodePlatform is the official Node.js build for this host.
func nodePlatform() (string, error) {
	arch := ""
	switch runtime.GOARCH {
	case "arm64":
		arch = "arm64"
	case "amd64":
		arch = "x64"
	default:
		return "", fmt.Errorf("Node.js has no official build for %s", runtime.GOARCH)
	}
	switch runtime.GOOS {
	case "android":
		if arch != "arm64" {
			return "", errors.New("DeepSeek Harness on Android needs an arm64 device")
		}
		return "linux-arm64", nil
	case "linux":
		if _, err := os.Stat("/etc/alpine-release"); err == nil {
			return "", errors.New("the official Node.js build needs glibc; Alpine (musl) is not supported")
		}
		return "linux-" + arch, nil
	case "darwin":
		return "darwin-" + arch, nil
	}
	return "", fmt.Errorf("installing DeepSeek Harness from the app is not supported on %s", runtime.GOOS)
}

func httpGetBytes(ctx context.Context, url string, limit int64) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s: HTTP %d", url, resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, limit))
}

// latestDshVersion asks the npm registry for the `latest` dist-tag.
func latestDshVersion(ctx context.Context) (string, error) {
	b, err := httpGetBytes(ctx, npmRegistryURL+"/-/package/"+strings.ReplaceAll(npmPackage, "/", "%2f")+"/dist-tags", 1<<16)
	if err != nil {
		return "", err
	}
	var tags map[string]string
	if err := json.Unmarshal(b, &tags); err != nil {
		return "", fmt.Errorf("unexpected npm answer: %w", err)
	}
	v := tags["latest"]
	if v == "" || !versionDirNameRe.MatchString(v) {
		return "", fmt.Errorf("npm reported no usable latest version (%q)", v)
	}
	return v, nil
}

// latestNode returns the newest Node.js nodeMajor.x release.
func latestNode(ctx context.Context) (string, error) {
	b, err := httpGetBytes(ctx, nodeDistURL+"/index.json", 8<<20)
	if err != nil {
		return "", err
	}
	var list []struct {
		Version string `json:"version"`
	}
	if err := json.Unmarshal(b, &list); err != nil {
		return "", fmt.Errorf("unexpected nodejs.org answer: %w", err)
	}
	prefix := fmt.Sprintf("v%d.", nodeMajor)
	for _, r := range list { // newest first
		if strings.HasPrefix(r.Version, prefix) {
			return r.Version, nil
		}
	}
	return "", fmt.Errorf("nodejs.org lists no Node.js %d release", nodeMajor)
}

// ensureNode installs the newest Node.js 24 (unless it is already there) and records it in state.json.
func (m *Manager) ensureNode(ctx context.Context, j *installJob) error {
	platform, err := nodePlatform()
	if err != nil {
		return err
	}
	version, err := latestNode(ctx)
	if err != nil {
		if cur := m.l.readState().NodeVersion; cur != "" && m.nodeBinary() != "" {
			j.logf("Could not check for a newer Node.js (%v); keeping %s.", err, cur)
			return nil
		}
		return fmt.Errorf("cannot find the Node.js release: %w", err)
	}
	dir := m.l.nodeDir(version)
	if _, err := os.Stat(filepath.Join(dir, "bin", "node")); err == nil {
		j.logf("Node.js %s is installed.", version)
		return m.setNodeVersion(version)
	}
	name := fmt.Sprintf("node-%s-%s.tar.gz", version, platform)
	sums, err := httpGetBytes(ctx, nodeDistURL+"/"+version+"/SHASUMS256.txt", 1<<20)
	if err != nil {
		return fmt.Errorf("cannot get Node.js checksums: %w", err)
	}
	want := ""
	for _, line := range strings.Split(string(sums), "\n") {
		f := strings.Fields(line)
		if len(f) == 2 && f[1] == name {
			want = f[0]
		}
	}
	if len(want) != 64 {
		return fmt.Errorf("no checksum for %s", name)
	}
	if err := os.MkdirAll(m.l.root, 0o755); err != nil {
		return err
	}
	tmpFile := filepath.Join(m.l.root, "."+name+".download")
	defer os.Remove(tmpFile)
	j.logf("Downloading Node.js %s (%s)…", version, platform)
	if err := download(ctx, nodeDistURL+"/"+version+"/"+name, tmpFile, want, j); err != nil {
		return err
	}
	stage := dir + ".partial"
	_ = os.RemoveAll(stage)
	j.logf("Unpacking Node.js…")
	if err := extractNode(tmpFile, stage); err != nil {
		_ = os.RemoveAll(stage)
		return fmt.Errorf("unpacking Node.js: %w", err)
	}
	_ = os.RemoveAll(dir)
	if err := os.Rename(stage, dir); err != nil {
		return err
	}
	j.logf("Installed Node.js %s (checksum verified).", version)
	return m.setNodeVersion(version)
}

func (m *Manager) setNodeVersion(version string) error {
	old := m.l.readState().NodeVersion
	if err := m.l.writeState(installState{NodeVersion: version}); err != nil {
		return err
	}
	// a running dsh keeps the old Node until it restarts; remove the old one only when nothing uses it
	if old != "" && old != version && !m.Running() {
		_ = os.RemoveAll(m.l.nodeDir(old))
	}
	return nil
}

// download fetches url into dest, verifying its sha256.
func download(ctx context.Context, url, dest, sha string, j *installJob) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := (&http.Client{Timeout: 60 * time.Minute}).Do(req)
	if err != nil {
		return fmt.Errorf("download failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download failed: HTTP %d (%s)", resp.StatusCode, url)
	}
	f, err := os.OpenFile(dest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	h := sha256.New()
	total := resp.ContentLength
	var done int64
	last := time.Now()
	buf := make([]byte, 256<<10)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				f.Close()
				return werr
			}
			h.Write(buf[:n])
			done += int64(n)
			if time.Since(last) > 2*time.Second {
				last = time.Now()
				if total > 0 {
					j.logf("  %d of %d MB", done>>20, total>>20)
				} else {
					j.logf("  %d MB", done>>20)
				}
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			f.Close()
			return fmt.Errorf("download failed: %w", rerr)
		}
	}
	if err := f.Close(); err != nil {
		return err
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != sha {
		return fmt.Errorf("checksum mismatch for %s (got %s, expected %s)", filepath.Base(url), got, sha)
	}
	return nil
}

// extractNode unpacks a Node.js tarball into dir (without the headers and docs, which dsh does not need).
func extractNode(archive, dir string) error {
	f, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(bufio.NewReaderSize(f, 1<<20))
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		h, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		// node-vX-platform/<rest>
		parts := strings.SplitN(h.Name, "/", 2)
		if len(parts) < 2 || parts[1] == "" {
			continue
		}
		rel := filepath.Clean(parts[1])
		if strings.HasPrefix(rel, "..") || filepath.IsAbs(rel) {
			return fmt.Errorf("unsafe path in archive: %s", h.Name)
		}
		if strings.HasPrefix(rel, "include/") || strings.HasPrefix(rel, "share/") || rel == "include" || rel == "share" {
			continue
		}
		target := filepath.Join(dir, rel)
		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeSymlink:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			_ = os.Remove(target)
			if err := os.Symlink(h.Linkname, target); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			out, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, os.FileMode(h.Mode)&0o777|0o600)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, tr); err != nil {
				out.Close()
				return err
			}
			if err := out.Close(); err != nil {
				return err
			}
		}
	}
}

// install installs (or updates to) the latest dsh: Node.js first, then `npm install` into a new version folder,
// the Android patch, and only then the switch of `current` (a failed update leaves the working version in place).
func (m *Manager) install(ctx context.Context, j *installJob, update bool) error {
	if err := m.ensureNode(ctx, j); err != nil {
		return err
	}
	if err := m.writeRuntimeFiles(); err != nil {
		return err
	}
	if m.glibcLoader() != "" {
		if err := writeGlibcLibs(m.l.libDir()); err != nil {
			return fmt.Errorf("writing the glibc libraries: %w", err)
		}
	}
	version, err := latestDshVersion(ctx)
	if err != nil {
		return fmt.Errorf("cannot get the latest DeepSeek Harness version: %w", err)
	}
	j.logf("Latest DeepSeek Harness: %s", version)
	if cur := m.l.installedVersion(); cur == version {
		j.logf("DeepSeek Harness %s is already installed.", version)
		return m.afterInstall(j)
	}

	stage := filepath.Join(m.l.versionsDir(), "."+version+".partial")
	_ = os.RemoveAll(stage)
	if err := os.MkdirAll(stage, 0o755); err != nil {
		return err
	}
	ok := false
	defer func() {
		if !ok {
			_ = os.RemoveAll(stage)
		}
	}()
	if err := os.WriteFile(filepath.Join(stage, "package.json"), []byte(`{"name":"antigem-dsh-runtime","private":true}`+"\n"), 0o644); err != nil {
		return err
	}
	npmCli := filepath.Join(m.l.nodeDir(m.l.readState().NodeVersion), "lib", "node_modules", "npm", "bin", "npm-cli.js")
	j.logf("Installing %s@%s with npm (no compiling; this downloads ~650 packages)…", npmPackage, version)
	cmd, err := m.nodeCommand(ctx, true, npmCli, "install", npmPackage+"@"+version,
		"--no-audit", "--no-fund", "--omit=dev", "--foreground-scripts", "--loglevel=warn")
	if err != nil {
		return err
	}
	cmd.Dir = stage
	cmd.Env = append(cmd.Env,
		"npm_config_cache="+m.l.npmCache(),
		"npm_config_update_notifier=false",
		"npm_config_progress=false",
	)
	if p := m.termuxPrefix(); p != "" {
		// npm runs install scripts with /bin/sh, which Android does not have
		cmd.Env = append(cmd.Env, "npm_config_script_shell="+filepath.Join(p, "bin", "sh"))
	}
	if err := runLogged(cmd, j); err != nil {
		return fmt.Errorf("npm install failed: %w", err)
	}
	if _, err := os.Stat(binJS(stage)); err != nil {
		return errors.New("npm finished but dsh is missing from the install")
	}
	if m.termuxPrefix() != "" {
		if err := patchSessionLink(stage); err != nil {
			return fmt.Errorf("this DeepSeek Harness version cannot be adapted for Android: %w", err)
		}
		j.logf("Applied the Android patch (session saving without hard links).")
	}
	final := m.l.versionDir(version)
	_ = os.RemoveAll(final)
	if err := os.Rename(stage, final); err != nil {
		return err
	}
	ok = true
	if err := switchCurrent(m.l, version); err != nil {
		return err
	}
	j.logf("Installed DeepSeek Harness %s.", version)
	m.pruneVersions(j)
	return m.afterInstall(j)
}

func (m *Manager) afterInstall(j *installJob) error {
	if err := m.writeLauncher(); err != nil {
		j.logf("Could not write the `dsh` terminal command: %v", err)
	}
	// check that dsh starts with this runtime
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	cmd, err := m.nodeCommand(ctx, false, "--expose-internals", binJS(m.l.current()), "--version")
	if err != nil {
		return err
	}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("dsh does not start: %v: %s", err, truncate(strings.TrimSpace(string(out)), 600))
	}
	j.logf("dsh --version: %s", strings.TrimSpace(string(out)))
	if m.Running() {
		j.logf("Restarting the running DeepSeek Harness on its next use.")
		m.Stop()
	}
	return nil
}

func switchCurrent(l layout, version string) error {
	tmp := l.current() + ".new"
	_ = os.Remove(tmp)
	if err := os.Symlink(filepath.Join("versions", version), tmp); err != nil {
		return err
	}
	return os.Rename(tmp, l.current())
}

// pruneVersions keeps the newest few versions (and always the current one).
func (m *Manager) pruneVersions(j *installJob) {
	entries, err := os.ReadDir(m.l.versionsDir())
	if err != nil {
		return
	}
	cur := m.l.installedVersion()
	type ver struct {
		name string
		mod  time.Time
	}
	var vs []ver
	for _, e := range entries {
		if !e.IsDir() || strings.HasPrefix(e.Name(), ".") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		vs = append(vs, ver{e.Name(), info.ModTime()})
	}
	sort.Slice(vs, func(a, b int) bool { return vs[a].mod.After(vs[b].mod) })
	kept := 0
	for _, v := range vs {
		if v.name == cur || kept < dshVersionsKept {
			kept++
			continue
		}
		if os.RemoveAll(m.l.versionDir(v.name)) == nil {
			j.logf("Removed old version %s.", v.name)
		}
	}
}

// remove deletes everything the installer put in place (dsh's own chats in ~/.dsh stay).
func (m *Manager) remove(j *installJob) error {
	m.Stop()
	_ = os.Remove(filepath.Join(m.HomeDir, ".local", "bin", "dsh"))
	for _, p := range []string{m.l.current(), m.l.versionsDir(), m.l.npmCache(), m.l.libDir(), m.l.rtDir(), m.l.stateFile()} {
		if err := os.RemoveAll(p); err != nil {
			return err
		}
	}
	entries, _ := os.ReadDir(m.l.root)
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "node-") {
			_ = os.RemoveAll(filepath.Join(m.l.root, e.Name()))
		}
	}
	j.logf("Removed DeepSeek Harness (your chats in ~/.dsh are kept).")
	return nil
}

// patchSessionLink adapts session saving for Android, where SELinux denies link(2) in app storage: dsh publishes a
// session file with link(tmp, final) (which fails if final exists); the patch falls back to rename after the same
// existence check. A version whose code no longer matches is refused rather than installed broken.
func patchSessionLink(versionDir string) error {
	f := filepath.Join(versionDir, "node_modules", "@deepseek-ai", "dsh-session-persistence-jsonl", "lib", "index.js")
	b, err := os.ReadFile(f)
	if err != nil {
		return err
	}
	s := string(b)
	const marker = "SELinux denies link(2)"
	if strings.Contains(s, marker) {
		return nil
	}
	from := "\t\t\tawait link(tmp, finalPath);\n\t\t\tlinked = true;\n"
	to := "\t\t\ttry {\n\t\t\t\tawait link(tmp, finalPath);\n\t\t\t} catch (error) {\n" +
		"\t\t\t\t/* AntiGem: Android SELinux denies link(2); keep link's no-clobber rule, then rename */\n" +
		"\t\t\t\tif (error?.code !== \"EACCES\" && error?.code !== \"EPERM\") throw error;\n" +
		"\t\t\t\tif (await __antigemStat(finalPath).then(() => true, () => false)) throw Object.assign(new Error(\"EEXIST: \" + finalPath), { code: \"EEXIST\" });\n" +
		"\t\t\t\tawait __antigemRename(tmp, finalPath);\n\t\t\t}\n\t\t\tlinked = true;\n"
	if strings.Count(s, from) != 1 {
		if !strings.Contains(s, "link(") {
			// upstream stopped using hard links: nothing to adapt
			return nil
		}
		return errors.New("its session-saving code changed (please report this to AntiGem)")
	}
	s = strings.Replace(s, from, to, 1)
	// module-private names, so the patch never clashes with the file's own imports
	s = "import { rename as __antigemRename, stat as __antigemStat } from \"node:fs/promises\";\n" + s
	return os.WriteFile(f, []byte(s), 0o644)
}

// runLogged runs cmd, copying its combined output into the job log line by line.
func runLogged(cmd *exec.Cmd, j *installJob) error {
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
		if line := strings.TrimRight(sc.Text(), "\r"); line != "" {
			j.logf("  %s", line)
		}
	}
	return cmd.Wait()
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
