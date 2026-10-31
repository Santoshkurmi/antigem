import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import vm from 'node:vm';
import readline from 'node:readline';
import { homedir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { randomUUID, randomBytes, webcrypto } from 'node:crypto';
import { createChromeAPI } from './chrome-api.mjs';
import { createChromePolyfill } from './chrome-polyfill.mjs';
import { BrowserAPI, matches, safeFile } from './browser-api.mjs';

const ROOT = path.join(homedir(), 'extensions');
const json = value => JSON.parse(JSON.stringify(value));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const mime = name => ({ '.html':'text/html; charset=utf-8', '.js':'text/javascript', '.mjs':'text/javascript', '.css':'text/css', '.json':'application/json', '.png':'image/png', '.svg':'image/svg+xml', '.jpg':'image/jpeg', '.woff2':'font/woff2' }[path.extname(name)] || 'application/octet-stream');
export class ExtensionHost {
  constructor(options = {}) {
    this.options = { bridgeUrl: process.env.AGY_BRIDGE_URL || 'http://127.0.0.1:8765', deviceId: process.env.AGY_DEVICE_ID || process.env.DEVICE_ID || '', root: ROOT, hostPort: 8865, ...options };
    this.logs = []; this.extensions = new Map(); this.toolRegistry = new Map(); this.contexts = new Map(); this.pendingMessages = new Map(); this.ports = new Map();
    this.browser = new BrowserAPI(this); this.sequence = 0; this.downloads = new Map(); this.downloadCounter = 0; this.closed = false; this.polling = false; this.disabledIds = new Set();
    this.stateRoot = options.stateRoot || path.join(this.options.root, '.antigem-state'); this.server = null;
  }
  recordLog(extensionId,level,args){const message=args.map(value=>{if(value?.stack)return value.stack;try{return typeof value==='string'?value:JSON.stringify(value)??String(value);}catch{return String(value);}}).join(' ').slice(0,8000);this.logs.push({extension_id:extensionId,time:Date.now(),level,message});if(this.logs.length>500)this.logs.splice(0,this.logs.length-500);}
  resourceBase(ext) { return `${this.baseUrl || `http://127.0.0.1:${this.options.hostPort}`}/extension/${ext.resourceToken}/`; }
  readState(ext) { try { return JSON.parse(fs.readFileSync(path.join(this.stateRoot, ext.id + '.json'), 'utf8')); } catch (e) { if (e.code !== 'ENOENT') throw new Error(`Cannot read extension state: ${e.message}`); return {}; } }
  saveState(ext) { fs.mkdirSync(this.stateRoot, { recursive: true, mode: 0o700 }); const file = path.join(this.stateRoot, ext.id + '.json'); const temporary = file + '.' + randomUUID() + '.tmp'; fs.writeFileSync(temporary, JSON.stringify({ version: ext.manifest.version, local: ext.storage.local, sync: ext.storage.sync, alarms: [...ext.alarms.values()].map(a => a.info) }), { mode: 0o600 }); fs.renameSync(temporary, file); }
  context(ext, type, extra = {}) { const ctx = { id: randomUUID(), token: randomBytes(24).toString('hex'), extension: ext, type, events: [], waiters: new Set(), ...extra }; this.contexts.set(ctx.id, ctx); return ctx; }
  send(ctx, name, args = []) {
    if (!this.contexts.has(ctx.id)) return;
    if (ctx.client) { ctx.client.dispatch(name, args).catch(console.error); return; }
    if (ctx.devtools) { ctx.devtools.send('Runtime.evaluate', { contextId: ctx.executionContextId, expression: `globalThis.__antigemClient?.dispatch(${JSON.stringify(name)},${JSON.stringify(args)})`, awaitPromise: false }).catch(() => this.dropContext(ctx)); return; }
    ctx.events.push({ seq: ++this.sequence, name, args });
    if (ctx.events.length > 500) { this.dropContext(ctx); return; }
    for (const resolve of ctx.waiters) resolve(); ctx.waiters.clear();
  }
  broadcast(extensionId, name, args) { for (const ctx of this.contexts.values()) if (ctx.extension.id === extensionId) this.send(ctx, name, args); }
  broadcastAll(name, args) { for (const ext of this.extensions.values()) if (ext.enabled) this.broadcast(ext.id, name, args); }
  sender(ctx) { return { id: ctx.extension.id, url: ctx.url || this.resourceBase(ctx.extension), origin: ctx.url ? new URL(ctx.url).origin : this.baseUrl, ...(ctx.tabId != null ? { tab: this.browser.lastTabs.get(ctx.tabId) || { id: ctx.tabId }, frameId: ctx.frameId || 0 } : {}) }; }
  async message(source, message, target) {
    const contexts = [...this.contexts.values()].filter(c => c.extension.enabled && c.id !== source.id && c.extension.id === (target.extensionId || source.extension.id) &&
      (target.tabId != null ? c.type === 'content' && c.tabId === target.tabId && (target.frameId == null || c.frameId === target.frameId) : c.type !== 'content'));
    if (!contexts.length) throw new Error('Could not establish connection. Receiving end does not exist.');
    return new Promise((resolve, reject) => {
      const requestId = randomUUID(); const pending = { resolve, reject, recipients: new Set(contexts.map(c => c.id)), timer: setTimeout(() => { this.pendingMessages.delete(requestId); reject(new Error('The message port closed before a response was received')); }, 180000) };
      this.pendingMessages.set(requestId, pending); for (const ctx of contexts) this.send(ctx, 'runtime.message', [json(message), this.sender(source), requestId]);
    });
  }
  reply(ctx, args, unhandled = false) {
    const p = this.pendingMessages.get(args.requestId); if (!p || !p.recipients.has(ctx.id)) return;
    p.recipients.delete(ctx.id);
    if (unhandled && p.recipients.size) return;
    this.pendingMessages.delete(args.requestId); clearTimeout(p.timer);
    if (unhandled) p.resolve(undefined); else args.error ? p.reject(new Error(args.error)) : p.resolve(args.response);
  }
  dropContext(ctx) {
    if (!this.contexts.delete(ctx.id)) return; for (const wake of ctx.waiters) wake(); ctx.client?.dispose();
    for (const [id, p] of this.pendingMessages) if (p.recipients.delete(ctx.id) && !p.recipients.size) { clearTimeout(p.timer); p.reject(new Error('Extension context was closed')); this.pendingMessages.delete(id); }
    for (const [id, p] of this.ports) if (p.peers.has(ctx.id)) { p.peers.delete(ctx.id); for (const peer of p.peers) { const target = this.contexts.get(peer); if (target) this.send(target, 'runtime.portDisconnect', [id]); } this.ports.delete(id); }
    for (const timer of ctx.timers || []) { clearTimeout(timer); clearInterval(timer); }
  }
  dropTabContexts(tabId) { for (const c of [...this.contexts.values()]) if (c.tabId === tabId) this.dropContext(c); }
  permissions(ext, name) { const permissions = ext.manifest.permissions || []; if (!permissions.includes(name)) throw new Error(`Extension requires the ${name} permission`); }
  async loadExtension(directory) {
    const dir = fs.realpathSync(directory); const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));
    const id = manifest.id || path.basename(dir); if (!/^[a-zA-Z0-9_-]+$/.test(id)) throw new Error('Invalid extension id');
    if (this.extensions.has(id)) { if (this.extensions.get(id).dir === dir) return this.extensions.get(id); throw new Error(`Duplicate extension id: ${id}`); }
    if (![2, 3].includes(manifest.manifest_version)) throw new Error('Only manifest versions 2 and 3 are supported');
    const state = this.readState({ id }); const ext = { id, dir, manifest, enabled: true, worldName: `antigem:${id}:${randomUUID()}`, binding: '__antigem_' + randomBytes(12).toString('hex'), resourceToken: randomBytes(24).toString('hex'), storage: { local: state.local || {}, sync: state.sync || {}, session: {} }, alarms: new Map(), scripts: [], registrations: new Map(), dynamicScripts: [], action: {}, sidePanel: {}, contexts: [] };
    if (manifest.default_locale) { try { manifest.__messages = JSON.parse(fs.readFileSync(safeFile(dir, `_locales/${manifest.default_locale}/messages.json`), 'utf8')); } catch {} }
    // Tool names are unique within an extension; separate MCP servers may reuse a name.
    const names = new Set();
    for (const tool of manifest.mcp_tools || []) { if (typeof tool.name !== 'string' || !tool.name || names.has(tool.name)) throw new Error(`Duplicate or missing MCP tool name: ${tool.name}`); names.add(tool.name); }
    const ctx = this.context(ext, 'background', { timers: new Set() });
    const chrome = createChromePolyfill({ extension: ext, context: ctx, host: this });
    chrome.antigem = this.nativeAPI(ctx);
    const log = Object.fromEntries(['log','warn','error','info','debug'].map(level => [level, (...a) => {this.recordLog(id,level,a);console.error(`[${id}]`, ...a);}]));
    const timer = (repeat, fn, ms, ...args) => { const handle = (repeat ? setInterval : setTimeout)(() => { try { fn(...args)?.catch?.(e => log.error(e)); } catch (e) { log.error(e); } if (!repeat) ctx.timers.delete(handle); }, ms); ctx.timers.add(handle); return handle; };
    const workerURL = this.resourceBase(ext) + (manifest.background?.service_worker || manifest.background?.scripts?.[0] || 'background.js');
    const sandbox = { console: log, chrome, browser: chrome, setTimeout: (...a) => timer(false, ...a), setInterval: (...a) => timer(true, ...a), clearTimeout: h => { clearTimeout(h); ctx.timers.delete(h); }, clearInterval: h => { clearInterval(h); ctx.timers.delete(h); },
      fetch: (input, options) => fetch(typeof input === 'string' ? new URL(input, workerURL) : input, options), URL, URLSearchParams, TextEncoder, TextDecoder,
      Blob, FormData, Request, Response, Headers, AbortController, AbortSignal, crypto: webcrypto, performance, structuredClone, atob, btoa,
      Event, EventTarget, MessageEvent, WebSocket: globalThis.WebSocket, navigator: { userAgent: `AntiGem Extension Host Node/${process.versions.node}`, language: 'en', languages: ['en'] }, location: new URL(workerURL) };
    sandbox.self = sandbox; sandbox.globalThis = sandbox;
    const scopeEvents = new EventTarget(); sandbox.addEventListener = scopeEvents.addEventListener.bind(scopeEvents); sandbox.removeEventListener = scopeEvents.removeEventListener.bind(scopeEvents); sandbox.dispatchEvent = scopeEvents.dispatchEvent.bind(scopeEvents);
    const context = vm.createContext(sandbox); ext.context = context; ext.background = ctx;
    const classic = file => new vm.Script(fs.readFileSync(safeFile(dir, file), 'utf8'), { filename: path.join(dir, file) }).runInContext(context, { timeout: 10000 });
    sandbox.importScripts = (...files) => { for (const f of files) classic(f); };
    try {
      const worker = manifest.background?.service_worker;
      if (worker && manifest.background.type === 'module' && /(^|\n)\s*(import\s|export\s)/.test(fs.readFileSync(safeFile(dir, worker), 'utf8'))) {
        if (!vm.SourceTextModule) throw new Error('Module workers require Node --experimental-vm-modules');
        const modules = new Map();
        const load = async file => { file = safeFile(dir, file); if (modules.has(file)) return modules.get(file); const module = new vm.SourceTextModule(fs.readFileSync(file, 'utf8'), { context, identifier: file, initializeImportMeta: meta => { meta.url = pathToFileURL(file).href; }, importModuleDynamically: async (specifier, ref) => { const child = await load(path.relative(dir, path.resolve(path.dirname(ref.identifier), specifier))); if (child.status === 'unlinked') await child.link(linker); if (child.status === 'linked') await child.evaluate(); return child; } }); modules.set(file, module); return module; };
        const linker = (specifier, ref) => { if (!specifier.startsWith('.')) throw new Error('Worker imports must be relative extension resources'); return load(path.relative(dir, path.resolve(path.dirname(ref.identifier), specifier))); };
        const module = await load(worker); await module.link(linker); await module.evaluate({ timeout: 10000 });
      } else for (const file of worker ? [worker] : manifest.background?.scripts || []) classic(file);
      this.extensions.set(id, ext);
      for (const tool of manifest.mcp_tools || []) this.toolRegistry.set(JSON.stringify([ext.id, tool.name]), { spec: tool, extension: ext });
      if (!state.version || state.version !== manifest.version) this.send(ctx, 'runtime.onInstalled', [{ reason: !state.version ? 'install' : 'update', previousVersion: state.version }]);
      this.send(ctx, 'runtime.onStartup');
      for (const alarm of state.alarms || []) this.createAlarm(ext, alarm.name, { when: alarm.scheduledTime, periodInMinutes: alarm.periodInMinutes });
      this.saveState(ext); console.error(`[ExtensionHost] Loaded ${manifest.name || id} (${(manifest.mcp_tools || []).length} tools)`); return ext;
    } catch (error) { this.recordLog(id,'error',[error]);this.extensions.delete(id); for(const [key,entry] of this.toolRegistry)if(entry.extension===ext)this.toolRegistry.delete(key);for(const alarm of ext.alarms.values())clearTimeout(alarm.timer);this.dropContext(ctx);throw new Error(`Failed to load ${id}: ${error.message}`); }
  }
  async loadAllExtensions(root = this.options.root) {
    let disabled = []; try { disabled = JSON.parse(fs.readFileSync(path.join(root, 'extensions.json'), 'utf8')).disabled || []; } catch {}
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) if (entry.isDirectory() && !entry.name.startsWith('.') && fs.existsSync(path.join(root, entry.name, 'manifest.json'))) {
      const dir = path.join(root, entry.name); const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'));
      for(const id of disabled)this.disabledIds.add(id);
      if (this.disabledIds.has(manifest.id || entry.name)) continue;
      try { await this.loadExtension(dir); } catch (error) { console.error(error.message); }
    }
  }
  nativeAPI(context) {
    const api = {};
    for (const name of ['automation','readFile','writeFile','listFiles','copyFile','stat','download','mediaFrame','mediaInfo']) api[name] = args => this.request(context, `antigem.${name}`, args);
    return api;
  }
  async request(ctx, method, args = []) {
    if (!this.contexts.has(ctx.id) || !ctx.extension.enabled) throw new Error('Extension context is no longer active');
    const ext = ctx.extension;
    if (method === 'runtime.respond' || method === 'runtime.unhandled') { this.reply(ctx, args, method.endsWith('unhandled')); return; }
    if (method === 'runtime.sendMessage') { if (args.extensionId !== ext.id) throw new Error('External extension messaging is not supported'); return this.message(ctx, args.message, args); }
    if (method === 'tabs.sendMessage') { await this.browser.checkAccess(ext, args.tabId); await this.ensureContentContext(ext, args.tabId); return this.message(ctx, args.message, { tabId: args.tabId, frameId: args.options?.frameId }); }
    if (method === 'runtime.connect') {
      if(args.target.extensionId&&args.target.extensionId!==ext.id)throw new Error('External extension messaging is not supported');
      const peers = [...this.contexts.values()].filter(c => c.id !== ctx.id && c.extension === ext && (args.target.tabId != null ? c.type === 'content' && c.tabId === args.target.tabId : c.type !== 'content'));
      if (!peers.length) throw new Error('Could not establish connection. Receiving end does not exist.');
      this.ports.set(args.portId, { peers: new Set([ctx.id, ...peers.map(p => p.id)]), owner: ctx.id });
      for (const peer of peers) this.send(peer, 'runtime.portConnect', [args.portId, args.name, this.sender(ctx)]); return;
    }
    if (method === 'runtime.portMessage' || method === 'runtime.portDisconnect') { const p = this.ports.get(args.portId); if (!p?.peers.has(ctx.id)) throw new Error('Attempting to use a disconnected port'); for (const id of p.peers) if (id !== ctx.id) { const peer = this.contexts.get(id); if (peer) this.send(peer, method === 'runtime.portMessage' ? 'runtime.portMessage' : 'runtime.portDisconnect', method === 'runtime.portMessage' ? [args.portId, args.message] : [args.portId]); } if (method.endsWith('portDisconnect')) this.ports.delete(args.portId); return; }
    if (method === 'runtime.getPlatformInfo') return { os: 'android', arch: process.arch === 'arm64' ? 'arm64' : process.arch, nacl_arch: process.arch };
    if (method === 'runtime.getBrowserInfo') return { name: 'AntiGem', vendor: 'AntiGem', version: '1.0', buildID: process.version };
    if (method === 'runtime.getContexts') return [...this.contexts.values()].filter(c => c.extension === ext).map(c => ({ contextId: c.id, contextType: c.type.toUpperCase(), tabId: c.tabId ?? -1, frameId: c.frameId ?? -1, documentUrl: c.url || '' }));
    if (method === 'runtime.reload') { setTimeout(() => this.reload(ext.id).catch(console.error), 10); return; }
    if (method === 'runtime.openOptionsPage' || method.startsWith('sidePanel.') || ['action.openPopup','action.setPopup','action.getPopup'].includes(method)) throw new Error('Extension UI is not supported; use this extension through its MCP server in Settings');
    if (method.startsWith('storage.')) {
      this.permissions(ext, 'storage'); const area = args.area; if (!['local','sync','session'].includes(area)) throw new Error('Unknown storage area'); const store = ext.storage[area], value = args.value;
      if (method === 'storage.get') { if (value == null) return json(store); if (typeof value === 'string') return value in store ? { [value]: json(store[value]) } : {}; if (Array.isArray(value)) return Object.fromEntries(value.filter(k => k in store).map(k => [k, json(store[k])])); return { ...value, ...Object.fromEntries(Object.keys(value).filter(k => k in store).map(k => [k, json(store[k])])) }; }
      if (method === 'storage.getKeys') return Object.keys(store);
      if (method === 'storage.getBytesInUse') { const keys = value == null ? Object.keys(store) : Array.isArray(value) ? value : [value]; return keys.reduce((n, k) => n + (k in store ? Buffer.byteLength(k) + Buffer.byteLength(JSON.stringify(store[k])) : 0), 0); }
      const changes = {}; const keys = method === 'storage.set' ? Object.keys(value || {}) : method === 'storage.clear' ? Object.keys(store) : Array.isArray(value) ? value : [value];
      for (const key of keys) { const oldValue = store[key], newValue = method === 'storage.set' ? value[key] : undefined; if (JSON.stringify(oldValue) === JSON.stringify(newValue)) continue; changes[key] = { ...(oldValue !== undefined ? { oldValue } : {}), ...(newValue !== undefined ? { newValue } : {}) }; if (newValue === undefined) delete store[key]; else store[key] = json(newValue); }
      if (area !== 'session') this.saveState(ext);
      if (Object.keys(changes).length) { this.broadcast(ext.id, 'storage.onChanged', [changes, area]); this.broadcast(ext.id, `storage.${area}.onChanged`, [changes]); } return;
    }
    if (method.startsWith('permissions.')) { const declared = { permissions: ext.manifest.permissions || [], origins: ext.manifest.host_permissions || [] }; if (method === 'permissions.getAll') return declared; if (method === 'permissions.contains') return (args[0]?.permissions || []).every(p => declared.permissions.includes(p)) && (args[0]?.origins || []).every(p => declared.origins.includes(p)); if (method === 'permissions.request') return this.request(ctx, 'permissions.contains', args); throw new Error('Change permissions in the extension manifest'); }
    if (method.startsWith('alarms.')) { this.permissions(ext, 'alarms'); const name = typeof args[0] === 'string' ? args[0] : ''; if (method === 'alarms.create') return this.createAlarm(ext, name, typeof args[0] === 'object' ? args[0] : args[1] || {}); if (method === 'alarms.get') return ext.alarms.get(name)?.info; if (method === 'alarms.getAll') return [...ext.alarms.values()].map(a => a.info); if (method === 'alarms.clearAll') { for (const a of ext.alarms.values()) clearTimeout(a.timer); ext.alarms.clear(); this.saveState(ext); return true; } const a = ext.alarms.get(name); if (a) clearTimeout(a.timer); const removed = ext.alarms.delete(name); this.saveState(ext); return removed; }
    if (method.startsWith('downloads.')) { this.permissions(ext, 'downloads'); return this.downloadRequest(ctx, method, args); }
    if (method.startsWith('action.')) {
      const [area, op] = method.split('.'); const key = op.replace(/^(set|get)/, '');
      if (op === 'enable' || op === 'disable') { ext.action.Enabled = op === 'enable'; return; } if (op === 'isEnabled') return ext.action.Enabled !== false;
      if (op.startsWith('set')) { const field = {BadgeText:'text',BadgeBackgroundColor:'color',Title:'title',Popup:'popup'}[key]; ext[area][key] = field ? args[0]?.[field] : args[0]; return; }
      if (op.startsWith('get')) return ext[area][key] ?? (key === 'Popup' ? ext.manifest.action?.default_popup || '' : key === 'Title' ? ext.manifest.action?.default_title || ext.manifest.name : key === 'BadgeText' ? '' : {});
      throw new Error(`Unsupported Chrome API: ${method}`);
    }
    if (method.startsWith('scripting.') && method.includes('ContentScripts')) { this.permissions(ext, 'scripting'); const ids = args[0]?.ids; if (method === 'scripting.getRegisteredContentScripts') return ext.dynamicScripts.filter(s => !ids || ids.includes(s.id)); if (method === 'scripting.unregisterContentScripts') ext.dynamicScripts = ext.dynamicScripts.filter(s => ids && !ids.includes(s.id)); else { for (const s of args[0] || []) { const index = ext.dynamicScripts.findIndex(d => d.id === s.id); if (method.includes('register') && !method.includes('unregister') && index >= 0) throw new Error(`Duplicate content script id ${s.id}`); if (index >= 0) ext.dynamicScripts[index] = { ...ext.dynamicScripts[index], ...s }; else ext.dynamicScripts.push(s); } } await this.resetRegistrations(ext); return; }
    if (method.startsWith('antigem.')) return this.nativeRequest(ctx, method, args);
    if (method.startsWith('debugger.')) this.permissions(ext, 'debugger');
    if (method.startsWith('scripting.')) this.permissions(ext, 'scripting');
    if (method.startsWith('cookies.')) this.permissions(ext, 'cookies');
    if (method === 'i18n.getAcceptLanguages') return ['en']; if (method === 'i18n.detectLanguage') return { isReliable: false, languages: [] };
    return this.browser.request(ctx, method, args);
  }
  createAlarm(ext, name, info) { const previous = ext.alarms.get(name); if (previous) clearTimeout(previous.timer); const alarm = { name, scheduledTime: info.when ?? Date.now() + (info.delayInMinutes ?? info.periodInMinutes ?? 0) * 60000, ...(info.periodInMinutes ? { periodInMinutes: info.periodInMinutes } : {}) }; const fire = () => { this.broadcast(ext.id, 'alarms.onAlarm', [alarm]); if (alarm.periodInMinutes) { alarm.scheduledTime = Date.now() + alarm.periodInMinutes * 60000; entry.timer = setTimeout(fire, alarm.periodInMinutes * 60000); } else ext.alarms.delete(name); this.saveState(ext); }; const entry = { info: alarm, timer: setTimeout(fire, Math.min(2147483647, Math.max(0, alarm.scheduledTime - Date.now()))) }; ext.alarms.set(name, entry); this.saveState(ext); }
  filePath(value) { const home = path.resolve(process.env.HOME || path.dirname(this.options.root)); const resolved = path.resolve(String(value || '').replace(/^~(?=\/|$)/, home)); if (resolved !== home && !resolved.startsWith(home + path.sep) && !resolved.startsWith('/sdcard/')) throw new Error('File must be inside the app home or shared storage'); return resolved; }
  async nativeRequest(ctx, method, args) {
    this.permissions(ctx.extension, 'antigemFiles');
    if (method === 'antigem.automation') { const tab = await this.browser.tab(args.tabId); await this.browser.checkAccess(ctx.extension, tab.id); return this.browser.bridge('automation', { ...args, tab_id: this.browser.native(tab.id), owner: ctx.extension.id }); }
    if (method === 'antigem.listFiles') { const root=this.filePath(args.path);const output=[];const limit=Math.min(2000,Math.max(1,args.limit||500));const scan=dir=>{for(const entry of fs.readdirSync(dir,{withFileTypes:true})){if(output.length>=limit)return;const file=path.join(dir,entry.name);if(entry.isSymbolicLink())continue;const stat=fs.statSync(file);output.push({name:entry.name,path:file,isFile:entry.isFile(),isDirectory:entry.isDirectory(),size:stat.size,modified:stat.mtimeMs});if(args.recursive&&entry.isDirectory())scan(file);}};scan(root);return output; }
    if (method === 'antigem.copyFile') { const from=this.filePath(args.from),to=this.filePath(args.to);fs.mkdirSync(path.dirname(to),{recursive:true});if(from!==to)fs.copyFileSync(from,to);return {path:to}; }
    if (method === 'antigem.stat') { const file = this.filePath(args.path); const s = fs.statSync(file); return { path: file, size: s.size, modified: s.mtimeMs, isFile: s.isFile() }; }
    if (method === 'antigem.readFile') { const file = this.filePath(args.path); const data = fs.readFileSync(file); return args.encoding === 'base64' ? data.toString('base64') : data.toString('utf8'); }
    if (method === 'antigem.writeFile') { const file = this.filePath(args.path); fs.mkdirSync(path.dirname(file), { recursive: true }); (args.append ? fs.appendFileSync : fs.writeFileSync)(file, args.data, args.encoding === 'base64' ? 'base64' : 'utf8'); return { path: file }; }
    if (method === 'antigem.download') { const file = this.filePath(args.path); if (args.reuse && fs.existsSync(file) && fs.statSync(file).size) return { path: file }; const response = await fetch(args.url, { signal: AbortSignal.timeout(180000) }); if (!response.ok) throw new Error(`Download HTTP ${response.status}`); fs.mkdirSync(path.dirname(file), { recursive: true }); const temp = file + '.' + randomUUID() + '.tmp'; try { const handle = fs.createWriteStream(temp); const { pipeline } = await import('node:stream/promises'); const { Readable } = await import('node:stream'); await pipeline(Readable.fromWeb(response.body), handle); fs.renameSync(temp, file); } finally { if (fs.existsSync(temp)) fs.unlinkSync(temp); } return { path: file }; }
    if (method === 'antigem.mediaFrame' || method === 'antigem.mediaInfo') return this.browser.bridge(method.endsWith('mediaFrame') ? 'media/frame' : 'media/info', { ...args, path: this.filePath(args.path) });
    throw new Error(`Unsupported native operation ${method}`);
  }
  async downloadRequest(ctx, method, args) {
    if (method === 'downloads.download') {
      const options = args[0] || {}; const name = options.filename || decodeURIComponent(new URL(options.url).pathname.split('/').pop()) || 'download';
      if (path.isAbsolute(name) || name.split(/[\\/]/).includes('..')) throw new Error('Download filename must be relative');
      const dir = path.join(this.options.downloadDir || path.join(path.dirname(this.options.root), 'Downloads'), ctx.extension.id); fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
      let filename = path.join(dir, name); if (options.conflictAction !== 'overwrite') { const original = filename; let n = 1; while (fs.existsSync(filename)) filename = path.join(path.dirname(original), `${path.basename(original, path.extname(original))} (${n++})${path.extname(original)}`); }
      const id = ++this.downloadCounter, abort = new AbortController(); const item = { id, url: options.url, finalUrl: options.url, filename, state: 'in_progress', paused: false, canResume: false, bytesReceived: 0, totalBytes: -1, fileSize: -1, exists: false, startTime: new Date().toISOString(), byExtensionId: ctx.extension.id, incognito: false };
      this.downloads.set(id, { item, abort }); this.broadcast(ctx.extension.id, 'downloads.onCreated', [item]);
      (async () => { try { const response = await fetch(options.url, { signal: abort.signal }); if (!response.ok) throw new Error(`HTTP ${response.status}`); item.finalUrl = response.url; item.mime = response.headers.get('content-type') || ''; item.totalBytes = Number(response.headers.get('content-length') || -1); const { pipeline } = await import('node:stream/promises'); const { Readable, Transform } = await import('node:stream'); await pipeline(Readable.fromWeb(response.body), new Transform({ transform(chunk, encoding, next) { item.bytesReceived += chunk.length; next(null, chunk); } }), fs.createWriteStream(filename)); item.state = 'complete'; item.exists = true; item.fileSize = item.bytesReceived; item.endTime = new Date().toISOString(); this.broadcast(ctx.extension.id, 'downloads.onChanged', [{ id, state: { previous: 'in_progress', current: 'complete' } }]); } catch (error) { item.state = 'interrupted'; item.error = error.message; fs.rmSync(filename, { force: true }); this.broadcast(ctx.extension.id, 'downloads.onChanged', [{ id, state: { previous: 'in_progress', current: 'interrupted' }, error: { current: error.message } }]); } })(); return id;
    }
    const query = args[0] || {}; const entries = [...this.downloads.values()].filter(d => d.item.byExtensionId === ctx.extension.id);
    if (method === 'downloads.search') return entries.map(d => d.item).filter(d => (query.id == null || d.id === query.id) && (!query.state || d.state === query.state) && (!query.query || query.query.every(q => d.filename.includes(q) || d.url.includes(q)))).slice(0, query.limit || 100);
    if (method === 'downloads.erase') { const ids=entries.filter(d=>query.id==null||d.item.id===query.id).map(d=>d.item.id);for(const id of ids){this.downloads.delete(id);this.broadcast(ctx.extension.id,'downloads.onErased',[id]);}return ids; }
    const entry = this.downloads.get(args[0]); if (!entry || entry.item.byExtensionId !== ctx.extension.id) throw new Error('Download not found');
    if (method === 'downloads.cancel') { entry.abort.abort(); return; }
    if (method === 'downloads.removeFile') { fs.rmSync(entry.item.filename, { force: true }); entry.item.exists = false; return; }
    throw new Error(`Unsupported Chrome API: ${method}`);
  }
  async onDevToolsEvent(tabId, name, params) {
    if (name === 'Runtime.executionContextDestroyed') { for (const c of [...this.contexts.values()]) if (c.tabId === tabId && c.executionContextId === params.executionContextId) this.dropContext(c); }
    if (name === 'Runtime.executionContextsCleared') this.dropTabContexts(tabId);
    if (name !== 'Runtime.bindingCalled' || !params.name.startsWith('__antigem_')) return;
    const ext = [...this.extensions.values()].find(e => e.binding === params.name && e.enabled); if (!ext) return;
    const value = JSON.parse(params.payload); let ctx = [...this.contexts.values()].find(c => c.extension === ext && c.tabId === tabId && c.executionContextId === params.executionContextId);
    if (!ctx) ctx = this.context(ext, 'content', { tabId, frameId: value.frameId || 0, executionContextId: params.executionContextId, devtools: await this.browser.connection(tabId), url: value.url });
    if (value.kind === 'ready') return;
    const deliver = (result, error) => ctx.devtools.send('Runtime.evaluate', { contextId: params.executionContextId, expression: `globalThis.__antigemResolve(${JSON.stringify(value.requestId)},${JSON.stringify(result ?? null)},${JSON.stringify(error || null)})` }).catch(() => {});
    try { const result = await this.request(ctx, value.method, value.args); await deliver(result); } catch (error) { await deliver(null, error.message); }
  }
  contentBootstrap(ext) {
    return `(()=>{if(globalThis.__antigemClient)return;const pending=new Map();let seq=0;const rpc=(method,args)=>new Promise((resolve,reject)=>{const requestId=++seq;pending.set(requestId,{resolve,reject});globalThis[${JSON.stringify(ext.binding)}](JSON.stringify({kind:'rpc',requestId,method,args,url:location.href}));});globalThis.__antigemResolve=(id,result,error)=>{const p=pending.get(id);if(p){pending.delete(id);error?p.reject(new Error(error)):p.resolve(result)}};const client=(${createChromeAPI.toString()})({id:${JSON.stringify(ext.id)},manifest:${JSON.stringify(ext.manifest)},contextType:'content',contextId:crypto.randomUUID(),resourceBase:${JSON.stringify(this.resourceBase(ext))},rpc});globalThis.chrome=client.chrome;globalThis.browser=client.chrome;globalThis.__antigemClient=client;globalThis[${JSON.stringify(ext.binding)}](JSON.stringify({kind:'ready',url:location.href}));})();`;
  }
  async ensureContentContext(ext, tabId, frameId) {
    const c = await this.browser.connection(tabId); ext.binding ||= '__antigem_' + ext.id.replace(/[^a-zA-Z0-9]/g, '_');
    if (!ext.registrations.get(tabId)?.binding) { await c.send('Runtime.addBinding', { name: ext.binding, executionContextName: ext.worldName }); }
    await this.browser.evaluate(tabId, this.contentBootstrap(ext), ext.worldName, frameId);
  }
  async prepareTab(tab) {
    const applicable = [...this.extensions.values()].filter(e => e.enabled && (e.manifest.content_scripts?.length || e.dynamicScripts.length));
    if (!applicable.length) return; const c = await this.browser.connection(tab.id);
    for (const ext of applicable) {
      if (ext.registrations.get(tab.id)?.connection === c) continue;
      ext.binding ||= '__antigem_' + ext.id.replace(/[^a-zA-Z0-9]/g, '_');
      await c.send('Runtime.addBinding', { name: ext.binding, executionContextName: ext.worldName });
      const registration = { connection: c, ids: [], binding: true }; ext.registrations.set(tab.id, registration);
      const scripts = [...(ext.manifest.content_scripts || []), ...ext.dynamicScripts];
      for (const [index, spec] of scripts.entries()) {
        const files = spec.js || [], css = (spec.css || []).map(f => fs.readFileSync(safeFile(ext.dir, f), 'utf8')).join('\n');
        const isolated = (spec.world || 'ISOLATED') !== 'MAIN';
        const key = `__antigemScript_${ext.worldName.replace(/\W/g, '_')}_${index}`;
        const match = `(${matches.toString()})`;
        const body = `if(!${JSON.stringify(spec.matches || [])}.some(p=>${match}(p,location.href))||${JSON.stringify(spec.exclude_matches || [])}.some(p=>${match}(p,location.href))||${!spec.all_frames}&&window!==top)return;if(globalThis[${JSON.stringify(key)}])return;globalThis[${JSON.stringify(key)}]=true;${isolated ? this.contentBootstrap(ext) : ''}\n${css ? `const style=document.createElement('style');style.textContent=${JSON.stringify(css)};(document.head||document.documentElement).appendChild(style);` : ''}\n${files.map(f => fs.readFileSync(safeFile(ext.dir, f), 'utf8')).join('\n;\n')}`;
        const source = `(function(){const run=()=>{${body}\n};${spec.run_at === 'document_start' ? 'run()' : "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else run()"}})();`;
        const result = await c.send('Page.addScriptToEvaluateOnNewDocument', { source, ...(isolated ? { worldName: ext.worldName } : {}) }); registration.ids.push(result.identifier);
        await this.browser.evaluate(tab.id, source, isolated ? ext.worldName : 'MAIN');
      }
    }
  }
  async resetRegistrations(ext) { for (const reg of ext.registrations.values()) for (const identifier of reg.ids) await reg.connection.send('Page.removeScriptToEvaluateOnNewDocument', { identifier }).catch(() => {}); ext.registrations.clear(); }
  async unload(id) { const ext = this.extensions.get(id); if (!ext) throw new Error('Extension not found'); ext.enabled = false; await this.resetRegistrations(ext); for (const a of ext.alarms.values()) clearTimeout(a.timer); for (const ctx of [...this.contexts.values()]) if (ctx.extension === ext) this.dropContext(ctx); for (const [name, entry] of this.toolRegistry) if (entry.extension === ext) this.toolRegistry.delete(name); for(const ids of this.browser.debuggers.values())ids.delete(id);this.extensions.delete(id); }
  async reload(id) { const ext = this.extensions.get(id); if (!ext) throw new Error('Extension not found'); const dir = ext.dir; await this.unload(id); return this.loadExtension(dir); }
  listTools(extensionId) { return [...this.toolRegistry.values()].filter(e => e.extension.enabled && (extensionId === undefined || e.extension.id === extensionId)).map(e => ({ name: e.spec.name, description: e.spec.description || '', inputSchema: e.spec.inputSchema || { type: 'object', properties: {} } })); }
  async callTool(name, args = {}, extensionId) { const entry = this.toolRegistry.get(JSON.stringify([extensionId, name])); if (!entry?.extension.enabled) throw new Error(`Tool not found in extension ${extensionId || '(not selected)'}: ${name}`); const caller = this.context(entry.extension, 'mcp'); try { return await this.message(caller, { type: 'mcp_tool_call', tool: name, args }, { extensionId: entry.extension.id }); } finally { this.dropContext(caller); } }
  async mcp(request, extensionId) {
    const { id, method, params } = request; const respond = result => ({ jsonrpc: '2.0', id, result });
    if (id === undefined) return null;
    const ext = this.extensions.get(extensionId);
    if (!ext?.enabled) return { jsonrpc: '2.0', id, error: { code: -32602, message: 'Select an enabled extension for this MCP server' } };
    if (method === 'initialize') return respond({ protocolVersion: params?.protocolVersion || '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: ext.id, version: ext.manifest.version || '1.0.0' } });
    if (method === 'ping') return respond({});
    if (method === 'tools/list') return respond({ tools: this.listTools(extensionId) });
    if (method === 'tools/call') { try { const value = await this.callTool(params?.name, params?.arguments || {}, extensionId); return respond(value?.content && Array.isArray(value.content) ? value : { content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value ?? null, null, 2) }] }); } catch (error) { return respond({ isError: true, content: [{ type: 'text', text: error.message }] }); } }
    return { jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } };
  }
  async startHttp() {
    if (this.server) return;
    this.server = http.createServer((req, res) => this.http(req, res).catch(error => { if (!res.headersSent) { res.writeHead(500, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error: error.message })); } else res.destroy(); }));
    await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(this.options.hostPort, '127.0.0.1', resolve); });
    this.baseUrl = `http://127.0.0.1:${this.server.address().port}`;
    this.pollTimer = setInterval(async () => { if (this.polling) return; this.polling = true; try { await this.browser.poll(); } catch (e) { if (this.options.verbose) console.error(e.message); } finally { this.polling = false; } }, 300);
  }
  async http(req, res) {
    const url = new URL(req.url, this.baseUrl); const send = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value ?? null)); };
    if (/^\/extension\/[a-f0-9]{48}\//.test(url.pathname)) {
      const [, , token, ...parts] = url.pathname.split('/'); const ext = [...this.extensions.values()].find(e => e.resourceToken === token && e.enabled); if (!ext) return send({ error: 'Extension resource not found' }, 404);
      const file = safeFile(ext.dir, decodeURIComponent(parts.join('/')));
      if (['.html','.htm','.xhtml'].includes(path.extname(file).toLowerCase())) return send({ error: 'Extension UI is not supported' }, 404);
      const content = fs.readFileSync(file);
      res.writeHead(200, { 'Content-Type': mime(file), 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }); res.end(content); return;
    }
    const ctx = this.contexts.get(req.headers['x-extension-context']);
    const validContext = ctx && ctx.token === req.headers['x-extension-token']; const native = this.options.deviceId && req.headers['x-device-id'] === this.options.deviceId;
    if (!native && !validContext) return send({ error: 'Unauthorized' }, 401);
    let body = ''; for await (const chunk of req) { body += chunk; if (Buffer.byteLength(body) > 64 * 1024 * 1024) return send({ error: 'Request too large' }, 413); }
    const data = body ? JSON.parse(body) : {};
    if (url.pathname === '/health' && native) return send({ status: 'ok', ready: !!this.ready, pid: process.pid, tools: this.toolRegistry.size });
    if (url.pathname === '/tab/release' && native) { const tab = (await this.browser.tabs()).find(t => this.browser.native(t.id) === data.nativeId); if (tab) this.browser.release(tab.id); return send({ success: true }); }
    if (url.pathname === '/tab/prepare' && native) { const tab = (await this.browser.tabs()).find(t => this.browser.native(t.id) === data.nativeId); if (!tab) throw new Error('Tab not found'); await this.prepareTab(tab); return send({ success: true }); }
    if (url.pathname === '/mcp' && native) {
      if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return send({ error: 'Use POST for MCP requests' }, 405); }
      const extensionId = url.searchParams.get('extension');
      if (!extensionId) return send({ error: 'Select one extension using ?extension=EXTENSION_ID' }, 400);
      if (!this.extensions.get(extensionId)?.enabled) return send({ error: 'Extension is disabled or not loaded' }, 404);
      const response = await this.mcp(data, extensionId);
      if (response === null) { res.writeHead(202, { 'Cache-Control': 'no-store' }); res.end(); return; }
      return send(response);
    }
    if (url.pathname === '/extensions' && native) return send([...this.extensions.values()].map(e => ({ id: e.id, name: e.manifest.name, enabled: e.enabled, dir: e.dir, tools: (e.manifest.mcp_tools || []).length })));
    if (url.pathname === '/extension/logs' && native) {const limit=data.limit??50;if(!Number.isInteger(limit)||limit<1||limit>200)throw new Error('Log limit must be 1–200');const entries=this.logs.filter(e=>!data.id||e.extension_id===data.id);return send({logs:entries.slice(-limit),retained:entries.length,capacity:500});}
    if (url.pathname === '/extension/load' && native) { const ext = await this.loadExtension(data.dir); return send({ id: ext.id }); }
    if (url.pathname === '/extension/reload' && native) return send({ id: (await this.reload(data.id)).id });
    if (url.pathname === '/extension/unload' && native) { await this.unload(data.id); return send({ success: true }); }
    if (url.pathname === '/rpc' && validContext) { try { return send({ result: await this.request(ctx, data.method, data.args) }); } catch (error) { return send({ error: error.message }, 400); } }
    if (url.pathname === '/context/close' && validContext) { this.dropContext(ctx); return send({}); }
    if (url.pathname === '/events' && validContext) { const after = Number(url.searchParams.get('after') || 0); ctx.events = ctx.events.filter(e => e.seq > after); if (!ctx.events.length) await new Promise(resolve => { let timer; const done = () => { clearTimeout(timer); ctx.waiters.delete(done); res.off('close', done); resolve(); }; timer = setTimeout(done, 20000); ctx.waiters.add(done); res.once('close', done); }); if (!res.destroyed) return send(ctx.events); return; }
    send({ error: 'Not found' }, 404);
  }
  async close() { this.closed = true; clearInterval(this.pollTimer); for (const ext of [...this.extensions.values()]) await this.unload(ext.id); this.browser.close(); for (const p of this.pendingMessages.values()) { clearTimeout(p.timer); p.reject(new Error('Extension host closed')); } this.server?.closeAllConnections?.(); await new Promise(resolve => this.server ? this.server.close(resolve) : resolve()); }
}

async function stdio(handle) {
  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of rl) { if (!line.trim()) continue; let request; try { request = JSON.parse(line); } catch { process.stdout.write(JSON.stringify({ jsonrpc:'2.0', id:null, error:{ code:-32700, message:'Parse error' } }) + '\n'); continue; }
    // Do not serialize long-running tool calls behind each other.
    Promise.resolve().then(() => handle(request)).then(value => { if (value) process.stdout.write(JSON.stringify(value) + '\n'); }, error => process.stdout.write(JSON.stringify({ jsonrpc:'2.0', id:request.id ?? null, error:{ code:-32603, message:error.message } }) + '\n'));
  }
}
async function main() {
  const args = process.argv.slice(2), option = (name, fallback) => { const index = args.indexOf(name); return index >= 0 ? args[index + 1] : fallback; };
  if (args.includes('--help')) { console.log('extension-host.mjs [--serve --port 8865] [--root DIR] [--extension DIR] [--device-id ID] [--bridge-url URL] [--connect URL] [--list] [--call TOOL JSON]'); return; }
  const deviceId = option('--device-id', process.env.AGY_DEVICE_ID || process.env.DEVICE_ID || '');
  const remote = option('--connect');
  if (remote) { await stdio(async request => { if (request.id === undefined) return null; const url = new URL('/mcp', remote); if(option('--extension-id'))url.searchParams.set('extension',option('--extension-id'));const response = await fetch(url, { method:'POST', headers:{ 'Content-Type':'application/json', 'X-Device-Id':deviceId }, body:JSON.stringify(request) }); if (!response.ok) throw new Error(`Extension host HTTP ${response.status}`); return response.json(); }); return; }
  const host = new ExtensionHost({ root:option('--root', ROOT), hostPort:Number(option('--port', 8865)), deviceId, bridgeUrl:option('--bridge-url', process.env.AGY_BRIDGE_URL || 'http://127.0.0.1:8765') });
  await host.startHttp();
  for (let i = 0; i < args.length; i++) if (args[i] === '--extra-root' && args[i + 1]) await host.loadAllExtensions(args[++i]);
  if (option('--extension')) await host.loadExtension(option('--extension')); else await host.loadAllExtensions();
  host.ready = true;
  if (process.env.AGY_PARENT_PID) {
    const timer = setInterval(() => { try { process.kill(Number(process.env.AGY_PARENT_PID), 0); } catch { clearInterval(timer); host.close().then(() => process.exit(0)); } }, 5000);
    timer.unref();
  }
  if (args.includes('--list')) { console.log(JSON.stringify(host.listTools(), null, 2)); await host.close(); return; }
  if (option('--call')) { console.log(JSON.stringify(await host.callTool(option('--call'), JSON.parse(args[args.indexOf('--call') + 2] || '{}'), option('--extension-id')), null, 2)); await host.close(); return; }
  for (const signal of ['SIGINT','SIGTERM']) process.on(signal, () => host.close().then(() => process.exit(0)));
  if (!args.includes('--serve')) { await stdio(request => host.mcp(request, option('--extension-id'))); await host.close(); }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error('[ExtensionHost]', error); process.exitCode = 1; });
