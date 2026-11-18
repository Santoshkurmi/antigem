package memory

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func setup(t *testing.T) (*Manager, string) {
	t.Helper()
	home := t.TempDir()
	project := filepath.Join(home, "work", "antigem")
	if err := os.MkdirAll(project, 0o700); err != nil {
		t.Fatal(err)
	}
	return NewManager(home), normPath(project)
}

func read(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(b)
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

const tick = "```"

func memBlock(lines ...string) string {
	return tick + "antigem-memory\n" + strings.Join(lines, "\n") + "\n" + tick
}

func TestFindBlocksTopLevelOnly(t *testing.T) {
	reply := strings.Join([]string{
		"Done.",
		memBlock("file: a.md", "---", "Fact A"),
		"An example, not a real one:",
		"````markdown",
		memBlock("file: example.md", "---", "Example"),
		"````",
		tick + "antigem-memory",
		"file: unfinished.md",
	}, "\n")
	got := FindBlocks(reply)
	if len(got) != 1 || !strings.Contains(got[0], "Fact A") {
		t.Fatalf("want only the top-level closed block, got %q", got)
	}
}

func TestParseBlock(t *testing.T) {
	pb := parseBlock("file: Search DB.md\nmode: always\nscope: all projects\ndescription: why FTS4\n---\nUse FTS4.\nBecause.")
	if cleanFile(pb.File) != "search-db.md" || pb.Mode != ModeAlways || pb.Scope != ScopeGlobal || pb.Description != "why FTS4" || pb.Text != "Use FTS4.\nBecause." {
		t.Fatalf("bad parse: %+v", pb)
	}
	pb = parseBlock("file: x.md\nThe text without a separator")
	if pb.File != "x.md" || pb.Text != "The text without a separator" {
		t.Fatalf("bad parse without ---: %+v", pb)
	}
	pb = parseBlock("file: x.md\ndelete: true")
	if !pb.Delete {
		t.Fatalf("want delete: %+v", pb)
	}
}

func TestSyncAppliesOnceWritesHistoryAndUndo(t *testing.T) {
	m, project := setup(t)
	dir := projectDir(project)
	reply := "I'll remember that.\n" + memBlock("file: db-choice.md", "description: Room FTS4 for search", "---", "Chat search uses Room FTS4.")
	req := SyncRequest{Project: project, Agent: "claude", ChatID: "11111111-2222", Title: "Memory design", History: true, Turns: []Turn{
		{Role: "user", Text: "use fts4", At: 1700000000000},
		{Role: "assistant", Text: reply, At: 1700000001000},
	}}
	res, err := m.Sync(req)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Applied) != 1 || res.Applied[0].Action != "saved" || res.Applied[0].ID != "projects/"+dir+"/db-choice.md" {
		t.Fatalf("bad apply: %+v", res.Applied)
	}
	memPath := filepath.Join(m.root, "projects", dir, "db-choice.md")
	if c := read(t, memPath); !strings.Contains(c, "Chat search uses Room FTS4.") || !strings.Contains(c, "mode: passive") || !strings.Contains(c, "source: history/"+dir+"/") {
		t.Fatalf("bad memory file:\n%s", c)
	}
	if c := read(t, filepath.Join(m.root, "INDEX.md")); !strings.Contains(c, "db-choice.md · passive · Room FTS4 for search") {
		t.Fatalf("bad INDEX.md:\n%s", c)
	}
	hist := read(t, filepath.Join(m.root, filepath.FromSlash(res.HistoryFile)))
	if !strings.Contains(hist, "## User") || !strings.Contains(hist, "[Saved to memory: db-choice.md]") || strings.Contains(hist, "Chat search uses Room FTS4.") {
		t.Fatalf("bad history file:\n%s", hist)
	}
	if !exists(filepath.Join(m.root, "history", dir, "INDEX.md")) {
		t.Fatal("no history INDEX.md")
	}

	// a second sync (chat reopened) applies nothing again
	res, err = m.Sync(req)
	if err != nil || len(res.Applied) != 0 {
		t.Fatalf("applied twice: %+v %v", res.Applied, err)
	}

	key := BlockKey(FindBlocks(reply)[0])
	if a := m.Applied(key); !a.Found || a.Action != "saved" {
		t.Fatalf("bad status: %+v", a)
	}
	if _, err := m.Undo(key); err != nil {
		t.Fatal(err)
	}
	if exists(memPath) {
		t.Fatal("undo should remove a new memory")
	}
	if a := m.Applied(key); !a.Undone {
		t.Fatalf("want undone: %+v", a)
	}
	// an undone block is not applied again on the next sync
	if res, _ = m.Sync(req); len(res.Applied) != 0 || exists(memPath) {
		t.Fatal("undone block came back")
	}
	// state survives a restart
	m2 := NewManager(filepath.Dir(filepath.Dir(m.root)))
	if a := m2.Applied(key); !a.Found || !a.Undone {
		t.Fatalf("state not saved: %+v", a)
	}
}

func TestUpdateMoveAndDeleteBlocks(t *testing.T) {
	m, project := setup(t)
	dir := projectDir(project)
	sync := func(text string) []AppliedInfo {
		t.Helper()
		res, err := m.Sync(SyncRequest{Project: project, Agent: "codex", ChatID: "c1", Turns: []Turn{{Role: "assistant", Text: text}}})
		if err != nil {
			t.Fatal(err)
		}
		return res.Applied
	}
	sync(memBlock("file: style.md", "scope: project", "mode: always", "---", "Old text"))
	got := sync(memBlock("file: style.md", "scope: global", "---", "Explain before editing"))
	if len(got) != 1 || got[0].Action != "updated" || got[0].ID != "global/style.md" {
		t.Fatalf("bad move: %+v", got)
	}
	if exists(filepath.Join(m.root, "projects", dir, "style.md")) {
		t.Fatal("project copy should have moved to global/")
	}
	if c := read(t, filepath.Join(m.root, "global", "style.md")); !strings.Contains(c, "mode: always") {
		t.Fatalf("mode should carry over:\n%s", c)
	}
	// undo of the move brings the project copy back and removes the global one
	if _, err := m.Undo(got[0].Key); err != nil {
		t.Fatal(err)
	}
	if !exists(filepath.Join(m.root, "projects", dir, "style.md")) || exists(filepath.Join(m.root, "global", "style.md")) {
		t.Fatal("undo of a move went wrong")
	}
	got = sync(memBlock("file: style.md", "delete: true"))
	if len(got) != 1 || got[0].Action != "deleted" || exists(filepath.Join(m.root, "projects", dir, "style.md")) {
		t.Fatalf("bad delete: %+v", got)
	}
	got = sync(memBlock("file: missing.md", "delete: true"))
	if len(got) != 1 || got[0].Action != "error" {
		t.Fatalf("deleting a missing memory should be an error: %+v", got)
	}
}

func TestNoProjectFallsBackToGlobal(t *testing.T) {
	m, _ := setup(t)
	res, err := m.Sync(SyncRequest{Agent: "agy", ChatID: "x", History: true, Turns: []Turn{{Role: "assistant", Text: memBlock("file: a.md", "scope: project", "---", "Fact")}}})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Applied) != 1 || res.Applied[0].ID != "global/a.md" {
		t.Fatalf("want global/: %+v", res.Applied)
	}
	if !strings.HasPrefix(res.HistoryFile, "history/"+noProjectDir+"/") {
		t.Fatalf("bad history file %q", res.HistoryFile)
	}
}

func TestContext(t *testing.T) {
	m, project := setup(t)
	other := filepath.Join(filepath.Dir(project), "other")
	_ = os.MkdirAll(other, 0o700)
	save := func(scope, proj, mode, file, desc, text string) {
		t.Helper()
		if _, err := m.Save(SaveRequest{File: file, Scope: scope, Project: proj, Mode: mode, Description: desc, Text: text}); err != nil {
			t.Fatal(err)
		}
	}
	save("global", "", "always", "explain-first", "Explain before edits", "Always explain planned changes before editing.")
	save("project", project, "passive", "m3u8-crash", "m3u8 playback crash fix", "Android 9 crash: wrong codec.")
	save("project", project, "passive", "db-choice", "Room FTS4 for search", "Chat search uses FTS4.")
	save("project", other, "passive", "secret-plan", "Other project's memory", "Not for this chat.")

	res := m.Context(ContextRequest{Project: project, Message: "the m3u8 player crashes again", Limit: 1, History: true})
	if !strings.Contains(res.Text, "Always explain planned changes before editing.") {
		t.Fatalf("always memory missing:\n%s", res.Text)
	}
	if !strings.Contains(res.Text, "m3u8-crash.md") || strings.Contains(res.Text, "db-choice.md") {
		t.Fatalf("want the matching memory listed first (limit 1):\n%s", res.Text)
	}
	if res.More != 1 || !strings.Contains(res.Text, "1 more memories are not listed") {
		t.Fatalf("bad more count %d:\n%s", res.More, res.Text)
	}
	if strings.Contains(res.Text, "secret-plan") {
		t.Fatal("another project's memory leaked into the context")
	}
	if !strings.Contains(res.Text, "this project's is "+projectDir(project)+"/") || !strings.Contains(res.Text, tick+"antigem-memory") {
		t.Fatalf("history note or block rules missing:\n%s", res.Text)
	}
	if res := m.Context(ContextRequest{Project: project, History: false}); strings.Contains(res.Text, "### Past chats") {
		t.Fatal("history note shown with history off")
	}
}

func TestCleanText(t *testing.T) {
	long := tick + "kotlin\n" + strings.Repeat("val x = 1\n", 20) + tick
	short := tick + "sh\nls\n" + tick
	out := cleanText("Here:\n"+long+"\nand\n"+short+"\nkey sk-abcdefghijklmnopqrstuvwxyz123456", nil)
	if !strings.Contains(out, "[kotlin code, 20 lines]") || !strings.Contains(out, short) || strings.Contains(out, "sk-abcdefghij") {
		t.Fatalf("bad clean:\n%s", out)
	}
}

func TestSaveDeleteAndValidation(t *testing.T) {
	m, project := setup(t)
	if _, err := m.Save(SaveRequest{File: "x", Scope: "project", Text: "t"}); err == nil {
		t.Fatal("project memory without a project should fail")
	}
	mem, err := m.Save(SaveRequest{File: "Note One", Scope: "project", Project: project, Text: "hello"})
	if err != nil || mem.ID != "projects/"+projectDir(project)+"/note-one.md" {
		t.Fatalf("bad save: %+v %v", mem, err)
	}
	moved, err := m.Save(SaveRequest{ID: mem.ID, File: "note-one", Scope: "global", Text: "hello again"})
	if err != nil || moved.ID != "global/note-one.md" || exists(m.abs(mem.ID)) {
		t.Fatalf("bad move: %+v %v", moved, err)
	}
	if err := m.Delete("../../etc/passwd"); err == nil {
		t.Fatal("bad id accepted")
	}
	if err := m.Delete(moved.ID); err != nil || exists(m.abs(moved.ID)) {
		t.Fatalf("delete failed: %v", err)
	}
}

func TestDeleteHistoryAndSearch(t *testing.T) {
	m, project := setup(t)
	_, err := m.Sync(SyncRequest{Project: project, Agent: "opencode", ChatID: "ses_abc123def456", Title: "Fix video", History: true, Turns: []Turn{
		{Role: "user", Text: "the exoplayer freezes"}, {Role: "assistant", Text: "Fixed the exoplayer buffer size."},
	}})
	if err != nil {
		t.Fatal(err)
	}
	res := m.Search("exoplayer", project)
	if len(res.History) != 1 || res.History[0].ChatID != "ses_abc123def456" || !strings.Contains(res.History[0].Snippet, "exoplayer") {
		t.Fatalf("bad search: %+v", res.History)
	}
	file := m.abs(res.History[0].File)
	if err := m.DeleteHistory("ses_abc123def456"); err != nil || exists(file) {
		t.Fatalf("history not deleted: %v", err)
	}
}

func TestHTTP(t *testing.T) {
	m, project := setup(t)
	srv := httptest.NewServer(m)
	defer srv.Close()
	post := func(path string, body interface{}) map[string]interface{} {
		t.Helper()
		b, _ := json.Marshal(body)
		resp, err := http.Post(srv.URL+"/api/memory/"+path, "application/json", bytes.NewReader(b))
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var out map[string]interface{}
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return out
	}
	if out := post("save", SaveRequest{File: "a", Scope: "project", Project: project, Mode: "always", Text: "Fact A"}); out["success"] != true {
		t.Fatalf("save failed: %v", out)
	}
	out := post("context", ContextRequest{Project: project})
	if out["success"] != true || !strings.Contains(out["text"].(string), "Fact A") {
		t.Fatalf("bad context: %v", out)
	}
	resp, err := http.Get(srv.URL + "/api/memory/list")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var list struct {
		Success  bool      `json:"success"`
		Memories []*Memory `json:"memories"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&list)
	if !list.Success || len(list.Memories) != 1 {
		t.Fatalf("bad list: %+v", list)
	}
}

func TestHistoryEditRemoveAndClear(t *testing.T) {
	m, project := setup(t)
	turns := []Turn{{Role: "user", Text: "first question"}, {Role: "assistant", Text: "first answer"}}
	sync := func(id string, turns []Turn) SyncResult {
		t.Helper()
		res, err := m.Sync(SyncRequest{Project: project, Agent: "claude", ChatID: id, Title: "Chat " + id, History: true, Turns: turns})
		if err != nil {
			t.Fatal(err)
		}
		return res
	}
	sync("chat-a", turns)
	sync("chat-b", turns)
	if list := m.HistoryList(); len(list) != 2 || list[0].Size == 0 {
		t.Fatalf("bad list: %+v", list)
	}

	// the user edits chat-a: kept, and the chat's new turns go at the end
	if _, err := m.HistorySave("chat-a", "# My notes\n\nOnly what matters."); err != nil {
		t.Fatal(err)
	}
	sync("chat-a", append(turns, Turn{Role: "user", Text: "second question"}, Turn{Role: "assistant", Text: "second answer"}))
	chat, text, err := m.HistoryGet("chat-a")
	if err != nil {
		t.Fatal(err)
	}
	if !chat.Edited || chat.Title != "My notes" || !strings.HasPrefix(text, "# My notes") || strings.Contains(text, "first answer") || !strings.Contains(text, "second answer") {
		t.Fatalf("edit not kept or new turns not added:\n%s", text)
	}
	// syncing the same chat again adds nothing twice
	sync("chat-a", append(turns, Turn{Role: "user", Text: "second question"}, Turn{Role: "assistant", Text: "second answer"}))
	if _, text, _ = m.HistoryGet("chat-a"); strings.Count(text, "second answer") != 1 {
		t.Fatalf("turns added twice:\n%s", text)
	}

	// removed: gone, and not saved again when the chat is synced
	if err := m.DeleteHistory("chat-b"); err != nil {
		t.Fatal(err)
	}
	if res := sync("chat-b", turns); res.HistoryFile != "" {
		t.Fatalf("removed chat saved again: %q", res.HistoryFile)
	}
	if n, err := m.ClearHistory(); err != nil || n != 1 || len(m.HistoryList()) != 0 {
		t.Fatalf("clear: %d %v", n, err)
	}
	if res := sync("chat-a", turns); res.HistoryFile != "" {
		t.Fatal("cleared chat saved again")
	}
	if res := sync("chat-c", turns); res.HistoryFile == "" {
		t.Fatal("a new chat should still be saved")
	}
}

func TestHistoryNotesShowUndo(t *testing.T) {
	m, project := setup(t)
	reply := "Noted.\n" + memBlock("file: pet.md", "scope: global", "---", "The user's cat is Mia.")
	req := SyncRequest{Project: project, Agent: "claude", ChatID: "chat-u", History: true, Turns: []Turn{
		{Role: "user", Text: "remember my cat is Mia"}, {Role: "assistant", Text: reply},
	}}
	res, err := m.Sync(req)
	if err != nil {
		t.Fatal(err)
	}
	read1 := read(t, m.abs(res.HistoryFile))
	if !strings.Contains(read1, "[Saved to memory: pet.md]") {
		t.Fatalf("first save should be noted as saved:\n%s", read1)
	}
	if _, err := m.Undo(BlockKey(FindBlocks(reply)[0])); err != nil {
		t.Fatal(err)
	}
	// the app syncs the open chat again after an undo: the note now says it was undone
	if _, err := m.Sync(req); err != nil {
		t.Fatal(err)
	}
	read2 := read(t, m.abs(res.HistoryFile))
	if !strings.Contains(read2, "[Memory change undone by the user: pet.md. Do not save it again unless the user asks]") || strings.Contains(read2, "[Saved to memory: pet.md]") {
		t.Fatalf("history should note the undo:\n%s", read2)
	}
	if text := m.Context(ContextRequest{Project: project, History: true}).Text; !strings.Contains(text, "search all of history/") || !strings.Contains(text, "was removed on purpose") {
		t.Fatalf("new rules missing:\n%s", text)
	}
}
