package com.example.gemini.ui.browser

/**
 * JavaScript helpers injected into the Google Flow tab of the in-app browser (see google-flow-mcp-spec.md).
 *
 * - Reads and management (credits, models, projects, media, video status, create/rename project) call Flow's
 *   own `batchexecute` RPCs from inside the page, using the page's cookies and live WIZ_global_data tokens.
 * - Generation is submitted through the real UI ("Start generation"), so the page creates its own
 *   reCAPTCHA token. A Send Guard checks every generate request inside the page against what was asked for
 *   (model, aspect, count, mode, reference ids) and blocks it before it leaves the page if anything is wrong,
 *   so no credits are spent on a wrong request. The token is never read, created or replayed.
 * - UI elements are located only by ARIA labels, roles and visible text, scoped to Flow's CDK overlay panes.
 *
 * The library lives on `window.__agFlow` and is re-installed after every navigation.
 */
object FlowPageScripts {

    const val VERSION = 9

    // RPC ids from the spec's verified catalog
    const val RPC_CREDITS = "nzlxg"
    const val RPC_MODELS = "HTrJv"
    const val RPC_PROJECTS = "UpteDb"
    const val RPC_PROJECT_CONTENTS = "Zzl0ze"
    const val RPC_CREATE_PROJECT = "jHPbke"
    const val RPC_RENAME_PROJECT = "o8DA4"
    const val RPC_GET_MEDIA = "as29s"
    const val RPC_VIDEO_STATUS = "jwpduf"
    const val RPC_GENERATE_IMAGES = "ogiZ0b"
    const val RPC_UPLOAD = "maseQ"
    const val RPC_VIDEO_TEXT = "YhhmEf"
    const val RPC_VIDEO_START = "eb1hJf"
    const val RPC_VIDEO_START_END = "nprQif"
    const val RPC_VIDEO_REFERENCES = "MZZa6b"
    val RPC_GENERATE_VIDEO = listOf(RPC_VIDEO_TEXT, RPC_VIDEO_START, RPC_VIDEO_START_END, RPC_VIDEO_REFERENCES)
    const val RPC_EDIT_VIDEO = "jIps6"
    const val RPC_UPSCALE_IMAGE = "SPrCad"
    const val RPC_RENAME_WORKFLOW = "mYWVGd"
    const val RPC_UPDATE_WORKFLOWS = "pGCYOe"
    const val RPC_CONCAT = "GjQLTd"
    const val RPC_CONCAT_STATUS = "IxdSwd"
    const val RPC_GIF = "QZasKb"
    const val RPC_CREATE_SCENE = "rqZuUc"
    const val RPC_EXTEND_VIDEO = "fZytfe"
    const val RPC_UPSCALE_VIDEO = "p0UkFb"

    private val LIB = """
        if (!window.__agFlow || window.__agFlow.v !== ${VERSION}) {
          window.__agFlow = (function () {
            var IMAGE_RPC = '${RPC_GENERATE_IMAGES}';
            var EDIT_RPC = '${RPC_EDIT_VIDEO}';
            var UPSCALE_RPC = '${RPC_UPSCALE_IMAGE}';
            var EXTEND_RPC = '${RPC_EXTEND_VIDEO}';
            var UPSCALE_VIDEO_RPC = '${RPC_UPSCALE_VIDEO}';
            var GEN_RPCS = [IMAGE_RPC, '${RPC_GENERATE_VIDEO.joinToString("', '")}', EDIT_RPC, UPSCALE_RPC, EXTEND_RPC, UPSCALE_VIDEO_RPC];
            var UPLOAD_RPC = '${RPC_UPLOAD}';
            var REFS_RPC = '${RPC_VIDEO_REFERENCES}';

            function shown(el) {
              if (!el || !el.getBoundingClientRect) return false;
              var r = el.getBoundingClientRect();
              if (r.width < 2 || r.height < 2) return false;
              var s = getComputedStyle(el);
              return s.display !== 'none' && s.visibility !== 'hidden' && parseFloat(s.opacity) !== 0;
            }
            function disabled(el) { return !!el.disabled || el.getAttribute('aria-disabled') === 'true'; }
            function norm(t) { return String(t || '').replace(/\s+/g, ' ').trim(); }
            function label(el) {
              var aria = el.getAttribute('aria-label') || '';
              var txt = norm(el.innerText || el.textContent);
              return norm(txt && txt.indexOf(aria) >= 0 ? txt : aria + ' ' + txt).slice(0, 200);
            }
            function point(el) {
              try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e) {}
              var r = el.getBoundingClientRect();
              var vv = window.visualViewport;
              var ox = vv ? vv.offsetLeft : 0, oy = vv ? vv.offsetTop : 0;
              var vw = vv ? vv.width : window.innerWidth, vh = vv ? vv.height : window.innerHeight;
              return {
                found: true,
                fx: (r.left + r.width / 2 - ox) / vw,
                fy: (r.top + r.height / 2 - oy) / vh,
                label: label(el),
                checked: el.getAttribute('aria-checked') === 'true',
                disabled: disabled(el)
              };
            }
            function byAria(name, root) {
              var list = (root || document).querySelectorAll('[aria-label]');
              for (var i = 0; i < list.length; i++) {
                if (list[i].getAttribute('aria-label') === name && shown(list[i])) return list[i];
              }
              return null;
            }
            // Shortest visible label matching the pattern wins (most specific element)
            function byText(pattern, sel, root) {
              var re = new RegExp(pattern, 'i'), best = null, bestLen = Infinity;
              (root || document).querySelectorAll(sel || 'button, [role="button"]').forEach(function (el) {
                if (!shown(el)) return;
                var l = label(el);
                if (l && re.test(l) && l.length < bestLen) { best = el; bestLen = l.length; }
              });
              return best;
            }
            function promptEl() {
              var el = document.querySelector('div.ProseMirror[contenteditable="true"]');
              if (el && shown(el)) return el;
              var list = document.querySelectorAll('[contenteditable="true"], textarea');
              for (var i = 0; i < list.length; i++) if (shown(list[i])) return list[i];
              return null;
            }
            // The settings popover is the CDK overlay pane that contains radios
            function pane() {
              var panes = document.querySelectorAll('.cdk-overlay-pane');
              for (var i = 0; i < panes.length; i++) {
                if (shown(panes[i]) && panes[i].querySelector('[role="radio"]')) return panes[i];
              }
              return null;
            }
            function menu() {
              var list = document.querySelectorAll('[role="menu"]');
              for (var i = 0; i < list.length; i++) if (shown(list[i])) return list[i];
              return null;
            }
            // The asset picker ("+" ingredients and Start/End frame slots) is the overlay pane with the asset list
            function picker() {
              var panes = document.querySelectorAll('.cdk-overlay-pane');
              for (var i = 0; i < panes.length; i++) {
                var p = panes[i];
                if (shown(p) && (p.querySelector('[role="listbox"][aria-label="Asset list"]') || byText('upload\\s*media', 'button', p))) return p;
              }
              return null;
            }
            function listbox() { var p = picker(); return p && p.querySelector('[role="listbox"]'); }
            // The prompt bar: the nearest container of the prompt box that also holds "Start generation".
            // Chips elsewhere (e.g. an image's info panel listing its own ingredients) must never be counted.
            function promptRoot() {
              var pm = promptEl();
              if (!pm) return null;
              var el = pm;
              for (var i = 0; el && i < 12; i++) {
                if (el.querySelector && el.querySelector('[aria-label="Start generation"]')) return el;
                el = el.parentElement;
              }
              el = pm;
              for (var j = 0; el.parentElement && j < 4; j++) el = el.parentElement;
              return el;
            }
            function chips() {
              var root = promptRoot();
              if (!root) return [];
              return Array.prototype.filter.call(root.querySelectorAll('[aria-label="Ingredient"], [aria-label="Image ingredient"]'), shown);
            }
            function refCount() { return chips().length; }
            // The media id each attached chip really carries (its thumbnail is flow-content.google/image/<mediaId>)
            function chipIds() {
              return chips().map(function (el) {
                var img = el.querySelector('img');
                var m = /\/image\/([^?\/#]+)/.exec(img ? (img.currentSrc || img.src || '') : '');
                return m ? m[1] : '';
              });
            }
            function chipPoint(index) { var c = chips()[index]; return c ? point(c) : { found: false }; }
            function pickerTab(name) {
              var p = picker();
              var el = p && byText('(^|\\s)' + name + '\\s*$', '[role="tab"]', p);
              return el ? point(el) : { found: false };
            }
            function pickerOptionAt(i) {
              var lb = listbox();
              var list = lb ? Array.prototype.filter.call(lb.querySelectorAll('[role="option"]'), shown) : [];
              return list[i] ? point(list[i]) : { found: false };
            }
            function pickerOptions() {
              var lb = listbox();
              var list = lb ? Array.prototype.filter.call(lb.querySelectorAll('[role="option"]'), shown) : [];
              return { count: list.length, first: list.length ? point(list[0]) : null, labels: list.slice(0, 10).map(label) };
            }
            // Keep the page at its zoomed-out width while AI drives it: no auto-zoom into focused fields
            function freezeZoom(on) {
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                (document.head || document.documentElement).appendChild(meta);
              }
              var current = meta.getAttribute('content') || '';
              if (on) {
                if (current.indexOf('user-scalable=no') >= 0 && window.__agFlowVp != null) return { ok: true };
                window.__agFlowVp = current;
                var width = (/width=(\d+)/.exec(current) || [])[1] || '1280';
                meta.setAttribute('content', 'width=' + width + ', user-scalable=no');
              } else if (window.__agFlowVp != null) {
                meta.setAttribute('content', window.__agFlowVp);
                window.__agFlowVp = null;
              }
              return { ok: true };
            }
            // Grid tiles: hover a tile (by its unique title) so its "More options" button appears
            function tileHover(title) {
              var tiles = document.querySelectorAll('flow-grid-tile-container');
              for (var i = 0; i < tiles.length; i++) {
                if (tiles[i].getAttribute('aria-label') !== title) continue;
                var t = tiles[i];
                try { t.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch (e) {}
                var r = t.getBoundingClientRect();
                var opts = { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
                [t].concat(Array.prototype.slice.call(t.querySelectorAll('*'), 0, 30)).forEach(function (el) {
                  ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'mousemove'].forEach(function (type) {
                    try { el.dispatchEvent(type.indexOf('pointer') === 0 ? new PointerEvent(type, opts) : new MouseEvent(type, opts)); } catch (e) {}
                  });
                });
                return { found: true };
              }
              return { found: false };
            }
            function tileMoreOptions(title) {
              var tiles = document.querySelectorAll('flow-grid-tile-container');
              for (var i = 0; i < tiles.length; i++) {
                if (tiles[i].getAttribute('aria-label') !== title) continue;
                var b = tiles[i].querySelector('button[aria-label="More options"]');
                return b ? point(b) : { found: false, tile: true };
              }
              return { found: false };
            }

            // ---------- Agent mode (the pill's aria-pressed is the source of truth) ----------
            function agentPill() {
              var list = document.querySelectorAll('button[aria-pressed]');
              for (var i = 0; i < list.length; i++) {
                if (shown(list[i]) && /(^|\s)agent\s*$/i.test(norm(list[i].innerText || list[i].textContent))) return list[i];
              }
              return null;
            }
            function agentState() {
              var pill = agentPill();
              return { found: !!pill, on: !!pill && pill.getAttribute('aria-pressed') === 'true',
                instructions: !!byAria('Agent instructions'), panel: !!byAria('Start new session') };
            }
            function agentOff() {
              var pill = agentPill();
              if (pill && pill.getAttribute('aria-pressed') === 'true') pill.click();
              if (byAria('Start new session')) { var close = byAria('Close'); if (close) close.click(); }
              return agentState();
            }

            // ---------- Project grid: search + tile menu (picker-free attach, upscales) ----------
            function setInputValue(input, value) {
              input.focus();
              var desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
              desc.set.call(input, value);
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
            }
            function gridSearch(value) {
              var input = document.querySelector('input[aria-label="Search"]');
              if (!input) return { ok: false };
              setInputValue(input, value);
              ['keydown', 'keypress', 'keyup'].forEach(function (type) {
                input.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
              });
              input.blur();
              return { ok: true };
            }
            function tiles(title) {
              return Array.prototype.filter.call(document.querySelectorAll('flow-grid-tile-container'), function (t) {
                return t.getAttribute('aria-label') === title;
              });
            }
            function tileCount(title) { return { count: tiles(title).length }; }
            function hover(t) {
              try { t.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch (e) {}
              var r = t.getBoundingClientRect();
              var o = { bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
              [t].concat(Array.prototype.slice.call(t.querySelectorAll('*'), 0, 40)).forEach(function (el) {
                ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'mousemove'].forEach(function (type) {
                  try { el.dispatchEvent(type.indexOf('pointer') === 0 ? new PointerEvent(type, o) : new MouseEvent(type, o)); } catch (e) {}
                });
              });
            }
            // Opens the "More options" menu of the (unique) tile with this title; script clicks work for this menu
            function tileMenu(title) {
              var list = tiles(title);
              if (list.length !== 1) return { ok: false, count: list.length };
              hover(list[0]);
              var b = list[0].querySelector('button[aria-label="More options"]');
              if (!b) return { ok: false, count: 1, noButton: true };
              b.click();
              return { ok: true };
            }
            function menuClick(name) {
              var want = name.toLowerCase(), best = null, bestLen = Infinity;
              document.querySelectorAll('[role="menu"] [role="menuitem"], .cdk-overlay-pane [role="menuitem"]').forEach(function (el) {
                var l = label(el);
                if (shown(el) && l.toLowerCase().indexOf(want) >= 0 && l.length < bestLen) { best = el; bestLen = l.length; }
              });
              if (!best) return { ok: false };
              if (disabled(best)) return { ok: false, disabled: true };
              best.click();
              return { ok: true, label: label(best) };
            }
            function clickAria(name) { var el = byAria(name); if (!el) return { ok: false }; el.click(); return { ok: true }; }

            // ---------- Editor: version history ----------
            function mainImageId() {
              var best = null;
              document.querySelectorAll('img').forEach(function (img) {
                var r = img.getBoundingClientRect();
                if (r.width > 300 && shown(img) && (!best || r.width > best.getBoundingClientRect().width)) best = img;
              });
              var m = best ? /\/image\/([^?\/#]+)/.exec(best.currentSrc || best.src || '') : null;
              return { id: m ? m[1] : null };
            }
            function historyItems() {
              return Array.prototype.filter.call(document.querySelectorAll('flow-tile-container'), shown);
            }
            function openHistory() {
              if (historyItems().length) return { ok: true, count: historyItems().length };
              var b = byText('(^|\\s)show history\\s*$');
              if (!b) return { ok: false };
              b.click();
              return { ok: true };
            }
            function historyCount() { return { count: historyItems().length }; }
            function historyClick(i) {
              var it = historyItems()[i];
              if (!it) return { ok: false };
              var target = it.querySelector('button, img') || it;
              target.click();
              return { ok: true };
            }

            // ---------- UI ----------
            function ui() {
              var w = window.WIZ_global_data;
              var m = /\/project\/([^/?#]+)/.exec(location.pathname);
              var trigger = byAria('Settings trigger');
              return {
                url: location.href,
                path: location.pathname,
                ready: !!(w && w.SNlM0e),
                projectId: m ? m[1] : null,
                prompt: !!promptEl(),
                settingsTrigger: !!trigger,
                settingsText: trigger ? norm(trigger.innerText || trigger.textContent) : null,
                agentInstructions: !!byAria('Agent instructions'),
                agentOn: (function () { var p = agentPill(); return !!p && p.getAttribute('aria-pressed') === 'true'; })(),
                newProject: !!byText('new\\s*project'),
                signIn: !!byText('^\\s*(sign\\s*in|log\\s*in)\\b', 'a, button, [role="button"]'),
                account: (function () {
                  var el = document.querySelector('[aria-label^="Google Account:"]');
                  if (!el) return null;
                  return norm(el.getAttribute('aria-label').replace(/^Google Account:\s*/, '')).replace(/\s*\(/, ' (').replace(/\)\s*,.*$/, ')');
                })(),
                settingsOpen: !!pane(),
                menuOpen: !!menu(),
                pickerOpen: !!picker(),
                refs: refCount(),
                chipIds: chipIds(),
                startSlot: !!byText('^\\s*start\\s*$'),
                endSlot: !!byText('^\\s*end\\s*$')
              };
            }
            function aria(name) { var el = byAria(name); return el ? point(el) : { found: false }; }
            function text(pattern, sel) { var el = byText(pattern, sel); return el ? point(el) : { found: false }; }
            function agentToggle() { var el = byText('^\\s*(\\S+\\s+)?agent\\s*$'); return el ? point(el) : { found: false }; }
            function radio(pattern) {
              var root = pane();
              if (!root) return { found: false };
              var el = byText(pattern, '[role="radio"]', root);
              return el ? point(el) : { found: false };
            }
            function radios() {
              var root = pane(), out = [];
              if (!root) return out;
              root.querySelectorAll('[role="radio"]').forEach(function (el) {
                if (shown(el)) out.push(label(el) + (el.getAttribute('aria-checked') === 'true' ? ' [on]' : ''));
              });
              return out;
            }
            function modelButton() {
              var root = pane();
              var el = root && root.querySelector('button[aria-label="Select model family"]');
              return el && shown(el) ? point(el) : { found: false };
            }
            function menuItem(name) {
              if (!menu()) return { found: false };
              var want = name.toLowerCase(), best = null, bestLen = Infinity;
              document.querySelectorAll('[role="menu"] [role="menuitem"]').forEach(function (el) {
                var l = label(el);
                if (shown(el) && l.toLowerCase().indexOf(want) >= 0 && l.length < bestLen) { best = el; bestLen = l.length; }
              });
              return best ? point(best) : { found: false };
            }
            function menuItems() {
              var root = menu(), out = [];
              if (root) root.querySelectorAll('[role="menuitem"]').forEach(function (el) { if (shown(el)) out.push(label(el)); });
              return out;
            }
            function cost() {
              var root = pane();
              var m = root ? /Generating will use (\d+) credits?/i.exec(root.innerText || '') : null;
              return { cost: m ? Number(m[1]) : null };
            }
            function pickerButton(pattern) {
              var p = picker();
              var el = p && byText(pattern, 'button, [role="button"]', p);
              return el ? point(el) : { found: false };
            }
            function pickerSearch(value) {
              var p = picker();
              var input = p && p.querySelector('input[aria-label="Search assets"]');
              if (!input) return { ok: false };
              input.focus();
              input.select();
              document.execCommand('insertText', false, value);
              if (input.value !== value) {
                var desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                desc.set.call(input, value);
                input.dispatchEvent(new Event('input', { bubbles: true }));
              }
              return { ok: true };
            }
            function pickerItem(mediaId) {
              var lb = listbox();
              if (!lb) return { found: false };
              var opts = lb.querySelectorAll('[role="option"]');
              for (var i = 0; i < opts.length; i++) {
                var img = opts[i].querySelector('img');
                var src = img ? (img.currentSrc || img.src || '') : '';
                if (src.indexOf('/' + mediaId) >= 0 && shown(opts[i])) return point(opts[i]);
              }
              return { found: false };
            }
            function pickerScroll() {
              var lb = listbox();
              if (!lb) return { ok: false };
              var before = lb.scrollTop;
              lb.scrollTop = before + Math.max(200, lb.clientHeight * 0.8);
              return { ok: true, moved: lb.scrollTop !== before };
            }
            function findPrompt() { var el = promptEl(); return el ? point(el) : { found: false }; }
            function promptValue() { var el = promptEl(); return { value: el ? norm(el.tagName === 'TEXTAREA' ? el.value : el.innerText) : '' }; }
            function setPrompt(value) {
              var el = promptEl();
              if (!el) return { ok: false, error: 'prompt box not found' };
              el.focus();
              if (el.tagName === 'TEXTAREA') {
                el.select();
                document.execCommand('insertText', false, value);
              } else {
                var range = document.createRange();
                range.selectNodeContents(el);
                var sel = window.getSelection();
                sel.removeAllRanges();
                sel.addRange(range);
                document.execCommand('delete', false);
                document.execCommand('insertText', false, value);
              }
              var v = norm(el.tagName === 'TEXTAREA' ? el.value : el.innerText);
              return { ok: v.indexOf(norm(value).slice(0, 40)) >= 0, value: v.slice(0, 200) };
            }
            function blurAll() {
              try { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); } catch (e) {}
              try { window.getSelection().removeAllRanges(); } catch (e) {}
              return { ok: true };
            }
            function escape() {
              var ev = { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true, cancelable: true };
              (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', ev));
              document.body.dispatchEvent(new KeyboardEvent('keydown', ev));
              return { ok: true };
            }
            function controls() {
              var out = [];
              document.querySelectorAll('button, [role="button"], [role="radio"], [role="menuitem"]').forEach(function (el) {
                if (!shown(el)) return;
                var l = label(el);
                if (l && l.length <= 80 && out.indexOf(l) < 0) out.push(l);
              });
              return out.slice(0, 60);
            }

            // ---------- RPC (batchexecute) ----------
            function parseBatch(text, id) {
              var lines = String(text || '').split('\n');
              for (var i = 0; i < lines.length; i++) {
                if (lines[i].charAt(0) !== '[') continue;
                try {
                  var arr = JSON.parse(lines[i]);
                  for (var j = 0; j < arr.length; j++) {
                    var e = arr[j];
                    if (e && e[0] === 'wrb.fr' && e[1] === id) {
                      return e[2] ? { value: JSON.parse(e[2]) } : { error: 'Flow returned error ' + JSON.stringify(e[5]) };
                    }
                  }
                } catch (err) {}
              }
              return null;
            }
            function rpc(id, args) {
              var w = window.WIZ_global_data;
              if (!w || !w.SNlM0e) return Promise.reject(new Error('Flow page is not ready (no WIZ_global_data)'));
              var q = new URLSearchParams({ rpcids: id, 'source-path': location.pathname, bl: w.cfb2h, 'f.sid': w.FdrFJe,
                hl: 'en', _reqid: String(100000 + Math.floor(Math.random() * 900000)), rt: 'c' });
              var body = new URLSearchParams({ 'f.req': JSON.stringify([[[id, JSON.stringify(args), null, 'generic']]]), at: w.SNlM0e });
              return fetch('/_/AiSandboxAngularFrontend/data/batchexecute?' + q, {
                method: 'POST', credentials: 'include', body: body,
                headers: { 'content-type': 'application/x-www-form-urlencoded;charset=UTF-8', 'x-same-domain': '1' }
              }).then(function (r) {
                return r.text().then(function (t) {
                  var res = parseBatch(t, id);
                  if (!res) throw new Error('No response for ' + id + ' (HTTP ' + r.status + ')');
                  if (res.error) throw new Error(res.error);
                  return res.value;
                });
              });
            }

            function at(x, path) {
              for (var i = 0; i < path.length; i++) { if (!Array.isArray(x)) return undefined; x = x[path[i]]; }
              return x;
            }
            function str(x) { return typeof x === 'string' ? x : null; }
            function flowUrl(x) { return typeof x === 'string' && x.indexOf('https://flow-content.google/') === 0 ? x : null; }
            function findUrl(x, depth) {
              if (x == null || depth > 10) return null;
              if (typeof x === 'string') return flowUrl(x);
              if (Array.isArray(x)) {
                for (var i = 0; i < x.length; i++) { var r = findUrl(x[i], depth + 1); if (r) return r; }
              }
              return null;
            }
            function mediaRecord(d) {
              var meta = at(d, [5]) || [];
              var video = flowUrl(at(d, [7, 0, 8]));
              var image = flowUrl(meta[10]);
              return {
                mediaId: str(at(d, [0])), projectId: str(at(d, [1])), workflowId: str(at(d, [2])),
                type: video || at(d, [7]) || /abra|veo|omni/.test(str(at(meta, [6, 1, 0, 0])) || '') ? 'video' : 'image',
                url: video || image || findUrl(d, 0), thumbnail: video ? image : null,
                modelKey: str(at(meta, [6, 1, 0, 0])),
                status: at(meta, [8, 0]) === undefined ? null : at(meta, [8, 0]),
                title: str(meta[1]) || '', prompt: (str(meta[12]) || '').slice(0, 300),
                w: at(d, [6, 2, 0]) || 0, h: at(d, [6, 2, 1]) || 0
              };
            }
            var POST = {
              credits: function (d) { return { credits: at(d, [0]) }; },
              models: function (d) {
                function names(list) { return (Array.isArray(list) ? list : []).map(function (f) { return str(at(f, [0])); }).filter(Boolean); }
                return { image: names(at(d, [0, 5])), video: names(at(d, [0, 4])) };
              },
              projects: function (d) {
                return (at(d, [0]) || []).map(function (p) {
                  return { projectId: at(p, [0]), title: at(p, [1, 0]) || '', created: at(p, [1, 2, 0]) || null };
                });
              },
              contents: function (d) {
                var wfs = {};
                (at(d, [1]) || []).forEach(function (w) {
                  var id = str(at(w, [0]));
                  if (id) wfs[id] = { title: str(at(w, [3, 0])) || '', archived: at(w, [3, 2]) === true };
                });
                return (at(d, [2]) || []).map(function (it) {
                  var meta = at(it, [5]) || [];
                  var status = at(meta, [8, 0]);
                  var key = str(at(meta, [6, 1, 0, 0])) || '';
                  var wf = wfs[str(at(it, [2])) || ''] || {};
                  return {
                    mediaId: at(it, [0]), workflowId: str(at(it, [2])),
                    type: at(it, [7]) || /abra|veo|omni/.test(key) ? 'video' : 'image',
                    title: wf.title || str(meta[1]) || '', trashed: !!wf.archived, modelKey: key || null,
                    prompt: (str(meta[12]) || '').slice(0, 300) || undefined, created: at(meta, [0, 0]) || null,
                    pending: status === 2 || status === 6,
                    w: at(it, [6, 2, 0]) || 0, h: at(it, [6, 2, 1]) || 0
                  };
                });
              },
              created: function (d) { return { projectId: str(at(d, [0])), title: at(d, [1, 0]) || '' }; },
              renamed: function (d) { return { title: at(d, [0]) || '' }; },
              media: mediaRecord,
              videoStatus: function (d) {
                return (at(d, [2]) || []).map(function (rec) { return { mediaId: str(at(rec, [0])), status: at(rec, [5, 8, 0]) }; });
              },
              concatStart: function (d) { return { job: str(at(d, [1, 0, 0])) }; },
              concatStatus: function (d, key) { var b = str(at(d, [4])); return { state: at(d, [0]), len: b ? stash(key, b) : 0, key: key }; },
              gif: function (d, key) { var b = str(at(d, [0])); return { len: b ? stash(key, b) : 0, key: key }; },
              scene: function (d) { return { sceneId: str(at(d, [0, 0])), name: str(at(d, [0, 1])) || '' }; },
              ok: function () { return { ok: true }; }
            };
            // Large base64 results (combined MP4, GIF) stay in the page and are read in chunks
            function stash(key, b64) {
              window.__agFlowBlob = window.__agFlowBlob || {};
              window.__agFlowBlob[key] = b64;
              return b64.length;
            }
            function blobChunk(key, start, len) { var b = (window.__agFlowBlob || {})[key]; return b ? b.substr(start, len) : ''; }
            function blobDrop(key) { if (window.__agFlowBlob) delete window.__agFlowBlob[key]; return { ok: true }; }

            // Async results are kept here until Kotlin collects them (evaluateJavascript cannot await)
            window.__agFlowRes = window.__agFlowRes || {};
            function rpcStart(key, id, argsJson, post) {
              var res = window.__agFlowRes;
              res[key] = { done: false };
              rpc(id, JSON.parse(argsJson)).then(function (v) {
                res[key] = { done: true, value: post && POST[post] ? POST[post](v, key) : v };
              }, function (e) {
                res[key] = { done: true, error: String((e && e.message) || e) };
              });
              return { ok: true };
            }
            function result(key) {
              var r = window.__agFlowRes[key];
              if (!r) return { done: true, error: 'unknown request' };
              if (r.done) delete window.__agFlowRes[key];
              return r;
            }

            // ---------- Send Guard ----------
            // An expectation is armed right before "Start generation" is tapped; the next generate request is
            // validated against it and blocked (never sent to Google, no credits) if anything differs.
            function expect(json) { window.__agFlowExpect = JSON.parse(json); watch(); return { ok: true }; }
            function clearExpect() { window.__agFlowExpect = null; return { ok: true }; }
            function sameList(a, b) { return JSON.stringify(a || []) === JSON.stringify(b || []); }
            function validate(rpc, a, exp) {
              var p = [];
              if (!a) return ['could not read the request'];
              if ((exp.rpcs || []).indexOf(rpc) < 0) return ['wrong request kind ' + rpc + ' (expected ' + (exp.rpcs || []).join('/') + ')'];
              if (rpc === UPSCALE_RPC) {
                if (a[0] !== exp.mediaId) p.push('upscale media ' + a[0] + ' != ' + exp.mediaId);
                return p;
              }
              if (rpc === EXTEND_RPC || rpc === UPSCALE_VIDEO_RPC) {
                if (JSON.stringify(a).indexOf(exp.mediaId) < 0) p.push('request does not reference ' + exp.mediaId);
                return p;
              }
              if (rpc === EDIT_RPC) {
                var ed = at(a, [0]) || [];
                if (JSON.stringify(ed).indexOf(exp.mediaId) < 0) p.push('edit request does not reference ' + exp.mediaId);
                return p;
              }
              var isImage = rpc === IMAGE_RPC, isRefs = rpc === REFS_RPC;
              var entries = isImage ? at(a, [1]) : at(a, [0]);
              if (!Array.isArray(entries) || !entries.length) return ['request has no entries'];
              if (exp.count && entries.length !== exp.count) p.push('count ' + entries.length + ' != ' + exp.count);
              entries.forEach(function (e) {
                var key = String(isImage ? e[5] : isRefs ? e[2] : e[1]);
                var aspect = isImage ? e[4] : isRefs ? e[3] : e[2];
                if (exp.modelKey && key !== exp.modelKey) p.push('model ' + key + ' != ' + exp.modelKey);
                (exp.keyMust || []).forEach(function (re) { if (!new RegExp(re).test(key)) p.push('model key ' + key + ' does not match ' + re); });
                (exp.keyMustNot || []).forEach(function (re) { if (new RegExp(re).test(key)) p.push('model key ' + key + ' must not match ' + re); });
                if (exp.aspect != null && aspect !== exp.aspect) p.push('aspect ' + aspect + ' != ' + exp.aspect);
                if (isImage || isRefs) {
                  var refs = isImage ? (e[2] || []).map(function (r) { return r && r[0]; }) : (e[1] || []).map(function (r) { return r && r[1]; });
                  if (!sameList(refs, exp.refs)) p.push('reference ids ' + JSON.stringify(refs) + ' != ' + JSON.stringify(exp.refs || []));
                }
                if (isImage && exp.workflowId && at(e, [7, 4]) !== exp.workflowId) p.push('edit targets workflow ' + at(e, [7, 4]) + ' != ' + exp.workflowId);
                if (exp.start != null && at(e, [4, 1]) !== exp.start) p.push('start frame ' + at(e, [4, 1]) + ' != ' + exp.start);
                if (exp.end != null && at(e, [5, 1]) !== exp.end) p.push('end frame ' + at(e, [5, 1]) + ' != ' + exp.end);
              });
              return p.filter(function (x, i) { return p.indexOf(x) === i; });
            }

            // ---------- capture of the page's own generate request + response ----------
            // Each generate/upload request gets a sequence number when it is SENT; its reply is attached
            // to the same entry when it arrives, so several requests can be in flight at once.
            function watch() {
              if (window.__agFlowCapOn) return;
              window.__agFlowCapOn = true;
              window.__agFlowCap = [];
              window.__agFlowCapN = 0;
              function start(url, reqBody) {
                try {
                  var u = new URL(url, location.href);
                  if (u.pathname.indexOf('batchexecute') < 0) return null;
                  var id = (u.searchParams.get('rpcids') || '').split(',').filter(function (r) {
                    return GEN_RPCS.indexOf(r) >= 0 || r === UPLOAD_RPC;
                  })[0];
                  if (!id) return null;
                  var args = null;
                  try { args = JSON.parse(JSON.parse(new URLSearchParams(String(reqBody)).get('f.req'))[0][0][1]); } catch (e) {}
                  window.__agFlowCapN++;
                  var entry = { n: window.__agFlowCapN, rpc: id, args: args, done: false, status: 0, body: '' };
                  var exp = window.__agFlowExpect;
                  if (exp && id !== UPLOAD_RPC) {
                    window.__agFlowExpect = null;
                    var problems = validate(id, args, exp);
                    if (exp.dryRun) problems = ['dry-run'].concat(problems);
                    entry.guarded = true;
                    if (problems.length) {
                      entry.blocked = true;
                      entry.problems = problems;
                      entry.done = true;
                    }
                  }
                  window.__agFlowCap.push(entry);
                  if (window.__agFlowCap.length > 80) window.__agFlowCap.shift();
                  return entry;
                } catch (e) { return null; }
              }
              function finish(entry, status, body) {
                if (!entry) return;
                entry.status = status;
                entry.body = String(body || '');
                entry.done = true;
              }
              var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (m, u) { this.__agU = u; return oo.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function (b) {
                var x = this, entry = start(x.__agU, b);
                if (entry && entry.blocked) {
                  // Blocked by the guard: send an empty request to a dead path instead, so the page sees a failure
                  oo.call(x, 'POST', '/_/AiSandboxAngularFrontend/data/agBlocked', true);
                  return os.call(x, '');
                }
                if (entry) {
                  x.addEventListener('loadend', function () {
                    var body = '';
                    try { if (x.responseType === '' || x.responseType === 'text') body = x.responseText; } catch (e) {}
                    finish(entry, x.status, body);
                  });
                }
                return os.apply(this, arguments);
              };
              var of = window.fetch;
              window.fetch = function (input, init) {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                var entry = url.indexOf('batchexecute') >= 0 ? start(url, init && init.body) : null;
                if (entry && entry.blocked) return Promise.reject(new TypeError('Blocked by AntiGem send guard'));
                var p = of.apply(window, arguments);
                if (entry) {
                  p.then(function (res) {
                    res.clone().text().then(function (t) { finish(entry, res.status, t); }, function () { finish(entry, res.status, ''); });
                  }, function () { finish(entry, 0, ''); });
                }
                return p;
              };
            }
            // Long strings (reCAPTCHA token, file bytes) are never handed out
            function redact(x, depth) {
              if (typeof x === 'string') return x.length > 600 ? '[' + x.length + ' chars redacted]' : x;
              if (Array.isArray(x) && depth < 12) return x.map(function (v) { return redact(v, depth + 1); });
              return x;
            }
            function capMark() { watch(); return { n: window.__agFlowCapN || 0 }; }
            function entryOf(seq) {
              var list = window.__agFlowCap || [];
              for (var i = 0; i < list.length; i++) if (list[i].n === seq) return list[i];
              return null;
            }
            // First generate request sent after mark n (ours, since submits are serialized) — what the page actually sent
            function capSent(n) {
              var list = window.__agFlowCap || [];
              for (var i = 0; i < list.length; i++) {
                var c = list[i];
                if (c.n <= n || c.rpc === UPLOAD_RPC) continue;
                var a = c.args;
                var sent = c.rpc === IMAGE_RPC
                  ? { count: (at(a, [1]) || []).length, modelKey: str(at(a, [1, 0, 5])), aspect: at(a, [1, 0, 4]), refs: (at(a, [1, 0, 2]) || []).length }
                  : c.rpc === REFS_RPC
                    ? { count: (at(a, [0]) || []).length, modelKey: str(at(a, [0, 0, 2])), aspect: at(a, [0, 0, 3]), refs: (at(a, [0, 0, 1]) || []).length }
                    : { count: (at(a, [0]) || []).length, modelKey: str(at(a, [0, 0, 1])), aspect: at(a, [0, 0, 2]) };
                if (c.problems && c.problems[0] === 'dry-run') sent.args = redact(a, 0);
                return { found: true, seq: c.n, rpc: c.rpc, sent: sent, guarded: !!c.guarded, blocked: !!c.blocked, problems: c.problems || [] };
              }
              return { found: false };
            }
            // The reply to request [seq]; lost: true when the page navigated and the entry is gone
            function capResult(seq) {
              var c = entryOf(seq);
              if (!c) return { found: false, lost: true };
              if (!c.done) return { found: true, done: false };
              var out = { found: true, done: true, rpc: c.rpc, status: c.status };
              var parsed = parseBatch(c.body, c.rpc);
              if (!parsed) { out.error = 'Flow generate request failed (HTTP ' + c.status + ')'; return out; }
              if (parsed.error) { out.error = parsed.error; return out; }
              var R = parsed.value;
              if (c.rpc === UPSCALE_RPC) {
                out.mediaId = str(at(R, [0, 0]));
                return out;
              }
              if (c.rpc === IMAGE_RPC) {
                out.images = (at(R, [0]) || []).map(function (it, idx) {
                  var url = flowUrl(at(it, [6, 0, 13])) || findUrl(it, 0);
                  var id = str(at(it, [0]));
                  if (!id && url) { var m = /\/image\/([^?\/#]+)/.exec(url); id = m ? m[1] : null; }
                  return { mediaId: id, url: url, title: str(at(R, [1, idx, 3, 0])) || '', w: at(it, [6, 2, 0]) || 0, h: at(it, [6, 2, 1]) || 0 };
                }).filter(function (r) { return r.mediaId; });
              } else {
                var ids = (at(R, [3]) || []).map(function (m) { return str(at(m, [0])); }).filter(Boolean);
                if (!ids.length) ids = (at(R, [2]) || []).map(function (w) { return str(at(w, [3, 4])); }).filter(Boolean);
                out.mediaIds = ids;
                out.creditsLeft = at(R, [1]);
              }
              return out;
            }

            function uploadsAfter(n) {
              var ids = [], errors = [];
              (window.__agFlowCap || []).forEach(function (c) {
                if (c.n <= n || c.rpc !== UPLOAD_RPC || !c.done) return;
                var parsed = parseBatch(c.body, c.rpc);
                if (!parsed) errors.push('upload failed (HTTP ' + c.status + ')');
                else if (parsed.error) errors.push(parsed.error);
                else { var id = str(at(parsed.value, [0, 0])); if (id) ids.push(id); }
              });
              return { ids: ids, errors: errors };
            }

            return {
              v: ${VERSION},
              ui: ui, aria: aria, text: text, agentToggle: agentToggle, radio: radio, radios: radios,
              modelButton: modelButton, menuItem: menuItem, menuItems: menuItems, cost: cost, findPrompt: findPrompt,
              setPrompt: setPrompt, escape: escape, controls: controls, rpcStart: rpcStart, result: result,
              capMark: capMark, capSent: capSent, capResult: capResult, uploadsAfter: uploadsAfter,
              pickerButton: pickerButton, pickerSearch: pickerSearch, pickerItem: pickerItem, pickerScroll: pickerScroll,
              pickerTab: pickerTab, pickerOptions: pickerOptions, pickerOptionAt: pickerOptionAt,
              agentState: agentState, agentOff: agentOff, gridSearch: gridSearch, tileCount: tileCount, tileMenu: tileMenu,
              menuClick: menuClick, clickAria: clickAria, mainImageId: mainImageId, openHistory: openHistory,
              historyCount: historyCount, historyClick: historyClick, blurAll: blurAll, promptValue: promptValue, chipIds: chipIds, chipPoint: chipPoint,
              expect: expect, clearExpect: clearExpect, freezeZoom: freezeZoom, tileHover: tileHover,
              tileMoreOptions: tileMoreOptions, blobChunk: blobChunk, blobDrop: blobDrop
            };
          })();
        }
    """.trimIndent()

    /**
     * Wraps [expr] (which may use `s`, the helper library) so it returns its result as a JSON string.
     */
    fun call(expr: String): String = """
        (function () {
        $LIB
          var s = window.__agFlow;
          try { return JSON.stringify($expr); } catch (e) { return JSON.stringify({ error: String(e) }); }
        })();
    """.trimIndent()
}
