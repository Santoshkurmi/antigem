package memory

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

const (
	maxCodeLines   = 15 // longer code blocks become "[kotlin code, 42 lines]" in history (the code itself is in git)
	maxSearchChats = 20
)

// Turn is one message of a chat as the app sends it: the user's text or the assistant's visible reply (no thinking,
// no tool calls).
type Turn struct {
	Role string `json:"role"` // user | assistant | summary (a compacted chat's summary)
	Text string `json:"text"`
	At   int64  `json:"at"` // unix ms, 0 when unknown
}

// SyncRequest is a chat after one of its turns finished (or when it was opened).
type SyncRequest struct {
	Project string `json:"project"`
	Agent   string `json:"agent"`
	ChatID  string `json:"chat_id"`
	Title   string `json:"title"`
	History bool   `json:"history"` // keep the chat's history file
	Turns   []Turn `json:"turns"`
}

type SyncResult struct {
	Applied     []AppliedInfo `json:"applied"`      // memory blocks applied now (not the ones applied before)
	HistoryFile string        `json:"history_file"` // under the memory folder, "" when history is off
}

// Sync writes the chat's history file (the whole chat again, so edits and rewinds show) and applies the memory
// blocks in its replies that were not applied before.
func (m *Manager) Sync(req SyncRequest) (SyncResult, error) {
	res := SyncResult{Applied: []AppliedInfo{}}
	chatID := strings.TrimSpace(req.ChatID)
	if chatID == "" {
		return res, errors.New("no chat id")
	}
	project := normPath(req.Project)
	dir := projectDir(project)
	agent := strings.ToLower(strings.TrimSpace(req.Agent))

	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.stateLocked()
	changed := false
	_, removed := st.Excluded[chatID] // the user removed its history: not saved again
	saveHistory := req.History && len(req.Turns) > 0 && !removed
	var entry *chatEntry
	if saveHistory {
		entry = m.historyEntryLocked(chatID, dir, req.Title, req.Turns)
		res.HistoryFile = entry.File
	} else if e := st.Chats[chatID]; e != nil {
		res.HistoryFile = e.File
	}
	// the blocks first: the history file then notes what became of each one
	for _, t := range req.Turns {
		if t.Role != "assistant" {
			continue
		}
		for _, body := range FindBlocks(t.Text) {
			key := BlockKey(body)
			if _, done := st.Applied[key]; done {
				continue
			}
			rec := m.applyBlockLocked(key, body, project, dir, agent, chatID, res.HistoryFile)
			res.Applied = append(res.Applied, info(key, rec))
			changed = true
		}
	}
	if saveHistory {
		if err := m.writeHistoryLocked(entry, chatID, project, agent, req.Title, req.Turns); err != nil {
			return res, err
		}
		changed = true
	}
	if changed {
		if err := m.saveStateLocked(); err != nil {
			return res, err
		}
	}
	return res, nil
}

// DeleteHistory removes a chat's history file (the user removed it, or the chat was deleted); the chat is not
// saved again.
func (m *Manager) DeleteHistory(chatID string) error {
	if chatID == "" {
		return errors.New("no chat id")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.stateLocked()
	st.Excluded[chatID] = time.Now().UnixMilli()
	if e := st.Chats[chatID]; e != nil {
		if err := os.Remove(m.abs(e.File)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		delete(st.Chats, chatID)
		m.writeHistoryIndexLocked(historyDirOf(e.File))
	}
	return m.saveStateLocked()
}

// ClearHistory removes every saved chat (those chats are not saved again; new ones are). Returns how many.
func (m *Manager) ClearHistory() (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.stateLocked()
	now := time.Now().UnixMilli()
	dirs := map[string]bool{}
	n := 0
	for id, e := range st.Chats {
		if err := os.Remove(m.abs(e.File)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return n, err
		}
		st.Excluded[id] = now
		delete(st.Chats, id)
		dirs[historyDirOf(e.File)] = true
		n++
	}
	for d := range dirs {
		m.writeHistoryIndexLocked(d)
	}
	return n, m.saveStateLocked()
}

// HistoryChat is one saved chat, for the app's history page.
type HistoryChat struct {
	ChatID  string `json:"chat_id"`
	Title   string `json:"title"`
	Agent   string `json:"agent"`
	Project string `json:"project"`
	File    string `json:"file"`
	Path    string `json:"path"`
	Started int64  `json:"started"`
	Updated int64  `json:"updated"`
	Edited  bool   `json:"edited"`
	Size    int64  `json:"size"`
}

func (m *Manager) historyChatLocked(id string, e *chatEntry) HistoryChat {
	h := HistoryChat{ChatID: id, Title: e.Title, Agent: e.Agent, Project: e.Project, File: e.File, Path: m.abs(e.File),
		Started: e.Started, Updated: e.Updated, Edited: e.Edited}
	if fi, err := os.Stat(h.Path); err == nil {
		h.Size = fi.Size()
	}
	return h
}

// HistoryList lists every saved chat, newest first.
func (m *Manager) HistoryList() []HistoryChat {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []HistoryChat{}
	for id, e := range m.stateLocked().Chats {
		out = append(out, m.historyChatLocked(id, e))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Updated > out[j].Updated })
	return out
}

// HistoryGet returns a saved chat and its file's text.
func (m *Manager) HistoryGet(chatID string) (HistoryChat, string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.stateLocked().Chats[chatID]
	if e == nil {
		return HistoryChat{}, "", errors.New("this chat has no saved history")
	}
	b, err := os.ReadFile(m.abs(e.File))
	if err != nil {
		return HistoryChat{}, "", err
	}
	return m.historyChatLocked(chatID, e), string(b), nil
}

// HistorySave writes the user's edit of a saved chat: kept from now on (new turns of the chat go at the end).
func (m *Manager) HistorySave(chatID, text string) (HistoryChat, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := m.stateLocked()
	e := st.Chats[chatID]
	if e == nil {
		return HistoryChat{}, errors.New("this chat has no saved history")
	}
	text = strings.ReplaceAll(text, "\r\n", "\n")
	if !strings.HasSuffix(text, "\n") {
		text += "\n"
	}
	if err := writeFileAtomic(m.abs(e.File), []byte(text)); err != nil {
		return HistoryChat{}, err
	}
	// a "# title" first line renames it in the list
	if first := strings.TrimSpace(strings.SplitN(text, "\n", 2)[0]); strings.HasPrefix(first, "# ") {
		if t := oneLine(strings.TrimPrefix(first, "# ")); t != "" {
			e.Title = t
		}
	}
	e.Edited, e.Updated = true, time.Now().UnixMilli()
	m.writeHistoryIndexLocked(historyDirOf(e.File))
	return m.historyChatLocked(chatID, e), m.saveStateLocked()
}

func historyDirOf(rel string) string {
	parts := strings.Split(rel, "/")
	if len(parts) >= 2 {
		return parts[1]
	}
	return noProjectDir
}

func historyTitle(title string, turns []Turn) string {
	if title = oneLine(title); title == "" && len(turns) > 0 {
		title = firstLine(turns[0].Text, 60)
	}
	return title
}

// historyEntryLocked returns the chat's history entry, made on its first save (moved when the chat's project changed).
func (m *Manager) historyEntryLocked(chatID, dir, title string, turns []Turn) *chatEntry {
	st := m.stateLocked()
	if dir == "" {
		dir = noProjectDir
	}
	e := st.Chats[chatID]
	if e != nil && historyDirOf(e.File) == dir {
		return e
	}
	if e != nil { // the chat's project changed: its old file goes
		_ = os.Remove(m.abs(e.File))
		delete(st.Chats, chatID)
		m.writeHistoryIndexLocked(historyDirOf(e.File))
	}
	started := time.Now().UnixMilli()
	for _, t := range turns {
		if t.At > 0 {
			started = t.At
			break
		}
	}
	name := time.UnixMilli(started).Format("2006-01-02") + "_" + shortID(chatID)
	if s := slug(historyTitle(title, turns), 40); s != "" {
		name += "_" + s
	}
	e = &chatEntry{File: "history/" + dir + "/" + name + ".md", Started: started}
	st.Chats[chatID] = e
	return e
}

// writeHistoryLocked writes the chat's history file: the whole chat again, or, when the user edited the file, only
// the turns that are new since (added at the end).
func (m *Manager) writeHistoryLocked(e *chatEntry, chatID, project, agent, title string, turns []Turn) error {
	dir := historyDirOf(e.File)
	title = historyTitle(title, turns)
	now := time.Now().UnixMilli()
	if e.Edited {
		if prev, err := os.ReadFile(m.abs(e.File)); err == nil {
			if len(turns) > e.Turns {
				var b strings.Builder
				b.Write(prev)
				m.renderTurnsLocked(&b, turns[e.Turns:])
				if err := writeFileAtomic(m.abs(e.File), []byte(b.String())); err != nil {
					return err
				}
				e.Turns, e.Agent, e.Updated = len(turns), agent, now
				m.writeHistoryIndexLocked(dir)
			}
			return nil
		}
		e.Edited = false // the file is gone: write it again from the chat
	}
	e.Title, e.Agent, e.Project, e.Updated, e.Turns = title, agent, project, now, len(turns)

	var b strings.Builder
	fmt.Fprintf(&b, "# %s\n\n", title)
	where := project
	if where == "" {
		where = "no project folder"
	}
	fmt.Fprintf(&b, "%s · chat %s · %s · started %s · last saved %s\n", agentName(agent), chatID, where, clock(e.Started), clock(now))
	m.renderTurnsLocked(&b, turns)
	if err := writeFileAtomic(m.abs(e.File), []byte(b.String())); err != nil {
		return err
	}
	m.writeHistoryIndexLocked(dir)
	return nil
}

func (m *Manager) renderTurnsLocked(b *strings.Builder, turns []Turn) {
	applied := m.stateLocked().Applied
	status := func(body string) *appliedBlock { return applied[BlockKey(body)] }
	for _, t := range turns {
		text := strings.TrimSpace(cleanText(t.Text, status))
		if text == "" {
			continue
		}
		switch t.Role {
		case "user":
			b.WriteString("\n## User")
		case "summary":
			b.WriteString("\n## Summary of the earlier conversation (written when the chat was compacted)")
		default:
			b.WriteString("\n## Assistant")
		}
		if t.At > 0 {
			b.WriteString(" · " + clock(t.At))
		}
		b.WriteString("\n\n" + text + "\n")
	}
}

// writeHistoryIndexLocked rewrites history/<dir>/INDEX.md: the project's chats, newest first.
func (m *Manager) writeHistoryIndexLocked(dir string) {
	st := m.stateLocked()
	prefix := "history/" + dir + "/"
	var list []*chatEntry
	for _, e := range st.Chats {
		if strings.HasPrefix(e.File, prefix) {
			list = append(list, e)
		}
	}
	path := filepath.Join(m.root, "history", dir, "INDEX.md")
	if len(list) == 0 {
		_ = os.Remove(path)
		return
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Updated > list[j].Updated })
	var b strings.Builder
	if dir == noProjectDir {
		b.WriteString("# Past chats without a project folder\n\n")
	} else {
		fmt.Fprintf(&b, "# Past chats of %s\n\n", projectName(dir, list[0].Project))
		if list[0].Project != "" {
			fmt.Fprintf(&b, "Project: %s\n", list[0].Project)
		}
	}
	b.WriteString("Newest first: last saved · agent · file · title. Each file has the user's messages and the assistants' replies (no tool output).\n\n")
	for _, e := range list {
		fmt.Fprintf(&b, "- %s · %s · %s · %s\n", day(e.Updated), agentName(e.Agent), filepath.Base(e.File), e.Title)
	}
	_ = writeFileAtomic(path, []byte(b.String()))
}

func shortID(id string) string {
	var b strings.Builder
	for _, r := range id {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		}
	}
	s := strings.ToLower(b.String())
	if len(s) > 8 {
		if strings.HasPrefix(strings.ToLower(id), "ses") || strings.Contains(id, "_") {
			return s[len(s)-8:] // "ses_…": the end differs between chats
		}
		return s[:8]
	}
	if s == "" {
		return "chat"
	}
	return s
}

func agentName(agent string) string {
	switch agent {
	case "claude":
		return "Claude Code"
	case "codex":
		return "Codex"
	case "opencode":
		return "OpenCode"
	case "agy":
		return "Antigravity"
	case "", "user":
		return "User"
	}
	return agent
}

func clock(ms int64) string { return time.UnixMilli(ms).Format("2006-01-02 15:04") }

// ---------------------------------------------------------------------------------------------- cleaning

// cleanText: memory blocks become a one-line note of what became of them ([status] looks a block up; nil: unknown),
// long code blocks a placeholder, secrets are hidden.
func cleanText(text string, status func(body string) *appliedBlock) string {
	lines := splitLines(text)
	fences := topFences(lines)
	if len(fences) > 0 {
		var out []string
		next := 0
		for _, f := range fences {
			out = append(out, lines[next:f.start]...)
			switch {
			case isMemoryFence(f):
				body := strings.Join(f.body, "\n")
				var rec *appliedBlock
				if status != nil {
					rec = status(body)
				}
				out = append(out, memoryNote(parseBlock(body), rec))
			case len(f.body) > maxCodeLines:
				lang := strings.Fields(f.info + " ")
				name := "code"
				if len(lang) > 0 {
					name = lang[0] + " code"
				}
				out = append(out, fmt.Sprintf("[%s, %d lines]", name, len(f.body)))
			default:
				out = append(out, lines[f.start:f.end+1]...)
			}
			next = f.end + 1
		}
		out = append(out, lines[next:]...)
		lines = out
	}
	return redact(strings.Join(lines, "\n"))
}

// memoryNote is what a history file shows for a memory block: what became of it, as of this save.
func memoryNote(pb parsedBlock, rec *appliedBlock) string {
	name := cleanFile(pb.File)
	if name == "" {
		name = "a memory"
	}
	switch {
	case rec == nil:
		return "[Memory block for " + name + ", not applied]"
	case rec.Undone:
		return "[Memory change undone by the user: " + name + ". Do not save it again unless the user asks]"
	case rec.Action == "error":
		return "[Memory not saved (" + rec.Error + "): " + name + "]"
	case rec.Action == "deleted":
		return "[Deleted from memory: " + name + "]"
	case rec.Action == "updated":
		return "[Updated in memory: " + name + "]"
	}
	return "[Saved to memory: " + name + "]"
}

var secretPatterns = []*regexp.Regexp{
	regexp.MustCompile(`-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----`),
	regexp.MustCompile(`\bsk-[A-Za-z0-9_\-]{20,}`),
	regexp.MustCompile(`\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}`),
	regexp.MustCompile(`\bgithub_pat_[A-Za-z0-9_]{30,}`),
	regexp.MustCompile(`\bAKIA[0-9A-Z]{16}\b`),
	regexp.MustCompile(`\bAIza[0-9A-Za-z_\-]{35}\b`),
	regexp.MustCompile(`\bxox[abprs]-[A-Za-z0-9\-]{10,}`),
	regexp.MustCompile(`(?i)\bbearer\s+[A-Za-z0-9._\-]{20,}`),
}

// redact hides things that look like keys and tokens.
func redact(s string) string {
	for _, re := range secretPatterns {
		s = re.ReplaceAllString(s, "[secret hidden]")
	}
	return s
}

// ---------------------------------------------------------------------------------------------- search

// HistoryHit is a past chat matching a search.
type HistoryHit struct {
	ChatID  string `json:"chat_id"`
	Title   string `json:"title"`
	Agent   string `json:"agent"`
	Project string `json:"project"`
	File    string `json:"file"`
	Path    string `json:"path"`
	Updated int64  `json:"updated"`
	Snippet string `json:"snippet"`
	Score   int    `json:"score"`
}

type SearchResult struct {
	Memories []*Memory    `json:"memories"`
	History  []HistoryHit `json:"history"`
}

// Search finds memories and past chats with the query's words ([project] limits the chats to one project).
func (m *Manager) Search(query, project string) SearchResult {
	res := SearchResult{Memories: []*Memory{}, History: []HistoryHit{}}
	words := tokens(query)
	if len(words) == 0 {
		return res
	}
	dir := projectDir(normPath(project))

	m.mu.Lock()
	defer m.mu.Unlock()
	for _, mem := range rank(m.allLocked(), query) {
		head := tokens(strings.TrimSuffix(mem.File, ".md") + " " + mem.Description)
		body := tokens(mem.Text)
		hit := false
		for w := range words {
			if head[w] || body[w] {
				hit = true
				break
			}
		}
		if !hit {
			break // ranked: the rest match nothing
		}
		res.Memories = append(res.Memories, mem)
	}
	for id, e := range m.stateLocked().Chats {
		if dir != "" && historyDirOf(e.File) != dir {
			continue
		}
		b, err := os.ReadFile(m.abs(e.File))
		if err != nil {
			continue
		}
		text := string(b)
		lower := strings.ToLower(text)
		score, first := 0, -1
		for w := range words {
			if n := strings.Count(lower, w); n > 0 {
				score += n
				if i := strings.Index(lower, w); first < 0 || i < first {
					first = i
				}
			}
		}
		if score == 0 {
			continue
		}
		res.History = append(res.History, HistoryHit{
			ChatID: id, Title: e.Title, Agent: e.Agent, Project: e.Project, File: e.File, Path: m.abs(e.File),
			Updated: e.Updated, Snippet: snippet(text, first), Score: score,
		})
	}
	sort.Slice(res.History, func(i, j int) bool {
		if res.History[i].Score != res.History[j].Score {
			return res.History[i].Score > res.History[j].Score
		}
		return res.History[i].Updated > res.History[j].Updated
	})
	if len(res.History) > maxSearchChats {
		res.History = res.History[:maxSearchChats]
	}
	return res
}

func snippet(text string, at int) string {
	if at < 0 {
		at = 0
	}
	start, end := at-80, at+160
	if start < 0 {
		start = 0
	}
	if end > len(text) {
		end = len(text)
	}
	// keep whole UTF-8 characters
	for start > 0 && start < len(text) && text[start]&0xC0 == 0x80 {
		start--
	}
	for end < len(text) && text[end]&0xC0 == 0x80 {
		end++
	}
	s := oneLine(text[start:end])
	if start > 0 {
		s = "…" + s
	}
	if end < len(text) {
		s += "…"
	}
	return s
}
