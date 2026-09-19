package models

// AiModel represents an AI model descriptor.
type AiModel struct {
	ID               string `json:"id"`
	Name             string `json:"name,omitempty"`
	DisplayName      string `json:"displayName"`
	Family           string `json:"family"`
	Provider         string `json:"provider,omitempty"`
	MaxTokens        int    `json:"maxTokens,omitempty"`
	Description      string `json:"description,omitempty"`
	SupportsThinking bool   `json:"supportsThinking"`
	Recommended      bool   `json:"recommended,omitempty"`
	IsDefault        bool   `json:"isDefault,omitempty"`
}

// BucketInfo represents a 5h or weekly window quota bucket.
type BucketInfo struct {
	Window            string  `json:"window"`
	DisplayName       string  `json:"displayName"`
	RemainingFraction float64 `json:"remainingFraction"`
	RemainingPct      string  `json:"remainingPct"`
	UsedPct           string  `json:"usedPct"`
	ResetTime         string  `json:"resetTime,omitempty"`
	Countdown         string  `json:"countdown"`
	Description       string  `json:"description,omitempty"`
}

// QuotaGroupItem matches server.js quota group.
type QuotaGroupItem struct {
	GroupID     string      `json:"groupId"`
	GroupName   string      `json:"groupName"`
	Description string      `json:"description,omitempty"`
	FiveHour    *BucketInfo `json:"fiveHour,omitempty"`
	Weekly      *BucketInfo `json:"weekly,omitempty"`
}

// QuotaSummaryResponse represents the API response for user quotas.
type QuotaSummaryResponse struct {
	Groups      []QuotaGroupItem `json:"groups"`
	LastUpdated string           `json:"lastUpdated"`
}

// ToolCall represents a tool execution invocation by the agent.
type ToolCall struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Command    string `json:"command"`
	Status     string `json:"status"` // RUNNING, SUCCESS, FAILED, TERMINATED
	Output     string `json:"output,omitempty"`
	ExitCode   *int   `json:"exitCode,omitempty"`
	DurationMs *int64 `json:"durationMs,omitempty"`
}

// TokenUsage holds turn token consumption telemetry.
type TokenUsage struct {
	InputTokens     int   `json:"inputTokens"`
	OutputTokens    int   `json:"outputTokens"`
	ThinkingTokens  int   `json:"thinkingTokens,omitempty"`
	CacheReadTokens int   `json:"cacheReadTokens,omitempty"`
	TotalTokens     int   `json:"totalTokens"`
	DurationMs      int64 `json:"durationMs,omitempty"`
}

// SystemMetadata contains XML system metadata stripped from prompts.
type SystemMetadata struct {
	AdditionalMetadata string `json:"additionalMetadata,omitempty"`
	UserSettingsChange string `json:"userSettingsChange,omitempty"`
}

// ChatMessage represents a single message turn.
type ChatMessage struct {
	Role            string          `json:"role"` // "user" or "agent"
	Content         string          `json:"content"`
	Thinking        string          `json:"thinking,omitempty"`
	ToolCalls       []ToolCall      `json:"tool_calls,omitempty"`
	StepIndex       *int            `json:"stepIndex,omitempty"`
	DurationSeconds *float64        `json:"durationSeconds,omitempty"`
	TokenUsage      *TokenUsage     `json:"tokenUsage,omitempty"`
	RawContent      string          `json:"rawContent,omitempty"`
	ContextSummary  string          `json:"contextSummary,omitempty"`
	SystemMetadata  *SystemMetadata `json:"systemMetadata,omitempty"`
	CreatedAt       string          `json:"created_at"`
}

// ConversationSummary represents a conversation list entry.
type ConversationSummary struct {
	ID           string `json:"id"`
	Title        string `json:"title"`
	CreatedAt    string `json:"created_at"`
	StepsCount   int    `json:"steps_count"`
	IsRunning    bool   `json:"isRunning"`
	LastActivity string `json:"lastActivity,omitempty"`
}

// ConversationListResponse represents a paged list of conversations.
type ConversationListResponse struct {
	Conversations []ConversationSummary `json:"conversations"`
	Total         int                   `json:"total"`
	Page          int                   `json:"page"`
	Limit         int                   `json:"limit"`
	HasMore       bool                  `json:"hasMore"`
}

// StreamSnapshot represents an in-flight stream snapshot for reconnecting clients.
type StreamSnapshot struct {
	Type           string     `json:"type"` // "stream_snapshot"
	ConversationID string     `json:"conversationId"`
	IsRunning      bool       `json:"isRunning"`
	Status         string     `json:"status"` // "IDLE", "THINKING", "EXECUTING_TOOL", "GENERATING_TEXT"
	Seq            int64      `json:"seq"`
	Prompt         string     `json:"prompt,omitempty"`
	Thought        string     `json:"thought"`
	Content        string     `json:"content"`
	ActiveTools    []ToolCall `json:"activeTools"`
}

// ActiveInstance represents an active agy worker session in RAM.
type ActiveInstance struct {
	ConversationID     string `json:"conversationId"`
	Model              string `json:"model"`
	WorkspaceDir       string `json:"workspaceDir"`
	PID                int    `json:"pid"`
	UptimeSeconds      int64  `json:"uptimeSeconds"`
	LastUsedAgoSeconds int64  `json:"lastUsedAgoSeconds"`
	IsReady            bool   `json:"isReady"`
	IsBusy             bool   `json:"isBusy"`
}

// ChatAttachment represents an attached file or image.
type ChatAttachment struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	SavedName string `json:"savedName"`
	Path      string `json:"path"`
	IsImage   bool   `json:"isImage"`
	Size      int    `json:"size"`
	URL       string `json:"url"`
}

// UploadRequest represents incoming base64 upload payload.
type UploadRequest struct {
	Filename    string `json:"filename"`
	Base64Data  string `json:"base64Data"`
	ProjectPath string `json:"projectPath,omitempty"`
}

// ProjectSummary represents a discovered workspace project.
type ProjectSummary struct {
	Name     string `json:"name"`
	Path     string `json:"path"`
	IsCustom bool   `json:"isCustom,omitempty"`
}

// FsItemNode represents an entry in the filesystem browser (file or directory).
type FsItemNode struct {
	Name    string `json:"name"`
	Path    string `json:"path"`
	IsDir   bool   `json:"isDir"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"` // unix millis
	Ext     string `json:"ext,omitempty"`
}

// FsBrowseResult represents directory navigation contents.
type FsBrowseResult struct {
	CurrentPath string           `json:"currentPath"`
	ParentPath  string           `json:"parentPath"`
	HomePath    string           `json:"homePath"`
	Directories []ProjectSummary `json:"directories"`
	Items       []FsItemNode     `json:"items"`
}

// MkdirReq represents a request to create a directory.
type MkdirReq struct {
	Path string `json:"path"`
}

// FileCopyReq represents a file or folder copy request.
type FileCopyReq struct {
	SourcePath string `json:"sourcePath"`
	TargetPath string `json:"targetPath"`
}

// ProjectOpReq represents add or remove saved project requests.
type ProjectOpReq struct {
	Path string `json:"path"`
	Name string `json:"name,omitempty"`
}

// SkillInfo holds parsed skill frontmatter.
type SkillInfo struct {
	Name        string `json:"name"`
	Path        string `json:"path"`
	Description string `json:"description"`
}

// FileNode represents a file or directory node in the IDE file tree.
type FileNode struct {
	Name     string     `json:"name"`
	Path     string     `json:"path"`
	IsDir    bool       `json:"isDir"`
	Size     int64      `json:"size"`
	Children []FileNode `json:"children,omitempty"`
}

// CreateProjectReq represents a new project creation request.
type CreateProjectReq struct {
	Name     string `json:"name"`
	Template string `json:"template"` // python, node, web, kotlin, cpp, blank
	Path     string `json:"path,omitempty"` // custom parent or target directory
}

// FileSaveReq represents an atomic file save request.
type FileSaveReq struct {
	Path         string `json:"path"`
	Content      string `json:"content"`
	ExpectedHash string `json:"expectedHash,omitempty"`
	Force        bool   `json:"force,omitempty"`
}

// FilePatchReq represents a line-range patch request.
type FilePatchReq struct {
	Path        string `json:"path"`
	StartLine   int    `json:"startLine"`
	EndLine     int    `json:"endLine"`
	Replacement string `json:"replacement"`
}

// FileOpReq represents file create/delete/rename operations.
type FileOpReq struct {
	Path    string `json:"path"`
	NewPath string `json:"newPath,omitempty"`
	IsDir   bool   `json:"isDir,omitempty"`
}

// SearchMatch represents a ripgrep code search match.
type SearchMatch struct {
	Path       string `json:"path"`
	LineNumber int    `json:"lineNumber"`
	LineText   string `json:"lineText"`
}

