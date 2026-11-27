package handlers

import (
	"os"
	"path/filepath"
	"testing"

	"gemini-server/pkg/security"
)

// The agy file is read in chunks: a header that straddles two chunks must be counted once, not twice or never.
func TestDebugScanHeadersAcrossChunks(t *testing.T) {
	token := "abcdefghijkl"
	ours := security.BuildFramedHeader(token)
	other := security.BuildFramedHeader("zzzz12345678")
	data := make([]byte, 2*debugScanChunk+100)
	for i := range data {
		data[i] = 'x'
	}
	put := func(at int, s string) { copy(data[at:], s) }
	put(10, ours)
	put(debugScanChunk-7, ours)                     // straddles the first chunk boundary
	put(debugScanChunk+security.HeaderLen-3, other) // straddles the carried-over bytes
	put(2*debugScanChunk-5, security.DefaultHeader)
	put(len(data)-security.HeaderLen, security.DefaultHeader) // ends the file
	path := filepath.Join(t.TempDir(), "agy")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	got := debugScanHeaders(path, token)
	if got.Error != "" {
		t.Fatal(got.Error)
	}
	if got.Ours != 2 || got.Default != 2 || len(got.Other) != 1 || got.Other[0].Count != 1 || got.Other[0].Token != "zzzz…" {
		t.Fatalf("got %+v", got)
	}
}

func TestDebugMaskerHidesSecrets(t *testing.T) {
	h := &Handler{SecToken: "abcdefghijkl"}
	mask := h.debugMasker("csrf-value-123")
	in := "x-agabcdefghijkl_9qx abcdefghijkl csrf-value-123"
	if out := mask(in); out != "<framed-header> <token> <csrf>" {
		t.Fatalf("got %q", out)
	}
	if out := mask("Open in your browser: http://localhost:1239/#t=54cabc12 and ?token=x&a=1"); out != "Open in your browser: http://localhost:1239/#t=<hidden> and ?token=<hidden>&a=1" {
		t.Fatalf("got %q", out)
	}
}
