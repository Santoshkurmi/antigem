package store

import (
	"fmt"
	"strings"
	"time"

	"antigem/netcap/internal/flow"
)

// Edit changes one stored flow (the GUI's pin, color and note) and returns the new snapshot. While the flow is
// still running the change also sticks to the engine's later writes of it.
func (s *Store) Edit(id int64, fn func(*flow.Flow)) (*flow.Flow, error) {
	s.writeMu.Lock() // no flush runs meanwhile: what the database holds can't change under us
	defer s.writeMu.Unlock()
	s.mu.Lock()
	if p, ok := s.pending[id]; ok {
		c := s.editLocked(p, fn)
		s.mu.Unlock()
		return c, nil
	}
	s.mu.Unlock()
	f, err := s.Get(id)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if p, ok := s.pending[id]; ok { // the engine wrote a newer version meanwhile
		f = p
	}
	if s.gone(id) {
		return nil, fmt.Errorf("no flow f%d", id)
	}
	return s.editLocked(f, fn), nil
}

// editLocked queues an edited copy of f (s.mu held); queued snapshots are never changed in place.
func (s *Store) editLocked(f *flow.Flow, fn func(*flow.Flow)) *flow.Flow {
	c := f.Clone()
	fn(c)
	if !c.Done {
		s.sticky[c.ID] = append(s.sticky[c.ID], fn)
	}
	s.pending[c.ID] = c
	return c.Clone()
}

// Delete removes flows and their messages (bodies stay in the blob store). Returns how many rows were deleted.
func (s *Store) Delete(ids []int64) (int64, error) {
	if len(ids) == 0 {
		return 0, nil
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	now := time.Now()
	want := map[int64]bool{}
	var queued int64
	s.mu.Lock()
	for id, t := range s.deleted { // a running flow ends within minutes; older entries aren't needed
		if now.Sub(t) > 10*time.Minute {
			delete(s.deleted, id)
		}
	}
	for _, id := range ids {
		want[id] = true
		s.deleted[id] = now
		if _, ok := s.pending[id]; ok {
			queued++
		}
		delete(s.pending, id)
		delete(s.sticky, id)
	}
	msgs := s.pendingMsg[:0]
	for _, m := range s.pendingMsg {
		if !want[m.FlowID] {
			msgs = append(msgs, m)
		}
	}
	s.pendingMsg = msgs
	s.mu.Unlock()
	var n int64
	for start := 0; start < len(ids); start += 500 {
		part := ids[start:min(len(ids), start+500)]
		ph := strings.TrimSuffix(strings.Repeat("?,", len(part)), ",")
		args := make([]any, len(part))
		for i, id := range part {
			args[i] = id
		}
		res, err := s.db.Exec(`DELETE FROM flows WHERE id IN (`+ph+`)`, args...)
		if err != nil {
			return n, err
		}
		k, _ := res.RowsAffected()
		n += k
		if _, err := s.db.Exec(`DELETE FROM messages WHERE flow_id IN (`+ph+`)`, args...); err != nil {
			return n, err
		}
	}
	if n < queued { // queued but not written yet: deleted all the same
		n = queued
	}
	return n, nil
}

// Clear removes every flow and message of the session (bodies, DNS records and findings stay). Running flows
// that end later are not recorded either.
func (s *Store) Clear() (int64, error) {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	s.mu.Lock()
	s.clearedTo = s.nextID.Load()
	queued := int64(len(s.pending))
	s.pending = map[int64]*flow.Flow{}
	s.sticky = map[int64][]func(*flow.Flow){}
	s.deleted = map[int64]time.Time{}
	s.pendingMsg = nil
	s.mu.Unlock()
	res, err := s.db.Exec(`DELETE FROM flows`)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	if _, err := s.db.Exec(`DELETE FROM messages`); err != nil {
		return n, err
	}
	return max(n, queued), nil
}

// MessageCount is how many messages of a flow are stored.
func (s *Store) MessageCount(flowID int64) int {
	var n int
	_ = s.db.QueryRow(`SELECT count(*) FROM messages WHERE flow_id = ?`, flowID).Scan(&n)
	return n
}

// Counts returns the number of flows and findings without waiting for the writer (for status lines that update
// often).
func (s *Store) Counts() (flows, findings int) {
	_ = s.db.QueryRow(`SELECT count(*) FROM flows`).Scan(&flows)
	_ = s.db.QueryRow(`SELECT count(*) FROM findings`).Scan(&findings)
	return
}
