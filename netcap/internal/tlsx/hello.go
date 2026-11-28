package tlsx

import (
	"encoding/binary"
	"errors"
)

// ClientHello is what the engine needs from a TLS ClientHello before deciding how to handle the connection.
type ClientHello struct {
	SNI  string
	ALPN []string
}

// ErrNotTLS means the bytes are not a TLS handshake.
var ErrNotTLS = errors.New("not a TLS ClientHello")

const maxHello = 64 << 10

// Peeker is satisfied by bufio.Reader (and sniff.PeekConn).
type Peeker interface {
	Peek(n int) ([]byte, error)
}

// PeekClientHello reads (without consuming) the whole ClientHello, which may span several TLS records, and parses it.
func PeekClientHello(p Peeker) (*ClientHello, error) {
	var hs []byte // handshake bytes collected from the records
	off := 0      // bytes of records peeked so far
	for {
		hdr, err := p.Peek(off + 5)
		if err != nil {
			return nil, err
		}
		rec := hdr[off:]
		if rec[0] != 0x16 || rec[1] != 0x03 {
			return nil, ErrNotTLS
		}
		n := int(binary.BigEndian.Uint16(rec[3:5]))
		if n == 0 || off+5+n > maxHello {
			return nil, ErrNotTLS
		}
		all, err := p.Peek(off + 5 + n)
		if err != nil {
			return nil, err
		}
		hs = append(hs, all[off+5:off+5+n]...)
		off += 5 + n
		if len(hs) >= 4 {
			if hs[0] != 1 { // client_hello
				return nil, ErrNotTLS
			}
			need := 4 + (int(hs[1])<<16 | int(hs[2])<<8 | int(hs[3]))
			if need > maxHello {
				return nil, ErrNotTLS
			}
			if len(hs) >= need {
				return parseHello(hs[4:need])
			}
		}
	}
}

func parseHello(b []byte) (*ClientHello, error) {
	r := reader{b: b}
	r.skip(2 + 32) // version, random
	r.skip(int(r.u8()))
	r.skip(int(r.u16()))
	r.skip(int(r.u8()))
	if r.err {
		return nil, ErrNotTLS
	}
	h := &ClientHello{}
	if r.left() == 0 { // no extensions
		return h, nil
	}
	ext := reader{b: r.bytes(int(r.u16()))}
	for ext.left() >= 4 && !ext.err {
		typ := ext.u16()
		data := reader{b: ext.bytes(int(ext.u16()))}
		switch typ {
		case 0: // server_name
			list := reader{b: data.bytes(int(data.u16()))}
			for list.left() > 3 && !list.err {
				kind := list.u8()
				name := list.bytes(int(list.u16()))
				if kind == 0 && h.SNI == "" {
					h.SNI = string(name)
				}
			}
		case 16: // application_layer_protocol_negotiation
			list := reader{b: data.bytes(int(data.u16()))}
			for list.left() > 0 && !list.err {
				p := list.bytes(int(list.u8()))
				if len(p) > 0 {
					h.ALPN = append(h.ALPN, string(p))
				}
			}
		}
	}
	if r.err || ext.err {
		return nil, ErrNotTLS
	}
	return h, nil
}

type reader struct {
	b   []byte
	err bool
}

func (r *reader) left() int { return len(r.b) }

func (r *reader) bytes(n int) []byte {
	if n < 0 || n > len(r.b) {
		r.err = true
		r.b = nil
		return nil
	}
	out := r.b[:n]
	r.b = r.b[n:]
	return out
}

func (r *reader) skip(n int) { r.bytes(n) }

func (r *reader) u8() byte {
	b := r.bytes(1)
	if len(b) < 1 {
		return 0
	}
	return b[0]
}

func (r *reader) u16() uint16 {
	b := r.bytes(2)
	if len(b) < 2 {
		return 0
	}
	return binary.BigEndian.Uint16(b)
}
