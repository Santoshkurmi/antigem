package relocate

import (
	"bufio"
	"bytes"
	"compress/bzip2"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

const arMagic = "!<arch>\n"

type arMember struct {
	name   string
	offset int64
	size   int64
}

func readAr(f *os.File) ([]arMember, error) {
	magic := make([]byte, len(arMagic))
	if _, err := io.ReadFull(f, magic); err != nil || string(magic) != arMagic {
		return nil, fmt.Errorf("not a .deb (ar archive)")
	}
	var members []arMember
	pos := int64(len(arMagic))
	hdr := make([]byte, 60)
	for {
		if _, err := f.ReadAt(hdr, pos); err != nil {
			if err == io.EOF {
				return members, nil
			}
			return nil, err
		}
		size, err := strconv.ParseInt(strings.TrimSpace(string(hdr[48:58])), 10, 64)
		if err != nil || string(hdr[58:60]) != "`\n" {
			return nil, fmt.Errorf("bad ar member header")
		}
		name := strings.TrimSuffix(strings.TrimSpace(string(hdr[0:16])), "/")
		members = append(members, arMember{name: name, offset: pos + 60, size: size})
		pos += 60 + size + size%2
	}
}

func writeArHeader(w io.Writer, name string, size int64) error {
	_, err := fmt.Fprintf(w, "%-16s%-12d%-6d%-6d%-8s%-10d`\n", name, 0, 0, 0, "100644", size)
	return err
}

// decompressed opens a member's tar stream. xz and zstd go through the tools of the Linux system (native and fast:
// xz-utils is always installed, termux-tools depends on it); wait must be called once the stream was read.
func decompressed(f *os.File, m arMember) (r io.Reader, wait func() error, err error) {
	section := io.NewSectionReader(f, m.offset, m.size)
	noWait := func() error { return nil }
	switch {
	case strings.HasSuffix(m.name, ".tar"):
		return section, noWait, nil
	case strings.HasSuffix(m.name, ".gz"):
		gz, err := gzip.NewReader(bufio.NewReaderSize(section, 64*1024))
		if err != nil {
			return nil, nil, err
		}
		return gz, noWait, nil
	case strings.HasSuffix(m.name, ".bz2"):
		return bzip2.NewReader(bufio.NewReaderSize(section, 64*1024)), noWait, nil
	case strings.HasSuffix(m.name, ".xz"), strings.HasSuffix(m.name, ".lzma"), strings.HasSuffix(m.name, ".zst"):
		tool, args := "xz", []string{"-dc"}
		if strings.HasSuffix(m.name, ".lzma") {
			args = []string{"--format=lzma", "-dc"}
		}
		if strings.HasSuffix(m.name, ".zst") {
			tool, args = "zstd", []string{"-dcq"}
		}
		path, err := exec.LookPath(tool)
		if err != nil {
			if prefix := os.Getenv("PREFIX"); prefix != "" {
				path = filepath.Join(prefix, "bin", tool)
			}
			if _, statErr := os.Stat(path); statErr != nil {
				return nil, nil, fmt.Errorf("%s isn't installed (needed for %s)", tool, m.name)
			}
		}
		cmd := exec.Command(path, args...)
		cmd.Stdin = section
		var stderr bytes.Buffer
		cmd.Stderr = &stderr
		out, err := cmd.StdoutPipe()
		if err != nil {
			return nil, nil, err
		}
		if err := cmd.Start(); err != nil {
			return nil, nil, err
		}
		return bufio.NewReaderSize(out, 256*1024), func() error {
			if err := cmd.Wait(); err != nil {
				return fmt.Errorf("%s -d %s: %v %s", tool, m.name, err, strings.TrimSpace(stderr.String()))
			}
			return nil
		}, nil
	}
	return nil, nil, fmt.Errorf("unknown compression of %s", m.name)
}

// rewriteMember rewrites one tar member of the .deb into out, gzip-compressed (level 1: fast, and far smaller than an
// uncompressed tar on the phone's storage).
func rewriteMember(rw *rewriter, f *os.File, m arMember, out io.Writer, md5sums map[string]string) (tarResult, error) {
	r, wait, err := decompressed(f, m)
	if err != nil {
		return tarResult{}, err
	}
	gz, _ := gzip.NewWriterLevel(out, gzip.BestSpeed)
	bw := bufio.NewWriterSize(gz, 256*1024)
	res, err := rw.rewriteTar(bw, r, md5sums)
	if err != nil {
		io.Copy(io.Discard, r) // let the decompressor finish
		wait()
		return res, err
	}
	// whatever follows the tar's end blocks (decompressor padding) was copied by rewriteTar; make sure all was read
	if _, err := io.Copy(io.Discard, r); err != nil {
		return res, err
	}
	if err := wait(); err != nil {
		return res, err
	}
	if err := bw.Flush(); err != nil {
		return res, err
	}
	return res, gz.Close()
}

func memberBase(name string) string {
	if i := strings.Index(name, ".tar"); i >= 0 {
		return name[:i]
	}
	return name
}

// relocateDeb rewrites the .deb at path in place (via a new file renamed over it). False: it had nothing to rewrite
// and was left as it is.
func relocateDeb(path string, rw *rewriter) (bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()
	members, err := readAr(f)
	if err != nil {
		return false, err
	}
	var control, data *arMember
	for i := range members {
		switch memberBase(members[i].name) {
		case "control":
			control = &members[i]
		case "data":
			data = &members[i]
		}
	}
	if control == nil || data == nil {
		return false, fmt.Errorf("no control or data archive in it")
	}

	dir := filepath.Dir(path)
	dataTmp, err := os.CreateTemp(dir, ".antigem-data-*")
	if err != nil {
		return false, err
	}
	defer os.Remove(dataTmp.Name())
	defer dataTmp.Close()

	// data first: the control archive's md5sums need the new files' sums
	dataRes, err := rewriteMember(rw, f, *data, dataTmp, nil)
	if err != nil {
		return false, fmt.Errorf("data: %w", err)
	}
	var controlBuf bytes.Buffer
	controlRes, err := rewriteMember(rw, f, *control, &controlBuf, dataRes.md5s)
	if err != nil {
		return false, fmt.Errorf("control: %w", err)
	}
	if !dataRes.changed && !controlRes.changed {
		return false, nil
	}

	dataSize, err := dataTmp.Seek(0, io.SeekCurrent)
	if err != nil {
		return false, err
	}
	out, err := os.CreateTemp(dir, ".antigem-deb-*")
	if err != nil {
		return false, err
	}
	outName := out.Name()
	ok := false
	defer func() {
		if !ok {
			out.Close()
			os.Remove(outName)
		}
	}()
	w := bufio.NewWriterSize(out, 256*1024)
	if _, err := w.WriteString(arMagic); err != nil {
		return false, err
	}
	for _, m := range members {
		var err error
		switch {
		case memberBase(m.name) == "control":
			err = writeArMember(w, "control.tar.gz", int64(controlBuf.Len()), &controlBuf)
		case memberBase(m.name) == "data":
			if _, err = dataTmp.Seek(0, io.SeekStart); err == nil {
				err = writeArMember(w, "data.tar.gz", dataSize, dataTmp)
			}
		default: // debian-binary, and any other member, as they are
			err = writeArMember(w, m.name, m.size, io.NewSectionReader(f, m.offset, m.size))
		}
		if err != nil {
			return false, err
		}
	}
	if err := w.Flush(); err != nil {
		return false, err
	}
	if st, err := f.Stat(); err == nil {
		out.Chmod(st.Mode().Perm())
	}
	if err := out.Close(); err != nil {
		return false, err
	}
	if err := os.Rename(outName, path); err != nil {
		return false, err
	}
	ok = true
	return true, nil
}

func writeArMember(w io.Writer, name string, size int64, r io.Reader) error {
	if err := writeArHeader(w, name, size); err != nil {
		return err
	}
	if _, err := io.CopyN(w, r, size); err != nil {
		return err
	}
	if size%2 == 1 {
		_, err := w.Write([]byte{'\n'})
		return err
	}
	return nil
}
