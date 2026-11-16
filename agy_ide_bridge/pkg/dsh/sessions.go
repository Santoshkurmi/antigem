package dsh

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Chats are read from dsh's own files, so the list and old chats show without starting dsh:
//
//	~/.dsh/sessions/<cwd slug>/<session id>/session.v4.jsonl.zstd   the session log (zstd JSON lines; Node reads it)
//	~/.dsh/storages/session_projcache/sessions/<session id>.json    dsh's projection cache: title, model, usage…
//	~/.dsh/storages/workspace.json                                  dsh's workspace state (archived sessions)

type sessionHeader struct {
	Origin          string          `json:"origin,omitempty"`
	ParentSession   string          `json:"parentSession,omitempty"`
	DelegationDepth int             `json:"delegationDepth,omitempty"`
	Raw             json.RawMessage `json:"-"`
}

type headerCache struct {
	mu sync.Mutex
	m  map[string]sessionHeader
}

// SessionInfo is one chat in the list.
type SessionInfo struct {
	ID              string          `json:"id"`
	Title           string          `json:"title"`
	TitleSource     string          `json:"title_source,omitempty"`
	Cwd             string          `json:"cwd,omitempty"`
	CreatedAt       int64           `json:"created_at,omitempty"`
	UpdatedAt       int64           `json:"updated_at"`
	Provider        string          `json:"provider,omitempty"`
	Model           string          `json:"model,omitempty"`
	Archived        bool            `json:"archived"`
	Running         bool            `json:"running"`
	Loaded          bool            `json:"loaded"`
	Origin          string          `json:"origin,omitempty"`
	ParentSession   string          `json:"parent_session,omitempty"`
	DelegationDepth int             `json:"delegation_depth,omitempty"`
	Turns           int             `json:"turns,omitempty"`
	TokenUsage      json.RawMessage `json:"token_usage,omitempty"`
	ContextPressure json.RawMessage `json:"context_pressure,omitempty"`
	Todos           json.RawMessage `json:"todos,omitempty"`
	Goal            json.RawMessage `json:"goal,omitempty"`
	Plan            json.RawMessage `json:"plan,omitempty"`
	Permissions     json.RawMessage `json:"permissions,omitempty"`
}

func (m *Manager) sessionsRoot() string { return filepath.Join(m.dshHome(), "sessions") }

// sessionDir finds a session's folder ("" when it does not exist).
func (m *Manager) sessionDir(id string) string {
	if id == "" || strings.ContainsAny(id, "/\\") || strings.HasPrefix(id, ".") {
		return ""
	}
	matches, _ := filepath.Glob(filepath.Join(m.sessionsRoot(), "*", id))
	for _, d := range matches {
		if fi, err := os.Stat(d); err == nil && fi.IsDir() {
			return d
		}
	}
	return ""
}

// sessionLog is the session's log file ("" when the session has none yet).
func sessionLog(dir string) string {
	matches, _ := filepath.Glob(filepath.Join(dir, "session.*.jsonl*"))
	sort.Strings(matches)
	for i := len(matches) - 1; i >= 0; i-- {
		if fi, err := os.Stat(matches[i]); err == nil && fi.Size() > 0 && !strings.HasSuffix(matches[i], ".tmp") {
			return matches[i]
		}
	}
	return ""
}

type projRow struct {
	Val json.RawMessage `json:"val"`
}

type projCache struct {
	Record struct {
		Identity struct {
			CreatedAt int64  `json:"createdAt"`
			Cwd       string `json:"cwd"`
		} `json:"identity"`
		Rows map[string]projRow `json:"rows"`
	} `json:"record"`
}

func (m *Manager) readProjCache(id string) *projCache {
	b, err := os.ReadFile(filepath.Join(m.dshHome(), "storages", "session_projcache", "sessions", id+".json"))
	if err != nil {
		return nil
	}
	var pc projCache
	if json.Unmarshal(b, &pc) != nil {
		return nil
	}
	return &pc
}

// dshArchived is dsh's own archive list (sessions archived from dsh's web UI).
func (m *Manager) dshArchived() map[string]bool {
	out := map[string]bool{}
	b, err := os.ReadFile(filepath.Join(m.dshHome(), "storages", "workspace.json"))
	if err != nil {
		return out
	}
	var ws struct {
		Global struct {
			Archived []string `json:"archivedSessionIds"`
		} `json:"global"`
	}
	if json.Unmarshal(b, &ws) == nil {
		for _, id := range ws.Global.Archived {
			out[id] = true
		}
	}
	return out
}

// loadHeaders reads the first log line of every session not yet cached (one Node run for all of them).
func (m *Manager) loadHeaders(ctx context.Context, logs map[string]string) {
	m.headers.mu.Lock()
	var missing []string
	byFile := map[string]string{}
	for id, f := range logs {
		if _, ok := m.headers.m[id]; !ok {
			missing = append(missing, f)
			byFile[f] = id
		}
	}
	m.headers.mu.Unlock()
	if len(missing) == 0 || m.nodeBinary() == "" {
		return
	}
	if err := m.writeRuntimeFiles(); err != nil {
		return
	}
	cmd, err := m.nodeCommand(ctx, false, append([]string{m.readerScript(), "--headers"}, missing...)...)
	if err != nil {
		return
	}
	out, err := cmd.Output()
	if err != nil && len(out) == 0 {
		return
	}
	m.headers.mu.Lock()
	defer m.headers.mu.Unlock()
	sc := bufio.NewScanner(strings.NewReader(string(out)))
	sc.Buffer(make([]byte, 64<<10), 4<<20)
	for sc.Scan() {
		var row struct {
			File   string          `json:"file"`
			Header json.RawMessage `json:"header"`
		}
		if json.Unmarshal(sc.Bytes(), &row) != nil {
			continue
		}
		id := byFile[row.File]
		if id == "" {
			continue
		}
		var h sessionHeader
		_ = json.Unmarshal(row.Header, &h)
		h.Raw = row.Header
		m.headers.m[id] = h
	}
}

// ListSessions returns every saved chat, newest first.
func (m *Manager) ListSessions(ctx context.Context) ([]SessionInfo, error) {
	// root sessions are "session-<uuid>", subagent sessions a bare uuid; any folder with a log is a session
	dirs, err := filepath.Glob(filepath.Join(m.sessionsRoot(), "*", "*"))
	if err != nil {
		return nil, err
	}
	logs := map[string]string{}
	for _, d := range dirs {
		if strings.HasPrefix(filepath.Base(d), ".") {
			continue
		}
		if f := sessionLog(d); f != "" {
			logs[filepath.Base(d)] = f
		}
	}
	m.loadHeaders(ctx, logs)
	meta := m.meta.load()
	dshArch := m.dshArchived()
	statuses := m.sessionStatuses()
	p := m.liveProc()
	out := make([]SessionInfo, 0, len(logs))
	for id, f := range logs {
		s := SessionInfo{ID: id}
		if fi, err := os.Stat(f); err == nil {
			s.UpdatedAt = fi.ModTime().UnixMilli()
		}
		if pc := m.readProjCache(id); pc != nil {
			s.CreatedAt = pc.Record.Identity.CreatedAt
			s.Cwd = pc.Record.Identity.Cwd
			rows := pc.Record.Rows
			var title string
			if json.Unmarshal(rows["title"].Val, &title) == nil {
				s.Title = title
				s.TitleSource = "dsh"
			}
			var sel struct {
				LastUsed *struct {
					Provider string `json:"provider"`
					Model    string `json:"model"`
				} `json:"lastUsed"`
			}
			if json.Unmarshal(rows["modelSelection"].Val, &sel) == nil && sel.LastUsed != nil {
				s.Provider, s.Model = sel.LastUsed.Provider, sel.LastUsed.Model
			}
			var stats struct {
				Turns int `json:"turns"`
			}
			if json.Unmarshal(rows["sessionStats"].Val, &stats) == nil {
				s.Turns = stats.Turns
			}
			s.TokenUsage = nonNull(rows["tokenUsage"].Val)
			s.ContextPressure = nonNull(rows["contextPressure"].Val)
			s.Todos = nonNull(rows["todos"].Val)
			s.Goal = nonNull(rows["goal"].Val)
			s.Plan = nonNull(rows["plan"].Val)
			s.Permissions = nonNull(rows["permissions"].Val)
		}
		m.headers.mu.Lock()
		if h, ok := m.headers.m[id]; ok {
			s.Origin, s.ParentSession, s.DelegationDepth = h.Origin, h.ParentSession, h.DelegationDepth
		}
		m.headers.mu.Unlock()
		if t := meta.Titles[id]; t != "" {
			s.Title, s.TitleSource = t, "user"
		}
		s.Archived = meta.Archived[id] || dshArch[id]
		s.Running = statuses[id] == "running"
		s.Loaded = p != nil && p.isLoaded(id)
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out, nil
}

func nonNull(r json.RawMessage) json.RawMessage {
	if len(r) == 0 || string(r) == "null" {
		return nil
	}
	return r
}

// writeTranscript streams a session's log as a JSON array of events (without system prompts, tool schemas and raw
// model streams).
func (m *Manager) writeTranscript(ctx context.Context, w http.ResponseWriter, id string) {
	dir := m.sessionDir(id)
	if dir == "" {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "this chat no longer exists"})
		return
	}
	f := sessionLog(dir)
	if f == "" {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "events": []interface{}{}})
		return
	}
	if err := m.writeRuntimeFiles(); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	cmd, err := m.nodeCommand(ctx, false, m.readerScript(), f)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	if err := cmd.Start(); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	defer func() { _ = cmd.Wait() }()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	bw := bufio.NewWriterSize(w, 256<<10)
	_, _ = bw.WriteString(`{"success":true,"session_id":`)
	idJSON, _ := json.Marshal(id)
	_, _ = bw.Write(idJSON)
	_, _ = bw.WriteString(`,"events":[`)
	rd := bufio.NewReaderSize(out, 1<<20)
	first := true
	for {
		line, err := rd.ReadBytes('\n')
		if t := strings.TrimSpace(string(line)); t != "" && json.Valid([]byte(t)) {
			if !first {
				_ = bw.WriteByte(',')
			}
			first = false
			_, _ = bw.WriteString(t)
		}
		if err != nil {
			break
		}
	}
	_, _ = bw.WriteString("]}\n")
	_ = bw.Flush()
}

// DeleteSession removes a chat's files (a running turn is stopped first).
func (m *Manager) DeleteSession(ctx context.Context, id string) error {
	dir := m.sessionDir(id)
	if dir == "" {
		return errors.New("this chat no longer exists")
	}
	if m.sessionStatuses()[id] == "running" {
		cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		_, _ = m.control(cctx, "cancel", map[string]string{"sessionId": id})
		cancel()
	}
	if err := os.RemoveAll(dir); err != nil {
		return err
	}
	_ = os.Remove(filepath.Join(m.dshHome(), "storages", "session_projcache", "sessions", id+".json"))
	m.headers.mu.Lock()
	delete(m.headers.m, id)
	m.headers.mu.Unlock()
	return m.meta.update(func(cm *chatMeta) {
		delete(cm.Titles, id)
		delete(cm.Archived, id)
	})
}
