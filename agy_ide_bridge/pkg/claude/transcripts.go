package claude

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

var sessionFileRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.jsonl$`)

const (
	headReadBytes = 256 << 10
	tailReadBytes = 512 << 10
)

// FindTranscript returns the path of ~/.claude/projects/*/<id>.jsonl, or "".
func (m *Manager) FindTranscript(id string) string {
	if id == "" || strings.ContainsAny(id, "/\\") {
		return ""
	}
	matches, _ := filepath.Glob(filepath.Join(m.ProjectsDir(), "*", id+".jsonl"))
	if len(matches) == 0 {
		return ""
	}
	return matches[0]
}

// SessionSummary is one sidebar entry.
type SessionSummary struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	LastPrompt string `json:"last_prompt"`
	Cwd        string `json:"cwd"`
	CreatedAt  int64  `json:"created_at"`
	UpdatedAt  int64  `json:"updated_at"`
	Live       bool   `json:"live"`
	Busy       bool   `json:"busy"`
	SizeBytes  int64  `json:"size_bytes"`
}

type transcriptLine struct {
	Type        string          `json:"type"`
	Subtype     string          `json:"subtype"`
	UUID        string          `json:"uuid"`
	ParentUUID  *string         `json:"parentUuid"`
	LogicalUUID *string         `json:"logicalParentUuid"`
	IsSidechain bool            `json:"isSidechain"`
	IsMeta      bool            `json:"isMeta"`
	Cwd         string          `json:"cwd"`
	Timestamp   string          `json:"timestamp"`
	CustomTitle string          `json:"customTitle"`
	AITitle     string          `json:"aiTitle"`
	LastPrompt  string          `json:"lastPrompt"`
	Message     json.RawMessage `json:"message"`
}

// ListSessions scans all projects for session transcripts that contain at least one user prompt.
func (m *Manager) ListSessions() []SessionSummary {
	files, _ := filepath.Glob(filepath.Join(m.ProjectsDir(), "*", "*.jsonl"))
	live := m.Snapshot()
	out := make([]SessionSummary, 0, len(files))
	for _, f := range files {
		name := filepath.Base(f)
		if !sessionFileRe.MatchString(name) {
			continue
		}
		fi, err := os.Stat(f)
		if err != nil {
			continue
		}
		sum, ok := summarize(f, fi.Size())
		if !ok {
			continue
		}
		sum.ID = strings.TrimSuffix(name, ".jsonl")
		sum.UpdatedAt = fi.ModTime().UnixMilli()
		if sum.CreatedAt == 0 {
			sum.CreatedAt = sum.UpdatedAt
		}
		sum.SizeBytes = fi.Size()
		if st, ok := live[sum.ID]; ok {
			sum.Live = st.Live
			sum.Busy = st.Busy
		}
		out = append(out, sum)
	}
	// Sessions created in this bridge that have no transcript yet are not listed: the app shows them itself.
	sort.Slice(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out
}

func summarize(path string, size int64) (SessionSummary, bool) {
	var sum SessionSummary
	f, err := os.Open(path)
	if err != nil {
		return sum, false
	}
	defer f.Close()

	firstPrompt := ""
	scanHead := func(data []byte) {
		forEachLine(data, func(l transcriptLine) {
			if sum.Cwd == "" && l.Cwd != "" {
				sum.Cwd = l.Cwd
			}
			if sum.CreatedAt == 0 && l.Timestamp != "" {
				if t, err := time.Parse(time.RFC3339Nano, l.Timestamp); err == nil {
					sum.CreatedAt = t.UnixMilli()
				}
			}
			if firstPrompt == "" && l.Type == "user" && !l.IsMeta && !l.IsSidechain {
				firstPrompt = userText(l.Message)
			}
		})
	}
	scanTail := func(data []byte) {
		forEachLine(data, func(l transcriptLine) {
			if l.Type == "last-prompt" && l.LastPrompt != "" {
				sum.LastPrompt = l.LastPrompt
			}
		})
	}

	if size <= headReadBytes+tailReadBytes {
		data, _ := io.ReadAll(f)
		scanHead(data)
		sum.Title = latestTitle(data)
		scanTail(data)
	} else {
		head := make([]byte, headReadBytes)
		n, _ := io.ReadFull(f, head)
		scanHead(head[:n])
		tail := make([]byte, tailReadBytes)
		n2, _ := f.ReadAt(tail, size-tailReadBytes)
		tail = tail[:n2]
		if i := bytes.IndexByte(tail, '\n'); i >= 0 {
			tail = tail[i+1:]
		}
		sum.Title = latestTitle(tail)
		scanTail(tail)
	}
	if firstPrompt == "" && sum.LastPrompt == "" {
		return sum, false
	}
	if sum.Title == "" {
		sum.Title = firstPrompt
		if sum.Title == "" {
			sum.Title = sum.LastPrompt
		}
	}
	sum.Title = oneLine(sum.Title, 80)
	sum.LastPrompt = oneLine(sum.LastPrompt, 120)
	return sum, true
}

// latestTitle: the last custom-title wins; otherwise the last ai-title.
func latestTitle(data []byte) string {
	custom, ai := "", ""
	forEachLine(data, func(l transcriptLine) {
		if l.Type == "custom-title" && l.CustomTitle != "" {
			custom = l.CustomTitle
		}
		if l.Type == "ai-title" && l.AITitle != "" {
			ai = l.AITitle
		}
	})
	if custom != "" {
		return custom
	}
	return ai
}

func forEachLine(data []byte, fn func(transcriptLine)) {
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64<<10), 64<<20)
	for sc.Scan() {
		b := sc.Bytes()
		if len(b) == 0 || b[0] != '{' {
			continue
		}
		var l transcriptLine
		if json.Unmarshal(b, &l) == nil {
			fn(l)
		}
	}
}

// userText extracts plain prompt text from a user message (string content or text blocks).
func userText(raw json.RawMessage) string {
	var msg struct {
		Content json.RawMessage `json:"content"`
	}
	if json.Unmarshal(raw, &msg) != nil {
		return ""
	}
	var s string
	if json.Unmarshal(msg.Content, &s) == nil {
		return cleanPrompt(s)
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(msg.Content, &blocks) == nil {
		for _, b := range blocks {
			if b.Type == "text" {
				if t := cleanPrompt(b.Text); t != "" {
					return t
				}
			}
		}
	}
	return ""
}

var tagBlockRe = regexp.MustCompile(`(?s)<(ide_selection|ide_opened_file|system-reminder|local-command-[a-z]+|command-[a-z]+)>.*?</(ide_selection|ide_opened_file|system-reminder|local-command-[a-z]+|command-[a-z]+)>`)

func cleanPrompt(s string) string {
	if strings.HasPrefix(strings.TrimSpace(s), "<command-name>") {
		if i := strings.Index(s, "</command-name>"); i > 0 {
			return strings.TrimSpace(s[strings.Index(s, ">")+1 : i])
		}
	}
	return strings.TrimSpace(tagBlockRe.ReplaceAllString(s, ""))
}

func oneLine(s string, max int) string {
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > max {
		return string(r[:max]) + "…"
	}
	return s
}

func transcriptCwd(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	head := make([]byte, headReadBytes)
	n, _ := io.ReadFull(f, head)
	cwd := ""
	forEachLine(head[:n], func(l transcriptLine) {
		if cwd == "" && l.Cwd != "" {
			cwd = l.Cwd
		}
	})
	return cwd
}

// History returns the main-chain transcript entries (user / assistant / system) of a session, read up to
// maxBytes (0 = whole file). Entries are raw JSON objects in conversation order.
func (m *Manager) History(id string, maxBytes int64) ([]json.RawMessage, error) {
	path := m.FindTranscript(id)
	if path == "" {
		return []json.RawMessage{}, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if maxBytes > 0 && int64(len(data)) > maxBytes {
		data = data[:maxBytes]
	}

	type entry struct {
		raw        json.RawMessage
		parent     string
		toolResult bool
	}
	byUUID := map[string]entry{}
	var order []string
	leaf := ""
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 64<<10), 256<<20)
	for sc.Scan() {
		b := sc.Bytes()
		if len(b) == 0 || b[0] != '{' {
			continue
		}
		var l transcriptLine
		if json.Unmarshal(b, &l) != nil || l.UUID == "" || l.IsSidechain {
			continue
		}
		// every uuid line is part of the parent chain (attachments, progress, …); only messages are returned
		wanted := l.Type == "user" || l.Type == "assistant" || l.Type == "system"
		parent := ""
		if l.ParentUUID != nil {
			parent = *l.ParentUUID
		} else if l.LogicalUUID != nil {
			parent = *l.LogicalUUID
		}
		e := entry{parent: parent}
		if wanted {
			e.raw = append(json.RawMessage{}, b...)
			e.toolResult = l.Type == "user" && bytes.Contains(b, []byte(`"tool_result"`))
		}
		if _, dup := byUUID[l.UUID]; !dup {
			order = append(order, l.UUID)
		}
		byUUID[l.UUID] = e
		if l.Type == "user" || l.Type == "assistant" {
			leaf = l.UUID
		}
	}

	var chainIDs []string
	onChain := map[string]bool{}
	for cur := leaf; cur != "" && !onChain[cur]; {
		if _, ok := byUUID[cur]; !ok {
			break
		}
		onChain[cur] = true
		chainIDs = append(chainIDs, cur)
		cur = byUUID[cur].parent
	}
	for i, j := 0, len(chainIDs)-1; i < j; i, j = i+1, j-1 {
		chainIDs[i], chainIDs[j] = chainIDs[j], chainIDs[i]
	}

	// Parallel tool calls write their results on sibling branches; keep those results next to the chain.
	siblingResults := map[string][]string{}
	for _, id := range order {
		e := byUUID[id]
		if !onChain[id] && e.toolResult && onChain[e.parent] {
			siblingResults[e.parent] = append(siblingResults[e.parent], id)
		}
	}

	chain := []json.RawMessage{}
	for _, id := range chainIDs {
		if e := byUUID[id]; e.raw != nil {
			chain = append(chain, e.raw)
		}
		for _, sib := range siblingResults[id] {
			chain = append(chain, byUUID[sib].raw)
		}
	}
	return chain, nil
}

// DeleteSession removes a session's transcript and its side folder (subagents, tool results).
func (m *Manager) DeleteSession(id string) error {
	// a dying CLI flushes its transcript on exit: stop it completely before deleting
	m.mu.Lock()
	s := m.sessions[id]
	delete(m.sessions, id)
	m.mu.Unlock()
	if s != nil {
		s.KillAndWait(6 * time.Second)
	}
	path := m.FindTranscript(id)
	if path == "" {
		return nil
	}
	_ = os.RemoveAll(strings.TrimSuffix(path, ".jsonl"))
	return os.Remove(path)
}

// RenameSession appends a custom-title entry, the same way the CLI's /rename does.
func (m *Manager) RenameSession(id, title string) error {
	path := m.FindTranscript(id)
	if path == "" {
		return os.ErrNotExist
	}
	line, _ := json.Marshal(map[string]string{"type": "custom-title", "customTitle": title, "sessionId": id})
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.Write(append(line, '\n'))
	return err
}
