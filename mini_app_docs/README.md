# AntiGem Mini Apps

A mini app is a plain folder of HTML, CSS and JavaScript that AntiGem opens full screen like a real Android app. It gets its own Recents card and home-screen icon, native screens and dialogs, native storage, files, network, notifications, reminders and background tasks, all through a JavaScript SDK (`window.AntiGem`) and gated by permissions the user grants. Apps are built on the phone, usually by an AI agent, with no build step; finished apps are shared as signed `.agapp` packages that install and update like APKs. These pages are the complete guide for people and agents who build mini apps.

## How it works

- **The WebView only renders.** Each app is served from disk at its own origin, `https://<slug>.antigem.invalid/` (the slug comes from the app's id), with the app folder as the root. A strict Content Security Policy lets the page connect only to its own origin. Everything else goes through native code.
- **Everything else is native.** `localStorage`, `sessionStorage`, `AntiGem.storage` and IndexedDB are kept in SQLite files outside the WebView. `fetch`, `XMLHttpRequest`, `WebSocket` and `EventSource` go through the phone's network stack (no CORS limits, a permanent cookie jar per app). Files, dialogs, sheets, pickers, the media viewer, notifications, reminders, background tasks, threads, intents and AntiGem's MCP tools are native modules. The SDK is injected into every page and worker before the app's own scripts run.
- **Permissions like a small Android.** An app can only use what its `manifest.json` declares, and the user allows or denies each permission (each folder separately). Undeclared or denied calls fail with `NotAllowedError`. Some places are never reachable whatever is granted ([permissions.md](permissions.md)).
- **Each app is its own Android task.** Its own Recents card with its name, icon and color; a splash screen; every page opened with `AntiGem.nav.push` is a native screen with system back and slide animations.
- **Development apps and installed apps.** A development app is a folder in `~/mini-apps/`: unsigned, editable, hot-reloaded, with a developer bubble and a `DEV` label; it only runs with Developer mode on. An installed app comes from a signed `.agapp` package: its code is read-only in AntiGem's private storage and every file is checked against the signature when it loads.
- **Signing.** Packages are signed with the developer's EC P-256 key. The first install records the key; updates must use the same key; an older version needs the user's OK. The key lives encrypted on the phone (Android Keystore), is backed up as a password-protected `.agkey` file, and works with the PC tool `agapp.mjs` too ([signing-and-distribution.md](signing-and-distribution.md)).

## Where things live on the phone

`~` is AntiGem's home folder (`/data/data/com.termux/files/home` in the `com.termux` build).

| Path | What |
|---|---|
| `~/mini-apps/<folder>/` | A development app: `manifest.json`, `index.html`, scripts, styles, icon |
| `~/mini-apps/README.md` | A one-file summary guide for agents, refreshed by every AntiGem update |
| `~/mini-apps/tools/agapp.mjs` | The Node tool that signs and verifies packages (for a PC), updated with every AntiGem version |
| `~/.antigem/mini-apps/<id>/` | Every app's data (development and installed): |
| `  kv.db` | `localStorage` and `AntiGem.storage` |
| `  idb.db` | IndexedDB |
| `  cookies.db` | The app's cookie jar |
| `  files/` | The app's private `data/` folder |
| `  console.log` | The app's console (`console.log.1`: the previous part) |
| `  net-cache/`, `thumbs/` | HTTP cache, thumbnail cache |
| AntiGem's private storage | Installed apps' code (`files/miniapps/installed/<id>/`), their original packages, permission decisions, reminders and background tasks |
| `~/mini-apps-dist/` | Signed packages exported from the phone (`<id>-<version>.agapp`) |
| `Download/AntiGem/` | Developer key backups (`antigem-key-*.agkey`), in public storage so they survive uninstalling AntiGem |

The data folder is outside the app folder on purpose: editing or replacing an app never touches its data.

## Start here (for AI agents)

1. Read [getting-started.md](getting-started.md), then [manifest.md](manifest.md).
2. Read the API pages the app needs: [api-reference.md](api-reference.md) first, then [storage.md](storage.md), [files.md](files.md), [network.md](network.md), [ui.md](ui.md), [notifications.md](notifications.md), [background.md](background.md), [intents.md](intents.md), [threads.md](threads.md) or [mcp.md](mcp.md) as needed.
3. Read [permissions.md](permissions.md) and declare the fewest permissions, with the narrowest hosts and folders.
4. If you have the **Mini App Developer MCP** (tools named `miniapp_…`), use it for the whole loop: create, write files, open, read the console, eval, screenshot, validate, sign, install ([developer-mcp.md](developer-mcp.md)). Its `miniapp_guide` tool serves these same pages, matching the AntiGem version on the phone. Without it, write the files into `~/mini-apps/<folder>/` and read `~/.antigem/mini-apps/<id>/console.log`.
5. Fix every console error, check the rules in [getting-started.md](getting-started.md#rules-for-good-apps), and use [troubleshooting.md](troubleshooting.md) when something doesn't work.
6. To ship the app, read [signing-and-distribution.md](signing-and-distribution.md). Never ask the user for the developer key's password: it is typed only on the phone.

## All pages

**Guides**

| Page | What it covers |
|---|---|
| [README.md](README.md) | This overview: architecture, where things live, where to start |
| [getting-started.md](getting-started.md) | Build a first app step by step, open it, hot reload, console, data reset, rules for good apps, the MCP dev loop |
| [manifest.md](manifest.md) | Every `manifest.json` field: types, defaults, validation, examples |
| [permissions.md](permissions.md) | Every permission, prompts and states, re-prompts, required permissions, the typed confirmation, App info, what is never reachable |
| [shared-data.md](shared-data.md) | `shared_id`: sharing `data/` folders between one developer's apps |
| [signing-and-distribution.md](signing-and-distribution.md) | The developer key and its backup, the `.agapp` format, export, install and update rules, sharing, hosting, Developer mode, uninstall |
| [pc-development.md](pc-development.md) | `agapp.mjs` on a PC, one key on the phone and the PC, building on the phone from a PC agent, getting files onto the phone |
| [troubleshooting.md](troubleshooting.md) | Common problems and fixes, install errors, where the logs are |
| [developer-mcp.md](developer-mcp.md) | The Mini App Developer MCP: adding it, the development loop, every tool |

**API**

| Page | What it covers |
|---|---|
| [api-reference.md](api-reference.md) | The whole `AntiGem` SDK on one page, events, error names |
| [storage.md](storage.md) | `localStorage`, `sessionStorage`, `AntiGem.storage`, IndexedDB, cookies, quotas |
| [files.md](files.md) | `AntiGem.fs`: paths, granted folders, reading and writing, file URLs, pickers |
| [network.md](network.md) | `fetch`, XHR, WebSocket, EventSource, web resources, cookies and credentials |
| [ui.md](ui.md) | The UI kit, theme and dark mode, native dialogs, media viewer, pages and navigation, back, device helpers |
| [notifications.md](notifications.md) | `AntiGem.notify`: notifications, channels, events, reminders |
| [background.md](background.md) | Background tasks: setup, schedules, handlers, limits |
| [intents.md](intents.md) | Sharing with other apps, receiving intents, links into apps, lifecycle events, device status |
| [threads.md](threads.md) | `AntiGem.thread` and workers |
| [mcp.md](mcp.md) | Calling AntiGem's MCP tools from an app |
