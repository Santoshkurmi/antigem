package claude

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

// --- login --------------------------------------------------------------------------------------

// loginSession is one in-progress OAuth login, driven through a throwaway claude process:
// claude_authenticate → (browser localhost callback | claude_oauth_callback with a pasted code)
// → claude_oauth_wait_for_completion.
type loginSession struct {
	ID           string `json:"login_id"`
	Method       string `json:"method"`
	State        string `json:"state"` // pending | success | error | cancelled
	Error        string `json:"error,omitempty"`
	ManualURL    string `json:"manual_url"`
	AutomaticURL string `json:"automatic_url"`
	proc         *controlProc
	started      time.Time
}

type loginRegistry struct {
	mu       sync.Mutex
	sessions map[string]*loginSession
}

func (m *Manager) startLogin(method string) (*loginSession, error) {
	p, err := m.startControlProc(m.HomeDir)
	if err != nil {
		return nil, err
	}
	if _, err := p.request(map[string]interface{}{"subtype": "initialize"}, 45*time.Second); err != nil {
		p.close()
		return nil, err
	}
	resp, err := p.request(map[string]interface{}{"subtype": "claude_authenticate", "loginWithClaudeAi": method != "console"}, 45*time.Second)
	if err != nil {
		p.close()
		return nil, err
	}
	var urls struct {
		ManualURL    string `json:"manualUrl"`
		AutomaticURL string `json:"automaticUrl"`
	}
	_ = json.Unmarshal(resp, &urls)
	ls := &loginSession{
		ID:           uuid.NewString(),
		Method:       method,
		State:        "pending",
		ManualURL:    urls.ManualURL,
		AutomaticURL: urls.AutomaticURL,
		proc:         p,
		started:      time.Now(),
	}
	m.logins.mu.Lock()
	if m.logins.sessions == nil {
		m.logins.sessions = map[string]*loginSession{}
	}
	m.logins.sessions[ls.ID] = ls
	m.logins.mu.Unlock()

	go func() {
		_, err := p.request(map[string]interface{}{"subtype": "claude_oauth_wait_for_completion"}, 15*time.Minute)
		m.logins.mu.Lock()
		if ls.State == "pending" {
			if err != nil {
				ls.State = "error"
				ls.Error = err.Error()
			} else {
				ls.State = "success"
			}
		}
		m.logins.mu.Unlock()
		p.close()
		m.invalidateCaches()
	}()
	return ls, nil
}

func (m *Manager) login(id string) *loginSession {
	m.logins.mu.Lock()
	defer m.logins.mu.Unlock()
	return m.logins.sessions[id]
}

func (m *Manager) handleLogin(w http.ResponseWriter, r *http.Request, rest string) {
	if rest == "" {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "POST only"})
			return
		}
		var body struct {
			Method string `json:"method"` // claudeai | console
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		ls, err := m.startLogin(body.Method)
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "login": m.loginSnapshot(ls)})
		return
	}
	parts := strings.Split(rest, "/")
	ls := m.login(parts[0])
	if ls == nil {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown login"})
		return
	}
	action := ""
	if len(parts) > 1 {
		action = parts[1]
	}
	switch {
	case action == "" && r.Method == http.MethodGet:
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "login": m.loginSnapshot(ls)})
	case action == "code" && r.Method == http.MethodPost:
		var body struct {
			Code string `json:"code"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		code := strings.TrimSpace(body.Code)
		state := ""
		if i := strings.Index(code, "#"); i >= 0 {
			code, state = code[:i], code[i+1:]
		}
		if code == "" {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "code required"})
			return
		}
		if _, err := ls.proc.request(map[string]interface{}{"subtype": "claude_oauth_callback", "authorizationCode": code, "state": state}, 60*time.Second); err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "login": m.loginSnapshot(ls)})
	case action == "" && r.Method == http.MethodDelete:
		m.logins.mu.Lock()
		if ls.State == "pending" {
			ls.State = "cancelled"
		}
		delete(m.logins.sessions, ls.ID)
		m.logins.mu.Unlock()
		ls.proc.close()
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": true})
	default:
		writeJSON(w, http.StatusNotFound, map[string]interface{}{"success": false, "error": "unknown login endpoint"})
	}
}

func (m *Manager) loginSnapshot(ls *loginSession) loginSession {
	m.logins.mu.Lock()
	defer m.logins.mu.Unlock()
	return loginSession{ID: ls.ID, Method: ls.Method, State: ls.State, Error: ls.Error, ManualURL: ls.ManualURL, AutomaticURL: ls.AutomaticURL}
}

// --- usage & info caches -------------------------------------------------------------------------

type cachedValue struct {
	value json.RawMessage
	at    time.Time
}

func (m *Manager) invalidateCaches() {
	m.infoMu.Lock()
	m.infoCache = nil
	m.usageCache = cachedValue{}
	m.infoMu.Unlock()
}

// Usage returns the `get_usage` response (plan limits, session cost), cached for 60 seconds.
func (m *Manager) Usage(refresh bool) (json.RawMessage, error) {
	m.infoMu.Lock()
	if !refresh && m.usageCache.value != nil && time.Since(m.usageCache.at) < 60*time.Second {
		v := m.usageCache.value
		m.infoMu.Unlock()
		return v, nil
	}
	m.infoMu.Unlock()
	resps, err := m.oneShot(m.HomeDir, 45*time.Second, map[string]interface{}{"subtype": "get_usage"})
	if err != nil {
		return nil, err
	}
	m.infoMu.Lock()
	m.usageCache = cachedValue{value: resps[0], at: time.Now()}
	m.infoMu.Unlock()
	return resps[0], nil
}

// Info returns the `initialize` response of a throwaway process (models, commands, account), cached 10 minutes.
func (m *Manager) Info(refresh bool) (json.RawMessage, error) {
	m.infoMu.Lock()
	if !refresh && m.infoCache != nil && time.Since(m.infoAt) < 10*time.Minute {
		v := m.infoCache
		m.infoMu.Unlock()
		return v, nil
	}
	m.infoMu.Unlock()
	p, err := m.startControlProc(m.HomeDir)
	if err != nil {
		return nil, err
	}
	defer p.close()
	resp, err := p.request(map[string]interface{}{"subtype": "initialize"}, 45*time.Second)
	if err != nil {
		return nil, err
	}
	if len(resp) == 0 {
		return nil, errors.New("empty initialize response")
	}
	m.infoMu.Lock()
	m.infoCache = resp
	m.infoAt = time.Now()
	m.infoMu.Unlock()
	return resp, nil
}

func (m *Manager) handleUsage(w http.ResponseWriter, r *http.Request) {
	u, err := m.Usage(r.URL.Query().Get("refresh") == "1")
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"success": true, "usage": u})
}
