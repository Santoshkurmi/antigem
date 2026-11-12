/* AntiGem mini apps: IndexedDB stored in native SQLite (MiniAppIdb.kt).
 * Replaces window.indexedDB and the IDB* classes. Keys are encoded into strings whose ASCII order is IDB key
 * order, so native range queries are plain TEXT comparisons. Values are a tagged-JSON structured-clone subset.
 * Known gaps: no `blocked`/`versionchange` between connections, prevunique returns the lowest primary key per
 * key (spec) but an index created in the same upgrade as an auto-generated in-line key won't see that key. */
(function () {
  'use strict';
  const AG = globalThis.__AG;
  if (!AG || typeof AG.call !== 'function') return;

  const KEY = {}; // token for internal constructors
  const NONE = {}; // "no value" from key path evaluation / lazy caches
  const AGAIN = {}; // a job queued a follow-up job instead of finishing
  const PAGE = 50; // cursor prefetch size
  const DIRS = ['next', 'nextunique', 'prev', 'prevunique'];
  const hasOwn = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
  const err = (name, msg) => new DOMException(msg || name, name);
  const toErr = (e) => (e instanceof DOMException ? e : err((e && e.name) || 'UnknownError', (e && e.message) || String(e)));

  // A macrotask without setTimeout's nesting clamp: events never fire inside the caller's task.
  const macro = (() => {
    if (typeof MessageChannel !== 'function') return (fn) => setTimeout(fn, 0);
    const q = [];
    const mc = new MessageChannel();
    mc.port1.onmessage = () => { const fn = q.shift(); if (fn) fn(); };
    return (fn) => { q.push(fn); mc.port2.postMessage(0); };
  })();

  const b64e = AG.b64encode ? (u8) => AG.b64encode(u8) : (u8) => {
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
  };
  const b64d = AG.b64decode ? (s) => AG.b64decode(s) : (s) => {
    const bin = atob(s);
    const u = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
    return u;
  };
  const isBinary = (v) => v instanceof ArrayBuffer || ArrayBuffer.isView(v);
  const bytesOf = (v) => (v instanceof ArrayBuffer ? new Uint8Array(v) : new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
  const tagOf = (v) => Object.prototype.toString.call(v).slice(8, -1);

  // ---------- keys ----------
  function toKey(v, seen) {
    if (typeof v === 'number') { if (Number.isNaN(v)) throw err('DataError', 'NaN is not a valid key.'); return v === 0 ? 0 : v; }
    if (typeof v === 'string') return v;
    if (v instanceof Date) { const t = v.getTime(); if (Number.isNaN(t)) throw err('DataError', 'Invalid Date is not a valid key.'); return new Date(t); }
    if (isBinary(v)) return v;
    if (Array.isArray(v)) {
      seen = seen || new Set();
      if (seen.has(v)) throw err('DataError', 'A cyclic array is not a valid key.');
      seen.add(v);
      const out = [];
      for (let i = 0; i < v.length; i++) {
        if (!hasOwn(v, i)) throw err('DataError', 'A sparse array is not a valid key.');
        out.push(toKey(v[i], seen));
      }
      seen.delete(v);
      return out;
    }
    throw err('DataError', 'The parameter is not a valid key.');
  }

  const dv = new DataView(new ArrayBuffer(8));
  const hex8 = (n) => n.toString(16).padStart(8, '0');
  // float64 bits made sortable: positives get the sign bit set, negatives are inverted
  function encNum(n) {
    dv.setFloat64(0, n === 0 ? 0 : n);
    let hi = dv.getUint32(0), lo = dv.getUint32(4);
    if (hi & 0x80000000) { hi = ~hi >>> 0; lo = ~lo >>> 0; } else hi = (hi | 0x80000000) >>> 0;
    return hex8(hi) + hex8(lo);
  }
  // number 1 < date 2 < string 3 < binary 4 < array 5; '.' and '!' terminators sort below everything
  function encKey(k) {
    if (typeof k === 'number') return '1' + encNum(k);
    if (typeof k === 'string') {
      const p = new Array(k.length);
      for (let i = 0; i < k.length; i++) p[i] = (k.charCodeAt(i) + 0x10000).toString(16).slice(1);
      return '3' + p.join('') + '.';
    }
    if (k instanceof Date) return '2' + encNum(k.getTime());
    if (Array.isArray(k)) { let s = '5'; for (const e of k) s += encKey(e); return s + '!'; }
    const b = bytesOf(k), p = new Array(b.length);
    for (let i = 0; i < b.length; i++) p[i] = (b[i] + 0x100).toString(16).slice(1);
    return '4' + p.join('') + '.';
  }
  const cmpEnc = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
  function keyJ(k) {
    if (typeof k === 'number') return Number.isFinite(k) ? k : { $t: 'n', v: String(k) };
    if (typeof k === 'string') return k;
    if (k instanceof Date) return { $t: 'd', v: k.getTime() };
    if (Array.isArray(k)) return k.map(keyJ);
    return { $t: 'ab', v: b64e(bytesOf(k)) };
  }
  const keyJson = (k) => JSON.stringify(keyJ(k));

  // ---------- values (structured clone subset as tagged JSON) ----------
  function encVal(v, ctx) {
    switch (typeof v) {
      case 'undefined': return { $t: 'u' };
      case 'number': return Number.isFinite(v) ? (Object.is(v, -0) ? { $t: 'n', v: '-0' } : v) : { $t: 'n', v: String(v) };
      case 'string': case 'boolean': return v;
      case 'bigint': return { $t: 'bi', v: v.toString() };
      case 'function': case 'symbol': throw err('DataCloneError', `A ${typeof v} could not be cloned.`);
    }
    if (v === null) return null;
    if (ctx.seen.has(v)) throw err('DataCloneError', 'Values with cycles are not supported.');
    ctx.seen.add(v);
    let out;
    if (Array.isArray(v)) {
      out = new Array(v.length);
      for (let i = 0; i < v.length; i++) out[i] = encVal(v[i], ctx);
    } else if (v instanceof Date) out = { $t: 'd', v: String(v.getTime()) };
    else if (v instanceof RegExp) out = { $t: 're', s: v.source, f: v.flags };
    else if (v instanceof ArrayBuffer) out = { $t: 'ab', v: b64e(new Uint8Array(v)) };
    else if (v instanceof DataView) out = { $t: 'dv', v: b64e(bytesOf(v)) };
    else if (ArrayBuffer.isView(v)) out = { $t: 'ta', c: tagOf(v), v: b64e(bytesOf(v)) };
    else if (typeof Blob !== 'undefined' && v instanceof Blob) {
      out = { $t: 'blob', type: v.type, v: '' };
      if (typeof File !== 'undefined' && v instanceof File) { out.name = v.name; out.lm = v.lastModified; }
      ctx.blobs.push([out, v]);
    } else if (v instanceof Map) {
      out = { $t: 'map', v: [] };
      for (const [k, x] of v) out.v.push([encVal(k, ctx), encVal(x, ctx)]);
    } else if (v instanceof Set) {
      out = { $t: 'set', v: [] };
      for (const x of v) out.v.push(encVal(x, ctx));
    } else if (v instanceof Error) out = { $t: 'err', n: v.name, m: v.message };
    else if (v instanceof Boolean || v instanceof Number || v instanceof String) out = { $t: 'box', v: encVal(v.valueOf(), ctx) };
    else {
      const o = {};
      for (const k of Object.keys(v)) o[k] = encVal(v[k], ctx);
      out = hasOwn(o, '$t') ? { $t: 'o', v: o } : o;
    }
    ctx.seen.delete(v);
    return out;
  }
  // { j, prep }: prep (only when Blobs are inside) fills the Blob bytes asynchronously
  function cloneOut(value) {
    const ctx = { seen: new Set(), blobs: [] };
    const j = encVal(value, ctx);
    if (!ctx.blobs.length) return { s: JSON.stringify(j) };
    const prep = Promise.all(ctx.blobs.map(([slot, blob]) => blob.arrayBuffer().then((ab) => { slot.v = b64e(new Uint8Array(ab)); })));
    return { j, prep };
  }
  function plain(o) {
    const out = {};
    for (const k of Object.keys(o)) {
      const x = decVal(o[k]);
      if (k === '__proto__') Object.defineProperty(out, k, { value: x, enumerable: true, writable: true, configurable: true });
      else out[k] = x;
    }
    return out;
  }
  function decVal(j) {
    if (j === null || typeof j !== 'object') return j;
    if (Array.isArray(j)) return j.map(decVal);
    if (!hasOwn(j, '$t')) return plain(j);
    switch (j.$t) {
      case 'u': return undefined;
      case 'n': return j.v === '-0' ? -0 : Number(j.v);
      case 'bi': return BigInt(j.v);
      case 'd': return new Date(Number(j.v));
      case 're': return new RegExp(j.s, j.f);
      case 'ab': return b64d(j.v).buffer;
      case 'dv': return new DataView(b64d(j.v).buffer);
      case 'ta': { const C = globalThis[j.c] || Uint8Array; const b = b64d(j.v).buffer; return new C(b); }
      case 'blob': {
        const parts = [b64d(j.v)];
        if (j.name !== undefined && typeof File !== 'undefined') return new File(parts, j.name, { type: j.type, lastModified: j.lm });
        return new Blob(parts, { type: j.type });
      }
      case 'map': return new Map(j.v.map(([k, x]) => [decVal(k), decVal(x)]));
      case 'set': return new Set(j.v.map(decVal));
      case 'err': { const C = globalThis[j.n] && globalThis[j.n].prototype instanceof Error ? globalThis[j.n] : Error; const e = new C(j.m); if (C === Error && j.n) e.name = j.n; return e; }
      case 'box': return Object(decVal(j.v));
      case 'o': return plain(j.v);
      default: return plain(j);
    }
  }
  const parseVal = (s) => decVal(JSON.parse(s));
  const parseKey = (s) => decVal(JSON.parse(s));

  // ---------- key paths ----------
  const IDENT = /^[$A-Z_a-zª-￿][$\wª-￿]*$/;
  const validPath = (p) => p === '' || p.split('.').every((s) => IDENT.test(s));
  const validKeyPath = (kp) => (Array.isArray(kp) ? kp.length > 0 && kp.every((p) => typeof p === 'string' && validPath(p)) : typeof kp === 'string' && validPath(kp));
  const normKeyPath = (kp) => (Array.isArray(kp) ? kp.map(String) : String(kp));

  function evalPath(value, kp) {
    if (Array.isArray(kp)) {
      const out = [];
      for (const p of kp) { const r = evalPath(value, p); if (r === NONE) return NONE; out.push(r); }
      return out;
    }
    if (kp === '') return value;
    let cur = value;
    for (const seg of kp.split('.')) {
      if (typeof cur === 'string' && seg === 'length') cur = cur.length;
      else if (Array.isArray(cur) && seg === 'length') cur = cur.length;
      else if (typeof Blob !== 'undefined' && cur instanceof Blob && (seg === 'size' || seg === 'type')) cur = cur[seg];
      else if (typeof File !== 'undefined' && cur instanceof File && (seg === 'name' || seg === 'lastModified')) cur = cur[seg];
      else if (cur === null || typeof cur !== 'object' || !hasOwn(cur, seg)) return NONE;
      else cur = cur[seg];
    }
    return cur;
  }
  function canInject(value, kp) {
    const segs = kp.split('.');
    let cur = value;
    for (let i = 0; i < segs.length - 1; i++) {
      if (cur === null || typeof cur !== 'object') return false;
      if (!hasOwn(cur, segs[i])) return true;
      cur = cur[segs[i]];
    }
    return cur !== null && typeof cur === 'object';
  }
  // [indexName, encodedKey, keyJson, unique] for every index entry the value produces
  function entriesFor(ix, value, out, withName) {
    const r = evalPath(value, ix.kp);
    if (r === NONE) return;
    const push = (k) => out.push(withName ? [ix.name, encKey(k), keyJson(k), ix.u ? 1 : 0] : [encKey(k), keyJson(k)]);
    if (ix.m && Array.isArray(r)) {
      const seen = new Set();
      for (const el of r) {
        let k; try { k = toKey(el); } catch (_) { continue; }
        const e = encKey(k);
        if (!seen.has(e)) { seen.add(e); push(k); }
      }
    } else {
      let k; try { k = toKey(r); } catch (_) { return; }
      push(k);
    }
  }

  // ---------- small DOM-ish pieces ----------
  class StringList extends Array {
    item(i) { return this[i] === undefined ? null : this[i]; }
    contains(s) { return this.includes(String(s)); }
  }
  const list = (a) => StringList.from([...a].sort());

  function handlers(proto, names) {
    for (const n of names) {
      Object.defineProperty(proto, 'on' + n, {
        configurable: true, enumerable: true,
        get() { return (this._on && this._on[n]) || null; },
        set(fn) {
          const on = this._on || (this._on = {});
          if (!(n in on)) {
            this.addEventListener(n, (e) => {
              const h = on[n];
              if (typeof h !== 'function') return;
              try { if (h.call(this, e) === false) e.preventDefault(); } catch (x) { e._agThrew = true; setTimeout(() => { throw x; }); }
            });
          }
          on[n] = typeof fn === 'function' ? fn : null;
        },
      });
    }
  }
  function setTarget(ev, t) { Object.defineProperty(ev, 'target', { value: t, configurable: true }); }
  // Fires one event at request → transaction → database (IDB's bubbling path), honoring stopPropagation.
  function fireChain(ev, chain) {
    let stopped = false;
    const sp = ev.stopPropagation, sip = ev.stopImmediatePropagation;
    ev.stopPropagation = function () { stopped = true; return sp.call(this); };
    ev.stopImmediatePropagation = function () { stopped = true; return sip.call(this); };
    for (const t of chain) { if (!t) continue; t.dispatchEvent(ev); if (stopped) break; }
  }

  let seq = 0;
  const pageTag = Math.random().toString(36).slice(2, 8);
  const newTxId = () => 'x' + pageTag + '.' + (++seq);
  function exec(params) {
    return AG.call('idb.exec', params).then((res) => {
      if (res && res.fail) throw err(res.fail[0], res.fail[1]);
      return res.r;
    });
  }
  const oneShot = (ops) => exec({ tx: newTxId(), first: true, ops, end: 'commit' });

  // ---------- requests ----------
  class IDBRequest extends EventTarget {
    constructor(token, source, tx) {
      if (token !== KEY) throw new TypeError('Illegal constructor');
      super();
      this._src = source || null; this._tx = tx || null; this._ready = 'pending'; this._result = undefined; this._error = null;
    }
    get source() { return this._src; }
    get transaction() { return this._tx; }
    get readyState() { return this._ready; }
    get result() { if (this._ready !== 'done') throw err('InvalidStateError', 'The request has not finished.'); return this._result; }
    get error() { if (this._ready !== 'done') throw err('InvalidStateError', 'The request has not finished.'); return this._error; }
  }
  handlers(IDBRequest.prototype, ['success', 'error']);
  class IDBOpenDBRequest extends IDBRequest {
    constructor(token) { super(token, null, null); }
  }
  handlers(IDBOpenDBRequest.prototype, ['upgradeneeded', 'blocked']);

  class IDBVersionChangeEvent extends Event {
    constructor(type, init) {
      super(type, init);
      this._old = (init && init.oldVersion) || 0;
      this._new = init && init.newVersion !== undefined ? init.newVersion : null;
    }
    get oldVersion() { return this._old; }
    get newVersion() { return this._new; }
  }

  // ---------- key ranges ----------
  class IDBKeyRange {
    constructor(token, lower, upper, lo, uo) {
      if (token !== KEY) throw new TypeError('Illegal constructor');
      this._l = lower; this._u = upper; this._lo = !!lo; this._uo = !!uo;
      this._le = lower === undefined ? undefined : encKey(lower);
      this._ue = upper === undefined ? undefined : encKey(upper);
    }
    get lower() { return this._l; }
    get upper() { return this._u; }
    get lowerOpen() { return this._lo; }
    get upperOpen() { return this._uo; }
    static only(v) { const k = toKey(v); return new IDBKeyRange(KEY, k, k, false, false); }
    static lowerBound(v, open) { return new IDBKeyRange(KEY, toKey(v), undefined, open, true); }
    static upperBound(v, open) { return new IDBKeyRange(KEY, undefined, toKey(v), true, open); }
    static bound(l, u, lo, uo) {
      const a = toKey(l), b = toKey(u), c = cmpEnc(encKey(a), encKey(b));
      if (c > 0 || (c === 0 && (lo || uo))) throw err('DataError', 'The lower key is greater than the upper key.');
      return new IDBKeyRange(KEY, a, b, lo, uo);
    }
    includes(key) {
      const e = encKey(toKey(key));
      if (this._le !== undefined && (e < this._le || (this._lo && e === this._le))) return false;
      if (this._ue !== undefined && (e > this._ue || (this._uo && e === this._ue))) return false;
      return true;
    }
    _enc() {
      const r = {};
      if (this._le !== undefined) { r.l = this._le; r.lo = this._lo ? 1 : 0; }
      if (this._ue !== undefined) { r.u = this._ue; r.uo = this._uo ? 1 : 0; }
      return r;
    }
  }
  function toRange(q, nullOk) {
    if (q instanceof IDBKeyRange) return q._enc();
    if (q === undefined || q === null) { if (nullOk) return null; throw err('DataError', 'No key or key range specified.'); }
    const e = encKey(toKey(q));
    return { l: e, u: e, lo: 0, uo: 0 };
  }
  function checkCount(count) {
    if (count === undefined) return 0;
    const n = Number(count);
    if (!Number.isFinite(n) || n < 0 || n > 0xFFFFFFFF) throw new TypeError('count is out of range.');
    return Math.floor(n);
  }

  // ---------- transactions ----------
  // A job is one native op (or a local step) plus what to do with its result:
  // { op | op(): object, prep?: Promise, req?: IDBRequest, onResult?(value), barrier?: bool }
  class IDBTransaction extends EventTarget {
    constructor(token, db, names, mode) {
      if (token !== KEY) throw new TypeError('Illegal constructor');
      super();
      this._db = db; this._names = names; this._mode = mode; this._id = newTxId();
      this._state = 'active'; // active | committing | finished
      this._q = []; this._busy = false; this._started = false; this._flushQueued = false;
      this._error = null; this._stores = new Map(); this._gen = 0; this._commitAsked = false; this._abortAfter = null; this._onEnd = null;
      macro(() => this._check());
    }
    get db() { return this._db; }
    get mode() { return this._mode; }
    get error() { return this._error; }
    get durability() { return 'default'; }
    get objectStoreNames() { return list(this._mode === 'versionchange' ? this._db._meta.keys() : this._names); }
    objectStore(name) {
      if (this._state === 'finished') throw err('InvalidStateError', 'The transaction has finished.');
      const meta = this._db._meta.get(String(name));
      if (!meta || (this._mode !== 'versionchange' && !this._names.includes(meta.name))) throw err('NotFoundError', `No object store named "${name}" in this transaction.`);
      let s = this._stores.get(meta);
      if (!s) { s = new IDBObjectStore(KEY, this, meta); this._stores.set(meta, s); }
      return s;
    }
    abort() {
      if (this._state !== 'active') throw err('InvalidStateError', 'The transaction has already finished or is committing.');
      this._abort(null);
    }
    commit() {
      this._usable();
      this._commitAsked = true;
      this._flush();
      macro(() => this._check());
    }

    _usable() { if (this._state !== 'active' || this._commitAsked) throw err('TransactionInactiveError', 'The transaction is not active.'); }
    _request(source, job) {
      const req = new IDBRequest(KEY, source, this);
      job.req = req;
      this._enqueue(job);
      return req;
    }
    _metaOp(op) { this._enqueue({ op }); }
    _enqueue(job, front) {
      if (front) this._q.unshift(job); else this._q.push(job);
      if (!this._flushQueued) { this._flushQueued = true; queueMicrotask(() => { this._flushQueued = false; this._flush(); }); }
    }
    // Sends every queued job up to (and including) the first barrier as one native call.
    _flush() {
      if (this._busy || this._state === 'finished' || !this._q.length) return;
      const batch = [];
      while (this._q.length) { const j = this._q.shift(); batch.push(j); if (j.barrier) break; }
      this._busy = true;
      const preps = batch.filter((j) => j.prep).map((j) => j.prep);
      const send = () => {
        const ops = [];
        for (const j of batch) if (j.op) { if (typeof j.op === 'function') j.op = j.op(); ops.push(j.op); }
        if (!ops.length) return new Promise((res) => macro(() => res([])));
        const first = !this._started;
        this._started = true;
        return exec({ tx: this._id, first, ops });
      };
      (preps.length ? Promise.all(preps).then(send) : send()).then((r) => this._done(batch, r, null), (e) => this._done(batch, null, e));
    }
    _done(batch, results, failure) {
      this._busy = false;
      if (failure && !this._abortAfter) this._abortAfter = { error: toErr(failure) };
      let ri = 0;
      for (const job of batch) {
        if (this._state === 'finished' || this._abortAfter) { this._abortReq(job); continue; }
        const res = job.op ? results[ri++] : [0, undefined];
        if (res[0] === 0) this._succeed(job, res[1]);
        else this._failJob(job, err(res[1], res[2]));
      }
      if (this._abortAfter) { const a = this._abortAfter; this._abortAfter = null; this._abort(a.error); return; }
      this._flush();
      macro(() => this._check());
    }
    _succeed(job, val) {
      let result;
      try { result = job.onResult ? job.onResult(val) : val; } catch (e) { this._failJob(job, toErr(e)); return; }
      if (result === AGAIN) return;
      const req = job.req;
      if (!req) return;
      req._ready = 'done'; req._result = result; req._error = null;
      const ev = new Event('success');
      req.dispatchEvent(ev);
      if (ev._agThrew) this._abort(err('AbortError', 'An exception was thrown in a success handler.'));
    }
    _failJob(job, e) {
      const req = job.req;
      if (!req) { this._abort(e); return; }
      req._ready = 'done'; req._result = undefined; req._error = e;
      const ev = new Event('error', { bubbles: true, cancelable: true });
      setTarget(ev, req);
      fireChain(ev, [req, this, this._db]);
      if (!ev.defaultPrevented || ev._agThrew) this._abort(e);
    }
    _abortReq(job) {
      const req = job.req;
      if (!req) return;
      req._ready = 'done'; req._result = undefined; req._error = err('AbortError', 'The transaction was aborted.');
      const ev = new Event('error', { bubbles: true, cancelable: true });
      setTarget(ev, req);
      fireChain(ev, [req, this, this._db]);
    }
    _abort(error) {
      if (this._state === 'finished') return;
      if (this._busy) { if (!this._abortAfter) this._abortAfter = { error }; return; }
      this._state = 'finished';
      this._error = error;
      if (this._mode === 'versionchange') this._db._revert();
      const pending = this._q;
      this._q = [];
      for (const j of pending) this._abortReq(j);
      const fire = () => {
        const ev = new Event('abort', { bubbles: true });
        setTarget(ev, this);
        fireChain(ev, [this, this._db]);
        if (this._onEnd) this._onEnd('abort');
      };
      if (this._started) exec({ tx: this._id, first: false, ops: [], end: 'abort' }).then(fire, fire);
      else macro(fire);
    }
    // Autocommit: runs as a macrotask, so promise continuations (await store.get()) have already queued their requests.
    _check() {
      if (this._state !== 'active' || this._busy || this._q.length || this._flushQueued) return;
      this._state = 'committing';
      const fin = () => {
        this._state = 'finished';
        this.dispatchEvent(new Event('complete'));
        if (this._onEnd) this._onEnd('complete');
      };
      if (!this._started) { fin(); return; }
      exec({ tx: this._id, first: false, ops: [], end: 'commit' }).then(fin, (e) => { this._state = 'active'; this._abort(toErr(e)); });
    }
  }
  handlers(IDBTransaction.prototype, ['complete', 'error', 'abort']);

  // ---------- database ----------
  const cloneMeta = (meta) => new Map([...meta].map(([n, m]) => [n, { ...m, ix: new Map([...m.ix].map(([k, i]) => [k, { ...i }])) }]));

  class IDBDatabase extends EventTarget {
    constructor(token, name, version, meta) {
      if (token !== KEY) throw new TypeError('Illegal constructor');
      super();
      this._name = name; this._version = version; this._meta = meta; this._closing = false; this._upgrade = null; this._backup = null;
    }
    get name() { return this._name; }
    get version() { return this._version; }
    get objectStoreNames() { return list(this._meta.keys()); }
    transaction(names, mode, options) {
      if (this._closing) throw err('InvalidStateError', 'The database connection is closing.');
      if (this._upgrade && this._upgrade._state !== 'finished') throw err('InvalidStateError', 'A version change transaction is running.');
      const arr = typeof names === 'string' ? [names] : Array.from(names || []);
      if (!arr.length) throw err('InvalidAccessError', 'The storeNames parameter was empty.');
      const uniq = [...new Set(arr.map(String))].sort();
      for (const n of uniq) if (!this._meta.has(n)) throw err('NotFoundError', `No object store named "${n}".`);
      mode = mode === undefined ? 'readonly' : mode;
      if (mode !== 'readonly' && mode !== 'readwrite') throw new TypeError(`Invalid transaction mode: ${mode}`);
      return new IDBTransaction(KEY, this, uniq, mode);
    }
    createObjectStore(name, options) {
      const tx = this._upgradeTx();
      name = String(name);
      options = options || {};
      let kp = options.keyPath === undefined || options.keyPath === null ? null : normKeyPath(options.keyPath);
      const ai = !!options.autoIncrement;
      if (kp !== null && !validKeyPath(kp)) throw err('SyntaxError', 'The keyPath is not valid.');
      if (this._meta.has(name)) throw err('ConstraintError', `An object store named "${name}" already exists.`);
      if (ai && (kp === '' || Array.isArray(kp))) throw err('InvalidAccessError', 'autoIncrement needs a non-empty, non-array keyPath.');
      this._meta.set(name, { name, kp, ai, ix: new Map(), deleted: false });
      tx._metaOp({ o: 'mkS', db: this._name, s: name, kp: kp === null ? null : JSON.stringify(kp), ai: ai ? 1 : 0 });
      return tx.objectStore(name);
    }
    deleteObjectStore(name) {
      const tx = this._upgradeTx();
      name = String(name);
      const m = this._meta.get(name);
      if (!m) throw err('NotFoundError', `No object store named "${name}".`);
      m.deleted = true;
      this._meta.delete(name);
      tx._metaOp({ o: 'rmS', db: this._name, s: name });
    }
    close() { this._closing = true; }
    _upgradeTx() {
      const tx = this._upgrade;
      if (!tx || tx._state === 'finished') throw err('InvalidStateError', 'Not inside a version change transaction.');
      tx._usable();
      return tx;
    }
    _revert() {
      if (!this._backup) return;
      for (const m of this._meta.values()) m.deleted = true;
      this._meta = this._backup.meta;
      this._version = this._backup.version;
    }
  }
  handlers(IDBDatabase.prototype, ['abort', 'close', 'error', 'versionchange']);

  // ---------- object stores and indexes ----------
  class IDBObjectStore {
    constructor(token, tx, meta) {
      if (token !== KEY) throw new TypeError('Illegal constructor');
      this._tx = tx; this._m = meta; this._ix = new Map();
    }
    get name() { return this._m.name; }
    set name(v) {
      const tx = this._tx, db = tx._db;
      v = String(v);
      if (tx._mode !== 'versionchange' || this._m.deleted) throw err('InvalidStateError', 'Stores can only be renamed in a version change transaction.');
      tx._usable();
      if (v === this._m.name) return;
      if (db._meta.has(v)) throw err('ConstraintError', `An object store named "${v}" already exists.`);
      const old = this._m.name;
      db._meta.delete(old); this._m.name = v; db._meta.set(v, this._m);
      tx._metaOp({ o: 'mvS', db: db._name, s: old, to: v });
    }
    get keyPath() { const kp = this._m.kp; return Array.isArray(kp) ? [...kp] : kp; }
    get indexNames() { return list(this._m.ix.keys()); }
    get transaction() { return this._tx; }
    get autoIncrement() { return this._m.ai; }

    _check(write) {
      if (this._m.deleted) throw err('InvalidStateError', 'The object store has been deleted.');
      this._tx._usable();
      if (write && this._tx._mode === 'readonly') throw err('ReadOnlyError', 'The transaction is read-only.');
    }
    _base(extra) { return Object.assign({ db: this._tx._db._name, s: this._m.name }, extra); }
    put(value, key) { return this._put(value, key, false, this); }
    add(value, key) { return this._put(value, key, true, this); }
    _put(value, key, noOverwrite, source) {
      this._check(true);
      const m = this._m, tx = this._tx;
      let k, inject = null;
      if (m.kp !== null && key !== undefined) throw err('DataError', 'The object store uses in-line keys and the key parameter was provided.');
      if (m.kp === null && key === undefined && !m.ai) throw err('DataError', 'The object store uses out-of-line keys and has no key generator, and no key was given.');
      if (key !== undefined) k = toKey(key);
      const c = cloneOut(value);
      if (m.kp !== null) {
        const r = evalPath(value, m.kp);
        if (r !== NONE) k = toKey(r);
        else if (m.ai) { if (!canInject(value, m.kp)) throw err('DataError', 'The generated key could not be put into the value.'); inject = m.kp; }
        else throw err('DataError', "Evaluating the object store's key path did not yield a value.");
      }
      const e = [];
      for (const ix of m.ix.values()) entriesFor(ix, value, e, true);
      tx._gen++;
      const op = this._base({ o: 'put', add: noOverwrite ? 1 : 0, e });
      if (k !== undefined) { op.k = encKey(k); op.kj = keyJson(k); } else { op.auto = 1; if (inject) op.inj = inject; }
      if (c.s !== undefined) op.v = c.s;
      return tx._request(source, {
        op: c.s !== undefined ? op : () => { op.v = JSON.stringify(c.j); return op; },
        prep: c.prep,
        onResult: parseKey,
      });
    }
    _q(range, n, kv, map, index) {
      const op = this._base({ o: 'q', d: 'next', n: n || 0, kv });
      if (index) op.i = index._m.name;
      if (range) op.r = range;
      return this._tx._request(index || this, { op, onResult: map });
    }
    _count(query, index) {
      const op = this._base({ o: 'n' });
      if (index) op.i = index._m.name;
      const r = toRange(query, true);
      if (r) op.r = r;
      return this._tx._request(index || this, { op });
    }
    _del(range, source) {
      this._tx._gen++;
      return this._tx._request(source, { op: this._base({ o: 'del', r: range }), onResult: () => undefined });
    }
    get(query) { this._check(); return this._q(toRange(query, false), 1, 'kv', (rows) => (rows.length ? parseVal(rows[0][2]) : undefined)); }
    getKey(query) { this._check(); return this._q(toRange(query, false), 1, 'k', (rows) => (rows.length ? parseKey(rows[0][1]) : undefined)); }
    getAll(query, count) { this._check(); return this._q(toRange(query, true), checkCount(count), 'kv', (rows) => rows.map((r) => parseVal(r[2]))); }
    getAllKeys(query, count) { this._check(); return this._q(toRange(query, true), checkCount(count), 'k', (rows) => rows.map((r) => parseKey(r[1]))); }
    count(query) { this._check(); return this._count(query, null); }
    delete(query) { this._check(true); return this._del(toRange(query, false), this); }
    clear() {
      this._check(true);
      this._tx._gen++;
      return this._tx._request(this, { op: this._base({ o: 'clr' }), onResult: () => undefined });
    }
    openCursor(query, dir) { return this._open(query, dir, false, null); }
    openKeyCursor(query, dir) { return this._open(query, dir, true, null); }
    _open(query, dir, keysOnly, index) {
      if (index) index._check(); else this._check();
      const range = toRange(query, true);
      dir = dir === undefined ? 'next' : String(dir);
      if (!DIRS.includes(dir)) throw new TypeError(`Invalid cursor direction: ${dir}`);
      const C = keysOnly ? IDBCursor : IDBCursorWithValue;
      const c = new C(KEY, this, index, range, dir, keysOnly);
      c._req = new IDBRequest(KEY, index || this, this._tx);
      c._step({ n: 1 }, true);
      return c._req;
    }
    index(name) {
      if (this._m.deleted) throw err('InvalidStateError', 'The object store has been deleted.');
      if (this._tx._state === 'finished') throw err('InvalidStateError', 'The transaction has finished.');
      const im = this._m.ix.get(String(name));
      if (!im) throw err('NotFoundError', `No index named "${name}".`);
      let ix = this._ix.get(im);
      if (!ix) { ix = new IDBIndex(KEY, this, im); this._ix.set(im, ix); }
      return ix;
    }
    createIndex(name, keyPath, options) {
      const tx = this._tx;
      if (tx._mode !== 'versionchange' || this._m.deleted) throw err('InvalidStateError', 'Indexes can only be created in a version change transaction.');
      tx._usable();
      name = String(name);
      if (this._m.ix.has(name)) throw err('ConstraintError', `An index named "${name}" already exists.`);
      const kp = normKeyPath(keyPath);
      if (!validKeyPath(kp)) throw err('SyntaxError', 'The keyPath is not valid.');
      options = options || {};
      const im = { name, kp, u: !!options.unique, m: !!options.multiEntry, deleted: false };
      if (im.m && Array.isArray(kp)) throw err('InvalidAccessError', 'multiEntry indexes cannot have an array keyPath.');
      this._m.ix.set(name, im);
      const base = this._base({});
      tx._metaOp(Object.assign({ o: 'mkI', i: name, kp: JSON.stringify(kp), u: im.u ? 1 : 0, m: im.m ? 1 : 0 }, base));
      // fill it from the records already stored (a unique violation aborts the upgrade)
      tx._enqueue({
        barrier: true,
        op: Object.assign({ o: 'all' }, base),
        onResult: (rows) => {
          const e = [];
          for (const [k, v] of rows) {
            const part = [];
            entriesFor(im, parseVal(v), part, false);
            for (const [ik, ikj] of part) e.push([ik, ikj, k]);
          }
          if (e.length) tx._enqueue({ op: Object.assign({ o: 'ixAdd', i: name, u: im.u ? 1 : 0, e }, base) }, true);
          return undefined;
        },
      });
      return this.index(name);
    }
    deleteIndex(name) {
      const tx = this._tx;
      if (tx._mode !== 'versionchange' || this._m.deleted) throw err('InvalidStateError', 'Indexes can only be deleted in a version change transaction.');
      tx._usable();
      name = String(name);
      const im = this._m.ix.get(name);
      if (!im) throw err('NotFoundError', `No index named "${name}".`);
      im.deleted = true;
      this._m.ix.delete(name);
      tx._metaOp(this._base({ o: 'rmI', i: name }));
    }
  }

  class IDBIndex {
    constructor(token, store, meta) {
      if (token !== KEY) throw new TypeError('Illegal constructor');
      this._s = store; this._m = meta;
    }
    get objectStore() { return this._s; }
    get name() { return this._m.name; }
    set name(v) {
      const st = this._s, tx = st._tx;
      v = String(v);
      if (tx._mode !== 'versionchange' || this._m.deleted || st._m.deleted) throw err('InvalidStateError', 'Indexes can only be renamed in a version change transaction.');
      tx._usable();
      if (v === this._m.name) return;
      if (st._m.ix.has(v)) throw err('ConstraintError', `An index named "${v}" already exists.`);
      const old = this._m.name;
      st._m.ix.delete(old); this._m.name = v; st._m.ix.set(v, this._m);
      tx._metaOp(st._base({ o: 'mvI', i: old, to: v }));
    }
    get keyPath() { const kp = this._m.kp; return Array.isArray(kp) ? [...kp] : kp; }
    get multiEntry() { return this._m.m; }
    get unique() { return this._m.u; }
    _check() {
      if (this._m.deleted || this._s._m.deleted) throw err('InvalidStateError', 'The index or its object store has been deleted.');
      this._s._tx._usable();
    }
    get(query) { this._check(); return this._s._q(toRange(query, false), 1, 'kv', (rows) => (rows.length ? parseVal(rows[0][4]) : undefined), this); }
    getKey(query) { this._check(); return this._s._q(toRange(query, false), 1, 'k', (rows) => (rows.length ? parseKey(rows[0][3]) : undefined), this); }
    getAll(query, count) { this._check(); return this._s._q(toRange(query, true), checkCount(count), 'kv', (rows) => rows.map((r) => parseVal(r[4])), this); }
    getAllKeys(query, count) { this._check(); return this._s._q(toRange(query, true), checkCount(count), 'k', (rows) => rows.map((r) => parseKey(r[3])), this); }
    count(query) { this._check(); return this._s._count(query, this); }
    openCursor(query, dir) { return this._s._open(query, dir, false, this); }
    openKeyCursor(query, dir) { return this._s._open(query, dir, true, this); }
  }

  // ---------- cursors ----------
  // Rows are normalized to { ek: position key, kj, epk: primary key, pkj, v }.
  class IDBCursor {
    constructor(token, store, index, range, dir, keysOnly) {
      if (token !== KEY) throw new TypeError('Illegal constructor');
      this._st = store; this._ix = index; this._range = range; this._dir = dir; this._ko = keysOnly;
      this._buf = []; this._end = false; this._bgen = -1; this._at = null; this._row = null; this._got = false;
      this._spec = null; this._req = null; this._k = NONE; this._pk = NONE; this._v = NONE;
    }
    get source() { return this._ix || this._st; }
    get direction() { return this._dir; }
    get request() { return this._req; }
    get key() { if (!this._row) return undefined; if (this._k === NONE) this._k = parseKey(this._row.kj); return this._k; }
    get primaryKey() { if (!this._row) return undefined; if (this._pk === NONE) this._pk = parseKey(this._row.pkj); return this._pk; }

    _norm(r) { return this._ix ? { ek: r[0], kj: r[1], epk: r[2], pkj: r[3], v: r[4] } : { ek: r[0], kj: r[1], epk: r[0], pkj: r[1], v: r[2] }; }
    _set(row) {
      this._row = row; this._got = !!row; this._k = NONE; this._pk = NONE; this._v = NONE;
      if (row) this._at = row;
    }
    _reached(row, sp) {
      if (this._dir.startsWith('next')) return row.ek > sp.key || (row.ek === sp.key && (sp.pk === undefined || row.epk >= sp.pk));
      return row.ek < sp.key || (row.ek === sp.key && (sp.pk === undefined || row.epk <= sp.pk));
    }
    _canLocal() {
      const sp = this._spec;
      let n = sp.n || 1;
      for (const row of this._buf) {
        if (sp.key !== undefined) { if (this._reached(row, sp)) return true; continue; }
        if (--n === 0) return true;
      }
      return this._end;
    }
    _step(spec, first) {
      const tx = this._st._tx;
      this._spec = spec; this._got = false;
      this._req._ready = 'pending';
      if (!first && this._bgen === tx._gen && this._canLocal()) tx._enqueue({ req: this._req, onResult: () => this._resolve() });
      else tx._enqueue(this._fetchJob(first));
    }
    _fetchJob(first) {
      const tx = this._st._tx, sp = this._spec, gen = tx._gen;
      const op = this._st._base({ o: 'q', d: this._dir, n: Math.min(Math.max(PAGE, (sp.n || 1) + 1), 1000), kv: this._ko ? 'k' : 'kv' });
      if (this._ix) op.i = this._ix._m.name;
      if (this._range) op.r = this._range;
      if (!first) op.from = this._from();
      return {
        req: this._req, barrier: true, op,
        onResult: (rows) => { this._buf = rows.map((r) => this._norm(r)); this._end = rows.length < op.n; this._bgen = gen; return this._resolve(); },
      };
    }
    _from() {
      const sp = this._spec;
      if (sp.key !== undefined) {
        if (!this._ix) return { k: sp.key, x: 1 };
        return sp.pk !== undefined ? { ik: sp.key, k: sp.pk, x: 1 } : { ik: sp.key, x: 1 };
      }
      const at = this._at;
      if (!this._ix) return { k: at.epk, x: 0 };
      return this._dir.endsWith('unique') ? { ik: at.ek, x: 0 } : { ik: at.ek, k: at.epk, x: 0 };
    }
    _resolve() {
      const sp = this._spec;
      while (this._buf.length) {
        const row = this._buf.shift();
        if (sp.key !== undefined) {
          if (!this._reached(row, sp)) { this._at = row; continue; }
        } else if (sp.n > 1) { sp.n--; this._at = row; continue; }
        this._set(row);
        return this;
      }
      if (this._end) { this._set(null); return null; }
      this._st._tx._enqueue(this._fetchJob(false), true); // the page ran out: fetch more first
      return AGAIN;
    }
    _checkStep() {
      this._st._tx._usable();
      if (this._st._m.deleted || (this._ix && this._ix._m.deleted)) throw err('InvalidStateError', "The cursor's source has been deleted.");
      if (!this._got) throw err('InvalidStateError', 'The cursor is being iterated or has iterated past its end.');
    }
    continue(key) {
      this._checkStep();
      if (key === undefined) { this._step({ n: 1 }); return; }
      const e = encKey(toKey(key));
      if (this._dir.startsWith('next') ? e <= this._row.ek : e >= this._row.ek) throw err('DataError', 'The key is not past the current cursor position.');
      this._step({ key: e });
    }
    advance(count) {
      const n = Number(count);
      if (!Number.isFinite(n) || n < 1 || n > 0xFFFFFFFF) throw new TypeError('advance() count must be at least 1.');
      this._checkStep();
      this._step({ n: Math.floor(n) });
    }
    continuePrimaryKey(key, primaryKey) {
      this._st._tx._usable();
      if (this._st._m.deleted || (this._ix && this._ix._m.deleted)) throw err('InvalidStateError', "The cursor's source has been deleted.");
      if (!this._ix || this._dir.endsWith('unique')) throw err('InvalidAccessError', 'continuePrimaryKey needs an index cursor going next or prev.');
      if (!this._got) throw err('InvalidStateError', 'The cursor is being iterated or has iterated past its end.');
      const e = encKey(toKey(key)), pe = encKey(toKey(primaryKey)), r = this._row;
      const behind = this._dir === 'next' ? e < r.ek || (e === r.ek && pe <= r.epk) : e > r.ek || (e === r.ek && pe >= r.epk);
      if (behind) throw err('DataError', 'The key is not past the current cursor position.');
      this._step({ key: e, pk: pe });
    }
    _writeCheck() {
      const tx = this._st._tx;
      tx._usable();
      if (tx._mode === 'readonly') throw err('ReadOnlyError', 'The transaction is read-only.');
      if (this._st._m.deleted || (this._ix && this._ix._m.deleted)) throw err('InvalidStateError', "The cursor's source has been deleted.");
      if (!this._got || this._ko) throw err('InvalidStateError', 'The cursor has no value.');
    }
    // writes through a store cursor touch only the current row, so its prefetched page stays valid
    _own(req) { if (!this._ix) this._bgen = this._st._tx._gen; return req; }
    update(value) {
      this._writeCheck();
      const st = this._st;
      if (st._m.kp !== null) {
        const r = evalPath(value, st._m.kp);
        let same = false;
        if (r !== NONE) { try { same = encKey(toKey(r)) === this._row.epk; } catch (_) { same = false; } }
        if (!same) throw err('DataError', "The new value's key does not match the cursor's primary key.");
        return this._own(st._put(value, undefined, false, this));
      }
      return this._own(st._put(value, parseKey(this._row.pkj), false, this));
    }
    delete() {
      this._writeCheck();
      const e = this._row.epk;
      return this._own(this._st._del({ l: e, u: e, lo: 0, uo: 0 }, this));
    }
  }
  class IDBCursorWithValue extends IDBCursor {
    get value() { if (!this._row) return undefined; if (this._v === NONE) this._v = parseVal(this._row.v); return this._v; }
  }

  // ---------- factory ----------
  function failRequest(req, e) {
    req._ready = 'done'; req._result = undefined; req._error = toErr(e);
    const ev = new Event('error', { bubbles: true, cancelable: true });
    req.dispatchEvent(ev);
  }
  class IDBFactory {
    constructor(token) { if (token !== KEY) throw new TypeError('Illegal constructor'); }
    open(name, version) {
      if (arguments.length < 1) throw new TypeError('open() needs a database name.');
      name = String(name);
      if (version !== undefined) {
        version = Number(version);
        if (!Number.isFinite(version) || version < 1 || version > Number.MAX_SAFE_INTEGER) throw new TypeError('The version must be a positive integer.');
        version = Math.floor(version);
      }
      const req = new IDBOpenDBRequest(KEY);
      oneShot([{ o: 'info', db: name }]).then(([info]) => {
        if (info[0]) throw err(info[1], info[2]);
        const cur = info[1].v;
        const want = version === undefined ? cur || 1 : version;
        if (want < cur) throw err('VersionError', `The requested version (${want}) is less than the existing version (${cur}).`);
        const meta = new Map();
        for (const s of info[1].s) {
          meta.set(s.n, {
            name: s.n, kp: s.kp == null ? null : JSON.parse(s.kp), ai: !!s.ai, deleted: false,
            ix: new Map(s.ix.map((i) => [i.n, { name: i.n, kp: JSON.parse(i.kp), u: !!i.u, m: !!i.m, deleted: false }])),
          });
        }
        const db = new IDBDatabase(KEY, name, cur, meta);
        if (want === cur) {
          req._ready = 'done'; req._result = db;
          req.dispatchEvent(new Event('success'));
          return;
        }
        db._backup = { meta: cloneMeta(meta), version: cur };
        db._version = want;
        const tx = new IDBTransaction(KEY, db, [], 'versionchange');
        db._upgrade = tx;
        tx._metaOp({ o: 'ver', db: name, v: want });
        tx._onEnd = (how) => {
          db._upgrade = null;
          req._tx = null;
          if (how === 'complete') { db._backup = null; req.dispatchEvent(new Event('success')); return; }
          db._closing = true;
          failRequest(req, err('AbortError', 'The version change transaction was aborted.'));
        };
        req._tx = tx; req._ready = 'done'; req._result = db;
        const ev = new IDBVersionChangeEvent('upgradeneeded', { oldVersion: cur, newVersion: want });
        req.dispatchEvent(ev);
        if (ev._agThrew) tx._abort(err('AbortError', 'An exception was thrown in the upgradeneeded handler.'));
      }).catch((e) => failRequest(req, e));
      return req;
    }
    deleteDatabase(name) {
      if (arguments.length < 1) throw new TypeError('deleteDatabase() needs a database name.');
      const req = new IDBOpenDBRequest(KEY);
      oneShot([{ o: 'dropDb', db: String(name) }]).then(([r]) => {
        if (r[0]) throw err(r[1], r[2]);
        req._ready = 'done'; req._result = undefined;
        req.dispatchEvent(new IDBVersionChangeEvent('success', { oldVersion: r[1], newVersion: null }));
      }).catch((e) => failRequest(req, e));
      return req;
    }
    databases() {
      return oneShot([{ o: 'dbs' }]).then(([r]) => {
        if (r[0]) throw err(r[1], r[2]);
        return r[1].map(([name, version]) => ({ name, version }));
      });
    }
    cmp(a, b) {
      if (arguments.length < 2) throw new TypeError('cmp() needs two keys.');
      return cmpEnc(encKey(toKey(a)), encKey(toKey(b)));
    }
  }

  const factory = new IDBFactory(KEY);
  const globals = {
    indexedDB: factory, IDBFactory, IDBKeyRange, IDBRequest, IDBOpenDBRequest, IDBDatabase, IDBTransaction,
    IDBObjectStore, IDBIndex, IDBCursor, IDBCursorWithValue, IDBVersionChangeEvent,
  };
  for (const k of Object.keys(globals)) {
    try { Object.defineProperty(globalThis, k, { value: globals[k], configurable: true, writable: true, enumerable: false }); } catch (_) { /* keep going */ }
  }
})();
