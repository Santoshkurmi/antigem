# Next: merge netcap into the bridge, then Phase 6 — handoff

Updated 2026-11-28. Read this, then README.md (status) and 05-rules-and-scripts.md ("As built" parts). Nothing is
committed (the user commits; never commit, push or stash).

## Where things stand

- Phases 0–5 are built and tested on the phone: engine `netcap/` (Go), Android side `app/src/main/java/ai/antigem/data/netcap/`,
  Settings → Network Interceptor (`ui/settings/NetcapSettingsScreen.kt`), `network_mcp` through the app server `/network`.
- Phase 5 (2026-11-28): `netcap/internal/script/` (goja; hooks, ctx, test sandbox, `js:` filters, inotify reload),
  `internal/service/scripts.go` (glue, ctx.query/send, findings texts), `internal/service/exthooks.go` +
  `internal/hookclient` + `netcap hook` (external hooks over `WS /v1/hooks`), `internal/intercept/ws_modify.go`
  (WebSocket message relay for onMessage), findings table in `internal/store/store.go`, MCP `net_script` /
  `net_findings` (group `scripts`). Also fixed: HTTP/2 requests whose body a hook read were stored as old + new body.
- Engine tests: `cd netcap && GOTOOLCHAIN=local go test ./...` (all pass, also with `-race`; scripts and hooks in
  `internal/e2e/scripts_test.go`).
- Phone test kit: `netcap/cmd/netcap-testserver` (on the PC) + `netcap/testapp` (Android app `ai.antigem.netcaptest`,
  32 tests in `all`, plus `script_http` and `script_ws`, which need the phone-test script below). Last run: 32/32 with
  a script on, script_http + script_ws pass, 4,000 requests at 200/s with a script on every request: 0 failed.
- Size (android/arm64): netcap 29.4 MB raw / 7.4 MB xz (goja added 10.4 / 2.2 MB); bridge 15.1 / 4.4 MB.

## Phase 5 phone test (how it was run)

- Scripts used: `phone-test` (`@match url:script=1`: header + JSON field both ways, a mock answer, WebSocket
  upper-case / drop / reply / welcome JSON, onComplete counter + finding, command `stats`) and `load-all`
  (`@match host:192.168.1.71 path:/get`, a header on every request). Both are still in the phone's
  `~/.antigem/netcap/scripts/` and switched off; the sources were in /tmp/nc-phone (gone after a reboot; rewrite from
  this description or remove them with `net_script action=remove`).
- Load a script: MCP `net_script {"action":"load","code":"…"}`; run `--es tests script_http,script_ws`.
- External hook from the PC against the phone engine: token from `/proc/<pid>/environ` (root), `adb forward tcp:18778
  tcp:8778`, a `run.json` `{"api":"127.0.0.1:18778","token":"…"}` in a folder, then `netcap hook --data <folder>
  --mode modify --filter "host:192.168.1.71 path:/get" --timeout 2s -- netcap-hook-example` (both built for the PC).
  The test server's `/last?path=/get` shows the headers it got.

## Next

1. Nothing to do for the engine release: the next app release (push of version.json) publishes `netcap-v1.0.0`
   itself (`netcap_release.sh ci` in release.yml). When netcap changes later, bump `netcap/release.json` "version"
   with that release; the workflow stops if you forget. (Tested locally with a fake `gh`; the real run is the next
   release.)
2. Phase 6 privacy report (07), Phase 7 GUI (08).

## How to build and test (commands that worked)

- Engine only: `cd netcap && GOTOOLCHAIN=local go vet ./... && GOTOOLCHAIN=local go test -count=1 ./...`
  New Go modules: pick versions that need Go ≤ 1.26.5 (go.mod says 1.26.5; local Go is 1.27; CI uses the bridge's
  go.mod). `GOTOOLCHAIN=local go get <mod>@<version>`.
- App (debug only, never release): `./gradlew :app:assembleDebug` → `adb -s 192.168.1.64:6666 install -r -d
  app/build/outputs/apk/debug/antiGem-debug.apk` → `adb shell am start -n ai.antidev/ai.antigem.MainActivity`.
  Reinstalling stops the capture; start it again through MCP.
- MCP from the PC: `adb forward tcp:18768 tcp:8768`, header `X-Device-Id` = the app's SSAID (memory
  phone-adb-testing says how to read it), POST `http://127.0.0.1:18768/network`. A helper was `/tmp/mcp.py` (gone
  after a reboot; it just posts tools/call JSON-RPC).
- Test server on the PC (192.168.1.71): `cd netcap && go build -o /tmp/netcap-testserver ./cmd/netcap-testserver &&
  /tmp/netcap-testserver &` (ports 18080 http/h2c, 18443 https self-signed, 18081 tcp, 18053 udp).
- Test app: `cd netcap/testapp && ../../gradlew -p . assembleDebug --offline`, install, then
  `adb shell am force-stop ai.antigem.netcaptest; adb shell am start -n ai.antigem.netcaptest/.MainActivity --es
  server 192.168.1.71 --es tests all` (or a comma list; `--ei rps 100 --ei seconds 30` for the load test).
  Results: `adb shell "run-as ai.antigem.netcaptest cat files/results.json"`; logcat tag `NCT`. The phone is often
  locked: always force-stop first (a new intent to a stopped activity waits for a resume).
- Capture the test app: MCP `net_capture {"action":"start","apps":["ai.antigem.netcaptest"]}`.
- Engine profile on the phone: token from `/proc/<pid of netcap>/environ` (root), `adb forward tcp:18778 tcp:8778`,
  `curl "http://127.0.0.1:18778/debug/pprof/profile?seconds=15&token=…"`.

## Phone state the user allowed ("dev mode")

"Agents may start a capture" is on, dev server `192.168.1.71` is set, `appops set ai.antidev ACTIVATE_VPN allow`
was granted, the AntiGem CA is a system certificate until reboot (reinstall: MCP `net_cert {"action":"install"}`).
Rules r1–r8 exist, all off. Don't turn on safety settings' opposites without the user saying so.

## Careful

- Never `pkill -f` / `pgrep -f` with a pattern that is also in your own command line (it kills your shell). Use
  `pgrep -x netcap` or a PID file.
- en.json: insert new texts as lines (the file has its own formatting), keys under `netcap.*`; UI text via `L.*`.
- Scope agreed with the user: no certificate-pinning bypass, no TLS-fingerprint / bot-detection evasion, no
  attack tools. Apps that refuse the CA pass through ("not decrypted").

## Decided 2026-11-28: the engine is downloaded, not merged into the bridge

Merging netcap into `agy_ide_bridge` (Android-only subcommand) was measured: separate 44.4 MB raw / 11.8 MB xz,
merged 32.1 / 8.15 → −3.6 MB APK. The user chose a download instead (most users never use the feature): release APKs
carry no engine and fetch the pinned file on first use. The merge code was removed again; the bridge is unchanged.
The netcap command line moved to `netcap/cli` (kept, cleaner).
