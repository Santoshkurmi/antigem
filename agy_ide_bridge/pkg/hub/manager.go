package hub

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"gemini-server/pkg/config"
	"gemini-server/pkg/security"
)

var (
	loginURLRegex   = regexp.MustCompile(`https://accounts\.google\.com/[^\s"'<>]+`)
	csrfJSONPattern = regexp.MustCompile(`"csrfToken":\s*"([^"]+)"`)
	csrfHTMLPattern = regexp.MustCompile(`(?i)(?:csrf[_-]?token|csrfToken)["']?\s*[:=]\s*["']([^"']+)["']`)
)

const (
	HubStatusIdle     = "idle"
	HubStatusStarting = "starting"
	HubStatusOnline   = "online"
	HubStatusError    = "error"
	HubStatusStopped  = "stopped"
)

// HubManager supervises the background `agy --hub` process on port 1235.
type HubManager struct {
	HubPort        string
	WorkspaceDir   string
	AppDataDir     string
	AgyBinPath     string
	SecurityToken  string
	OnLoginURL     func(url string)
	OnStatusChange func(status string, csrfToken string, errorMsg string, logs []string)

	cmd         *exec.Cmd
	stdinPipe   io.WriteCloser
	cancel      context.CancelFunc
	status      string
	csrfToken   string
	lastError   string
	isRunning   bool
	isExternal  bool
	processDone chan struct{}
	monitorStop chan struct{}
	recentLogs  []string
	mu          sync.Mutex
}

// FindAllAgyBinaries discovers all existing AGY binaries in candidate paths and PATH.
func FindAllAgyBinaries() []string {
	home, _ := os.UserHomeDir()
	var rawCandidates []string
	if home != "" {
		rawCandidates = append(rawCandidates,
			filepath.Join(home, ".local", "bin", "agy"),
			filepath.Join(home, ".gemini", "bin", "agy"),
			filepath.Join(home, ".antigravity", "bin", "agy"),
			filepath.Join(home, "usr", "bin", "agy"),
			filepath.Join(home, "..", "usr", "bin", "agy"),
		)
	}
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		rawCandidates = append(rawCandidates, filepath.Join(prefix, "bin", "agy"))
	}
	if p := config.SafeLookPath("agy"); p != "" {
		rawCandidates = append(rawCandidates, p)
	}

	seen := make(map[string]bool)
	var results []string

	for _, c := range rawCandidates {
		if c == "" {
			continue
		}
		absPath, err := filepath.Abs(c)
		if err != nil {
			absPath = c
		}
		absPath = filepath.Clean(absPath)
		if seen[absPath] {
			continue
		}
		if fi, err := os.Stat(absPath); err == nil && !fi.IsDir() {
			seen[absPath] = true
			results = append(results, absPath)
		}
	}
	return results
}

// ResolveAgyBinaryInteractive resolves the AGY binary path. If customPath is provided, it validates existence.
// If multiple binaries are found, it prompts interactively (or defaults to top priority if forceAuto).
func ResolveAgyBinaryInteractive(customPath string, forceAuto bool) (string, error) {
	if customPath != "" {
		expanded := config.ExpandHome(customPath)
		cleanPath, err := filepath.Abs(expanded)
		if err != nil {
			cleanPath = expanded
		}
		cleanPath = filepath.Clean(cleanPath)
		if fi, err := os.Stat(cleanPath); err != nil || fi.IsDir() {
			return "", fmt.Errorf("specified AGY binary not found at '%s' (resolved: '%s')", customPath, cleanPath)
		}
		return cleanPath, nil
	}

	bins := FindAllAgyBinaries()
	if len(bins) == 0 {
		return "", errors.New("no AGY binary found in ~/.local/bin, ~/.gemini/bin, ~/.antigravity/bin, ~/usr/bin, ~/../usr/bin, or PATH")
	}

	if len(bins) == 1 || forceAuto {
		return bins[0], nil
	}

	// Interactive selection
	fmt.Println("\n \033[1;33m⚡ Multiple AGY binaries detected:\033[0m")
	for idx, b := range bins {
		fmt.Printf("   \033[1m[%d]\033[0m %s\n", idx+1, b)
	}
	fmt.Printf(" \033[1;36m? Select AGY binary to run [1-%d] (default: 1): \033[0m", len(bins))

	reader := bufio.NewReader(os.Stdin)
	input, err := reader.ReadString('\n')
	if err != nil {
		return bins[0], nil
	}
	trimmed := strings.TrimSpace(input)
	if trimmed == "" {
		return bins[0], nil
	}
	choice, err := strconv.Atoi(trimmed)
	if err != nil || choice < 1 || choice > len(bins) {
		fmt.Printf(" \033[33mℹ Invalid selection '%s'. Defaulting to [1] %s\033[0m\n", trimmed, bins[0])
		return bins[0], nil
	}
	return bins[choice-1], nil
}

// ResolveAgyBinary is the default non-interactive fallback.
func ResolveAgyBinary() string {
	bins := FindAllAgyBinaries()
	if len(bins) > 0 {
		return bins[0]
	}
	return "agy"
}

// ResolvePatchTarget determines the target binary for CSRF header patching.
// If the selected binary is < 10 MB (wrapper script), it checks for agy.va39 in the same directory.
func ResolvePatchTarget(binPath string) string {
	if binPath == "" {
		return ""
	}
	fi, err := os.Stat(binPath)
	if err == nil && fi.Size() >= 10*1024*1024 {
		return binPath
	}

	// If < 10 MB, search in same directory for agy.va39
	dir := filepath.Dir(binPath)
	companion := filepath.Join(dir, "agy.va39")
	if fi2, err2 := os.Stat(companion); err2 == nil && !fi2.IsDir() && fi2.Size() >= 10*1024*1024 {
		return companion
	}

	return binPath
}

// NewHubManager initializes a supervisor for AGY Hub.
func NewHubManager(hubPort, workspaceDir, appDataDir, securityToken, agyBinPath string) *HubManager {
	if hubPort == "" {
		hubPort = "1235"
	}
	return &HubManager{
		HubPort:       hubPort,
		WorkspaceDir:  workspaceDir,
		AppDataDir:    appDataDir,
		SecurityToken: securityToken,
		AgyBinPath:    agyBinPath,
		status:        HubStatusIdle,
		recentLogs:    make([]string, 0, 50),
		monitorStop:   make(chan struct{}),
	}
}

// isHubReady probes if port 1235 is active and accepting connections, logging probe details for debugging
func (m *HubManager) isHubReady() bool {
	start := time.Now()
	conn, err := net.DialTimeout("tcp", "127.0.0.1:"+m.HubPort, 400*time.Millisecond)
	elapsed := time.Since(start)

	if err != nil {
		log.Printf("\033[90m[Hub Probe] 127.0.0.1:%s -> Not connected (%v in %v) | Assumed state: offline\033[0m", m.HubPort, err, elapsed)
		return false
	}
	_ = conn.Close()

	log.Printf("\033[32m[Hub Probe] 127.0.0.1:%s -> TCP Connected OK in %v | Assumed state: ONLINE\033[0m", m.HubPort, elapsed)
	return true
}

// FetchCsrfToken attempts to retrieve the active CSRF token directly from the Hub root endpoint.
func (m *HubManager) FetchCsrfToken() string {
	url := fmt.Sprintf("http://127.0.0.1:%s/", m.HubPort)
	client := &http.Client{
		Timeout: 1 * time.Second,
	}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		log.Printf("[FetchCsrfToken] NewRequest error: %v", err)
		return ""
	}
	req.Header.Set("User-Agent", "antiGem-Go-Supervisor")

	resp, err := client.Do(req)
	if err != nil {
		log.Printf("[FetchCsrfToken] GET %s error: %v", url, err)
		return ""
	}
	defer resp.Body.Close()

	if headerToken := strings.TrimSpace(resp.Header.Get("x-codeium-csrf-token")); headerToken != "" {
		log.Printf("[FetchCsrfToken] Found token in header: %s", headerToken)
		return headerToken
	}

	bodyBytes, err := io.ReadAll(io.LimitReader(resp.Body, 128*1024))
	if err != nil {
		log.Printf("[FetchCsrfToken] Read body error: %v", err)
		return ""
	}
	bodyStr := string(bodyBytes)

	if match := csrfJSONPattern.FindStringSubmatch(bodyStr); len(match) > 1 {
		token := strings.TrimSpace(match[1])
		if token != "" {
			log.Printf("[FetchCsrfToken] Extracted token via JSON pattern: %s", token)
			return token
		}
	}

	if match := csrfHTMLPattern.FindStringSubmatch(bodyStr); len(match) > 1 {
		token := strings.TrimSpace(match[1])
		if token != "" {
			log.Printf("[FetchCsrfToken] Extracted token via HTML pattern: %s", token)
			return token
		}
	}

	log.Printf("[FetchCsrfToken] No CSRF token matched in body (%d bytes)", len(bodyStr))
	return ""
}

// GetStatusInfo returns the current status, csrf token, last error, and captured logs.
func (m *HubManager) GetStatusInfo() (string, string, string, []string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	logsCopy := make([]string, len(m.recentLogs))
	copy(logsCopy, m.recentLogs)
	return m.status, m.csrfToken, m.lastError, logsCopy
}

func (m *HubManager) setStatus(status string, errorMsg string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.setStatusLocked(status, errorMsg)
}

func (m *HubManager) setStatusLocked(status string, errorMsg string) {
	oldStatus := m.status
	m.status = status
	m.lastError = errorMsg
	if status == HubStatusStopped || status == HubStatusError || status == HubStatusIdle {
		m.csrfToken = ""
	}
	csrfToken := m.csrfToken
	cb := m.OnStatusChange
	logsCopy := make([]string, len(m.recentLogs))
	copy(logsCopy, m.recentLogs)

	log.Printf("\033[1;36m[Hub State Update]\033[0m Transition: '%s' -> '%s' (csrf: %t, err: '%s')", oldStatus, status, csrfToken != "", errorMsg)

	if cb != nil {
		go cb(status, csrfToken, errorMsg, logsCopy)
	}
}

// StartContinuousMonitor actively checks AGY Hub state in the background and broadcasts updates.
// When online and CSRF token is present, it checks every 10 seconds; when offline, starting, or missing CSRF token, it checks every 1 second.
func (m *HubManager) StartContinuousMonitor() {
	go func() {
		for {
			interval := 1 * time.Second
			m.mu.Lock()
			if m.status == HubStatusOnline && m.csrfToken != "" {
				interval = 10 * time.Second
			}
			m.mu.Unlock()

			select {
			case <-m.monitorStop:
				return
			case <-time.After(interval):
				active := m.isHubReady()
				m.mu.Lock()
				currentStatus := m.status
				if active {
					tokenAcquired := false
					if m.csrfToken == "" {
						m.mu.Unlock()
						tok := m.FetchCsrfToken()
						m.mu.Lock()
						if tok != "" {
							m.csrfToken = tok
							tokenAcquired = true
						}
					}
					if currentStatus != HubStatusOnline || tokenAcquired {
						m.isRunning = true
						m.setStatusLocked(HubStatusOnline, "")
						log.Printf("\033[1;32m[Hub Monitor]\033[0m ✅ AGY Hub detected ONLINE on http://127.0.0.1:%s (CSRF token present: %t)", m.HubPort, m.csrfToken != "")
					}
				} else {
					if currentStatus == HubStatusOnline || currentStatus == HubStatusStarting {
						m.isRunning = false
						m.csrfToken = ""
						m.setStatusLocked(HubStatusStopped, "")
						log.Printf("\033[1;33m[Hub Monitor]\033[0m ⚠️ AGY Hub port %s became unreachable -> marked STOPPED", m.HubPort)
					}
				}
				m.mu.Unlock()
			}
		}
	}()
}

// IsPortActive checks if the hub port is currently listening.
func (m *HubManager) IsPortActive() bool {
	return m.isHubReady()
}

// IsRunning reports whether the hub process is active.
func (m *HubManager) IsRunning() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.isRunning || m.isHubReady()
}

// Start launches agy --hub with interactive feedback, a spinner, and non-blocking update bypass.
func (m *HubManager) Start() error {
	m.mu.Lock()
	// 1. Check if already active and responding to RPC requests
	if m.isHubReady() {
		m.isRunning = true
		m.isExternal = true
		m.mu.Unlock()
		tok := m.FetchCsrfToken()
		m.mu.Lock()
		if tok != "" {
			m.csrfToken = tok
		}
		m.setStatusLocked(HubStatusOnline, "")
		m.mu.Unlock()
		fmt.Printf(" \033[32m[✓]\033[0m AGY Hub is already active and listening on http://127.0.0.1:%s\n", m.HubPort)
		return nil
	}

	m.setStatusLocked(HubStatusStarting, "")

	if m.AgyBinPath == "" {
		err := fmt.Errorf("AGY executable binary not specified or found")
		m.setStatusLocked(HubStatusError, err.Error())
		return err
	}

	// In-place patch AGY binary with 20-byte framed security header (Strict Fail-Closed Security)
	if m.SecurityToken != "" {
		patchTarget := ResolvePatchTarget(m.AgyBinPath)
		if patchTarget == "" {
			err := fmt.Errorf("AGY executable binary not found for security header patching")
			m.setStatusLocked(HubStatusError, err.Error())
			return err
		}
		patchedHeader, elapsed, err := security.PatchAgyHeader(patchTarget, m.SecurityToken)
		if err != nil {
			errMsg := fmt.Sprintf("Security Error: Failed to patch AGY binary (%s): %v", patchTarget, err)
			log.Printf("\033[1;31m[Security Fatal]\033[0m %s", errMsg)
			m.setStatusLocked(HubStatusError, errMsg)
			return errors.New(errMsg)
		}
		log.Printf("\033[1;32m[Security]\033[0m In-place patched AGY binary (%s) in %v -> Active Header: %s", patchTarget, elapsed, patchedHeader)
	}

	args := []string{
		"--hub",
		"--hub-port=" + m.HubPort,
		"--app_data_dir=antigravity-cli",
	}

	wsDir := m.WorkspaceDir
	if wsDir == "" {
		if wd, err := os.Getwd(); err == nil {
			wsDir = wd
		}
	}
	if wsDir != "" {
		if fi, err := os.Stat(wsDir); err == nil && fi.IsDir() {
			args = append(args, "--add-dir="+wsDir)
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	m.cancel = cancel

	shArgs := append([]string{"-c", `exec "$0" "$@"`, m.AgyBinPath}, args...)
	cmd := exec.CommandContext(ctx, "sh", shArgs...)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	// Set required environment variables for agy hub mode dynamically across all platforms
	home, _ := os.UserHomeDir()
	geminiBin := filepath.Join(home, ".gemini", "bin")
	localBin := filepath.Join(home, ".local", "bin")
	usrBin := filepath.Join(home, "..", "usr", "bin")
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		usrBin = filepath.Join(prefix, "bin")
	}

	cmd.Env = append(os.Environ(),
		"HOME="+home,
		"USERPROFILE="+home,
		"PATH="+geminiBin+":"+localBin+":"+usrBin+":/usr/local/bin:/usr/bin:/bin:/system/bin:/system/xbin:"+os.Getenv("PATH"),
		"AGY_ENABLE_HUB=1",
		"ANTIGRAVITY_VSCODE_HOST=1",
		"ANTIGRAVITY_AUTH_SUCCESS_APP=vscode",
		"CI=1",
		"AGY_NO_UPDATE_PROMPT=1",
		"DEBIAN_FRONTEND=noninteractive",
	)

	stdoutPipe, errOut := cmd.StdoutPipe()
	stderrPipe, errErr := cmd.StderrPipe()

	if err := cmd.Start(); err != nil {
		cancel()
		m.setStatusLocked(HubStatusError, err.Error())
		m.mu.Unlock()
		return fmt.Errorf("failed to start agy hub via sh: %w", err)
	}

	m.cmd = cmd
	m.isRunning = true
	m.isExternal = false
	m.processDone = make(chan struct{})
	doneChan := m.processDone
	m.mu.Unlock()

	processExited := make(chan error, 1)
	var streamWg sync.WaitGroup

	// Stream stdout & stderr cleanly with [agy-hub] prefix and extract auth URLs
	if errOut == nil {
		streamWg.Add(1)
		go func() {
			defer streamWg.Done()
			scanner := bufio.NewScanner(stdoutPipe)
			for scanner.Scan() {
				m.handleHubLine(scanner.Text())
			}
		}()
	}
	if errErr == nil {
		streamWg.Add(1)
		go func() {
			defer streamWg.Done()
			scanner := bufio.NewScanner(stderrPipe)
			for scanner.Scan() {
				m.handleHubLine(scanner.Text())
			}
		}()
	}

	// Supervise process exit in background
	go func() {
		err := cmd.Wait()
		streamWg.Wait() // Ensure all remaining output is drained and processed
		m.mu.Lock()
		m.isRunning = false
		if err != nil {
			m.setStatusLocked(HubStatusError, err.Error())
		} else {
			m.setStatusLocked(HubStatusStopped, "")
		}
		m.mu.Unlock()
		processExited <- err
		select {
		case <-doneChan:
		default:
			close(doneChan)
		}
	}()

	// Loading animation while waiting for port and RPC server readiness
	spinner := []string{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}
	spinIdx := 0
	ready := false
	startTime := time.Now()
	timeout := 5 * time.Minute

	for time.Since(startTime) < timeout {
		if !m.IsRunning() {
			return nil
		}

		select {
		case err := <-processExited:
			fmt.Printf("\r\033[K \033[31m[✗]\033[0m AGY Hub process terminated: %v\n", err)
			m.dumpRecentLogs()
			m.setStatus(HubStatusError, fmt.Sprintf("Process exited: %v", err))
			return fmt.Errorf("agy hub process exited unexpectedly: %w", err)
		default:
		}

		if m.isHubReady() {
			ready = true
			break
		}
		fmt.Printf("\r\033[K \033[36m[%s]\033[0m Starting AGY Hub on port %s...", spinner[spinIdx%len(spinner)], m.HubPort)
		spinIdx++
		time.Sleep(1 * time.Second)
	}

	if ready {
		tok := m.FetchCsrfToken()
		m.mu.Lock()
		if tok != "" {
			m.csrfToken = tok
		}
		m.setStatusLocked(HubStatusOnline, "")
		m.mu.Unlock()
		fmt.Printf("\r\033[K \033[32m[✓]\033[0m AGY Hub is online and listening on http://127.0.0.1:%s\n", m.HubPort)
	} else if m.IsRunning() {
		fmt.Printf("\r\033[K \033[33m[!]\033[0m AGY Hub started (PID %d), waiting for initialization on port %s...\n", cmd.Process.Pid, m.HubPort)
	}

	return nil
}

// Stop gracefully shuts down the agy --hub child process, its process group, and the continuous monitor.
func (m *HubManager) Stop() {
	m.mu.Lock()
	cmd := m.cmd
	cancel := m.cancel
	isRunning := m.isRunning
	isExternal := m.isExternal
	doneChan := m.processDone
	m.isRunning = false
	select {
	case <-m.monitorStop:
	default:
		close(m.monitorStop)
	}
	m.mu.Unlock()

	if !isRunning || isExternal {
		m.setStatus(HubStatusStopped, "")
		return
	}

	if cancel != nil {
		cancel()
	}

	if cmd != nil && cmd.Process != nil {
		pid := cmd.Process.Pid
		fmt.Printf("🛑 Stopping AGY Hub process tree (PID %d)...\n", pid)

		// Terminate the entire process group
		pgid, err := syscall.Getpgid(pid)
		if err == nil {
			_ = syscall.Kill(-pgid, syscall.SIGTERM)
		} else {
			_ = cmd.Process.Signal(syscall.SIGTERM)
		}

		stopped := false
		if doneChan != nil {
			select {
			case <-doneChan:
				stopped = true
				fmt.Println("✅ AGY Hub stopped cleanly.")
			case <-time.After(1000 * time.Millisecond):
			}
		}

		if !stopped {
			if pgid > 0 {
				_ = syscall.Kill(-pgid, syscall.SIGKILL)
			}
			_ = cmd.Process.Kill()
			_ = exec.Command("pkill", "-9", "-P", fmt.Sprintf("%d", pid)).Run()
			_ = exec.Command("pkill", "-9", "-f", "agy --hub").Run()
			fmt.Println("⚠️  AGY Hub force terminated.")
		}

		// Auto-unpatch binary back to standard header once process has exited and lock is released
		if m.AgyBinPath != "" {
			patchTarget := ResolvePatchTarget(m.AgyBinPath)
			if patchTarget != "" {
				restored, elapsed, err := security.UnpatchAgyHeader(patchTarget)
				if err == nil {
					log.Printf("\033[1;32m[Security]\033[0m Auto-unpatched AGY binary (%s) back to '%s' in %v", patchTarget, restored, elapsed)
				} else {
					log.Printf("\033[1;33m[Security Warning]\033[0m Could not auto-unpatch AGY binary: %v", err)
				}
			}
		}
	}

	m.setStatus(HubStatusStopped, "")
}

func (m *HubManager) handleHubLine(line string) {
	trimmed := strings.TrimSpace(line)
	if trimmed == "" {
		return
	}

	m.mu.Lock()
	if len(m.recentLogs) >= 50 {
		m.recentLogs = m.recentLogs[1:]
	}
	m.recentLogs = append(m.recentLogs, line)
	m.mu.Unlock()

	fmt.Printf("\r\033[K\033[90m[agy-hub]\033[0m %s\n", line)

	if m.OnLoginURL != nil && strings.Contains(trimmed, "accounts.google.com") {
		matches := loginURLRegex.FindAllString(trimmed, -1)
		for _, u := range matches {
			if strings.HasPrefix(u, "https://accounts.google.com") {
				fmt.Printf(" \033[1;32m[auth-url detected]\033[0m %s\n", u)
				m.OnLoginURL(u)
			}
		}
	}
}

func (m *HubManager) dumpRecentLogs() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.recentLogs) == 0 {
		fmt.Printf(" \033[33mℹ No output was captured from AGY binary.\033[0m Path: %s\n", m.AgyBinPath)
		return
	}
	fmt.Println(" \033[1;31m--- AGY Hub Output / Error Logs ---\033[0m")
	for _, l := range m.recentLogs {
		fmt.Printf("   \033[90m%s\033[0m\n", l)
	}
	fmt.Println(" \033[1;31m------------------------------------\033[0m")
}

