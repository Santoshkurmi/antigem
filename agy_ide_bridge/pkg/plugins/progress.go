package plugins

import (
	"io"
	"sync"
)

// Progress of one repository install. The app polls GET /repo/progress?folder= while POST /repo/install runs.
type Progress struct {
	Phase      string `json:"phase"` // listing | downloading | extracting | installing
	BytesDone  int64  `json:"bytes_done"`
	BytesTotal int64  `json:"bytes_total"` // 0 while unknown (an archive GitHub sends without a length)
	FilesDone  int    `json:"files_done"`
	FilesTotal int    `json:"files_total"`
}

// progressTracker is safe to use from the parallel file downloads; a nil tracker ignores every update.
type progressTracker struct {
	mu sync.Mutex
	p  Progress
}

func (t *progressTracker) set(fn func(*Progress)) {
	if t == nil {
		return
	}
	t.mu.Lock()
	fn(&t.p)
	t.mu.Unlock()
}

func (t *progressTracker) snapshot() Progress {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.p
}

// counting adds every byte read from r to the tracker's BytesDone.
func (t *progressTracker) counting(r io.Reader) io.Reader {
	if t == nil {
		return r
	}
	return countingReader{r, t}
}

type countingReader struct {
	r io.Reader
	t *progressTracker
}

func (c countingReader) Read(b []byte) (int, error) {
	n, err := c.r.Read(b)
	if n > 0 {
		c.t.set(func(p *Progress) { p.BytesDone += int64(n) })
	}
	return n, err
}
