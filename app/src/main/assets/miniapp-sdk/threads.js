/*
 * AntiGem Mini Apps SDK: threads. Runs in pages and workers.
 *  - Every Worker gets the SDK first (native fetch/XHR/WebSocket/indexedDB/AntiGem inside the thread):
 *    same-origin worker files get it injected by the runtime (?__agw), blob: workers get a small bootstrap.
 *  - AntiGem.thread: run a function on another CPU core without writing a worker file. Each thread is a real
 *    OS thread with its own V8 engine; a pool sized to the phone's cores is kept warm and reused.
 */
(function () {
  'use strict';
  var G = globalThis;
  var AG = G.__AG;
  if (!AG || AG._threads) return;
  AG._threads = true;

  var SDK_URL = location.origin + '/_antigem/worker-sdk.js';
  var OrigWorker = G.Worker;

  if (typeof OrigWorker === 'function') {
    var Worker = class Worker extends OrigWorker {
      constructor(url, options) {
        var o = Object.assign({}, options || {});
        var u = new URL(String(url), location.href);
        var module = o.type === 'module';
        var target;
        if (u.protocol === 'blob:') {
          // a blob can't be changed: start a tiny bootstrap that loads the SDK, then the app's blob
          var boot = module
            ? 'import ' + JSON.stringify(SDK_URL) + ';\nimport ' + JSON.stringify(u.href) + ';'
            : 'importScripts(' + JSON.stringify(SDK_URL) + ', ' + JSON.stringify(u.href) + ');';
          target = URL.createObjectURL(new Blob([boot], { type: 'text/javascript' }));
        } else if (u.origin === location.origin && (u.protocol === 'https:' || u.protocol === 'http:')) {
          u.searchParams.set('__agw', module ? 'm' : 'c'); // the runtime puts the SDK in front of the file
          target = u.href;
        } else {
          target = u.href; // data: and other origins: no SDK (they can't reach the app's origin anyway)
        }
        super(target, o);
        AG.attachWorker(this);
      }
    };
    AG.define(G, 'Worker', Worker);
  }

  // ------------------------------------------------------------------ AntiGem.thread
  // What each pool thread runs: receive {src, args}, call the function, send the result back.
  var RUNNER = '(' + function () {
    var cache = new Map();
    function transferList(v) {
      if (v instanceof ArrayBuffer) return [v];
      if (ArrayBuffer.isView(v)) return [v.buffer];
      return [];
    }
    self.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || d.__agt !== 1) return;
      Promise.resolve().then(function () {
        var f = cache.get(d.src);
        if (!f) { f = (0, eval)('(' + d.src + ')'); cache.set(d.src, f); }
        if (typeof f !== 'function') throw new TypeError('AntiGem.thread.run needs a function');
        return f.apply(null, d.args || []);
      }).then(function (r) {
        try { self.postMessage({ __agt: 1, id: d.id, ok: true, r: r }, transferList(r)); }
        catch (err) { self.postMessage({ __agt: 1, id: d.id, ok: false, e: { name: 'DataCloneError', message: 'The result could not be sent back: ' + err.message } }); }
      }, function (err) {
        self.postMessage({ __agt: 1, id: d.id, ok: false, e: { name: (err && err.name) || 'Error', message: (err && err.message) || String(err), stack: err && err.stack } });
      });
    });
  }.toString() + ')();';
  var runnerUrl = null;

  function threadError(e) {
    var err = new Error(e.message);
    err.name = e.name || 'Error';
    if (e.stack) err.stack = e.stack;
    return err;
  }

  function cores() { return (G.navigator && G.navigator.hardwareConcurrency) || 4; }

  function Pool(size) {
    size = Math.max(1, Math.min(Number(size) || Math.max(1, cores() - 1), 16));
    var slots = [], queue = [], nextId = 1, closed = false;

    function spawn() {
      runnerUrl = runnerUrl || URL.createObjectURL(new Blob([RUNNER], { type: 'text/javascript' }));
      var slot = { w: new G.Worker(runnerUrl), job: null };
      slot.w.onmessage = function (e) {
        var d = e.data;
        if (!d || d.__agt !== 1) return;
        var job = slot.job;
        slot.job = null;
        if (job && job.id === d.id) { if (d.ok) job.resolve(d.r); else job.reject(threadError(d.e)); }
        pump();
      };
      slot.w.onerror = function (ev) {
        if (ev && ev.preventDefault) ev.preventDefault();
        var job = slot.job;
        slot.job = null;
        if (job) job.reject(new Error((ev && ev.message) || 'Thread crashed'));
        pump();
      };
      slots.push(slot);
      return slot;
    }

    function pump() {
      while (queue.length && !closed) {
        var slot = null;
        for (var i = 0; i < slots.length; i++) if (!slots[i].job) { slot = slots[i]; break; }
        if (!slot && slots.length < size) slot = spawn();
        if (!slot) return;
        var job = queue.shift();
        slot.job = job;
        try { slot.w.postMessage({ __agt: 1, id: job.id, src: job.src, args: job.args }, job.transfer || []); }
        catch (err) { slot.job = null; job.reject(err); }
      }
    }

    var pool = {
      size: size,
      /** Runs fn(...args) on a free thread. fn is copied as source: it can't use outside variables, pass them in args. */
      run: function (fn, args, opts) {
        if (closed) return Promise.reject(new DOMException('This thread pool is closed', 'InvalidStateError'));
        var src = typeof fn === 'function' ? fn.toString() : String(fn);
        return new Promise(function (resolve, reject) {
          queue.push({ id: nextId++, src: src, args: args || [], transfer: opts && opts.transfer, resolve: resolve, reject: reject });
          pump();
        });
      },
      /** fn(item) for every item, spread over all threads; results in the same order. */
      map: function (items, fn) { return Promise.all(Array.from(items, function (x) { return pool.run(fn, [x]); })); },
      close: function () {
        closed = true;
        slots.forEach(function (s) { s.w.terminate(); });
        slots = [];
        queue.splice(0).forEach(function (j) { j.reject(new DOMException('The thread pool was closed', 'AbortError')); });
      }
    };
    return pool;
  }

  var shared = null;
  var thread = {
    get cores() { return cores(); },
    run: function (fn, args, opts) { shared = shared || Pool(); return shared.run(fn, args, opts); },
    map: function (items, fn) { shared = shared || Pool(); return shared.map(items, fn); },
    pool: function (o) { return Pool(o && typeof o === 'object' ? o.size : o); }
  };
  if (G.AntiGem) G.AntiGem.thread = thread;
})();
