package service

import (
	"bufio"
	"bytes"
	"container/list"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/view"
)

// The GUI's body viewer pages through bodies of any size. A body is decoded once and kept (with the line index of
// its text and pretty forms, and the JSON tree) in a small LRU cache; big pretty texts live in temp files.

const (
	displayLineMax = 2000      // runes: longer lines are split into display lines of this length
	docMemMax      = 4 << 20   // a pretty text up to this size stays in memory, bigger ones go to a temp file
	prettyMax      = 256 << 20 // a pretty form bigger than this is not made
	treeMax        = 64 << 20
	searchCountMax = 100000
)

// ---- cache ----

type docCache struct {
	dir   string
	max   int64
	mu    sync.Mutex
	used  int64
	ll    *list.List // front = used last; values are *bodyDoc
	m     map[string]*list.Element
	clean sync.Once
}

func newDocCache(dir string, max int64) *docCache {
	return &docCache{dir: dir, max: max, ll: list.New(), m: map[string]*list.Element{}}
}

// bodyDoc is one decoded body and the views made from it so far. Its fields are guarded by mu.
type bodyDoc struct {
	key    string
	c      *docCache
	mu     sync.Mutex
	loaded bool
	closed bool
	err    error
	data   []byte // decoded body
	text   *textDoc
	pretty *textDoc
	tried  bool // the pretty form was attempted (pretty nil: none)
	tree   *jsonTree
	size   int64 // memory counted for the cache (guarded by c.mu)
}

func (c *docCache) get(key string) *bodyDoc {
	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.m[key]; ok {
		c.ll.MoveToFront(el)
		return el.Value.(*bodyDoc)
	}
	d := &bodyDoc{key: key, c: c}
	c.m[key] = c.ll.PushFront(d)
	return d
}

// grow counts n more bytes for d and evicts the least used other documents while the cache is over its size (d
// itself always stays, even when it alone is bigger).
func (c *docCache) grow(d *bodyDoc, n int64) {
	var evicted []*bodyDoc
	c.mu.Lock()
	el, ok := c.m[d.key]
	if ok && el.Value.(*bodyDoc) == d {
		d.size += n
		c.used += n
		for c.used > c.max {
			back := c.ll.Back()
			if back == nil || back == el {
				break
			}
			old := back.Value.(*bodyDoc)
			c.ll.Remove(back)
			delete(c.m, old.key)
			c.used -= old.size
			evicted = append(evicted, old)
		}
	}
	c.mu.Unlock()
	for _, old := range evicted {
		go old.close() // not here: the caller holds d.mu, and old's holder may be waiting on the cache
	}
}

func (c *docCache) closeAll() {
	c.mu.Lock()
	var all []*bodyDoc
	for el := c.ll.Front(); el != nil; el = el.Next() {
		all = append(all, el.Value.(*bodyDoc))
	}
	c.ll.Init()
	c.m = map[string]*list.Element{}
	c.used = 0
	c.mu.Unlock()
	for _, d := range all {
		d.close()
	}
	_ = os.RemoveAll(c.dir)
}

func (c *docCache) tempFile() (*os.File, error) {
	c.clean.Do(func() { _ = os.RemoveAll(c.dir) }) // files of an earlier run
	if err := os.MkdirAll(c.dir, 0o700); err != nil {
		return nil, err
	}
	return os.CreateTemp(c.dir, "v*")
}

func (d *bodyDoc) close() {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.closed = true
	for _, t := range []*textDoc{d.text, d.pretty} {
		if t != nil {
			t.close()
		}
	}
	d.data, d.text, d.pretty, d.tree = nil, nil, nil, nil
}

var errNoBody = errors.New("no body stored")

func bodyOf(f *flow.Flow, side string) *flow.Body {
	if side == "req" {
		return &f.Req
	}
	return &f.Res
}

// withDoc runs fn with the flow's decoded body document (loaded once, then cached).
func (s *Service) withDoc(sess *Session, f *flow.Flow, side string, fn func(d *bodyDoc) error) error {
	b := bodyOf(f, side)
	if b.Hash == "" {
		return errNoBody
	}
	key := sess.ID + "/" + b.Hash + "/" + b.Encoding + "/" + b.Type + "/" + strconv.FormatBool(b.Truncated)
	for try := 0; try < 3; try++ {
		d := s.docs.get(key)
		d.mu.Lock()
		if d.closed { // evicted meanwhile: get a fresh one
			d.mu.Unlock()
			continue
		}
		if !d.loaded {
			d.loaded = true
			d.data, _, d.err = s.Body(sess, f, side, false, 0)
			if d.err == nil {
				s.docs.grow(d, int64(len(d.data)))
			}
		}
		err := d.err
		if err == nil {
			err = fn(d)
		}
		d.mu.Unlock()
		return err
	}
	return errors.New("the body cache is busy: try again")
}

// textOf returns the text form (bad UTF-8 replaced), indexed by display line.
func (d *bodyDoc) textOf() *textDoc {
	if d.text == nil {
		src := d.data
		extra := int64(0)
		if !utf8.Valid(src) {
			src = []byte(strings.ToValidUTF8(string(src), "�"))
			extra = int64(len(src))
		}
		ix := &lineIndexer{}
		ix.Write(src)
		d.text = &textDoc{mem: src, size: int64(len(src)), lineIndexer: *ix}
		d.c.grow(d, extra+d.text.indexBytes())
	}
	return d.text
}

// prettyOf returns the pretty form, or nil when the type has none (or it would be too big).
func (d *bodyDoc) prettyOf(typ string) *textDoc {
	if d.tried {
		return d.pretty
	}
	d.tried = true
	if !view.HasPretty(typ) {
		return nil
	}
	src := d.textOf().mem
	sp := &spill{c: d.c, memMax: docMemMax, max: prettyMax}
	ix := &lineIndexer{}
	ok, err := view.PrettyTo(io.MultiWriter(ix, sp), src, typ)
	mem, file, ferr := sp.finish()
	if !ok || err != nil || ferr != nil {
		if file != nil {
			file.Close()
			os.Remove(file.Name())
		}
		return nil
	}
	d.pretty = &textDoc{mem: mem, file: file, size: ix.off, lineIndexer: *ix}
	d.c.grow(d, int64(len(mem))+d.pretty.indexBytes())
	return d.pretty
}

// spill keeps written bytes in memory up to memMax, then moves them to a temp file.
type spill struct {
	c      *docCache
	memMax int64
	max    int64
	n      int64
	buf    bytes.Buffer
	f      *os.File
	bw     *bufio.Writer
}

var errTooBig = errors.New("too big")

func (s *spill) Write(p []byte) (int, error) {
	if s.n+int64(len(p)) > s.max {
		return 0, errTooBig
	}
	s.n += int64(len(p))
	if s.f == nil && int64(s.buf.Len()+len(p)) <= s.memMax {
		return s.buf.Write(p)
	}
	if s.f == nil {
		f, err := s.c.tempFile()
		if err != nil {
			return 0, err
		}
		s.f, s.bw = f, bufio.NewWriterSize(f, 256<<10)
		if _, err := s.bw.Write(s.buf.Bytes()); err != nil {
			return 0, err
		}
		s.buf = bytes.Buffer{}
	}
	return s.bw.Write(p)
}

func (s *spill) finish() ([]byte, *os.File, error) {
	if s.f == nil {
		return s.buf.Bytes(), nil, nil
	}
	return nil, s.f, s.bw.Flush()
}

// ---- display lines ----

// lineIndexer records where each display line starts: at the start of the text, after every '\n', and after
// every displayLineMax runes of a longer line (a continuation line).
type lineIndexer struct {
	off     int64
	runes   int
	started bool
	newLine bool // the next byte starts a new line
	starts  []uint32
	cont    []uint64 // bit i: display line i continues line i-1
	lastNL  bool     // the text ends with '\n'
	wrapped bool     // some line was split
}

func (ix *lineIndexer) Write(p []byte) (int, error) {
	for i, c := range p {
		if !ix.started || ix.newLine {
			ix.add(ix.off+int64(i), false)
			ix.started, ix.newLine, ix.runes = true, false, 0
		}
		if c == '\n' {
			ix.newLine = true
			continue
		}
		if utf8.RuneStart(c) {
			if ix.runes >= displayLineMax {
				ix.add(ix.off+int64(i), true)
				ix.runes = 0
			}
			ix.runes++
		}
	}
	if len(p) > 0 {
		ix.off += int64(len(p))
		ix.lastNL = p[len(p)-1] == '\n'
	}
	return len(p), nil
}

func (ix *lineIndexer) add(pos int64, cont bool) {
	ix.starts = append(ix.starts, uint32(pos))
	if cont {
		i := len(ix.starts) - 1
		for len(ix.cont) <= i/64 {
			ix.cont = append(ix.cont, 0)
		}
		ix.cont[i/64] |= 1 << (i % 64)
		ix.wrapped = true
	}
}

func (ix *lineIndexer) isCont(i int) bool {
	return i/64 < len(ix.cont) && ix.cont[i/64]>>(i%64)&1 == 1
}

// textDoc is a text and its display lines, in memory or in a temp file.
type textDoc struct {
	mem  []byte
	file *os.File
	size int64
	lineIndexer
}

func (t *textDoc) total() int { return len(t.starts) }

func (t *textDoc) indexBytes() int64 { return int64(len(t.starts))*4 + int64(len(t.cont))*8 + 64 }

func (t *textDoc) close() {
	if t.file != nil {
		t.file.Close()
		os.Remove(t.file.Name())
		t.file = nil
	}
	t.mem = nil
}

// lineEnd is the byte offset after display line i (without its '\n').
func (t *textDoc) lineEnd(i int) int64 {
	if i+1 < len(t.starts) {
		e := int64(t.starts[i+1])
		if !t.isCont(i + 1) {
			e-- // the '\n'
		}
		return e
	}
	e := t.size
	if t.lastNL {
		e--
	}
	return e
}

func (t *textDoc) read(lo, hi int64) ([]byte, error) {
	if hi <= lo {
		return nil, nil
	}
	if t.file == nil {
		return t.mem[lo:hi], nil
	}
	buf := make([]byte, hi-lo)
	_, err := t.file.ReadAt(buf, lo)
	return buf, err
}

// lines returns display lines from..from+count and the indexes among them that continue the line before.
func (t *textDoc) lines(from, count int) ([]string, []int, error) {
	n := t.total()
	out, cont := []string{}, []int{}
	if from >= n || count <= 0 {
		return out, cont, nil
	}
	to := min(n, from+count)
	lo, hi := int64(t.starts[from]), t.lineEnd(to-1)
	buf, err := t.read(lo, hi)
	if err != nil {
		return nil, nil, err
	}
	for i := from; i < to; i++ {
		a, b := int64(t.starts[i])-lo, t.lineEnd(i)-lo
		line := buf[a:max(a, b)]
		if i+1 >= n || !t.isCont(i+1) {
			line = bytes.TrimSuffix(line, []byte("\r"))
		}
		out = append(out, string(line))
		if t.isCont(i) {
			cont = append(cont, i)
		}
	}
	return out, cont, nil
}

// eachLine calls fn for every text line (split at '\n') with its byte offset; fn returns false to stop.
func (t *textDoc) eachLine(fn func(line []byte, off int64) bool) error {
	if t.file == nil {
		for off := 0; off < len(t.mem); {
			end := len(t.mem)
			if k := bytes.IndexByte(t.mem[off:], '\n'); k >= 0 {
				end = off + k
			}
			if !fn(t.mem[off:end], int64(off)) {
				return nil
			}
			off = end + 1
		}
		return nil
	}
	r := bufio.NewReaderSize(io.NewSectionReader(t.file, 0, t.size), 256<<10)
	var off int64
	var long []byte
	for {
		part, err := r.ReadSlice('\n')
		if err == bufio.ErrBufferFull {
			long = append(long, part...)
			continue
		}
		line := part
		if long != nil {
			line = append(long, part...)
			long = nil
		}
		n := int64(len(line))
		line = bytes.TrimSuffix(line, []byte("\n"))
		if len(line) > 0 || n > 0 {
			if !fn(line, off) {
				return nil
			}
		}
		off += n
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
	}
}

// lineOf is the display line holding byte offset off.
func (t *textDoc) lineOf(off int64) int {
	return sort.Search(len(t.starts), func(i int) bool { return int64(t.starts[i]) > off }) - 1
}

func utf16Len(b []byte) int {
	n := 0
	for len(b) > 0 {
		r, size := utf8.DecodeRune(b)
		if r >= 0x10000 {
			n += 2
		} else {
			n++
		}
		b = b[size:]
	}
	return n
}

// ---- API ----

// UIBodyInfo describes one side's body for the detail screen.
type UIBodyInfo struct {
	Present     bool   `json:"present"`
	Size        int64  `json:"size"` // on the wire
	DecodedSize int64  `json:"decoded_size"`
	Stored      int64  `json:"stored"`
	Truncated   bool   `json:"truncated"`
	Type        string `json:"type"`
	ContentType string `json:"content_type"`
	Encoding    string `json:"encoding"`
	Text        bool   `json:"text"`
	Image       bool   `json:"image"`
	Lines       int    `json:"lines"`        // display lines of the text form (view=lines pretty=0), -1 = not text
	PrettyLines int    `json:"pretty_lines"` // display lines of the pretty form, -1 = no pretty form
}

// UIFlowDetail is a flow with what the viewer needs to know about its bodies.
type UIFlowDetail struct {
	Flow    *flow.Flow `json:"flow"`
	ReqBody UIBodyInfo `json:"req_body"`
	ResBody UIBodyInfo `json:"res_body"`
}

// UIFlow returns a flow and its body facts (the bodies are decoded and indexed now, so paging is quick later).
func (s *Service) UIFlow(session, id string) (*UIFlowDetail, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, err
	}
	return &UIFlowDetail{Flow: f, ReqBody: s.bodyInfo(sess, f, "req"), ResBody: s.bodyInfo(sess, f, "res")}, nil
}

func (s *Service) uiFlowOf(session, id string) (*Session, *flow.Flow, error) {
	sess, err := s.Session(session)
	if err != nil {
		return nil, nil, err
	}
	n, _, err := ParseID(id)
	if err != nil {
		return nil, nil, err
	}
	f, err := sess.Store.Get(n)
	if err != nil {
		return nil, nil, err
	}
	return sess, f, nil
}

func textType(typ string) bool {
	switch typ {
	case "image", "media", "font", "proto":
		return false
	}
	return true
}

func (s *Service) bodyInfo(sess *Session, f *flow.Flow, side string) UIBodyInfo {
	b := bodyOf(f, side)
	info := UIBodyInfo{Present: b.Hash != "", Size: b.Size, Stored: b.Stored, Truncated: b.Truncated, Type: b.Type,
		ContentType: b.ContentType, Encoding: b.Encoding, Image: b.Type == "image", Lines: -1, PrettyLines: -1}
	if !info.Present {
		return info
	}
	_ = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		info.DecodedSize = int64(len(d.data))
		info.Text = view.IsText(b.Type) || (textType(b.Type) && isText(d.data))
		if info.Text {
			info.Lines = d.textOf().total()
			if p := d.prettyOf(b.Type); p != nil {
				info.PrettyLines = p.total()
			}
		}
		return nil
	})
	return info
}

// UILines is a page of display lines.
type UILines struct {
	Total   int      `json:"total"`
	From    int      `json:"from"`
	Lines   []string `json:"lines"`
	Wrapped bool     `json:"wrapped"` // some lines of the text are longer than 2000 characters and were split
	Cont    []int    `json:"cont"`    // indexes (in the whole text) of the returned lines that continue the line before
}

// BodyLines pages through a body's text (pretty: the formatted form, when the type has one).
func (s *Service) BodyLines(session, id, side string, from, count int, pretty bool) (*UILines, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, err
	}
	if count <= 0 {
		count = 200
	}
	count = min(count, 5000)
	from = max(0, from)
	var out *UILines
	err = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		t := d.textOf()
		if pretty {
			if p := d.prettyOf(bodyOf(f, side).Type); p != nil {
				t = p
			}
		}
		lines, cont, err := t.lines(from, count)
		if err != nil {
			return err
		}
		out = &UILines{Total: t.total(), From: from, Lines: lines, Wrapped: t.wrapped, Cont: cont}
		return nil
	})
	return out, err
}

// UIHex is a range of a body's decoded bytes.
type UIHex struct {
	Size       int64  `json:"size"`
	Offset     int64  `json:"offset"`
	DataBase64 string `json:"data_base64"`
}

// BodyHex returns decoded bytes offset..offset+length (at most 64 KB).
func (s *Service) BodyHex(session, id, side string, offset, length int64) (*UIHex, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, err
	}
	if length <= 0 {
		length = 4096
	}
	length = min(length, 64<<10)
	var out *UIHex
	err = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		n := int64(len(d.data))
		offset = min(max(0, offset), n)
		end := min(n, offset+length)
		out = &UIHex{Size: n, Offset: offset, DataBase64: base64.StdEncoding.EncodeToString(d.data[offset:end])}
		return nil
	})
	return out, err
}

// UIMatch is one search hit: display line, and column and length in UTF-16 code units (a Kotlin/Java string
// index). A hit can run past the end of its display line into the continuation line.
type UIMatch struct {
	Line int `json:"line"`
	Col  int `json:"col"`
	Len  int `json:"len"`
}

// UISearch is the result of BodySearch.
type UISearch struct {
	Matches []UIMatch `json:"matches"`
	Total   int       `json:"total"` // all hits (counting stops at 100000)
	Cut     bool      `json:"cut"`   // more hits than returned
}

// BodySearch finds text (or a regex) in a body's text or pretty form; line numbers match BodyLines.
func (s *Service) BodySearch(session, id, side, find string, regex, matchCase, pretty bool, maxMatches int) (*UISearch, error) {
	if find == "" {
		return nil, errors.New("give find")
	}
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, err
	}
	if maxMatches <= 0 {
		maxMatches = 2000
	}
	maxMatches = min(maxMatches, 20000)
	finder, err := newFinder(find, regex, matchCase)
	if err != nil {
		return nil, err
	}
	out := &UISearch{Matches: []UIMatch{}}
	err = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		t := d.textOf()
		if pretty {
			if p := d.prettyOf(bodyOf(f, side).Type); p != nil {
				t = p
			}
		}
		return t.eachLine(func(line []byte, off int64) bool {
			return finder(line, searchCountMax-out.Total, func(a, b int) bool {
				out.Total++
				if len(out.Matches) < maxMatches {
					abs := off + int64(a)
					li := t.lineOf(abs)
					start := max(0, int(int64(t.starts[li])-off))
					out.Matches = append(out.Matches, UIMatch{Line: li, Col: utf16Len(line[start:a]), Len: utf16Len(line[a:b])})
				}
				return out.Total < searchCountMax
			})
		})
	})
	if err != nil {
		return nil, err
	}
	out.Cut = out.Total > len(out.Matches) || out.Total >= searchCountMax
	return out, nil
}

// newFinder returns a function that calls hit for every match in a line (at most limit), until hit returns
// false; it returns false when the search should stop.
func newFinder(find string, regex, matchCase bool) (func(line []byte, limit int, hit func(a, b int) bool) bool, error) {
	ascii := true
	for i := 0; i < len(find); i++ {
		if find[i] >= utf8.RuneSelf {
			ascii = false
		}
	}
	if !regex && (matchCase || ascii) {
		needle := []byte(find)
		if !matchCase {
			needle = bytes.ToLower(needle)
		}
		var low []byte
		return func(line []byte, limit int, hit func(a, b int) bool) bool {
			hay := line
			if !matchCase {
				low = append(low[:0], line...)
				for i, c := range low {
					if c >= 'A' && c <= 'Z' {
						low[i] = c + 32
					}
				}
				hay = low
			}
			for pos := 0; pos <= len(hay); {
				k := bytes.Index(hay[pos:], needle)
				if k < 0 {
					break
				}
				a := pos + k
				if !hit(a, a+len(needle)) {
					return false
				}
				pos = a + len(needle)
			}
			return true
		}, nil
	}
	src := find
	if !regex {
		src = regexp.QuoteMeta(find)
	}
	if !matchCase {
		src = "(?i)" + src
	}
	re, err := regexp.Compile(src)
	if err != nil {
		return nil, fmt.Errorf("regex: %w", err)
	}
	return func(line []byte, limit int, hit func(a, b int) bool) bool {
		for _, m := range re.FindAllIndex(line, limit+1) {
			if m[1] == m[0] {
				continue // empty matches show nothing
			}
			if !hit(m[0], m[1]) {
				return false
			}
		}
		return true
	}, nil
}

// BodyRaw returns the decoded body bytes and their content type.
func (s *Service) BodyRaw(session, id, side string) ([]byte, string, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, "", err
	}
	var data []byte
	err = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		data = d.data
		return nil
	})
	ct := bodyOf(f, side).ContentType
	if ct == "" {
		ct = "application/octet-stream"
	}
	return data, ct, err
}

// BodyToFile writes the decoded (or pretty) body to <session>/out/ui/f<id>.<side>[.pretty].<ext> and returns
// the path; an existing file of the right size is used again. Its own folder: out/bodies holds the AI's copies, which
// hide secrets, and neither side may pick up the other's file.
func (s *Service) BodyToFile(session, id, side string, pretty bool) (string, int64, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return "", 0, err
	}
	b := bodyOf(f, side)
	var path string
	var size int64
	err = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		var t *textDoc
		if pretty {
			t = d.prettyOf(b.Type)
		}
		name := fmt.Sprintf("%s.%s.%s", FID(f.ID), side, uiExt(b))
		size = int64(len(d.data))
		if t != nil {
			name = fmt.Sprintf("%s.%s.pretty.%s", FID(f.ID), side, uiExt(b))
			size = t.size
		}
		dir := filepath.Join(sess.Dir, "out", "ui")
		path = filepath.Join(dir, name)
		if st, err := os.Stat(path); err == nil && st.Size() == size {
			return nil
		}
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return err
		}
		if t == nil {
			return os.WriteFile(path, d.data, 0o600)
		}
		if t.file == nil {
			return os.WriteFile(path, t.mem, 0o600)
		}
		out, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
		if err != nil {
			return err
		}
		_, err = io.Copy(out, io.NewSectionReader(t.file, 0, t.size))
		if cerr := out.Close(); err == nil {
			err = cerr
		}
		return err
	})
	return path, size, err
}

// uiExt is a file extension (without the dot) for a body.
func uiExt(b *flow.Body) string {
	ct := strings.ToLower(strings.TrimSpace(strings.Split(b.ContentType, ";")[0]))
	switch ct {
	case "image/png":
		return "png"
	case "image/jpeg", "image/jpg", "image/pjpeg":
		return "jpg"
	case "image/gif":
		return "gif"
	case "image/webp":
		return "webp"
	case "image/svg+xml":
		return "svg"
	case "image/x-icon", "image/vnd.microsoft.icon":
		return "ico"
	case "image/avif":
		return "avif"
	case "image/bmp":
		return "bmp"
	case "video/mp4":
		return "mp4"
	case "video/webm":
		return "webm"
	case "audio/mpeg":
		return "mp3"
	case "audio/ogg":
		return "ogg"
	case "audio/wav", "audio/x-wav":
		return "wav"
	case "application/pdf":
		return "pdf"
	case "application/zip":
		return "zip"
	case "font/woff":
		return "woff"
	case "font/woff2":
		return "woff2"
	case "font/ttf":
		return "ttf"
	case "font/otf":
		return "otf"
	}
	switch b.Type {
	case "json", "html", "xml", "js", "css":
		return b.Type
	case "text", "form":
		return "txt"
	case "proto":
		return "pb"
	}
	return "bin"
}

// ---- JSON tree ----

// UITreeNode is one entry of the tree view.
type UITreeNode struct {
	Key     string `json:"key"` // object key, or [3] for an array element
	Path    string `json:"path"`
	Kind    string `json:"kind"` // object, array, string, number, bool, null
	Preview string `json:"preview"`
	Count   int    `json:"count,omitempty"` // children of an object or array
}

// UITree is one level of the tree view.
type UITree struct {
	Path     string       `json:"path"`
	Kind     string       `json:"kind"`
	Count    int          `json:"count"`
	Children []UITreeNode `json:"children"`
}

// BodyTree lists the children from..from+count of the JSON value at path (jq style: "" or "." = the root,
// .data, .data[3], .["a key"]).
func (s *Service) BodyTree(session, id, side, path string, from, count int) (*UITree, error) {
	sess, f, err := s.uiFlowOf(session, id)
	if err != nil {
		return nil, err
	}
	if count <= 0 {
		count = 200
	}
	count = min(count, 2000)
	from = max(0, from)
	var out *UITree
	err = s.withDoc(sess, f, side, func(d *bodyDoc) error {
		if d.tree == nil {
			if len(d.data) > treeMax {
				return fmt.Errorf("the body is %s: the tree view takes JSON up to %s (use the text view)", Size(int64(len(d.data))), Size(treeMax))
			}
			if !json.Valid(d.data) {
				return errors.New("the body is not one valid JSON value: no tree view (use the text view)")
			}
			d.tree = &jsonTree{b: d.data, info: map[int]*containerInfo{}, paths: map[string]int{}}
		}
		before := d.tree.mem
		t, err := d.tree.list(path, from, count)
		d.c.grow(d, d.tree.mem-before)
		out = t
		return err
	})
	return out, err
}

type jsonTree struct {
	b     []byte
	info  map[int]*containerInfo // by the offset of the container's first byte
	paths map[string]int         // resolved paths → offset of the value
	mem   int64
}

type containerInfo struct {
	count int
	marks []int32 // offset of child 0, 256, 512, … (the key of an object member)
}

const treeMark = 256

func (t *jsonTree) ws(i int) int {
	for i < len(t.b) && (t.b[i] == ' ' || t.b[i] == '\t' || t.b[i] == '\r' || t.b[i] == '\n') {
		i++
	}
	return i
}

// skip returns the offset after the value at i.
func (t *jsonTree) skip(i int) int {
	b := t.b
	if i >= len(b) {
		return i
	}
	switch b[i] {
	case '"':
		return stringEndJSON(b, i)
	case '{', '[':
		depth := 0
		for j := i; j < len(b); j++ {
			switch b[j] {
			case '"':
				j = stringEndJSON(b, j) - 1
			case '{', '[':
				depth++
			case '}', ']':
				depth--
				if depth == 0 {
					return j + 1
				}
			}
		}
		return len(b)
	}
	j := i
	for j < len(b) && !strings.ContainsRune(",}] \t\r\n", rune(b[j])) {
		j++
	}
	return j
}

func stringEndJSON(b []byte, i int) int {
	for j := i + 1; j < len(b); j++ {
		switch b[j] {
		case '\\':
			j++
		case '"':
			return j + 1
		}
	}
	return len(b)
}

// each walks the children of the container at o starting at pos (a child's start, or just inside the bracket)
// with index i; fn gets the key bounds (objects) and value bounds and returns false to stop.
func (t *jsonTree) each(o, pos, i int, fn func(i, ks, ke, vs, ve int) bool) {
	b := t.b
	obj := b[o] == '{'
	for {
		pos = t.ws(pos)
		if pos >= len(b) || b[pos] == '}' || b[pos] == ']' {
			return
		}
		if b[pos] == ',' {
			pos = t.ws(pos + 1)
		}
		ks, ke := -1, -1
		if obj {
			ks = pos
			ke = stringEndJSON(b, pos)
			pos = t.ws(ke)
			if pos < len(b) && b[pos] == ':' {
				pos = t.ws(pos + 1)
			}
		}
		vs := pos
		ve := t.skip(vs)
		if !fn(i, ks, ke, vs, ve) {
			return
		}
		i++
		pos = ve
	}
}

func (t *jsonTree) infoOf(o int) *containerInfo {
	if inf, ok := t.info[o]; ok {
		return inf
	}
	inf := &containerInfo{}
	t.each(o, o+1, 0, func(i, ks, _, vs, _ int) bool {
		if i%treeMark == 0 {
			start := vs
			if ks >= 0 {
				start = ks
			}
			inf.marks = append(inf.marks, int32(start))
		}
		inf.count++
		return true
	})
	t.info[o] = inf
	t.mem += 64 + int64(len(inf.marks))*4
	return inf
}

// from walks the children of the container at o from index k.
func (t *jsonTree) from(o, k int, fn func(i, ks, ke, vs, ve int) bool) {
	inf := t.infoOf(o)
	if k >= inf.count {
		return
	}
	m := k / treeMark
	t.each(o, int(inf.marks[m]), m*treeMark, func(i, ks, ke, vs, ve int) bool {
		if i < k {
			return true
		}
		return fn(i, ks, ke, vs, ve)
	})
}

func (t *jsonTree) key(ks, ke int) string {
	raw := t.b[ks:ke]
	if bytes.IndexByte(raw, '\\') < 0 && len(raw) >= 2 {
		return string(raw[1 : len(raw)-1])
	}
	var k string
	if json.Unmarshal(raw, &k) != nil {
		return string(raw)
	}
	return k
}

func kindOf(c byte) string {
	switch c {
	case '{':
		return "object"
	case '[':
		return "array"
	case '"':
		return "string"
	case 't', 'f':
		return "bool"
	case 'n':
		return "null"
	}
	return "number"
}

var simpleKey = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

func childPath(parent, key string, index int, isArray bool) string {
	if parent == "." {
		parent = ""
	}
	if isArray {
		return parent + "[" + strconv.Itoa(index) + "]"
	}
	if simpleKey.MatchString(key) {
		return parent + "." + key
	}
	q, _ := json.Marshal(key)
	return parent + "[" + string(q) + "]"
}

// resolve finds the offset of the value at path.
func (t *jsonTree) resolve(path string) (int, error) {
	path = strings.TrimSpace(path)
	if path == "" || path == "." {
		return t.ws(0), nil
	}
	if o, ok := t.paths[path]; ok {
		return o, nil
	}
	o := t.ws(0)
	rest := path
	for rest != "" {
		var key string
		index, isIndex := -1, false
		switch {
		case strings.HasPrefix(rest, "[\""), strings.HasPrefix(rest, ".[\""):
			rest = strings.TrimPrefix(rest, ".")
			end := stringEndJSON([]byte(rest), 1)
			if end >= len(rest) || rest[end] != ']' {
				return 0, fmt.Errorf("bad path %q", path)
			}
			if err := json.Unmarshal([]byte(rest[1:end]), &key); err != nil {
				return 0, fmt.Errorf("bad path %q", path)
			}
			rest = rest[end+1:]
		case strings.HasPrefix(rest, "["), strings.HasPrefix(rest, ".["):
			rest = strings.TrimPrefix(rest, ".")
			end := strings.IndexByte(rest, ']')
			if end < 0 {
				return 0, fmt.Errorf("bad path %q", path)
			}
			n, err := strconv.Atoi(rest[1:end])
			if err != nil || n < 0 {
				return 0, fmt.Errorf("bad index in path %q", path)
			}
			index, isIndex = n, true
			rest = rest[end+1:]
		case strings.HasPrefix(rest, "."):
			rest = rest[1:]
			end := strings.IndexAny(rest, ".[")
			if end < 0 {
				end = len(rest)
			}
			key, rest = rest[:end], rest[end:]
		default:
			return 0, fmt.Errorf("bad path %q (use .key, [3] or [\"key\"])", path)
		}
		found := -1
		switch {
		case isIndex && t.b[o] == '[':
			t.from(o, index, func(_, _, _, vs, _ int) bool { found = vs; return false })
		case !isIndex && t.b[o] == '{':
			t.each(o, o+1, 0, func(_, ks, ke, vs, _ int) bool {
				if t.key(ks, ke) == key {
					found = vs
					return false
				}
				return true
			})
		}
		if found < 0 {
			return 0, fmt.Errorf("no %s in the JSON", path)
		}
		o = found
	}
	if len(t.paths) > 10000 {
		t.paths = map[string]int{}
	}
	t.paths[path] = o
	t.mem += int64(len(path)) + 32
	return o, nil
}

func (t *jsonTree) list(path string, from, count int) (*UITree, error) {
	o, err := t.resolve(path)
	if err != nil {
		return nil, err
	}
	if path == "" {
		path = "."
	}
	out := &UITree{Path: path, Kind: kindOf(t.b[o]), Children: []UITreeNode{}}
	if out.Kind != "object" && out.Kind != "array" {
		return out, nil
	}
	isArray := out.Kind == "array"
	out.Count = t.infoOf(o).count
	t.from(o, from, func(i, ks, ke, vs, ve int) bool {
		if i >= from+count {
			return false
		}
		n := UITreeNode{Kind: kindOf(t.b[vs]), Preview: t.preview(vs, ve)}
		if isArray {
			n.Key = "[" + strconv.Itoa(i) + "]"
			n.Path = childPath(path, "", i, true)
		} else {
			n.Key = t.key(ks, ke)
			n.Path = childPath(path, n.Key, 0, false)
		}
		if n.Kind == "object" || n.Kind == "array" {
			n.Count = t.infoOf(vs).count
		}
		out.Children = append(out.Children, n)
		return true
	})
	return out, nil
}

// preview is a short text of a value: a string's text, a literal, or the start of an object/array without spaces.
func (t *jsonTree) preview(vs, ve int) string {
	raw := t.b[vs:ve]
	switch raw[0] {
	case '"':
		cut := raw
		if len(cut) > 600 {
			// cut before 600 bytes, outside an escape, and close the string
			end := 0
			for j := 1; j < 600; j++ {
				if raw[j] == '\\' {
					j++
					continue
				}
				end = j
			}
			cut = append(append([]byte(nil), raw[:end+1]...), '"')
		}
		var str string
		if json.Unmarshal(cut, &str) != nil {
			str = string(cut)
		}
		return Cut(str, 120)
	case '{', '[':
		var b strings.Builder
		for j := 0; j < len(raw) && b.Len() < 480; j++ {
			c := raw[j]
			switch c {
			case ' ', '\t', '\r', '\n':
				continue
			case '"':
				e := stringEndJSON(raw, j)
				b.Write(raw[j:min(e, j+200)])
				j = e - 1
				continue
			}
			b.WriteByte(c)
		}
		return Cut(strings.ToValidUTF8(b.String(), ""), 120)
	}
	return Cut(string(raw), 120)
}
