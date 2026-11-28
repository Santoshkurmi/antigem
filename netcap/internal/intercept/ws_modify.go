package intercept

import (
	"bufio"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"antigem/netcap/internal/flow"
)

// WSMessage is one WebSocket message given to a message hook.
type WSMessage struct {
	Dir   string // out = app → server, in = server → app
	Type  string // text, binary
	Data  []byte
	Index int
}

// WSAction is what a message hook decided.
type WSAction struct {
	Changed bool
	Drop    bool
	Type    string // of the changed message (text, binary)
	Data    []byte
	Extra   []WSMessage // sent after it, the same direction
	Replies []WSMessage // sent back, the other direction
}

// WSHook sees every message of one WebSocket. It is called from two goroutines (one per direction).
type WSHook func(m WSMessage) WSAction

const (
	wsHookMsgMax   = 16 << 20 // bigger messages pass through unseen
	wsHookFrameMax = 16 << 20
)

// lockedConn serializes writes: replies to a message go out from the other direction's goroutine.
type lockedConn struct {
	net.Conn
	mu sync.Mutex
}

func (c *lockedConn) write(parts ...[]byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, p := range parts {
		if _, err := c.Conn.Write(p); err != nil {
			return err
		}
	}
	return nil
}

// relayWSHooked forwards a WebSocket message by message, so a hook can change, drop or add messages. Unchanged
// messages go out as the same bytes; changed ones as one new frame. Used only when a message hook applies (and
// then permessage-deflate was taken out of the handshake).
func (e *Engine) relayWSHooked(f *flow.Flow, cli net.Conn, up net.Conn, rec *wsRecorder, hook WSHook) {
	lc := &lockedConn{Conn: cli}
	lu := &lockedConn{Conn: up}
	var seen atomic.Int32
	var changed, dropped atomic.Bool
	var outN, inN int64
	done := make(chan struct{})
	go func() {
		outN = e.wsDirection("out", cli, lu, lc, rec, hook, &seen, &changed, &dropped)
		closeWrite(up)
		close(done)
	}()
	inN = e.wsDirection("in", up, lc, lu, rec, hook, &seen, &changed, &dropped)
	closeWrite(cli)
	select {
	case <-done:
	case <-time.After(2 * time.Minute):
		cli.Close()
		up.Close()
		<-done
	}
	f.BytesOut, f.BytesIn = outN, inN
	f.Messages = int(rec.idx.Load())
	if rec.dropped.Load() > 0 {
		f.Flags = append(f.Flags, "messages-cut")
	}
	if changed.Load() {
		f.Flags = append(f.Flags, "modified")
	}
	if dropped.Load() {
		f.Flags = append(f.Flags, "messages-dropped")
	}
}

// wsDirection reads frames from src and writes them to dst (back = the other way, for replies).
func (e *Engine) wsDirection(dir string, src net.Conn, dst, back *lockedConn, rec *wsRecorder, hook WSHook,
	seen *atomic.Int32, changed, dropped *atomic.Bool) int64 {
	br := bufio.NewReaderSize(src, 32<<10)
	toServer := dir == "out"
	var total int64
	var raw []byte  // frames of the current data message as they came
	var data []byte // its payload, unmasked
	var op byte
	pass := false // the current message is too big: forwarded as it comes
	write := func(c *lockedConn, parts ...[]byte) bool {
		for _, p := range parts {
			total += int64(len(p))
		}
		return c.write(parts...) == nil
	}
	other := "in"
	if dir == "in" {
		other = "out"
	}
	for {
		hdr, n, masked, mask, err := readWSHeader(br)
		if err != nil {
			return total
		}
		fin, opcode := hdr[0]&0x80 != 0, hdr[0]&0x0f
		ctrl := opcode >= 8
		if !ctrl && opcode != 0 && !pass {
			op = opcode
		}
		if n > wsHookFrameMax || (pass && !ctrl) {
			if len(raw) > 0 {
				if !write(dst, raw) {
					return total
				}
				raw, data = nil, nil
			}
			if !write(dst, hdr) {
				return total
			}
			k, err := io.CopyN(dst.Conn, br, n)
			total += k
			if err != nil {
				return total
			}
			if !ctrl {
				pass = !fin
				if fin {
					rec.emit(dir, wsType(op, opcode), int(n), nil, true, false)
				}
			}
			continue
		}
		frame := make([]byte, len(hdr)+int(n))
		copy(frame, hdr)
		if _, err := io.ReadFull(br, frame[len(hdr):]); err != nil {
			return total
		}
		payload := frame[len(hdr):]
		if masked {
			payload = append([]byte(nil), payload...)
			for i := range payload {
				payload[i] ^= mask[i&3]
			}
		}
		if ctrl {
			if !write(dst, frame) {
				return total
			}
			if opcode == 8 {
				rec.emit(dir, "close", len(payload), append([]byte(nil), payload...), false, false)
			}
			continue
		}
		if opcode != 0 {
			raw, data = nil, nil
		}
		raw = append(raw, frame...)
		data = append(data, payload...)
		if len(data) > wsHookMsgMax {
			if !write(dst, raw) {
				return total
			}
			raw, data = nil, nil
			pass = !fin
			continue
		}
		if !fin {
			continue
		}
		typ := wsType(op, op)
		act := hook(WSMessage{Dir: dir, Type: typ, Data: data, Index: int(seen.Add(1)) - 1})
		switch {
		case act.Drop:
			dropped.Store(true)
			rec.emit(dir, "dropped", len(data), keepWS(data), len(data) > wsMsgKeep, false)
		case act.Changed:
			changed.Store(true)
			t := act.Type
			if t == "" {
				t = typ
			}
			if !write(dst, wsFrame(t, act.Data, toServer)) {
				return total
			}
			rec.emit(dir, t, len(act.Data), keepWS(act.Data), len(act.Data) > wsMsgKeep, false)
		default:
			if !write(dst, raw) {
				return total
			}
			rec.emit(dir, typ, len(data), keepWS(data), len(data) > wsMsgKeep, false)
		}
		for _, x := range act.Extra {
			changed.Store(true)
			if !write(dst, wsFrame(x.Type, x.Data, toServer)) {
				return total
			}
			rec.emit(dir, x.Type, len(x.Data), keepWS(x.Data), len(x.Data) > wsMsgKeep, false)
		}
		for _, x := range act.Replies {
			changed.Store(true)
			if back.write(wsFrame(x.Type, x.Data, !toServer)) != nil {
				break
			}
			rec.emit(other, x.Type, len(x.Data), keepWS(x.Data), len(x.Data) > wsMsgKeep, false)
		}
		raw, data = nil, nil
	}
}

func wsType(first, opcode byte) string {
	if first == 1 || opcode == 1 {
		return "text"
	}
	return "binary"
}

func keepWS(b []byte) []byte {
	if len(b) > wsMsgKeep {
		b = b[:wsMsgKeep]
	}
	return append([]byte(nil), b...)
}

// readWSHeader reads one frame header: the bytes, the payload length and the mask.
func readWSHeader(r *bufio.Reader) (hdr []byte, n int64, masked bool, mask [4]byte, err error) {
	h := make([]byte, 2, 14)
	if _, err = io.ReadFull(r, h); err != nil {
		return
	}
	masked = h[1]&0x80 != 0
	switch l := h[1] & 0x7f; l {
	case 126:
		h = h[:4]
		if _, err = io.ReadFull(r, h[2:4]); err != nil {
			return
		}
		n = int64(binary.BigEndian.Uint16(h[2:4]))
	case 127:
		h = h[:10]
		if _, err = io.ReadFull(r, h[2:10]); err != nil {
			return
		}
		n = int64(binary.BigEndian.Uint64(h[2:10]))
	default:
		n = int64(l)
	}
	if n < 0 {
		err = io.ErrUnexpectedEOF
		return
	}
	if masked {
		k := len(h)
		h = h[:k+4]
		if _, err = io.ReadFull(r, h[k:k+4]); err != nil {
			return
		}
		copy(mask[:], h[k:k+4])
	}
	return h, n, masked, mask, nil
}

// wsFrame builds one final frame (masked when it goes to the server).
func wsFrame(typ string, data []byte, masked bool) []byte {
	op := byte(2)
	if typ == "text" {
		op = 1
	}
	b := make([]byte, 0, len(data)+14)
	b = append(b, 0x80|op)
	mbit := byte(0)
	if masked {
		mbit = 0x80
	}
	switch n := len(data); {
	case n < 126:
		b = append(b, mbit|byte(n))
	case n <= 0xffff:
		b = append(b, mbit|126, byte(n>>8), byte(n))
	default:
		b = append(b, mbit|127)
		b = binary.BigEndian.AppendUint64(b, uint64(n))
	}
	if !masked {
		return append(b, data...)
	}
	var key [4]byte
	_, _ = rand.Read(key[:])
	b = append(b, key[:]...)
	start := len(b)
	b = append(b, data...)
	for i := range data {
		b[start+i] ^= key[i&3]
	}
	return b
}
