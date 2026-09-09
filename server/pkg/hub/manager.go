package hub

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// HubManager supervises the background `agy --hub` process on port 8090.
type HubManager struct {
	HubPort      string
	WorkspaceDir string
	AppDataDir   string
	AgyBinPath   string

	cmd         *exec.Cmd
	stdinPipe   io.WriteCloser
	cancel      context.CancelFunc
	isRunning   bool
	isExternal  bool
	processDone chan struct{}
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
	}
}

// resolveAgyBinary finds the agy executable in standard locations.
func resolveAgyBinary() string {
	home, _ := os.UserHomeDir()
	candidates := []string{
		filepath.Join(home, ".gemini", "bin", "agy"),
		"/data/data/com.termux/files/home/.gemini/bin/agy",
		"/usr/bin/agy",
		"/usr/local/bin/agy",
		"/data/data/com.termux/files/usr/bin/agy",
	}

	for _, c := range candidates {
		if fi, err := os.Stat(c); err == nil && !fi.IsDir() {
			return c
		}
	}

	if p, err := exec.LookPath("agy"); err == nil {
		return p
	}

	return "agy"
}

// IsPortActive checks if the hub port is currently listening.
func (m *HubManager) IsPortActive() bool {
	conn, err := net.DialTimeout("tcp", "127.0.0.1:"+m.HubPort, 300*time.Millisecond)
	if err == nil {
		_ = conn.Close()
		return true
	}
	return false
}

// IsRunning reports whether the hub process is active.
func (m *HubManager) IsRunning() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.isRunning || m.IsPortActive()
}

// Start launches agy --hub with interactive feedback, a spinner, and non-blocking update bypass.
func (m *HubManager) Start() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	// 1. Check if already active
	if m.IsPortActive() {
		m.isRunning = true
		m.isExternal = true
		fmt.Printf(" \033[32m[✓]\033[0m AGY Hub is already active and listening on http://127.0.0.1:%s\n", m.HubPort)
		return nil
	}

	args := []string{
		"--hub",
		"--hub-port=" + m.HubPort,
		"--app_data_dir=antigravity",
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

	cmd := exec.CommandContext(ctx, m.AgyBinPath, args...)
	// Set required environment variables for agy hub mode
	home, _ := os.UserHomeDir()
	cmd.Env = append(os.Environ(),
		"HOME="+home,
		"USERPROFILE="+home,
		"AGY_ENABLE_HUB=1",
		"ANTIGRAVITY_VSCODE_HOST=1",
		"ANTIGRAVITY_AUTH_SUCCESS_APP=vscode",
		"CI=1",
		"AGY_NO_UPDATE_PROMPT=1",
		"DEBIAN_FRONTEND=noninteractive",
	)

	// Automatic 'n' feed to stdin: answers any potential agy update prompt with 'no' immediately
	cmd.Stdin = strings.NewReader("n\n")

	stdoutPipe, errOut := cmd.StdoutPipe()
	stderrPipe, errErr := cmd.StderrPipe()

	if err := cmd.Start(); err != nil {
		cancel()
		return fmt.Errorf("failed to start agy hub (%s): %w", m.AgyBinPath, err)
	}

	m.cmd = cmd
	m.isRunning = true
	m.isExternal = false
	m.processDone = make(chan struct{})
	processExited := make(chan error, 1)

	// Stream stdout & stderr cleanly with [agy-hub] prefix
	if errOut == nil {
		go func() {
			scanner := bufio.NewScanner(stdoutPipe)
			for scanner.Scan() {
				line := scanner.Text()
				if strings.TrimSpace(line) != "" {
					fmt.Printf("\033[90m[agy-hub]\033[0m %s\n", line)
				}
			}
		}()
	}
	if errErr == nil {
		go func() {
			scanner := bufio.NewScanner(stderrPipe)
			for scanner.Scan() {
				line := scanner.Text()
				if strings.TrimSpace(line) != "" {
					fmt.Printf("\033[90m[agy-hub]\033[0m %s\n", line)
				}
			}
		}()
	}

	// Supervise process exit in background
	go func() {
		err := cmd.Wait()
		m.mu.Lock()
		m.isRunning = false
		m.mu.Unlock()
		processExited <- err
		close(m.processDone)
	}()

	// Loading animation while waiting for port to open
	spinner := []string{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}
	spinIdx := 0
	ready := false
	startTime := time.Now()
	timeout := 25 * time.Second

	for time.Since(startTime) < timeout {
		select {
		case err := <-processExited:
			fmt.Printf("\r \033[31m[✗]\033[0m AGY Hub process terminated: %v\n", err)
			return fmt.Errorf("agy hub process exited unexpectedly: %w", err)
		default:
		}

		if m.IsPortActive() {
			ready = true
			break
		}
		fmt.Printf("\r \033[36m[%s]\033[0m Starting AGY Hub on port %s...", spinner[spinIdx%len(spinner)], m.HubPort)
		spinIdx++
		time.Sleep(120 * time.Millisecond)
	}

	if ready {
		fmt.Printf("\r \033[32m[✓]\033[0m AGY Hub is online and listening on http://127.0.0.1:%s   \n", m.HubPort)
	} else {
		fmt.Printf("\r \033[33m[!]\033[0m AGY Hub started (PID %d), waiting for initialization on port %s...\n", cmd.Process.Pid, m.HubPort)
	}

	return nil
}

// Stop gracefully shuts down the agy --hub child process.
func (m *HubManager) Stop() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.isRunning || m.isExternal {
		return
	}

	if m.stdinPipe != nil {
		_ = m.stdinPipe.Close()
	}

	if m.cancel != nil {
		m.cancel()
	}

	if m.cmd != nil && m.cmd.Process != nil {
		fmt.Printf("🛑 Stopping AGY Hub (PID %d)...\n", m.cmd.Process.Pid)
		_ = m.cmd.Process.Signal(os.Interrupt)

		select {
		case <-m.processDone:
			fmt.Println("✅ AGY Hub stopped cleanly.")
		case <-time.After(3 * time.Second):
			_ = m.cmd.Process.Kill()
			fmt.Println("⚠️  AGY Hub terminated.")
		}
	}

	m.isRunning = false
}

