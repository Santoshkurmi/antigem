package handlers

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/hub"
)

// Installing and inspecting the Antigravity CLI (`agy`) on the machine the bridge runs on, so the app can offer
// "Install" and show the installed version the way it does for Claude Code.
//
//	GET  /api/agy/cli          -> {installed, path, version, job}
//	POST /api/agy/cli/install  -> starts the official installer (curl -fsSL https://antigravity.google/cli/install.sh | bash)
//	POST /api/agy/cli/update   -> runs `agy update` (agy's own updater: Google's release manifest for this platform)
//
// A running hub keeps the agy it started with until the server restarts; the app restarts the server after an update
// that installed a newer agy (the log's "Updated agy <old> -> <new>." line).

const agyInstallCommand = "curl -fsSL https://antigravity.google/cli/install.sh | bash"

type agyCliJob struct {
	mu       sync.Mutex
	Kind     string
	Running  bool
	ExitCode *int
	log      bytes.Buffer
}

func (j *agyCliJob) Write(p []byte) (int, error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.log.Write(p)
	// keep the log small
	if j.log.Len() > 64*1024 {
		rest := append([]byte(nil), j.log.Bytes()[j.log.Len()-32*1024:]...)
		j.log.Reset()
		j.log.Write(rest)
	}
	return len(p), nil
}

func (j *agyCliJob) snapshot() map[string]interface{} {
	j.mu.Lock()
	defer j.mu.Unlock()
	out := map[string]interface{}{"kind": j.Kind, "running": j.Running, "log": j.log.String()}
	if j.ExitCode != nil {
		out["exit_code"] = *j.ExitCode
	}
	return out
}

var (
	agyJobMu sync.Mutex
	agyJob   *agyCliJob

	agyVerMu    sync.Mutex
	agyVerPath  string
	agyVerStamp time.Time
	agyVerValue string
)

// agyPath is the agy the hub runs: the one the app named with --bin (installed or not yet), or, for a bridge started
// by hand without --bin, the one it found at startup. With neither, where Google's installer puts it.
func (h *Handler) agyPath() string {
	if h.HubManager != nil {
		if p := h.HubManager.GetAgyBin(); p != "" {
			return p
		}
	}
	return hub.InstallerAgyPath()
}

func agyInstalled(path string) bool {
	fi, err := os.Stat(path)
	return err == nil && !fi.IsDir()
}

// agyVersion runs `agy --version` (cached until the file changes).
func agyVersion(bin string) string {
	fi, err := os.Stat(bin)
	if err != nil {
		return ""
	}
	agyVerMu.Lock()
	defer agyVerMu.Unlock()
	if agyVerPath == bin && agyVerStamp.Equal(fi.ModTime()) && agyVerValue != "" {
		return agyVerValue
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, "--version")
	cmd.Stdin = nil
	// stdout only: agy logs to stderr before it is set up (on Android "ERROR: logging before google.Init: I… Initialized
	// Android local timezone…"), which is not an error and not the version
	out, _ := cmd.Output()
	v := versionLine(string(out))
	if v != "" {
		agyVerPath, agyVerStamp, agyVerValue = bin, fi.ModTime(), v
	}
	return v
}

var agyVersionRe = regexp.MustCompile(`^v?\d+(\.\d+)+\S*$`)

// versionLine is the first line that looks like a version ("1.5.3"), else the first line.
func versionLine(s string) string {
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(l); agyVersionRe.MatchString(l) {
			return l
		}
	}
	return firstLine(s)
}

func firstLine(s string) string {
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(l); l != "" {
			return l
		}
	}
	return ""
}

func (h *Handler) AgyCliHandler(w http.ResponseWriter, r *http.Request) {
	bin := h.agyPath()
	installed := agyInstalled(bin)
	resp := map[string]interface{}{"success": true, "installed": installed, "path": bin, "version": ""}
	if installed {
		resp["version"] = agyVersion(bin)
	}
	agyJobMu.Lock()
	job := agyJob
	agyJobMu.Unlock()
	if job != nil {
		resp["job"] = job.snapshot()
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) AgyCliInstallHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "POST only"})
		return
	}
	cmd := exec.Command("bash", "-lc", agyInstallCommand)
	h.runAgyJob(w, "install", cmd, func(job *agyCliJob, code int) {
		if code != 0 {
			return
		}
		target := h.agyPath()
		if !agyInstalled(target) {
			// the installer always uses ~/.local/bin/agy; the app is set to another path
			fmt.Fprintf(job, "\nagy was installed to %s, but the app is set to use %s. Change the agy path in the app's settings, then restart the server.\n", hub.InstallerAgyPath(), target)
			return
		}
		// installed: let the hub use it right away (it could not start without agy). Start waits for the hub,
		// so it runs on its own and the install shows as finished meanwhile.
		if h.HubManager != nil && !h.HubManager.Disabled && !h.HubManager.IsRunning() {
			go func() {
				h.HubManager.SetAgyBin(target)
				_ = h.HubManager.Start()
			}()
		}
	})
}

// AgyCliUpdateHandler runs `agy update` on the agy the hub uses. agy downloads the newest release for this platform
// (Termux gets the Android build) and replaces itself; the hub patches the new binary when it next starts.
func (h *Handler) AgyCliUpdateHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "POST only"})
		return
	}
	bin := h.agyPath()
	if !agyInstalled(bin) {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "agy is not installed at " + bin})
		return
	}
	before := agyVersion(bin)
	cmd := exec.Command(bin, "update")
	h.runAgyJob(w, "update", cmd, func(job *agyCliJob, code int) {
		if code != 0 {
			return
		}
		after := agyVersion(bin)
		switch {
		case after != "" && before != "" && after != before:
			// the app restarts the server when it sees this line (keep the format)
			fmt.Fprintf(job, "\nUpdated agy %s -> %s.\nThe running hub keeps using %s until the server restarts.\n", before, after, before)
		case after != "":
			fmt.Fprintf(job, "\nagy %s is the latest version.\n", after)
		}
	})
}

// runAgyJob runs [cmd] as the (single) agy CLI job, streaming its output into the job log, and answers the request
// with the job. [done] runs after the command exits, before the job is marked finished.
func (h *Handler) runAgyJob(w http.ResponseWriter, kind string, cmd *exec.Cmd, done func(job *agyCliJob, code int)) {
	agyJobMu.Lock()
	if agyJob != nil {
		agyJob.mu.Lock()
		running := agyJob.Running
		runningKind := agyJob.Kind
		agyJob.mu.Unlock()
		if running {
			agyJobMu.Unlock()
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "an agy " + runningKind + " is already running"})
			return
		}
	}
	job := &agyCliJob{Kind: kind, Running: true}
	agyJob = job
	agyJobMu.Unlock()

	home, _ := os.UserHomeDir()
	cmd.Dir = home
	cmd.Env = os.Environ()
	cmd.Stdout = job
	cmd.Stderr = job
	if err := cmd.Start(); err != nil {
		job.mu.Lock()
		code := 1
		job.Running = false
		job.ExitCode = &code
		job.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	go func() {
		_ = cmd.Wait()
		code := 0
		if cmd.ProcessState != nil {
			code = cmd.ProcessState.ExitCode()
		}
		if done != nil {
			done(job, code)
		}
		job.mu.Lock()
		job.Running = false
		job.ExitCode = &code
		job.mu.Unlock()
	}()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": job.snapshot()})
}
