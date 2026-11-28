# 01 · Architecture

## Processes on Android

| Process | Language | Job |
|---|---|---|
| App (`ai.antigem`) | Kotlin | VPN service, starts and stops the engine, certificate install (root), tells the engine which app owns a connection, local server route `/network` for MCP, Network settings and screen |
| `netcap` engine | Go | everything else: TCP/IP stack, TLS, protocols, rules, scripts, storage, MCP, API |
| Agents (Claude Code, Codex, OpenCode, AGY) | — | call `network_mcp` through the app's local server |

Why a separate binary and not part of `agy_ide_bridge`:

- the bridge is about agents; capture has its own lifecycle (it runs while the VPN is on, even if the bridge restarts)
- a crash in capture never takes the bridge or the app down
- the engine builds for Linux, macOS and Windows on its own (VS Code, desktop) without the bridge's code
- it is built and shipped the same way as the bridge: Gradle Exec task, xz-compressed asset, unpacked to `$PREFIX/bin`
  (as built: release APKs download it on first use from a pinned GitHub release instead, see 06-android.md)

Why not inside the app process through JNI: a crash would kill the app, and an engine update would need an app
restart.

## Layers inside the engine

```
ingress ─► conn (sniff, TLS) ─► protocol (h1, h2, ws, grpc, dns, raw) ─► pipeline (rules, scripts, breakpoints) ─► upstream
                                                    │
                                                    ▼
                                                  store
service layer (every use case lives here) ◄── mcp · httpapi · cli
```

Rule: MCP, API and CLI hold no logic. Each one only translates calls to the service layer, so a feature added to the
service layer is available to the AI, the GUI and the CLI at the same time.

## Go module layout (`netcap/` at the repo root)

```
netcap/
  cmd/netcap/            main: the daemon and the CLI subcommands
  api/netcap.proto       every API message (one schema for JSON and binary)
  internal/
    ingress/tun/         gVisor stack on the TUN handle, TCP/UDP forwarders, QUIC block
    ingress/proxy/       HTTP proxy (CONNECT) + SOCKS5 listener: PC, terminal tools, tests
    control/             local socket to the app: tunnel handle, owner lookups, device facts, events
    dnsx/                DNS forwarder and recorder, IP → name map
    tlsx/                CA, per-host certificates, ClientHello parsing, passthrough list
    proto/http1/ proto/http2/ proto/ws/ proto/grpc/ proto/raw/
    sniff/               detects the protocol from the first bytes
    pipeline/            flow life cycle, hook order, buffer-or-stream decision
    rules/               declarative rules, variables, environments
    script/              JS runner, external hooks
    store/               SQLite, body blobs, mirror files, export and import
    query/               filter language → SQL + in-memory matcher
    view/                body decoding and views (pretty, outline, jq, grep, hex, protobuf)
    analyze/             privacy detectors, endpoint map, diff
    service/             use cases: capture, flows, rules, scripts, send, sessions
    mcp/                 MCP server (streamable HTTP) on top of service
    httpapi/             HTTP JSON + WebSocket events on top of service
```

## Control socket (app ↔ engine)

- An abstract Unix socket with a random name; the name and a token are given to the engine in its environment when
  the app starts it.
- Length-prefixed protobuf messages:
  - app → engine: `Tun` (the tunnel handle, sent with `SCM_RIGHTS`), `StartCapture {apps, options}`, `Stop`,
    `DeviceFacts {…}`, `CaKey {…}` (the decrypted CA key, kept only in engine memory)
  - engine → app: `OwnerLookup {proto, src, dst}` → reply `{uid, package}`, `State {…}`, `Notify {…}` (counts for the
    notification), `NewCaKey {…}` (first start: the app encrypts and stores it)
- If the socket closes, the engine stops capturing (the app died). If the engine dies, the app restarts it.

## Public API (GUI, CLI, VS Code)

- Defined in `api/netcap.proto`. Served as JSON (protojson) over HTTP and as a WebSocket event stream. JSON and binary
  carry exactly the same fields (the project's format-parity rule).
- Endpoints (sketch):
  - `GET /v1/status`, `POST /v1/capture/{start,stop,pause,resume}`
  - `GET /v1/flows?q=&cursor=&limit=&fields=`, `GET /v1/flows/{id}`, `GET /v1/flows/{id}/body/{req|res}?view=`,
    `GET /v1/flows/{id}/messages`
  - `GET /v1/summary?group_by=&q=`, `GET /v1/diff?a=&b=`
  - `POST /v1/send`, `/v1/rules`, `/v1/scripts`, `/v1/intercept`, `/v1/sessions`, `/v1/export`, `/v1/findings`,
    `/v1/report/privacy`, `/v1/cert`
  - `WS /v1/events`: flow added or updated, breakpoint hit, finding, script log, state change
  - `WS /v1/hooks`: external hooks (05)
- Auth: a token header. Listens on 127.0.0.1 only.

## Data folder

```
$HOME/.antigem/netcap/
  config.json             global settings: limits, default ignore list, AI options
  ca/ca.crt               public certificate (export, install)
  ca/ca.key.enc           private key, encrypted with an Android Keystore key (only the app can open it)
  rules/*.json            global rules
  scripts/*.js            global scripts
  data/trackers.json      tracker domain list (updatable)
  sessions/<id>/
    session.json          name, apps, created, options
    index.db              SQLite (WAL)
    blobs/ab/cd…          bodies, content-addressed, compressed
    files/                mirror files for AI (when turned on)
    rules/  scripts/      rules and scripts for this session only
    out/                  script outputs, reports, exports
```

On a PC (VS Code, desktop) the same layout lives under `~/.antigem/netcap/`; there the CA key is protected by the
OS keychain instead of the Android Keystore.

## Reuse beyond Android

| Where | Ingress | MCP | GUI |
|---|---|---|---|
| Android app | TUN (VPN) + proxy port | through the app server at `/network` | Network screen |
| VS Code extension (PC) | proxy port (browser, system or per-app proxy settings) | the engine's own `/mcp`, with a token | webview |
| Desktop on its own | proxy port | `/mcp` | browser or a desktop shell |
