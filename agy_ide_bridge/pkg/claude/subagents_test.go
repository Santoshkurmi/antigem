package claude

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSubagentsAreListedUnderTheirChatAndReadable(t *testing.T) {
	m := &Manager{HomeDir: t.TempDir(), sessions: map[string]*Session{}}
	sid := "11111111-2222-3333-4444-555555555555"
	proj := filepath.Join(m.ProjectsDir(), "-home-x")
	sub := filepath.Join(proj, sid, "subagents")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	write := func(p, s string) {
		if err := os.WriteFile(p, []byte(s), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write(filepath.Join(proj, sid+".jsonl"),
		`{"type":"user","uuid":"u1","parentUuid":null,"cwd":"/home/x","timestamp":"2026-01-01T00:00:00Z","message":{"role":"user","content":"main prompt"}}`+"\n")
	write(filepath.Join(sub, "agent-abc.jsonl"),
		`{"type":"user","uuid":"s1","parentUuid":null,"isSidechain":true,"agentId":"abc","cwd":"/home/x","timestamp":"2026-01-01T00:00:01Z","message":{"role":"user","content":"find the files"}}`+"\n"+
			`{"type":"assistant","uuid":"s2","parentUuid":"s1","isSidechain":true,"agentId":"abc","message":{"role":"assistant","content":[{"type":"text","text":"found them"}]}}`+"\n")
	write(filepath.Join(sub, "agent-abc.meta.json"), `{"agentType":"Explore","description":"Find the files","spawnDepth":1,"finished":true}`)
	write(filepath.Join(sub, "agent-def.jsonl"),
		`{"type":"user","uuid":"n1","parentUuid":null,"isSidechain":true,"agentId":"def","message":{"role":"user","content":"nested"}}`+"\n")
	write(filepath.Join(sub, "agent-def.meta.json"), `{"agentType":"general-purpose","description":"Nested one","spawnDepth":2,"parentAgentId":"abc"}`)
	// a subagent of a chat that is not listed is left out
	other := filepath.Join(proj, "99999999-2222-3333-4444-555555555555", "subagents")
	_ = os.MkdirAll(other, 0o755)
	write(filepath.Join(other, "agent-zzz.jsonl"), `{"type":"user","uuid":"z","isSidechain":true,"message":{"role":"user","content":"x"}}`+"\n")

	byID := map[string]SessionSummary{}
	for _, s := range m.ListSessions() {
		byID[s.ID] = s
	}
	if len(byID) != 3 {
		t.Fatalf("want chat + 2 subagents, got %v", byID)
	}
	a := byID["agent-abc"]
	if !a.Subagent || a.ParentID != sid || a.AgentType != "Explore" || a.Title != "Find the files" || a.Cwd != "/home/x" || a.Busy {
		t.Fatalf("bad subagent summary: %+v", a)
	}
	if d := byID["agent-def"]; d.ParentID != "agent-abc" || d.Busy {
		t.Fatalf("nested subagent: %+v (its chat's CLI is not running, so it is not busy)", d)
	}
	if byID[sid].Subagent || byID[sid].ParentID != "" {
		t.Fatalf("the chat itself is not a subagent: %+v", byID[sid])
	}
	h, err := m.History("agent-abc", 0, nil)
	if err != nil || len(h) != 2 {
		t.Fatalf("subagent history: %d entries, err %v", len(h), err)
	}
	if h, _ := m.History(sid, 0, nil); len(h) != 1 {
		t.Fatalf("the chat's own history must not include sidechains: %d", len(h))
	}

	// through the HTTP API, as the app reads it
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/claude/sessions/agent-abc/history", nil))
	var resp struct {
		Success bool              `json:"success"`
		Entries []json.RawMessage `json:"entries"`
	}
	if rec.Code != http.StatusOK || json.Unmarshal(rec.Body.Bytes(), &resp) != nil || !resp.Success || len(resp.Entries) != 2 {
		t.Fatalf("GET subagent history: %d %s", rec.Code, rec.Body.String())
	}
	// anything else on a subagent is refused
	for _, req := range []*http.Request{
		httptest.NewRequest(http.MethodPost, "/api/claude/sessions/agent-abc/title", nil),
		httptest.NewRequest(http.MethodDelete, "/api/claude/sessions/agent-abc", nil),
		httptest.NewRequest(http.MethodGet, "/api/claude/sessions/agent-../history", nil),
	} {
		rec := httptest.NewRecorder()
		m.ServeHTTP(rec, req)
		if rec.Code == http.StatusOK {
			t.Fatalf("%s %s must be refused: %s", req.Method, req.URL.Path, rec.Body.String())
		}
	}
}
