// Package httpapi is the engine's local HTTP API: MCP at /mcp, tool calls for the CLI, flows as JSON, the CA
// certificate and a live event stream for GUIs. Every route except /v1/cert needs the token.
package httpapi

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/pprof"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/websocket"

	"antigem/netcap/internal/mcp"
	"antigem/netcap/internal/service"
)

// API serves the routes.
type API struct {
	Svc   *service.Service
	MCP   *mcp.Server
	Token string
	// Extra routes (the Android control layer adds its own).
	Extra map[string]http.HandlerFunc
}

// Handler returns the HTTP handler.
func (a *API) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/mcp", a.auth(a.MCP))
	mux.HandleFunc("/v1/cert", a.cert)
	mux.HandleFunc("/v1/status", a.authF(a.status))
	mux.HandleFunc("/v1/tool/", a.authF(a.tool))
	mux.HandleFunc("/v1/flows", a.authF(a.flows))
	mux.HandleFunc("/v1/flows/", a.authF(a.flow))
	mux.HandleFunc("/v1/events", a.authF(a.events))
	mux.HandleFunc("/v1/hooks", a.authF(a.hooks))
	a.uiRoutes(mux) // the native GUI: /v1/ui/… (ui.go)
	// profiling (token needed): go tool pprof http://…/debug/pprof/profile?seconds=20&token=…
	mux.HandleFunc("/debug/pprof/", a.authF(pprof.Index))
	mux.HandleFunc("/debug/pprof/profile", a.authF(pprof.Profile))
	mux.HandleFunc("/debug/pprof/heap", a.authF(pprof.Handler("heap").ServeHTTP))
	mux.HandleFunc("/debug/pprof/goroutine", a.authF(pprof.Handler("goroutine").ServeHTTP))
	for path, h := range a.Extra {
		mux.HandleFunc(path, a.authF(h))
	}
	return mux
}

func (a *API) ok(r *http.Request) bool {
	if a.Token == "" {
		return true
	}
	got := r.Header.Get("X-Netcap-Token")
	if got == "" {
		if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
			got = strings.TrimPrefix(h, "Bearer ")
		}
	}
	if got == "" {
		got = r.URL.Query().Get("token")
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(a.Token)) == 1
}

func (a *API) auth(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !a.ok(r) {
			http.Error(w, "missing or wrong token", http.StatusUnauthorized)
			return
		}
		h.ServeHTTP(w, r)
	})
}

func (a *API) authF(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.ok(r) {
			http.Error(w, "missing or wrong token", http.StatusUnauthorized)
			return
		}
		h(w, r)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func (a *API) cert(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/x-x509-ca-cert")
	w.Header().Set("Content-Disposition", `attachment; filename="antigem-netcap-ca.crt"`)
	w.Write(a.Svc.CA.CertPEM)
}

func (a *API) status(w http.ResponseWriter, r *http.Request) {
	sess := a.Svc.Current()
	st := sess.Store.Stats()
	writeJSON(w, map[string]any{
		"capture":  a.Svc.CaptureInfo(),
		"session":  map[string]any{"id": sess.ID, "name": sess.Name, "flows": st.Flows, "dir": sess.Dir},
		"ca":       map[string]any{"fingerprint": a.Svc.CA.Fingerprint(), "android_name": a.Svc.CA.AndroidName(), "file": a.Svc.CertPath()},
		"text":     a.Svc.Status(),
		"settings": a.Svc.Settings(),
	})
}

// tool runs an MCP tool and returns its text: POST /v1/tool/net_list {"filter": "..."}.
func (a *API) tool(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/v1/tool/")
	args := map[string]any{}
	if r.Method == http.MethodPost {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 4<<20))
		if len(body) > 0 {
			if err := json.Unmarshal(body, &args); err != nil {
				http.Error(w, "body must be a JSON object: "+err.Error(), http.StatusBadRequest)
				return
			}
		}
	}
	for k, v := range r.URL.Query() {
		if k != "token" && len(v) > 0 {
			args[k] = v[0]
		}
	}
	text, err := a.MCP.Call(r.Context(), name, args)
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprintln(w, "error: "+err.Error())
		return
	}
	fmt.Fprint(w, text)
}

// flows: GET /v1/flows?q=&limit=&cursor=&sort=&session= → JSON flows (for GUIs).
func (a *API) flows(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	res, err := a.Svc.List(service.ListOptions{Session: q.Get("session"), Filter: q.Get("q"), Limit: limit, Cursor: q.Get("cursor"),
		Sort: q.Get("sort"), Collapse: false, Noise: true, Secrets: true})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	flows := make([]any, 0, len(res.Rows))
	for _, row := range res.Rows {
		flows = append(flows, row.Flow)
	}
	writeJSON(w, map[string]any{"session": res.Session.ID, "total": res.Total, "flows": flows, "next": res.Next})
}

// flow: GET /v1/flows/{id}, /v1/flows/{id}/body/{req|res}[?raw=1], /v1/flows/{id}/messages?from=&limit=
func (a *API) flow(w http.ResponseWriter, r *http.Request) {
	rest := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/flows/"), "/")
	q := r.URL.Query()
	sess, err := a.Svc.Session(q.Get("session"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	id, _, err := service.ParseID(rest[0])
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	f, err := sess.Store.Get(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	if len(rest) == 1 {
		writeJSON(w, f)
		return
	}
	switch rest[1] {
	case "body":
		side := "res"
		if len(rest) > 2 && rest[2] == "req" {
			side = "req"
		}
		data, _, err := a.Svc.Body(sess, f, side, q.Get("raw") == "1", 0)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		ct := f.Res.ContentType
		if side == "req" {
			ct = f.Req.ContentType
		}
		if ct == "" {
			ct = "application/octet-stream"
		}
		w.Header().Set("Content-Type", ct)
		w.Write(data)
	case "messages":
		from, _ := strconv.Atoi(q.Get("from"))
		limit, _ := strconv.Atoi(q.Get("limit"))
		msgs, err := sess.Store.Messages(id, from, limit)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		out := make([]map[string]any, 0, len(msgs))
		for _, m := range msgs {
			out = append(out, map[string]any{"index": m.Index, "dir": m.Dir, "time": m.Time, "type": m.Type, "size": m.Size,
				"truncated": m.Truncated, "compressed": m.Compressed, "data": m.Data})
		}
		writeJSON(w, out)
	default:
		http.NotFound(w, r)
	}
}

// events streams finished flows as server-sent events.
func (a *API) events(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming not supported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	ch, stop := a.Svc.Hub.Subscribe(1024)
	defer stop()
	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	fmt.Fprint(w, ": connected\n\n")
	fl.Flush()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ping.C:
			fmt.Fprint(w, ": ping\n\n")
			fl.Flush()
		case e, ok := <-ch:
			if !ok {
				return
			}
			b, _ := json.Marshal(e)
			fmt.Fprintf(w, "event: flow\ndata: %s\n\n", b)
			fl.Flush()
		}
	}
}

// hooks connects an external hook: WebSocket /v1/hooks?mode=observe|modify&phases=request,response&filter=…&timeout=200ms
// &name=…&body=0. Every text frame is one JSON message (see the docs, "External hooks").
func (a *API) hooks(w http.ResponseWriter, r *http.Request) {
	o, err := service.ParseHookOptions(r.URL.Query().Get)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	srv := websocket.Server{
		Handshake: func(*websocket.Config, *http.Request) error { return nil }, // local programs send no Origin
		Handler: func(ws *websocket.Conn) {
			defer ws.Close()
			ws.MaxPayloadBytes = 16 << 20
			recv := func() ([]byte, error) {
				var msg string
				err := websocket.Message.Receive(ws, &msg)
				return []byte(msg), err
			}
			send := func(b []byte) error { return websocket.Message.Send(ws, string(b)) }
			if err := a.Svc.RunHook(r.Context(), o, recv, send); err != nil {
				b, _ := json.Marshal(map[string]string{"type": "error", "error": err.Error()})
				_ = send(b)
			}
		},
	}
	srv.ServeHTTP(w, r)
}
