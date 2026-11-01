package plugins

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Developer MCP drafts live in ~/extension-projects/<project_id>/src, with packaged ZIPs in …/dist. The app
// supplies template files (they are in its assets) and pages text; every file operation happens here.

var projectPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

type draftFile struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

func (m *Manager) projectsRoot() (string, error) {
	root := filepath.Join(m.home, "extension-projects")
	if fi, err := os.Lstat(root); err == nil && fi.Mode()&fs.ModeSymlink != 0 {
		return "", errors.New("Authoring workspace cannot be a symlink")
	}
	return root, nil
}

func (m *Manager) projectDir(id string) (string, error) {
	if !projectPattern.MatchString(id) {
		return "", errors.New("project_id must contain 1–64 letters, numbers, underscores or hyphens")
	}
	root, err := m.projectsRoot()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(root, id)
	if fi, err := os.Lstat(dir); err == nil && fi.Mode()&fs.ModeSymlink != 0 {
		return "", errors.New("Draft project cannot be a symlink")
	}
	return dir, nil
}

func (m *Manager) draftSource(id string) (string, error) {
	dir, err := m.projectDir(id)
	if err != nil {
		return "", err
	}
	src := filepath.Join(dir, "src")
	fi, err := os.Lstat(src)
	if err != nil || !fi.IsDir() {
		return "", errors.New("Draft source not found; create the project first")
	}
	return src, nil
}

// draftPath resolves a relative path inside a draft, refusing escapes and any existing symlink along the way.
func draftPath(root, rel string) (string, error) {
	if rel == "" || strings.HasPrefix(rel, "/") || strings.Contains(rel, "\\") || drivePrefix.MatchString(rel) {
		return "", errors.New("Use a relative file path")
	}
	if !validPath(rel) {
		return "", errors.New("Relative paths cannot contain empty, . or .. components")
	}
	current := root
	for _, part := range strings.Split(rel, "/") {
		current = filepath.Join(current, part)
		if fi, err := os.Lstat(current); err == nil && fi.Mode()&fs.ModeSymlink != 0 {
			return "", errors.New("Path escapes the draft or contains a symlink")
		}
	}
	return current, nil
}

func (m *Manager) draftProjects() ([]string, error) {
	root, err := m.projectsRoot()
	if err != nil {
		return nil, err
	}
	entries, _ := os.ReadDir(root)
	names := []string{}
	for _, e := range entries {
		if e.IsDir() && !strings.HasPrefix(e.Name(), ".") {
			names = append(names, e.Name())
		}
	}
	return names, nil
}

// createDraft writes a new draft's files (the app's template) without ever replacing an existing draft.
func (m *Manager) createDraft(id string, files []draftFile) (map[string]interface{}, error) {
	dir, err := m.projectDir(id)
	if err != nil {
		return nil, err
	}
	src := filepath.Join(dir, "src")
	if _, err := os.Lstat(src); err == nil {
		return nil, errors.New("Draft already exists; use read_file and write_files to edit it")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("Cannot create draft workspace: %w", err)
	}
	stage, err := os.MkdirTemp(dir, ".create-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(stage)
	if _, err := writeDraftFiles(stage, files); err != nil {
		return nil, err
	}
	if err := os.Rename(stage, src); err != nil {
		return nil, fmt.Errorf("Cannot publish draft source: %w", err)
	}
	return map[string]interface{}{"project_id": id, "source_dir": src, "suggested_zip_path": filepath.Join(dir, "dist", id+".zip")}, nil
}

// writeDraftFiles validates every path and size first, then writes each file atomically (not the batch).
func writeDraftFiles(root string, files []draftFile) ([]string, error) {
	if len(files) < 1 || len(files) > 100 {
		return nil, errors.New("Provide 1–100 files")
	}
	targets := make([]string, len(files))
	seen := map[string]bool{}
	var total int64
	for i, f := range files {
		target, err := draftPath(root, f.Path)
		if err != nil {
			return nil, err
		}
		if seen[target] {
			return nil, errors.New("Duplicate file path")
		}
		seen[target] = true
		if fi, err := os.Stat(target); err == nil && fi.IsDir() {
			return nil, errors.New("File path is a directory")
		}
		total += int64(len(f.Content))
		if len(f.Content) > maxFileBytes || total > maxTotal {
			return nil, errors.New("Draft write exceeds package size limits")
		}
		targets[i] = target
	}
	written := make([]string, len(files))
	for i, f := range files {
		if err := os.MkdirAll(filepath.Dir(targets[i]), 0o755); err != nil {
			return nil, errors.New("Cannot create draft directory")
		}
		if err := writeAtomic(targets[i], []byte(f.Content)); err != nil {
			return nil, fmt.Errorf("Cannot write draft file: %w", err)
		}
		written[i] = f.Path
	}
	return written, nil
}

// readDraft lists a draft's files, or returns one file's full text (the app pages it).
func (m *Manager) readDraft(id, rel string) (map[string]interface{}, error) {
	src, err := m.draftSource(id)
	if err != nil {
		return nil, err
	}
	if rel == "" {
		type entry struct {
			Path string `json:"path"`
			Size int64  `json:"size"`
		}
		files := []entry{}
		err := filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if d.Type()&fs.ModeSymlink != 0 {
				return errors.New("Symlinks are not supported in drafts: " + p)
			}
			if d.Type().IsRegular() {
				if len(files) >= maxEntries {
					return errors.New("Extension contains too many files")
				}
				info, _ := d.Info()
				r, _ := filepath.Rel(src, p)
				files = append(files, entry{filepath.ToSlash(r), info.Size()})
			}
			return nil
		})
		if err != nil {
			return nil, err
		}
		sort.Slice(files, func(i, j int) bool { return files[i].Path < files[j].Path })
		return map[string]interface{}{"source_dir": src, "files": files}, nil
	}
	target, err := draftPath(src, rel)
	if err != nil {
		return nil, err
	}
	fi, err := os.Stat(target)
	if err != nil || !fi.Mode().IsRegular() || fi.Size() > maxFileBytes {
		return nil, errors.New("Draft text file is missing or exceeds 32 MiB")
	}
	data, err := os.ReadFile(target)
	if err != nil {
		return nil, err
	}
	return map[string]interface{}{"path": rel, "content": string(data)}, nil
}

// packageDraft validates a draft and writes dist/<id>.zip.
func (m *Manager) packageDraft(id string) (map[string]interface{}, error) {
	src, err := m.draftSource(id)
	if err != nil {
		return nil, err
	}
	manifest, err := validatePackage(src)
	if err != nil {
		return nil, err
	}
	dir, _ := m.projectDir(id)
	dist := filepath.Join(dir, "dist")
	if err := os.MkdirAll(dist, 0o755); err != nil {
		return nil, err
	}
	out, err := os.CreateTemp(dist, ".package-*.zip")
	if err != nil {
		return nil, err
	}
	defer os.Remove(out.Name())
	err = writeZip(src, out, func(string) bool { return false })
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return nil, err
	}
	zipPath := filepath.Join(dist, id+".zip")
	if err := os.Rename(out.Name(), zipPath); err != nil {
		return nil, fmt.Errorf("Cannot publish extension ZIP: %w", err)
	}
	fi, _ := os.Stat(zipPath)
	return map[string]interface{}{"project_id": id, "zip_path": zipPath, "bytes": fi.Size(), "manifest": manifest,
		"validated": "Package structure; worker JavaScript is checked during enabled installation"}, nil
}

// installableZip resolves a ZIP path for install: ~/… or an absolute path inside the terminal home or shared storage.
func (m *Manager) installableZip(p string) (string, error) {
	if strings.HasPrefix(p, "~/") {
		p = filepath.Join(m.home, p[2:])
	}
	real, err := filepath.EvalSymlinks(p)
	if err != nil {
		return "", errors.New("ZIP file not found")
	}
	allowed := false
	for _, base := range []string{m.home, "/sdcard", "/storage/emulated/0"} {
		if b, err := filepath.EvalSymlinks(base); err == nil && strings.HasPrefix(real, b+string(os.PathSeparator)) {
			allowed = true
		}
	}
	if !allowed {
		return "", errors.New("ZIP must be inside terminal home or shared storage")
	}
	if !fileExists(real) {
		return "", errors.New("ZIP file not found")
	}
	return real, nil
}
