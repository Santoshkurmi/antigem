package hub

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"gemini-server/pkg/config"
)

var loginURLRegex = regexp.MustCompile(`https://accounts\.google\.com/[^\s"'<>]+`)

const (
	HubStatusIdle     = "idle"
	HubStatusStarting = "starting"
	HubStatusOnline   = "online"
	HubStatusError    = "error"
	HubStatusStopped  = "stopped"
)

// HubManager supervises the background `agy --hub` process on port 8090.
type HubManager struct {
	HubPort        string
	WorkspaceDir   string
	AppDataDir     string
	AgyBinPath     string
	OnLoginURL     func(url string)
	OnStatusChange func(status string, errorMsg string, logs []string)

	cmd         *exec.Cmd
	stdinPipe   io.WriteCloser
	cancel      context.CancelFunc
	status      string
	lastError   string
	isRunning   bool
	isExternal  bool
	processDone chan struct{}
	recentLogs  []string
	mu          sync.Mutex
}

// NewHubManager initializes a supervisor for AGY Hub.
func NewHubManager(hubPort, workspaceDir, appDataDir string) *HubManager {
	if hubPort == "" {
		hubPort = "8090"
	}
	return &HubManager{
		HubPort:      hubPort,
		WorkspaceDir: workspaceDir,
		AppDataDir:   appDataDir,
		AgyBinPath:   resolveAgyBinary(),
		status:       HubStatusIdle,
		recentLogs:   make([]string, 0, 50),
	}
}

// resolveAgyBinary finds the agy executable in standard locations.
func resolveAgyBinary() string {
	if p := config.SafeLookPath("agy"); p != "" {
		return p
	}
	return "agy"
}

// isHubReady probes if port 8090 is active and accepting connections, logging probe details for debugging
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

// GetStatusInfo returns the current status, last error, and captured logs.
func (m *HubManager) GetStatusInfo() (string, string, []string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	logsCopy := make([]string, len(m.recentLogs))
	copy(logsCopy, m.recentLogs)
	return m.status, m.lastError, logsCopy
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
	cb := m.OnStatusChange
	logsCopy := make([]string, len(m.recentLogs))
	copy(logsCopy, m.recentLogs)

	log.Printf("\033[1;36m[Hub State Update]\033[0m Transition: '%s' -> '%s' (err: '%s')", oldStatus, status, errorMsg)

	if cb != nil {
		go cb(status, errorMsg, logsCopy)
	}
}

// StartContinuousMonitor actively checks AGY Hub state every 2 seconds in the background and broadcasts updates.
func (m *HubManager) StartContinuousMonitor() {
	go func() {
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			active := m.isHubReady()
			m.mu.Lock()
			currentStatus := m.status
			if active {
				if currentStatus != HubStatusOnline {
					m.isRunning = true
					m.setStatusLocked(HubStatusOnline, "")
					log.Printf("\033[1;32m[Hub Monitor]\033[0m ✅ AGY Hub detected ONLINE on http://127.0.0.1:%s", m.HubPort)
				}
			} else {
				if currentStatus == HubStatusOnline {
					m.isRunning = false
					m.setStatusLocked(HubStatusStopped, "")
					log.Printf("\033[1;33m[Hub Monitor]\033[0m ⚠️ AGY Hub port %s became unreachable -> marked STOPPED", m.HubPort)
				}
			}
			m.mu.Unlock()
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
		m.setStatusLocked(HubStatusOnline, "")
		m.mu.Unlock()
		fmt.Printf(" \033[32m[✓]\033[0m AGY Hub is already active and listening on http://127.0.0.1:%s\n", m.HubPort)
		return nil
	}

	m.setStatusLocked(HubStatusStarting, "")

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

	shArgs := append([]string{"-c", `exec agy "$@"`, "agy"}, args...)
	cmd := exec.CommandContext(ctx, "sh", shArgs...)

	// Set required environment variables for agy hub mode
	home, _ := os.UserHomeDir()
	termuxBin := "/data/data/com.termux/files/usr/bin"
	geminiBin := "/data/data/com.termux/files/home/.gemini/bin"
	if home != "" {
		geminiBin = home + "/.gemini/bin"
	}

	cmd.Env = append(os.Environ(),
		"HOME="+home,
		"USERPROFILE="+home,
		"PATH="+geminiBin+":"+termuxBin+":/usr/local/bin:/usr/bin:/bin:/system/bin:/system/xbin:"+os.Getenv("PATH"),
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
		m.setStatus(HubStatusError, err.Error())
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
			m.setStatus(HubStatusError, err.Error())
		} else {
			m.setStatus(HubStatusStopped, "")
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
	timeout := 30 * time.Second

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
		time.Sleep(150 * time.Millisecond)
	}

	if ready {
		m.setStatus(HubStatusOnline, "")
		fmt.Printf("\r\033[K \033[32m[✓]\033[0m AGY Hub is online and listening on http://127.0.0.1:%s\n", m.HubPort)
	} else if m.IsRunning() {
		fmt.Printf("\r\033[K \033[33m[!]\033[0m AGY Hub started (PID %d), waiting for initialization on port %s...\n", cmd.Process.Pid, m.HubPort)
	}

	return nil
}

// Stop gracefully shuts down the agy --hub child process and child tree.
func (m *HubManager) Stop() {
	m.mu.Lock()
	cmd := m.cmd
	cancel := m.cancel
	isRunning := m.isRunning
	isExternal := m.isExternal
	doneChan := m.processDone
	m.isRunning = false
	m.mu.Unlock()

	if !isRunning || isExternal {
		return
	}

	if cancel != nil {
		cancel()
	}

	if cmd != nil && cmd.Process != nil {
		pid := cmd.Process.Pid
		fmt.Printf("🛑 Stopping AGY Hub (PID %d)...\n", pid)
		_ = cmd.Process.Signal(syscall.SIGTERM)
		_ = cmd.Process.Signal(os.Interrupt)

		stopped := false
		if doneChan != nil {
			select {
			case <-doneChan:
				stopped = true
				fmt.Println("✅ AGY Hub stopped cleanly.")
			case <-time.After(1500 * time.Millisecond):
			}
		}

		if !stopped {
			_ = cmd.Process.Kill()
			_ = exec.Command("pkill", "-9", "-P", fmt.Sprintf("%d", pid)).Run()
			_ = exec.Command("pkill", "-9", "-f", "agy --hub").Run()
			fmt.Println("⚠️  AGY Hub force terminated.")
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

