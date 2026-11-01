package plugins

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Same limits as the app's ZIP installer.
const (
	maxEntries   = 5000
	maxFileBytes = 32 << 20
	maxTotal     = 128 << 20
)

var drivePrefix = regexp.MustCompile(`^[A-Za-z]:`)

// safeRelative reports whether a slash-separated path stays inside its root.
func safeRelative(name string) bool {
	if name == "" || strings.HasPrefix(name, "/") || drivePrefix.MatchString(name) {
		return false
	}
	for _, part := range strings.Split(name, "/") {
		if part == ".." {
			return false
		}
	}
	return true
}

// extractZip unpacks a plugin ZIP into dest and returns the package root: dest itself when manifest.json is at the
// top, or the single enclosing folder that has one. Symlink entries, unsafe paths and oversized content are rejected.
func extractZip(zipPath, dest string) (string, error) {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return "", fmt.Errorf("not a ZIP file: %w", err)
	}
	defer r.Close()
	if len(r.File) > maxEntries {
		return "", errors.New("ZIP contains too many entries")
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return "", err
	}
	seen := map[string]bool{}
	var total int64
	for _, f := range r.File {
		name := strings.ReplaceAll(f.Name, "\\", "/")
		if !safeRelative(name) {
			return "", fmt.Errorf("Unsafe ZIP entry: %s", f.Name)
		}
		if f.Mode()&fs.ModeSymlink != 0 {
			return "", fmt.Errorf("ZIP entry is a symlink: %s", f.Name)
		}
		target := filepath.Join(dest, filepath.FromSlash(name))
		if !strings.HasPrefix(target, filepath.Clean(dest)+string(os.PathSeparator)) || seen[target] {
			return "", fmt.Errorf("Unsafe or duplicate ZIP entry: %s", f.Name)
		}
		seen[target] = true
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return "", err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return "", err
		}
		n, err := writeEntry(f, target, maxTotal-total)
		if err != nil {
			return "", err
		}
		total += n
	}
	if fileExists(filepath.Join(dest, "manifest.json")) {
		return dest, nil
	}
	entries, _ := os.ReadDir(dest)
	var candidates []string
	for _, e := range entries {
		if e.IsDir() && !strings.HasPrefix(e.Name(), ".") && e.Name() != "__MACOSX" && fileExists(filepath.Join(dest, e.Name(), "manifest.json")) {
			candidates = append(candidates, filepath.Join(dest, e.Name()))
		}
	}
	if len(candidates) != 1 {
		return "", errors.New("ZIP must contain one extension with manifest.json at its root or inside one folder")
	}
	return candidates[0], nil
}

func writeEntry(f *zip.File, target string, remaining int64) (int64, error) {
	in, err := f.Open()
	if err != nil {
		return 0, err
	}
	defer in.Close()
	out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o644)
	if err != nil {
		return 0, err
	}
	defer out.Close()
	limit := int64(maxFileBytes)
	if remaining < limit {
		limit = remaining
	}
	n, err := io.Copy(out, io.LimitReader(in, limit+1))
	if err != nil {
		return n, err
	}
	if n > limit {
		return n, errors.New("Unpacked extension exceeds size limit")
	}
	return n, nil
}

// validatePackage checks the manifest contract: a name, manifest version 2 or 3, and either MCP tools with a
// background worker or skills/*.md. It returns the parsed manifest.
func validatePackage(root string) (map[string]interface{}, error) {
	manifest, err := readManifest(root)
	if err != nil {
		return nil, err
	}
	if v, _ := manifest["manifest_version"].(float64); v != 2 && v != 3 {
		return nil, errors.New("Only manifest versions 2 and 3 are supported")
	}
	if str(manifest["name"]) == "" {
		return nil, errors.New("Extension name is required")
	}
	tools := arr(manifest["mcp_tools"])
	if len(tools) == 0 && len(listSkills(root)) == 0 {
		return nil, errors.New("Extension must declare at least one MCP tool in mcp_tools or include skills/*.md")
	}
	names := map[string]bool{}
	for _, t := range tools {
		tool, _ := t.(map[string]interface{})
		name := str(tool["name"])
		if name == "" || names[name] {
			return nil, errors.New("Duplicate or empty MCP tool name")
		}
		names[name] = true
	}
	background, hasBackground := manifest["background"].(map[string]interface{})
	if len(tools) == 0 && !hasBackground {
		return manifest, nil // skills only: the host serves the skill tools itself
	}
	if !hasBackground {
		return nil, errors.New("Extension background worker is required")
	}
	files := strings2(background["scripts"])
	if w := str(background["service_worker"]); w != "" {
		files = []string{w}
	}
	if len(files) == 0 {
		return nil, errors.New("Extension background worker is required")
	}
	for _, f := range files {
		if p, ok := inside(root, filepath.FromSlash(f)); !ok || !fileExists(p) {
			return nil, fmt.Errorf("Missing or invalid background file: %s", f)
		}
	}
	return manifest, nil
}

// writeZip packs a plugin folder for sharing. Symlinks are skipped (never followed); exclude gets each
// slash-separated relative path.
func writeZip(dir string, w io.Writer, exclude func(string) bool) error {
	zw := zip.NewWriter(w)
	var files []string
	err := filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.Type()&fs.ModeSymlink != 0 || !(d.IsDir() || d.Type().IsRegular()) {
			return nil
		}
		if !d.IsDir() {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		return err
	}
	sort.Strings(files)
	if len(files) > maxEntries {
		return errors.New("Extension contains too many files")
	}
	for _, path := range files {
		rel, _ := filepath.Rel(dir, path)
		rel = filepath.ToSlash(rel)
		if exclude(rel) {
			continue
		}
		in, err := os.Open(path)
		if err != nil {
			return err
		}
		out, err := zw.Create(rel)
		if err == nil {
			_, err = io.Copy(out, in)
		}
		in.Close()
		if err != nil {
			return err
		}
	}
	return zw.Close()
}

// copyTree copies regular files and folders; symlinks are skipped rather than followed.
func copyTree(src, dst string) error {
	return filepath.WalkDir(src, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, path)
		target := filepath.Join(dst, rel)
		switch {
		case d.Type()&fs.ModeSymlink != 0:
			return nil
		case d.IsDir():
			return os.MkdirAll(target, 0o755)
		case d.Type().IsRegular():
			in, err := os.Open(path)
			if err != nil {
				return err
			}
			defer in.Close()
			out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
			if err != nil {
				return err
			}
			_, err = io.Copy(out, in)
			if cerr := out.Close(); err == nil {
				err = cerr
			}
			return err
		}
		return nil
	})
}

func fileExists(path string) bool {
	fi, err := os.Stat(path)
	return err == nil && fi.Mode().IsRegular()
}
