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

// ActiveTurn tracks a currently running turn and its listeners.
type ActiveTurn struct {
	Listeners      []WsSender
	Prompt         string
	StartTime      time.Time
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
	scanner := bufio.NewScanner(s.Stdout)
	buf := make([]byte, 1024*1024)
	scanner.Buffer(buf, 10*1024*1024)

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		var parsed map[string]interface{}
		if err := json.Unmarshal([]byte(line), &parsed); err != nil {
			continue
		}

		s.handleParsedEvent(parsed, manager)
	}

	s.Mu.Lock()
	s.IsReady = false
	s.Cmd = nil
	s.Mu.Unlock()
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
		for _, listener := range turn.Listeners {
			_ = listener.SendJSON(map[string]interface{}{
				"type":           "instance_status",
				"status":         "ready",
				"conversationId": s.ConversationID,
			})
		}
	}

	if turn != nil {
		turn.Mu.Lock()
		turn.BufferedEvents = append(turn.BufferedEvents, parsed)

		if evType == "step_update" {
			if stepUpdate, ok := parsed["step_update"].(map[string]interface{}); ok {
				stepType, _ := stepUpdate["step_type"].(string)
				textDelta, _ := stepUpdate["text_delta"].(string)

				if stepType == "agent_response" && textDelta != "" {
					fmt.Print(textDelta)
				} else if stepType == "tool" || stepUpdate["tool_info"] != nil || stepUpdate["tool_name"] != nil {
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

		// Broadcast to all active turn listeners
		for _, listener := range turn.Listeners {
			_ = listener.SendJSON(map[string]interface{}{
				"type": "agy_event",
				"data": parsed,
			})
		}

		if evType == "result" {
			turn.IsDone = true
			for _, listener := range turn.Listeners {
				_ = listener.SendJSON(map[string]interface{}{
					"type":     "done",
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

// AttachClient connects a WebSocket listener to an ongoing turn.
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
	turn.Listeners = append(turn.Listeners, ws)
	_ = ws.SendJSON(map[string]interface{}{
		"type":           "session_attached",
		"conversationId": s.ConversationID,
		"isRunning":      true,
		"prompt":         turn.Prompt,
	})
	for _, ev := range turn.BufferedEvents {
		_ = ws.SendJSON(map[string]interface{}{
			"type": "agy_event",
			"data": ev,
		})
	}
	turn.Mu.Unlock()
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

	// Prune oldest idle session if over capacity
	if len(m.sessions) >= m.maxSessions {
		var oldestKey string
		var oldestTime time.Time
		for k, s := range m.sessions {
			if s.CurrentTurn == nil && (oldestKey == "" || s.LastUsed.Before(oldestTime)) {
				oldestKey = k
				oldestTime = s.LastUsed
			}
		}
		if oldestKey != "" {
			fmt.Printf("[SessionPool] Pruning idle instance: %s\n", oldestKey)
			m.sessions[oldestKey].Destroy()
			delete(m.sessions, oldestKey)
		}
	}

	inst := &SessionInstance{
		Model:          targetModel,
		ConversationID: convID,
		WorkspaceDir:   targetWs,
		IsReady:        false,
		CreatedAt:      time.Now(),
		LastUsed:       time.Now(),
		OnResultHook:   m.OnResultHook,
	}

	m.sessions[key] = inst
	inst.Spawn(m)
	return inst
}

// Prewarm warms an instance in background.
func (m *SessionPoolManager) Prewarm(model, convID, wsDir string) {
	m.GetOrCreate(model, convID, wsDir)
}

// RegisterAssignedConversation binds a dynamically assigned conversation ID to the pool map.
func (m *SessionPoolManager) RegisterAssignedConversation(model, convID, wsDir string, inst *SessionInstance) {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := m.getKey(model, convID, wsDir)
	m.sessions[key] = inst
}

// FindRunningSession locates any session actively executing a prompt turn.
func (m *SessionPoolManager) FindRunningSession(convID string) *SessionInstance {
	if convID == "" {
		return nil
	}
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, s := range m.sessions {
		if s.ConversationID == convID && s.CurrentTurn != nil && !s.CurrentTurn.IsDone {
			return s
		}
	}
	return nil
}

// AbortSession aborts a conversation.
func (m *SessionPoolManager) AbortSession(convID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if convID == "" {
		for _, s := range m.sessions {
			s.Destroy()
		}
		m.sessions = make(map[string]*SessionInstance)
		return
	}

	for key, s := range m.sessions {
		if s.ConversationID == convID || strings.Contains(key, fmt.Sprintf("__%s__", convID)) {
			fmt.Printf("[SessionPool] Aborting generation for conversation: %s\n", convID)
			model := s.Model
			wsDir := s.WorkspaceDir
			s.Destroy()
			delete(m.sessions, key)
			go m.Prewarm(model, convID, wsDir)
		}
	}
}

// TerminateInstance terminates a conversation instance.
func (m *SessionPoolManager) TerminateInstance(convID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	found := false
	for key, s := range m.sessions {
		if s.ConversationID == convID || strings.Contains(key, fmt.Sprintf("__%s__", convID)) {
			fmt.Printf("[SessionPool] Explicitly terminating instance for conversation: %s\n", convID)
			s.Destroy()
			delete(m.sessions, key)
			found = true
		}
	}
	return found
}

// GetActiveInstances returns list of active instances.
func (m *SessionPoolManager) GetActiveInstances() []models.ActiveInstance {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var list []models.ActiveInstance
	for _, s := range m.sessions {
		if s.Cmd != nil && s.Cmd.Process != nil {
			pid := s.Cmd.Process.Pid
			uptime := int64(time.Since(s.CreatedAt).Seconds())
			lastUsed := int64(time.Since(s.LastUsed).Seconds())
			cID := s.ConversationID
			if cID == "" {
				cID = "new"
			}
			list = append(list, models.ActiveInstance{
				ConversationID:     cID,
				Model:              s.Model,
				WorkspaceDir:       s.WorkspaceDir,
				PID:                pid,
				UptimeSeconds:      uptime,
				LastUsedAgoSeconds: lastUsed,
				IsReady:            s.IsReady,
				IsBusy:             s.CurrentTurn != nil,
			})
		}
	}
	return list
}

func dirExists(p string) bool {
	info, err := os.Stat(p)
	return err == nil && info.IsDir()
}
