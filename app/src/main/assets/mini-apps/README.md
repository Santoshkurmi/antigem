# AntiGem Mini Apps: guide for agents

A mini app is a plain folder of HTML/CSS/JS that AntiGem opens full screen like a real Android app. It gets its own
Recents card and home-screen icon, and native pages, dialogs, storage, files, network and notifications. The WebView
only renders. Everything else is native and gated by permissions the user grants.

> **This is the short guide.** The full documentation (every API, option and limit) is in `~/mini-apps/docs/`
> (start with `docs/README.md`). With the **Mini App Developer MCP** (an agent's MCP settings → Quick Presets), call
> `miniapp_guide` for the same pages and use its tools to create, run, inspect (console, eval, screenshot), sign and
> install apps.

## Folder layout

```
~/mini-apps/
  README.md            ← this file
  my-app/              ← any folder name
    manifest.json      ← required
    index.html         ← the entry page
    app.js, style.css, icon.png, other pages, assets…
```

- No build step. Write plain HTML, CSS and JS. ES modules (`<script type="module">`) and relative imports work.
- Each app is served at its own origin, `https://<slug>.antigem.invalid/`, where the app folder is the root
  (`/style.css` is `my-app/style.css`).
- The runtime injects `/_antigem/sdk.js` at the top of `<head>` of every HTML page, so `window.AntiGem` exists before
  your scripts run. Don't include it yourself.
- Paths that aren't files and have no extension fall back to the entry page, so SPA routing works.

## manifest.json

```json
{
  "manifest_version": 1,
  "id": "com.example.todo",
  "name": "Todo",
  "version": "1.0.0",
  "description": "Short description shown in the Mini Apps list",
  "entry": "index.html",
  "icon": "icon.png",
  "theme_color": "#2E7D32",
  "permissions": { "notifications": true }
}
```

| Field | Required | Meaning |
|---|---|---|
| `manifest_version` | yes | Always `1` |
| `id` | yes | Unique reverse-domain id. Data is stored under this id, so don't change it later |
| `name` | yes | Shown on the card, in Recents and on the home-screen shortcut |
| `version` | yes | Semver string |
| `description` | no | One line |
| `entry` | no | First page, default `index.html` |
| `icon` | no | Square PNG, 192×192 or larger. Without one, a letter icon is generated |
| `theme_color` | no | Splash, Recents card and default status bar color |
| `permissions` | no | Object (below) or a list of names, e.g. `["notifications", "clipboard"]` |
| `background` | no | Script for background tasks, e.g. `"background.js"` (or `{"script": "bg.js", "type": "module"}`). See "Background tasks" |
| `intents` | no | What other apps may send it: `[{"action": "share", "types": ["text/plain", "image/*"]}]`. See "Intents" |
| `version_code` | no | Integer for update ordering (default from `version`: a.b.c → a*1000000+b*1000+c) |
| `required_permissions` | no | Permissions the app can't work without, e.g. `["notifications"]`. If the user denies one, the app doesn't open (a native screen says why). `"storage"` here means every declared folder |
| `shared_id` | no | Like Android's sharedUserId: apps with the same one (e.g. `"com.you.suite"`) use each other's `data/` folder. See "Shared data" |

### Permissions

An app can only use what it declares, and the user grants each declared permission (like Android). Undeclared or
denied capabilities reject with a `DOMException` named `NotAllowedError`. The app's private `data/` folder,
`localStorage`, `AntiGem.storage`, IndexedDB and the native UI need no permission.

| Name | Declaration | Gives |
|---|---|---|
| `network` | `true` (any host) or `{ "hosts": ["api.example.com", "*.example.com"] }` | Internet (http/https, ws/wss), for those hosts only (redirects too). An empty `hosts` list allows none |
| `localhost` | `true` (any port) or `{ "ports": [8080, 3000] }` | Servers on the phone itself (`127.0.0.1`, `localhost`, and the phone's own Wi-Fi address). An empty `ports` list allows none |
| `storage` | `{ "folders": [{ "path": "/sdcard/Download", "access": "read" }], "pick": "read" }` | Files outside the app's own folder. See "Storage" below |
| `notifications` | `true` | Show notifications and schedule reminders |
| `background` | `true` | Run background tasks while the app is closed (needs the manifest's `background` script) |
| `clipboard` | `true` | Read the clipboard (writing is always allowed) |
| `camera` | `true` | Camera through getUserMedia |
| `microphone` | `true` | Microphone |
| `mcp` | `["notes_mcp", "theme_mcp"]` | Call AntiGem MCP tool servers. Each one is the permission `mcp:<server>` |

### Storage

```json
"storage": {
  "folders": [
    { "path": "/sdcard/Download", "access": "read" },
    { "path": "/sdcard/Documents/MyApp", "access": "readwrite" },
    { "path": "~/exports", "access": "write" }
  ],
  "pick": "read"
}
```

- Each folder is its own permission (`storage:<path>`): the user can allow some folders and not others. The access
  comes from the manifest only, the user just allows or denies it: `read` (read, list), `write` (create, change,
  delete; no reading), `readwrite`.
- `pick`: the app may call `AntiGem.fs.pickFolder()`; the user then chooses any folder in AntiGem's file explorer and
  the app gets that access to it.
- In the list form (`"permissions": ["storage"]`) storage is ignored: name the folders.
- `"storage": true` (or `{"full": true, "access": "read"}`) asks for **all files**. That, a folder like `~/`, `/sdcard`,
  or picking one of those, makes the user type a confirmation phrase under a warning. Ask for the narrowest folder.
- Paths are resolved through symlinks before any check: a symlink inside an allowed folder that points outside it is
  refused, shows up in listings as `{type: "link", blocked: true}`, and deleting a folder removes links, not what they
  point to.

### Shared data (`shared_id`)

Apps of one developer can share files, like Android apps with the same sharedUserId: give each the same
`"shared_id": "com.you.suite"`. Each still has its own `data/`; the others' are `app:<id>/…`, read and write, no
permission prompt:

```js
await AntiGem.fs.write('app:com.you.notes/inbox/todo.txt', 'from Tasks');
const list = await AntiGem.fs.list('app:com.you.notes/inbox');
const groups = (await AntiGem.fs.folders()).filter(f => f.source === 'shared'); // [{path: 'app:com.you.notes', app: {id, name}}]
```

- Installed apps share only with installed apps signed with the **same key**: a package whose `shared_id` group already
  has an app signed by another key is not installed. Development apps share only with other development apps.
- Only the `data/` folder is shared (`AntiGem.storage`, IndexedDB and cookies stay per app). Uninstalling an app deletes
  its `data/` for the whole group.
- The install screen and App info show which apps share data.

Never reachable, whatever is granted: other mini apps' code (`~/mini-apps/`) and data (`~/.antigem/`; only its
`shared_id` group's `data/` folders), hidden folders in home (`~/.ssh`, `~/.codex`, …), anything outside home and `/sdcard`, and AntiGem's own local ports.
Widening a permission in the manifest (more hosts, ports or access) asks the user again; narrowing or reordering
keeps the user's choice. Ignored or suspicious manifest entries are printed as warnings in the app's console.

Ask for the least you need. Most apps need no network at all. States are `granted`, `denied` and `prompt`
(the user hasn't decided yet: the first use or `AntiGem.permissions.request()` shows a native prompt).

## The AntiGem API

```js
AntiGem.app      // {id, name, version, origin}
AntiGem.page     // {id, path, params, root, background}: params = the query string as an object; root = the first page; background = a background task
AntiGem.ready    // Promise. Calls made earlier are queued, so awaiting is optional
AntiGem.theme    // {dark, colors: {primary: '#rrggbb', ...}}, also live CSS variables --ag-*
AntiGem.on(event, fn) / AntiGem.off(event, fn)
//   'theme' (theme changed) · 'resume' / 'pause' (page shown/hidden) · 'storage' ({key}: another page changed a key)
//   'permission' ({name, state})

// Key-value storage: synchronous, JSON values, native, shared by every page of the app
AntiGem.storage.get(key, fallback?) · set(key, value) · remove(key) · keys() · clear()

// Files: "data/..." = the app's private folder (always allowed); "~/..." or "/sdcard/..." need a granted storage folder
await AntiGem.fs.read(path, {encoding: 'utf8' | 'base64'}?)   // string
await AntiGem.fs.readJSON(path)
await AntiGem.fs.write(path, stringOrArrayBufferOrUint8ArrayOrBlob, {append}?)
await AntiGem.fs.writeJSON(path, value)
await AntiGem.fs.list(path)      // [{name, path, type: 'file' | 'dir', size, mtime}]
await AntiGem.fs.stat(path)      // {name, path, type, size, mtime} or null
await AntiGem.fs.exists(path)
await AntiGem.fs.mkdir(path) · delete(path, {recursive}?) · move(from, to)
AntiGem.fs.url(path)             // URL for <img>, <video> or fetch, streamed natively
await AntiGem.fs.pick({accept: 'image/*', multiple}?)   // native picker → [{name, path, size, type}], copied into data/imports/
AntiGem.fs.url(path, {thumb: 320}) // a native JPEG thumbnail (photos, HEIC, video frames), cached: use it for grids
await AntiGem.fs.pickFolder()        // AntiGem's file explorer → {path, access} | null (needs "pick" in the manifest)
await AntiGem.fs.folders()           // [{path, access, source: "own" | "shared" | "manifest" | "picked"}]
await AntiGem.fs.forgetFolder(path?) // give back a picked folder

// Permissions
AntiGem.permissions.all()            // {name: state} for every declared permission
AntiGem.permissions.query(name)      // 'granted' | 'denied' | 'prompt' | 'undeclared'
await AntiGem.permissions.request(name)   // shows the native prompt → 'granted' | 'denied' | 'undeclared'

// Native UI
AntiGem.ui.toast(text, {long}?)
await AntiGem.ui.alert(msg | {title, message, ok})
await AntiGem.ui.confirm(msg | {title, message, ok, cancel, destructive})   // boolean
await AntiGem.ui.prompt(msg | {title, message, value, placeholder, ok, cancel})   // string or null
await AntiGem.ui.sheet({title, items: [{id, label, icon?, destructive?}]})   // chosen id or null; icon = emoji
await AntiGem.ui.pickDate({value?: 'YYYY-MM-DD', title?})   // string or null
await AntiGem.ui.pickTime({value?: 'HH:MM'})                // string or null
AntiGem.ui.viewMedia({items, index})  // native full-screen photo/video viewer: swipe, pinch/double-tap zoom, system video
                                     // player, share. items: paths, https URLs or {src, type: 'image'|'video', title} → {index}
AntiGem.ui.statusBar({color})        // any CSS color; icon contrast is automatic

// Device
AntiGem.device.haptic(kind)          // 'light' | 'medium' | 'heavy' | 'selection' | 'success' | 'warning' | 'error'
await AntiGem.device.share({title, text, url})
await AntiGem.device.copy(text)
await AntiGem.device.paste()         // needs `clipboard`
AntiGem.device.openUrl(url)          // opens in the system browser
await AntiGem.device.status()        // {network: {online, type, metered}, battery: {level, charging, saver}}
AntiGem.on('network', n => {}) · AntiGem.on('battery', b => {})   // while the app is on screen, when they change
await AntiGem.device.info()          // {manufacturer, model, android, sdk, dark, locale}

// Notifications (need `notifications`): see "Notifications" below
await AntiGem.notify.show({id, title, body, ...})   // → {id}; same id updates in place
await AntiGem.notify.channel({id, name, importance, sound, vibrate, lights, badge})
await AntiGem.notify.active() · dismiss(id?) · deleteChannel(id)
AntiGem.on('notification', e => {})  // {id, action: "tap" | "dismiss" | <button id>, reply?, queued?}
// Reminders: kept natively, they fire with the app closed and survive restarts and reboots. Same id = replaced.
await AntiGem.notify.schedule({id?, title, body, at?, delay?, repeat?, exact?, ...any notify.show option})   // → {id, at, repeat}
//   at:     Date | epoch ms | "HH:MM" (next time of day) | "2026-12-24T18:00" (local)
//   delay:  ms or "10m" / "2h" / "1d"
//   repeat: "daily" | "weekly" | "weekdays" | "hourly" | "2h" | {every: "30m"} | {days: ["mon", "thu"], time?: "08:00"}
//           plus optional until (Date/ms) and count (fire N times)
//   exact:  default true (on time even in Doze); false lets Android batch it
await AntiGem.notify.list()          // scheduled reminders: [{id, title, body, repeat, at}]
await AntiGem.notify.cancel(id) · AntiGem.notify.cancelAll()   // cancel reminders (and the shown notification)

// Cookies: each app has a permanent cookie jar, sent automatically (fetch credentials: 'omit' turns it off)
await AntiGem.cookies.list(url?)     // [{name, value, domain, path, expires, session, secure, httpOnly}]
await AntiGem.cookies.get(url, name)
await AntiGem.cookies.set({url, name, value, path?, domain?, expires?: Date|ms, maxAge?: s, secure?, httpOnly?})
await AntiGem.cookies.import(url, "a=1; b=2", {days?})   // a "Cookie:" header the user pasted (e.g. a login)
await AntiGem.cookies.delete({url?, name?, path?}) · AntiGem.cookies.clear(url?)

// Background tasks (need `background` + the manifest's background script). See "Background tasks".
await AntiGem.background.register(name, {every: "1h"} | {delay: "10m"} | {at: "07:30", repeat: "daily"}, ...)
await AntiGem.background.list() · cancel(name?) · run(name)
AntiGem.background.on(name, async (task) => {...})   // in the background script

// Navigation: every page is a native screen (slide in, swipe or back to return)
AntiGem.nav.push(path, params?)      // path relative to the app root, e.g. nav.push('item.html', {id: 7})
AntiGem.nav.back()                   // close this page
AntiGem.nav.close()                  // close the whole app
AntiGem.onBack(fn)                   // fn() returns true if it handled back (close your own panel, go to the first tab)

// AntiGem MCP tools (need `mcp:<server>`)
await AntiGem.mcp.list(server)       // tools[]
await AntiGem.mcp.call(server, tool, args)

// Dev
AntiGem.dev.console()                // native console panel: every log and error, plus a JS input
AntiGem.dev.devtools()               // Eruda devtools: elements, network, storage
AntiGem.reload()

// Threads (see "Threads" below)
AntiGem.thread.run(fn, args?) · map(items, fn) · pool(size) · cores
```

## Standard web APIs, routed natively

Write ordinary web code. These are handled natively behind the scenes:

- **`fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource`, `navigator.sendBeacon`** go through the phone's network
  stack. There are no CORS limits, and any header can be set (`Origin`, `Cookie`, `User-Agent`). They need the
  `network` permission (and the host listed), or `localhost` for 127.0.0.1.
- **`<img>`, CSS, fonts and scripts from https** are fetched natively and cached, and need `network` with that host.
  Relative URLs (your own files) always work.
- **`localStorage` / `sessionStorage`** are native. `localStorage` is shared by all pages of the app and survives
  clearing the WebView's data.
- **`indexedDB`** is native SQLite (the standard API, plus libraries like `idb`). Booleans are not valid IndexedDB
  keys, so index `0`/`1` instead.
- **`alert()` / `confirm()` / `prompt()`** show native dialogs. In new code prefer `AntiGem.ui.*` (async, with titles).
- **`<input type="file">`** opens the native picker.
- **`console.*`**, uncaught errors and unhandled rejections go to the native console panel and the log file.
- **`new Worker(...)`** (classic, `{type: 'module'}` or a `blob:` URL) works too: the SDK is put into every worker, so
  `fetch`, XHR, `WebSocket`, `EventSource`, `indexedDB` (the same databases as the page) and `AntiGem.fs` / `mcp` /
  `notify` / `thread` work inside it, with the same permissions. `importScripts` and module `import` of your own files
  work. Not in workers: `localStorage` (no browser has it there) and the UI APIs. `SharedArrayBuffer` is not available
  (Android WebView doesn't support cross-origin isolation), so threads share data by messages, not memory.

## Threads: `AntiGem.thread`

Heavy work (parsing, image processing, search, math) goes on other CPU cores, so the UI never stutters. Each thread is
a real OS thread with its own V8 engine; a pool sized to the phone (cores − 1) is started on first use and reused.

```js
// Runs on another core. The function is copied to the thread as source code:
// it can't use variables from outside it, so pass everything in as arguments.
const sum = await AntiGem.thread.run((a, b) => a + b, [2, 40]);

// Spread a list over all cores; results come back in order
const sizes = await AntiGem.thread.map(urls, async (url) => (await (await fetch(url)).arrayBuffer()).byteLength);

// Your own pool (e.g. 2 threads for background work); close it when done
const pool = AntiGem.thread.pool(2);
await pool.run(fn, [args]);
pool.close();

AntiGem.thread.cores   // number of CPU cores
```

Inside a thread the function may be `async` and may use `fetch`, `indexedDB` and `AntiGem.fs`. A thrown error rejects
the promise with the same name and message. Arguments and results are copied (structured clone); an `ArrayBuffer` or
typed array returned is moved without copying. For a long-running thread with its own message protocol, use a normal
`new Worker('worker.js')`.

## Intents: share / open with another app

```js
// send: the user picks the app (mini apps that accept it, or "Other apps…" = Android's share sheet)
await AntiGem.intent.send({ action: 'share', path: '/sdcard/DCIM/x.jpg', title: 'Sunset' });   // → {to}
await AntiGem.intent.send({ action: 'share', text: 'Hello', url: 'https://example.com' });
// receive (declare "intents" in the manifest)
AntiGem.on('intent', (it) => { /* {action, type, text?, url?, path?, title?, data?, from: {id, name}} */ });
AntiGem.intent.current   // the last one received
```
A file goes along as a one-day read grant for that file only: copy it into your `data/` if you keep it. Android's
share sheet has "Mini app" too: what's shared there arrives the same way (files copied into `data/inbox/`).
Links `antigem-miniapp://app/<id>/<path>?a=1` open the app and arrive as `{action: "view", path, params, url}`.

## Notifications

Native Android notifications, as an installed app has them. The same `id` updates a notification in place.

```js
await AntiGem.notify.channel({ id: 'timer', name: 'Running timer', importance: 'low', sound: false });
await AntiGem.notify.show({
  id: 'timer', channel: 'timer', ongoing: true, color: '#C62828',
  title: 'Focus', body: 'Ends at 14:25',
  countdown: endTime,                        // the system ticks it: no updates needed
  actions: [{ id: 'pause', title: 'Pause', open: false }, { id: 'skip', title: 'Skip', open: false }],
});
AntiGem.on('notification', (e) => { if (e.action === 'pause') pause(); });
```

| Option | Meaning |
|---|---|
| `id` | Same id = update in place. Default: a new one |
| `title`, `body`, `subtext` | Text (`subtext` defaults to the app name) |
| `channel` | A channel made with `notify.channel` (the user can tune it in Android settings, under the app's name). Default: alerts (sound, pop-up); `ongoing`: the main section without sound (timers, downloads); `silent`: the Silent section. For your own channel of a running thing use `importance: "default", sound: false` — `low` puts it under Silent. Android never lets an app raise a channel's importance later: use a new channel id |
| `color` | Accent color (icon, app name, buttons). `colorized: true` asks Android to fill the whole notification, which only some notification types allow |
| `icon` | Large icon: a file of the app (`img/x.png`), a `data/…` file, or an https URL the app may reach. Default: the app icon |
| `smallIcon` | Status-bar icon: a white-on-transparent PNG of the app |
| `image` | Big picture (same sources as `icon`) |
| `lines` | Inbox style: up to 7 lines |
| `messages` | Chat style: `[{from, text, time}]` (`from` empty = you), title = conversation name |
| `progress` | `{value, max}` or `{indeterminate: true}` |
| `countdown` / `chronometer` | A Date / epoch ms: counts down to it / up from it. Shown like Clock's stopwatch: the time is the big content, ticked by the system (no updates needed). A countdown goes away when it ends |
| `display` | A big static value in the same layout, e.g. a paused timer's `"12:34"` |
| `ongoing` | Stays until the app removes it (the user can still swipe it on Android 14+) |
| `silent`, `onlyAlertOnce` | No sound or vibration (on updates) |
| `priority` | `min` `low` `default` `high` `max` |
| `category` | Android category: `alarm`, `reminder`, `progress`, `status`, `msg`, `event`, … |
| `visibility` | Lock screen: `public`, `private` (default, hides the text), `secret` |
| `group`, `groupSummary` | Bundle the app's notifications together. Without a group each notification stands alone (never mixed with other apps') |
| `number` | Badge count |
| `timeout` | Remove after this many ms |
| `actions` | Up to 3 buttons `{id, title, open?, reply?}`. `open: false` doesn't open the app: the event goes to the app if it is on screen, else to its background script (`AntiGem.background.on('notification', task => task.data)`), else when it opens. `reply: {placeholder}` adds a text box; the text comes as `e.reply` |
| `tapAction` | Event action for a tap on the notification (default `"tap"`); a tap opens the app |

Events: `{id, action, reply?, queued?}`. `action` is `"tap"`, `"dismiss"` (swiped away) or a button id. `queued: true`
means it happened while the app was closed. At most 12 notifications per app at a time, 10 channels per app. Android
keeps the user's channel choices: once created, a channel's importance and sound can't be changed by the app.

## Background tasks

Like a native app, a mini app can do work while it is closed: check something online, sync, then notify.

1. Add `"background": "background.js"` to the manifest and `"background": true` to its permissions.
2. In `background.js` (it runs in an invisible page with the full SDK: fetch, IndexedDB, localStorage, files,
   cookies, notifications; no UI, no dialogs):
   ```js
   AntiGem.background.on('sync', async (task) => {      // task: {name, reason: "schedule"|"alarm"|"manual", runId}
     const items = await (await fetch('https://api.example.com/new')).json();
     if (items.length) await AntiGem.notify.show({ title: `${items.length} new`, tag: 'sync' });
   });
   ```
3. From the app (calling it on every start is fine, the schedule is kept):
   ```js
   await AntiGem.permissions.request('background');
   await AntiGem.background.register('sync', { every: '1h', network: 'any' });   // flexible, battery friendly
   await AntiGem.background.register('digest', { at: '07:30', repeat: 'daily' }); // exact time
   ```
   Options: `every` (≥ 15 min, periodic), `delay` (once), `at` + `repeat` (exact, same rules as reminders), and for
   `every`/`delay`: `network` (`"any"` or `"unmetered"`), `charging`, `batteryNotLow`.

Limits: at most 10 tasks per app, 48 runs per app per 24 h, 60 s per run, one task at a time on the phone, nothing in
battery saver (except `run()`). The handler's promise is the run: finish your work before it resolves. Timers
(`setTimeout`) may be slowed in the invisible page; await promises instead. `every` tasks run roughly on time
(Android batches them); use `at` when the minute matters. Runs and errors show in App info → Background and in the
console log. Force-stopping AntiGem stops everything until it is opened again.

## Development apps and signed packages

- **Development app:** a folder in `~/mini-apps` (what you build). Unsigned, hot reload, console and DevTools, a
  **DEV** label. It only runs with Developer mode on (Mini Apps → `</>`, on by default in the beta).
- **Installed app:** installed from a signed package (`.agapp`). Like an Android APK: the first install records the
  developer's key; an update must be signed with the same key (else: uninstall first, which deletes its data); a lower
  `version_code` needs the user's OK. Its files live in AntiGem's private folder and each one is checked against the
  signature when it loads: a modified installed app doesn't run. An installed app owns its id: a development folder
  with the same id doesn't run, and a package can't take a development app's id.
- **Making a package:** App info → "Export signed package" (signed with the phone's developer key, Mini Apps → key
  icon; back the key up: without it your apps can't be updated), or on a PC:
  ```bash
  node ~/mini-apps/tools/agapp.mjs keygen --developer "Your Name" --out dev.agkey   # once (or the phone's backup)
  node ~/mini-apps/tools/agapp.mjs sign ~/mini-apps/myapp --key dev.agkey          # → com.you.myapp-1.2.0.agapp
  node ~/mini-apps/tools/agapp.mjs verify com.you.myapp-1.2.0.agapp
  ```
  Bump `version` (or `version_code`) for every release.
- Packages are only ever signed: there is no way to ship a development app as an installable package.
- **Sharing and installing** (like an APK): "Export signed package" opens the share sheet (WhatsApp, Telegram, email,
  Drive, Nearby Share…). On the other phone, tapping the `.agapp` (in a chat, Files, a browser's downloads or AntiGem's
  file explorer) or sharing it to "Mini app" opens AntiGem's installer: who signed it, new / update / downgrade, new
  permissions, then Open / Done. Updates go the same way. In AntiGem: Mini Apps → install icon → "Install from a file"
  or "Install from a link" (https:// only, e.g. a GitHub release asset). An installed app's App info → "Share app" sends
  the exact package it was installed from, still with the developer's signature.
- Hosting packages: serve them as `application/vnd.antigem.agapp` (or `application/octet-stream`) with the `.agapp`
  name, so phones open them in AntiGem.

## UI kit

Add `<link rel="stylesheet" href="/_antigem/ui.css">` for Material 3 components that follow the user's AntiGem theme
(light and dark) automatically. Its main classes:

- **Layout:** `ag-page`, `ag-appbar` (`ag-large` for a big title that shrinks while scrolling), `ag-appbar-title`,
  `ag-appbar-sub`, `ag-appbar-actions`, `ag-content`, `ag-section`, `ag-divider`, `ag-row`, `ag-stack`, `ag-grow`.
- **Surfaces:** `ag-card` (`-filled`, `-outlined`, `-elevated`, `-primary`), `ag-card-title`, `ag-card-sub`,
  `ag-card-actions`.
- **Lists:** `ag-list` (`ag-plain`, `ag-divided`), `ag-item` (as `button`, `a` or `label` it gets a press state),
  `ag-item-lead`, `ag-avatar`, `ag-item-body`, `ag-item-title`, `ag-item-sub`, `ag-item-trail`.
- **Buttons:** `ag-btn` (`-tonal`, `-outlined`, `-text`, `-danger`, `-icon`, `-sm`, `-block`), `ag-fab`
  (`ag-fab-extended`).
- **Inputs:** `ag-field` + `ag-input` / `ag-textarea` + `ag-label` (floating label, needs `placeholder=" "`),
  `ag-helper`, `input.ag-switch`, `input.ag-checkbox` (`ag-round`), `ag-segmented`, `ag-chips` / `ag-chip`
  (`is-selected`), `ag-badge` (`-success`, `-warning`, `-neutral`, `-primary`).
- **Navigation:** `ag-navbar` > `ag-navbar-item` (`is-active`) > `ag-navbar-icon` + `ag-navbar-label`.
- **Feedback:** `ag-empty` (`ag-empty-icon`, `ag-empty-title`), `ag-progress` > `ag-progress-bar`
  (`style="--value: 40%"`, or `ag-indeterminate`), `ag-spinner`.
- **In-page sheet:** `ag-scrim` and `ag-sheet` (toggle `is-open`).
- **Motion and text:** `ag-enter`, `ag-fade`, `ag-display`, `ag-headline`, `ag-title`, `ag-title-m`, `ag-body-s`,
  `ag-caption`, `ag-muted`, `ag-selectable`.

```html
<div class="ag-page">
  <header class="ag-appbar"><h1 class="ag-appbar-title">Notes</h1></header>
  <main class="ag-content">
    <ul class="ag-list">
      <li><button class="ag-item"><span class="ag-avatar">📝</span>
        <span class="ag-item-body"><span class="ag-item-title">Groceries</span><span class="ag-item-sub">Milk, eggs</span></span>
      </button></li>
    </ul>
  </main>
  <button class="ag-fab" aria-label="New">＋</button>
  <nav class="ag-navbar">
    <button class="ag-navbar-item is-active"><span class="ag-navbar-icon">🏠</span><span class="ag-navbar-label">Home</span></button>
    <button class="ag-navbar-item"><span class="ag-navbar-icon">⚙️</span><span class="ag-navbar-label">Settings</span></button>
  </nav>
</div>
```

Theme colors are CSS variables on `:root`. The name is `--ag-` plus the theme token with `_` turned into `-`:
`--ag-primary`, `--ag-on-primary`, `--ag-primary-container`, `--ag-secondary-container`, `--ag-background`,
`--ag-surface`, `--ag-on-surface`, `--ag-on-surface-variant`, `--ag-surface-container-low` … `-highest`,
`--ag-outline`, `--ag-outline-variant`, `--ag-error`, `--ag-success`, `--ag-warning`, `--ag-info`, and
`--ag-app-color` (your `theme_color`). `html[data-theme="dark"|"light"]` and `color-scheme` are set too. To use a brand
color in kit components, set `--ag-accent` and `--ag-on-accent`.

## Hot reload, logs and data

- **Hot reload:** saving any file in the app folder reloads every open page of the app within about 100 ms. Edits to
  only `.css` files hot-swap the stylesheets without a reload, so state is kept. Storage persists across reloads.
- **Logs:** `~/.antigem/mini-apps/<app id>/console.log` (`tail -f` it while testing), or the native console
  (`AntiGem.dev.console()`).
- **Data:** `~/.antigem/mini-apps/<app id>/` holds `kv.db` (localStorage + AntiGem.storage, SQLite), `idb.db`
  (IndexedDB), `cookies.db` (the cookie jar) and `files/` (the app's `data/` folder). Reminders and background tasks
  are kept by AntiGem itself (not in this folder) and survive reboots.

## Rules for building apps

1. **Offline first.** Don't load CDNs or web fonts unless the app truly needs the network and declares it. Bundle
   small libraries into the app folder instead.
2. **Use the theme variables** (or the UI kit) for every color, so the app matches AntiGem in light and dark.
   Never hard-code white or black backgrounds.
3. **Use `AntiGem.ui`** for dialogs, sheets, pickers and toasts, and `AntiGem.device.haptic` on important taps.
   That's what makes it feel native.
4. **Handle back:** register `AntiGem.onBack` to close in-page panels and to return to the first tab before the app
   closes.
5. **Declare the fewest permissions,** and handle `NotAllowedError` with a friendly message.
6. **Test with `AntiGem.dev.console()`** or the log file. Fix every error before calling the app done.
7. **Keep `id` stable;** bump `version` when you change the app.
