# Network

A mini app page can only connect to its own origin by itself (a strict Content Security Policy). Everything else goes
through AntiGem's native network stack: the SDK replaces `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource` and
`navigator.sendBeacon`, and `https` images, styles, fonts and scripts are fetched natively too. You write ordinary web
code. There are no CORS limits and any header can be set, but every connection is checked against the app's `network`
and `localhost` permissions. The app's cookie jar is applied automatically.

## Permissions

Without a permission, the only network an app has is its own files.

| Permission | Manifest | Allows |
|---|---|---|
| `network` | `true`, or `{ "hosts": ["api.example.com", "*.example.com"] }` | Any host (`true`, no `hosts` list, or `"*"`), or only the listed hosts, on any port. An empty list allows no host |
| `localhost` | `true`, or `{ "ports": [8080, 3000] }` | Servers on the phone itself, any port (no `ports` list) or only the listed ones. An empty list allows no port |

```json
"permissions": {
  "network": { "hosts": ["api.open-meteo.com", "*.githubusercontent.com"] },
  "localhost": { "ports": [8080] }
}
```

Host rules:

- Matching is case-insensitive and exact: `api.example.com` does not cover `example.com` or `cdn.example.com`.
- `*.example.com` covers every subdomain (`a.example.com`, `a.b.example.com`) but not `example.com` itself: list both if
  you need both.
- The check is made on the URL you request, and again on every redirect: a redirect to a host outside the list fails.
  A host outside the list is not even looked up (DNS). Ports don't matter for `network`.
- Loopback hosts never count as `network`: `localhost`, `*.localhost`, `127.*`, `::1` and `0.0.0.0` need the
  `localhost` permission.
- The phone's own addresses count as local too: its Wi-Fi or LAN IP, and any hostname that resolves to this phone.
  Reaching them needs `localhost` (with that port), and, since the name or IP in the URL is also a host, `network`
  with that host. The address is checked when the socket connects, for every connection: `fetch` and XHR,
  subresources, WebSocket handshakes and redirects.
- AntiGem's own local servers can never be reached, even with `localhost: true`: ports `1234`, `1235`, `8765` and
  `8865` in the `com.termux` build, `1236`, `1237`, `8767` and `8867` in the `com.antigem` build.
- Widening the hosts or ports in the manifest asks the user again; narrowing them keeps the user's choice. See
  [permissions](permissions.md#when-a-declaration-changes).

A blocked request rejects with `NotAllowedError` (not `TypeError`), and a line is written to the console log. A
redirect or a connection blocked on the way fails with `TypeError` instead ("Failed to fetch: Blocked: …"):

```js
try {
  await fetch('https://example.com/');
} catch (e) {
  if (e.name === 'NotAllowedError') AntiGem.ui.toast('This app may not connect to example.com');
  else throw e;
}
```

## fetch

`fetch(url, init)` behaves like the browser's, with these differences:

- **No CORS.** Every response is readable (status, headers, body), whatever the server's CORS headers say.
- **Any header.** Headers browsers refuse to send (`Cookie`, `Origin`, `Referer`, `User-Agent`, …) are sent as given.
  Put them in `init.headers`: a `Request` object built beforehand has already dropped them.
- `Accept-Encoding`, `Content-Length`, `Host` and `Connection` are managed natively and ignored if you set them.
  Compressed responses are decompressed for you.
- Without your own `User-Agent`, requests use the WebView's user agent plus ` AntiGemMiniApp/1`.
- `http:` and `https:` both work. Other schemes reject with `TypeError`.
- **Streaming responses.** The body arrives as a real stream (`res.body.getReader()` works for downloads and
  server-sent events). Request bodies are sent in one piece (no upload streaming).
- `signal` (`AbortController`) cancels the native request; the promise rejects with `AbortError`.
- `redirect`: `'follow'` (default) follows redirects natively; `res.redirected` and `res.url` tell where it ended.
  Each hop must be a host the app may reach, or the request fails with `TypeError` ("Failed to fetch: Blocked: …").
  `'manual'` returns the redirect response itself, with its real status (`301`, `302`, …) and a readable `Location`
  header (browsers return an opaque response instead). `'error'` rejects with `TypeError` if the server redirects.
- `credentials: 'omit'` sends no cookies and stores none. Any other value (the default too) uses the app's jar. See
  [Cookies and credentials](#cookies-and-credentials).
- Responses go through a native HTTP cache (50 MB per app) that follows the server's `Cache-Control` headers. The
  `cache` option of `fetch` is not used.
- Timeouts: 20 s to connect, 5 min between bytes when reading, 2 min when writing.
- Requests to the app's own origin, `data:` and `blob:` URLs are not sent natively; they work as usual.

```js
const res = await fetch('https://api.github.com/repos/torvalds/linux', {
  headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'MyMiniApp/1.0' },
});
if (!res.ok) throw new Error(`HTTP ${res.status}`);
const repo = await res.json();

// streaming
const r = await fetch('https://example.com/big.bin');
const reader = r.body.getReader();
for (let n = 0; ; ) {
  const { done, value } = await reader.read();
  if (done) break;
  n += value.length;
  progress(n);
}
```

Network failures reject with `TypeError` ("Failed to fetch: …"), as in browsers.

## XMLHttpRequest

`XMLHttpRequest` is rebuilt on top of the native `fetch`, so the same rules apply.

- `responseType`: `''`, `'text'`, `'json'`, `'arraybuffer'`, `'blob'`, `'document'`. `overrideMimeType()` works.
- `timeout`, `abort()`, `getResponseHeader()`, `getAllResponseHeaders()`, `responseURL`, the `on*` handlers and
  `addEventListener` all work.
- Progress: the response is read in full, then one `progress` event fires. On `upload`, `loadstart` fires when the
  request is sent and `progress`, `load`, `loadend` fire once, after the response arrives. Use `fetch` with a stream
  reader for real download progress.
- `withCredentials` doesn't turn cookies off: the jar is used either way.
- Synchronous requests (`open(method, url, false)`) only reach the app's own files.

## WebSocket

`new WebSocket(url, protocols)` opens a native socket.

- `ws:`, `wss:`, and also `http:` / `https:` URLs (turned into `ws:` / `wss:`) are accepted.
- `send()` takes strings, `Blob`, `ArrayBuffer` and typed arrays. `binaryType` is `'blob'` (default) or
  `'arraybuffer'`.
- `close(code, reason)`: `code` must be `1000` or `3000`–`4999` (else `InvalidAccessError`).
- The handshake sends `Origin: <the app's origin>`, the AntiGem user agent and the app's saved cookies for that host;
  `Set-Cookie` in the handshake response is stored in the jar. Custom headers can't be set (as in browsers).
- The handshake is checked like any other request: the hosts list and the `localhost` rules.
- Open, message, error and close events fire as usual. A connection that can't be opened (including a permission
  refusal) fires `error`, then `close` with code `1006`; the reason is written to the console log.

```js
const ws = new WebSocket('wss://ws.postman-echo.com/raw');
ws.onopen = () => ws.send('hello');
ws.onmessage = (e) => console.log('got', e.data);
ws.onclose = (e) => console.log('closed', e.code);
```

## EventSource

`new EventSource(url, {withCredentials})` is built on the streaming `fetch`.

- Named events (`event:`), `id:` with `Last-Event-ID` on reconnect, and `retry:` work. The default reconnect delay is
  3 s.
- When the stream ends or the connection fails, it fires `error` and reconnects. A response that isn't `2xx` with a
  `text/event-stream` content type closes it for good (`readyState` 2) after an `error` event.
- Cookies from the jar are sent whether or not `withCredentials` is set.

## navigator.sendBeacon

`sendBeacon(url, data)` sends a native `POST` in the background and always returns `true`. Failures are ignored.

## Images, styles, fonts and scripts from https

`<img src="https://…">`, CSS `url()`, `@font-face`, `<link rel="stylesheet" href="https://…">`,
`<script src="https://…">` and other `https` `GET` subresources are fetched natively, through the same HTTP cache as
`fetch` (it follows the server's cache headers). They need the `network` permission with that host; a blocked one gets
a `403` (the console log says why).

- Only `https` subresources are allowed by the page's policy. To show an image from an `http://` server (for example a
  local one with the `localhost` permission), `fetch` it and use a `blob:` URL:

  ```js
  const blob = await (await fetch('http://127.0.0.1:8080/chart.png')).blob();
  img.src = URL.createObjectURL(blob);
  ```

- Relative URLs (your own files), `data:` and `blob:` URLs always work.
- Prefer bundling libraries and fonts in the app folder: the app then works without the network and needs no
  permission for them.

Two native features load web URLs for the app too:

- **Notification images** (`icon`, `smallIcon`, `image` in [notifications](notifications.md)) from an `http(s)` URL
  are fetched as the app: the same hosts list, cookies and `localhost` rules.
- **The media viewer** (`AntiGem.ui.viewMedia`) checks a web URL against the hosts list; a hostname that points to
  this phone needs the `localhost` permission too.

## Cookies and credentials

The app has one persistent cookie jar (see [storage](storage.md#cookies)):

| Request | Cookies from the jar | `Set-Cookie` stored |
|---|---|---|
| `fetch` (default, `'same-origin'`, `'include'`) | Sent | Yes |
| `fetch` with `credentials: 'omit'` | Not sent | No |
| `XMLHttpRequest`, `EventSource` (any `withCredentials`) | Sent | Yes |
| `sendBeacon`, https subresources | Sent | Yes |
| `WebSocket` handshake | Sent | Yes |

- Cookies are applied on every hop of a redirect, so a login that redirects keeps its session.
- If you set a `Cookie` header yourself, it is merged with the jar's cookies; your values win for the same name.
- `AntiGem.cookies.import(url, header)` saves a `Cookie:` header the user copied from a browser, for APIs that need a
  browser login.

## Not supported

| Feature | Status |
|---|---|
| WebRTC (`RTCPeerConnection`, `RTCDataChannel`) | Removed: those globals are `undefined`. They would open connections no permission can check |
| Service workers | `navigator.serviceWorker.register()` rejects with `NotSupportedError` |
| `SharedArrayBuffer`, cross-origin isolation | Not available: Android WebView doesn't grant cross-origin isolation. See [threads](threads.md) |
| Plain `http://` subresources (`<img>`, CSS, scripts) | Blocked by the page's policy; use `fetch` |
| `<iframe>` of other sites, `<form action>` to other sites | Blocked: frames and form posts may only target the app itself |
| Navigating the page to another site | Blocked. A link the user taps opens in the system browser instead (after a confirmation if the app has no `network` permission) |
| `navigator.geolocation` | Always denied |
| Upload progress, request body streaming | Request bodies are sent in one piece |

## Limits and errors

| Error | When |
|---|---|
| `NotAllowedError` | No `network` / `localhost` permission, host not listed, port not listed, or an AntiGem port |
| `TypeError` | Network failure, invalid URL, unsupported scheme, `redirect: 'error'` with a redirect; a redirect, or an address found only when connecting, that the app may not reach ("Failed to fetch: Blocked: …") |
| `AbortError` | The request's signal was aborted |
| `InvalidAccessError` | `WebSocket.close()` with a bad code |
| `SyntaxError` | `new WebSocket()` with a non-`ws` scheme or a `#fragment` |

- The hosts list is checked for the URL you request, and again for every redirect.
- An app's open requests, unread response bodies and sockets are closed when its page closes.
- Status codes outside 200–599 are reported as `200` (the `Response` constructor doesn't accept them).
