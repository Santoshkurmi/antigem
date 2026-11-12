# Intents, links and device events

Mini apps exchange content the way Android apps do. An app declares in its manifest what it accepts ("share text and
images"); another app calls `AntiGem.intent.send()`, the user picks the target in a native sheet, and the target opens
and receives an `intent` event. Android's own share sheet has a "Mini app" entry that delivers the same way, and
`antigem-miniapp://` links open an app at a path. This page also covers the events about the outside world: the
page's lifecycle (`resume`, `pause`) and the device's network and battery status.

## Declaring what the app accepts

```json
"intents": [
  { "action": "share", "types": ["text/plain", "text/uri-list", "image/*"] },
  { "action": "edit", "types": ["image/jpeg", "image/png"] }
]
```

| Field | Default | Meaning |
|---|---|---|
| `action` | `'share'` | Any name. `share`, `open`, `view` and `edit` are the usual ones |
| `types` | `["*/*"]` | MIME types this action accepts. `image/*` style wildcards, `*/*` and `*` work |

An app is offered as a target only when one entry has the same `action` and a type that matches what is sent, and it
can run (an installed app that is intact, or a development app with Developer mode on). See [manifest](manifest.md).
No permission is needed to send or receive.

## Sending

```js
// a file: the user picks Pocket Notes, another mini app, or "Other apps…" (Android's share sheet)
const r = await AntiGem.intent.send({ action: 'share', path: '/sdcard/DCIM/Camera/IMG_1.jpg', title: 'Sunset' });
// r = {to: 'com.antigem.notes'} | {to: 'android'} | null (cancelled)

// text and a link
await AntiGem.intent.send({ action: 'share', text: 'Look at this', url: 'https://example.com' });

// structured data to an app that knows your format
await AntiGem.intent.send({ action: 'import-tasks', type: 'application/x-tasks+json', data: { tasks } });
```

| Option | Type | Default | Meaning |
|---|---|---|---|
| `action` | string | `'share'` | Up to 40 characters |
| `type` | MIME type | from the file's extension; `'text/uri-list'` with a `url`; else `'text/plain'` | Used to find targets |
| `path` | string | none | A file the app may read (any [AntiGem.fs path](files.md#paths)). It must exist (`NotFoundError`) |
| `text` | string | none | |
| `url` | string | none | |
| `title` | string | none | |
| `data` | object | none | Any JSON object, passed as it is (other values are dropped) |

The user picks the target in a native sheet titled "Share with", "Open with" (for `open` and `view`), "Edit with",
or "<Action> with". It lists every mini app that accepts the action and type (not the sender itself), plus
**"Other apps…"** when the action is `share`, `open` or `view` and there is a file, text or URL to hand over.

`send` resolves with:

- `{to: '<app id>'}`: the target app opens (or comes to the front) and receives the intent;
- `{to: 'android'}`: the user chose "Other apps…". A file is shared to Android apps; for `open`/`view` with a `url`
  the link opens in the browser; otherwise `text` and `url` are shared as text;
- `null`: the user closed the sheet.

It rejects with `NotFoundError` when no app accepts it (and "Other apps…" doesn't apply), and with
`NotSupportedError` in the background page.

### Files go along as a one-day grant

When the intent carries a file, the receiving app gets a **read grant for that one file for 24 hours**: it can
`read`, `stat`, `exists` and `url()` that exact path, and nothing else in the folder. The sender doesn't give away its
folder, and the receiver needs no storage permission. If the receiver wants to keep the file, it copies it into its own
`data/` folder.

## Receiving

```js
AntiGem.on('intent', async (it) => {
  if (it.action !== 'share') return;
  if (it.path && it.type?.startsWith('image/')) {
    const blob = await (await fetch(AntiGem.fs.url(it.path))).blob();   // the grant lasts one day: keep a copy
    await AntiGem.fs.write(`data/images/${Date.now()}-${it.path.split('/').pop()}`, blob);
  }
  addNote([it.title, it.text, it.url].filter(Boolean).join('\n\n'));
  AntiGem.ui.toast(`Saved from ${it.from?.name ?? 'another app'}`);
});

// or later: the last intent this page received
if (AntiGem.intent.current?.action === 'view') openPath(AntiGem.intent.current.path);
```

| Field | Meaning |
|---|---|
| `action` | What the sender asked for |
| `type` | The MIME type (absent for links) |
| `text`, `url`, `title` | As sent |
| `data` | The sender's JSON object, if any |
| `path` | The file: a real absolute path with a one-day read grant (from another mini app), or `data/inbox/<name>` (from Android, already copied into the app) |
| `params` | Links only: the link's query parameters as an object |
| `from` | `{id, name, signed}` of the sending mini app; `{id: 'android', name: 'Android'}` for Android's share sheet; `{id: 'link', name: 'Link'}` for a link |

- When the app is on screen, every open page gets the event at once. Otherwise the event waits, the app opens, and the
  event is delivered to every page once it has loaded (at most 20 waiting events per app, notifications included).
  Register the listener when the page starts so it doesn't miss it.
- `AntiGem.intent.current` holds the last intent the page received (or `null`).
- The event is the only thing that happens: the app decides what to show (for example `AntiGem.nav.push`).

## Android's share sheet: "Mini app"

When the user shares from any Android app and picks **Mini app**, AntiGem lists the mini apps whose manifest accepts
`share` with that MIME type. The chosen app opens and gets an `intent` event:

```js
// {action: 'share', type: 'image/jpeg', path: 'data/inbox/IMG_1.jpg', title?, text?, from: {id: 'android', name: 'Android'}}
```

- A file is copied into the app's `data/inbox/` first (a name that exists gets a `1-`, `2-`… prefix), so it is the
  app's own. When several files are shared, only the first is delivered.
- Shared text arrives as `text`, the subject as `title`.
- A shared `.agapp` package opens AntiGem's installer instead. See
  [signing and distribution](signing-and-distribution.md).

## Links: antigem-miniapp://

`antigem-miniapp://app/<app id>/<path>?<query>` opens a mini app from a browser, a chat, a QR code or another Android
app.

- `antigem-miniapp://app/com.example.todo` just opens the app (as it was, if it was running).
- With a path, the app also receives an intent:

  ```js
  // antigem-miniapp://app/com.example.todo/list/42?highlight=7
  AntiGem.on('intent', (it) => {
    if (it.action !== 'view') return;
    // it = {action: 'view', path: 'list/42', params: {highlight: '7'},
    //       url: 'antigem-miniapp://app/com.example.todo/list/42?highlight=7', from: {id: 'link', name: 'Link'}}
    const [, listId] = it.path.split('/');
    AntiGem.nav.push('list.html', { id: listId, highlight: it.params.highlight });
  });
  ```

- The path is not loaded for you; it is only data. Values in `params` are strings.
- A mini app can't open these links itself (`AntiGem.device.openUrl` only opens `http(s)`, `mailto` and `tel`): to
  hand something to another mini app, use `AntiGem.intent.send`.

## Lifecycle events

Each page gets `pause` when its screen leaves the front, and `resume` when it comes back on screen after a `pause`
(back from a pushed page, another app, Home or the lock screen). Use them to refresh data another page changed, or to
save and stop work nobody sees.

```js
AntiGem.on('resume', () => refreshList());
AntiGem.on('pause', () => saveDraft());
```

- The first open sends no `resume`, by design: that is the page's own load. Run your start-up code directly.
- Each page gets its own events: a page under a pushed page is paused until the pushed page closes.
- The background page never gets these events.

## Device status

`AntiGem.device.status()` returns the network and battery now. No permission is needed.

```js
const { network, battery } = await AntiGem.device.status();
if (!network.online) showOfflineBanner();
if (network.metered) useLowQualityImages();
if (battery.saver) disableAnimations();
```

| Field | Values |
|---|---|
| `network.online` | `true` when the active network has internet |
| `network.type` | `'wifi'`, `'cellular'`, `'ethernet'`, `'vpn'`, `'other'` or `'none'` |
| `network.metered` | `true` on metered networks (most mobile data) |
| `battery.level` | `0`–`100`, or `null` when unknown |
| `battery.charging` | `true` while charging or full on the charger |
| `battery.saver` | `true` when battery saver is on |

While a page is on screen, it gets `network` (with the `network` object) and `battery` (with the `battery` object)
events when something changes. They are not sent while the page is in the background; call `status()` on `resume` if
you need the current values.

```js
AntiGem.on('network', (n) => setOffline(!n.online));
AntiGem.on('battery', (b) => (b.saver ? reduceWork() : null));
AntiGem.on('resume', async () => setOffline(!(await AntiGem.device.status()).network.online));
```

## Limits and errors

| Error | When |
|---|---|
| `NotFoundError` | `intent.send` with a `path` that isn't a file; no app accepts the action and type |
| `NotAllowedError` | `intent.send` with a `path` the app may not read |
| `NotSupportedError` | `intent.send` in the background page |

- File grants last 24 hours and are read-only.
- Intents and notification events share one queue of 20 waiting events per app.
- Only the first file of a multi-file Android share is delivered.
