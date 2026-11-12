# Files

`AntiGem.fs` reads and writes real files natively. Every app has a private `data/` folder it may always use. Folders
outside it (shared storage like `/sdcard/Download`, the AntiGem home `~/`) are reachable only when the manifest
declares them and the user grants them, or when the user picks a folder at runtime. Every path is checked natively,
after symlinks are resolved, before anything is read or written. Files are served to `<img>`, `<video>` and `fetch`
through `AntiGem.fs.url()`, with byte ranges and native thumbnails.

## Paths

Every `AntiGem.fs` method takes one of these forms. Anything else (for example a bare `notes.txt`) rejects with
`TypeError`.

| Form | Means | Access |
|---|---|---|
| `data` or `data/…` | The app's private folder (`~/.antigem/mini-apps/<app id>/files/`) | Always read and write |
| `app:<id>/…` | The `data/` folder of another app in the same `shared_id` group | Read and write, no prompt. See [shared data](shared-data.md) |
| `~` or `~/…` | AntiGem's home folder | Only inside granted folders |
| `/…` | An absolute path, e.g. `/sdcard/Download/x.pdf` | Only inside granted folders |

Paths are made canonical before the check: `..` and every symlink are resolved to the real location. `/sdcard/…` is
reported back as its real path, `/storage/emulated/0/…`.

The same forms (`app:<id>/…` included) work for `AntiGem.ui.viewMedia` items and for notification images (`icon`,
`smallIcon`, `image`), with the same checks.

Never reachable, whatever is granted:

- other apps' data (`~/.antigem/`, except the `data/` folders of the app's shared group) and every app's code
  (`~/mini-apps/`),
- hidden folders directly in home (`~/.ssh`, `~/.codex`, …),
- anything outside AntiGem's home and the phone's shared storage (`/storage/emulated/0`), such as system folders or a
  removable SD card.

## Granted folders

Folders come from the manifest's `permissions.storage` (see [permissions](permissions.md) and
[manifest](manifest.md)):

```json
"permissions": {
  "storage": {
    "folders": [
      { "path": "/sdcard/DCIM", "access": "read" },
      { "path": "/sdcard/Pictures", "access": "readwrite" }
    ],
    "pick": "read"
  }
}
```

Each folder is its own permission (`storage:/sdcard/DCIM`), granted or denied by the user. Its access comes from the
manifest:

| Access | Allows |
|---|---|
| `read` | `read`, `readJSON`, `list`, `stat`, `exists`, `url` |
| `write` | `write`, `writeJSON`, `mkdir`, `delete`, `move`, `stat`, `exists` (no reading) |
| `readwrite` | Everything |

A granted folder also covers everything below it. `AntiGem.permissions.request('storage')` asks for every declared
folder that isn't granted yet; `AntiGem.permissions.query('storage:/sdcard/DCIM')` checks one. `query('storage')` is
`granted` only when every declared folder is granted, `prompt` while any is undecided, and `denied` otherwise.

## Reading and writing

| Method | Returns | Notes |
|---|---|---|
| `read(path, {encoding?})` | `Promise<string>` | `encoding: 'utf8'` (default) or `'base64'` for binary files. Files over 64 MB reject with `QuotaExceededError` |
| `readJSON(path)` | `Promise<any>` | `read` + `JSON.parse` (invalid JSON throws `SyntaxError`) |
| `write(path, data, {append?})` | `Promise<void>` | `data`: string (saved as UTF-8), `Blob`, `ArrayBuffer`, typed array or `DataView`; anything else is converted with `String()`. Creates missing parent folders. Without `append`, the file is replaced atomically (written to a temporary file, then renamed) |
| `writeJSON(path, value)` | `Promise<void>` | `JSON.stringify(value, null, 2)` |

```js
await AntiGem.fs.write('data/notes.txt', 'Hello');
await AntiGem.fs.write('data/log.txt', `${new Date().toISOString()} started\n`, { append: true });
const text = await AntiGem.fs.read('data/notes.txt');

await AntiGem.fs.writeJSON('data/state.json', { count: 3 });
const state = await AntiGem.fs.readJSON('data/state.json').catch(() => ({ count: 0 }));

// binary
const blob = await (await fetch('https://example.com/a.png')).blob();
await AntiGem.fs.write('data/images/a.png', blob);
const b64 = await AntiGem.fs.read('data/images/a.png', { encoding: 'base64' });
```

Whole-file reads and writes travel through the bridge as text or base64. For big files (videos, large photos) use
`AntiGem.fs.url()` to stream them instead of reading them into JavaScript.

## Folders and file info

| Method | Returns | Notes |
|---|---|---|
| `list(path)` | `Promise<entry[]>` | Folders first, then files, by name (case-insensitive). `NotFoundError` if `path` is not a folder |
| `stat(path)` | `Promise<entry \| null>` | `null` when nothing is there |
| `exists(path)` | `Promise<boolean>` | |
| `mkdir(path)` | `Promise<void>` | Creates missing parents; no error if it exists |
| `delete(path, {recursive?})` | `Promise<void>` | A non-empty folder needs `recursive: true` (`InvalidStateError` otherwise) |
| `move(from, to)` | `Promise<void>` | Renames or moves; creates `to`'s parent folders. Between different storages it copies, then deletes |

An entry is `{name, path, type: 'file' | 'dir', size, mtime}`. `size` is `0` for folders; `mtime` is epoch ms. The
`path` of a listed entry is built from the path you passed (`list('data/')` gives `data/notes.txt`), so it can be
passed straight back to other methods.

`stat` and `exists` work with any access (`read` or `write`) to the folder. A path outside every allowed folder rejects
with `NotAllowedError` rather than returning `false`.

```js
const entries = await AntiGem.fs.list('/sdcard/Download');
for (const e of entries) console.log(e.type, e.name, e.size);
await AntiGem.fs.mkdir('data/exports');
await AntiGem.fs.move('data/draft.txt', 'data/exports/final.txt');
await AntiGem.fs.delete('data/cache', { recursive: true });
```

Root folders can't be deleted or moved as a whole: the app's own `data` folder, a shared-group app's `data` folder, a
granted folder itself, home and `/sdcard` (`NotAllowedError`).

### Symlinks

- A symlink is followed to its real target before any check. A link inside an allowed folder that points outside it is
  refused like any other outside path.
- In `list()`, a link whose target the app may use is shown as the target (`type`, `size`, `mtime` of the target)
  with `link: true`. A link whose target is not allowed is shown as
  `{name, path, type: 'link', size: 0, mtime: 0, blocked: true}`; nothing about its target is revealed.
- `delete(path, {recursive: true})` removes links inside the folder, never what they point to.
- `move` across storages skips symlinks when it copies.

## Showing files: AntiGem.fs.url()

`AntiGem.fs.url(path, {thumb?})` returns a same-origin URL (synchronously) that streams the file natively. Use it in
`<img>`, `<video>`, `<audio>`, CSS, or `fetch()`.

```js
img.src = AntiGem.fs.url('data/photo.jpg');
video.src = AntiGem.fs.url('/sdcard/DCIM/Camera/clip.mp4');   // seeking works (Range requests)
grid.append(...photos.map((p) => Object.assign(new Image(), { src: AntiGem.fs.url(p.path, { thumb: 320 }), loading: 'lazy' })));

const blob = await (await fetch(AntiGem.fs.url('data/report.pdf'))).blob();
```

| Option | Type | Default | Meaning |
|---|---|---|---|
| `thumb` | number (px) | none | A JPEG thumbnail at most this many pixels on its long side, made natively for photos and video frames. Clamped to 32–1600. Ignored for other file types |

- The permission check happens when the URL is loaded. A refused path answers `403`, a missing file `404`, and a
  thumbnail or HEIC file that can't be decoded `415`.
- `Range` requests are answered with `206 Partial Content`, so videos seek and audio scrubs.
- The `Content-Type` comes from the file extension.
- HEIC/HEIF photos are always served as JPEG (the WebView can't show HEIC), capped at 4096 px.
- Thumbnails are cached in the app's data folder (`thumbs/`), keyed by file, size, modification time and thumbnail
  size. They are cheap to request again.
- Workers have `url(path, {thumb?})` too.

## Picking files

### AntiGem.fs.pick()

Opens Android's file picker. The chosen files are **copied** into `data/imports/` (the app never gets the original);
a name that already exists gets ` (1)`, ` (2)`… added.

```js
const files = await AntiGem.fs.pick({ accept: 'image/*', multiple: true });
// [{name: 'IMG_1.jpg', path: 'data/imports/IMG_1.jpg', size: 204800, type: 'image/jpeg'}]
for (const f of files) addPhoto(AntiGem.fs.url(f.path));
```

| Option | Type | Default | Meaning |
|---|---|---|---|
| `accept` | string | `'*/*'` | One MIME type pattern for the picker, e.g. `'image/*'`, `'application/pdf'` |
| `multiple` | boolean | `false` | Allow several files |

Cancelling resolves with `[]`. A file that can't be read is left out. No permission is needed.

### `<input type="file">`

A file input opens the same native picker. The first type in `accept` is used (`.pdf` style extensions are turned
into MIME types) and `multiple` works. The page gets normal `File` objects; nothing is copied into `data/`.

With the `capture` attribute and `accept="image/*"` (or `video/*`), the input opens the camera app instead, when the
app declares the `camera` permission and the user allows it (Android's camera permission for AntiGem is needed too).
The photo or video comes back as a file in the input. Without the permission, the normal file picker opens and the
console logs a warning.

```html
<input type="file" accept="image/*" capture>
```

### AntiGem.fs.pickFolder()

The user picks any folder in AntiGem's own file explorer (phone storage or the AntiGem home), and the app gets the
manifest's `pick` access to it, kept until the app gives it back. The manifest must allow it with
`"storage": {"pick": "read" | "write" | "readwrite"}` (`true` means `read`).

```js
const picked = await AntiGem.fs.pickFolder();   // {path: '/storage/emulated/0/Music', access: 'read'} or null
if (picked) render(await AntiGem.fs.list(picked.path));
```

- Resolves `null` when the user cancels.
- Picking home, the root of shared storage, or a folder above them asks the user to type a confirmation phrase under a
  warning first.
- Rejects with `NotAllowedError` when the manifest has no `pick`, when the folder is protected, or when the user picks a
  folder on a PC/server side of the explorer.
- A picked folder keeps the access it was picked with, but never more than the manifest's current `pick` allows: an
  update that changes `pick` from `read` to `write` doesn't make folders picked for reading writable. When the two
  have nothing in common (`read` and `write`), the folder no longer counts. Removing `pick` from the manifest disables
  all picked folders.

### AntiGem.fs.folders() and forgetFolder()

`folders()` lists every folder the app may use right now:

| Field | Meaning |
|---|---|
| `path` | `'data'`, `'app:<id>'`, or a real absolute path |
| `access` | `'read'`, `'write'` or `'readwrite'` |
| `source` | `'own'` (its `data/`), `'shared'` (a shared-group app's `data/`), `'manifest'` (a granted manifest folder) or `'picked'` (`pickFolder`) |
| `app` | For `'shared'` only: `{id, name}` of the other app |

Manifest folders appear only when granted, with their real path (`/storage/emulated/0/DCIM`, not `/sdcard/DCIM`).
`"storage": true` (all files) appears as two entries: home and `/storage/emulated/0`.

`forgetFolder(path?)` gives back one picked folder (pass the `path` that `pickFolder()` or `folders()` returned), or
all picked folders when called without a path. The user can also remove them in App info.

```js
for (const f of await AntiGem.fs.folders()) {
  if (f.source === 'picked') await AntiGem.fs.forgetFolder(f.path);
}
```

## Files sent by other apps

When another mini app sends a file with `AntiGem.intent.send({path})`, the receiving app gets a **one-day read grant
for that one file**: `read`, `stat`, `exists` and `url` work on the exact `path` from the `intent` event, nothing else
in its folder, and no writes. Copy it into `data/` if you want to keep it:

```js
AntiGem.on('intent', async (it) => {
  if (!it.path) return;
  const blob = await (await fetch(AntiGem.fs.url(it.path))).blob();
  await AntiGem.fs.write(`data/inbox/${it.path.split('/').pop()}`, blob);
});
```

Files shared from Android's share sheet are copied into `data/inbox/` before the app sees them, so they are its own.
See [intents](intents.md).

## Limits and errors

| Error | When |
|---|---|
| `TypeError` | The path doesn't start with `data`, `app:<id>`, `~` or `/`; an `app:` id is not a valid app id |
| `NotAllowedError` | The path is protected, outside every allowed folder, or the folder's access doesn't allow the operation ("the app may only read there"); deleting or moving a root folder; `pickFolder` not allowed by the manifest |
| `NotFoundError` | `read` of something that isn't a file; `list` of something that isn't a folder; `delete`/`move` of something missing |
| `InvalidStateError` | `write` to a folder path; `delete` of a non-empty folder without `recursive`; a delete the system refused |
| `QuotaExceededError` | `read` of a file over 64 MB |
| `NotSupportedError` | `pick` or `pickFolder` in the background page |

- `pick`, `pickFolder` and `forgetFolder` are not available in workers.
- Write access to a folder never implies read access: an app with `write` can create and replace files there but
  can't read or list them.
- There is no quota on `data/`; it is limited by the phone's free space. "Clear data" and uninstall delete it.
