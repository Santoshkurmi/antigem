package opencode

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Custom providers: endpoints the user adds by URL (any OpenAI-compatible API — Ollama, LM Studio, vLLM, LiteLLM,
// Groq, a company gateway… — or an Anthropic-compatible one). OpenCode's ~240 built-in providers need no entry here:
// the app connects those with a key through OpenCode's own integration API.

const (
	KindOpenAI    = "openai"    // POST {base}/chat/completions
	KindAnthropic = "anthropic" // POST {base}/messages
)

var providerIDRe = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,39}$`)

type CustomModel struct {
	ID            string `json:"id"`
	Name          string `json:"name,omitempty"`
	ContextLength int64  `json:"context_length,omitempty"`
	MaxOutput     int64  `json:"max_output_tokens,omitempty"`
	// Reasoning: the model streams its thinking (OpenAI-compatible "reasoning_content").
	Reasoning bool `json:"reasoning,omitempty"`
	// NoTools: the model cannot call tools (OpenCode then only chats with it).
	NoTools bool `json:"no_tools,omitempty"`
	// Images: the model reads images.
	Images bool `json:"images,omitempty"`
}

type CustomProvider struct {
	ID      string            `json:"id"`
	Name    string            `json:"name"`
	Kind    string            `json:"kind"`
	BaseURL string            `json:"base_url"`
	APIKey  string            `json:"api_key,omitempty"`
	Headers map[string]string `json:"headers,omitempty"`
	Models  []CustomModel     `json:"models"`
}

// publicProvider is what the app gets back: never the key, only whether one is set and its last characters.
type publicProvider struct {
	CustomProvider
	APIKey     string `json:"api_key,omitempty"`
	HasAPIKey  bool   `json:"has_api_key"`
	APIKeyHint string `json:"api_key_hint,omitempty"`
}

func (m *Manager) providersPath() string { return filepath.Join(m.dataDir(), "custom_providers.json") }

func (m *Manager) loadProviders() ([]CustomProvider, error) {
	data, err := os.ReadFile(m.providersPath())
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var list []CustomProvider
	if err := json.Unmarshal(data, &list); err != nil {
		return nil, fmt.Errorf("custom_providers.json: %w", err)
	}
	return list, nil
}

func writePrivate(path string, v interface{}) error {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (m *Manager) saveProviders(list []CustomProvider) error {
	sort.SliceStable(list, func(i, j int) bool { return strings.ToLower(list[i].Name) < strings.ToLower(list[j].Name) })
	if err := writePrivate(m.providersPath(), list); err != nil {
		return err
	}
	return m.writeConfig()
}

// writeConfig writes the OpenCode config the server is started with (OPENCODE_CONFIG): the custom providers in
// OpenCode's v2 "providers" format.
func (m *Manager) writeConfig() error {
	m.provMu.Lock()
	defer m.provMu.Unlock()
	list, err := m.loadProviders()
	if err != nil {
		return err
	}
	// OpenCode drops a config provider whose id is one it has built in; one saved before the bridge checked gets a free id
	if renameBuiltinIDs(list, m.builtinProviderIDs()) {
		if err := writePrivate(m.providersPath(), list); err != nil {
			return err
		}
	}
	providers := map[string]interface{}{}
	for _, p := range list {
		pkg := "@opencode/ai/providers/openai-compatible"
		if p.Kind == KindAnthropic {
			pkg = "@opencode/ai/providers/anthropic-compatible"
		}
		settings := map[string]interface{}{"baseURL": strings.TrimRight(p.BaseURL, "/")}
		if p.APIKey != "" {
			settings["apiKey"] = p.APIKey
		} else {
			// local servers (Ollama, LM Studio) take no key, but the SDK wants one
			settings["apiKey"] = "none"
		}
		models := map[string]interface{}{}
		for _, md := range p.Models {
			entry := map[string]interface{}{}
			if md.Name != "" {
				entry["name"] = md.Name
			}
			limit := map[string]interface{}{}
			if md.ContextLength > 0 {
				limit["context"] = md.ContextLength
			}
			if md.MaxOutput > 0 {
				limit["output"] = md.MaxOutput
			}
			if len(limit) > 0 {
				entry["limit"] = limit
			}
			input := []string{"text"}
			if md.Images {
				input = append(input, "image")
			}
			// OpenCode v2 has no "reasoning" capability: one model with it makes OpenCode drop the whole provider. A
			// reasoning model is told by the field its thinking streams in instead (an Anthropic one sends thinking blocks).
			entry["capabilities"] = map[string]interface{}{"tools": !md.NoTools, "input": input, "output": []string{"text"}}
			if md.Reasoning && p.Kind != KindAnthropic {
				entry["compatibility"] = map[string]interface{}{"reasoningField": reasoningField(p.BaseURL)}
			}
			models[md.ID] = entry
		}
		entry := map[string]interface{}{
			"name":     p.Name,
			"package":  pkg,
			"settings": settings,
			"models":   models,
		}
		if len(p.Headers) > 0 {
			entry["headers"] = p.Headers
		}
		providers[p.ID] = entry
	}
	// OpenCode's built-in LM Studio plugin polls http://127.0.0.1:1234/api/v1/models every 30 s, which is the
	// bridge's own port (logged as a 401 each time). An empty baseURL gives it no endpoint, so it never probes.
	// Declaring it also makes OpenCode list LM Studio's catalog models, which cannot work here, so it is disabled too
	// (disabled_providers alone hides the models but does not stop the probe).
	_, lmstudioTaken := providers["lmstudio"]
	if !lmstudioTaken {
		providers["lmstudio"] = map[string]interface{}{"settings": map[string]interface{}{"baseURL": ""}}
	}
	cfg := map[string]interface{}{"$schema": "https://opencode.ai/config.json", "providers": providers}
	if !lmstudioTaken {
		cfg["disabled_providers"] = []string{"lmstudio"}
	}
	if servers, err := m.loadMcp(); err == nil && len(servers) > 0 {
		cfg["mcp"] = map[string]interface{}{"servers": servers}
	}
	// skills and MCP servers turned off in the app: OpenCode's policies take a denied one out of every chat
	var policies []map[string]string
	for _, kind := range []string{"skill", "mcp"} {
		for _, id := range m.loadTurnedOff(kind) {
			policies = append(policies, map[string]string{"action": kind + ".use", "resource": id, "effect": "deny"})
		}
	}
	if len(policies) > 0 {
		cfg["policies"] = policies
	}
	return writePrivate(m.configPath(), cfg)
}

// Skills and MCP servers turned off in the app, kept in the bridge's config like the custom providers. OpenCode's
// skill id is the skill's folder name; an MCP server is its name (the user's own config's servers too).

func (m *Manager) turnedOffPath(kind string) string {
	if kind == "mcp" {
		return filepath.Join(m.dataDir(), "disabled_mcp.json")
	}
	return filepath.Join(m.dataDir(), "disabled_skills.json")
}

// loadTurnedOff reads the turned-off ids of [kind] (skill | mcp), sorted (the caller holds provMu).
func (m *Manager) loadTurnedOff(kind string) []string {
	data, err := os.ReadFile(m.turnedOffPath(kind))
	if err != nil {
		return nil
	}
	var ids []string
	if json.Unmarshal(data, &ids) != nil {
		return nil
	}
	sort.Strings(ids)
	return ids
}

func (m *Manager) turnedOff(kind string) map[string]bool {
	m.provMu.Lock()
	defer m.provMu.Unlock()
	out := map[string]bool{}
	for _, id := range m.loadTurnedOff(kind) {
		out[id] = true
	}
	return out
}

// setTurnedOff turns [id] of [kind] off or on for every OpenCode chat; a running server reloads its config.
func (m *Manager) setTurnedOff(ctx context.Context, kind, id string, off bool) error {
	m.provMu.Lock()
	set := map[string]bool{}
	for _, s := range m.loadTurnedOff(kind) {
		set[s] = true
	}
	if set[id] == off {
		m.provMu.Unlock()
		return nil
	}
	if off {
		set[id] = true
	} else {
		delete(set, id)
	}
	ids := make([]string, 0, len(set))
	for s := range set {
		ids = append(ids, s)
	}
	sort.Strings(ids)
	err := writePrivate(m.turnedOffPath(kind), ids)
	m.provMu.Unlock()
	if err != nil {
		return err
	}
	if err := m.writeConfig(); err != nil {
		return err
	}
	m.reloadQuietly(ctx)
	return nil
}

// DisabledSkills is the set of skill ids turned off in the app.
func (m *Manager) DisabledSkills() map[string]bool { return m.turnedOff("skill") }

// SetSkillEnabled turns skill [id] off or on for every OpenCode chat.
func (m *Manager) SetSkillEnabled(ctx context.Context, id string, enabled bool) error {
	return m.setTurnedOff(ctx, "skill", id, !enabled)
}

// reasoningField is where an OpenAI-compatible endpoint streams a model's thinking: OpenRouter sends "reasoning",
// most others (DeepSeek, Qwen, vLLM, LiteLLM…) "reasoning_content".
func reasoningField(baseURL string) string {
	if u, err := url.Parse(baseURL); err == nil && strings.HasSuffix(strings.ToLower(u.Hostname()), "openrouter.ai") {
		return "reasoning"
	}
	return "reasoning_content"
}

// builtinProviderIDs: the providers OpenCode has itself (the models.dev catalog's, and these if the catalog is not
// there yet). A custom provider must not use one of their ids: OpenCode then drops it, so its models never show.
func (m *Manager) builtinProviderIDs() map[string]bool {
	ids := map[string]bool{}
	for _, id := range []string{
		"opencode", "openrouter", "openai", "anthropic", "google", "google-vertex", "azure", "amazon-bedrock", "github-copilot",
		"groq", "mistral", "deepseek", "xai", "togetherai", "fireworks-ai", "cerebras", "deepinfra", "huggingface", "ollama",
		"lmstudio", "vercel", "zai", "moonshotai", "alibaba", "perplexity", "cohere", "nvidia", "gitlab",
	} {
		ids[id] = true
	}
	if cat := m.cachedCatalog(); cat != nil {
		for id := range cat.providers {
			ids[id] = true
		}
	}
	return ids
}

// freeProviderID: base, or base-2, base-3… when that is taken or built in.
func freeProviderID(base string, taken, builtin map[string]bool) string {
	id := base
	for n := 2; taken[id] || builtin[id]; n++ {
		id = fmt.Sprintf("%s-%d", base, n)
	}
	return id
}

// renameBuiltinIDs gives each provider with a built-in id its own ("openrouter" → "openrouter-custom"); whether any changed.
func renameBuiltinIDs(list []CustomProvider, builtin map[string]bool) bool {
	taken := map[string]bool{}
	for _, p := range list {
		taken[p.ID] = true
	}
	changed := false
	for i := range list {
		if builtin[list[i].ID] {
			id := freeProviderID(list[i].ID+"-custom", taken, builtin)
			taken[id] = true
			list[i].ID = id
			changed = true
		}
	}
	return changed
}

func keyHint(k string) string {
	if len(k) <= 4 {
		return ""
	}
	return "…" + k[len(k)-4:]
}

func toPublic(p CustomProvider) publicProvider {
	return publicProvider{CustomProvider: p, HasAPIKey: p.APIKey != "", APIKeyHint: keyHint(p.APIKey)}
}

func slug(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	var b strings.Builder
	dash := false
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9':
			b.WriteRune(r)
			dash = false
		case !dash && b.Len() > 0:
			b.WriteByte('-')
			dash = true
		}
	}
	out := strings.Trim(b.String(), "-")
	if len(out) > 32 {
		out = out[:32]
	}
	return out
}

func validate(p *CustomProvider) error {
	p.Name = strings.TrimSpace(p.Name)
	p.BaseURL = strings.TrimRight(strings.TrimSpace(p.BaseURL), "/")
	if p.Kind == "" {
		p.Kind = KindOpenAI
	}
	if p.Kind != KindOpenAI && p.Kind != KindAnthropic {
		return errors.New(`kind must be "openai" or "anthropic"`)
	}
	if p.Name == "" {
		return errors.New("a name is required")
	}
	if !strings.HasPrefix(p.BaseURL, "http://") && !strings.HasPrefix(p.BaseURL, "https://") {
		return errors.New("the base URL must start with http:// or https://")
	}
	seen := map[string]bool{}
	models := p.Models[:0]
	for _, md := range p.Models {
		md.ID = strings.TrimSpace(md.ID)
		md.Name = strings.TrimSpace(md.Name)
		if md.ID == "" || seen[md.ID] {
			continue
		}
		seen[md.ID] = true
		models = append(models, md)
	}
	p.Models = models
	if len(p.Models) == 0 {
		return errors.New("add at least one model")
	}
	return nil
}

func (m *Manager) handleCustomProviders(w http.ResponseWriter, r *http.Request, id string) {
	m.provMu.Lock()
	list, err := m.loadProviders()
	m.provMu.Unlock()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	idx := -1
	for i, p := range list {
		if p.ID == id {
			idx = i
		}
	}
	switch {
	case id == "" && r.Method == http.MethodGet:
		out := make([]publicProvider, 0, len(list))
		for _, p := range list {
			out = append(out, toPublic(p))
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "providers": out, "config_path": m.configPath()})
		return
	case (id == "" && r.Method == http.MethodPost) || (id != "" && r.Method == http.MethodPut):
		var p CustomProvider
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&p); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid JSON: " + err.Error()})
			return
		}
		if err := validate(&p); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		if id != "" {
			if idx < 0 {
				writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "no such provider"})
				return
			}
			p.ID = id
			// a blank key keeps the saved one ("-" clears it)
			switch strings.TrimSpace(p.APIKey) {
			case "":
				p.APIKey = list[idx].APIKey
			case "-":
				p.APIKey = ""
			}
			list[idx] = p
		} else {
			if p.ID == "" {
				p.ID = slug(p.Name)
			}
			if !providerIDRe.MatchString(p.ID) {
				writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "the id may only use a-z, 0-9, - and _"})
				return
			}
			taken := map[string]bool{}
			for _, q := range list {
				taken[q.ID] = true
			}
			builtin := m.builtinProviderIDs()
			if builtin[p.ID] {
				p.ID += "-custom"
			}
			p.ID = freeProviderID(p.ID, taken, builtin)
			list = append(list, p)
		}
		if err := m.saveProviders(list); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		m.reloadQuietly(r.Context())
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "provider": toPublic(p)})
		return
	case id != "" && r.Method == http.MethodDelete:
		if idx < 0 {
			writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "no such provider"})
			return
		}
		list = append(list[:idx], list[idx+1:]...)
		if err := m.saveProviders(list); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		m.reloadQuietly(r.Context())
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
		return
	}
	writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "method not allowed"})
}

// reloadQuietly makes a running server pick up a changed config. A server that is not running reads it at start.
func (m *Manager) reloadQuietly(ctx context.Context) {
	if !m.Running() {
		return
	}
	cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	_ = m.Call(cctx, http.MethodPost, "/api/location/reload", nil, nil)
}

type fetchModelsRequest struct {
	Kind    string `json:"kind"`
	BaseURL string `json:"base_url"`
	APIKey  string `json:"api_key"`
	// ID: use the saved key of this provider when APIKey is blank (editing a provider).
	ID string `json:"id"`
}

// fetchedModel is a listed model with what it can do filled in; Detected names where that came from
// ("endpoint", "ollama", "models.dev", joined by " + "), empty when nothing is known.
type fetchedModel struct {
	CustomModel
	Detected string `json:"detected,omitempty"`
}

// handleFetchModels lists an endpoint's models (GET {base}/models, the OpenAI and Anthropic shape both have data[].id)
// and what each can do (capabilities.go).
func (m *Manager) handleFetchModels(w http.ResponseWriter, r *http.Request) {
	var req fetchModelsRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid JSON"})
		return
	}
	base := strings.TrimRight(strings.TrimSpace(req.BaseURL), "/")
	if !strings.HasPrefix(base, "http://") && !strings.HasPrefix(base, "https://") {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "the base URL must start with http:// or https://"})
		return
	}
	key := strings.TrimSpace(req.APIKey)
	if key == "" && req.ID != "" {
		m.provMu.Lock()
		list, _ := m.loadProviders()
		m.provMu.Unlock()
		for _, p := range list {
			if p.ID == req.ID {
				key = p.APIKey
			}
		}
	}
	// the catalog loads while the endpoint answers (downloaded once a day, else read from disk)
	catalogReady := make(chan *modelCatalog, 1)
	go func() { catalogReady <- m.loadCatalog(r.Context()) }()
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	hreq, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/models", nil)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	if key != "" {
		if req.Kind == KindAnthropic {
			hreq.Header.Set("x-api-key", key)
			hreq.Header.Set("anthropic-version", "2023-06-01")
		} else {
			hreq.Header.Set("Authorization", "Bearer "+key)
		}
	}
	resp, err := http.DefaultClient.Do(hreq)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if resp.StatusCode != http.StatusOK {
		msg := strings.TrimSpace(string(data))
		if len(msg) > 200 {
			msg = msg[:200]
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": fmt.Sprintf("HTTP %d from %s/models %s", resp.StatusCode, base, msg)})
		return
	}
	var parsed struct {
		Data []map[string]interface{} `json:"data"`
		// Ollama native /api/tags style
		Models []struct {
			Name  string `json:"name"`
			Model string `json:"model"`
		} `json:"models"`
	}
	if err := json.Unmarshal(data, &parsed); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "the endpoint's model list is not JSON"})
		return
	}

	type found struct {
		md   CustomModel
		caps caps
		from []string
	}
	var list []*found
	for _, d := range parsed.Data {
		id := strings.TrimSpace(strOf(d["id"]))
		if id == "" {
			continue
		}
		name := strOf(d["display_name"])
		if name == "" {
			name = strOf(d["name"])
		}
		f := &found{md: CustomModel{ID: id, Name: name}}
		if f.caps.fill(endpointCaps(d)) {
			f.from = append(f.from, "endpoint")
		}
		list = append(list, f)
	}
	for _, d := range parsed.Models {
		id := d.Model
		if id == "" {
			id = d.Name
		}
		if id != "" {
			list = append(list, &found{md: CustomModel{ID: id, Name: d.Name}})
		}
	}

	// Ollama says per model what it can do
	ollama := req.Kind != KindAnthropic && looksLikeOllama(base, parsed.Data, len(parsed.Models) > 0)
	if ollama && len(list) > 0 {
		ids := make([]string, 0, len(list))
		for _, f := range list {
			ids = append(ids, f.md.ID)
		}
		octx, ocancel := context.WithTimeout(r.Context(), 15*time.Second)
		shown := ollamaCaps(octx, base, key, ids)
		ocancel()
		for _, f := range list {
			if c, ok := shown[f.md.ID]; ok && f.caps.fill(c) {
				f.from = append(f.from, "ollama")
			}
		}
	}

	// the rest from the catalog, by model id
	if cat := <-catalogReady; cat != nil {
		for _, f := range list {
			c, ok := cat.lookup(f.md.ID)
			if !ok {
				continue
			}
			if ollama {
				// Ollama runs a model with a smaller context than its maximum
				c.Context, c.Output = 0, 0
			}
			if f.caps.fill(c) {
				f.from = append(f.from, "models.dev")
			}
		}
	}

	out := make([]fetchedModel, 0, len(list))
	for _, f := range list {
		f.caps.apply(&f.md)
		out = append(out, fetchedModel{CustomModel: f.md, Detected: strings.Join(f.from, " + ")})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "models": out})
}

// ---------------------------------------------------------------- MCP servers added from the app

// MCP servers the app adds (the Jarvis server, …) live in the bridge's config like the custom providers: OpenCode's own
// "add MCP server" API keeps them only until it restarts.

func (m *Manager) mcpPath() string { return filepath.Join(m.dataDir(), "mcp_servers.json") }

func (m *Manager) loadMcp() (map[string]json.RawMessage, error) {
	data, err := os.ReadFile(m.mcpPath())
	if errors.Is(err, os.ErrNotExist) {
		return map[string]json.RawMessage{}, nil
	}
	if err != nil {
		return nil, err
	}
	out := map[string]json.RawMessage{}
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("mcp_servers.json: %w", err)
	}
	return out, nil
}

var mcpNameRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)

// handleMcp: GET /api/opencode/mcp (the saved servers, OpenCode's live status and the "off" names), PUT
// /api/opencode/mcp/{name} {"type":"remote","url":…,"headers":{…}} or {"type":"local","command":[…]}, PATCH
// /api/opencode/mcp/{name} {"enabled":bool} (any server, the user's own config's too), DELETE /api/opencode/mcp/{name}.
func (m *Manager) handleMcp(w http.ResponseWriter, r *http.Request, name string) {
	m.provMu.Lock()
	servers, err := m.loadMcp()
	m.provMu.Unlock()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	switch {
	case name == "" && r.Method == http.MethodGet:
		off := []string{}
		for n := range m.turnedOff("mcp") {
			off = append(off, n)
		}
		sort.Strings(off)
		// a turned-off server is left out of OpenCode's live list: "off" tells the app it is there, but off
		resp := map[string]interface{}{"success": true, "servers": servers, "off": off}
		if m.Running() {
			var live json.RawMessage
			ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
			defer cancel()
			if err := m.Call(ctx, http.MethodGet, "/api/mcp", nil, &live); err == nil {
				resp["status"] = live
			}
		}
		writeJSON(w, http.StatusOK, resp)
		return
	case name != "" && r.Method == http.MethodPatch:
		var body struct {
			Enabled bool `json:"enabled"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid JSON"})
			return
		}
		if err := m.setTurnedOff(r.Context(), "mcp", name, !body.Enabled); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
		return
	case name != "" && r.Method == http.MethodPut:
		if !mcpNameRe.MatchString(name) {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "invalid server name"})
			return
		}
		var cfg map[string]interface{}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&cfg); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid JSON"})
			return
		}
		switch cfg["type"] {
		case "remote":
			if u, _ := cfg["url"].(string); !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
				writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "a remote server needs an http(s) url"})
				return
			}
		case "local":
			if c, _ := cfg["command"].([]interface{}); len(c) == 0 {
				writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "a local server needs a command"})
				return
			}
		default:
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": `type must be "remote" or "local"`})
			return
		}
		raw, _ := json.Marshal(cfg)
		servers[name] = raw
	case name != "" && r.Method == http.MethodDelete:
		delete(servers, name)
		// a removed server leaves no "off" behind
		if err := m.setTurnedOff(r.Context(), "mcp", name, false); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "method not allowed"})
		return
	}
	m.provMu.Lock()
	err = writePrivate(m.mcpPath(), servers)
	m.provMu.Unlock()
	if err == nil {
		err = m.writeConfig()
	}
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	m.reloadQuietly(r.Context())
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}
