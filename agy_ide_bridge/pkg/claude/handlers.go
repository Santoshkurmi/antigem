package claude

import (
	"context"
	"encoding/json"
	"net/http"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var sessionIDRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

var upgrader = websocket.Upgrader{
	CheckOrigin:     func(r *http.Request) bool { return true },
	ReadBufferSize:  64 << 10,
	WriteBufferSize: 64 << 10,
}

// ServeHTTP routes everything under /api/claude/.
//
//	GET    /api/claude/status                   CLI installed? version, `claude auth status --json`
//	GET    /api/claude/info[?refresh=1]         `initialize` response (models, commands, account)
//	POST   /api/claude/auth/logout              `claude auth logout`
//	GET    /api/claude/sessions                 session list for the sidebar
//	GET    /api/claude/sessions/{id}/history    main-chain transcript entries + live state
//	POST   /api/claude/sessions/{id}/title      {"title": "..."}
//	POST   /api/claude/sessions/{id}/kill       stop the process
//	POST   /api/claude/sessions/{id}/rewind     {"before": "<prompt uuid>"} edit an earlier prompt in place
//	POST   /api/claude/sessions/{id}/fork       {"new_id", "title"} copy the chat under a new id
//	DELETE /api/claude/sessions/{id}            delete transcript (and stop the process)
//	GET    /api/claude/session?id=..&since=..   WebSocket: NDJSON relay to the session's process
//	GET    /api/claude/voice?language=en        WebSocket: dictation relay (16 kHz PCM in, transcripts out)
//	POST   /api/claude/auth/login               start OAuth login {"method":"claudeai"|"console"}
//	GET    /api/claude/auth/login/{id}          login state; POST …/code {"code"}; DELETE cancels
//	GET    /api/claude/usage[?refresh=1]        plan limits & cost (`get_usage`)
//	GET|PUT /api/claude/settings                ~/.claude/settings.json
//	GET|PUT /api/claude/memory?scope=user|project&cwd=  CLAUDE.md
//	GET|DELETE /api/claude/memory/auto?cwd=[&name=]     notes Claude saved by itself for the project
//	GET|POST|DELETE /api/claude/mcp             MCP servers (status / add / remove)
//	GET    /api/claude/plugins                  installed + available plugins, marketplaces
//	POST   /api/claude/plugins/{install|uninstall|enable|disable|update|marketplace-add|marketplace-remove|marketplace-update}
//	GET    /api/claude/cli/job; POST /api/claude/cli/{install|update}
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/claude"), "/")
	switch {
	case path == "/session":
		m.serveSessionWS(w, r)
	case path == "/voice":
		m.serveVoiceWS(w, r)
	case path == "/auth/login" || strings.HasPrefix(path, "/auth/login/"):
		m.handleLogin(w, r, strings.TrimPrefix(strings.TrimPrefix(path, "/auth/login"), "/"))
	case path == "/usage" && r.Method == http.MethodGet:
		m.handleUsage(w, r)
	case path == "/settings":
		m.handleSettings(w, r)
	case path == "/memory":
		m.handleMemory(w, r)
	case path == "/memory/auto":
		m.handleAutoMemory(w, r)
	case path == "/mcp":
		m.handleMcp(w, r)
	case path == "/plugins" || strings.HasPrefix(path, "/plugins/"):
		m.handlePlugins(w, r, strings.TrimPrefix(strings.TrimPrefix(path, "/plugins"), "/"))
	case path == "/cli/job" || strings.HasPrefix(path, "/cli/"):
		m.handleCLIJob(w, r, strings.TrimPrefix(path, "/cli/"))
	case path == "/attachments" || strings.HasPrefix(path, "/attachments/"):
		m.handleAttachments(w, r, strings.TrimPrefix(strings.TrimPrefix(path, "/attachments"), "/"))
	case path == "/sandbox" && r.Method == http.MethodGet:
		m.handleSandbox(w, r)
	case path == "/status" && r.Method == http.MethodGet:
		m.handleStatus(w, r)
	case path == "/info" && r.Method == http.MethodGet:
		m.handleInfo(w, r)
	case path == "/auth/logout" && r.Method == http.MethodPost:
		m.handleLogout(w, r)
	case path == "/sessions" && r.Method == http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]interface{}{"sessions": m.ListSessions()})
	case strings.HasPrefix(path, "/sessions/"):
		m.handleSessionREST(w, r, strings.TrimPrefix(path, "/sessions/"))
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown claude endpoint"})
	}
}

func (m *Manager) handleSessionREST(w http.ResponseWriter, r *http.Request, rest string) {
	parts := strings.Split(rest, "/")
	id := parts[0]
	if !sessionIDRe.MatchString(id) {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid session id"})
		return
	}
	action := ""
	if len(parts) > 1 {
		action = parts[1]
	}
	switch {
	case action == "history" && r.Method == http.MethodGet:
		st := SessionState{}
		var maxBytes int64
		var cut *string
		if s := m.Existing(id); s != nil {
			st = s.State()
			if st.Live {
				maxBytes = st.HistoryOffset
				if maxBytes == 0 {
					maxBytes = -1 // new session: everything comes from the live buffer
				}
				cut = s.HistoryCut()
			}
		}
		if cut == nil {
			if at, ok := m.rewindPoint(id); ok {
				cut = &at
			}
		}
		var entries []json.RawMessage
		var err error
		if maxBytes < 0 {
			entries = []json.RawMessage{}
		} else {
			entries, err = m.History(id, maxBytes, cut)
		}
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "entries": entries, "state": st})
	case action == "title" && r.Method == http.MethodPost:
		var body struct {
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || strings.TrimSpace(body.Title) == "" {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "title required"})
			return
		}
		if err := m.RenameSession(id, strings.TrimSpace(body.Title)); err != nil {
			writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case action == "rewind" && r.Method == http.MethodPost:
		var body struct {
			Before string `json:"before"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || !sessionIDRe.MatchString(body.Before) {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "before (prompt uuid) required"})
			return
		}
		if err := m.RewindSession(id, body.Before); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case action == "fork" && r.Method == http.MethodPost:
		var body struct {
			NewID string `json:"new_id"`
			Title string `json:"title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || !sessionIDRe.MatchString(body.NewID) {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "new_id required"})
			return
		}
		if err := m.ForkSession(id, body.NewID, strings.TrimSpace(body.Title)); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case action == "kill" && r.Method == http.MethodPost:
		if s := m.Existing(id); s != nil {
			s.Kill()
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case action == "" && r.Method == http.MethodDelete:
		if err := m.DeleteSession(id); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown session endpoint"})
	}
}

// --- WebSocket ---------------------------------------------------------------------------------

type subscriber struct {
	conn *websocket.Conn
	ch   chan []byte
	done chan struct{}
	once sync.Once
}

func (s *subscriber) send(b []byte) {
	select {
	case <-s.done:
	case s.ch <- b:
	default:
		// client too slow: drop it, it reconnects with ?since=
		s.close()
	}
}

func (s *subscriber) close() {
	s.once.Do(func() {
		close(s.done)
		_ = s.conn.Close()
	})
}

func (s *subscriber) writeLoop() {
	for {
		select {
		case <-s.done:
			return
		case b := <-s.ch:
			if err := s.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				s.close()
				return
			}
		}
	}
}

func (m *Manager) serveSessionWS(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	id := q.Get("id")
	if !sessionIDRe.MatchString(id) {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid session id"})
		return
	}
	since, err := strconv.ParseInt(q.Get("since"), 10, 64)
	if err != nil {
		since = -1
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	// The HTTP server's read/write timeouts survive the hijack; a chat socket lives much longer.
	_ = conn.UnderlyingConn().SetDeadline(time.Time{})
	conn.SetReadLimit(256 << 20)

	sess := m.Session(id)
	sess.Configure(SpawnOptions{
		Cwd:             q.Get("cwd"),
		Model:           q.Get("model"),
		PermissionMode:  q.Get("permission_mode"),
		Effort:          q.Get("effort"),
		ForkFrom:        q.Get("fork_from"),
		ResumeSessionAt: q.Get("resume_session_at"),
		Thinking:        q.Get("thinking"),
	})

	sub := &subscriber{conn: conn, ch: make(chan []byte, maxBufferedLines+1024), done: make(chan struct{})}
	sess.mu.Lock()
	sub.send(stateMessage(sess.stateLocked(), "attached", nil, ""))
	if since >= 0 {
		for i, line := range sess.lines {
			seq := sess.baseSeq + int64(i)
			if seq >= since {
				sub.send(lineMessage(seq, line))
			}
		}
	}
	sess.subs[sub] = struct{}{}
	sess.lastActivity = time.Now()
	sess.mu.Unlock()

	go sub.writeLoop()
	defer func() {
		sess.mu.Lock()
		delete(sess.subs, sub)
		sess.lastActivity = time.Now()
		sess.mu.Unlock()
		sub.close()
	}()

	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var head struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(msg, &head) != nil {
			sub.send(errorMessage("invalid JSON frame"))
			continue
		}
		switch head.Type {
		case "bridge_config":
			var o SpawnOptions
			_ = json.Unmarshal(msg, &o)
			sess.Configure(o)
		case "bridge_kill":
			sess.Kill()
		default:
			if err := sess.Write(msg); err != nil {
				sub.send(errorMessage(err.Error()))
			}
		}
	}
}

// --- CLI status / info / logout ----------------------------------------------------------------

func (m *Manager) runCLI(timeout time.Duration, args ...string) ([]byte, error) {
	bin, err := m.ResolveBinary()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Dir = m.HomeDir
	return cmd.Output()
}

func (m *Manager) handleStatus(w http.ResponseWriter, r *http.Request) {
	bin, err := m.ResolveBinary()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "installed": false, "error": err.Error()})
		return
	}
	resp := map[string]interface{}{"success": true, "installed": true, "bin_path": bin}
	// both start a node process; on a phone that takes seconds, so run them side by side
	version := make(chan string, 1)
	go func() {
		out, err := m.runCLI(15*time.Second, "--version")
		if err != nil {
			version <- ""
			return
		}
		version <- strings.TrimSpace(string(out))
	}()
	out, err := m.runCLI(20*time.Second, "auth", "status", "--json")
	if v := <-version; v != "" {
		resp["version"] = v
	}
	var auth map[string]interface{}
	if len(out) > 0 && json.Unmarshal(out, &auth) == nil {
		resp["auth"] = auth
	} else if err != nil {
		resp["auth_error"] = err.Error()
	}
	writeJSON(w, http.StatusOK, resp)
}

func (m *Manager) handleLogout(w http.ResponseWriter, r *http.Request) {
	if _, err := m.runCLI(30*time.Second, "auth", "logout"); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	m.invalidateCaches()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (m *Manager) handleInfo(w http.ResponseWriter, r *http.Request) {
	info, err := m.Info(r.URL.Query().Get("refresh") == "1")
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	// the mode new chats start in (read fresh: settings.json may have changed since the cached initialize)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "info": info, "default_mode": m.defaultPermissionMode()})
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
