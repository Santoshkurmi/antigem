package claude

import (
	"bufio"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Editing an earlier prompt rewinds the chat in place, like the CLI's own rewind: the next process starts with
// `--resume-session-at=<the entry before that prompt>` and the new prompt branches off there. Until that prompt
// is written the transcript still ends with the old branch, so the rewind point is kept on disk (it survives
// bridge restarts) and history is cut at it.

func (m *Manager) rewindsDir() string {
	return filepath.Join(m.HomeDir, ".antigem", "claude-rewinds")
}

// rewindPoint returns the pending rewind of a session ("" = before the first prompt).
func (m *Manager) rewindPoint(id string) (string, bool) {
	data, err := os.ReadFile(filepath.Join(m.rewindsDir(), id+".json"))
	if err != nil {
		return "", false
	}
	var r struct {
		At string `json:"at"`
	}
	if json.Unmarshal(data, &r) != nil {
		return "", false
	}
	return r.At, true
}

func (m *Manager) setRewind(id, at string) error {
	if err := os.MkdirAll(m.rewindsDir(), 0o700); err != nil {
		return err
	}
	data, _ := json.Marshal(map[string]string{"at": at})
	return os.WriteFile(filepath.Join(m.rewindsDir(), id+".json"), data, 0o600)
}

func (m *Manager) clearRewind(id string) {
	_ = os.Remove(filepath.Join(m.rewindsDir(), id+".json"))
}

// RewindSession stops the session's process and makes the chat end right before the prompt `before` (the
// entry that prompt was written after; none for the first prompt). The next prompt continues from there.
func (m *Manager) RewindSession(id, before string) error {
	if m.FindTranscript(id) == "" {
		return os.ErrNotExist
	}
	if s := m.Existing(id); s != nil {
		// a running process would keep writing the old branch (and flushes the transcript on exit)
		s.KillAndWait(6 * time.Second)
	}
	data, err := os.ReadFile(m.FindTranscript(id))
	if err != nil {
		return err
	}
	at, found := "", false
	forEachLine(data, func(l transcriptLine) {
		if found || l.UUID != before {
			return
		}
		found = true
		if l.ParentUUID != nil {
			at = *l.ParentUUID
		} else if l.LogicalUUID != nil {
			at = *l.LogicalUUID
		}
	})
	if !found {
		return errors.New("that message is not in the transcript")
	}
	return m.setRewind(id, at)
}

// ForkSession copies a session's transcript under newID (same project folder) so the copy is a chat of its own
// right away; resuming it gives Claude the same history.
func (m *Manager) ForkSession(id, newID, title string) error {
	src := m.FindTranscript(id)
	if src == "" {
		return os.ErrNotExist
	}
	if m.FindTranscript(newID) != "" {
		return errors.New("session already exists")
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	dst := filepath.Join(filepath.Dir(src), newID+".jsonl")
	tmp := dst + ".tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	w := bufio.NewWriterSize(out, 1<<20)
	sessionID, _ := json.Marshal(newID)
	br := bufio.NewReaderSize(in, 1<<20)
	for {
		line, rerr := br.ReadBytes('\n')
		trimmed := strings.TrimSpace(string(line))
		if strings.HasPrefix(trimmed, "{") {
			var obj map[string]json.RawMessage
			if json.Unmarshal([]byte(trimmed), &obj) == nil {
				if _, ok := obj["sessionId"]; ok {
					obj["sessionId"] = sessionID
				}
				if b, err := json.Marshal(obj); err == nil {
					_, _ = w.Write(append(b, '\n'))
				}
			}
		}
		if rerr != nil {
			break
		}
	}
	if title != "" {
		b, _ := json.Marshal(map[string]string{"type": "custom-title", "customTitle": title, "sessionId": newID})
		_, _ = w.Write(append(b, '\n'))
	}
	if err := w.Flush(); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		return err
	}
	// a rewind not taken yet belongs to the copy too
	if at, ok := m.rewindPoint(id); ok {
		_ = m.setRewind(newID, at)
	}
	return nil
}
