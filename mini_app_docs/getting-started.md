# Getting started

This page builds a first mini app step by step: a folder, a manifest, a page, a script, a stylesheet with the UI kit, and an icon. It then shows how to open the app, how hot reload works, where the console and developer tools are, how to reset the app's data, the rules every good app follows, and the development loop with the Mini App Developer MCP. No build step and no install are needed: AntiGem runs the folder as it is.

## What you need

- AntiGem with **Developer mode** on (Mini Apps → `</>`; on by default in the beta).
- A way to write files into `~/mini-apps/`: an agent, AntiGem's IDE or terminal, or the [Mini App Developer MCP](developer-mcp.md).

`~` is AntiGem's home folder (in the `com.termux` build, `/data/data/com.termux/files/home`).

## 1. Create the folder

```
~/mini-apps/
  tally/              ← any folder name; folders starting with "." are ignored
    manifest.json
    index.html
    app.js
    style.css
    icon.png
```

Each folder with a `manifest.json` is one app. Write plain HTML, CSS and JavaScript. ES modules and relative imports work. Bundle small libraries into the folder instead of loading them from a CDN.

## 2. manifest.json

```json
{
  "manifest_version": 1,
  "id": "com.example.tally",
  "name": "Tally",
  "version": "1.0.0",
  "description": "Count anything with one tap",
  "entry": "index.html",
  "icon": "icon.png",
  "theme_color": "#2E7D32"
}
```

- `id` is required and must never change: the app's data is stored under it. Letters, digits, `.`, `_`, `-`.
- This app declares no permissions. It can use its own storage, the native UI and haptics without asking.
- Every field is explained in [manifest.md](manifest.md).

## 3. index.html

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Tally</title>
  <link rel="stylesheet" href="/_antigem/ui.css">
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <div class="ag-page">
    <header class="ag-appbar">
      <h1 class="ag-appbar-title">Tally</h1>
    </header>
    <main class="ag-content">
      <div class="ag-card ag-card-filled counter">
        <div class="ag-display" id="count">0</div>
        <div class="ag-caption ag-muted">taps</div>
      </div>
      <div class="ag-row actions">
        <button class="ag-btn ag-btn-tonal" id="reset">Reset</button>
      </div>
    </main>
    <button class="ag-fab" id="add" aria-label="Add one">＋</button>
  </div>
  <script type="module" src="app.js"></script>
</body>
</html>
```

- `/_antigem/ui.css` is AntiGem's UI kit: Material 3 components that follow the user's AntiGem theme, light and dark ([ui.md](ui.md)).
- Don't add the SDK yourself. AntiGem puts `<script src="/_antigem/sdk.js">` at the top of `<head>` of every HTML page, so `window.AntiGem` exists before your scripts run. A viewport tag is added too when the page has none.
- The app is served at its own origin, `https://com-example-tally.antigem.invalid/`, with the app folder as the root: `/style.css` is `tally/style.css`.

## 4. style.css

```css
.counter { margin: 16px; padding: 32px 16px; text-align: center; }
.counter .ag-display { color: var(--ag-primary); }
.actions { justify-content: center; }
```

Use the theme variables (`--ag-primary`, `--ag-surface`, `--ag-on-surface`, …) for every color, never fixed white or black, so the app looks right in light and dark themes.

## 5. app.js

```js
const AG = window.AntiGem;
const countEl = document.getElementById('count');

// AntiGem.storage: native, synchronous, JSON values, survives restarts
let count = AG.storage.get('count', 0);
render();

document.getElementById('add').addEventListener('click', () => {
  count += 1;
  AG.storage.set('count', count);
  AG.device.haptic('light');
  render();
});

document.getElementById('reset').addEventListener('click', async () => {
  const ok = await AG.ui.confirm({
    title: 'Reset the tally?', message: 'The count goes back to 0.', ok: 'Reset', destructive: true,
  });
  if (!ok) return;
  count = 0;
  AG.storage.set('count', count);
  AG.ui.toast('Reset');
  render();
});

function render() {
  countEl.textContent = String(count);
}
```

The full API is in [api-reference.md](api-reference.md); storage options in [storage.md](storage.md).

## 6. icon.png

A square PNG, 192 × 192 or larger. Without an icon (or with one Android can't decode, such as SVG), AntiGem draws a rounded tile in `theme_color` with the first letter of the name.

## 7. Open it

1. In AntiGem, open the chat drawer and tap the **Mini Apps** icon (the apps grid icon, next to the Themes palette).
2. **Tally** is in the grid with the label `DEV · 1.0.0`. The grid rescans every time the screen comes back; the refresh icon rescans by hand. If the manifest has an error, the folder is listed in red with the reason instead (`~/mini-apps/tally isn't an app: …`).
3. Tap it. The app opens full screen with a splash in its color (marked `DEV`), and gets its own card in Android's Recents.

Back on the first page sends the app to the background, like a real app, so it reopens instantly. Long-press the app in the grid for **App info** (permissions, data, home-screen shortcut, export).

The first time an app with permissions opens, a native dialog asks for them ([permissions.md](permissions.md)).

## 8. Hot reload

Edit any file and save. Every open page of the app reloads a moment later. Writes are grouped, so writing five files gives one reload. When only `.css` files changed, the stylesheets are swapped without a reload and the page keeps its state. Storage survives reloads.

- Saving `manifest.json` re-reads it: name, color and permissions apply at once. Entries it ignores, or that look like a mistake, show as warnings in the console ([manifest.md](manifest.md#manifest-warnings)).
- Files and folders starting with `.`, `node_modules`, and `*~`, `*.swp`, `*.tmp` files are ignored.
- Hot reload is for development apps only. Installed apps change only through a signed update.

## 9. Console and developer tools

A development app shows a small round **developer bubble** (`⋯`) that you can drag anywhere. Tap it for:

| Item | Does |
|---|---|
| **Reload** | Reloads the page |
| **Console** | Native console: every `console.*` message, uncaught error and unhandled rejection, with a **Run JavaScript…** box, **DevTools** and a clear button |
| **DevTools (elements, network, storage)** | Opens Eruda in the page. It loads from a CDN, so the phone must be online (the app needs no network permission for it) |
| **App info & permissions** | The App info sheet |
| **Add to home screen** | A launcher shortcut |
| **Close app** | Closes the app and its Recents card |

The same console is written to a file, for agents and for `tail -f`:

```bash
tail -f ~/.antigem/mini-apps/com.example.tally/console.log
```

Lines look like `14:03:22.418 ERROR Uncaught TypeError: … (/app.js:12)`. Blocked requests, 404s and permission errors are logged there too. From code: `AntiGem.dev.console()` opens the console, `AntiGem.dev.devtools()` opens Eruda.

## 10. Reset the app's data

App info → **Clear data** → "Its storage, databases, files and logs are deleted. The app itself stays." This also cancels its reminders, background tasks, notifications and cookies. Permissions are kept; **Reset permissions** forgets those.

The data lives in `~/.antigem/mini-apps/<id>/`, outside the app folder, so editing the app never touches it:

| File | Holds |
|---|---|
| `kv.db` | `localStorage` and `AntiGem.storage` |
| `idb.db` | IndexedDB |
| `cookies.db` | The app's cookie jar |
| `files/` | The app's `data/` folder |
| `console.log` | The console (rotated to `console.log.1` at 1 MB) |
| `net-cache/`, `thumbs/` | Caches |

## 11. Add a permission

To remind the user once a day, declare `notifications`:

```json
"permissions": { "notifications": true }
```

```js
if (await AG.permissions.request('notifications') === 'granted') {
  await AG.notify.schedule({ id: 'daily', title: 'Tally', body: 'Time to count', at: '20:00', repeat: 'daily' });
}
```

Saving the manifest reloads the app; `request()` shows the native prompt. See [notifications.md](notifications.md).

## Rules for good apps

- [ ] **Offline first.** Don't load CDNs or web fonts unless the app truly needs the network and declares it. Bundle small libraries into the folder.
- [ ] **Theme colors only.** Use the UI kit or the `--ag-*` variables for every color; test in light and dark. Never hard-code white or black backgrounds.
- [ ] **Native UI.** Use `AntiGem.ui` for dialogs, sheets, pickers and toasts, and `AntiGem.device.haptic` on important taps.
- [ ] **Handle back.** Register `AntiGem.onBack` to close in-page panels and go back to the first tab before the app closes.
- [ ] **Fewest permissions.** Declare only what you use, the narrowest hosts and folders, and handle `NotAllowedError` with a friendly message.
- [ ] **No errors.** Test with the console or `console.log`, and fix every error before calling the app done.
- [ ] **Stable id, growing version.** Never change `id`; bump `version` with every change you ship.
- [ ] **Don't include `/_antigem/sdk.js`** yourself, and use relative paths for your own files.

## The dev loop with the Mini App Developer MCP

With the **Mini App Developer** preset added to an agent's MCP settings (or the `/mini_app` slash command), the agent builds and tests apps on the phone without you copying files:

1. Read the guide (`miniapp_guide` serves these pages).
2. Create the app (`miniapp_create`) and write its files (`miniapp_write_files`).
3. Open it on the phone (`miniapp_open`); hot reload picks up later writes, `miniapp_reload` forces one.
4. Check it: read the console (`miniapp_console`), run JavaScript in the page (`miniapp_eval`), look at it (`miniapp_screenshot`).
5. Fix and repeat until the console is clean.
6. Check the manifest and files (`miniapp_validate`), then sign (`miniapp_sign`) and install (`miniapp_install`) a package to test it as users get it. Installing opens AntiGem's install screen on the phone, and you confirm it there.

The tools and their parameters are in [developer-mcp.md](developer-mcp.md). Signing uses the phone's developer key; the model never sees the key or its password ([signing-and-distribution.md](signing-and-distribution.md#back-up-the-key)). A package can't take the id of a development app that is still in `~/mini-apps`, so to install a signed copy on the phone you develop on, use another id or remove the development folder first.

## Next

- [manifest.md](manifest.md): every manifest field.
- [permissions.md](permissions.md): what each permission allows.
- [api-reference.md](api-reference.md): the whole `AntiGem` API.
- [signing-and-distribution.md](signing-and-distribution.md): ship the app to other phones.
- The sample apps in `~/mini-apps` (Habits, Pocket Notes, Focus, Gallery, Sky, Explorer, Dev Toolbox, Showcase) show every feature in working code.
