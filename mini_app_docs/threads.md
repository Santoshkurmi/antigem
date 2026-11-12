# Threads and Web Workers

Heavy work (parsing, image processing, search, math) can run on other CPU cores so the page never stutters. Standard
Web Workers work, and the SDK is loaded inside every worker of the app, so `fetch`, `XMLHttpRequest`, `WebSocket`,
`EventSource`, `indexedDB` and most of `AntiGem` behave there exactly as in the page, with the same permissions. On top
of that, `AntiGem.thread` runs a plain function on a pool of worker threads without writing a worker file. Threads
share data by copying messages: `SharedArrayBuffer` is not available.

## AntiGem.thread

```js
// one call on another core
const sum = await AntiGem.thread.run((a, b) => a + b, [2, 40]);   // 42

// a list spread over all threads; results come back in the same order
const sizes = await AntiGem.thread.map(urls, async (url) => (await (await fetch(url)).arrayBuffer()).byteLength);

// a pool of your own (for example 2 threads for background work), closed when done
const pool = AntiGem.thread.pool(2);
const parsed = await pool.run((text) => JSON.parse(text).items.length, [bigJsonText]);
pool.close();
```

| Member | Returns | Meaning |
|---|---|---|
| `cores` | number | CPU cores (`navigator.hardwareConcurrency`, or 4 when unknown) |
| `run(fn, args?, {transfer?})` | `Promise<result>` | Calls `fn(...args)` on a free thread of the shared pool |
| `map(items, fn)` | `Promise<result[]>` | Calls `fn(item)` for every item (any iterable), spread over the shared pool |
| `pool(size)` or `pool({size})` | pool | A pool of your own: `{size, run(fn, args?, {transfer?}), map(items, fn), close()}` |

How it works:

- The shared pool has `cores - 1` threads (at least 1). A pool's size is clamped to 1–16; without a size it is
  `cores - 1`. Threads start on first use and are reused; jobs wait in a queue when all threads are busy.
- **The function is copied to the thread as source code.** It can't use variables from outside its own body: pass
  everything in `args`. Use an arrow function or a `function` expression; a method written in shorthand
  (`{ f() {} }`) or a bound function can't be rebuilt from its source. You may also pass the source as a string.
- The function may be `async`, and may use `fetch`, `indexedDB`, `AntiGem.fs`, `AntiGem.mcp` and the rest of the
  [worker SDK](#what-workers-have).
- Arguments and the result are copied with structured clone. An `ArrayBuffer` or typed array returned as the result
  is moved, not copied. To move arguments instead of copying them, list them in `transfer`:

  ```js
  const pixels = ctx.getImageData(0, 0, w, h).data.buffer;
  const out = await AntiGem.thread.run((buf) => { const p = new Uint8ClampedArray(buf); for (let i = 0; i < p.length; i += 4) p[i] = 255 - p[i]; return buf; },
    [pixels], { transfer: [pixels] });
  ```

- A thrown error rejects the promise with an `Error` that has the same `name`, `message` and `stack`. A result that
  can't be cloned rejects with `DataCloneError`. If the thread itself reports an error outside your function, the job
  rejects with that message (or "Thread crashed").
- `close()` stops the pool's threads; queued jobs reject with `AbortError`, and later `run` calls reject with
  `InvalidStateError`.

For a long-running thread with its own message protocol, use a normal `new Worker('worker.js')`.

## Web Workers

```js
// page
const worker = new Worker('worker.js');                    // classic
const mod = new Worker('worker.mjs', { type: 'module' });  // module
worker.onmessage = (e) => render(e.data);
worker.postMessage({ query: 'rust' });
```

```js
// worker.js: the SDK is already loaded here
self.onmessage = async (e) => {
  const res = await fetch(`https://api.example.com/search?q=${encodeURIComponent(e.data.query)}`);
  const db = await openDb();                  // the same IndexedDB databases as the page
  self.postMessage(await res.json());
};
```

- **Files of the app** (`new Worker('worker.js')`, classic or `{type: 'module'}`) get the SDK added at the start of
  the file by the runtime, on the same line so error line numbers stay right (after a `'use strict'` directive if
  there is one).
- **`blob:` workers** get a small bootstrap that loads the SDK first, then your blob.
- `data:` URLs and other origins get no SDK.
- `importScripts()` and module `import` of the app's own files work.
- Workers can start their own workers; those get the SDK too.
- A worker reaches native code through the page that created it: when that page closes, its workers stop with it.

### What workers have

| Available | Not available |
|---|---|
| `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource` (native, same permissions as the page) | `localStorage`, `sessionStorage`, `AntiGem.storage` |
| `indexedDB` (the same databases as the page) | `AntiGem.ui` dialogs, `viewMedia`, `statusBar` |
| `AntiGem.fs`: `read`, `readJSON`, `write`, `writeJSON`, `list`, `stat`, `exists`, `mkdir`, `delete`, `move`, `url(path, {thumb?})`, `folders` | `AntiGem.fs.pick`, `pickFolder`, `forgetFolder` |
| `AntiGem.notify`: `show`, `dismiss`, `schedule`, `list`, `cancel`, `cancelAll` | `AntiGem.notify.channel`, `deleteChannel`, `active` |
| `AntiGem.cookies`: `list`, `set`, `delete` | `AntiGem.cookies.get`, `import`, `clear` |
| `AntiGem.mcp.list`, `AntiGem.mcp.call` | `AntiGem.on` / `off` (no events), `AntiGem.onBack`, `AntiGem.nav`, `AntiGem.intent`, `AntiGem.background` |
| `AntiGem.permissions.all`, `query` | `AntiGem.permissions.request` |
| `AntiGem.ui.toast`, `AntiGem.device.haptic`, `copy`, `info` | `AntiGem.device.share`, `paste`, `openUrl`, `status` |
| `AntiGem.thread` | `AntiGem.dev`, `AntiGem.theme`, `AntiGem.page` |
| `AntiGem.app`, `AntiGem.sdk`, `AntiGem.worker` (`true`) | |

Inside workers, `Date` values are converted to epoch milliseconds as in pages: in `notify.show` and `notify.schedule`
(`at`, `until`, `repeat.until`, `countdown`, `chronometer`, message times) and in cookie `expires`.

## No SharedArrayBuffer

Android WebView doesn't grant cross-origin isolation, so `SharedArrayBuffer` and `Atomics.wait` on shared memory are
not available, whatever headers you send. Libraries that need them (some WebAssembly builds with threads) won't run
their threaded mode. Move data between threads with messages, and transfer `ArrayBuffer`s to avoid copies.

## Limits and errors

| Error | When |
|---|---|
| `DataCloneError` | A thread's result can't be cloned (functions, DOM nodes, …) |
| `TypeError` | The value passed to `run` isn't a function (or function source) |
| `AbortError` | The pool was closed while the job was queued |
| `InvalidStateError` | `run` on a closed pool |

- Each thread is a real OS thread with its own JavaScript engine: starting one costs memory and some time. Reuse pools
  instead of creating one per job.
- Catch errors inside workers and post them to the page if you want to see them in the page's console.
