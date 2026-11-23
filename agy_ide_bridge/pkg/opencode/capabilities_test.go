package opencode

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func decodeEntry(t *testing.T, s string) map[string]interface{} {
	t.Helper()
	var d map[string]interface{}
	if err := json.Unmarshal([]byte(s), &d); err != nil {
		t.Fatal(err)
	}
	return d
}

func is(b *bool, want bool) bool { return b != nil && *b == want }

func TestEndpointCaps(t *testing.T) {
	// OpenRouter
	c := endpointCaps(decodeEntry(t, `{"id":"openai/gpt-4o","context_length":128000,
		"architecture":{"modality":"text+image->text","input_modalities":["text","image"]},
		"top_provider":{"max_completion_tokens":16384},"supported_parameters":["tools","tool_choice","temperature"]}`))
	if !is(c.Images, true) || !is(c.Tools, true) || !is(c.Reasoning, false) || c.Context != 128000 || c.Output != 16384 {
		t.Fatalf("openrouter: %+v", c)
	}
	// OpenRouter, text only, reasoning
	c = endpointCaps(decodeEntry(t, `{"id":"deepseek/r1","architecture":{"input_modalities":["text"]},"supported_parameters":["reasoning","include_reasoning"]}`))
	if !is(c.Images, false) || !is(c.Reasoning, true) || !is(c.Tools, false) {
		t.Fatalf("openrouter text: %+v", c)
	}
	// only the modality string
	c = endpointCaps(decodeEntry(t, `{"id":"x","architecture":{"modality":"text+image->text"}}`))
	if !is(c.Images, true) {
		t.Fatalf("modality string: %+v", c)
	}
	// Mistral
	c = endpointCaps(decodeEntry(t, `{"id":"pixtral-large","max_context_length":131072,"capabilities":{"completion_chat":true,"function_calling":true,"vision":true}}`))
	if !is(c.Images, true) || !is(c.Tools, true) || c.Context != 131072 {
		t.Fatalf("mistral: %+v", c)
	}
	// Fireworks-style flags
	c = endpointCaps(decodeEntry(t, `{"id":"f","supports_image_input":false,"supports_tools":true}`))
	if !is(c.Images, false) || !is(c.Tools, true) {
		t.Fatalf("flags: %+v", c)
	}
	// LM Studio
	if c = endpointCaps(decodeEntry(t, `{"id":"qwen2-vl","type":"vlm"}`)); !is(c.Images, true) {
		t.Fatalf("lm studio: %+v", c)
	}
	// a list of capability names only says what is there
	c = endpointCaps(decodeEntry(t, `{"id":"g","capabilities":["streaming","tool-calling"]}`))
	if c.Images != nil || !is(c.Tools, true) {
		t.Fatalf("capability list: %+v", c)
	}
	// vLLM
	if c = endpointCaps(decodeEntry(t, `{"id":"v","max_model_len":32768}`)); c.Context != 32768 || c.Images != nil {
		t.Fatalf("vllm: %+v", c)
	}
	// a plain OpenAI-style entry says nothing
	if c = endpointCaps(decodeEntry(t, `{"id":"tokenplan/hy4flash","object":"model","owned_by":"x"}`)); !c.empty() {
		t.Fatalf("plain: %+v", c)
	}
}

const testCatalog = `{
  "openai": {"models": {
    "gpt-4o": {"attachment": true, "tool_call": true, "modalities": {"input": ["text","image"]}, "limit": {"context": 128000, "output": 16384}}
  }},
  "deepseek": {"models": {
    "deepseek-reasoner": {"reasoning": true, "tool_call": false, "modalities": {"input": ["text"]}, "limit": {"context": 128000, "output": 64000}}
  }},
  "google": {"models": {
    "gemini-2.5-flash": {"attachment": true, "reasoning": true, "tool_call": true, "modalities": {"input": ["text","image","audio"]}, "limit": {"context": 1048576, "output": 65536}}
  }},
  "openrouter": {"models": {
    "openai/gpt-4o": {"attachment": true, "tool_call": true, "modalities": {"input": ["text","image"]}, "limit": {"context": 128000, "output": 4096}}
  }}
}`

func TestCatalogLookup(t *testing.T) {
	cat := parseCatalog([]byte(testCatalog))
	if cat == nil {
		t.Fatal("catalog not parsed")
	}
	for _, id := range []string{"gpt-4o", "GPT-4o", "openai/gpt-4o:free", "azure/gpt-4o", "gpt-4o@2024-08-06"} {
		c, ok := cat.lookup(id)
		if !ok || !is(c.Images, true) || !is(c.Tools, true) {
			t.Fatalf("%s: %v %+v", id, ok, c)
		}
	}
	// an exact id takes that entry's limits
	if c, _ := cat.lookup("gpt-4o"); c.Output != 16384 {
		t.Fatalf("exact limits: %+v", c)
	}
	// by name, two entries with different output limits: the smaller one wins the tie
	if c, _ := cat.lookup("azure/gpt-4o"); c.Output != 4096 || c.Context != 128000 {
		t.Fatalf("limits: %+v", c)
	}
	if c, ok := cat.lookup("models/gemini-2.5-flash"); !ok || !is(c.Images, true) || !is(c.Reasoning, true) || c.Context != 1048576 {
		t.Fatalf("gemini: %v %+v", ok, c)
	}
	if c, ok := cat.lookup("deepseek-reasoner"); !ok || !is(c.Images, false) || !is(c.Reasoning, true) || !is(c.Tools, false) {
		t.Fatalf("deepseek: %v %+v", ok, c)
	}
	if _, ok := cat.lookup("tokenplan/hy4flash"); ok {
		t.Fatal("unknown model found")
	}
	if parseCatalog([]byte(`{"x":{"models":{}}}`)) != nil || parseCatalog([]byte(`nope`)) != nil {
		t.Fatal("empty or broken catalog accepted")
	}
}

func fetchModels(t *testing.T, m *Manager, base string) []map[string]interface{} {
	t.Helper()
	res := doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers/fetch-models", `{"kind":"openai","base_url":"`+base+`"}`)
	if res["success"] != true {
		t.Fatalf("fetch failed: %v", res)
	}
	var out []map[string]interface{}
	for _, it := range res["models"].([]interface{}) {
		out = append(out, it.(map[string]interface{}))
	}
	return out
}

func byID(list []map[string]interface{}, id string) map[string]interface{} {
	for _, m := range list {
		if m["id"] == id {
			return m
		}
	}
	return nil
}

func TestFetchModelsDetectsCapabilities(t *testing.T) {
	home := t.TempDir()
	t.Setenv("XDG_CACHE_HOME", t.TempDir())
	catalogHits := 0
	catSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		catalogHits++
		w.Write([]byte(testCatalog))
	}))
	defer catSrv.Close()
	old := catalogURL
	catalogURL = catSrv.URL
	defer func() { catalogURL = old }()

	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"data":[
			{"id":"gpt-4o","object":"model"},
			{"id":"tokenplan/hy4flash","object":"model"},
			{"id":"router/vision-x","architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools"],"context_length":64000}
		]}`))
	}))
	defer api.Close()

	m := NewManager(home, home, "")
	list := fetchModels(t, m, api.URL+"/v1")
	if g := byID(list, "gpt-4o"); g["images"] != true || g["detected"] != "models.dev" || g["context_length"] != float64(128000) || g["no_tools"] != nil {
		t.Fatalf("gpt-4o: %v", g)
	}
	if h := byID(list, "tokenplan/hy4flash"); h["images"] != nil || h["detected"] != nil {
		t.Fatalf("unknown model: %v", h)
	}
	if v := byID(list, "router/vision-x"); v["images"] != true || v["detected"] != "endpoint" || v["context_length"] != float64(64000) {
		t.Fatalf("endpoint model: %v", v)
	}
	// the catalog is kept on disk and in memory
	if _, err := os.Stat(m.catalogCachePath()); err != nil {
		t.Fatalf("catalog not cached: %v", err)
	}
	fetchModels(t, m, api.URL+"/v1")
	if catalogHits != 1 {
		t.Fatalf("catalog downloaded %d times", catalogHits)
	}
	// a new manager reads the copy on disk
	fetchModels(t, NewManager(home, home, ""), api.URL+"/v1")
	if catalogHits != 1 {
		t.Fatalf("catalog downloaded again instead of read from disk: %d", catalogHits)
	}
}

func TestFetchModelsAsksOllama(t *testing.T) {
	home := t.TempDir()
	t.Setenv("XDG_CACHE_HOME", t.TempDir())
	old := catalogURL
	catalogURL = "http://127.0.0.1:1/none" // no catalog
	defer func() { catalogURL = old }()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/models":
			w.Write([]byte(`{"object":"list","data":[{"id":"gemma3:4b","owned_by":"library"},{"id":"llama3.1:8b","owned_by":"library"}]}`))
		case "/api/show":
			var body struct{ Model string }
			json.NewDecoder(r.Body).Decode(&body)
			if strings.HasPrefix(body.Model, "gemma3") {
				w.Write([]byte(`{"capabilities":["completion","vision"]}`))
			} else {
				w.Write([]byte(`{"capabilities":["completion","tools"]}`))
			}
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	list := fetchModels(t, NewManager(home, home, ""), srv.URL+"/v1")
	if g := byID(list, "gemma3:4b"); g["images"] != true || g["no_tools"] != true || g["detected"] != "ollama" {
		t.Fatalf("gemma3: %v", g)
	}
	if l := byID(list, "llama3.1:8b"); l["images"] != nil || l["no_tools"] != nil || l["detected"] != "ollama" {
		t.Fatalf("llama: %v", l)
	}
}

func TestImagesGoIntoTheConfig(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, home, "")
	res := doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers",
		`{"name":"Athena","base_url":"https://ai.example/v1","models":[{"id":"tokenplan/hy4flash","images":true,"reasoning":true},{"id":"plain","no_tools":true}]}`)
	if res["success"] != true {
		t.Fatalf("add failed: %v", res)
	}
	data, err := os.ReadFile(m.configPath())
	if err != nil {
		t.Fatal(err)
	}
	var cfg struct {
		Providers map[string]struct {
			Models map[string]struct {
				Capabilities  map[string]interface{} `json:"capabilities"`
				Compatibility map[string]interface{} `json:"compatibility"`
			} `json:"models"`
		} `json:"providers"`
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		t.Fatal(err)
	}
	inputs := func(caps map[string]interface{}) string {
		var out []string
		for _, v := range caps["input"].([]interface{}) {
			out = append(out, v.(string))
		}
		return strings.Join(out, ",")
	}
	hy := cfg.Providers["athena"].Models["tokenplan/hy4flash"]
	if inputs(hy.Capabilities) != "text,image" || hy.Capabilities["tools"] != true {
		t.Fatalf("hy4flash: %+v", hy)
	}
	// OpenCode v2 drops the whole provider for a "reasoning" capability; reasoning goes in as the field it streams in
	if _, bad := hy.Capabilities["reasoning"]; bad || hy.Compatibility["reasoningField"] != "reasoning_content" {
		t.Fatalf("hy4flash reasoning: %+v", hy)
	}
	plain := cfg.Providers["athena"].Models["plain"]
	if inputs(plain.Capabilities) != "text" || plain.Capabilities["tools"] != false || plain.Compatibility != nil {
		t.Fatalf("plain: %+v", plain)
	}
	if reasoningField("https://openrouter.ai/api/v1") != "reasoning" || reasoningField("http://localhost:8000/v1") != "reasoning_content" {
		t.Fatal("reasoning field by host")
	}
}

func TestCustomProviderNeverGetsABuiltinID(t *testing.T) {
	home := t.TempDir()
	t.Setenv("XDG_CACHE_HOME", t.TempDir())
	m := NewManager(home, home, "")

	// a new one named like a built-in provider gets its own id
	res := doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers", `{"name":"OpenRouter","base_url":"https://openrouter.ai/api/v1","models":[{"id":"a/b"}]}`)
	if id := res["provider"].(map[string]interface{})["id"]; id != "openrouter-custom" {
		t.Fatalf("new provider id: %v", id)
	}
	res = doJSON(t, m, http.MethodPost, "/api/opencode/custom-providers", `{"name":"OpenRouter","base_url":"https://openrouter.ai/api/v1","models":[{"id":"a/b"}]}`)
	if id := res["provider"].(map[string]interface{})["id"]; id != "openrouter-custom-2" {
		t.Fatalf("second provider id: %v", id)
	}

	// one saved with a built-in id before the check is renamed when the config is written
	if err := writePrivate(m.providersPath(), []CustomProvider{{ID: "openai", Name: "Mine", Kind: KindOpenAI, BaseURL: "https://x/v1", Models: []CustomModel{{ID: "m"}}}}); err != nil {
		t.Fatal(err)
	}
	if err := m.writeConfig(); err != nil {
		t.Fatal(err)
	}
	list, _ := m.loadProviders()
	if len(list) != 1 || list[0].ID != "openai-custom" {
		t.Fatalf("saved provider not renamed: %+v", list)
	}
	data, _ := os.ReadFile(m.configPath())
	var cfg struct {
		Providers map[string]json.RawMessage `json:"providers"`
	}
	json.Unmarshal(data, &cfg)
	if _, ok := cfg.Providers["openai-custom"]; !ok {
		t.Fatalf("config providers: %v", cfg.Providers)
	}
	if _, ok := cfg.Providers["openai"]; ok {
		t.Fatal("the built-in id is still in the config")
	}
}
