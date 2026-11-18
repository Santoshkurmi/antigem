package memory

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
)

// ServeHTTP serves /api/memory/…:
//
//	POST context {project, agent, message, limit, history}      → {text, always, listed, more}
//	POST sync {project, agent, chat_id, title, history, turns}  → {applied, history_file}
//	GET  applied?key=                                           POST undo {key}
//	GET  list                                                   POST save {id, file, scope, project, mode, description, text}
//	POST delete {id}                                            GET  search?q=&project=
//	GET  history/list                                           GET  history/get?chat_id=
//	POST history/save {chat_id, text}                           POST history/delete {chat_id}
//	POST history/clear
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("[memory] panic: %v", rec)
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": fmt.Sprint(rec)})
		}
	}()
	route := strings.Trim(strings.TrimPrefix(r.URL.Path, "/api/memory"), "/")
	switch {
	case route == "context" && r.Method == http.MethodPost:
		var req ContextRequest
		if !decode(w, r, &req, 1<<20) {
			return
		}
		res := m.Context(req)
		reply(w, map[string]interface{}{"text": res.Text, "always": res.Always, "listed": res.Listed, "more": res.More, "root": m.root}, nil)
	case route == "sync" && r.Method == http.MethodPost:
		var req SyncRequest
		if !decode(w, r, &req, 32<<20) {
			return
		}
		res, err := m.Sync(req)
		reply(w, map[string]interface{}{"applied": res.Applied, "history_file": res.HistoryFile}, err)
	case route == "applied" && r.Method == http.MethodGet:
		a := m.Applied(strings.TrimSpace(r.URL.Query().Get("key")))
		reply(w, map[string]interface{}{"applied": a}, nil)
	case route == "undo" && r.Method == http.MethodPost:
		var req struct {
			Key string `json:"key"`
		}
		if !decode(w, r, &req, 1<<16) {
			return
		}
		a, err := m.Undo(strings.TrimSpace(req.Key))
		reply(w, map[string]interface{}{"applied": a}, err)
	case route == "list" && r.Method == http.MethodGet:
		reply(w, map[string]interface{}{"memories": nonNil(m.List()), "root": m.root}, nil)
	case route == "save" && r.Method == http.MethodPost:
		var req SaveRequest
		if !decode(w, r, &req, 1<<20) {
			return
		}
		mem, err := m.Save(req)
		reply(w, map[string]interface{}{"memory": mem}, err)
	case route == "delete" && r.Method == http.MethodPost:
		var req struct {
			ID string `json:"id"`
		}
		if !decode(w, r, &req, 1<<16) {
			return
		}
		reply(w, nil, m.Delete(strings.TrimSpace(req.ID)))
	case route == "search" && r.Method == http.MethodGet:
		q := r.URL.Query()
		res := m.Search(q.Get("q"), q.Get("project"))
		reply(w, map[string]interface{}{"memories": res.Memories, "history": res.History}, nil)
	case route == "history/delete" && r.Method == http.MethodPost:
		var req struct {
			ChatID string `json:"chat_id"`
		}
		if !decode(w, r, &req, 1<<16) {
			return
		}
		reply(w, nil, m.DeleteHistory(strings.TrimSpace(req.ChatID)))
	case route == "history/list" && r.Method == http.MethodGet:
		reply(w, map[string]interface{}{"chats": m.HistoryList()}, nil)
	case route == "history/get" && r.Method == http.MethodGet:
		chat, text, err := m.HistoryGet(strings.TrimSpace(r.URL.Query().Get("chat_id")))
		reply(w, map[string]interface{}{"chat": chat, "text": text}, err)
	case route == "history/save" && r.Method == http.MethodPost:
		var req struct {
			ChatID string `json:"chat_id"`
			Text   string `json:"text"`
		}
		if !decode(w, r, &req, 32<<20) {
			return
		}
		chat, err := m.HistorySave(strings.TrimSpace(req.ChatID), req.Text)
		reply(w, map[string]interface{}{"chat": chat}, err)
	case route == "history/clear" && r.Method == http.MethodPost:
		n, err := m.ClearHistory()
		reply(w, map[string]interface{}{"removed": n}, err)
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown memory call: " + route})
	}
}

func nonNil(list []*Memory) []*Memory {
	if list == nil {
		return []*Memory{}
	}
	return list
}

func decode(w http.ResponseWriter, r *http.Request, v interface{}, limit int64) bool {
	b, err := io.ReadAll(io.LimitReader(r.Body, limit))
	if err == nil && len(b) > 0 {
		err = json.Unmarshal(b, v)
	}
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "bad request: " + err.Error()})
		return false
	}
	return true
}

func reply(w http.ResponseWriter, fields map[string]interface{}, err error) {
	out := map[string]interface{}{"success": err == nil}
	for k, v := range fields {
		out[k] = v
	}
	if err != nil {
		out["error"] = err.Error()
	}
	writeJSON(w, http.StatusOK, out)
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
