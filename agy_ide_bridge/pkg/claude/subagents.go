package claude

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Subagents (the Agent / Task tool) write their own transcript next to the chat that started them:
//
//	~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl
//	~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.meta.json
//
// They are listed as read-only chats with the id "agent-<agentId>", nested under their parent in the app's sidebar.
// A subagent cannot be resumed: it can be read, renamed (a title kept by the bridge, see subagentTitlesPath) and deleted.

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
	titles := m.subagentTitles()
	out := make([]SessionSummary, 0, len(files))
	// a session folder in two projects: each subagent once, from the file findSubagentTranscript opens (the first)
	listed := make(map[string]bool, len(files))
	for _, f := range files {
		// <project>/<sessionId>/subagents/agent-x.jsonl: the session folder names the chat that started it
		sessionID := filepath.Base(filepath.Dir(filepath.Dir(f)))
		if !parents[sessionID] {
			continue
		}
		id := strings.TrimSuffix(filepath.Base(f), ".jsonl")
		if listed[id] {
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
		sum.ID = id
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
		if t := titles[sum.ID]; t != "" {
			sum.Title = t
		}
		sum.UpdatedAt = fi.ModTime().UnixMilli()
		if sum.CreatedAt == 0 {
			sum.CreatedAt = sum.UpdatedAt
		}
		sum.SizeBytes = fi.Size()
		if subagentRunning(meta, fi, live[sessionID]) {
			sum.Live = true
			sum.Busy = true
		}
		listed[id] = true
		out = append(out, sum)
	}
	return out
}

// subagentRunning: not marked finished, its chat's CLI is still alive, and it wrote something lately.
func subagentRunning(meta subagentMeta, fi os.FileInfo, parent SessionState) bool {
	if meta.Finished != nil && *meta.Finished {
		return false
	}
	return parent.Live && time.Since(fi.ModTime()) < subagentStaleAfter
}

// deleteSubagent removes a subagent's transcript and meta file, with the subagents it started itself. The chat that
// ran it keeps working: its own transcript holds the subagent's result (resuming it needs no subagent file).
func (m *Manager) deleteSubagent(id string) error {
	path := m.findSubagentTranscript(id)
	if path == "" {
		return nil
	}
	dir := filepath.Dir(path)
	sessionID := filepath.Base(filepath.Dir(dir))
	readMeta := func(agentID string) subagentMeta {
		var meta subagentMeta
		if b, err := os.ReadFile(filepath.Join(dir, agentID+".meta.json")); err == nil {
			_ = json.Unmarshal(b, &meta)
		}
		return meta
	}
	if fi, err := os.Stat(path); err == nil && subagentRunning(readMeta(id), fi, m.Snapshot()[sessionID]) {
		return errors.New("the subagent is still running")
	}
	// the subagents it started: their meta names it as parent (same folder)
	children := map[string][]string{}
	files, _ := filepath.Glob(filepath.Join(dir, subagentPrefix+"*.jsonl"))
	for _, f := range files {
		other := strings.TrimSuffix(filepath.Base(f), ".jsonl")
		if p := readMeta(other).ParentAgentID; p != "" {
			children[subagentPrefix+p] = append(children[subagentPrefix+p], other)
		}
	}
	doomed := []string{id}
	for i := 0; i < len(doomed); i++ {
		doomed = append(doomed, children[doomed[i]]...)
	}
	var firstErr error
	for _, d := range doomed {
		_ = os.Remove(filepath.Join(dir, d+".meta.json"))
		if err := os.Remove(filepath.Join(dir, d+".jsonl")); err != nil && !os.IsNotExist(err) && firstErr == nil {
			firstErr = err
		}
	}
	_ = m.forgetSubagentTitles(doomed)
	return firstErr
}

// subagentIDsOf lists the subagents of a chat (its transcript's side folder).
func subagentIDsOf(transcript string) []string {
	files, _ := filepath.Glob(filepath.Join(strings.TrimSuffix(transcript, ".jsonl"), "subagents", subagentPrefix+"*.jsonl"))
	ids := make([]string, 0, len(files))
	for _, f := range files {
		ids = append(ids, strings.TrimSuffix(filepath.Base(f), ".jsonl"))
	}
	return ids
}

// Subagent titles set in the app. Claude's subagent files are left as they are (the CLI reads them to continue an
// agent), so the bridge keeps them: ~/.claude/agy_subagent_titles.json, subagent chat id -> title.

var subagentTitlesMu sync.Mutex

type subagentTitlesFile struct {
	Titles map[string]string `json:"titles"`
}

func (m *Manager) subagentTitlesPath() string {
	return filepath.Join(m.HomeDir, ".claude", "agy_subagent_titles.json")
}

func (m *Manager) loadSubagentTitlesLocked() subagentTitlesFile {
	var f subagentTitlesFile
	if raw, err := os.ReadFile(m.subagentTitlesPath()); err == nil {
		_ = json.Unmarshal(raw, &f)
	}
	if f.Titles == nil {
		f.Titles = map[string]string{}
	}
	return f
}

func (m *Manager) subagentTitles() map[string]string {
	subagentTitlesMu.Lock()
	defer subagentTitlesMu.Unlock()
	return m.loadSubagentTitlesLocked().Titles
}

// renameSubagent sets the title the app lists a subagent under.
func (m *Manager) renameSubagent(id, title string) error {
	if m.findSubagentTranscript(id) == "" {
		return os.ErrNotExist
	}
	subagentTitlesMu.Lock()
	defer subagentTitlesMu.Unlock()
	f := m.loadSubagentTitlesLocked()
	f.Titles[id] = title
	return writeJSONFile(m.subagentTitlesPath(), f)
}

// forgetSubagentTitles drops the titles of deleted subagents.
func (m *Manager) forgetSubagentTitles(ids []string) error {
	subagentTitlesMu.Lock()
	defer subagentTitlesMu.Unlock()
	f := m.loadSubagentTitlesLocked()
	n := len(f.Titles)
	for _, id := range ids {
		delete(f.Titles, id)
	}
	if len(f.Titles) == n {
		return nil
	}
	return writeJSONFile(m.subagentTitlesPath(), f)
}
