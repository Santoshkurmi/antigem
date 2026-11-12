# API reference

Every page of a mini app gets the SDK before any of its own scripts run, so `window.AntiGem` always exists. This page
lists every member of `AntiGem`, every event you can listen to, and the standard web APIs the SDK replaces with
native ones. Each entry links to the page that explains it. Permission names refer to the manifest's `permissions`
object (see [permissions](permissions.md)).

## Where the SDK runs

| Context | What it has |
|---|---|
| A page (`index.html`, pushed pages) | The full `AntiGem` object below |
| The background page (the manifest's `background` script) | The full object, but anything that needs the screen rejects with `NotSupportedError`. See [background](background.md) |
| A Web Worker or an `AntiGem.thread` thread | A smaller `AntiGem` (see [In workers](#in-workers)). See [threads](threads.md) |

All methods that return a Promise reject with a `DOMException` whose `name` tells what went wrong (see
[Error names](#error-names)). Methods marked "no Promise" return nothing and never throw; their errors only appear in
the console log.

## Top-level members

| Member | Type | Meaning | Page |
|---|---|---|---|
| `AntiGem.sdk` | number | SDK version, currently `1` | |
| `AntiGem.app` | object (frozen) | `{id, name, version, origin}` of this app | [getting started](getting-started.md) |
| `AntiGem.page` | object (frozen) | `{id, path, params, root, background}` of this page. `params` = the page's query string as an object (values are strings). `root` is `true` on the first page, `false` on pushed pages. `background` is `true` in the background page | [ui](ui.md#pages-and-navigation) |
| `AntiGem.ready` | Promise | Resolves once the native side has answered. You don't need to await it before calling APIs | |
| `AntiGem.theme` | object | `{dark, colors, appColor}`, updated on every `theme` event | [ui](ui.md#theme-and-dark-mode) |
| `AntiGem.on(event, fn)` | function | Listens to one of the [events](#events). Unknown event names are ignored | |
| `AntiGem.off(event, fn)` | function | Removes a listener | |
| `AntiGem.onBack(fn)` | function → function | Adds a back handler. `fn()` returns `true` (synchronously) when it handled back. Returns a function that removes the handler | [ui](ui.md#back-button) |
| `AntiGem.reload()` | no Promise | Reloads this page | |
| `AntiGem.storage` | namespace | Key-value storage with JSON values | [storage](storage.md#antigemstorage) |
| `AntiGem.fs` | namespace | Files | [files](files.md) |
| `AntiGem.permissions` | namespace | Permission states and prompts | [permissions](permissions.md) |
| `AntiGem.ui` | namespace | Native dialogs, toasts, media viewer, status bar | [ui](ui.md) |
| `AntiGem.device` | namespace | Haptics, clipboard, share, links, device info and status | [ui](ui.md#device-helpers), [intents](intents.md#device-status) |
| `AntiGem.notify` | namespace | Notifications and reminders | [notifications](notifications.md) |
| `AntiGem.nav` | namespace | Native pages | [ui](ui.md#pages-and-navigation) |
| `AntiGem.cookies` | namespace | The app's cookie jar | [storage](storage.md#cookies) |
| `AntiGem.background` | namespace | Background tasks | [background](background.md) |
| `AntiGem.intent` | namespace | Share / open with other apps | [intents](intents.md) |
| `AntiGem.mcp` | namespace | Call AntiGem's MCP tool servers | [mcp](mcp.md) |
| `AntiGem.dev` | namespace | Developer tools | [troubleshooting](troubleshooting.md) |
| `AntiGem.thread` | namespace | Run functions on other CPU cores | [threads](threads.md) |

## Events

Listen with `AntiGem.on(name, fn)`. Only these names are accepted.

| Event | Data | When | Page |
|---|---|---|---|
| `theme` | `{dark, colors, appColor}` | AntiGem's theme or light/dark mode changed | [ui](ui.md#theme-and-dark-mode) |
| `resume` | `null` | This page came back on screen after a `pause` (another app, Home, the lock screen, back from a pushed page). Not sent on the first open | [intents](intents.md#lifecycle-events) |
| `pause` | `null` | This page's screen left the front | [intents](intents.md#lifecycle-events) |
| `storage` | `{key}` (`key` is `null` after `clear()`) | Another page of the app changed `AntiGem.storage`. Changes to `localStorage` fire the standard `window` `storage` event instead | [storage](storage.md) |
| `permission` | `{name, state}` | A permission's state changed | [permissions](permissions.md) |
| `notification` | `{id, action, reply?, queued?}` | A tap, button, inline reply or swipe-away on one of the app's notifications | [notifications](notifications.md#events) |
| `intent` | `{action, type?, text?, url?, path?, title?, data?, params?, from}` | Another app, Android's share sheet or a link sent something to this app | [intents](intents.md#receiving) |
| `network` | `{online, type, metered}` | Connectivity changed while the page is on screen | [intents](intents.md#device-status) |
| `battery` | `{level, charging, saver}` | Battery state changed while the page is on screen | [intents](intents.md#device-status) |

```js
AntiGem.on('theme', (t) => console.log('dark mode:', t.dark));
AntiGem.on('notification', (e) => { if (e.action === 'pause') pauseTimer(); });
```

## AntiGem.storage

Synchronous. Values are stored as JSON. No permission needed. See [storage](storage.md#antigemstorage).

| Method | Returns | Meaning |
|---|---|---|
| `get(key, fallback?)` | value | The stored value (parsed from JSON), or `fallback` when the key is missing |
| `set(key, value)` | `undefined` | Stores `JSON.stringify(value)` (`undefined` is stored as `null`) |
| `remove(key)` | `undefined` | Deletes a key |
| `keys()` | `string[]` | All keys of `AntiGem.storage` (not `localStorage`'s) |
| `clear()` | `undefined` | Deletes every `AntiGem.storage` key |

## AntiGem.fs

Paths: `data/…` (own folder), `app:<id>/…` (shared group), `~/…`, or an absolute path. See [files](files.md).

| Method | Returns | Permission |
|---|---|---|
| `read(path, {encoding?: 'utf8' \| 'base64'})` | `Promise<string>` | read access to the folder |
| `readJSON(path)` | `Promise<any>` | read access |
| `write(path, data, {append?})` | `Promise<void>`. `data`: string, `Blob`, `ArrayBuffer` or typed array | write access |
| `writeJSON(path, value)` | `Promise<void>` (pretty-printed, 2 spaces) | write access |
| `list(path)` | `Promise<[{name, path, type: 'file'\|'dir'\|'link', size, mtime, link?, blocked?}]>` | read access |
| `stat(path)` | `Promise<{name, path, type, size, mtime} \| null>` | any access to the folder |
| `exists(path)` | `Promise<boolean>` | any access to the folder |
| `mkdir(path)` | `Promise<void>` (creates parents) | write access |
| `delete(path, {recursive?})` | `Promise<void>` | write access |
| `move(from, to)` | `Promise<void>` | write access to both |
| `url(path, {thumb?})` | `string` (synchronous) | checked when the URL is loaded |
| `pick({accept?, multiple?})` | `Promise<[{name, path, size, type}]>` | none (files are copied into `data/imports/`) |
| `pickFolder()` | `Promise<{path, access} \| null>` | manifest `storage.pick` |
| `folders()` | `Promise<[{path, access, source, app?}]>` | none |
| `forgetFolder(path?)` | `Promise<void>` | none |

## AntiGem.permissions

See [permissions](permissions.md).

| Method | Returns | Meaning |
|---|---|---|
| `all()` | `{name: state}` | Every declared permission and its state |
| `query(name)` | `'granted' \| 'denied' \| 'prompt' \| 'undeclared'` | One permission, synchronously |
| `request(name)` | `Promise<'granted' \| 'denied' \| 'prompt' \| 'undeclared'>` | Shows the native prompt when the permission is declared and not granted. In the background page it only returns the current state |

Permission names: `network`, `localhost`, `notifications`, `background`, `clipboard`, `camera`, `microphone`,
`storage:<path>` (one per declared folder), `storage:all`, `mcp:<server>`. `request('storage')` asks for every
declared folder that isn't granted yet. `query('storage')` (pages and workers) is `granted` only when every declared
folder is granted, `prompt` while any is undecided, otherwise `denied`.

## AntiGem.ui

Not available in the background page (rejects with `NotSupportedError`, `toast` and `statusBar` do nothing). See
[ui](ui.md).

| Method | Returns | Meaning |
|---|---|---|
| `toast(text, {long?})` | no Promise | Android toast |
| `alert(message \| {title?, message, ok?})` | `Promise<void>` | Native alert |
| `confirm(message \| {title?, message?, ok?, cancel?, destructive?})` | `Promise<boolean>` | `false` when cancelled or dismissed |
| `prompt(message \| {title?, message?, value?, placeholder?, ok?, cancel?})` | `Promise<string \| null>` | `null` when cancelled |
| `sheet({title?, items: [{id, label, icon?, destructive?}]})` | `Promise<string \| null>` | The chosen item's `id` |
| `pickDate({value?: 'YYYY-MM-DD', title?})` | `Promise<string \| null>` | `'YYYY-MM-DD'` |
| `pickTime({value?: 'HH:MM'})` | `Promise<string \| null>` | `'HH:MM'` (24 h) |
| `viewMedia(items \| {items, index?})` | `Promise<{index}>` | Native photo/video viewer; resolves when closed with the last index shown |
| `statusBar({color?})` | no Promise | Status bar color (a CSS color value, `var(--name)` too); no color, or a value that isn't a color = back to the default |

## AntiGem.device

See [ui](ui.md#device-helpers) and [intents](intents.md#device-status).

| Method | Returns | Permission | Meaning |
|---|---|---|---|
| `haptic(kind?)` | no Promise | none | `'light'` (default), `'medium'`, `'heavy'`, `'selection'`, `'success'`, `'warning'`, `'error'` |
| `share({title?, text?, url?})` | `Promise<void>` | none | Android share sheet with text |
| `copy(text)` | `Promise<void>` | none | Writes the clipboard |
| `paste()` | `Promise<string>` | `clipboard` | Reads the clipboard (`''` when empty) |
| `openUrl(url)` | `Promise<void>` | none (asks the user when `network` isn't granted) | Opens an `http(s)`, `mailto` or `tel` link outside AntiGem |
| `info()` | `Promise<{manufacturer, model, android, sdk, dark, locale}>` | none | Device facts |
| `status()` | `Promise<{network: {online, type, metered}, battery: {level, charging, saver}}>` | none | Connectivity and battery now |

## AntiGem.notify

See [notifications](notifications.md).

| Method | Returns | Permission |
|---|---|---|
| `show(options)` | `Promise<{id}>` | `notifications` |
| `channel({id, name?, description?, importance?, sound?, vibrate?, lights?, badge?})` | `Promise<{id, importance}>` | `notifications` |
| `deleteChannel(id)` | `Promise<void>` | none |
| `active()` | `Promise<[{id, title, body, ongoing, posted}]>` | none |
| `dismiss(id?)` | `Promise<void>` (no id = all shown notifications of the app) | none |
| `schedule(options)` | `Promise<{id, at, repeat}>` | `notifications` |
| `list()` | `Promise<[{id, title, body, repeat, at}]>` | none |
| `cancel(id)` | `Promise<void>` (cancels the reminder and removes its notification) | none |
| `cancelAll()` | `Promise<void>` | none |

## AntiGem.nav

See [ui](ui.md#pages-and-navigation). All three return nothing.

| Method | Meaning |
|---|---|
| `push(path, params?)` | Opens `path` (relative to the app folder) as a new native page. `params` become the query string; object values are JSON-encoded |
| `back()` | Closes this page. On the root page, sends the app to the background |
| `close()` | Closes the whole app (all its pages) |

## AntiGem.cookies

Only for hosts the app may reach. See [storage](storage.md#cookies).

| Method | Returns |
|---|---|
| `list(url?)` | `Promise<[{name, value, domain, path, expires, session, secure, httpOnly, hostOnly}]>`: the cookies that would be sent to `url`, or all cookies |
| `get(url, name)` | `Promise<cookie \| null>` |
| `set({url, name, value, path?, domain?, expires?, maxAge?, secure?, httpOnly?})` | `Promise<cookie>` |
| `import(url, header, {days?})` | `Promise<number>`: how many were saved |
| `delete({url?, name?, path?})` | `Promise<number>`: how many were removed |
| `clear(url?)` | `Promise<number>` |

## AntiGem.background

See [background](background.md).

| Member | Returns | Permission |
|---|---|---|
| `isBackground` | boolean: `true` inside the background page | |
| `register(name, {every} \| {delay} \| {at, repeat?}, plus network?, charging?, batteryNotLow?)` | `Promise<{name, schedule, next}>` | `background` |
| `list()` | `Promise<[{name, schedule, next, lastRun?}]>` | none |
| `cancel(name?)` | `Promise<void>` (no name = all tasks) | none |
| `run(name)` | `Promise<void>`: runs the task now | `background` |
| `on(name, fn)` | `undefined`: in the background script, the handler for task `name` (`'*'` = any) | |

## AntiGem.intent

See [intents](intents.md).

| Member | Returns |
|---|---|
| `send({action?, type?, text?, url?, path?, title?, data?})` | `Promise<{to} \| null>`: `to` is the receiving app's id or `'android'`; `null` when the user cancelled |
| `current` | The last `intent` event this page received, or `null` |

## AntiGem.mcp

Needs `mcp:<server>` for each server. See [mcp](mcp.md).

| Method | Returns |
|---|---|
| `list(server)` | `Promise<tool[]>` |
| `call(server, tool, args?)` | `Promise<result>` (the MCP `tools/call` result) |

## AntiGem.dev

Both return nothing. See [troubleshooting](troubleshooting.md).

| Method | Meaning |
|---|---|
| `console()` | Opens the native console panel (logs, errors, a JS input) |
| `devtools()` | Opens Eruda DevTools in this page |

## AntiGem.thread

See [threads](threads.md).

| Member | Returns |
|---|---|
| `cores` | number of CPU cores (`navigator.hardwareConcurrency`, 4 when unknown) |
| `run(fn, args?, {transfer?})` | `Promise<result>`: `fn(...args)` on a pool thread |
| `map(items, fn)` | `Promise<result[]>`: `fn(item)` for every item, in order |
| `pool(size \| {size})` | `{size, run, map, close}`: a pool of your own (1 to 16 threads) |

## In workers

Inside a Web Worker or an `AntiGem.thread` thread, `AntiGem` has only these members. `AntiGem.worker` is `true` there.

| Member | Methods |
|---|---|
| `sdk`, `app`, `worker` | as in pages |
| `fs` | `read`, `readJSON`, `write`, `writeJSON`, `list`, `stat`, `exists`, `mkdir`, `delete`, `move`, `url(path, {thumb?})`, `folders` |
| `permissions` | `all`, `query` |
| `ui` | `toast` |
| `device` | `haptic`, `copy`, `info` |
| `notify` | `show`, `dismiss`, `schedule`, `list`, `cancel`, `cancelAll` |
| `cookies` | `list`, `set`, `delete` |
| `mcp` | `list`, `call` |
| `thread` | `run`, `map`, `pool`, `cores` |

In workers, `Date` values are converted to epoch milliseconds as in pages: `notify.show` / `notify.schedule` (`at`,
`until`, `repeat.until`, `countdown`, `chronometer`, message times) and cookie `expires`.

## Standard web APIs the SDK replaces

| API | What happens | Page |
|---|---|---|
| `localStorage`, `sessionStorage` | Native, synchronous; `localStorage` is shared by every page of the app | [storage](storage.md) |
| `indexedDB` and the `IDB*` classes | Native SQLite | [storage](storage.md#indexeddb) |
| `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource`, `navigator.sendBeacon` | Native network stack, no CORS, permission-checked | [network](network.md) |
| `Worker` | Gets the SDK inside | [threads](threads.md) |
| `alert()`, `confirm()`, `prompt()` | Native dialogs titled with the app name. In a background task they return at once (`confirm()` → `false`, `prompt()` → `null`) | [ui](ui.md#native-dialogs) |
| `<input type="file">` | Native file picker; with `capture`, the camera app (needs `camera`) | [files](files.md#picking-files) |
| `RTCPeerConnection`, `webkitRTCPeerConnection`, `RTCDataChannel` | Removed (`undefined`) | [network](network.md#not-supported) |
| `navigator.serviceWorker.register` | Rejects with `NotSupportedError` | [network](network.md#not-supported) |
| `navigator.geolocation` | Always denied | [ui](ui.md#device-helpers) |

## Error names

| Name | Typical cause |
|---|---|
| `NotAllowedError` | Permission not declared or not granted, path outside allowed folders, host not in the manifest, user declined |
| `NotFoundError` | No such file, folder, channel, MCP server, or no app accepts an intent |
| `NotSupportedError` | A UI call in the background page; an unknown method; service workers |
| `TypeError` | Bad arguments: invalid path, URL, duration, channel or task name; network failure in `fetch` |
| `InvalidStateError` | Writing to a folder path, deleting a non-empty folder without `recursive`, no `background` script in the manifest |
| `QuotaExceededError` | Too many notifications, channels, reminders or tasks; `fs.read` of a file over 64 MB |
| `AbortError` | A request or thread pool job was aborted |
| `OperationError` | An MCP server returned an error |
| `DataCloneError` | A value can't be stored in IndexedDB or sent back from a thread |
