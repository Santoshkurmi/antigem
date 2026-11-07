package opencode

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Sign-ins (OAuth) of OpenCode providers that send the browser back to 127.0.0.1 on the machine OpenCode runs on
// ("Sign in with ChatGPT", Poe, GitLab…). On the phone that just works: the phone's browser reaches the phone's
// loopback. With the server on another machine the browser lands on the phone's 127.0.0.1 and fails; the app then
// hands the address it was sent to here, and the bridge opens it on the server's own loopback, where OpenCode waits.

// handleOAuthCallback: POST /api/opencode/oauth-callback {"url": "http://127.0.0.1:1455/auth/callback?code=…&state=…"}
func (m *Manager) handleOAuthCallback(w http.ResponseWriter, r *http.Request) {
	var req struct {
		URL string `json:"url"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid JSON"})
		return
	}
	target, msg := checkCallbackURL(strings.TrimSpace(req.URL))
	if target == nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": msg})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	hreq, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	// never follow a redirect away from the loopback callback
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := client.Do(hreq)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"success": false,
			"error":   "Nothing answered on the server at " + target.Host + ": the sign-in may have expired or been cancelled. Start it again.",
		})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": resp.StatusCode < 400,
		"status":  resp.StatusCode,
		"body":    string(body),
		"error": func() string {
			if resp.StatusCode < 400 {
				return ""
			}
			return "The sign-in callback answered HTTP " + resp.Status
		}(),
	})
}

// checkCallbackURL accepts only an OAuth callback on the loopback (http://127.0.0.1|localhost:<port>/…callback…?state=…).
func checkCallbackURL(raw string) (*url.URL, string) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "http" {
		return nil, "Paste the full address the browser showed (it starts with http://127.0.0.1 or http://localhost)."
	}
	host, port, err := net.SplitHostPort(u.Host)
	if err != nil || port == "" {
		return nil, "The address has no port: paste the full address the browser showed."
	}
	if host != "127.0.0.1" && host != "localhost" && host != "[::1]" && host != "::1" {
		return nil, "Only a sign-in address on 127.0.0.1 or localhost can be passed to the server."
	}
	if !strings.Contains(strings.ToLower(u.Path), "callback") {
		return nil, "That is not a sign-in callback address."
	}
	q := u.Query()
	if q.Get("state") == "" && q.Get("code") == "" {
		return nil, "The address has no sign-in code: copy it again from the browser's address bar after signing in."
	}
	u.Host = net.JoinHostPort("127.0.0.1", port)
	u.Fragment = ""
	return u, ""
}

// handleCredentials: GET /api/opencode/credentials — the saved accounts (which one is active per provider) without the
// secrets themselves (OpenCode's own list returns the keys and tokens in plain text).
func (m *Manager) handleCredentials(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	var raw struct {
		Data []map[string]interface{} `json:"data"`
	}
	if err := m.Call(ctx, http.MethodGet, "/api/credential", nil, &raw); err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	out := make([]map[string]interface{}, 0, len(raw.Data))
	for _, c := range raw.Data {
		item := map[string]interface{}{}
		for _, k := range []string{"id", "integrationID", "label", "active"} {
			if v, ok := c[k]; ok {
				item[k] = v
			}
		}
		if v, ok := c["value"].(map[string]interface{}); ok {
			item["type"] = v["type"]
		}
		out = append(out, item)
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "credentials": out})
}
