package intercept

import (
	"bytes"
	"compress/flate"
	"encoding/binary"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/sniff"
)

const (
	wsMsgKeep   = 256 << 10 // bytes of one message kept
	wsZipKeep   = 4 << 20   // compressed messages are kept whole up to this, so the next ones can still be inflated
	wsFlowKeep  = 32 << 20  // bytes of messages kept per flow
	wsMaxStored = 50000     // messages kept per flow
)

// relayWS forwards a WebSocket connection byte for byte and records every message on the side. With a message
// hook, messages go through the hook instead (relayWSHooked).
func (e *Engine) relayWS(f *flow.Flow, cli *sniff.Conn, uc *upConn, resHeader http.Header, hook WSHook) {
	deflate, clientNoCtx, serverNoCtx := parseDeflate(resHeader.Get("Sec-WebSocket-Extensions"))
	rec := &wsRecorder{e: e, f: f}
	if hook != nil && !deflate {
		e.relayWSHooked(f, cli, &readerConn{Conn: uc.conn, r: uc.br}, rec, hook)
		return
	}
	if hook != nil {
		f.Flags = append(f.Flags, "messages-not-hooked") // the server compressed anyway: observed only
	}
	out := &wsParser{rec: rec, dir: "out", deflate: deflate, noCtx: clientNoCtx}
	in := &wsParser{rec: rec, dir: "in", deflate: deflate, noCtx: serverNoCtx}
	ab, ba := pipe(cli, &readerConn{Conn: uc.conn, r: uc.br}, out.feed, in.feed)
	f.BytesOut, f.BytesIn = ab, ba
	f.Messages = int(rec.idx.Load())
	if rec.dropped.Load() > 0 {
		f.Flags = append(f.Flags, "messages-cut")
	}
}

func parseDeflate(ext string) (on, clientNoCtx, serverNoCtx bool) {
	for _, e := range strings.Split(ext, ",") {
		parts := strings.Split(e, ";")
		if strings.TrimSpace(strings.ToLower(parts[0])) != "permessage-deflate" {
			continue
		}
		on = true
		for _, p := range parts[1:] {
			switch strings.TrimSpace(strings.ToLower(strings.SplitN(p, "=", 2)[0])) {
			case "client_no_context_takeover":
				clientNoCtx = true
			case "server_no_context_takeover":
				serverNoCtx = true
			}
		}
	}
	return
}

type wsRecorder struct {
	e       *Engine
	f       *flow.Flow
	idx     atomic.Int32
	kept    atomic.Int64
	dropped atomic.Int32
}

func (r *wsRecorder) emit(dir, typ string, size int, data []byte, truncated, compressed bool) {
	i := int(r.idx.Add(1)) - 1
	if i >= wsMaxStored || r.kept.Load() > wsFlowKeep {
		r.dropped.Add(1)
		return
	}
	r.kept.Add(int64(len(data)))
	r.e.cfg.Sink.Message(r.f, flow.Message{FlowID: r.f.ID, Index: i, Dir: dir, Time: time.Now(), Type: typ, Size: size,
		Data: data, Truncated: truncated, Compressed: compressed})
}

// wsParser follows the frames of one direction from the raw bytes.
type wsParser struct {
	rec     *wsRecorder
	dir     string
	deflate bool
	noCtx   bool

	hdr      []byte
	need     int64 // payload bytes left in the current frame
	inFrame  bool
	fin      bool
	opcode   byte
	rsv1     bool
	masked   bool
	mask     [4]byte
	maskPos  int
	ctrl     []byte // payload of the current control frame
	msg      []byte // payload of the current data message
	msgSize  int
	msgType  byte
	msgZip   bool
	msgCut   bool
	window   []byte // inflated history (permessage-deflate with context takeover)
	broken   bool   // lost track of the frames or of the deflate window
	zipBroke bool
}

func (p *wsParser) feed(b []byte) {
	for len(b) > 0 && !p.broken {
		if !p.inFrame {
			b = p.readHeader(b)
			continue
		}
		k := int64(len(b))
		if k > p.need {
			k = p.need
		}
		p.payload(b[:k])
		b = b[k:]
		p.need -= k
		if p.need == 0 {
			p.frameEnd()
		}
	}
}

func (p *wsParser) readHeader(b []byte) []byte {
	for len(b) > 0 {
		p.hdr = append(p.hdr, b[0])
		b = b[1:]
		if len(p.hdr) < 2 {
			continue
		}
		n := 2
		l7 := p.hdr[1] & 0x7f
		switch l7 {
		case 126:
			n += 2
		case 127:
			n += 8
		}
		if p.hdr[1]&0x80 != 0 {
			n += 4
		}
		if len(p.hdr) < n {
			continue
		}
		h := p.hdr
		p.fin = h[0]&0x80 != 0
		p.rsv1 = h[0]&0x40 != 0
		p.opcode = h[0] & 0x0f
		p.masked = h[1]&0x80 != 0
		off := 2
		switch l7 {
		case 126:
			p.need = int64(binary.BigEndian.Uint16(h[2:4]))
			off = 4
		case 127:
			p.need = int64(binary.BigEndian.Uint64(h[2:10]))
			off = 10
		default:
			p.need = int64(l7)
		}
		if p.need < 0 {
			p.broken = true
			return nil
		}
		if p.masked {
			copy(p.mask[:], h[off:off+4])
		}
		p.maskPos = 0
		p.hdr = p.hdr[:0]
		p.inFrame = true
		p.ctrl = p.ctrl[:0]
		if p.opcode != 0 && p.opcode < 8 { // first frame of a data message
			p.msg, p.msgSize, p.msgType, p.msgZip, p.msgCut = nil, 0, p.opcode, p.rsv1 && p.deflate, false
		}
		if p.need == 0 {
			p.frameEnd()
		}
		return b
	}
	return b
}

func (p *wsParser) payload(b []byte) {
	ctrl := p.opcode >= 8
	keep := wsMsgKeep
	if p.msgZip {
		keep = wsZipKeep
	}
	for _, c := range b {
		if p.masked {
			c ^= p.mask[p.maskPos&3]
			p.maskPos++
		}
		if ctrl {
			if len(p.ctrl) < 125 {
				p.ctrl = append(p.ctrl, c)
			}
			continue
		}
		p.msgSize++
		if len(p.msg) < keep {
			p.msg = append(p.msg, c)
		} else {
			p.msgCut = true
		}
	}
}

func (p *wsParser) frameEnd() {
	p.inFrame = false
	switch p.opcode {
	case 8: // close
		p.rec.emit(p.dir, "close", len(p.ctrl), append([]byte(nil), p.ctrl...), false, false)
		return
	case 9, 10: // ping, pong: not listed
		return
	}
	if !p.fin {
		return
	}
	data := p.msg
	typ := "binary"
	if p.msgType == 1 {
		typ = "text"
	}
	zip := p.msgZip
	if zip {
		if p.msgCut || p.zipBroke {
			p.zipBroke = !p.noCtx // with context takeover every later message depends on this one
			p.rec.emit(p.dir, typ, p.msgSize, nil, true, true)
			p.msg = nil
			return
		}
		if inflated, ok := p.inflate(data); ok {
			data = inflated
		} else {
			p.zipBroke = !p.noCtx
		}
	}
	cut := p.msgCut
	if len(data) > wsMsgKeep {
		data, cut = data[:wsMsgKeep], true
	}
	p.rec.emit(p.dir, typ, p.msgSize, data, cut, zip)
	p.msg = nil
}

func (p *wsParser) inflate(data []byte) ([]byte, bool) {
	src := append(append([]byte(nil), data...), 0x00, 0x00, 0xff, 0xff)
	var r io.ReadCloser
	if p.noCtx || len(p.window) == 0 {
		r = flate.NewReader(bytes.NewReader(src))
	} else {
		r = flate.NewReaderDict(bytes.NewReader(src), p.window)
	}
	out, err := io.ReadAll(io.LimitReader(r, 16<<20))
	if err != nil && err != io.ErrUnexpectedEOF && len(out) == 0 {
		return nil, false
	}
	if !p.noCtx {
		p.window = append(p.window, out...)
		if len(p.window) > 32<<10 {
			p.window = append([]byte(nil), p.window[len(p.window)-32<<10:]...)
		}
	}
	return out, true
}
