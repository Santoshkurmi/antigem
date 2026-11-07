package handlers

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"

	"gemini-server/pkg/config"
)

func fsTestHandler(t *testing.T) (*Handler, string) {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", filepath.Join(dir, "home"))
	_ = os.MkdirAll(filepath.Join(dir, "home"), 0755)
	return &Handler{Cfg: &config.Config{ProjectsBaseDir: filepath.Join(dir, "home", "projects")}}, dir
}

func fsCall(t *testing.T, fn http.HandlerFunc, method, target string, body interface{}) map[string]interface{} {
	t.Helper()
	var rd *bytes.Reader
	if b, ok := body.([]byte); ok {
		rd = bytes.NewReader(b)
	} else {
		raw, _ := json.Marshal(body)
		rd = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, target, rd)
	rec := httptest.NewRecorder()
	fn(rec, req)
	out := map[string]interface{}{"_status": float64(rec.Code)}
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return out
}

func write(t *testing.T, p, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0644); err != nil {
		t.Fatal(err)
	}
}

func TestFsTransferConflictsAndMove(t *testing.T) {
	h, dir := fsTestHandler(t)
	src := filepath.Join(dir, "a", "note.txt")
	write(t, src, "one")
	dstDir := filepath.Join(dir, "b")
	write(t, filepath.Join(dstDir, "note.txt"), "existing")

	res := fsCall(t, h.FsTransferHandler, "POST", "/", fsTransferReq{Sources: []string{src}, TargetDir: dstDir})
	if res["success"] != true {
		t.Fatalf("copy failed: %v", res)
	}
	if b, _ := os.ReadFile(filepath.Join(dstDir, "note (1).txt")); string(b) != "one" {
		t.Fatalf("expected renamed copy, got %q", b)
	}
	fsCall(t, h.FsTransferHandler, "POST", "/", fsTransferReq{Sources: []string{src}, TargetDir: dstDir, Conflict: "overwrite"})
	if b, _ := os.ReadFile(filepath.Join(dstDir, "note.txt")); string(b) != "one" {
		t.Fatalf("expected overwrite, got %q", b)
	}
	// duplicate in place
	fsCall(t, h.FsTransferHandler, "POST", "/", fsTransferReq{Sources: []string{src}, TargetDir: filepath.Dir(src)})
	if _, err := os.Stat(filepath.Join(dir, "a", "note (1).txt")); err != nil {
		t.Fatalf("duplicate missing: %v", err)
	}
	// folder into itself is refused
	res = fsCall(t, h.FsTransferHandler, "POST", "/", fsTransferReq{Sources: []string{filepath.Join(dir, "a")}, TargetDir: filepath.Join(dir, "a"), Move: true})
	if res["success"] != false {
		t.Fatalf("moving a folder into itself must fail: %v", res)
	}
	// move
	res = fsCall(t, h.FsTransferHandler, "POST", "/", fsTransferReq{Sources: []string{filepath.Join(dir, "a")}, TargetDir: dstDir, Move: true})
	if res["success"] != true {
		t.Fatalf("move failed: %v", res)
	}
	if _, err := os.Stat(filepath.Join(dir, "a")); !os.IsNotExist(err) {
		t.Fatalf("source still exists after move")
	}
}

func TestFsDeleteProtected(t *testing.T) {
	h, dir := fsTestHandler(t)
	f := filepath.Join(dir, "home", "x", "y.txt")
	write(t, f, "y")
	res := fsCall(t, h.FsDeleteHandler, "POST", "/", fsPathsReq{Paths: []string{"~", "/", "", filepath.Join(dir, "home", "x")}})
	if res["failed"].(float64) != 3 {
		t.Fatalf("expected 3 refusals, got %v", res)
	}
	if _, err := os.Stat(filepath.Join(dir, "home")); err != nil {
		t.Fatalf("home was deleted")
	}
	if _, err := os.Stat(filepath.Join(dir, "home", "x")); !os.IsNotExist(err) {
		t.Fatalf("x not deleted")
	}
}

func TestFsCompressExtractRoundTrip(t *testing.T) {
	h, dir := fsTestHandler(t)
	write(t, filepath.Join(dir, "proj", "src", "main.go"), "package main")
	write(t, filepath.Join(dir, "proj", "README.md"), "# hi")
	res := fsCall(t, h.FsCompressHandler, "POST", "/", fsCompressReq{Sources: []string{filepath.Join(dir, "proj")}, Target: filepath.Join(dir, "out")})
	if res["success"] != true {
		t.Fatalf("compress failed: %v", res)
	}
	zipPath := res["path"].(string)
	res = fsCall(t, h.FsExtractHandler, "POST", "/", fsExtractReq{Path: zipPath})
	if res["success"] != true {
		t.Fatalf("extract failed: %v", res)
	}
	if b, _ := os.ReadFile(filepath.Join(res["targetDir"].(string), "proj", "src", "main.go")); string(b) != "package main" {
		t.Fatalf("round trip content mismatch: %q", b)
	}
	list := fsCall(t, h.FsArchiveListHandler, "GET", "/?path="+url.QueryEscape(zipPath), nil)
	if len(list["entries"].([]interface{})) < 3 {
		t.Fatalf("archive list too short: %v", list)
	}
}

func TestFsExtractZipSlip(t *testing.T) {
	h, dir := fsTestHandler(t)
	zp := filepath.Join(dir, "evil.zip")
	f, _ := os.Create(zp)
	zw := zip.NewWriter(f)
	w, _ := zw.Create("../../escaped.txt")
	w.Write([]byte("x"))
	zw.Close()
	f.Close()
	res := fsCall(t, h.FsExtractHandler, "POST", "/", fsExtractReq{Path: zp})
	if res["success"] != false {
		t.Fatalf("zip slip must be refused: %v", res)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dir), "escaped.txt")); err == nil {
		t.Fatalf("file escaped the target folder")
	}
}

func TestFsNewRenameUploadRaw(t *testing.T) {
	h, dir := fsTestHandler(t)
	res := fsCall(t, h.FsNewHandler, "POST", "/", fsNewReq{Dir: dir, Name: "a.txt"})
	if res["success"] != true {
		t.Fatalf("new failed: %v", res)
	}
	res = fsCall(t, h.FsNewHandler, "POST", "/", fsNewReq{Dir: dir, Name: "a.txt"})
	if res["_status"].(float64) != 409 {
		t.Fatalf("existing file must give 409: %v", res)
	}
	write(t, filepath.Join(dir, "b.txt"), "b")
	res = fsCall(t, h.FsRenameHandler, "POST", "/", fsRenameReq{Path: filepath.Join(dir, "a.txt"), NewName: "b.txt"})
	if res["_status"].(float64) != 409 {
		t.Fatalf("rename onto existing must give 409: %v", res)
	}
	res = fsCall(t, h.FsRenameHandler, "POST", "/", fsRenameReq{Path: filepath.Join(dir, "a.txt"), NewName: "../c.txt"})
	if res["_status"].(float64) != 400 {
		t.Fatalf("slash in name must give 400: %v", res)
	}

	target := filepath.Join(dir, "up", "video.mp4")
	res = fsCall(t, h.FsUploadHandler, "POST", "/?path="+url.QueryEscape(target), []byte("0123456789"))
	if res["success"] != true || res["path"] != target {
		t.Fatalf("upload failed: %v", res)
	}
	res = fsCall(t, h.FsUploadHandler, "POST", "/?path="+url.QueryEscape(target), []byte("abc"))
	if res["path"] != filepath.Join(dir, "up", "video (1).mp4") {
		t.Fatalf("second upload should be renamed: %v", res)
	}

	req := httptest.NewRequest("GET", "/?path="+url.QueryEscape(target), nil)
	req.Header.Set("Range", "bytes=2-4")
	rec := httptest.NewRecorder()
	h.FsRawHandler(rec, req)
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "234" {
		t.Fatalf("range read wrong: %d %q", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "video/mp4" {
		t.Logf("content type: %q", ct)
	}

	st := fsCall(t, h.FsStatHandler, "GET", "/?path="+url.QueryEscape(filepath.Join(dir, "up")), nil)
	if st["files"].(float64) != 2 || st["size"].(float64) != 13 {
		t.Fatalf("stat wrong: %v", st)
	}
	found := fsCall(t, h.FsSearchHandler, "GET", "/?q=VIDEO&dir="+url.QueryEscape(dir), nil)
	if len(found["items"].([]interface{})) != 2 {
		t.Fatalf("search wrong: %v", found)
	}
}
