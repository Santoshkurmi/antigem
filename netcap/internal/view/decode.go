// Package view turns stored bodies into what people and models read: decoded, typed, pretty, outlined, filtered.
package view

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"errors"
	"io"
	"mime"
	"strings"
	"unicode/utf8"

	"github.com/andybalholm/brotli"
	"github.com/klauspost/compress/zstd"
)

// MaxDecoded caps how much a decoded body may grow to (zip bombs).
const MaxDecoded = 64 << 20

// Decode removes Content-Encoding (gzip, deflate, br, zstd; several = applied in reverse order).
func Decode(raw []byte, encoding string) ([]byte, error) {
	encs := strings.Split(strings.ToLower(encoding), ",")
	out := raw
	for i := len(encs) - 1; i >= 0; i-- {
		e := strings.TrimSpace(encs[i])
		var r io.Reader
		switch e {
		case "", "identity", "none":
			continue
		case "gzip", "x-gzip":
			zr, err := gzip.NewReader(bytes.NewReader(out))
			if err != nil {
				return raw, err
			}
			r = zr
		case "deflate":
			if zr, err := zlib.NewReader(bytes.NewReader(out)); err == nil {
				r = zr
			} else {
				r = flate.NewReader(bytes.NewReader(out))
			}
		case "br":
			r = brotli.NewReader(bytes.NewReader(out))
		case "zstd":
			zr, err := zstd.NewReader(bytes.NewReader(out), zstd.WithDecoderConcurrency(1), zstd.WithDecoderMaxMemory(MaxDecoded))
			if err != nil {
				return raw, err
			}
			defer zr.Close()
			r = zr
		default:
			return raw, errors.New("unknown content-encoding " + e)
		}
		b, err := io.ReadAll(io.LimitReader(r, MaxDecoded+1))
		if err != nil && len(b) == 0 {
			return raw, err
		}
		if len(b) > MaxDecoded {
			return raw, errors.New("decoded body too big")
		}
		out = b
	}
	return out, nil
}

// ShortType gives the short type used everywhere (filters, lists): json, html, xml, js, css, image, media, font,
// form, proto, text, binary. sample is a few bytes of the (decoded) body, used when the content type is missing.
func ShortType(contentType string, sample []byte) string {
	mt, _, _ := mime.ParseMediaType(contentType)
	if mt == "" {
		mt = strings.ToLower(strings.TrimSpace(strings.Split(contentType, ";")[0]))
	}
	switch {
	case mt == "":
	case strings.HasPrefix(mt, "application/grpc"), strings.Contains(mt, "protobuf"), strings.Contains(mt, "x-proto"):
		return "proto"
	case strings.HasSuffix(mt, "json") || strings.Contains(mt, "+json") || mt == "application/x-ndjson":
		return "json"
	case mt == "text/html" || mt == "application/xhtml+xml":
		return "html"
	case strings.HasSuffix(mt, "xml"):
		return "xml"
	case strings.Contains(mt, "javascript") || mt == "application/ecmascript":
		return "js"
	case mt == "text/css":
		return "css"
	case strings.HasPrefix(mt, "image/"):
		return "image"
	case strings.HasPrefix(mt, "audio/"), strings.HasPrefix(mt, "video/"), mt == "application/vnd.apple.mpegurl", mt == "application/x-mpegurl", mt == "application/dash+xml":
		return "media"
	case strings.HasPrefix(mt, "font/"), strings.Contains(mt, "font"):
		return "font"
	case mt == "application/x-www-form-urlencoded", strings.HasPrefix(mt, "multipart/"):
		return "form"
	case strings.HasPrefix(mt, "text/"):
		return "text"
	case mt == "application/octet-stream":
		return sniffType(sample, "binary")
	default:
		return sniffType(sample, "binary")
	}
	return sniffType(sample, "")
}

func sniffType(b []byte, fallback string) string {
	t := bytes.TrimLeft(b, " \t\r\n\xef\xbb\xbf")
	switch {
	case len(t) == 0:
		return fallback
	case t[0] == '{' || t[0] == '[':
		return "json"
	case bytes.HasPrefix(bytes.ToLower(t[:min(len(t), 15)]), []byte("<!doctype html")) || bytes.HasPrefix(bytes.ToLower(t[:min(len(t), 5)]), []byte("<html")):
		return "html"
	case t[0] == '<':
		return "xml"
	case bytes.HasPrefix(b, []byte("\x89PNG")), bytes.HasPrefix(b, []byte("\xff\xd8\xff")), bytes.HasPrefix(b, []byte("GIF8")), bytes.HasPrefix(b, []byte("RIFF")) && len(b) > 12 && string(b[8:12]) == "WEBP":
		return "image"
	}
	if utf8.Valid(b) && isMostlyPrintable(b) {
		if fallback == "" || fallback == "binary" {
			return "text"
		}
	}
	if fallback == "" {
		return "binary"
	}
	return fallback
}

func isMostlyPrintable(b []byte) bool {
	bad := 0
	for _, c := range b {
		if c < 0x20 && c != '\n' && c != '\r' && c != '\t' {
			bad++
		}
	}
	return bad*20 < len(b)+1
}

// IsText reports whether a short type is readable text.
func IsText(t string) bool {
	switch t {
	case "json", "html", "xml", "js", "css", "form", "text":
		return true
	}
	return false
}

// Ext is a file extension for a short type / content type (for files written for the AI).
func Ext(t, contentType string) string {
	switch t {
	case "json":
		return ".json"
	case "html":
		return ".html"
	case "xml":
		return ".xml"
	case "js":
		return ".js"
	case "css":
		return ".css"
	case "form", "text":
		return ".txt"
	case "proto":
		return ".pb"
	}
	if exts, _ := mime.ExtensionsByType(strings.Split(contentType, ";")[0]); len(exts) > 0 {
		return exts[0]
	}
	return ".bin"
}
