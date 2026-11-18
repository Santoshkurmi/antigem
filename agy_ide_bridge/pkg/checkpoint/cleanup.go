package checkpoint

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	cleanupEvery     = 10 * time.Minute // at most this often per project after a new checkpoint
	keepPreRestore   = 24 * time.Hour   // an "undo restore" copy is always kept this long
	maxPreRestore    = 20
	sizePasses       = 4
	autoPruneExpires = "1.hour.ago"
)

// DeleteRequest: ids, or everything older than N days, or all of the project's checkpoints.
type DeleteRequest struct {
	Project       string   `json:"project"`
	IDs           []string `json:"ids"`
	OlderThanDays int      `json:"older_than_days"`
	All           bool     `json:"all"`
	Forget        bool     `json:"forget"` // with all: also forget the answer given for the project
}

type DeleteResult struct {
	Deleted    int   `json:"deleted"`
	SizeBefore int64 `json:"size_before"`
	SizeAfter  int64 `json:"size_after"`
}

// Delete removes checkpoints. Newer ones are never affected by deleting older ones (each is complete on its own).
func (m *Manager) Delete(req DeleteRequest) (*DeleteResult, error) {
	path := normPath(req.Project)
	if path == "" {
		return nil, errors.New("no project")
	}
	if req.Forget {
		_ = m.SetDecision(path, "")
	}
	p, err := m.project(path, false)
	if err != nil {
		return &DeleteResult{}, nil
	}
	res := &DeleteResult{SizeBefore: p.size(true)}
	if req.All {
		p.mu.Lock()
		res.Deleted = len(p.records)
		err := os.RemoveAll(p.dir)
		m.projMu.Lock()
		delete(m.projects, p.key)
		m.projMu.Unlock()
		p.records = nil
		p.mu.Unlock()
		return res, err
	}
	now := time.Now()
	ids := map[string]bool{}
	for _, id := range req.IDs {
		ids[id] = true
	}
	p.mu.Lock()
	var drop []*Record
	for _, r := range p.records {
		if ids[r.ID] || (req.OlderThanDays > 0 && now.Sub(time.UnixMilli(r.Time)) > time.Duration(req.OlderThanDays)*24*time.Hour) {
			drop = append(drop, r)
		}
	}
	err = m.dropLocked(p, drop)
	if err == nil && len(drop) > 0 {
		// asked for by the user: the space comes back now
		m.gc(p, "now")
	}
	p.mu.Unlock()
	res.Deleted = len(drop)
	res.SizeAfter = p.size(true)
	return res, err
}

// dropLocked removes records and their refs (their files go at the next gc).
func (m *Manager) dropLocked(p *project, drop []*Record) error {
	if len(drop) == 0 {
		return nil
	}
	gone := map[string]bool{}
	var b strings.Builder
	for _, r := range drop {
		gone[r.ID] = true
		b.WriteString("delete " + cpRef(r.ID) + "\n")
	}
	if _, err := m.shadowStdin(p, []byte(b.String())).run("update-ref", "--stdin"); err != nil {
		log.Printf("[checkpoints] removing refs: %v", err)
	}
	kept := p.records[:0]
	for _, r := range p.records {
		if !gone[r.ID] {
			kept = append(kept, r)
		}
	}
	p.records = kept
	return p.saveRecordsLocked()
}

// gc packs the checkpoint repository and frees what no checkpoint uses any more. prune "now" only while the project
// is locked (nothing is half written then); otherwise objects younger than an hour are kept.
func (m *Manager) gc(p *project, prune string) {
	if !p.gcRunning.CompareAndSwap(false, true) {
		return
	}
	defer p.gcRunning.Store(false)
	c := m.shadow(p, "")
	c.timeout = 20 * time.Minute
	if _, err := c.run("-c", "gc.auto=0", "-c", "pack.threads=1", "gc", "--quiet", "--prune="+prune); err != nil {
		log.Printf("[checkpoints] gc %s: %v", p.path, err)
	}
	p.sizeMu.Lock()
	p.sizeAt = time.Time{}
	p.sizeMu.Unlock()
}

// maybeCleanup runs the automatic cleanup for a project in the background (at most every few minutes).
func (m *Manager) maybeCleanup(p *project) {
	p.mu.Lock()
	due := time.Since(p.lastCleanup) > cleanupEvery
	if due {
		p.lastCleanup = time.Now()
	}
	p.mu.Unlock()
	if due {
		go m.autoCleanup(p)
	}
}

// autoCleanup applies the retention settings: age, count per chat, size of the project's store.
func (m *Manager) autoCleanup(p *project) {
	s := m.Settings()
	now := time.Now()
	p.mu.Lock()
	var drop []*Record
	perChat := map[string]int{}
	pre := 0
	// newest first: the counts keep the newest ones
	for i := len(p.records) - 1; i >= 0; i-- {
		r := p.records[i]
		age := now.Sub(time.UnixMilli(r.Time))
		if r.Kind == KindPreRestore {
			pre++
			if age > keepPreRestore && (pre > maxPreRestore || (s.RetentionDays > 0 && age > time.Duration(s.RetentionDays)*24*time.Hour)) {
				drop = append(drop, r)
			}
			continue
		}
		if s.RetentionDays > 0 && age > time.Duration(s.RetentionDays)*24*time.Hour {
			drop = append(drop, r)
			continue
		}
		perChat[r.ChatID]++
		if s.MaxPerChat > 0 && perChat[r.ChatID] > s.MaxPerChat {
			drop = append(drop, r)
		}
	}
	_ = m.dropLocked(p, drop)
	p.mu.Unlock()
	if len(drop) > 0 {
		m.gc(p, autoPruneExpires)
	}
	if s.MaxProjectMB <= 0 {
		return
	}
	limit := int64(s.MaxProjectMB) * 1024 * 1024
	for pass := 0; pass < sizePasses && p.size(true) > limit; pass++ {
		p.mu.Lock()
		// oldest quarter of the checkpoints goes (the newest one always stays)
		n := len(p.records) / 4
		if n == 0 && len(p.records) > 1 {
			n = 1
		}
		if n == 0 {
			p.mu.Unlock()
			break
		}
		recs := append([]*Record(nil), p.records...)
		sort.SliceStable(recs, func(i, j int) bool { return recs[i].Time < recs[j].Time })
		var cut []*Record
		for _, r := range recs[:n] {
			if r.Kind == KindPreRestore && now.Sub(time.UnixMilli(r.Time)) < keepPreRestore {
				continue
			}
			cut = append(cut, r)
		}
		_ = m.dropLocked(p, cut)
		if len(cut) > 0 {
			m.gc(p, "now")
		}
		p.mu.Unlock()
		if len(cut) == 0 {
			break
		}
	}
}

// cleanupAll runs the automatic cleanup on every project with checkpoints.
func (m *Manager) cleanupAll(force bool) {
	entries, _ := os.ReadDir(filepath.Join(m.root, "projects"))
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		var meta struct {
			Path string `json:"path"`
		}
		b, err := os.ReadFile(filepath.Join(m.root, "projects", e.Name(), "project.json"))
		if err != nil || json.Unmarshal(b, &meta) != nil || meta.Path == "" {
			continue
		}
		p, err := m.project(meta.Path, false)
		if err != nil {
			continue
		}
		if force {
			p.mu.Lock()
			p.lastCleanup = time.Now()
			p.mu.Unlock()
			m.autoCleanup(p)
		} else {
			m.maybeCleanup(p)
		}
	}
}

func (m *Manager) cleanupLoop() {
	time.Sleep(2 * time.Minute)
	for {
		m.cleanupAll(true)
		time.Sleep(12 * time.Hour)
	}
}
