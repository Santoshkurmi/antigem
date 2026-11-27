package security

import (
	"os"
	"path/filepath"
	"testing"
)

func writeBin(t *testing.T, parts ...string) string {
	t.Helper()
	data := []byte("\x7fELF padding ")
	for _, p := range parts {
		data = append(data, []byte(p+" more bytes x-agent here ")...)
	}
	path := filepath.Join(t.TempDir(), "agy")
	if err := os.WriteFile(path, data, 0o700); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestAlreadyPatched(t *testing.T) {
	ours := BuildFramedHeader("abcdefghijkl")
	cases := []struct {
		name  string
		parts []string
		want  bool
	}{
		{"ours only", []string{ours, ours}, true},
		{"standard header", []string{DefaultHeader}, false},
		{"ours and standard", []string{ours, DefaultHeader}, false},
		{"another token", []string{BuildFramedHeader("zzzzzzzzzzzz")}, false},
		{"no header", []string{"nothing"}, false},
	}
	for _, c := range cases {
		p := writeBin(t, c.parts...)
		fi, _ := os.Stat(p)
		if got := alreadyPatched(p, int(fi.Size()), []byte(ours)); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}

// A file already patched for the token is left alone (no write), and patching the standard header still works.
func TestPatchAgyHeaderSkipsWriteWhenPatched(t *testing.T) {
	p := writeBin(t, DefaultHeader, DefaultHeader)
	if _, _, err := PatchAgyHeader(p, "abcdefghijkl"); err != nil {
		t.Fatal(err)
	}
	fi, _ := os.Stat(p)
	if !alreadyPatched(p, int(fi.Size()), []byte(BuildFramedHeader("abcdefghijkl"))) {
		t.Fatal("not patched")
	}
	// read-only file: a second patch must succeed without opening it for writing
	if err := os.Chmod(p, 0o500); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(p, 0o700)
	if _, _, err := PatchAgyHeader(p, "abcdefghijkl"); err != nil {
		t.Fatalf("second patch opened the file for writing: %v", err)
	}
}
