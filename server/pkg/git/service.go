package git

import (
	"bytes"
	"fmt"
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
		return nil, err
	}

	res := &models.GitStatusResponse{
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
		// Strip quotes if git quoted special filenames
		filePath = strings.Trim(filePath, "\"")
		oldPath = strings.Trim(oldPath, "\"")

		if indexStatus == '?' && workTreeStatus == '?' {
			res.UntrackedFiles = append(res.UntrackedFiles, models.GitFileStatus{
				Path:   filePath,
				Status: "U",
				Staged: false,
			})
			continue
		}

		// Staged status
		if indexStatus != ' ' && indexStatus != '?' {
			res.StagedFiles = append(res.StagedFiles, models.GitFileStatus{
				Path:    filePath,
				OldPath: oldPath,
				Status:  string(indexStatus),
				Staged:  true,
			})
		}

		// Unstaged status
		if workTreeStatus != ' ' && workTreeStatus != '?' {
			res.UnstagedFiles = append(res.UnstagedFiles, models.GitFileStatus{
				Path:    filePath,
				OldPath: oldPath,
				Status:  string(workTreeStatus),
				Staged:  false,
			})
		}
	}

	// Check for stash
	stashOut, _ := runGitCmd(projectDir, "stash", "list")
	res.HasStash = strings.TrimSpace(stashOut) != ""

	return res, nil
}

// GetBranches returns all local and remote branches.
func GetBranches(projectDir string) ([]models.GitBranchInfo, error) {
	out, err := runGitCmd(projectDir, "branch", "-a", "--no-color")
	if err != nil {
		return nil, err
	}

	var branches []models.GitBranchInfo
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.Contains(trimmed, "->") {
			continue
		}

		isCurrent := strings.HasPrefix(line, "*")
		name := strings.TrimPrefix(trimmed, "* ")
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
func CheckoutBranch(projectDir, branch string, create bool) error {
	if create {
		_, err := runGitCmd(projectDir, "checkout", "-b", branch)
		return err
	}
	_, err := runGitCmd(projectDir, "checkout", branch)
	return err
}

// Stage adds files to staging area.
func Stage(projectDir string, paths []string) error {
	if len(paths) == 0 {
		_, err := runGitCmd(projectDir, "add", "-A")
		return err
	}
	args := append([]string{"add", "--"}, paths...)
	_, err := runGitCmd(projectDir, args...)
	return err
}

// Unstage resets files from index.
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
		// Try restore first (Git 2.23+), fallback to checkout
		_, err := runGitCmd(projectDir, "restore", "--", p)
		if err != nil {
			_, _ = runGitCmd(projectDir, "checkout", "--", p)
		}
		// In case it was untracked, clean it
		_, _ = runGitCmd(projectDir, "clean", "-fd", "--", p)
	}
	return nil
}

// Commit creates a new commit with the given message.
func Commit(projectDir, message string) error {
	_, err := runGitCmd(projectDir, "commit", "-m", message)
	return err
}

// Push pushes commits to remote.
func Push(projectDir string) error {
	_, err := runGitCmd(projectDir, "push")
	return err
}

// Pull pulls commits from remote.
func Pull(projectDir string) error {
	_, err := runGitCmd(projectDir, "pull")
	return err
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
			// Check if file is untracked
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

// GetLog returns recent commit history.
func GetLog(projectDir string, limit int) ([]models.GitCommitLog, error) {
	if limit <= 0 {
		limit = 20
	}
	out, err := runGitCmd(projectDir, "log", fmt.Sprintf("-n%d", limit), "--pretty=format:%H%x00%h%x00%an%x00%cr%x00%s")
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
