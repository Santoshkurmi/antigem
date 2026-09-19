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

	"gemini-server/pkg/models"
)

var (
	aheadReg  = regexp.MustCompile(`ahead (\d+)`)
	behindReg = regexp.MustCompile(`behind (\d+)`)
)

func runGitCmd(dir string, args ...string) (string, error) {
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	var out, errOut bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errOut
	err := cmd.Run()
	if err != nil {
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

// GetStatus parses git status into structured Staged, Unstaged, and Untracked lists.
func GetStatus(projectDir string) (*models.GitStatusResponse, error) {
	out, err := runGitCmd(projectDir, "status", "--porcelain=v1", "-b", "-uall")
	if err != nil {
		errStr := err.Error()
		if strings.Contains(strings.ToLower(errStr), "not a git repository") {
			return &models.GitStatusResponse{
				IsGitRepo:      false,
				Branch:         "",
				StagedFiles:    []models.GitFileStatus{},
				UnstagedFiles:  []models.GitFileStatus{},
				UntrackedFiles: []models.GitFileStatus{},
			}, nil
		}
		return nil, err
	}

	res := &models.GitStatusResponse{
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
			} else {
				parts := strings.SplitN(header, "...", 2)
				res.Branch = parts[0]
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

		if len(line) < 4 {
			continue
		}

		indexStatus := line[0]
		workTreeStatus := line[1]
		filePath := strings.TrimSpace(line[3:])

		// Handle renamed files (e.g. "old -> new")
		oldPath := ""
		if strings.Contains(filePath, " -> ") {
			parts := strings.SplitN(filePath, " -> ", 2)
			oldPath = parts[0]
			filePath = parts[1]
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

	return res, nil
}

// InitRepo initializes a new Git repository and generates a .gitignore if missing.
func InitRepo(projectDir string) error {
	_, err := runGitCmd(projectDir, "init")
	if err != nil {
		return err
	}
	gitignorePath := filepath.Join(projectDir, ".gitignore")
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

	var branches []models.GitBranchInfo
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
	if create {
		_, err := runGitCmd(projectDir, "checkout", "-b", branchName)
		return err
	}
	_, err := runGitCmd(projectDir, "checkout", branchName)
	return err
}

// Stage adds paths to the git index.
func Stage(projectDir string, paths []string) error {
	if len(paths) == 0 {
		_, err := runGitCmd(projectDir, "add", "-A")
		return err
	}
	args := append([]string{"add", "--"}, paths...)
	_, err := runGitCmd(projectDir, args...)
	return err
}

// Unstage resets paths from the git index.
func Unstage(projectDir string, paths []string) error {
	if len(paths) == 0 {
		_, err := runGitCmd(projectDir, "reset", "HEAD")
		return err
	}
	args := append([]string{"reset", "HEAD", "--"}, paths...)
	_, err := runGitCmd(projectDir, args...)
	return err
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
		_, err := runGitCmd(projectDir, "restore", "--", p)
		if err != nil {
			_, _ = runGitCmd(projectDir, "checkout", "--", p)
		}
		_, _ = runGitCmd(projectDir, "clean", "-fd", "--", p)
	}
	return nil
}

// Commit creates a new commit with the given message.
func Commit(projectDir, message string) error {
	_, err := runGitCmd(projectDir, "commit", "-m", message)
	return err
}

// PushWithOutput pushes commits to remote and returns combined output.
func PushWithOutput(projectDir string) (string, error) {
	return runGitCmd(projectDir, "push")
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

// GetDiff retrieves the unified diff for a single file.
func GetDiff(projectDir, path string, staged bool) (*models.GitDiffResponse, error) {
	var out string
	var err error

	if staged {
		out, err = runGitCmd(projectDir, "diff", "--cached", "--", path)
	} else {
		out, err = runGitCmd(projectDir, "diff", "--", path)
		if out == "" {
			fullPath := filepath.Join(projectDir, path)
			out, _ = runGitCmd(projectDir, "diff", "--no-index", "/dev/null", fullPath)
		}
	}

	if err != nil && out == "" {
		return nil, err
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
		return []models.GitCommitLog{}, nil
	}

	var logs []models.GitCommitLog
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		if line == "" {
			continue
		}
		parts := strings.Split(line, "\x00")
		if len(parts) >= 5 {
			logs = append(logs, models.GitCommitLog{
				Hash:      parts[0],
				ShortHash: parts[1],
				Author:    parts[2],
				Date:      parts[3],
				Message:   parts[4],
			})
		}
	}

	if logs == nil {
		logs = []models.GitCommitLog{}
	}
	return logs, nil
}

// GetCommitDetails returns detailed metadata and changed files for a commit.
func GetCommitDetails(projectDir, hash string) (*models.GitCommitDetails, error) {
	headerOut, err := runGitCmd(projectDir, "show", "-s", "--format=%H%x00%h%x00%an%x00%cr%x00%s%x00%b", hash)
	if err != nil {
		return nil, err
	}
	parts := strings.Split(headerOut, "\x00")
	if len(parts) < 6 {
		return nil, fmt.Errorf("invalid commit header format")
	}

	filesOut, _ := runGitCmd(projectDir, "diff-tree", "--no-commit-id", "--name-status", "-r", hash)
	var changedFiles []models.GitCommitFileChange
	for _, line := range strings.Split(filesOut, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 {
			changedFiles = append(changedFiles, models.GitCommitFileChange{
				Status: fields[0],
				Path:   fields[1],
			})
		}
	}

	return &models.GitCommitDetails{
		Hash:         parts[0],
		ShortHash:    parts[1],
		Author:       parts[2],
		Date:         parts[3],
		Subject:      parts[4],
		Body:         strings.TrimSpace(parts[5]),
		ChangedFiles: changedFiles,
	}, nil
}

// GetCommitFileDiff returns unified diff of a specific file at a commit vs its parent.
func GetCommitFileDiff(projectDir, hash, filePath string) (string, error) {
	return runGitCmd(projectDir, "show", "--format=", "--patch", hash, "--", filePath)
}

// GetCommitFileContent returns the full content of a file at a specific commit.
func GetCommitFileContent(projectDir, hash, filePath string) (string, error) {
	return runGitCmd(projectDir, "show", fmt.Sprintf("%s:%s", hash, filePath))
}

