# 08 · GUI: the Network page (as built, 2026-11-28)

A native Compose page of the app, like Chat, IDE, Notes and Files (`AppViewMode.NETWORK`, in the floating switcher, the
page overview and Settings → Appearance → switcher items). The earlier idea of a web UI served by the engine was dropped:
native lists scroll faster, and the page uses the app's theme, `L` texts, overlay and notification.

The page and the agents (`network_mcp`) use the **same engine and the same state**: what an agent changes (capture
apps, rules, scripts, breakpoints, sessions) shows on the page at once through the live stream, and the other way round.

## Files

| Where | What |
|---|---|
| `data/netcap/NetcapApi.kt` | the engine's `/v1/ui/*` JSON API + the live stream (SSE), models (`NetRow`, `NetFlow`, `NetPaused`, …) |
| `data/netcap/NetStore.kt` | one store for the page and the bubble: rows of the current filter (window of 6,000, paging), state, paused list; live only while someone watches (`watch`/`unwatch`) |
| `data/netcap/NetApps.kt` | installed apps (picker) and cached app icons |
| `ui/netcap/NetworkScreen.kt` | the page: its own back stack (`NetDest`, slide transitions), top bar with the apps button ("2 apps"), tab chips, a round Start/Stop floating button (icon only, 100 dp above the bottom, main tabs only), engine download/start card |
| `ui/netcap/NetTraffic.kt` | list: filter box (filter language + help sheet; its pause icon freezes the list, it doesn't pause the capture), chips, by host / by app, sort, follow live, multi-select, long-press actions; content types as icons (`{}` JSON…) |
| `ui/netcap/NetDetail.kt` | one flow as swipeable tabs (overview with a timing waterfall, request, response, messages); the tab, each side's part (Body/Headers…) and body view (Pretty/Text…) are remembered for the next flow (`DetailUi`, in memory); top bar: Edit & send + ⋮ |
| `ui/netcap/NetBody.kt` | body viewer read in pages: pretty / text lines (JSON colors, search, wrap), lazy JSON tree, hex, image; open in the IDE, copy, save |
| `ui/netcap/NetComposer.kt` | edit and send (method, URL, headers, query, body in the Sora editor ≤ 8 MB, original bytes, or a file of any size; repeat); shared editor parts |
| `ui/netcap/NetPaused.kt` | breakpoint queue (countdown, +60 s, continue / drop), "pause the next request", editor (apply, answer it, as it is, drop) |
| `ui/netcap/NetRules.kt` | rules list (on/off, hits), editor for every rule type, test on stored flows, variables |
| `ui/netcap/NetScripts.kt` | scripts (on/off, errors, counts, logs, test, commands, new from a template, edit in the IDE) |
| `ui/netcap/NetTools.kt` | endpoint map, decoder, compare (diff), sessions, findings, DNS log, HAR / folder export |
| `ui/netcap/NetAppPicker.kt` | choose apps (icons, search, system apps, chosen first); while capturing, Apply restarts the capture |
| `ui/netcap/NetFloating.kt` | the capture bubble over other apps (below) |

## Floating bubble and notification

- While a capture runs and AntiGem is not on screen: a circle over other apps with the request count; ring green
  (capturing), amber (paused), red with a badge (breakpoints waiting). Drag, docks to the nearer side, long press hides
  it until the next capture. Needs "Display over other apps"; the page's ⋮ menu turns it on/off (on by default).
- Tapped: a panel with Pause/Resume, Stop, Clear, the latest requests, and the paused ones (Drop / Edit / Continue);
  a row or Edit opens the app at that place.
- The capture notification has Pause/Resume and Stop; tapping it opens the Network page (also on a cold start, through
  `requestViewMode("NETWORK")`).

## Engine API for the GUI (`netcap/internal/httpapi/ui.go`, `internal/service/ui*.go`)

All JSON, token as for the other routes. Errors `{"error": "…"}`.

| Route | Does |
|---|---|
| `GET /v1/ui/live?session=&q=` | SSE: `state` first, then `batch` ≤ every 150 ms: changed rows (running flows too), `removed`, `paused` (when changed), `state` (when changed), `gap` (reload) |
| `GET /v1/ui/state`, `/rows`, `/groups`, `/sessions` (+ POST) | lists (rows: compact, cursor paging; `total` −1 on later pages for non-SQL filters) |
| `GET /v1/ui/flow`, `/body?view=lines|hex|search|tree|file|raw`, `/messages`, `/message`, `/curl`, `/raw` | one flow; bodies read in pages from a decoded, line-indexed cache (64 MB LRU, big pretty forms in temp files); display lines ≤ 2,000 characters (`cont` marks continuations); `view=file` writes to `<session>/out/ui/` (not `out/bodies`, the AI's redacted copies) |
| `POST /v1/ui/mark`, `/delete`, `/send` | pin / color / note; delete or clear; send a full request (from a flow, overrides, body text / base64 / file of any size, repeat ≤ 1,000 × ≤ 16 at once; no AI rate limit) |
| `GET/POST /v1/ui/rules`, `/rules/enable|remove|test`, `/vars` | rules as JSON |
| `GET /v1/ui/paused`, `/paused/body`, `POST /paused/resolve|extend`, `/breaknext` | breakpoints: full header and body replacement (any size), answer, drop, more time, one-shot breakpoint |
| `GET /v1/ui/scripts`, `/scripts/logs`, `POST /scripts/enable|remove|new` | scripts |
| `POST /v1/ui/capture/pause` | pause/resume recording (start/stop stay with the app: it owns the VPN) |

Tests: `netcap/internal/e2e/ui_test.go`.

## Tested on the phone (2026-11-28)

List with an old session and live traffic of the test app (600+ requests), detail (pretty JSON, tree, headers, query,
cookies), app picker, start/pause/stop, notification Pause/Resume, composer send + compare, the bubble and its panel over
the test app, a breakpoint opened from the bubble, edited (Format) and applied: the server got the changed body with the
right Content-Length. Rules (an agent's r1–r8) and scripts listed and editable.

## Not built yet

- Privacy report screen (Phase 6), findings as a proper list (now the MCP text), HAR import, saved filters, a Quick
  Settings tile, a home-screen shortcut (`HomeLinks.Page`), tablet two-pane layout.
- Bodies over 5 MB can't be opened in the IDE (its limit); the composer and breakpoints take them as a file instead.
