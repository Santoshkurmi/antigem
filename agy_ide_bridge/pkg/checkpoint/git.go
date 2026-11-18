package checkpoint

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/config"
)

var (
	gitBinOnce sync.Once
	gitBin     string
	baseEnvVal []string
)

// gitPath is the git binary (Termux's when it is there).
func gitPath() string {
	gitBinOnce.Do(func() {
		gitBin = "git"
		if p := config.SafeLookPath("git"); p != "" {
			gitBin = p
		} else if _, err := os.Stat("/data/data/com.termux/files/usr/bin/git"); err == nil {
			gitBin = "/data/data/com.termux/files/usr/bin/git"
		}
		baseEnvVal = buildBaseEnv()
	})
	return gitBin
}

// buildBaseEnv is the bridge's environment without any GIT_* variable (so nothing of the caller leaks into the
// checkpoint repository), with Termux's bin on PATH.
func buildBaseEnv() []string {
	var env []string
	path := ""
	for _, e := range os.Environ() {
		if strings.HasPrefix(e, "GIT_") {
			continue
		}
		if strings.HasPrefix(e, "PATH=") {
			path = strings.TrimPrefix(e, "PATH=")
			continue
		}
		env = append(env, e)
	}
	termuxBin := "/data/data/com.termux/files/usr/bin"
	if !strings.Contains(path, termuxBin) {
		if path == "" {
			path = termuxBin + ":/usr/local/bin:/usr/bin:/bin"
		} else {
			path = path + ":" + termuxBin
		}
	}
	env = append(env,
		"PATH="+path,
		"GIT_TERMINAL_PROMPT=0",
		"GIT_OPTIONAL_LOCKS=0",
		"LC_ALL=C",
	)
	return env
}

// shadowOpts are the -c options every command on a checkpoint repository gets: no line-ending or path quoting
// changes, never an automatic gc (the cleanup runs it), and the project's own file-mode / symlink / case settings.
func shadowOpts(p projectOpts) []string {
	return []string{
		"-c", "core.autocrlf=false",
		"-c", "core.safecrlf=false",
		"-c", "core.quotepath=false",
		"-c", "core.fsmonitor=false",
		"-c", "core.untrackedCache=false",
		"-c", "core.hooksPath=/dev/null",
		"-c", "gc.auto=0",
		"-c", "maintenance.auto=false",
		"-c", "core.fileMode=" + p.fileMode,
		"-c", "core.symlinks=" + p.symlinks,
		"-c", "core.ignoreCase=" + p.ignoreCase,
	}
}

type gitCall struct {
	dir     string
	gitDir  string // GIT_DIR ("" = git finds the repository itself)
	work    string // GIT_WORK_TREE
	index   string // GIT_INDEX_FILE
	stdin   []byte
	timeout time.Duration
	okCodes []int // exit codes that are not errors (besides 0)
}

// run runs git and returns its stdout. The error carries stderr.
func (c gitCall) run(args ...string) ([]byte, error) {
	bin := gitPath()
	timeout := c.timeout
	if timeout == 0 {
		timeout = 5 * time.Minute
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Dir = c.dir
	env := append([]string(nil), baseEnvVal...)
	if c.gitDir != "" {
		env = append(env, "GIT_DIR="+c.gitDir)
	}
	if c.work != "" {
		env = append(env, "GIT_WORK_TREE="+c.work)
	}
	if c.index != "" {
		env = append(env, "GIT_INDEX_FILE="+c.index)
	}
	cmd.Env = env
	if c.stdin != nil {
		cmd.Stdin = bytes.NewReader(c.stdin)
	}
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	err := cmd.Run()
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			for _, code := range c.okCodes {
				if ee.ExitCode() == code {
					return out.Bytes(), nil
				}
			}
		}
		if ctx.Err() == context.DeadlineExceeded {
			return out.Bytes(), fmt.Errorf("git %s: timed out after %s", firstArg(args), timeout)
		}
		msg := strings.TrimSpace(errb.String())
		if msg == "" {
			msg = err.Error()
		}
		if len(msg) > 600 {
			msg = msg[:600] + "…"
		}
		return out.Bytes(), fmt.Errorf("git %s: %s", firstArg(args), msg)
	}
	return out.Bytes(), nil
}

func firstArg(args []string) string {
	for i := 0; i < len(args); i++ {
		if args[i] == "-c" {
			i++
			continue
		}
		return args[i]
	}
	return ""
}

// splitZ splits NUL-separated git output.
func splitZ(b []byte) []string {
	if len(b) == 0 {
		return nil
	}
	parts := strings.Split(string(b), "\x00")
	out := parts[:0]
	for _, p := range parts {
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

// isGitWorkTree reports whether dir is inside a git working tree (the user's own repository).
func isGitWorkTree(dir string) bool {
	out, err := gitCall{dir: dir, timeout: 20 * time.Second}.run("-c", "safe.directory=*", "rev-parse", "--is-inside-work-tree")
	return err == nil && strings.TrimSpace(string(out)) == "true"
}

// projectOpts are the core settings of the user's repository the checkpoint repository copies.
type projectOpts struct {
	fileMode   string
	symlinks   string
	ignoreCase string
	at         time.Time
}

func readProjectOpts(dir string) projectOpts {
	o := projectOpts{fileMode: "true", symlinks: "true", ignoreCase: "false", at: time.Now()}
	out, err := gitCall{dir: dir, timeout: 20 * time.Second, okCodes: []int{1}}.run(
		"-c", "safe.directory=*", "config", "--get-regexp", `^core\.(filemode|symlinks|ignorecase)$`)
	if err != nil {
		return o
	}
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) != 2 {
			continue
		}
		v := strings.ToLower(f[1])
		if v != "true" && v != "false" {
			continue
		}
		switch strings.ToLower(f[0]) {
		case "core.filemode":
			o.fileMode = v
		case "core.symlinks":
			o.symlinks = v
		case "core.ignorecase":
			o.ignoreCase = v
		}
	}
	return o
}

// headOf is "branch@shortsha" of the repository at dir ("" when it has no commit yet or is unreadable).
func headOf(dir string) string {
	out, err := gitCall{dir: dir, timeout: 20 * time.Second}.run("-c", "safe.directory=*", "rev-parse", "--abbrev-ref", "HEAD", "HEAD")
	if err != nil {
		return ""
	}
	lines := strings.Fields(string(out))
	if len(lines) != 2 {
		return ""
	}
	sha := lines[1]
	if len(sha) > 10 {
		sha = sha[:10]
	}
	return lines[0] + "@" + sha
}

// ensureShadowRepo creates the checkpoint repository at gitDir when it is not there yet.
func ensureShadowRepo(gitDir string) error {
	if _, err := os.Stat(filepath.Join(gitDir, "HEAD")); err == nil {
		return nil
	}
	if err := os.MkdirAll(gitDir, 0o700); err != nil {
		return err
	}
	if _, err := (gitCall{dir: gitDir, timeout: time.Minute}).run("init", "--bare", "-q", gitDir); err != nil {
		return err
	}
	for _, kv := range [][2]string{
		{"core.bare", "false"},
		{"core.logAllRefUpdates", "false"},
		{"gc.auto", "0"},
		{"core.autocrlf", "false"},
	} {
		if _, err := (gitCall{dir: gitDir, gitDir: gitDir, timeout: time.Minute}).run("config", kv[0], kv[1]); err != nil {
			return err
		}
	}
	return nil
}
