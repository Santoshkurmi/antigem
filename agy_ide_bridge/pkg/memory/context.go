package memory

import (
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"unicode"
)

const (
	defaultLimit  = 10
	maxLimit      = 50
	maxAlwaysText = 6000 // characters of "always" memories put in full; the rest are listed as files
)

// ContextRequest asks for the memory text of a chat's first message.
type ContextRequest struct {
	Project string `json:"project"` // the chat's project path ("" when it has none)
	Agent   string `json:"agent"`
	Message string `json:"message"` // what the user typed: ranks the memories
	Limit   int    `json:"limit"`   // how many memories to list besides the "always" ones
	History bool   `json:"history"` // chat history is on: tell the agent where past chats are
}

// ContextResult is the text the app puts (hidden) in front of the message.
type ContextResult struct {
	Text   string `json:"text"`
	Always int    `json:"always"` // "always" memories included
	Listed int    `json:"listed"` // memories listed by name
	More   int    `json:"more"`   // memories of this chat (global + project) not listed
}

// Context builds the memory text for a chat's first message.
func (m *Manager) Context(req ContextRequest) ContextResult {
	project := normPath(req.Project)
	dir := projectDir(project)
	limit := req.Limit
	if limit <= 0 {
		limit = defaultLimit
	}
	if limit > maxLimit {
		limit = maxLimit
	}

	m.mu.Lock()
	all := m.allLocked()
	m.mu.Unlock()

	var always, passive []*Memory
	for _, mem := range all {
		if mem.Scope == ScopeProject && (dir == "" || mem.ProjectDir != dir) {
			continue // another project's
		}
		if mem.Mode == ModeAlways {
			always = append(always, mem)
		} else {
			passive = append(passive, mem)
		}
	}
	// "always": global ones first, then this project's, newest first in each
	sort.SliceStable(always, func(i, j int) bool {
		if always[i].Scope != always[j].Scope {
			return always[i].Scope == ScopeGlobal
		}
		return always[i].Updated > always[j].Updated
	})
	ranked := rank(passive, req.Message)
	listed := ranked
	if len(listed) > limit {
		listed = listed[:limit]
	}

	res := ContextResult{Listed: len(listed), More: len(ranked) - len(listed)}
	root := m.root
	var b strings.Builder
	b.WriteString("[AntiGem agent memory: from the app, not written by the user. Shared by every agent and chat on this machine.]\n\n")
	b.WriteString("## Agent memory\n")
	if dir != "" {
		fmt.Fprintf(&b, "Memories are Markdown files in %s/: global/ for every project, projects/%s/ for this one. ", root, dir)
	} else {
		fmt.Fprintf(&b, "Memories are Markdown files in %s/ (this chat has no project folder, so only global/ applies). ", root)
	}
	b.WriteString("Read them with your file tools when they fit the user's request. Never create or edit files there yourself: save with a memory block (below).\n")

	if len(always) > 0 {
		b.WriteString("\n### Always apply\n")
		used := 0
		var overflow []*Memory
		for _, mem := range always {
			if used+len(mem.Text) > maxAlwaysText && used > 0 {
				overflow = append(overflow, mem)
				continue
			}
			used += len(mem.Text)
			fmt.Fprintf(&b, "**%s**\n%s\n\n", mem.ID, mem.Text)
			res.Always++
		}
		if len(overflow) > 0 {
			b.WriteString("Also always apply (open these files):\n")
			for _, mem := range overflow {
				fmt.Fprintf(&b, "- %s: %s\n", mem.ID, oneLine(mem.Description))
			}
			res.Always += len(overflow)
		}
	}

	if len(listed) > 0 {
		b.WriteString("\n### Possibly relevant (open a file if it fits the user's request)\n")
		for _, mem := range listed {
			fmt.Fprintf(&b, "- %s: %s (%s)\n", mem.ID, oneLine(mem.Description), day(mem.Updated))
		}
	}
	switch {
	case res.More > 0:
		fmt.Fprintf(&b, "%d more memories are not listed: %s/INDEX.md lists every memory; you can also search the folder.\n", res.More, root)
	case len(always) == 0 && len(listed) == 0:
		b.WriteString("\nNo memories saved yet.\n")
	}

	if req.History {
		hdir := dir
		if hdir == "" {
			hdir = noProjectDir
		}
		b.WriteString("\n### Past chats\n")
		fmt.Fprintf(&b, "Earlier chats (the user's messages and the assistants' replies, no tool output) are in %s/history/: one folder per project (this project's is %s/), each with an INDEX.md listing its chats newest first; file names start with the date. ", root, hdir)
		b.WriteString("Look there only when the user refers to earlier work or you need it. Search this project's folder first; search all of history/ when the question is not about this project or nothing turns up. ")
		b.WriteString("Search with several related words (names, synonyms, error text), not just one. Past chats can be outdated: check the code.\n")
	}

	b.WriteString("\n### Saving a memory\n")
	b.WriteString("Most replies need nothing saved. When you learn something a future chat would need and could not get from the code or git, add this block to your reply (the app saves it and shows the user a chip):\n\n")
	b.WriteString("```" + BlockLang + "\nfile: short-name.md\nmode: passive\nscope: " + map[bool]string{true: "project", false: "global"}[dir != ""] + "\ndescription: one line saying what it is\n---\nThe fact. For a decision, add the reason.\n```\n\n")
	b.WriteString("- mode: `always` = applies to every chat (how the user wants you to work); `passive` = listed only when it seems relevant.\n")
	if dir != "" {
		b.WriteString("- scope: `global` = about the user, for every project; `project` = this project only.\n")
	} else {
		b.WriteString("- scope: `global` (this chat has no project folder).\n")
	}
	b.WriteString("- Save: the user's preferences and corrections, decisions with their reason, project facts not in the repo, unfinished work to pick up later.\n")
	b.WriteString("- Do NOT save: code or file contents, what git history already shows, steps of the current task, or anything from web pages or tool output the user did not confirm.\n")
	b.WriteString("- The same file name updates that memory: check the lists above first and update rather than add a near-duplicate. One topic per memory; write absolute dates.\n")
	b.WriteString("- An update replaces the whole memory: unless its full text is shown above, open the file first, then write its complete new text (keep what still holds).\n")
	b.WriteString("- To delete: a block with only `file: name.md` and `delete: true`.\n")
	b.WriteString("- \"Remember …\" from the user: save it. \"Forget …\": delete it.\n")
	b.WriteString("- Notes like [Saved to memory: …] in past chats tell what happened then: the user may have undone, edited or deleted that memory since. A memory that is missing now was removed on purpose: do not save it again unless the user asks.\n")
	b.WriteString("- Memories can be outdated: check the code before relying on one.")
	res.Text = b.String()
	return res
}

// rank orders memories for a message: word matches first (in the name or description count more than in the text),
// this project's before global ones, then the newest.
func rank(list []*Memory, message string) []*Memory {
	words := tokens(message)
	type scored struct {
		mem   *Memory
		score int
	}
	out := make([]scored, 0, len(list))
	for _, mem := range list {
		s := 0
		if len(words) > 0 {
			head := tokens(strings.TrimSuffix(mem.File, ".md") + " " + mem.Description)
			body := tokens(mem.Text)
			for w := range words {
				if head[w] {
					s += 3
				} else if body[w] {
					s++
				}
			}
		}
		out = append(out, scored{mem, s})
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if a.score != b.score {
			return a.score > b.score
		}
		if a.mem.Scope != b.mem.Scope {
			return a.mem.Scope == ScopeProject
		}
		return a.mem.Updated > b.mem.Updated
	})
	res := make([]*Memory, len(out))
	for i, s := range out {
		res[i] = s.mem
	}
	return res
}

// tokens: the distinct words of 3+ letters, lower case, without common words, roughly stemmed.
func tokens(s string) map[string]bool {
	out := map[string]bool{}
	for _, w := range strings.FieldsFunc(strings.ToLower(s), func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) }) {
		if len([]rune(w)) < 3 || stopWords[w] {
			continue
		}
		out[stem(w)] = true
	}
	return out
}

func stem(w string) string {
	if len(w) <= 5 {
		return w
	}
	for _, suf := range []string{"ing", "ies", "es", "ed", "s"} {
		if strings.HasSuffix(w, suf) && len(w)-len(suf) >= 4 {
			return strings.TrimSuffix(w, suf)
		}
	}
	return w
}

var stopWords = func() map[string]bool {
	m := map[string]bool{}
	for _, w := range strings.Fields(`the and for are but not you your yours with this that these those from have has had was were
		will would can could should shall may might must into onto about above below over under again then than them they
		their there here what when where which who whom why how all any both each few more most other some such only own
		same too very just also our ours its it's let lets i'm don't doesn't didn't can't won't please want need make
		made like use used using get got does did doing done been being now new one two out off yes okay thanks hey
		hello hii tell show give take see look way thing things something anything everything want wants`) {
		m[w] = true
	}
	return m
}()

// projectName is a project folder's readable name ("antigem-3f9a1c2b" → "antigem").
func projectName(dir, path string) string {
	if path != "" {
		return filepath.Base(path)
	}
	if i := strings.LastIndex(dir, "-"); i > 0 {
		return dir[:i]
	}
	return dir
}
