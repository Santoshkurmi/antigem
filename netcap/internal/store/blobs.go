package store

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"hash"
	"io"
	"os"
	"path/filepath"
	"sync"

	"github.com/klauspost/compress/zstd"
)

// Blobs stores bodies content-addressed (sha256 of the bytes) and zstd-compressed: blobs/ab/abcdef….zst.
// The same body seen 100 times is stored once.
type Blobs struct {
	dir   string
	small smallStore // small bodies live in the database (one row each, written in the flow batches)
}

// smallStore keeps compressed bodies up to smallMax in the session database instead of files: most API bodies are
// small, and one file per body costs a lot of I/O on a phone.
type smallStore interface {
	putSmall(hash string, comp []byte)
	getSmall(hash string) ([]byte, bool)
}

const smallMax = 64 << 10

var (
	encPool = sync.Pool{New: func() any {
		e, _ := zstd.NewWriter(nil, zstd.WithEncoderLevel(zstd.SpeedFastest), zstd.WithEncoderConcurrency(1), zstd.WithLowerEncoderMem(true))
		return e
	}}
	decPool = sync.Pool{New: func() any {
		d, _ := zstd.NewReader(nil, zstd.WithDecoderConcurrency(1), zstd.WithDecoderLowmem(true))
		return d
	}}
)

// NewBlobs uses dir (created if needed).
func NewBlobs(dir string) (*Blobs, error) {
	if err := os.MkdirAll(filepath.Join(dir, "tmp"), 0o700); err != nil {
		return nil, err
	}
	return &Blobs{dir: dir}, nil
}

func (b *Blobs) path(hash string) string {
	if len(hash) < 4 {
		return filepath.Join(b.dir, "bad")
	}
	return filepath.Join(b.dir, hash[:2], hash+".zst")
}

// Has reports whether a blob exists.
func (b *Blobs) Has(hash string) bool {
	_, err := os.Stat(b.path(hash))
	return err == nil
}

// Open returns the decompressed bytes of a blob as a stream.
func (b *Blobs) Open(hash string) (io.ReadCloser, error) {
	if b.small != nil {
		if comp, ok := b.small.getSmall(hash); ok {
			d := decPool.Get().(*zstd.Decoder)
			data, err := d.DecodeAll(comp, nil)
			decPool.Put(d)
			if err != nil {
				return nil, err
			}
			return io.NopCloser(bytes.NewReader(data)), nil
		}
	}
	f, err := os.Open(b.path(hash))
	if err != nil {
		return nil, err
	}
	d := decPool.Get().(*zstd.Decoder)
	if err := d.Reset(f); err != nil {
		f.Close()
		decPool.Put(d)
		return nil, err
	}
	return &blobReader{d: d, f: f}, nil
}

type blobReader struct {
	d *zstd.Decoder
	f *os.File
}

func (r *blobReader) Read(p []byte) (int, error) { return r.d.Read(p) }
func (r *blobReader) Close() error {
	_ = r.d.Reset(nil)
	decPool.Put(r.d)
	return r.f.Close()
}

// Read returns up to max bytes of a blob (max <= 0: all) and whether it was cut.
func (b *Blobs) Read(hash string, max int64) ([]byte, bool, error) {
	if hash == "" {
		return nil, false, nil
	}
	r, err := b.Open(hash)
	if err != nil {
		return nil, false, err
	}
	defer r.Close()
	if max <= 0 {
		data, err := io.ReadAll(r)
		return data, false, err
	}
	data, err := io.ReadAll(io.LimitReader(r, max+1))
	if err != nil {
		return data, false, err
	}
	if int64(len(data)) > max {
		return data[:max], true, nil
	}
	return data, false, nil
}

// Put stores bytes and returns their hash.
func (b *Blobs) Put(data []byte) (string, error) {
	if len(data) == 0 {
		return "", nil
	}
	sum := sha256.Sum256(data)
	hash := hex.EncodeToString(sum[:])
	if b.Has(hash) {
		return hash, nil
	}
	e := encPool.Get().(*zstd.Encoder)
	comp := e.EncodeAll(data, nil)
	encPool.Put(e)
	return hash, b.writeFile(hash, comp)
}

func (b *Blobs) writeFile(hash string, comp []byte) error {
	p := b.path(hash)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Join(b.dir, "tmp"), "w*")
	if err != nil {
		return err
	}
	if _, err := tmp.Write(comp); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return err
	}
	tmp.Close()
	return os.Rename(tmp.Name(), p)
}

// Writer captures a body while it streams: the first bytes in memory, the rest spilled to a temp file, everything
// past max only counted.
type Writer struct {
	b       *Blobs
	max     int64
	mem     bytes.Buffer
	file    *os.File
	enc     *zstd.Encoder
	h       hash.Hash
	size    int64 // seen
	stored  int64
	closed  bool
	failure error
}

const memLimit = 256 << 10

// NewWriter starts capturing a body that keeps at most max bytes.
func (b *Blobs) NewWriter(max int64) *Writer {
	return &Writer{b: b, max: max, h: sha256.New()}
}

// Write never fails (a capture problem must not break the traffic); errors are kept for Close.
func (w *Writer) Write(p []byte) (int, error) {
	n := len(p)
	w.size += int64(n)
	if w.closed || w.failure != nil || w.stored >= w.max {
		return n, nil
	}
	if room := w.max - w.stored; int64(len(p)) > room {
		p = p[:room]
	}
	w.h.Write(p)
	w.stored += int64(len(p))
	if w.file == nil && w.mem.Len()+len(p) <= memLimit {
		w.mem.Write(p)
		return n, nil
	}
	if w.file == nil {
		f, err := os.CreateTemp(filepath.Join(w.b.dir, "tmp"), "s*")
		if err != nil {
			w.failure = err
			return n, nil
		}
		w.file = f
		w.enc = encPool.Get().(*zstd.Encoder)
		w.enc.Reset(f)
		if _, err := w.enc.Write(w.mem.Bytes()); err != nil {
			w.failure = err
		}
		w.mem = bytes.Buffer{}
	}
	if _, err := w.enc.Write(p); err != nil {
		w.failure = err
	}
	return n, nil
}

// Result of a finished capture.
type Result struct {
	Hash   string
	Size   int64 // bytes seen
	Stored int64
	Err    error
}

// Close finishes the capture and stores the blob.
func (w *Writer) Close() Result {
	if w.closed {
		return Result{Size: w.size, Stored: w.stored, Err: errors.New("closed twice")}
	}
	w.closed = true
	res := Result{Size: w.size, Stored: w.stored}
	if w.stored == 0 {
		if w.file != nil {
			w.discard()
		}
		return res
	}
	hash := hex.EncodeToString(w.h.Sum(nil))
	res.Hash = hash
	if w.file == nil {
		e := encPool.Get().(*zstd.Encoder)
		comp := e.EncodeAll(w.mem.Bytes(), nil)
		encPool.Put(e)
		if len(comp) <= smallMax && w.b.small != nil {
			w.b.small.putSmall(hash, comp)
		} else if !w.b.Has(hash) {
			res.Err = w.b.writeFile(hash, comp)
		}
		return res
	}
	if w.failure != nil {
		w.discard()
		res.Hash, res.Err = "", w.failure
		return res
	}
	if err := w.enc.Close(); err != nil {
		w.discard()
		res.Hash, res.Err = "", err
		return res
	}
	encPool.Put(w.enc)
	w.enc = nil
	name := w.file.Name()
	w.file.Close()
	if w.b.Has(hash) {
		os.Remove(name)
		return res
	}
	p := w.b.path(hash)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		os.Remove(name)
		res.Hash, res.Err = "", err
		return res
	}
	if err := os.Rename(name, p); err != nil {
		os.Remove(name)
		res.Hash, res.Err = "", err
	}
	return res
}

func (w *Writer) discard() {
	if w.enc != nil {
		w.enc.Reset(nil)
		encPool.Put(w.enc)
		w.enc = nil
	}
	name := w.file.Name()
	w.file.Close()
	os.Remove(name)
}
