package session

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/models"
)

// WsSender defines the contract for sending events to client WebSocket.
type WsSender interface {
	SendJSON(v interface{}) error
}

// ActiveTurn tracks a currently running turn, sequence numbers, and its listeners.
type ActiveTurn struct {
	Listeners      []WsSender
	Prompt         string
	StartTime      time.Time
	LastActivity   time.Time
	Seq            int64
	Status         string // "THINKING", "EXECUTING_TOOL", "GENERATING_TEXT"
	ThoughtBuffer  strings.Builder
	ContentBuffer  strings.Builder
	ActiveTools    []models.ToolCall
	BufferedEvents []map[string]interface{}
	IsDone         bool
	Mu             sync.Mutex
}

// SessionInstance represents a single warm agy CLI process.
type SessionInstance struct {
	Model          string
	ConversationID string
	WorkspaceDir   string
	IsReady        bool
	Cmd            *exec.Cmd
	Stdin          io.WriteCloser
	Stdout         io.ReadCloser
	CurrentTurn    *ActiveTurn
	CreatedAt      time.Time
	LastUsed       time.Time
	OnResultHook   func()
	Mu             sync.Mutex
}

// Spawn initializes the agy CLI child process with stream-json format.
func (s *SessionInstance) Spawn(manager *SessionPoolManager) {
	args := []string{
		"--input-format", "stream-json",
		"--output-format", "stream-json",
		"--dangerously-skip-permissions",
		"--add-dir", s.WorkspaceDir,
	}

	if s.Model != "" && s.Model != "default" {
		args = append(args, "--model", s.Model)
	}
	if s.ConversationID != "" && s.ConversationID != "new" {
		args = append(args, "--conversation", s.ConversationID)
	}

	convLabel := s.ConversationID
	if convLabel == "" {
		convLabel = "new"
	}
	fmt.Printf("[SessionPool] Spawning warm instance for [%s | %s | %s]...\n", s.Model, convLabel, s.WorkspaceDir)

	cmd := exec.Command("agy", args...)
	cmd.Dir = s.WorkspaceDir
	cmd.Env = append(os.Environ(), "NO_COLOR=1")

	stdin, err := cmd.StdinPipe()
	if err != nil {
		fmt.Printf("[SessionPool ERROR] StdinPipe: %v\n", err)
		return
	}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		fmt.Printf("[SessionPool ERROR] StdoutPipe: %v\n", err)
		return
	}

	stderr, _ := cmd.StderrPipe()

	if err := cmd.Start(); err != nil {
		fmt.Printf("[SessionPool ERROR] Spawn failed: %v\n", err)
		return
	}

	s.Cmd = cmd
	s.Stdin = stdin
	s.Stdout = stdout

	// Stderr logger
	if stderr != nil {
		go func() {
			sc := bufio.NewScanner(stderr)
			for sc.Scan() {
				errText := strings.TrimSpace(sc.Text())
				if errText != "" && !strings.Contains(errText, "Fetching available models") {
					fmt.Printf("[SessionPool STDERR] %s\n", errText)
				}
			}
		}()
	}

	// Stdout JSON scanner
	go s.listenStdout(manager)
}

func (s *SessionInstance) listenStdout(manager *SessionPoolManager) {
	reader := bufio.NewReaderSize(s.Stdout, 10*1024*1024)

	for {
		lineBytes, err := reader.ReadBytes('\n')
		if len(lineBytes) > 0 {
			line := strings.TrimSpace(string(lineBytes))
			if line != "" {
				var parsed map[string]interface{}
				if jsonErr := json.Unmarshal([]byte(line), &parsed); jsonErr == nil {
					s.handleParsedEvent(parsed, manager)
				}
			}
		}

		if err != nil {
			if err != io.EOF {
				fmt.Printf("[SessionPool] Stdout read error: %v\n", err)
			}
			break
		}
	}

	s.Mu.Lock()
	turn := s.CurrentTurn
	s.IsReady = false
	s.Cmd = nil
	s.Mu.Unlock()

	// Ensure any pending turn is closed with a done event so the client never hangs!
	if turn != nil {
		turn.Mu.Lock()
		if !turn.IsDone {
			turn.IsDone = true
			for _, listener := range turn.Listeners {
				_ = listener.SendJSON(map[string]interface{}{
					"type":     "done",
					"exitCode": 0,
					"message":  "Process completed",
				})
			}
		}
		turn.Mu.Unlock()
	}
}

func (s *SessionInstance) handleParsedEvent(parsed map[string]interface{}, manager *SessionPoolManager) {
	evType, _ := parsed["event"].(string)

	if evType == "init" {
		s.Mu.Lock()
		s.IsReady = true
		if cid, ok := parsed["conversation_id"].(string); ok && cid != "" {
			s.ConversationID = cid
		}
		fmt.Printf("[SessionPool] Instance ready! Conversation ID: %s (%s)\n", s.ConversationID, s.Model)
		s.Mu.Unlock()
	}

	s.Mu.Lock()
	turn := s.CurrentTurn
	s.Mu.Unlock()

	if evType == "init" && turn != nil {
		turn.Mu.Lock()
		for _, listener := range turn.Listeners {
			_ = listener.SendJSON(map[string]interface{}{
				"type":           "instance_status",
				"status":         "ready",
				"conversationId": s.ConversationID,
			})
		}
		turn.Mu.Unlock()
	}

	if turn != nil {
		turn.Mu.Lock()
		turn.LastActivity = time.Now()
		turn.Seq++
		turn.BufferedEvents = append(turn.BufferedEvents, parsed)

		if evType == "step_update" {
			if stepUpdate, ok := parsed["step_update"].(map[string]interface{}); ok {
				stepType, _ := stepUpdate["step_type"].(string)
				textDelta, _ := stepUpdate["text_delta"].(string)
				thoughtDelta, _ := stepUpdate["thought_delta"].(string)

				if thoughtDelta != "" || stepType == "thought" {
					turn.Status = "THINKING"
					if thoughtDelta != "" {
						turn.ThoughtBuffer.WriteString(thoughtDelta)
					}
				} else if stepType == "agent_response" && textDelta != "" {
					turn.Status = "GENERATING_TEXT"
					turn.ContentBuffer.WriteString(textDelta)
					fmt.Print(textDelta)
				} else if stepType == "tool" || stepUpdate["tool_info"] != nil || stepUpdate["tool_name"] != nil {
					turn.Status = "EXECUTING_TOOL"
					toolName := "tool"
					if tn, ok := stepUpdate["tool_name"].(string); ok && tn != "" {
						toolName = tn
					}
					var params interface{} = map[string]interface{}{}
					if ti, ok := stepUpdate["tool_info"].(map[string]interface{}); ok {
						if n, ok2 := ti["name"].(string); ok2 && n != "" {
							toolName = n
						}
						if p, ok2 := ti["parameters"]; ok2 {
							params = p
						}
					}
					paramsJSON, _ := json.Marshal(params)
					status := "ACTIVE"
					if st, ok := stepUpdate["state"].(string); ok && st == "DONE" {
						dur := 0.0
						if ds, ok2 := stepUpdate["duration_seconds"].(float64); ok2 {
							dur = ds
						}
						status = fmt.Sprintf("DONE (%.2fs)", dur)
					}
					fmt.Printf("\n[CLI ⚙ TOOL] %s [%s] %s\n", toolName, status, string(paramsJSON))
				}
			}
		}

		if evType == "result" {
			dur := time.Since(turn.StartTime).Seconds()
			res, _ := parsed["result"].(map[string]interface{})
			status := "DONE"
			if res != nil {
				if st, ok := res["status"].(string); ok && st != "" {
					status = st
				}
			}
			tokens := 0
			if res != nil {
				if u, ok := res["usage"].(map[string]interface{}); ok {
					if tt, ok2 := u["total_tokens"].(float64); ok2 {
						tokens = int(tt)
					}
				}
			}
			fmt.Printf("\n[CLI ⚙ RESULT] Status: %s | Tokens: %d | Duration: %.2fs\n", status, tokens, dur)
			fmt.Printf("=======================================================\n\n")
		}

		// Broadcast to all active turn listeners with seq tag
		for _, listener := range turn.Listeners {
			_ = listener.SendJSON(map[string]interface{}{
				"type": "agy_event",
				"seq":  turn.Seq,
				"data": parsed,
			})
		}

		if evType == "result" {
			turn.IsDone = true
			for _, listener := range turn.Listeners {
				_ = listener.SendJSON(map[string]interface{}{
					"type":     "done",
					"seq":      turn.Seq,
					"exitCode": 0,
				})
			}
			s.Mu.Lock()
			s.CurrentTurn = nil
			s.Mu.Unlock()

			if s.OnResultHook != nil {
				go s.OnResultHook()
			}
		}
		turn.Mu.Unlock()
	}
}

// AttachClient connects a WebSocket listener to an ongoing turn using StreamSnapshot.
func (s *SessionInstance) AttachClient(ws WsSender) bool {
	s.Mu.Lock()
	turn := s.CurrentTurn
	s.Mu.Unlock()

	if turn == nil || turn.IsDone {
		_ = ws.SendJSON(map[string]interface{}{
			"type":           "session_attached",
			"conversationId": s.ConversationID,
			"isRunning":      false,
		})
		_ = ws.SendJSON(map[string]interface{}{
			"type":     "done",
			"exitCode": 0,
		})
		return false
	}

	turn.Mu.Lock()
	defer turn.Mu.Unlock()

	turn.Listeners = append(turn.Listeners, ws)

	// Send comprehensive in-flight snapshot with sequence number
	_ = ws.SendJSON(models.StreamSnapshot{
		Type:           "stream_snapshot",
		ConversationID: s.ConversationID,
		IsRunning:      true,
		Status:         turn.Status,
		Seq:            turn.Seq,
		Prompt:         turn.Prompt,
		Thought:        turn.ThoughtBuffer.String(),
		Content:        turn.ContentBuffer.String(),
		ActiveTools:    turn.ActiveTools,
	})

	return true
}

// SendTurn dispatches prompt turn to stdin asynchronously.
func (s *SessionInstance) SendTurn(prompt string, ws WsSender) {
	go func() {
		s.Mu.Lock()
		isReady := s.IsReady
		cID := s.ConversationID
		s.Mu.Unlock()

		if !isReady && ws != nil {
			_ = ws.SendJSON(map[string]interface{}{
				"type":           "instance_status",
				"status":         "creating",
				"message":        "Creating new instance for this chat...",
				"conversationId": cID,
			})
		}

		s.Mu.Lock()
		s.LastUsed = time.Now()
		turn := &ActiveTurn{
			Listeners:      []WsSender{ws},
			Prompt:         prompt,
			StartTime:      time.Now(),
			LastActivity:   time.Now(),
			Seq:            0,
			Status:         "THINKING",
			BufferedEvents: make([]map[string]interface{}, 0),
			IsDone:         false,
		}
		s.CurrentTurn = turn
		s.Mu.Unlock()

		convLabel := s.ConversationID
		if convLabel == "" {
			convLabel = "new"
		}
		fmt.Printf("\n================== [NEW PROMPT TURN] ==================\n")
		fmt.Printf("[WS ➜ REQ] Prompt: \"%s\" [Model: %s] [Conv: %s] [Dir: %s]\n", prompt, s.Model, convLabel, s.WorkspaceDir)
		fmt.Printf("[SessionPool ➜ STDIN] Sending turn to warm instance...\n")

		payload, _ := json.Marshal(map[string]interface{}{
			"event": "user",
			"message": map[string]string{
				"content": prompt,
			},
		})
		payload = append(payload, '\n')
		s.Mu.Lock()
		if s.Stdin != nil {
			_, err := s.Stdin.Write(payload)
			if err != nil {
				fmt.Printf("[SessionPool ERROR] Stdin.Write failed: %v\n", err)
			}
		}
		s.Mu.Unlock()
	}()
}

// Abort cancels the active turn.
func (s *SessionInstance) Abort() {
	s.Mu.Lock()
	turn := s.CurrentTurn
	s.CurrentTurn = nil
	s.Mu.Unlock()

	if turn != nil {
		turn.Mu.Lock()
		for _, ws := range turn.Listeners {
			_ = ws.SendJSON(map[string]interface{}{
				"type":     "done",
				"exitCode": 130,
				"message":  "Interrupted by user",
			})
		}
		turn.Mu.Unlock()
	}

	if s.Stdin != nil {
		_, _ = s.Stdin.Write([]byte("\x1b\x1b"))
	}
	if s.Cmd != nil && s.Cmd.Process != nil {
		_ = s.Cmd.Process.Signal(os.Interrupt)
	}
}

// Destroy forcefully kills the instance.
func (s *SessionInstance) Destroy() {
	s.Abort()
	s.Mu.Lock()
	defer s.Mu.Unlock()
	if s.Cmd != nil && s.Cmd.Process != nil {
		_ = s.Cmd.Process.Kill()
		s.Cmd = nil
	}
	s.IsReady = false
}

// SessionPoolManager manages instances across multiple conversations.
type SessionPoolManager struct {
	sessions     map[string]*SessionInstance
	maxSessions  int
	defaultWsDir string
	OnResultHook func()
	mu           sync.RWMutex
}

// NewSessionPoolManager creates a new pool manager.
func NewSessionPoolManager(defaultWsDir string, maxSessions int, onResultHook func()) *SessionPoolManager {
	return &SessionPoolManager{
		sessions:     make(map[string]*SessionInstance),
		maxSessions:  maxSessions,
		defaultWsDir: defaultWsDir,
		OnResultHook: onResultHook,
	}
}

func (m *SessionPoolManager) getKey(model, convID, wsDir string) string {
	mID := model
	if mID == "" {
		mID = "default"
	}
	cID := convID
	if cID == "" {
		cID = "new"
	}
	wDir := wsDir
	if wDir == "" {
		wDir = "default"
	}
	return fmt.Sprintf("%s__%s__%s", mID, cID, wDir)
}

// IsRunning checks if there is an active turn executing for a conversation.
func (m *SessionPoolManager) IsRunning(convID string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, inst := range m.sessions {
		if inst.ConversationID == convID {
			inst.Mu.Lock()
			running := inst.CurrentTurn != nil && !inst.CurrentTurn.IsDone
			inst.Mu.Unlock()
			if running {
				return true
			}
		}
	}
	return false
}

// GetOrCreate retrieves an existing warm instance or spawns a new one.
func (m *SessionPoolManager) GetOrCreate(model, convID, wsDir string) *SessionInstance {
	m.mu.Lock()
	defer m.mu.Unlock()

	targetModel := model
	if targetModel == "" {
		targetModel = "gemini-3.7-flash-high"
	}

	targetWs := wsDir
	if targetWs == "" || !dirExists(targetWs) {
		targetWs = m.defaultWsDir
	}

	// 1. If convID specified, ensure single instance exists for this conversation
	if convID != "" && convID != "new" {
		for key, existing := range m.sessions {
			if existing.ConversationID == convID {
				if existing.Model == targetModel && existing.WorkspaceDir == targetWs && existing.Cmd != nil {
					existing.LastUsed = time.Now()
					return existing
				}
				fmt.Printf("[SessionPool] Model or project changed for conv %s (%s -> %s). Terminating old instance...\n", convID, existing.Model, targetModel)
				existing.Destroy()
				delete(m.sessions, key)
			}
		}
	}

	key := m.getKey(targetModel, convID, targetWs)
	if existing, ok := m.sessions[key]; ok && existing.Cmd != nil {
		existing.LastUsed = time.Now()
		return existing
	}

	m.evictOldSessionsIfNeeded()

	newInstance := &SessionInstance{
		Model:          targetModel,
		ConversationID: convID,
		WorkspaceDir:   targetWs,
		CreatedAt:      time.Now(),
		LastUsed:       time.Now(),
		OnResultHook:   m.OnResultHook,
	}
	m.sessions[key] = newInstance
	newInstance.Spawn(m)

	return newInstance
}

// Prewarm warms up an instance ahead of time.
func (m *SessionPoolManager) Prewarm(model, convID, wsDir string) *SessionInstance {
	return m.GetOrCreate(model, convID, wsDir)
}

// FindRunningSession locates a currently active session for a conversation.
func (m *SessionPoolManager) FindRunningSession(convID string) *SessionInstance {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, inst := range m.sessions {
		if inst.ConversationID == convID {
			inst.Mu.Lock()
			running := inst.CurrentTurn != nil && !inst.CurrentTurn.IsDone
			inst.Mu.Unlock()
			if running {
				return inst
			}
		}
	}
	return nil
}

// AbortSession interrupts the active turn for a conversation.
func (m *SessionPoolManager) AbortSession(convID string) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, inst := range m.sessions {
		if inst.ConversationID == convID || convID == "" {
			inst.Abort()
		}
	}
}

// TerminateInstance stops and removes a session.
func (m *SessionPoolManager) TerminateInstance(convID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	for key, inst := range m.sessions {
		if inst.ConversationID == convID || convID == "" {
			inst.Destroy()
			delete(m.sessions, key)
			fmt.Printf("[SessionPool] Terminated and removed instance for conv: %s\n", convID)
		}
	}
}

// ListInstances returns telemetry summaries for all active worker instances in RAM.
func (m *SessionPoolManager) ListInstances() []models.ActiveInstance {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var list []models.ActiveInstance
	now := time.Now()

	for _, inst := range m.sessions {
		inst.Mu.Lock()
		pid := 0
		if inst.Cmd != nil && inst.Cmd.Process != nil {
			pid = inst.Cmd.Process.Pid
		}
		isBusy := inst.CurrentTurn != nil && !inst.CurrentTurn.IsDone
		list = append(list, models.ActiveInstance{
			ConversationID:     inst.ConversationID,
			Model:              inst.Model,
			WorkspaceDir:       inst.WorkspaceDir,
			PID:                pid,
			UptimeSeconds:      int64(now.Sub(inst.CreatedAt).Seconds()),
			LastUsedAgoSeconds: int64(now.Sub(inst.LastUsed).Seconds()),
			IsReady:            inst.IsReady,
			IsBusy:             isBusy,
		})
		inst.Mu.Unlock()
	}

	return list
}

func (m *SessionPoolManager) evictOldSessionsIfNeeded() {
	if len(m.sessions) < m.maxSessions {
		return
	}

	var oldestKey string
	var oldestTime time.Time = time.Now()

	for key, inst := range m.sessions {
		inst.Mu.Lock()
		isBusy := inst.CurrentTurn != nil && !inst.CurrentTurn.IsDone
		inst.Mu.Unlock()

		if !isBusy && (oldestKey == "" || inst.LastUsed.Before(oldestTime)) {
			oldestKey = key
			oldestTime = inst.LastUsed
		}
	}

	if oldestKey != "" {
		if inst, ok := m.sessions[oldestKey]; ok {
			fmt.Printf("[SessionPool] Evicting oldest idle instance: %s\n", oldestKey)
			inst.Destroy()
			delete(m.sessions, oldestKey)
		}
	}
}

func dirExists(path string) bool {
	if path == "" {
		return false
	}
	stat, err := os.Stat(path)
	return err == nil && stat.IsDir()
}
