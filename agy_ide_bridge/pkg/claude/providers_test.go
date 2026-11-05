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
		"CLAUDE_CODE_MAX_RETRIES": "3", // the default for a provider that sets none
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
		_, _ = w.Write([]byte(`{"data":[{"id":"a","context_length":1000},{"foo":1}]}`))
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

func TestLimitsResolution(t *testing.T) {
	p := Provider{
		ThinkingBudget: 8000, DefaultContext: 64000, DefaultMaxOutput: 4096,
		Models: []ProviderModel{
			{ID: "plain"},
			{ID: "big", ContextLength: 1_000_000, MaxOutput: 32000},
			{ID: "nothink", ThinkingMode: "off"},
			{ID: "deep", ThinkingMode: "budget", ThinkingBudget: 20000},
		},
	}
	cases := map[string]ModelLimits{
		"plain":   {Context: 64000, MaxOutput: 4096, ThinkingBudget: 8000},
		"big":     {Context: 1_000_000, MaxOutput: 32000, ThinkingBudget: 8000},
		"nothink": {Context: 64000, MaxOutput: 4096, ThinkingBudget: 0},
		"deep":    {Context: 64000, MaxOutput: 4096, ThinkingBudget: 20000},
		"unknown": {Context: 64000, MaxOutput: 4096, ThinkingBudget: 8000},
	}
	for id, want := range cases {
		if got := p.Limits(id); got != want {
			t.Errorf("%s: got %+v want %+v", id, got, want)
		}
	}
}

func TestRestartProviderSessionsSkipsOthers(t *testing.T) {
	m := &Manager{HomeDir: t.TempDir(), sessions: map[string]*Session{}}
	// idle sessions without a process are not counted; only live idle ones of the provider are restarted
	m.sessions["a"] = &Session{ID: "a", mgr: m, opts: SpawnOptions{Model: "@or/x"}}
	m.sessions["b"] = &Session{ID: "b", mgr: m, opts: SpawnOptions{Model: "sonnet"}}
	if r, b := m.restartProviderSessions("or"); r != 0 || b != 0 {
		t.Fatalf("restarted=%d busy=%d, want 0 0", r, b)
	}
}

func fetchWith(t *testing.T, h http.HandlerFunc, mp *ModelsMapping) ([]ProviderModel, *FetchRaw, error) {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return fetchProviderModelsRaw(context.Background(), Provider{BaseURL: srv.URL, ModelsURL: srv.URL + "/v1/models", APIKey: "k", ModelsMap: mp}, false)
}

func TestFetchAnthropicCursorPagination(t *testing.T) {
	var queries []string
	models, raw, err := fetchWith(t, func(w http.ResponseWriter, r *http.Request) {
		queries = append(queries, r.URL.RawQuery)
		if r.URL.Query().Get("after_id") == "" {
			_, _ = w.Write([]byte(`{"data":[{"type":"model","id":"m1","display_name":"M1","max_input_tokens":200000,"max_tokens":64000}],"has_more":true,"first_id":"m1","last_id":"m1"}`))
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"m2"},{"id":"m3"}],"has_more":false,"first_id":"m2","last_id":"m3"}`))
	}, nil)
	if err != nil || len(models) != 3 || raw.Pages != 2 || raw.Pagination != "cursor" {
		t.Fatalf("models=%v raw=%+v err=%v", models, raw, err)
	}
	if models[0].ContextLength != 200000 || models[0].MaxOutput != 64000 || models[0].Name != "M1" {
		t.Fatalf("first model: %+v", models[0])
	}
	if len(queries) != 2 || !strings.Contains(queries[1], "after_id=m1") || !strings.Contains(queries[1], "limit=1000") {
		t.Fatalf("second request: %v", queries)
	}
}

func TestFetchTokenAndNextURLPagination(t *testing.T) {
	models, raw, err := fetchWith(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("pageToken") == "" {
			_, _ = w.Write([]byte(`{"models":[{"name":"models/gemini-a","inputTokenLimit":1048576,"outputTokenLimit":8192}],"nextPageToken":"T2"}`))
			return
		}
		_, _ = w.Write([]byte(`{"models":[{"name":"models/gemini-b"}]}`))
	}, nil)
	if err != nil || len(models) != 2 || raw.Pagination != "token" {
		t.Fatalf("token: models=%v raw=%+v err=%v", models, raw, err)
	}
	if models[0].ID != "gemini-a" || models[0].ContextLength != 1048576 || models[0].MaxOutput != 8192 {
		t.Fatalf("google model: %+v", models[0])
	}

	var srvURL string
	models, raw, err = fetchWith(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("page") == "2" {
			_, _ = w.Write([]byte(`{"data":[{"id":"y"}],"next":null}`))
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"x"}],"next":"` + "http://" + r.Host + `/v1/models?page=2"}`))
	}, nil)
	_ = srvURL
	if err != nil || len(models) != 2 || raw.Pagination != "next_url" {
		t.Fatalf("next_url: models=%v raw=%+v err=%v", models, raw, err)
	}
}

func TestFetchMappingAndShape(t *testing.T) {
	body := `{"result":{"items":[{"slug":"a/b","meta":{"ctx":"4096"},"label":"AB"},{"slug":"c"}]},"status":"ok"}`
	h := func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(body)) }
	// without a mapping the nested list and the "slug" id are still detected, but not the size
	if models, raw, err := fetchWith(t, h, nil); err != nil || len(models) != 2 || models[0].ContextLength != 0 {
		t.Fatalf("auto: models=%+v raw=%+v err=%v", models, raw, err)
	} else if len(raw.Lists) != 1 || raw.Lists[0].Path != "result.items" || len(raw.Lists[0].Keys) == 0 || len(raw.TopKeys) != 1 {
		t.Fatalf("shape: %+v %+v", raw.Lists, raw.TopKeys)
	}
	mp := &ModelsMapping{ListPath: "result.items", IDField: "slug", NameField: "label", ContextField: "meta.ctx"}
	models, _, err := fetchWith(t, h, mp)
	if err != nil || len(models) != 2 || models[0].ID != "a/b" || models[0].Name != "AB" || models[0].ContextLength != 4096 {
		t.Fatalf("mapped: %+v err=%v", models, err)
	}
	// a plain array of strings
	models, _, err = fetchWith(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`["m1","m2"]`)) }, nil)
	if err != nil || len(models) != 2 {
		t.Fatalf("strings: %+v err=%v", models, err)
	}
}

func TestFetchPagingGuardAndNonList(t *testing.T) {
	// a server that keeps saying has_more with the same page must not loop forever
	models, raw, err := fetchWith(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"data":[{"id":"same"}],"has_more":true,"last_id":"same"}`))
	}, nil)
	if err != nil || len(models) != 1 || raw.PagingError == "" {
		t.Fatalf("guard: models=%v raw=%+v err=%v", models, raw, err)
	}
	if _, _, err := fetchWith(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(`{"ok":true}`)) }, nil); err == nil {
		t.Fatal("expected an error for an answer without a list")
	}
}

func TestProviderRetries(t *testing.T) {
	for in, want := range map[int]int{0: 3, -1: 0, 1: 1, 7: 7, 99: 10} {
		if got := (Provider{MaxRetries: in}).retries(); got != want {
			t.Errorf("MaxRetries %d: got %d want %d", in, got, want)
		}
	}
}
