import fs from 'node:fs';
import path from 'node:path';
import { DevToolsConnection, listTargets } from './devtools.mjs';

export function matches(pattern, input) {
  try {
    const u = new URL(input);
    if (pattern === '<all_urls>') return ['http:', 'https:', 'file:', 'ftp:'].includes(u.protocol);
    const m = /^(\*|https?|file|ftp):\/\/([^/]*)(\/.*)$/.exec(pattern); if (!m) return false;
    if (m[1] === '*' ? !['http:', 'https:'].includes(u.protocol) : u.protocol !== m[1] + ':') return false;
    const host = m[2].toLowerCase(); if (host !== '*' && (host.startsWith('*.') ? u.hostname !== host.slice(2) && !u.hostname.endsWith('.' + host.slice(2)) : u.hostname !== host)) return false;
    const expression = m[3].replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*');
    return new RegExp('^' + expression + '$').test(u.pathname + u.search);
  } catch { return false; }
}
// Google sign-in must load with no DevTools session attached; its bot checks detect one.
export function isSignInPage(input) {
  try { const host = new URL(input).hostname.toLowerCase(); return host === 'accounts.youtube.com' || host === 'myaccount.google.com' || /^accounts\.google\.[a-z.]+$/.test(host); } catch { return false; }
}
export function safeFile(root, relative) {
  if (typeof relative !== 'string' || path.isAbsolute(relative)) throw new Error('Extension resources must use relative paths');
  const resolved = fs.realpathSync(path.resolve(root, relative)); const base = fs.realpathSync(root);
  if (resolved !== base && !resolved.startsWith(base + path.sep)) throw new Error('Extension resource escapes its directory');
  return resolved;
}
export class BrowserAPI {
  constructor(host) { this.host = host; this.ids = new Map(); this.nextId = 1; this.connections = new Map(); this.initializing = new Map(); this.endpoint = null; this.lastTabs = new Map(); this.debuggers = new Map(); }
  async bridge(endpoint, body, method = 'POST') {
    const url = new URL(`/api/browser/${endpoint}`, this.host.options.bridgeUrl);
    const response = await fetch(url, { method, headers: { 'Content-Type': 'application/json', 'X-Device-Id': this.host.options.deviceId }, body: method === 'GET' ? undefined : JSON.stringify(body || {}), signal: AbortSignal.timeout(90000) });
    const value = await response.json(); if (!response.ok || value?.success === false) throw new Error(value.error || `Browser bridge HTTP ${response.status}`); return value;
  }
  id(native) { if (!this.ids.has(native)) this.ids.set(native, this.nextId++); return this.ids.get(native); }
  native(id) { if (id == null || id === -1) return null; for (const [native, number] of this.ids) if (number === id) return native; throw new Error(`No tab with id: ${id}`); }
  async tabs(query = {}) {
    const result = await this.bridge('tabs', null, 'GET');
    const tabs = (Array.isArray(result) ? result : result.tabs || []).map((tab, index) => ({ id: this.id(tab.id), index, windowId: 1, active: !!(tab.isActive ?? tab.active), highlighted: !!(tab.isActive ?? tab.active), pinned: false, incognito: false, url: tab.url || '', title: tab.title || '', status: (tab.isLoading ?? tab.loading) ? 'loading' : 'complete', discarded: false, autoDiscardable: false, groupId: -1, audible: false }));
    return tabs.filter(t => (query.active === undefined || t.active === query.active) && (query.windowId === undefined || [-2, 1].includes(query.windowId)) &&
      (!query.url || (Array.isArray(query.url) ? query.url : [query.url]).some(p => matches(p, t.url))) && (!query.status || t.status === query.status) &&
      (!query.title || new RegExp('^' + query.title.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$', 'i').test(t.title)));
  }
  async tab(id) { const tabs = await this.tabs(); const tab = id == null ? tabs.find(t => t.active) : tabs.find(t => t.id === id); if (!tab) throw new Error(`No tab with id: ${id}`); return tab; }
  allowed(extension, url) {
    const patterns = [...(extension.manifest.host_permissions || []), ...(extension.manifest.permissions || []).filter(p => p.includes('://') || p === '<all_urls>')];
    return patterns.some(p => matches(p, url));
  }
  async checkAccess(extension, tabId) {
    const tab = await this.tab(tabId);
    if (!this.allowed(extension, tab.url) && !(extension.manifest.permissions?.includes('activeTab') && tab.active)) throw new Error(`Cannot access ${tab.url}: host permission is required`);
    return tab;
  }
  async connection(tabId) {
    const cached = this.connections.get(tabId); if (cached && !cached.closed) return cached;
    if (this.initializing.has(tabId)) return this.initializing.get(tabId);
    const promise = this.connect(tabId); this.initializing.set(tabId, promise);
    try { return await promise; } finally { this.initializing.delete(tabId); }
  }
  async connect(tabId) {
    if (isSignInPage((await this.tab(tabId)).url)) throw new Error('Extensions are paused on Google sign-in pages');
    const nativeId = this.native(tabId); let info;
    if (process.env.AGY_CDP_URL) {
      await this.bridge('eval', { tab_id: nativeId, script: `window.__antigemTabId=${JSON.stringify(nativeId)}` });
      info = { url: process.env.AGY_CDP_URL };
    } else info = await this.bridge('devtools', { tab_id: nativeId });
    const endpoint = info.socket ? { socket: info.socket } : { url: info.url };
    const targets = await listTargets(endpoint);
    for (const target of targets.filter(t => t.type === 'page')) {
      if ([...this.connections.values()].some(c => c.targetId === target.id && !c.closed)) continue;
      const connection = await DevToolsConnection.connect(endpoint, target.id);
      const result = await connection.send('Runtime.evaluate', { expression: 'window.__antigemTabId', returnByValue: true });
      if (result.result?.value !== nativeId) { connection.close(); continue; }
      connection.targetId = target.id; connection.tabId = tabId; this.connections.set(tabId, connection);
      connection.on('event', (name, params) => { this.host.onDevToolsEvent(tabId, name, params).catch(e => console.error(e)); for (const extension of this.debuggers.get(tabId) || []) this.host.broadcast(extension, 'debugger.onEvent', [{ tabId }, name, params]); });
      connection.on('closed', () => { this.connections.delete(tabId); this.host.dropTabContexts(tabId); for (const extension of this.debuggers.get(tabId) || []) this.host.broadcast(extension, 'debugger.onDetach', [{ tabId }, 'target_closed']); this.debuggers.delete(tabId); });
      await connection.send('Runtime.enable'); await connection.send('Page.enable');
      return connection;
    }
    throw new Error('Could not identify the tab in WebView DevTools; retry after the page has opened');
  }
  async evaluate(tabId, expression, world = 'MAIN', frameId) {
    const connection = await this.connection(tabId); const params = { expression, awaitPromise: true, returnByValue: true, userGesture: true };
    if (world !== 'MAIN') {
      const frame = frameId || (await connection.send('Page.getFrameTree')).frameTree.frame.id;
      const context = await connection.send('Page.createIsolatedWorld', { frameId: frame, worldName: world }); params.contextId = context.executionContextId;
    }
    const result = await connection.send('Runtime.evaluate', params, 180000);
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result?.value;
  }
  async execute(extension, details) {
    const tab = await this.checkAccess(extension, details.target?.tabId); const connection = await this.connection(tab.id);
    if (!!details.func === !!details.files?.length) throw new Error('Specify exactly one of func or files');
    if (details.target.documentIds) throw new Error('Targeting documentIds is not supported');
    let frames = [null];
    if (details.target.allFrames || details.target.frameIds?.some(id => id !== 0)) {
      const tree = (await connection.send('Page.getFrameTree')).frameTree; const flat = [];
      const walk = t => { flat.push(t.frame); for (const child of t.childFrames || []) walk(child); }; walk(tree);
      frames = flat.filter((f, i) => details.target.allFrames || details.target.frameIds?.includes(i)).map(f => f.id);
    }
    const world = details.world === 'MAIN' ? 'MAIN' : extension.worldName;
    const sources = details.files?.map(f => fs.readFileSync(safeFile(extension.dir, f), 'utf8'));
    const results = [];
    for (const [index, frameId] of frames.entries()) {
      if (world !== 'MAIN') await this.host.ensureContentContext(extension, tab.id, frameId);
      let result;
      if (sources) for (const source of sources) result = await this.evaluate(tab.id, source, world, frameId);
      else result = await this.evaluate(tab.id, `(${details.func})(...${JSON.stringify(details.args || [])})`, world, frameId);
      results.push({ frameId: index, result });
    }
    return results;
  }
  async request(context, method, args) {
    const extension = context.extension;
    if (method === 'tabs.query') return this.tabs(args[0]);
    if (method === 'tabs.get') return this.tab(args[0]);
    if (method === 'tabs.create') { const properties = args[0] || {}; const tab = await this.bridge('open', { url: properties.url || 'about:blank', new_tab: true, active: properties.active !== false, desktop: true }); await this.poll(); return this.tab(this.id(tab.id)); }
    if (method === 'tabs.update') { const tab = await this.tab(args[0]); const properties = args[1] || {}; await this.bridge('update', { tab_id: this.native(tab.id), ...properties }); return this.tab(tab.id); }
    if (method === 'tabs.remove') { for (const id of Array.isArray(args[0]) ? args[0] : [args[0]]) await this.bridge('close', { tab_id: this.native(id) }); await this.poll(); return; }
    if (method === 'tabs.reload') { const tab = await this.tab(args[0]); await (await this.connection(tab.id)).send('Page.reload', { ignoreCache: !!args[1]?.bypassCache }); return; }
    if (method === 'tabs.duplicate') { const tab = await this.tab(args[0]); return this.request(context, 'tabs.create', [{ url: tab.url }]); }
    if (method === 'tabs.getZoom') { const tab = await this.tab(args[0]); return (await this.evaluate(tab.id, 'window.visualViewport?.scale || 1')) || 1; }
    if (method === 'tabs.setZoom') { const hasId = args.length > 1; const tab = await this.tab(hasId ? args[0] : null); await this.bridge('zoom', { tab_id: this.native(tab.id), factor: hasId ? args[1] : args[0] }); return; }
    if (method === 'tabs.getZoomSettings') return { mode: 'automatic', scope: 'per-tab', defaultZoomFactor: 1 };
    if (method === 'tabs.setZoomSettings') { if (args.at(-1)?.mode !== 'automatic') throw new Error('Only automatic per-tab zoom is supported'); return; }
    if (method === 'tabs.goBack' || method === 'tabs.goForward') { const tab = await this.tab(args[0]); await this.evaluate(tab.id, method.endsWith('goBack') ? 'history.back()' : 'history.forward()'); return; }
    if (method === 'tabs.captureVisibleTab') { if (args[0] != null && ![-2, 1].includes(args[0])) throw new Error('Window not found'); const tab = await this.tab(); const res = await (await this.connection(tab.id)).send('Page.captureScreenshot', { format: args[1]?.format || 'jpeg', ...(args[1]?.format === 'png' ? {} : { quality: args[1]?.quality ?? 80 }) }); return `data:image/${args[1]?.format || 'jpeg'};base64,${res.data}`; }
    if (method === 'scripting.executeScript') return this.execute(extension, args);
    if (method === 'tabs.executeScript') { const tab = await this.tab(args.tabId); const d = args.details || {}; const res = await this.execute(extension, { target: { tabId: tab.id }, files: d.file ? [d.file] : undefined, func: d.code ? `()=>{return eval(${JSON.stringify(d.code)})}` : undefined }); return res.map(r => r.result); }
    if (method === 'scripting.insertCSS' || method === 'scripting.removeCSS') {
      const d = args[0], tab = await this.checkAccess(extension, d.target.tabId); const css = d.css ?? (d.files || []).map(f => fs.readFileSync(safeFile(extension.dir, f), 'utf8')).join('\n');
      await this.evaluate(tab.id, `(()=>{const key=${JSON.stringify(extension.id + ':' + css)};const nodes=[...document.querySelectorAll('style[data-antigem-style]')];${method.endsWith('removeCSS') ? "nodes.filter(e=>e.dataset.antigemStyle===key).forEach(e=>e.remove())" : `if(!nodes.some(e=>e.dataset.antigemStyle===key)){const e=document.createElement('style');e.dataset.antigemStyle=key;e.textContent=${JSON.stringify(css)};(document.head||document.documentElement).appendChild(e)}`} })()`); return;
    }
    if (method.startsWith('debugger.')) {
      if (method === 'debugger.getTargets') return (await this.tabs()).map(t => ({ id: String(t.id), tabId: t.id, type: 'page', title: t.title, url: t.url, attached: !!this.debuggers.get(t.id)?.size }));
      const target = args[0]; if (!Number.isInteger(target?.tabId)) throw new Error('debugger requires a tabId'); await this.checkAccess(extension, target.tabId);
      if (method === 'debugger.attach') { await this.connection(target.tabId); const set = this.debuggers.get(target.tabId) || new Set(); if (set.size && !set.has(extension.id)) throw new Error('Another extension is attached to this tab'); set.add(extension.id); this.debuggers.set(target.tabId, set); return; }
      if (method === 'debugger.detach') { this.debuggers.get(target.tabId)?.delete(extension.id); this.host.broadcast(extension.id, 'debugger.onDetach', [target, 'canceled_by_user']); return; }
      if (method === 'debugger.sendCommand') {
        if (!this.debuggers.get(target.tabId)?.has(extension.id)) throw new Error('Debugger is not attached');
        // Pass CDP through to Chromium, preserving mousePressed/mouseReleased, remote objects and promises.
        return (await this.connection(target.tabId)).send(args[1], args[2] || {}, 180000);
      }
    }
    if (method.startsWith('windows.')) {
      const win = async populate => ({ id: 1, focused: true, incognito: false, type: 'normal', state: 'normal', alwaysOnTop: false, ...(populate ? { tabs: await this.tabs() } : {}) });
      if (method === 'windows.getAll') return [await win(args[0]?.populate)];
      if (['windows.get', 'windows.getCurrent', 'windows.getLastFocused', 'windows.update'].includes(method)) { if (typeof args[0] === 'number' && ![-2, 1].includes(args[0])) throw new Error('Window not found'); return win(args.at(-1)?.populate); }
      if (method === 'windows.create') { const urls = args[0]?.url; for (const url of Array.isArray(urls) ? urls : [urls || 'about:blank']) await this.request(context, 'tabs.create', [{ url }]); return win(true); }
      if (method === 'windows.remove') throw new Error('Closing the Android browser window is not supported; use tabs.remove');
    }
    if (method.startsWith('cookies.')) {
      const d = args[0] || {}, tabs = await this.tabs(); if(method==='cookies.getAllCookieStores')return [{id:'0',tabIds:tabs.map(t=>t.id)}]; if (!d.url && method !== 'cookies.getAll') throw new Error('cookies requires a URL');
      if (d.url && !this.allowed(extension, d.url)) throw new Error('Cookie host permission is required');
      const tab = tabs.find(t => this.allowed(extension, t.url)); if (!tab) throw new Error('Open a permitted tab to access its cookie store'); const c = await this.connection(tab.id);
      if (method === 'cookies.get' || method === 'cookies.getAll') { const value = await c.send('Network.getCookies', { urls: d.url ? [d.url] : tabs.filter(t => this.allowed(extension, t.url)).map(t => t.url) }); const all = value.cookies.filter(x => (!d.name || x.name === d.name) && (!d.domain || x.domain.replace(/^\./, '').endsWith(d.domain.replace(/^\./, '')))).map(x => ({ ...x, hostOnly: !x.domain.startsWith('.'), session: x.expires === -1, expirationDate: x.expires, storeId: '0', sameSite: x.sameSite?.toLowerCase() || 'unspecified' })); return method === 'cookies.get' ? all.find(x => x.name === d.name) || null : all; }
      if (method === 'cookies.set') { const p = { url: d.url, name: d.name, value: d.value, path: d.path || '/', secure: !!d.secure, httpOnly: !!d.httpOnly }; if (d.domain) p.domain = d.domain; if (d.expirationDate != null) p.expires = d.expirationDate; const result = await c.send('Network.setCookie', p); if (!result.success) throw new Error('Cookie was rejected'); return this.request(context, 'cookies.get', [d]); }
      if (method === 'cookies.remove') { await c.send('Network.deleteCookies', { url: d.url, name: d.name }); return { url: d.url, name: d.name, storeId: '0' }; }
      if (method === 'cookies.getAllCookieStores') return [{ id: '0', tabIds: tabs.map(t => t.id) }];
    }
    throw new Error(`Unsupported Chrome API: ${method}`);
  }
  async poll() {
    const tabs = await this.tabs(); const now = new Map(tabs.map(t => [t.id, t]));
    for (const tab of tabs) { const old = this.lastTabs.get(tab.id); if (!old) this.host.broadcastAll('tabs.onCreated', [tab]); else { const change = {}; for (const key of ['url', 'title', 'status']) if (old[key] !== tab[key]) change[key] = tab[key]; if (Object.keys(change).length) this.host.broadcastAll('tabs.onUpdated', [tab.id, change, tab]); if (!old.active && tab.active) this.host.broadcastAll('tabs.onActivated', [{ tabId: tab.id, windowId: 1 }]); }
      if (isSignInPage(tab.url)) { this.release(tab.id); continue; }
      await this.host.prepareTab(tab).catch(e => { if (this.host.options.verbose) console.error(e.message); });
    }
    for (const id of this.lastTabs.keys()) if (!now.has(id)) { this.host.broadcastAll('tabs.onRemoved', [id, { windowId: 1, isWindowClosing: false }]); this.connections.get(id)?.close(); this.host.dropTabContexts(id); }
    this.lastTabs = now;
  }
  release(tabId) { this.connections.get(tabId)?.close(); }
  close() { for (const c of this.connections.values()) c.close(); }
}
