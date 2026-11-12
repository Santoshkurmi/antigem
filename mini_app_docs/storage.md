# Storage

A mini app keeps its data natively, outside the WebView: `localStorage`, `sessionStorage` and `AntiGem.storage` live
in a native key-value store, IndexedDB is stored in SQLite, cookies are in the app's own persistent jar, and files go
in the app's private `data/` folder (see [files](files.md)). None of this needs a permission. Each app's data is
separate from every other app's, survives app updates and AntiGem restarts, and is removed by "Clear data" or
uninstall.

## Where data lives

All of an app's data is in `~/.antigem/mini-apps/<app id>/`, never in the app folder itself:

| File / folder | Holds |
|---|---|
| `kv.db` | `localStorage` and `AntiGem.storage` (SQLite) |
| `idb.db` | Every IndexedDB database of the app (SQLite) |
| `cookies.db` | The cookie jar |
| `files/` | The app's `data/` folder |
| `net-cache/` | HTTP cache of the native network layer (up to 50 MB) |
| `thumbs/` | Cached thumbnails from `AntiGem.fs.url(path, {thumb})` |
| `console.log` | Console output (see [troubleshooting](troubleshooting.md)) |

Reminders and background tasks are kept by AntiGem itself, not in this folder.

## localStorage and sessionStorage

Both are the standard API (`getItem`, `setItem`, `removeItem`, `clear`, `key`, `length`, and property access like
`localStorage.theme = 'dark'`), backed by native storage.

- **Reads are synchronous and instant.** Each page receives a copy of the whole store when it loads.
- **Writes are write-behind.** `setItem` updates the page's copy at once. The writes of one JavaScript task are sent
  to the native side together, applied to its memory copy, and written to SQLite on a background thread right after.
- **`localStorage` is shared by every page of the app**, including pushed pages and the background page. When one page
  changes it, the other open pages update their copy and get the standard `storage` event on `window`.
- **`sessionStorage` belongs to one page.** It survives reloads of that page and is gone when the page closes. Pushed
  pages each have their own.
- Not available in Web Workers (no browser has them there).

```js
localStorage.setItem('counter', String(Number(localStorage.getItem('counter') ?? 0) + 1));

// another page of the app changed it
window.addEventListener('storage', (e) => {
  if (e.key === 'counter') render(e.newValue);
});
```

## AntiGem.storage

A small key-value API on the same native store, in its own namespace (its keys never clash with `localStorage`).
Values are saved as JSON, so objects and arrays come back as objects and arrays.

| Method | Returns | Meaning |
|---|---|---|
| `get(key, fallback?)` | value | Parsed value, or `fallback` when the key is missing. A stored value that isn't valid JSON comes back as a string |
| `set(key, value)` | `undefined` | Saves `JSON.stringify(value)`; `undefined` is saved as `null` |
| `remove(key)` | `undefined` | Deletes the key |
| `keys()` | `string[]` | Keys of `AntiGem.storage` only |
| `clear()` | `undefined` | Deletes all `AntiGem.storage` keys (not `localStorage`) |

```js
const settings = AntiGem.storage.get('settings', { units: 'metric', alerts: true });
settings.alerts = false;
AntiGem.storage.set('settings', settings);

// another page of the app changed AntiGem.storage
AntiGem.on('storage', ({ key }) => { if (key === 'settings' || key === null) reload(); });
```

`AntiGem.storage` changes made by other pages fire `AntiGem.on('storage', …)` with `{key}` (`key` is `null` after
`clear()`), not the `window` `storage` event. Like `localStorage`, it is synchronous, write-behind, and not available
in workers.

## IndexedDB

`indexedDB` and the `IDB*` classes are replaced with an implementation stored in native SQLite. Libraries built on the
standard API (for example `idb`) work. Every database of the app lives in `idb.db`. Pages, the background page and
workers of the same app all see the same databases.

```js
const db = await new Promise((resolve, reject) => {
  const req = indexedDB.open('todos', 1);
  req.onupgradeneeded = () => {
    const store = req.result.createObjectStore('todos', { keyPath: 'id', autoIncrement: true });
    store.createIndex('done', 'done'); // 0 or 1: booleans are not valid keys
  };
  req.onsuccess = () => resolve(req.result);
  req.onerror = () => reject(req.error);
});

const tx = db.transaction('todos', 'readwrite');
tx.objectStore('todos').add({ text: 'Buy milk', done: 0, created: Date.now() });
tx.oncomplete = () => console.log('saved');
```

### What is supported

- `indexedDB.open`, `deleteDatabase`, `databases()`, `cmp()`.
- Versioned upgrades with `upgradeneeded` (`oldVersion`, `newVersion`), `createObjectStore`, `deleteObjectStore`,
  `createIndex`, `deleteIndex`, renaming stores and indexes (setting `name` during an upgrade).
- Key paths (dotted and arrays), `autoIncrement` key generators, out-of-line keys, `unique` and `multiEntry` indexes.
  A new index is filled from the records already stored; a unique violation aborts the upgrade.
- `get`, `getKey`, `getAll(query, count)`, `getAllKeys(query, count)`, `count`, `put`, `add`, `delete`, `clear` on
  stores and indexes.
- Cursors: `openCursor`, `openKeyCursor`, the directions `next`, `nextunique`, `prev`, `prevunique`, `continue(key)`,
  `advance(n)`, `continuePrimaryKey`, `cursor.update`, `cursor.delete`.
- `IDBKeyRange` (`only`, `lowerBound`, `upperBound`, `bound`, `includes`).
- Transactions with `complete`, `error` and `abort` events, `abort()`, `commit()`, auto-commit, and error events that
  bubble request → transaction → database (`preventDefault()` keeps the transaction going).
- Code that awaits a request's result and then makes the next request in the same transaction (as with the `idb`
  library). As in browsers, awaiting anything else (a `fetch`, a timer) lets the transaction commit.

### Keys and values

- **Keys:** numbers, strings, `Date`, `ArrayBuffer` / typed arrays, and arrays of these. Booleans, `null` and objects
  are not keys (a `DataError`, as in browsers). Store flags as `0` / `1` if you want to index them.
- **Values:** `undefined`, `null`, booleans, numbers (including `NaN`, `Infinity`, `-0`), strings, `BigInt`, arrays,
  plain objects, `Date`, `RegExp`, `ArrayBuffer`, `DataView`, typed arrays, `Blob`, `File`, `Map`, `Set`, `Error`, and
  boxed primitives.
- Functions and symbols throw `DataCloneError`. So do values with **cycles** (an object that contains itself), which
  browsers allow.
- Class instances are stored as plain objects (their own enumerable properties). Two references to the same object
  are stored as two copies.
- `Blob` and `File` contents are stored inside the database row. Keep large media in files (`AntiGem.fs`) and store
  the path instead.

### Known limits

| Limit | What happens |
|---|---|
| One transaction at a time per app | Transactions of all pages of the app run one after another (also `readonly` ones). A long transaction makes others wait |
| Idle transactions | A transaction that sends nothing for 30 s is rolled back; its next request fails with `TimeoutError` |
| Closing a page | Its open transaction is rolled back |
| `versionchange` and `blocked` | Never fired. Another page with the database open does not block an upgrade, and is not told about it |
| `close` event on the database | Never fired. `db.close()` only stops new transactions on that connection |
| `durability` | Always `'default'` |
| Key generator | Up to 2^53; past that, `ConstraintError` |
| An index created in the same upgrade as records with generated in-line keys | Those generated keys are not seen by the new index |

Errors use the standard names: `ConstraintError`, `DataError`, `DataCloneError`, `InvalidStateError`,
`NotFoundError`, `ReadOnlyError`, `TransactionInactiveError`, `VersionError`, `AbortError`, `TimeoutError`,
`UnknownError`.

## Cookies

Each app has its own permanent cookie jar, like a browser profile. The native network layer sends its cookies on every
HTTP request (`fetch`, `XMLHttpRequest`, `EventSource`, `sendBeacon`, WebSocket handshakes, notification images, and
`https` images, styles, fonts and scripts) and stores every `Set-Cookie` it receives, including on redirects. A login cookie therefore keeps working after
AntiGem restarts. See [network](network.md#cookies-and-credentials) for how requests use it.

`AntiGem.cookies` reads and changes the jar. Every `url` must be a host the app may reach (its `network` hosts, or
`localhost` with that permission); others reject with `NotAllowedError`.

| Method | Returns | Meaning |
|---|---|---|
| `list(url?)` | `Promise<cookie[]>` | The cookies that would be sent to `url` (domain, path and `secure` matched); without `url`, every cookie in the jar |
| `get(url, name)` | `Promise<cookie \| null>` | The first cookie named `name` for `url` |
| `set(options)` | `Promise<cookie>` | Saves one cookie (options below) |
| `import(url, header, {days?})` | `Promise<number>` | Saves a `Cookie:` header copied from a browser (`"a=1; b=2"`, an optional `Cookie:` prefix is removed) as host-only cookies for `url`'s host with path `/`. They last `days` days (default 400) |
| `delete({url?, name?, path?})` | `Promise<number>` | Removes matching cookies. `url` limits it to cookies whose domain is that host or a parent domain of it |
| `clear(url?)` | `Promise<number>` | Removes every cookie that `delete({url})` would match, or the whole jar |

A cookie object is `{name, value, domain, path, expires, session, secure, httpOnly, hostOnly}`. `expires` is epoch
milliseconds, or `null` for a session cookie.

`set` options:

| Option | Type | Default | Meaning |
|---|---|---|---|
| `url` | string | required | Decides the host (`TypeError` without it) |
| `name` | string | required | |
| `value` | string | `''` | |
| `path` | string | `'/'` | |
| `domain` | string | none (host-only) | Must be the URL's host or a parent of it, else `NotAllowedError`. Set it to share the cookie with subdomains |
| `expires` | `Date`, epoch ms, or an ISO or HTTP date string | | Expiry time. A value that can't be read rejects with `TypeError` |
| `maxAge` | seconds | | Wins over `expires`. `0` or less deletes the cookie |
| `secure` | boolean | `false` | Only sent over `https` |
| `httpOnly` | boolean | `false` | Stored as a flag only |

Without `expires` or `maxAge`, a cookie the app sets lasts 400 days.

```js
// a token the user pasted from a browser session
const n = await AntiGem.cookies.import('https://api.example.com/', userPastedHeader);

await AntiGem.cookies.set({ url: 'https://api.example.com/', name: 'lang', value: 'en', maxAge: 30 * 86400 });
const all = await AntiGem.cookies.list('https://api.example.com/');
await AntiGem.cookies.clear('https://api.example.com/');
```

Jar rules:

- Session cookies from servers (no expiry) are kept in memory until AntiGem's process ends.
- A cookie whose name plus value is longer than 4096 characters is dropped. At most 180 cookies per domain and 3000 per
  app; the oldest go first.
- A server cookie with a past date deletes the cookie, as in browsers.
- Jars are never shared between apps, not even within a [shared data](shared-data.md) group.

## Quotas

There is no fixed quota on `localStorage`, `AntiGem.storage`, IndexedDB or files. The practical limits are the phone's
free space and these:

| What | Limit |
|---|---|
| `localStorage` + `AntiGem.storage` | No quota, but the whole store is copied into every page when it loads: keep it small (settings, small state) and put bulk data in IndexedDB or files |
| `AntiGem.fs.read` | 64 MB per call (`QuotaExceededError`); stream bigger files with `AntiGem.fs.url()` |
| HTTP cache | 50 MB per app |
| Cookies | 4096 characters per cookie, 180 per domain, 3000 per app |

## Clearing data

- **Clear data** (App info → Clear data) deletes the app's whole data folder: storage, IndexedDB, cookies, files,
  caches and logs. It also cancels its reminders, background tasks and notifications. The app itself stays.
  Permissions are kept ("Reset permissions" is a separate button). When you clear data from the app's own App info
  (developer menu), that page reloads; other pages that are still open keep their in-memory copy of `localStorage`
  until they reload.
- **Uninstalling** an installed app removes the app and all of the above. See
  [signing and distribution](signing-and-distribution.md).
- From code, clear what you own: `localStorage.clear()`, `AntiGem.storage.clear()`, `indexedDB.deleteDatabase(name)`,
  `AntiGem.cookies.clear()`, `AntiGem.fs.delete('data/x', {recursive: true})`.

## Limits and errors

- Writes to `localStorage` and `AntiGem.storage` are asynchronous on disk. They are safe across page reloads and
  normal app closes, but a write in the last moment before the process is killed can be lost. Keep critical data in
  IndexedDB (its transactions commit to SQLite before `complete` fires) or in files.
- `localStorage`, `sessionStorage` and `AntiGem.storage` are not available in workers. IndexedDB, files and cookies
  are.
- `AntiGem.cookies` rejects with `NotAllowedError` for hosts the app may not reach and with `TypeError` for an invalid
  or missing `url` or an `expires` it can't read.
