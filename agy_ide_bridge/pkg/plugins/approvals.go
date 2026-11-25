package plugins

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"sync"
)

// Plugins in ~/extensions run only after the user approved them.
// Whatever can write a file there (a mini app with a folder grant, an agent steered by a web page) could otherwise
// plant code that runs as AntiGem on the next start. An approval is the plugin ID plus a hash of all its files, kept
// outside every plugin folder (<files>/plugin-approvals.json, 0600), so writing a plugin never approves it; a changed
// file needs a new approval. Installs through the app approve what they placed.
//
// The first time (no approvals file yet) the plugins already there and switched on are approved, so nothing the user
// had stops working with the update.

const maxApprovedHashes = 5 // per plugin: the current files and a few earlier versions (an install can be undone)

type approvals struct {
	path  string
	mu    sync.Mutex
	cache map[string][2]string // plugin dir → {fingerprint (names, sizes, times), content hash}
}

type approvalFile struct {
	Approved map[string][]string `json:"approved"` // plugin ID → approved hashes, newest first
}

func newApprovals(path string) *approvals {
	return &approvals{path: path, cache: map[string][2]string{}}
}

func (a *approvals) read() (approvalFile, bool) {
	f := approvalFile{Approved: map[string][]string{}}
	data, err := os.ReadFile(a.path)
	if err != nil {
		return f, false
	}
	if json.Unmarshal(data, &f) != nil || f.Approved == nil {
		f.Approved = map[string][]string{}
	}
	return f, true
}

func (a *approvals) write(f approvalFile) error {
	data, _ := json.MarshalIndent(f, "", "  ")
	if err := os.MkdirAll(filepath.Dir(a.path), 0o700); err != nil {
		return err
	}
	tmp := fmt.Sprintf("%s.tmp", a.path)
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	_ = os.Chmod(tmp, 0o600)
	return os.Rename(tmp, a.path)
}

// isApproved reports whether the files in dir are an approved version of plugin id.
func (a *approvals) isApproved(id, dir string) bool {
	h, err := a.hash(dir)
	if err != nil {
		return false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	f, _ := a.read()
	for _, ok := range f.Approved[id] {
		if ok == h {
			return true
		}
	}
	return false
}

// approve records the files now in dir as approved for plugin id.
func (a *approvals) approve(id, dir string) error {
	h, err := a.hash(dir)
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	f, _ := a.read()
	list := []string{h}
	for _, old := range f.Approved[id] {
		if old != h && len(list) < maxApprovedHashes {
			list = append(list, old)
		}
	}
	f.Approved[id] = list
	return a.write(f)
}

// forget drops every approval of plugin id (it was removed).
func (a *approvals) forget(id string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	f, ok := a.read()
	if !ok {
		return
	}
	if _, has := f.Approved[id]; has {
		delete(f.Approved, id)
		_ = a.write(f)
	}
}

// migrate runs once (no approvals file yet): approves the plugins found in the approval roots that are switched on.
func (a *approvals) migrate(roots []root) {
	a.mu.Lock()
	_, exists := a.read()
	a.mu.Unlock()
	if exists {
		return
	}
	f := approvalFile{Approved: map[string][]string{}}
	seen := map[string]bool{}
	for _, r := range roots {
		if !r.approval {
			continue
		}
		entries, err := os.ReadDir(r.dir)
		if err != nil {
			continue
		}
		disabled := readDisabled(r.dir)
		for _, e := range entries {
			if !e.IsDir() || len(e.Name()) == 0 || e.Name()[0] == '.' {
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
			if seen[id] || disabled[id] {
				continue
			}
			seen[id] = true
			if h, err := a.hash(dir); err == nil {
				f.Approved[id] = []string{h}
			}
		}
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, exists := a.read(); !exists {
		_ = a.write(f)
	}
}

// hash is a SHA-256 over every file of the plugin (path, kind and content; a symlink by its target). The result is
// cached per folder until a name, size or modification time changes.
func (a *approvals) hash(dir string) (string, error) {
	type entry struct {
		rel  string
		path string
		mode fs.FileMode
		size int64
		mod  int64
	}
	var list []entry
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(dir, p)
		info, err := os.Lstat(p)
		if err != nil {
			return err
		}
		list = append(list, entry{filepath.ToSlash(rel), p, info.Mode(), info.Size(), info.ModTime().UnixNano()})
		return nil
	})
	if err != nil {
		return "", err
	}
	sort.Slice(list, func(i, j int) bool { return list[i].rel < list[j].rel })
	fp := sha256.New()
	for _, e := range list {
		fmt.Fprintf(fp, "%s\x00%d\x00%d\x00%d\n", e.rel, e.mode, e.size, e.mod)
	}
	fingerprint := hex.EncodeToString(fp.Sum(nil))
	a.mu.Lock()
	cached, ok := a.cache[dir]
	a.mu.Unlock()
	if ok && cached[0] == fingerprint {
		return cached[1], nil
	}
	h := sha256.New()
	for _, e := range list {
		switch {
		case e.mode&fs.ModeSymlink != 0:
			target, err := os.Readlink(e.path)
			if err != nil {
				return "", err
			}
			fmt.Fprintf(h, "L %s\x00%s\n", e.rel, target)
		case e.mode.IsDir():
			fmt.Fprintf(h, "D %s\n", e.rel)
		case e.mode.IsRegular():
			fmt.Fprintf(h, "F %s\x00%d\n", e.rel, e.size)
			file, err := os.Open(e.path)
			if err != nil {
				return "", err
			}
			_, err = io.Copy(h, file)
			file.Close()
			if err != nil {
				return "", err
			}
		default:
			return "", errors.New("plugin folder holds a special file: " + e.rel)
		}
	}
	sum := hex.EncodeToString(h.Sum(nil))
	a.mu.Lock()
	a.cache[dir] = [2]string{fingerprint, sum}
	a.mu.Unlock()
	return sum, nil
}
