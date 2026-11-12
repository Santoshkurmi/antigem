/*
 * AntiGem Mini Apps SDK: network. fetch, XMLHttpRequest, WebSocket, EventSource and sendBeacon go through the
 * native network layer (OkHttp): no CORS, any header, checked against the app's network/localhost permissions.
 * The page itself may only connect to its own origin (CSP), so this is the only way out.
 */
(function () {
  'use strict';
  // runs in pages and in workers (threads.js injects the SDK into every worker)
  var G = globalThis;
  var AG = G.__AG;
  if (!AG || AG._net) return;
  AG._net = true;

  var origFetch = G.fetch.bind(G);
  var OrigXHR = G.XMLHttpRequest;
  var NULL_BODY = { 101: 1, 204: 1, 205: 1, 304: 1 };

  function abortError() { return new DOMException('The operation was aborted.', 'AbortError'); }
  function rid() { return Math.random().toString(36).slice(2) + Date.now().toString(36); }
  function isOwn(u) { return u.origin === location.origin || u.protocol === 'data:' || u.protocol === 'blob:'; }

  function toHeaders(list) {
    var h = new Headers();
    (list || []).forEach(function (p) { try { h.append(p[0], p[1]); } catch (e) {} });
    return h;
  }

  // ------------------------------------------------------------------ fetch
  async function nativeFetch(input, init) {
    init = init || {};
    var req = new Request(input, init);
    var url = new URL(req.url);
    if (isOwn(url)) return origFetch(input, init);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new TypeError('Failed to fetch: unsupported scheme ' + url.protocol);
    var signal = req.signal;
    if (signal && signal.aborted) throw abortError();

    var headers = [];
    req.headers.forEach(function (v, k) { headers.push([k, v]); });
    // headers a browser refuses to send (Cookie, Origin, Referer, …) still go out natively
    if (init.headers) {
      try { new Headers(init.headers).forEach(function (v, k) { if (!req.headers.has(k)) headers.push([k, v]); }); } catch (e) {}
    }
    var body = '';
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      var buf = await req.arrayBuffer();
      if (buf.byteLength) body = AG.b64encode(buf);
    }

    var id = rid();
    var onAbort = function () { AG.send('net.abort', { rid: id }); };
    if (signal) signal.addEventListener('abort', onAbort);
    var meta;
    try {
      // cookies are sent like a native app's HTTP client does; only credentials: 'omit' turns them off
      meta = await AG.call('net.fetch', { rid: id, url: url.href, method: req.method, headers: headers, body: body, redirect: req.redirect, credentials: req.credentials });
    } catch (e) {
      if (signal) signal.removeEventListener('abort', onAbort);
      if (e && e.name === 'AbortError') throw abortError();
      if (e && e.name === 'NotAllowedError') { console.warn('[AntiGem] ' + e.message); throw e; }
      throw new TypeError(e && e.message ? e.message : 'Failed to fetch');
    }
    if (signal) signal.removeEventListener('abort', onAbort);
    if (signal && signal.aborted) { if (meta.sid) AG.send('net.drop', { sid: meta.sid }); throw abortError(); }

    // the body streams from a one-time local URL: no base64, no copies, real streaming (SSE, downloads)
    var stream = null;
    if (meta.sid) stream = (await origFetch(location.origin + '/_antigem/net/' + meta.sid, { signal: signal, cache: 'no-store' })).body;
    var status = meta.status;
    var resp;
    try {
      resp = new Response(NULL_BODY[status] ? null : stream, { status: status >= 200 && status <= 599 ? status : 200, statusText: meta.statusText || '', headers: toHeaders(meta.headers) });
    } catch (e) {
      resp = new Response(NULL_BODY[status] ? null : stream, { status: status >= 200 && status <= 599 ? status : 200, headers: toHeaders(meta.headers) });
    }
    Object.defineProperty(resp, 'url', { value: meta.url });
    Object.defineProperty(resp, 'redirected', { value: !!meta.redirected });
    return resp;
  }
  AG.define(G, 'fetch', nativeFetch);

  // ------------------------------------------------------------------ XMLHttpRequest (on top of fetch)
  class NativeXHR extends EventTarget {
    constructor() {
    super();
    var self = this;
    this.readyState = 0; this.status = 0; this.statusText = ''; this.responseURL = '';
    this.response = ''; this.responseText = ''; this.responseXML = null;
    this.responseType = ''; this.timeout = 0; this.withCredentials = false;
    this._headers = {}; this._resp = null; this._ctrl = null; this._sent = false; this._real = null;
    this.upload = new EventTarget();
    ['onreadystatechange', 'onload', 'onerror', 'onabort', 'ontimeout', 'onloadstart', 'onloadend', 'onprogress'].forEach(function (k) { self[k] = null; });
    }
  }
  ['UNSENT', 'OPENED', 'HEADERS_RECEIVED', 'LOADING', 'DONE'].forEach(function (n, i) { NativeXHR[n] = i; NativeXHR.prototype[n] = i; });

  NativeXHR.prototype._fire = function (type, target, loaded, total) {
    var t = target || this;
    var ev = type === 'readystatechange' ? new Event(type) : new ProgressEvent(type, { lengthComputable: total > 0, loaded: loaded || 0, total: total || 0 });
    t.dispatchEvent(ev);
    var h = t['on' + type];
    if (typeof h === 'function') { try { h.call(t, ev); } catch (e) { console.error(e); } }
  };
  NativeXHR.prototype._state = function (s) { this.readyState = s; this._fire('readystatechange'); };

  NativeXHR.prototype.open = function (method, url, async) {
    this._method = String(method || 'GET').toUpperCase();
    this._url = new URL(String(url), location.href).href;
    this._headers = {}; this._resp = null; this._sent = false; this._aborted = false; this._timedOut = false;
    this.status = 0; this.statusText = ''; this.response = ''; this.responseText = '';
    // synchronous requests can only reach the app's own files (the native layer is async)
    if (async === false) {
      this._real = new OrigXHR();
      this._real.open(method, url, false);
    } else this._real = null;
    this._state(1);
  };

  NativeXHR.prototype.setRequestHeader = function (k, v) {
    if (this.readyState !== 1 || this._sent) throw new DOMException('The object\'s state must be OPENED.', 'InvalidStateError');
    if (this._real) return this._real.setRequestHeader(k, v);
    this._headers[k] = this._headers[k] ? this._headers[k] + ', ' + v : String(v);
  };
  NativeXHR.prototype.overrideMimeType = function (m) { this._mime = m; };
  NativeXHR.prototype.getResponseHeader = function (name) {
    if (this._real) return this._real.getResponseHeader(name);
    return this._resp ? this._resp.headers.get(name) : null;
  };
  NativeXHR.prototype.getAllResponseHeaders = function () {
    if (this._real) return this._real.getAllResponseHeaders();
    if (!this._resp) return '';
    var out = '';
    this._resp.headers.forEach(function (v, k) { out += k + ': ' + v + '\r\n'; });
    return out;
  };

  NativeXHR.prototype._setResponse = function (buf, contentType) {
    var type = this.responseType || '';
    var mime = this._mime || contentType || '';
    var text = function () { var cs = (/charset=([^;]+)/i.exec(mime) || [])[1] || 'utf-8'; try { return new TextDecoder(cs.trim()).decode(buf); } catch (e) { return new TextDecoder().decode(buf); } };
    if (type === '' || type === 'text') { this.responseText = text(); this.response = this.responseText; }
    else if (type === 'json') { try { this.response = JSON.parse(text()); } catch (e) { this.response = null; } }
    else if (type === 'arraybuffer') this.response = buf;
    else if (type === 'blob') this.response = new Blob([buf], { type: mime.split(';')[0] });
    else if (type === 'document') {
      if (typeof DOMParser !== 'undefined') this.response = this.responseXML = new DOMParser().parseFromString(text(), /xml/.test(mime) ? 'application/xml' : 'text/html');
    }
  };

  NativeXHR.prototype.send = function (body) {
    var self = this;
    if (this.readyState !== 1 || this._sent) throw new DOMException('The object\'s state must be OPENED.', 'InvalidStateError');
    this._sent = true;
    if (this._real) {
      this._real.responseType = this.responseType === 'json' ? '' : this.responseType;
      this._real.send(body);
      this.status = this._real.status; this.statusText = this._real.statusText; this.responseURL = this._real.responseURL;
      if (this.responseType === 'json') { try { this.response = JSON.parse(this._real.responseText); } catch (e) { this.response = null; } }
      else { this.response = this._real.response; if (!this.responseType || this.responseType === 'text') this.responseText = this._real.responseText; }
      this._state(4); this._fire('load'); this._fire('loadend');
      return;
    }
    if (typeof Document !== 'undefined' && body instanceof Document) body = new XMLSerializer().serializeToString(body);
    var hasBody = body != null && this._method !== 'GET' && this._method !== 'HEAD';
    this._ctrl = new AbortController();
    var timer = this.timeout > 0 ? setTimeout(function () { self._timedOut = true; self._ctrl.abort(); }, this.timeout) : null;
    this._fire('loadstart');
    if (hasBody) this._fire('loadstart', this.upload);
    G.fetch(this._url, {
      method: this._method, headers: this._headers, body: hasBody ? body : undefined,
      signal: this._ctrl.signal, credentials: this.withCredentials ? 'include' : 'same-origin'
    }).then(function (r) {
      self._resp = r; self.status = r.status; self.statusText = r.statusText; self.responseURL = r.url;
      if (hasBody) { self._fire('progress', self.upload, 1, 1); self._fire('load', self.upload, 1, 1); self._fire('loadend', self.upload, 1, 1); }
      self._state(2); self._state(3);
      return r.arrayBuffer().then(function (buf) {
        if (timer) clearTimeout(timer);
        self._setResponse(buf, r.headers.get('content-type'));
        self._state(4);
        self._fire('progress', null, buf.byteLength, buf.byteLength);
        self._fire('load', null, buf.byteLength, buf.byteLength);
        self._fire('loadend', null, buf.byteLength, buf.byteLength);
      });
    }).catch(function (e) {
      if (timer) clearTimeout(timer);
      if (self._aborted) return;
      self.status = 0; self.readyState = 4; self._fire('readystatechange');
      if (self._timedOut) self._fire('timeout');
      else { if (e && e.message) console.warn('[AntiGem] XHR ' + self._url + ': ' + e.message); self._fire('error'); }
      self._fire('loadend');
    });
  };

  NativeXHR.prototype.abort = function () {
    if (this._real) return this._real.abort();
    if (this._sent && this.readyState > 0 && this.readyState < 4) {
      this._aborted = true;
      if (this._ctrl) this._ctrl.abort();
      this.status = 0; this.readyState = 4; this._fire('readystatechange'); this._fire('abort'); this._fire('loadend');
    }
    this.readyState = 0;
  };
  AG.define(G, 'XMLHttpRequest', NativeXHR);

  // ------------------------------------------------------------------ WebSocket
  var sockets = new Map();
  class NativeWebSocket extends EventTarget {
    constructor(url, protocols) {
    super();
    var u = new URL(String(url), location.href);
    if (u.protocol === 'http:') u.protocol = 'ws:';
    else if (u.protocol === 'https:') u.protocol = 'wss:';
    if (u.protocol !== 'ws:' && u.protocol !== 'wss:') throw new DOMException("The URL's scheme must be either 'ws' or 'wss'.", 'SyntaxError');
    if (u.hash) throw new DOMException('The URL contains a fragment identifier.', 'SyntaxError');
    var self = this;
    this.url = u.href; this.readyState = 0; this.protocol = ''; this.extensions = ''; this.bufferedAmount = 0;
    this._binaryType = 'blob';
    this.onopen = this.onmessage = this.onerror = this.onclose = null;
    this._sid = rid();
    sockets.set(this._sid, this);
    var list = protocols == null ? [] : Array.isArray(protocols) ? protocols : [protocols];
    AG.call('ws.open', { sid: this._sid, url: u.href, protocols: list.map(String) }).catch(function (e) {
      console.warn('[AntiGem] WebSocket ' + u.href + ': ' + (e && e.message));
      self._event({ t: 'error' });
      self._event({ t: 'close', code: 1006, reason: '', clean: false });
    });
    }
  }
  ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (n, i) { NativeWebSocket[n] = i; NativeWebSocket.prototype[n] = i; });
  Object.defineProperty(NativeWebSocket.prototype, 'binaryType', {
    get: function () { return this._binaryType; },
    set: function (v) { if (v === 'blob' || v === 'arraybuffer') this._binaryType = v; }
  });
  NativeWebSocket.prototype.send = function (data) {
    if (this.readyState === 0) throw new DOMException('Still in CONNECTING state.', 'InvalidStateError');
    if (this.readyState !== 1) return;
    var sid = this._sid;
    if (typeof data === 'string') AG.send('ws.send', { sid: sid, text: data });
    else if (data instanceof Blob) data.arrayBuffer().then(function (b) { AG.send('ws.send', { sid: sid, b64: AG.b64encode(b) }); });
    else AG.send('ws.send', { sid: sid, b64: AG.b64encode(data) });
  };
  NativeWebSocket.prototype.close = function (code, reason) {
    if (code !== undefined && code !== 1000 && (code < 3000 || code > 4999)) throw new DOMException('The close code must be either 1000, or between 3000 and 4999.', 'InvalidAccessError');
    if (this.readyState >= 2) return;
    this.readyState = 2;
    AG.send('ws.close', { sid: this._sid, code: code || 1000, reason: reason ? String(reason) : '' });
  };
  NativeWebSocket.prototype._fire = function (ev) {
    this.dispatchEvent(ev);
    var h = this['on' + ev.type];
    if (typeof h === 'function') { try { h.call(this, ev); } catch (e) { console.error(e); } }
  };
  NativeWebSocket.prototype._event = function (d) {
    if (d.t === 'open') { this.readyState = 1; this.protocol = d.protocol || ''; this._fire(new Event('open')); }
    else if (d.t === 'msg') {
      var data = d.text != null ? d.text : (function (u8, type) { return type === 'arraybuffer' ? u8.buffer : new Blob([u8]); })(AG.b64decode(d.b64 || ''), this._binaryType);
      this._fire(new MessageEvent('message', { data: data, origin: new URL(this.url).origin }));
    } else if (d.t === 'error') {
      // like a browser, the event carries no reason: the console gets it (blocked, refused, TLS…)
      if (d.message) console.warn('[AntiGem] WebSocket ' + this.url + ': ' + d.message);
      this._fire(new Event('error'));
    }
    else if (d.t === 'close') {
      if (this.readyState === 3) return;
      this.readyState = 3;
      sockets.delete(this._sid);
      this._fire(new CloseEvent('close', { code: d.code || 1006, reason: d.reason || '', wasClean: !!d.clean }));
    }
  };
  AG.on('ws', function (d) { var s = sockets.get(d.sid); if (s) s._event(d); });
  AG.define(G, 'WebSocket', NativeWebSocket);

  // ------------------------------------------------------------------ EventSource (on top of streaming fetch)
  class NativeEventSource extends EventTarget {
    constructor(url, init) {
    super();
    this.url = new URL(String(url), location.href).href;
    this.withCredentials = !!(init && init.withCredentials);
    this.readyState = 0;
    this.onopen = this.onmessage = this.onerror = null;
    this._last = ''; this._retry = 3000; this._ctrl = null;
    this._connect();
    }
  }
  ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function (n, i) { NativeEventSource[n] = i; NativeEventSource.prototype[n] = i; });
  NativeEventSource.prototype._fire = NativeWebSocket.prototype._fire;
  NativeEventSource.prototype._connect = function () {
    var self = this;
    if (this.readyState === 2) return;
    this._ctrl = new AbortController();
    var headers = { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' };
    if (this._last) headers['Last-Event-ID'] = this._last;
    G.fetch(this.url, { headers: headers, signal: this._ctrl.signal, credentials: this.withCredentials ? 'include' : 'same-origin' }).then(function (r) {
      if (!r.ok || !/text\/event-stream/i.test(r.headers.get('content-type') || '')) { self.readyState = 2; self._fire(new Event('error')); return; }
      self.readyState = 1;
      self._fire(new Event('open'));
      var reader = r.body.getReader(), dec = new TextDecoder(), buf = '', data = [], type = '';
      var origin = new URL(self.url).origin;
      function line(l) {
        if (l === '') {
          if (data.length) self._fire(new MessageEvent(type || 'message', { data: data.join('\n'), lastEventId: self._last, origin: origin }));
          data = []; type = '';
          return;
        }
        if (l.charAt(0) === ':') return;
        var i = l.indexOf(':'), f = i < 0 ? l : l.slice(0, i), v = i < 0 ? '' : l.slice(i + 1);
        if (v.charAt(0) === ' ') v = v.slice(1);
        if (f === 'data') data.push(v);
        else if (f === 'event') type = v;
        else if (f === 'id') self._last = v;
        else if (f === 'retry' && /^\d+$/.test(v)) self._retry = +v;
      }
      function pump() {
        return reader.read().then(function (x) {
          if (x.done) { self._reconnect(); return; }
          buf += dec.decode(x.value, { stream: true });
          var lines = buf.split(/\r\n|\r|\n/);
          buf = lines.pop();
          lines.forEach(line);
          return pump();
        });
      }
      return pump();
    }).catch(function () { self._reconnect(); });
  };
  NativeEventSource.prototype._reconnect = function () {
    var self = this;
    if (this.readyState === 2) return;
    this.readyState = 0;
    this._fire(new Event('error'));
    setTimeout(function () { self._connect(); }, this._retry);
  };
  NativeEventSource.prototype.close = function () { this.readyState = 2; if (this._ctrl) this._ctrl.abort(); };
  AG.define(G, 'EventSource', NativeEventSource);

  // ------------------------------------------------------------------ sendBeacon
  if (typeof navigator.sendBeacon === 'function') try {
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      value: function (url, data) { nativeFetch(url, { method: 'POST', body: data, keepalive: true }).catch(function () {}); return true; }
    });
  } catch (e) {}
})();
