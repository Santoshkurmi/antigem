# 09 · Phases, tests, risks

| Phase | What | Done when | Size |
|---|---|---|---|
| 0 Spike | gVisor builds for android/arm64 with `CGO_ENABLED=0` (size, memory); tunnel handle Kotlin → Go; goja vs QuickJS-on-wazero test; modernc FTS5; HTTP/2 interception with `x/net/http2`; final names | small test binaries work on the phone; numbers written into these docs | 1 session |
| 1 Engine core (on PC) | proxy-port ingress, `tlsx`, HTTP/1.1, HTTP/2, WebSocket, store, filter language, CLI (`netcap list`, `netcap get`) | curl and a browser on the PC through the proxy show h1, h2 and ws flows; tests pass | 2–3 |
| 2 Android capture | VpnService, control socket, gVisor TUN, DNS, QUIC block, owner lookup, root certificate install (Android 7–16), Gradle packaging, small settings page, notification | Chrome and 3 test apps captured and decrypted on the phone; Android 7 emulator and the phone | 2–3 |
| 3 MCP read | `network_mcp` core and analyze groups, mirror files, secret hiding, presets on all 4 agent pages, instructions | an agent runs the privacy-check and learn-an-API workflows on the phone | 1–2 |
| 4 Modify | rules, variables, breakpoints, `net_send`, `net_rules`, `net_intercept` | the debug-login workflow works; rule test works | 2 |
| 5 Scripts | JS runner, hooks, commands, timers, findings, external hooks | the example scripts work; limits are enforced | 2 |
| 6 Privacy report | detectors, device facts, tracker list, report file | the report on 3 real apps matches a manual check | 1 |
| 7 GUI | decide A or B (08), build the screens | the user can do the basics by hand | 3–5 |
| 8 Extras | gRPC names from `.proto`, HTTP/3 capture (quic-go), Socket.IO view, use from PC/VS Code, root transparent mode | as needed | — |

Size = my working sessions, with your phone tests in between (rough).

## Tests

- Go unit tests per package. End-to-end tests on the PC through the proxy port against local test servers: HTTP/1.1,
  HTTP/2, WebSocket, gRPC, big bodies, slow servers, broken TLS.
- Phone test apps: Chrome, an OkHttp app, a Flutter app, a WebView app, a gRPC app, a WebSocket chat app, and our own
  debug app with the user-certificate config.
- Android versions: 7 (emulator), 10, 13, 14+, and the newest (phone).
- Load: 200 parallel requests and a 1 GB download through capture; measure delay, memory, and battery over 30 minutes.

## Risks

| Risk | Plan |
|---|---|
| gVisor binary size and memory | measure in Phase 0; tune buffers; xz in the APK |
| Android 14+ certificate needs re-applying after each reboot, and apps need a restart | re-applied automatically when a capture starts; the app says which apps to restart |
| Apps that don't trust the certificate | automatic passthrough, shown clearly |
| Private DNS, apps with their own DNS | test in Phase 2; DNS recorded where possible; flows still named by IP + SNI |
| Breakpoints make apps time out | auto-continue and a warning |
| Only one VPN at a time | a warning; proxy-port mode for tools that support a proxy |
| Battery | no polling, idle when quiet, optional stop after the screen has been off for a long time |
| Licenses (the app is GPL-3) | gVisor Apache-2.0, goja MIT, modernc BSD-3, gojq MIT, quic-go MIT; each checked again in Phase 0 |
| x86_64 build needs cgo | same NDK setup as the bridge |
