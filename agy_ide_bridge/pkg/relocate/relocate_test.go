package relocate

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/md5"
	"encoding/hex"
	"io"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func testRewriter() *rewriter {
	return newRewriter("com.termux", "ai.antigem", []string{".termuxam", ".x11."}, []string{"apk", "jar", "zip"})
}

// reference: the same rule on a whole buffer, the simple way
func referenceRewrite(b []byte) []byte {
	out := append([]byte(nil), b...)
	for i := 0; i+10 <= len(out); {
		if string(out[i:i+10]) == "com.termux" {
			rest := string(out[i+10:])
			if !strings.HasPrefix(rest, ".termuxam") && !strings.HasPrefix(rest, ".x11.") {
				copy(out[i:], "ai.antigem")
			}
			i += 10
		} else {
			i++
		}
	}
	return out
}

func TestCopyRewrittenAgreesWithReference(t *testing.T) {
	rw := testRewriter()
	rnd := rand.New(rand.NewSource(1))
	pieces := []string{"com.termux", "/com.termux/", "com.termux.termuxam.Am", "com.termux.x11.Loader", "com.termux.x11", "com.termu", "x"}
	for round := 0; round < 40; round++ {
		size := rnd.Intn(3*chunkSize) + 1
		b := make([]byte, size)
		for i := range b {
			b[i] = byte('a' + rnd.Intn(3))
		}
		for k := 0; k < 300; k++ {
			p := pieces[rnd.Intn(len(pieces))]
			at := rnd.Intn(size)
			if rnd.Intn(2) == 0 { // near chunk edges
				at = (rnd.Intn(3)+1)*chunkSize + rnd.Intn(40) - 20
			}
			if at >= 0 && at+len(p) <= size {
				copy(b[at:], p)
			}
		}
		var out bytes.Buffer
		h := md5.New()
		if _, err := rw.copyRewritten(&out, bytes.NewReader(b), int64(len(b)), h, nil); err != nil {
			t.Fatal(err)
		}
		want := referenceRewrite(b)
		if !bytes.Equal(out.Bytes(), want) {
			t.Fatalf("round %d (size %d): differs from the reference", round, size)
		}
		if got, exp := hex.EncodeToString(h.Sum(nil)), md5sum(want); got != exp {
			t.Fatalf("round %d: md5 of written bytes %s, want %s", round, got, exp)
		}
	}
}

func md5sum(b []byte) string {
	s := md5.Sum(b)
	return hex.EncodeToString(s[:])
}

type entry struct {
	hdr     tar.Header
	content string
}

func buildTar(t *testing.T, format tar.Format, entries []entry) []byte {
	var b bytes.Buffer
	tw := tar.NewWriter(&b)
	for _, e := range entries {
		h := e.hdr
		h.Size = int64(len(e.content))
		h.Format = format
		if err := tw.WriteHeader(&h); err != nil {
			t.Fatal(err)
		}
		io.WriteString(tw, e.content)
	}
	tw.Close()
	return b.Bytes()
}

func readTar(t *testing.T, b []byte) map[string]entry {
	got := map[string]entry{}
	tr := tar.NewReader(bytes.NewReader(b))
	for {
		h, err := tr.Next()
		if err == io.EOF {
			return got
		}
		if err != nil {
			t.Fatalf("rewritten tar doesn't read back: %v", err)
		}
		c, _ := io.ReadAll(tr)
		got[h.Name] = entry{hdr: *h, content: string(c)}
	}
}

func TestRewriteTar(t *testing.T) {
	long := "./data/data/com.termux/files/usr/lib/" + strings.Repeat("deep/", 30) + "com.termux.file.txt"
	big := strings.Repeat("x", chunkSize-4) + "/data/data/com.termux/files" + strings.Repeat("y", chunkSize) + "com.termux.termuxam"
	for _, format := range []tar.Format{tar.FormatGNU, tar.FormatPAX} {
		in := buildTar(t, format, []entry{
			{hdr: tar.Header{Name: "./data/data/com.termux/files/usr/bin/", Typeflag: tar.TypeDir, Mode: 0755}},
			{hdr: tar.Header{Name: "./data/data/com.termux/files/usr/bin/am", Typeflag: tar.TypeReg, Mode: 0755},
				content: "#!/data/data/com.termux/files/usr/bin/sh\nexec app_process / com.termux.termuxam.Am\n"},
			{hdr: tar.Header{Name: "./data/data/com.termux/files/usr/bin/sh", Typeflag: tar.TypeSymlink, Linkname: "/data/data/com.termux/files/usr/bin/dash"}},
			{hdr: tar.Header{Name: long, Typeflag: tar.TypeReg, Mode: 0644}, content: "com.termux"},
			{hdr: tar.Header{Name: "./data/data/com.termux/files/usr/share/x.apk", Typeflag: tar.TypeReg, Mode: 0644}, content: "com.termux in a zip"},
			{hdr: tar.Header{Name: "./data/data/com.termux/files/usr/lib/big", Typeflag: tar.TypeReg, Mode: 0644}, content: big},
		})
		var out bytes.Buffer
		res, err := testRewriter().rewriteTar(&out, bytes.NewReader(in), nil)
		if err != nil {
			t.Fatal(err)
		}
		if !res.changed {
			t.Fatal("not changed")
		}
		got := readTar(t, out.Bytes())
		if _, ok := got["./data/data/ai.antigem/files/usr/bin/"]; !ok {
			t.Fatalf("%v: directory not renamed: %v", format, keys(got))
		}
		am := got["./data/data/ai.antigem/files/usr/bin/am"].content
		if am != "#!/data/data/ai.antigem/files/usr/bin/sh\nexec app_process / com.termux.termuxam.Am\n" {
			t.Fatalf("%v: am = %q", format, am)
		}
		if l := got["./data/data/ai.antigem/files/usr/bin/sh"].hdr.Linkname; l != "/data/data/ai.antigem/files/usr/bin/dash" {
			t.Fatalf("%v: link target %q", format, l)
		}
		newLong := strings.ReplaceAll(long, "com.termux", "ai.antigem")
		if e, ok := got[newLong]; !ok || e.content != "ai.antigem" {
			t.Fatalf("%v: long name entry missing or wrong: %v", format, keys(got))
		}
		if c := got["./data/data/ai.antigem/files/usr/share/x.apk"].content; c != "com.termux in a zip" {
			t.Fatalf("%v: skipped extension rewritten: %q", format, c)
		}
		if c := got["./data/data/ai.antigem/files/usr/lib/big"].content; c != string(referenceRewrite([]byte(big))) {
			t.Fatalf("%v: big file wrong", format)
		}
		if res.md5s["data/data/ai.antigem/files/usr/bin/am"] != md5sum([]byte(am)) {
			t.Fatalf("%v: md5 of am wrong", format)
		}
	}
}

func keys(m map[string]entry) []string {
	var k []string
	for n := range m {
		k = append(k, n)
	}
	return k
}

func TestReplaceSums(t *testing.T) {
	c := []byte(strings.Repeat("0", 32) + "  data/data/ai.antigem/files/usr/bin/am\n" + strings.Repeat("1", 32) + "  other\n")
	replaceSums(c, map[string]string{"data/data/ai.antigem/files/usr/bin/am": strings.Repeat("a", 32)})
	if string(c) != strings.Repeat("a", 32)+"  data/data/ai.antigem/files/usr/bin/am\n"+strings.Repeat("1", 32)+"  other\n" {
		t.Fatalf("got %q", c)
	}
}

// A whole .deb: control.tar.gz + data.tar.xz (xz from the PATH), rewritten, then read back.
func TestRelocateDeb(t *testing.T) {
	if _, err := exec.LookPath("xz"); err != nil {
		t.Skip("xz not installed")
	}
	dir := t.TempDir()
	amContent := "#!/data/data/com.termux/files/usr/bin/sh\nexec app_process / com.termux.termuxam.Am \"$@\"\n"
	data := buildTar(t, tar.FormatGNU, []entry{
		{hdr: tar.Header{Name: "./", Typeflag: tar.TypeDir, Mode: 0755}},
		{hdr: tar.Header{Name: "./data/data/com.termux/files/usr/bin/am", Typeflag: tar.TypeReg, Mode: 0755}, content: amContent},
	})
	xzCmd := exec.Command("xz", "-c")
	xzCmd.Stdin = bytes.NewReader(data)
	dataXz, err := xzCmd.Output()
	if err != nil {
		t.Fatal(err)
	}
	control := buildTar(t, tar.FormatGNU, []entry{
		{hdr: tar.Header{Name: "./control", Typeflag: tar.TypeReg, Mode: 0644}, content: "Package: termux-am\nVersion: 0.8.1\n"},
		{hdr: tar.Header{Name: "./md5sums", Typeflag: tar.TypeReg, Mode: 0644}, content: md5sum([]byte(amContent)) + "  data/data/com.termux/files/usr/bin/am\n"},
		{hdr: tar.Header{Name: "./postinst", Typeflag: tar.TypeReg, Mode: 0755}, content: "#!/data/data/com.termux/files/usr/bin/sh\nchmod 400 /data/data/com.termux/files/usr/libexec/termux-am/am.apk\n"},
	})
	var controlGz bytes.Buffer
	gz := gzip.NewWriter(&controlGz)
	gz.Write(control)
	gz.Close()

	var deb bytes.Buffer
	deb.WriteString(arMagic)
	writeArMember(&deb, "debian-binary", 4, strings.NewReader("2.0\n"))
	writeArMember(&deb, "control.tar.gz", int64(controlGz.Len()), bytes.NewReader(controlGz.Bytes()))
	writeArMember(&deb, "data.tar.xz", int64(len(dataXz)), bytes.NewReader(dataXz))
	path := filepath.Join(dir, "termux-am_0.8.1_all.deb")
	os.WriteFile(path, deb.Bytes(), 0644)

	changed, err := relocateDeb(path, testRewriter())
	if err != nil || !changed {
		t.Fatalf("changed=%v err=%v", changed, err)
	}
	f, _ := os.Open(path)
	defer f.Close()
	members, err := readAr(f)
	if err != nil {
		t.Fatal(err)
	}
	if len(members) != 3 || members[0].name != "debian-binary" || members[1].name != "control.tar.gz" || members[2].name != "data.tar.gz" {
		t.Fatalf("members %+v", members)
	}
	read := func(m arMember) map[string]entry {
		r, _ := gzip.NewReader(io.NewSectionReader(f, m.offset, m.size))
		b, _ := io.ReadAll(r)
		return readTar(t, b)
	}
	d := read(members[2])
	newAm := d["./data/data/ai.antigem/files/usr/bin/am"].content
	if newAm != strings.ReplaceAll(amContent, "/data/data/com.termux", "/data/data/ai.antigem") {
		t.Fatalf("am = %q", newAm)
	}
	c := read(members[1])
	if want := md5sum([]byte(newAm)) + "  data/data/ai.antigem/files/usr/bin/am\n"; c["./md5sums"].content != want {
		t.Fatalf("md5sums = %q, want %q", c["./md5sums"].content, want)
	}
	if !strings.Contains(c["./postinst"].content, "/data/data/ai.antigem/files/usr/libexec") {
		t.Fatalf("postinst = %q", c["./postinst"].content)
	}

	// a second run finds nothing to do and leaves the file alone
	before, _ := os.ReadFile(path)
	changed, err = relocateDeb(path, testRewriter())
	after, _ := os.ReadFile(path)
	if err != nil || changed || !bytes.Equal(before, after) {
		t.Fatalf("second run: changed=%v err=%v same=%v", changed, err, bytes.Equal(before, after))
	}
}
