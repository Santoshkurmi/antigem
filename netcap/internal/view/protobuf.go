package view

import (
	"encoding/binary"
	"fmt"
	"math"
	"strings"
	"unicode/utf8"
)

// GRPCFrames splits a gRPC body (each message: 1 byte compressed flag, 4 bytes length, payload).
func GRPCFrames(b []byte) (frames [][]byte, compressed []bool, ok bool) {
	for len(b) >= 5 {
		n := int(binary.BigEndian.Uint32(b[1:5]))
		if n < 0 || 5+n > len(b) {
			return frames, compressed, false
		}
		compressed = append(compressed, b[0] == 1)
		frames = append(frames, b[5:5+n])
		b = b[5+n:]
	}
	return frames, compressed, len(b) == 0
}

// Protobuf decodes a protobuf message without its schema: field numbers, wire types and values; length-delimited
// fields are shown as nested messages when they parse as one, else as text or hex.
func Protobuf(b []byte, maxLines int) string {
	if maxLines <= 0 {
		maxLines = 200
	}
	var lines []string
	if !pbMessage(b, 0, &lines, maxLines) {
		return "(not a protobuf message)\n" + Hex(b, 0, min(len(b), 256))
	}
	if len(lines) >= maxLines {
		lines = append(lines, "… cut")
	}
	return strings.Join(lines, "\n")
}

func pbMessage(b []byte, depth int, lines *[]string, maxLines int) bool {
	if len(b) == 0 {
		return true
	}
	start := len(*lines)
	for len(b) > 0 {
		key, n := binary.Uvarint(b)
		if n <= 0 {
			*lines = (*lines)[:start]
			return false
		}
		b = b[n:]
		field, wire := key>>3, key&7
		if field == 0 {
			*lines = (*lines)[:start]
			return false
		}
		ind := strings.Repeat("  ", depth)
		add := func(s string) {
			if len(*lines) < maxLines {
				*lines = append(*lines, ind+s)
			}
		}
		switch wire {
		case 0:
			v, n := binary.Uvarint(b)
			if n <= 0 {
				*lines = (*lines)[:start]
				return false
			}
			b = b[n:]
			add(fmt.Sprintf("%d: %d", field, v))
		case 1:
			if len(b) < 8 {
				*lines = (*lines)[:start]
				return false
			}
			v := binary.LittleEndian.Uint64(b)
			b = b[8:]
			add(fmt.Sprintf("%d: %d (fixed64, double %g)", field, v, math.Float64frombits(v)))
		case 5:
			if len(b) < 4 {
				*lines = (*lines)[:start]
				return false
			}
			v := binary.LittleEndian.Uint32(b)
			b = b[4:]
			add(fmt.Sprintf("%d: %d (fixed32, float %g)", field, v, math.Float32frombits(v)))
		case 2:
			l, n := binary.Uvarint(b)
			if n <= 0 || int(l) > len(b)-n || l > uint64(len(b)) {
				*lines = (*lines)[:start]
				return false
			}
			data := b[n : n+int(l)]
			b = b[n+int(l):]
			if len(data) > 0 && depth < 8 && !looksText(data) {
				mark := len(*lines)
				add(fmt.Sprintf("%d: {", field))
				if pbMessage(data, depth+1, lines, maxLines) {
					add("}")
					continue
				}
				*lines = (*lines)[:mark]
			}
			if looksText(data) {
				s := string(data)
				if len(s) > 200 {
					s = s[:200] + "…"
				}
				add(fmt.Sprintf("%d: %q", field, s))
			} else {
				add(fmt.Sprintf("%d: bytes[%d] %x", field, len(data), data[:min(len(data), 32)]))
			}
		default:
			*lines = (*lines)[:start]
			return false
		}
	}
	return true
}

func looksText(b []byte) bool {
	if !utf8.Valid(b) {
		return false
	}
	for _, c := range b {
		if c < 0x20 && c != '\n' && c != '\r' && c != '\t' {
			return false
		}
	}
	return true
}
