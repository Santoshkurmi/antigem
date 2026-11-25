package plugins

import (
	"archive/zip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// AgentSkills serves /api/agent-skills/…: the skills each agent loads from its own user folder (Claude Code,
// Codex, Antigravity, OpenCode), added from GitHub repositories, and Antigravity plugins installed from GitHub
// with agy's own installer. Repositories are downloaded file by file (or as one archive when the GitHub API is
// out of requests), so git is not needed.
//
//	GET  /?agent=claude|codex|agy|opencode       → {dir, skills:[{id, name, description, path, kind, source?}]}
//	POST /inspect {source, agent?}               → {repo, ref, subpath, skills:[{path, id, name, description, exists, same_source}], plugin_kinds}
//	POST /install {agent, source, paths, replace} → {installed:[id], replaced:[id]}   (paths empty = every skill found)
//	POST /update  {agent, id}                    → {id}   (downloads it again from where it came from)
//	POST /remove  {agent, id}
//	POST /toggle  {agent, id, enabled}           (turns a skill off or on for every chat, in the agent's own way)
//	POST /agy-plugin {source}                    → {output}   (downloads the repository, then `agy plugin install <folder>`)
//	GET  /agy-plugins                            → {plugins:{name: enabled}}   (~/.gemini/config/config.json)
//	POST /agy-plugin-toggle {id, enabled}        (`agy plugin enable|disable <name>`)
type AgentSkills struct {
	m      *Manager
	home   string
	agyBin func() string
	mu     sync.Mutex // moves into the skills folders
	// snapshot opens a repository (tests replace it with files in memory)
	snapshot func(r Repo, tmp string) (repoSnapshot, error)
	// Toggles reach the agents that keep a skill's on/off state in their own running servers
	Toggles SkillToggles
}

// SkillToggles connects turning skills off and on to Codex and OpenCode (their managers live in other packages).
// Claude Code's state is in its settings.json; Antigravity has none, so its turned-off skills are moved aside.
type SkillToggles struct {
	// CodexCall makes one Codex app-server request (skills/list, skills/config/write); nil when Codex is turned off
	CodexCall func(ctx context.Context, method string, params json.RawMessage) (json.RawMessage, error)
	// OpenCodeDisabled / OpenCodeSet: the skills turned off in the bridge's OpenCode config; nil when OpenCode is off
	OpenCodeDisabled func() map[string]bool
	OpenCodeSet      func(ctx context.Context, id string, enabled bool) error
}

// NewAgentSkills shares [m]'s GitHub client and download limits; [agyBin] returns the agy binary in use.
func NewAgentSkills(m *Manager, home string, agyBin func() string) *AgentSkills {
	a := &AgentSkills{m: m, home: home, agyBin: agyBin}
	a.snapshot = a.openSnapshot
	return a
}

const (
	skillFile     = "SKILL.md"
	maxRepoSkills = 300
)

// SkillSource is written as .antigem-source.json into every skill folder added from GitHub.
type SkillSource struct {
	Repo        string `json:"repo"`
	Ref         string `json:"ref"`
	Path        string `json:"path"`
	InstalledAt string `json:"installed_at"`
}

type agentSkill struct {
	ID          string       `json:"id"`
	Name        string       `json:"name"`
	Description string       `json:"description"`
	Path        string       `json:"path"`
	Kind        string       `json:"kind"` // skill | plugin (a Claude Code plugin folder, loaded as <name>@skills-dir)
	Source      *SkillSource `json:"source,omitempty"`
	Enabled     bool         `json:"enabled"` // false: turned off, the agent does not load it
}

type repoSkill struct {
	Path        string `json:"path"` // folder inside the repository ("" = its root)
	ID          string `json:"id"`   // folder name in the agent's skills folder
	Name        string `json:"name"`
	Description string `json:"description"`
	Exists      bool   `json:"exists"`      // the agent already has a skill folder with this id
	SameSource  bool   `json:"same_source"` // …added earlier from this same repository folder (adding it again updates it)
}

// skillsDir is where [agent] loads the user's own skills from (the same folders the app's Jarvis skill uses).
func (a *AgentSkills) skillsDir(agent string) (string, error) {
	switch agent {
	case "claude":
		if d := os.Getenv("CLAUDE_CONFIG_DIR"); d != "" {
			return filepath.Join(d, "skills"), nil
		}
		return filepath.Join(a.home, ".claude", "skills"), nil
	case "codex":
		if d := os.Getenv("CODEX_HOME"); d != "" {
			return filepath.Join(d, "skills"), nil
		}
		return filepath.Join(a.home, ".codex", "skills"), nil
	case "agy":
		return filepath.Join(a.home, ".gemini", "antigravity", "skills"), nil
	case "opencode":
		if d := os.Getenv("XDG_CONFIG_HOME"); d != "" {
			return filepath.Join(d, "opencode", "skills"), nil
		}
		return filepath.Join(a.home, ".config", "opencode", "skills"), nil
	}
	return "", fmt.Errorf("Unknown agent %q", agent)
}

func (a *AgentSkills) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	route := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/agent-skills"), "/")
	if r.Method == http.MethodGet && route == "" {
		agent := r.URL.Query().Get("agent")
		dir, err := a.skillsDir(agent)
		if err != nil {
			replyErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "dir": dir, "skills": a.withState(r.Context(), agent, a.list(agent, dir))})
		return
	}
	if r.Method == http.MethodGet && route == "/agy-plugins" {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "plugins": a.agyPluginStates()})
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown agent-skills endpoint"})
		return
	}
	var body struct {
		Agent   string   `json:"agent"`
		Source  string   `json:"source"`
		Paths   []string `json:"paths"`
		Replace bool     `json:"replace"`
		ID      string   `json:"id"`
		Enabled bool     `json:"enabled"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil {
		replyErr(w, errors.New("invalid JSON body"))
		return
	}
	switch route {
	case "/inspect":
		res, err := a.inspect(body.Source, body.Agent)
		if err != nil {
			replyErr(w, err)
			return
		}
		res["success"] = true
		writeJSON(w, http.StatusOK, res)
	case "/install":
		installed, replaced, err := a.install(body.Agent, body.Source, body.Paths, body.Replace)
		if err != nil {
			replyErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "installed": installed, "replaced": replaced})
	case "/update":
		if err := a.update(body.Agent, body.ID); err != nil {
			replyErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "id": body.ID})
	case "/remove":
		if err := a.remove(r.Context(), body.Agent, body.ID); err != nil {
			replyErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case "/toggle":
		if err := a.setEnabled(r.Context(), body.Agent, body.ID, body.Enabled); err != nil {
			replyErr(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	case "/agy-plugin-toggle":
		out, err := a.setAgyPluginEnabled(body.ID, body.Enabled)
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error(), "output": out})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "output": out})
	case "/agy-plugin":
		out, err := a.installAgyPlugin(body.Source)
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error(), "output": out})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "output": out})
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown agent-skills endpoint"})
	}
}

func replyErr(w http.ResponseWriter, err error) {
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
}

// ---- the agent's skills folder ----

func (a *AgentSkills) list(agent, dir string) []agentSkill {
	out := []agentSkill{}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return out
	}
	for _, e := range entries {
		// .system (Codex' built-in skills) and other dot folders are not the user's
		if strings.HasPrefix(e.Name(), ".") {
			continue
		}
		p := filepath.Join(dir, e.Name())
		if fi, err := os.Stat(p); err != nil || !fi.IsDir() {
			continue
		}
		s := agentSkill{ID: e.Name(), Name: e.Name(), Path: p, Kind: "skill", Source: readSkillSource(p), Enabled: true}
		if text, err := readSmall(filepath.Join(p, skillFile), 64<<10); err == nil {
			name, desc := parseFrontmatter(text)
			if name != "" {
				s.Name = name
			}
			s.Description = desc
		} else if agent == "claude" {
			manifest, err := readSmall(filepath.Join(p, ".claude-plugin", "plugin.json"), 256<<10)
			if err != nil {
				continue
			}
			var pj struct {
				Name        string `json:"name"`
				Description string `json:"description"`
			}
			_ = json.Unmarshal([]byte(manifest), &pj)
			if pj.Name != "" {
				s.Name = pj.Name
			}
			s.Description, s.Kind = pj.Description, "plugin"
		} else {
			continue
		}
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

func readSkillSource(dir string) *SkillSource {
	data, err := readSmall(filepath.Join(dir, sourceFile), 64<<10)
	if err != nil {
		return nil
	}
	var s SkillSource
	if json.Unmarshal([]byte(data), &s) != nil || s.Repo == "" {
		return nil
	}
	return &s
}

func readSmall(p string, limit int64) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, limit))
	return string(data), err
}

func (a *AgentSkills) remove(ctx context.Context, agent, id string) error {
	dir, err := a.skillsDir(agent)
	if err != nil {
		return err
	}
	if !folderPattern.MatchString(id) {
		return fmt.Errorf("Invalid skill name: %s", id)
	}
	var kind, name string
	for _, s := range a.list(agent, dir) {
		if s.ID == id {
			kind, name = s.Kind, s.Name
		}
	}
	target := filepath.Join(dir, id)
	if agent == "agy" && a.agyOff(id) {
		target = filepath.Join(a.offDir(agent), id) // a turned-off skill waits outside the skills folder
	}
	a.mu.Lock()
	fi, err := os.Lstat(target)
	if err != nil {
		a.mu.Unlock()
		return fmt.Errorf("%s is not installed", id)
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		err = os.Remove(target) // a linked skill: only the link goes, never what it points to
	} else {
		err = os.RemoveAll(target)
	}
	a.mu.Unlock()
	if err != nil {
		return err
	}
	// a removed skill leaves no "off" behind (one added later under this name starts turned on)
	switch agent {
	case "claude":
		_ = a.setClaudeSkillOff(id, kind, name, false)
	case "opencode":
		if a.Toggles.OpenCodeSet != nil {
			_ = a.Toggles.OpenCodeSet(ctx, id, true)
		}
	}
	return nil
}

func (a *AgentSkills) update(agent, id string) (err error) {
	dir, err := a.skillsDir(agent)
	if err != nil {
		return err
	}
	if !folderPattern.MatchString(id) {
		return fmt.Errorf("Invalid skill name: %s", id)
	}
	// a turned-off Antigravity skill is updated in the skills folder, then moved aside again
	off := agent == "agy" && a.agyOff(id)
	if off {
		if err := a.moveAgySkill(id, true); err != nil {
			return err
		}
		defer func() {
			if err2 := a.moveAgySkill(id, false); err == nil {
				err = err2
			}
		}()
	}
	src := readSkillSource(filepath.Join(dir, id))
	if src == nil {
		return fmt.Errorf("%s was not added from GitHub, so there is nothing to update it from", id)
	}
	owner, name, ok := strings.Cut(src.Repo, "/")
	if !ok {
		return fmt.Errorf("Bad source of %s: %s", id, src.Repo)
	}
	_, _, err = a.installDirs(agent, Repo{owner, name, src.Ref}, []string{src.Path}, map[string]string{src.Path: id}, true)
	return err
}

// ---- turning skills off and on ----
//
// Each agent keeps it its own way (all checked against the real CLIs):
//   - Claude Code: settings.json "skillOverrides": {"<folder name>": "off"}; a plugin folder kept in the skills
//     folder (loaded as <name>@skills-dir) via "enabledPlugins": {"<name>@skills-dir": false}
//   - Codex: app-server skills/config/write {path: <SKILL.md>, enabled} ([[skills.config]] in config.toml)
//   - OpenCode: a "skill.use" deny policy in the bridge's OpenCode config (the skill id is its folder name)
//   - Antigravity has no switch: a turned-off skill is moved to ~/.antigem/disabled-skills/agy/<id> and back

// withState fills in Enabled of [skills] (all true from [list]) and adds Antigravity's turned-off skills.
func (a *AgentSkills) withState(ctx context.Context, agent string, skills []agentSkill) []agentSkill {
	switch agent {
	case "claude":
		st := readJSONObject(a.claudeSettingsPath())
		overrides, _ := st["skillOverrides"].(map[string]interface{})
		plugins, _ := st["enabledPlugins"].(map[string]interface{})
		for i := range skills {
			s := &skills[i]
			if s.Kind == "plugin" {
				if on, ok := plugins[s.Name+"@skills-dir"].(bool); ok && !on {
					s.Enabled = false
				}
			} else if overrides[s.ID] == "off" {
				s.Enabled = false
			}
		}
	case "codex":
		state := a.codexSkillStates(ctx)
		for i := range skills {
			if on, ok := state[cleanSkillPath(filepath.Join(skills[i].Path, skillFile))]; ok {
				skills[i].Enabled = on
			}
		}
	case "opencode":
		if a.Toggles.OpenCodeDisabled != nil {
			off := a.Toggles.OpenCodeDisabled()
			for i := range skills {
				if off[skills[i].ID] {
					skills[i].Enabled = false
				}
			}
		}
	case "agy":
		for _, s := range a.list(agent, a.offDir(agent)) {
			s.Enabled = false
			skills = append(skills, s)
		}
		sort.Slice(skills, func(i, j int) bool { return strings.ToLower(skills[i].Name) < strings.ToLower(skills[j].Name) })
	}
	return skills
}

func (a *AgentSkills) setEnabled(ctx context.Context, agent, id string, enabled bool) error {
	dir, err := a.skillsDir(agent)
	if err != nil {
		return err
	}
	if !folderPattern.MatchString(id) {
		return fmt.Errorf("Invalid skill name: %s", id)
	}
	switch agent {
	case "claude":
		for _, s := range a.list(agent, dir) {
			if s.ID == id {
				return a.setClaudeSkillOff(id, s.Kind, s.Name, !enabled)
			}
		}
		return fmt.Errorf("%s is not installed", id)
	case "codex":
		if a.Toggles.CodexCall == nil {
			return errors.New("Codex is turned off in the app")
		}
		file := filepath.Join(dir, id, skillFile)
		if _, err := os.Stat(file); err != nil {
			return fmt.Errorf("%s is not installed", id)
		}
		// Codex may know the skill by its resolved path: use the one it lists
		want := cleanSkillPath(file)
		for p := range a.codexSkillPaths(ctx) {
			if cleanSkillPath(p) == want {
				file = p
				break
			}
		}
		params, _ := json.Marshal(map[string]interface{}{"path": file, "enabled": enabled})
		cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
		defer cancel()
		_, err := a.Toggles.CodexCall(cctx, "skills/config/write", params)
		return err
	case "opencode":
		if a.Toggles.OpenCodeSet == nil {
			return errors.New("OpenCode is turned off in the app")
		}
		if _, err := os.Stat(filepath.Join(dir, id)); err != nil {
			return fmt.Errorf("%s is not installed", id)
		}
		return a.Toggles.OpenCodeSet(ctx, id, enabled)
	case "agy":
		return a.moveAgySkill(id, enabled)
	}
	return fmt.Errorf("Unknown agent %q", agent)
}

// claudeSettingsPath is Claude Code's user settings.json (next to its skills folder).
func (a *AgentSkills) claudeSettingsPath() string {
	if d := os.Getenv("CLAUDE_CONFIG_DIR"); d != "" {
		return filepath.Join(d, "settings.json")
	}
	return filepath.Join(a.home, ".claude", "settings.json")
}

// setClaudeSkillOff writes (off) or clears Claude Code's switch for one skill folder; other settings stay as they are.
func (a *AgentSkills) setClaudeSkillOff(id, kind, name string, off bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	p := a.claudeSettingsPath()
	st := map[string]interface{}{}
	if data, err := os.ReadFile(p); err == nil && len(strings.TrimSpace(string(data))) > 0 {
		if err := json.Unmarshal(data, &st); err != nil {
			return fmt.Errorf("settings.json is not valid JSON: %w", err)
		} else if st == nil {
			st = map[string]interface{}{}
		}
	}
	var changed bool
	if kind == "plugin" {
		var v interface{}
		if off {
			v = false
		}
		changed = setNested(st, "enabledPlugins", name+"@skills-dir", v)
	} else {
		var v interface{}
		if off {
			v = "off"
		}
		changed = setNested(st, "skillOverrides", id, v)
	}
	if !changed {
		return nil
	}
	return writeJSONAtomic(p, st)
}

// setNested sets st[key][sub] = v (nil deletes it, and an emptied st[key]); reports whether anything changed.
func setNested(st map[string]interface{}, key, sub string, v interface{}) bool {
	m, _ := st[key].(map[string]interface{})
	old, had := m[sub]
	if v == nil {
		if !had {
			return false
		}
		delete(m, sub)
	} else {
		if had && old == v {
			return false
		}
		if m == nil {
			m = map[string]interface{}{}
		}
		m[sub] = v
	}
	if len(m) == 0 {
		delete(st, key)
	} else {
		st[key] = m
	}
	return true
}

func readJSONObject(p string) map[string]interface{} {
	out := map[string]interface{}{}
	if data, err := os.ReadFile(p); err == nil {
		_ = json.Unmarshal(data, &out)
	}
	if out == nil {
		out = map[string]interface{}{}
	}
	return out
}

// writeJSONAtomic replaces a JSON file through a temporary file, keeping the old file's mode.
func writeJSONAtomic(p string, v interface{}) error {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	mode := os.FileMode(0600)
	if fi, err := os.Stat(p); err == nil {
		mode = fi.Mode().Perm()
	}
	if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
		return err
	}
	tmp := p + ".antigem-tmp"
	if err := os.WriteFile(tmp, append(data, '\n'), mode); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

func cleanSkillPath(p string) string {
	if real, err := filepath.EvalSymlinks(p); err == nil {
		return real
	}
	return filepath.Clean(p)
}

// codexSkillPaths is every skill Codex lists (SKILL.md path → enabled), read fresh from disk; empty when Codex
// can't be asked.
func (a *AgentSkills) codexSkillPaths(ctx context.Context) map[string]bool {
	out := map[string]bool{}
	if a.Toggles.CodexCall == nil {
		return out
	}
	cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	raw, err := a.Toggles.CodexCall(cctx, "skills/list", json.RawMessage(`{"forceReload":true}`))
	if err != nil {
		return out
	}
	var res struct {
		Data []struct {
			Skills []struct {
				Path    string `json:"path"`
				Enabled *bool  `json:"enabled"`
			} `json:"skills"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &res) != nil {
		return out
	}
	for _, e := range res.Data {
		for _, s := range e.Skills {
			if s.Path != "" {
				out[s.Path] = s.Enabled == nil || *s.Enabled
			}
		}
	}
	return out
}

// codexSkillStates is [codexSkillPaths] keyed by resolved path.
func (a *AgentSkills) codexSkillStates(ctx context.Context) map[string]bool {
	out := map[string]bool{}
	for p, on := range a.codexSkillPaths(ctx) {
		out[cleanSkillPath(p)] = on
	}
	return out
}

// offDir is where AntiGem keeps an agent's turned-off skills (only Antigravity, which has no switch of its own).
func (a *AgentSkills) offDir(agent string) string {
	return filepath.Join(a.home, ".antigem", "disabled-skills", agent)
}

func (a *AgentSkills) agyOff(id string) bool {
	_, err := os.Lstat(filepath.Join(a.offDir("agy"), id))
	return err == nil
}

// moveAgySkill moves an Antigravity skill folder out of its skills folder (off) or back (on).
func (a *AgentSkills) moveAgySkill(id string, enabled bool) error {
	dir, err := a.skillsDir("agy")
	if err != nil {
		return err
	}
	on, off := filepath.Join(dir, id), filepath.Join(a.offDir("agy"), id)
	from, to := on, off
	if enabled {
		from, to = off, on
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, err := os.Lstat(from); err != nil {
		if _, err := os.Lstat(to); err == nil {
			return nil // already so
		}
		return fmt.Errorf("%s is not installed", id)
	}
	if _, err := os.Lstat(to); err == nil {
		if enabled {
			return fmt.Errorf("Another skill named %s was added meanwhile: remove one of them first", id)
		}
		// an older turned-off copy of this skill: the current one replaces it
		if err := os.RemoveAll(to); err != nil {
			return err
		}
	}
	if err := os.MkdirAll(filepath.Dir(to), 0755); err != nil {
		return err
	}
	return os.Rename(from, to)
}

// ---- Antigravity plugins: on and off ----

// agyPluginStates reads agy's plugin switches (~/.gemini/config/config.json "plugins": {name: {enabled}}); a
// plugin missing there is on.
func (a *AgentSkills) agyPluginStates() map[string]bool {
	out := map[string]bool{}
	var cfg struct {
		Plugins map[string]struct {
			Enabled *bool `json:"enabled"`
		} `json:"plugins"`
	}
	if data, err := os.ReadFile(a.agyConfigPath()); err == nil && json.Unmarshal(data, &cfg) == nil {
		for name, p := range cfg.Plugins {
			out[name] = p.Enabled == nil || *p.Enabled
		}
	}
	return out
}

func (a *AgentSkills) agyConfigPath() string {
	return filepath.Join(a.home, ".gemini", "config", "config.json")
}

var agyPluginName = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._@:-]{0,127}$`)

// setAgyPluginEnabled runs `agy plugin enable|disable <name>`; without the agy CLI it writes the same switch
// into agy's config.json itself.
func (a *AgentSkills) setAgyPluginEnabled(name string, enabled bool) (string, error) {
	if !agyPluginName.MatchString(name) {
		return "", fmt.Errorf("Invalid plugin name: %s", name)
	}
	bin := ""
	if a.agyBin != nil {
		bin = a.agyBin()
	}
	if fi, err := os.Stat(bin); bin != "" && err == nil && !fi.IsDir() {
		verb := "disable"
		if enabled {
			verb = "enable"
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
		defer cancel()
		cmd := exec.CommandContext(ctx, bin, "plugin", verb, name)
		cmd.Dir = a.home
		cmd.Env = os.Environ()
		out, err := cmd.CombinedOutput()
		text := strings.TrimSpace(string(out))
		if err != nil {
			msg := lastLines(text, 6)
			if msg == "" {
				msg = err.Error()
			}
			return text, fmt.Errorf("agy could not %s it: %s", verb, msg)
		}
		return text, nil
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	p := a.agyConfigPath()
	cfg := readJSONObject(p)
	plugins, _ := cfg["plugins"].(map[string]interface{})
	if plugins == nil {
		plugins = map[string]interface{}{}
	}
	entry, _ := plugins[name].(map[string]interface{})
	if entry == nil {
		entry = map[string]interface{}{}
	}
	entry["enabled"] = enabled
	plugins[name] = entry
	cfg["plugins"] = plugins
	return "", writeJSONAtomic(p, cfg)
}

// ---- GitHub sources ----

type skillSource struct {
	Repo    Repo
	Subpath string // a folder inside the repository the link pointed at ("" = the whole repository)
}

var ghLink = regexp.MustCompile(`^(` + repoName + `)/(` + repoName + `)/(tree|blob)/([^/]+)(?:/(.*))?$`)

// parseSkillSource accepts what parseRepo does plus links to a folder or file inside a repository
// (github.com/o/r/tree/<branch>/<folder>, …/blob/<branch>/<folder>/SKILL.md). A branch name with a slash
// can't be told apart from a folder in such links: use owner/repo@branch for those.
func parseSkillSource(input string) (skillSource, error) {
	text := strings.TrimSpace(input)
	if i := strings.IndexAny(text, "?#"); i >= 0 {
		text = text[:i]
	}
	text = strings.TrimSuffix(text, "/")
	bare := ghPrefix.ReplaceAllString(text, "")
	if m := ghLink.FindStringSubmatch(bare); m != nil {
		ref, sub := m[4], strings.Trim(m[5], "/")
		if m[3] == "blob" {
			sub = path.Dir(sub)
			if sub == "." {
				sub = ""
			}
		}
		if !validPath(ref) || (sub != "" && !validPath(sub)) {
			return skillSource{}, fmt.Errorf("Invalid GitHub link: %s", input)
		}
		return skillSource{Repo: Repo{m[1], m[2], ref}, Subpath: sub}, nil
	}
	r, err := ParseRepo(text)
	if err != nil {
		return skillSource{}, err
	}
	return skillSource{Repo: r}, nil
}

// repoSnapshot is a downloaded view of a repository: its file list, and reading or copying files out of it.
type repoSnapshot interface {
	files() []treeEntry
	read(p string) (string, error)
	extract(prefix, dest string) error // every file under folder prefix ("" = the whole repository) into dest
	close()
}

// openSnapshot lists the repository with one GitHub API call, or downloads its archive into tmp when the API
// can't be used (out of requests on a shared mobile IP, for example).
func (a *AgentSkills) openSnapshot(r Repo, tmp string) (repoSnapshot, error) {
	files, err := a.m.tree(r)
	if err == nil {
		return &treeSnapshot{a.m, r, files}, nil
	}
	if strings.Contains(err.Error(), "not found") {
		return nil, err
	}
	z, zerr := openZipSnapshot(a.m, r, tmp)
	if zerr != nil {
		return nil, fmt.Errorf("Could not read %s: %v; archive: %v", r.slug(), err, zerr)
	}
	return z, nil
}

type treeSnapshot struct {
	m    *Manager
	r    Repo
	list []treeEntry
}

func (t *treeSnapshot) files() []treeEntry { return t.list }
func (t *treeSnapshot) close()             {}

func (t *treeSnapshot) read(p string) (string, error) {
	text, ok, err := t.m.text(t.m.rawURL(t.r, p))
	if err == nil && !ok {
		err = fmt.Errorf("%s not found", p)
	}
	return text, err
}

func (t *treeSnapshot) extract(prefix, dest string) error {
	var wanted []treeEntry
	var total int64
	for _, f := range t.list {
		if underPrefix(f.Path, prefix) {
			wanted = append(wanted, f)
			total += f.Size
		}
	}
	if len(wanted) == 0 {
		return fmt.Errorf("Folder %s not found", displayPath(prefix))
	}
	if len(wanted) > maxEntries || total > maxTotal {
		return fmt.Errorf("%s is too large", displayPath(prefix))
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return err
	}
	sem := make(chan struct{}, 6)
	errs := make(chan error, len(wanted))
	var wg sync.WaitGroup
	for _, f := range wanted {
		wg.Add(1)
		go func(f treeEntry) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			var err error
			for attempt := 0; attempt < 3; attempt++ {
				if err = t.m.downloadFile(t.m.rawURL(t.r, f.Path), dest, relTo(f.Path, prefix), nil); err == nil {
					break
				}
				time.Sleep(time.Duration(attempt+1) * 400 * time.Millisecond)
			}
			errs <- err
		}(f)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			return err
		}
	}
	return nil
}

type zipSnapshot struct {
	tmp     string
	zr      *zip.ReadCloser
	entries map[string]*zip.File // path inside the repository → entry
	list    []treeEntry
}

func openZipSnapshot(m *Manager, r Repo, tmp string) (*zipSnapshot, error) {
	resp, err := m.get(fmt.Sprintf("https://github.com/%s/%s/archive/%s.zip", r.Owner, r.Name, encodePath(r.ref())))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	f, err := os.CreateTemp(tmp, "repo-*.zip")
	if err != nil {
		return nil, err
	}
	_, err = io.Copy(f, io.LimitReader(resp.Body, 512<<20))
	f.Close()
	if err != nil {
		os.Remove(f.Name())
		return nil, err
	}
	zr, err := zip.OpenReader(f.Name())
	if err != nil {
		os.Remove(f.Name())
		return nil, err
	}
	z := &zipSnapshot{tmp: f.Name(), zr: zr, entries: map[string]*zip.File{}}
	for _, e := range zr.File {
		// entries are "<repo>-<ref>/<path inside the repository>"
		_, inner, found := strings.Cut(strings.ReplaceAll(e.Name, "\\", "/"), "/")
		if !found || inner == "" || e.FileInfo().IsDir() || e.Mode()&os.ModeSymlink != 0 {
			continue
		}
		z.entries[inner] = e
		z.list = append(z.list, treeEntry{Path: inner, Type: "blob", Size: int64(e.UncompressedSize64)})
	}
	return z, nil
}

func (z *zipSnapshot) files() []treeEntry { return z.list }

func (z *zipSnapshot) close() {
	z.zr.Close()
	os.Remove(z.tmp)
}

func (z *zipSnapshot) read(p string) (string, error) {
	e, ok := z.entries[p]
	if !ok {
		return "", fmt.Errorf("%s not found", p)
	}
	in, err := e.Open()
	if err != nil {
		return "", err
	}
	defer in.Close()
	data, err := io.ReadAll(io.LimitReader(in, 1<<20))
	return string(data), err
}

func (z *zipSnapshot) extract(prefix, dest string) error {
	count := 0
	var total int64
	for _, f := range z.list {
		if !underPrefix(f.Path, prefix) {
			continue
		}
		if count++; count > maxEntries {
			return fmt.Errorf("%s has too many files", displayPath(prefix))
		}
		if total += f.Size; total > maxTotal {
			return fmt.Errorf("%s is too large", displayPath(prefix))
		}
		target, err := safeTarget(dest, relTo(f.Path, prefix))
		if err != nil {
			return err
		}
		in, err := z.entries[f.Path].Open()
		if err != nil {
			return err
		}
		err = writeLimited(in, target, maxFileBytes)
		in.Close()
		if err != nil {
			return err
		}
	}
	if count == 0 {
		return fmt.Errorf("Folder %s not found", displayPath(prefix))
	}
	return nil
}

func underPrefix(p, prefix string) bool { return prefix == "" || strings.HasPrefix(p, prefix+"/") }

func relTo(p, prefix string) string {
	if prefix == "" {
		return p
	}
	return strings.TrimPrefix(p, prefix+"/")
}

func displayPath(p string) string {
	if p == "" {
		return "The repository"
	}
	return p
}

// ---- finding skills in a repository ----

// findSkillDirs returns every folder holding a SKILL.md under subpath; a SKILL.md inside another skill's
// folder belongs to that skill (examples, templates).
func findSkillDirs(files []treeEntry, subpath string) []string {
	var dirs []string
	for _, f := range files {
		if path.Base(f.Path) != skillFile {
			continue
		}
		dir := path.Dir(f.Path)
		if dir == "." {
			dir = ""
		}
		if subpath != "" && dir != subpath && !strings.HasPrefix(dir, subpath+"/") {
			continue
		}
		if skippedDir(dir) {
			continue
		}
		dirs = append(dirs, dir)
	}
	sort.Strings(dirs)
	var out []string
	for _, d := range dirs {
		nested := false
		for _, o := range out {
			if o == "" || strings.HasPrefix(d, o+"/") {
				nested = true
				break
			}
		}
		if !nested {
			out = append(out, d)
		}
	}
	return out
}

func skippedDir(dir string) bool {
	for _, part := range strings.Split(dir, "/") {
		if part == "node_modules" || part == ".git" {
			return true
		}
	}
	return false
}

// pluginKinds tells which agents' plugin formats the repository folder holds, so the app can point to the
// right Plugins page.
func pluginKinds(files []treeEntry, subpath string) []string {
	has := map[string]bool{}
	for _, f := range files {
		has[f.Path] = true
	}
	at := func(p string) bool {
		if subpath != "" {
			p = subpath + "/" + p
		}
		return has[p]
	}
	kinds := []string{}
	if at(".claude-plugin/marketplace.json") {
		kinds = append(kinds, "claude-marketplace")
	}
	if at(".claude-plugin/plugin.json") {
		kinds = append(kinds, "claude-plugin")
	}
	if at(".codex-plugin/plugin.json") || at(".agents/plugins/marketplace.json") {
		kinds = append(kinds, "codex-plugin")
	}
	if at("plugin.json") {
		kinds = append(kinds, "agy-plugin")
	}
	return kinds
}

var skillIDBad = regexp.MustCompile(`[^A-Za-z0-9._-]+`)

// skillID turns a folder or skill name into a folder name the agents accept.
func skillID(s string) string {
	id := strings.Trim(skillIDBad.ReplaceAllString(strings.TrimSpace(s), "-"), "-._")
	if len(id) > 64 {
		id = strings.Trim(id[:64], "-._")
	}
	if id == "" {
		return "skill"
	}
	return id
}

// parseFrontmatter reads name and description from a SKILL.md's YAML front matter (plain, quoted and
// block-scalar values).
func parseFrontmatter(text string) (name, desc string) {
	lines := strings.Split(strings.ReplaceAll(strings.TrimPrefix(text, "\uFEFF"), "\r\n", "\n"), "\n")
	if len(lines) == 0 || strings.TrimSpace(lines[0]) != "---" {
		return "", ""
	}
	for i := 1; i < len(lines); i++ {
		line := lines[i]
		if strings.TrimSpace(line) == "---" {
			break
		}
		if line == "" || line[0] == ' ' || line[0] == '\t' || line[0] == '#' {
			continue
		}
		key, val, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		key, val = strings.TrimSpace(key), strings.TrimSpace(val)
		if key != "name" && key != "description" {
			continue
		}
		if strings.HasPrefix(val, ">") || strings.HasPrefix(val, "|") {
			var parts []string
			for i+1 < len(lines) && (strings.HasPrefix(lines[i+1], " ") || strings.HasPrefix(lines[i+1], "\t") || strings.TrimSpace(lines[i+1]) == "") {
				i++
				if t := strings.TrimSpace(lines[i]); t != "" {
					parts = append(parts, t)
				}
			}
			val = strings.Join(parts, " ")
		} else if len(val) >= 2 && (val[0] == '"' && val[len(val)-1] == '"' || val[0] == '\'' && val[len(val)-1] == '\'') {
			val = val[1 : len(val)-1]
		}
		if key == "name" {
			name = val
		} else {
			desc = val
		}
	}
	return name, desc
}

// describeRepoSkills reads the skills' front matter in parallel and gives each one a folder name (unique in this set).
func describeRepoSkills(snap repoSnapshot, r Repo, dirs []string, fixed map[string]string) []repoSkill {
	out := make([]repoSkill, len(dirs))
	sem := make(chan struct{}, 8)
	var wg sync.WaitGroup
	for i, d := range dirs {
		wg.Add(1)
		go func(i int, d string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			p := skillFile
			if d != "" {
				p = d + "/" + skillFile
			}
			s := repoSkill{Path: d}
			if text, err := snap.read(p); err == nil {
				s.Name, s.Description = parseFrontmatter(text)
			}
			out[i] = s
		}(i, d)
	}
	wg.Wait()
	used := map[string]bool{}
	for i := range out {
		if id, ok := fixed[out[i].Path]; ok {
			out[i].ID = id
		} else {
			base := path.Base(out[i].Path)
			if out[i].Path == "" {
				base = out[i].Name
				if base == "" {
					base = r.Name
				}
			}
			id := skillID(base)
			for n := 2; used[strings.ToLower(id)]; n++ {
				id = fmt.Sprintf("%s-%d", skillID(base), n)
			}
			out[i].ID = id
		}
		used[strings.ToLower(out[i].ID)] = true
		if out[i].Name == "" {
			out[i].Name = out[i].ID
		}
	}
	return out
}

func (a *AgentSkills) tmpDir() (string, error) {
	root := filepath.Join(a.home, ".antigem", "tmp")
	if err := os.MkdirAll(root, 0o755); err != nil {
		return "", err
	}
	return os.MkdirTemp(root, "skills-*")
}

func (a *AgentSkills) inspect(source, agent string) (map[string]interface{}, error) {
	src, err := parseSkillSource(source)
	if err != nil {
		return nil, err
	}
	tmp, err := a.tmpDir()
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(tmp)
	snap, err := a.snapshot(src.Repo, tmp)
	if err != nil {
		return nil, err
	}
	defer snap.close()
	dirs := findSkillDirs(snap.files(), src.Subpath)
	if len(dirs) > maxRepoSkills {
		return nil, fmt.Errorf("%s has %d skills; link to the folder of the ones you want", src.Repo.slug(), len(dirs))
	}
	skills := describeRepoSkills(snap, src.Repo, dirs, nil)
	if dir, err := a.skillsDir(agent); err == nil {
		for i := range skills {
			target := filepath.Join(dir, skills[i].ID)
			if _, err := os.Lstat(target); err == nil {
				skills[i].Exists = true
				if s := readSkillSource(target); s != nil && strings.EqualFold(s.Repo, src.Repo.slug()) && s.Path == skills[i].Path {
					skills[i].SameSource = true
				}
			}
		}
	}
	return map[string]interface{}{
		"repo": src.Repo.slug(), "ref": src.Repo.Ref, "subpath": src.Subpath,
		"skills": skills, "plugin_kinds": pluginKinds(snap.files(), src.Subpath),
	}, nil
}

func (a *AgentSkills) install(agent, source string, paths []string, replace bool) ([]string, []string, error) {
	src, err := parseSkillSource(source)
	if err != nil {
		return nil, nil, err
	}
	return a.installDirs(agent, src.Repo, nil, nil, replace, installFilter{subpath: src.Subpath, paths: paths})
}

type installFilter struct {
	subpath string
	paths   []string
}

// installDirs downloads skill folders of r into the agent's skills folder. dirs: exactly these folders (an
// update); otherwise every skill under filter.subpath, or only filter.paths. ids fixes folder names by path.
func (a *AgentSkills) installDirs(agent string, r Repo, dirs []string, ids map[string]string, replace bool, filter ...installFilter) ([]string, []string, error) {
	dir, err := a.skillsDir(agent)
	if err != nil {
		return nil, nil, err
	}
	tmp, err := a.tmpDir()
	if err != nil {
		return nil, nil, err
	}
	defer os.RemoveAll(tmp)
	snap, err := a.snapshot(r, tmp)
	if err != nil {
		return nil, nil, err
	}
	defer snap.close()

	found := findSkillDirs(snap.files(), "")
	if dirs == nil {
		var f installFilter
		if len(filter) > 0 {
			f = filter[0]
		}
		found = findSkillDirs(snap.files(), f.subpath)
		if len(f.paths) > 0 {
			want := map[string]bool{}
			for _, p := range f.paths {
				want[strings.Trim(p, "/")] = true
			}
			var picked []string
			for _, d := range found {
				if want[d] {
					picked = append(picked, d)
					delete(want, d)
				}
			}
			for p := range want {
				return nil, nil, fmt.Errorf("No skill in %s at %s", r.slug(), displayPath(p))
			}
			found = picked
		}
	} else {
		have := map[string]bool{}
		for _, d := range found {
			have[d] = true
		}
		for _, d := range dirs {
			if !have[d] {
				return nil, nil, fmt.Errorf("%s no longer has a skill at %s", r.slug(), displayPath(d))
			}
		}
		found = dirs
	}
	if len(found) == 0 {
		return nil, nil, fmt.Errorf("No skills (folders with a %s) found in %s", skillFile, r.slug())
	}
	if len(found) > maxRepoSkills {
		return nil, nil, fmt.Errorf("Too many skills (%d) at once", len(found))
	}
	skills := describeRepoSkills(snap, r, found, ids)

	// download and stage everything first, so a failed download leaves the skills folder as it was
	stage := filepath.Join(tmp, "stage")
	now := time.Now().UTC().Format(time.RFC3339)
	for _, s := range skills {
		into := filepath.Join(stage, s.ID)
		if err := snap.extract(s.Path, into); err != nil {
			return nil, nil, err
		}
		if _, err := os.Stat(filepath.Join(into, skillFile)); err != nil {
			return nil, nil, fmt.Errorf("%s has no %s", displayPath(s.Path), skillFile)
		}
		meta, _ := json.MarshalIndent(SkillSource{Repo: r.slug(), Ref: r.Ref, Path: s.Path, InstalledAt: now}, "", "  ")
		if err := os.WriteFile(filepath.Join(into, sourceFile), meta, 0o644); err != nil {
			return nil, nil, err
		}
	}

	a.mu.Lock()
	defer a.mu.Unlock()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, nil, err
	}
	if !replace {
		var clash []string
		for _, s := range skills {
			target := filepath.Join(dir, s.ID)
			if _, err := os.Lstat(target); err == nil {
				if src := readSkillSource(target); src == nil || !strings.EqualFold(src.Repo, r.slug()) || src.Path != s.Path {
					clash = append(clash, s.ID)
				}
			}
		}
		if len(clash) > 0 {
			return nil, nil, fmt.Errorf("A different skill already uses the name %s; remove it first", strings.Join(clash, ", "))
		}
	}
	var installed, replaced []string
	for _, s := range skills {
		target := filepath.Join(dir, s.ID)
		if fi, err := os.Lstat(target); err == nil {
			old := filepath.Join(tmp, "old-"+s.ID)
			if fi.Mode()&os.ModeSymlink != 0 {
				err = os.Remove(target)
			} else {
				err = moveDir(target, old)
			}
			if err != nil {
				return installed, replaced, fmt.Errorf("Could not replace %s: %v", s.ID, err)
			}
			replaced = append(replaced, s.ID)
		}
		if err := moveDir(filepath.Join(stage, s.ID), target); err != nil {
			return installed, replaced, fmt.Errorf("Could not add %s: %v", s.ID, err)
		}
		installed = append(installed, s.ID)
	}
	return installed, replaced, nil
}

// moveDir renames, or copies and deletes when the two folders are on different file systems.
func moveDir(from, to string) error {
	if err := os.Rename(from, to); err == nil {
		return nil
	}
	if err := copyDir(from, to); err != nil {
		os.RemoveAll(to)
		return err
	}
	return os.RemoveAll(from)
}

func copyDir(from, to string) error {
	return filepath.Walk(from, func(p string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(from, p)
		if err != nil {
			return err
		}
		target := filepath.Join(to, rel)
		switch {
		case fi.IsDir():
			return os.MkdirAll(target, 0o755)
		case fi.Mode().IsRegular():
			in, err := os.Open(p)
			if err != nil {
				return err
			}
			defer in.Close()
			out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, fi.Mode().Perm())
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, in); err != nil {
				out.Close()
				return err
			}
			return out.Close()
		}
		return nil
	})
}

// ---- Antigravity plugins from GitHub ----

// installAgyPlugin downloads the repository (or the folder the link points at) and hands it to
// `agy plugin install <folder>`, agy's own installer: it reads Antigravity and Claude Code plugin folders and
// Claude Code marketplaces, and copies them into ~/.gemini/config/plugins.
func (a *AgentSkills) installAgyPlugin(source string) (string, error) {
	bin := ""
	if a.agyBin != nil {
		bin = a.agyBin()
	}
	if fi, err := os.Stat(bin); bin == "" || err != nil || fi.IsDir() {
		return "", errors.New("The agy CLI is not installed (Settings → Antigravity → install agy)")
	}
	src, err := parseSkillSource(source)
	if err != nil {
		return "", err
	}
	tmp, err := a.tmpDir()
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)
	snap, err := a.snapshot(src.Repo, tmp)
	if err != nil {
		return "", err
	}
	defer snap.close()
	// agy names a plugin without a manifest name after its folder
	name := src.Repo.Name
	if src.Subpath != "" {
		name = path.Base(src.Subpath)
	}
	folder := filepath.Join(tmp, "src", skillID(name))
	if err := snap.extract(src.Subpath, folder); err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, "plugin", "install", folder)
	cmd.Dir = a.home
	cmd.Env = os.Environ()
	out, err := cmd.CombinedOutput()
	text := strings.TrimSpace(string(out))
	if err != nil {
		msg := lastLines(text, 6)
		if msg == "" {
			msg = err.Error()
		}
		return text, fmt.Errorf("agy could not install it: %s", msg)
	}
	return text, nil
}

func lastLines(s string, n int) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}
