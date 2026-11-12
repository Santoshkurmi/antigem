/*
 * AntiGem Mini Apps SDK: core. Injected first in <head> of every page, before any app script.
 * The WebView only renders: storage, network, files, dialogs and devices are native, reached through
 * one origin-locked message channel. window.__AG is the internal API used by net.js and idb.js.
 */
(function () {
  'use strict';
  if (window.__AG) return;
  var BOOT = window.__AG_BOOT || {};
  try { delete window.__AG_BOOT; } catch (e) { window.__AG_BOOT = undefined; }

  // ------------------------------------------------------------------ transport
  var nativeObj = window.__antigemNative;
  var legacyObj = window.__antigemNativeLegacy;
  var nextId = 1;
  var pending = new Map();
  var listeners = Object.create(null);

  function post(obj) {
    var s = JSON.stringify(obj);
    if (nativeObj) nativeObj.postMessage(s);
    else if (legacyObj) legacyObj.postMessage(s);
    else console.warn('[AntiGem] native bridge missing (not running inside AntiGem?)');
  }

  function makeError(e) {
    var name = (e && e.name) || 'Error';
    var message = (e && e.message) || 'Unknown error';
    if (name === 'TypeError') return new TypeError(message);
    if (name === 'SyntaxError') try { return new DOMException(message, name); } catch (x) {}
    try { return new DOMException(message, name); } catch (x) { var err = new Error(message); err.name = name; return err; }
  }

  function recv(text) {
    var msg;
    try { msg = typeof text === 'string' ? JSON.parse(text) : text; } catch (e) { return; }
    if (msg.i != null) {
      var p = pending.get(msg.i);
      if (!p) return;
      pending.delete(msg.i);
      if (msg.e) p.reject(makeError(msg.e)); else p.resolve(msg.r);
    } else if (msg.ev) {
      emit(msg.ev, msg.d);
      // workers can't reach native themselves: they get the events (e.g. their WebSockets) through us
      workerPorts.forEach(function (port) { port.postMessage(msg); });
    }
  }
  if (nativeObj) nativeObj.onmessage = function (e) { recv(e.data); };

  function call(m, p) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending.set(id, { resolve: resolve, reject: reject });
      post({ i: id, m: m, p: p || {} });
    });
  }
  function send(m, p) { post({ m: m, p: p || {} }); }

  // A worker (thread) of this app talks to native through a private channel to this page (threads.js wires it up).
  var workerPorts = [];
  function attachWorker(w) {
    var ch = new MessageChannel(), port = ch.port1;
    port.onmessage = function (e) {
      var m = e.data;
      if (!m || !m.m) return;
      if (m.i == null) { send(m.m, m.p); return; }
      call(m.m, m.p).then(
        function (r) { port.postMessage({ i: m.i, r: r }); },
        function (err) { port.postMessage({ i: m.i, e: { name: (err && err.name) || 'Error', message: (err && err.message) || String(err) } }); }
      );
    };
    workerPorts.push(port);
    w.postMessage({ __ag: 'init' }, [ch.port2]);
    var terminate = w.terminate;
    w.terminate = function () {
      var i = workerPorts.indexOf(port);
      if (i >= 0) workerPorts.splice(i, 1);
      port.close();
      return terminate.call(w);
    };
  }
  function on(ev, fn) { (listeners[ev] = listeners[ev] || []).push(fn); }
  function off(ev, fn) { var l = listeners[ev]; if (l) { var i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); } }
  function emit(ev, d) {
    (listeners[ev] || []).slice().forEach(function (fn) { try { fn(d); } catch (e) { console.error(e); } });
  }

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
    catch (e) { try { obj[name] = value; } catch (x) { console.warn('[AntiGem] could not install ' + name, e); } }
  }

  var backHandlers = [];
  var AG = {
    boot: BOOT, call: call, send: send, on: on, off: off, emit: emit, define: define, attachWorker: attachWorker,
    b64encode: b64encode, b64decode: b64decode, _recv: recv,
    _back: function () {
      for (var i = backHandlers.length - 1; i >= 0; i--) { try { if (backHandlers[i]() === true) return true; } catch (e) { console.error(e); } }
      return false;
    }
  };
  Object.defineProperty(window, '__AG', { value: AG, configurable: false, enumerable: false, writable: false });
  var ready = call('hello').then(function () {});

  // ------------------------------------------------------------------ base look & theme
  var head = document.head || document.documentElement;
  var base = document.createElement('style');
  base.id = 'antigem-base';
  base.textContent =
    ':where(html){-webkit-tap-highlight-color:transparent;-webkit-text-size-adjust:100%;text-size-adjust:100%}' +
    ':where(body){overscroll-behavior:none;-webkit-touch-callout:none}' +
    ':where(img,a){-webkit-user-drag:none;-webkit-touch-callout:none}';
  head.appendChild(base);
  var themeStyle = document.createElement('style');
  themeStyle.id = 'antigem-theme';
  head.appendChild(themeStyle);

  var theme = {};
  function applyTheme(t) {
    theme = t || {};
    var colors = theme.colors || {};
    var css = ':root{';
    Object.keys(colors).forEach(function (k) { css += '--ag-' + k.replace(/_/g, '-') + ':' + colors[k] + ';'; });
    css += '--ag-app-color:' + (theme.appColor || colors.primary || '#6750a4') + ';color-scheme:' + (theme.dark ? 'dark' : 'light') + '}';
    themeStyle.textContent = css;
    document.documentElement.setAttribute('data-theme', theme.dark ? 'dark' : 'light');
    if (window.AntiGem) window.AntiGem.theme = theme;
  }
  applyTheme(BOOT.theme);
  on('theme', function (t) { applyTheme(t); });

  // CSS-only edits: swap the stylesheets in place, no reload (keeps all state)
  on('hotcss', function () {
    var v = Date.now();
    document.querySelectorAll('link[rel="stylesheet"]').forEach(function (link) {
      try {
        var u = new URL(link.href, location.href);
        if (u.origin !== location.origin) return;
        u.searchParams.set('__v', v);
        var next = link.cloneNode();
        next.href = u.href;
        next.onload = function () { link.remove(); };
        link.after(next);
      } catch (e) {}
    });
  });

  // ------------------------------------------------------------------ storage (native kv)
  var areas = { l: new Map(), s: new Map() };
  var kv = BOOT.kv || {};
  Object.keys(kv).forEach(function (k) {
    var a = k.slice(0, 2);
    if (a === 'l:') areas.l.set(k.slice(2), kv[k]);
    else if (a === 's:') areas.s.set(k.slice(2), kv[k]);
  });
  var sessionMap = new Map();
  Object.keys(BOOT.ss || {}).forEach(function (k) { sessionMap.set(k, BOOT.ss[k]); });

  // writes are batched per tick and applied natively in order (memory first, disk right after)
  var kvQueue = [];
  function kvOp(o) {
    kvQueue.push(o);
    if (kvQueue.length === 1) queueMicrotask(function () { var ops = kvQueue; kvQueue = []; send('kv', { ops: ops }); });
  }

  function makeStorage(map, area) {
    var t = {
      key: function (i) { var k = Array.from(map.keys())[i]; return k === undefined ? null : k; },
      getItem: function (k) { k = String(k); return map.has(k) ? map.get(k) : null; },
      setItem: function (k, v) { k = String(k); v = String(v); map.set(k, v); kvOp({ a: area, op: 'set', k: k, v: v }); },
      removeItem: function (k) { k = String(k); if (map.delete(k)) kvOp({ a: area, op: 'del', k: k }); },
      clear: function () { map.clear(); kvOp({ a: area, op: 'clear' }); }
    };
    Object.defineProperty(t, 'length', { get: function () { return map.size; }, configurable: true });
    try { if (window.Storage) Object.setPrototypeOf(t, window.Storage.prototype); } catch (e) {}
    // localStorage.foo = 'x', localStorage.foo, Object.keys(localStorage) and 'foo' in localStorage all work
    return new Proxy(t, {
      get: function (o, p) {
        if (typeof p === 'symbol' || p in o) { var v = o[p]; return typeof v === 'function' ? v.bind(o) : v; }
        return map.has(p) ? map.get(p) : undefined;
      },
      set: function (o, p, v) { if (typeof p === 'symbol' || p in o) return true; o.setItem(p, v); return true; },
      deleteProperty: function (o, p) { o.removeItem(p); return true; },
      has: function (o, p) { return p in o || map.has(p); },
      ownKeys: function () { return Array.from(map.keys()); },
      getOwnPropertyDescriptor: function (o, p) {
        return map.has(p) ? { value: map.get(p), enumerable: true, configurable: true, writable: true } : undefined;
      }
    });
  }
  var localStore = makeStorage(areas.l, 'l');
  var sessionStore = makeStorage(sessionMap, 'ss');
  define(window, 'localStorage', localStore);
  define(window, 'sessionStorage', sessionStore);

  // another page of this app changed storage: update our copy, fire `storage` like a browser does across tabs
  on('kv', function (d) {
    (d.ops || []).forEach(function (o) {
      var map = areas[o.a];
      if (!map) return;
      var old = map.has(o.k) ? map.get(o.k) : null;
      if (o.op === 'set') map.set(o.k, o.v); else if (o.op === 'del') map.delete(o.k); else map.clear();
      if (o.a === 'l') {
        try {
          var ev = new StorageEvent('storage', { key: o.op === 'clear' ? null : o.k, oldValue: old, newValue: o.op === 'set' ? o.v : null, url: location.href });
          Object.defineProperty(ev, 'storageArea', { value: localStore });
          window.dispatchEvent(ev);
        } catch (e) {}
      } else emit('storage', { key: o.op === 'clear' ? null : o.k });
    });
  });

  var storage = {
    get: function (key, fallback) {
      var v = areas.s.get(String(key));
      if (v === undefined) return fallback;
      try { return JSON.parse(v); } catch (e) { return v; }
    },
    set: function (key, value) {
      key = String(key);
      var v = JSON.stringify(value === undefined ? null : value);
      areas.s.set(key, v);
      kvOp({ a: 's', op: 'set', k: key, v: v });
    },
    remove: function (key) { key = String(key); if (areas.s.delete(key)) kvOp({ a: 's', op: 'del', k: key }); },
    keys: function () { return Array.from(areas.s.keys()); },
    clear: function () { areas.s.clear(); kvOp({ a: 's', op: 'clear' }); }
  };

  // ------------------------------------------------------------------ files
  function payload(data) {
    if (typeof data === 'string') return Promise.resolve({ text: data });
    if (data instanceof Blob) return data.arrayBuffer().then(function (b) { return { b64: b64encode(b) }; });
    if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) return Promise.resolve({ b64: b64encode(data) });
    return Promise.resolve({ text: String(data) });
  }
  var fs = {
    read: function (path, o) { return call('fs.read', { path: path, enc: o && o.encoding === 'base64' ? 'base64' : 'utf8' }); },
    readJSON: function (path) { return fs.read(path).then(function (t) { return JSON.parse(t); }); },
    write: function (path, data, o) {
      return payload(data).then(function (p) {
        p.path = path; p.append = !!(o && o.append);
        return call('fs.write', p);
      }).then(function () {});
    },
    writeJSON: function (path, value) { return fs.write(path, JSON.stringify(value, null, 2)); },
    list: function (path) { return call('fs.list', { path: path }); },
    stat: function (path) { return call('fs.stat', { path: path }); },
    exists: function (path) { return call('fs.exists', { path: path }); },
    mkdir: function (path) { return call('fs.mkdir', { path: path }).then(function () {}); },
    delete: function (path, o) { return call('fs.delete', { path: path, recursive: !!(o && o.recursive) }).then(function () {}); },
    move: function (from, to) { return call('fs.move', { from: from, to: to }).then(function () {}); },
    /** URL for <img>/<video>/fetch. {thumb: px}: a native JPEG thumbnail (photos and video frames). HEIC/HEIF become JPEG. */
    url: function (path, o) { return location.origin + '/_antigem/fs?p=' + encodeURIComponent(path) + (o && o.thumb ? '&thumb=' + Math.round(o.thumb) : ''); },
    pick: function (o) { return call('fs.pick', { accept: (o && o.accept) || '*/*', multiple: !!(o && o.multiple) }); },
    /** The user picks a folder in AntiGem's file explorer; the app gets the manifest's "pick" access to it. → {path, access} | null */
    pickFolder: function () { return call('fs.pickFolder'); },
    /** Folders this app may use: [{path, access: "read"|"write"|"readwrite", source: "own"|"shared"|"manifest"|"picked"}] */
    folders: function () { return call('fs.folders'); },
    /** Gives back access to a picked folder (all picked folders when no path). */
    forgetFolder: function (path) { return call('fs.forgetFolder', { path: path || '' }).then(function () {}); }
  };

  // ------------------------------------------------------------------ permissions
  var perms = BOOT.perms || {};
  on('perms', function (d) {
    var old = perms;
    perms = d || {};
    Object.keys(perms).forEach(function (n) { if (old[n] !== perms[n]) emit('permission', { name: n, state: perms[n] }); });
  });
  var permissions = {
    all: function () { return Object.assign({}, perms); },
    query: function (name) { return perms[name] || 'undeclared'; },
    request: function (name) {
      return call('perm.request', { name: name }).then(function (s) {
        if (s !== 'undeclared' && perms[name] !== s) { perms[name] = s; emit('permission', { name: name, state: s }); }
        return s;
      });
    }
  };

  // ------------------------------------------------------------------ native UI & device
  function msg(x) { return typeof x === 'string' ? { message: x } : (x || {}); }
  var colorCtx = null;
  /** Any CSS color (also var(--x)) as #rrggbb; '' when it isn't a color (the native side then resets the bar). */
  function cssToHex(c) {
    try {
      c = String(c).trim();
      var m0 = c.match(/^var\(\s*(--[\w-]+)\s*(?:,\s*(.+))?\)$/);
      if (m0) c = (getComputedStyle(document.documentElement).getPropertyValue(m0[1]) || '').trim() || (m0[2] || '').trim();
      if (!c) return '';
      colorCtx = colorCtx || document.createElement('canvas').getContext('2d');
      // a value canvas doesn't accept leaves the sentinel: not a color
      colorCtx.fillStyle = '#010203'; colorCtx.fillStyle = c;
      var v = colorCtx.fillStyle;
      if (v === '#010203' && c.toLowerCase() !== '#010203') return '';
      if (v.charAt(0) === '#') return v;
      var m = v.match(/rgba?\(([^)]+)\)/);
      if (m) return '#' + m[1].split(',').slice(0, 3).map(function (n) { return ('0' + Math.round(parseFloat(n)).toString(16)).slice(-2); }).join('');
    } catch (e) {}
    return '';
  }
  var ui = {
    toast: function (text, o) { send('ui.toast', { text: String(text), long: !!(o && o.long) }); },
    alert: function (x) { return call('ui.alert', msg(x)).then(function () {}); },
    confirm: function (x) { return call('ui.confirm', msg(x)); },
    prompt: function (x) { return call('ui.prompt', msg(x)); },
    sheet: function (o) { return call('ui.sheet', o || {}); },
    pickDate: function (o) { return call('ui.date', o || {}); },
    pickTime: function (o) { return call('ui.time', o || {}); },
    /** Native full-screen viewer: items = paths / https URLs / {src, type: 'image'|'video', title}. → {index} when closed. */
    viewMedia: function (o) { return call('ui.viewMedia', Array.isArray(o) ? { items: o } : (o || {})); },
    statusBar: function (o) { send('ui.statusBar', { color: o && o.color ? cssToHex(o.color) : '' }); }
  };
  var device = {
    haptic: function (kind) { send('device.haptic', { kind: kind || 'light' }); },
    share: function (o) { return call('device.share', o || {}).then(function () {}); },
    copy: function (text) { return call('device.copy', { text: String(text) }).then(function () {}); },
    paste: function () { return call('device.paste'); },
    openUrl: function (url) { return call('device.openUrl', { url: String(url) }).then(function () {}); },
    info: function () { return call('device.info'); },
    /** {network: {online, type, metered}, battery: {level, charging, saver}} */
    status: function () { return call('device.status'); }
  };
  function when(o) {
    o = Object.assign({}, o || {});
    if (o.at instanceof Date) o.at = o.at.getTime();
    if (o.repeat && o.repeat.until instanceof Date) o.repeat = Object.assign({}, o.repeat, { until: o.repeat.until.getTime() });
    if (o.until instanceof Date) o.until = o.until.getTime();
    return o;
  }
  // Notification options: Dates become epoch ms (countdown, chronometer, message times, reminders)
  function notifOpts(o) {
    o = when(o);
    ['countdown', 'chronometer'].forEach(function (k) { if (o[k] instanceof Date) o[k] = o[k].getTime(); });
    if (Array.isArray(o.messages)) o.messages = o.messages.map(function (m) { return Object.assign({}, m, m.time instanceof Date ? { time: m.time.getTime() } : {}); });
    return o;
  }
  var notify = {
    /** Shows or updates (same id) a notification → {id}. Options: see README "Notifications". */
    show: function (o) { return call('notify.show', notifOpts(o)); },
    /** An app notification channel the user can tune in Android settings: {id, name, importance, sound, vibrate, lights, badge}. */
    channel: function (o) { return call('notify.channel', o || {}); },
    deleteChannel: function (id) { return call('notify.deleteChannel', { id: String(id) }).then(function () {}); },
    /** The app's notifications on screen now. */
    active: function () { return call('notify.active'); },
    /** Removes a shown notification (all of the app's without an id); scheduled reminders stay. */
    dismiss: function (id) { return call('notify.dismiss', { id: id == null ? '' : String(id) }).then(function () {}); },
    /** Reminder that survives restarts and reboots: {id, title, body, at: Date|ms|"HH:MM", delay, repeat}. */
    schedule: function (o) { return call('notify.schedule', notifOpts(o)); },
    list: function () { return call('notify.list'); },
    cancel: function (id) { return call('notify.cancel', { id: String(id) }).then(function () {}); },
    cancelAll: function () { return call('notify.cancelAll').then(function () {}); }
  };

  // The app's own cookie jar (persistent, like a browser profile). Only hosts the app may reach.
  var cookies = {
    list: function (url) { return call('cookies.list', { url: url || '' }); },
    get: function (url, name) { return cookies.list(url).then(function (a) { return a.filter(function (c) { return c.name === name; })[0] || null; }); },
    set: function (o) { o = Object.assign({}, o); if (o.expires instanceof Date) o.expires = o.expires.getTime(); return call('cookies.set', o); },
    /** A "Cookie:" header copied from a browser ("a=1; b=2"), saved for url's host. */
    import: function (url, header, o) { return call('cookies.import', { url: url, header: String(header || ''), days: o && o.days }); },
    delete: function (o) { return call('cookies.delete', o || {}); },
    clear: function (url) { return call('cookies.clear', { url: url || '' }); }
  };

  // Background tasks: the manifest's "background" script runs with the app closed.
  var bgHandlers = Object.create(null);
  var background = {
    isBackground: !!(BOOT.page || {}).background,
    /** {every: "1h"} | {delay: "10m"} | {at: "07:30", repeat: "daily"}, plus network: "any"|"unmetered", charging, batteryNotLow */
    register: function (name, opts) { return call('bg.register', { name: String(name), opts: when(opts) }); },
    list: function () { return call('bg.list'); },
    cancel: function (name) { return call('bg.cancel', { name: name == null ? '' : String(name) }).then(function () {}); },
    run: function (name) { return call('bg.run', { name: String(name) }).then(function () {}); },
    /** In the background script: what to do when task [name] runs ('*' = any task). May be async. */
    on: function (name, fn) { bgHandlers[String(name)] = fn; }
  };
  var nav = {
    push: function (path, params) {
      var p = String(path || '').replace(/^\.?\//, '');
      if (params && typeof params === 'object') {
        var q = new URLSearchParams();
        Object.keys(params).forEach(function (k) { q.set(k, typeof params[k] === 'object' ? JSON.stringify(params[k]) : String(params[k])); });
        var qs = q.toString();
        if (qs) p += (p.indexOf('?') >= 0 ? '&' : '?') + qs;
      }
      send('nav.push', { path: p });
    },
    back: function () { send('nav.back'); },
    close: function () { send('nav.close'); }
  };

  var publicEvents = { theme: 1, resume: 1, pause: 1, storage: 1, permission: 1, notification: 1, intent: 1, network: 1, battery: 1 };

  // pause / resume. Android may freeze a hidden page, and a message sent to it right then can be lost, so the
  // activity's signal is combined with the page's own visibility and freeze / resume events: each change fires once.
  // "resume" = back on screen after being away (not on the first open).
  var shown = true, lastResume = 0;
  function goneAway() { if (shown) { shown = false; emit('pause', null); } }
  function cameBack(wasAway) {
    if (!shown) { shown = true; lastResume = Date.now(); emit('resume', null); }
    // it surely was away (the activity says so, or the page was frozen) but the "pause" side got lost
    else if (wasAway && Date.now() - lastResume > 2000) { lastResume = Date.now(); emit('resume', null); }
  }
  on('lifecycle', function (s) { if (s === 'pause') goneAway(); else cameBack(true); });
  document.addEventListener('visibilitychange', function () { if (document.visibilityState === 'hidden') goneAway(); else cameBack(false); });
  document.addEventListener('freeze', goneAway);
  document.addEventListener('resume', function () { cameBack(true); });

  // Intents: share / open with another mini app (or Android), and what others send to this one
  var intentApi = {
    /** The last intent this app received ({action, type, text?, url?, path?, title?, data?, from}), or null. */
    current: null,
    /** {action: 'share'|'open'|'edit'|'view'|…, type?, text?, url?, path?, title?, data?} → {to} after the user picks the app */
    send: function (o) { return call('intent.send', o || {}); }
  };
  on('intent', function (d) { intentApi.current = d; });
  var AntiGem = {
    sdk: BOOT.sdk || 1,
    app: Object.freeze(Object.assign({}, BOOT.app)),
    page: Object.freeze({ id: (BOOT.page || {}).id, path: (BOOT.page || {}).path, params: Object.freeze(Object.assign({}, (BOOT.page || {}).params)), root: !!(BOOT.page || {}).root, background: !!(BOOT.page || {}).background }),
    ready: ready,
    theme: theme,
    on: function (ev, fn) { if (publicEvents[ev] && typeof fn === 'function') on(ev, fn); },
    off: function (ev, fn) { off(ev, fn); },
    storage: storage,
    fs: fs,
    permissions: permissions,
    ui: ui,
    device: device,
    notify: notify,
    nav: nav,
    cookies: cookies,
    background: background,
    intent: intentApi,
    onBack: function (fn) {
      backHandlers.push(fn);
      return function () { var i = backHandlers.indexOf(fn); if (i >= 0) backHandlers.splice(i, 1); };
    },
    mcp: {
      list: function (server) { return call('mcp.list', { server: String(server) }); },
      call: function (server, tool, args) { return call('mcp.call', { server: String(server), tool: String(tool), args: args || {} }); }
    },
    dev: {
      console: function () { send('dev.console'); },
      devtools: function () { send('dev.devtools'); }
    },
    reload: function () { send('app.reload'); }
  };
  Object.defineProperty(window, 'AntiGem', { value: AntiGem, configurable: false, enumerable: true, writable: false });

  // ------------------------------------------------------------------ running a background task
  // This page is the invisible one of a background task: once the background script has run, call its handler.
  if (BOOT.task) {
    window.addEventListener('load', function () {
      var t = BOOT.task;
      var finish = function (ok, err) {
        send('bg.done', { runId: t.runId, ok: ok, error: err ? String((err && err.stack) || (err && err.message) || err) : '' });
      };
      var h = bgHandlers[t.name] || bgHandlers['*'];
      if (typeof h !== 'function') { finish(false, 'No handler: call AntiGem.background.on("' + t.name + '", fn) in the background script'); return; }
      Promise.resolve().then(function () { return h({ name: t.name, reason: t.reason, runId: t.runId, data: t.data || null }); })
        .then(function () { finish(true); }, function (e) { console.error(e); finish(false, e); });
    });
  }

  // ------------------------------------------------------------------ closing the doors the native layer can't police
  // WebRTC opens its own connections (no CSP covers it); service workers would sit between the app and the runtime.
  ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel'].forEach(function (n) { define(window, n, undefined); });
  try {
    if (navigator.serviceWorker) Object.defineProperty(navigator.serviceWorker, 'register', {
      value: function () { return Promise.reject(new DOMException('Service workers are not available in mini apps', 'NotSupportedError')); }
    });
  } catch (e) {}
})();
