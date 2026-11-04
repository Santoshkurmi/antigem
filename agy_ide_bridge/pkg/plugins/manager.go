// Package plugins manages custom AntiGem plugins (browser extensions with MCP tools and/or skills) on disk:
// listing, installing from ZIPs or GitHub repositories, updating, removing and exporting them. The app's
// Node extension host loads them; the app tells it to load or unload after each change made here.
package plugins

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	sourceFile  = ".antigem-source.json"
	backupDir   = ".antigem-backup"
	stagingDir  = ".antigem-staging"
	disabledCfg = "extensions.json"
)

var (
	idPattern     = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
	folderPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`)
	iconTypes     = map[string]bool{"png": true, "jpg": true, "jpeg": true, "webp": true, "svg": true}
)

// Plugin is one discovered plugin; JSON keys match the app's BrowserExtension.
type Plugin struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Version     string   `json:"version"`
	Description string   `json:"description"`
	ToolCount   int      `json:"toolCount"`
	SkillCount  int      `json:"skillCount"`
	FolderName  string   `json:"folderName"`
	Permissions []string `json:"permissions"`
	IsEnabled   bool     `json:"isEnabled"`
	DirPath     string   `json:"dirPath"`
	IconPath    string   `json:"iconPath"`
	Source      *Source  `json:"source,omitempty"`
	Location    string   `json:"location"`
	Skills      []Skill  `json:"skills"`
}

// Source is written as .antigem-source.json into repository installs so updates can be found later.
type Source struct {
	Repo string `json:"repo"`
	Ref  string `json:"ref"`
	Path string `json:"path"`
}

type root struct {
	dir      string
	location string // installed | bundled | user_folder
}

type Manager struct {
	home     string
	stateDir string     // the extension host's per-plugin storage files (<id>.json)
	roots    []root     // discovery order; the first plugin with an ID wins; roots[0] receives installs
	mu       sync.Mutex // plugin folders
	draftsMu sync.Mutex // developer drafts
	client   *http.Client
	installs sync.Map // target folder → *progressTracker of a running repository install
}

// NewManager uses the same plugin folders, in the same order, as the app: the app's private files
// directory is the parent of its terminal home.
func NewManager(home string) *Manager {
	files := filepath.Dir(home)
	return &Manager{
		home:     home,
		stateDir: filepath.Join(files, "browser-extensions", ".antigem-state"),
		roots: []root{
			{filepath.Join(home, "extensions"), "installed"},
			{filepath.Join(home, "projects", "antiGem", "extensions"), "user_folder"},
			{filepath.Join(files, "extensions"), "installed"},
			{filepath.Join(files, "browser-extensions"), "bundled"},
			{"/sdcard/antiGem/extensions", "user_folder"},
		},
		client: &http.Client{Timeout: 2 * time.Minute},
	}
}

func (m *Manager) managedRoot() string { return m.roots[0].dir }

// ---- discovery ----

func (m *Manager) scan() []Plugin {
	var out []Plugin
	seen := map[string]bool{}
	for _, r := range m.roots {
		entries, err := os.ReadDir(r.dir)
		if err != nil {
			continue
		}
		disabled := readDisabled(r.dir)
		sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
		for _, e := range entries {
			if !e.IsDir() || strings.HasPrefix(e.Name(), ".") {
				continue
			}
			dir := filepath.Join(r.dir, e.Name())
			manifest, err := readManifest(dir)
			if err != nil {
				continue
			}
			id := str(manifest["id"])
			if id == "" {
				id = e.Name()
			}
			if seen[id] {
				continue
			}
			seen[id] = true
			skills := listSkills(dir)
			p := Plugin{
				ID: id, Name: str(manifest["name"]), Version: str(manifest["version"]), Description: str(manifest["description"]),
				ToolCount: len(arr(manifest["mcp_tools"])), SkillCount: len(skills), FolderName: e.Name(),
				Permissions: strings2(manifest["permissions"]), IsEnabled: !disabled[id], DirPath: dir,
				IconPath: resolveIcon(dir, manifest), Source: readSource(dir), Location: r.location, Skills: skills,
			}
			if p.Name == "" {
				p.Name = e.Name()
			}
			out = append(out, p)
		}
	}
	return out
}

func (m *Manager) find(id string) (Plugin, bool) {
	for _, p := range m.scan() {
		if p.ID == id {
			return p, true
		}
	}
	return Plugin{}, false
}

func readManifest(dir string) (map[string]interface{}, error) {
	data, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return nil, err
	}
	var manifest map[string]interface{}
	if err := json.Unmarshal(data, &manifest); err != nil {
		return nil, fmt.Errorf("manifest.json is not valid JSON: %w", err)
	}
	return manifest, nil
}

func readSource(dir string) *Source {
	data, err := os.ReadFile(filepath.Join(dir, sourceFile))
	if err != nil {
		return nil
	}
	var s Source
	if json.Unmarshal(data, &s) != nil || s.Repo == "" || s.Path == "" {
		return nil
	}
	return &s
}

// iconName is the manifest's `icon`, or the largest entry of Chrome-style `icons` ({"48": "icon48.png"}).
func iconName(manifest map[string]interface{}) string {
	if s := str(manifest["icon"]); s != "" {
		return s
	}
	icons, _ := manifest["icons"].(map[string]interface{})
	best, bestSize := "", -1
	for size, v := range icons {
		n := 0
		fmt.Sscanf(size, "%d", &n)
		if s := str(v); s != "" && n > bestSize {
			best, bestSize = s, n
		}
	}
	return best
}

// resolveIcon returns the icon's absolute path if it is an image file inside the plugin folder.
func resolveIcon(dir string, manifest map[string]interface{}) string {
	name := iconName(manifest)
	if name == "" || !iconTypes[strings.ToLower(strings.TrimPrefix(filepath.Ext(name), "."))] {
		return ""
	}
	file, ok := inside(dir, name)
	if !ok {
		return ""
	}
	if fi, err := os.Stat(file); err != nil || !fi.Mode().IsRegular() {
		return ""
	}
	return file
}

// inside joins a relative path onto dir and reports whether the real result stays inside dir.
func inside(dir, relative string) (string, bool) {
	if relative == "" || filepath.IsAbs(relative) || strings.Contains(relative, "\\") {
		return "", false
	}
	file := filepath.Join(dir, relative)
	realDir, err1 := filepath.EvalSymlinks(dir)
	realFile, err2 := filepath.EvalSymlinks(file)
	if err1 != nil || err2 != nil || !strings.HasPrefix(realFile, realDir+string(os.PathSeparator)) {
		return "", false
	}
	return file, true
}

// ---- enabled state (per root: extensions.json {"disabled": [ids]}) ----

func readDisabled(rootDir string) map[string]bool {
	out := map[string]bool{}
	data, err := os.ReadFile(filepath.Join(rootDir, disabledCfg))
	if err != nil {
		return out
	}
	var cfg struct {
		Disabled []string `json:"disabled"`
	}
	if json.Unmarshal(data, &cfg) == nil {
		for _, id := range cfg.Disabled {
			out[id] = true
		}
	}
	return out
}

func writeDisabled(rootDir string, disabled map[string]bool) error {
	ids := []string{}
	for id, off := range disabled {
		if off {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	data, _ := json.MarshalIndent(map[string]interface{}{"disabled": ids}, "", "  ")
	if err := os.MkdirAll(rootDir, 0o755); err != nil {
		return err
	}
	return writeAtomic(filepath.Join(rootDir, disabledCfg), data)
}

func setDisabled(rootDir, id string, off bool) error {
	disabled := readDisabled(rootDir)
	if disabled[id] == off {
		return nil
	}
	disabled[id] = off
	return writeDisabled(rootDir, disabled)
}

// ---- changes ----

// install places an unpacked, validated package in the managed folder. updateID selects an installed plugin to
// replace (keeping its ID, folder and enabled state); asNew gives a separate copy an ID taken from the folder.
// The replaced files are kept as a backup until commit or restore.
func (m *Manager) install(pkg, folder, updateID string, asNew bool, source *Source) (Plugin, error) {
	if err := checkFolder(folder); err != nil {
		return Plugin{}, err
	}
	var previous *Plugin
	if updateID != "" {
		p, ok := m.find(updateID)
		if !ok {
			return Plugin{}, errors.New("Extension not found")
		}
		previous = &p
		if folder != p.FolderName {
			return Plugin{}, errors.New("an update must keep the plugin's folder name")
		}
	}
	manifest, err := validatePackage(pkg)
	if err != nil {
		return Plugin{}, err
	}
	id := ""
	switch {
	case previous != nil:
		id = previous.ID
	case !asNew:
		id = str(manifest["id"])
	}
	if id == "" {
		id = strings.ReplaceAll(folder, ".", "_")
	}
	if !idPattern.MatchString(id) {
		return Plugin{}, errors.New("Invalid extension ID")
	}
	if previous == nil {
		if _, exists := m.find(id); exists {
			if asNew {
				return Plugin{}, fmt.Errorf("A plugin with ID %s already exists; choose another folder name", id)
			}
			return Plugin{}, errors.New("Extension already exists; use Update ZIP")
		}
	}
	manifest["id"] = id
	data, _ := json.MarshalIndent(manifest, "", "  ")
	if err := os.WriteFile(filepath.Join(pkg, "manifest.json"), data, 0o644); err != nil {
		return Plugin{}, err
	}
	_ = os.Remove(filepath.Join(pkg, sourceFile))
	if source != nil {
		data, _ := json.MarshalIndent(source, "", "  ")
		if err := os.WriteFile(filepath.Join(pkg, sourceFile), data, 0o644); err != nil {
			return Plugin{}, err
		}
	}

	managed := m.managedRoot()
	target := filepath.Join(managed, folder)
	_, statErr := os.Lstat(target)
	exists := statErr == nil
	if previous == nil && exists {
		return Plugin{}, errors.New("Folder already exists")
	}
	if previous != nil && exists && target != previous.DirPath {
		return Plugin{}, errors.New("Folder belongs to another extension")
	}
	backup := filepath.Join(managed, backupDir, folder)
	if err := os.RemoveAll(backup); err != nil {
		return Plugin{}, err
	}
	if exists {
		if err := os.MkdirAll(filepath.Dir(backup), 0o755); err != nil {
			return Plugin{}, err
		}
		if err := os.Rename(target, backup); err != nil {
			return Plugin{}, fmt.Errorf("cannot stage previous plugin: %w", err)
		}
	}
	if err := os.Rename(pkg, target); err != nil {
		if exists {
			_ = os.Rename(backup, target)
		}
		return Plugin{}, fmt.Errorf("cannot place plugin: %w", err)
	}
	enabled := previous == nil || previous.IsEnabled
	if err := setDisabled(managed, id, !enabled); err != nil {
		return Plugin{}, err
	}
	p, _ := m.find(id)
	return p, nil
}

// restore undoes the last install into folder: puts the backup back, or removes a fresh install.
func (m *Manager) restore(folder string) error {
	if err := checkFolder(folder); err != nil {
		return err
	}
	target := filepath.Join(m.managedRoot(), folder)
	backup := filepath.Join(m.managedRoot(), backupDir, folder)
	if err := os.RemoveAll(target); err != nil {
		return err
	}
	if _, err := os.Lstat(backup); err == nil {
		return os.Rename(backup, target)
	}
	return nil
}

func (m *Manager) commit(folder string) error {
	if err := checkFolder(folder); err != nil {
		return err
	}
	return os.RemoveAll(filepath.Join(m.managedRoot(), backupDir, folder))
}

// remove deletes a plugin folder that sits directly inside one of the plugin roots. os.RemoveAll never follows
// symlinks: a linked plugin folder or a link inside it is removed as a link, never the files it points to.
func (m *Manager) remove(id string, deleteData bool) error {
	p, ok := m.find(id)
	if !ok {
		return errors.New("Extension not found")
	}
	parent := filepath.Dir(filepath.Clean(p.DirPath))
	var rootDir string
	for _, r := range m.roots {
		if filepath.Clean(r.dir) == parent {
			rootDir = r.dir
		}
	}
	if rootDir == "" || filepath.Base(p.DirPath) != p.FolderName || p.FolderName == "" || strings.HasPrefix(p.FolderName, ".") {
		return fmt.Errorf("refusing to delete %s: not a plugin folder", p.DirPath)
	}
	if err := os.RemoveAll(p.DirPath); err != nil {
		return err
	}
	_ = os.RemoveAll(filepath.Join(rootDir, backupDir, p.FolderName))
	if err := setDisabled(rootDir, id, false); err != nil {
		return err
	}
	if deleteData && idPattern.MatchString(id) {
		_ = os.Remove(filepath.Join(m.stateDir, id+".json"))
	}
	return nil
}

func (m *Manager) setEnabled(id string, enabled bool) (Plugin, error) {
	p, ok := m.find(id)
	if !ok {
		return Plugin{}, errors.New("Extension not found")
	}
	if err := setDisabled(filepath.Dir(p.DirPath), id, !enabled); err != nil {
		return Plugin{}, err
	}
	p.IsEnabled = enabled
	return p, nil
}

// rename moves a plugin into the managed folder under a new name, keeping its ID; one from another root is copied.
func (m *Manager) rename(id, folder string) (Plugin, error) {
	if err := checkFolder(folder); err != nil {
		return Plugin{}, err
	}
	p, ok := m.find(id)
	if !ok {
		return Plugin{}, errors.New("Extension not found")
	}
	managed := m.managedRoot()
	target := filepath.Join(managed, folder)
	if filepath.Clean(p.DirPath) == filepath.Clean(target) {
		return p, nil
	}
	if _, err := os.Lstat(target); err == nil {
		return Plugin{}, errors.New("Folder already exists")
	}
	if err := os.MkdirAll(managed, 0o755); err != nil {
		return Plugin{}, err
	}
	if filepath.Dir(filepath.Clean(p.DirPath)) == filepath.Clean(managed) {
		if err := os.Rename(p.DirPath, target); err != nil {
			return Plugin{}, err
		}
	} else if err := copyTree(p.DirPath, target); err != nil {
		_ = os.RemoveAll(target)
		return Plugin{}, err
	}
	manifest, err := readManifest(target)
	if err == nil {
		manifest["id"] = id
		data, _ := json.MarshalIndent(manifest, "", "  ")
		err = os.WriteFile(filepath.Join(target, "manifest.json"), data, 0o644)
	}
	if err == nil {
		err = setDisabled(managed, id, !p.IsEnabled)
	}
	if err != nil {
		return Plugin{}, err
	}
	np, _ := m.find(id)
	return np, nil
}

func checkFolder(name string) error {
	if !folderPattern.MatchString(name) || name == "runtime" {
		return errors.New("Use 1–64 letters, numbers, dots, underscores or hyphens for the folder name, starting with a letter or number")
	}
	return nil
}

// newStage makes a scratch folder on the same filesystem as the managed folder, so installs can rename into place.
func (m *Manager) newStage() (string, error) {
	base := filepath.Join(m.managedRoot(), stagingDir)
	if err := os.MkdirAll(base, 0o755); err != nil {
		return "", err
	}
	return os.MkdirTemp(base, "pkg-")
}

func writeAtomic(path string, data []byte) error {
	tmp := fmt.Sprintf("%s.%d.tmp", path, time.Now().UnixNano())
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// ---- JSON helpers ----

func str(v interface{}) string {
	s, _ := v.(string)
	return strings.TrimSpace(s)
}

func arr(v interface{}) []interface{} {
	a, _ := v.([]interface{})
	return a
}

func strings2(v interface{}) []string {
	out := []string{}
	for _, x := range arr(v) {
		if s, ok := x.(string); ok {
			out = append(out, s)
		}
	}
	return out
}
