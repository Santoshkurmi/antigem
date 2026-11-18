// Package checkpoint keeps "before this message" copies of a project's files for every chat agent (Claude Code,
// Codex, OpenCode, Antigravity) and puts them back on request.
//
// Each project gets its own hidden git repository under ~/.antigem_bridge/checkpoints/projects/<key>/git, with the
// project as its work tree. The user's own .git (and every nested repository's) is only ever read: no branch, commit,
// stash, ref or index of theirs changes. A checkpoint is a set of trees (the project, and each nested git repository
// inside it on its own), each kept alive by a ref of its own, with no parent: deleting an old one never touches newer
// ones.
package checkpoint

import (
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"gemini-server/pkg/config"
)

const (
	ModeOff      = "off"
	ModeSelected = "selected"
	ModeAll      = "all"

	KindTurn       = "turn"
	KindPreRestore = "pre-restore"

	maxUnits       = 64 // project + nested repositories
	maxUnitDepth   = 4
	maxSkippedList = 200
)

// Settings is what the app's Settings → Project checkpoints page edits.
type Settings struct {
	Mode          string            `json:"mode"`           // off | selected | all
	RetentionDays int               `json:"retention_days"` // 0 = keep
	MaxPerChat    int               `json:"max_per_chat"`   // 0 = no limit
	MaxProjectMB  int               `json:"max_project_mb"` // 0 = no limit
	MaxFileMB     int               `json:"max_file_mb"`    // bigger files are not saved
	Projects      map[string]string `json:"projects"`       // project path → "on" | "off" (asked once)
}

func defaultSettings() Settings {
	return Settings{Mode: ModeOff, RetentionDays: 30, MaxPerChat: 100, MaxProjectMB: 500, MaxFileMB: 20, Projects: map[string]string{}}
}

// UnitSnap is one repository's files in a checkpoint: the project itself (".") or a nested repository.
type UnitSnap struct {
	Path    string   `json:"path"`
	Tree    string   `json:"tree"`
	Head    string   `json:"head,omitempty"`    // branch@commit of the user's repository at that time
	Skipped []string `json:"skipped,omitempty"` // files not saved (too large / unreadable)
}

// Record is one checkpoint.
type Record struct {
	ID           string     `json:"id"`
	Time         int64      `json:"time"` // unix ms
	Kind         string     `json:"kind"` // turn | pre-restore
	Agent        string     `json:"agent,omitempty"`
	ChatID       string     `json:"chat_id,omitempty"`
	Keys         []string   `json:"keys,omitempty"` // the agent's ids of the message (the app looks it up by them)
	Title        string     `json:"title,omitempty"`
	MidTurn      bool       `json:"mid_turn,omitempty"` // sent while the agent was still working
	Late         bool       `json:"late,omitempty"`     // the copy finished after the app stopped waiting
	TookMs       int64      `json:"took_ms,omitempty"`
	RestoredFrom string     `json:"restored_from,omitempty"` // pre-restore: the checkpoint that was being restored
	Units        []UnitSnap `json:"units"`
}

// Summary is what lists and lookups return about a checkpoint.
type Summary struct {
	ID           string `json:"id"`
	Time         int64  `json:"time"`
	Kind         string `json:"kind"`
	Agent        string `json:"agent,omitempty"`
	ChatID       string `json:"chat_id,omitempty"`
	Title        string `json:"title,omitempty"`
	MidTurn      bool   `json:"mid_turn,omitempty"`
	Late         bool   `json:"late,omitempty"`
	TookMs       int64  `json:"took_ms,omitempty"`
	RestoredFrom string `json:"restored_from,omitempty"`
	Repos        int    `json:"repos"`
	Skipped      int    `json:"skipped,omitempty"`
}

func (r *Record) summary() Summary {
	sk := 0
	for _, u := range r.Units {
		sk += len(u.Skipped)
	}
	return Summary{ID: r.ID, Time: r.Time, Kind: r.Kind, Agent: r.Agent, ChatID: r.ChatID, Title: r.Title,
		MidTurn: r.MidTurn, Late: r.Late, TookMs: r.TookMs, RestoredFrom: r.RestoredFrom, Repos: len(r.Units), Skipped: sk}
}

type project struct {
	mu      sync.Mutex // snapshots, restores, records
	key     string
	path    string
	dir     string
	records []*Record
	opts    projectOpts
	warmed  bool

	lastCleanup time.Time
	gcRunning   atomic.Bool

	sizeMu    sync.Mutex
	sizeBytes int64
	sizeAt    time.Time
}

func (p *project) gitDir() string               { return filepath.Join(p.dir, "git") }
func (p *project) indexPath(unit string) string { return filepath.Join(p.dir, "index", unitKey(unit)) }

// Manager is the checkpoint store; it serves /api/checkpoints/.
type Manager struct {
	root string

	setMu    sync.Mutex
	settings Settings

	projMu   sync.Mutex
	projects map[string]*project

	gitMu    sync.Mutex
	gitCache map[string]gitCacheEntry
}

type gitCacheEntry struct {
	isGit bool
	at    time.Time
}

func NewManager(homeDir string) *Manager {
	m := &Manager{
		root:     filepath.Join(homeDir, ".antigem_bridge", "checkpoints"),
		projects: map[string]*project{},
		gitCache: map[string]gitCacheEntry{},
	}
	m.settings = m.loadSettings()
	go m.cleanupLoop()
	return m
}

// ---------------------------------------------------------------------------------------------- settings

func (m *Manager) settingsPath() string { return filepath.Join(m.root, "settings.json") }

func (m *Manager) loadSettings() Settings {
	s := defaultSettings()
	if b, err := os.ReadFile(m.settingsPath()); err == nil {
		_ = json.Unmarshal(b, &s)
	}
	return sanitize(s)
}

func sanitize(s Settings) Settings {
	switch s.Mode {
	case ModeOff, ModeSelected, ModeAll:
	default:
		s.Mode = ModeOff
	}
	if s.RetentionDays < 0 {
		s.RetentionDays = 0
	}
	if s.MaxPerChat < 0 {
		s.MaxPerChat = 0
	}
	if s.MaxProjectMB < 0 {
		s.MaxProjectMB = 0
	}
	if s.MaxFileMB <= 0 {
		s.MaxFileMB = 20
	}
	if s.Projects == nil {
		s.Projects = map[string]string{}
	}
	return s
}

// Settings returns a copy of the settings.
func (m *Manager) Settings() Settings {
	m.setMu.Lock()
	defer m.setMu.Unlock()
	s := m.settings
	s.Projects = make(map[string]string, len(m.settings.Projects))
	for k, v := range m.settings.Projects {
		s.Projects[k] = v
	}
	return s
}

// SettingsPatch changes only the fields that are set.
type SettingsPatch struct {
	Mode          *string `json:"mode"`
	RetentionDays *int    `json:"retention_days"`
	MaxPerChat    *int    `json:"max_per_chat"`
	MaxProjectMB  *int    `json:"max_project_mb"`
	MaxFileMB     *int    `json:"max_file_mb"`
}

func (m *Manager) UpdateSettings(p SettingsPatch) (Settings, error) {
	m.setMu.Lock()
	s := m.settings
	if p.Mode != nil {
		s.Mode = *p.Mode
	}
	if p.RetentionDays != nil {
		s.RetentionDays = *p.RetentionDays
	}
	if p.MaxPerChat != nil {
		s.MaxPerChat = *p.MaxPerChat
	}
	if p.MaxProjectMB != nil {
		s.MaxProjectMB = *p.MaxProjectMB
	}
	if p.MaxFileMB != nil {
		s.MaxFileMB = *p.MaxFileMB
	}
	s = sanitize(s)
	m.settings = s
	err := m.saveSettingsLocked()
	m.setMu.Unlock()
	if p.RetentionDays != nil || p.MaxPerChat != nil || p.MaxProjectMB != nil {
		go m.cleanupAll(true)
	}
	return m.Settings(), err
}

func (m *Manager) saveSettingsLocked() error {
	return writeJSONAtomic(m.settingsPath(), m.settings)
}

// SetDecision remembers the user's answer for a project ("on", "off", or "" to ask again).
func (m *Manager) SetDecision(path, decision string) error {
	path = normPath(path)
	if path == "" {
		return errors.New("no project")
	}
	m.setMu.Lock()
	switch decision {
	case "on", "off":
		m.settings.Projects[path] = decision
	default:
		delete(m.settings.Projects, path)
	}
	err := m.saveSettingsLocked()
	m.setMu.Unlock()
	if decision == "on" {
		go m.warm(path)
	}
	return err
}

// ---------------------------------------------------------------------------------------------- tracking state

// ProjectState is whether a project's turns get checkpoints, and why.
type ProjectState struct {
	Path      string `json:"path"`
	Exists    bool   `json:"exists"`
	IsGit     bool   `json:"is_git"`
	Mode      string `json:"mode"`
	Decision  string `json:"decision"` // on | off | ""
	Tracked   bool   `json:"tracked"`
	ShouldAsk bool   `json:"should_ask"` // mode "selected" and never answered for this project
	Reason    string `json:"reason,omitempty"`
}

func (m *Manager) isGit(path string) bool {
	m.gitMu.Lock()
	if e, ok := m.gitCache[path]; ok && time.Since(e.at) < 15*time.Second {
		m.gitMu.Unlock()
		return e.isGit
	}
	m.gitMu.Unlock()
	v := isGitWorkTree(path)
	m.gitMu.Lock()
	m.gitCache[path] = gitCacheEntry{isGit: v, at: time.Now()}
	m.gitMu.Unlock()
	return v
}

func (m *Manager) State(path string) ProjectState {
	path = normPath(path)
	s := m.Settings()
	st := ProjectState{Path: path, Mode: s.Mode, Decision: s.Projects[path]}
	if path == "" {
		st.Reason = "no project"
		return st
	}
	if fi, err := os.Stat(path); err == nil && fi.IsDir() {
		st.Exists = true
		st.IsGit = m.isGit(path)
	}
	st.ShouldAsk = s.Mode == ModeSelected && st.Decision == "" && st.Exists
	switch {
	case s.Mode == ModeOff:
		st.Reason = "Project checkpoints are off"
	case !st.Exists:
		st.Reason = "The project folder does not exist"
	case s.Mode == ModeSelected && st.Decision != "on":
		st.Reason = "Not turned on for this project"
	case s.Mode == ModeAll && st.Decision == "off":
		st.Reason = "Turned off for this project"
	case !st.IsGit:
		st.Reason = "Not a git repository (run git init to start checkpoints)"
	default:
		st.Tracked = true
	}
	return st
}

// ---------------------------------------------------------------------------------------------- projects

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

func projectKey(path string) string {
	h := sha1.Sum([]byte(path))
	return hex.EncodeToString(h[:])[:16]
}

func unitKey(unit string) string {
	if unit == "." || unit == "" {
		return "root"
	}
	h := sha1.Sum([]byte(unit))
	return "u" + hex.EncodeToString(h[:])[:16]
}

// project returns the project's store, loading its records once (path must be normalized).
func (m *Manager) project(path string, create bool) (*project, error) {
	key := projectKey(path)
	m.projMu.Lock()
	defer m.projMu.Unlock()
	if p, ok := m.projects[key]; ok {
		return p, nil
	}
	dir := filepath.Join(m.root, "projects", key)
	if _, err := os.Stat(dir); err != nil {
		if !create {
			return nil, os.ErrNotExist
		}
		if err := os.MkdirAll(filepath.Join(dir, "index"), 0o700); err != nil {
			return nil, err
		}
		if err := writeJSONAtomic(filepath.Join(dir, "project.json"), map[string]interface{}{"path": path, "created": time.Now().UnixMilli()}); err != nil {
			return nil, err
		}
	}
	p := &project{key: key, path: path, dir: dir}
	if b, err := os.ReadFile(filepath.Join(dir, "records.json")); err == nil {
		_ = json.Unmarshal(b, &p.records)
	}
	sort.SliceStable(p.records, func(i, j int) bool { return p.records[i].Time < p.records[j].Time })
	m.projects[key] = p
	return p, nil
}

func (p *project) saveRecordsLocked() error {
	if p.records == nil {
		p.records = []*Record{}
	}
	return writeJSONAtomic(filepath.Join(p.dir, "records.json"), p.records)
}

func (p *project) find(id string) *Record {
	for _, r := range p.records {
		if r.ID == id {
			return r
		}
	}
	return nil
}

func writeJSONAtomic(path string, v interface{}) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(v, "", " ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func newID() string {
	var b [3]byte
	_, _ = rand.Read(b[:])
	return strconv.FormatInt(time.Now().UnixMilli(), 36) + hex.EncodeToString(b[:])
}

func cpRef(id string) string { return "refs/antigem/cp/" + id }

// ---------------------------------------------------------------------------------------------- turns

// TurnRequest is sent by the app right before a message goes to an agent.
type TurnRequest struct {
	Project    string `json:"project"`
	Agent      string `json:"agent"`
	ChatID     string `json:"chat_id"`
	Key        string `json:"key"`
	Title      string `json:"title"`
	MidTurn    bool   `json:"mid_turn"`
	DeadlineMs int64  `json:"deadline_ms"`
}

type TurnResult struct {
	Tracked    bool     `json:"tracked"`
	Reason     string   `json:"reason,omitempty"`
	Checkpoint *Summary `json:"checkpoint,omitempty"`
	Skipped    []string `json:"skipped,omitempty"`
}

// Turn saves a checkpoint of the project when it is tracked (else answers right away).
func (m *Manager) Turn(req TurnRequest) (*TurnResult, error) {
	st := m.State(req.Project)
	if !st.Tracked {
		return &TurnResult{Tracked: false, Reason: st.Reason}, nil
	}
	p, err := m.project(st.Path, true)
	if err != nil {
		return nil, err
	}
	start := time.Now()
	p.mu.Lock()
	units, err := m.snapshotLocked(p)
	if err != nil {
		p.mu.Unlock()
		return nil, err
	}
	took := time.Since(start)
	rec := &Record{
		ID: newID(), Time: start.UnixMilli(), Kind: KindTurn, Agent: req.Agent, ChatID: req.ChatID,
		Title: clip(req.Title, 120), MidTurn: req.MidTurn, TookMs: took.Milliseconds(), Units: units,
	}
	if req.Key != "" {
		rec.Keys = []string{req.Key}
	}
	if req.DeadlineMs > 0 && took.Milliseconds() > req.DeadlineMs {
		rec.Late = true
	}
	err = m.addRecordLocked(p, rec)
	p.mu.Unlock()
	if err != nil {
		return nil, err
	}
	m.maybeCleanup(p)
	sum := rec.summary()
	var skipped []string
	for _, u := range units {
		for _, s := range u.Skipped {
			skipped = append(skipped, joinUnit(u.Path, s))
		}
	}
	return &TurnResult{Tracked: true, Checkpoint: &sum, Skipped: skipped}, nil
}

// addRecordLocked keeps the record's trees alive with a ref and saves it.
func (m *Manager) addRecordLocked(p *project, rec *Record) error {
	holder, err := m.holderTree(p, rec.Units)
	if err != nil {
		return err
	}
	if _, err := m.shadow(p, "").run("update-ref", cpRef(rec.ID), holder); err != nil {
		return err
	}
	p.records = append(p.records, rec)
	return p.saveRecordsLocked()
}

// holderTree is one tree holding every unit's tree (so a single ref keeps them all).
func (m *Manager) holderTree(p *project, units []UnitSnap) (string, error) {
	var b strings.Builder
	for i, u := range units {
		fmt.Fprintf(&b, "040000 tree %s\tu%03d\x00", u.Tree, i)
	}
	out, err := m.shadowStdin(p, []byte(b.String())).run("mktree", "-z", "--missing")
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

// Bind adds another id of the message (Codex's turn id, known once the turn started).
func (m *Manager) Bind(path, id, key string) error {
	path = normPath(path)
	p, err := m.project(path, false)
	if err != nil {
		return errors.New("no checkpoints for this project")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	r := p.find(id)
	if r == nil {
		return errors.New("checkpoint not found")
	}
	for _, k := range r.Keys {
		if k == key {
			return nil
		}
	}
	r.Keys = append(r.Keys, key)
	return p.saveRecordsLocked()
}

// Lookup finds the checkpoints of messages by their ids (newest wins when one id was used twice).
func (m *Manager) Lookup(path string, keys []string) map[string]Summary {
	out := map[string]Summary{}
	path = normPath(path)
	if path == "" || len(keys) == 0 {
		return out
	}
	p, err := m.project(path, false)
	if err != nil {
		return out
	}
	want := make(map[string]bool, len(keys))
	for _, k := range keys {
		want[k] = true
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, r := range p.records {
		if r.Kind != KindTurn {
			continue
		}
		for _, k := range r.Keys {
			if want[k] {
				out[k] = r.summary()
			}
		}
	}
	return out
}

// History lists a project's checkpoints, newest first.
func (m *Manager) History(path string, limit int) ([]Summary, error) {
	path = normPath(path)
	p, err := m.project(path, false)
	if err != nil {
		return []Summary{}, nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]Summary, 0, len(p.records))
	for i := len(p.records) - 1; i >= 0; i-- {
		out = append(out, p.records[i].summary())
		if limit > 0 && len(out) >= limit {
			break
		}
	}
	return out, nil
}

// warm makes the first copy of a project right away (the slow one: every file is read), so the first message
// does not wait for it.
func (m *Manager) warm(path string) {
	st := m.State(path)
	if !st.Tracked {
		return
	}
	p, err := m.project(st.Path, true)
	if err != nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.warmed {
		return
	}
	if _, err := m.snapshotLocked(p); err == nil {
		p.warmed = true
	}
}

// WarmIfNeeded starts the first copy in the background when the project is tracked and has none yet.
func (m *Manager) WarmIfNeeded(st ProjectState) {
	if !st.Tracked {
		return
	}
	if _, err := os.Stat(filepath.Join(m.root, "projects", projectKey(st.Path), "index", unitKey("."))); err == nil {
		return
	}
	go m.warm(st.Path)
}

// ---------------------------------------------------------------------------------------------- storage list

type ProjectInfo struct {
	ProjectState
	Count     int   `json:"count"`
	SizeBytes int64 `json:"size_bytes"`
	LastTime  int64 `json:"last_time,omitempty"`
	HasData   bool  `json:"has_data"`
}

// Projects lists every project that has checkpoints or an answer saved.
func (m *Manager) Projects() []ProjectInfo {
	paths := map[string]bool{}
	for p := range m.Settings().Projects {
		paths[p] = true
	}
	entries, _ := os.ReadDir(filepath.Join(m.root, "projects"))
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		var meta struct {
			Path string `json:"path"`
		}
		if b, err := os.ReadFile(filepath.Join(m.root, "projects", e.Name(), "project.json")); err == nil && json.Unmarshal(b, &meta) == nil && meta.Path != "" {
			paths[meta.Path] = true
		}
	}
	out := make([]ProjectInfo, 0, len(paths))
	for path := range paths {
		info := ProjectInfo{ProjectState: m.State(path)}
		if p, err := m.project(path, false); err == nil {
			info.HasData = true
			p.mu.Lock()
			info.Count = len(p.records)
			if n := len(p.records); n > 0 {
				info.LastTime = p.records[n-1].Time
			}
			p.mu.Unlock()
			info.SizeBytes = p.size(false)
		}
		out = append(out, info)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].LastTime != out[j].LastTime {
			return out[i].LastTime > out[j].LastTime
		}
		return out[i].Path < out[j].Path
	})
	return out
}

func (p *project) size(fresh bool) int64 {
	p.sizeMu.Lock()
	defer p.sizeMu.Unlock()
	if !fresh && !p.sizeAt.IsZero() && time.Since(p.sizeAt) < 2*time.Minute {
		return p.sizeBytes
	}
	var total int64
	_ = filepath.WalkDir(p.dir, func(_ string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if !d.IsDir() {
			if fi, err := d.Info(); err == nil {
				total += fi.Size()
			}
		}
		return nil
	})
	p.sizeBytes = total
	p.sizeAt = time.Now()
	return total
}

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}

func joinUnit(unit, rel string) string {
	if unit == "." || unit == "" {
		return rel
	}
	return unit + "/" + rel
}
