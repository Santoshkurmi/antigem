package handlers

// File explorer endpoints used by the app's Files screen and every folder / file picker built on it.
// Everything goes through the bridge, so the same explorer works for this phone, a Linux distro and a remote server.

import (
	"archive/tar"
	"archive/zip"
	"compress/bzip2"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// ---------- helpers ----------

func fsHome(h *Handler) string {
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		return home
	}
	return h.Cfg.ProjectsBaseDir
}

// fsResolve expands "~" and cleans the path. Relative paths are taken from the home folder.
func fsResolve(h *Handler, p string) string {
	p = strings.TrimSpace(p)
	home := fsHome(h)
	switch {
	case p == "" || p == "~":
		return home
	case strings.HasPrefix(p, "~/"):
		p = filepath.Join(home, p[2:])
	case !filepath.IsAbs(p):
		p = filepath.Join(home, p)
	}
	return filepath.Clean(p)
}

// fsProtected lists folders that must never be deleted, moved or overwritten as a whole.
func fsProtected(h *Handler, p string) bool {
	p = filepath.Clean(p)
	home := fsHome(h)
	protected := []string{
		"/", home, filepath.Dir(home), "/storage", "/storage/emulated", "/storage/emulated/0", "/sdcard",
		"/data", "/system", "/proc", "/dev", "/sys", "/usr", "/etc", "/bin", "/sbin", "/lib", "/var", "/root", "/home",
		h.Cfg.ProjectsBaseDir, h.Cfg.WorkspaceDir,
	}
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		protected = append(protected, prefix)
	}
	for _, q := range protected {
		if q != "" && filepath.Clean(q) == p {
			return true
		}
	}
	return false
}

// fsUniquePath returns p itself when free, else "name (1).ext", "name (2).ext", ...
func fsUniquePath(p string) string {
	if _, err := os.Lstat(p); os.IsNotExist(err) {
		return p
	}
	dir, base := filepath.Split(p)
	ext := ""
	stem := base
	lower := strings.ToLower(base)
	for _, multi := range []string{".tar.gz", ".tar.bz2", ".tar.xz"} {
		if strings.HasSuffix(lower, multi) && len(base) > len(multi) {
			ext = base[len(base)-len(multi):]
			stem = base[:len(base)-len(multi)]
			break
		}
	}
	if ext == "" {
		if e := filepath.Ext(base); e != "" && e != base {
			ext = e
			stem = strings.TrimSuffix(base, e)
		}
	}
	for i := 1; i < 10000; i++ {
		cand := filepath.Join(dir, fmt.Sprintf("%s (%d)%s", stem, i, ext))
		if _, err := os.Lstat(cand); os.IsNotExist(err) {
			return cand
		}
	}
	return filepath.Join(dir, fmt.Sprintf("%s (%d)%s", stem, time.Now().UnixNano(), ext))
}

func fsValidName(name string) error {
	n := strings.TrimSpace(name)
	if n == "" || n == "." || n == ".." {
		return errors.New("name is empty")
	}
	if strings.ContainsAny(n, "/\x00") {
		return errors.New("name cannot contain '/'")
	}
	if len(n) > 255 {
		return errors.New("name is too long")
	}
	return nil
}

func fsIsWithin(base, target string) bool {
	rel, err := filepath.Rel(base, target)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func fsDecode(w http.ResponseWriter, r *http.Request, v interface{}) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid request: " + err.Error()})
		return false
	}
	return true
}

func fsFail(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]interface{}{"success": false, "error": msg})
}

// copyTree copies a file, folder or symlink, streaming file data and keeping file modes.
func copyTree(src, dst string) error {
	info, err := os.Lstat(src)
	if err != nil {
		return err
	}
	switch {
	case info.Mode()&os.ModeSymlink != 0:
		target, err := os.Readlink(src)
		if err != nil {
			return err
		}
		return os.Symlink(target, dst)
	case info.IsDir():
		if err := os.MkdirAll(dst, info.Mode().Perm()|0700); err != nil {
			return err
		}
		entries, err := os.ReadDir(src)
		if err != nil {
			return err
		}
		for _, e := range entries {
			if err := copyTree(filepath.Join(src, e.Name()), filepath.Join(dst, e.Name())); err != nil {
				return err
			}
		}
		return nil
	default:
		in, err := os.Open(src)
		if err != nil {
			return err
		}
		defer in.Close()
		out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, info.Mode().Perm()|0600)
		if err != nil {
			return err
		}
		if _, err := io.Copy(out, in); err != nil {
			out.Close()
			return err
		}
		if err := out.Close(); err != nil {
			return err
		}
		_ = os.Chtimes(dst, time.Now(), info.ModTime())
		return nil
	}
}

func fsModeString(m fs.FileMode) string { return m.String() }

func fsCountChildren(path string) int {
	f, err := os.Open(path)
	if err != nil {
		return -1
	}
	defer f.Close()
	names, _ := f.Readdirnames(1001)
	return len(names)
}

// ---------- /api/fs/roots ----------

type fsRoot struct {
	Name  string `json:"name"`
	Path  string `json:"path"`
	Kind  string `json:"kind"`
	Total uint64 `json:"total"`
	Free  uint64 `json:"free"`
}

// FsRootsHandler lists the places worth jumping to: home, projects, phone storage and its usual folders, and "/".
func (h *Handler) FsRootsHandler(w http.ResponseWriter, r *http.Request) {
	home := fsHome(h)
	var roots []fsRoot
	seen := map[string]bool{}
	add := func(name, path, kind string, withSpace bool) {
		path = filepath.Clean(path)
		if seen[path] {
			return
		}
		if info, err := os.Stat(path); err != nil || !info.IsDir() {
			return
		}
		seen[path] = true
		root := fsRoot{Name: name, Path: path, Kind: kind}
		if withSpace {
			root.Total, root.Free = fsDiskSpace(path)
		}
		roots = append(roots, root)
	}

	add("Home", home, "home", true)
	if h.Cfg.ProjectsBaseDir != "" {
		add("Projects", h.Cfg.ProjectsBaseDir, "projects", false)
	}
	storage := ""
	for _, cand := range []string{"/storage/emulated/0", "/sdcard"} {
		if entries, err := os.ReadDir(cand); err == nil && len(entries) > 0 {
			storage = cand
			break
		}
	}
	if storage != "" {
		add("Internal storage", storage, "storage", true)
		add("Downloads", filepath.Join(storage, "Download"), "downloads", false)
		add("Documents", filepath.Join(storage, "Documents"), "documents", false)
		add("Camera", filepath.Join(storage, "DCIM"), "camera", false)
		add("Pictures", filepath.Join(storage, "Pictures"), "pictures", false)
		add("Movies", filepath.Join(storage, "Movies"), "movies", false)
		add("Music", filepath.Join(storage, "Music"), "music", false)
	}
	// SD cards and USB drives show up as /storage/XXXX-XXXX
	if entries, err := os.ReadDir("/storage"); err == nil {
		for _, e := range entries {
			n := e.Name()
			if n == "emulated" || n == "self" || !strings.Contains(n, "-") {
				continue
			}
			add("SD card ("+n+")", filepath.Join("/storage", n), "sdcard", true)
		}
	}
	if _, err := os.ReadDir("/"); err == nil {
		add("Root", "/", "root", false)
	}
	if roots == nil {
		roots = []fsRoot{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"home": home, "roots": roots})
}

// ---------- /api/fs/stat ----------

// FsStatHandler reports details for one path; folders get their total size and item counts (bounded in time).
func (h *Handler) FsStatHandler(w http.ResponseWriter, r *http.Request) {
	p := fsResolve(h, r.URL.Query().Get("path"))
	linfo, err := os.Lstat(p)
	if err != nil {
		fsFail(w, http.StatusNotFound, err.Error())
		return
	}
	info := linfo
	symlinkTarget := ""
	if linfo.Mode()&os.ModeSymlink != 0 {
		symlinkTarget, _ = os.Readlink(p)
		if st, err := os.Stat(p); err == nil {
			info = st
		}
	}
	res := map[string]interface{}{
		"success":       true,
		"name":          filepath.Base(p),
		"path":          p,
		"isDir":         info.IsDir(),
		"size":          info.Size(),
		"modTime":       info.ModTime().UnixMilli(),
		"mode":          fsModeString(linfo.Mode()),
		"modeOctal":     fmt.Sprintf("%04o", info.Mode().Perm()),
		"symlinkTarget": symlinkTarget,
		"hidden":        strings.HasPrefix(filepath.Base(p), "."),
		"executable":    !info.IsDir() && info.Mode().Perm()&0111 != 0,
		"mime":          mime.TypeByExtension(strings.ToLower(filepath.Ext(p))),
	}
	if f, err := os.OpenFile(p, os.O_RDONLY, 0); err == nil {
		f.Close()
		res["readable"] = true
	} else {
		res["readable"] = false
	}
	res["writable"] = fsWritable(p)

	if info.IsDir() {
		var total int64
		files, dirs := 0, 0
		deadline := time.Now().Add(4 * time.Second)
		partial := false
		_ = filepath.WalkDir(p, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if time.Now().After(deadline) {
				partial = true
				return filepath.SkipAll
			}
			if path == p {
				return nil
			}
			if d.IsDir() {
				dirs++
				return nil
			}
			files++
			if fi, err := d.Info(); err == nil && fi.Mode().IsRegular() {
				total += fi.Size()
			}
			return nil
		})
		res["size"] = total
		res["files"] = files
		res["dirs"] = dirs
		res["partial"] = partial
	}
	writeJSON(w, http.StatusOK, res)
}

// ---------- /api/fs/new, /api/fs/rename, /api/fs/chmod ----------

type fsNewReq struct {
	Dir   string `json:"dir"`
	Name  string `json:"name"`
	IsDir bool   `json:"isDir"`
}

// FsNewHandler creates a file or folder and refuses to touch anything that already exists.
func (h *Handler) FsNewHandler(w http.ResponseWriter, r *http.Request) {
	var req fsNewReq
	if !fsDecode(w, r, &req) {
		return
	}
	if err := fsValidName(req.Name); err != nil {
		fsFail(w, http.StatusBadRequest, err.Error())
		return
	}
	target := filepath.Join(fsResolve(h, req.Dir), strings.TrimSpace(req.Name))
	if _, err := os.Lstat(target); err == nil {
		fsFail(w, http.StatusConflict, fmt.Sprintf("\"%s\" already exists", filepath.Base(target)))
		return
	}
	var err error
	if req.IsDir {
		err = os.MkdirAll(target, 0755)
	} else {
		var f *os.File
		f, err = os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
		if err == nil {
			err = f.Close()
		}
	}
	if err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.NotifyGitChanged(target)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": target})
}

type fsRenameReq struct {
	Path    string `json:"path"`
	NewName string `json:"newName"`
}

// FsRenameHandler renames in place and never replaces an existing entry.
func (h *Handler) FsRenameHandler(w http.ResponseWriter, r *http.Request) {
	var req fsRenameReq
	if !fsDecode(w, r, &req) {
		return
	}
	if err := fsValidName(req.NewName); err != nil {
		fsFail(w, http.StatusBadRequest, err.Error())
		return
	}
	src := fsResolve(h, req.Path)
	if fsProtected(h, src) {
		fsFail(w, http.StatusForbidden, "This folder is protected and cannot be renamed")
		return
	}
	dst := filepath.Join(filepath.Dir(src), strings.TrimSpace(req.NewName))
	if dst == src {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": dst})
		return
	}
	// a case-only rename on a case-insensitive disk points at the same file
	if st, err := os.Lstat(dst); err == nil {
		if srcSt, err2 := os.Lstat(src); err2 != nil || !os.SameFile(st, srcSt) {
			fsFail(w, http.StatusConflict, fmt.Sprintf("\"%s\" already exists", filepath.Base(dst)))
			return
		}
	}
	if err := os.Rename(src, dst); err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.NotifyGitChanged(dst)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": dst})
}

type fsChmodReq struct {
	Path string `json:"path"`
	Mode string `json:"mode"` // octal, e.g. "755"
}

func (h *Handler) FsChmodHandler(w http.ResponseWriter, r *http.Request) {
	var req fsChmodReq
	if !fsDecode(w, r, &req) {
		return
	}
	mode, err := strconv.ParseUint(strings.TrimSpace(req.Mode), 8, 32)
	if err != nil || mode > 07777 {
		fsFail(w, http.StatusBadRequest, "mode must be octal, e.g. 755")
		return
	}
	p := fsResolve(h, req.Path)
	if err := os.Chmod(p, os.FileMode(mode)); err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": p})
}

// ---------- /api/fs/transfer ----------

type fsTransferReq struct {
	Sources   []string `json:"sources"`
	TargetDir string   `json:"targetDir"`
	Move      bool     `json:"move"`
	Conflict  string   `json:"conflict"` // "rename" (default), "overwrite", "skip"
}

type fsOpResult struct {
	Source  string `json:"source"`
	Target  string `json:"target,omitempty"`
	Skipped bool   `json:"skipped,omitempty"`
	Error   string `json:"error,omitempty"`
}

func fsMove(src, dst string) error {
	if err := os.Rename(src, dst); err == nil {
		return nil
	} else {
		var linkErr *os.LinkError
		if !errors.As(err, &linkErr) || !errors.Is(linkErr.Err, syscall.EXDEV) {
			return err
		}
	}
	// different file systems (e.g. app storage -> shared storage): copy, then remove the original
	if err := copyTree(src, dst); err != nil {
		_ = os.RemoveAll(dst)
		return err
	}
	return os.RemoveAll(src)
}

// FsTransferHandler copies or moves many entries into one folder with a chosen conflict policy.
func (h *Handler) FsTransferHandler(w http.ResponseWriter, r *http.Request) {
	var req fsTransferReq
	if !fsDecode(w, r, &req) {
		return
	}
	targetDir := fsResolve(h, req.TargetDir)
	if info, err := os.Stat(targetDir); err != nil || !info.IsDir() {
		fsFail(w, http.StatusBadRequest, "Destination folder does not exist")
		return
	}
	results := make([]fsOpResult, 0, len(req.Sources))
	failed := 0
	for _, raw := range req.Sources {
		src := fsResolve(h, raw)
		res := fsOpResult{Source: src}
		srcInfo, err := os.Lstat(src)
		switch {
		case err != nil:
			res.Error = err.Error()
		case req.Move && fsProtected(h, src):
			res.Error = "protected folder cannot be moved"
		case srcInfo.IsDir() && fsIsWithin(src, targetDir):
			res.Error = "cannot put a folder inside itself"
		}
		if res.Error != "" {
			failed++
			results = append(results, res)
			continue
		}
		dst := filepath.Join(targetDir, filepath.Base(src))
		if dst == src {
			if req.Move {
				res.Target = dst
				res.Skipped = true
				results = append(results, res)
				continue
			}
			dst = fsUniquePath(dst) // copy into the same folder = duplicate
		} else if _, err := os.Lstat(dst); err == nil {
			switch req.Conflict {
			case "skip":
				res.Target = dst
				res.Skipped = true
				results = append(results, res)
				continue
			case "overwrite":
				if fsProtected(h, dst) {
					res.Error = "protected folder cannot be replaced"
					failed++
					results = append(results, res)
					continue
				}
				if err := os.RemoveAll(dst); err != nil {
					res.Error = err.Error()
					failed++
					results = append(results, res)
					continue
				}
			default:
				dst = fsUniquePath(dst)
			}
		}
		if req.Move {
			err = fsMove(src, dst)
		} else {
			err = copyTree(src, dst)
		}
		res.Target = dst
		if err != nil {
			res.Error = err.Error()
			failed++
		}
		results = append(results, res)
	}
	h.NotifyGitChanged(targetDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": failed == 0, "failed": failed, "results": results})
}

// ---------- /api/fs/delete ----------

type fsPathsReq struct {
	Paths []string `json:"paths"`
}

// FsDeleteHandler deletes many entries; system and top-level folders are always refused.
func (h *Handler) FsDeleteHandler(w http.ResponseWriter, r *http.Request) {
	var req fsPathsReq
	if !fsDecode(w, r, &req) {
		return
	}
	results := make([]fsOpResult, 0, len(req.Paths))
	failed := 0
	for _, raw := range req.Paths {
		p := fsResolve(h, raw)
		res := fsOpResult{Source: p}
		if strings.TrimSpace(raw) == "" || fsProtected(h, p) {
			res.Error = "protected location cannot be deleted"
		} else if _, err := os.Lstat(p); err != nil {
			res.Error = err.Error()
		} else if err := os.RemoveAll(p); err != nil {
			res.Error = err.Error()
		}
		if res.Error != "" {
			failed++
		} else {
			h.NotifyGitChanged(p)
		}
		results = append(results, res)
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": failed == 0, "failed": failed, "results": results})
}

// ---------- /api/fs/compress ----------

type fsCompressReq struct {
	Sources []string `json:"sources"`
	Target  string   `json:"target"` // full path of the .zip to create; made unique when taken
}

// FsCompressHandler zips files and folders (folder contents keep their relative paths).
func (h *Handler) FsCompressHandler(w http.ResponseWriter, r *http.Request) {
	var req fsCompressReq
	if !fsDecode(w, r, &req) {
		return
	}
	if len(req.Sources) == 0 {
		fsFail(w, http.StatusBadRequest, "nothing to compress")
		return
	}
	target := fsResolve(h, req.Target)
	if !strings.HasSuffix(strings.ToLower(target), ".zip") {
		target += ".zip"
	}
	target = fsUniquePath(target)

	out, err := os.Create(target)
	if err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	zw := zip.NewWriter(out)
	fail := func(e error) {
		zw.Close()
		out.Close()
		os.Remove(target)
		fsFail(w, http.StatusInternalServerError, e.Error())
	}
	count := 0
	for _, raw := range req.Sources {
		src := fsResolve(h, raw)
		base := filepath.Dir(src)
		err := filepath.WalkDir(src, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if path == target {
				return nil
			}
			rel, err := filepath.Rel(base, path)
			if err != nil {
				return err
			}
			name := filepath.ToSlash(rel)
			info, err := d.Info()
			if err != nil {
				return err
			}
			if d.IsDir() {
				hdr, _ := zip.FileInfoHeader(info)
				hdr.Name = name + "/"
				_, err := zw.CreateHeader(hdr)
				return err
			}
			if !info.Mode().IsRegular() {
				return nil // sockets, pipes and links are left out
			}
			hdr, err := zip.FileInfoHeader(info)
			if err != nil {
				return err
			}
			hdr.Name = name
			hdr.Method = zip.Deflate
			wr, err := zw.CreateHeader(hdr)
			if err != nil {
				return err
			}
			f, err := os.Open(path)
			if err != nil {
				return err
			}
			defer f.Close()
			_, err = io.Copy(wr, f)
			count++
			return err
		})
		if err != nil {
			fail(err)
			return
		}
	}
	if err := zw.Close(); err != nil {
		out.Close()
		os.Remove(target)
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := out.Close(); err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	size := int64(0)
	if st, err := os.Stat(target); err == nil {
		size = st.Size()
	}
	h.NotifyGitChanged(target)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": target, "files": count, "size": size})
}

// ---------- /api/fs/extract ----------

type fsExtractReq struct {
	Path      string `json:"path"`
	TargetDir string `json:"targetDir"` // empty = a new folder named after the archive, next to it
	Overwrite bool   `json:"overwrite"`
}

func fsArchiveKind(name string) (kind string, stem string) {
	lower := strings.ToLower(name)
	for _, s := range []struct{ suffix, kind string }{
		{".tar.gz", "tgz"}, {".tgz", "tgz"}, {".tar.bz2", "tbz"}, {".tbz2", "tbz"}, {".tar", "tar"},
		{".zip", "zip"}, {".jar", "zip"}, {".apk", "zip"}, {".aar", "zip"}, {".whl", "zip"}, {".epub", "zip"},
		{".gz", "gz"}, {".bz2", "bz2"},
	} {
		if strings.HasSuffix(lower, s.suffix) && len(name) > len(s.suffix) {
			return s.kind, name[:len(name)-len(s.suffix)]
		}
	}
	return "", name
}

type fsExtractor struct {
	root      string
	overwrite bool
	count     int
}

func (x *fsExtractor) target(name string) (string, error) {
	name = strings.TrimLeft(filepath.FromSlash(name), string(filepath.Separator))
	dst := filepath.Join(x.root, name)
	if !fsIsWithin(x.root, dst) {
		return "", fmt.Errorf("unsafe path in archive: %s", name)
	}
	return dst, nil
}

func (x *fsExtractor) writeFile(dst string, mode fs.FileMode, rd io.Reader) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
		return err
	}
	if _, err := os.Lstat(dst); err == nil {
		if !x.overwrite {
			dst = fsUniquePath(dst)
		} else if err := os.RemoveAll(dst); err != nil {
			return err
		}
	}
	perm := mode.Perm()
	if perm == 0 {
		perm = 0644
	}
	f, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, perm|0600)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, rd); err != nil {
		f.Close()
		return err
	}
	x.count++
	return f.Close()
}

func (x *fsExtractor) zip(path string) error {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return err
	}
	defer zr.Close()
	for _, f := range zr.File {
		dst, err := x.target(f.Name)
		if err != nil {
			return err
		}
		if f.FileInfo().IsDir() || strings.HasSuffix(f.Name, "/") {
			if err := os.MkdirAll(dst, 0755); err != nil {
				return err
			}
			continue
		}
		if f.Mode()&os.ModeSymlink != 0 {
			continue // links inside archives could point outside the folder
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		err = x.writeFile(dst, f.Mode(), rc)
		rc.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

func (x *fsExtractor) tar(rd io.Reader) error {
	tr := tar.NewReader(rd)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		dst, err := x.target(hdr.Name)
		if err != nil {
			return err
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(dst, 0755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := x.writeFile(dst, fs.FileMode(hdr.Mode), tr); err != nil {
				return err
			}
		}
	}
}

// FsExtractHandler unpacks zip (and jar/apk), tar, tar.gz, tar.bz2, gz and bz2 archives.
func (h *Handler) FsExtractHandler(w http.ResponseWriter, r *http.Request) {
	var req fsExtractReq
	if !fsDecode(w, r, &req) {
		return
	}
	src := fsResolve(h, req.Path)
	kind, stem := fsArchiveKind(filepath.Base(src))
	if kind == "" {
		fsFail(w, http.StatusBadRequest, "Unsupported archive. Supported: zip, jar, apk, tar, tar.gz, tgz, tar.bz2, gz, bz2")
		return
	}
	targetDir := ""
	if strings.TrimSpace(req.TargetDir) == "" {
		if kind == "gz" || kind == "bz2" {
			targetDir = filepath.Dir(src)
		} else {
			targetDir = fsUniquePath(filepath.Join(filepath.Dir(src), stem))
		}
	} else {
		targetDir = fsResolve(h, req.TargetDir)
	}
	_, statErr := os.Stat(targetDir)
	createdTarget := os.IsNotExist(statErr)
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	x := &fsExtractor{root: targetDir, overwrite: req.Overwrite}

	var err error
	switch kind {
	case "zip":
		err = x.zip(src)
	default:
		var f *os.File
		f, err = os.Open(src)
		if err == nil {
			defer f.Close()
			switch kind {
			case "tar":
				err = x.tar(f)
			case "tgz":
				var gz *gzip.Reader
				if gz, err = gzip.NewReader(f); err == nil {
					err = x.tar(gz)
					gz.Close()
				}
			case "tbz":
				err = x.tar(bzip2.NewReader(f))
			case "gz":
				var gz *gzip.Reader
				if gz, err = gzip.NewReader(f); err == nil {
					err = x.writeFile(filepath.Join(targetDir, stem), 0644, gz)
					gz.Close()
				}
			case "bz2":
				err = x.writeFile(filepath.Join(targetDir, stem), 0644, bzip2.NewReader(f))
			}
		}
	}
	if err != nil {
		if createdTarget {
			_ = os.RemoveAll(targetDir) // leave nothing half-extracted behind
		}
		fsFail(w, http.StatusInternalServerError, "Extract failed: "+err.Error())
		return
	}
	h.NotifyGitChanged(targetDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "targetDir": targetDir, "files": x.count})
}

// ---------- /api/fs/archive/list ----------

// FsArchiveListHandler lists what is inside a zip or tar archive without unpacking it.
func (h *Handler) FsArchiveListHandler(w http.ResponseWriter, r *http.Request) {
	src := fsResolve(h, r.URL.Query().Get("path"))
	kind, _ := fsArchiveKind(filepath.Base(src))
	type entry struct {
		Name  string `json:"name"`
		Size  int64  `json:"size"`
		IsDir bool   `json:"isDir"`
	}
	var entries []entry
	const limit = 2000
	truncated := false
	switch kind {
	case "zip":
		zr, err := zip.OpenReader(src)
		if err != nil {
			fsFail(w, http.StatusInternalServerError, err.Error())
			return
		}
		defer zr.Close()
		for _, f := range zr.File {
			if len(entries) >= limit {
				truncated = true
				break
			}
			entries = append(entries, entry{Name: f.Name, Size: int64(f.UncompressedSize64), IsDir: f.FileInfo().IsDir()})
		}
	case "tar", "tgz", "tbz":
		f, err := os.Open(src)
		if err != nil {
			fsFail(w, http.StatusInternalServerError, err.Error())
			return
		}
		defer f.Close()
		var rd io.Reader = f
		if kind == "tgz" {
			gz, err := gzip.NewReader(f)
			if err != nil {
				fsFail(w, http.StatusInternalServerError, err.Error())
				return
			}
			defer gz.Close()
			rd = gz
		} else if kind == "tbz" {
			rd = bzip2.NewReader(f)
		}
		tr := tar.NewReader(rd)
		for {
			hdr, err := tr.Next()
			if err != nil {
				break
			}
			if len(entries) >= limit {
				truncated = true
				break
			}
			entries = append(entries, entry{Name: hdr.Name, Size: hdr.Size, IsDir: hdr.Typeflag == tar.TypeDir})
		}
	default:
		fsFail(w, http.StatusBadRequest, "Not a browsable archive")
		return
	}
	if entries == nil {
		entries = []entry{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "entries": entries, "truncated": truncated})
}

// ---------- /api/fs/search ----------

// FsSearchHandler finds files and folders by name below a folder (case-insensitive, bounded in time and count).
func (h *Handler) FsSearchHandler(w http.ResponseWriter, r *http.Request) {
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	dir := fsResolve(h, r.URL.Query().Get("dir"))
	includeHidden := r.URL.Query().Get("hidden") == "1"
	limit := 300
	if n, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && n > 0 && n <= 2000 {
		limit = n
	}
	items := []map[string]interface{}{}
	if q == "" {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "items": items})
		return
	}
	skip := map[string]bool{"node_modules": true, ".git": true, ".gradle": true, "build": true, "proc": true, "sys": true}
	deadline := time.Now().Add(8 * time.Second)
	truncated := false
	_ = filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if d != nil && d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if path == dir {
			return nil
		}
		if time.Now().After(deadline) || len(items) >= limit {
			truncated = true
			return filepath.SkipAll
		}
		name := d.Name()
		hidden := strings.HasPrefix(name, ".")
		if d.IsDir() && (skip[name] || (hidden && !includeHidden)) {
			if strings.Contains(strings.ToLower(name), q) && (!hidden || includeHidden) {
				items = append(items, fsItemJSON(path, d))
			}
			return filepath.SkipDir
		}
		if hidden && !includeHidden {
			return nil
		}
		if strings.Contains(strings.ToLower(name), q) {
			items = append(items, fsItemJSON(path, d))
		}
		return nil
	})
	sortFsItems(items)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "items": items, "truncated": truncated})
}

func fsItemJSON(path string, d fs.DirEntry) map[string]interface{} {
	var size, mod int64
	isDir := d.IsDir()
	if st, err := os.Stat(path); err == nil {
		size, mod, isDir = st.Size(), st.ModTime().UnixMilli(), st.IsDir()
	}
	ext := ""
	if !isDir {
		ext = strings.ToLower(filepath.Ext(d.Name()))
	}
	return map[string]interface{}{
		"name": d.Name(), "path": path, "isDir": isDir, "size": size, "modTime": mod, "ext": ext,
		"hidden": strings.HasPrefix(d.Name(), "."), "symlink": d.Type()&os.ModeSymlink != 0,
	}
}

// ---------- /api/fs/raw and /api/fs/upload ----------

// FsRawHandler streams a file's bytes (with Range support) so other apps can play or view it.
func (h *Handler) FsRawHandler(w http.ResponseWriter, r *http.Request) {
	p := fsResolve(h, r.URL.Query().Get("path"))
	f, err := os.Open(p)
	if err != nil {
		fsFail(w, http.StatusNotFound, err.Error())
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.IsDir() {
		fsFail(w, http.StatusBadRequest, "not a file")
		return
	}
	if ct := mime.TypeByExtension(strings.ToLower(filepath.Ext(p))); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	if r.URL.Query().Get("download") == "1" {
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filepath.Base(p)))
	}
	http.ServeContent(w, r, filepath.Base(p), info.ModTime(), f)
}

// FsUploadHandler writes the raw request body to ?path= (conflict: "rename" default, "overwrite", "fail").
func (h *Handler) FsUploadHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		fsFail(w, http.StatusMethodNotAllowed, "use POST")
		return
	}
	target := fsResolve(h, r.URL.Query().Get("path"))
	if err := fsValidName(filepath.Base(target)); err != nil {
		fsFail(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	if st, err := os.Lstat(target); err == nil {
		switch r.URL.Query().Get("conflict") {
		case "overwrite":
			if st.IsDir() {
				fsFail(w, http.StatusConflict, "a folder with this name already exists")
				return
			}
		case "fail":
			fsFail(w, http.StatusConflict, fmt.Sprintf("\"%s\" already exists", filepath.Base(target)))
			return
		default:
			target = fsUniquePath(target)
		}
	}
	tmp := target + ".part"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if err != nil {
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	n, err := io.Copy(f, r.Body)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp)
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	if err := os.Rename(tmp, target); err != nil {
		os.Remove(tmp)
		fsFail(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.NotifyGitChanged(target)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "path": target, "size": n})
}

// sortFsItems orders folders first, then by name (case-insensitive).
func sortFsItems(items []map[string]interface{}) {
	sort.SliceStable(items, func(i, j int) bool {
		di, dj := items[i]["isDir"].(bool), items[j]["isDir"].(bool)
		if di != dj {
			return di
		}
		return strings.ToLower(items[i]["name"].(string)) < strings.ToLower(items[j]["name"].(string))
	})
}
