package checkpoint

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
)

// ServeHTTP serves /api/checkpoints/…:
//
//	GET  settings                      POST settings {mode, retention_days, max_per_chat, max_project_mb, max_file_mb}
//	GET  project?path=                 POST project {path, decision: on|off|""}
//	GET  projects                      GET  history?project=&limit=
//	POST turn {project, agent, chat_id, key, title, mid_turn, deadline_ms}
//	POST bind {project, id, key}       POST lookup {project, keys}
//	POST preview {project, id}         POST restore {project, id}
//	POST diff {project, id, path}      POST delete {project, ids | older_than_days | all, forget}
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("[checkpoints] panic: %v", rec)
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": fmt.Sprint(rec)})
		}
	}()
	route := strings.Trim(strings.TrimPrefix(r.URL.Path, "/api/checkpoints"), "/")
	switch {
	case route == "settings" && r.Method == http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "settings": m.Settings()})
	case route == "settings" && r.Method == http.MethodPost:
		var p SettingsPatch
		if !decode(w, r, &p) {
			return
		}
		s, err := m.UpdateSettings(p)
		reply(w, map[string]interface{}{"settings": s}, err)
	case route == "project" && r.Method == http.MethodGet:
		st := m.State(r.URL.Query().Get("path"))
		m.WarmIfNeeded(st)
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "project": st})
	case route == "project" && r.Method == http.MethodPost:
		var req struct {
			Path     string `json:"path"`
			Decision string `json:"decision"`
		}
		if !decode(w, r, &req) {
			return
		}
		err := m.SetDecision(req.Path, req.Decision)
		reply(w, map[string]interface{}{"project": m.State(req.Path)}, err)
	case route == "projects":
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "settings": m.Settings(), "projects": m.Projects()})
	case route == "history":
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		h, err := m.History(r.URL.Query().Get("project"), limit)
		reply(w, map[string]interface{}{"checkpoints": h}, err)
	case route == "turn" && r.Method == http.MethodPost:
		var req TurnRequest
		if !decode(w, r, &req) {
			return
		}
		res, err := m.Turn(req)
		if err != nil {
			log.Printf("[checkpoints] %s: %v", req.Project, err)
		}
		reply(w, map[string]interface{}{"result": res}, err)
	case route == "bind" && r.Method == http.MethodPost:
		var req struct {
			Project string `json:"project"`
			ID      string `json:"id"`
			Key     string `json:"key"`
		}
		if !decode(w, r, &req) {
			return
		}
		reply(w, nil, m.Bind(req.Project, req.ID, req.Key))
	case route == "lookup" && r.Method == http.MethodPost:
		var req struct {
			Project string   `json:"project"`
			Keys    []string `json:"keys"`
		}
		if !decode(w, r, &req) {
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "checkpoints": m.Lookup(req.Project, req.Keys)})
	case route == "preview" && r.Method == http.MethodPost:
		var req struct {
			Project string `json:"project"`
			ID      string `json:"id"`
		}
		if !decode(w, r, &req) {
			return
		}
		pv, err := m.PreviewRestore(req.Project, req.ID)
		reply(w, map[string]interface{}{"preview": pv}, err)
	case route == "restore" && r.Method == http.MethodPost:
		var req struct {
			Project string `json:"project"`
			ID      string `json:"id"`
		}
		if !decode(w, r, &req) {
			return
		}
		res, err := m.Restore(req.Project, req.ID)
		reply(w, map[string]interface{}{"result": res}, err)
	case route == "diff" && r.Method == http.MethodPost:
		var req struct {
			Project string `json:"project"`
			ID      string `json:"id"`
			Path    string `json:"path"`
		}
		if !decode(w, r, &req) {
			return
		}
		d, err := m.FileDiff(req.Project, req.ID, req.Path)
		reply(w, map[string]interface{}{"diff": d}, err)
	case route == "delete" && r.Method == http.MethodPost:
		var req DeleteRequest
		if !decode(w, r, &req) {
			return
		}
		res, err := m.Delete(req)
		reply(w, map[string]interface{}{"result": res}, err)
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown checkpoints call: " + route})
	}
}

func decode(w http.ResponseWriter, r *http.Request, v interface{}) bool {
	b, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
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
