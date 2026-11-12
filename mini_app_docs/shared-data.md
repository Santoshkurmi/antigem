# Shared data (`shared_id`)

Apps of one developer can share files, like Android apps with the same `sharedUserId`. Give each app the same `"shared_id"` in its manifest and each can read and write the others' `data/` folder through `app:<id>/…` paths, without a permission prompt. Sharing is limited on purpose: installed apps share only with installed apps signed with the same key, development apps only with development apps, and only the `data/` folder is shared.

## Declaring it

```json
{ "id": "com.you.notes", "name": "Notes", "version": "1.0.0", "shared_id": "com.you.suite" }
```

```json
{ "id": "com.you.tasks", "name": "Tasks", "version": "1.0.0", "shared_id": "com.you.suite" }
```

`shared_id` follows the same rules as `id`: 1–100 letters, digits, `.`, `_` or `-`, starting with a letter or digit. A bad value makes the whole manifest invalid. Use a reverse-domain name you own, such as `com.you.suite`.

## Who shares with whom

Two apps share data when all of this is true:

| Rule | |
|---|---|
| Same `shared_id` | Exactly the same string. |
| Both installed, same key | Both installed from signed packages, signed with the same developer key (same fingerprint). |
| …or both development apps | Both folders in `~/mini-apps`. A development app never reaches an installed app's data, nor the other way round. |
| Neither is modified | An installed app whose files no longer match its signature is cut off from the group. |

AntiGem enforces the key rule when installing: a package whose `shared_id` group already holds an installed app signed by another key is not installed. The install screen says:

> It shares data with the apps of "com.you.suite", and Notes there is signed by Someone Else (key AB12:CD34:EF56:7890), not by this package's developer. Only apps signed with the same key can share data.

This applies to updates too: an update can't move an app into a group signed by someone else.

## What is shared

Only each app's `data/` folder (on disk: `~/.antigem/mini-apps/<id>/files/`). Everything else stays per app:

| Shared | Not shared |
|---|---|
| `data/` files, read and write | `AntiGem.storage`, `localStorage`, `sessionStorage` |
| | IndexedDB |
| | Cookies |
| | Permissions, picked folders, reminders, background tasks, notifications |

There is no read-only sharing: every app of the group can read, change and delete the files in the others' `data/` folders.

## Paths

Each app still uses `data/…` for its own folder. The other apps' folders are `app:<id>/…`:

```js
// in com.you.tasks
await AntiGem.fs.write('app:com.you.notes/inbox/todo.txt', 'Buy milk');
const items = await AntiGem.fs.list('app:com.you.notes/inbox');
const text = await AntiGem.fs.read('app:com.you.notes/inbox/todo.txt');
```

- `app:<own id>/…` is the same as `data/…`.
- The other app must be present on the phone (installed, or a development folder). Until then, and for any app outside the group, the path is refused with `NotAllowedError` (`app:<id>/… is protected`).
- The other app is checked on disk on every access. If it is deleted, uninstalled, or its manifest no longer has the same `id` and `shared_id`, its folder can't be reached from that moment on.
- The root of another app's `data/` folder can't be deleted or moved as a whole; its contents can.
- `AntiGem.fs.url('app:<id>/photo.jpg')` works like any other readable path (for `<img>`, `<video>`, `fetch`).
- `app:<id>/…` paths also work in `AntiGem.ui.viewMedia` items and in notification images (`icon`, `smallIcon`, `image`).

## Finding the group

`AntiGem.fs.folders()` lists the group's folders with `source: "shared"`:

```js
const shared = (await AntiGem.fs.folders()).filter(f => f.source === 'shared');
// [{ path: 'app:com.you.notes', access: 'readwrite', source: 'shared', app: { id: 'com.you.notes', name: 'Notes' } }]
```

The list is current: an app that was just installed or deleted appears or disappears on the next call.

## Example: a suite with a common inbox

Notes collects items, Tasks drops new ones into Notes' inbox.

```js
// Tasks: send a task to Notes, if Notes is installed
async function sendToNotes(title) {
  const notes = (await AntiGem.fs.folders()).find(f => f.source === 'shared' && f.app.id === 'com.you.notes');
  if (!notes) return AntiGem.ui.toast('Install Notes to send tasks there');
  await AntiGem.fs.writeJSON(`app:com.you.notes/inbox/${Date.now()}.json`, { title, from: 'tasks' });
}
```

```js
// Notes: read its own inbox (the same folder, seen from inside)
async function readInbox() {
  if (!(await AntiGem.fs.exists('data/inbox'))) return;   // list() fails on a missing folder
  const files = await AntiGem.fs.list('data/inbox');
  for (const f of files.filter(f => f.type === 'file')) {
    const item = await AntiGem.fs.readJSON(f.path);
    addNote(item.title);
    await AntiGem.fs.delete(f.path);
  }
}
```

Design tips:

- Agree on a simple folder layout and file format, and version it (`inbox/v1/…`).
- Each app should own the files it writes; avoid two apps rewriting the same file at the same time.
- Keep shared things in the owner's folder, not spread over every app, so uninstalling one app removes exactly its part.

## Where users see it

- **Install screen:** "Shares its data with Notes (same developer): each can read and change the other's files."
- **App info → Storage:** `Shared data ("com.you.suite"): Notes, same developer: each can read and change the other's files` for installed apps, `…, development apps` for development apps, or "no other app in this group yet".

## Uninstalling and clearing data

- Uninstalling an app deletes its data, including its `data/` folder, so the other apps of the group lose those files too.
- **Clear data** on one app empties that app's `data/` folder for the whole group in the same way.
- The other apps' own folders are not touched.

## Development and release

- While you develop, give the development folders the same `shared_id`: they share with each other.
- Sign every app of the suite with the same developer key ([signing-and-distribution.md](signing-and-distribution.md)). Installed copies then share with each other, never with the development folders.
- Keep the key safe. A user who has your apps installed can only add another app to the group if it is signed with the same key.
