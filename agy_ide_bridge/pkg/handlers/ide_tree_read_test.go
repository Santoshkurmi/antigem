package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gemini-server/pkg/models"
)

func treeLevel(t *testing.T, h *Handler, dir, etag string) (*httptest.ResponseRecorder, []models.FileNode) {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/tree?depth=1&dir="+url.QueryEscape(dir), nil)
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	rec := httptest.NewRecorder()
	h.FileTreeHandler(rec, req)
	var nodes []models.FileNode
	if rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &nodes); err != nil {
			t.Fatalf("bad JSON: %v", err)
		}
	}
	return rec, nodes
}

func TestFileTreeLevelListsOneFolderAndAnswers304(t *testing.T) {
	h, dir := fsTestHandler(t)
	proj := filepath.Join(dir, "proj")
	for _, d := range []string{".git/objects", "node_modules/pkg", "src/deep"} {
		_ = os.MkdirAll(filepath.Join(proj, d), 0755)
	}
	_ = os.WriteFile(filepath.Join(proj, "a.txt"), []byte("a"), 0644)
	_ = os.WriteFile(filepath.Join(proj, "src", "b.txt"), []byte("b"), 0644)
	old := time.Now().Add(-time.Minute)
	_ = os.Chtimes(proj, old, old)

	rec, nodes := treeLevel(t, h, proj, "")
	if rec.Code != http.StatusOK || rec.Header().Get("X-Tree-Depth") != "1" {
		t.Fatalf("status %d, depth header %q", rec.Code, rec.Header().Get("X-Tree-Depth"))
	}
	var names []string
	for _, n := range nodes {
		names = append(names, n.Name)
		if len(n.Children) != 0 {
			t.Fatalf("%s has children in a one-level reply", n.Name)
		}
	}
	// folders first, .git hidden, dependency folders shown in this mode
	if got := strings.Join(names, ","); got != "node_modules,src,a.txt" {
		t.Fatalf("entries = %s", got)
	}

	etag := rec.Header().Get("ETag")
	if rec2, _ := treeLevel(t, h, proj, etag); rec2.Code != http.StatusNotModified {
		t.Fatalf("unchanged folder: status %d, want 304", rec2.Code)
	}

	_ = os.WriteFile(filepath.Join(proj, "c.txt"), []byte("c"), 0644)
	rec3, nodes3 := treeLevel(t, h, proj, etag)
	if rec3.Code != http.StatusOK || len(nodes3) != 4 {
		t.Fatalf("after adding a file: status %d, %d entries", rec3.Code, len(nodes3))
	}
}

func TestFileTreeWholeTreeStillSkipsHeavyFolders(t *testing.T) {
	h, dir := fsTestHandler(t)
	proj := filepath.Join(dir, "proj")
	_ = os.MkdirAll(filepath.Join(proj, "node_modules", "pkg"), 0755)
	_ = os.MkdirAll(filepath.Join(proj, "src"), 0755)
	_ = os.WriteFile(filepath.Join(proj, "src", "b.txt"), []byte("b"), 0644)

	req := httptest.NewRequest(http.MethodGet, "/api/tree?dir="+url.QueryEscape(proj), nil)
	rec := httptest.NewRecorder()
	h.FileTreeHandler(rec, req)
	var nodes []models.FileNode
	_ = json.Unmarshal(rec.Body.Bytes(), &nodes)
	if len(nodes) != 1 || nodes[0].Name != "src" || len(nodes[0].Children) != 1 {
		t.Fatalf("whole tree = %+v", nodes)
	}
}

func readFile(t *testing.T, h *Handler, path, query string, header map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/file/read?path="+url.QueryEscape(path)+query, nil)
	for k, v := range header {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.FileReadHandler(rec, req)
	return rec
}

func TestFileReadStreamsAndRefusesAboveMax(t *testing.T) {
	h, dir := fsTestHandler(t)
	text := filepath.Join(dir, "notes.txt")
	_ = os.WriteFile(text, []byte(strings.Repeat("hello ", 2000)), 0644) // 12000 bytes
	bin := filepath.Join(dir, "prog")
	_ = os.WriteFile(bin, append([]byte{0x7f, 'E', 'L', 'F', 0, 0}, make([]byte, 5000)...), 0644)

	rec := readFile(t, h, text, "", nil)
	if rec.Code != http.StatusOK || rec.Body.Len() != 12000 || rec.Header().Get("X-File-Category") != "text" ||
		rec.Header().Get("X-File-Size") != "12000" {
		t.Fatalf("text: status %d, %d bytes, category %q, size %q", rec.Code, rec.Body.Len(),
			rec.Header().Get("X-File-Category"), rec.Header().Get("X-File-Size"))
	}

	rec = readFile(t, h, text, "&max=1000", nil)
	var tooBig map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &tooBig)
	if rec.Code != http.StatusRequestEntityTooLarge || tooBig["size"] != float64(12000) || tooBig["category"] != "text" {
		t.Fatalf("max: status %d, body %v", rec.Code, tooBig)
	}

	rec = readFile(t, h, bin, "&max=100000", nil)
	if rec.Code != http.StatusOK || rec.Header().Get("X-File-Category") != "binary" || rec.Body.Len() != 5006 {
		t.Fatalf("binary: status %d, category %q, %d bytes", rec.Code, rec.Header().Get("X-File-Category"), rec.Body.Len())
	}

	rec = readFile(t, h, text, "", map[string]string{"Range": "bytes=0-4"})
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "hello" {
		t.Fatalf("range: status %d, body %q", rec.Code, rec.Body.String())
	}

	if rec = readFile(t, h, dir, "", nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("folder: status %d", rec.Code)
	}
}

func TestFileReadUnsizedFile(t *testing.T) {
	h, dir := fsTestHandler(t)
	empty := filepath.Join(dir, "empty.txt")
	_ = os.WriteFile(empty, nil, 0644)
	rec := readFile(t, h, empty, "&max=10", nil)
	if rec.Code != http.StatusOK || rec.Body.Len() != 0 || rec.Header().Get("X-File-Category") != "text" {
		t.Fatalf("empty: status %d, %d bytes", rec.Code, rec.Body.Len())
	}
	// /proc files report size 0 but have content
	if _, err := os.Stat("/proc/self/status"); err == nil {
		rec = readFile(t, h, "/proc/self/status", "", nil)
		if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "Name:") {
			t.Fatalf("/proc: status %d, body %q", rec.Code, rec.Body.String())
		}
	}
}
