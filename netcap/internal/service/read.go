package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
	"antigem/netcap/internal/redact"
	"antigem/netcap/internal/view"
)

// ParseID reads f123 / 123 / f123.m4 (returns flow id and message index or -1).
func ParseID(s string) (int64, int, error) {
	s = strings.TrimSpace(strings.ToLower(s))
	s = strings.TrimPrefix(s, "f")
	msg := -1
	if a, b, ok := strings.Cut(s, ".m"); ok {
		s = a
		n, err := strconv.Atoi(b)
		if err != nil {
			return 0, 0, fmt.Errorf("bad message id %q", b)
		}
		msg = n
	}
	id, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, 0, fmt.Errorf("bad flow id %q (use f123)", s)
	}
	return id, msg, nil
}

// ---- bodies ----

// Body returns a flow's body bytes: decoded (Content-Encoding removed) unless raw. max <= 0: all stored bytes.
func (s *Service) Body(sess *Session, f *flow.Flow, side string, raw bool, max int64) ([]byte, bool, error) {
	b := &f.Res
	if side == "req" {
		b = &f.Req
	}
	if b.Hash == "" {
		return nil, false, nil
	}
	data, cut, err := sess.Store.Blobs.Read(b.Hash, 0)
	if err != nil {
		return nil, false, err
	}
	if !raw && b.Encoding != "" && !b.Truncated {
		if d, err := view.Decode(data, b.Encoding); err == nil {
			data = d
		}
	}
	if max > 0 && int64(len(data)) > max {
		data, cut = data[:max], true
	}
	return data, cut || b.Truncated, nil
}

// bodySource gives filters the decoded text bodies (binary and huge ones are skipped).
type bodySource struct {
	s    *Service
	sess *Session
}

func (b bodySource) Body(f *flow.Flow, side string) []byte {
	body := &f.Res
	if side == "req" {
		body = &f.Req
	}
	if body.Hash == "" || body.Size > 4<<20 || (body.Type != "" && !view.IsText(body.Type) && body.Type != "proto") {
		return nil
	}
	data, _, err := b.s.Body(b.sess, f, side, false, 2<<20)
	if err != nil {
		return nil
	}
	return data
}

// ---- list ----

// ListOptions for List.
type ListOptions struct {
	Session  string
	Filter   string
	Limit    int
	Cursor   string
	Sort     string // newest (default), oldest, slowest, largest
	Collapse bool
	Noise    bool
	Secrets  bool
	MaxLimit int  // highest Limit allowed (0 = 500)
	CountAll bool // keep scanning after the page to count every match (Matched)
}

// ListResult of List.
type ListResult struct {
	Session  *Session
	Total    int
	Rows     []Row
	Hidden   int // noise rows left out
	Next     string
	Scanned  int
	ScanCut  bool
	ShowApps bool
	Matched  int  // CountAll: flows that match (up to the scan limit)
	CountCut bool // CountAll: counting stopped at the scan limit
}

// Row is one list line (Count > 1 = collapsed repeats).
type Row struct {
	Flow   *flow.Flow
	Count  int
	LastID int64 // id of the last flow collapsed into this row (where the next page starts)
}

// List lists flows.
func (s *Service) List(o ListOptions) (*ListResult, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return nil, err
	}
	flt, err := query.Parse(o.Filter)
	if err != nil {
		return nil, err
	}
	if o.Limit <= 0 {
		o.Limit = 20
	}
	maxLimit := 500
	if o.MaxLimit > 0 {
		maxLimit = o.MaxLimit
	}
	if o.Limit > maxLimit {
		o.Limit = maxLimit
	}
	sess.Store.Flush()
	where, args := flt.SQL()
	asc := o.Sort == "oldest"
	if o.Cursor != "" {
		id, _, err := ParseID(o.Cursor)
		if err != nil {
			return nil, err
		}
		if asc {
			where += " AND id > ?"
		} else {
			where += " AND id < ?"
		}
		args = append(args, id)
	}
	hideNoise := !o.Noise && !flt.Mentions("noise") && !flt.Mentions("type")
	scanMax := 100000
	if flt.NeedsBody() {
		scanMax = 3000
	}
	res := &ListResult{Session: sess, Total: sess.Store.Stats().Flows}
	bodies := bodySource{s: s, sess: sess}
	sorted := o.Sort == "slowest" || o.Sort == "largest"
	var all []*flow.Flow
	apps := map[string]bool{}
	more := false
	err = sess.Store.Scan(where, args, asc, func(f *flow.Flow) bool {
		res.Scanned++
		if res.Scanned > scanMax {
			if more {
				res.CountCut = true
			} else {
				res.ScanCut = true
			}
			return false
		}
		if !flt.Match(f, bodies) {
			return true
		}
		if hideNoise && query.IsNoise(f) {
			res.Hidden++
			return true
		}
		res.Matched++
		if more { // CountAll: the page is full, only counting now (up to 20000 flows scanned)
			if res.Scanned > 20000 {
				res.CountCut = true
				return false
			}
			return true
		}
		apps[f.App] = true
		if sorted {
			all = append(all, f)
			return len(all) < 20000
		}
		if o.Collapse && len(res.Rows) > 0 {
			last := &res.Rows[len(res.Rows)-1]
			if last.Flow.Endpoint == f.Endpoint && last.Flow.Status == f.Status && last.Flow.Error == "" && f.Error == "" && f.Endpoint != "" {
				last.Count++
				last.LastID = f.ID
				return true
			}
		}
		if len(res.Rows) >= o.Limit {
			more = true
			return o.CountAll
		}
		res.Rows = append(res.Rows, Row{Flow: f, Count: 1, LastID: f.ID})
		return true
	})
	if err != nil {
		return nil, err
	}
	if sorted {
		sort.SliceStable(all, func(i, j int) bool {
			if o.Sort == "slowest" {
				return all[i].Timings.Total > all[j].Timings.Total
			}
			return all[i].Res.Size+all[i].Req.Size > all[j].Res.Size+all[j].Req.Size
		})
		for i, f := range all {
			if i >= o.Limit {
				break
			}
			res.Rows = append(res.Rows, Row{Flow: f, Count: 1})
		}
	} else if more && len(res.Rows) > 0 {
		// the cursor continues after the last row shown, including rows collapsed into it
		res.Next = FID(res.Rows[len(res.Rows)-1].LastID)
	}
	res.ShowApps = len(apps) > 1
	return res, nil
}

// Text renders a list for people and models.
func (r *ListResult) Text(o ListOptions) string {
	var b strings.Builder
	what := "all"
	if o.Filter != "" {
		what = "`" + o.Filter + "`"
	}
	fmt.Fprintf(&b, "session %s · %d flows · showing %d matching %s", r.Session.ID, r.Total, len(r.Rows), what)
	if o.Sort != "" && o.Sort != "newest" {
		b.WriteString(" · " + o.Sort + " first")
	}
	if r.Next != "" {
		b.WriteString(" · more: cursor=" + r.Next)
	}
	b.WriteByte('\n')
	for _, row := range r.Rows {
		b.WriteString(RowText(row.Flow, row.Count, r.ShowApps, o.Secrets))
		b.WriteByte('\n')
	}
	if len(r.Rows) == 0 {
		b.WriteString("(none)\n")
	}
	if r.Hidden > 0 {
		fmt.Fprintf(&b, "+%d static files hidden (images, fonts, media, css): add noise:true to show\n", r.Hidden)
	}
	if r.ScanCut {
		fmt.Fprintf(&b, "(stopped after scanning %d flows: narrow the filter, e.g. host:, since:)\n", r.Scanned-1)
	}
	return b.String()
}

// ---- get ----

// GetOptions for Get.
type GetOptions struct {
	Session  string
	ID       string
	Parts    []string // summary, headers, req.headers, res.headers, req.body, res.body, body, timing, tls, trailers, all
	View     string   // auto (default), raw, pretty, outline, jq, grep, range, hex, protobuf, file
	Side     string   // req or res for view/jq/grep/range/hex/file (default res)
	JQ       string
	Grep     string
	Context  int
	From     int
	Length   int
	MaxChars int
	Secrets  bool
}

// Get shows one flow.
func (s *Service) Get(o GetOptions) (string, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return "", err
	}
	id, msg, err := ParseID(o.ID)
	if err != nil {
		return "", err
	}
	if msg >= 0 {
		return s.MessageText(sess, id, msg, o)
	}
	f, err := sess.Store.Get(id)
	if err != nil {
		return "", fmt.Errorf("%v in session %s (latest is %s)", err, sess.ID, FID(sess.Store.LastID()))
	}
	if o.MaxChars <= 0 {
		o.MaxChars = 4000
	}
	if o.Side == "" {
		o.Side = "res"
	}
	parts := map[string]bool{}
	for _, p := range o.Parts {
		parts[strings.ToLower(strings.TrimSpace(p))] = true
	}
	view := strings.ToLower(o.View)
	if view == "" {
		view = "auto"
	}
	if o.JQ != "" {
		view = "jq"
	} else if o.Grep != "" && view == "auto" {
		view = "grep"
	}
	focused := view != "auto" && view != "outline" && view != "pretty" && view != "raw"
	if len(parts) == 0 {
		if focused { // a jq/grep/range/hex/file call: summary line + that body only
			parts["summary"] = true
			parts[o.Side+".body"] = true
		} else {
			for _, p := range []string{"summary", "headers", "body"} {
				parts[p] = true
			}
		}
	}
	all := parts["all"]
	has := func(p string) bool { return all || parts[p] }
	var b strings.Builder
	b.WriteString(s.summaryLine(f, o.Secrets))
	b.WriteByte('\n')
	if f.Error != "" {
		b.WriteString("error: " + f.Error + "\n")
	}
	if has("timing") || all {
		t := f.Timings
		fmt.Fprintf(&b, "timing: connect %s · tls %s · first byte %s · total %s\n", Dur(t.Connect), Dur(t.TLS), Dur(t.TTFB), Dur(t.Total))
	}
	if (has("tls") || all) && f.TLSInfo != nil {
		t := f.TLSInfo
		fmt.Fprintf(&b, "tls: %s %s alpn=%s sni=%s app offered %v · server cert %s, issuer %s, expires %s\n",
			t.Version, t.Cipher, t.ALPN, t.SNI, t.ClientALPN, t.ServerSubject, t.ServerIssuer, t.ServerExpires)
	}
	switch f.Kind {
	case flow.KindTLS, flow.KindTCP, flow.KindUDP:
		if f.Messages > 0 {
			fmt.Fprintf(&b, "%d chunks recorded: net_ws id=%s\n", f.Messages, FID(f.ID))
		}
		return cutText(b.String(), o.MaxChars), nil
	}
	if has("headers") || has("req.headers") {
		if has("headers") && !all && !has("req.headers") {
			fmt.Fprintf(&b, "req headers (%d): %s\n", len(f.ReqHeaders), HeaderLine(f.ReqHeaders, 14, o.Secrets))
		} else {
			fmt.Fprintf(&b, "req headers (%d):\n%s", len(f.ReqHeaders), HeaderBlock(f.ReqHeaders, o.Secrets))
		}
	}
	if has("body") || has("req.body") {
		b.WriteString(s.bodySection(sess, f, "req", view, o, has("req.body") || all))
	}
	if has("headers") || has("res.headers") {
		if has("headers") && !all && !has("res.headers") {
			fmt.Fprintf(&b, "res headers (%d): %s\n", len(f.ResHeaders), HeaderLine(f.ResHeaders, 14, o.Secrets))
		} else {
			fmt.Fprintf(&b, "res headers (%d):\n%s", len(f.ResHeaders), HeaderBlock(f.ResHeaders, o.Secrets))
		}
	}
	if has("body") || has("res.body") {
		b.WriteString(s.bodySection(sess, f, "res", view, o, has("res.body") || all))
	}
	if (has("trailers") || all || f.Kind == flow.KindGRPC) && len(f.ResTrailers) > 0 {
		fmt.Fprintf(&b, "res trailers: %s\n", HeaderLine(f.ResTrailers, 0, o.Secrets))
	}
	if f.Kind == flow.KindWS {
		fmt.Fprintf(&b, "%d WebSocket messages: net_ws id=%s\n", f.Messages, FID(f.ID))
	}
	return cutText(b.String(), o.MaxChars), nil
}

func (s *Service) summaryLine(f *flow.Flow, secrets bool) string {
	var b strings.Builder
	b.WriteString(FID(f.ID) + " ")
	switch f.Kind {
	case flow.KindTLS, flow.KindTCP, flow.KindUDP:
		b.WriteString(RowText(f, 1, true, secrets)[len(FID(f.ID))+1:])
		return b.String()
	}
	u := f.URL()
	if !secrets && f.Query != "" {
		u = strings.TrimSuffix(u, f.Query) + redact.Query(f.Query)
	}
	method := f.Method
	if f.Kind == flow.KindWS {
		method = "WS"
	}
	fmt.Fprintf(&b, "%s %s → ", method, u)
	if f.Status > 0 {
		b.WriteString(strconv.Itoa(f.Status))
	} else if f.Done {
		b.WriteString("no response")
	} else {
		b.WriteString("pending")
	}
	fmt.Fprintf(&b, " · %s · %s", httpVer(f.HTTPVersion), Dur(f.Timings.Total))
	if f.App != "" {
		b.WriteString(" · app " + f.App)
	}
	b.WriteString(" · " + f.TLS)
	b.WriteString(" · " + f.Start.Format("15:04:05"))
	if f.Kind == flow.KindGRPC {
		b.WriteString(" · gRPC")
	}
	if len(f.Tags) > 0 {
		b.WriteString(" · tags " + strings.Join(f.Tags, ","))
	}
	if f.ResentFrom != 0 {
		b.WriteString(" · resent from " + FID(f.ResentFrom))
	}
	return b.String()
}

// bodySection shows one side's body: small bodies whole, big ones as an outline, or the asked view.
func (s *Service) bodySection(sess *Session, f *flow.Flow, side, mode string, o GetOptions, explicit bool) string {
	body := f.Res
	if side == "req" {
		body = f.Req
	}
	if body.Size == 0 && body.Hash == "" {
		if explicit {
			return side + " body: none\n"
		}
		return ""
	}
	typ := body.Type
	if typ == "" {
		typ = "binary"
	}
	head := fmt.Sprintf("%s body %s %s", side, typ, Size(body.Size))
	if body.Encoding != "" {
		head += " (" + body.Encoding + ")"
	}
	if body.Truncated {
		head += fmt.Sprintf(" · only the first %s were stored", Size(body.Stored))
	}
	if side != o.Side && mode != "auto" && mode != "outline" && mode != "pretty" && mode != "raw" {
		mode = "auto"
	}
	data, _, err := s.Body(sess, f, side, false, 0)
	if err != nil {
		return head + ": cannot read (" + err.Error() + ")\n"
	}
	secretsOK := o.Secrets
	clean := func(b []byte) []byte {
		if secretsOK {
			return b
		}
		return redact.Body(b, typ)
	}
	switch mode {
	case "file":
		path, err := s.writeBodyFile(sess, f, side, clean(data), typ)
		if err != nil {
			return head + ": " + err.Error() + "\n"
		}
		return head + " → written to " + path + "\n"
	case "hex":
		n := o.Length
		if n <= 0 {
			n = 512
		}
		return head + " hex from " + strconv.Itoa(o.From) + ":\n" + view.Hex(data, o.From, n)
	case "range":
		from, n := o.From, o.Length
		if n <= 0 {
			n = 2000
		}
		if from > len(data) {
			from = len(data)
		}
		end := min(len(data), from+n)
		return fmt.Sprintf("%s bytes %d–%d of %d:\n%s\n", head, from, end, len(data), view.ToText(clean(data[from:end])))
	case "jq":
		if typ != "json" {
			return head + ": jq needs a JSON body\n"
		}
		out, err := view.JQ(clean(data), o.JQ, 200)
		if err != nil {
			return head + ": " + err.Error() + "\n"
		}
		return head + " jq `" + o.JQ + "`:\n" + out + "\n"
	case "grep":
		text := view.Pretty(clean(data), typ)
		out, n, err := view.Grep(text, o.Grep, max(o.Context, 0), 30)
		if err != nil {
			return head + ": " + err.Error() + "\n"
		}
		return fmt.Sprintf("%s grep %q: %d matches\n%s\n", head, o.Grep, n, out)
	case "protobuf":
		return head + " protobuf:\n" + s.protoView(data, typ, f) + "\n"
	case "raw":
		return head + ":\n" + view.ToText(clean(data)) + "\n"
	case "pretty":
		return head + ":\n" + view.Pretty(clean(data), typ) + "\n"
	case "outline":
		return head + " outline:\n" + view.Outline(clean(data), typ, 60) + "\n"
	}
	// auto: images and other binary never inline; small text whole; big text as an outline
	switch {
	case typ == "proto":
		return head + " (protobuf, decoded without schema):\n" + Cut(s.protoView(data, typ, f), 1500) + "\n"
	case !view.IsText(typ):
		return head + ": binary, not shown (view=file writes it to a file, view=hex shows bytes)\n"
	case len(data) <= 700:
		return head + ":\n" + view.Pretty(clean(data), typ) + "\n"
	case typ == "json" || typ == "form":
		return head + " outline (view=pretty for all, jq=… to pick fields):\n" + view.Outline(clean(data), typ, 40) + "\n"
	}
	return head + " first lines (view=raw for all, grep=… to search):\n" + Cut(view.ToText(clean(data)), 600) + "\n"
}

func (s *Service) protoView(data []byte, typ string, f *flow.Flow) string {
	if f.Kind == flow.KindGRPC || strings.HasPrefix(f.Res.ContentType, "application/grpc") || strings.HasPrefix(f.Req.ContentType, "application/grpc") {
		frames, comp, ok := view.GRPCFrames(data)
		if ok {
			var parts []string
			for i, fr := range frames {
				if i >= 20 {
					parts = append(parts, fmt.Sprintf("… %d more messages", len(frames)-i))
					break
				}
				if comp[i] {
					parts = append(parts, fmt.Sprintf("message %d: compressed (%d bytes)", i, len(fr)))
					continue
				}
				parts = append(parts, fmt.Sprintf("message %d (%d bytes):\n%s", i, len(fr), view.Protobuf(fr, 80)))
			}
			return strings.Join(parts, "\n")
		}
	}
	return view.Protobuf(data, 150)
}

func cutText(s string, max int) string {
	if len(s) <= max {
		return s
	}
	cut := s[:max]
	if i := strings.LastIndexByte(cut, '\n'); i > max/2 {
		cut = cut[:i+1]
	}
	return cut + fmt.Sprintf("\n… cut at %d of %d characters (ask for one part, a jq/grep view, or a larger max_chars)\n", len(cut), len(s))
}

// MessageText shows one WebSocket message or raw chunk.
func (s *Service) MessageText(sess *Session, id int64, idx int, o GetOptions) (string, error) {
	msgs, err := sess.Store.Messages(id, idx, 1)
	if err != nil {
		return "", err
	}
	if len(msgs) == 0 || msgs[0].Index != idx {
		return "", fmt.Errorf("no message %s.m%d", FID(id), idx)
	}
	m := msgs[0]
	head := fmt.Sprintf("%s.m%d %s %s %s %s", FID(id), m.Index, arrow(m.Dir), m.Dir, m.Type, Size(int64(m.Size)))
	if m.Truncated {
		head += fmt.Sprintf(" (first %s stored)", Size(int64(len(m.Data))))
	}
	if m.Compressed {
		head += " (permessage-deflate, shown inflated)"
	}
	head += " " + m.Time.Format("15:04:05.000")
	data := m.Data
	typ := view.ShortType("", data)
	if !o.Secrets {
		data = redact.Body(data, typ)
	}
	switch {
	case o.View == "hex" || (m.Type != "text" && !view.IsText(typ)):
		n := o.Length
		if n <= 0 {
			n = 512
		}
		return head + "\n" + view.Hex(data, o.From, n), nil
	case o.JQ != "":
		out, err := view.JQ(data, o.JQ, 200)
		if err != nil {
			return head + "\n" + err.Error(), nil
		}
		return head + "\n" + out, nil
	}
	if o.MaxChars <= 0 {
		o.MaxChars = 4000
	}
	return cutText(head+"\n"+view.Pretty(data, typ), o.MaxChars), nil
}

func arrow(dir string) string {
	if dir == "out" {
		return "→"
	}
	return "←"
}

// MessagesOptions for Messages.
type MessagesOptions struct {
	Session string
	ID      string
	Dir     string // in, out or ""
	Text    string // contains (or /regex/)
	From    int
	Limit   int
	Secrets bool
}

// Messages lists a flow's WebSocket messages or raw chunks.
func (s *Service) Messages(o MessagesOptions) (string, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return "", err
	}
	id, _, err := ParseID(o.ID)
	if err != nil {
		return "", err
	}
	f, err := sess.Store.Get(id)
	if err != nil {
		return "", err
	}
	sess.Store.Flush()
	if o.Limit <= 0 {
		o.Limit = 30
	}
	var re interface{ Match([]byte) bool }
	if o.Text != "" {
		r, err := view.Pattern(o.Text)
		if err != nil {
			return "", err
		}
		re = r
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s %s %s · %d messages\n", FID(f.ID), strings.ToUpper(string(f.Kind)), f.Host+f.Path, f.Messages)
	shown, from, next := 0, o.From, -1
	for shown < o.Limit {
		batch, err := sess.Store.Messages(id, from, 200)
		if err != nil {
			return "", err
		}
		if len(batch) == 0 {
			break
		}
		for _, m := range batch {
			from = m.Index + 1
			if o.Dir != "" && m.Dir != o.Dir {
				continue
			}
			if re != nil && !re.Match(m.Data) {
				continue
			}
			if shown >= o.Limit {
				next = m.Index
				break
			}
			data := m.Data
			typ := view.ShortType("", data)
			if !o.Secrets {
				data = redact.Body(data, typ)
			}
			preview := ""
			if m.Type == "text" || view.IsText(typ) {
				preview = strings.ReplaceAll(view.ToText(data), "\n", " ")
			} else if m.Type == "close" {
				preview = fmt.Sprintf("close %x", data)
			} else {
				preview = fmt.Sprintf("%x", data[:min(len(data), 24)])
			}
			fmt.Fprintf(&b, "%s.m%d %s %-4s %-6s %6s %s %s\n", FID(id), m.Index, arrow(m.Dir), m.Dir, m.Type, Size(int64(m.Size)),
				m.Time.Format("15:04:05"), Cut(preview, 120))
			shown++
		}
		if next >= 0 {
			break
		}
	}
	if shown == 0 {
		b.WriteString("(no messages match)\n")
	}
	if next >= 0 {
		fmt.Fprintf(&b, "more: from=%d\n", next)
	}
	return b.String(), nil
}

// ---- wait ----

// Wait returns flows that finish within timeout and match the filter (count of them, default 1).
func (s *Service) Wait(ctx context.Context, filter string, timeout time.Duration, count int, secrets bool) (string, error) {
	flt, err := query.Parse(filter)
	if err != nil {
		return "", err
	}
	if count <= 0 {
		count = 1
	}
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	if timeout > 120*time.Second {
		timeout = 120 * time.Second
	}
	ch, stop := s.Hub.Subscribe(1024)
	defer stop()
	sess := s.Current()
	bodies := bodySource{s: s, sess: sess}
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	var got []*flow.Flow
	for len(got) < count {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-deadline.C:
			if len(got) == 0 {
				c := s.CaptureInfo()
				state := "capture is on"
				if !c.On {
					state = "capture is OFF (net_capture action=start)"
				}
				return fmt.Sprintf("no matching flow in %s (%s)", timeout, state), nil
			}
			return waitText(got, count, secrets), nil
		case e, ok := <-ch:
			if !ok {
				return "", errors.New("stopped")
			}
			if e.Session != sess.ID || e.Paused != "" {
				continue
			}
			if flt.Match(e.Flow, bodies) {
				got = append(got, e.Flow)
			}
		}
	}
	return waitText(got, count, secrets), nil
}

func waitText(got []*flow.Flow, count int, secrets bool) string {
	var b strings.Builder
	if len(got) < count {
		fmt.Fprintf(&b, "%d of %d wanted flows arrived before the timeout:\n", len(got), count)
	}
	for _, f := range got {
		b.WriteString(RowText(f, 1, false, secrets) + "\n")
	}
	return b.String()
}

// FlowJSON returns a flow as JSON (for the HTTP API).
func (s *Service) FlowJSON(sessID, id string) ([]byte, error) {
	sess, err := s.Session(sessID)
	if err != nil {
		return nil, err
	}
	n, _, err := ParseID(id)
	if err != nil {
		return nil, err
	}
	f, err := sess.Store.Get(n)
	if err != nil {
		return nil, err
	}
	return json.Marshal(f)
}
