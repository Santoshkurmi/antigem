package dsh

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// the published dsh 0.2.2 code the Android patch adapts (dsh-session-persistence-jsonl/lib/index.js)
const upstreamPublish = `import { link, mkdir, rm } from "node:fs/promises";
async function publish(tmp, finalPath) {
		let linked = false;
		try {
			await link(tmp, finalPath);
			linked = true;
		} finally {
			if (!linked) await rm(tmp, { force: true });
		}
}
`

func writePersistence(t *testing.T, dir, content string) string {
	t.Helper()
	f := filepath.Join(dir, "node_modules", "@deepseek-ai", "dsh-session-persistence-jsonl", "lib", "index.js")
	if err := os.MkdirAll(filepath.Dir(f), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestPatchSessionLink(t *testing.T) {
	dir := t.TempDir()
	f := writePersistence(t, dir, upstreamPublish)
	if err := patchSessionLink(dir); err != nil {
		t.Fatalf("patch: %v", err)
	}
	b, _ := os.ReadFile(f)
	s := string(b)
	for _, want := range []string{"SELinux denies link(2)", "__antigemRename(tmp, finalPath)", `import { rename as __antigemRename, stat as __antigemStat } from "node:fs/promises";`} {
		if !strings.Contains(s, want) {
			t.Errorf("patched file lacks %q", want)
		}
	}
	// idempotent
	if err := patchSessionLink(dir); err != nil {
		t.Fatalf("second patch: %v", err)
	}
	b2, _ := os.ReadFile(f)
	if string(b2) != s {
		t.Error("patching twice changed the file again")
	}
}

func TestPatchSessionLinkChangedUpstream(t *testing.T) {
	dir := t.TempDir()
	writePersistence(t, dir, strings.ReplaceAll(upstreamPublish, "\t\t\tlinked = true;\n", "\t\t\tlinked = !!1;\n"))
	if err := patchSessionLink(dir); err == nil {
		t.Fatal("a version whose code changed must be refused")
	}
	// no hard links at all: nothing to adapt
	writePersistence(t, dir, "export const x = 1;\n")
	if err := patchSessionLink(dir); err != nil {
		t.Fatalf("no link(): %v", err)
	}
}

func TestProfilePatch(t *testing.T) {
	m := NewManager(t.TempDir(), "", "")
	cfg := Config{Provider: "openrouter", Model: "deepseek/deepseek-v4-flash", Keys: map[string]string{"OPENROUTER_API_KEY": "k", "DEEPSEEK_API_KEY": "d"},
		Mcp: []McpServer{
			{Name: "gh", Transport: "stdio", Command: "npx", Args: []string{"-y", "server: \"x\""}, Env: map[string]string{"T": "a'b"}},
			{Name: "off", Transport: "stdio", Command: "x", Disabled: true},
			{Name: "web", Transport: "streamable-http", URL: "http://localhost:3000/mcp"},
		}}
	cfg.normalize()
	p := m.profilePatch(cfg)
	for _, want := range []string{
		"- id: session-title-llm\n  disabled: false",
		`providers: {"openrouter":{"apiKeyEnv":"OPENROUTER_API_KEY"}}`,
		"name: './antigem-dsh-control.mjs'",
		`- id: "antigem-mcp-gh"`,
		`"args":["-y","server: \"x\""]`,
		`- id: "antigem-mcp-web"`,
	} {
		if !strings.Contains(p, want) {
			t.Errorf("profile patch lacks %q:\n%s", want, p)
		}
	}
	if strings.Contains(p, "antigem-mcp-off") {
		t.Error("a disabled MCP server was mounted")
	}
	if strings.Contains(p, "deepseek-official") {
		t.Error("deepseek-official is dsh's own adapter and must not be declared as a pi-ai route")
	}
}

func TestValidateMcp(t *testing.T) {
	ok := []McpServer{{Name: "a_b-1", Transport: "stdio", Command: "x"}, {Name: "w", Transport: "streamable-http", URL: "https://e/mcp"}}
	if err := validateMcp(ok); err != nil {
		t.Fatalf("valid list refused: %v", err)
	}
	bad := [][]McpServer{
		{{Name: "bad name", Transport: "stdio", Command: "x"}},
		{{Name: "a", Transport: "stdio", Command: "x"}, {Name: "a", Transport: "stdio", Command: "y"}},
		{{Name: "a", Transport: "stdio"}},
		{{Name: "a", Transport: "streamable-http", URL: "ftp://x"}},
		{{Name: "a", Transport: "sse", URL: "http://x"}},
	}
	for i, l := range bad {
		if validateMcp(l) == nil {
			t.Errorf("case %d accepted", i)
		}
	}
}

func TestStripStream(t *testing.T) {
	line := []byte(`{"method":"session.event","params":{"sessionId":"s","event":{"type":"assistant/message","seq":3,"data":{"message":{"role":"assistant"},"stream":[{"type":"chunk"}],"usage":{"inputTokens":1}}}}}`)
	out := stripStream(line)
	if strings.Contains(string(out), "stream") {
		t.Fatalf("stream kept: %s", out)
	}
	var v map[string]interface{}
	if json.Unmarshal(out, &v) != nil || !strings.Contains(string(out), `"usage":{"inputTokens":1}`) {
		t.Fatalf("message damaged: %s", out)
	}
}

// a fake ~/.dsh with one root chat and one subagent chat (no Node: headers are not read)
func TestListSessionsAndMeta(t *testing.T) {
	home := t.TempDir()
	t.Setenv("DSH_HOME", "")
	m := NewManager(home, "", "")
	dsh := filepath.Join(home, ".dsh")
	mk := func(id string, title string) {
		d := filepath.Join(dsh, "sessions", "--slug--", id)
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
		_ = os.WriteFile(filepath.Join(d, "session.lock"), nil, 0o644)
		_ = os.WriteFile(filepath.Join(d, "session.v4.jsonl.zstd"), []byte("x"), 0o644)
		pc := map[string]interface{}{"version": 7, "record": map[string]interface{}{
			"identity": map[string]interface{}{"createdAt": 1, "cwd": "/w"},
			"rows": map[string]interface{}{
				"title":          map[string]interface{}{"val": title},
				"modelSelection": map[string]interface{}{"val": map[string]interface{}{"lastUsed": map[string]string{"provider": "openrouter", "model": "m"}}},
			}}}
		b, _ := json.Marshal(pc)
		pd := filepath.Join(dsh, "storages", "session_projcache", "sessions")
		_ = os.MkdirAll(pd, 0o755)
		_ = os.WriteFile(filepath.Join(pd, id+".json"), b, 0o644)
	}
	mk("session-aaa", "First chat")
	mk("0b6f-child", "Child")
	// a folder without a log (dsh creates the lock first) is not a chat
	_ = os.MkdirAll(filepath.Join(dsh, "sessions", "--slug--", "session-empty"), 0o755)

	if err := m.meta.update(func(cm *chatMeta) { cm.Titles["session-aaa"] = "Renamed"; cm.Archived["0b6f-child"] = true }); err != nil {
		t.Fatal(err)
	}
	list, err := m.ListSessions(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 {
		t.Fatalf("want 2 chats, got %d: %+v", len(list), list)
	}
	byID := map[string]SessionInfo{}
	for _, s := range list {
		byID[s.ID] = s
	}
	if s := byID["session-aaa"]; s.Title != "Renamed" || s.TitleSource != "user" || s.Model != "m" || s.Cwd != "/w" {
		t.Errorf("root chat: %+v", s)
	}
	if s := byID["0b6f-child"]; !s.Archived || s.Title != "Child" {
		t.Errorf("child chat: %+v", s)
	}
	if m.sessionDir("0b6f-child") == "" || m.sessionDir("../x") != "" {
		t.Error("sessionDir lookup")
	}
	if err := m.DeleteSession(t.Context(), "session-aaa"); err != nil {
		t.Fatal(err)
	}
	if m.sessionDir("session-aaa") != "" {
		t.Error("deleted chat still on disk")
	}
	if _, ok := m.meta.load().Titles["session-aaa"]; ok {
		t.Error("deleted chat's title kept")
	}
}

func TestStatusAndConfigRoutes(t *testing.T) {
	m := NewManager(t.TempDir(), "", "")
	do := func(method, path, body string) map[string]interface{} {
		t.Helper()
		req := httptest.NewRequest(method, "/api/dsh"+path, strings.NewReader(body))
		rec := httptest.NewRecorder()
		m.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s %s: HTTP %d %s", method, path, rec.Code, rec.Body.String())
		}
		var v map[string]interface{}
		if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
			t.Fatalf("%s %s: %v", method, path, err)
		}
		return v
	}
	st := do("GET", "/status", "")
	if st["installed"] != false || st["running"] != false {
		t.Errorf("fresh status: %v", st)
	}
	res := do("POST", "/config", `{"provider":"openrouter","model":"x/y","keys":{"OPENROUTER_API_KEY":"secret"}}`)
	if res["success"] != true {
		t.Fatalf("config: %v", res)
	}
	cfg := do("GET", "/config", "")["config"].(map[string]interface{})
	if cfg["provider"] != "openrouter" || cfg["model"] != "x/y" {
		t.Errorf("config not saved: %v", cfg)
	}
	if b, _ := json.Marshal(cfg); strings.Contains(string(b), "secret") {
		t.Error("an API key was sent back")
	}
	if fi, err := os.Stat(m.l.configFile()); err != nil || fi.Mode().Perm() != 0o600 {
		t.Errorf("config.json must be private (0600): %v %v", fi, err)
	}
	if do("POST", "/config", `{"mcp":[{"name":"bad name","transport":"stdio","command":"x"}]}`)["success"] != false {
		t.Error("an invalid MCP server was accepted")
	}
	if r := do("POST", "/cancel", `{"session_id":"s"}`); r["success"] != false {
		t.Errorf("cancel without dsh running: %v", r)
	}
}
