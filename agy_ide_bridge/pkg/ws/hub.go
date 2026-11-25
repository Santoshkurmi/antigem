package ws

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"

	"github.com/gorilla/websocket"

	"gemini-server/pkg/config"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: checkOrigin,
}

// checkOrigin lets in the app (OkHttp sends no Origin) and pages served by the bridge itself (same host), never a
// website or a WebView page from somewhere else.
func checkOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	return err == nil && strings.EqualFold(u.Host, r.Host)
}

type ClientConn struct {
	ws *websocket.Conn
	mu sync.Mutex
	// wantsCSRF: the client asked for the hub's CSRF value (?hub_csrf=1); everyone else gets hub_status without it
	wantsCSRF bool
}

func (c *ClientConn) SendJSON(v interface{}) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ws.WriteJSON(v)
}

type HubStatusProvider interface {
	GetStatusInfo() (status string, csrfToken string, errorMsg string, logs []string)
}

type Hub struct {
	Cfg        *config.Config
	StatusProv HubStatusProvider
	HubPort    string
	clients    map[*ClientConn]bool
	mu         sync.RWMutex
}

func NewHub(cfg *config.Config) *Hub {
	return &Hub{
		Cfg:     cfg,
		HubPort: "1235",
		clients: make(map[*ClientConn]bool),
	}
}

func (h *Hub) Broadcast(msg interface{}) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for client := range h.clients {
		_ = client.SendJSON(msg)
	}
}

// hubStatusMessage is the hub_status message; the CSRF value only for a client that asked for it.
func hubStatusMessage(status, port, csrfToken, errorMsg string, logs []string, withCSRF bool) map[string]interface{} {
	msg := map[string]interface{}{
		"type":   "hub_status",
		"status": status,
		"port":   port,
		"error":  errorMsg,
		"logs":   logs,
	}
	if withCSRF {
		msg["csrf_token"] = csrfToken
	}
	return msg
}

func (h *Hub) BroadcastHubStatus(status string, port string, csrfToken string, errorMsg string, logs []string) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for client := range h.clients {
		_ = client.SendJSON(hubStatusMessage(status, port, csrfToken, errorMsg, logs, client.wantsCSRF))
	}
}

// Close disconnects all active WebSocket clients cleanly.
func (h *Hub) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for client := range h.clients {
		_ = client.ws.Close()
	}
	h.clients = make(map[*ClientConn]bool)
}

type clientMessage struct {
	Type           string `json:"type"` // "ping", "attach_session", "abort"
	ConversationID string `json:"conversationId"`
	Model          string `json:"model"`
	Prompt         string `json:"prompt"`
	WorkspaceDir   string `json:"workspaceDir"`
}

func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request) {
	rawConn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		fmt.Printf("[WS] Upgrade error: %v\n", err)
		return
	}
	client := &ClientConn{ws: rawConn, wantsCSRF: r.URL.Query().Get("hub_csrf") == "1"}

	h.mu.Lock()
	h.clients[client] = true
	prov := h.StatusProv
	port := h.HubPort
	h.mu.Unlock()

	// Send initial hub status immediately on connection
	if prov != nil {
		st, csrf, errMsg, logs := prov.GetStatusInfo()
		_ = client.SendJSON(hubStatusMessage(st, port, csrf, errMsg, logs, client.wantsCSRF))
	}

	defer func() {
		h.mu.Lock()
		delete(h.clients, client)
		h.mu.Unlock()
		_ = rawConn.Close()
	}()

	for {
		_, msgBytes, err := rawConn.ReadMessage()
		if err != nil {
			break
		}

		var msg clientMessage
		if err := json.Unmarshal(msgBytes, &msg); err != nil {
			continue
		}

		switch msg.Type {
		case "ping":
			_ = client.SendJSON(map[string]string{"type": "pong"})

		case "attach_session":
			_ = client.SendJSON(map[string]interface{}{
				"type":           "session_attached",
				"conversationId": msg.ConversationID,
				"isRunning":      false,
			})
			_ = client.SendJSON(map[string]interface{}{
				"type":     "done",
				"exitCode": 0,
			})

		case "abort":
			_ = client.SendJSON(map[string]interface{}{
				"type":           "aborted",
				"conversationId": msg.ConversationID,
			})

		case "send_prompt":
			_ = client.SendJSON(map[string]interface{}{
				"type":  "error",
				"error": "Chat is managed directly via AGY Hub on port 1235",
			})
		}
	}
}
