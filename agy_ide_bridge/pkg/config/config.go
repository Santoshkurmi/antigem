package config

import (
	"os"
	"path/filepath"
	"strings"
)

// Config holds global runtime configuration parameters.
type Config struct {
	Port                  string
	CloudCodePort         string
	CloudCodeUpstreamHost string
	HomeDir               string
	WorkspaceDir          string
	AppDataDir            string
	BrainDir              string
	TokenFile             string
	ProjectsBaseDir       string
}

// LoadConfig initializes configuration with sensible defaults and environment overrides.
func LoadConfig() *Config {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		if h := os.Getenv("HOME"); h != "" {
			home = h
		} else {
			home = "."
		}
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "1234"
	}

	cloudCodePort := os.Getenv("CLOUD_CODE_PORT")
	if cloudCodePort == "" {
		cloudCodePort = "1236"
	}

	cloudCodeHost := os.Getenv("CLOUD_CODE_UPSTREAM_HOST")
	if cloudCodeHost == "" {
		cloudCodeHost = "https://daily-cloudcode-pa.googleapis.com"
	}

	workspace := os.Getenv("WORKSPACE_DIR")
	if workspace == "" {
		candidates := []string{
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
		if fi2, err2 := os.Stat(workspace); err2 == nil && fi2.IsDir() {
			projectsBase = workspace
		}
	}

	return &Config{
		Port:                  port,
		CloudCodePort:         cloudCodePort,
		CloudCodeUpstreamHost: cloudCodeHost,
		HomeDir:               home,
		WorkspaceDir:          workspace,
		AppDataDir:            appData,
		BrainDir:              brain,
		TokenFile:             tokenFile,
		ProjectsBaseDir:       projectsBase,
	}
}

// ExpandHome expands tilde (~) in file paths to the user's home directory.
func ExpandHome(p string) string {
	p = strings.TrimSpace(p)
	if strings.HasPrefix(p, "~/") || p == "~" {
		home, err := os.UserHomeDir()
		if err != nil || home == "" {
			home = os.Getenv("HOME")
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
	var candidates []string
	if home != "" {
		candidates = append(candidates,
			filepath.Join(home, ".local", "bin", name),
			filepath.Join(home, ".gemini", "bin", name),
			filepath.Join(home, ".antigravity", "bin", name),
			filepath.Join(home, "usr", "bin", name),
			filepath.Join(home, "..", "usr", "bin", name),
		)
	}
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		candidates = append([]string{filepath.Join(prefix, "bin", name)}, candidates...)
	}
	candidates = append(candidates,
		"/usr/bin/"+name,
		"/usr/local/bin/"+name,
		"/system/bin/"+name,
		"/system/xbin/"+name,
		"/bin/"+name,
	)

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
