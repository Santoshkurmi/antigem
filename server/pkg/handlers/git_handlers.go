package handlers

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"gemini-server/pkg/git"
	"gemini-server/pkg/models"
)

func (h *Handler) resolveProjectDir(r *http.Request) string {
	project := r.URL.Query().Get("project")
	if project == "" {
		project = h.Cfg.WorkspaceDir
	}
	return project
}

func (h *Handler) GitStatusHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	status, err := git.GetStatus(projectDir)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"isGitRepo":      false,
			"error":          err.Error(),
			"branch":         "HEAD",
			"stagedFiles":    []models.GitFileStatus{},
			"unstagedFiles":  []models.GitFileStatus{},
			"untrackedFiles": []models.GitFileStatus{},
		})
		return
	}
	writeJSON(w, http.StatusOK, status)
}

func (h *Handler) GitInitHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.InitRepo(projectDir); err != nil {
		writeJSON(w, http.StatusInternalServerError, models.GitActionResult{
			Success: false,
			Error:   err.Error(),
		})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, models.GitActionResult{
		Success: true,
		Output:  "Initialized Git repository with default .gitignore",
	})
}

func (h *Handler) GitGetConfigHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	config, err := git.GetConfig(projectDir)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, config)
}

func (h *Handler) GitSetConfigHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitSetConfigReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid payload"})
		return
	}
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.SetConfig(projectDir, req); err != nil {
		writeJSON(w, http.StatusInternalServerError, models.GitActionResult{
			Success: false,
			Error:   err.Error(),
		})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, models.GitActionResult{
		Success: true,
		Output:  "Git configuration updated successfully",
	})
}

func (h *Handler) GitBranchesHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	branches, err := git.GetBranches(projectDir)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"branches": []models.GitBranchInfo{}})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"branches": branches})
}

func (h *Handler) GitCheckoutHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Branch == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "branch required"})
		return
	}
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.CheckoutBranch(projectDir, req.Branch, req.Create); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "branch": req.Branch})
}

func (h *Handler) GitStageHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.Stage(projectDir, req.Paths); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (h *Handler) GitUnstageHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.Unstage(projectDir, req.Paths); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (h *Handler) GitDiscardHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.Discard(projectDir, req.Paths); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (h *Handler) GitCommitHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.Message) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "commit message required"})
		return
	}
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.Commit(projectDir, req.Message); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (h *Handler) GitPushHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	out, err := git.PushWithOutput(projectDir)
	if err != nil {
		writeJSON(w, http.StatusOK, models.GitActionResult{
			Success: false,
			Output:  out,
			Error:   err.Error(),
		})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, models.GitActionResult{
		Success: true,
		Output:  out,
	})
}

func (h *Handler) GitPullHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	out, err := git.PullWithOutput(projectDir)
	if err != nil {
		writeJSON(w, http.StatusOK, models.GitActionResult{
			Success: false,
			Output:  out,
			Error:   err.Error(),
		})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, models.GitActionResult{
		Success: true,
		Output:  out,
	})
}

func (h *Handler) GitStashHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.Stash(projectDir, req.Message); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (h *Handler) GitStashPopHandler(w http.ResponseWriter, r *http.Request) {
	var req models.GitActionReq
	_ = json.NewDecoder(r.Body).Decode(&req)
	projectDir := req.Project
	if projectDir == "" {
		projectDir = h.Cfg.WorkspaceDir
	}

	if err := git.StashPop(projectDir); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	h.NotifyGitChanged(projectDir)
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
}

func (h *Handler) GitDiffHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	path := r.URL.Query().Get("file")
	staged := r.URL.Query().Get("staged") == "true"

	if path == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "file parameter required"})
		return
	}

	diffRes, err := git.GetDiff(projectDir, path, staged)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, diffRes)
}

func (h *Handler) GitLogHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	limit := 20
	skip := 0
	if l := r.URL.Query().Get("limit"); l != "" {
		if val, err := strconv.Atoi(l); err == nil && val > 0 {
			limit = val
		}
	}
	if s := r.URL.Query().Get("skip"); s != "" {
		if val, err := strconv.Atoi(s); err == nil && val > 0 {
			skip = val
		}
	}

	logs, err := git.GetLog(projectDir, limit, skip)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"commits": []models.GitCommitLog{}})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"commits": logs})
}

func (h *Handler) GitCommitDetailsHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	hash := r.URL.Query().Get("hash")
	if hash == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "hash parameter required"})
		return
	}

	details, err := git.GetCommitDetails(projectDir, hash)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, details)
}

func (h *Handler) GitCommitFileDiffHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	hash := r.URL.Query().Get("hash")
	file := r.URL.Query().Get("file")
	if hash == "" || file == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "hash and file parameters required"})
		return
	}

	diffOut, err := git.GetCommitFileDiff(projectDir, hash, file)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"hash": hash,
		"file": file,
		"diff": diffOut,
	})
}

func (h *Handler) GitCommitFileContentHandler(w http.ResponseWriter, r *http.Request) {
	projectDir := h.resolveProjectDir(r)
	hash := r.URL.Query().Get("hash")
	file := r.URL.Query().Get("file")
	if hash == "" || file == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "hash and file parameters required"})
		return
	}

	content, err := git.GetCommitFileContent(projectDir, hash, file)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"hash":    hash,
		"file":    file,
		"content": content,
	})
}

