package plugins

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// ServeHTTP handles /api/custom-plugins/…:
//
//	GET  /                    → {plugins}
//	POST /inspect             (ZIP body) → {manifest}
//	POST /install?folder=&update_id=&as_new=  (ZIP body) → {plugin, replaced}
//	POST /commit {folder}     drop the backup kept by the last install
//	POST /restore {folder}    undo the last install (the app calls this when the new version fails to load)
//	POST /remove {id, delete_data}
//	POST /set-enabled {id, enabled}
//	POST /rename {id, folder}
//	GET  /export?id=          → plugin ZIP
//	POST /repo/list {repo}    → listing
//	POST /repo/install {repo, path, folder, update_id, as_new} → {plugin, replaced}
//	GET  /repo/progress?folder= → {active, phase, bytes_done, bytes_total, files_done, files_total} of that running install
//	GET  /drafts               → {projects}   (developer MCP drafts in ~/extension-projects)
//	POST /drafts/create {project_id, files} · /drafts/read {project_id, path?} · /drafts/write {project_id, files}
//	POST /drafts/package {project_id} → {zip_path, manifest, …}
//	POST /drafts/install {project_id | zip_path, folder, update_id} → {plugin, replaced}
func (m *Manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	route := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/custom-plugins"), "/")
	if r.Method == http.MethodGet {
		switch route {
		case "":
			m.mu.Lock()
			plugins := m.scan()
			m.mu.Unlock()
			if plugins == nil {
				plugins = []Plugin{}
			}
			writeJSON(w, http.StatusOK, map[string]interface{}{"plugins": plugins})
		case "/export":
			m.handleExport(w, r)
		case "/repo/progress":
			t, ok := m.installs.Load(r.URL.Query().Get("folder"))
			if !ok {
				writeJSON(w, http.StatusOK, map[string]interface{}{"active": false})
				return
			}
			pr := t.(*progressTracker).snapshot()
			writeJSON(w, http.StatusOK, map[string]interface{}{"active": true, "phase": pr.Phase, "bytes_done": pr.BytesDone,
				"bytes_total": pr.BytesTotal, "files_done": pr.FilesDone, "files_total": pr.FilesTotal})
		case "/drafts":
			m.draftsMu.Lock()
			projects, err := m.draftProjects()
			m.draftsMu.Unlock()
			if err != nil {
				fail(w, err)
				return
			}
			writeJSON(w, http.StatusOK, map[string]interface{}{"projects": projects})
		default:
			http.NotFound(w, r)
		}
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "use GET or POST"})
		return
	}
	if strings.HasPrefix(route, "/drafts/") {
		m.serveDrafts(w, r, strings.TrimPrefix(route, "/drafts/"))
		return
	}
	switch route {
	case "/inspect":
		m.withUploadedPackage(w, r, func(pkg string) {
			manifest, err := readManifest(pkg)
			if err != nil {
				fail(w, err)
				return
			}
			writeJSON(w, http.StatusOK, map[string]interface{}{"manifest": manifest})
		})
	case "/install":
		q := r.URL.Query()
		m.withUploadedPackage(w, r, func(pkg string) {
			m.respondInstall(w, pkg, q.Get("folder"), q.Get("update_id"), q.Get("as_new") == "true", nil)
		})
	case "/repo/install":
		var req struct {
			Repo, Path, Folder string
			UpdateID           string `json:"update_id"`
			AsNew              bool   `json:"as_new"`
		}
		if !decode(w, r, &req) {
			return
		}
		repo, err := ParseRepo(req.Repo)
		if err != nil {
			fail(w, err)
			return
		}
		p := strings.Trim(req.Path, "/")
		if !validPath(p) {
			fail(w, fmt.Errorf("invalid plugin path %q", req.Path))
			return
		}
		stage, err := m.newStage()
		if err != nil {
			fail(w, err)
			return
		}
		defer os.RemoveAll(stage)
		t := &progressTracker{p: Progress{Phase: "listing"}}
		m.installs.Store(req.Folder, t)
		defer m.installs.Delete(req.Folder)
		pkg := filepath.Join(stage, "pkg")
		if err := m.downloadFolder(repo, p, pkg, t); err != nil {
			fail(w, err)
			return
		}
		t.set(func(pr *Progress) { pr.Phase = "installing" })
		if !fileExists(filepath.Join(pkg, "manifest.json")) {
			fail(w, fmt.Errorf("%s in %s has no manifest.json", p, repo.slug()))
			return
		}
		m.respondInstall(w, pkg, req.Folder, req.UpdateID, req.AsNew, &Source{repo.slug(), repo.Ref, p})
	case "/repo/list":
		var req struct{ Repo string }
		if !decode(w, r, &req) {
			return
		}
		repo, err := ParseRepo(req.Repo)
		if err != nil {
			fail(w, err)
			return
		}
		listing, err := m.listRepo(repo)
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, http.StatusOK, listing)
	case "/commit", "/restore":
		var req struct{ Folder string }
		if !decode(w, r, &req) {
			return
		}
		m.mu.Lock()
		var err error
		if route == "/commit" {
			err = m.commit(req.Folder)
		} else {
			err = m.restore(req.Folder)
		}
		m.mu.Unlock()
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"success": true})
	case "/remove":
		var req struct {
			ID         string
			DeleteData bool `json:"delete_data"`
		}
		if !decode(w, r, &req) {
			return
		}
		m.mu.Lock()
		err := m.remove(req.ID, req.DeleteData)
		m.mu.Unlock()
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"success": true})
	case "/set-enabled":
		var req struct {
			ID      string
			Enabled bool
		}
		if !decode(w, r, &req) {
			return
		}
		m.mu.Lock()
		p, err := m.setEnabled(req.ID, req.Enabled)
		m.mu.Unlock()
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"plugin": p})
	case "/rename":
		var req struct{ ID, Folder string }
		if !decode(w, r, &req) {
			return
		}
		m.mu.Lock()
		p, err := m.rename(req.ID, req.Folder)
		m.mu.Unlock()
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"plugin": p})
	default:
		http.NotFound(w, r)
	}
}

func (m *Manager) serveDrafts(w http.ResponseWriter, r *http.Request, action string) {
	var req struct {
		ProjectID string      `json:"project_id"`
		Path      string      `json:"path"`
		Files     []draftFile `json:"files"`
		ZipPath   string      `json:"zip_path"`
		Folder    string      `json:"folder"`
		UpdateID  string      `json:"update_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxTotal+16<<20)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON body"})
		return
	}
	if action == "install" {
		m.installDraft(w, req.ProjectID, req.ZipPath, req.Folder, req.UpdateID)
		return
	}
	m.draftsMu.Lock()
	defer m.draftsMu.Unlock()
	var result interface{}
	var err error
	switch action {
	case "create":
		result, err = m.createDraft(req.ProjectID, req.Files)
	case "read":
		result, err = m.readDraft(req.ProjectID, req.Path)
	case "write":
		var src string
		if src, err = m.draftSource(req.ProjectID); err == nil {
			var written []string
			if written, err = writeDraftFiles(src, req.Files); err == nil {
				var total int
				for _, f := range req.Files {
					total += len(f.Content)
				}
				result = map[string]interface{}{"written": written, "bytes": total, "installed_worker_changed": false}
			}
		}
	case "package":
		result, err = m.packageDraft(req.ProjectID)
	default:
		http.NotFound(w, r)
		return
	}
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

// installDraft packages a draft (or takes a ZIP from the terminal home or shared storage) and installs it.
func (m *Manager) installDraft(w http.ResponseWriter, projectID, zipPath, folder, updateID string) {
	if (projectID == "") == (zipPath == "") {
		fail(w, errors.New("Provide exactly one of project_id or zip_path"))
		return
	}
	if projectID != "" {
		m.draftsMu.Lock()
		packaged, err := m.packageDraft(projectID)
		m.draftsMu.Unlock()
		if err != nil {
			fail(w, err)
			return
		}
		zipPath = packaged["zip_path"].(string)
	} else {
		resolved, err := m.installableZip(zipPath)
		if err != nil {
			fail(w, err)
			return
		}
		zipPath = resolved
	}
	stage, err := m.newStage()
	if err != nil {
		fail(w, err)
		return
	}
	defer os.RemoveAll(stage)
	pkg, err := extractZip(zipPath, filepath.Join(stage, "unpacked"))
	if err != nil {
		fail(w, err)
		return
	}
	m.respondInstall(w, pkg, folder, updateID, false, nil)
}

func (m *Manager) respondInstall(w http.ResponseWriter, pkg, folder, updateID string, asNew bool, source *Source) {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, statErr := os.Lstat(filepath.Join(m.managedRoot(), folder))
	p, err := m.install(pkg, folder, updateID, asNew, source)
	if err != nil {
		fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"plugin": p, "replaced": statErr == nil})
}

// withUploadedPackage saves a ZIP request body, unpacks it safely and passes the package folder to fn.
func (m *Manager) withUploadedPackage(w http.ResponseWriter, r *http.Request, fn func(pkg string)) {
	stage, err := m.newStage()
	if err != nil {
		fail(w, err)
		return
	}
	defer os.RemoveAll(stage)
	zipPath := filepath.Join(stage, "upload.zip")
	out, err := os.Create(zipPath)
	if err != nil {
		fail(w, err)
		return
	}
	_, err = io.Copy(out, http.MaxBytesReader(w, r.Body, maxTotal+16<<20))
	out.Close()
	if err != nil {
		fail(w, fmt.Errorf("could not read the ZIP: %w", err))
		return
	}
	pkg, err := extractZip(zipPath, filepath.Join(stage, "unpacked"))
	if err != nil {
		fail(w, err)
		return
	}
	fn(pkg)
}

func (m *Manager) handleExport(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	p, ok := m.find(r.URL.Query().Get("id"))
	m.mu.Unlock()
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Extension not found"})
		return
	}
	name := p.FolderName
	if p.Version != "" {
		name += "-" + strings.TrimPrefix(p.Version, "v")
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename=%q`, name+".zip"))
	// Repository bookkeeping stays out of shared copies
	if err := writeZip(p.DirPath, w, func(rel string) bool { return rel == sourceFile }); err != nil {
		fmt.Fprintf(os.Stderr, "[plugins] export %s: %v\n", p.ID, err)
	}
}

func decode(w http.ResponseWriter, r *http.Request, v interface{}) bool {
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid JSON body"})
		return false
	}
	return true
}

func fail(w http.ResponseWriter, err error) {
	writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
