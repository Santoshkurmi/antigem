# UI

A mini app looks and behaves like a native Android app: each page is its own native screen with system back and
slide transitions, dialogs and sheets are real Android dialogs, the status bar and Recents card take the app's color,
and the page follows AntiGem's theme in light and dark. This page covers the optional UI kit (`/_antigem/ui.css`),
theme colors, the status bar and splash, native dialogs, the media viewer, pages and the back button, and the small
device helpers (haptics, clipboard, share, links, camera).

## What every page gets

The runtime adds, before your own code:

- the SDK script (`window.AntiGem`) at the top of `<head>`,
- a viewport tag when the page has none (`width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no,
  viewport-fit=cover`); pinch zoom of the page is off,
- a tiny base style: no tap highlight, no text auto-sizing, no overscroll glow, no long-press callout or image dragging,
- the theme colors as CSS variables (see [Theme and dark mode](#theme-and-dark-mode)).

Everything else is up to you: plain CSS works, or use the UI kit below.

## The UI kit

```html
<link rel="stylesheet" href="/_antigem/ui.css">
```

`ui.css` is a small Material 3 style sheet built on the theme variables, so it follows the user's AntiGem theme in
light and dark with no work. It also sets the page font, background and text color, makes text unselectable except in
inputs and `.ag-selectable`, and honors `prefers-reduced-motion`.

```html
<div class="ag-page">
  <header class="ag-appbar">
    <h1 class="ag-appbar-title">Notes</h1>
    <div class="ag-appbar-actions"><button class="ag-btn ag-btn-icon" aria-label="Search">⌕</button></div>
  </header>
  <main class="ag-content">
    <ul class="ag-list">
      <li><button class="ag-item">
        <span class="ag-avatar">G</span>
        <span class="ag-item-body"><span class="ag-item-title">Groceries</span><span class="ag-item-sub">Milk, eggs</span></span>
      </button></li>
    </ul>
  </main>
  <button class="ag-fab" aria-label="New">+</button>
  <nav class="ag-navbar">
    <button class="ag-navbar-item is-active"><span class="ag-navbar-icon">H</span><span class="ag-navbar-label">Home</span></button>
    <button class="ag-navbar-item"><span class="ag-navbar-icon">S</span><span class="ag-navbar-label">Settings</span></button>
  </nav>
</div>
```

### Classes

| Group | Classes | Notes |
|---|---|---|
| Page | `ag-page`, `ag-content` | Full-height column; `ag-content` is the padded, scrolling body. With an `ag-navbar` or `ag-fab` directly inside `ag-page`, the content gets extra bottom padding |
| App bar | `ag-appbar`, `ag-appbar-title` (or an `h1` inside the bar), `ag-appbar-sub`, `ag-appbar-actions` | Sticky. Tints itself while the page scrolls (where scroll timelines are supported); add `is-scrolled` to tint it yourself. `ag-large`: a big title under the actions that shrinks while scrolling. An `ag-btn-icon` as first child lines up as a back/menu button |
| Layout | `ag-section` (section heading), `ag-divider`, `ag-row` (horizontal, wraps), `ag-stack` (vertical), `ag-grow` (fills the row) | |
| Text | `ag-display`, `ag-headline`, `ag-title`, `ag-title-m`, `ag-body-s` (same as `ag-text-sm`), `ag-caption`, `ag-muted`, `ag-mono`, `ag-selectable` | |
| Cards | `ag-card`, `ag-card-filled`, `ag-card-outlined`, `ag-card-elevated`, `ag-card-primary`, `ag-card-title`, `ag-card-sub`, `ag-card-actions` | A `button.ag-card` or `a.ag-card` gets a press effect |
| Lists | `ag-list` (`ag-plain`, `ag-divided`), `ag-item`, `ag-item-lead`, `ag-avatar` (an `img` inside fills it), `ag-item-body`, `ag-item-title`, `ag-item-sub`, `ag-item-trail` | An `ag-item` that is a `button`, `a` or `label` gets a press effect |
| Buttons | `ag-btn` (filled), `ag-btn-tonal`, `ag-btn-outlined`, `ag-btn-text`, `ag-btn-danger`, `ag-btn-sm`, `ag-btn-block`, `ag-btn-icon` (round; combine with `ag-btn-tonal` or `ag-btn-filled`) | `disabled` or `aria-disabled="true"` dims the button |
| FAB | `ag-fab`, `ag-fab-extended` | Fixed bottom-right; moves above an `ag-navbar` |
| Chips and badges | `ag-chips` (container), `ag-chip` (selected: `is-selected`, `aria-pressed="true"` or `aria-selected="true"`), `ag-badge`, `ag-badge-success`, `ag-badge-warning`, `ag-badge-neutral`, `ag-badge-primary` | Plain `ag-badge` is the error color |
| Text fields | `ag-field` > `ag-input` / `ag-textarea` + `ag-label`, `ag-helper` | Floating label: give the input `placeholder=" "` and put the `label` after it |
| Controls | `input.ag-switch`, `input.ag-checkbox` (`ag-round` for a round one), `ag-segmented` (children selected with `is-selected`, `aria-pressed` or `aria-selected`) | Use on `<input type="checkbox">` |
| Bottom navigation | `ag-navbar` > `ag-navbar-item` (`is-active` or `aria-selected="true"`) > `ag-navbar-icon` + `ag-navbar-label` | Fixed at the bottom |
| In-page sheet | `ag-scrim` + `ag-sheet`, toggle `is-open` on both | For custom sheets; `AntiGem.ui.sheet` is the native one |
| Feedback | `ag-empty` (`ag-empty-icon`, `ag-empty-title`, `p`), `ag-progress` > `ag-progress-bar` (`style="--value: 40%"`), `ag-progress ag-indeterminate`, `ag-spinner` | |
| Motion | `ag-enter` (slide up and fade in), `ag-fade` | |
| Press effect | `ag-clickable` | Adds the press overlay to any element |

```html
<label class="ag-field">
  <input class="ag-input" id="name" placeholder=" ">
  <span class="ag-label">Name</span>
</label>
<span class="ag-helper">Shown on your profile</span>

<div class="ag-progress"><span class="ag-progress-bar" style="--value: 40%"></span></div>
```

Kit variables you can set: `--ag-accent` and `--ag-on-accent` (a brand color for buttons, switches, links; default the
theme's primary), `--ag-font`, `--ag-mono`, `--ag-ease`, `--ag-r-s`, `--ag-r-m`, `--ag-r-l`, `--ag-r-xl` (corner
radii), `--ag-shadow-1`, `--ag-shadow-3`.

```css
:root { --ag-accent: var(--ag-app-color); --ag-on-accent: #fff; }
```

## Theme and dark mode

The runtime puts AntiGem's current theme on every page and updates it live when the user changes theme or switches
between light and dark.

- **CSS variables** on `:root`: `--ag-` plus each theme token with `_` turned into `-`. The Material colors are
  `--ag-primary`, `--ag-on-primary`, `--ag-primary-container`, `--ag-on-primary-container`, the same four for
  `secondary` and `tertiary`, `--ag-background`, `--ag-on-background`, `--ag-surface`, `--ag-on-surface`,
  `--ag-surface-variant`, `--ag-on-surface-variant`, `--ag-surface-container-lowest`, `--ag-surface-container-low`,
  `--ag-surface-container`, `--ag-surface-container-high`, `--ag-surface-container-highest`, `--ag-outline`, `--ag-outline-variant`, `--ag-error`, `--ag-on-error`, `--ag-error-container`,
  `--ag-on-error-container`, `--ag-inverse-surface`, `--ag-inverse-on-surface`, `--ag-inverse-primary`. AntiGem's own
  tokens are there too, for example `--ag-success`, `--ag-warning`, `--ag-danger`, `--ag-info`,
  `--ag-success-container`, `--ag-card`, `--ag-border`, `--ag-code-bg`. Read `AntiGem.theme.colors` for the full list.
- `--ag-app-color`: the manifest's `theme_color`, or the theme's background when there is none.
- `html[data-theme="dark"]` or `html[data-theme="light"]`, and `color-scheme` on `:root`.
- `@media (prefers-color-scheme: dark)` matches AntiGem's mode, not just the system's.
- `AntiGem.theme`: `{dark, colors, appColor}`. Colors are `#rrggbb`, or `rgba(r, g, b, a)` when transparent.
- `AntiGem.on('theme', t => …)` fires after the variables changed, for anything drawn in JavaScript (canvas charts).

```css
body { background: var(--ag-background); color: var(--ag-on-background); }
.card { background: var(--ag-surface-container-low); border: 1px solid var(--ag-outline-variant); }
```

```js
function drawChart() { chart.setColors(AntiGem.theme.colors.primary, AntiGem.theme.colors.on_surface); }
AntiGem.on('theme', drawChart);
```

Never hard-code white or black backgrounds: use the variables so the app works in both modes.

## Status bar, theme color and splash

- The manifest's `theme_color` colors the splash screen, the Recents card and the default status bar. Without it,
  they use the theme's background. See [manifest](manifest.md).
- The status bar and navigation bar are opaque (the page is not drawn behind them), so the keyboard resizes the page
  natively. The navigation bar always uses the theme's background.
- `AntiGem.ui.statusBar({color})` changes the status bar of the current page's screen. `color` is a CSS color value
  (`#hex`, `rgb()`, `hsl()`, a color name, or `var(--name)` / `var(--name, fallback)`, read from the page's CSS
  variables); transparency is dropped. A value that isn't a color resets the bar to the default color. The icons
  switch between light and dark automatically. Call it without a color to go back to the default. A pushed page
  starts with the default color.

```js
AntiGem.ui.statusBar({ color: '#C62828' });

AntiGem.ui.statusBar({ color: 'var(--ag-primary)' });

AntiGem.ui.statusBar();   // back to theme_color / theme background
```

- **Splash:** when the app starts, a native splash (the `theme_color` background, the icon, the app name, and a "DEV"
  label for development apps) covers the first page until it first paints, at most 4 seconds. Pushed pages have no
  splash.

## Native dialogs

All dialogs are native, themed, and async. A string argument is the message. They reject with `NotSupportedError` in
the background page.

| Call | Options | Resolves |
|---|---|---|
| `AntiGem.ui.toast(text, {long?})` | `long`: show longer | nothing (no Promise) |
| `AntiGem.ui.alert(x)` | `title`, `message`, `ok` (default `'OK'`) | `undefined` when closed |
| `AntiGem.ui.confirm(x)` | `title`, `message`, `ok` (`'OK'`), `cancel` (`'Cancel'`), `destructive` (OK in the error color) | `true` / `false` (dismissing is `false`) |
| `AntiGem.ui.prompt(x)` | `title`, `message`, `value` (initial text), `placeholder`, `ok`, `cancel` | the text, or `null` when cancelled. One line; the keyboard's Done key confirms |
| `AntiGem.ui.sheet({title, items})` | `items: [{id, label, icon?, destructive?}]`; `icon` is short text, usually one emoji | the chosen `id`, or `null` |
| `AntiGem.ui.pickDate({value?, title?})` | `value: 'YYYY-MM-DD'` (default today) | `'YYYY-MM-DD'` or `null` |
| `AntiGem.ui.pickTime({value?})` | `value: 'HH:MM'` (default now) | `'HH:MM'` (24-hour) or `null`. The clock face follows the phone's 12/24-hour setting |

```js
if (await AntiGem.ui.confirm({ title: 'Delete note?', message: note.title, ok: 'Delete', destructive: true })) remove(note);

const name = await AntiGem.ui.prompt({ title: 'Rename', value: note.title, placeholder: 'Title' });
if (name !== null) rename(note, name);

const choice = await AntiGem.ui.sheet({
  title: 'Photo',
  items: [{ id: 'share', label: 'Share' }, { id: 'edit', label: 'Edit' }, { id: 'delete', label: 'Delete', destructive: true }],
});

const day = await AntiGem.ui.pickDate({ value: '2026-12-24', title: 'Due date' });
AntiGem.ui.toast('Saved');
```

The standard `alert()`, `confirm()` and `prompt()` also show native dialogs (titled with the app name) and block the
page's script like in a browser. Prefer `AntiGem.ui.*` in new code. If a second dialog opens while one is showing, the
newest is shown and the older one comes back when it closes. In a background task nobody can answer them: `alert()`
returns at once, `confirm()` returns `false` and `prompt()` returns `null`, with a warning in the console.

## Media viewer

`AntiGem.ui.viewMedia()` opens a native full-screen viewer like a phone gallery: swipe between items, pinch or
double-tap to zoom photos (HEIC too), videos in the system player with its controls, a share button, and close.

```js
const items = photos.map((p) => ({ src: p.path, title: p.name, type: p.isVideo ? 'video' : 'image' }));
const { index } = await AntiGem.ui.viewMedia({ items, index: 3 });   // resolves when the viewer closes
scrollToPhoto(index);

await AntiGem.ui.viewMedia(['data/a.jpg', 'https://example.com/b.jpg']);   // an array works too
```

| Option | Type | Default | Meaning |
|---|---|---|---|
| `items` | array, required | | Each item: a path or URL string, or `{src, type?, title?}`. Up to 5000 |
| `index` | number | `0` | The item shown first |
| item `src` | string | | `data/…`, `app:<id>/…`, `~/…` or `/…` (checked like [AntiGem.fs](files.md)), an `http(s)` URL (checked like the [network](network.md); a hostname that points to this phone needs the `localhost` permission), or any other string = a file of the app itself, relative to its folder (for an installed app, only if it matches the signature) |
| item `type` | `'image'` \| `'video'` | from the extension | Video extensions: `mp4`, `m4v`, `webm`, `mov`, `3gp`, `mkv` |
| item `title` | string | the file name | Shown at the top |

Resolves `{index}`: the item on screen when the user closed it. Rejects with `TypeError` when `items` is missing or
empty, with `NotAllowedError` when an item's path or host isn't allowed, and with `SecurityError` when a file of an
installed app doesn't match its signature.

## Pages and navigation

Every page is its own native screen (an Android activity) with its own WebView, JavaScript context and
`sessionStorage`. The first page is the manifest's `entry`.

```js
// open item.html as a new screen that slides in
AntiGem.nav.push('item.html', { id: 7, tags: ['a', 'b'] });

// in item.html
const id = Number(AntiGem.page.params.id);            // params are strings
const tags = JSON.parse(AntiGem.page.params.tags);    // objects were JSON-encoded
document.querySelector('#close').onclick = () => AntiGem.nav.back();
```

| Call | Meaning |
|---|---|
| `AntiGem.nav.push(path, params?)` | Opens `path` (relative to the app folder; a leading `/` or `./` is removed; empty = the entry page) as a new screen. `params` are added to the query string: strings and numbers as text, objects as JSON |
| `AntiGem.nav.back()` | Closes this page. On the first page it sends the app to the background (like Home; it stays in Recents) |
| `AntiGem.nav.close()` | Closes the whole app, all its pages, and removes it from Recents |

`AntiGem.page` describes the current page: `{id, path, params, root, background}`. `path` has no query string;
`params` holds the query string values as strings; `root` is `true` only on the first page.

- Pages share `localStorage`, `AntiGem.storage`, IndexedDB and files. A page that comes back on screen after a
  `pause` (back from a pushed page, another app, Home, the lock screen) gets a `resume` event: refresh what another
  page may have changed there. The first open sends no `resume`: that is the page's own load.
- Inside one page, normal navigation also works (`<a href="other.html">`, `location.href`): it loads in the same
  screen, and back goes through that history first.
- A link to another site that the user taps opens in the system browser (with a confirmation when the app has no
  `network` permission). Navigating to another site by script is blocked.
- `nav.push` doesn't work in the background page.

## Back button

The system back gesture or button is handled in this order:

1. a full-screen video exits full screen;
2. the page's `AntiGem.onBack` handlers, newest first, until one returns `true`;
3. the page's own history (if it navigated inside the same screen);
4. a pushed page closes; the first page sends the app to the background.

```js
const remove = AntiGem.onBack(() => {
  if (panel.classList.contains('is-open')) { closePanel(); return true; }   // handled
  if (currentTab !== 'home') { showTab('home'); return true; }
  return false;                                                             // let the app go back
});
// later: remove();
```

The handler must return `true` synchronously. An `async` function returns a Promise, which does not count as handled.

## Device helpers

| Call | Permission | Meaning |
|---|---|---|
| `AntiGem.device.haptic(kind?)` | none | A short vibration: `'light'` (default), `'medium'`, `'heavy'`, `'selection'`, `'success'`, `'warning'`, `'error'`. On Android 11+ `success` and `warning`/`error` use the system's confirm and reject patterns |
| `AntiGem.device.copy(text)` | none | Puts text on the clipboard |
| `AntiGem.device.paste()` | `clipboard` | Reads the clipboard as text (`''` when empty) |
| `AntiGem.device.share({title?, text?, url?})` | none | Android's share sheet with text (`text` and `url` joined by a new line; `title` as subject) |
| `AntiGem.device.openUrl(url)` | none | Opens an `http`, `https`, `mailto` or `tel` link in another app. Apps without a granted `network` permission ask the user first |
| `AntiGem.device.info()` | none | `{manufacturer, model, android, sdk, dark, locale}`: `android` is the version name (`'14'`), `sdk` the API level (`34`), `dark` AntiGem's mode, `locale` a tag like `'en-US'` |
| `AntiGem.device.status()` | none | Network and battery now. See [intents](intents.md#device-status) |

```js
saveButton.onclick = async () => { await save(); AntiGem.device.haptic('success'); AntiGem.ui.toast('Saved'); };
await AntiGem.device.copy(link);
await AntiGem.device.share({ title: 'My list', text: list.join('\n') });
```

To share a file rather than text, use `AntiGem.intent.send({action: 'share', path})`: the user can pick another mini
app or "Other apps…" (Android apps). See [intents](intents.md).

### Camera and microphone

`navigator.mediaDevices.getUserMedia({video: true})` and `({audio: true})` work with the `camera` and `microphone`
permissions. If the permission isn't granted yet, the native prompt is shown; AntiGem then also needs Android's own
camera or microphone permission. A request for anything else is denied.

`<input type="file" accept="image/*" capture>` (or `accept="video/*"`) opens the phone's camera app when the app
declares `camera` and the user allows it (plus Android's camera permission). The photo or video is given to the
input as a file. Without the permission the normal file picker opens and the console logs a warning
([files](files.md#picking-files)).

### Not available

`navigator.geolocation` is always denied.

### Full screen

A video's full-screen button (or `element.requestFullscreen()`) shows the element full screen with the system bars
hidden; back leaves full screen.

## Limits and errors

| Error | When |
|---|---|
| `NotSupportedError` | Dialogs, pickers, `viewMedia`, `nav.push`, `device.share` and `device.openUrl` in the background page |
| `NotAllowedError` | `device.paste()` without `clipboard`; `openUrl` the user declined; a `viewMedia` item that isn't allowed |
| `TypeError` | `openUrl` with another scheme; `viewMedia` without items |
| `SecurityError` | A `viewMedia` item from an installed app's own files that doesn't match its signature |

- `toast`, `statusBar`, `haptic` and `nav.*` return nothing; their errors only reach the console log.
- `onBack` handlers and `resume`/`pause` events are per page: register them in every page that needs them.
