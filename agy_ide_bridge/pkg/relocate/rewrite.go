package relocate

import (
	"bytes"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"strconv"
	"strings"
)

const chunkSize = 256 * 1024

// rewriter replaces every old with new (same length) in bytes, except where old is followed by a kept suffix.
type rewriter struct {
	old, new []byte
	keep     [][]byte
	maxKeep  int
	skipExt  map[string]bool
}

func newRewriter(from, to string, keep []string, skipExt []string) *rewriter {
	rw := &rewriter{old: []byte(from), new: []byte(to), skipExt: map[string]bool{}}
	for _, k := range keep {
		if k == "" {
			continue
		}
		rw.keep = append(rw.keep, []byte(k))
		if len(k) > rw.maxKeep {
			rw.maxKeep = len(k)
		}
	}
	for _, e := range skipExt {
		if e = strings.TrimSpace(strings.ToLower(e)); e != "" {
			rw.skipExt[e] = true
		}
	}
	return rw
}

func (rw *rewriter) keptAt(buf []byte, at int) bool {
	for _, k := range rw.keep {
		if at+len(k) <= len(buf) && bytes.Equal(buf[at:at+len(k)], k) {
			return true
		}
	}
	return false
}

// rewriteIn replaces in place in buf[:n] the matches starting at or before limit (the caller guarantees each has
// its whole match and lookahead inside buf[:n] unless n is the end of the data). It returns the index up to which
// buf is final, and whether something changed.
func (rw *rewriter) rewriteIn(buf []byte, n, limit int) (int, bool) {
	changed := false
	i := 0
	for i <= limit {
		j := bytes.Index(buf[i:n], rw.old)
		if j < 0 {
			break
		}
		j += i
		if j > limit {
			break
		}
		if !rw.keptAt(buf[:n], j+len(rw.old)) {
			copy(buf[j:], rw.new)
			changed = true
		}
		i = j + len(rw.old)
	}
	done := limit + 1
	if i > done {
		done = i
	}
	if done < 0 {
		done = 0
	}
	if done > n {
		done = n
	}
	return done, changed
}

// rewriteAll rewrites a whole buffer in place.
func (rw *rewriter) rewriteAll(b []byte) bool {
	_, changed := rw.rewriteIn(b, len(b), len(b)-len(rw.old))
	return changed
}

// copyRewritten copies exactly n bytes from r to w, rewritten, feeding h (if not nil) with what is written.
func (rw *rewriter) copyRewritten(w io.Writer, r io.Reader, n int64, h hash.Hash, buf []byte) (bool, error) {
	look := len(rw.old) - 1 + rw.maxKeep
	if len(buf) < chunkSize+look {
		buf = make([]byte, chunkSize+look)
	}
	filled := 0
	remaining := n
	changed := false
	for {
		for filled < len(buf) && remaining > 0 {
			want := len(buf) - filled
			if int64(want) > remaining {
				want = int(remaining)
			}
			m, err := io.ReadFull(r, buf[filled:filled+want])
			filled += m
			remaining -= int64(m)
			if err != nil {
				return changed, err
			}
		}
		eof := remaining == 0
		limit := filled - len(rw.old)
		if !eof {
			limit -= rw.maxKeep
		}
		done, c := rw.rewriteIn(buf, filled, limit)
		changed = changed || c
		if eof {
			done = filled
		}
		if done > 0 {
			if _, err := w.Write(buf[:done]); err != nil {
				return changed, err
			}
			if h != nil {
				h.Write(buf[:done])
			}
			copy(buf, buf[done:filled])
			filled -= done
		}
		if eof && filled == 0 {
			return changed, nil
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------- tar

// tarResult is what rewriting one tar stream found: whether anything changed, and the md5 of each regular file
// (by path without a leading "./"), as written.
type tarResult struct {
	changed bool
	md5s    map[string]string
}

func parseTarNumber(field []byte) (int64, error) {
	if len(field) > 0 && field[0]&0x80 != 0 { // base-256 (GNU, big files)
		var v int64
		for i, b := range field {
			if i == 0 {
				b &= 0x7f
			}
			v = v<<8 | int64(b)
		}
		return v, nil
	}
	s := strings.Trim(string(field), " \x00")
	if s == "" {
		return 0, nil
	}
	return strconv.ParseInt(s, 8, 64)
}

func cString(b []byte) string {
	if i := bytes.IndexByte(b, 0); i >= 0 {
		b = b[:i]
	}
	return string(b)
}

func setTarChecksum(h []byte) {
	sum := 0
	for i, b := range h {
		if i >= 148 && i < 156 {
			sum += ' '
		} else {
			sum += int(b)
		}
	}
	copy(h[148:156], fmt.Sprintf("%06o\x00 ", sum))
}

func normalize(name string) string {
	return strings.TrimPrefix(strings.TrimPrefix(name, "./"), "/")
}

// paxPath returns the path or linkpath record of a pax header block's content.
func paxValue(content []byte, key string) string {
	for _, line := range strings.Split(string(content), "\n") {
		if sp := strings.IndexByte(line, ' '); sp >= 0 {
			if kv := line[sp+1:]; strings.HasPrefix(kv, key+"=") {
				return kv[len(key)+1:]
			}
		}
	}
	return ""
}

// rewriteTar copies a tar stream from r to w, rewriting every header (names, link targets) and the contents of
// regular files (except skipped extensions) and of long-name / pax entries. [md5sums], when given, is the md5 of the
// data files: a "md5sums" entry (the control archive's) gets its sums replaced from it.
func (rw *rewriter) rewriteTar(w io.Writer, r io.Reader, md5sums map[string]string) (tarResult, error) {
	res := tarResult{md5s: map[string]string{}}
	hdr := make([]byte, 512)
	buf := make([]byte, chunkSize+len(rw.old)+rw.maxKeep)
	longName := ""
	paxName := ""
	for {
		if _, err := io.ReadFull(r, hdr); err != nil {
			if errors.Is(err, io.EOF) {
				return res, nil
			}
			return res, fmt.Errorf("tar header: %w", err)
		}
		if bytes.Count(hdr, []byte{0}) == 512 { // end of archive: the rest (zero blocks) as it is
			if _, err := w.Write(hdr); err != nil {
				return res, err
			}
			_, err := io.Copy(w, r)
			return res, err
		}
		if rw.rewriteAll(hdr) {
			res.changed = true
		}
		setTarChecksum(hdr)
		if _, err := w.Write(hdr); err != nil {
			return res, err
		}
		size, err := parseTarNumber(hdr[124:136])
		if err != nil {
			return res, fmt.Errorf("tar size: %w", err)
		}
		padded := (size + 511) / 512 * 512
		typ := hdr[156]

		name := cString(hdr[0:100])
		if string(hdr[257:262]) == "ustar" {
			if prefix := cString(hdr[345:500]); prefix != "" {
				name = prefix + "/" + name
			}
		}
		if longName != "" {
			name, longName = longName, ""
		}
		if paxName != "" {
			name, paxName = paxName, ""
		}

		switch typ {
		case 'L', 'K', 'x', 'g': // GNU long names, pax headers: small, rewritten whole
			if padded > 16*1024*1024 {
				return res, fmt.Errorf("tar: %c entry of %d bytes", typ, size)
			}
			content := make([]byte, padded)
			if _, err := io.ReadFull(r, content); err != nil {
				return res, err
			}
			if rw.rewriteAll(content[:size]) {
				res.changed = true
			}
			switch typ {
			case 'L':
				longName = cString(content[:size])
			case 'x':
				paxName = paxValue(content[:size], "path")
			}
			if _, err := w.Write(content); err != nil {
				return res, err
			}
			continue
		case '0', 0, '7':
			key := normalize(name)
			ext := ""
			if dot := strings.LastIndexByte(key, '.'); dot >= 0 && !strings.Contains(key[dot:], "/") {
				ext = strings.ToLower(key[dot+1:])
			}
			h := md5.New()
			if md5sums != nil && key == "md5sums" {
				content := make([]byte, size)
				if _, err := io.ReadFull(r, content); err != nil {
					return res, err
				}
				if rw.rewriteAll(content) {
					res.changed = true
				}
				replaceSums(content, md5sums)
				w.Write(content)
				h.Write(content)
			} else if rw.skipExt[ext] {
				if _, err := io.CopyN(io.MultiWriter(w, h), r, size); err != nil {
					return res, err
				}
			} else {
				changed, err := rw.copyRewritten(w, r, size, h, buf)
				if err != nil {
					return res, err
				}
				res.changed = res.changed || changed
			}
			res.md5s[key] = hex.EncodeToString(h.Sum(nil))
			if _, err := io.CopyN(w, r, padded-size); err != nil {
				return res, err
			}
		default: // directories, links, devices: no content normally; whatever there is, as it is
			if _, err := io.CopyN(w, r, padded); err != nil {
				return res, err
			}
		}
	}
}

// replaceSums puts the new md5 of each listed file into an md5sums file ("<32 hex>  <path>" lines), in place.
func replaceSums(content []byte, sums map[string]string) {
	start := 0
	for start < len(content) {
		end := bytes.IndexByte(content[start:], '\n')
		if end < 0 {
			end = len(content)
		} else {
			end += start
		}
		line := content[start:end]
		if len(line) > 34 && line[32] == ' ' && line[33] == ' ' {
			if sum, ok := sums[normalize(string(line[34:]))]; ok && len(sum) == 32 {
				copy(line[:32], sum)
			}
		}
		start = end + 1
	}
}
