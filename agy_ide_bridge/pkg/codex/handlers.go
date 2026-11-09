package codex

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
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

// ServeHTTP routes /api/codex/.
//
//	GET  /api/codex/status                  installed? version, binary, app-server running, recent log lines
//	POST /api/codex/start                   start app-server (no-op when running)
//	POST /api/codex/restart                 restart app-server (running turns end)
//	POST /api/codex/rpc {"method","params","timeout_ms"?}   one app-server request → {"success","result"|"error"}
//	GET  /api/codex/requests                server→client requests waiting for an answer (approvals, questions)
//	POST /api/codex/requests/{key}/reply {"result"} | {"error":{"code","message"}}
//	GET  /api/codex/ws?since=N              WebSocket: every notification and request, sequence-numbered
//	GET  /api/codex/turns-meta?thread=ID    per turn: model, effort, token usage, duration (from the thread's rollout)
//	GET  /api/codex/cli/job; POST /api/codex/cli/{install|update}   install or update Codex
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/codex")
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
	case path == "/turns-meta" && r.Method == http.MethodGet:
		m.handleTurnsMeta(w, r)
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
	case path == "/cli/job" || strings.HasPrefix(path, "/cli/"):
		m.handleInstall(w, r, strings.TrimPrefix(path, "/cli/"))
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown codex endpoint"})
	}
}

func (m *Manager) handleStatus(w http.ResponseWriter, r *http.Request) {
	resp := map[string]interface{}{"success": true, "termux_package": m.termuxNative()}
	bin, err := m.ResolveBinary()
	if err != nil {
		resp["installed"] = false
		resp["error"] = err.Error()
		writeJSON(w, http.StatusOK, resp)
		return
	}
	resp["installed"] = true
	resp["binary"] = bin
	resp["version"] = m.Version(bin)
	m.mu.Lock()
	running := m.proc != nil && m.proc.alive() && m.starting == nil
	resp["starting"] = m.starting != nil
	if m.startErr != nil && !running {
		resp["error"] = m.startErr.Error()
	}
	if running {
		resp["uptime_seconds"] = int(time.Since(m.proc.started).Seconds())
		if len(m.initInfo) > 0 {
			resp["server"] = m.initInfo
		}
	}
	m.mu.Unlock()
	resp["running"] = running
	m.evMu.Lock()
	resp["pending_requests"] = len(m.pending)
	resp["subscribed_threads"] = len(m.threads)
	m.evMu.Unlock()
	resp["logs"] = m.recentLogs(20)
	writeJSON(w, http.StatusOK, resp)
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
	timeout := 2 * time.Minute
	if body.TimeoutMs > 0 {
		timeout = time.Duration(body.TimeoutMs) * time.Millisecond
		if timeout > 10*time.Minute {
			timeout = 10 * time.Minute
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
			// the server could not be reached at all (not installed, did not start, exited)
			resp["codex_unavailable"] = true
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

// --- event stream ------------------------------------------------------------------------------

// subscriber is one WebSocket following the event stream.
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

// appendLocked adds one app-server message to the buffer and sends it to every subscriber (caller holds evMu).
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

// BridgeState is sent to the app when it attaches and whenever app-server starts or exits.
type BridgeState struct {
	Running     bool              `json:"running"`
	Generation  int64             `json:"generation"`
	BufferStart int64             `json:"buffer_start"`
	NextSeq     int64             `json:"next_seq"`
	Pending     []*PendingRequest `json:"pending"`
}

func (m *Manager) stateFrame(event, errText string) []byte {
	m.mu.Lock()
	running := m.proc != nil && m.proc.alive() && m.starting == nil
	gen := m.gen
	m.mu.Unlock()
	if event == "exited" {
		running = false
	}
	m.evMu.Lock()
	st := BridgeState{Running: running, Generation: gen, NextSeq: m.nextSeq, Pending: m.pendingLocked()}
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
	}{"bridge_state", event, st, errText})
	return b
}

func (m *Manager) broadcastState(event, errText string) {
	frame := m.stateFrame(event, errText)
	m.evMu.Lock()
	for sub := range m.subs {
		sub.send(frame)
	}
	m.evMu.Unlock()
}

// serveWS streams the event buffer: first a bridge_state "attached" frame, then (with ?since=N) every buffered
// event from N on, then live events. If N is older than buffer_start the app has missed events and should reload
// what it shows (thread/turns/list).
func (m *Manager) serveWS(w http.ResponseWriter, r *http.Request) {
	since, err := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	if err != nil {
		since = -1
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	// The HTTP server's read/write timeouts survive the hijack; this socket lives much longer.
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
	// the app only listens; reading keeps pongs and the close handshake working
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
