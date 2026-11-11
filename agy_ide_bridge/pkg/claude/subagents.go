package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// Subagents (the Agent / Task tool) write their own transcript next to the chat that started them:
//
//	~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl
//	~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.meta.json
//
// They are listed as read-only chats with the id "agent-<agentId>", nested under their parent in the app's sidebar.
// A subagent cannot be resumed, so nothing but reading its history works on them.

const subagentPrefix = "agent-"

// subagentIDRe: a subagent chat id as listed ("agent-" + Claude's agent id).
var subagentIDRe = regexp.MustCompile(`^agent-[A-Za-z0-9_-]{1,64}$`)

// A subagent that has not written anything for this long is not running any more (a CLI that never marks it finished).
const subagentStaleAfter = 10 * time.Minute

type subagentMeta struct {
	AgentType     string `json:"agentType"`
	Description   string `json:"description"`
	ParentAgentID string `json:"parentAgentId"`
	SpawnDepth    int    `json:"spawnDepth"`
	Finished      *bool  `json:"finished"`
}

// IsSubagentID reports whether id names a subagent transcript (read-only).
func IsSubagentID(id string) bool {
	return strings.HasPrefix(id, subagentPrefix)
}

// findSubagentTranscript returns the path of a subagent's transcript, or "".
func (m *Manager) findSubagentTranscript(id string) string {
	if !subagentIDRe.MatchString(id) {
		return ""
	}
	matches, _ := filepath.Glob(filepath.Join(m.ProjectsDir(), "*", "*", "subagents", id+".jsonl"))
	if len(matches) == 0 {
		return ""
	}
	return matches[0]
}

// listSubagents returns the subagents of the chats in [parents] (session id → listed).
func (m *Manager) listSubagents(parents map[string]bool, live map[string]SessionState) []SessionSummary {
	files, _ := filepath.Glob(filepath.Join(m.ProjectsDir(), "*", "*", "subagents", subagentPrefix+"*.jsonl"))
	out := make([]SessionSummary, 0, len(files))
	for _, f := range files {
		// <project>/<sessionId>/subagents/agent-x.jsonl: the session folder names the chat that started it
		sessionID := filepath.Base(filepath.Dir(filepath.Dir(f)))
		if !parents[sessionID] {
			continue
		}
		fi, err := os.Stat(f)
		if err != nil || fi.Size() == 0 {
			continue
		}
		var meta subagentMeta
		if b, err := os.ReadFile(strings.TrimSuffix(f, ".jsonl") + ".meta.json"); err == nil {
			_ = json.Unmarshal(b, &meta)
		}
		sum, _ := summarizeFile(f, fi.Size(), true)
		sum.ID = strings.TrimSuffix(filepath.Base(f), ".jsonl")
		sum.Subagent = true
		sum.AgentType = meta.AgentType
		sum.ParentID = sessionID
		if meta.ParentAgentID != "" {
			sum.ParentID = subagentPrefix + meta.ParentAgentID
		}
		if d := oneLine(meta.Description, 80); d != "" {
			sum.Title = d
		}
		if sum.Title == "" {
			sum.Title = meta.AgentType
		}
		sum.UpdatedAt = fi.ModTime().UnixMilli()
		if sum.CreatedAt == 0 {
			sum.CreatedAt = sum.UpdatedAt
		}
		sum.SizeBytes = fi.Size()
		// running: not marked finished, its chat's CLI is still alive, and it wrote something lately
		if meta.Finished == nil || !*meta.Finished {
			if st, ok := live[sessionID]; ok && st.Live && time.Since(fi.ModTime()) < subagentStaleAfter {
				sum.Live = true
				sum.Busy = true
			}
		}
		out = append(out, sum)
	}
	return out
}
