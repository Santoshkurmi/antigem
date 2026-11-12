package plugins

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

// memSnapshot is a repository held in memory.
type memSnapshot map[string]string

func (s memSnapshot) files() []treeEntry {
	var out []treeEntry
	for p, c := range s {
		out = append(out, treeEntry{Path: p, Type: "blob", Size: int64(len(c))})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

func (s memSnapshot) read(p string) (string, error) {
	c, ok := s[p]
	if !ok {
		return "", fmt.Errorf("%s not found", p)
	}
	return c, nil
}

func (s memSnapshot) extract(prefix, dest string) error {
	n := 0
	for p, c := range s {
		if !underPrefix(p, prefix) {
			continue
		}
		target, err := safeTarget(dest, relTo(p, prefix))
		if err != nil {
			return err
		}
		if err := os.WriteFile(target, []byte(c), 0o644); err != nil {
			return err
		}
		n++
	}
	if n == 0 {
		return fmt.Errorf("Folder %s not found", displayPath(prefix))
	}
	return nil
}

func (s memSnapshot) close() {}

func skillMD(name, desc string) string {
	return "---\nname: " + name + "\ndescription: " + desc + "\n---\nDo the thing.\n"
}

func newTestSkills(t *testing.T, repos map[string]memSnapshot) (*AgentSkills, string) {
	home := t.TempDir()
	a := NewAgentSkills(nil, home, nil)
	a.snapshot = func(r Repo, tmp string) (repoSnapshot, error) {
		s, ok := repos[r.slug()]
		if !ok {
			return nil, fmt.Errorf("Repository or branch not found")
		}
		return s, nil
	}
	t.Setenv("CLAUDE_CONFIG_DIR", "")
	t.Setenv("CODEX_HOME", "")
	t.Setenv("XDG_CONFIG_HOME", "")
	return a, home
}

func TestParseSkillSource(t *testing.T) {
	cases := []struct {
		in        string
		repo, ref string
		sub       string
	}{
		{"anthropics/skills", "anthropics/skills", "", ""},
		{"https://github.com/anthropics/skills", "anthropics/skills", "", ""},
		{"https://github.com/anthropics/skills.git", "anthropics/skills", "", ""},
		{"github.com/anthropics/skills/", "anthropics/skills", "", ""},
		{"anthropics/skills@dev", "anthropics/skills", "dev", ""},
		{"https://github.com/anthropics/skills/tree/main/skills/pdf", "anthropics/skills", "main", "skills/pdf"},
		{"https://github.com/anthropics/skills/tree/main", "anthropics/skills", "main", ""},
		{"https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md", "anthropics/skills", "main", "skills/pdf"},
		{"https://github.com/o/r/blob/main/SKILL.md", "o/r", "main", ""},
		{"https://github.com/o/r?tab=readme-ov-file#install", "o/r", "", ""},
	}
	for _, c := range cases {
		s, err := parseSkillSource(c.in)
		if err != nil {
			t.Fatalf("%s: %v", c.in, err)
		}
		if s.Repo.slug() != c.repo || s.Repo.Ref != c.ref || s.Subpath != c.sub {
			t.Errorf("%s: got %s ref=%q sub=%q", c.in, s.Repo.slug(), s.Repo.Ref, s.Subpath)
		}
	}
	for _, bad := range []string{"", "just-a-word", "https://github.com/o/r/tree/main/../x", "https://example.com/o/r"} {
		if _, err := parseSkillSource(bad); err == nil {
			t.Errorf("%q: expected an error", bad)
		}
	}
}

func TestFindSkillDirs(t *testing.T) {
	files := memSnapshot{
		"README.md":                             "",
		"skills/pdf/SKILL.md":                   "",
		"skills/pdf/examples/inner/SKILL.md":    "", // belongs to pdf
		"skills/docx/SKILL.md":                  "",
		"other/tools/review/SKILL.md":           "",
		"node_modules/x/SKILL.md":               "",
		"skills/notes.md":                       "",
		"skills/docx/reference/SKILL.md.bak":    "",
		".claude/skills/project-skill/SKILL.md": "",
	}.files()
	got := findSkillDirs(files, "")
	want := []string{".claude/skills/project-skill", "other/tools/review", "skills/docx", "skills/pdf"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("all: got %v want %v", got, want)
	}
	if got := findSkillDirs(files, "skills"); !reflect.DeepEqual(got, []string{"skills/docx", "skills/pdf"}) {
		t.Fatalf("subpath: got %v", got)
	}
	if got := findSkillDirs(files, "skills/pdf"); !reflect.DeepEqual(got, []string{"skills/pdf"}) {
		t.Fatalf("one folder: got %v", got)
	}
	// a repository that is one skill: everything below belongs to it
	root := memSnapshot{"SKILL.md": "", "sub/SKILL.md": ""}.files()
	if got := findSkillDirs(root, ""); !reflect.DeepEqual(got, []string{""}) {
		t.Fatalf("root: got %v", got)
	}
}

func TestParseFrontmatter(t *testing.T) {
	cases := []struct{ text, name, desc string }{
		{skillMD("pdf", "Read PDFs"), "pdf", "Read PDFs"},
		{"\uFEFF---\r\nname: \"quoted name\"\r\ndescription: 'single'\r\n---\r\n", "quoted name", "single"},
		{"---\nname: x\ndescription: >\n  folded over\n  two lines\nlicense: MIT\n---\n", "x", "folded over two lines"},
		{"---\nname: x\nmetadata:\n  name: nested\ndescription: |\n  block\n---\n", "x", "block"},
		{"no front matter\nname: x\n", "", ""},
	}
	for _, c := range cases {
		n, d := parseFrontmatter(c.text)
		if n != c.name || d != c.desc {
			t.Errorf("%q: got (%q, %q)", c.text, n, d)
		}
	}
}

func TestSkillIDAndPluginKinds(t *testing.T) {
	for in, want := range map[string]string{"pdf": "pdf", "Git Release!": "Git-Release", "../x": "x", "": "skill", "--.": "skill"} {
		if got := skillID(in); got != want {
			t.Errorf("skillID(%q) = %q, want %q", in, got, want)
		}
		if !folderPattern.MatchString(skillID(in)) {
			t.Errorf("skillID(%q) is not a valid folder name", in)
		}
	}
	files := memSnapshot{".claude-plugin/marketplace.json": "", ".claude-plugin/plugin.json": "", ".agents/plugins/marketplace.json": "", "plugin.json": ""}.files()
	if got := pluginKinds(files, ""); !reflect.DeepEqual(got, []string{"claude-marketplace", "claude-plugin", "codex-plugin", "agy-plugin"}) {
		t.Fatalf("kinds: %v", got)
	}
	if got := pluginKinds(files, "sub"); len(got) != 0 {
		t.Fatalf("kinds under sub: %v", got)
	}
}

func TestInstallListUpdateRemove(t *testing.T) {
	repo := memSnapshot{
		"skills/pdf/SKILL.md":         skillMD("pdf", "Read PDFs"),
		"skills/pdf/scripts/split.py": "print(1)",
		"skills/docx/SKILL.md":        skillMD("docx", "Write Word files"),
	}
	a, home := newTestSkills(t, map[string]memSnapshot{"o/skills": repo})

	// only the picked skill, into Claude Code's folder
	installed, replaced, err := a.install("claude", "https://github.com/o/skills", []string{"skills/pdf"}, false)
	if err != nil || !reflect.DeepEqual(installed, []string{"pdf"}) || len(replaced) != 0 {
		t.Fatalf("install: %v %v %v", installed, replaced, err)
	}
	dir := filepath.Join(home, ".claude", "skills")
	if b, err := os.ReadFile(filepath.Join(dir, "pdf", "scripts", "split.py")); err != nil || string(b) != "print(1)" {
		t.Fatalf("skill files not copied: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "docx")); !os.IsNotExist(err) {
		t.Fatal("a skill that was not picked was installed")
	}
	src := readSkillSource(filepath.Join(dir, "pdf"))
	if src == nil || src.Repo != "o/skills" || src.Path != "skills/pdf" {
		t.Fatalf("source: %+v", src)
	}

	// a hand-made skill and a Codex system folder are listed / skipped as such
	if err := os.MkdirAll(filepath.Join(dir, "mine"), 0o755); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(dir, "mine", skillFile), []byte(skillMD("My skill", "By hand")), 0o644)
	_ = os.MkdirAll(filepath.Join(dir, ".system", "x"), 0o755)
	list := a.list("claude", dir)
	if len(list) != 2 || list[0].ID != "mine" || list[0].Name != "My skill" || list[0].Source != nil || list[1].ID != "pdf" || list[1].Source == nil {
		t.Fatalf("list: %+v", list)
	}

	// adding the same repository folder again updates it without asking
	repo["skills/pdf/SKILL.md"] = skillMD("pdf", "Read PDFs v2")
	if _, replaced, err := a.install("claude", "o/skills", []string{"skills/pdf"}, false); err != nil || !reflect.DeepEqual(replaced, []string{"pdf"}) {
		t.Fatalf("re-add: %v %v", replaced, err)
	}
	// update downloads it again from where it came from
	repo["skills/pdf/SKILL.md"] = skillMD("pdf", "Read PDFs v3")
	if err := a.update("claude", "pdf"); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, "pdf", skillFile)); !strings.Contains(string(b), "v3") {
		t.Fatalf("not updated: %s", b)
	}
	if err := a.update("claude", "mine"); err == nil {
		t.Fatal("a hand-made skill has nothing to update from")
	}

	// a different skill with the same name is only replaced when asked
	other := memSnapshot{"pdf/SKILL.md": skillMD("pdf", "Another")}
	a.snapshot = func(r Repo, tmp string) (repoSnapshot, error) { return other, nil }
	if _, _, err := a.install("claude", "x/y", nil, false); err == nil || !strings.Contains(err.Error(), "already uses the name pdf") {
		t.Fatalf("clash: %v", err)
	}
	if _, replaced, err := a.install("claude", "x/y", nil, true); err != nil || !reflect.DeepEqual(replaced, []string{"pdf"}) {
		t.Fatalf("replace: %v %v", replaced, err)
	}

	if err := a.remove("claude", "pdf"); err != nil {
		t.Fatal(err)
	}
	if err := a.remove("claude", "../x"); err == nil {
		t.Fatal("unsafe id accepted")
	}
	if err := a.remove("claude", "pdf"); err == nil {
		t.Fatal("removed twice")
	}
}

func TestInstallRootSkillAndNoSkills(t *testing.T) {
	a, home := newTestSkills(t, map[string]memSnapshot{
		"o/one-skill": {"SKILL.md": skillMD("Grill Me", "Ask hard questions"), "README.md": "x"},
		"o/empty":     {"README.md": "x"},
	})
	installed, _, err := a.install("opencode", "o/one-skill", nil, false)
	if err != nil || !reflect.DeepEqual(installed, []string{"Grill-Me"}) {
		t.Fatalf("root skill: %v %v", installed, err)
	}
	if _, err := os.Stat(filepath.Join(home, ".config", "opencode", "skills", "Grill-Me", skillFile)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := a.install("codex", "o/empty", nil, false); err == nil || !strings.Contains(err.Error(), "No skills") {
		t.Fatalf("empty repo: %v", err)
	}
	if _, _, err := a.install("nobody", "o/one-skill", nil, false); err == nil {
		t.Fatal("unknown agent accepted")
	}
}

func TestRemoveLinkedSkillKeepsTarget(t *testing.T) {
	a, home := newTestSkills(t, nil)
	outside := t.TempDir()
	_ = os.WriteFile(filepath.Join(outside, skillFile), []byte(skillMD("linked", "x")), 0o644)
	dir := filepath.Join(home, ".codex", "skills")
	_ = os.MkdirAll(dir, 0o755)
	if err := os.Symlink(outside, filepath.Join(dir, "linked")); err != nil {
		t.Skip("symlinks not supported")
	}
	if got := a.list("codex", dir); len(got) != 1 || got[0].ID != "linked" {
		t.Fatalf("list: %+v", got)
	}
	if err := a.remove("codex", "linked"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(outside, skillFile)); err != nil {
		t.Fatal("removing a linked skill deleted what it points to")
	}
}

func TestAgentSkillsHTTP(t *testing.T) {
	a, _ := newTestSkills(t, map[string]memSnapshot{"o/skills": {"a/SKILL.md": skillMD("a", "A"), ".claude-plugin/plugin.json": "{}"}})
	call := func(method, route, body string) map[string]interface{} {
		req := httptest.NewRequest(method, "/api/agent-skills"+route, bytes.NewBufferString(body))
		rec := httptest.NewRecorder()
		a.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s %s: HTTP %d", method, route, rec.Code)
		}
		var out map[string]interface{}
		_ = json.Unmarshal(rec.Body.Bytes(), &out)
		return out
	}
	res := call("POST", "/inspect", `{"source":"o/skills","agent":"agy"}`)
	if res["success"] != true || len(res["skills"].([]interface{})) != 1 || res["plugin_kinds"].([]interface{})[0] != "claude-plugin" {
		t.Fatalf("inspect: %v", res)
	}
	if res := call("POST", "/install", `{"agent":"agy","source":"o/skills"}`); res["success"] != true {
		t.Fatalf("install: %v", res)
	}
	res = call("GET", "/?agent=agy", "")
	if skills := res["skills"].([]interface{}); len(skills) != 1 || skills[0].(map[string]interface{})["id"] != "a" {
		t.Fatalf("list: %v", res)
	}
	if res := call("POST", "/inspect", `{"source":"o/skills","agent":"agy"}`); res["skills"].([]interface{})[0].(map[string]interface{})["same_source"] != true {
		t.Fatalf("inspect after install: %v", res)
	}
	if res := call("GET", "/?agent=nope", ""); res["success"] != false {
		t.Fatalf("unknown agent: %v", res)
	}
	if res := call("POST", "/agy-plugin", `{"source":"o/skills"}`); res["success"] != false || !strings.Contains(res["error"].(string), "not installed") {
		t.Fatalf("agy-plugin without agy: %v", res)
	}
}
