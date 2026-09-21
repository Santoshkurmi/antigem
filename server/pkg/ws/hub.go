package ws

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"

	"gemini-server/pkg/config"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow all origins (Android app & webview)
	},
}

type ClientConn struct {
	ws *websocket.Conn
	mu sync.Mutex
}

func (c *ClientConn) SendJSON(v interface{}) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ws.WriteJSON(v)
}

type HubStatusProvider interface {
	GetStatusInfo() (status string, errorMsg string, logs []string)
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
		HubPort: "8090",
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

func (h *Hub) BroadcastHubStatus(status string, port string, errorMsg string, logs []string) {
	h.Broadcast(map[string]interface{}{
		"type":   "hub_status",
		"status": status,
		"port":   port,
		"error":  errorMsg,
		"logs":   logs,
	})
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
	client := &ClientConn{ws: rawConn}

	h.mu.Lock()
	h.clients[client] = true
	prov := h.StatusProv
	port := h.HubPort
	h.mu.Unlock()

	// Send initial hub status immediately on connection
	if prov != nil {
		st, errMsg, logs := prov.GetStatusInfo()
		_ = client.SendJSON(map[string]interface{}{
			"type":   "hub_status",
			"status": st,
			"port":   port,
			"error":  errMsg,
			"logs":   logs,
		})
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
				"error": "Chat is managed directly via AGY Hub on port 8090",
			})
		}
	}
}
