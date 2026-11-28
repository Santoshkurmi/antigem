# 02 · Engine

## 1. Capture through the VPN (TUN ingress)

- gVisor netstack (`gvisor.dev/gvisor/pkg/tcpip`, pure Go, Apache-2.0) reads IP packets from the TUN handle and hands
  the engine normal TCP and UDP connections. Other Go VPN apps on Android use the same stack (for example
  RethinkDNS's firestack and several tun2socks projects).
- TCP: each new connection gets its own goroutine; the engine opens the real connection itself. Engine sockets don't
  loop back into the VPN, because the app's own UID is not in the VPN's app list.
- UDP: DNS (port 53) goes to `dnsx`; UDP 443 (QUIC) is refused so apps fall back to TCP and HTTP/2; other UDP is
  forwarded and recorded as raw.
- IPv4 and IPv6 addresses and routes on the TUN, so apps can't skip capture over IPv6.
- MTU 1500 to start, tuned in Phase 0; packets read in batches.
- When more than one app is captured, each connection asks the app which app owns it (cached per source port).

Size: gVisor makes the binary big (one project reports about 18 MB stripped). It is xz-compressed in the APK like
the bridge. Measured in Phase 0.

## 2. Proxy port (second ingress)

- HTTP proxy (`CONNECT`) and SOCKS5 on `127.0.0.1:<port>`.
- Used for: terminal tools (`HTTP_PROXY=…`; they run as the app's UID, so the VPN can't capture them), PC use, and
  tests (the whole pipeline can be tested on the PC without a phone).
- Optional upstream proxy: forward everything to another proxy (for example Charles or Burp on a PC).

## 3. Protocol detection

The first bytes of a connection decide; the port is only a hint.

| Looks like | Goes to |
|---|---|
| TLS ClientHello | `tlsx` (reads SNI and ALPN) |
| `GET `, `POST `, … | `http1` |
| `PRI * HTTP/2.0` | `http2` (cleartext h2c) |
| anything else | `raw` |

## 4. HTTPS (`tlsx`)

- **CA**: created once per install, ECDSA P-256.
- **Per-host certificates**: made on demand, signed by the CA, cached in memory (LRU). ECDSA makes each one in about
  a millisecond. Valid less than 398 days (clients reject longer ones).
- **Upstream first**: the engine connects to the real server before it finishes the app's handshake. It copies the
  server's names (SANs) into its certificate and picks the same ALPN (h2 or http/1.1) on both sides.
- **Apps that refuse the certificate**: when the app aborts right after receiving our certificate, the host goes on an
  automatic passthrough list for that app. Later connections are relayed untouched (still encrypted) and shown as
  "not decrypted: the app does not trust the AntiGem certificate". The app keeps working.
- **Manual lists**: decrypt only these hosts, or never decrypt these hosts (for example banking).
- **Recorded**: TLS version, cipher, ALPN, SNI, a summary of the server certificate (subject, issuer, expiry), errors
  and which side caused them.
- **Upstream check**: real server certificates are checked against the system roots plus the bundled Mozilla roots
  (as the bridge's `androidcerts` does). A bad server certificate shows as an error flow, which also reveals real
  problems with your own server.

## 5. HTTP/1.1

- keep-alive (each request on a connection is its own flow), chunked bodies, `100-continue`, `HEAD`,
  `Connection: upgrade` → WebSocket.
- Bodies are streamed and copied to the blob writer at the same time.

## 6. HTTP/2

- `golang.org/x/net/http2` on both sides; each stream is its own flow.
- Flow control respected, trailers kept (needed for gRPC), server push turned off, priorities ignored.
- Connection-level events (SETTINGS, GOAWAY) are recorded on the connection, not as flows.

## 7. WebSocket

- After the upgrade (HTTP/1.1 first; HTTP/2 extended CONNECT later), frames are read in both directions. Each message
  is recorded: direction, text or binary, size, time. `permessage-deflate` is decoded for display. Pings and pongs are
  counted, not listed.
- Rules and scripts can change or drop messages and add new ones (for debugging your own app).
- Socket.IO, SignalR and similar run on WebSocket, so they work. Showing Socket.IO event names is an extra (Phase 8).

## 8. gRPC

- `application/grpc*` is detected; the length-prefixed messages are split, and each one is shown as protobuf decoded
  without a schema (field numbers and types). Load a `.proto` file or descriptor set to get real field names.
- grpc-web too.

## 9. DNS

- Queries and answers are recorded: name, type, answer IPs, time, app.
- An IP → name map gives names to connections that have no SNI.
- DNS-over-HTTPS from apps is ordinary HTTPS, so it shows up as flows.
- Android Private DNS (DNS-over-TLS) with an active VPN: test item in Phase 2.

## 10. Raw TCP and UDP

- Unknown protocols are recorded as chunks (direction, time, bytes), capped per connection (default 1 MB), and shown
  as hex plus text. MQTT, XMPP and custom protocols appear here; parsers for them can come later.

## 11. HTTP/3

- v1: QUIC is blocked, so apps use HTTP/2.
- Later (Phase 8): quic-go ends and re-opens QUIC, so HTTP/3 shows up as h3 flows.

## 12. Pipeline (each flow)

```
request in  → ignore? → rules (request)  → scripts onRequest  → breakpoint? → upstream
response in →           rules (response) → scripts onResponse → breakpoint? → to the app
done        → store update → scripts onComplete (async) → analyzers (async) → events
```

- **Buffer or stream**: a body is held whole only when a matching rule, a script (`@body`) or a breakpoint needs it.
  Otherwise it streams to the app and is copied to the blob writer at the same time. Buffer cap (default 8 MB): above
  it the body streams and is marked "too big to change".
- **Stored body cap** per flow (default 20 MB; past that, bytes are only counted), configurable.
- **Logging never slows traffic**: store writes go through a queue. When the queue is full, bodies are left out of the
  log (the flow is marked) instead of blocking the app.
- **Errors are part of the flow**: DNS failure, connection refused, timeout, reset, TLS error (and which side).
- **Timings**: DNS, connect, TLS, request sent, first byte, done.

## 13. Speed and resource targets

| Item | Target |
|---|---|
| Added delay per request (certificate cached) | about 1–2 ms (p50) on the phone |
| First TLS connection to a new host | about +5 ms for making the certificate |
| Memory | `GOMEMLIMIT` about 150 MB; bodies never whole in RAM unless needed |
| Idle CPU | about 0: no polling, timers only for scripts that ask for them |
| Store | one writer goroutine, batched transactions |

Measured in Phases 1 and 2 with a load test (many parallel requests through the proxy port, a 1 GB download).

## 14. Engine safety

- Listens only on 127.0.0.1 and an abstract socket; every API and MCP call needs the token.
- The CA private key lives only in engine memory (handed over by the app) and is never sent over the API or MCP.
- No traffic content in logcat; the engine's own log hides header values.
