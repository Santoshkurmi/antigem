package service

import (
	"sync"

	"antigem/netcap/internal/flow"
)

// Event: a flow finished.
type Event struct {
	Session string     `json:"session"`
	Flow    *flow.Flow `json:"flow"`
	Paused  string     `json:"paused,omitempty"` // a breakpoint stopped this flow (id of the paused item)
}

// Hub fans finished flows out to listeners (net_wait, the event stream). Slow listeners miss events instead of
// slowing the traffic.
type Hub struct {
	mu   sync.Mutex
	next int
	subs map[int]chan Event
}

// NewHub makes a hub.
func NewHub() *Hub { return &Hub{subs: map[int]chan Event{}} }

// Subscribe returns a channel of events and a function to stop.
func (h *Hub) Subscribe(buffer int) (<-chan Event, func()) {
	if buffer <= 0 {
		buffer = 256
	}
	ch := make(chan Event, buffer)
	h.mu.Lock()
	id := h.next
	h.next++
	h.subs[id] = ch
	h.mu.Unlock()
	return ch, func() {
		h.mu.Lock()
		if c, ok := h.subs[id]; ok {
			delete(h.subs, id)
			close(c)
		}
		h.mu.Unlock()
	}
}

// Publish sends an event to every listener that has room.
func (h *Hub) Publish(e Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, ch := range h.subs {
		select {
		case ch <- e:
		default:
		}
	}
}
