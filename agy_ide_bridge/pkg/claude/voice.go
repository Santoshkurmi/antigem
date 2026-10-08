package claude

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const voiceEndpoint = "wss://api.anthropic.com/api/ws/speech_to_text/voice_stream"

// oauthAccessToken reads the claude.ai OAuth token the CLI stored. If it is about to expire, `claude auth status`
// is run first so the CLI can refresh it.
func (m *Manager) oauthAccessToken() (string, error) {
	read := func() (string, int64, error) {
		data, err := os.ReadFile(filepath.Join(m.HomeDir, ".claude", ".credentials.json"))
		if err != nil {
			return "", 0, errors.New("not signed in to Claude (no credentials)")
		}
		var creds struct {
			ClaudeAiOauth struct {
				AccessToken string `json:"accessToken"`
				ExpiresAt   int64  `json:"expiresAt"`
			} `json:"claudeAiOauth"`
		}
		if err := json.Unmarshal(data, &creds); err != nil || creds.ClaudeAiOauth.AccessToken == "" {
			return "", 0, errors.New("voice needs a Claude.ai sign-in (API-key logins are not supported)")
		}
		return creds.ClaudeAiOauth.AccessToken, creds.ClaudeAiOauth.ExpiresAt, nil
	}
	tok, exp, err := read()
	if err != nil {
		return "", err
	}
	if exp > 0 && time.UnixMilli(exp).Before(time.Now().Add(2*time.Minute)) {
		_, _ = m.runCLI(30*time.Second, "auth", "status", "--json")
		tok, _, err = read()
	}
	return tok, err
}

// serveVoiceWS relays one dictation session: binary PCM frames (16 kHz, mono, s16le) from the app go to
// Anthropic's speech-to-text socket; its JSON transcript frames come back unchanged. The app ends the session
// by sending {"type":"CloseStream"}.
func (m *Manager) serveVoiceWS(w http.ResponseWriter, r *http.Request) {
	lang := r.URL.Query().Get("language")
	if lang == "" {
		lang = "en"
	}
	app, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	_ = app.UnderlyingConn().SetDeadline(time.Time{})
	defer app.Close()

	fail := func(msg string) {
		b, _ := json.Marshal(map[string]string{"type": "error", "message": msg})
		_ = app.WriteMessage(websocket.TextMessage, b)
	}
	token, err := m.oauthAccessToken()
	if err != nil {
		fail(err.Error())
		return
	}
	q := url.Values{
		"encoding": {"linear16"}, "sample_rate": {"16000"}, "channels": {"1"},
		"endpointing_ms": {"300"}, "utterance_end_ms": {"1000"}, "language": {lang},
		"use_conversation_engine": {"true"},
	}
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	h.Set("x-app", "cli")
	h.Set("anthropic-client-platform", "android")
	upstream, resp, err := websocket.DefaultDialer.Dial(voiceEndpoint+"?"+q.Encode(), h)
	if err != nil {
		if resp != nil {
			fail("voice service rejected the connection (HTTP " + resp.Status + ")")
		} else {
			fail("cannot reach voice service: " + err.Error())
		}
		return
	}
	defer upstream.Close()

	var upMu sync.Mutex
	sendUp := func(kind int, data []byte) error {
		upMu.Lock()
		defer upMu.Unlock()
		return upstream.WriteMessage(kind, data)
	}
	_ = sendUp(websocket.TextMessage, []byte(`{"type":"KeepAlive"}`))
	done := make(chan struct{})
	var once sync.Once
	stop := func() { once.Do(func() { close(done) }) }

	go func() {
		t := time.NewTicker(8 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				if sendUp(websocket.TextMessage, []byte(`{"type":"KeepAlive"}`)) != nil {
					stop()
					return
				}
			}
		}
	}()
	go func() {
		defer stop()
		for {
			kind, data, err := upstream.ReadMessage()
			if err != nil {
				return
			}
			if app.WriteMessage(kind, data) != nil {
				return
			}
		}
	}()
	go func() {
		defer stop()
		for {
			kind, data, err := app.ReadMessage()
			if err != nil {
				_ = sendUp(websocket.TextMessage, []byte(`{"type":"CloseStream"}`))
				return
			}
			if sendUp(kind, data) != nil {
				return
			}
		}
	}()
	<-done
	// give the service a moment to deliver the final transcript after CloseStream, then close politely
	time.Sleep(300 * time.Millisecond)
	_ = app.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
}
