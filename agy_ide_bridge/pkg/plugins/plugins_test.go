package plugins

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
)

func writeFiles(t *testing.T, dir string, files map[string]string) {
	t.Helper()
	for name, content := range files {
		p := filepath.Join(dir, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func zipOf(t *testing.T, files map[string]string) string {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range files {
		w, _ := zw.Create(name)
		w.Write([]byte(content))
	}
	zw.Close()
	p := filepath.Join(t.TempDir(), "p.zip")
	os.WriteFile(p, buf.Bytes(), 0o644)
	return p
}

func newTestManager(t *testing.T) *Manager {
	files := t.TempDir()
	home := filepath.Join(files, "home")
	os.MkdirAll(home, 0o755)
	return NewManager(home)
}

const skillsManifest = `{"manifest_version":3,"name":"YT","version":"1.0.0","id":"yt"}`

// installZip runs the same steps as the /install route.
func installZip(t *testing.T, m *Manager, zipPath, folder, updateID string, asNew bool) (Plugin, error) {
	t.Helper()
	stage, err := m.newStage()
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(stage)
	pkg, err := extractZip(zipPath, filepath.Join(stage, "unpacked"))
	if err != nil {
		return Plugin{}, err
	}
	return m.install(pkg, folder, updateID, asNew, nil)
}

func TestInstallUpdateRestoreAndAsNew(t *testing.T) {
	m := newTestManager(t)
	p, err := installZip(t, m, zipOf(t, map[string]string{"yt/manifest.json": skillsManifest, "yt/skills/upload.md": "# Upload\n\nUploads a video."}), "yt", "", false)
	if err != nil {
		t.Fatal(err)
	}
	if p.ID != "yt" || p.SkillCount != 1 || p.Skills[0].Description != "Uploads a video." || !p.IsEnabled || p.Location != "installed" {
		t.Fatalf("unexpected plugin %+v", p)
	}
	if _, err := installZip(t, m, zipOf(t, map[string]string{"manifest.json": skillsManifest, "skills/a.md": "# A"}), "other", "", false); err == nil || !strings.Contains(err.Error(), "already exists") {
		t.Fatalf("same ID installed twice: %v", err)
	}
	copyP, err := installZip(t, m, zipOf(t, map[string]string{"manifest.json": skillsManifest, "skills/a.md": "# A"}), "yt-2", "", true)
	if err != nil || copyP.ID != "yt-2" {
		t.Fatalf("install as new: %+v %v", copyP, err)
	}
	updated, err := installZip(t, m, zipOf(t, map[string]string{"manifest.json": strings.Replace(skillsManifest, "1.0.0", "1.1.0", 1), "skills/b.md": "# B"}), "yt", "yt", false)
	if err != nil || updated.Version != "1.1.0" {
		t.Fatalf("update: %+v %v", updated, err)
	}
	if err := m.restore("yt"); err != nil {
		t.Fatal(err)
	}
	if p, _ := m.find("yt"); p.Version != "1.0.0" {
		t.Fatalf("restore did not bring back 1.0.0: %+v", p)
	}
}

func TestRemoveNeverFollowsSymlinks(t *testing.T) {
	m := newTestManager(t)
	outside := t.TempDir()
	writeFiles(t, outside, map[string]string{"precious.txt": "keep me"})
	plugin := filepath.Join(m.managedRoot(), "linky")
	writeFiles(t, plugin, map[string]string{"manifest.json": `{"manifest_version":3,"name":"L","id":"linky"}`, "skills/a.md": "# A"})
	if err := os.Symlink(outside, filepath.Join(plugin, "data")); err != nil {
		t.Skip("symlinks unsupported:", err)
	}
	// A plugin folder that is itself a link into user files is not listed (like the extension host), so it can't be removed
	if err := os.Symlink(outside, filepath.Join(m.managedRoot(), "linked")); err != nil {
		t.Fatal(err)
	}
	writeFiles(t, outside, map[string]string{"manifest.json": `{"manifest_version":3,"name":"Linked","id":"linked"}`})

	if err := m.remove("linky", false); err != nil {
		t.Fatal(err)
	}
	if err := m.remove("linked", false); err == nil {
		t.Fatal("removed a symlinked plugin folder")
	}
	if data, err := os.ReadFile(filepath.Join(outside, "precious.txt")); err != nil || string(data) != "keep me" {
		t.Fatalf("files behind a symlink were deleted: %v", err)
	}
	if _, err := os.Lstat(plugin); !os.IsNotExist(err) {
		t.Fatal("plugin folder still exists")
	}
	if _, err := os.ReadFile(filepath.Join(outside, "manifest.json")); err != nil {
		t.Fatal("files behind the linked plugin folder were deleted")
	}
}

func TestExtractRejectsUnsafeEntries(t *testing.T) {
	for _, name := range []string{"../evil.txt", "/abs.txt", "C:/x.txt", "a/../../b"} {
		if _, err := extractZip(zipOf(t, map[string]string{"manifest.json": skillsManifest, name: "x"}), t.TempDir()); err == nil {
			t.Fatalf("accepted %s", name)
		}
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	h := &zip.FileHeader{Name: "link"}
	h.SetMode(os.ModeSymlink | 0o777)
	w, _ := zw.CreateHeader(h)
	w.Write([]byte("/sdcard"))
	zw.Close()
	p := filepath.Join(t.TempDir(), "s.zip")
	os.WriteFile(p, buf.Bytes(), 0o644)
	if _, err := extractZip(p, t.TempDir()); err == nil {
		t.Fatal("accepted a symlink entry")
	}
}

func TestValidatePackage(t *testing.T) {
	dir := t.TempDir()
	writeFiles(t, dir, map[string]string{"manifest.json": `{"manifest_version":3,"name":"T","mcp_tools":[{"name":"a"}],"background":{"service_worker":"w.js"}}`})
	if _, err := validatePackage(dir); err == nil || !strings.Contains(err.Error(), "w.js") {
		t.Fatalf("missing worker accepted: %v", err)
	}
	writeFiles(t, dir, map[string]string{"w.js": ""})
	if _, err := validatePackage(dir); err != nil {
		t.Fatal(err)
	}
	empty := t.TempDir()
	writeFiles(t, empty, map[string]string{"manifest.json": `{"manifest_version":3,"name":"T"}`})
	if _, err := validatePackage(empty); err == nil {
		t.Fatal("plugin without tools or skills accepted")
	}
}

func TestExportLeavesOutSourceAndSymlinks(t *testing.T) {
	dir := t.TempDir()
	writeFiles(t, dir, map[string]string{"manifest.json": skillsManifest, "skills/a.md": "# A", sourceFile: `{"repo":"a/b","path":"yt"}`})
	os.Symlink(t.TempDir(), filepath.Join(dir, "outside"))
	var buf bytes.Buffer
	if err := writeZip(dir, &buf, func(rel string) bool { return rel == sourceFile }); err != nil {
		t.Fatal(err)
	}
	zr, _ := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
	}
	if strings.Join(names, ",") != "manifest.json,skills/a.md" {
		t.Fatalf("exported %v", names)
	}
}

func TestDescribeAndParseRepo(t *testing.T) {
	if title, desc := describe("---\nname: Search\ndescription: \"Find videos\"\n---\nBody", "s"); title != "Search" || desc != "Find videos" {
		t.Fatalf("frontmatter: %q %q", title, desc)
	}
	if title, desc := describe("# Upload a video\n\nUploads a **video** to [YouTube](https://y.t).\n\nMore", "u"); title != "Upload a video" || desc != "Uploads a video to YouTube." {
		t.Fatalf("heading: %q %q", title, desc)
	}
	cases := map[string]Repo{
		"me/plugins":                               {"me", "plugins", ""},
		"me/plugins@dev":                           {"me", "plugins", "dev"},
		"https://github.com/me/plugins.git":        {"me", "plugins", ""},
		"github.com/me/my.plugins/tree/feature/x/": {"me", "my.plugins", "feature/x"},
	}
	for in, want := range cases {
		if got, err := ParseRepo(in); err != nil || got != want {
			t.Fatalf("%s: %+v %v", in, got, err)
		}
	}
	for _, bad := range []string{"plugins", "me/plugins@bad ref", "me/plugins@../x", "https://gitlab.com/me/plugins"} {
		if _, err := ParseRepo(bad); err == nil {
			t.Fatalf("accepted %s", bad)
		}
	}
}

func TestHTTPInstallListExportRemove(t *testing.T) {
	m := newTestManager(t)
	srv := httptest.NewServer(m)
	defer srv.Close()
	zipData, _ := os.ReadFile(zipOf(t, map[string]string{"manifest.json": skillsManifest, "skills/upload.md": "# Upload\n\nUploads."}))

	resp, err := http.Post(srv.URL+"/api/custom-plugins/install?folder=yt", "application/zip", bytes.NewReader(zipData))
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("install: %v %v", err, resp.Status)
	}
	var installed struct {
		Plugin   Plugin
		Replaced bool
	}
	json.NewDecoder(resp.Body).Decode(&installed)
	if installed.Plugin.ID != "yt" || installed.Replaced {
		t.Fatalf("install result %+v", installed)
	}

	resp, _ = http.Get(srv.URL + "/api/custom-plugins/")
	var list struct{ Plugins []Plugin }
	json.NewDecoder(resp.Body).Decode(&list)
	if len(list.Plugins) != 1 || list.Plugins[0].Skills[0].Name != "upload" {
		t.Fatalf("list %+v", list)
	}

	resp, _ = http.Get(srv.URL + "/api/custom-plugins/export?id=yt")
	if resp.Header.Get("Content-Disposition") != `attachment; filename="yt-1.0.0.zip"` {
		t.Fatalf("export header %q", resp.Header.Get("Content-Disposition"))
	}
	body, _ := io.ReadAll(resp.Body)
	if zr, err := zip.NewReader(bytes.NewReader(body), int64(len(body))); err != nil || len(zr.File) != 2 {
		t.Fatalf("export zip: %v", err)
	}

	resp, _ = http.Post(srv.URL+"/api/custom-plugins/remove", "application/json", strings.NewReader(`{"id":"yt"}`))
	if resp.StatusCode != 200 {
		t.Fatalf("remove: %s", resp.Status)
	}
	if _, ok := m.find("yt"); ok {
		t.Fatal("still listed after remove")
	}
	resp, _ = http.Post(srv.URL+"/api/custom-plugins/install?folder=bad", "application/zip", strings.NewReader("not a zip"))
	if resp.StatusCode != 400 {
		t.Fatalf("bad ZIP accepted: %s", resp.Status)
	}
}

func TestDraftsCreateWritePackageInstall(t *testing.T) {
	m := newTestManager(t)
	files := []draftFile{
		{"manifest.json", `{"manifest_version":3,"id":"helper","name":"Helper","version":"0.1.0","mcp_tools":[{"name":"hello"}],"background":{"service_worker":"worker.js"}}`},
		{"worker.js", "// worker"},
	}
	created, err := m.createDraft("helper", files)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.createDraft("helper", files); err == nil {
		t.Fatal("create replaced an existing draft")
	}
	src := created["source_dir"].(string)
	if _, err := writeDraftFiles(src, []draftFile{{"skills/use.md", "# Use\n\nHow to use."}}); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{"../x", "/etc/x", "a//b", "C:/x"} {
		if _, err := writeDraftFiles(src, []draftFile{{bad, "x"}}); err == nil {
			t.Fatalf("wrote %s", bad)
		}
	}
	outside := t.TempDir()
	os.Symlink(outside, filepath.Join(src, "link"))
	if _, err := writeDraftFiles(src, []draftFile{{"link/evil.txt", "x"}}); err == nil {
		t.Fatal("wrote through a symlink")
	}
	os.Remove(filepath.Join(src, "link"))
	listing, err := m.readDraft("helper", "")
	raw, _ := json.Marshal(listing)
	var decoded struct{ Files []struct{ Path string } }
	json.Unmarshal(raw, &decoded)
	if err != nil || len(decoded.Files) != 3 || decoded.Files[0].Path != "manifest.json" {
		t.Fatalf("list: %s %v", raw, err)
	}
	if read, err := m.readDraft("helper", "skills/use.md"); err != nil || read["content"] != "# Use\n\nHow to use." {
		t.Fatalf("read: %v %v", read, err)
	}

	srv := httptest.NewServer(m)
	defer srv.Close()
	resp, _ := http.Post(srv.URL+"/api/custom-plugins/drafts/install", "application/json", strings.NewReader(`{"project_id":"helper","folder":"helper"}`))
	var installed struct{ Plugin Plugin }
	json.NewDecoder(resp.Body).Decode(&installed)
	if resp.StatusCode != 200 || installed.Plugin.ID != "helper" || installed.Plugin.SkillCount != 1 {
		t.Fatalf("draft install: %s %+v", resp.Status, installed)
	}
	if !fileExists(filepath.Join(filepath.Dir(src), "dist", "helper.zip")) {
		t.Fatal("dist ZIP missing")
	}
	resp, _ = http.Post(srv.URL+"/api/custom-plugins/drafts/install", "application/json", strings.NewReader(`{"zip_path":"/etc/passwd","folder":"x"}`))
	if resp.StatusCode != 400 {
		t.Fatalf("installed a ZIP outside home: %s", resp.Status)
	}
}
