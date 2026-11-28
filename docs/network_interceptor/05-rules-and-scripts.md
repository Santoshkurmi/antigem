# 05 · Rules, breakpoints, scripts

Three levels:

| Level | For | Speed |
|---|---|---|
| Rules (JSON, no code) | common changes: block, mock, map, rewrite, delay | fastest, compiled once |
| Scripts (JS inside the engine) | logic: conditions, pulling out values, signing, custom commands | fast (milliseconds) |
| External hooks (any language, over JSON lines) | heavy work in Python or Node from the terminal | slower, optional |

All three are files. The AI writes them with its own file tools, the GUI edits the same files, and both see the same
thing.

## Rules

| Type | Does |
|---|---|
| `block` | fails the request (connection reset, or a chosen status) |
| `mock` | answers without contacting the server (status, headers, body as text, JSON or a file) |
| `map_local` | answers from a local file or folder (serve a new version of your JSON, JS or images) |
| `map_remote` | sends to another URL (for example points the app at your local dev server) |
| `rewrite` | changes headers (set, remove), status, query, body (find/replace with regex), JSON (`json_set`, `json_delete`) |
| `delay` | adds delay or limits bandwidth (test slow networks) |
| `breakpoint` | pauses for editing (below) |
| `tag` | adds a tag (for filters) |
| `set_var` | copies a value (JSON path, header, regex group) into a variable |
| `passthrough` | doesn't decrypt this host |
| `ignore` | doesn't record (traffic still passes) |

Example, `rules/new-ui-flag.json`:

```json
{
  "id": "r3",
  "name": "Turn on the new UI flag in my app",
  "enabled": true,
  "match": "host:api.myapp.dev path:/v1/config",
  "phase": "response",
  "action": { "type": "rewrite", "json_set": { ".features.new_ui": true } }
}
```

- `match` uses the filter language (03). `phase` is request or response. Rules have a `priority`, and each counts its
  hits (the `rule:r3` filter finds them).
- **Variables**: `{{name}}` works in any rule value. Values come from `set_var` rules, scripts (`ctx.vars`) or the
  user. **Environments** (dev, staging, prod) switch a group of variables at once; ProxyPin has the same idea.
- `net_rules test` shows what a rule would change on stored flows before it's turned on.

## Breakpoints

- A `breakpoint` rule pauses matching flows at the request or the response.
- Paused items go into a queue (`x7`). The AI (`net_intercept`) or the GUI edits them and then resumes, drops, or
  answers with a mock.
- Auto-continue after a timeout (default 30 s for AI, configurable). Apps often give up after 10–30 s, and models are
  slow.
- Editing a body needs the whole body, so a breakpoint on a huge body says "too big to edit".

## JS scripts

Built in Phase 5 (`netcap/internal/script/`) with **goja**. Where the build differs from the plan below, the
"As built" parts say so.

### Engine choice (Phase 0 test)

| Option | Good | Bad |
|---|---|---|
| goja (pure Go, MIT) | fast start, easy Go interop, widely used | ES5.1 plus part of ES6, no async/await; one runtime per goroutine |
| QuickJS built to WebAssembly and run with wazero (pure Go) | modern JS (async/await and newer syntax) | slower calls between Go and JS, more memory |

The choice comes from a test: hook calls per second, memory, script start time. Scripts are written synchronously
either way, so both options work. The engine keeps a small pool of runtimes per script, because a runtime can only be
used by one goroutine at a time. `Interrupt` stops a script that runs too long.

### Script file

```js
// @name     login-debug
// @match    host:api.myapp.dev
// @hooks    request, response, complete
// @body     response          (only these bodies are held whole for the script)
// @timeout  20ms

function onRequest(f, ctx) {
  if (ctx.vars.token) f.req.headers.set("Authorization", "Bearer " + ctx.vars.token)
}

function onResponse(f, ctx) {
  if (f.path === "/v1/login" && f.res.status === 200) {
    ctx.vars.token = f.res.json().token
    f.tag("login-ok")
  }
}

function onComplete(f, ctx) {            // after the flow ends, off the traffic path
  if (f.res.status >= 500) ctx.finding({ severity: "warn", title: "Server error on " + f.path, flow: f.id })
}

command("slow_endpoints", function (args, ctx) {   // AI: net_script call login-debug.slow_endpoints
  return ctx.query("host:api.myapp.dev time>1s", { group_by: "endpoint" })
})
```

The `@match` line means the engine only calls the script for matching flows, and `@body` tells it which bodies it must
hold whole. Everything else streams.

**As built:**

- Folders: `<data>/scripts/*.js` for every session, `<session>/scripts/*.js` for one session (the active one).
  inotify reloads a file ~150 ms after it changes (polling every 2 s where inotify is missing); a file with a syntax
  error stops running and shows `SyntaxError: … at name.js:3:5` in `net_script list`. On/off state is kept in
  `scripts/.state.json`, `ctx.store` in `scripts/.store/<name>.json`.
- More header lines: `@enabled false` (loads switched off), `@priority N` (higher runs first; same priority: by
  name), `@workers N` (1–8, default 4). Without `@hooks` the defined functions are the hooks; `@hooks` limits them.
- `@body` is optional: `f.req.text()` / `f.res.json()` read the body on demand too (up to 8 MB). `@body` only reads it
  before the hook starts. The time spent waiting for a body (or for files, `ctx.query`, `ctx.send`) does not count
  against the hook's time limit.
- Changes are staged: the engine applies them only when the hook returns without an error or timeout, so a failing
  hook really leaves the flow unchanged. Order: rules of the phase, then scripts, then external hooks. Changed
  flows get the flags `script:<name>` and `modified` / `mocked` / `mapped`.
- Workers: each script has up to 4 goja runtimes (created when needed). **Top-level variables are per worker**, so
  shared state belongs in `ctx.vars` or `ctx.store`; `@workers 1` makes one runtime (traffic then waits for it, up to
  the hook's time limit, else the script is skipped for that flow).
- `async` functions work only when they finish at once (goja runs the job queue after each call); a Promise still
  pending is an error. The instructions tell the AI to write plain synchronous code.
- `onConnect` and `onDns` are not built. `onComplete` runs on a queue off the traffic path (2,048 flows; when it is
  full, flows skip onComplete). A script's own `ctx.send` flows don't trigger its onComplete.

### Hooks

| Hook | When | Can change traffic |
|---|---|---|
| `onConnect(c, ctx)` | new connection (host, port, app) | picks passthrough or ignore |
| `onRequest(f, ctx)` | before the request goes upstream | yes |
| `onResponse(f, ctx)` | before the answer goes to the app | yes |
| `onMessage(m, ctx)` | each WebSocket message | yes: change, drop, send an extra one |
| `onComplete(f, ctx)` | flow finished | no (async, for analysis) |
| `onDns(q, ctx)` | DNS answer | no (v1) |
| `every("5m", fn)` | timer | custom triggers |
| `command(name, fn)` | called by the AI or the GUI | custom tools |

### Objects

- `f` (flow): `id`, `app`, `method`, `url`, `host`, `path`, `query`, `kind`. `f.req` and `f.res`:
  `headers.get/set/add/remove/all`, `text()`, `json()`, `bytes()`, `setText()`, `setJson()`, and `status` on `res`.
  Also `f.tag(name)`, `f.note(text)`, `f.respond({status, headers, json | text | file})`, `f.drop()`,
  `f.delay(ms)`, `f.redirect(url)`.
- `m` (message): `dir`, `type`, `text`, `json()`, `setText()`, `drop()`, `sendExtra(text)`.
- `ctx`: `vars` (shared with rules), `store` (kept per script across restarts), `log()`, `finding({…})`,
  `files.write(name, data)` (into the session's `out/` folder), `query(filter, options)` (reads stored flows),
  `send(request)` (one request, same rate limit as `net_send`), `util` (base64, hex, url, sha256, hmac, uuid,
  jwtDecode, gzip, now).

**As built:** `f` also has `scheme`, `port`, `params` (query as an object), `status`, `tags`, `flags`, `time`, `ttfb`,
`start`, `num`; `f.req` / `f.res` have `headers.get/has/set/add/remove/all/names`, `contentType`, `size`, `type`,
`text()`, `json()` (null for an empty body), `bytes()` (ArrayBuffer), `setText/setJson/setBytes`; `f.req.setMethod`,
`setPath`, `setQuery(k, v)`, `removeQuery(k)`; `f.res.setStatus`. In `onRequest` there is no `f.res`. In onComplete,
onMessage and `js:` filters everything is read-only except `f.tag` / `f.note`. `m` has `dir`, `type`, `index`, `size`,
`text` (also settable), `json()`, `bytes()`, `setText/setJson/setBytes`, `drop()`, `sendExtra(x)` (same direction),
`reply(x)` (back to the sender) and `flow` (read-only `f`). `ctx` also has `files.append` and `files.read` (the script's
folder or `out/`), `script` (its name); `console.log/warn/error` write to the script's log. `ctx.query` returns rows
(`{id, start, kind, app, method, url, host, path, status, type, size, ms, tags}`) or with `group_by` (endpoint, host,
app, status, type, kind, method, path, tag) `{key, count, errors, avg_ms, max_ms, statuses}`. `ctx.query` and
`ctx.send` throw in traffic hooks (they would hold up the app); `ctx.finding` keeps at most 120 a minute per script.
`util`: `base64 / base64url / hex / url` each with `encode`, `decode`, `decodeBytes`; `md5 sha1 sha256 sha512 (data,
"hex"|"base64")`, `hmac(alg, key, data, enc)`, `jwtDecode`, `gzip`, `gunzip`, `uuid`, `now`, `text`, `bytes`.

**WebSocket messages:** when an `onMessage` script (or a message hook) matches the upgrade request, the engine takes
`Sec-WebSocket-Extensions` out of the handshake (no permessage-deflate, so messages can be changed) and forwards
message by message: unchanged messages as the same bytes, changed ones as one new frame (masked toward the server).
Dropped messages are recorded with type `dropped`. Without such a script WebSockets are relayed byte for byte as
before.

### Limits

- Each hook has a time limit: 20 ms for request and response, 500 ms for complete, 5 s for a command. When a hook goes
  over, it's stopped for that flow and the flow continues unchanged.
- A script that fails 20 times in a row is turned off, and a finding says why.
- Files: read only from `scripts/`, write only to `out/`. Network: only through `ctx.send`.
- A script reloads when its file changes (or with `net_script reload`). Syntax errors come back with the line number.
- **As built:** `@timeout` changes the traffic-hook limit (at most 2 s); `every()` callbacks have 1 s, the top level 1 s
  at load. A hook that finds all workers busy for its whole limit is skipped for that flow (counted as "skipped",
  not as a failure). `net_script test` runs on stored flows in a sandbox (variables and store changes stay there,
  findings are listed instead of saved, `ctx.send` and `files.write` only say what they would do).

## External hooks (any language)

- For Python or Node scripts running in the terminal, like Burp's extensions in Java or Python.
- Start one with `netcap hook --filter "host:api.myapp.dev" --mode observe|modify -- python3 my_hook.py`, or connect
  to `WS /v1/hooks`.
- JSON lines. Engine → hook: `{"type":"request","flow":{…},"body_file":"…"}`.
  Hook → engine: `{"id":"f12","action":"continue|modify|respond|drop","patch":{…}}`.
- `observe` mode never waits. `modify` waits up to its timeout (default 200 ms), then continues unchanged.

**As built** (`internal/service/exthooks.go`, `internal/hookclient`, example `netcap/cmd/netcap-hook-example`):

- `WS /v1/hooks?mode=observe|modify&phases=request,response,message,complete&filter=…&timeout=200ms&name=…&body=0`
  (token as for the API; one JSON object per text frame). `netcap hook [--mode] [--filter] [--phases] [--timeout]
  [--name] [--no-body] -- cmd args` reads `run.json`, starts the command and relays: engine messages to its stdin,
  one per line; its stdout lines back (lines that are not JSON become log lines).
- Defaults: modify → phases request, response; observe → complete. The timeout is 10 ms–10 s.
- Engine → hook: first `{"type":"hello","hook":"k1","mode":…,"phases":[…],"filter":…,"timeout_ms":…}`, then
  `{"type":"request|response|message|complete","id":"k1.7","flow":"f12","app","method","url","host","path",
  "request":{"headers":[[name,value]…],"body":"…" | "body_base64":"…","body_size":n,"body_omitted":"…"},
  "response":{"status":200,"headers":…,"body":…}}`. Bodies are inline (text, or base64 when binary) up to 1 MB; there
  is no `body_file`. `id` is only set when the engine waits for an answer (modify; never for complete). Messages carry
  `dir`, `msg_type`, `data` | `data_base64`, `index`; complete adds `status`, `error`, `time_ms`, `tags`.
- Hook → engine: `{"id":"k1.7","action":"continue|modify|respond|drop","patch":{…},"tag":"…","note":"…"}`. `patch`
  is net_send's / breakpoint resume's (`set_headers`, `remove_headers`, `query`, `body_text`, `json_set`,
  `json_delete`, `replace`, `method`, `url`, `status`); `respond` takes `"response":{"status","headers","body"|"json"}`;
  a message answer takes `"data"` (text) or `"data_base64"`. Without an `id`: `{"type":"log","text":…}` and
  `{"type":"finding","severity","title","detail","flow"}`. Answers after the timeout are counted as "too late".
