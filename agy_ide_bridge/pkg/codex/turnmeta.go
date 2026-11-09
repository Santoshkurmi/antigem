package codex

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"sync"
	"time"
)

// What app-server's history (thread/turns/list) leaves out: each turn's model, reasoning effort, token usage and
// duration. Codex writes them to the thread's rollout file (the JSONL at thread.path) as `turn_context`,
// `token_usage_record` and `task_complete` records; the app shows them on each reply, also after reopening a chat.

// TurnMeta is one turn's model and usage.
type TurnMeta struct {
	Model      string          `json:"model,omitempty"`
	Effort     string          `json:"effort,omitempty"`
	Usage      json.RawMessage `json:"usage,omitempty"`
	DurationMs int64           `json:"duration_ms,omitempty"`
}

type metaCacheEntry struct {
	size  int64
	mod   time.Time
	turns map[string]*TurnMeta
}

var (
	metaCacheMu sync.Mutex
	metaCache   = map[string]*metaCacheEntry{}
)

var (
	markTurnContext = []byte(`"turn_context"`)
	markUsage       = []byte(`"token_usage_record"`)
	markComplete    = []byte(`"task_complete"`)
)

// readTurnMeta parses a rollout file (cached until the file changes).
func readTurnMeta(path string) (map[string]*TurnMeta, error) {
	fi, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	metaCacheMu.Lock()
	if c := metaCache[path]; c != nil && c.size == fi.Size() && c.mod.Equal(fi.ModTime()) {
		metaCacheMu.Unlock()
		return c.turns, nil
	}
	metaCacheMu.Unlock()

	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	turns := map[string]*TurnMeta{}
	roots := map[string]string{} // turn → its root turn (a review runs as an inner turn of the turn the app shows)
	get := func(id string) *TurnMeta {
		t := turns[id]
		if t == nil {
			t = &TurnMeta{}
			turns[id] = t
		}
		return t
	}
	rd := bufio.NewReaderSize(f, 1<<20)
	for {
		line, err := rd.ReadBytes('\n')
		if len(line) > 0 && (bytes.Contains(line, markTurnContext) || bytes.Contains(line, markUsage) || bytes.Contains(line, markComplete)) {
			var rec struct {
				Type    string `json:"type"`
				Payload struct {
					Type           string          `json:"type"`
					TurnID         string          `json:"turn_id"`
					RootTurnID     string          `json:"root_turn_id"`
					Model          string          `json:"model"`
					Effort         string          `json:"effort"`
					TurnTokenUsage json.RawMessage `json:"turn_token_usage"`
					DurationMs     int64           `json:"duration_ms"`
				} `json:"payload"`
			}
			if json.Unmarshal(line, &rec) == nil && rec.Payload.TurnID != "" {
				p := rec.Payload
				if p.RootTurnID != "" && p.RootTurnID != p.TurnID {
					roots[p.TurnID] = p.RootTurnID
				}
				switch {
				case rec.Type == "turn_context":
					t := get(p.TurnID)
					t.Model, t.Effort = p.Model, p.Effort
				case rec.Type == "token_usage_record" && len(p.TurnTokenUsage) > 0:
					// the last record of a turn holds the whole turn's usage
					get(p.TurnID).Usage = p.TurnTokenUsage
				case p.Type == "task_complete":
					get(p.TurnID).DurationMs = p.DurationMs
				}
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
	}
	// the outer turn of a review shows the inner turn's model and usage
	for inner, root := range roots {
		if t := turns[inner]; t != nil {
			if r := turns[root]; r == nil || (r.Model == "" && len(r.Usage) == 0) {
				cp := *t
				turns[root] = &cp
			}
		}
	}
	metaCacheMu.Lock()
	metaCache[path] = &metaCacheEntry{size: fi.Size(), mod: fi.ModTime(), turns: turns}
	if len(metaCache) > 64 {
		for k := range metaCache {
			if k != path {
				delete(metaCache, k)
				break
			}
		}
	}
	metaCacheMu.Unlock()
	return turns, nil
}

// handleTurnsMeta: GET /api/codex/turns-meta?thread=ID → {"success", "turns": {turnId: TurnMeta}}.
func (m *Manager) handleTurnsMeta(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("thread")
	if id == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "thread is required"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	params, _ := json.Marshal(map[string]interface{}{"threadId": id})
	res, err := m.Call(ctx, "thread/read", params)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	var read struct {
		Thread struct {
			Path string `json:"path"`
		} `json:"thread"`
	}
	_ = json.Unmarshal(res, &read)
	turns := map[string]*TurnMeta{}
	if read.Thread.Path != "" {
		if t, err := readTurnMeta(read.Thread.Path); err == nil {
			turns = t
		}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "turns": turns})
}
