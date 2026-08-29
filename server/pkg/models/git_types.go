package models

// GitFileStatus represents a changed file in Git.
type GitFileStatus struct {
	Path     string `json:"path"`
	OldPath  string `json:"oldPath,omitempty"`
	Status   string `json:"status"` // "M", "A", "D", "U", "R"
	Staged   bool   `json:"staged"`
}

// GitStatusResponse represents git status output.
type GitStatusResponse struct {
	Branch         string          `json:"branch"`
	Tracking       string          `json:"tracking,omitempty"`
	Ahead          int             `json:"ahead"`
	Behind         int             `json:"behind"`
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
	Hash      string `json:"hash"`
	ShortHash string `json:"shortHash"`
	Author    string `json:"author"`
	Date      string `json:"date"`
	Message   string `json:"message"`
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
