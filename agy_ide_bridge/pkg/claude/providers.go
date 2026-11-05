package claude

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
	"strconv"
	"strings"
	"time"
)

// Custom providers: Anthropic-compatible APIs (OpenRouter, Kimi, …) a chat can run on instead of the user's Claude
// login. A chat's model is then "@<provider id>/<model id>"; the bridge starts that chat's process with the provider's
// endpoint and key in a --settings file, so ~/.claude/settings.json and the other chats are never touched.

// ProviderModel is one model of a provider.
type ProviderModel struct {
	ID   string `json:"id"`
	Name string `json:"name,omitempty"`
	// ContextLength is the model's context window in tokens (0 = unknown, the CLI's default is used).
	ContextLength int64 `json:"context_length,omitempty"`
	// MaxOutput is the model's maximum output tokens (0 = unknown).
	MaxOutput int64 `json:"max_output_tokens,omitempty"`
	// ThinkingMode overrides the provider's thinking setting for this model: "" = inherit, "off" = never send a
	// budget, "budget" = send ThinkingBudget.
	ThinkingMode   string `json:"thinking_mode,omitempty"`
	ThinkingBudget int    `json:"thinking_budget,omitempty"`
	// CanThink is set when the provider's model list says the model supports reasoning.
	CanThink bool `json:"can_think,omitempty"`
}

// Auth modes: how the provider's key is sent.
const (
	authBearer  = "bearer"    // Authorization: Bearer <key> (ANTHROPIC_AUTH_TOKEN)
	authXAPIKey = "x-api-key" // x-api-key: <key> (ANTHROPIC_API_KEY)
)

// Provider is an Anthropic-compatible endpoint.
type Provider struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	BaseURL string `json:"base_url"`
	APIKey  string `json:"api_key,omitempty"`
	// ModelsURL lists the provider's models (OpenRouter's is known, others are set by the user).
	ModelsURL string `json:"models_url,omitempty"`
	// SmallModel serves Claude Code's background calls (titles, summaries); empty = the chat's own model.
	SmallModel string `json:"small_model,omitempty"`
	// AuthMode is "bearer" (default) or "x-api-key".
	AuthMode string `json:"auth_mode,omitempty"`
	// Headers are extra request headers (ANTHROPIC_CUSTOM_HEADERS); ExtraEnv is extra environment of the chat's process.
	Headers  map[string]string `json:"headers,omitempty"`
	ExtraEnv map[string]string `json:"extra_env,omitempty"`
	// TimeoutSec is the request timeout (API_TIMEOUT_MS); 0 = the CLI's default.
	TimeoutSec int `json:"timeout_sec,omitempty"`
	// ThinkingBudget is the thinking token budget sent when Thinking is on; 0 = never send one (many providers reject it).
	ThinkingBudget int `json:"thinking_budget,omitempty"`
	// MaxRetries is how often the CLI retries a failed request (rate limit, bad key, network): 0 = the app's default
	// (3), -1 = never, otherwise 1..10. The CLI's own default of 10 retries with growing waits keeps a chat on a
	// rate-limited provider "loading" for minutes.
	MaxRetries int `json:"max_retries,omitempty"`
	// DefaultContext / DefaultMaxOutput apply to models that have no size of their own.
	DefaultContext   int64 `json:"default_context,omitempty"`
	DefaultMaxOutput int64 `json:"default_max_output,omitempty"`
	// DisableBetas stops the CLI from sending experimental beta headers/fields, which strict gateways reject.
	// ModelsMap tells the model-list fetch how to read this provider's answer and pages (nil = detect everything).
	ModelsMap    *ModelsMapping  `json:"models_map,omitempty"`
	DisableBetas bool            `json:"disable_betas,omitempty"`
	Models       []ProviderModel `json:"models"`
}

const defaultProviderRetries = 3

// retries is the number of retries the CLI gets for this provider.
func (p Provider) retries() int {
	switch {
	case p.MaxRetries < 0:
		return 0
	case p.MaxRetries == 0:
		return defaultProviderRetries
	}
	return min(p.MaxRetries, 10)
}

// ModelLimits are the settings a chat on one model runs with: the model's own value, else the provider's default.
type ModelLimits struct {
	Context        int64
	MaxOutput      int64
	ThinkingBudget int // 0 = never send a thinking budget
}

// Limits resolves the settings of model id.
func (p Provider) Limits(id string) ModelLimits {
	m := p.ModelInfo(id)
	l := ModelLimits{Context: m.ContextLength, MaxOutput: m.MaxOutput, ThinkingBudget: p.ThinkingBudget}
	if l.Context <= 0 {
		l.Context = p.DefaultContext
	}
	if l.MaxOutput <= 0 {
		l.MaxOutput = p.DefaultMaxOutput
	}
	switch m.ThinkingMode {
	case "off":
		l.ThinkingBudget = 0
	case "budget":
		l.ThinkingBudget = m.ThinkingBudget
	}
	return l
}

// ModelInfo is the saved entry of model id (zero value when unknown).
func (p Provider) ModelInfo(id string) ProviderModel {
	for _, m := range p.Models {
		if m.ID == id {
			return m
		}
	}
	return ProviderModel{ID: id}
}

type providersFile struct {
	Providers []Provider `json:"providers"`
	// Sessions maps a chat id to the custom model it runs ("@provider/model"); official chats are not listed.
	Sessions map[string]string `json:"sessions,omitempty"`
}

const openRouterModelsURL = "https://openrouter.ai/api/v1/models"

var providerIDRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,39}$`)

func (m *Manager) providersPath() string {
	return filepath.Join(m.HomeDir, ".claude", "agy_providers.json")
}

func (m *Manager) loadProvidersLocked() providersFile {
	var f providersFile
	if raw, err := os.ReadFile(m.providersPath()); err == nil {
		_ = json.Unmarshal(raw, &f)
	}
	if f.Sessions == nil {
		f.Sessions = map[string]string{}
	}
	return f
}

func (m *Manager) saveProvidersLocked(f providersFile) error {
	return writeJSONFile(m.providersPath(), f)
}

// ParseModelRef splits "@provider/model" into its parts; ok is false for an official Claude model.
func ParseModelRef(ref string) (providerID, model string, ok bool) {
	if !strings.HasPrefix(ref, "@") {
		return "", "", false
	}
	providerID, model, ok = strings.Cut(ref[1:], "/")
	return providerID, model, ok && providerID != "" && model != ""
}

func isOpenRouter(baseURL string) bool {
	u, err := url.Parse(baseURL)
	return err == nil && (u.Host == "openrouter.ai" || strings.HasSuffix(u.Host, ".openrouter.ai"))
}

func (m *Manager) provider(id string) (Provider, bool) {
	m.provMu.Lock()
	defer m.provMu.Unlock()
	for _, p := range m.loadProvidersLocked().Providers {
		if p.ID == id {
			return p, true
		}
	}
	return Provider{}, false
}

// providerSettingsFile writes the --settings file of provider p (endpoint, key, models, limits) and returns its path.
// Every model alias is pinned to the provider's model, so subagents, plan mode and aliases never ask for a Claude
// model the provider lacks; the same keys also override any ANTHROPIC_* a user's settings.json would add.
func (m *Manager) providerSettingsFile(p Provider, model string) (string, error) {
	small := p.SmallModel
	if small == "" {
		small = model
	}
	env := map[string]string{
		"ANTHROPIC_BASE_URL":             p.BaseURL,
		"ANTHROPIC_MODEL":                model,
		"ANTHROPIC_DEFAULT_SONNET_MODEL": model,
		"ANTHROPIC_DEFAULT_OPUS_MODEL":   model,
		"ANTHROPIC_DEFAULT_HAIKU_MODEL":  small,
		"ANTHROPIC_SMALL_FAST_MODEL":     small,
		"CLAUDE_CODE_SUBAGENT_MODEL":     model,
		// telemetry / update checks are pointless (and may fail) on a third-party endpoint
		"CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
	}
	// the key goes into exactly one variable; the other is emptied so an environment value cannot win
	if p.AuthMode == authXAPIKey {
		env["ANTHROPIC_API_KEY"] = p.APIKey
		env["ANTHROPIC_AUTH_TOKEN"] = ""
	} else {
		env["ANTHROPIC_AUTH_TOKEN"] = p.APIKey
		env["ANTHROPIC_API_KEY"] = ""
	}
	limits := p.Limits(model)
	if limits.Context > 0 {
		// the CLI assumes a window of its own for unknown models; this makes auto-compact follow the real one
		env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"] = strconv.FormatInt(limits.Context, 10)
	}
	if limits.MaxOutput > 0 {
		env["CLAUDE_CODE_MAX_OUTPUT_TOKENS"] = strconv.FormatInt(limits.MaxOutput, 10)
	}
	env["CLAUDE_CODE_MAX_RETRIES"] = strconv.Itoa(p.retries())
	if p.TimeoutSec > 0 {
		env["API_TIMEOUT_MS"] = strconv.Itoa(p.TimeoutSec * 1000)
	}
	if p.DisableBetas {
		env["CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"] = "1"
	}
	if len(p.Headers) > 0 {
		names := make([]string, 0, len(p.Headers))
		for k := range p.Headers {
			names = append(names, k)
		}
		sort.Strings(names)
		lines := make([]string, 0, len(names))
		for _, k := range names {
			lines = append(lines, k+": "+p.Headers[k])
		}
		env["ANTHROPIC_CUSTOM_HEADERS"] = strings.Join(lines, "\n")
	}
	for k, v := range p.ExtraEnv {
		env[k] = v
	}
	path := filepath.Join(m.HomeDir, ".claude", "agy_providers", p.ID+".settings.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", err
	}
	data, _ := json.Marshal(map[string]interface{}{"env": env})
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

// SessionModelRef is the custom model chat id last ran ("" = an official model).
func (m *Manager) SessionModelRef(id string) string {
	m.provMu.Lock()
	defer m.provMu.Unlock()
	return m.loadProvidersLocked().Sessions[id]
}

// rememberSessionModel records the model a chat runs, so a reopened chat starts on the same provider.
func (m *Manager) rememberSessionModel(id, model string) {
	m.provMu.Lock()
	defer m.provMu.Unlock()
	f := m.loadProvidersLocked()
	_, _, custom := ParseModelRef(model)
	switch cur := f.Sessions[id]; {
	case custom && cur != model:
		f.Sessions[id] = model
	case !custom && cur != "":
		delete(f.Sessions, id)
	default:
		return
	}
	_ = m.saveProvidersLocked(f)
}

// copySessionModel gives a forked chat the provider of its source.
func (m *Manager) copySessionModel(from, to string) {
	if ref := m.SessionModelRef(from); ref != "" {
		m.rememberSessionModel(to, ref)
	}
}

// providerView is a provider as the app sees it: the key is never sent back.
type providerView struct {
	Provider
	APIKeyHint string `json:"api_key_hint,omitempty"`
	OpenRouter bool   `json:"openrouter"`
}

func viewOf(p Provider) providerView {
	v := providerView{Provider: p, OpenRouter: isOpenRouter(p.BaseURL)}
	if k := p.APIKey; k != "" {
		v.APIKeyHint = "…" + k[max(0, len(k)-4):]
	}
	v.APIKey = ""
	if v.Models == nil {
		v.Models = []ProviderModel{}
	}
	return v
}

// settingsEndpoint is a custom endpoint configured in ~/.claude/settings.json (it would apply to every chat).
func (m *Manager) settingsEndpoint() (map[string]interface{}, map[string]interface{}) {
	settings := map[string]interface{}{}
	raw, err := os.ReadFile(m.settingsPath())
	if err != nil || json.Unmarshal(raw, &settings) != nil {
		return nil, nil
	}
	env, _ := settings["env"].(map[string]interface{})
	if s, _ := env["ANTHROPIC_BASE_URL"].(string); strings.TrimSpace(s) == "" {
		return nil, nil
	}
	return settings, env
}

// formProvider cleans a provider sent by the editor form; an empty key falls back to the saved provider's key.
func (m *Manager) formProvider(p Provider) Provider {
	p.BaseURL = strings.TrimRight(strings.TrimSpace(p.BaseURL), "/")
	p.ModelsURL = strings.TrimSpace(p.ModelsURL)
	p.APIKey = strings.TrimSpace(p.APIKey)
	if saved, ok := m.provider(p.ID); ok && p.APIKey == "" {
		p.APIKey = saved.APIKey
	}
	return p
}

// settingsConflicts lists the ANTHROPIC_* keys of settings.json that a provider chat would otherwise inherit; the
// provider's --settings file overrides every one of them, so this is informational.
func (m *Manager) settingsConflicts() []string {
	var keys []string
	settings := map[string]interface{}{}
	raw, err := os.ReadFile(m.settingsPath())
	if err != nil || json.Unmarshal(raw, &settings) != nil {
		return nil
	}
	env, _ := settings["env"].(map[string]interface{})
	for k := range env {
		if strings.HasPrefix(k, "ANTHROPIC_") || strings.HasPrefix(k, "CLAUDE_CODE_") || k == "API_TIMEOUT_MS" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	return keys
}

// handleProviders routes /api/claude/providers[/...].
//
//	GET    /providers                        providers (keys masked) + "settings_endpoint" if settings.json has one
//	POST   /providers                        add {name, base_url, api_key, models_url, small_model, models}
//	PUT    /providers/{id}                   update (an empty api_key keeps the saved one)
//	DELETE /providers/{id}
//	POST   /providers/{id}/fetch-models      {"free_only": bool} the provider's model list
//	POST   /providers/{id}/restart-sessions  restart idle chats running on the provider (they re-read its settings)
//	POST   /providers/_test                  {…provider, model} one tiny request, like the CLI sends
//	POST   /providers/_fetch                 {…provider, free_only} the model list with the form's values
//	POST   /providers/import-settings        move the endpoint of settings.json into a provider
func (m *Manager) handleProviders(w http.ResponseWriter, r *http.Request, rest string) {
	id, action, _ := strings.Cut(rest, "/")
	switch {
	case id == "" && r.Method == http.MethodGet:
		m.provMu.Lock()
		f := m.loadProvidersLocked()
		m.provMu.Unlock()
		views := make([]providerView, 0, len(f.Providers))
		for _, p := range f.Providers {
			views = append(views, viewOf(p))
		}
		resp := map[string]interface{}{"success": true, "providers": views}
		if _, env := m.settingsEndpoint(); env != nil {
			resp["settings_endpoint"] = env["ANTHROPIC_BASE_URL"]
		}
		if keys := m.settingsConflicts(); len(keys) > 0 {
			resp["settings_conflicts"] = keys
		}
		writeJSON(w, http.StatusOK, resp)
	case id == "" && r.Method == http.MethodPost:
		var p Provider
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "provider object required"})
			return
		}
		m.saveProvider(w, "", p)
	case action == "restart-sessions" && r.Method == http.MethodPost:
		restarted, busy := m.restartProviderSessions(id)
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "restarted": restarted, "busy": busy})
	case id == "_test" && r.Method == http.MethodPost:
		// tests the form's values (saved or not); an empty key falls back to the saved one
		var body struct {
			Provider
			Model string `json:"model"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "provider object required"})
			return
		}
		p := m.formProvider(body.Provider)
		if u, err := url.Parse(p.BaseURL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "result": ProviderTestResult{Error: "enter an http(s) base URL first"}})
			return
		}
		if p.APIKey == "" {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "result": ProviderTestResult{Error: "enter the API key first"}})
			return
		}
		if err := normalizeProvider(&p); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "result": ProviderTestResult{Error: err.Error()}})
			return
		}
		model := strings.TrimSpace(body.Model)
		if model == "" && len(p.Models) > 0 {
			model = p.Models[0].ID
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "result": testProvider(r.Context(), p, model)})
	case id == "_fetch" && r.Method == http.MethodPost:
		// fetches the model list with the form's values (saved or not)
		var body struct {
			Provider
			FreeOnly bool `json:"free_only"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "provider object required"})
			return
		}
		p := m.formProvider(body.Provider)
		if err := normalizeProvider(&p); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		if p.APIKey == "" {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": "enter the API key first"})
			return
		}
		models, raw, err := fetchProviderModelsRaw(r.Context(), p, body.FreeOnly)
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error(), "raw": raw})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "models": models, "raw": raw})
	case id == "import-settings" && r.Method == http.MethodPost:
		m.importSettingsEndpoint(w)
	case action == "" && r.Method == http.MethodPut:
		var p Provider
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "provider object required"})
			return
		}
		m.saveProvider(w, id, p)
	case action == "" && r.Method == http.MethodDelete:
		m.provMu.Lock()
		f := m.loadProvidersLocked()
		kept := f.Providers[:0]
		for _, p := range f.Providers {
			if p.ID != id {
				kept = append(kept, p)
			}
		}
		f.Providers = kept
		err := m.saveProvidersLocked(f)
		m.provMu.Unlock()
		_ = os.Remove(filepath.Join(m.HomeDir, ".claude", "agy_providers", id+".settings.json"))
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case action == "fetch-models" && r.Method == http.MethodPost:
		var body struct {
			FreeOnly bool `json:"free_only"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		p, ok := m.provider(id)
		if !ok {
			writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown provider"})
			return
		}
		models, err := fetchProviderModels(r.Context(), p, body.FreeOnly)
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "models": models})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "unknown providers endpoint"})
	}
}

// saveProvider adds (id == "") or updates provider p and replies with it.
func (m *Manager) saveProvider(w http.ResponseWriter, id string, p Provider) {
	p.Name = strings.TrimSpace(p.Name)
	p.BaseURL = strings.TrimRight(strings.TrimSpace(p.BaseURL), "/")
	p.ModelsURL = strings.TrimSpace(p.ModelsURL)
	p.SmallModel = strings.TrimSpace(p.SmallModel)
	p.APIKey = strings.TrimSpace(p.APIKey)
	if err := normalizeProvider(&p); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	if u, err := url.Parse(p.BaseURL); p.Name == "" || err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "a name and an http(s) base URL are required"})
		return
	}
	models := make([]ProviderModel, 0, len(p.Models))
	seen := map[string]bool{}
	for _, md := range p.Models {
		md.ID = strings.TrimSpace(md.ID)
		md.ContextLength, md.MaxOutput = max(md.ContextLength, 0), max(md.MaxOutput, 0)
		switch md.ThinkingMode {
		case "off":
			md.ThinkingBudget = 0
		case "budget":
			if md.ThinkingBudget < 1024 {
				writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": fmt.Sprintf("%s: a thinking budget must be at least 1024 tokens", md.ID)})
				return
			}
		default:
			md.ThinkingMode, md.ThinkingBudget = "", 0
		}
		if strings.ContainsAny(md.ID, " \t\r\n") {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": fmt.Sprintf("model id %q must not contain spaces", md.ID)})
			return
		}
		if md.ID != "" && !seen[md.ID] {
			seen[md.ID] = true
			models = append(models, md)
		}
	}
	p.Models = models

	m.provMu.Lock()
	defer m.provMu.Unlock()
	f := m.loadProvidersLocked()
	idx := -1
	for i, old := range f.Providers {
		if old.ID == id {
			idx = i
		}
	}
	if id != "" && idx < 0 {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown provider"})
		return
	}
	if idx >= 0 {
		p.ID = id
		if p.APIKey == "" {
			p.APIKey = f.Providers[idx].APIKey
		}
		f.Providers[idx] = p
	} else {
		if p.APIKey == "" {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "an API key is required"})
			return
		}
		p.ID = uniqueProviderID(f.Providers, p.Name)
		f.Providers = append(f.Providers, p)
	}
	if err := m.saveProvidersLocked(f); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "provider": viewOf(p)})
}

var (
	headerNameRe = regexp.MustCompile(`^[A-Za-z0-9-]+$`)
	envNameRe    = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)
)

// normalizeProvider validates and cleans the optional fields of p.
func normalizeProvider(p *Provider) error {
	if p.AuthMode != authXAPIKey {
		p.AuthMode = authBearer
	}
	p.TimeoutSec, p.ThinkingBudget = max(p.TimeoutSec, 0), max(p.ThinkingBudget, 0)
	p.DefaultContext, p.DefaultMaxOutput = max(p.DefaultContext, 0), max(p.DefaultMaxOutput, 0)
	p.MaxRetries = max(min(p.MaxRetries, 10), -1)
	if mp := p.ModelsMap; mp != nil {
		for _, f := range []*string{&mp.ListPath, &mp.IDField, &mp.NameField, &mp.ContextField, &mp.MaxOutputField, &mp.StripPrefix,
			&mp.LimitParam, &mp.PageParam, &mp.NextField, &mp.MoreField} {
			*f = strings.TrimSpace(*f)
		}
		switch mp.Pagination {
		case "", "none", "cursor", "token", "next_url":
		default:
			return fmt.Errorf("unknown pagination mode %q", mp.Pagination)
		}
		mp.PageSize = max(mp.PageSize, 0)
		if *mp == (ModelsMapping{}) {
			p.ModelsMap = nil
		}
	}
	if p.ThinkingBudget > 0 && p.ThinkingBudget < 1024 {
		return errors.New("a thinking budget must be at least 1024 tokens (or 0 to never send one)")
	}
	headers := map[string]string{}
	for k, v := range p.Headers {
		k, v = strings.TrimSpace(k), strings.TrimSpace(v)
		if k == "" {
			continue
		}
		if !headerNameRe.MatchString(k) || strings.ContainsAny(v, "\r\n") {
			return fmt.Errorf("invalid header %q", k)
		}
		headers[k] = v
	}
	p.Headers = headers
	env := map[string]string{}
	for k, v := range p.ExtraEnv {
		k = strings.TrimSpace(k)
		if k == "" {
			continue
		}
		if !envNameRe.MatchString(k) {
			return fmt.Errorf("invalid environment variable name %q", k)
		}
		env[k] = v
	}
	p.ExtraEnv = env
	return nil
}

// ProviderTestResult is the answer of a connection test.
type ProviderTestResult struct {
	OK        bool   `json:"ok"`
	Status    int    `json:"status,omitempty"`
	LatencyMs int64  `json:"latency_ms,omitempty"`
	Model     string `json:"model,omitempty"`
	Reply     string `json:"reply,omitempty"`
	Error     string `json:"error,omitempty"`
	Hint      string `json:"hint,omitempty"`
	URL       string `json:"url,omitempty"`
}

// testProvider sends one tiny /v1/messages request exactly like the CLI would (same URL, auth mode and headers), so a
// wrong base URL, key, auth mode or model id shows up here instead of as a CLI error in the middle of a chat.
func testProvider(ctx context.Context, p Provider, model string) ProviderTestResult {
	res := ProviderTestResult{Model: model, URL: p.BaseURL + "/v1/messages"}
	if model == "" {
		res.Error = "add a model id first (the test sends one short message to it)"
		return res
	}
	timeout := 30 * time.Second
	if p.TimeoutSec > 0 {
		timeout = time.Duration(min(p.TimeoutSec, 120)) * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	payload, _ := json.Marshal(map[string]interface{}{
		"model": model, "max_tokens": 32,
		"messages": []map[string]string{{"role": "user", "content": "Reply with the single word: ok"}},
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, res.URL, strings.NewReader(string(payload)))
	if err != nil {
		res.Error = err.Error()
		return res
	}
	req.Header.Set("content-type", "application/json")
	req.Header.Set("anthropic-version", "2023-06-01")
	if p.AuthMode == authXAPIKey {
		req.Header.Set("x-api-key", p.APIKey)
	} else {
		req.Header.Set("Authorization", "Bearer "+p.APIKey)
	}
	for k, v := range p.Headers {
		req.Header.Set(k, v)
	}
	start := time.Now()
	resp, err := http.DefaultClient.Do(req)
	res.LatencyMs = time.Since(start).Milliseconds()
	if err != nil {
		res.Error = err.Error()
		res.Hint = "Cannot reach the server: check the base URL and your connection."
		return res
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	res.Status = resp.StatusCode
	var parsed struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
		Error json.RawMessage `json:"error"`
	}
	_ = json.Unmarshal(body, &parsed)
	if resp.StatusCode/100 == 2 {
		for _, c := range parsed.Content {
			if c.Text != "" {
				res.Reply = strings.TrimSpace(c.Text)
				break
			}
		}
		if len(parsed.Content) == 0 && len(parsed.Error) == 0 {
			res.Error = "HTTP 200 but not an Anthropic-style answer: " + strings.TrimSpace(string(body[:min(len(body), 160)]))
			res.Hint = "The base URL is probably not an Anthropic-compatible endpoint."
			return res
		}
		res.OK = true
		return res
	}
	msg := strings.TrimSpace(string(body[:min(len(body), 300)]))
	var obj struct {
		Message string `json:"message"`
	}
	if json.Unmarshal(parsed.Error, &obj) == nil && obj.Message != "" {
		msg = obj.Message
	} else if len(parsed.Error) > 0 {
		var s string
		if json.Unmarshal(parsed.Error, &s) == nil && s != "" {
			msg = s
		}
	}
	res.Error = fmt.Sprintf("HTTP %d: %s", resp.StatusCode, msg)
	switch resp.StatusCode {
	case 401, 403:
		other := "x-api-key"
		if p.AuthMode == authXAPIKey {
			other = "Bearer token"
		}
		res.Hint = "The key was refused. Check the key, or try the other auth mode (" + other + ")."
	case 404:
		res.Hint = "Not found: the CLI calls " + res.URL + ". Check the base URL (some providers need a /anthropic suffix) and the model id."
	case 400, 422:
		res.Hint = "The provider rejected the request: check the model id; if it names unsupported fields, enable \"Disable experimental betas\"."
	case 429:
		res.Hint = "Rate limited or out of credit."
	}
	return res
}

var nonSlugRe = regexp.MustCompile(`[^a-z0-9]+`)

func uniqueProviderID(existing []Provider, name string) string {
	base := strings.Trim(nonSlugRe.ReplaceAllString(strings.ToLower(name), "-"), "-")
	if len(base) > 30 {
		base = strings.Trim(base[:30], "-")
	}
	if base == "" {
		base = "provider"
	}
	taken := map[string]bool{}
	for _, p := range existing {
		taken[p.ID] = true
	}
	id := base
	for n := 2; taken[id] || !providerIDRe.MatchString(id); n++ {
		id = fmt.Sprintf("%s-%d", base, n)
	}
	return id
}

// importSettingsEndpoint turns the endpoint in settings.json into a provider and removes it from settings.json, so the
// CLI in a terminal (and official chats) use the Claude login again.
func (m *Manager) importSettingsEndpoint(w http.ResponseWriter) {
	settings, env := m.settingsEndpoint()
	if env == nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "settings.json has no custom endpoint"})
		return
	}
	str := func(k string) string { s, _ := env[k].(string); return strings.TrimSpace(s) }
	p := Provider{BaseURL: strings.TrimRight(str("ANTHROPIC_BASE_URL"), "/"), APIKey: str("ANTHROPIC_AUTH_TOKEN")}
	if p.APIKey == "" {
		p.APIKey = str("ANTHROPIC_API_KEY")
	}
	if u, err := url.Parse(p.BaseURL); err == nil && u.Host != "" {
		p.Name = u.Host
		if isOpenRouter(p.BaseURL) {
			p.Name = "OpenRouter"
		}
	} else {
		p.Name = "Imported"
	}
	model := str("ANTHROPIC_MODEL")
	if model == "" {
		if s, _ := settings["model"].(string); s != "" && !isClaudeAlias(s) {
			model = s
		}
	}
	if model != "" {
		p.Models = []ProviderModel{{ID: model}}
	}
	if small := str("ANTHROPIC_SMALL_FAST_MODEL"); small != "" && small != model {
		p.SmallModel = small
	}

	m.provMu.Lock()
	f := m.loadProvidersLocked()
	p.ID = uniqueProviderID(f.Providers, p.Name)
	f.Providers = append(f.Providers, p)
	err := m.saveProvidersLocked(f)
	m.provMu.Unlock()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	for _, k := range []string{"ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_MODEL",
		"ANTHROPIC_SMALL_FAST_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL"} {
		delete(env, k)
	}
	if len(env) == 0 {
		delete(settings, "env")
	}
	if s, _ := settings["model"].(string); model != "" && s == model {
		delete(settings, "model")
	}
	if err := writeJSONFile(m.settingsPath(), settings); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	m.invalidateCaches()
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "provider": viewOf(p)})
}

func isClaudeAlias(model string) bool {
	switch model {
	case "default", "opus", "sonnet", "haiku", "opusplan", "best":
		return true
	}
	return strings.HasPrefix(model, "claude-")
}

// fetchProviderModels reads the provider's model list (OpenAI / Anthropic style: {"data":[{"id",...}]}). freeOnly
// (OpenRouter) keeps the free models that can call tools, which Claude Code needs.
func fetchProviderModels(ctx context.Context, p Provider, freeOnly bool) ([]ProviderModel, error) {
	models, _, err := fetchProviderModelsRaw(ctx, p, freeOnly)
	return models, err
}

// FetchRaw is what a model fetch sent and received, so the user can tell a server problem from an app problem.
type FetchRaw struct {
	URL string `json:"url"`
	// RequestHeaders are the headers sent, with keys masked.
	RequestHeaders []string `json:"request_headers,omitempty"`
	Status         int      `json:"status,omitempty"`
	// Body is the response body (pretty-printed JSON when it is JSON, cut at 256 KB).
	Body      string `json:"body,omitempty"`
	Truncated bool   `json:"truncated,omitempty"`
	// Entries is how many models the server listed; Kept how many the app accepted (an entry without "id", or a
	// free-only filter, drops some).
	Entries int `json:"entries"`
	Kept    int `json:"kept"`
	// Pages is how many pages were read; Pagination the mode used ("none", "cursor", "token", "next_url").
	Pages      int    `json:"pages,omitempty"`
	Pagination string `json:"pagination,omitempty"`
	// PagingError says why reading the later pages stopped (the models of the earlier pages are kept).
	PagingError string `json:"paging_error,omitempty"`
	// Lists and TopKeys describe the shape of the first answer, for the Field mapping dialog.
	Lists   []RawList `json:"lists,omitempty"`
	TopKeys []RawKey  `json:"top_keys,omitempty"`
}

func maskKey(k string) string {
	if len(k) <= 4 {
		return "…"
	}
	return "…" + k[len(k)-4:]
}

// restartProviderSessions restarts the idle running chats of provider id, so they start again with its saved settings.
// Chats in the middle of a reply are left alone and counted as busy.
func (m *Manager) restartProviderSessions(id string) (restarted, busy int) {
	m.mu.Lock()
	all := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		all = append(all, s)
	}
	m.mu.Unlock()
	for _, s := range all {
		s.mu.Lock()
		pid, _, custom := ParseModelRef(s.opts.Model)
		live, working := s.cmd != nil, s.pendingTurns > 0
		s.mu.Unlock()
		if !custom || pid != id || !live {
			continue
		}
		if working {
			busy++
			continue
		}
		s.Restart()
		restarted++
	}
	return restarted, busy
}
