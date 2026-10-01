package config

import (
	"os"
	"path/filepath"
	"strings"
)

// Config holds global runtime configuration parameters.
type Config struct {
	Port            string
	HomeDir         string
	WorkspaceDir    string
	AppDataDir      string
	BrainDir        string
	TokenFile       string
	ProjectsBaseDir string
}

// LoadConfig initializes configuration with sensible defaults and environment overrides.
func LoadConfig() *Config {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "/data/data/com.termux/files/home"
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "1234"
	}

	workspace := os.Getenv("WORKSPACE_DIR")
	if workspace == "" {
		candidates := []string{
			"/home/cat/extra",
			filepath.Join(home, "extra"),
			filepath.Join(home, "projects", "gemini"),
			filepath.Join(home, "projects"),
		}
		for _, c := range candidates {
			if fi, err := os.Stat(c); err == nil && fi.IsDir() {
				workspace = c
				break
			}
		}
		if workspace == "" {
			if wd, err := os.Getwd(); err == nil {
				workspace = wd
			}
		}
	}

	appData := filepath.Join(home, ".gemini", "antigravity-cli")
	brain := filepath.Join(appData, "brain")
	tokenFile := filepath.Join(appData, "antigravity-oauth-token")
	projectsBase := filepath.Join(home, "projects")
	if fi, err := os.Stat(projectsBase); err != nil || !fi.IsDir() {
		if fi2, err2 := os.Stat("/home/cat/extra"); err2 == nil && fi2.IsDir() {
			projectsBase = "/home/cat/extra"
		} else if fi3, err3 := os.Stat(workspace); err3 == nil && fi3.IsDir() {
			projectsBase = workspace
		}
	}

	return &Config{
		Port:            port,
		HomeDir:         home,
		WorkspaceDir:    workspace,
		AppDataDir:      appData,
		BrainDir:        brain,
		TokenFile:       tokenFile,
		ProjectsBaseDir: projectsBase,
	}
}

// ExpandHome expands tilde (~) in file paths to the user's home directory.
func ExpandHome(p string) string {
	p = strings.TrimSpace(p)
	if strings.HasPrefix(p, "~/") || p == "~" {
		home, err := os.UserHomeDir()
		if err != nil || home == "" {
			home = "/data/data/com.termux/files/home"
		}
		if p == "~" {
			return home
		}
		return filepath.Join(home, strings.TrimPrefix(p, "~/"))
	}
	return p
}

// SafeLookPath finds an executable in PATH or standard Android/Linux locations without triggering faccessat2 syscall (which causes SIGSYS on Android seccomp)
func SafeLookPath(name string) string {
	if filepath.IsAbs(name) {
		if fi, err := os.Stat(name); err == nil && !fi.IsDir() {
			return name
		}
		return ""
	}

	home, _ := os.UserHomeDir()
	candidates := []string{
		"/data/data/com.termux/files/usr/bin/" + name,
		"/usr/bin/" + name,
		"/usr/local/bin/" + name,
		"/system/bin/" + name,
		"/system/xbin/" + name,
		"/bin/" + name,
	}
	if home != "" {
		candidates = append(candidates, filepath.Join(home, ".gemini", "bin", name))
	}
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		candidates = append([]string{filepath.Join(prefix, "bin", name)}, candidates...)
	}

	for _, c := range candidates {
		if fi, err := os.Stat(c); err == nil && !fi.IsDir() {
			return c
		}
	}

	pathEnv := os.Getenv("PATH")
	for _, dir := range filepath.SplitList(pathEnv) {
		if dir == "" {
			continue
		}
		target := filepath.Join(dir, name)
		if fi, err := os.Stat(target); err == nil && !fi.IsDir() {
			return target
		}
	}

	return ""
}
