package models

// GitFileStatus represents a changed file in Git.
type GitFileStatus struct {
	Path    string `json:"path"`
	OldPath string `json:"oldPath,omitempty"`
	Status  string `json:"status"` // "M", "A", "D", "U", "R"
	Staged  bool   `json:"staged"`
}

// GitStatusResponse represents git status output.
type GitStatusResponse struct {
	IsGitRepo      bool            `json:"isGitRepo"`
	Branch         string          `json:"branch"`
	Tracking       string          `json:"tracking,omitempty"`
	Ahead          int             `json:"ahead"`
	Behind         int             `json:"behind"`
	UnpushedCount  int             `json:"unpushedCount"`
	StagedFiles    []GitFileStatus `json:"stagedFiles"`
	UnstagedFiles  []GitFileStatus `json:"unstagedFiles"`
	UntrackedFiles []GitFileStatus `json:"untrackedFiles"`
	HasStash       bool            `json:"hasStash"`
}

// GitBranchInfo represents a local or remote branch.
type GitBranchInfo struct {
	Name      string `json:"name"`
	IsCurrent bool   `json:"isCurrent"`
	IsRemote  bool   `json:"isRemote"`
}

// GitCommitLog represents a git commit history entry.
type GitCommitLog struct {
	Hash       string `json:"hash"`
	ShortHash  string `json:"shortHash"`
	Author     string `json:"author"`
	Date       string `json:"date"`
	Message    string `json:"message"`
	IsUnpushed bool   `json:"isUnpushed"`
}

// GitCommitFileChange represents a file modified in a specific commit.
type GitCommitFileChange struct {
	Path   string `json:"path"`
	Status string `json:"status"` // "M", "A", "D", "R"
}

// GitCommitDetails represents full metadata and file changes for a commit.
type GitCommitDetails struct {
	Hash         string                `json:"hash"`
	ShortHash    string                `json:"shortHash"`
	Author       string                `json:"author"`
	Date         string                `json:"date"`
	Subject      string                `json:"subject"`
	Body         string                `json:"body"`
	ChangedFiles []GitCommitFileChange `json:"changedFiles"`
}

// GitConfig represents git user settings and remote origin.
type GitConfig struct {
	UserName   string `json:"userName"`
	UserEmail  string `json:"userEmail"`
	PullRebase string `json:"pullRebase"` // "false", "true", "merges"
	RemoteURL  string `json:"remoteUrl"`
}

// GitSetConfigReq represents updating git config settings.
type GitSetConfigReq struct {
	Project    string `json:"project"`
	UserName   string `json:"userName,omitempty"`
	UserEmail  string `json:"userEmail,omitempty"`
	PullRebase string `json:"pullRebase,omitempty"`
	RemoteURL  string `json:"remoteUrl,omitempty"`
	IsGlobal   bool   `json:"isGlobal"`
}

// GitActionResult represents the outcome and stderr/stdout of a git operation.
type GitActionResult struct {
	Success bool   `json:"success"`
	Output  string `json:"output,omitempty"`
	Error   string `json:"error,omitempty"`
}

// GitActionReq represents incoming git operation requests.
type GitActionReq struct {
	Project string   `json:"project"`
	Paths   []string `json:"paths,omitempty"`
	Branch  string   `json:"branch,omitempty"`
	Message string   `json:"message,omitempty"`
	Create  bool     `json:"create,omitempty"`
}

// GitDiffResponse represents unified diff for a file.
type GitDiffResponse struct {
	Path      string `json:"path"`
	Staged    bool   `json:"staged"`
	Diff      string `json:"diff"`
	Additions int    `json:"additions"`
	Deletions int    `json:"deletions"`
}
