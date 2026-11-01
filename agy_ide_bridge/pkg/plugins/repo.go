package plugins

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

const repoIndex = "antigem-plugins.json"

// Repo is a public GitHub repository of plugin folders; Ref is a branch or tag, empty for the default branch.
type Repo struct {
	Owner, Name, Ref string
}

func (r Repo) slug() string { return r.Owner + "/" + r.Name }

// ref is what GitHub URLs accept for this repo; "HEAD" is the default branch on raw, archive and tree URLs.
func (r Repo) ref() string {
	if r.Ref == "" {
		return "HEAD"
	}
	return r.Ref
}

// RepoPlugin is one plugin a repository offers; Version comes from the index, or its manifest when the index has none.
type RepoPlugin struct {
	Path        string `json:"path"`
	ID          string `json:"id"`
	Name        string `json:"name"`
	Version     string `json:"version"`
	Description string `json:"description"`
	IconURL     string `json:"iconUrl"`
}

type RepoListing struct {
	Title     string       `json:"title"`
	Plugins   []RepoPlugin `json:"plugins"`
	FromIndex bool         `json:"fromIndex"`
}

var (
	repoName = `[A-Za-z0-9_.-]+`
	treeURL  = regexp.MustCompile(`^(` + repoName + `)/(` + repoName + `)/tree/(.+)$`)
	plainRef = regexp.MustCompile(`^(` + repoName + `)/(` + repoName + `)(?:@(.+))?$`)
	ghPrefix = regexp.MustCompile(`^(https?://)?(www\.)?github\.com/`)
)

// ParseRepo accepts owner/repo, owner/repo@ref, https://github.com/owner/repo and …/tree/ref URLs.
func ParseRepo(input string) (Repo, error) {
	text := strings.TrimSuffix(strings.TrimSuffix(strings.TrimSpace(input), "/"), ".git")
	text = ghPrefix.ReplaceAllString(text, "")
	m := treeURL.FindStringSubmatch(text)
	if m == nil {
		m = plainRef.FindStringSubmatch(text)
	}
	if m == nil {
		return Repo{}, errors.New("Use owner/repo, owner/repo@branch or a github.com repository URL")
	}
	ref := m[3]
	if ref != "" && (strings.ContainsAny(ref, " \t\n") || !validPath(ref)) {
		return Repo{}, fmt.Errorf("Invalid branch or tag: %s", ref)
	}
	return Repo{m[1], m[2], ref}, nil
}

func validPath(p string) bool {
	for _, part := range strings.Split(p, "/") {
		if part == "" || part == "." || part == ".." {
			return false
		}
	}
	return true
}

func encodePath(p string) string {
	parts := strings.Split(p, "/")
	for i, part := range parts {
		parts[i] = url.PathEscape(part)
	}
	return strings.Join(parts, "/")
}

func (m *Manager) rawURL(r Repo, file string) string {
	return fmt.Sprintf("https://raw.githubusercontent.com/%s/%s/%s/%s", r.Owner, r.Name, encodePath(r.ref()), encodePath(file))
}

func (m *Manager) get(u string) (*http.Response, error) {
	req, err := http.NewRequest(http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "AntiGem")
	return m.client.Do(req)
}

// text returns a small text file, or ok=false if it doesn't exist.
func (m *Manager) text(u string) (string, bool, error) {
	resp, err := m.get(u)
	if err != nil {
		return "", false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return "", false, nil
	}
	if resp.StatusCode != http.StatusOK {
		return "", false, fmt.Errorf("HTTP %d reading %s", resp.StatusCode, path.Base(u))
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20+1))
	if err != nil {
		return "", false, err
	}
	if len(data) > 1<<20 {
		return "", false, fmt.Errorf("%s is too large", path.Base(u))
	}
	return string(data), true, nil
}

type treeEntry struct {
	Path string `json:"path"`
	Type string `json:"type"`
	Size int64  `json:"size"`
}

// tree lists every file in the repository with one GitHub API call.
func (m *Manager) tree(r Repo) ([]treeEntry, error) {
	req, _ := http.NewRequest(http.MethodGet, fmt.Sprintf("https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1", r.Owner, r.Name, encodePath(r.ref())), nil)
	req.Header.Set("User-Agent", "AntiGem")
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := m.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusTooManyRequests:
		return nil, errors.New("GitHub API limit reached; try again later")
	case resp.StatusCode == http.StatusNotFound:
		return nil, errors.New("Repository or branch not found (private repositories are not supported)")
	case resp.StatusCode != http.StatusOK:
		return nil, fmt.Errorf("GitHub API HTTP %d", resp.StatusCode)
	}
	var body struct {
		Tree      []treeEntry `json:"tree"`
		Truncated bool        `json:"truncated"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, err
	}
	if body.Truncated {
		return nil, errors.New("Repository is too large to list")
	}
	var files []treeEntry
	for _, e := range body.Tree {
		if e.Type == "blob" {
			files = append(files, e)
		}
	}
	return files, nil
}

// ---- listing ----

func (m *Manager) listRepo(r Repo) (RepoListing, error) {
	index, ok, err := m.text(m.rawURL(r, repoIndex))
	if err != nil {
		return RepoListing{}, err
	}
	if ok {
		var parsed struct {
			Name    string                   `json:"name"`
			Plugins []map[string]interface{} `json:"plugins"`
		}
		if err := json.Unmarshal([]byte(index), &parsed); err != nil {
			return RepoListing{}, fmt.Errorf("%s is not valid JSON: %w", repoIndex, err)
		}
		if parsed.Plugins == nil {
			return RepoListing{}, fmt.Errorf("%s has no \"plugins\" array", repoIndex)
		}
		plugins := make([]RepoPlugin, len(parsed.Plugins))
		errs := make([]error, len(parsed.Plugins))
		var wg sync.WaitGroup
		for i, entry := range parsed.Plugins {
			wg.Add(1)
			go func(i int, entry map[string]interface{}) {
				defer wg.Done()
				plugins[i], errs[i] = m.fromIndex(r, entry)
			}(i, entry)
		}
		wg.Wait()
		for _, e := range errs {
			if e != nil {
				return RepoListing{}, e
			}
		}
		title := parsed.Name
		if title == "" {
			title = r.slug()
		}
		return RepoListing{title, plugins, true}, nil
	}
	files, err := m.tree(r)
	if err != nil {
		return RepoListing{}, fmt.Errorf("No %s in the repository, and its folders could not be listed (%v). Add %s to the repository root.", repoIndex, err, repoIndex)
	}
	var folders []string
	for _, f := range files {
		if strings.Count(f.Path, "/") == 1 && strings.HasSuffix(f.Path, "/manifest.json") {
			folders = append(folders, strings.Split(f.Path, "/")[0])
		}
	}
	plugins := make([]RepoPlugin, len(folders))
	var wg sync.WaitGroup
	for i, folder := range folders {
		wg.Add(1)
		go func(i int, folder string) {
			defer wg.Done()
			plugins[i] = m.fromManifest(r, folder, map[string]interface{}{})
		}(i, folder)
	}
	wg.Wait()
	return RepoListing{r.slug(), plugins, false}, nil
}

func (m *Manager) fromIndex(r Repo, entry map[string]interface{}) (RepoPlugin, error) {
	p := strings.Trim(str(entry["path"]), "/")
	if p == "" || !validPath(p) {
		return RepoPlugin{}, fmt.Errorf("%s has an entry with an invalid path: %q", repoIndex, p)
	}
	// Only fetch the manifest when the index leaves out something the list needs
	if str(entry["id"]) == "" || str(entry["version"]) == "" {
		return m.fromManifest(r, p, entry), nil
	}
	return m.repoPlugin(r, p, entry, map[string]interface{}{}), nil
}

func (m *Manager) fromManifest(r Repo, p string, entry map[string]interface{}) RepoPlugin {
	manifest := map[string]interface{}{}
	if text, ok, err := m.text(m.rawURL(r, p+"/manifest.json")); err == nil && ok {
		_ = json.Unmarshal([]byte(text), &manifest)
	}
	return m.repoPlugin(r, p, entry, manifest)
}

func (m *Manager) repoPlugin(r Repo, p string, entry, manifest map[string]interface{}) RepoPlugin {
	field := func(k string) string {
		if v := str(entry[k]); v != "" {
			return v
		}
		return str(manifest[k])
	}
	icon := strings.Trim(str(entry["icon"]), "/")
	if icon == "" {
		icon = strings.Trim(iconName(manifest), "/")
	}
	iconURL := ""
	if icon != "" && validPath(icon) && iconTypes[strings.ToLower(strings.TrimPrefix(path.Ext(icon), "."))] {
		iconURL = m.rawURL(r, p+"/"+icon)
	}
	name := field("name")
	if name == "" {
		name = p
	}
	return RepoPlugin{p, field("id"), name, field("version"), field("description"), iconURL}
}

// ---- downloading one folder ----

// downloadFolder fetches only folder p into dest: its files from raw URLs after one tree call, or, when the API
// can't be used, by streaming the repository archive and keeping that folder.
func (m *Manager) downloadFolder(r Repo, p, dest string) error {
	treeErr := m.downloadViaTree(r, p, dest)
	if treeErr == nil {
		return nil
	}
	_ = os.RemoveAll(dest)
	if err := m.downloadViaArchive(r, p, dest); err != nil {
		return fmt.Errorf("Could not download %s from %s: %v; archive: %v", p, r.slug(), treeErr, err)
	}
	return nil
}

func (m *Manager) downloadViaTree(r Repo, p, dest string) error {
	files, err := m.tree(r)
	if err != nil {
		return err
	}
	prefix := p + "/"
	var wanted []treeEntry
	var total int64
	for _, f := range files {
		if strings.HasPrefix(f.Path, prefix) {
			wanted = append(wanted, f)
			total += f.Size
		}
	}
	if len(wanted) == 0 {
		return fmt.Errorf("Folder %s not found", p)
	}
	if len(wanted) > maxEntries || total > maxTotal {
		return fmt.Errorf("%s is larger than a plugin may be", p)
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
			errs <- m.downloadFile(m.rawURL(r, f.Path), dest, strings.TrimPrefix(f.Path, prefix))
		}(f)
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		if e != nil {
			return e
		}
	}
	return nil
}

func (m *Manager) downloadFile(u, dest, rel string) error {
	target, err := safeTarget(dest, rel)
	if err != nil {
		return err
	}
	resp, err := m.get(u)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d for %s", resp.StatusCode, rel)
	}
	return writeLimited(resp.Body, target, maxFileBytes)
}

func (m *Manager) downloadViaArchive(r Repo, p, dest string) error {
	resp, err := m.get(fmt.Sprintf("https://github.com/%s/%s/archive/%s.zip", r.Owner, r.Name, encodePath(r.ref())))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	// zip needs random access: spool the archive next to dest, then keep only the folder
	tmp, err := os.CreateTemp(filepath.Dir(dest), "repo-*.zip")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	_, err = io.Copy(tmp, io.LimitReader(resp.Body, 512<<20))
	tmp.Close()
	if err != nil {
		return err
	}
	zr, err := zip.OpenReader(tmp.Name())
	if err != nil {
		return err
	}
	defer zr.Close()
	count := 0
	var total int64
	for _, f := range zr.File {
		// Entries are "<repo>-<ref>/<path inside repo>"
		_, inner, found := strings.Cut(strings.ReplaceAll(f.Name, "\\", "/"), "/")
		if !found || f.FileInfo().IsDir() || f.Mode()&os.ModeSymlink != 0 || !strings.HasPrefix(inner, p+"/") {
			continue
		}
		if count++; count > maxEntries {
			return fmt.Errorf("%s has too many files", p)
		}
		target, err := safeTarget(dest, strings.TrimPrefix(inner, p+"/"))
		if err != nil {
			return err
		}
		in, err := f.Open()
		if err != nil {
			return err
		}
		err = writeLimited(in, target, maxFileBytes)
		in.Close()
		if err != nil {
			return err
		}
		total += int64(f.UncompressedSize64)
		if total > maxTotal {
			return fmt.Errorf("%s is larger than a plugin may be", p)
		}
	}
	if count == 0 {
		return fmt.Errorf("Folder %s not found", p)
	}
	return nil
}

func safeTarget(root, rel string) (string, error) {
	if !validPath(rel) {
		return "", fmt.Errorf("Unsafe file path in repository: %s", rel)
	}
	target := filepath.Join(root, filepath.FromSlash(rel))
	if !strings.HasPrefix(target, filepath.Clean(root)+string(os.PathSeparator)) {
		return "", fmt.Errorf("Unsafe file path in repository: %s", rel)
	}
	return target, os.MkdirAll(filepath.Dir(target), 0o755)
}

func writeLimited(r io.Reader, target string, limit int64) error {
	out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	n, err := io.Copy(out, io.LimitReader(r, limit+1))
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err == nil && n > limit {
		err = fmt.Errorf("%s is larger than 32 MiB", filepath.Base(target))
	}
	return err
}
