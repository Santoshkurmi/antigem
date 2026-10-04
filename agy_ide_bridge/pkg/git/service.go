package git

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"

	"gemini-server/pkg/config"
	"gemini-server/pkg/models"
)

var (
	aheadReg        = regexp.MustCompile(`ahead (\d+)`)
	behindReg       = regexp.MustCompile(`behind (\d+)`)
	cachedGitBinary string
	cachedGitOnce   sync.Once
)

func getGitBinary() string {
	cachedGitOnce.Do(func() {
		if p := config.SafeLookPath("git"); p != "" {
			cachedGitBinary = p
			return
		}
		cachedGitBinary = "git"
	})
	return cachedGitBinary
}

func getEnv() []string {
	env := os.Environ()
	termuxBin := "/data/data/com.termux/files/usr/bin"
	home, _ := os.UserHomeDir()
	localBin := filepath.Join(home, ".local", "bin")
	geminiBin := filepath.Join(home, ".gemini", "bin")

	pathFound := false
	for i, e := range env {
		if strings.HasPrefix(e, "PATH=") {
			pathFound = true
			curPath := strings.TrimPrefix(e, "PATH=")
			var newParts []string
			if home != "" && !strings.Contains(curPath, localBin) {
				newParts = append(newParts, localBin)
			}
			if !strings.Contains(curPath, termuxBin) {
				newParts = append(newParts, termuxBin)
			}
			if home != "" && !strings.Contains(curPath, geminiBin) {
				newParts = append(newParts, geminiBin)
			}
			newParts = append(newParts, curPath)
			env[i] = "PATH=" + strings.Join(newParts, ":")
			break
		}
	}
	if !pathFound {
		env = append(env, "PATH="+localBin+":"+termuxBin+":"+geminiBin+":/usr/local/bin:/usr/bin:/bin:/system/bin:/system/xbin")
	}
	return env
}

func cleanDir(dir string) (string, error) {
	d := config.ExpandHome(strings.TrimSpace(dir))
	if d == "" {
		return "", fmt.Errorf("empty project directory")
	}
	d = filepath.Clean(d)
	fi, err := os.Stat(d)
	if err != nil {
		return "", fmt.Errorf("project directory does not exist: %s", d)
	}
	if !fi.IsDir() {
		return "", fmt.Errorf("project path is not a directory: %s", d)
	}
	return d, nil
}

func runGitCmd(dir string, args ...string) (string, error) {
	resolvedDir, err := cleanDir(dir)
	if err != nil {
		return "", err
	}

	shArgs := append([]string{"-c", `exec git "$@"`, "git"}, args...)
	cmd := exec.Command("sh", shArgs...)
	cmd.Dir = resolvedDir
	cmd.Env = getEnv()

	var out, errOut bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errOut

	if err := cmd.Run(); err != nil {
		errStr := strings.TrimSpace(errOut.String())
		if errStr == "" {
			errStr = strings.TrimSpace(out.String())
		}
		if errStr == "" {
			errStr = err.Error()
		}
		return out.String(), fmt.Errorf("%s", errStr)
	}
	return out.String(), nil
}

// runGitDiffCmd handles git diff commands where exit code 1 signifies differences exist (not a fatal failure).
func runGitDiffCmd(dir string, args ...string) (string, error) {
	resolvedDir, err := cleanDir(dir)
	if err != nil {
		return "", err
	}

	shArgs := append([]string{"-c", `exec git "$@"`, "git"}, args...)
	cmd := exec.Command("sh", shArgs...)
	cmd.Dir = resolvedDir
	cmd.Env = getEnv()

	var out, errOut bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errOut

	err = cmd.Run()
	if err != nil {
		// Exit code 1 for git diff indicates differences were found
		if exitErr, ok := err.(*exec.ExitError); ok && exitErr.ExitCode() == 1 {
			return out.String(), nil
		}
		errStr := strings.TrimSpace(errOut.String())
		if errStr == "" {
			errStr = strings.TrimSpace(out.String())
		}
		if errStr == "" {
			errStr = err.Error()
		}
		return out.String(), fmt.Errorf("%s", errStr)
	}
	return out.String(), nil
}

func unquotePath(p string) string {
	p = strings.TrimSpace(p)
	if strings.HasPrefix(p, "\"") && strings.HasSuffix(p, "\"") {
		if unq, err := strconv.Unquote(p); err == nil {
			return unq
		}
	}
	return p
}

// GetStatus parses git status into structured Staged, Unstaged, and Untracked lists.
func GetStatus(projectDir string) (res *models.GitStatusResponse, err error) {
	defer func() {
		if r := recover(); r != nil {
			res = &models.GitStatusResponse{
				IsGitRepo:      false,
				Branch:         "HEAD",
				StagedFiles:    []models.GitFileStatus{},
				UnstagedFiles:  []models.GitFileStatus{},
				UntrackedFiles: []models.GitFileStatus{},
			}
			err = fmt.Errorf("panic in GetStatus: %v", r)
		}
	}()

	emptyResp := &models.GitStatusResponse{
		IsGitRepo:      false,
		Branch:         "HEAD",
		StagedFiles:    []models.GitFileStatus{},
		UnstagedFiles:  []models.GitFileStatus{},
		UntrackedFiles: []models.GitFileStatus{},
	}

	out, err := runGitCmd(projectDir, "status", "--porcelain=v1", "-b", "-uall")
	if err != nil {
		errStr := strings.ToLower(err.Error())
		if strings.Contains(errStr, "not a git repository") || strings.Contains(errStr, "does not exist") || strings.Contains(errStr, "no such file") {
			return emptyResp, nil
		}
		return emptyResp, err
	}

	res = &models.GitStatusResponse{
		IsGitRepo:      true,
		Branch:         "HEAD",
		StagedFiles:    []models.GitFileStatus{},
		UnstagedFiles:  []models.GitFileStatus{},
		UntrackedFiles: []models.GitFileStatus{},
	}

	lines := strings.Split(out, "\n")
	for _, line := range lines {
		if line == "" {
			continue
		}

		if strings.HasPrefix(line, "##") {
			// Branch info header: "## branch...tracking [ahead 1, behind 2]"
			header := strings.TrimPrefix(line, "## ")
			if strings.HasPrefix(header, "Initial commit on ") {
				res.Branch = strings.TrimPrefix(header, "Initial commit on ")
			} else if strings.HasPrefix(header, "No commits yet on ") {
				res.Branch = strings.TrimPrefix(header, "No commits yet on ")
			} else if strings.HasPrefix(header, "HEAD (no branch)") {
				res.Branch = "HEAD"
			} else {
				parts := strings.SplitN(header, "...", 2)
				res.Branch = strings.TrimSpace(parts[0])
				if len(parts) > 1 {
					trackingPart := parts[1]
					if idx := strings.Index(trackingPart, " ["); idx != -1 {
						res.Tracking = trackingPart[:idx]
						meta := trackingPart[idx:]
						if m := aheadReg.FindStringSubmatch(meta); len(m) > 1 {
							res.Ahead, _ = strconv.Atoi(m[1])
						}
						if m := behindReg.FindStringSubmatch(meta); len(m) > 1 {
							res.Behind, _ = strconv.Atoi(m[1])
						}
					} else {
						res.Tracking = strings.TrimSpace(trackingPart)
					}
				}
			}
			continue
		}

		if len(line) < 3 {
			continue
		}

		indexStatus := line[0]
		workTreeStatus := line[1]
		filePath := strings.TrimSpace(line[2:])
		filePath = unquotePath(filePath)

		// Handle renamed files (e.g. "old -> new")
		oldPath := ""
		if strings.Contains(filePath, " -> ") {
			parts := strings.SplitN(filePath, " -> ", 2)
			oldPath = unquotePath(parts[0])
			filePath = unquotePath(parts[1])
		}

		// 1. Untracked
		if indexStatus == '?' && workTreeStatus == '?' {
			res.UntrackedFiles = append(res.UntrackedFiles, models.GitFileStatus{
				Path:   filePath,
				Status: "U",
				Staged: false,
			})
			continue
		}

		// 2. Staged
		if indexStatus != ' ' && indexStatus != '?' {
			res.StagedFiles = append(res.StagedFiles, models.GitFileStatus{
				Path:    filePath,
				OldPath: oldPath,
				Status:  string(indexStatus),
				Staged:  true,
			})
		}

		// 3. Unstaged / Working tree modified
		if workTreeStatus != ' ' && workTreeStatus != '?' {
			res.UnstagedFiles = append(res.UnstagedFiles, models.GitFileStatus{
				Path:    filePath,
				OldPath: oldPath,
				Status:  string(workTreeStatus),
				Staged:  false,
			})
		}
	}

	// Check for existing stashes
	stashOut, _ := runGitCmd(projectDir, "stash", "list")
	res.HasStash = strings.TrimSpace(stashOut) != ""

	// Calculate unpushed commits
	unpushedMap := GetUnpushedCommitHashes(projectDir)
	res.UnpushedCount = len(unpushedMap)
	if res.Ahead == 0 && res.UnpushedCount > 0 {
		res.Ahead = res.UnpushedCount
	}

	return res, nil
}

// GetUnpushedCommitHashes returns a set of commit hashes on the current HEAD that are not present in remote.
func GetUnpushedCommitHashes(projectDir string) map[string]bool {
	unpushed := make(map[string]bool)
	resolvedDir, err := cleanDir(projectDir)
	if err != nil {
		return unpushed
	}

	// 1. Try @{u}..HEAD (if upstream is configured)
	out, err := runGitCmd(resolvedDir, "rev-list", "@{u}..HEAD")
	if err == nil {
		for _, h := range strings.Split(strings.TrimSpace(out), "\n") {
			h = strings.TrimSpace(h)
			if h != "" {
				unpushed[h] = true
			}
		}
		return unpushed
	}

	// 2. If no upstream set, check if remote(s) exist
	remotesOut, _ := runGitCmd(resolvedDir, "remote")
	if strings.TrimSpace(remotesOut) != "" {
		// Remotes exist, check commits not in any remote
		out, err = runGitCmd(resolvedDir, "rev-list", "HEAD", "--not", "--remotes")
		if err == nil {
			for _, h := range strings.Split(strings.TrimSpace(out), "\n") {
				h = strings.TrimSpace(h)
				if h != "" {
					unpushed[h] = true
				}
			}
			return unpushed
		}
	} else {
		// No remote configured at all: all commits on HEAD are local
		out, err = runGitCmd(resolvedDir, "rev-list", "HEAD")
		if err == nil {
			for _, h := range strings.Split(strings.TrimSpace(out), "\n") {
				h = strings.TrimSpace(h)
				if h != "" {
					unpushed[h] = true
				}
			}
		}
	}

	return unpushed
}

// InitRepo initializes a new Git repository and generates a .gitignore if missing.
func InitRepo(projectDir string) error {
	resolvedDir, err := cleanDir(projectDir)
	if err != nil {
		return err
	}
	_, err = runGitCmd(resolvedDir, "init")
	if err != nil {
		return err
	}
	gitignorePath := filepath.Join(resolvedDir, ".gitignore")
	if _, err := os.Stat(gitignorePath); os.IsNotExist(err) {
		defaultIgnore := `# Dependencies & Build
node_modules/
__pycache__/
*.py[cod]
.venv/
venv/
env/
dist/
build/
.gradle/
*.class
*.apk
*.aab

# OS & Editor
.DS_Store
Thumbs.db
.idea/
.vscode/
*.swp
*.tmp
*.log
`
		_ = os.WriteFile(gitignorePath, []byte(defaultIgnore), 0644)
	}
	return nil
}

// GetConfig reads git username, email, pull.rebase, and remote origin URL.
func GetConfig(projectDir string) (*models.GitConfig, error) {
	userName, _ := runGitCmd(projectDir, "config", "user.name")
	userEmail, _ := runGitCmd(projectDir, "config", "user.email")
	pullRebase, _ := runGitCmd(projectDir, "config", "pull.rebase")
	remoteUrl, _ := runGitCmd(projectDir, "remote", "get-url", "origin")

	return &models.GitConfig{
		UserName:   strings.TrimSpace(userName),
		UserEmail:  strings.TrimSpace(userEmail),
		PullRebase: strings.TrimSpace(pullRebase),
		RemoteURL:  strings.TrimSpace(remoteUrl),
	}, nil
}

// SetConfig sets git user.name, user.email, pull.rebase, and remote origin URL.
func SetConfig(projectDir string, req models.GitSetConfigReq) error {
	scopeArgs := []string{"config"}
	if req.IsGlobal {
		scopeArgs = append(scopeArgs, "--global")
	}

	if req.UserName != "" {
		args := append(scopeArgs, "user.name", req.UserName)
		if _, err := runGitCmd(projectDir, args...); err != nil {
			return err
		}
	}
	if req.UserEmail != "" {
		args := append(scopeArgs, "user.email", req.UserEmail)
		if _, err := runGitCmd(projectDir, args...); err != nil {
			return err
		}
	}
	if req.PullRebase != "" {
		args := append(scopeArgs, "pull.rebase", req.PullRebase)
		if _, err := runGitCmd(projectDir, args...); err != nil {
			return err
		}
	}
	if req.RemoteURL != "" {
		if _, err := runGitCmd(projectDir, "remote", "set-url", "origin", req.RemoteURL); err != nil {
			_, _ = runGitCmd(projectDir, "remote", "add", "origin", req.RemoteURL)
		}
	}
	return nil
}

// GetBranches returns all local and remote branches.
func GetBranches(projectDir string) ([]models.GitBranchInfo, error) {
	out, err := runGitCmd(projectDir, "branch", "-a", "--no-color")
	if err != nil {
		return []models.GitBranchInfo{}, nil
	}

	branches := []models.GitBranchInfo{}
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.Contains(line, "->") {
			continue
		}

		isCurrent := strings.HasPrefix(line, "*")
		name := strings.TrimPrefix(line, "*")
		name = strings.TrimSpace(name)

		isRemote := strings.HasPrefix(name, "remotes/")
		if isRemote {
			name = strings.TrimPrefix(name, "remotes/")
		}

		branches = append(branches, models.GitBranchInfo{
			Name:      name,
			IsCurrent: isCurrent,
			IsRemote:  isRemote,
		})
	}

	return branches, nil
}

// CheckoutBranch switches to an existing branch or creates a new one.
func CheckoutBranch(projectDir, branchName string, create bool) error {
	branchName = strings.TrimSpace(branchName)
	if branchName == "" {
		return fmt.Errorf("branch name cannot be empty")
	}
	if create {
		_, err := runGitCmd(projectDir, "checkout", "-b", branchName)
		return err
	}
	_, err := runGitCmd(projectDir, "checkout", branchName)
	return err
}

func cleanRelPath(projectDir, p string) string {
	resolvedDir, err := cleanDir(projectDir)
	if err != nil {
		resolvedDir = projectDir
	}
	p = unquotePath(p)
	if filepath.IsAbs(p) {
		if rel, err := filepath.Rel(resolvedDir, p); err == nil && !strings.HasPrefix(rel, "..") {
			return filepath.ToSlash(rel)
		}
	}
	return filepath.ToSlash(p)
}

// Stage adds paths to the git index.
func Stage(projectDir string, paths []string) error {
	if len(paths) == 0 {
		_, err := runGitCmd(projectDir, "add", "-A")
		return err
	}
	var cleanPaths []string
	for _, p := range paths {
		if strings.TrimSpace(p) != "" {
			cleanPaths = append(cleanPaths, cleanRelPath(projectDir, p))
		}
	}
	if len(cleanPaths) == 0 {
		_, err := runGitCmd(projectDir, "add", "-A")
		return err
	}
	args := append([]string{"add", "--"}, cleanPaths...)
	_, err := runGitCmd(projectDir, args...)
	return err
}

// Unstage resets paths from the git index.
func Unstage(projectDir string, paths []string) error {
	if len(paths) == 0 {
		// 1. Try modern git restore --staged .
		_, err := runGitCmd(projectDir, "restore", "--staged", ".")
		if err == nil {
			return nil
		}
		// 2. Fallback to git reset
		_, err = runGitCmd(projectDir, "reset")
		if err == nil {
			return nil
		}
		// 3. Fallback for initial commit / unborn branch (no HEAD ref yet)
		_, err = runGitCmd(projectDir, "rm", "--cached", "-r", ".")
		return err
	}

	for _, p := range paths {
		rel := cleanRelPath(projectDir, p)
		if rel == "" {
			continue
		}
		_, err := runGitCmd(projectDir, "restore", "--staged", "--", rel)
		if err != nil {
			_, err = runGitCmd(projectDir, "reset", "HEAD", "--", rel)
			if err != nil {
				_, _ = runGitCmd(projectDir, "rm", "--cached", "-r", "--", rel)
			}
		}
	}
	return nil
}

// Discard reverts modified files or cleans untracked files.
func Discard(projectDir string, paths []string) error {
	if len(paths) == 0 {
		_, _ = runGitCmd(projectDir, "restore", ".")
		_, _ = runGitCmd(projectDir, "checkout", "--", ".")
		_, _ = runGitCmd(projectDir, "clean", "-fd")
		return nil
	}

	for _, p := range paths {
		rel := cleanRelPath(projectDir, p)
		if rel == "" {
			continue
		}
		_, err := runGitCmd(projectDir, "restore", "--", rel)
		if err != nil {
			_, _ = runGitCmd(projectDir, "checkout", "--", rel)
		}
		_, _ = runGitCmd(projectDir, "clean", "-fd", "--", rel)
	}
	return nil
}

// Commit creates a new commit with the given message.
func Commit(projectDir, message string) error {
	message = strings.TrimSpace(message)
	if message == "" {
		return fmt.Errorf("commit message cannot be empty")
	}
	_, err := runGitCmd(projectDir, "commit", "-m", message)
	return err
}

// PushWithOutput pushes commits to remote and returns combined output.
func PushWithOutput(projectDir string) (string, error) {
	out, err := runGitCmd(projectDir, "push")
	if err != nil {
		errStr := strings.ToLower(err.Error())
		if strings.Contains(errStr, "--set-upstream") || strings.Contains(errStr, "no upstream branch") {
			branchOut, _ := runGitCmd(projectDir, "rev-parse", "--abbrev-ref", "HEAD")
			branch := strings.TrimSpace(branchOut)
			if branch != "" && branch != "HEAD" {
				return runGitCmd(projectDir, "push", "--set-upstream", "origin", branch)
			}
		}
	}
	return out, err
}

// PullWithOutput pulls commits from remote and returns combined output.
func PullWithOutput(projectDir string) (string, error) {
	return runGitCmd(projectDir, "pull")
}

// Stash saves local modifications.
func Stash(projectDir, message string) error {
	if strings.TrimSpace(message) != "" {
		_, err := runGitCmd(projectDir, "stash", "push", "-m", message)
		return err
	}
	_, err := runGitCmd(projectDir, "stash", "push")
	return err
}

// StashPop applies and removes the most recent stash.
func StashPop(projectDir string) error {
	_, err := runGitCmd(projectDir, "stash", "pop")
	return err
}

// GetDiff retrieves the unified diff for a single file safely.
func GetDiff(projectDir, path string, staged bool) (*models.GitDiffResponse, error) {
	resolvedDir, err := cleanDir(projectDir)
	if err != nil {
		return &models.GitDiffResponse{
			Path:      path,
			Staged:    staged,
			Diff:      "",
			Additions: 0,
			Deletions: 0,
		}, err
	}

	relPath := cleanRelPath(resolvedDir, path)
	fullPath := filepath.Join(resolvedDir, relPath)

	var out string
	if staged {
		out, err = runGitDiffCmd(resolvedDir, "diff", "--cached", "--", relPath)
		// If HEAD is unborn / empty repository, diff against empty tree hash (4b825dc642cb6eb9a060e54bf8d69288fbee4904)
		if err != nil && strings.Contains(strings.ToLower(err.Error()), "unknown revision") {
			out, err = runGitDiffCmd(resolvedDir, "diff", "--cached", "4b825dc642cb6eb9a060e54bf8d69288fbee4904", "--", relPath)
		}
	} else {
		out, err = runGitDiffCmd(resolvedDir, "diff", "--", relPath)
		if out == "" {
			// For untracked or new files, diff against /dev/null
			if fi, statErr := os.Stat(fullPath); statErr == nil && !fi.IsDir() {
				out, _ = runGitDiffCmd(resolvedDir, "diff", "--no-index", "/dev/null", fullPath)
			}
		}
	}

	additions := 0
	deletions := 0
	lines := strings.Split(out, "\n")
	for _, l := range lines {
		if strings.HasPrefix(l, "+") && !strings.HasPrefix(l, "+++") {
			additions++
		} else if strings.HasPrefix(l, "-") && !strings.HasPrefix(l, "---") {
			deletions++
		}
	}

	return &models.GitDiffResponse{
		Path:      path,
		Staged:    staged,
		Diff:      out,
		Additions: additions,
		Deletions: deletions,
	}, nil
}

// GetLog returns recent commit history with pagination.
func GetLog(projectDir string, limit int, skip int) ([]models.GitCommitLog, error) {
	if limit <= 0 {
		limit = 20
	}
	skipArg := ""
	if skip > 0 {
		skipArg = fmt.Sprintf("--skip=%d", skip)
	}

	args := []string{"log", fmt.Sprintf("-n%d", limit)}
	if skipArg != "" {
		args = append(args, skipArg)
	}
	args = append(args, "--pretty=format:%H%x00%h%x00%an%x00%cr%x00%s")

	out, err := runGitCmd(projectDir, args...)
	if err != nil {
		// Empty repo with no commits yet returns empty list cleanly
		return []models.GitCommitLog{}, nil
	}

	unpushedMap := GetUnpushedCommitHashes(projectDir)
	logs := []models.GitCommitLog{}
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.Split(line, "\x00")
		if len(parts) >= 5 {
			hash := parts[0]
			logs = append(logs, models.GitCommitLog{
				Hash:       hash,
				ShortHash:  parts[1],
				Author:     parts[2],
				Date:       parts[3],
				Message:    parts[4],
				IsUnpushed: unpushedMap[hash],
			})
		}
	}

	return logs, nil
}

// GetCommitDetails returns detailed metadata and changed files for a commit.
func GetCommitDetails(projectDir, hash string) (*models.GitCommitDetails, error) {
	hash = strings.TrimSpace(hash)
	if hash == "" {
		return nil, fmt.Errorf("hash parameter required")
	}

	headerOut, err := runGitCmd(projectDir, "show", "-s", "--format=%H%x00%h%x00%an%x00%cr%x00%s%x00%b", hash)
	if err != nil {
		return nil, err
	}
	parts := strings.Split(headerOut, "\x00")

	hashVal := hash
	shortHash := hash
	if len(shortHash) > 7 {
		shortHash = shortHash[:7]
	}
	author := ""
	date := ""
	subject := ""
	body := ""

	if len(parts) > 0 && parts[0] != "" {
		hashVal = parts[0]
	}
	if len(parts) > 1 && parts[1] != "" {
		shortHash = parts[1]
	}
	if len(parts) > 2 {
		author = parts[2]
	}
	if len(parts) > 3 {
		date = parts[3]
	}
	if len(parts) > 4 {
		subject = parts[4]
	}
	if len(parts) > 5 {
		body = strings.TrimSpace(parts[5])
	}

	// Use --root to support root commits
	filesOut, _ := runGitCmd(projectDir, "diff-tree", "--no-commit-id", "--name-status", "--root", "-r", hash)
	changedFiles := []models.GitCommitFileChange{}
	for _, line := range strings.Split(filesOut, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 {
			changedFiles = append(changedFiles, models.GitCommitFileChange{
				Status: fields[0],
				Path:   unquotePath(fields[1]),
			})
		}
	}

	return &models.GitCommitDetails{
		Hash:         hashVal,
		ShortHash:    shortHash,
		Author:       author,
		Date:         date,
		Subject:      subject,
		Body:         body,
		ChangedFiles: changedFiles,
	}, nil
}

// GetCommitFileDiff returns unified diff of a specific file at a commit vs its parent.
func GetCommitFileDiff(projectDir, hash, filePath string) (string, error) {
	relPath := cleanRelPath(projectDir, filePath)
	return runGitDiffCmd(projectDir, "show", "--format=", "--patch", hash, "--", relPath)
}

// GetCommitFileContent returns the full content of a file at a specific commit.
func GetCommitFileContent(projectDir, hash, filePath string) (string, error) {
	relPath := cleanRelPath(projectDir, filePath)
	return runGitCmd(projectDir, "show", fmt.Sprintf("%s:%s", hash, relPath))
}
