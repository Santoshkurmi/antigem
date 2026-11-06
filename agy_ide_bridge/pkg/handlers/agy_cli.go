package handlers

import (
	"bytes"
	"context"
	"net/http"
	"os"
	"os/exec"
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

// currentAgyBinary is the agy the hub uses, or the first one found on this machine ("" = none).
func (h *Handler) currentAgyBinary() string {
	if h.HubManager != nil {
		if p := h.HubManager.GetAgyBin(); p != "" {
			if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
				return p
			}
		}
	}
	if bins := hub.FindAllAgyBinaries(); len(bins) > 0 {
		return bins[0]
	}
	return ""
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
	out, _ := cmd.CombinedOutput()
	v := firstLine(string(out))
	if v != "" {
		agyVerPath, agyVerStamp, agyVerValue = bin, fi.ModTime(), v
	}
	return v
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
	bin := h.currentAgyBinary()
	resp := map[string]interface{}{"success": true, "installed": bin != "", "path": bin, "version": ""}
	if bin != "" {
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
	agyJobMu.Lock()
	if agyJob != nil {
		agyJob.mu.Lock()
		running := agyJob.Running
		agyJob.mu.Unlock()
		if running {
			agyJobMu.Unlock()
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "an install is already running"})
			return
		}
	}
	job := &agyCliJob{Kind: "install", Running: true}
	agyJob = job
	agyJobMu.Unlock()

	home, _ := os.UserHomeDir()
	cmd := exec.Command("bash", "-lc", agyInstallCommand)
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
		job.mu.Lock()
		job.Running = false
		job.ExitCode = &code
		job.mu.Unlock()
		// installed: let the hub use it right away (it could not start without agy)
		if code == 0 && h.HubManager != nil && !h.HubManager.Disabled && !h.HubManager.IsRunning() {
			if bins := hub.FindAllAgyBinaries(); len(bins) > 0 {
				h.HubManager.SetAgyBin(bins[0])
				_ = h.HubManager.Start()
			}
		}
	}()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": job.snapshot()})
}
