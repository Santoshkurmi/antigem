package opencode

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func doJSON(t *testing.T, m *Manager, method, path, body string) map[string]interface{} {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, req)
	var out map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("%s %s: not JSON: %s", method, path, rec.Body.String())
	}
	return out
}

func TestCustomProvidersRoundTrip(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, home, "")

	// validation
	res := doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers", `{"name":"x","base_url":"ftp://nope","models":[{"id":"a"}]}`)
	if res["success"] != false {
		t.Fatalf("bad URL accepted: %v", res)
	}
	res = doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers", `{"name":"x","base_url":"http://h/v1","models":[]}`)
	if res["success"] != false {
		t.Fatalf("no models accepted: %v", res)
	}

	// add: id from the name, key never returned
	res = doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers",
		`{"name":"My Local Ollama","base_url":"http://localhost:11434/v1/","api_key":"sk-secret-1234","models":[{"id":"llama3.1","name":"Llama"},{"id":"llama3.1"},{"id":" "}]}`)
	if res["success"] != true {
		t.Fatalf("add failed: %v", res)
	}
	p := res["provider"].(map[string]interface{})
	if p["id"] != "my-local-ollama" || p["api_key"] != nil || p["has_api_key"] != true || p["api_key_hint"] != "…1234" {
		t.Fatalf("unexpected provider: %v", p)
	}
	if n := len(p["models"].([]interface{})); n != 1 {
		t.Fatalf("models not deduplicated: %d", n)
	}

	// same name again gets its own id
	res = doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers", `{"name":"My Local Ollama","kind":"anthropic","base_url":"https://a/v1","models":[{"id":"m"}]}`)
	if res["provider"].(map[string]interface{})["id"] != "my-local-ollama-2" {
		t.Fatalf("duplicate id: %v", res)
	}

	// the generated OpenCode config (v2 "providers" format)
	data, err := os.ReadFile(m.configPath())
	if err != nil {
		t.Fatal(err)
	}
	var cfg struct {
		Providers map[string]struct {
			Package  string                            `json:"package"`
			Settings map[string]string                 `json:"settings"`
			Models   map[string]map[string]interface{} `json:"models"`
		} `json:"providers"`
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		t.Fatal(err)
	}
	o := cfg.Providers["my-local-ollama"]
	if o.Package != "@opencode/ai/providers/openai-compatible" || o.Settings["baseURL"] != "http://localhost:11434/v1" || o.Settings["apiKey"] != "sk-secret-1234" {
		t.Fatalf("openai provider config: %+v", o)
	}
	if _, ok := o.Models["llama3.1"]; !ok {
		t.Fatalf("model missing: %+v", o.Models)
	}
	a := cfg.Providers["my-local-ollama-2"]
	if a.Package != "@opencode/ai/providers/anthropic-compatible" || a.Settings["apiKey"] != "none" {
		t.Fatalf("anthropic provider config: %+v", a)
	}
	if fi, _ := os.Stat(m.configPath()); fi.Mode().Perm() != 0600 {
		t.Fatalf("config readable by others: %v", fi.Mode())
	}

	// update with a blank key keeps the saved one; "-" clears it
	res = doJSON(t, m, http.MethodPut, "/api/opencode/custom-providers/my-local-ollama", `{"name":"Ollama","base_url":"http://localhost:11434/v1","models":[{"id":"qwen3"}]}`)
	if res["success"] != true || res["provider"].(map[string]interface{})["has_api_key"] != true {
		t.Fatalf("blank key dropped the saved key: %v", res)
	}
	res = doJSON(t, m, http.MethodPut, "/api/opencode/custom-providers/my-local-ollama", `{"name":"Ollama","base_url":"http://localhost:11434/v1","api_key":"-","models":[{"id":"qwen3"}]}`)
	if res["provider"].(map[string]interface{})["has_api_key"] != false {
		t.Fatalf(`"-" did not clear the key: %v`, res)
	}

	// list, delete
	res = doJSON(t, m, http.MethodGet, "/api/opencode/custom-providers", "")
	if n := len(res["providers"].([]interface{})); n != 2 {
		t.Fatalf("list: %d", n)
	}
	if strings.Contains(mustJSON(res), "sk-secret") {
		t.Fatal("key leaked in the list")
	}
	res = doJSON(t, m, http.MethodDelete, "/api/opencode/custom-providers/my-local-ollama-2", "")
	if res["success"] != true {
		t.Fatalf("delete: %v", res)
	}
	res = doJSON(t, m, http.MethodGet, "/api/opencode/custom-providers", "")
	if n := len(res["providers"].([]interface{})); n != 1 {
		t.Fatalf("after delete: %d", n)
	}
}

func TestStatusWithoutBinary(t *testing.T) {
	home := t.TempDir()
	t.Setenv("PATH", home)
	t.Setenv("OPENCODE_BIN", "")
	m := NewManager(home, home, "")
	res := doJSON(t, m, http.MethodGet, "/api/opencode/status", "")
	if res["installed"] != false || res["error"] == nil {
		t.Fatalf("status: %v", res)
	}
	// the relay answers JSON (the app shows it) instead of hanging
	req := httptest.NewRequest(http.MethodGet, "/api/opencode/x/session", nil)
	rec := httptest.NewRecorder()
	m.ServeHTTP(rec, req)
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "opencode_unavailable") {
		t.Fatalf("relay without binary: %d %s", rec.Code, rec.Body.String())
	}
}

func mustJSON(v interface{}) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func TestMcpServersGoIntoTheConfig(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, home, "")
	res := doJSON(t, m, http.MethodPut, "/api/opencode/mcp/jarvis_mcp", `{"type":"remote","url":"http://127.0.0.1:8765/jarvis","headers":{"X-Device-Id":"d1"}}`)
	if res["success"] != true {
		t.Fatalf("add: %v", res)
	}
	if res := doJSON(t, m, http.MethodPut, "/api/opencode/mcp/bad", `{"type":"remote","url":"ftp://x"}`); res["success"] != false {
		t.Fatalf("bad url accepted: %v", res)
	}
	if res := doJSON(t, m, http.MethodPut, "/api/opencode/mcp/x%20y", `{"type":"local","command":["a"]}`); res["success"] != false {
		t.Fatalf("bad name accepted: %v", res)
	}
	var cfg struct {
		Mcp struct {
			Servers map[string]map[string]interface{} `json:"servers"`
		} `json:"mcp"`
	}
	data, _ := os.ReadFile(m.configPath())
	if err := json.Unmarshal(data, &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.Mcp.Servers["jarvis_mcp"]["url"] != "http://127.0.0.1:8765/jarvis" {
		t.Fatalf("config: %s", data)
	}
	res = doJSON(t, m, http.MethodGet, "/api/opencode/mcp", "")
	if _, ok := res["servers"].(map[string]interface{})["jarvis_mcp"]; !ok {
		t.Fatalf("list: %v", res)
	}
	doJSON(t, m, http.MethodDelete, "/api/opencode/mcp/jarvis_mcp", "")
	data, _ = os.ReadFile(m.configPath())
	if strings.Contains(string(data), "jarvis_mcp") {
		t.Fatalf("not removed: %s", data)
	}
}

func TestBinaryPathTildeAndFallback(t *testing.T) {
	home := t.TempDir()
	bin := filepath.Join(home, ".opencode", "bin", "opencode")
	_ = os.MkdirAll(filepath.Dir(bin), 0755)
	_ = os.WriteFile(bin, []byte("#!/bin/sh\n"), 0755)
	t.Setenv("PATH", home)
	t.Setenv("OPENCODE_BIN", "")
	if got, err := NewManager(home, home, "~/.opencode/bin/opencode").ResolveBinary(); err != nil || got != bin {
		t.Fatalf("tilde path: %q %v", got, err)
	}
	// a path that does not exist falls back to the usual folders
	if got, err := NewManager(home, home, "~/nope/opencode").ResolveBinary(); err != nil || got != bin {
		t.Fatalf("fallback: %q %v", got, err)
	}
}
