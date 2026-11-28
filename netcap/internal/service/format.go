package service

import (
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/redact"
	"antigem/netcap/internal/view"
)

// Size formats bytes as 312, 1.1k, 14.2k, 3.4M.
func Size(n int64) string {
	switch {
	case n < 1000:
		return strconv.FormatInt(n, 10)
	case n < 10*1024:
		return fmt.Sprintf("%.1fk", float64(n)/1024)
	case n < 1000*1024:
		return fmt.Sprintf("%.0fk", float64(n)/1024)
	case n < 10*1024*1024:
		return fmt.Sprintf("%.1fM", float64(n)/(1024*1024))
	case n < 1000*1024*1024:
		return fmt.Sprintf("%.0fM", float64(n)/(1024*1024))
	}
	return fmt.Sprintf("%.1fG", float64(n)/(1024*1024*1024))
}

// Dur formats milliseconds as 95ms, 1.2s, 2m10s.
func Dur(msec float64) string {
	switch {
	case msec <= 0:
		return "-"
	case msec < 1000:
		return fmt.Sprintf("%.0fms", msec)
	case msec < 60000:
		return fmt.Sprintf("%.1fs", msec/1000)
	}
	d := time.Duration(msec) * time.Millisecond
	return fmt.Sprintf("%dm%02ds", int(d.Minutes()), int(d.Seconds())%60)
}

// Cut shortens s to n runes with an ellipsis.
func Cut(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	r := []rune(s)
	if n < 2 {
		return string(r[:n])
	}
	return string(r[:n-1]) + "…"
}

// FID formats a flow id.
func FID(id int64) string { return "f" + strconv.FormatInt(id, 10) }

func httpVer(v string) string {
	switch v {
	case "2":
		return "h2"
	case "1.0":
		return "h1.0"
	case "":
		return "  "
	}
	return "h1"
}

// RowText is one line of a flow list.
func RowText(f *flow.Flow, count int, showApp, secrets bool) string {
	var b strings.Builder
	b.WriteString(FID(f.ID))
	b.WriteByte(' ')
	b.WriteString(f.Start.Format("15:04:05"))
	b.WriteByte(' ')
	if showApp && f.App != "" {
		b.WriteString("[" + f.App + "] ")
	}
	switch f.Kind {
	case flow.KindTLS:
		fmt.Fprintf(&b, "TLS  --- %s:%d ", f.Host, f.Port)
		switch f.TLS {
		case flow.TLSPassthrough:
			b.WriteString("not decrypted (passthrough)")
		case flow.TLSUntrusted:
			b.WriteString("not decrypted: the app does not trust the AntiGem certificate")
		default:
			b.WriteString("TLS failed")
		}
		if f.BytesOut+f.BytesIn > 0 {
			fmt.Fprintf(&b, " ↑%s ↓%s", Size(f.BytesOut), Size(f.BytesIn))
		}
		if f.Error != "" && f.TLS != flow.TLSUntrusted {
			b.WriteString(" ERR: " + Cut(f.Error, 90))
		}
		if !f.Done {
			b.WriteString(" open")
		} else {
			b.WriteString(" " + Dur(f.Timings.Total))
		}
		return b.String()
	case flow.KindTCP, flow.KindUDP:
		fmt.Fprintf(&b, "%-4s --- %s:%d ↑%s ↓%s", strings.ToUpper(string(f.Kind)), f.Host, f.Port, Size(f.BytesOut), Size(f.BytesIn))
		if f.Messages > 0 {
			fmt.Fprintf(&b, " %d chunks", f.Messages)
		}
		if f.Error != "" {
			b.WriteString(" ERR: " + Cut(f.Error, 90))
		}
		if !f.Done {
			b.WriteString(" open")
		} else {
			b.WriteString(" " + Dur(f.Timings.Total))
		}
		return b.String()
	}
	method := f.Method
	if f.Kind == flow.KindWS {
		method = "WS"
	}
	status := "---"
	if f.Status > 0 {
		status = strconv.Itoa(f.Status)
	} else if !f.Done {
		status = "..."
	}
	fmt.Fprintf(&b, "%-4s %s %s %s ", method, status, httpVer(f.HTTPVersion), f.Host)
	if f.Port != 0 && !((f.Scheme == "https" && f.Port == 443) || (f.Scheme == "http" && f.Port == 80)) {
		b.WriteString(":" + strconv.Itoa(f.Port) + " ")
	}
	p := f.Path
	if f.Query != "" {
		q := f.Query
		if !secrets {
			q = redact.Query(q)
		}
		p += "?" + q
	}
	b.WriteString(Cut(p, 70))
	if count > 1 {
		fmt.Fprintf(&b, " ×%d", count)
	}
	switch f.Kind {
	case flow.KindWS:
		fmt.Fprintf(&b, "  %d msgs ↑%s ↓%s", f.Messages, Size(f.BytesOut), Size(f.BytesIn))
	default:
		typ := f.Res.Type
		if typ == "" {
			typ = f.Req.Type
		}
		if typ == "" {
			typ = "-"
		}
		b.WriteString("  " + typ + " ")
		if f.Req.Size > 0 {
			fmt.Fprintf(&b, "↑%s ↓%s", Size(f.Req.Size), Size(f.Res.Size))
		} else {
			b.WriteString(Size(f.Res.Size))
		}
	}
	b.WriteString(" " + Dur(f.Timings.Total))
	if len(f.Tags) > 0 {
		b.WriteString(" [" + strings.Join(f.Tags, ",") + "]")
	}
	if f.TLS == flow.TLSPlain && f.Kind != flow.KindTCP {
		b.WriteString(" (plain http)")
	}
	if f.Error != "" {
		b.WriteString(" ERR: " + Cut(f.Error, 90))
	}
	return b.String()
}

// HeaderLine shows headers on one line ("a: 1 · b: 2"), values cut, secrets hidden unless allowed.
func HeaderLine(h flow.Headers, max int, secrets bool) string {
	var parts []string
	for i, kv := range h {
		if max > 0 && i >= max {
			parts = append(parts, fmt.Sprintf("… %d more", len(h)-i))
			break
		}
		v := kv[1]
		if !secrets {
			v = redact.HeaderValue(kv[0], v)
		}
		parts = append(parts, strings.ToLower(kv[0])+": "+Cut(v, 80))
	}
	return strings.Join(parts, " · ")
}

// HeaderBlock shows headers one per line, full values (secrets hidden unless allowed).
func HeaderBlock(h flow.Headers, secrets bool) string {
	var b strings.Builder
	for _, kv := range h {
		v := kv[1]
		if !secrets {
			v = redact.HeaderValue(kv[0], v)
		}
		b.WriteString("  " + kv[0] + ": " + v + "\n")
	}
	return b.String()
}

// shortTypeOf is the short type of a captured body (the sample is decoded first when it is compressed).
func shortTypeOf(contentType, encoding string, sample []byte, size int64) string {
	if size == 0 && contentType == "" {
		return ""
	}
	if encoding != "" && len(sample) > 0 {
		if d, err := view.Decode(sample, encoding); err == nil {
			sample = d
		}
	}
	return view.ShortType(contentType, sample)
}
