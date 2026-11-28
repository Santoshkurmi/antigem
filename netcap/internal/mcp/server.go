// Package mcp is network_mcp: the engine's tools for AI agents over MCP (JSON-RPC over HTTP POST, the same way the
// app's other MCP servers answer). Tools return short text, built for small context.
package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"antigem/netcap/internal/service"
)

// Tool is one MCP tool.
type Tool struct {
	Name  string
	Group string
	Desc  string
	Props map[string]any
	Req   []string
	Run   func(ctx context.Context, a Args) (string, error)
}

// Server serves network_mcp.
type Server struct {
	svc    *service.Service
	tools  []Tool
	byName map[string]*Tool
}

// New builds the server with all tools.
func New(svc *service.Service) *Server {
	s := &Server{svc: svc, byName: map[string]*Tool{}}
	s.tools = tools(svc)
	for i := range s.tools {
		s.byName[s.tools[i].Name] = &s.tools[i]
	}
	return s
}

// Tools lists the tool names (for the CLI).
func (s *Server) Tools() []string {
	var out []string
	for _, t := range s.tools {
		out = append(out, t.Name)
	}
	sort.Strings(out)
	return out
}

// Call runs a tool directly (the CLI and the HTTP API use it).
func (s *Server) Call(ctx context.Context, name string, args map[string]any) (string, error) {
	t := s.byName[name]
	if t == nil {
		t = s.byName["net_"+name]
	}
	if t == nil {
		return "", fmt.Errorf("no tool %q (tools: %s)", name, strings.Join(s.Tools(), ", "))
	}
	return t.Run(ctx, Args(args))
}

func (s *Server) groups(r *http.Request) map[string]bool {
	g := r.URL.Query().Get("groups")
	if g == "" {
		if set := s.svc.Settings().ToolGroups; len(set) > 0 {
			g = strings.Join(set, ",")
		}
	}
	if g == "" || g == "all" {
		return nil
	}
	m := map[string]bool{}
	for _, x := range strings.Split(g, ",") {
		m[strings.TrimSpace(x)] = true
	}
	return m
}

// ServeHTTP answers MCP JSON-RPC requests (single or batch). Notifications get 202.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSON(w, http.StatusMethodNotAllowed, rpcError(nil, -32600, "Use POST for MCP requests"))
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, rpcError(nil, -32700, "read error"))
		return
	}
	groups := s.groups(r)
	trim := strings.TrimSpace(string(body))
	if strings.HasPrefix(trim, "[") {
		var batch []json.RawMessage
		if json.Unmarshal(body, &batch) != nil {
			writeJSON(w, http.StatusOK, rpcError(nil, -32700, "Parse error"))
			return
		}
		var out []any
		for _, item := range batch {
			if resp := s.handle(r.Context(), item, groups); resp != nil {
				out = append(out, resp)
			}
		}
		if len(out) == 0 {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		writeJSON(w, http.StatusOK, out)
		return
	}
	resp := s.handle(r.Context(), body, groups)
	if resp == nil {
		w.WriteHeader(http.StatusAccepted)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

type rpcReq struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

var knownVersions = map[string]bool{"2024-11-05": true, "2025-03-26": true, "2025-06-18": true, "2025-11-25": true}

func (s *Server) handle(ctx context.Context, raw []byte, groups map[string]bool) any {
	var req rpcReq
	if err := json.Unmarshal(raw, &req); err != nil {
		return rpcError(nil, -32700, "Parse error")
	}
	if len(req.ID) == 0 || string(req.ID) == "null" {
		return nil // notification
	}
	switch req.Method {
	case "initialize":
		var p struct {
			ProtocolVersion string `json:"protocolVersion"`
		}
		_ = json.Unmarshal(req.Params, &p)
		v := p.ProtocolVersion
		if !knownVersions[v] {
			v = "2024-11-05"
		}
		return rpcResult(req.ID, map[string]any{
			"protocolVersion": v,
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": "network_mcp", "version": "1.0.0"},
			"instructions":    Instructions,
		})
	case "ping":
		return rpcResult(req.ID, map[string]any{})
	case "tools/list":
		var list []any
		for _, t := range s.tools {
			if groups != nil && !groups[t.Group] {
				continue
			}
			schema := map[string]any{"type": "object", "properties": t.Props}
			if len(t.Req) > 0 {
				schema["required"] = t.Req
			}
			list = append(list, map[string]any{"name": t.Name, "description": t.Desc, "inputSchema": schema})
		}
		return rpcResult(req.ID, map[string]any{"tools": list})
	case "tools/call":
		var p struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		}
		if err := json.Unmarshal(req.Params, &p); err != nil {
			return rpcError(req.ID, -32602, "bad params")
		}
		t := s.byName[p.Name]
		if t == nil || (groups != nil && !groups[t.Group]) {
			return rpcError(req.ID, -32602, "unknown tool "+p.Name)
		}
		text, err := t.Run(ctx, Args(p.Arguments))
		if err != nil {
			return rpcResult(req.ID, map[string]any{"content": []any{map[string]any{"type": "text", "text": "error: " + err.Error()}}, "isError": true})
		}
		return rpcResult(req.ID, map[string]any{"content": []any{map[string]any{"type": "text", "text": text}}})
	case "resources/list":
		return rpcResult(req.ID, map[string]any{"resources": []any{}})
	case "prompts/list":
		return rpcResult(req.ID, map[string]any{"prompts": []any{}})
	}
	return rpcError(req.ID, -32601, "Method not found: "+req.Method)
}

func rpcResult(id json.RawMessage, result any) map[string]any {
	return map[string]any{"jsonrpc": "2.0", "id": id, "result": result}
}

func rpcError(id json.RawMessage, code int, msg string) map[string]any {
	var idv any = nil
	if len(id) > 0 {
		idv = id
	}
	return map[string]any{"jsonrpc": "2.0", "id": idv, "error": map[string]any{"code": code, "message": msg}}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// Args are tool arguments with lenient getters (models send numbers as strings and lists as text).
type Args map[string]any

// Str returns a string argument.
func (a Args) Str(k string) string {
	switch v := a[k].(type) {
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(v)
	case nil:
		return ""
	}
	b, _ := json.Marshal(a[k])
	return string(b)
}

// Int returns an integer argument (def when missing).
func (a Args) Int(k string, def int) int {
	switch v := a[k].(type) {
	case float64:
		return int(v)
	case int:
		return v
	case int64:
		return int(v)
	case string:
		if n, err := strconv.Atoi(strings.TrimSpace(v)); err == nil {
			return n
		}
	}
	return def
}

// Bool returns a boolean argument.
func (a Args) Bool(k string, def bool) bool {
	switch v := a[k].(type) {
	case bool:
		return v
	case string:
		if b, err := strconv.ParseBool(v); err == nil {
			return b
		}
	}
	return def
}

// Strs returns a list argument (a JSON list, or comma-separated text).
func (a Args) Strs(k string) []string {
	switch v := a[k].(type) {
	case []any:
		var out []string
		for _, x := range v {
			if s, ok := x.(string); ok && strings.TrimSpace(s) != "" {
				out = append(out, strings.TrimSpace(s))
			}
		}
		return out
	case string:
		var out []string
		for _, s := range strings.Split(v, ",") {
			if s = strings.TrimSpace(s); s != "" {
				out = append(out, s)
			}
		}
		return out
	}
	return nil
}
