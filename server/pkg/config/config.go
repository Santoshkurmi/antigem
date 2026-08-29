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
		workspace = filepath.Join(home, "projects", "gemini")
	}

	appData := filepath.Join(home, ".gemini", "antigravity-cli")
	brain := filepath.Join(appData, "brain")
	tokenFile := filepath.Join(appData, "antigravity-oauth-token")
	projectsBase := filepath.Join(home, "projects")

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
