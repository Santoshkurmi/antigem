# 04 · `network_mcp` (for AI agents)

## Connecting

- URL `http://127.0.0.1:<app port>/network` with the `X-Device-Id` header, like the other app MCPs. The app's local
  server forwards calls to the engine and starts it on the first call, so agents can always connect.
- A preset is added the same way on all four agent pages: `ClaudeSettingsScreen.kt`, `CodexMcpPage.kt`,
  `OpenCodeMcpPage.kt` and `SettingsDialog.kt` (AGY).
- On a PC (later), the engine serves `/mcp` itself, with a token.

## Rules for keeping the AI's context small

1. Lists are text rows (about 25 tokens per flow), not JSON objects.
2. Every tool has default limits (rows, characters). Its output says what was cut and how to get more: a cursor, or a
   narrower call.
3. Filtering happens in the engine, with the filter language (03).
4. Structure before content: `outline` first, then `jq` or `grep`, the full body last.
5. Summaries instead of lists: the endpoint map, distinct values, diffs.
6. Only what's new: cursors and `after:`.
7. Repeats collapsed: the same endpoint and status in a row becomes one line with `×12`.
8. Noise out by default: image, font and media bodies are never inline; hosts on a default noise list (known analytics
   and CDN hosts) are collapsed unless asked for (`noise:true`).
9. Big or repeated work goes to files or scripts: mirror and export folders for grep and jq; scripts run on every flow
   inside the engine and report only results.
10. Wait instead of polling: `net_wait`.
11. Short, stable ids: `f123` (flow), `f123.m4` (message), `r3` (rule), `s2` (session), `x7` (paused item).
12. Binary data never inline: type, size and a file path.
13. Secrets hidden by default (07).
14. Few, grouped tools, because tool definitions take context too. Groups can be switched off, like REA's tool groups.

## Tool groups

| Group | Tools | Default |
|---|---|---|
| core | `net_status`, `net_capture`, `net_list`, `net_get`, `net_wait`, `net_summarize` | on |
| analyze | `net_diff`, `net_ws`, `net_dns`, `net_export`, `net_privacy_report`, `net_decode` | on |
| modify | `net_send`, `net_rules`, `net_intercept` | on |
| scripts | `net_script`, `net_findings` | on |
| admin | `net_cert`, `net_session` | on |

## Tools

### `net_status`

Engine, VPN and certificate state, active session, captured apps, counts, active rules and scripts, paused items,
warnings.

```
capture: ON · apps: com.myapp · session s4 "login bug" · 312 flows since 12:00
cert: system ✓ (Android 15, applied 11:58) · decrypted: 9 hosts · not decrypted: 1 (cdn.myapp.dev: app does not trust cert)
rules: 2 on · scripts: 1 on (login-debug) · paused: 0 · findings: 4 new
```

### `net_capture`

`action: start|stop|pause|resume`, `apps: [package]`, `options: {decrypt_only, never_decrypt, ignore, mirror (filter
or off), body_cap, new_session (name)}`. Starting may first ask the user in the app (07, "Allow agents to start
capture").

### `net_list`

`filter`, `limit` (default 20), `cursor`, `sort` (newest, oldest, slowest, largest), `fields` (extra columns: app,
tls, tags, `req.header.X`…), `collapse` (default true), `noise` (default false).

```
s4 · 312 flows · 23 match `host:api.myapp.dev` · newest 20 · more: cursor=f281
f312 12:04:10 POST 200 h2 api.myapp.dev /v1/feed?page=2      json 14.2k 182ms
f309 12:04:08 GET  200 h2 api.myapp.dev /v1/users/{n}  ×6    json  1.1k  80ms
f301 12:03:59 POST 401 h2 api.myapp.dev /v1/login            json   312  95ms [auth]
```

### `net_get`

`id`, `parts` (default: summary, short headers, body outline), `view` for each body (03), `jq`, `grep`, `range`,
`max_chars` (default 4000), `show_secrets` (works only when the user allowed it).

```
f301 POST https://api.myapp.dev/v1/login → 401 · h2 · 95 ms · app com.myapp · decrypted
req headers (8): content-type: application/json · authorization: [hidden #a1f3] · user-agent: okhttp/4.12.0 · …
req body json 212 B outline: {email: string, password: [hidden], device: {model: string, os: string}}
res headers (6): content-type: application/json · retry-after: 30 · …
res body json 64 B: {"error":"invalid_credentials","retry_after":30}
```

### `net_wait`

`filter`, `timeout` (seconds, default 20, max 120), `count` (default 1). Returns matching rows as they arrive, or
"none in 20 s". Meant for right after an `android_tap`.

### `net_summarize`

`group_by: endpoint|host|app|status|type|tag`, `filter`, `values` (an expression whose distinct values are listed
with counts, for example `req.header.user-agent`, `res.json:.data[].id`, `query.page`), `limit`.

```
api.myapp.dev · 214 flows
  POST /v1/login          ×3   401×2 200×1   json→json  95–140ms
  GET  /v1/users/{n}      ×58  200           →json      ~80ms    {n}: 17 distinct
  GET  /v1/feed?page=     ×40  200           →json      ~180ms
graph.facebook.com · 22 flows · tracker: Facebook
  POST /{n}/activities    ×22  200           form→json
```

### `net_diff`

`a`, `b` (two flows, or a flow and its resend), `parts` (req, res, both), `ignore` (for example dates and ids).
A short diff of headers, and of JSON by path.

```
f301 vs f305 (res): status 401 → 200 · body: - .error "invalid_credentials" · + .token [hidden #c09e] · + .user.id 42
```

### `net_ws`

Messages of a WebSocket, gRPC or raw flow: `id`, `filter` (`dir:in|out`, text, a jq condition), `limit`, `cursor`,
`view`. Rows look like `f140.m12 → out text 84B 12:03:10 {"type":"subscribe",…}`, cut to a short length.

### `net_send`

Sends one request: `from` (a flow id) plus `patch` (`method`, `url`, `set_headers`, `remove_headers`, `query`,
`body_text`, `json_set`, `json_delete`), or `raw` (HTTP text). Options: `follow_redirects`, `timeout`. The answer comes
back like a `net_get` summary and is stored as a new flow marked `resent_from f301`. A per-host rate limit (default
30 a minute) protects servers and your account.

### `net_rules`

`action: list|add|update|remove|enable|disable|test`, with a rule as JSON (05). `test` runs the rule over stored flows
and shows what it would change, without touching traffic.

### `net_intercept`

The breakpoint queue: `action: list|get|resume|drop`. `resume` takes a `patch` (same as `net_send`) or a mock
answer. The list shows each item's auto-continue countdown.

### `net_script`

`action: list|load|enable|disable|remove|reload|logs|call|test`. `load` takes `code` (the source) or `path` (a file
the AI wrote), plus `name` and `session`; the AI can also write straight into the scripts folder (it reloads by
itself). `call` runs a command the script registered (`name=script.command`, `args`). `test` runs the hooks on stored
flows in a sandbox (variables, store and findings are not kept, nothing is sent) and lists what each hook would
change. `logs` shows the last lines (`lines`, `clear`). The list also shows connected external hooks.

### `net_findings`

`action: list|get|clear`, `severity` (at least: info, warn, error), `script`, `limit`. Findings come from
`ctx.finding`, from external hooks (`{"type":"finding"}`) and from the engine when it turns a failing script off.

### `net_privacy_report`

`app`, `since`. Runs the detectors (07) and returns a short report by host and data type. Writes the full Markdown
report to a file and returns its path.

### `net_decode`

`input`, `as: auto|base64|url|hex|jwt|gzip|protobuf|timestamp|html`, and the matching encode options. JWT: header and
claims decoded (no signature check).

### `net_export`

`filter`, `format: folder|har|curl|http|netcap`, optional `path`. Returns the path and counts.

### `net_cert`

`action: status|install|remove|export`. `install` uses root (DeviceAccess) and says which apps to restart.

### `net_session`

`action: list|new|switch|rename|delete|export|import`.

## Instructions sent with the MCP (draft)

A short text in the MCP `instructions` field, like `JarvisMcp.INSTRUCTIONS`:

- Start with `net_status`. To explore an app: `net_capture start` → `android_launch_app` → use the app
  (`android_tap`, …) → `net_summarize` → `net_get` on the interesting flows, `outline` first.
- Prefer filters and summaries to long lists; use `after:` or the cursor for new traffic.
- Many flows or big bodies: `net_export format=folder` (or the mirror), then read the files.
- Work that repeats for every request: write a script, `net_script load` it, read its results with `net_findings`
  or `net_script call`.
- Don't resend requests in loops: one `net_send` per change.

## Example AI workflows

**Privacy check of an app** (about 6 calls)

1. `net_capture start apps=[com.some.app] new_session="privacy com.some.app"`
2. `android_launch_app`, wait 30 s, a few taps
3. `net_privacy_report app=com.some.app` → short report and the file path
4. `net_get` on 1–2 flows the report names, as proof

**Debug my app's login error**

1. capture, launch, tap Login (`android_*`)
2. `net_wait filter="host:api.myapp.dev path:/v1/login"` → `f301 401`
3. `net_get f301` → the body outline shows a missing field
4. `net_send from=f301 patch={json_set: {".device.os": "android"}}` → 200
5. `net_diff f301 f305` → shows the fix; the agent edits the app's code

**Learn an API**

capture → use the app for 2 minutes → `net_summarize group_by=endpoint` →
`net_export format=folder filter="host:api.x"` → read the files → write API notes.

## Errors

Short, with a hint: `engine starting, try again in 2 s`, `no flow f999 in session s4 (latest is f312)`,
`body too big to change (12 MB > 8 MB buffer cap)`.
