package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"antigem/netcap/internal/rules"
	"antigem/netcap/internal/script"
	"antigem/netcap/internal/service"
)

// Instructions are sent with initialize: how to use the tools without filling the context.
const Instructions = `network_mcp shows (and later changes) the network traffic of chosen apps: a debugging and learning tool for your own apps and your own traffic.
Workflow: net_status → net_capture action=start apps=[package] → use the app (android_launch_app, android_tap … from android_automation_mcp) → net_summarize (the API map) → net_list with a filter → net_get on interesting flows.
Keep context small: filter in the engine instead of paging; net_summarize before net_list; net_get shows small bodies whole and big ones as an outline: then ask jq=… or grep=… for the part you need; use cursor/after:fN for only new traffic; net_wait instead of polling after a tap.
Many flows or big bodies: net_export format=folder (or capture option mirror) writes request.http/response.http/meta.json per flow; read them with your own file tools.
Filter language (net_list, net_summarize, net_wait, net_export): terms ANDed by spaces, OR, -term to negate, (…) groups, bare word = URL contains.
Fields: app: host: (glob, *.example.com) method: path: (glob) url: (text or /regex/) status: (404, 4xx, 200-299) type: (json html xml js css image media font form proto text binary) kind: (http ws grpc tcp tls) http: (1, 2) tls: (decrypted passthrough untrusted plain failed) size>10k time>500ms since:10m after:f120 tag: error:any req.header.NAME: res.header.NAME: body:"text" res.json:'.user.role=="admin"' noise:true.
Ids: f123 = flow, f123.m4 = WebSocket message 4. Secrets (tokens, cookies, passwords) show as [hidden #abcd] unless the user allowed them; the same #abcd means the same value.
Change traffic: net_rules (block, mock, map, rewrite, delay, breakpoint …; {{var}} variables), net_intercept (paused flows), net_send (send a request again with changes, max 30/min per host).
Scripts (net_script) are JavaScript files the engine runs on every matching flow, for logic rules can't do (conditions, pulling a token out, signing, custom commands); repeated work stays in the engine instead of your context. Header lines: // @name x, // @match <filter>, // @body request|response (read the body before the hook starts), // @timeout 20ms. Functions: onRequest(f, ctx), onResponse(f, ctx), onMessage(m, ctx) (WebSocket: m.dir m.text m.json() m.setText() m.drop() m.sendExtra() m.reply()), onComplete(f, ctx) (after the flow ends, reads stored bodies), command("name", function (args, ctx) {…}) (net_script action=call), every("5m", function (ctx) {…}).
Write plain synchronous JavaScript (ES2020: let, arrow functions, classes, JSON; no await, fetch, require or timers). Traffic hooks have 20 ms, onComplete 500 ms, commands 5 s; a hook that fails or runs over leaves the flow unchanged, 20 failures in a row turn the script off.
f: id host path method url status params(query) req res, headers.get/set/add/remove/all, text() json() bytes() setText() setJson(), f.req.setPath/setQuery/setMethod, f.res.setStatus, f.tag() f.note() f.respond({status, headers, json|text|file}) f.drop() f.delay(ms) f.redirect(url). ctx: vars (shared with rules' {{var}}), store (kept across restarts; assign whole values), log(), finding({severity, title, detail, flow}) (net_findings), files.write/append(name, data) into the session's out/ folder, files.read, query(filter, {limit, group_by}) and send({url, method, headers, json}) (not in traffic hooks; send has net_send's limit), util (base64 base64url hex url .encode/.decode, sha256 hmac md5, jwtDecode, gzip gunzip, uuid, now).
Top-level variables are per worker (a script runs in up to 4 at once): keep shared state in ctx.vars or ctx.store. Check a new script with net_script action=test (runs it on stored flows, changes nothing) before it meets live traffic. Filters also take js:"<expression on f>", e.g. js:"f.res.json().items.length === 0".
Apps that refuse the AntiGem certificate pass through without decryption (shown as "not decrypted"); this tool does not get around such protections.`

const filterDesc = "Filter, e.g. `host:api.example.com method:POST status:4xx since:10m` (fields: app host method path url status type kind http tls size time since after tag flag error req.header.NAME res.header.NAME body req.json res.json js noise; OR, -negate, (groups))"

func tools(svc *service.Service) []Tool {
	secrets := func() bool { return svc.Settings().ShowSecretsToAI }
	str := func(desc string) map[string]any { return map[string]any{"type": "string", "description": desc} }
	num := func(desc string) map[string]any { return map[string]any{"type": "integer", "description": desc} }
	boo := func(desc string) map[string]any { return map[string]any{"type": "boolean", "description": desc} }
	list := func(desc string) map[string]any {
		return map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "description": desc}
	}
	enum := func(desc string, vals ...string) map[string]any {
		return map[string]any{"type": "string", "enum": vals, "description": desc}
	}
	session := str("Session id (default: the active one)")
	obj := func(desc string) map[string]any { return map[string]any{"type": "object", "description": desc} }

	return []Tool{
		{
			Name: "net_status", Group: "core",
			Desc:  "Capture state, active session, flow counts, certificate state, hosts not decrypted, proxy port. Start here.",
			Props: map[string]any{},
			Run:   func(ctx context.Context, a Args) (string, error) { return svc.Status(), nil },
		},
		{
			Name: "net_capture", Group: "core",
			Desc: "Start, stop, pause or resume recording. On the phone, start opens the VPN for the given apps (package names). " +
				"Options: decrypt_only / never_decrypt / ignore (host globs), mirror (a filter: matching flows are also written as files for you to read; \"all\" for everything), new_session (name). " +
				"reset_untrusted forgets the hosts whose app refused the certificate, so the next connection tries decryption again.",
			Props: map[string]any{
				"action":        enum("What to do", "start", "stop", "pause", "resume", "reset_untrusted"),
				"apps":          list("Package names to capture (phone), e.g. [\"com.example.app\"]"),
				"decrypt_only":  list("Decrypt only these hosts (globs); others pass through encrypted"),
				"never_decrypt": list("Never decrypt these hosts (globs)"),
				"ignore":        list("Don't record these hosts at all (globs)"),
				"mirror":        str("Also write matching flows to files: a filter, \"all\", or \"off\""),
				"new_session":   str("Start a new session with this name first"),
				"host":          str("reset_untrusted: only this host (default all)"),
			},
			Req: []string{"action"},
			Run: func(ctx context.Context, a Args) (string, error) {
				switch a.Str("action") {
				case "start":
					err := svc.StartCapture(service.StartOptions{Apps: a.Strs("apps"), DecryptOnly: a.Strs("decrypt_only"),
						NeverDecrypt: a.Strs("never_decrypt"), Ignore: a.Strs("ignore"), Mirror: a.Str("mirror"),
						NewSession: a.Str("new_session"), ByAgent: true})
					if err != nil {
						return "", err
					}
					return "capture started\n" + svc.Status(), nil
				case "stop":
					if err := svc.StopCapture(); err != nil {
						return "", err
					}
					return "capture stopped\n" + svc.Status(), nil
				case "pause":
					svc.Pause(true)
					return "paused: traffic keeps flowing, nothing is recorded", nil
				case "resume":
					svc.Pause(false)
					return "recording again", nil
				case "reset_untrusted":
					n := svc.Untrusted.Clear(a.Str("host"))
					return fmt.Sprintf("forgot %d hosts; their next connections try decryption again (restart the app if it keeps old connections)", n), nil
				}
				return "", fmt.Errorf("action must be start, stop, pause, resume or reset_untrusted")
			},
		},
		{
			Name: "net_list", Group: "core",
			Desc: "List flows as one short line each, newest first: id time method status http host path type size time [tags]. " +
				"Repeats of the same endpoint+status collapse into one line with ×N. Static files (images, fonts, media, css) are hidden unless noise=true or the filter names type:. " +
				"Use cursor (from the output) for the next page, or after:fN in the filter for only newer flows.",
			Props: map[string]any{
				"filter":   str(filterDesc),
				"limit":    num("Rows (default 20, max 500)"),
				"cursor":   str("Continue after this id (from the previous output)"),
				"sort":     enum("Order", "newest", "oldest", "slowest", "largest"),
				"collapse": boo("Collapse repeats (default true)"),
				"noise":    boo("Include static files (default false)"),
				"session":  session,
			},
			Run: func(ctx context.Context, a Args) (string, error) {
				o := service.ListOptions{Session: a.Str("session"), Filter: a.Str("filter"), Limit: a.Int("limit", 20),
					Cursor: a.Str("cursor"), Sort: a.Str("sort"), Collapse: a.Bool("collapse", true), Noise: a.Bool("noise", false), Secrets: secrets()}
				r, err := svc.List(o)
				if err != nil {
					return "", err
				}
				return r.Text(o), nil
			},
		},
		{
			Name: "net_get", Group: "core",
			Desc: "One flow: summary line, headers (short), and bodies: small ones whole, big JSON as an outline (keys, types, array sizes), binary never inline. " +
				"Focus with view: pretty | raw | outline | jq (with jq=…) | grep (with grep=…, context=N) | range (from, length) | hex | protobuf | file (writes the decoded body to a file and returns the path). " +
				"side picks req or res for focused views. parts: summary, headers, req.headers, res.headers, body, req.body, res.body, timing, tls, trailers, all. Also f12.m3 for one WebSocket message.",
			Props: map[string]any{
				"id":        str("Flow id, e.g. f301 (or f301.m3 for a WebSocket message)"),
				"parts":     list("Parts to show (default: summary, headers, body)"),
				"view":      enum("Body view", "auto", "pretty", "raw", "outline", "jq", "grep", "range", "hex", "protobuf", "file"),
				"side":      enum("Body side for focused views (default res)", "req", "res"),
				"jq":        str("jq expression on the JSON body, e.g. .data[] | {id, name}"),
				"grep":      str("Text or /regex/ to find in the body"),
				"context":   num("grep: lines around each match (default 0)"),
				"from":      num("range/hex: start byte"),
				"length":    num("range/hex: bytes"),
				"max_chars": num("Cut the answer at this many characters (default 4000)"),
				"session":   session,
			},
			Req: []string{"id"},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.Get(service.GetOptions{Session: a.Str("session"), ID: a.Str("id"), Parts: a.Strs("parts"), View: a.Str("view"),
					Side: a.Str("side"), JQ: a.Str("jq"), Grep: a.Str("grep"), Context: a.Int("context", 0), From: a.Int("from", 0),
					Length: a.Int("length", 0), MaxChars: a.Int("max_chars", 4000), Secrets: secrets()})
			},
		},
		{
			Name: "net_wait", Group: "core",
			Desc:  "Wait for flows matching a filter to finish (use right after a tap or launch instead of polling). Returns their rows, or 'none' after the timeout.",
			Props: map[string]any{"filter": str(filterDesc), "timeout": num("Seconds (default 20, max 120)"), "count": num("How many flows to wait for (default 1)")},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.Wait(ctx, a.Str("filter"), time.Duration(a.Int("timeout", 20))*time.Second, a.Int("count", 1), secrets())
			},
		},
		{
			Name: "net_summarize", Group: "core",
			Desc: "The API map: flows grouped by endpoint (method + host + path with ids as {n} {uuid} {hash} {id}), with counts, statuses, types and times, hosts sorted by traffic. " +
				"group_by host|app|status|type|kind|tag for other views. values=EXPR lists the distinct values with counts instead: req.header.NAME, res.header.NAME, query.KEY, host, path, app, status, method, endpoint, req.json:<jq>, res.json:<jq>.",
			Props: map[string]any{
				"filter":   str(filterDesc),
				"group_by": enum("Grouping (default endpoint)", "endpoint", "host", "app", "status", "type", "kind", "tag"),
				"values":   str("Count distinct values of this expression instead of grouping"),
				"limit":    num("Groups (default 40)"),
				"noise":    boo("Include static files"),
				"session":  session,
			},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.Summarize(service.SummaryOptions{Session: a.Str("session"), Filter: a.Str("filter"), GroupBy: a.Str("group_by"),
					Values: a.Str("values"), Limit: a.Int("limit", 40), Noise: a.Bool("noise", false), Secrets: secrets()})
			},
		},
		{
			Name: "net_diff", Group: "analyze",
			Desc:  "Compare two flows: URL, status, headers (date/trace headers skipped) and bodies (JSON by path, text by line).",
			Props: map[string]any{"a": str("First flow id"), "b": str("Second flow id"), "parts": enum("Which side", "both", "req", "res"), "ignore": list("Header names or JSON paths to skip"), "session": session},
			Req:   []string{"a", "b"},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.Diff(service.DiffOptions{Session: a.Str("session"), A: a.Str("a"), B: a.Str("b"), Parts: a.Str("parts"), Ignore: a.Strs("ignore"), Secrets: secrets()})
			},
		},
		{
			Name: "net_ws", Group: "analyze",
			Desc:  "Messages of a WebSocket flow (or chunks of a raw TCP flow), one line each: id direction type size time preview. net_get id=f12.m3 shows one in full.",
			Props: map[string]any{"id": str("Flow id"), "dir": enum("Only one direction", "in", "out"), "text": str("Only messages containing this text or /regex/"), "from": num("Start at message index"), "limit": num("Messages (default 30)"), "session": session},
			Req:   []string{"id"},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.Messages(service.MessagesOptions{Session: a.Str("session"), ID: a.Str("id"), Dir: a.Str("dir"), Text: a.Str("text"),
					From: a.Int("from", 0), Limit: a.Int("limit", 30), Secrets: secrets()})
			},
		},
		{
			Name: "net_dns", Group: "analyze",
			Desc:  "DNS queries the captured apps made (name, type, answers, time), newest first: shows every domain an app looks up, even ones it never connects to.",
			Props: map[string]any{"name": str("Only names containing this"), "limit": num("Rows (default 50)"), "session": session},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.DNSText(a.Str("session"), a.Str("name"), a.Int("limit", 50))
			},
		},
		{
			Name: "net_export", Group: "analyze",
			Desc: "Write matching flows to files and return the path: folder (one folder per flow with request.http, response.http, meta.json, body files; index.jsonl), har (HAR 1.2 with WebSocket messages), " +
				"curl (commands, returned as text) or http (raw requests, returned as text).",
			Props: map[string]any{"filter": str(filterDesc), "format": enum("Format (default folder)", "folder", "har", "curl", "http"), "path": str("Where to write (default: the session's out/ folder)"), "limit": num("Flows (default 2000)"), "session": session},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.Export(service.ExportOptions{Session: a.Str("session"), Filter: a.Str("filter"), Format: a.Str("format"), Path: a.Str("path"), Limit: a.Int("limit", 2000), Secrets: secrets()})
			},
		},
		{
			Name: "net_decode", Group: "analyze",
			Desc:  "Decode (or encode) a value: auto, base64, base64url, url, hex, jwt (header + claims, exp as a date; signature not checked), gzip/deflate/br/zstd (input base64 or hex), protobuf (base64 or hex), timestamp, html.",
			Props: map[string]any{"input": str("The value"), "as": str("Encoding (default auto)"), "encode": boo("Encode instead (base64, base64url, url, hex, sha256)")},
			Req:   []string{"input"},
			Run: func(ctx context.Context, a Args) (string, error) {
				return service.Decode(a.Str("input"), a.Str("as"), a.Bool("encode", false))
			},
		},
		{
			Name: "net_rules", Group: "modify",
			Desc: "Rules change traffic without code. action list | add | update | remove | enable | disable | test | set_var. A rule: " +
				`{"name":"…","match":"<filter>","phase":"request|response","enabled":true,"priority":0,"action":{"type":…}}. ` +
				"Types: block {status: 0 = reset, or a status}, mock {status, headers, body | json | file}, map_local {file or folder}, " +
				"map_remote {url: https://host[/prefix], keep_path}, rewrite {set_headers, remove_headers, set_status, set_query, remove_query, set_method, " +
				"replace: [{find, with, regex}], json_set: {\".a.b[0]\": value}, json_delete: [paths], body, json, clear_body}, delay {ms, kbps}, tag {tag}, " +
				"set_var {var, from: res.json:.path | res.header:Name | req.header:Name | res.body:~regex(group) | query:key}, breakpoint {timeout_s}, " +
				"passthrough (don't decrypt), ignore (don't record). {{var}} in any value is replaced by a variable. test shows which stored flows a rule matches.",
			Props: map[string]any{
				"action": enum("What to do (default list)", "list", "add", "update", "remove", "enable", "disable", "test", "set_var"),
				"rule":   map[string]any{"type": "object", "description": "The rule (add, update, test)"},
				"id":     str("Rule id (update, remove, enable, disable, test)"),
				"var":    str("set_var: variable name"),
				"value":  str("set_var: value"),
			},
			Run: func(ctx context.Context, a Args) (string, error) {
				act := a.Str("action")
				parseRule := func() (*rules.Rule, error) {
					var r rules.Rule
					raw := a["rule"]
					var b []byte
					if str, ok := raw.(string); ok {
						b = []byte(str)
					} else {
						b, _ = json.Marshal(raw)
					}
					if raw == nil {
						return nil, fmt.Errorf("give rule (a JSON object)")
					}
					if err := json.Unmarshal(b, &r); err != nil {
						return nil, fmt.Errorf("rule: %w", err)
					}
					var fields map[string]any
					_ = json.Unmarshal(b, &fields)
					if _, has := fields["enabled"]; !has {
						r.Enabled = true // a new rule is on unless it says otherwise
					}
					return &r, nil
				}
				switch act {
				case "add":
					r, err := parseRule()
					if err != nil {
						return "", err
					}
					r.ID = ""
					added, err := svc.Rules.Add(r)
					if err != nil {
						return "", err
					}
					return "added " + added.ID + "\n" + svc.RulesText(), nil
				case "update":
					r, err := parseRule()
					if err != nil {
						return "", err
					}
					if a.Str("id") != "" {
						r.ID = a.Str("id")
					}
					if err := svc.Rules.Update(r); err != nil {
						return "", err
					}
					return "updated " + r.ID + "\n" + svc.RulesText(), nil
				case "remove":
					if err := svc.Rules.Remove(a.Str("id")); err != nil {
						return "", err
					}
					return "removed " + a.Str("id"), nil
				case "enable", "disable":
					if err := svc.Rules.Enable(a.Str("id"), act == "enable"); err != nil {
						return "", err
					}
					return act + "d " + a.Str("id"), nil
				case "test":
					var r *rules.Rule
					if a.Str("id") != "" {
						if r = svc.Rules.Get(a.Str("id")); r == nil {
							return "", fmt.Errorf("no rule %s", a.Str("id"))
						}
					} else {
						var err error
						if r, err = parseRule(); err != nil {
							return "", err
						}
					}
					return svc.TestRule(r, a.Str("session"), 10)
				case "set_var":
					if a.Str("var") == "" {
						return "", fmt.Errorf("give var and value")
					}
					svc.Rules.SetVar(a.Str("var"), a.Str("value"))
					return "{{" + a.Str("var") + "}} set", nil
				}
				return svc.RulesText(), nil
			},
		},
		{
			Name: "net_intercept", Group: "modify",
			Desc: "Flows paused by breakpoint rules: list, get (headers and body), resume (with changes: method, url, set_headers, remove_headers, query, " +
				"body_text, json_set, json_delete, replace, status for responses), drop (reset), or respond (answer yourself: status, headers, body or json). " +
				"Paused flows continue by themselves after the rule's timeout (default 30 s); apps often give up after 10–30 s.",
			Props: map[string]any{
				"action":         enum("What to do (default list)", "list", "get", "resume", "drop", "respond"),
				"id":             str("Paused item, e.g. x3"),
				"method":         str("resume: new method"),
				"url":            str("resume: new URL (another host = sent there instead)"),
				"set_headers":    map[string]any{"type": "object", "description": "resume/respond: headers to set"},
				"remove_headers": list("resume: headers to remove"),
				"query":          map[string]any{"type": "object", "description": "resume: query keys to set"},
				"body_text":      str("resume/respond: the whole body"),
				"json_set":       map[string]any{"type": "object", "description": "resume: {\".path\": value} edits of a JSON body"},
				"json_delete":    list("resume: JSON paths to delete"),
				"status":         num("resume (response) or respond: status"),
				"json":           map[string]any{"description": "respond: a JSON body"},
			},
			Run: func(ctx context.Context, a Args) (string, error) {
				switch a.Str("action") {
				case "get":
					return svc.PausedText(a.Str("id"), secrets())
				case "resume":
					return "resumed " + a.Str("id"), svc.Resolve(a.Str("id"), service.Decision{Action: "continue", Patch: patchFrom(a)})
				case "drop":
					return "dropped " + a.Str("id"), svc.Resolve(a.Str("id"), service.Decision{Action: "drop"})
				case "respond":
					m := &rules.Action{Type: "mock", Status: a.Int("status", 200), Body: a.Str("body_text"), JSON: a["json"], Headers: strMap(a["set_headers"])}
					return "answered " + a.Str("id"), svc.Resolve(a.Str("id"), service.Decision{Action: "respond", Mock: m})
				}
				return svc.PausedText("", secrets())
			},
		},
		{
			Name: "net_send", Group: "modify",
			Desc: "Send one request again (from=f12) or a raw HTTP request (raw=…), optionally changed: method, url, set_headers, remove_headers, query, " +
				"body_text, json_set {\".path\": value}, json_delete, replace [{find, with, regex}]. The answer is recorded as a new flow (resent from …) and shown like net_get. " +
				"At most 30 requests a minute per host; don't send in loops.",
			Props: map[string]any{
				"from":             str("Flow id to send again"),
				"raw":              str("Raw HTTP request text (request line, headers, blank line, body)"),
				"scheme":           enum("raw with a relative URL: scheme (default https)", "https", "http"),
				"method":           str("New method"),
				"url":              str("New URL (absolute, or a path)"),
				"set_headers":      map[string]any{"type": "object", "description": "Headers to set ({{var}} works)"},
				"remove_headers":   list("Headers to remove"),
				"query":            map[string]any{"type": "object", "description": "Query keys to set"},
				"body_text":        str("New body"),
				"json_set":         map[string]any{"type": "object", "description": "Edits of a JSON body"},
				"json_delete":      list("JSON paths to delete"),
				"follow_redirects": boo("Follow redirects (default false)"),
				"timeout":          num("Seconds (default 30)"),
				"session":          session,
			},
			Run: func(ctx context.Context, a Args) (string, error) {
				return svc.Send(ctx, service.SendOptions{Session: a.Str("session"), From: a.Str("from"), Raw: a.Str("raw"), Scheme: a.Str("scheme"),
					Patch: patchFrom(a), FollowRedirects: a.Bool("follow_redirects", false), Timeout: time.Duration(a.Int("timeout", 30)) * time.Second, Secrets: secrets()})
			},
		},
		{
			Name: "net_script", Group: "scripts",
			Desc: "JavaScript scripts that run inside the engine on matching flows (see the instructions for the API). action list (default) | load (code=… or path=…, name; session=true for this session only) | " +
				"enable | disable | remove (name) | reload (name, or all) | logs (name, lines, clear) | call (name=script.command, args={…}) | " +
				"test (name, optional filter, limit: runs the hooks on stored flows and shows what they would change; nothing is changed, sent or saved). " +
				"Files are " + svc.Scripts.Dir() + "/*.js (all sessions) and <session>/scripts/*.js; editing a file reloads it, syntax errors come back with the line.",
			Props: map[string]any{
				"action":  enum("What to do (default list)", "list", "load", "enable", "disable", "remove", "reload", "logs", "call", "test"),
				"name":    str("Script name (call: script.command)"),
				"code":    str("load: the script's source"),
				"path":    str("load: a .js file to copy into the scripts folder"),
				"session": boo("load: only for the active session"),
				"args":    obj("call: the command's argument (any JSON)"),
				"filter":  str("test: only stored flows matching this filter (besides the script's @match)"),
				"limit":   num("test: flows (default 5)"),
				"lines":   num("logs: lines (default 40)"),
				"clear":   boo("logs: empty the log after showing it"),
			},
			Run: func(ctx context.Context, a Args) (string, error) {
				sm := svc.Scripts
				name := a.Str("name")
				need := func() (*script.Script, error) {
					if name == "" {
						return nil, fmt.Errorf("give name")
					}
					sc := sm.Get(name)
					if sc == nil {
						return nil, fmt.Errorf("no script %q (net_script action=list)", name)
					}
					return sc, nil
				}
				switch a.Str("action") {
				case "load":
					code := a.Str("code")
					if p := a.Str("path"); p != "" && code == "" {
						b, err := os.ReadFile(p)
						if err != nil {
							return "", err
						}
						code = string(b)
						if name == "" && !strings.Contains(code, "@name") {
							name = strings.TrimSuffix(filepath.Base(p), ".js")
						}
					}
					sc, err := sm.Save(name, code, a.Bool("session", false))
					if err != nil {
						return "", err
					}
					return service.ScriptText(sc), nil
				case "enable", "disable":
					if err := sm.Enable(name, a.Str("action") == "enable"); err != nil {
						return "", err
					}
					return a.Str("action") + "d " + name, nil
				case "remove":
					if err := sm.Remove(name); err != nil {
						return "", err
					}
					return "removed " + name, nil
				case "reload":
					if name != "" {
						sc, err := need()
						if err != nil {
							return "", err
						}
						sm.ReloadFile(sc.File)
						if sc = sm.Get(name); sc == nil {
							return "reloaded; the script is gone (renamed or removed?)", nil
						}
						return service.ScriptText(sc), nil
					}
					msgs := sm.Reload()
					if len(msgs) == 0 {
						return "nothing changed\n" + svc.ScriptsText(), nil
					}
					return strings.Join(msgs, "\n") + "\n" + svc.ScriptsText(), nil
				case "logs":
					sc, err := need()
					if err != nil {
						return "", err
					}
					lines := sc.Logs(a.Int("lines", 40))
					if a.Bool("clear", false) {
						sc.ClearLogs()
					}
					if len(lines) == 0 {
						return "the log is empty (ctx.log(…) writes to it; errors land here too)", nil
					}
					return strings.Join(lines, "\n"), nil
				case "call":
					scName, cmd, ok := strings.Cut(name, ".")
					if !ok {
						return "", fmt.Errorf("name=script.command, e.g. login-debug.slow_endpoints")
					}
					sc := sm.Get(scName)
					if sc == nil {
						return "", fmt.Errorf("no script %q", scName)
					}
					if !sc.Running() {
						return "", fmt.Errorf("%s is off or doesn't load", scName)
					}
					out, err := sc.Command(ctx, cmd, a["args"])
					if err != nil {
						return "", err
					}
					return service.Cut(out, 20000), nil
				case "test":
					sc, err := need()
					if err != nil {
						return "", err
					}
					return svc.TestScript(sc, a.Str("filter"), a.Int("limit", 5), a.Str("session"))
				}
				return svc.ScriptsText(), nil
			},
		},
		{
			Name: "net_findings", Group: "scripts",
			Desc: "What scripts reported with ctx.finding (and the engine about scripts it turned off), newest first: action list (default; severity info|warn|error = at least, script, limit) | get (id) | clear (script, or all).",
			Props: map[string]any{
				"action":   enum("What to do (default list)", "list", "get", "clear"),
				"id":       str("get: finding id, e.g. 3"),
				"severity": enum("list: at least this severity", "info", "warn", "error"),
				"script":   str("list / clear: only this script"),
				"limit":    num("list: rows (default 30)"),
				"session":  session,
			},
			Run: func(ctx context.Context, a Args) (string, error) {
				switch a.Str("action") {
				case "get":
					return svc.FindingText(a.Str("session"), a.Str("id"))
				case "clear":
					sess, err := svc.Session(a.Str("session"))
					if err != nil {
						return "", err
					}
					n, err := sess.Store.ClearFindings(a.Str("script"))
					if err != nil {
						return "", err
					}
					return fmt.Sprintf("cleared %d findings", n), nil
				}
				return svc.FindingsText(a.Str("session"), a.Str("script"), a.Str("severity"), a.Int("limit", 30))
			},
		},
		{
			Name: "net_cert", Group: "admin",
			Desc:  "The AntiGem CA certificate: status (fingerprint, file, whether the phone trusts it), install / remove as a system certificate (phone, needs root; restart the app under test after).",
			Props: map[string]any{"action": enum("What to do (default status)", "status", "install", "remove")},
			Run: func(ctx context.Context, a Args) (string, error) {
				p := svc.Options().Platform
				switch a.Str("action") {
				case "install":
					if p == nil {
						return "on a PC, trust this file in your browser or system yourself:\n" + svc.CertInfo(), nil
					}
					return p.InstallCert()
				case "remove":
					if p == nil {
						return "on a PC, remove the certificate from your browser or system yourself", nil
					}
					return p.RemoveCert()
				}
				return svc.CertInfo(), nil
			},
		},
		{
			Name: "net_session", Group: "admin",
			Desc:  "Sessions (one per capture project): list, new (name), switch (id), rename (id, name), delete (id; not the active one).",
			Props: map[string]any{"action": enum("What to do (default list)", "list", "new", "switch", "rename", "delete"), "id": str("Session id, e.g. s2"), "name": str("Name")},
			Run: func(ctx context.Context, a Args) (string, error) {
				switch a.Str("action") {
				case "new":
					s, err := svc.NewSession(a.Str("name"))
					if err != nil {
						return "", err
					}
					return "new session " + s.ID + " is active", nil
				case "switch":
					s, err := svc.SwitchSession(a.Str("id"))
					if err != nil {
						return "", err
					}
					return "session " + s.ID + " is active", nil
				case "rename":
					return "renamed", svc.RenameSession(a.Str("id"), a.Str("name"))
				case "delete":
					return "deleted", svc.DeleteSession(a.Str("id"))
				}
				var b strings.Builder
				for _, s := range svc.Sessions() {
					mark := " "
					if s.Active {
						mark = "*"
					}
					fmt.Fprintf(&b, "%s %s %q · %d flows · %s · created %s\n", mark, s.ID, s.Name, s.Flows, service.Size(s.Disk), s.Created.Format("2006-01-02 15:04"))
				}
				return b.String(), nil
			},
		},
	}
}

func strMap(v any) map[string]string {
	m, ok := v.(map[string]any)
	if !ok {
		return nil
	}
	out := map[string]string{}
	for k, x := range m {
		if s, ok := x.(string); ok {
			out[k] = s
		} else {
			b, _ := json.Marshal(x)
			out[k] = string(b)
		}
	}
	return out
}

func anyMap(v any) map[string]any {
	m, _ := v.(map[string]any)
	return m
}

func patchFrom(a Args) service.Patch {
	p := service.Patch{Method: a.Str("method"), URL: a.Str("url"), SetHeaders: strMap(a["set_headers"]), RemoveHeaders: a.Strs("remove_headers"),
		Query: strMap(a["query"]), JSONSet: anyMap(a["json_set"]), JSONDelete: a.Strs("json_delete"), Status: a.Int("status", 0)}
	if _, ok := a["body_text"]; ok {
		b := a.Str("body_text")
		p.BodyText = &b
	}
	if list, ok := a["replace"].([]any); ok {
		for _, x := range list {
			if m, ok := x.(map[string]any); ok {
				r := rules.Replace{}
				r.Find, _ = m["find"].(string)
				r.With, _ = m["with"].(string)
				r.Regex, _ = m["regex"].(bool)
				p.Replace = append(p.Replace, r)
			}
		}
	}
	return p
}
