package intercept

import (
	"context"
	"sync"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/sniff"
)

const (
	rawKeepPerDir = 1 << 20  // bytes recorded per direction
	rawChunkKeep  = 64 << 10 // bytes kept of one chunk
)

// relayRaw forwards a connection the engine does not understand and records its bytes as chunks.
func (e *Engine) relayRaw(ctx context.Context, pc *sniff.Conn, t *Target, m Meta) {
	f := e.cfg.Sink.NewFlow(flow.KindTCP, m)
	f.Host, f.Port, f.TLS = t.Host, t.Port, flow.TLSPlain
	f.Server = t.Addr
	t0 := time.Now()
	up, err := e.dialTarget(ctx, t)
	if err != nil {
		f.Error = "connect " + t.Addr + ": " + err.Error()
		e.finish(f, f.Start)
		return
	}
	defer up.Close()
	f.Timings.Connect = ms(time.Since(t0))
	f.Server = up.RemoteAddr().String()
	e.cfg.Sink.Update(f)
	rec := &rawRecorder{e: e, f: f}
	out, in := pipe(pc, up, func(b []byte) { rec.chunk("out", b) }, func(b []byte) { rec.chunk("in", b) })
	f.BytesOut, f.BytesIn = out, in
	f.Req.Size, f.Res.Size = out, in
	f.Messages = rec.n
	e.finish(f, f.Start)
}

type rawRecorder struct {
	e    *Engine
	f    *flow.Flow
	mu   sync.Mutex
	n    int
	kept map[string]int
}

func (r *rawRecorder) chunk(dir string, b []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.kept == nil {
		r.kept = map[string]int{}
	}
	if r.kept[dir] >= rawKeepPerDir {
		return
	}
	keep := b
	cut := false
	if len(keep) > rawChunkKeep {
		keep, cut = keep[:rawChunkKeep], true
	}
	r.kept[dir] += len(keep)
	r.e.cfg.Sink.Message(r.f, flow.Message{FlowID: r.f.ID, Index: r.n, Dir: dir, Time: time.Now(), Type: "chunk",
		Size: len(b), Data: append([]byte(nil), keep...), Truncated: cut})
	r.n++
}
