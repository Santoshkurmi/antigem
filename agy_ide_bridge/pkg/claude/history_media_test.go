package claude

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestScanLinesHandlesLongLinesAndOffsets(t *testing.T) {
	long := strings.Repeat("x", 3<<20) // longer than the reader's buffer
	data := "a\n" + long + "\r\n\nlast"
	var got []string
	var offs []int64
	if err := scanLines(strings.NewReader(data), func(off int64, line []byte) {
		got = append(got, string(line))
		offs = append(offs, off)
	}); err != nil {
		t.Fatal(err)
	}
	want := []string{"a", long, "", "last"}
	if len(got) != len(want) {
		t.Fatalf("got %d lines, want %d", len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("line %d differs (len %d vs %d)", i, len(got[i]), len(want[i]))
		}
		if data[offs[i]:offs[i]+int64(len(want[i]))] != want[i] {
			t.Fatalf("offset of line %d is wrong", i)
		}
	}
}

func TestHistoryChainAndSiblingResultsReadFromPositions(t *testing.T) {
	m := &Manager{HomeDir: t.TempDir(), sessions: map[string]*Session{}}
	sid := "11111111-2222-3333-4444-666666666666"
	proj := filepath.Join(m.ProjectsDir(), "-home-x")
	if err := os.MkdirAll(proj, 0o755); err != nil {
		t.Fatal(err)
	}
	lines := []string{
		`{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hi"}}`,
		`{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1"},{"type":"tool_use","id":"t2"}]}}`,
		`{"type":"user","uuid":"r1","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"one"}]}}`,
		`{"type":"user","uuid":"r2","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"two"}]}}`,
		`{"type":"progress","uuid":"p1","parentUuid":"r2"}`,
		`{"type":"assistant","uuid":"a2","parentUuid":"p1","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}`,
		`not json`,
	}
	if err := os.WriteFile(filepath.Join(proj, sid+".jsonl"), []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	h, err := m.History(sid, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, e := range h {
		var l struct {
			UUID string `json:"uuid"`
		}
		if json.Unmarshal(e, &l) != nil {
			t.Fatalf("entry is not JSON: %s", e)
		}
		ids = append(ids, l.UUID)
	}
	// the chain u1 → a1 → r2 → (p1) → a2, with r1 (a parallel tool result on a sibling branch) next to a1
	if got := strings.Join(ids, ","); got != "u1,a1,r1,r2,a2" {
		t.Fatalf("chain = %s", got)
	}
	cut := "a1"
	h, _ = m.History(sid, 0, &cut)
	if len(h) != 4 { // u1, a1 and the tool results next to a1 (r1, r2), as the full read always did
		t.Fatalf("cut history has %d entries", len(h))
	}
}

func TestHistoryMediaRefs(t *testing.T) {
	m := &Manager{HomeDir: t.TempDir(), sessions: map[string]*Session{}}
	sid := "11111111-2222-3333-4444-777777777777"
	png := []byte("\x89PNG fake picture")
	data := base64.StdEncoding.EncodeToString(png)

	plain := []byte(`{"type":"assistant","uuid":"a","message":{"content":[{"type":"text","text":"no pictures"}]}}`)
	if got := m.historyWithMediaRefs(sid, plain); !bytes.Equal(got, plain) {
		t.Fatalf("an entry without media must come back as it is: %s", got)
	}

	prompt := []byte(`{"type":"user","uuid":"u","message":{"role":"user","content":[{"type":"text","text":"look <b>"},` +
		`{"type":"image","source":{"type":"base64","media_type":"image/png","data":"` + data + `"}}]},"n":12345678901234567}`)
	out := m.historyWithMediaRefs(sid, prompt)
	if bytes.Contains(out, []byte(data)) {
		t.Fatalf("the prompt's picture is still inline: %s", out)
	}
	var entry struct {
		N       json.Number `json:"n"`
		Message struct {
			Content []struct {
				Type   string `json:"type"`
				Text   string `json:"text"`
				Source struct {
					Type      string `json:"type"`
					MediaType string `json:"media_type"`
					Path      string `json:"path"`
				} `json:"source"`
			} `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal(out, &entry); err != nil {
		t.Fatal(err)
	}
	if entry.N.String() != "12345678901234567" {
		t.Fatalf("numbers must stay exact: %s", entry.N)
	}
	if entry.Message.Content[0].Text != "look <b>" {
		t.Fatalf("text changed: %q", entry.Message.Content[0].Text)
	}
	src := entry.Message.Content[1].Source
	if src.Type != "path" || src.MediaType != "image/png" || !strings.HasPrefix(src.Path, filepath.Join(m.attachmentsRoot(), sid)+string(os.PathSeparator)) {
		t.Fatalf("bad saved source: %+v", src)
	}
	if b, err := os.ReadFile(src.Path); err != nil || !bytes.Equal(b, png) {
		t.Fatalf("saved file: %v", err)
	}
	// the same picture again: the same file
	if again := m.historyWithMediaRefs(sid, prompt); !bytes.Equal(again, out) {
		t.Fatalf("reading the history twice gives different entries")
	}

	result := []byte(`{"type":"user","uuid":"r","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":[` +
		`{"type":"text","text":"shot"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"` + data + `"}}]}]},` +
		`"toolUseResult":{"type":"image","file":{"base64":"` + data + `","type":"image/png","originalSize":17}}}`)
	out = m.historyWithMediaRefs(sid, result)
	if bytes.Contains(out, []byte(data)) {
		t.Fatalf("a tool result's picture is still inline: %s", out)
	}
	if !bytes.Contains(out, []byte(`"type":"omitted"`)) || !bytes.Contains(out, []byte(`"originalSize":17`)) {
		t.Fatalf("unexpected tool result entry: %s", out)
	}
}

func TestHistoryEndpointStreamsTheSameShape(t *testing.T) {
	m := &Manager{HomeDir: t.TempDir(), sessions: map[string]*Session{}}
	sid := "11111111-2222-3333-4444-888888888888"
	proj := filepath.Join(m.ProjectsDir(), "-home-x")
	if err := os.MkdirAll(proj, 0o755); err != nil {
		t.Fatal(err)
	}
	data := base64.StdEncoding.EncodeToString([]byte("picture"))
	transcript := `{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"` + data + `"}}]}}` + "\n" +
		`{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}` + "\n"
	if err := os.WriteFile(filepath.Join(proj, sid+".jsonl"), []byte(transcript), 0o600); err != nil {
		t.Fatal(err)
	}
	read := func(query string) (bool, []json.RawMessage, string) {
		rec := httptest.NewRecorder()
		m.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/claude/sessions/"+sid+"/history"+query, nil))
		var resp struct {
			Success bool              `json:"success"`
			Entries []json.RawMessage `json:"entries"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatalf("answer is not JSON: %v: %s", err, rec.Body.String())
		}
		return resp.Success, resp.Entries, rec.Body.String()
	}
	ok, entries, body := read("")
	if !ok || len(entries) != 2 || !strings.Contains(body, data) {
		t.Fatalf("plain history: ok=%v entries=%d", ok, len(entries))
	}
	ok, entries, body = read("?media=refs")
	if !ok || len(entries) != 2 || strings.Contains(body, data) || !strings.Contains(body, `"type":"path"`) {
		t.Fatalf("media=refs history: ok=%v entries=%d body=%s", ok, len(entries), body)
	}
}
