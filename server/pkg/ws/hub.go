package ws

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"

	"gemini-server/pkg/config"
	"gemini-server/pkg/session"
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

type Hub struct {
	Cfg     *config.Config
	Pool    *session.SessionPoolManager
	clients map[*ClientConn]bool
	mu      sync.RWMutex
}

func NewHub(cfg *config.Config, pool *session.SessionPoolManager) *Hub {
	return &Hub{
		Cfg:     cfg,
		Pool:    pool,
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

type clientMessage struct {
	Type           string `json:"type"` // "send_prompt", "attach_session", "abort", "ping"
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
	h.mu.Unlock()

	fmt.Println("[WS] Client connected")

	defer func() {
		h.mu.Lock()
		delete(h.clients, client)
		h.mu.Unlock()
		_ = rawConn.Close()
		fmt.Println("[WS] Client disconnected")
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

		case "send_prompt":
			targetModel := msg.Model
			if targetModel == "" {
				targetModel = "gemini-3.7-flash-high"
			}
			targetConv := msg.ConversationID
			if targetConv == "new" {
				targetConv = ""
			}

			sessionInst := h.Pool.GetOrCreate(targetModel, targetConv, msg.WorkspaceDir)
			sessionInst.SendTurn(msg.Prompt, client)

		case "attach_session":
			running := h.Pool.FindRunningSession(msg.ConversationID)
			if running != nil {
				fmt.Printf("[WS] Reconnecting / attaching client to running turn for conv: %s\n", msg.ConversationID)
				running.AttachClient(client)
			} else {
				_ = client.SendJSON(map[string]interface{}{
					"type":           "session_attached",
					"conversationId": msg.ConversationID,
					"isRunning":      false,
				})
				_ = client.SendJSON(map[string]interface{}{
					"type":     "done",
					"exitCode": 0,
				})
			}

		case "abort":
			h.Pool.AbortSession(msg.ConversationID)
			_ = client.SendJSON(map[string]interface{}{
				"type":           "aborted",
				"conversationId": msg.ConversationID,
			})
		}
	}
}
