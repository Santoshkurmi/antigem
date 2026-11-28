package service

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
	"antigem/netcap/internal/redact"
	"antigem/netcap/internal/view"
)

type mirrorItem struct {
	sess *Session
	f    *flow.Flow
}

// mirrorLoop writes the flows the capture's mirror filter picks to files (off the traffic path).
func (s *Service) mirrorLoop() {
	for it := range s.mirrorCh {
		dir := filepath.Join(it.sess.Dir, "files")
		_, _ = s.writeFlowFolder(it.sess, it.f, dir, s.Settings().ShowSecretsToAI)
	}
}

var unsafeName = regexp.MustCompile(`[^A-Za-z0-9._-]+`)

func flowFolderName(f *flow.Flow) string {
	p := strings.Trim(unsafeName.ReplaceAllString(f.Path, "-"), "-")
	name := fmt.Sprintf("f%06d_%s_%s", f.ID, strings.ToUpper(nonEmpty(f.Method, string(f.Kind))), unsafeName.ReplaceAllString(f.Host, "-"))
	if p != "" {
		name += "_" + Cut(p, 50)
	}
	return strings.TrimSuffix(name, "…")
}

func nonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// writeFlowFolder writes one flow as request.http, response.http, meta.json and body files.
func (s *Service) writeFlowFolder(sess *Session, f *flow.Flow, dir string, secrets bool) (string, error) {
	fd := filepath.Join(dir, flowFolderName(f))
	if err := os.MkdirAll(fd, 0o700); err != nil {
		return "", err
	}
	meta := map[string]any{"id": FID(f.ID), "kind": f.Kind, "app": f.App, "url": f.URL(), "method": f.Method,
		"status": f.Status, "http": f.HTTPVersion, "tls": f.TLS, "start": f.Start.Format(time.RFC3339Nano),
		"timings_ms": f.Timings, "req": f.Req, "res": f.Res, "error": f.Error, "tags": f.Tags, "endpoint": f.Endpoint,
		"messages": f.Messages, "tls_info": f.TLSInfo}
	mj, _ := json.MarshalIndent(meta, "", "  ")
	_ = os.WriteFile(filepath.Join(fd, "meta.json"), mj, 0o600)
	if f.Kind == flow.KindHTTP || f.Kind == flow.KindWS || f.Kind == flow.KindGRPC {
		for _, side := range []string{"req", "res"} {
			text, bodyFile := s.httpText(sess, f, side, secrets)
			name := "request"
			if side == "res" {
				name = "response"
			}
			_ = os.WriteFile(filepath.Join(fd, name+".http"), []byte(text), 0o600)
			if bodyFile != nil {
				_ = os.WriteFile(filepath.Join(fd, name+".body"+bodyFile.ext), bodyFile.data, 0o600)
			}
		}
	}
	if f.Messages > 0 {
		var lines []string
		for from := 0; ; {
			batch, err := sess.Store.Messages(f.ID, from, 500)
			if err != nil || len(batch) == 0 {
				break
			}
			for _, m := range batch {
				from = m.Index + 1
				data := m.Data
				typ := view.ShortType("", data)
				entry := map[string]any{"i": m.Index, "dir": m.Dir, "t": m.Time.Format(time.RFC3339Nano), "type": m.Type, "size": m.Size}
				if m.Type == "text" || view.IsText(typ) {
					if !secrets {
						data = redact.Body(data, typ)
					}
					entry["text"] = view.ToText(data)
				} else {
					entry["base64"] = base64.StdEncoding.EncodeToString(data)
				}
				if m.Truncated {
					entry["truncated"] = true
				}
				j, _ := json.Marshal(entry)
				lines = append(lines, string(j))
			}
		}
		_ = os.WriteFile(filepath.Join(fd, "messages.jsonl"), []byte(strings.Join(lines, "\n")+"\n"), 0o600)
	}
	// one line per flow in the folder's index
	idx, err := os.OpenFile(filepath.Join(dir, "index.jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err == nil {
		line, _ := json.Marshal(map[string]any{"id": FID(f.ID), "row": RowText(f, 1, true, secrets), "folder": filepath.Base(fd)})
		idx.Write(append(line, '\n'))
		idx.Close()
	}
	return fd, nil
}

type bodyFile struct {
	ext  string
	data []byte
}

// httpText renders one side as raw HTTP text with a decoded text body; binary or big bodies go to a separate file.
func (s *Service) httpText(sess *Session, f *flow.Flow, side string, secrets bool) (string, *bodyFile) {
	var b strings.Builder
	h, body := f.ReqHeaders, f.Req
	if side == "req" {
		q := f.Query
		if !secrets {
			q = redact.Query(q)
		}
		target := f.Path
		if q != "" {
			target += "?" + q
		}
		fmt.Fprintf(&b, "%s %s HTTP/%s\n", f.Method, target, nonEmpty(f.HTTPVersion, "1.1"))
	} else {
		h, body = f.ResHeaders, f.Res
		if f.Status == 0 {
			fmt.Fprintf(&b, "(no response) %s\n", f.Error)
			return b.String(), nil
		}
		fmt.Fprintf(&b, "HTTP/%s %d\n", nonEmpty(f.HTTPVersion, "1.1"), f.Status)
	}
	for _, kv := range h {
		v := kv[1]
		if !secrets {
			v = redact.HeaderValue(kv[0], v)
		}
		fmt.Fprintf(&b, "%s: %s\n", kv[0], v)
	}
	b.WriteString("\n")
	if body.Hash == "" {
		return b.String(), nil
	}
	data, _, err := s.Body(sess, f, side, false, 0)
	if err != nil {
		fmt.Fprintf(&b, "(body not readable: %v)\n", err)
		return b.String(), nil
	}
	typ := body.Type
	if view.IsText(typ) && len(data) <= 256<<10 {
		if !secrets {
			data = redact.Body(data, typ)
		}
		b.WriteString(view.Pretty(data, typ))
		b.WriteString("\n")
		return b.String(), nil
	}
	if view.IsText(typ) && !secrets {
		data = redact.Body(data, typ)
	}
	ext := view.Ext(typ, body.ContentType)
	fmt.Fprintf(&b, "(%s body, %s: see %s.body%s)\n", typ, Size(int64(len(data))), map[string]string{"req": "request", "res": "response"}[side], ext)
	return b.String(), &bodyFile{ext: ext, data: data}
}

func (s *Service) writeBodyFile(sess *Session, f *flow.Flow, side string, data []byte, typ string) (string, error) {
	dir := filepath.Join(sess.Dir, "out", "bodies")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	body := f.Res
	if side == "req" {
		body = f.Req
	}
	p := filepath.Join(dir, fmt.Sprintf("%s.%s%s", FID(f.ID), side, view.Ext(typ, body.ContentType)))
	return p, os.WriteFile(p, data, 0o600)
}

// ExportOptions for Export.
type ExportOptions struct {
	Session string
	Filter  string
	Format  string // folder (default), har, curl, http
	Path    string
	Limit   int
	Secrets bool
}

// Export writes flows to files and returns where.
func (s *Service) Export(o ExportOptions) (string, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return "", err
	}
	flt, err := query.Parse(o.Filter)
	if err != nil {
		return "", err
	}
	if o.Limit <= 0 {
		o.Limit = 2000
	}
	sess.Store.Flush()
	where, args := flt.SQL()
	bodies := bodySource{s: s, sess: sess}
	var flows []*flow.Flow
	_ = sess.Store.Scan(where, args, true, func(f *flow.Flow) bool {
		if flt.Match(f, bodies) {
			flows = append(flows, f)
		}
		return len(flows) < o.Limit
	})
	if len(flows) == 0 {
		return "no flows match", nil
	}
	format := strings.ToLower(o.Format)
	if format == "" {
		format = "folder"
	}
	stamp := time.Now().Format("20060102-150405")
	switch format {
	case "folder":
		dir := o.Path
		if dir == "" {
			dir = filepath.Join(sess.Dir, "out", "export-"+stamp)
		}
		for _, f := range flows {
			if _, err := s.writeFlowFolder(sess, f, dir, o.Secrets); err != nil {
				return "", err
			}
		}
		return fmt.Sprintf("%d flows written to %s (one folder each: request.http, response.http, meta.json; index.jsonl lists them)", len(flows), dir), nil
	case "har":
		p := o.Path
		if p == "" {
			p = filepath.Join(sess.Dir, "out", "export-"+stamp+".har")
		}
		har := s.har(sess, flows, o.Secrets)
		if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
			return "", err
		}
		data, _ := json.MarshalIndent(har, "", " ")
		if err := os.WriteFile(p, data, 0o600); err != nil {
			return "", err
		}
		return fmt.Sprintf("%d flows written to %s (HAR 1.2)", len(flows), p), nil
	case "curl":
		var lines []string
		for i, f := range flows {
			if i >= 20 {
				lines = append(lines, fmt.Sprintf("# … %d more (export as folder or har)", len(flows)-i))
				break
			}
			lines = append(lines, "# "+FID(f.ID)+"\n"+s.curl(sess, f, o.Secrets))
		}
		return strings.Join(lines, "\n\n"), nil
	case "http":
		var parts []string
		for i, f := range flows {
			if i >= 5 {
				parts = append(parts, fmt.Sprintf("… %d more (export as folder)", len(flows)-i))
				break
			}
			t, _ := s.httpText(sess, f, "req", o.Secrets)
			parts = append(parts, "### "+FID(f.ID)+" "+f.URL()+"\n"+t)
		}
		return strings.Join(parts, "\n"), nil
	}
	return "", fmt.Errorf("format must be folder, har, curl or http")
}

func (s *Service) curl(sess *Session, f *flow.Flow, secrets bool) string {
	var b strings.Builder
	b.WriteString("curl")
	if f.Method != "GET" {
		b.WriteString(" -X " + f.Method)
	}
	u := f.URL()
	if !secrets && f.Query != "" {
		u = strings.TrimSuffix(u, f.Query) + redact.Query(f.Query)
	}
	b.WriteString(" " + shellQuote(u))
	for _, kv := range f.ReqHeaders {
		k := strings.ToLower(kv[0])
		if k == "host" || k == ":authority" || k == "content-length" {
			continue
		}
		v := kv[1]
		if !secrets {
			v = redact.HeaderValue(kv[0], v)
		}
		b.WriteString(" \\\n  -H " + shellQuote(kv[0]+": "+v))
	}
	if f.Req.Hash != "" {
		data, _, _ := s.Body(sess, f, "req", true, 64<<10)
		if view.IsText(f.Req.Type) && f.Req.Encoding == "" {
			if !secrets {
				data = redact.Body(data, f.Req.Type)
			}
			b.WriteString(" \\\n  --data-raw " + shellQuote(string(data)))
		} else {
			b.WriteString(" \\\n  --data-binary @body.bin  # binary body: export view=file")
		}
	}
	if f.Res.Encoding != "" {
		b.WriteString(" \\\n  --compressed")
	}
	return b.String()
}

func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

func (s *Service) har(sess *Session, flows []*flow.Flow, secrets bool) map[string]any {
	hdrs := func(h flow.Headers) []map[string]string {
		out := []map[string]string{}
		for _, kv := range h {
			if strings.HasPrefix(kv[0], ":") {
				continue
			}
			v := kv[1]
			if !secrets {
				v = redact.HeaderValue(kv[0], v)
			}
			out = append(out, map[string]string{"name": kv[0], "value": v})
		}
		return out
	}
	var entries []map[string]any
	for _, f := range flows {
		if f.Kind != flow.KindHTTP && f.Kind != flow.KindWS && f.Kind != flow.KindGRPC {
			continue
		}
		qs := []map[string]string{}
		if vals, err := url.ParseQuery(f.Query); err == nil {
			for k, vs := range vals {
				for _, v := range vs {
					if !secrets && (redact.SensitiveKey(k) || k == "key") {
						v = redact.Hide(v)
					}
					qs = append(qs, map[string]string{"name": k, "value": v})
				}
			}
		}
		req := map[string]any{"method": f.Method, "url": f.URL(), "httpVersion": "HTTP/" + nonEmpty(f.HTTPVersion, "1.1"),
			"headers": hdrs(f.ReqHeaders), "queryString": qs, "cookies": []any{}, "headersSize": -1, "bodySize": f.Req.Size}
		if f.Req.Hash != "" {
			data, _, _ := s.Body(sess, f, "req", false, 4<<20)
			if !secrets {
				data = redact.Body(data, f.Req.Type)
			}
			pd := map[string]any{"mimeType": f.Req.ContentType}
			if view.IsText(f.Req.Type) {
				pd["text"] = string(data)
			} else {
				pd["text"] = base64.StdEncoding.EncodeToString(data)
				pd["encoding"] = "base64"
			}
			req["postData"] = pd
		}
		content := map[string]any{"size": f.Res.Size, "mimeType": f.Res.ContentType}
		if f.Res.Hash != "" {
			data, _, _ := s.Body(sess, f, "res", false, 4<<20)
			if view.IsText(f.Res.Type) {
				if !secrets {
					data = redact.Body(data, f.Res.Type)
				}
				content["text"] = string(data)
			} else {
				content["text"] = base64.StdEncoding.EncodeToString(data)
				content["encoding"] = "base64"
			}
		}
		res := map[string]any{"status": f.Status, "statusText": "", "httpVersion": "HTTP/" + nonEmpty(f.HTTPVersion, "1.1"),
			"headers": hdrs(f.ResHeaders), "cookies": []any{}, "content": content, "redirectURL": "", "headersSize": -1, "bodySize": f.Res.Size}
		e := map[string]any{"startedDateTime": f.Start.Format(time.RFC3339Nano), "time": f.Timings.Total, "request": req, "response": res,
			"cache": map[string]any{}, "timings": map[string]any{"send": 0, "wait": f.Timings.TTFB, "receive": max(0, f.Timings.Total-f.Timings.TTFB),
				"connect": orNeg(f.Timings.Connect), "ssl": orNeg(f.Timings.TLS)}, "_netcapId": FID(f.ID)}
		if f.Kind == flow.KindWS {
			var msgs []map[string]any
			batch, _ := sess.Store.Messages(f.ID, 0, 5000)
			for _, m := range batch {
				t := "receive"
				if m.Dir == "out" {
					t = "send"
				}
				op := 1
				data := string(m.Data)
				if m.Type == "binary" {
					op = 2
					data = base64.StdEncoding.EncodeToString(m.Data)
				} else if !secrets {
					data = string(redact.Body(m.Data, view.ShortType("", m.Data)))
				}
				msgs = append(msgs, map[string]any{"type": t, "time": float64(m.Time.UnixMicro()) / 1e6, "opcode": op, "data": data})
			}
			e["_webSocketMessages"] = msgs
		}
		entries = append(entries, e)
	}
	return map[string]any{"log": map[string]any{"version": "1.2", "creator": map[string]string{"name": "AntiGem Network Interceptor", "version": "1"},
		"entries": entries}}
}

func orNeg(v float64) float64 {
	if v == 0 {
		return -1
	}
	return v
}
