# 08 · Future GUI (ProxyPin-style)

Not in the first phases, but the engine API is designed for it from the start.

## Screens

| Screen | Features |
|---|---|
| Capture bar | start/stop, app picker, session, live counts |
| Flow list | live rows, a filter box (same language as the MCP), quick chips (app, host, status, type), repeats collapsed, sorting, select several to export |
| Flow detail | overview, request, response, headers, body views (pretty, outline, hex, image, protobuf), timings, TLS, WebSocket messages, copy as cURL, resend, edit and send, compare with another flow |
| Endpoint map | hosts → endpoints with counts (same as `net_summarize`) |
| Breakpoints | the queue; edit the request or response; resume, drop, mock |
| Rules | list; an editor that shows live what the rule matches; test on stored flows; variables and environments |
| Scripts | list, logs, on/off; editing opens the `.js` file in the IDE tab |
| Findings and privacy report | per app |
| Decoder | base64, URL, hex, JWT, protobuf, timestamps |
| Certificate and settings | status, install, remove, export; limits; never-decrypt list; AI options |

## How to build it: two options

| | A. Compose screens in the app | B. Web UI served by the engine |
|---|---|---|
| Look | native, theme tokens, `L.*` | the theme and texts are passed in from the host |
| Where it works | Android only | Android (a page with a WebView), VS Code (webview), desktop (browser) |
| Work | one UI per platform | one UI for all |
| Big lists | `LazyColumn` | virtual list |

Recommendation: B, written in Solid (like the VS Code extension's UI) and served by the engine binary. On Android it
opens in a page, with theme tokens and `L` texts passed in by the app. Decide before Phase 7.

## What the API already gives the GUI

Live events over WebSocket, cursor pagination, body views and byte ranges, the same filter language, and the rule,
script and intercept endpoints. The GUI needs no engine changes.
