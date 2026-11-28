# 03 · Storage, files for AI, filter language

## Sessions

A session is one capture project (for example "myapp login bug") with its own database, body files, rules and
scripts. New traffic goes to the active session; older sessions stay readable. Setting: a new session for every
capture start, or one per day.

## Database

SQLite in WAL mode, through `modernc.org/sqlite` (pure Go, already used by the bridge).

| Table | Main columns |
|---|---|
| `flows` | id (shown as `f123`), kind (http, ws, grpc, dns, tcp, udp), app, uid, started_at, timings (dns, connect, tls, ttfb, total), scheme, host, port, method, path, query, http_version, status, req_type and res_type (short: json, html, image…), req_size and res_size (on the wire and decoded), req_blob and res_blob (hashes), tls_state (decrypted, passthrough, untrusted, plain), error, tags, rule_hits, note, endpoint_key, flags (truncated, modified, mocked, resent_from) |
| `headers` | flow_id, side, index, name, value (its own table so header filters are fast) |
| `messages` | WebSocket, gRPC and raw chunks: flow_id, index, direction, time, type, size, blob or short inline text |
| `dns` | name, type, answers, app, time |
| `findings` | from analyzers and scripts: id, source, severity, title, detail, flow ids, time |
| `vars`, `kv` | variables (05) and the scripts' own storage |
| `intercepts` | paused items (breakpoints) |

Full-text index (FTS5) over the URL plus text bodies (the first 256 KB of each) for `body:` searches. Phase 0 checks
that modernc's build includes FTS5.

## Body files (blobs)

- Content-addressed (sha256) and compressed (zstd). A body is stored once even when 100 flows have the same one.
- Kept as received, with transfer encoding removed. The decoded version (gzip, br or zstd removed, charset turned into
  UTF-8) is made on demand and cached.

## Limits and cleanup

- Per-session size cap (default 500 MB on the phone) and a per-body cap. Oldest bodies are deleted first; rows stay.
- "Clear all", "delete session", and an optional "delete when capture stops".

## Files for AI (mirror)

Turned on per session (or one-off with `net_export`), with a filter so only the interesting flows are written:

```
sessions/<id>/files/
  index.jsonl                               one line per flow (same fields as a net_list row)
  f000123_POST_api.myapp.dev_v1-login/
    request.http        request line, headers, decoded body as text (secrets hidden unless allowed)
    response.http
    meta.json           id, app, timings, TLS, sizes, tags, rule hits
    response.body.json  pretty copy of big JSON, so grep and jq work on it
    request.body.bin    only for binary bodies, with the right extension (.png, .pb, .bin)
  f000140_ws_chat.myapp.dev/
    messages.jsonl      one line per WebSocket message
```

- Written by an async writer after each flow finishes; folder names sort in time order.
- The AI then uses its own tools (Read, grep, jq, python) instead of pulling bodies through MCP.
- The same writer does exports.

## Export and import

| Format | Use |
|---|---|
| HAR 1.2 | open in browsers and other tools; WebSocket messages in Chrome's `_webSocketMessages` field |
| folder (above) | AI and scripts |
| cURL | one flow as a command |
| `.http` | one flow as raw text, which `net_send` can also send |
| `.netcap` (zip: database + blobs + rules + scripts) | move a session between the phone and a PC, or share it |

## Filter language

One small language used everywhere: MCP filters, a rule's `match`, a script's `@match` and the GUI search box.

Grammar: terms separated by spaces mean AND; `OR`; `-term` means NOT; parentheses group; a bare word means "the URL
contains it".

| Field | Example | Meaning |
|---|---|---|
| `app` | `app:com.myapp*` | package name |
| `host` | `host:*.myapp.dev` | glob |
| `method` | `method:POST` | |
| `path` | `path:/v1/users/*` | glob |
| `url` | `url:/token=\w+/` | substring or /regex/ |
| `status` | `status:4xx`, `status:200-299` | |
| `type` | `type:json` | json, html, js, css, image, media, font, form, proto, text, binary |
| `kind` | `kind:ws` | http, ws, grpc, dns, tcp, udp |
| `http` | `http:2` | 1, 2, 3 |
| `size` | `size>100k`, `res.size<1k` | |
| `time` | `time>500ms` | total duration |
| headers | `req.header.authorization:exists`, `res.header.cache-control:*no-store*` | |
| body | `body:"email"`, `res.body:/"id":\d+/` | text search (uses FTS when it can) |
| `json` | `res.json:.user.role=="admin"` | a jq expression that must be true |
| `tag`, `rule`, `note` | `tag:login`, `rule:r3` | |
| `tls` | `tls:passthrough` | decrypted, passthrough, untrusted, plain |
| `error` | `error:any`, `error:timeout` | |
| `since`, `after` | `since:10m`, `after:f1200` | time window, or newer than an id |
| `noise` | `noise:true` | include hosts from the noise list |
| `js` | `js:"f.res.json().items.length > 50"` | escape hatch, slower |

Examples:

- `app:com.myapp host:api.myapp.dev status:4xx since:5m`
- `type:json -host:*.googleapis.com res.body:"email"`
- `(status:5xx OR error:any) kind:http`

How it runs: parsed once into a tree. Fields on indexed columns become SQL. `body`, `json` and `js` terms run on the
rows SQL returns, with a scan limit; the answer then says "scanned N of M".

## Endpoint templates (for the API map)

Path parts that look like ids become placeholders: numbers `{n}`, UUIDs `{uuid}`, long hex or base64 `{hash}`, dates
`{date}`. Query keys are kept and their values dropped, so `GET /v1/users/{n}/posts?page=` is one endpoint. The key is
stored in `flows.endpoint_key`, so grouping is a plain SQL `GROUP BY`.

## Body views

| View | What it gives |
|---|---|
| `raw` | bytes as text (UTF-8), with an optional byte range |
| `pretty` | formatted JSON, XML, HTML, JS, form data |
| `outline` | structure only: keys, types, array lengths, short sample values (JSON, XML, form, protobuf) |
| `jq` | result of a jq expression (gojq, pure Go), for example `.data[] \| {id, name}` |
| `grep` | matching lines with N lines of context |
| `range` | bytes from–to |
| `hex` | hex dump, with a range |
| `form`, `multipart` | fields, and file parts saved to files (paths returned) |
| `protobuf` | decoded fields, without a schema or with loaded descriptors |
| `file` | writes the decoded body to a file and returns the path (images, media, big bodies) |
