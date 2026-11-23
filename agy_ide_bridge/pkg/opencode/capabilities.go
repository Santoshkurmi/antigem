package opencode

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// What a custom endpoint's models can do (read images, reason, call tools) and how much they take. OpenCode's built-in
// providers get this from the models.dev catalog; a custom endpoint only has what the bridge writes into the config, so
// "Fetch from endpoint" fills it in from three places, the first that knows wins per field:
//
//  1. the endpoint's own /models answer (OpenRouter's architecture.input_modalities and supported_parameters, Mistral's
//     capabilities.vision, Fireworks' supports_image_input, LM Studio's type "vlm", vLLM's max_model_len…)
//  2. Ollama's /api/show for each model ("capabilities": ["vision", "tools", "thinking"])
//  3. the models.dev catalog, by model id (gateways such as LiteLLM or one-api serve well-known ids like gpt-4o)
//
// Anything still unknown stays off: the user turns it on per model in the app.

// caps is what one source knows about a model; nil / 0 means it does not say.
type caps struct {
	Images, Reasoning, Tools *bool
	Context, Output          int64
}

func (c caps) empty() bool {
	return c.Images == nil && c.Reasoning == nil && c.Tools == nil && c.Context == 0 && c.Output == 0
}

// fill takes the fields c does not know yet from o; whether o added a capability (images, reasoning, tools), which is
// what the app shows as detected. The limits are filled in too but do not count.
func (c *caps) fill(o caps) bool {
	added := false
	if c.Images == nil && o.Images != nil {
		c.Images, added = o.Images, true
	}
	if c.Reasoning == nil && o.Reasoning != nil {
		c.Reasoning, added = o.Reasoning, true
	}
	if c.Tools == nil && o.Tools != nil {
		c.Tools, added = o.Tools, true
	}
	if c.Context == 0 && o.Context > 0 {
		c.Context = o.Context
	}
	if c.Output == 0 && o.Output > 0 {
		c.Output = o.Output
	}
	return added
}

func (c caps) apply(md *CustomModel) {
	md.Images = c.Images != nil && *c.Images
	md.Reasoning = c.Reasoning != nil && *c.Reasoning
	md.NoTools = c.Tools != nil && !*c.Tools
	if md.ContextLength == 0 {
		md.ContextLength = c.Context
	}
	if md.MaxOutput == 0 {
		md.MaxOutput = c.Output
	}
}

func boolPtr(b bool) *bool { return &b }

// ---------------------------------------------------------------- 1. the endpoint's own model list

func jsonObj(v interface{}) map[string]interface{} {
	o, _ := v.(map[string]interface{})
	return o
}

func jsonBool(v interface{}) *bool {
	if b, ok := v.(bool); ok {
		return &b
	}
	return nil
}

func jsonInt(v interface{}) int64 {
	switch n := v.(type) {
	case float64:
		if n > 0 {
			return int64(n)
		}
	case string:
		var i int64
		if err := json.Unmarshal([]byte(n), &i); err == nil && i > 0 {
			return i
		}
	}
	return 0
}

// jsonStrings: a list of strings, lower-cased; ok is false when v is not a list.
func jsonStrings(v interface{}) (out []string, ok bool) {
	list, ok := v.([]interface{})
	if !ok {
		return nil, false
	}
	for _, it := range list {
		if s, isStr := it.(string); isStr {
			out = append(out, strings.ToLower(strings.TrimSpace(s)))
		}
	}
	return out, true
}

func hasAny(list []string, want ...string) bool {
	for _, s := range list {
		for _, w := range want {
			if s == w {
				return true
			}
		}
	}
	return false
}

func firstBool(o map[string]interface{}, keys ...string) *bool {
	for _, k := range keys {
		if b := jsonBool(o[k]); b != nil {
			return b
		}
	}
	return nil
}

func firstInt(o map[string]interface{}, keys ...string) int64 {
	for _, k := range keys {
		if n := jsonInt(o[k]); n > 0 {
			return n
		}
	}
	return 0
}

var (
	imageWords     = []string{"image", "images", "vision", "image_input"}
	reasoningWords = []string{"reasoning", "thinking", "include_reasoning", "reasoning_effort"}
	toolWords      = []string{"tools", "tool_use", "tool-use", "tool_calls", "tool_call", "tool-calling", "tool_calling", "function_calling", "function-calling"}
)

// modalitiesHaveImage reads a list of input modalities (["text","image"]) or a modality string ("text+image->text").
func modalitiesHaveImage(v interface{}) *bool {
	if list, ok := jsonStrings(v); ok && len(list) > 0 {
		return boolPtr(hasAny(list, "image", "images", "vision"))
	}
	if s, ok := v.(string); ok && s != "" {
		in := strings.ToLower(strings.SplitN(s, "->", 2)[0])
		return boolPtr(strings.Contains(in, "image"))
	}
	return nil
}

// endpointCaps reads what one entry of a /models answer says about its model.
func endpointCaps(d map[string]interface{}) caps {
	var c caps
	arch := jsonObj(d["architecture"])
	top := jsonObj(d["top_provider"])

	// images
	for _, v := range []interface{}{arch["input_modalities"], d["input_modalities"], d["supported_input_modalities"], jsonObj(d["modalities"])["input"], d["modalities"], arch["modality"]} {
		if c.Images = modalitiesHaveImage(v); c.Images != nil {
			break
		}
	}
	if c.Images == nil {
		c.Images = firstBool(d, "supports_vision", "supports_image_input", "supports_images", "vision")
	}
	if c.Images == nil {
		switch strings.ToLower(strOf(d["type"])) { // LM Studio
		case "vlm":
			c.Images = boolPtr(true)
		case "llm":
			c.Images = boolPtr(false)
		}
	}

	// capabilities: an object of flags (Mistral, some gateways) or a list of names (GitHub Models, Ollama-likes)
	switch cv := d["capabilities"].(type) {
	case map[string]interface{}:
		if c.Images == nil {
			c.Images = firstBool(cv, "vision", "image_input", "images", "supports_vision")
		}
		c.Reasoning = firstBool(cv, "reasoning", "thinking")
		c.Tools = firstBool(cv, "function_calling", "tools", "tool_call", "tool_calling", "tool_use")
	case []interface{}:
		list, _ := jsonStrings(cv)
		// a list only says what a model has: missing names prove nothing here (unlike Ollama's /api/show)
		if c.Images == nil && hasAny(list, imageWords...) {
			c.Images = boolPtr(true)
		}
		if hasAny(list, reasoningWords...) {
			c.Reasoning = boolPtr(true)
		}
		if hasAny(list, toolWords...) {
			c.Tools = boolPtr(true)
		}
	}

	// OpenRouter (and the Hugging Face router): the request parameters a model takes
	if params, ok := jsonStrings(d["supported_parameters"]); ok && len(params) > 0 {
		if c.Reasoning == nil {
			c.Reasoning = boolPtr(hasAny(params, "reasoning", "include_reasoning"))
		}
		if c.Tools == nil {
			c.Tools = boolPtr(hasAny(params, "tools", "tool_choice"))
		}
	}
	if c.Reasoning == nil {
		c.Reasoning = firstBool(d, "supports_reasoning", "reasoning")
	}
	if c.Tools == nil {
		c.Tools = firstBool(d, "supports_tools", "supports_function_calling", "function_calling", "tool_call")
	}

	c.Context = firstInt(d, "context_length", "max_context_length", "context_window", "max_model_len", "max_input_tokens", "input_token_limit", "inputTokenLimit")
	if c.Context == 0 {
		c.Context = firstInt(top, "context_length")
	}
	c.Output = firstInt(top, "max_completion_tokens")
	if c.Output == 0 {
		c.Output = firstInt(d, "max_output_tokens", "max_completion_tokens", "output_token_limit", "outputTokenLimit")
	}
	return c
}

func strOf(v interface{}) string {
	s, _ := v.(string)
	return s
}

// ---------------------------------------------------------------- 2. Ollama

// ollamaRoot is the server's address without the OpenAI-compatible "/v1" or the native "/api" ("http://host:11434").
func ollamaRoot(base string) string {
	base = strings.TrimRight(base, "/")
	for _, suffix := range []string{"/v1", "/api"} {
		if strings.HasSuffix(base, suffix) {
			return strings.TrimSuffix(base, suffix)
		}
	}
	return base
}

// looksLikeOllama: Ollama's /v1/models says owned_by "library" for its own models; its default port is 11434.
func looksLikeOllama(base string, entries []map[string]interface{}, nativeList bool) bool {
	if nativeList {
		return true
	}
	if u, err := url.Parse(base); err == nil && u.Port() == "11434" {
		return true
	}
	for _, d := range entries {
		if o := strings.ToLower(strOf(d["owned_by"])); o == "library" || o == "ollama" {
			return true
		}
	}
	return false
}

// ollamaCaps asks Ollama's /api/show what each model can do. Its list of capabilities is complete, so a missing
// "vision" means the model cannot read images. The context stays unknown: Ollama runs with a smaller num_ctx than
// the model's maximum, so that number would mislead OpenCode.
func ollamaCaps(ctx context.Context, base, key string, ids []string) map[string]caps {
	const maxModels, workers = 64, 6
	if len(ids) > maxModels {
		ids = ids[:maxModels]
	}
	root := ollamaRoot(base)
	out := map[string]caps{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, workers)
	for _, id := range ids {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			rctx, cancel := context.WithTimeout(ctx, 6*time.Second)
			defer cancel()
			body, _ := json.Marshal(map[string]string{"model": id})
			req, err := http.NewRequestWithContext(rctx, http.MethodPost, root+"/api/show", bytes.NewReader(body))
			if err != nil {
				return
			}
			req.Header.Set("Content-Type", "application/json")
			if key != "" {
				req.Header.Set("Authorization", "Bearer "+key)
			}
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				return
			}
			var show struct {
				Capabilities []string `json:"capabilities"`
			}
			if json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&show) != nil || len(show.Capabilities) == 0 {
				return
			}
			list := make([]string, 0, len(show.Capabilities))
			for _, s := range show.Capabilities {
				list = append(list, strings.ToLower(s))
			}
			c := caps{
				Images:    boolPtr(hasAny(list, "vision")),
				Reasoning: boolPtr(hasAny(list, "thinking")),
				Tools:     boolPtr(hasAny(list, "tools")),
			}
			mu.Lock()
			out[id] = c
			mu.Unlock()
		}(id)
	}
	wg.Wait()
	return out
}

// ---------------------------------------------------------------- 3. the models.dev catalog

// catalogURL is the catalog OpenCode itself uses (a variable so tests can point it elsewhere).
var catalogURL = "https://models.dev/api.json"

const catalogMaxAge = 24 * time.Hour

type catalogModel struct {
	Attachment bool  `json:"attachment"`
	Reasoning  bool  `json:"reasoning"`
	ToolCall   *bool `json:"tool_call"`
	Modalities *struct {
		Input []string `json:"input"`
	} `json:"modalities"`
	Limit struct {
		Context int64 `json:"context"`
		Output  int64 `json:"output"`
	} `json:"limit"`
}

func (cm catalogModel) images() bool {
	if cm.Modalities != nil && len(cm.Modalities.Input) > 0 {
		for _, s := range cm.Modalities.Input {
			if strings.EqualFold(s, "image") {
				return true
			}
		}
		return false
	}
	return cm.Attachment
}

// modelCatalog: every catalog model by its lower-cased id, and by the id's last part ("anthropic/claude-x" → "claude-x").
type modelCatalog struct {
	byID, byName map[string][]catalogModel
	// providers: the catalog's provider ids ("openrouter", "openai"…), which OpenCode has built in
	providers map[string]bool
}

type catalogCache struct {
	mu     sync.Mutex
	cat    *modelCatalog
	loaded time.Time
}

func parseCatalog(data []byte) *modelCatalog {
	var raw map[string]struct {
		Models map[string]catalogModel `json:"models"`
	}
	if json.Unmarshal(data, &raw) != nil {
		return nil
	}
	cat := &modelCatalog{byID: map[string][]catalogModel{}, byName: map[string][]catalogModel{}, providers: map[string]bool{}}
	n := 0
	for pid, p := range raw {
		cat.providers[strings.ToLower(pid)] = true
		for id, md := range p.Models {
			id = strings.ToLower(strings.TrimSpace(id))
			if id == "" {
				continue
			}
			cat.byID[id] = append(cat.byID[id], md)
			last := lastPart(id)
			cat.byName[last] = append(cat.byName[last], md)
			n++
		}
	}
	if n == 0 {
		return nil
	}
	return cat
}

func lastPart(id string) string {
	if i := strings.LastIndex(id, "/"); i >= 0 {
		return id[i+1:]
	}
	return id
}

// catalogKeys: the ways an endpoint's model id may appear in the catalog, most exact first.
// "models/gemini-2.5-flash" (Google), "openai/gpt-4o:free" (OpenRouter), "gpt-4o@2024-08-06" (some gateways).
func catalogKeys(id string) []string {
	id = strings.ToLower(strings.TrimSpace(id))
	keys := []string{id}
	add := func(k string) {
		for _, have := range keys {
			if have == k {
				return
			}
		}
		if k != "" {
			keys = append(keys, k)
		}
	}
	for _, sep := range []string{":", "@"} {
		if i := strings.Index(id, sep); i > 0 {
			add(id[:i])
		}
	}
	return keys
}

// lookup combines the catalog's entries for id (several providers list the same model): a capability counts when most
// of them have it; the limits are the most common value, the smaller one on a tie.
func (cat *modelCatalog) lookup(id string) (caps, bool) {
	if cat == nil {
		return caps{}, false
	}
	var found []catalogModel
	for _, k := range catalogKeys(id) {
		if found = cat.byID[k]; len(found) > 0 {
			break
		}
	}
	if len(found) == 0 {
		for _, k := range catalogKeys(id) {
			if found = cat.byName[lastPart(k)]; len(found) > 0 {
				break
			}
		}
	}
	if len(found) == 0 {
		return caps{}, false
	}
	var img, reason, tools, toolsKnown int
	var ctxs, outs []int64
	for _, md := range found {
		if md.images() {
			img++
		}
		if md.Reasoning {
			reason++
		}
		if md.ToolCall != nil {
			toolsKnown++
			if *md.ToolCall {
				tools++
			}
		}
		ctxs = append(ctxs, md.Limit.Context)
		outs = append(outs, md.Limit.Output)
	}
	half := func(n, of int) bool { return of > 0 && 2*n >= of }
	c := caps{
		Images:    boolPtr(half(img, len(found))),
		Reasoning: boolPtr(half(reason, len(found))),
		Context:   mostCommon(ctxs),
		Output:    mostCommon(outs),
	}
	if toolsKnown > 0 {
		c.Tools = boolPtr(half(tools, toolsKnown))
	}
	return c, true
}

func mostCommon(vals []int64) int64 {
	count := map[int64]int{}
	var best int64
	for _, v := range vals {
		if v <= 0 {
			continue
		}
		count[v]++
		if c, b := count[v], count[best]; best == 0 || c > b || c == b && v < best {
			best = v
		}
	}
	return best
}

func (m *Manager) catalogCachePath() string { return filepath.Join(m.dataDir(), "models_catalog.json") }

// openCodeCatalogPaths: where OpenCode keeps its own copy of the catalog ($XDG_CACHE_HOME/opencode/models.json).
func (m *Manager) openCodeCatalogPaths() []string {
	var out []string
	if x := os.Getenv("XDG_CACHE_HOME"); x != "" {
		out = append(out, filepath.Join(x, "opencode", "models.json"))
	}
	return append(out, filepath.Join(m.HomeDir, ".cache", "opencode", "models.json"))
}

// loadCatalog loads the catalog: a fresh copy on disk (the bridge's or OpenCode's), else models.dev, else any old
// copy. Nil when there is none; detection then simply goes without it.
func (m *Manager) loadCatalog(ctx context.Context) *modelCatalog {
	m.catalog.mu.Lock()
	defer m.catalog.mu.Unlock()
	if m.catalog.cat != nil && time.Since(m.catalog.loaded) < catalogMaxAge {
		return m.catalog.cat
	}
	keep := func(cat *modelCatalog) *modelCatalog {
		m.catalog.cat, m.catalog.loaded = cat, time.Now()
		return cat
	}
	paths := append([]string{m.catalogCachePath()}, m.openCodeCatalogPaths()...)
	fromDisk := func(fresh bool) *modelCatalog {
		for _, p := range paths {
			fi, err := os.Stat(p)
			if err != nil || fresh && time.Since(fi.ModTime()) > catalogMaxAge {
				continue
			}
			if data, err := os.ReadFile(p); err == nil {
				if cat := parseCatalog(data); cat != nil {
					return cat
				}
			}
		}
		return nil
	}
	if cat := fromDisk(true); cat != nil {
		return keep(cat)
	}
	if data := downloadCatalog(ctx); data != nil {
		if cat := parseCatalog(data); cat != nil {
			_ = os.MkdirAll(m.dataDir(), 0700)
			_ = os.WriteFile(m.catalogCachePath(), data, 0600)
			return keep(cat)
		}
	}
	if cat := fromDisk(false); cat != nil {
		// stale, so try the download again next time rather than in a day
		m.catalog.cat, m.catalog.loaded = cat, time.Now().Add(-catalogMaxAge+10*time.Minute)
		return cat
	}
	return nil
}

// cachedCatalog is the catalog without going online: the one in memory, else a copy on disk of any age (its age is
// kept, so loadCatalog still fetches a newer one when it is old). Nil when there is none yet.
func (m *Manager) cachedCatalog() *modelCatalog {
	m.catalog.mu.Lock()
	defer m.catalog.mu.Unlock()
	if m.catalog.cat != nil {
		return m.catalog.cat
	}
	for _, p := range append([]string{m.catalogCachePath()}, m.openCodeCatalogPaths()...) {
		fi, err := os.Stat(p)
		if err != nil {
			continue
		}
		if data, err := os.ReadFile(p); err == nil {
			if cat := parseCatalog(data); cat != nil {
				m.catalog.cat, m.catalog.loaded = cat, fi.ModTime()
				return cat
			}
		}
	}
	return nil
}

func downloadCatalog(ctx context.Context) []byte {
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, catalogURL, nil)
	if err != nil {
		return nil
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return nil
	}
	return data
}
