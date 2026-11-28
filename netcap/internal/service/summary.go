package service

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"

	"github.com/itchyny/gojq"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/query"
	"antigem/netcap/internal/redact"
)

// SummaryOptions for Summarize.
type SummaryOptions struct {
	Session string
	Filter  string
	GroupBy string // endpoint (default), host, app, status, type, kind, tag
	Values  string // expression whose distinct values are counted
	Limit   int
	Noise   bool
	Secrets bool
}

type group struct {
	key      string
	host     string
	count    int
	statuses map[string]int
	types    map[string]int
	minMs    float64
	maxMs    float64
	sumMs    float64
	bytes    int64
	paths    map[string]bool
	apps     map[string]bool
	firstID  int64
	lastID   int64
	errors   int
}

// Summarize groups flows (the API map) or counts the values of an expression.
func (s *Service) Summarize(o SummaryOptions) (string, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return "", err
	}
	flt, err := query.Parse(o.Filter)
	if err != nil {
		return "", err
	}
	if o.Limit <= 0 {
		o.Limit = 40
	}
	sess.Store.Flush()
	where, args := flt.SQL()
	bodies := bodySource{s: s, sess: sess}
	hideNoise := !o.Noise && !flt.Mentions("noise") && !flt.Mentions("type")
	scanMax := 50000
	if flt.NeedsBody() || strings.Contains(o.Values, "json") {
		scanMax = 3000
	}
	var vals *valueCounter
	if o.Values != "" {
		if vals, err = newValueCounter(o.Values, o.Secrets); err != nil {
			return "", err
		}
	}
	groups := map[string]*group{}
	scanned, matched, hidden := 0, 0, 0
	cut := false
	err = sess.Store.Scan(where, args, true, func(f *flow.Flow) bool {
		scanned++
		if scanned > scanMax {
			cut = true
			return false
		}
		if !flt.Match(f, bodies) {
			return true
		}
		if hideNoise && query.IsNoise(f) {
			hidden++
			return true
		}
		matched++
		if vals != nil {
			vals.add(f, bodies)
			return true
		}
		key, host := groupKey(f, o.GroupBy)
		g := groups[key]
		if g == nil {
			g = &group{key: key, host: host, statuses: map[string]int{}, types: map[string]int{}, paths: map[string]bool{},
				apps: map[string]bool{}, minMs: -1, firstID: f.ID}
			groups[key] = g
		}
		g.count++
		g.lastID = f.ID
		st := strconv.Itoa(f.Status)
		if f.Status == 0 {
			st = "---"
		}
		if f.Error != "" {
			g.errors++
		}
		g.statuses[st]++
		rt := f.Res.Type
		if rt == "" {
			rt = "-"
		}
		qt := f.Req.Type
		g.types[qt+"→"+rt]++
		ms := f.Timings.Total
		if g.minMs < 0 || ms < g.minMs {
			g.minMs = ms
		}
		if ms > g.maxMs {
			g.maxMs = ms
		}
		g.sumMs += ms
		g.bytes += f.Req.Size + f.Res.Size
		if len(g.paths) < 1000 {
			g.paths[f.Path] = true
		}
		g.apps[f.App] = true
		return true
	})
	if err != nil {
		return "", err
	}
	var b strings.Builder
	what := "all flows"
	if o.Filter != "" {
		what = "`" + o.Filter + "`"
	}
	fmt.Fprintf(&b, "session %s · %d flows match %s", sess.ID, matched, what)
	if hidden > 0 {
		fmt.Fprintf(&b, " (+%d static files hidden, noise:true to include)", hidden)
	}
	if cut {
		fmt.Fprintf(&b, " · stopped after %d flows, narrow the filter", scanMax)
	}
	b.WriteByte('\n')
	if vals != nil {
		b.WriteString(vals.text(o.Limit))
		return b.String(), nil
	}
	list := make([]*group, 0, len(groups))
	for _, g := range groups {
		list = append(list, g)
	}
	by := o.GroupBy
	if by == "" {
		by = "endpoint"
	}
	if by == "endpoint" {
		// hosts by flow count, endpoints by count inside a host
		hostCount := map[string]int{}
		for _, g := range list {
			hostCount[g.host] += g.count
		}
		sort.Slice(list, func(i, j int) bool {
			a, c := list[i], list[j]
			if a.host != c.host {
				if hostCount[a.host] != hostCount[c.host] {
					return hostCount[a.host] > hostCount[c.host]
				}
				return a.host < c.host
			}
			if a.count != c.count {
				return a.count > c.count
			}
			return a.key < c.key
		})
		lastHost := "\x00"
		shown := 0
		for _, g := range list {
			if shown >= o.Limit {
				fmt.Fprintf(&b, "… %d more endpoints (raise limit, or filter by host:)\n", len(list)-shown)
				break
			}
			if g.host != lastHost {
				fmt.Fprintf(&b, "%s · %d flows\n", g.host, hostCount[g.host])
				lastHost = g.host
			}
			ep := g.key
			// key is "METHOD host/path?q": show "METHOD /path?q" (TCP/TLS/UDP keys are "KIND host:port")
			if m, rest, ok := strings.Cut(g.key, " "); ok {
				if strings.HasPrefix(rest, g.host) {
					ep = strings.TrimSpace(m + " " + strings.TrimPrefix(rest, g.host))
				} else {
					ep = m + " " + rest
				}
			}
			fmt.Fprintf(&b, "  %-44s ×%-4d %s  %s  %s", Cut(ep, 60), g.count, mapText(g.statuses, 3), mapText(g.types, 2), msRange(g))
			if len(g.paths) > 1 && strings.Contains(ep, "{") {
				fmt.Fprintf(&b, "  %d distinct paths", len(g.paths))
			}
			fmt.Fprintf(&b, "  e.g. %s\n", FID(g.lastID))
			shown++
		}
		return b.String(), nil
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].count != list[j].count {
			return list[i].count > list[j].count
		}
		return list[i].key < list[j].key
	})
	for i, g := range list {
		if i >= o.Limit {
			fmt.Fprintf(&b, "… %d more groups\n", len(list)-i)
			break
		}
		fmt.Fprintf(&b, "  %-36s ×%-5d %s  %s  %s  %s  e.g. %s\n", Cut(g.key, 40), g.count, mapText(g.statuses, 3), mapText(g.types, 2), msRange(g), Size(g.bytes), FID(g.lastID))
	}
	return b.String(), nil
}

func groupKey(f *flow.Flow, by string) (key, host string) {
	host = strings.ToLower(f.Host)
	if f.Port != 0 && !((f.Scheme == "https" && f.Port == 443) || (f.Scheme == "http" && f.Port == 80)) && f.Kind != flow.KindTLS && f.Kind != flow.KindTCP && f.Kind != flow.KindUDP {
		host += ":" + strconv.Itoa(f.Port)
	}
	switch by {
	case "host":
		return host, host
	case "app":
		if f.App == "" {
			return "(unknown app)", host
		}
		return f.App, host
	case "status":
		if f.Error != "" {
			return "error", host
		}
		return strconv.Itoa(f.Status), host
	case "type":
		if f.Res.Type == "" {
			return "-", host
		}
		return f.Res.Type, host
	case "kind":
		return string(f.Kind), host
	case "tag":
		if len(f.Tags) == 0 {
			return "(no tag)", host
		}
		return strings.Join(f.Tags, ","), host
	}
	return f.Endpoint, host
}

func mapText(m map[string]int, max int) string {
	type kv struct {
		k string
		v int
	}
	var list []kv
	for k, v := range m {
		list = append(list, kv{k, v})
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].v != list[j].v {
			return list[i].v > list[j].v
		}
		return list[i].k < list[j].k
	})
	if len(list) == 1 {
		return list[0].k
	}
	var parts []string
	for i, e := range list {
		if i >= max {
			parts = append(parts, "…")
			break
		}
		parts = append(parts, fmt.Sprintf("%s×%d", e.k, e.v))
	}
	return strings.Join(parts, " ")
}

func msRange(g *group) string {
	if g.count == 1 || g.minMs == g.maxMs {
		return Dur(g.maxMs)
	}
	return Dur(g.minMs) + "–" + Dur(g.maxMs)
}

// valueCounter counts distinct values of req.header.X, res.header.X, query.KEY, host, path, app, status,
// req.json:<jq>, res.json:<jq>.
type valueCounter struct {
	expr    string
	get     func(f *flow.Flow, b query.Bodies) []string
	counts  map[string]int
	first   map[string]int64
	secrets bool
}

func newValueCounter(expr string, secrets bool) (*valueCounter, error) {
	v := &valueCounter{expr: expr, counts: map[string]int{}, first: map[string]int64{}, secrets: secrets}
	e := strings.TrimSpace(expr)
	le := strings.ToLower(e)
	switch {
	case strings.HasPrefix(le, "req.header."), strings.HasPrefix(le, "res.header."):
		name := e[len("req.header."):]
		req := strings.HasPrefix(le, "req.")
		v.get = func(f *flow.Flow, _ query.Bodies) []string {
			h := f.ResHeaders
			if req {
				h = f.ReqHeaders
			}
			var out []string
			for _, kv := range h {
				if strings.EqualFold(kv[0], name) {
					val := kv[1]
					if !secrets {
						val = redact.HeaderValue(kv[0], val)
					}
					out = append(out, val)
				}
			}
			return out
		}
	case strings.HasPrefix(le, "query."):
		key := e[len("query."):]
		v.get = func(f *flow.Flow, _ query.Bodies) []string {
			var out []string
			for _, p := range strings.Split(f.Query, "&") {
				k, val, _ := strings.Cut(p, "=")
				if k == key {
					if !secrets && (redact.SensitiveKey(k) || k == "key") {
						val = redact.Hide(val)
					}
					out = append(out, val)
				}
			}
			return out
		}
	case le == "host", le == "path", le == "app", le == "status", le == "method", le == "endpoint", le == "type", le == "kind", le == "tls":
		v.get = func(f *flow.Flow, _ query.Bodies) []string {
			switch le {
			case "host":
				return []string{f.Host}
			case "path":
				return []string{f.Path}
			case "app":
				return []string{f.App}
			case "status":
				return []string{strconv.Itoa(f.Status)}
			case "method":
				return []string{f.Method}
			case "endpoint":
				return []string{f.Endpoint}
			case "type":
				return []string{f.Res.Type}
			case "kind":
				return []string{string(f.Kind)}
			}
			return []string{f.TLS}
		}
	case strings.HasPrefix(le, "req.json:"), strings.HasPrefix(le, "res.json:"):
		side := le[:3]
		q, err := gojq.Parse(e[len("res.json:"):])
		if err != nil {
			return nil, fmt.Errorf("values: %w", err)
		}
		code, err := gojq.Compile(q)
		if err != nil {
			return nil, fmt.Errorf("values: %w", err)
		}
		v.get = func(f *flow.Flow, b query.Bodies) []string {
			data := b.Body(f, side)
			if len(data) == 0 {
				return nil
			}
			if !secrets {
				data = redact.Body(data, "json")
			}
			var doc any
			if json.Unmarshal(data, &doc) != nil {
				return nil
			}
			var out []string
			iter := code.Run(doc)
			for i := 0; i < 100; i++ {
				r, ok := iter.Next()
				if !ok {
					break
				}
				if _, isErr := r.(error); isErr {
					break
				}
				j, _ := json.Marshal(r)
				out = append(out, string(j))
			}
			return out
		}
	default:
		return nil, fmt.Errorf("values: use req.header.NAME, res.header.NAME, query.KEY, host, path, app, status, method, endpoint, type, kind, tls, req.json:<jq> or res.json:<jq>")
	}
	return v, nil
}

func (v *valueCounter) add(f *flow.Flow, b query.Bodies) {
	for _, s := range v.get(f, b) {
		s = Cut(s, 200)
		if _, ok := v.first[s]; !ok {
			v.first[s] = f.ID
		}
		v.counts[s]++
	}
}

func (v *valueCounter) text(limit int) string {
	type kv struct {
		k string
		n int
	}
	var list []kv
	for k, n := range v.counts {
		list = append(list, kv{k, n})
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].n != list[j].n {
			return list[i].n > list[j].n
		}
		return list[i].k < list[j].k
	})
	var b strings.Builder
	fmt.Fprintf(&b, "%d distinct values of %s:\n", len(list), v.expr)
	for i, e := range list {
		if i >= limit {
			fmt.Fprintf(&b, "… %d more\n", len(list)-i)
			break
		}
		fmt.Fprintf(&b, "  ×%-5d %s  (first %s)\n", e.n, e.k, FID(v.first[e.k]))
	}
	return b.String()
}
