package config

import (
	"os"
	"path/filepath"
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
		port = "8080"
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
