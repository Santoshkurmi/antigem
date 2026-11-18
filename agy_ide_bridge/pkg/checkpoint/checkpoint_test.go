package checkpoint

import (
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

func sh(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), "GIT_AUTHOR_NAME=t", "GIT_AUTHOR_EMAIL=t@t", "GIT_COMMITTER_NAME=t", "GIT_COMMITTER_EMAIL=t@t")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v: %v\n%s", args, err, out)
	}
	return string(out)
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func read(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		return "<missing>"
	}
	return string(b)
}

func exists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

// a project (git repo with a commit, a staged change, an ignored file) and a manager tracking it
func setup(t *testing.T) (*Manager, string) {
	t.Helper()
	home := t.TempDir()
	proj := filepath.Join(t.TempDir(), "proj")
	write(t, filepath.Join(proj, "a.txt"), "a1\n")
	write(t, filepath.Join(proj, "src", "b.txt"), "b1\n")
	write(t, filepath.Join(proj, ".gitignore"), "secret.env\n")
	sh(t, proj, "init", "-q")
	sh(t, proj, "add", "-A")
	sh(t, proj, "commit", "-qm", "init")
	write(t, filepath.Join(proj, "secret.env"), "KEY=1\n")
	write(t, filepath.Join(proj, "staged.txt"), "s\n")
	sh(t, proj, "add", "staged.txt")
	m := &Manager{root: filepath.Join(home, "cp"), projects: map[string]*project{}, gitCache: map[string]gitCacheEntry{}}
	m.settings = defaultSettings()
	if _, err := m.UpdateSettings(SettingsPatch{Mode: strPtr(ModeSelected)}); err != nil {
		t.Fatal(err)
	}
	if err := m.SetDecision(proj, "on"); err != nil {
		t.Fatal(err)
	}
	return m, normPath(proj)
}

func strPtr(s string) *string { return &s }
func intPtr(i int) *int       { return &i }

func turn(t *testing.T, m *Manager, proj, chat, key string) *Summary {
	t.Helper()
	res, err := m.Turn(TurnRequest{Project: proj, Agent: "codex", ChatID: chat, Key: key, Title: key})
	if err != nil {
		t.Fatal(err)
	}
	if !res.Tracked || res.Checkpoint == nil {
		t.Fatalf("not tracked: %+v", res)
	}
	return res.Checkpoint
}

func TestRestoreRoundTripAndUserRepoUntouched(t *testing.T) {
	m, proj := setup(t)
	statusBefore := sh(t, proj, "status", "--porcelain")
	refsBefore := sh(t, proj, "for-each-ref")
	stashBefore, _ := exec.Command("git", "-C", proj, "stash", "list").CombinedOutput()

	cp := turn(t, m, proj, "chat1", "k1")

	// the "agent" works: edits, creates, deletes, replaces a file with a folder
	write(t, filepath.Join(proj, "a.txt"), "a2 broken\n")
	write(t, filepath.Join(proj, "new", "made.txt"), "new\n")
	os.Remove(filepath.Join(proj, "src", "b.txt"))
	write(t, filepath.Join(proj, "secret.env"), "KEY=2\n") // ignored: never touched

	pv, err := m.PreviewRestore(proj, cp.ID)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]string{}
	for _, f := range pv.Files {
		got[f.Path] = f.Change
	}
	want := map[string]string{"a.txt": "modified", "new/made.txt": "deleted", "src/b.txt": "recreated"}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("preview %s = %q, want %q (all: %v)", k, got[k], v, got)
		}
	}
	if len(got) != len(want) {
		t.Errorf("preview has extra files: %v", got)
	}

	res, err := m.Restore(proj, cp.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Errors) > 0 {
		t.Fatalf("restore errors: %v", res.Errors)
	}
	if read(t, filepath.Join(proj, "a.txt")) != "a1\n" || read(t, filepath.Join(proj, "src", "b.txt")) != "b1\n" {
		t.Error("files not back")
	}
	if exists(filepath.Join(proj, "new")) {
		t.Error("created folder not removed")
	}
	if read(t, filepath.Join(proj, "secret.env")) != "KEY=2\n" {
		t.Error("ignored file was touched")
	}
	if s := sh(t, proj, "status", "--porcelain"); s != statusBefore {
		t.Errorf("git status changed:\n%s\nwas:\n%s", s, statusBefore)
	}
	if r := sh(t, proj, "for-each-ref"); r != refsBefore {
		t.Errorf("user refs changed")
	}
	if s, _ := exec.Command("git", "-C", proj, "stash", "list").CombinedOutput(); string(s) != string(stashBefore) {
		t.Errorf("stash changed")
	}

	// undo the restore: the broken state comes back
	if _, err := m.Restore(proj, res.UndoID); err != nil {
		t.Fatal(err)
	}
	if read(t, filepath.Join(proj, "a.txt")) != "a2 broken\n" || !exists(filepath.Join(proj, "new", "made.txt")) || exists(filepath.Join(proj, "src", "b.txt")) {
		t.Error("undo did not bring the later state back")
	}
}

func TestNestedRepository(t *testing.T) {
	m, proj := setup(t)
	nested := filepath.Join(proj, "lib")
	write(t, filepath.Join(nested, "x.txt"), "x1\n")
	sh(t, nested, "init", "-q")
	sh(t, nested, "add", "-A")
	sh(t, nested, "commit", "-qm", "n")

	cp := turn(t, m, proj, "c", "k")
	rec := m.projects[projectKey(proj)].find(cp.ID)
	if len(rec.Units) != 2 || rec.Units[1].Path != "lib" {
		t.Fatalf("units = %+v", rec.Units)
	}

	write(t, filepath.Join(nested, "x.txt"), "x2\n")
	write(t, filepath.Join(nested, "y.txt"), "y\n")
	pv, err := m.PreviewRestore(proj, cp.ID)
	if err != nil {
		t.Fatal(err)
	}
	var paths []string
	for _, f := range pv.Files {
		paths = append(paths, f.Path+":"+f.Change)
	}
	sort.Strings(paths)
	if strings.Join(paths, ",") != "lib/x.txt:modified,lib/y.txt:deleted" {
		t.Fatalf("preview = %v", paths)
	}
	d, err := m.FileDiff(proj, cp.ID, "lib/x.txt")
	if err != nil || !strings.Contains(d, "-x2") || !strings.Contains(d, "+x1") {
		t.Fatalf("diff = %q %v", d, err)
	}
	if _, err := m.Restore(proj, cp.ID); err != nil {
		t.Fatal(err)
	}
	if read(t, filepath.Join(nested, "x.txt")) != "x1\n" || exists(filepath.Join(nested, "y.txt")) {
		t.Error("nested repo files not restored")
	}
	if !exists(filepath.Join(nested, ".git", "HEAD")) {
		t.Error("nested .git touched")
	}
}

func TestLargeFilesAndTracking(t *testing.T) {
	m, proj := setup(t)
	if _, err := m.UpdateSettings(SettingsPatch{MaxFileMB: intPtr(1)}); err != nil {
		t.Fatal(err)
	}
	big := strings.Repeat("x", 2<<20)
	write(t, filepath.Join(proj, "big.bin"), big)
	res, err := m.Turn(TurnRequest{Project: proj, ChatID: "c", Key: "k"})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Skipped) != 1 || res.Skipped[0] != "big.bin" {
		t.Fatalf("skipped = %v", res.Skipped)
	}
	// restoring never deletes a file that was too large to save then
	write(t, filepath.Join(proj, "big.bin"), "small now\n")
	if _, err := m.Restore(proj, res.Checkpoint.ID); err != nil {
		t.Fatal(err)
	}
	if read(t, filepath.Join(proj, "big.bin")) != "small now\n" {
		t.Error("too-large-then file was changed")
	}

	// turned off: no checkpoint
	_ = m.SetDecision(proj, "off")
	r2, _ := m.Turn(TurnRequest{Project: proj, Key: "k2"})
	if r2.Tracked {
		t.Error("tracked while off")
	}
	// not a git repo
	plain := t.TempDir()
	_, _ = m.UpdateSettings(SettingsPatch{Mode: strPtr(ModeAll)})
	if st := m.State(plain); st.Tracked || st.IsGit {
		t.Errorf("plain folder tracked: %+v", st)
	}
}

func TestDeleteOldKeepsNewAndLookup(t *testing.T) {
	m, proj := setup(t)
	old := turn(t, m, proj, "c", "k-old")
	write(t, filepath.Join(proj, "a.txt"), "a-mid\n")
	mid := turn(t, m, proj, "c", "k-mid")
	write(t, filepath.Join(proj, "a.txt"), "a-new\n")

	p := m.projects[projectKey(proj)]
	p.mu.Lock()
	p.find(old.ID).Time = time.Now().Add(-40 * 24 * time.Hour).UnixMilli()
	p.mu.Unlock()

	res, err := m.Delete(DeleteRequest{Project: proj, OlderThanDays: 30})
	if err != nil || res.Deleted != 1 {
		t.Fatalf("delete = %+v %v", res, err)
	}
	if err := m.Bind(proj, mid.ID, "turn-123"); err != nil {
		t.Fatal(err)
	}
	found := m.Lookup(proj, []string{"k-old", "k-mid", "turn-123"})
	if _, ok := found["k-old"]; ok {
		t.Error("deleted checkpoint still found")
	}
	if found["turn-123"].ID != mid.ID || found["k-mid"].ID != mid.ID {
		t.Errorf("lookup = %+v", found)
	}
	// the newer checkpoint still restores fully after the gc
	if _, err := m.Restore(proj, mid.ID); err != nil {
		t.Fatal(err)
	}
	if read(t, filepath.Join(proj, "a.txt")) != "a-mid\n" {
		t.Error("newer checkpoint broken by deleting the old one")
	}
	if _, err := m.Delete(DeleteRequest{Project: proj, All: true}); err != nil {
		t.Fatal(err)
	}
	if exists(filepath.Join(m.root, "projects", projectKey(proj))) {
		t.Error("delete all left data")
	}
}

func TestOtherChatWarningAndFileToFolder(t *testing.T) {
	m, proj := setup(t)
	cp := turn(t, m, proj, "chatA", "a1")
	write(t, filepath.Join(proj, "x"), "file\n")
	turn(t, m, proj, "chatB", "b1")
	os.Remove(filepath.Join(proj, "x"))
	write(t, filepath.Join(proj, "x", "inner.txt"), "now a folder\n")
	pv, err := m.PreviewRestore(proj, cp.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(pv.LaterChats) != 1 || pv.LaterChats[0].ChatID != "chatB" {
		t.Errorf("later chats = %+v", pv.LaterChats)
	}
	res, err := m.Restore(proj, cp.ID)
	if err != nil || len(res.Errors) > 0 {
		t.Fatalf("%v %v", err, res.Errors)
	}
	if exists(filepath.Join(proj, "x")) {
		t.Error("x should be gone (did not exist at the checkpoint)")
	}
}
