package security

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGuardResponseRemovesTokenFromHeaders(t *testing.T) {
	token := "abcdef123456"
	h := GuardResponse(token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, "+BuildFramedHeader(token))
		w.Header().Set("X-Debug", "token="+token)
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte("ok"))
	}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/x", nil))
	for name, values := range rec.Header() {
		for _, v := range values {
			if strings.Contains(strings.ToLower(v), token) {
				t.Fatalf("header %s still contains the token: %q", name, v)
			}
		}
	}
	if rec.Header().Get("Content-Type") != "text/plain" || rec.Body.String() != "ok" {
		t.Fatalf("normal response was damaged: %v %q", rec.Header(), rec.Body.String())
	}
}

func TestAuthMiddlewareIgnoresTokenInURL(t *testing.T) {
	token := "abcdef123456"
	h := AuthMiddleware(token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/api/file/read?token="+token, nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("token in the URL must not authenticate, got %d", rec.Code)
	}
	req := httptest.NewRequest("GET", "/api/file/read", nil)
	req.Header.Set(BuildFramedHeader(token), "true")
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 200 {
		t.Fatalf("the header must still authenticate, got %d", rec.Code)
	}
}
