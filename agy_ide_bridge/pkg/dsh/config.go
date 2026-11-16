package dsh

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
)

// Layout (everything the bridge owns lives under ~/.local/share/dsh; dsh's own data stays in ~/.dsh):
//
//	node-<version>/          official Node.js build (glibc on Android, started through the app's loader)
//	versions/<dsh version>/  `npm install @deepseek-ai/dsh` prefix
//	current -> versions/<v>  the version in use (switched only after a complete install)
//	lib/                     Android: libstdc++, libgcc_s, libutil for the glibc Node
//	antigem/                 files the bridge generates: node launcher, execPath fix, companion plugin, profile patch
//	npm-cache/               npm's download cache
//	state.json               installed Node version
//	config.json              provider, model, API keys (0600), MCP servers, permission mode
//	meta.json                chat titles and archive flags set from the app
type layout struct{ root string }

func (l layout) versionsDir() string        { return filepath.Join(l.root, "versions") }
func (l layout) versionDir(v string) string { return filepath.Join(l.root, "versions", v) }
func (l layout) current() string            { return filepath.Join(l.root, "current") }
func (l layout) libDir() string             { return filepath.Join(l.root, "lib") }
func (l layout) rtDir() string              { return filepath.Join(l.root, "antigem") }
func (l layout) npmCache() string           { return filepath.Join(l.root, "npm-cache") }
func (l layout) stateFile() string          { return filepath.Join(l.root, "state.json") }
func (l layout) configFile() string         { return filepath.Join(l.root, "config.json") }
func (l layout) metaFile() string           { return filepath.Join(l.root, "meta.json") }
func (l layout) nodeDir(v string) string    { return filepath.Join(l.root, "node-"+v) }
func (l layout) controlSocket() string      { return filepath.Join(l.rtDir(), "ctl.sock") }
func (l layout) patchFile() string          { return filepath.Join(l.rtDir(), "antigem.patch.yml") }

// binJS is dsh's CLI entry inside an installed version folder.
func binJS(versionDir string) string {
	return filepath.Join(versionDir, "node_modules", "@deepseek-ai", "dsh", "lib", "bin.js")
}

// installState records what the installer put in place.
type installState struct {
	NodeVersion string `json:"node_version"`
}

func (l layout) readState() installState {
	var s installState
	if b, err := os.ReadFile(l.stateFile()); err == nil {
		_ = json.Unmarshal(b, &s)
	}
	return s
}

func (l layout) writeState(s installState) error {
	b, _ := json.MarshalIndent(s, "", "  ")
	return writeFileAtomic(l.stateFile(), b, 0o644)
}

// installedVersion is the dsh version `current` points to ("" when none is installed).
func (l layout) installedVersion() string {
	target, err := os.Readlink(l.current())
	if err != nil {
		return ""
	}
	v := filepath.Base(target)
	if _, err := os.Stat(binJS(l.versionDir(v))); err != nil {
		return ""
	}
	return v
}

// --- provider catalog ----------------------------------------------------------------------------

// Provider is a model route dsh can use with an API key: `deepseek-official` is dsh's own DeepSeek adapter, the rest
// are pi-ai routes (dsh-llm-pi-ai) that read their key from the environment variable named here.
type Provider struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Env          string `json:"env"`
	DefaultModel string `json:"default_model,omitempty"`
	KeyURL       string `json:"key_url,omitempty"`
}

// Providers is what the app offers in its settings. Model lists come from the running dsh (GET /models).
var Providers = []Provider{
	{ID: "deepseek-official", Name: "DeepSeek", Env: "DEEPSEEK_API_KEY", DefaultModel: "deepseek-flash", KeyURL: "https://platform.deepseek.com/api_keys"},
	{ID: "openrouter", Name: "OpenRouter", Env: "OPENROUTER_API_KEY", DefaultModel: "deepseek/deepseek-v4-flash", KeyURL: "https://openrouter.ai/keys"},
	{ID: "openai", Name: "OpenAI", Env: "OPENAI_API_KEY", KeyURL: "https://platform.openai.com/api-keys"},
	{ID: "anthropic", Name: "Anthropic", Env: "ANTHROPIC_API_KEY", KeyURL: "https://console.anthropic.com/settings/keys"},
	{ID: "google", Name: "Google Gemini", Env: "GEMINI_API_KEY", KeyURL: "https://aistudio.google.com/apikey"},
	{ID: "xai", Name: "xAI", Env: "XAI_API_KEY", KeyURL: "https://console.x.ai"},
	{ID: "groq", Name: "Groq", Env: "GROQ_API_KEY", KeyURL: "https://console.groq.com/keys"},
	{ID: "mistral", Name: "Mistral", Env: "MISTRAL_API_KEY", KeyURL: "https://console.mistral.ai/api-keys"},
	{ID: "zai", Name: "Z.ai", Env: "ZAI_API_KEY", KeyURL: "https://z.ai/manage-apikey/apikey-list"},
	{ID: "moonshotai", Name: "Moonshot AI", Env: "MOONSHOT_API_KEY", KeyURL: "https://platform.moonshot.ai/console/api-keys"},
	{ID: "together", Name: "Together AI", Env: "TOGETHER_API_KEY", KeyURL: "https://api.together.ai/settings/api-keys"},
	{ID: "fireworks", Name: "Fireworks", Env: "FIREWORKS_API_KEY", KeyURL: "https://fireworks.ai/account/api-keys"},
	{ID: "cerebras", Name: "Cerebras", Env: "CEREBRAS_API_KEY", KeyURL: "https://cloud.cerebras.ai"},
	{ID: "nvidia", Name: "NVIDIA", Env: "NVIDIA_API_KEY", KeyURL: "https://build.nvidia.com"},
	{ID: "huggingface", Name: "Hugging Face", Env: "HF_TOKEN", KeyURL: "https://huggingface.co/settings/tokens"},
}

func providerByID(id string) *Provider {
	for i := range Providers {
		if Providers[i].ID == id {
			return &Providers[i]
		}
	}
	return nil
}

// --- settings ------------------------------------------------------------------------------------

// McpServer is one MCP server mounted into every dsh session (dsh-mcp-client).
type McpServer struct {
	Name      string            `json:"name"`
	Transport string            `json:"transport"` // stdio | streamable-http
	Command   string            `json:"command,omitempty"`
	Args      []string          `json:"args,omitempty"`
	Env       map[string]string `json:"env,omitempty"`
	URL       string            `json:"url,omitempty"`
	Headers   map[string]string `json:"headers,omitempty"`
	Disabled  bool              `json:"disabled,omitempty"`
}

// Config is what the app sets for dsh (kept by the bridge, applied to the dsh process).
type Config struct {
	Provider        string            `json:"provider"`
	Model           string            `json:"model"`
	ReasoningEffort string            `json:"reasoning_effort,omitempty"`
	MaxTokens       int               `json:"max_tokens,omitempty"`
	Permission      string            `json:"permission,omitempty"` // ask | full
	Keys            map[string]string `json:"keys,omitempty"`       // env var → secret
	Mcp             []McpServer       `json:"mcp,omitempty"`
}

const defaultMaxTokens = 32768

var mcpNameRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,32}$`)

func (c *Config) normalize() {
	if c.Provider == "" {
		c.Provider = "deepseek-official"
	}
	if c.Model == "" {
		if p := providerByID(c.Provider); p != nil {
			c.Model = p.DefaultModel
		}
	}
	if c.MaxTokens <= 0 {
		c.MaxTokens = defaultMaxTokens
	}
	if c.Permission != "full" {
		c.Permission = "ask"
	}
	if c.Keys == nil {
		c.Keys = map[string]string{}
	}
}

type configStore struct {
	mu   sync.Mutex
	path string
}

func (s *configStore) load() Config {
	s.mu.Lock()
	defer s.mu.Unlock()
	var c Config
	if b, err := os.ReadFile(s.path); err == nil {
		_ = json.Unmarshal(b, &c)
	}
	c.normalize()
	return c
}

func (s *configStore) save(c Config) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	c.normalize()
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	// holds API keys
	return writeFileAtomic(s.path, b, 0o600)
}

// validateMcp checks the servers the app sends before they are written into the profile patch.
func validateMcp(list []McpServer) error {
	seen := map[string]bool{}
	for _, s := range list {
		if !mcpNameRe.MatchString(s.Name) {
			return errors.New("MCP server names may only use letters, digits, - and _ (up to 32): " + s.Name)
		}
		if seen[s.Name] {
			return errors.New("two MCP servers are named " + s.Name)
		}
		seen[s.Name] = true
		switch s.Transport {
		case "stdio":
			if strings.TrimSpace(s.Command) == "" {
				return errors.New("MCP server " + s.Name + " needs a command")
			}
		case "streamable-http":
			if !strings.HasPrefix(s.URL, "http://") && !strings.HasPrefix(s.URL, "https://") {
				return errors.New("MCP server " + s.Name + " needs an http(s) URL")
			}
		default:
			return errors.New("MCP server " + s.Name + ": transport must be stdio or streamable-http")
		}
	}
	return nil
}

// publicConfig is the config as the app sees it: keys are reported as set, never sent back.
func publicConfig(c Config) map[string]interface{} {
	set := []string{}
	for k, v := range c.Keys {
		if v != "" {
			set = append(set, k)
		}
	}
	sort.Strings(set)
	mcp := c.Mcp
	if mcp == nil {
		mcp = []McpServer{}
	}
	return map[string]interface{}{
		"provider":         c.Provider,
		"model":            c.Model,
		"reasoning_effort": c.ReasoningEffort,
		"max_tokens":       c.MaxTokens,
		"permission":       c.Permission,
		"keys_set":         set,
		"mcp":              mcp,
		"providers":        Providers,
		"platform":         runtime.GOOS + "/" + runtime.GOARCH,
	}
}

// --- chat metadata (titles, archive) set from the app ---------------------------------------------

type chatMeta struct {
	Titles   map[string]string `json:"titles,omitempty"`
	Archived map[string]bool   `json:"archived,omitempty"`
}

type metaStore struct {
	mu   sync.Mutex
	path string
}

func (s *metaStore) load() chatMeta {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.loadLocked()
}

func (s *metaStore) loadLocked() chatMeta {
	var m chatMeta
	if b, err := os.ReadFile(s.path); err == nil {
		_ = json.Unmarshal(b, &m)
	}
	if m.Titles == nil {
		m.Titles = map[string]string{}
	}
	if m.Archived == nil {
		m.Archived = map[string]bool{}
	}
	return m
}

func (s *metaStore) update(fn func(*chatMeta)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m := s.loadLocked()
	fn(&m)
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(s.path, b, 0o644)
}

func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
