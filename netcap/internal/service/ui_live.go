package service

import (
	"bytes"
	"context"
	"encoding/json"
	"sync"
	"sync/atomic"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
)

// The GUI's live list: every change of a flow (started, response headers in, finished, edited by the user) goes to
// the streams watching its session, coalesced by id and sent in batches. Publishing never blocks the traffic: it
// only puts a pointer into a map.

const (
	liveBatchMax = 500 // rows per batch; more = the oldest are dropped and the batch says gap
	liveTick     = 150 * time.Millisecond
	livePing     = 20 * time.Second
)

type liveHub struct {
	n         atomic.Int32
	mu        sync.RWMutex
	subs      map[*liveSub]struct{}
	pausedGen atomic.Int64 // changes when a flow pauses, continues or gets more time
}

type liveSub struct {
	session  string // "" = the active session
	filter   *query.Filter
	deferred bool // the filter reads bodies or runs JS: matched in the stream, not on the traffic path

	mu      sync.Mutex
	items   map[int64]liveItem
	order   []int64 // ids in the order they came (may hold ids already taken out)
	removed []int64
	gap     bool
	open    map[int64]bool // running flows sent to this stream (a later version that stops matching is removed)
}

type liveItem struct {
	f     *flow.Flow // a store snapshot: never changed
	force bool       // the user changed it: report it removed when it stops matching
}

func (h *liveHub) subscribe(session string, filter *query.Filter) *liveSub {
	sub := &liveSub{session: session, filter: filter, items: map[int64]liveItem{}, open: map[int64]bool{}}
	if filter != nil {
		sub.deferred = filter.NeedsBody() || filter.Mentions("js")
	}
	h.mu.Lock()
	if h.subs == nil {
		h.subs = map[*liveSub]struct{}{}
	}
	h.subs[sub] = struct{}{}
	h.n.Store(int32(len(h.subs)))
	h.mu.Unlock()
	return sub
}

func (h *liveHub) unsubscribe(sub *liveSub) {
	h.mu.Lock()
	delete(h.subs, sub)
	h.n.Store(int32(len(h.subs)))
	h.mu.Unlock()
}

// each calls fn for the streams watching session sessID.
func (h *liveHub) each(s *Service, sessID string, fn func(*liveSub)) {
	if h.n.Load() == 0 {
		return
	}
	active := ""
	h.mu.RLock()
	defer h.mu.RUnlock()
	for sub := range h.subs {
		want := sub.session
		if want == "" {
			if active == "" {
				active = s.Current().ID
			}
			want = active
		}
		if want == sessID {
			fn(sub)
		}
	}
}

// flow publishes a new snapshot of a flow.
func (h *liveHub) flow(s *Service, sessID string, f *flow.Flow, force bool) {
	h.each(s, sessID, func(sub *liveSub) { sub.put(f, force) })
}

// removed tells the streams that flows were deleted.
func (h *liveHub) removed(s *Service, sessID string, ids []int64) {
	h.each(s, sessID, func(sub *liveSub) {
		sub.mu.Lock()
		for _, id := range ids {
			delete(sub.items, id)
			delete(sub.open, id)
		}
		sub.removed = append(sub.removed, ids...)
		if len(sub.removed) > 5000 {
			sub.removed, sub.gap = nil, true
		}
		sub.mu.Unlock()
	})
}

// reset: the whole session changed (cleared): the streams send gap, so the GUI reloads.
func (h *liveHub) reset(s *Service, sessID string) {
	h.each(s, sessID, func(sub *liveSub) {
		sub.mu.Lock()
		sub.items, sub.order, sub.removed, sub.open = map[int64]liveItem{}, nil, nil, map[int64]bool{}
		sub.gap = true
		sub.mu.Unlock()
	})
}

func (h *liveHub) pausedChanged() { h.pausedGen.Add(1) }

func (sub *liveSub) put(f *flow.Flow, force bool) {
	match := sub.filter == nil || sub.deferred || sub.filter.Match(f, nil)
	sub.mu.Lock()
	defer sub.mu.Unlock()
	if !match {
		if force || sub.open[f.ID] {
			delete(sub.open, f.ID)
			delete(sub.items, f.ID)
			sub.removed = append(sub.removed, f.ID)
		}
		return
	}
	if f.Done {
		delete(sub.open, f.ID)
	} else if len(sub.open) < 20000 {
		sub.open[f.ID] = true
	}
	old, had := sub.items[f.ID]
	if !had {
		sub.order = append(sub.order, f.ID)
		for len(sub.items) >= liveBatchMax && len(sub.order) > 0 { // the stream falls behind: drop the oldest
			id := sub.order[0]
			sub.order = sub.order[1:]
			if _, ok := sub.items[id]; ok {
				delete(sub.items, id)
				sub.gap = true
			}
		}
	}
	sub.items[f.ID] = liveItem{f: f, force: force || old.force}
}

func (sub *liveSub) take() (items []liveItem, removed []int64, gap bool) {
	sub.mu.Lock()
	defer sub.mu.Unlock()
	for _, id := range sub.order {
		if it, ok := sub.items[id]; ok {
			items = append(items, it)
			delete(sub.items, id)
		}
	}
	sub.order = sub.order[:0]
	removed, gap = sub.removed, sub.gap
	sub.removed, sub.gap = nil, false
	return
}

// LiveBatch is one "batch" event of the live stream.
type LiveBatch struct {
	Seq     int         `json:"seq"`
	Session string      `json:"session"`
	Rows    []UIRow     `json:"rows,omitempty"`
	Removed []int64     `json:"removed,omitempty"`
	Gap     bool        `json:"gap,omitempty"`
	Paused  *[]UIPaused `json:"paused,omitempty"` // the whole paused list, only when it changed
	State   *UIState    `json:"state,omitempty"`  // only when it changed
}

// LiveWriter receives the stream (the HTTP API writes server-sent events).
type LiveWriter interface {
	Event(name string, data []byte) error
	Comment(text string) error
}

// Live streams the GUI's live list until ctx ends: first the state, then batches at most every 150 ms (rows of
// flows that changed, removed ids, the paused list and the state when they changed), a ping comment every 20 s.
// session "" follows the active session; q filters the rows (terms on bodies, json: and js: are checked in the
// stream against the stored bodies, so a running flow shows up when it finishes).
func (s *Service) Live(ctx context.Context, session, q string, w LiveWriter) error {
	if session != "" {
		if _, err := s.Session(session); err != nil {
			return err
		}
	}
	var flt *query.Filter
	if q != "" {
		f, err := query.Parse(q)
		if err != nil {
			return err
		}
		flt = f
	}
	sub := s.live.subscribe(session, flt)
	defer s.live.unsubscribe(sub)
	st, err := s.UIState(session)
	if err != nil {
		return err
	}
	lastState, _ := json.Marshal(st)
	if err := w.Event("state", lastState); err != nil {
		return err
	}
	lastPaused := int64(-1)
	seq := 0
	tick := time.NewTicker(liveTick)
	defer tick.Stop()
	ping := time.NewTicker(livePing)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ping.C:
			if err := w.Comment("ping"); err != nil {
				return err
			}
		case <-tick.C:
			b := LiveBatch{Session: session}
			if b.Session == "" {
				b.Session = s.Current().ID
			}
			items, removed, gap := sub.take()
			b.Removed, b.Gap = removed, gap
			if len(items) > 0 {
				paused := s.pausedByFlow()
				var bodies query.Bodies
				if sub.deferred {
					if sess, err := s.Session(b.Session); err == nil {
						bodies = bodySource{s: s, sess: sess}
					}
				}
				for _, it := range items {
					if sub.deferred && !sub.filter.Match(it.f, bodies) {
						if it.force {
							b.Removed = append(b.Removed, it.f.ID)
						}
						continue
					}
					b.Rows = append(b.Rows, uiRow(it.f, paused[pausedKey{b.Session, it.f.ID}]))
				}
			}
			if g := s.live.pausedGen.Load(); g != lastPaused {
				lastPaused = g
				list := s.UIPaused(false)
				b.Paused = &list
			}
			if st, err := s.UIState(session); err == nil {
				if j, _ := json.Marshal(st); !bytes.Equal(j, lastState) {
					lastState = j
					b.State = &st
				}
			}
			if len(b.Rows) == 0 && len(b.Removed) == 0 && !b.Gap && b.Paused == nil && b.State == nil {
				continue
			}
			seq++
			b.Seq = seq
			data, _ := json.Marshal(b)
			if err := w.Event("batch", data); err != nil {
				return err
			}
		}
	}
}
