// Package analyze turns many flows into few lines: endpoint templates for grouping, and diffs.
package analyze

import (
	"regexp"
	"sort"
	"strconv"
	"strings"

	"antigem/netcap/internal/flow"
)

var (
	uuidRe  = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	hexRe   = regexp.MustCompile(`^[0-9a-fA-F]{16,}$`)
	numRe   = regexp.MustCompile(`^-?[0-9]+$`)
	dateRe  = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}([T_ ]\d{2}[:\-]?\d{2}([:\-]?\d{2})?)?Z?$`)
	emailRe = regexp.MustCompile(`^[^@/]+@[^@/]+\.[a-zA-Z]{2,}$`)
	b64Re   = regexp.MustCompile(`^[A-Za-z0-9_\-+=]{20,}$`)
	mixedRe = regexp.MustCompile(`\d`)
)

// Segment turns one path segment into a placeholder when it looks like an id.
func Segment(s string) string {
	switch {
	case s == "":
		return s
	case numRe.MatchString(s):
		return "{n}"
	case uuidRe.MatchString(s):
		return "{uuid}"
	case hexRe.MatchString(s):
		return "{hash}"
	case dateRe.MatchString(s):
		return "{date}"
	case emailRe.MatchString(s):
		return "{email}"
	case b64Re.MatchString(s) && len(mixedRe.FindAllString(s, -1)) >= 3:
		return "{id}"
	case len(s) >= 12 && len(mixedRe.FindAllString(s, -1)) >= 4 && !strings.ContainsAny(s, ".~"):
		return "{id}"
	}
	return s
}

// TemplatePath replaces id-like segments: /v1/users/42/posts → /v1/users/{n}/posts.
func TemplatePath(p string) string {
	if p == "" || p == "/" {
		return "/"
	}
	parts := strings.Split(p, "/")
	for i, s := range parts {
		parts[i] = Segment(s)
	}
	return strings.Join(parts, "/")
}

// QueryKeys returns "?a=&b=" for a raw query (sorted keys, values dropped), or "".
func QueryKeys(raw string) string {
	if raw == "" {
		return ""
	}
	seen := map[string]bool{}
	var keys []string
	for _, p := range strings.Split(raw, "&") {
		k, _, _ := strings.Cut(p, "=")
		if k != "" && !seen[k] {
			seen[k] = true
			keys = append(keys, k)
		}
	}
	if len(keys) == 0 {
		return ""
	}
	sort.Strings(keys)
	if len(keys) > 8 {
		keys = append(keys[:8], "…")
	}
	return "?" + strings.Join(keys, "=&") + "="
}

// EndpointKey is what flows are grouped by: "METHOD host[:port]/templated/path?keys=" (the port only when it is not
// the scheme's usual one, so two services on one host stay apart).
func EndpointKey(f *flow.Flow) string {
	host := strings.ToLower(f.Host)
	switch f.Kind {
	case flow.KindTLS, flow.KindTCP, flow.KindUDP:
		return strings.ToUpper(string(f.Kind)) + " " + host + ":" + strconv.Itoa(f.Port)
	}
	if f.Port != 0 && !((f.Scheme == "https" && f.Port == 443) || (f.Scheme == "http" && f.Port == 80)) {
		host += ":" + strconv.Itoa(f.Port)
	}
	m := f.Method
	if f.Kind == flow.KindWS {
		m = "WS"
	}
	return m + " " + host + TemplatePath(f.Path) + QueryKeys(f.Query)
}
