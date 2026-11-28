// Package redact hides secrets (tokens, cookies, passwords) before captured traffic is shown to an AI model.
// A hidden value keeps a short fingerprint, so "the same token in two requests" stays visible.
package redact

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/url"
	"regexp"
	"strings"
)

// Hide returns the placeholder for a secret value.
func Hide(v string) string {
	if v == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(v))
	return "[hidden #" + hex.EncodeToString(sum[:2]) + "]"
}

var headerWords = []string{"auth", "token", "secret", "api-key", "apikey", "api_key", "x-key", "session", "password",
	"signature", "credential", "cookie", "csrf", "xsrf", "sec-websocket-key"}

// SensitiveHeader reports whether a header's value should be hidden.
func SensitiveHeader(name string) bool {
	n := strings.ToLower(name)
	if n == ":authority" || n == "authority" || n == "www-authenticate" || n == "x-authenticated-userid" ||
		strings.HasPrefix(n, "access-control-") {
		return false
	}
	for _, w := range headerWords {
		if strings.Contains(n, w) {
			return true
		}
	}
	return false
}

// HeaderValue hides a header value when the header is sensitive. Cookies keep their names.
func HeaderValue(name, value string) string {
	n := strings.ToLower(name)
	switch n {
	case "cookie":
		parts := strings.Split(value, ";")
		for i, p := range parts {
			k, v, ok := strings.Cut(strings.TrimSpace(p), "=")
			if ok {
				parts[i] = k + "=" + Hide(v)
			} else {
				parts[i] = Hide(p)
			}
		}
		return strings.Join(parts, "; ")
	case "set-cookie":
		k, rest, ok := strings.Cut(value, "=")
		if !ok {
			return Hide(value)
		}
		v, attrs, _ := strings.Cut(rest, ";")
		out := k + "=" + Hide(v)
		if attrs != "" {
			out += ";" + attrs
		}
		return out
	case "authorization", "proxy-authorization":
		scheme, cred, ok := strings.Cut(value, " ")
		if ok {
			return scheme + " " + Hide(cred)
		}
		return Hide(value)
	}
	if SensitiveHeader(name) {
		return Hide(value)
	}
	return Text(value)
}

var keyRe = regexp.MustCompile(`(?i)(^|[_\-.])(pass(word|wd|code)?|pwd|secret|token|api[_\-]?key|apikey|otp|pin|auth(orization|_?token|_?code|_?key)?|session(_?id|_?key|_?token)?|sid|cookie|private[_\-]?key|credential(s)?|cvv|cvc|card[_\-]?number|signature|refresh|access[_\-]?key|client[_\-]?secret)$`)

// SensitiveKey reports whether a JSON or form field name holds a secret.
func SensitiveKey(k string) bool {
	return keyRe.MatchString(k) || strings.Contains(strings.ToLower(k), "password") || strings.Contains(strings.ToLower(k), "token") || strings.Contains(strings.ToLower(k), "secret")
}

var (
	// "password":"…" inside any text, also when the JSON is itself escaped inside a string (\"password\":\"…\")
	jsonKVRe = regexp.MustCompile(`(?i)(\\?"(?:password|passwd|pwd|passcode|secret|client_secret|token|access_token|refresh_token|id_token|auth_token|api_key|apikey|api-key|otp|pin|session_?id|session_?token|private_key|authorization)\\?"\s*:\s*\\?")([^"\\]+)(\\?")`)
	formKVRe = regexp.MustCompile(`(?i)\b((?:password|passwd|pwd|secret|client_secret|token|access_token|refresh_token|id_token|api_key|apikey|otp)=)([^&\s"']+)`)
	jwtRe    = regexp.MustCompile(`eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}`)
	bearerRe = regexp.MustCompile(`(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{12,}`)
	// common key formats: Google API keys, GitHub, Slack, Stripe, AWS access keys, OpenAI/Anthropic-style keys
	keyPatternRe = regexp.MustCompile(`\b(AIza[0-9A-Za-z_\-]{30,}|gh[pousr]_[0-9A-Za-z]{30,}|xox[abpr]-[0-9A-Za-z\-]{10,}|sk_(live|test)_[0-9A-Za-z]{16,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9_\-]{20,})\b`)
)

// Text hides tokens that look like secrets anywhere in free text (JWTs, bearer credentials, known key formats).
func Text(s string) string {
	s = jsonKVRe.ReplaceAllStringFunc(s, func(m string) string {
		g := jsonKVRe.FindStringSubmatch(m)
		if strings.HasPrefix(g[2], "[hidden #") {
			return m
		}
		return g[1] + Hide(g[2]) + g[3]
	})
	s = formKVRe.ReplaceAllStringFunc(s, func(m string) string {
		g := formKVRe.FindStringSubmatch(m)
		if strings.HasPrefix(g[2], "[hidden") || strings.HasPrefix(g[2], "%5Bhidden") {
			return m
		}
		return g[1] + Hide(g[2])
	})
	s = jwtRe.ReplaceAllStringFunc(s, Hide)
	s = bearerRe.ReplaceAllStringFunc(s, func(m string) string {
		scheme, cred, _ := strings.Cut(m, " ")
		return scheme + " " + Hide(strings.TrimSpace(cred))
	})
	s = keyPatternRe.ReplaceAllStringFunc(s, Hide)
	return s
}

// Body hides secrets in a decoded body of the given short type (json, form, others as free text).
func Body(b []byte, typ string) []byte {
	switch typ {
	case "json":
		trim := bytes.TrimSpace(b)
		if len(trim) > 0 && (trim[0] == '{' || trim[0] == '[') {
			var v any
			dec := json.NewDecoder(bytes.NewReader(trim))
			dec.UseNumber()
			if dec.Decode(&v) == nil && !dec.More() {
				v = jsonValue("", v)
				if out, err := json.Marshal(v); err == nil {
					return []byte(Text(string(out)))
				}
			}
		}
	case "form":
		if vals, err := url.ParseQuery(string(b)); err == nil && !bytes.ContainsAny(b, "\n") {
			changed := false
			for k, vs := range vals {
				if SensitiveKey(k) {
					for i := range vs {
						vs[i] = Hide(vs[i])
					}
					changed = true
				}
			}
			if changed {
				return []byte(Text(vals.Encode()))
			}
		}
	}
	return []byte(Text(string(b)))
}

func jsonValue(key string, v any) any {
	switch x := v.(type) {
	case map[string]any:
		for k, val := range x {
			x[k] = jsonValue(k, val)
		}
		return x
	case []any:
		for i := range x {
			x[i] = jsonValue(key, x[i])
		}
		return x
	case string:
		if key != "" && SensitiveKey(key) {
			return Hide(x)
		}
		// JSON sent as a string inside JSON: hide inside it too
		if t := strings.TrimSpace(x); len(t) > 1 && (t[0] == '{' || t[0] == '[') {
			var inner any
			dec := json.NewDecoder(strings.NewReader(t))
			dec.UseNumber()
			if dec.Decode(&inner) == nil && !dec.More() {
				if out, err := json.Marshal(jsonValue("", inner)); err == nil {
					return string(out)
				}
			}
		}
	case json.Number:
		if key != "" && SensitiveKey(key) {
			return Hide(x.String())
		}
	}
	return v
}

// Query hides sensitive query parameters (token=…, key=…) in a raw query string.
func Query(q string) string {
	if q == "" {
		return q
	}
	parts := strings.Split(q, "&")
	for i, p := range parts {
		k, v, ok := strings.Cut(p, "=")
		if !ok {
			continue
		}
		dk, _ := url.QueryUnescape(k)
		if SensitiveKey(dk) || dk == "key" || strings.EqualFold(dk, "sig") {
			parts[i] = k + "=" + Hide(v) // display only: kept readable
		}
	}
	return Text(strings.Join(parts, "&"))
}
