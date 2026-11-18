package memory

import (
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
)

// BlockLang is the fence an agent writes a memory in: ```antigem-memory … ```.
const BlockLang = "antigem-memory"

// ---------------------------------------------------------------------------------------------- fences

type fence struct {
	info  string   // the text after the opening fence ("antigem-memory", "kotlin", …)
	body  []string // the lines inside
	start int      // line of the opening fence
	end   int      // line of the closing fence
}

func fenceOpen(t string) (ch byte, n int, info string) {
	if t == "" || (t[0] != '`' && t[0] != '~') {
		return 0, 0, ""
	}
	ch = t[0]
	for n < len(t) && t[n] == ch {
		n++
	}
	if n < 3 {
		return 0, 0, ""
	}
	info = strings.TrimSpace(t[n:])
	if ch == '`' && strings.Contains(info, "`") {
		return 0, 0, ""
	}
	return ch, n, info
}

func fenceClose(t string, ch byte, n int) bool {
	i := 0
	for i < len(t) && t[i] == ch {
		i++
	}
	return i >= n && strings.TrimSpace(t[i:]) == ""
}

// topFences finds the closed fenced code blocks that are not inside another one (a fence still open at the end, as
// in a reply being written, is left out).
func topFences(lines []string) []fence {
	var out []fence
	var cur *fence
	var ch byte
	var n int
	for i, ln := range lines {
		t := strings.TrimLeft(ln, " ")
		indent := len(ln) - len(t)
		if cur == nil {
			if indent <= 3 {
				if c, k, info := fenceOpen(t); k >= 3 {
					cur, ch, n = &fence{info: info, start: i}, c, k
				}
			}
			continue
		}
		if indent <= 3 && fenceClose(t, ch, n) {
			cur.end = i
			out = append(out, *cur)
			cur = nil
			continue
		}
		cur.body = append(cur.body, ln)
	}
	return out
}

func splitLines(text string) []string {
	return strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n")
}

func isMemoryFence(f fence) bool {
	return strings.EqualFold(strings.Fields(f.info + " x")[0], BlockLang)
}

// FindBlocks returns the bodies of the top-level ```antigem-memory blocks in a reply.
func FindBlocks(text string) []string {
	var out []string
	for _, f := range topFences(splitLines(text)) {
		if isMemoryFence(f) {
			out = append(out, strings.Join(f.body, "\n"))
		}
	}
	return out
}

// BlockKey identifies a block (the app computes the same for its chip): sha1 of the body without \r, trimmed.
func BlockKey(body string) string {
	h := sha1.Sum([]byte(strings.TrimSpace(strings.ReplaceAll(body, "\r", ""))))
	return hex.EncodeToString(h[:])
}

// ---------------------------------------------------------------------------------------------- one block

type parsedBlock struct {
	File        string
	Mode        string
	Scope       string
	Description string
	Text        string
	Delete      bool
}

// parseBlock reads "key: value" lines, then "---", then the memory's text. Without "---" the leading known
// key lines are the header and the rest is the text.
func parseBlock(body string) parsedBlock {
	var pb parsedBlock
	lines := splitLines(strings.TrimSpace(strings.ReplaceAll(body, "\r", "")))
	textFrom := len(lines)
	for i, l := range lines {
		t := strings.TrimSpace(l)
		if t == "---" {
			textFrom = i + 1
			break
		}
		if t == "" {
			continue
		}
		k, v, ok := strings.Cut(t, ":")
		key := strings.ToLower(strings.TrimSpace(k))
		if !ok || !knownKey(key) {
			textFrom = i
			break
		}
		v = strings.TrimSpace(v)
		switch key {
		case "file", "name":
			pb.File = v
		case "mode":
			pb.Mode = normMode(v, "")
		case "scope":
			pb.Scope = normScope(v)
		case "description":
			pb.Description = v
		case "delete":
			pb.Delete = strings.EqualFold(v, "true") || strings.EqualFold(v, "yes")
		}
	}
	if textFrom < len(lines) {
		pb.Text = strings.TrimSpace(strings.Join(lines[textFrom:], "\n"))
	}
	return pb
}

func knownKey(k string) bool {
	switch k {
	case "file", "name", "mode", "scope", "description", "delete":
		return true
	}
	return false
}

// applyBlockLocked saves, updates or deletes the memory a block describes and records it under its key.
// project/dir: the chat's project ("" when it has none), source: the chat's history file.
func (m *Manager) applyBlockLocked(key, body, project, dir, agent, chatID, source string) *appliedBlock {
	st := m.stateLocked()
	rec := &appliedBlock{At: time.Now().UnixMilli(), ChatID: chatID}
	st.Applied[key] = rec
	pb := parseBlock(body)
	file := cleanFile(pb.File)
	if file == "" && !pb.Delete {
		file = cleanFile(firstLine(pb.Description+"\n"+pb.Text, 60))
	}
	if file == "" {
		rec.Action, rec.Error = "error", "the block has no file name"
		return rec
	}
	// the same name in this project and in global/ (project first)
	var here []string
	if dir != "" {
		here = append(here, idFor(ScopeProject, dir, file))
	}
	here = append(here, idFor(ScopeGlobal, "", file))

	if pb.Delete {
		for _, id := range here {
			if old := m.readLocked(id); old != nil {
				rec.Changes = append(rec.Changes, fileChange{ID: id, Prev: m.rawLocked(id)})
				if err := m.restoreLocked(id, nil); err != nil {
					rec.Action, rec.Error = "error", err.Error()
					return rec
				}
				if rec.ID == "" {
					rec.ID, rec.Description = id, old.Description
				}
			}
		}
		if rec.ID == "" {
			rec.Action, rec.Error = "error", "no memory named "+file
			return rec
		}
		rec.Action = "deleted"
		m.writeIndexLocked()
		return rec
	}

	text := redact(pb.Text)
	if text == "" {
		rec.Action, rec.Error = "error", "the memory has no text"
		return rec
	}
	// no scope given: where a memory of that name already is (project first), else this project when there is one
	scope := pb.Scope
	if scope == "" {
		for _, id := range here {
			if e := m.readLocked(id); e != nil {
				scope = e.Scope
				break
			}
		}
	}
	if scope == "" {
		scope = ScopeGlobal
		if dir != "" {
			scope = ScopeProject
		}
	}
	if scope == ScopeProject && dir == "" {
		scope = ScopeGlobal // a chat without a project folder: global/
	}
	id := idFor(scope, dir, file)
	existing := m.readLocked(id)
	moved := false
	if existing == nil {
		// the same name in the other place: the memory moves here
		for _, other := range here {
			if other != id {
				if existing = m.readLocked(other); existing != nil {
					moved = true
					break
				}
			}
		}
	}
	mem := &Memory{
		ID: id, File: file, Scope: scope, Mode: pb.Mode, Description: oneLine(redact(pb.Description)), Text: text,
		Updated: time.Now().UnixMilli(), Agent: agent, Source: source,
	}
	if scope == ScopeProject {
		mem.Project, mem.ProjectDir = project, dir
	}
	if mem.Mode == "" {
		mem.Mode = ModePassive
		if existing != nil {
			mem.Mode = existing.Mode
		}
	}
	if mem.Description == "" {
		if existing != nil && existing.Description != "" {
			mem.Description = existing.Description
		} else {
			mem.Description = firstLine(text, 120)
		}
	}
	rec.Changes = append(rec.Changes, fileChange{ID: id, Prev: m.rawLocked(id)})
	if err := m.writeLocked(mem); err != nil {
		rec.Action, rec.Error = "error", err.Error()
		return rec
	}
	// it moved scope: the old copy goes, undo brings it back
	if moved {
		rec.Changes = append(rec.Changes, fileChange{ID: existing.ID, Prev: m.rawLocked(existing.ID)})
		_ = m.restoreLocked(existing.ID, nil)
	}
	rec.ID, rec.Description = id, mem.Description
	rec.Action = "saved"
	if existing != nil {
		rec.Action = "updated"
	}
	m.writeIndexLocked()
	return rec
}

// ---------------------------------------------------------------------------------------------- status and undo

// AppliedInfo is what the app's chip shows for a block.
type AppliedInfo struct {
	Key         string `json:"key"`
	Found       bool   `json:"found"`
	Action      string `json:"action,omitempty"`
	ID          string `json:"id,omitempty"`
	Description string `json:"description,omitempty"`
	Error       string `json:"error,omitempty"`
	Undone      bool   `json:"undone,omitempty"`
	At          int64  `json:"at,omitempty"`
}

func info(key string, rec *appliedBlock) AppliedInfo {
	if rec == nil {
		return AppliedInfo{Key: key}
	}
	return AppliedInfo{Key: key, Found: true, Action: rec.Action, ID: rec.ID, Description: rec.Description, Error: rec.Error, Undone: rec.Undone, At: rec.At}
}

// Applied tells what happened to the block with this key.
func (m *Manager) Applied(key string) AppliedInfo {
	m.mu.Lock()
	defer m.mu.Unlock()
	return info(key, m.stateLocked().Applied[key])
}

// Undo puts the memory files a block changed back as they were.
func (m *Manager) Undo(key string) (AppliedInfo, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.stateLocked()
	rec := st.Applied[key]
	if rec == nil {
		return info(key, nil), errors.New("this memory block was not saved")
	}
	if rec.Undone {
		return info(key, rec), nil
	}
	if rec.Action == "error" {
		return info(key, rec), errors.New("nothing was saved from this block")
	}
	for i := len(rec.Changes) - 1; i >= 0; i-- {
		c := rec.Changes[i]
		if err := m.restoreLocked(c.ID, c.Prev); err != nil {
			return info(key, rec), fmt.Errorf("undo %s: %w", c.ID, err)
		}
	}
	rec.Undone = true
	m.writeIndexLocked()
	return info(key, rec), m.saveStateLocked()
}
