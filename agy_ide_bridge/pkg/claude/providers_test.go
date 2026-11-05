package claude

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestProviderSettingsFile(t *testing.T) {
	m := &Manager{HomeDir: t.TempDir()}
	p := Provider{
		ID: "kimi", BaseURL: "https://x/anthropic", APIKey: "k", AuthMode: authXAPIKey,
		Headers: map[string]string{"X-A": "1"}, ExtraEnv: map[string]string{"FOO": "bar"}, TimeoutSec: 60,
		Models: []ProviderModel{{ID: "m1", ContextLength: 128000, MaxOutput: 8192}},
	}
	path, err := m.providerSettingsFile(p, "m1")
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(path)
	var f struct{ Env map[string]string }
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	want := map[string]string{
		"ANTHROPIC_API_KEY": "k", "ANTHROPIC_AUTH_TOKEN": "", "ANTHROPIC_MODEL": "m1",
		"ANTHROPIC_DEFAULT_SONNET_MODEL": "m1", "ANTHROPIC_DEFAULT_OPUS_MODEL": "m1",
		"CLAUDE_CODE_AUTO_COMPACT_WINDOW": "128000", "CLAUDE_CODE_MAX_OUTPUT_TOKENS": "8192",
		"API_TIMEOUT_MS": "60000", "ANTHROPIC_CUSTOM_HEADERS": "X-A: 1", "FOO": "bar",
	}
	for k, v := range want {
		if got, ok := f.Env[k]; !ok || got != v {
			t.Errorf("%s = %q (present %v), want %q", k, got, ok, v)
		}
	}
}

func TestTestProvider(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/messages" || r.Header.Get("x-api-key") != "good" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":{"message":"bad key"}}`))
			return
		}
		_, _ = w.Write([]byte(`{"content":[{"type":"text","text":"ok"}]}`))
	}))
	defer srv.Close()
	p := Provider{BaseURL: srv.URL, APIKey: "good", AuthMode: authXAPIKey}
	if r := testProvider(context.Background(), p, "m"); !r.OK || r.Reply != "ok" {
		t.Fatalf("expected ok, got %+v", r)
	}
	p.AuthMode = authBearer
	if r := testProvider(context.Background(), p, "m"); r.OK || r.Status != 401 || r.Hint == "" {
		t.Fatalf("expected a 401 with a hint, got %+v", r)
	}
}

func TestFetchRaw(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"data":[{"id":"a","context_length":1000},{"name":"no id"}]}`))
	}))
	defer srv.Close()
	models, raw, err := fetchProviderModelsRaw(context.Background(), Provider{BaseURL: srv.URL, ModelsURL: srv.URL, APIKey: "secret1234"}, false)
	if err != nil || len(models) != 1 || models[0].ContextLength != 1000 {
		t.Fatalf("models=%+v err=%v", models, err)
	}
	if raw.Status != 200 || raw.Entries != 2 || raw.Kept != 1 || raw.Body == "" {
		t.Fatalf("raw=%+v", raw)
	}
	for _, h := range raw.RequestHeaders {
		if strings.Contains(h, "secret1234") {
			t.Fatalf("key leaked in %q", h)
		}
	}
}
