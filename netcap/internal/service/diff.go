package service

import (
	"bytes"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"

	"antigem/netcap/internal/flow"
	"antigem/netcap/internal/redact"
	"antigem/netcap/internal/view"
)

// DiffOptions for Diff.
type DiffOptions struct {
	Session string
	A, B    string
	Parts   string   // req, res or both (default)
	Ignore  []string // header names or JSON paths to skip
	Secrets bool
}

var boringHeaders = map[string]bool{"date": true, "age": true, "x-request-id": true, "x-amzn-requestid": true,
	"x-amz-cf-id": true, "cf-ray": true, "x-trace-id": true, "traceparent": true, "server-timing": true,
	"x-served-by": true, "x-timer": true, "expires": true, "last-modified": true, "etag": true, "report-to": true, "nel": true}

// Diff compares two flows: status, headers and bodies (JSON by path, text by line).
func (s *Service) Diff(o DiffOptions) (string, error) {
	sess, err := s.Session(o.Session)
	if err != nil {
		return "", err
	}
	ida, _, err := ParseID(o.A)
	if err != nil {
		return "", err
	}
	idb, _, err := ParseID(o.B)
	if err != nil {
		return "", err
	}
	fa, err := sess.Store.Get(ida)
	if err != nil {
		return "", err
	}
	fb, err := sess.Store.Get(idb)
	if err != nil {
		return "", err
	}
	ignore := map[string]bool{}
	for _, i := range o.Ignore {
		ignore[strings.ToLower(i)] = true
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s vs %s\n", FID(fa.ID), FID(fb.ID))
	if fa.Method != fb.Method || fa.URL() != fb.URL() {
		fmt.Fprintf(&b, "request: %s %s → %s %s\n", fa.Method, Cut(fa.URL(), 100), fb.Method, Cut(fb.URL(), 100))
	}
	parts := strings.ToLower(o.Parts)
	if parts == "" {
		parts = "both"
	}
	if parts == "req" || parts == "both" {
		s.diffSide(&b, sess, fa, fb, "req", ignore, o.Secrets)
	}
	if parts == "res" || parts == "both" {
		if fa.Status != fb.Status {
			fmt.Fprintf(&b, "status: %d → %d\n", fa.Status, fb.Status)
		}
		s.diffSide(&b, sess, fa, fb, "res", ignore, o.Secrets)
	}
	if strings.Count(b.String(), "\n") == 1 {
		b.WriteString("no differences (ignoring date/trace headers)\n")
	}
	return b.String(), nil
}

func (s *Service) diffSide(b *strings.Builder, sess *Session, fa, fb *flow.Flow, side string, ignore map[string]bool, secrets bool) {
	ha, hb := fa.ReqHeaders, fb.ReqHeaders
	ba, bb := fa.Req, fb.Req
	if side == "res" {
		ha, hb = fa.ResHeaders, fb.ResHeaders
		ba, bb = fa.Res, fb.Res
	}
	ma, mb := headerMap(ha), headerMap(hb)
	var changes []string
	keys := map[string]bool{}
	for k := range ma {
		keys[k] = true
	}
	for k := range mb {
		keys[k] = true
	}
	sorted := make([]string, 0, len(keys))
	for k := range keys {
		sorted = append(sorted, k)
	}
	sort.Strings(sorted)
	show := func(k, v string) string {
		if !secrets {
			v = redact.HeaderValue(k, v)
		}
		return Cut(v, 80)
	}
	for _, k := range sorted {
		if boringHeaders[k] || ignore[k] {
			continue
		}
		va, oka := ma[k]
		vb, okb := mb[k]
		switch {
		case oka && !okb:
			changes = append(changes, "- "+k+": "+show(k, va))
		case !oka && okb:
			changes = append(changes, "+ "+k+": "+show(k, vb))
		case va != vb:
			changes = append(changes, "~ "+k+": "+show(k, va)+" → "+show(k, vb))
		}
	}
	if len(changes) > 0 {
		fmt.Fprintf(b, "%s headers: %s\n", side, strings.Join(changes, " · "))
	}
	if ba.Hash == bb.Hash {
		return
	}
	da, _, _ := s.Body(sess, fa, side, false, 2<<20)
	db, _, _ := s.Body(sess, fb, side, false, 2<<20)
	if !secrets {
		da, db = redact.Body(da, ba.Type), redact.Body(db, bb.Type)
	}
	if ba.Type == "json" && bb.Type == "json" {
		pa, pb := flatJSON(da), flatJSON(db)
		if pa != nil && pb != nil {
			var lines []string
			all := map[string]bool{}
			for k := range pa {
				all[k] = true
			}
			for k := range pb {
				all[k] = true
			}
			ks := make([]string, 0, len(all))
			for k := range all {
				ks = append(ks, k)
			}
			sort.Strings(ks)
			for _, k := range ks {
				if ignore[strings.ToLower(k)] {
					continue
				}
				va, oka := pa[k]
				vb, okb := pb[k]
				switch {
				case oka && !okb:
					lines = append(lines, "- "+k+" "+Cut(va, 60))
				case !oka && okb:
					lines = append(lines, "+ "+k+" "+Cut(vb, 60))
				case va != vb:
					lines = append(lines, "~ "+k+" "+Cut(va, 40)+" → "+Cut(vb, 40))
				}
			}
			if len(lines) > 60 {
				lines = append(lines[:60], fmt.Sprintf("… %d more differences", len(lines)-60))
			}
			if len(lines) > 0 {
				fmt.Fprintf(b, "%s body (JSON paths):\n  %s\n", side, strings.Join(lines, "\n  "))
			}
			return
		}
	}
	if view.IsText(ba.Type) && view.IsText(bb.Type) {
		out := lineDiff(strings.Split(view.ToText(da), "\n"), strings.Split(view.ToText(db), "\n"), 40)
		if out != "" {
			fmt.Fprintf(b, "%s body (lines):\n%s", side, out)
		}
		return
	}
	fmt.Fprintf(b, "%s body: %s %s → %s %s (binary, differs)\n", side, ba.Type, Size(ba.Size), bb.Type, Size(bb.Size))
}

func headerMap(h flow.Headers) map[string]string {
	m := map[string]string{}
	for _, kv := range h {
		k := strings.ToLower(kv[0])
		if v, ok := m[k]; ok {
			m[k] = v + ", " + kv[1]
		} else {
			m[k] = kv[1]
		}
	}
	return m
}

// flatJSON turns JSON into path → value text (.a.b[0].c = 1).
func flatJSON(b []byte) map[string]string {
	var v any
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.UseNumber()
	if dec.Decode(&v) != nil {
		return nil
	}
	out := map[string]string{}
	var walk func(p string, v any)
	walk = func(p string, v any) {
		if len(out) > 5000 {
			return
		}
		switch x := v.(type) {
		case map[string]any:
			if len(x) == 0 {
				out[p] = "{}"
			}
			for k, val := range x {
				walk(p+"."+k, val)
			}
		case []any:
			if len(x) == 0 {
				out[p] = "[]"
			}
			for i, val := range x {
				walk(p+"["+strconv.Itoa(i)+"]", val)
			}
		default:
			j, _ := json.Marshal(x)
			if p == "" {
				p = "."
			}
			out[p] = string(j)
		}
	}
	walk("", v)
	return out
}

// lineDiff is a small LCS diff for short texts (falls back to a summary for long ones).
func lineDiff(a, b []string, maxLines int) string {
	if len(a)*len(b) > 4_000_000 {
		return fmt.Sprintf("  texts differ (%d → %d lines; too long for a line diff)\n", len(a), len(b))
	}
	n, m := len(a), len(b)
	dp := make([][]int, n+1)
	for i := range dp {
		dp[i] = make([]int, m+1)
	}
	for i := n - 1; i >= 0; i-- {
		for j := m - 1; j >= 0; j-- {
			if a[i] == b[j] {
				dp[i][j] = dp[i+1][j+1] + 1
			} else if dp[i+1][j] >= dp[i][j+1] {
				dp[i][j] = dp[i+1][j]
			} else {
				dp[i][j] = dp[i][j+1]
			}
		}
	}
	var out []string
	i, j := 0, 0
	for i < n || j < m {
		switch {
		case i < n && j < m && a[i] == b[j]:
			i++
			j++
		case j < m && (i == n || dp[i][j+1] >= dp[i+1][j]):
			out = append(out, fmt.Sprintf("  +%d: %s", j+1, Cut(b[j], 160)))
			j++
		default:
			out = append(out, fmt.Sprintf("  -%d: %s", i+1, Cut(a[i], 160)))
			i++
		}
		if len(out) >= maxLines {
			out = append(out, "  … more differences")
			break
		}
	}
	if len(out) == 0 {
		return ""
	}
	return strings.Join(out, "\n") + "\n"
}
