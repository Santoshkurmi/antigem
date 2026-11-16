package dsh

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin:     func(r *http.Request) bool { return true },
	ReadBufferSize:  64 << 10,
	WriteBufferSize: 64 << 10,
}

// ServeHTTP routes /api/dsh/.
//
//	GET  /api/dsh/status                     installed? versions, running, recent log lines, permission presets
//	POST /api/dsh/start | /restart | /stop   control the dsh process (running turns end on restart/stop)
//	POST /api/dsh/rpc {"method","params","timeout_ms"?}   one SDK request → {"success","result"|"error"}
//	GET  /api/dsh/requests                   approvals / questions waiting for an answer
//	POST /api/dsh/requests/{key}/reply {"result"} | {"error":{"code","message"}}
//	GET  /api/dsh/ws?since=N                 WebSocket: every notification and request, sequence-numbered, plus
//	                                         live-only "bridge_live" frames (typing deltas, never replayed)
//	GET  /api/dsh/cli/job; POST /api/dsh/cli/{install|update|remove}
//	GET  /api/dsh/config; POST /api/dsh/config {provider?, model?, reasoning_effort?, max_tokens?, permission?, keys?, mcp?}
//	GET  /api/dsh/models                     models of every provider with a key (needs dsh running)
//	POST /api/dsh/cancel {"session_id"}      stop the session's running turn
//	POST /api/dsh/model {"session_id","provider","model","reasoning_effort"?}   switch one chat's model
//	GET  /api/dsh/sessions                   saved chats (works without dsh running)
//	GET  /api/dsh/transcript?session=ID      one chat's events
//	POST /api/dsh/sessions/{id}/rename {"title"}; /archive {"archived"}; /delete
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/dsh")
	switch {
	case path == "/ws":
		m.serveWS(w, r)
	case path == "/rpc" && r.Method == http.MethodPost:
		m.handleRPC(w, r)
	case path == "/requests" && r.Method == http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "requests": m.PendingRequests()})
	case strings.HasPrefix(path, "/requests/") && strings.HasSuffix(path, "/reply") && r.Method == http.MethodPost:
		m.handleReply(w, r, strings.TrimSuffix(strings.TrimPrefix(path, "/requests/"), "/reply"))
	case path == "/status" && r.Method == http.MethodGet:
		m.handleStatus(w, r)
	case path == "/start" && r.Method == http.MethodPost:
		if _, err := m.Ensure(r.Context()); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case path == "/restart" && r.Method == http.MethodPost:
		m.Stop()
		if _, err := m.Ensure(r.Context()); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case path == "/stop" && r.Method == http.MethodPost:
		m.Stop()
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case path == "/cli/job" || strings.HasPrefix(path, "/cli/"):
		m.handleInstall(w, r, strings.TrimPrefix(path, "/cli/"))
	case path == "/config":
		m.handleConfig(w, r)
	case path == "/models" && r.Method == http.MethodGet:
		m.handleModels(w, r)
	case path == "/cancel" && r.Method == http.MethodPost:
		m.handleCancel(w, r)
	case path == "/model" && r.Method == http.MethodPost:
		m.handleSetModel(w, r)
	case path == "/sessions" && r.Method == http.MethodGet:
		list, err := m.ListSessions(r.Context())
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "sessions": list})
	case path == "/transcript" && r.Method == http.MethodGet:
		m.writeTranscript(r.Context(), w, r.URL.Query().Get("session"))
	case strings.HasPrefix(path, "/sessions/") && r.Method == http.MethodPost:
		m.handleSessionAction(w, r, strings.TrimPrefix(path, "/sessions/"))
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown dsh endpoint"})
	}
}

func (m *Manager) handleStatus(w http.ResponseWriter, r *http.Request) {
	cfg := m.cfg.load()
	resp := map[string]interface{}{
		"success":      true,
		"installed":    m.Installed(),
		"platform":     runtime.GOOS + "/" + runtime.GOARCH,
		"presets":      m.presets(),
		"provider":     cfg.Provider,
		"model":        cfg.Model,
		"permission":   cfg.Permission,
		"has_key":      m.hasKey(cfg),
		"custom_bin":   m.BinPath,
		"node_version": m.l.readState().NodeVersion,
		"version":      m.l.installedVersion(),
	}
	if loader := m.glibcLoader(); loader != "" {
		_, err := os.Stat(loader)
		resp["glibc_ok"] = err == nil
	}
	m.mu.Lock()
	running := m.proc != nil && m.proc.alive() && m.starting == nil
	resp["starting"] = m.starting != nil
	if m.startErr != nil && !running {
		resp["error"] = m.startErr.Error()
	}
	if running {
		resp["uptime_seconds"] = int(time.Since(m.proc.started).Seconds())
		resp["route"] = m.proc.route
		if len(m.initInfo) > 0 {
			resp["server"] = m.initInfo
		}
	}
	m.mu.Unlock()
	resp["running"] = running
	m.evMu.Lock()
	resp["pending_requests"] = len(m.pending)
	m.evMu.Unlock()
	resp["statuses"] = m.sessionStatuses()
	resp["logs"] = m.recentLogs(30)
	m.jobMu.Lock()
	if m.job != nil {
		resp["job"] = m.job.snapshot()
	}
	m.jobMu.Unlock()
	writeJSON(w, http.StatusOK, resp)
}

// hasKey: the configured provider has its API key (from the app or the environment).
func (m *Manager) hasKey(cfg Config) bool {
	p := providerByID(cfg.Provider)
	if p == nil {
		return true // a provider the app does not know: assume it is set up outside the app
	}
	return cfg.Keys[p.Env] != "" || os.Getenv(p.Env) != ""
}

func (m *Manager) handleRPC(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Method    string          `json:"method"`
		Params    json.RawMessage `json:"params"`
		TimeoutMs int             `json:"timeout_ms"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<20)).Decode(&body); err != nil || body.Method == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "expected {\"method\", \"params\"}"})
		return
	}
	if bridgeOwnedMethods[body.Method] {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": body.Method + " is handled by the bridge"})
		return
	}
	timeout := 4 * time.Minute // includes a cold dsh start
	if body.TimeoutMs > 0 {
		timeout = time.Duration(body.TimeoutMs) * time.Millisecond
		if timeout > 30*time.Minute {
			timeout = 30 * time.Minute
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	defer cancel()
	res, err := m.Call(ctx, body.Method, body.Params)
	if err != nil {
		resp := map[string]interface{}{"success": false, "error": err.Error()}
		var rpcErr *RPCError
		if errors.As(err, &rpcErr) {
			resp["error"] = rpcErr.Message
			resp["code"] = rpcErr.Code
			if len(rpcErr.Data) > 0 {
				resp["data"] = rpcErr.Data
			}
		} else {
			resp["dsh_unavailable"] = true
		}
		writeJSON(w, http.StatusOK, resp)
		return
	}
	if len(res) == 0 {
		res = json.RawMessage("null")
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "result": res})
}

func (m *Manager) handleReply(w http.ResponseWriter, r *http.Request, key string) {
	var body struct {
		Result json.RawMessage `json:"result"`
		Error  *RPCError       `json:"error"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<20)).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "expected {\"result\"} or {\"error\"}"})
		return
	}
	if err := m.ReplyRequest(key, body.Result, body.Error); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (m *Manager) handleConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "config": publicConfig(m.cfg.load())})
		return
	}
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "use GET or POST"})
		return
	}
	var body struct {
		Provider        *string           `json:"provider"`
		Model           *string           `json:"model"`
		ReasoningEffort *string           `json:"reasoning_effort"`
		MaxTokens       *int              `json:"max_tokens"`
		Permission      *string           `json:"permission"`
		Keys            map[string]string `json:"keys"`
		Mcp             *[]McpServer      `json:"mcp"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid JSON"})
		return
	}
	cfg := m.cfg.load()
	old := cfg
	oldRoutes := jsonText(piAiRoutes(cfg)) // before Keys (a shared map) changes
	restart := false
	if body.Provider != nil {
		cfg.Provider = strings.TrimSpace(*body.Provider)
	}
	if body.Model != nil {
		cfg.Model = strings.TrimSpace(*body.Model)
	}
	if body.ReasoningEffort != nil {
		cfg.ReasoningEffort = strings.TrimSpace(*body.ReasoningEffort)
	}
	if body.MaxTokens != nil {
		cfg.MaxTokens = *body.MaxTokens
	}
	if body.Permission != nil {
		cfg.Permission = *body.Permission
	}
	envChanges := map[string]interface{}{}
	for k, v := range body.Keys {
		k = strings.TrimSpace(k)
		if k == "" || strings.ContainsAny(k, "= \t\n") {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid key name " + k})
			return
		}
		v = strings.TrimSpace(v)
		if v == "" {
			delete(cfg.Keys, k)
			envChanges[k] = nil
		} else {
			cfg.Keys[k] = v
			envChanges[k] = v
		}
	}
	if body.Mcp != nil {
		if err := validateMcp(*body.Mcp); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		cfg.Mcp = *body.Mcp
		restart = true
	}
	cfg.normalize()
	if err := m.cfg.save(cfg); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	// initialize (default route, output cap) and permission defaults are process-wide
	if cfg.Provider != old.Provider || cfg.Model != old.Model || cfg.ReasoningEffort != old.ReasoningEffort ||
		cfg.MaxTokens != old.MaxTokens || cfg.Permission != old.Permission || jsonText(piAiRoutes(cfg)) != oldRoutes {
		restart = true
	}
	running := m.Running()
	if len(envChanges) > 0 && running {
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		_, err := m.control(ctx, "setEnv", map[string]interface{}{"vars": envChanges})
		cancel()
		if err != nil {
			restart = true
		}
	}
	_ = m.writeRuntimeFiles()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "config": publicConfig(cfg), "restart_required": restart && running})
}

func (m *Manager) handleModels(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
	defer cancel()
	if r.URL.Query().Get("start") == "1" {
		if _, err := m.Ensure(ctx); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
	}
	res, err := m.control(ctx, "models", nil)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error(), "running": m.Running()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "models": res})
}

func (m *Manager) handleCancel(w http.ResponseWriter, r *http.Request) {
	var body struct {
		SessionID string `json:"session_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body); err != nil || body.SessionID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "expected {\"session_id\"}"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	res, err := m.control(ctx, "cancel", map[string]string{"sessionId": body.SessionID})
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "result": res})
}

func (m *Manager) handleSetModel(w http.ResponseWriter, r *http.Request) {
	var body struct {
		SessionID       string `json:"session_id"`
		Provider        string `json:"provider"`
		Model           string `json:"model"`
		ReasoningEffort string `json:"reasoning_effort"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body); err != nil || body.SessionID == "" || body.Provider == "" || body.Model == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "expected {\"session_id\",\"provider\",\"model\"}"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Minute)
	defer cancel()
	p, err := m.Ensure(ctx)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	if err := m.ensureLoaded(ctx, p, body.SessionID, false); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	// a new id has no agent until dsh creates it: reading its directory creates the session without a turn
	if m.sessionDir(body.SessionID) == "" {
		if _, err := m.call(ctx, p, "session/working-directory/get", map[string]string{"sessionId": body.SessionID}); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
	}
	params := map[string]string{"sessionId": body.SessionID, "provider": body.Provider, "model": body.Model}
	if body.ReasoningEffort != "" {
		params["reasoningEffort"] = body.ReasoningEffort
	}
	if _, err := m.control(ctx, "setModel", params); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (m *Manager) handleSessionAction(w http.ResponseWriter, r *http.Request, rest string) {
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) != 2 || parts[0] == "" {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown session action"})
		return
	}
	id, action := parts[0], parts[1]
	var body struct {
		Title    string `json:"title"`
		Archived *bool  `json:"archived"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body)
	var err error
	switch action {
	case "rename":
		title := strings.TrimSpace(body.Title)
		err = m.meta.update(func(cm *chatMeta) {
			if title == "" {
				delete(cm.Titles, id)
			} else {
				cm.Titles[id] = title
			}
		})
	case "archive":
		archived := body.Archived == nil || *body.Archived
		err = m.meta.update(func(cm *chatMeta) {
			if archived {
				cm.Archived[id] = true
			} else {
				delete(cm.Archived, id)
			}
		})
	case "delete":
		err = m.DeleteSession(r.Context(), id)
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown session action " + action})
		return
	}
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

// --- install / update / remove jobs ------------------------------------------------------------

func (m *Manager) handleInstall(w http.ResponseWriter, r *http.Request, action string) {
	m.jobMu.Lock()
	job := m.job
	m.jobMu.Unlock()
	if r.Method == http.MethodGet || action == "job" {
		var snap interface{}
		if job != nil {
			snap = job.snapshot()
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": snap})
		return
	}
	if action != "install" && action != "update" && action != "remove" {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown cli action"})
		return
	}
	if m.BinPath != "" && action != "remove" {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "the bridge uses the dsh given with --dsh-bin; update that installation yourself"})
		return
	}
	if job != nil {
		job.mu.Lock()
		running := job.Running
		job.mu.Unlock()
		if running {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "another install/update is running"})
			return
		}
	}
	nj := &installJob{Kind: action, Running: true, Started: time.Now()}
	m.jobMu.Lock()
	m.job = nj
	m.jobMu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Minute)
		defer cancel()
		var err error
		if action == "remove" {
			err = m.remove(nj)
		} else {
			err = m.install(ctx, nj, action == "update")
		}
		code := 0
		if err != nil {
			nj.logf("Error: %v", err)
			code = 1
		}
		nj.mu.Lock()
		nj.Running = false
		nj.ExitCode = &code
		nj.mu.Unlock()
		m.broadcastState("installed", "")
	}()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "job": nj.snapshot()})
}

// --- event stream ------------------------------------------------------------------------------

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
	ping := time.NewTicker(30 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-s.done:
			return
		case b := <-s.ch:
			if err := s.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				s.close()
				return
			}
		case <-ping.C:
			if err := s.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)); err != nil {
				s.close()
				return
			}
		}
	}
}

// appendLocked adds one message to the buffer and sends it to every subscriber (caller holds evMu).
func (m *Manager) appendLocked(raw []byte, key string) int64 {
	seq := m.nextSeq
	m.nextSeq++
	frame, _ := json.Marshal(struct {
		Type string          `json:"type"`
		Seq  int64           `json:"seq"`
		Key  string          `json:"key,omitempty"`
		Data json.RawMessage `json:"data"`
	}{"bridge_line", seq, key, raw})
	m.events = append(m.events, event{seq: seq, frame: frame})
	m.evBytes += len(frame)
	drop := 0
	for len(m.events)-drop > maxBufferedEvents || (m.evBytes > maxBufferedBytes && len(m.events)-drop > 1) {
		m.evBytes -= len(m.events[drop].frame)
		drop++
	}
	if drop > 0 {
		m.events = append([]event(nil), m.events[drop:]...)
	}
	for sub := range m.subs {
		sub.send(frame)
	}
	return seq
}

// BridgeState is sent when the app attaches and whenever dsh starts, exits or is (re)installed.
type BridgeState struct {
	Running     bool              `json:"running"`
	Starting    bool              `json:"starting"`
	Generation  int64             `json:"generation"`
	BufferStart int64             `json:"buffer_start"`
	NextSeq     int64             `json:"next_seq"`
	Pending     []*PendingRequest `json:"pending"`
	Statuses    map[string]string `json:"statuses"`
}

func (m *Manager) stateFrame(ev, errText string) []byte {
	m.mu.Lock()
	running := m.proc != nil && m.proc.alive() && m.starting == nil
	starting := m.starting != nil
	gen := m.gen
	m.mu.Unlock()
	if ev == "exited" {
		running = false
	}
	m.evMu.Lock()
	st := BridgeState{Running: running, Starting: starting, Generation: gen, NextSeq: m.nextSeq, Pending: m.pendingLocked(), Statuses: map[string]string{}}
	for k, v := range m.statuses {
		st.Statuses[k] = v
	}
	st.BufferStart = m.nextSeq
	if len(m.events) > 0 {
		st.BufferStart = m.events[0].seq
	}
	m.evMu.Unlock()
	b, _ := json.Marshal(struct {
		Type  string      `json:"type"`
		Event string      `json:"event"`
		State BridgeState `json:"state"`
		Error string      `json:"error,omitempty"`
	}{"bridge_state", ev, st, errText})
	return b
}

func (m *Manager) broadcastState(ev, errText string) {
	frame := m.stateFrame(ev, errText)
	m.evMu.Lock()
	for sub := range m.subs {
		sub.send(frame)
	}
	m.evMu.Unlock()
}

// serveWS streams the event buffer: a bridge_state "attached" frame, then (with ?since=N) every buffered event from
// N on, then live events. If N is older than buffer_start the app has missed events and reloads the chat.
func (m *Manager) serveWS(w http.ResponseWriter, r *http.Request) {
	since, err := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	if err != nil {
		since = -1
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	_ = conn.UnderlyingConn().SetDeadline(time.Time{})
	conn.SetReadLimit(1 << 20)

	sub := &subscriber{conn: conn, ch: make(chan []byte, maxBufferedEvents+1024), done: make(chan struct{})}
	attached := m.stateFrame("attached", "")
	m.evMu.Lock()
	sub.send(attached)
	if since >= 0 {
		for _, ev := range m.events {
			if ev.seq >= since {
				sub.send(ev.frame)
			}
		}
	}
	m.subs[sub] = struct{}{}
	m.evMu.Unlock()

	go sub.writeLoop()
	defer func() {
		m.evMu.Lock()
		delete(m.subs, sub)
		m.evMu.Unlock()
		sub.close()
	}()
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			return
		}
	}
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
