/*
 * AntiGem Mini Apps SDK: worker core. Runs first in every worker / thread of a mini app (the runtime injects it).
 * Workers can't reach native code directly, so calls travel over a private MessageChannel to the page that
 * created the worker, which forwards them (core.js attachWorker). Same internal API as the page: __AG.call/send/on.
 * fetch, XHR, WebSocket, EventSource and indexedDB then work exactly as in the page (net.js, idb.js).
 */
(function () {
  'use strict';
  var G = globalThis;
  if (G.__AG) return;
  var BOOT = G.__AG_BOOT || {};
  try { delete G.__AG_BOOT; } catch (e) {}

  var port = null;
  var outbox = [];
  var nextId = 1;
  var pending = new Map();
  var listeners = Object.create(null);
  var childPorts = [];

  function post(o) { if (port) port.postMessage(o); else outbox.push(o); }

  function makeError(e) {
    var name = (e && e.name) || 'Error', message = (e && e.message) || 'Unknown error';
    if (name === 'TypeError') return new TypeError(message);
    try { return new DOMException(message, name); } catch (x) { var err = new Error(message); err.name = name; return err; }
  }

  function emit(ev, d) { (listeners[ev] || []).slice().forEach(function (fn) { try { fn(d); } catch (e) { console.error(e); } }); }

  function recv(msg) {
    if (!msg) return;
    if (msg.i != null) {
      var p = pending.get(msg.i);
      if (!p) return;
      pending.delete(msg.i);
      if (msg.e) p.reject(makeError(msg.e)); else p.resolve(msg.r);
    } else if (msg.ev) {
      emit(msg.ev, msg.d);
      childPorts.forEach(function (cp) { cp.postMessage(msg); });
    }
  }

  // The page sends the channel as the very first message; it never reaches the app's own onmessage.
  G.addEventListener('message', function (e) {
    var d = e.data;
    if (port || !d || d.__ag !== 'init' || !e.ports || !e.ports[0]) return;
    e.stopImmediatePropagation();
    port = e.ports[0];
    port.onmessage = function (ev) { recv(ev.data); };
    outbox.splice(0).forEach(function (o) { port.postMessage(o); });
  });

  function call(m, p) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending.set(id, { resolve: resolve, reject: reject });
      post({ i: id, m: m, p: p || {} });
    });
  }
  function send(m, p) { post({ m: m, p: p || {} }); }
  function on(ev, fn) { (listeners[ev] = listeners[ev] || []).push(fn); }
  function off(ev, fn) { var l = listeners[ev]; if (l) { var i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); } }

  function u8of(data) {
    if (data instanceof Uint8Array) return data;
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    return new Uint8Array(data);
  }
  function b64encode(data) {
    var u8 = u8of(data), s = '', CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    return btoa(s);
  }
  function b64decode(str) {
    var s = atob(str), u8 = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
    return u8;
  }
  function define(obj, name, value) {
    try { Object.defineProperty(obj, name, { value: value, configurable: true, enumerable: true, writable: true }); }
    catch (e) { try { obj[name] = value; } catch (x) {} }
  }

  // A worker can start its own workers: their calls hop through us to the page.
  function attachWorker(w) {
    var ch = new MessageChannel(), cp = ch.port1;
    cp.onmessage = function (e) {
      var m = e.data;
      if (!m || !m.m) return;
      if (m.i == null) { send(m.m, m.p); return; }
      call(m.m, m.p).then(
        function (r) { cp.postMessage({ i: m.i, r: r }); },
        function (err) { cp.postMessage({ i: m.i, e: { name: (err && err.name) || 'Error', message: (err && err.message) || String(err) } }); }
      );
    };
    childPorts.push(cp);
    w.postMessage({ __ag: 'init' }, [ch.port2]);
    var terminate = w.terminate;
    w.terminate = function () {
      var i = childPorts.indexOf(cp);
      if (i >= 0) childPorts.splice(i, 1);
      cp.close();
      return terminate.call(w);
    };
  }

  Object.defineProperty(G, '__AG', {
    value: { boot: BOOT, worker: true, call: call, send: send, on: on, off: off, emit: emit, define: define, attachWorker: attachWorker, b64encode: b64encode, b64decode: b64decode },
    configurable: false, enumerable: false, writable: false
  });

  // ------------------------------------------------------------------ AntiGem (what makes sense off the UI thread)
  function payload(data) {
    if (typeof data === 'string') return Promise.resolve({ text: data });
    if (data instanceof Blob) return data.arrayBuffer().then(function (b) { return { b64: b64encode(b) }; });
    if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) return Promise.resolve({ b64: b64encode(data) });
    return Promise.resolve({ text: String(data) });
  }
  var perms = BOOT.perms || {};
  on('perms', function (d) { perms = d || {}; });
  var fs = {
    read: function (path, o) { return call('fs.read', { path: path, enc: o && o.encoding === 'base64' ? 'base64' : 'utf8' }); },
    readJSON: function (path) { return fs.read(path).then(function (t) { return JSON.parse(t); }); },
    write: function (path, data, o) { return payload(data).then(function (p) { p.path = path; p.append = !!(o && o.append); return call('fs.write', p); }).then(function () {}); },
    writeJSON: function (path, value) { return fs.write(path, JSON.stringify(value, null, 2)); },
    list: function (path) { return call('fs.list', { path: path }); },
    stat: function (path) { return call('fs.stat', { path: path }); },
    exists: function (path) { return call('fs.exists', { path: path }); },
    mkdir: function (path) { return call('fs.mkdir', { path: path }).then(function () {}); },
    delete: function (path, o) { return call('fs.delete', { path: path, recursive: !!(o && o.recursive) }).then(function () {}); },
    move: function (from, to) { return call('fs.move', { from: from, to: to }).then(function () {}); },
    url: function (path, o) { return location.origin + '/_antigem/fs?p=' + encodeURIComponent(path) + (o && o.thumb ? '&thumb=' + Math.round(o.thumb) : ''); },
    folders: function () { return call('fs.folders'); }
  };
  // Dates become epoch ms, as in the page SDK (structured clone would turn them into strings the native side can't read)
  function notifOpts(o) {
    o = Object.assign({}, o || {});
    if (o.at instanceof Date) o.at = o.at.getTime();
    if (o.until instanceof Date) o.until = o.until.getTime();
    if (o.repeat && o.repeat.until instanceof Date) o.repeat = Object.assign({}, o.repeat, { until: o.repeat.until.getTime() });
    ['countdown', 'chronometer'].forEach(function (k) { if (o[k] instanceof Date) o[k] = o[k].getTime(); });
    if (Array.isArray(o.messages)) o.messages = o.messages.map(function (m) { return Object.assign({}, m, m.time instanceof Date ? { time: m.time.getTime() } : {}); });
    return o;
  }
  define(G, 'AntiGem', {
    sdk: BOOT.sdk || 1,
    worker: true,
    app: Object.freeze(Object.assign({}, BOOT.app)),
    fs: fs,
    permissions: {
      all: function () { return Object.assign({}, perms); },
      query: function (name) { return perms[name] || 'undeclared'; }
    },
    ui: { toast: function (text, o) { send('ui.toast', { text: String(text), long: !!(o && o.long) }); } },
    device: {
      haptic: function (kind) { send('device.haptic', { kind: kind || 'light' }); },
      copy: function (text) { return call('device.copy', { text: String(text) }).then(function () {}); },
      info: function () { return call('device.info'); }
    },
    notify: {
      show: function (o) { return call('notify.show', notifOpts(o)); },
      dismiss: function (id) { return call('notify.dismiss', { id: id == null ? '' : String(id) }).then(function () {}); },
      schedule: function (o) { return call('notify.schedule', notifOpts(o)); },
      list: function () { return call('notify.list'); },
      cancel: function (id) { return call('notify.cancel', { id: String(id) }).then(function () {}); },
      cancelAll: function () { return call('notify.cancelAll').then(function () {}); }
    },
    cookies: {
      list: function (url) { return call('cookies.list', { url: url || '' }); },
      set: function (o) { o = Object.assign({}, o || {}); if (o.expires instanceof Date) o.expires = o.expires.getTime(); return call('cookies.set', o); },
      delete: function (o) { return call('cookies.delete', o || {}); }
    },
    mcp: {
      list: function (server) { return call('mcp.list', { server: String(server) }); },
      call: function (server, tool, args) { return call('mcp.call', { server: String(server), tool: String(tool), args: args || {} }); }
    }
  });
})();
