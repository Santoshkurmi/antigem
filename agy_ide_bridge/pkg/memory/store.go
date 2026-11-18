// Package memory is AntiGem's agent memory: short Markdown memory files the agents read with their own file tools,
// and a cleaned copy of every chat (the user's messages and the assistants' replies) for looking up earlier work.
// Everything lives on the machine the agents run on, so it works the same with the bridge on the phone or on a server.
// The app turns it on per agent and attaches the text from /api/memory/context to the first message of a chat; the
// agents save memories by writing an ```antigem-memory block in their reply, which the app sends back with /sync.
//
//	~/.antigem/memory/                      readable by the agents (they never write here themselves)
//	  INDEX.md                              every memory, one line each
//	  global/<name>.md                      memories for every project
//	  projects/<name>-<hash8>/<name>.md     memories for one project
//	  history/<name>-<hash8>/INDEX.md       that project's past chats, newest first
//	  history/<name>-<hash8>/<date>_<chat>_<title>.md
//	~/.antigem_bridge/memory/state.json     applied memory blocks (for undo) and chat → history file
package memory

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"

	"gemini-server/pkg/config"
)

const (
	ModeAlways  = "always"
	ModePassive = "passive"

	ScopeGlobal  = "global"
	ScopeProject = "project"

	noProjectDir = "no-project"
	maxApplied   = 5000
)

// Memory is one memory file.
type Memory struct {
	ID          string `json:"id"`          // path under the memory folder: global/x.md or projects/<dir>/x.md
	File        string `json:"file"`        // x.md
	Scope       string `json:"scope"`       // global | project
	Project     string `json:"project"`     // the project's path (project scope)
	ProjectDir  string `json:"project_dir"` // the projects/<dir>/ folder (project scope)
	Mode        string `json:"mode"`        // always | passive
	Description string `json:"description"` // one line
	Text        string `json:"text"`
	Updated     int64  `json:"updated"` // unix ms
	Agent       string `json:"agent"`   // who saved it: claude, codex, opencode, agy or user
	Source      string `json:"source"`  // the history file (under the memory folder) of the chat that saved it
	Path        string `json:"path"`    // absolute path on this machine
}

// fileChange is one memory file as it was before a block changed it (Prev nil: it did not exist).
type fileChange struct {
	ID   string  `json:"id"`
	Prev *string `json:"prev"`
}

// appliedBlock records what one ```antigem-memory block did, so it is applied once and can be undone.
type appliedBlock struct {
	ID          string       `json:"id"`
	Action      string       `json:"action"` // saved | updated | deleted | error
	Description string       `json:"description"`
	Error       string       `json:"error,omitempty"`
	Changes     []fileChange `json:"changes,omitempty"`
	ChatID      string       `json:"chat_id,omitempty"`
	At          int64        `json:"at"`
	Undone      bool         `json:"undone,omitempty"`
}

// chatEntry is one chat's history file.
type chatEntry struct {
	File    string `json:"file"` // under the memory folder: history/<dir>/<name>.md
	Title   string `json:"title"`
	Agent   string `json:"agent"`
	Project string `json:"project"`
	Started int64  `json:"started"`
	Updated int64  `json:"updated"`
	Turns   int    `json:"turns"`            // turns in the file when the bridge last wrote it
	Edited  bool   `json:"edited,omitempty"` // the user edited it: kept as is, new turns are added at the end
}

type state struct {
	Applied map[string]*appliedBlock `json:"applied"`
	Chats   map[string]*chatEntry    `json:"chats"`
	// chats whose history the user removed (or that were deleted): never saved again
	Excluded map[string]int64 `json:"excluded"`
}

// Manager serves /api/memory/.
type Manager struct {
	root      string // ~/.antigem/memory
	statePath string // ~/.antigem_bridge/memory/state.json

	mu sync.Mutex
	st *state
}

func NewManager(homeDir string) *Manager {
	return &Manager{
		root:      filepath.Join(homeDir, ".antigem", "memory"),
		statePath: filepath.Join(homeDir, ".antigem_bridge", "memory", "state.json"),
	}
}

// Root is the memory folder the agents read.
func (m *Manager) Root() string { return m.root }

// ---------------------------------------------------------------------------------------------- state

func (m *Manager) stateLocked() *state {
	if m.st != nil {
		return m.st
	}
	st := &state{}
	if b, err := os.ReadFile(m.statePath); err == nil {
		_ = json.Unmarshal(b, st)
	}
	if st.Applied == nil {
		st.Applied = map[string]*appliedBlock{}
	}
	if st.Chats == nil {
		st.Chats = map[string]*chatEntry{}
	}
	if st.Excluded == nil {
		st.Excluded = map[string]int64{}
	}
	m.st = st
	return st
}

func (m *Manager) saveStateLocked() error {
	st := m.stateLocked()
	if len(st.Applied) > maxApplied {
		keys := make([]string, 0, len(st.Applied))
		for k := range st.Applied {
			keys = append(keys, k)
		}
		sort.Slice(keys, func(i, j int) bool { return st.Applied[keys[i]].At < st.Applied[keys[j]].At })
		for _, k := range keys[:len(keys)-maxApplied] {
			delete(st.Applied, k)
		}
	}
	if len(st.Excluded) > maxApplied {
		keys := make([]string, 0, len(st.Excluded))
		for k := range st.Excluded {
			keys = append(keys, k)
		}
		sort.Slice(keys, func(i, j int) bool { return st.Excluded[keys[i]] < st.Excluded[keys[j]] })
		for _, k := range keys[:len(keys)-maxApplied] {
			delete(st.Excluded, k)
		}
	}
	b, err := json.MarshalIndent(st, "", " ")
	if err != nil {
		return err
	}
	return writeFileAtomic(m.statePath, b)
}

// ---------------------------------------------------------------------------------------------- paths and names

func normPath(p string) string {
	p = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(p), "file://"))
	if p == "" {
		return ""
	}
	p = config.ExpandHome(p)
	if abs, err := filepath.Abs(p); err == nil {
		p = abs
	}
	p = filepath.Clean(p)
	if real, err := filepath.EvalSymlinks(p); err == nil {
		p = real
	}
	return p
}

// projectDir is a project's folder name: its own folder name plus a short hash of its full path ("antigem-3f9a1c2b").
func projectDir(path string) string {
	if path == "" {
		return ""
	}
	h := sha1.Sum([]byte(path))
	base := slug(filepath.Base(path), 40)
	if base == "" {
		base = "project"
	}
	return base + "-" + hex.EncodeToString(h[:])[:8]
}

// slug keeps letters and digits (any script), '_' and single '-' between words, lower case, at most max runes.
func slug(s string, max int) string {
	var b strings.Builder
	dash := false
	n := 0
	for _, r := range strings.ToLower(strings.TrimSpace(s)) {
		if n >= max {
			break
		}
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '_' {
			b.WriteRune(r)
			dash = false
			n++
		} else if !dash && b.Len() > 0 {
			b.WriteRune('-')
			dash = true
			n++
		}
	}
	return strings.Trim(b.String(), "-")
}

// cleanFile turns a name the model or the user gave into a memory file name ("Search DB" → "search-db.md").
func cleanFile(name string) string {
	name = strings.TrimSpace(name)
	name = strings.TrimSuffix(strings.TrimSuffix(name, ".md"), ".MD")
	if i := strings.LastIndexAny(name, `/\`); i >= 0 {
		name = name[i+1:]
	}
	s := slug(name, 60)
	if s == "" || s == "index" {
		return ""
	}
	return s + ".md"
}

func idFor(scope, dir, file string) string {
	if scope == ScopeProject && dir != "" {
		return "projects/" + dir + "/" + file
	}
	return "global/" + file
}

// validID: global/<file>.md or projects/<dir>/<file>.md, nothing else.
func validID(id string) bool {
	parts := strings.Split(id, "/")
	ok := func(s string) bool { return s != "" && s != "." && s != ".." && !strings.ContainsAny(s, `\`) }
	switch {
	case len(parts) == 2 && parts[0] == "global":
		return ok(parts[1]) && strings.HasSuffix(parts[1], ".md")
	case len(parts) == 3 && parts[0] == "projects":
		return ok(parts[1]) && ok(parts[2]) && strings.HasSuffix(parts[2], ".md")
	}
	return false
}

func (m *Manager) abs(id string) string { return filepath.Join(m.root, filepath.FromSlash(id)) }

func writeFileAtomic(path string, b []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// ---------------------------------------------------------------------------------------------- memory files

func oneLine(s string) string { return strings.Join(strings.Fields(s), " ") }

func firstLine(s string, max int) string {
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(strings.TrimLeft(strings.TrimSpace(l), "#-*> ")); l != "" {
			r := []rune(l)
			if len(r) > max {
				return string(r[:max-1]) + "…"
			}
			return l
		}
	}
	return ""
}

func render(mem *Memory) string {
	var b strings.Builder
	b.WriteString("---\n")
	line := func(k, v string) {
		if v = oneLine(v); v != "" {
			fmt.Fprintf(&b, "%s: %s\n", k, v)
		}
	}
	line("description", mem.Description)
	line("mode", mem.Mode)
	line("scope", mem.Scope)
	line("project", mem.Project)
	if mem.Updated > 0 {
		line("updated", time.UnixMilli(mem.Updated).UTC().Format(time.RFC3339))
	}
	line("agent", mem.Agent)
	line("source", mem.Source)
	b.WriteString("---\n")
	b.WriteString(strings.TrimSpace(mem.Text))
	b.WriteString("\n")
	return b.String()
}

// parseFile reads a memory file's front matter and text; files edited by hand without front matter still work.
func parseFile(id, content string, mod time.Time) *Memory {
	mem := &Memory{ID: id, Mode: ModePassive}
	parts := strings.Split(id, "/")
	mem.File = parts[len(parts)-1]
	if parts[0] == "projects" {
		mem.Scope, mem.ProjectDir = ScopeProject, parts[1]
	} else {
		mem.Scope = ScopeGlobal
	}
	lines := strings.Split(strings.ReplaceAll(content, "\r\n", "\n"), "\n")
	body := strings.Join(lines, "\n")
	if len(lines) > 1 && strings.TrimSpace(lines[0]) == "---" {
		end := -1
		for i := 1; i < len(lines); i++ {
			if strings.TrimSpace(lines[i]) == "---" {
				end = i
				break
			}
		}
		if end > 0 {
			body = strings.Join(lines[end+1:], "\n")
			for _, l := range lines[1:end] {
				k, v, ok := strings.Cut(l, ":")
				if !ok {
					continue
				}
				v = strings.TrimSpace(v)
				switch strings.ToLower(strings.TrimSpace(k)) {
				case "description":
					mem.Description = v
				case "mode":
					mem.Mode = normMode(v, ModePassive)
				case "project":
					mem.Project = v
				case "updated":
					if t, err := time.Parse(time.RFC3339, v); err == nil {
						mem.Updated = t.UnixMilli()
					}
				case "agent":
					mem.Agent = v
				case "source":
					mem.Source = v
				}
			}
		}
	}
	mem.Text = strings.TrimSpace(body)
	if mem.Description == "" {
		mem.Description = firstLine(mem.Text, 120)
	}
	if mem.Updated == 0 {
		mem.Updated = mod.UnixMilli()
	}
	return mem
}

func normMode(v, def string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "always", "pinned", "default", "always-on":
		return ModeAlways
	case "passive", "on-demand", "on demand", "relevant":
		return ModePassive
	}
	return def
}

func normScope(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "global", "all", "all projects", "user", "everywhere":
		return ScopeGlobal
	case "project", "this project", "local":
		return ScopeProject
	}
	return ""
}

// readLocked reads one memory, nil when it does not exist.
func (m *Manager) readLocked(id string) *Memory {
	p := m.abs(id)
	b, err := os.ReadFile(p)
	if err != nil {
		return nil
	}
	fi, _ := os.Stat(p)
	mod := time.Now()
	if fi != nil {
		mod = fi.ModTime()
	}
	mem := parseFile(id, string(b), mod)
	mem.Path = p
	return mem
}

func (m *Manager) rawLocked(id string) *string {
	b, err := os.ReadFile(m.abs(id))
	if err != nil {
		return nil
	}
	s := string(b)
	return &s
}

// allLocked lists every memory, newest change first.
func (m *Manager) allLocked() []*Memory {
	var out []*Memory
	add := func(dirID string) {
		entries, err := os.ReadDir(m.abs(dirID))
		if err != nil {
			return
		}
		for _, e := range entries {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") || strings.HasPrefix(e.Name(), ".") {
				continue
			}
			if mem := m.readLocked(dirID + "/" + e.Name()); mem != nil {
				out = append(out, mem)
			}
		}
	}
	add("global")
	if dirs, err := os.ReadDir(filepath.Join(m.root, "projects")); err == nil {
		for _, d := range dirs {
			if d.IsDir() {
				add("projects/" + d.Name())
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Updated > out[j].Updated })
	return out
}

func (m *Manager) writeLocked(mem *Memory) error {
	if !validID(mem.ID) {
		return fmt.Errorf("bad memory id %q", mem.ID)
	}
	return writeFileAtomic(m.abs(mem.ID), []byte(render(mem)))
}

// restoreLocked puts a file back as it was (prev nil: removes it).
func (m *Manager) restoreLocked(id string, prev *string) error {
	if !validID(id) {
		return fmt.Errorf("bad memory id %q", id)
	}
	if prev == nil {
		if err := os.Remove(m.abs(id)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	return writeFileAtomic(m.abs(id), []byte(*prev))
}

// writeIndexLocked rewrites INDEX.md: every memory, one line each, grouped by scope.
func (m *Manager) writeIndexLocked() {
	all := m.allLocked()
	var b strings.Builder
	b.WriteString("# AntiGem agent memory\n\n")
	b.WriteString("Every memory, one line each: file · mode · description · last updated. Open a file to read it in full.\n")
	if len(all) == 0 {
		b.WriteString("\nNo memories saved yet.\n")
	}
	groups := map[string][]*Memory{}
	paths := map[string]string{}
	for _, mem := range all {
		g := "global"
		if mem.Scope == ScopeProject {
			g = mem.ProjectDir
			if paths[g] == "" {
				paths[g] = mem.Project
			}
		}
		groups[g] = append(groups[g], mem)
	}
	write := func(title string, list []*Memory) {
		if len(list) == 0 {
			return
		}
		b.WriteString("\n## " + title + "\n")
		for _, mem := range list {
			fmt.Fprintf(&b, "- %s · %s · %s · %s\n", mem.ID, mem.Mode, oneLine(mem.Description), day(mem.Updated))
		}
	}
	write("All projects (global/)", groups["global"])
	var dirs []string
	for g := range groups {
		if g != "global" {
			dirs = append(dirs, g)
		}
	}
	sort.Strings(dirs)
	for _, d := range dirs {
		title := "Project " + d
		if paths[d] != "" {
			title = "Project " + filepath.Base(paths[d]) + " (" + paths[d] + ")"
		}
		write(title, groups[d])
	}
	_ = writeFileAtomic(filepath.Join(m.root, "INDEX.md"), []byte(b.String()))
}

func day(ms int64) string {
	if ms <= 0 {
		return ""
	}
	return time.UnixMilli(ms).Format("2006-01-02")
}

// ---------------------------------------------------------------------------------------------- the settings screen

// List returns every memory.
func (m *Manager) List() []*Memory {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.allLocked()
}

// SaveRequest is a memory written or edited in the app's settings.
type SaveRequest struct {
	ID          string `json:"id"` // the memory being edited ("" for a new one)
	File        string `json:"file"`
	Scope       string `json:"scope"`
	Project     string `json:"project"`
	Mode        string `json:"mode"`
	Description string `json:"description"`
	Text        string `json:"text"`
}

// Save writes a memory from the settings screen (moving it when its name or scope changed).
func (m *Manager) Save(req SaveRequest) (*Memory, error) {
	text := strings.TrimSpace(req.Text)
	if text == "" {
		return nil, errors.New("the memory has no text")
	}
	file := cleanFile(req.File)
	if file == "" {
		file = cleanFile(firstLine(req.Description+"\n"+text, 60))
	}
	if file == "" {
		return nil, errors.New("give the memory a name")
	}
	project := normPath(req.Project)
	scope := normScope(req.Scope)
	if scope == "" {
		scope = ScopeGlobal
	}
	if scope == ScopeProject && project == "" {
		return nil, errors.New("pick the project for a project memory")
	}
	dir := ""
	if scope == ScopeProject {
		dir = projectDir(project)
	}
	id := idFor(scope, dir, file)

	m.mu.Lock()
	defer m.mu.Unlock()
	var old *Memory
	if req.ID != "" {
		if !validID(req.ID) {
			return nil, fmt.Errorf("bad memory id %q", req.ID)
		}
		old = m.readLocked(req.ID)
	}
	if req.ID != id && m.readLocked(id) != nil {
		return nil, fmt.Errorf("a memory named %s already exists there", file)
	}
	mem := &Memory{
		ID: id, File: file, Scope: scope, Mode: normMode(req.Mode, ModePassive),
		Description: oneLine(req.Description), Text: text, Updated: time.Now().UnixMilli(), Agent: "user",
	}
	if scope == ScopeProject {
		mem.Project, mem.ProjectDir = project, dir
	}
	if mem.Description == "" {
		mem.Description = firstLine(text, 120)
	}
	if old != nil {
		mem.Source = old.Source
	}
	if err := m.writeLocked(mem); err != nil {
		return nil, err
	}
	if old != nil && old.ID != id {
		_ = m.restoreLocked(old.ID, nil)
	}
	m.writeIndexLocked()
	mem.Path = m.abs(id)
	return mem, nil
}

// Delete removes a memory.
func (m *Manager) Delete(id string) error {
	if !validID(id) {
		return fmt.Errorf("bad memory id %q", id)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if err := m.restoreLocked(id, nil); err != nil {
		return err
	}
	m.writeIndexLocked()
	return nil
}
