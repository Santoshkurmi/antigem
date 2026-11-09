package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Archived chats. The CLI has no archive of its own (the VS Code extension keeps its list in VS Code's storage), so
// the bridge keeps one: ~/.claude/agy_archived.json, session id -> archive time (unix ms). Transcripts are untouched.

var archiveMu sync.Mutex

type archiveFile struct {
	Sessions map[string]int64 `json:"sessions"`
}

func (m *Manager) archivePath() string {
	return filepath.Join(m.HomeDir, ".claude", "agy_archived.json")
}

func (m *Manager) loadArchiveLocked() archiveFile {
	var f archiveFile
	if raw, err := os.ReadFile(m.archivePath()); err == nil {
		_ = json.Unmarshal(raw, &f)
	}
	if f.Sessions == nil {
		f.Sessions = map[string]int64{}
	}
	return f
}

// ArchivedSessions returns the archived session ids.
func (m *Manager) ArchivedSessions() map[string]int64 {
	archiveMu.Lock()
	defer archiveMu.Unlock()
	return m.loadArchiveLocked().Sessions
}

// SetSessionArchived archives or unarchives a chat.
func (m *Manager) SetSessionArchived(id string, archived bool) error {
	archiveMu.Lock()
	defer archiveMu.Unlock()
	f := m.loadArchiveLocked()
	_, was := f.Sessions[id]
	if was == archived {
		return nil
	}
	if archived {
		f.Sessions[id] = time.Now().UnixMilli()
	} else {
		delete(f.Sessions, id)
	}
	return writeJSONFile(m.archivePath(), f)
}
