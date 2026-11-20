package handlers

// Antigravity (agy) account switcher.
//
// agy keeps its Google login in ~/.gemini/jetski-standalone-oauth-token when there is no OS keyring (Android/Termux,
// headless Linux servers): that file is the only per-account state agy has. agy reads it again on every sign-in
// status check, so switching accounts is: keep copies of the file, write another copy in its place, then let the
// app ask for the sign-in status. Saved copies live in ~/.gemini/antigem-accounts/<email>.json; tokens never leave
// the bridge (the app only gets emails and dates).
//
// A watcher on ~/.gemini (see agy_accounts_watch_*.go) tells the app over the WebSocket when the signed-in account
// changes from outside (the Agy Accounts mini app, a terminal, a new sign-in), so the app refreshes right away.

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	agyTokenFileName   = "jetski-standalone-oauth-token"
	agyAccountsDirName = "antigem-accounts"
)

var (
	agyAccountsMu      sync.Mutex
	agyAccountFileName = regexp.MustCompile(`[^a-z0-9@._-]+`)
)

// agyAccountsSupported: agy uses a token file only where there is no OS keyring. On macOS and Windows it keeps the
// login in the system keychain / credential store, so swapping the file would change nothing.
func agyAccountsSupported() bool {
	return runtime.GOOS == "linux" || runtime.GOOS == "android"
}

func (h *Handler) agyGeminiDir() string { return filepath.Join(h.Cfg.HomeDir, ".gemini") }
func (h *Handler) agyLiveTokenPath() string {
	return filepath.Join(h.agyGeminiDir(), agyTokenFileName)
}
func (h *Handler) agyAccountsDir() string {
	return filepath.Join(h.agyGeminiDir(), agyAccountsDirName)
}

// agySavedAccount is the file format of a saved copy (the same as the Agy Accounts mini app's).
type agySavedAccount struct {
	Name    string                 `json:"name"`
	Email   string                 `json:"email"`
	SavedAt int64                  `json:"savedAt"`
	UsedAt  int64                  `json:"usedAt"`
	Token   map[string]interface{} `json:"token"`
	file    string
}

func agyTokenEmail(t map[string]interface{}) string {
	s, _ := t["id_token_email"].(string)
	return strings.TrimSpace(s)
}

func agyTokenMethod(t map[string]interface{}) string {
	s, _ := t["auth_method"].(string)
	return s
}

// agyTokenKey identifies a login: the refresh token (access tokens change every hour), else the access token.
func agyTokenKey(t map[string]interface{}) string {
	inner, _ := t["token"].(map[string]interface{})
	if inner == nil {
		return ""
	}
	if s, _ := inner["refresh_token"].(string); s != "" {
		return s
	}
	s, _ := inner["access_token"].(string)
	return s
}

// agyTokenIdentity is a short fingerprint of a login (never the token itself), "" when signed out.
func agyTokenIdentity(t map[string]interface{}) string {
	key := agyTokenKey(t)
	if key == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(agyTokenEmail(t) + "\x00" + key))
	return hex.EncodeToString(sum[:8])
}

// agyCleanToken drops writer_id: it marks which agy process wrote the file, and a restored copy must look like an
// outside change to the running agy.
func agyCleanToken(t map[string]interface{}) map[string]interface{} {
	out := make(map[string]interface{}, len(t))
	for k, v := range t {
		if k != "writer_id" {
			out[k] = v
		}
	}
	return out
}

func readAgyTokenFile(path string) (map[string]interface{}, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(string(data)) == "" {
		return nil, os.ErrNotExist
	}
	var t map[string]interface{}
	if err := json.Unmarshal(data, &t); err != nil {
		return nil, err
	}
	if agyTokenKey(t) == "" {
		return nil, errors.New("the login file has no token in it")
	}
	return t, nil
}

// writeFileAtomic writes through a temp file + rename in the same folder, so agy never reads a half-written file.
func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".antigem-tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op after a successful rename
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(0600); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

func (h *Handler) readAgySavedAccounts() []agySavedAccount {
	entries, err := os.ReadDir(h.agyAccountsDir())
	if err != nil {
		return nil
	}
	var out []agySavedAccount
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		path := filepath.Join(h.agyAccountsDir(), e.Name())
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var acc agySavedAccount
		if json.Unmarshal(data, &acc) != nil || agyTokenKey(acc.Token) == "" {
			continue
		}
		if acc.Email == "" {
			acc.Email = agyTokenEmail(acc.Token)
		}
		if acc.Name == "" {
			acc.Name = acc.Email
		}
		acc.file = path
		out = append(out, acc)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

func (h *Handler) agyAccountFile(name string) string {
	base := agyAccountFileName.ReplaceAllString(strings.ToLower(strings.TrimSpace(name)), "_")
	if base == "" || base == "." || base == ".." {
		base = "account"
	}
	return filepath.Join(h.agyAccountsDir(), base+".json")
}

func (h *Handler) writeAgySaved(acc agySavedAccount) error {
	data, err := json.MarshalIndent(acc, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(acc.file, data)
}

// saveLiveLocked saves the signed-in login (updating the copy of the same email). Returns its email.
func (h *Handler) saveLiveLocked(live map[string]interface{}, saved []agySavedAccount) (string, error) {
	email := agyTokenEmail(live)
	if email == "" {
		return "", errors.New("this login has no email in it, so it can't be saved")
	}
	now := time.Now().UnixMilli()
	acc := agySavedAccount{Name: email, Email: email, SavedAt: now, UsedAt: now, Token: agyCleanToken(live), file: h.agyAccountFile(email)}
	for _, s := range saved {
		if strings.EqualFold(s.Email, email) {
			acc.Name, acc.UsedAt, acc.file = s.Name, s.UsedAt, s.file
			if acc.UsedAt == 0 {
				acc.UsedAt = now
			}
			break
		}
	}
	return email, h.writeAgySaved(acc)
}

// liveIsSaved: the signed-in login has a saved copy (the same login, not only the same email).
func liveIsSaved(live map[string]interface{}, saved []agySavedAccount) bool {
	key := agyTokenKey(live)
	for _, s := range saved {
		if agyTokenKey(s.Token) == key {
			return true
		}
	}
	return false
}

// keepLiveFreshLocked: when the signed-in account already has a saved copy by email, keep that copy's token current
// (it may have signed in again, for example after a password change).
func (h *Handler) keepLiveFreshLocked(live map[string]interface{}, saved []agySavedAccount) {
	if live == nil || liveIsSaved(live, saved) {
		return
	}
	email := agyTokenEmail(live)
	for _, s := range saved {
		if email != "" && strings.EqualFold(s.Email, email) {
			_, _ = h.saveLiveLocked(live, saved)
			return
		}
	}
}

func (h *Handler) agyAccountsState() map[string]interface{} {
	live, liveErr := readAgyTokenFile(h.agyLiveTokenPath())
	saved := h.readAgySavedAccounts()
	h.keepLiveFreshLocked(live, saved)
	if live != nil {
		saved = h.readAgySavedAccounts()
	}

	current := map[string]interface{}{"signedIn": live != nil}
	if live != nil {
		current["email"] = agyTokenEmail(live)
		current["authMethod"] = agyTokenMethod(live)
		current["saved"] = liveIsSaved(live, saved)
	} else if liveErr != nil && !errors.Is(liveErr, os.ErrNotExist) {
		current["error"] = liveErr.Error()
	}
	liveKey := agyTokenKey(live)
	list := make([]map[string]interface{}, 0, len(saved))
	for _, s := range saved {
		list = append(list, map[string]interface{}{
			"name":       s.Name,
			"email":      s.Email,
			"authMethod": agyTokenMethod(s.Token),
			"savedAt":    s.SavedAt,
			"usedAt":     s.UsedAt,
			"active":     live != nil && agyTokenKey(s.Token) == liveKey,
		})
	}
	return map[string]interface{}{
		"success":   true,
		"supported": agyAccountsSupported(),
		"current":   current,
		"accounts":  list,
	}
}

type agyAccountsReq struct {
	Email string `json:"email"`
	// SaveCurrent saves the signed-in login first; Force goes on without saving it.
	SaveCurrent bool `json:"saveCurrent"`
	Force       bool `json:"force"`
}

// AgyAccountsHandler serves /api/agy/accounts (GET: state) and /api/agy/accounts/{save,switch,delete,clear} (POST).
func (h *Handler) AgyAccountsHandler(w http.ResponseWriter, r *http.Request) {
	action := strings.Trim(strings.TrimPrefix(r.URL.Path, "/api/agy/accounts"), "/")
	agyAccountsMu.Lock()
	defer agyAccountsMu.Unlock()

	if action == "" {
		if r.Method != http.MethodGet {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "method not allowed"})
			return
		}
		writeJSON(w, http.StatusOK, h.agyAccountsState())
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "method not allowed"})
		return
	}
	if !agyAccountsSupported() {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "On this computer agy keeps its login in the system keychain, so accounts can't be switched here."})
		return
	}
	var req agyAccountsReq
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req)

	fail := func(code int, msg string) {
		writeJSON(w, code, map[string]interface{}{"success": false, "error": msg})
	}
	live, _ := readAgyTokenFile(h.agyLiveTokenPath())
	saved := h.readAgySavedAccounts()

	// Before the signed-in login is replaced or removed: save it, or the caller confirmed losing it.
	keepCurrent := func() bool {
		if live == nil || liveIsSaved(live, saved) {
			return true
		}
		email := agyTokenEmail(live)
		for _, s := range saved {
			if email != "" && strings.EqualFold(s.Email, email) {
				_, _ = h.saveLiveLocked(live, saved) // same account signed in again: refresh its copy
				return true
			}
		}
		if req.SaveCurrent {
			if _, err := h.saveLiveLocked(live, saved); err != nil {
				fail(http.StatusInternalServerError, err.Error())
				return false
			}
			return true
		}
		if req.Force {
			return true
		}
		writeJSON(w, http.StatusConflict, map[string]interface{}{"success": false, "error": "current_not_saved", "email": email})
		return false
	}

	switch action {
	case "save":
		if live == nil {
			fail(http.StatusBadRequest, "agy is not signed in")
			return
		}
		if _, err := h.saveLiveLocked(live, saved); err != nil {
			fail(http.StatusInternalServerError, err.Error())
			return
		}

	case "switch":
		var target *agySavedAccount
		for i := range saved {
			if strings.EqualFold(saved[i].Email, req.Email) {
				target = &saved[i]
				break
			}
		}
		if target == nil {
			fail(http.StatusNotFound, "No saved account "+req.Email)
			return
		}
		if live != nil && agyTokenKey(live) == agyTokenKey(target.Token) {
			break // already signed in
		}
		if !keepCurrent() {
			return
		}
		data, err := json.Marshal(agyCleanToken(target.Token))
		if err != nil {
			fail(http.StatusInternalServerError, err.Error())
			return
		}
		if err := writeFileAtomic(h.agyLiveTokenPath(), data); err != nil {
			fail(http.StatusInternalServerError, err.Error())
			return
		}
		target.UsedAt = time.Now().UnixMilli()
		_ = h.writeAgySaved(*target)

	case "clear":
		// Add account: agy signed out locally, without agy's logout (that could revoke the saved token).
		if !keepCurrent() {
			return
		}
		if err := os.Remove(h.agyLiveTokenPath()); err != nil && !errors.Is(err, os.ErrNotExist) {
			fail(http.StatusInternalServerError, err.Error())
			return
		}

	case "delete":
		var target *agySavedAccount
		for i := range saved {
			if strings.EqualFold(saved[i].Email, req.Email) {
				target = &saved[i]
				break
			}
		}
		if target == nil {
			fail(http.StatusNotFound, "No saved account "+req.Email)
			return
		}
		if err := os.Remove(target.file); err != nil && !errors.Is(err, os.ErrNotExist) {
			fail(http.StatusInternalServerError, err.Error())
			return
		}

	default:
		fail(http.StatusNotFound, "unknown action "+action)
		return
	}
	writeJSON(w, http.StatusOK, h.agyAccountsState())
}

// agyLiveIdentity is the fingerprint of the signed-in login ("" when signed out), for the watcher.
func (h *Handler) agyLiveIdentity() (identity string, email string) {
	t, err := readAgyTokenFile(h.agyLiveTokenPath())
	if err != nil {
		return "", ""
	}
	return agyTokenIdentity(t), agyTokenEmail(t)
}

// notifyAgyAccountIfChanged broadcasts agy_account_changed when the signed-in login differs from the last one seen.
// agy rewrites the file about once an hour to renew the access token: same login, so nothing is sent.
func (h *Handler) notifyAgyAccountIfChanged(last *string) {
	id, email := h.agyLiveIdentity()
	if id == *last {
		return
	}
	*last = id
	if h.Hub != nil {
		h.Hub.Broadcast(map[string]interface{}{
			"type":     "agy_account_changed",
			"signedIn": id != "",
			"email":    email,
		})
	}
}
