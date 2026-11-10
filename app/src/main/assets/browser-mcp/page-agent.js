/*
 * Page helper for the browser MCP (BrowserMcp.kt). Injected on demand into a tab's main frame; lives on
 * window.__antigemAgent. Gives elements stable refs (ref_N, kept per element until the page is replaced),
 * renders the page as an indented accessibility-style tree, finds elements by words, fills form fields
 * the way frameworks expect, extracts readable text and hit-tests points.
 *
 * Coordinates: "visual" = CSS px relative to the visible area (what screenshots show and CDP input uses);
 * "client" = CSS px relative to the top document's layout viewport (getBoundingClientRect, elementFromPoint).
 * client = visual + visualViewport offset.
 */
(function () {
  'use strict';
  var VERSION = 10;
  var prev = window.__antigemAgent;
  if (prev && prev.version === VERSION) return true;
  var store = prev && prev._store ? prev._store : { byRef: new Map(), byEl: new WeakMap(), next: 0 };
  if (!store.meta) store.meta = new Map(); // ref -> {tag, role, name}: lets a ref find its element again after a re-render
  var lastHealed = '';

  var SKIP = { script: 1, style: 1, noscript: 1, template: 1, meta: 1, link: 1, title: 1, head: 1, base: 1, br: 1, wbr: 1, col: 1, colgroup: 1, source: 1, track: 1, param: 1, datalist: 1 };
  var INTERACTIVE_ROLES = { button: 1, link: 1, checkbox: 1, radio: 1, 'switch': 1, tab: 1, menuitem: 1, menuitemcheckbox: 1, menuitemradio: 1, option: 1, combobox: 1, textbox: 1, searchbox: 1, slider: 1, spinbutton: 1, listbox: 1, treeitem: 1, 'file-input': 1 };
  var LANDMARKS = { navigation: 1, main: 1, banner: 1, contentinfo: 1, complementary: 1, form: 1, search: 1, region: 1, dialog: 1, alertdialog: 1, alert: 1, article: 1, table: 1, list: 1, menu: 1, menubar: 1, tablist: 1, toolbar: 1, tree: 1, grid: 1, group: 1, radiogroup: 1, tabpanel: 1, status: 1, figure: 1 };
  var NAME_FROM_CONTENT = { button: 1, link: 1, heading: 1, tab: 1, menuitem: 1, menuitemcheckbox: 1, menuitemradio: 1, option: 1, treeitem: 1, cell: 1, columnheader: 1, rowheader: 1, checkbox: 1, radio: 1, 'switch': 1, tooltip: 1, term: 1, legend: 1, caption: 1, label: 1 };
  var TEXT_BLOCK_ROLES = { paragraph: 1, listitem: 1, cell: 1, columnheader: 1, rowheader: 1, term: 1, definition: 1, caption: 1, blockquote: 1, code: 1, generic: 1, legend: 1, status: 1, alert: 1 };
  var TAG_ROLES = {
    button: 'button', select: 'combobox', textarea: 'textbox', img: 'image', nav: 'navigation', main: 'main',
    aside: 'complementary', form: 'form', article: 'article', table: 'table', tr: 'row', td: 'cell', th: 'columnheader',
    ul: 'list', ol: 'list', menu: 'list', dl: 'list', li: 'listitem', dt: 'term', dd: 'definition', dialog: 'dialog',
    details: 'group', summary: 'button', fieldset: 'group', legend: 'legend', label: 'label', p: 'paragraph',
    blockquote: 'blockquote', pre: 'code', option: 'option', progress: 'progressbar', meter: 'meter', video: 'video',
    audio: 'audio', iframe: 'iframe', frame: 'iframe', canvas: 'canvas', figure: 'figure', figcaption: 'caption',
    caption: 'caption', hr: 'separator', output: 'status', header: 'banner', footer: 'contentinfo', search: 'search',
    h1: 'heading', h2: 'heading', h3: 'heading', h4: 'heading', h5: 'heading', h6: 'heading', svg: 'image'
  };
  var TEXT_INPUTS = { text: 1, search: 1, email: 1, tel: 1, url: 1, password: 1, number: 1, '': 1 };
  var SENSITIVE_AUTOCOMPLETE = /\b(current-password|new-password|one-time-code|cc-number|cc-csc|cc-exp(-month|-year)?)\b/;
  var INTERACTIVE_SELECTOR = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[contenteditable=""],[contenteditable=true],' +
    '[contenteditable=plaintext-only],[onclick],[role=button],[role=link],[role=checkbox],[role=radio],[role=switch],[role=tab],' +
    '[role=menuitem],[role=option],[role=combobox],[role=textbox],[role=slider],[tabindex]:not([tabindex="-1"])';

  // ---------- refs ----------

  function refFor(el, role, name) {
    var r = store.byEl.get(el);
    if (r) {
      var w = store.byRef.get(r);
      if (w && w.deref() === el) return r;
    }
    r = 'ref_' + (++store.next);
    store.byRef.set(r, new WeakRef(el));
    store.byEl.set(el, r);
    if (role === undefined) { role = roleOf(el); name = nameOf(el, role); }
    if (store.meta.size > 20000) store.meta.clear();
    store.meta.set(r, { tag: el.localName, role: role, name: name || '' });
    return r;
  }

  /** Pages often redraw an element as a new node: find the one element that has the same tag, role and name. */
  function heal(ref) {
    var m = store.meta.get(ref);
    if (!m || !m.name) return null;
    var list = document.getElementsByTagName(m.tag), found = [];
    for (var i = 0; i < list.length && found.length < 2; i++) {
      var e = list[i];
      if (roleOf(e) !== m.role || nameOf(e, m.role) !== m.name) continue;
      var st = styleOf(e);
      if (!st || st.display === 'none' || st.visibility === 'hidden') continue;
      found.push(e);
    }
    if (found.length !== 1) return null;
    store.byRef.set(ref, new WeakRef(found[0]));
    if (!store.byEl.get(found[0])) store.byEl.set(found[0], ref);
    lastHealed = ref;
    return found[0];
  }

  function healNote(ref) {
    if (lastHealed !== normRef(ref)) return '';
    lastHealed = '';
    return ' (' + normRef(ref) + ' had been redrawn by the page; used the matching element)';
  }

  function normRef(ref) {
    ref = String(ref == null ? '' : ref).trim().replace(/^\[|\]$/g, '');
    return /^\d+$/.test(ref) ? 'ref_' + ref : ref;
  }

  function elFor(ref) {
    ref = normRef(ref);
    var w = store.byRef.get(ref);
    var el = w && w.deref();
    if (!el || !el.isConnected) {
      if (w) store.byRef.delete(ref);
      return heal(ref);
    }
    return el;
  }

  function missing(ref) {
    var id = normRef(ref), n = +id.replace(/^ref_/, '');
    if (!store.meta.has(id) && (isNaN(n) || n > store.next)) {
      return { ok: false, code: 'ref_unknown', error: id + ' is not from this page (' + location.href + '): the page was reloaded or replaced since it was read, or the app restarted. Read the page again (browser_find or browser_read_page) for fresh refs.' };
    }
    var m = store.meta.get(id);
    var what = m ? ' (' + (m.role === 'generic' ? m.tag : m.role) + (m.name ? ' "' + clip(m.name, 60) + '"' : '') + ')' : '';
    return { ok: false, code: 'ref_stale', error: 'No element ' + normRef(ref) + what + ' on the page any more (it was removed or the page changed). Read the page again (browser_find or browser_read_page) for fresh refs.' };
  }

  function prune() {
    store.byRef.forEach(function (w, r) {
      var e = w.deref();
      if (!e || !e.isConnected) store.byRef.delete(r);
    });
  }

  // ---------- geometry ----------

  function vv() {
    var v = window.visualViewport;
    return v ? { w: v.width, h: v.height, ox: v.offsetLeft, oy: v.offsetTop, s: v.scale } : { w: innerWidth, h: innerHeight, ox: 0, oy: 0, s: 1 };
  }

  function winOf(el) { return (el.ownerDocument && el.ownerDocument.defaultView) || window; }
  function styleOf(el) { try { return winOf(el).getComputedStyle(el); } catch (e) { return null; } }

  /** Offset of el's document inside the top document, in client px. */
  function frameOffset(el) {
    var x = 0, y = 0, w = winOf(el);
    while (w && w !== window) {
      var fe;
      try { fe = w.frameElement; } catch (e) { fe = null; }
      if (!fe) break;
      var r = fe.getBoundingClientRect();
      x += r.left + fe.clientLeft;
      y += r.top + fe.clientTop;
      w = w.parent;
    }
    return { x: x, y: y };
  }

  function clientRect(el) {
    var r = el.getBoundingClientRect(), o = frameOffset(el);
    return { left: r.left + o.x, top: r.top + o.y, width: r.width, height: r.height, right: r.right + o.x, bottom: r.bottom + o.y };
  }

  /** Point to press: centre of the first non-empty client rect (wrapped links), clamped into the visible area. */
  function pressPoint(el) {
    var rects = el.getClientRects(), o = frameOffset(el), r = null;
    for (var i = 0; i < rects.length; i++) if (rects[i].width > 0 && rects[i].height > 0) { r = rects[i]; break; }
    if (!r) r = el.getBoundingClientRect();
    var v = vv();
    var left = Math.max(r.left + o.x, v.ox), right = Math.min(r.right + o.x, v.ox + v.w);
    var top = Math.max(r.top + o.y, v.oy), bottom = Math.min(r.bottom + o.y, v.oy + v.h);
    if (right <= left || bottom <= top) return { cx: r.left + o.x + r.width / 2, cy: r.top + o.y + r.height / 2, onScreen: false, hasBox: r.width > 0 || r.height > 0 };
    return { cx: (left + right) / 2, cy: (top + bottom) / 2, onScreen: true, hasBox: true };
  }

  function onScreen(rect, v) {
    return rect.bottom > v.oy && rect.top < v.oy + v.h && rect.right > v.ox && rect.left < v.ox + v.w;
  }

  function fullyOnScreen(rect, v) {
    return rect.top >= v.oy - 1 && rect.bottom <= v.oy + v.h + 1 && rect.left >= v.ox - 1 && rect.right <= v.ox + v.w + 1;
  }

  // ---------- text, roles, names ----------

  function clip(s, n) {
    s = String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  function q(s) { return '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"'; }

  function textOf(el, max) {
    var s = '';
    try { s = el.innerText; } catch (e) { s = ''; }
    if (s == null || s === '') s = el.textContent || '';
    return clip(s, max);
  }

  /** Text of root without the text of skip (e.g. a label that wraps its own <select>). */
  function textExcluding(root, skip, max) {
    var out = '', doc = root.ownerDocument;
    var walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
      acceptNode: function (n) {
        if (n === skip) return NodeFilter.FILTER_REJECT;
        if (n.nodeType === 1 && SKIP[n.localName]) return NodeFilter.FILTER_REJECT;
        return n.nodeType === 3 ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      }
    });
    for (var n = walker.nextNode(); n && out.length < max * 2; n = walker.nextNode()) out += ' ' + n.nodeValue;
    return clip(out, max);
  }

  /** Text of el outside the controls inside it (empty for a wrapper around a link or button). */
  function looseText(el, max) {
    var out = '';
    var w = el.ownerDocument.createTreeWalker(el, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        // Labels are their fields' names, so they count as part of the controls
        if (n.nodeType === 1) return (SKIP[n.localName] || (n.localName === 'label' && n.control) || (n.matches && n.matches(INTERACTIVE_SELECTOR))) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_SKIP;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    for (var n = w.nextNode(); n && out.length < max * 2; n = w.nextNode()) out += ' ' + n.nodeValue;
    out = clip(out, max);
    return out.replace(/[\s\p{P}\p{S}]/gu, '').length >= 2 ? out : '';
  }

  function ownText(el, max) {
    var s = '';
    for (var n = el.firstChild; n; n = n.nextSibling) if (n.nodeType === 3) s += n.nodeValue;
    return clip(s, max);
  }

  function idsText(el, attr, max) {
    var ids = el.getAttribute(attr);
    if (!ids) return '';
    var doc = el.getRootNode && el.getRootNode().getElementById ? el.getRootNode() : el.ownerDocument;
    return clip(ids.split(/\s+/).slice(0, 5).map(function (id) {
      var t = (doc.getElementById && doc.getElementById(id)) || el.ownerDocument.getElementById(id);
      return t ? textOf(t, max) : '';
    }).join(' '), max);
  }

  function inputType(el) { return String(el.type || el.getAttribute('type') || '').toLowerCase(); }

  function isSensitive(el) {
    var t = el.localName;
    if (t !== 'input' && t !== 'textarea' && t !== 'select') return false;
    var type = inputType(el);
    if (type === 'password') return true;
    if (SENSITIVE_AUTOCOMPLETE.test(String(el.getAttribute('autocomplete') || '').toLowerCase())) return true;
    var st = styleOf(el);
    return !!(st && st.webkitTextSecurity && st.webkitTextSecurity !== 'none');
  }

  function roleOf(el) {
    var r = (el.getAttribute('role') || '').trim().split(/\s+/)[0].toLowerCase();
    if (r && r !== 'presentation' && r !== 'none') return r;
    var t = el.localName;
    if (t === 'a' || t === 'area') return el.hasAttribute('href') ? 'link' : 'generic';
    if (t === 'input') {
      var type = inputType(el);
      if (type === 'checkbox') return 'checkbox';
      if (type === 'radio') return 'radio';
      if (type === 'file') return 'file-input';
      if (type === 'range') return 'slider';
      if (type === 'number') return 'spinbutton';
      if (type === 'search') return 'searchbox';
      if (type === 'button' || type === 'submit' || type === 'reset' || type === 'image') return 'button';
      if (type === 'color') return 'button';
      return 'textbox';
    }
    if (t === 'select') return (el.multiple || el.size > 1) ? 'listbox' : 'combobox';
    if (t === 'section') return (el.hasAttribute('aria-label') || el.hasAttribute('aria-labelledby')) ? 'region' : 'generic';
    if (t === 'svg') return (el.hasAttribute('aria-label') || el.querySelector(':scope > title')) ? 'image' : 'generic';
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return 'textbox';
    return TAG_ROLES[t] || 'generic';
  }

  function nameOf(el, role) {
    var t = el.localName, v;
    if ((v = idsText(el, 'aria-labelledby', 150))) return v;
    if ((v = clip(el.getAttribute('aria-label'), 150))) return v;
    if (t === 'input' || t === 'select' || t === 'textarea') {
      var type = inputType(el);
      if (type === 'submit' || type === 'button' || type === 'reset') return clip(el.value || (type === 'submit' ? 'Submit' : type === 'reset' ? 'Reset' : ''), 150);
      if (type === 'image') return clip(el.getAttribute('alt') || el.value || 'Submit', 150);
      if (el.labels && el.labels.length) {
        v = clip(Array.prototype.map.call(el.labels, function (l) { return textExcluding(l, el, 150); }).join(' '), 150);
        if (v) return v;
      }
      if ((v = clip(el.getAttribute('placeholder'), 150))) return v;
      return clip(el.getAttribute('title'), 150);
    }
    if (t === 'img' || t === 'area') return clip(el.getAttribute('alt') || el.getAttribute('title'), 150);
    if (t === 'svg') {
      var title = el.querySelector(':scope > title');
      return clip(title ? title.textContent : '', 150);
    }
    if (t === 'iframe' || t === 'frame') return clip(el.getAttribute('title') || el.getAttribute('name'), 150);
    if (NAME_FROM_CONTENT[role]) {
      v = textOf(el, 150);
      if (!v) {
        var inner = el.querySelector('img[alt],[aria-label],svg > title');
        if (inner) v = clip(inner.getAttribute('alt') || inner.getAttribute('aria-label') || inner.textContent, 150);
      }
      return v || clip(el.getAttribute('title'), 150);
    }
    return clip(el.getAttribute('title'), 150);
  }

  function isInteractive(el, role, st, parentPointer) {
    if (INTERACTIVE_ROLES[role]) return true;
    var t = el.localName;
    if (t === 'input' || t === 'select' || t === 'textarea' || t === 'button' || t === 'summary') return true;
    if ((t === 'video' || t === 'audio') && el.controls) return true;
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return true;
    if (el.hasAttribute('onclick')) return true;
    var ti = el.getAttribute('tabindex');
    if (ti !== null && +ti >= 0 && t !== 'body' && t !== 'html' && role !== 'generic' && role !== 'region' && role !== 'dialog') return true;
    // Framework click handlers leave no attribute; the outermost cursor:pointer element is the clickable one
    if (st && st.cursor === 'pointer' && !parentPointer && t !== 'html' && t !== 'body' && t !== 'label') return true;
    return false;
  }

  function kids(el) {
    if (el.shadowRoot) return el.shadowRoot.children;
    var t = el.localName;
    if (t === 'slot') {
      var assigned = el.assignedElements ? el.assignedElements({ flatten: true }) : [];
      return assigned.length ? assigned : el.children;
    }
    if (t === 'iframe' || t === 'frame') {
      try {
        var d = el.contentDocument;
        if (d && d.body) return [d.body];
      } catch (e) {}
      return [];
    }
    return el.children;
  }

  /** A label is redundant when the field it names is itself on the page (custom checkboxes hide theirs). */
  function labelsShownControl(label) {
    var c = label.control;
    if (!c) return false;
    var st = styleOf(c);
    return !!(st && st.display !== 'none' && st.visibility !== 'hidden' && (!c.checkVisibility || c.checkVisibility()));
  }

  function frameReadable(el) {
    try { return !!(el.contentDocument && el.contentDocument.body); } catch (e) { return false; }
  }

  function deepActive() {
    var a = document.activeElement;
    for (var i = 0; a && i < 20; i++) {
      if (a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
      else if ((a.localName === 'iframe' || a.localName === 'frame') && frameReadable(a) && a.contentDocument.activeElement) a = a.contentDocument.activeElement;
      else break;
    }
    return a;
  }

  function isEditable(el) {
    if (!el) return false;
    var t = el.localName;
    if (t === 'textarea') return !el.readOnly && !el.disabled;
    if (t === 'input') return !!(TEXT_INPUTS[inputType(el)] || /^(date|time|datetime-local|month|week|color)$/.test(inputType(el))) && !el.readOnly && !el.disabled;
    return !!el.isContentEditable;
  }

  function describe(el) {
    if (!el) return 'nothing';
    var role = roleOf(el);
    var name = nameOf(el, role);
    if (!name && (role === 'generic' || role === 'paragraph' || TEXT_BLOCK_ROLES[role])) name = textOf(el, 60);
    var s = role === 'generic' ? el.localName : role;
    if (name) s += ' ' + q(clip(name, 80));
    if (role === 'textbox' && isSensitive(el)) s += ' (password field)';
    return s;
  }

  /** Deepest element at a client point, through open shadow roots and same-origin iframes. */
  function deepElementAt(cx, cy) {
    var root = document, x = cx, y = cy, el = null;
    for (var i = 0; i < 20; i++) {
      var hit = root.elementFromPoint(x, y);
      if (!hit || hit === el) break;
      el = hit;
      if (hit.shadowRoot) { root = hit.shadowRoot; continue; }
      if ((hit.localName === 'iframe' || hit.localName === 'frame') && frameReadable(hit)) {
        var r = hit.getBoundingClientRect();
        x -= r.left + hit.clientLeft; y -= r.top + hit.clientTop;
        root = hit.contentDocument;
        continue;
      }
      break;
    }
    return el;
  }

  /** The element a person would mean by "this": the nearest interactive ancestor of the hit element. */
  function meaningful(el) {
    for (var e = el, i = 0; e && i < 8; i++) {
      var role = roleOf(e);
      if (isInteractive(e, role, styleOf(e), false) && e.localName !== 'label') return e;
      if (e.localName === 'label' && e.control) return e;
      e = e.parentElement || (e.getRootNode && e.getRootNode().host) || null;
    }
    return el;
  }

  function contains(a, b) {
    for (var e = b, i = 0; e && i < 200; i++) {
      if (e === a) return true;
      e = e.parentNode || null;
      if (e && e.nodeType === 11 && e.host) e = e.host;
    }
    return false;
  }

  function opensFilePicker(el) {
    if (!el) return false;
    if (el.localName === 'input' && inputType(el) === 'file') return true;
    if (el.localName === 'label' && el.control && el.control.localName === 'input' && inputType(el.control) === 'file') return true;
    return false;
  }

  // ---------- tree ----------

  function walk(opts) {
    var v = vv();
    var lines = [], nodes = opts.collect ? [] : null;
    var maxDepth = Math.max(1, Math.min(opts.depth || 25, 60));
    var interactiveOnly = opts.filter === 'interactive';
    var deadline = Date.now() + (opts.budgetMs || 2500);
    var count = 0, truncated = false, active = deepActive();
    var maxNodes = opts.maxNodes || 8000;

    function stateText(el, role) {
      var s = '', t = el.localName;
      if (role === 'heading') { var lvl = el.getAttribute('aria-level') || (/^h[1-6]$/.test(t) ? t[1] : ''); if (lvl) s += ' level=' + lvl; }
      var href = el.getAttribute('href');
      if (href && role === 'link') s += ' href=' + q(clip(href, 150));
      if (t === 'input') {
        var type = inputType(el);
        if (type && type !== 'text' && type !== 'checkbox' && type !== 'radio' && type !== 'submit' && type !== 'button') s += ' type=' + type;
        if (TEXT_INPUTS[type] || /^(date|time|datetime-local|month|week|range|color)$/.test(type)) {
          if (el.value) s += ' value=' + (isSensitive(el) ? '[redacted]' : q(clip(el.value, 120)));
          var ph = el.getAttribute('placeholder');
          if (ph && ph.trim() && clip(ph, 150) !== nameOf(el, role)) s += ' placeholder=' + q(clip(ph, 80));
        }
        if (type === 'file' && el.files && el.files.length) s += ' files=' + el.files.length;
      } else if (t === 'textarea') {
        if (el.value) s += ' value=' + (isSensitive(el) ? '[redacted]' : q(clip(el.value, 160)));
        var tph = el.getAttribute('placeholder');
        if (tph && clip(tph, 150) !== nameOf(el, role)) s += ' placeholder=' + q(clip(tph, 80));
      } else if (t === 'select' && !isSensitive(el)) {
        var sel = el.selectedOptions && el.selectedOptions.length ? Array.prototype.map.call(el.selectedOptions, function (o) { return clip(o.textContent, 60); }).join(', ') : '';
        if (sel) s += ' value=' + q(sel);
      } else if (el.isContentEditable && role === 'textbox') {
        var txt = textOf(el, 120);
        if (txt) s += ' value=' + q(txt);
      }
      var checkable = role === 'checkbox' || role === 'radio' || role === 'switch' || role === 'menuitemcheckbox' || role === 'menuitemradio';
      if (checkable) {
        var checked = (t === 'input') ? (el.indeterminate ? 'mixed' : el.checked) : el.getAttribute('aria-checked');
        s += checked === true || checked === 'true' ? ' (checked)' : checked === 'mixed' ? ' (mixed)' : ' (unchecked)';
      }
      var ex = el.getAttribute('aria-expanded');
      if (ex === 'true') s += ' (expanded)'; else if (ex === 'false') s += ' (collapsed)';
      if (el.getAttribute('aria-selected') === 'true' || (t === 'option' && el.selected)) s += ' (selected)';
      var pr = el.getAttribute('aria-pressed');
      if (pr === 'true') s += ' (pressed)';
      if (el.getAttribute('aria-current') && el.getAttribute('aria-current') !== 'false') s += ' (current)';
      if (el.disabled === true || el.getAttribute('aria-disabled') === 'true') s += ' (disabled)';
      if (el.required === true || el.getAttribute('aria-required') === 'true') s += ' (required)';
      if (el.readOnly === true && (t === 'input' || t === 'textarea')) s += ' (readonly)';
      if (el === active && t !== 'body') s += ' (focused)';
      return s;
    }

    function visit(el, depth, parentPointer, forceInclude, inLabel) {
      if (truncated) return;
      if (count >= maxNodes || Date.now() > deadline) { truncated = true; return; }
      var t = el.localName;
      if (!t || SKIP[t]) return;
      if (t === 'input' && inputType(el) === 'hidden') return;
      var st = styleOf(el);
      if (!st || st.display === 'none') return;
      var shown = st.visibility !== 'hidden' && st.visibility !== 'collapse';
      // Default checks only: content-visibility:auto sections are off-screen, not hidden
      if (shown && el.checkVisibility && !el.checkVisibility()) shown = false;
      if (!shown && !forceInclude) {
        // visibility:hidden can be undone by a child; display:none ancestors already returned above
        if (st.visibility === 'hidden') { var c0 = kids(el); for (var j = 0; j < c0.length; j++) visit(c0[j], depth, false, false, inLabel); }
        return;
      }
      if (interactiveOnly && el.getAttribute('aria-hidden') === 'true') return;

      var role = roleOf(el);
      var pointer = st.cursor === 'pointer';
      var inter = isInteractive(el, role, st, parentPointer);
      var name = '', include = false, textBlock = false, ownsText = false, line;

      if (interactiveOnly) include = inter || forceInclude;
      else if (inLabel && !inter && !forceInclude && !LANDMARKS[role] && role !== 'heading' && role !== 'image') include = false; // already the control's name
      else if (t === 'label' && !el.hasAttribute('role') && !forceInclude && labelsShownControl(el)) include = false; // the field carries its name
      else {
        name = nameOf(el, role);
        if (inter || forceInclude || LANDMARKS[role] || role === 'heading' || role === 'iframe' || role === 'video' || role === 'audio' || role === 'canvas' || role === 'progressbar' || role === 'meter' || role === 'separator') include = true;
        else if (role === 'image') include = !!name;
        else if (TEXT_BLOCK_ROLES[role] || role === 'label') {
          var own = ownText(el, 300);
          // A short block with no controls inside reads best as one line of text
          if ((role !== 'generic' || own) && !el.shadowRoot && !(el.querySelector && el.querySelector(INTERACTIVE_SELECTOR))) {
            var full = textOf(el, 301);
            if (full && full.length <= 300) { name = full; include = true; textBlock = true; }
          }
          if (!include && role !== 'generic') {
            // A paragraph or item with links in it: its whole text as the name, then only the controls inside
            var whole = looseText(el, 40) ? textOf(el, 301) : '';
            if (whole) { name = whole; include = true; ownsText = true; }
          }
          if (!include && own) { name = own; include = true; }
        } else if (role !== 'generic') include = !!name || el.hasAttribute('role');
      }
      if (inter && !name) name = nameOf(el, role) || (role === 'generic' || role === 'textbox' ? textOf(el, 80) : '');

      if (include) {
        count++;
        var ref = refFor(el, role, name);
        var shownRole = role === 'generic' ? (inter ? 'clickable' : 'text') : role;
        line = new Array(depth + 1).join(' ') + shownRole;
        if (name) line += ' ' + q(name);
        line += ' [' + ref + ']';
        line += stateText(el, role);
        var rect = null;
        if (inter || role === 'iframe') {
          rect = clientRect(el);
          if (!onScreen(rect, v)) line += ' (off-screen)';
        }
        if ((t === 'iframe' || t === 'frame') && !frameReadable(el)) line += ' (cross-origin frame: contents not readable, use a screenshot)';
        if (!opts.collect || opts.lines) lines.push(line);
        if (nodes) {
          if (!rect && opts.collect) rect = clientRect(el);
          nodes.push({ el: el, ref: ref, role: shownRole, name: name, inter: inter, disabled: el.disabled === true || el.getAttribute('aria-disabled') === 'true', onScreen: rect ? onScreen(rect, v) : false, index: nodes.length });
        }
        if ((t === 'select') && !isSensitive(el)) {
          var opts2 = el.options, shownOpts = Math.min(opts2.length, 60);
          for (var i = 0; i < shownOpts; i++) {
            var o = opts2[i], txt = clip(o.textContent, 100);
            var ol = new Array(depth + 2).join(' ') + 'option ' + q(txt);
            if (o.value && o.value !== txt) ol += ' value=' + q(clip(o.value, 60));
            if (o.selected) ol += ' (selected)';
            if (o.disabled) ol += ' (disabled)';
            if (!opts.collect) lines.push(ol);
          }
          if (opts2.length > shownOpts && !opts.collect) lines.push(new Array(depth + 2).join(' ') + '… ' + (opts2.length - shownOpts) + ' more options');
          return;
        }
        if (textBlock || t === 'textarea' || t === 'input') return;
        if (depth + 1 > maxDepth) {
          if (kids(el).length && !opts.collect) lines.push(new Array(depth + 2).join(' ') + '… (deeper levels hidden; pass ref_id=' + ref + ' to expand)');
          return;
        }
      }
      var c = kids(el);
      // cursor is inherited, so children of a cursor:pointer element are part of that one clickable
      var labelled = inLabel || ownsText || (include && !!NAME_FROM_CONTENT[role] && !!name);
      for (var k = 0; k < c.length; k++) visit(c[k], include ? depth + 1 : depth, pointer, false, labelled);
    }

    var start = document.body || document.documentElement;
    if (opts.refId) {
      start = elFor(opts.refId);
      if (!start) return { error: missing(opts.refId).error };
    }
    if (start) visit(start, 0, false, !!opts.refId);
    prune();
    return { lines: lines, nodes: nodes, truncated: truncated, count: count };
  }

  function pageHeader() {
    var v = vv(), d = document.documentElement;
    return 'Page: ' + (clip(document.title, 120) || '(no title)') + ' — ' + location.href + '\n' +
      'Visible area: ' + Math.round(v.w) + 'x' + Math.round(v.h) + ' CSS px' + (Math.abs(v.s - 1) > 0.01 ? ' (zoom ' + v.s.toFixed(2) + ')' : '') +
      ', scrolled to ' + Math.round(scrollX + v.ox) + ',' + Math.round(scrollY + v.oy) + ' of ' + d.scrollWidth + 'x' + d.scrollHeight;
  }

  function tree(o) {
    o = o || {};
    var filter = o.filter === 'interactive' ? 'interactive' : 'all';
    var res = walk({ filter: filter, depth: o.depth, refId: o.refId || null });
    if (res.error) return { ok: false, error: res.error };
    var max = Math.max(1000, Math.min(o.maxChars || 50000, 400000));
    var body = res.lines.join('\n');
    if (!body) body = filter === 'interactive' ? '(no interactive elements found' + (o.refId ? ' inside ' + normRef(o.refId) : '') + ')' : '(the page has no readable content yet: it may still be loading, or draw everything on a canvas; take a screenshot)';
    var notes = [];
    if (res.truncated) notes.push('[stopped after ' + res.count + ' elements: the page is very large. Use ref_id to read one part, filter="interactive", or browser_find.]');
    var full = body.length;
    if (body.length > max) {
      var cut = body.lastIndexOf('\n', max);
      body = body.slice(0, cut > 0 ? cut : max);
      notes.push('[output cut at ' + max + ' of ' + full + ' characters. Pass a larger max_chars, use ref_id to read one part, or filter="interactive".]');
    }
    return { ok: true, text: pageHeader() + '\n\n' + body + (notes.length ? '\n\n' + notes.join('\n') : '') };
  }

  // ---------- find ----------

  var STOP = {};
  ('the a an of for to in on at with and or this that these is are be it its please me my your our i you we find locate get show click press tap ' +
    'hit select choose open go enter type fill use called named labeled labelled titled says which where element thing page one can want need from by ' +
    'near about into under above below inside within next there here some any all').split(' ').forEach(function (w) { STOP[w] = 1; });
  var ROLE_WORDS = {};
  [['button btn', 'button file-input'], ['link hyperlink url', 'link'],
    ['field input box textbox textfield textarea text entry', 'textbox searchbox combobox'], ['search searchbox', 'searchbox combobox'],
    ['checkbox tickbox check', 'checkbox'], ['radio', 'radio'], ['dropdown select selector picker combobox combo', 'combobox listbox'],
    ['tab', 'tab'], ['heading headline header title', 'heading'], ['image img picture photo icon logo thumbnail', 'image'], ['toggle switch', 'switch checkbox'],
    ['slider range', 'slider'], ['option', 'option'], ['menu menuitem', 'menuitem'], ['upload file attach', 'file-input']
  ].forEach(function (p) { p[0].split(' ').forEach(function (w) { ROLE_WORDS[w] = p[1].split(' '); }); });

  function words(s) {
    s = String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
    return s.match(/[\p{L}\p{N}]+/gu) || [];
  }
  function stem(w) { return w.length <= 3 ? w : (w.replace(/ies$/, 'y').replace(/(ing|ed|es|s)$/, '') || w); }
  function wordSet(list) {
    var set = {};
    list.forEach(function (s) { words(s).forEach(function (w) { set[stem(w)] = 1; }); });
    return set;
  }
  function near(a, b) {
    if (a.length >= 4 && b.indexOf(a) === 0) return true;
    if (b.length >= 4 && a.indexOf(b) === 0) return true;
    if (a.length < 5 || Math.abs(a.length - b.length) > 1) return false;
    var i = 0, j = 0, edits = 0;
    while (i < a.length && j < b.length) {
      if (a[i] === b[j]) { i++; j++; continue; }
      if (++edits > 1) return false;
      if (a.length > b.length) i++; else if (b.length > a.length) j++; else { i++; j++; }
    }
    return edits + (a.length - i) + (b.length - j) <= 1;
  }

  function containerText(el) {
    for (var e = el.parentElement, i = 0; e && e !== document.body && i < 7; i++, e = e.parentElement) {
      var t = e.localName, r = e.getAttribute('role');
      if (/^(tr|li|article|dialog|fieldset|form|section|aside)$/.test(t) || /^(row|listitem|article|dialog|alertdialog|group|region)$/.test(r || '')) {
        var h = e.querySelector('h1,h2,h3,h4,h5,h6,legend,[role=heading],label,th');
        var s = h && h !== el ? textOf(h, 80) : textOf(e, 120);
        return s;
      }
    }
    return '';
  }

  /**
   * Scores elements against the query's words. Every word may match an element's name, label, text, ids or
   * surroundings; a word that names a kind of control ("button", "box", "field") also counts, more weakly, when the
   * element is of that kind. When some elements match by text, elements that only match by kind are dropped.
   */
  function find(query) {
    var qw = words(query);
    var picked = qw.filter(function (w) { return !STOP[w]; });
    if (!picked.length) picked = qw.slice(0, 12);
    var seen = {}, terms = [];
    picked.forEach(function (w) {
      var st = stem(w);
      if (seen[st]) return;
      seen[st] = 1;
      terms.push({ w: st, roles: ROLE_WORDS[w] || ROLE_WORDS[st] || null });
    });
    terms = terms.slice(0, 16);
    var phrase = terms.map(function (t) { return t.w; }).join(' ');
    var kindOnly = terms.every(function (t) { return !!t.roles; });
    // The query's own words in order, minus kind words: "click here link" -> "click here", which may be a name
    var named = qw.filter(function (w) { return !ROLE_WORDS[w] && !ROLE_WORDS[stem(w)]; });
    var namedPhrase = (named.length >= 2 || (named.length === 1 && !STOP[named[0]])) ? ' ' + named.join(' ') + ' ' : '';
    var kindWeight = kindOnly ? 1 : 0.6;
    if (!terms.length) return { ok: false, code: 'find_no_match', error: 'The query has no words to look for.' };

    var res = walk({ filter: 'all', depth: 60, collect: true, maxNodes: 12000, budgetMs: 3000 });
    var scored = [], anyText = false;
    res.nodes.forEach(function (n) {
      var el = n.el;
      var fields = [
        [wordSet([n.name, el.getAttribute('aria-label'), el.getAttribute('placeholder'), el.getAttribute('title'), el.getAttribute('alt'),
          (el.localName === 'input' && /^(submit|button|reset)$/.test(inputType(el))) ? el.value : '', n.inter ? textOf(el, 150) : '',
          n.role === 'file-input' ? 'file files upload attach' : '', el.localName === 'select' ? Array.prototype.slice.call(el.options, 0, 30).map(function (o) { return o.textContent; }).join(' ') : '']), 1],
        [wordSet([el.id, el.getAttribute('name'), el.getAttribute('data-testid'), el.getAttribute('data-test-id'), el.getAttribute('data-test'), el.getAttribute('data-qa'), el.getAttribute('data-cy'), el.getAttribute('href')]), 0.5],
        [wordSet([n.inter ? containerText(el) : '', idsText(el, 'aria-describedby', 100)]), 0.3]
      ];
      var text = 0, kind = 0;
      terms.forEach(function (t) {
        var best = 0;
        fields.forEach(function (f) {
          if (f[0][t.w]) best = Math.max(best, f[1]);
          else if (best < f[1]) for (var k in f[0]) if (near(t.w, k)) { best = Math.max(best, 0.7 * f[1]); break; }
        });
        if (best < kindWeight && t.roles && t.roles.indexOf(n.role) >= 0) kind += kindWeight - best;
        text += best;
      });
      // A name that contains the query's words verbatim matches fully, even common words like "click here"
      if (namedPhrase && (' ' + words(n.name).join(' ') + ' ').indexOf(namedPhrase) >= 0) text = Math.max(text, terms.length);
      if (!text && !kind) return;
      var score = (text + kind) / terms.length;
      if (phrase && words(n.name).map(stem).join(' ').indexOf(phrase) >= 0) score += 0.3;
      if (n.inter) score += 0.15;
      if (!n.onScreen) score *= 0.9;
      if (n.disabled) score *= 0.8;
      n.score = score;
      n.byText = text > 0;
      if (n.byText) anyText = true;
      n.container = n.inter ? containerText(el) : '';
      scored.push(n);
    });
    if (anyText && !kindOnly) scored = scored.filter(function (n) { return n.byText; });
    scored.sort(function (a, b) { return b.score - a.score || a.index - b.index; });
    // With a clear winner, matches far weaker than it are noise (e.g. every field of a form sharing its label)
    if (scored.length && scored[0].score >= 0.6) { var floor = scored[0].score * 0.55; scored = scored.filter(function (n) { return n.score >= floor; }); }
    var top = scored.slice(0, 20);
    if (!top.length) return { ok: false, code: 'find_no_match', error: 'Nothing on the page matches "' + clip(query, 80) + '". Try words that appear on the page, or use browser_read_page to list its elements. Hidden things (closed menus, collapsed sections) are not searched: open them first.' + (res.truncated ? ' (Only the first part of this very large page was searched.)' : '') };
    var dup = {};
    top.forEach(function (n) { var k = n.role + '|' + n.name; dup[k] = (dup[k] || 0) + 1; });
    var out = top.map(function (n) {
      var line = '- ' + n.ref + ': ' + n.role + (n.name ? ' ' + q(clip(n.name, 100)) : '');
      if (dup[n.role + '|' + n.name] > 1 && n.container && n.container !== n.name) line += ' in ' + q(clip(n.container, 60));
      var flags = [];
      if (!n.onScreen) flags.push('off-screen');
      if (n.disabled) flags.push('disabled');
      return line + (flags.length ? ' (' + flags.join(', ') + ')' : '');
    });
    var more = scored.length > 20 ? '\n\n' + (scored.length - 20) + ' more weaker matches not shown; use a more specific query.' : '';
    return { ok: true, text: 'Best matches for "' + clip(query, 80) + '" (best first):\n' + out.join('\n') + more + (res.truncated ? '\nOnly the first part of this very large page was searched.' : '') };
  }

  // ---------- acting on refs and points ----------

  /** Scrolls the element into view when needed and returns where to press it. */
  function resolve(ref, scroll) {
    var el = elFor(ref);
    if (!el) return missing(ref);
    var v = vv();
    if (scroll !== false) {
      var r0 = clientRect(el);
      if (!fullyOnScreen(r0, v)) {
        try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e) { el.scrollIntoView(); }
        v = vv();
      }
    }
    var p = pressPoint(el);
    if (!p.hasBox) return { ok: false, code: 'not_visible', error: normRef(ref) + ' (' + describe(el) + ') has no size on the page right now (hidden or collapsed), so it cannot be pressed. Read the page again, or open whatever reveals it first.' };
    var x = p.cx - v.ox, y = p.cy - v.oy;
    var hit = deepElementAt(p.cx, p.cy);
    var covered = !!(p.onScreen && hit && !contains(el, hit) && !contains(hit, el) && !(el.localName === 'label' && el.control && contains(el.control, hit)));
    return {
      ok: true, x: x, y: y, cx: p.cx, cy: p.cy, vw: v.w, vh: v.h, onScreen: p.onScreen,
      desc: describe(el), ref: normRef(ref), fileInput: opensFilePicker(el), editable: isEditable(el),
      coveredBy: covered ? describe(meaningful(hit)) : '', note: healNote(ref)
    };
  }

  function hitTest(x, y) {
    var v = vv();
    var el = deepElementAt(x + v.ox, y + v.oy);
    if (!el) return { ok: true, desc: 'nothing (outside the page)', ref: '' };
    var m = meaningful(el);
    return { ok: true, desc: describe(m), ref: refFor(m), fileInput: opensFilePicker(m) || opensFilePicker(el), editable: isEditable(el) || isEditable(m) };
  }

  function contentSig(a) {
    if (!a) return '';
    var v = (a.localName === 'input' || a.localName === 'textarea') ? String(a.value) : a.isContentEditable ? String(a.textContent) : '';
    var h = 5381;
    for (var i = 0; i < v.length; i++) h = ((h << 5) + h + v.charCodeAt(i)) | 0;
    return v.length + ':' + h;
  }

  /** What has keyboard focus: kind is editable, frame (cross-origin iframe: unknown), other or none. */
  function focusInfo() {
    var a = deepActive();
    var none = !a || a.localName === 'body' || a.localName === 'html';
    var kind = none ? 'none' : isEditable(a) ? 'editable' : ((a.localName === 'iframe' || a.localName === 'frame') && !frameReadable(a)) ? 'frame' : 'other';
    return { ok: true, kind: kind, editable: kind === 'editable', desc: none ? 'the page itself' : describe(a), ref: none ? '' : refFor(a), sensitive: !!(a && isSensitive(a)), sig: contentSig(a) };
  }

  function viewport() {
    var v = vv(), d = document.documentElement;
    return { ok: true, w: v.w, h: v.h, ox: v.ox, oy: v.oy, s: v.s, iw: innerWidth, ih: innerHeight, sx: scrollX, sy: scrollY, pw: d.scrollWidth, ph: d.scrollHeight, dpr: devicePixelRatio, url: location.href, title: document.title, ready: document.readyState };
  }

  function scrollIntoViewRef(ref) {
    var el = elFor(ref);
    if (!el) return missing(ref);
    try { el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' }); } catch (e) { el.scrollIntoView(); }
    var r = clientRect(el), v = vv();
    return { ok: true, desc: describe(el), onScreen: onScreen(r, v), note: healNote(ref) };
  }

  /** Fallback scrolling without DevTools: scroll the nearest scrollable box under the point, else the page. */
  function scrollAt(x, y, dx, dy) {
    var v = vv();
    var el = deepElementAt(x + v.ox, y + v.oy);
    for (var e = el, i = 0; e && i < 30; i++) {
      var st = styleOf(e);
      if (st) {
        var canY = dy && /(auto|scroll|overlay)/.test(st.overflowY) && e.scrollHeight > e.clientHeight + 1;
        var canX = dx && /(auto|scroll|overlay)/.test(st.overflowX) && e.scrollWidth > e.clientWidth + 1;
        if (canY || canX) {
          var bt = e.scrollTop, bl = e.scrollLeft;
          e.scrollBy({ left: dx, top: dy, behavior: 'instant' });
          if (e.scrollTop !== bt || e.scrollLeft !== bl) return { ok: true, scrolled: describe(e) };
        }
      }
      e = e.parentElement || (e.getRootNode && e.getRootNode().host) || null;
    }
    var sx = scrollX, sy = scrollY;
    window.scrollBy({ left: dx, top: dy, behavior: 'instant' });
    return { ok: true, scrolled: (scrollX !== sx || scrollY !== sy) ? 'the page' : '' };
  }

  /** Fallback hover/context menu without DevTools (untrusted events). */
  function pointerEvent(x, y, kind) {
    var v = vv(), cx = x + v.ox, cy = y + v.oy;
    var el = deepElementAt(cx, cy);
    if (!el) return { ok: false, error: 'Nothing at that point' };
    var init = { bubbles: true, cancelable: true, composed: true, clientX: cx, clientY: cy, view: window };
    var types = kind === 'context' ? ['pointerdown', 'mousedown', 'contextmenu', 'pointerup', 'mouseup'] : ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'pointermove', 'mousemove'];
    types.forEach(function (t) {
      var Ctor = t.indexOf('pointer') === 0 && window.PointerEvent ? PointerEvent : MouseEvent;
      var opts = Object.assign({}, init, kind === 'context' ? { button: 2, buttons: t.indexOf('down') >= 0 ? 2 : 0 } : {});
      if (Ctor === PointerEvent) opts.pointerType = 'mouse';
      el.dispatchEvent(new Ctor(t, opts));
    });
    return { ok: true, desc: describe(meaningful(el)) };
  }

  /** Fallback typing without DevTools: inserts text at the caret like a keyboard would. */
  function insertText(text) {
    var a = deepActive();
    if (!isEditable(a)) return { ok: false, error: 'No text field is focused. Click the field first.' };
    var doc = a.ownerDocument;
    var done = false;
    try { done = doc.execCommand('insertText', false, text); } catch (e) { done = false; }
    if (!done && (a.localName === 'input' || a.localName === 'textarea')) {
      var start = a.selectionStart == null ? a.value.length : a.selectionStart, end = a.selectionEnd == null ? start : a.selectionEnd;
      setNativeValue(a, a.value.slice(0, start) + text + a.value.slice(end));
      a.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return { ok: true, desc: describe(a) };
  }

  // ---------- forms ----------

  function setNativeValue(el, value) {
    var w = winOf(el);
    var proto = el.localName === 'textarea' ? w.HTMLTextAreaElement.prototype : el.localName === 'select' ? w.HTMLSelectElement.prototype : w.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;
  }

  function fire(el, types) {
    types.forEach(function (t) { el.dispatchEvent(new Event(t, { bubbles: true })); });
  }

  function formInput(ref, value) {
    var res = formInputRaw(ref, value);
    if (res.ok && res.text) res.text += healNote(ref);
    return res;
  }

  function formInputRaw(ref, value) {
    var el = elFor(ref);
    if (!el) return missing(ref);
    var t = el.localName, role = roleOf(el), type = inputType(el);
    var secret = isSensitive(el);
    var show = function (v) { return secret ? '[redacted]' : q(clip(v, 120)); };
    try { el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' }); } catch (e) {}
    if (el.disabled) return { ok: false, code: 'input_invalid', error: describe(el) + ' is disabled.' };

    if (t === 'select') {
      var opts = Array.prototype.slice.call(el.options);
      var wanted = Array.isArray(value) ? value.map(String) : [String(value)];
      var picks = wanted.map(function (w) {
        var lw = w.trim().toLowerCase();
        return opts.filter(function (o) { return o.value === w; })[0] || opts.filter(function (o) { return clip(o.textContent, 1000) === w.trim(); })[0] ||
          opts.filter(function (o) { return clip(o.textContent, 1000).toLowerCase() === lw; })[0] || null;
      });
      var bad = wanted.filter(function (w, i) { return !picks[i]; });
      if (bad.length) {
        return { ok: false, code: 'input_invalid', error: 'No option ' + q(bad[0]) + ' in ' + describe(el) + '. Options: ' + (secret ? '[redacted]' : opts.slice(0, 80).map(function (o) { return q(clip(o.textContent, 60)) + (o.value !== clip(o.textContent, 60) ? ' (value ' + q(o.value) + ')' : ''); }).join(', ')) };
      }
      var before = Array.prototype.map.call(el.selectedOptions, function (o) { return clip(o.textContent, 60); }).join(', ');
      el.focus();
      if (el.multiple) opts.forEach(function (o) { o.selected = picks.indexOf(o) >= 0; });
      else setNativeValue(el, picks[0].value);
      fire(el, ['input', 'change']);
      return { ok: true, text: 'Selected ' + picks.map(function (o) { return show(clip(o.textContent, 80)); }).join(', ') + ' in ' + describe(el) + ' (was ' + (before ? show(before) : 'nothing') + ')' };
    }

    var checkable = (t === 'input' && (type === 'checkbox' || type === 'radio')) || role === 'checkbox' || role === 'radio' || role === 'switch' || role === 'menuitemcheckbox' || role === 'menuitemradio';
    if (checkable) {
      var native = t === 'input';
      var isRadio = type === 'radio' || role === 'radio' || role === 'menuitemradio';
      var lv = String(value).trim().toLowerCase();
      var yes = value === true || value === 1 || /^(true|1|on|yes|checked|check|selected|select)$/.test(lv);
      var no = value === false || value === 0 || /^(false|0|off|no|unchecked|uncheck|unselected|none)$/.test(lv);
      // For a radio button any other value (e.g. its label) means "choose it"
      if (isRadio && !no) yes = true;
      if (!yes && !no) return { ok: false, code: 'input_invalid', error: describe(el) + ' needs true (check) or false (uncheck).' };
      var want = yes;
      if (!want && isRadio) return { ok: false, code: 'input_invalid', error: 'A radio button cannot be unchecked directly; choose another option in its group instead.' };
      var state = function () { return native ? (el.indeterminate ? 'mixed' : el.checked) : (el.getAttribute('aria-checked') === 'true' ? true : el.getAttribute('aria-checked') === 'mixed' ? 'mixed' : false); };
      var was = state();
      if (was === want) return { ok: true, text: describe(el) + ' was already ' + (want ? 'checked' : 'unchecked') + '; nothing changed.' };
      el.focus();
      el.click();
      var now = state();
      if (now !== want) return { ok: false, code: 'input_invalid', error: 'Clicked ' + describe(el) + ' but it is now ' + (now === 'mixed' ? 'mixed' : now ? 'checked' : 'unchecked') + '. The page may handle it differently; take a screenshot, or click its label with browser_computer.' };
      return { ok: true, text: (want ? 'Checked ' : 'Unchecked ') + describe(el) };
    }

    if (t === 'input' && type === 'file') return { ok: false, code: 'element_unsupported', error: 'This is a file input: use browser_upload_file with ref=' + normRef(ref) + '.' };

    if (t === 'input' || t === 'textarea') {
      if (el.readOnly) return { ok: false, code: 'input_invalid', error: describe(el) + ' is read-only.' };
      var v = value == null ? '' : String(value);
      if (type === 'number' && v !== '' && isNaN(Number(v))) return { ok: false, code: 'input_invalid', error: 'A number field needs a number.' };
      if (type === 'range' && isNaN(Number(v))) return { ok: false, code: 'input_invalid', error: 'A slider needs a number.' };
      var old = el.value;
      el.focus();
      setNativeValue(el, v);
      fire(el, ['input', 'change']);
      if (TEXT_INPUTS[type] || t === 'textarea') { try { el.setSelectionRange(el.value.length, el.value.length); } catch (e) {} }
      var note = (v !== '' && el.value === '' && type !== 'password') ? ' — the field did not accept that value (its format may be different, e.g. dates as YYYY-MM-DD)' : '';
      return { ok: true, text: 'Set ' + describe(el) + ' to ' + show(el.value) + ' (was ' + (old ? show(old) : 'empty') + ')' + note };
    }

    if (el.isContentEditable) {
      var prevText = textOf(el, 120);
      el.focus();
      var sel = winOf(el).getSelection(), range = el.ownerDocument.createRange();
      range.selectNodeContents(el);
      sel.removeAllRanges();
      sel.addRange(range);
      var ok = false;
      try { ok = el.ownerDocument.execCommand('insertText', false, value == null ? '' : String(value)); } catch (e) { ok = false; }
      if (!ok) { el.textContent = value == null ? '' : String(value); fire(el, ['input']); }
      return { ok: true, text: 'Set ' + describe(el) + ' text (was ' + (prevText ? q(prevText) : 'empty') + ')' };
    }

    if (role === 'combobox' || role === 'listbox') return { ok: false, code: 'element_unsupported', error: describe(el) + ' is a custom dropdown: click it with browser_computer, then click the option (use browser_find to get its ref).' };
    return { ok: false, code: 'element_unsupported', error: describe(el) + ' is not a form field. Use browser_computer to click or type instead.' };
  }

  // ---------- page text ----------

  var BLOCK_TAGS = /^(p|div|li|ul|ol|dl|dt|dd|h[1-6]|section|article|header|footer|nav|aside|main|table|tr|td|th|caption|blockquote|pre|figure|figcaption|details|summary|form|fieldset|legend|label|br|hr|option)$/;

  /** All text under root, including hidden parts (collapsed sections, tabs), one line per block. */
  function allText(root) {
    var lines = [], line = '', lastBlock = null;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        if (n.nodeType === 1) return SKIP[n.localName] ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_SKIP;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    for (var n = w.nextNode(); n; n = w.nextNode()) {
      var t = n.nodeValue.replace(/\s+/g, ' ');
      if (!t.trim()) continue;
      var b = n.parentElement;
      while (b && b !== root && !BLOCK_TAGS.test(b.localName)) b = b.parentElement;
      if (b !== lastBlock && line.trim()) { lines.push(line.trim()); line = ''; }
      lastBlock = b;
      line += t;
    }
    if (line.trim()) lines.push(line.trim());
    return lines.join('\n');
  }

  function pageText(maxChars, includeHidden) {
    var max = Math.max(500, Math.min(maxChars || 50000, 400000));
    var picks = ['article', 'main', '[role=main]', '[class*=articleBody]', '[class*=article-body]', '[class*=post-content]', '[class*=entry-content]', '[class*=content-body]', '#content', '.content'];
    var root = null;
    for (var i = 0; i < picks.length && !root; i++) {
      var all = document.querySelectorAll(picks[i]), best = null, bestLen = 0;
      for (var j = 0; j < all.length; j++) { var len = (includeHidden ? all[j].textContent : all[j].innerText || '').length; if (len > bestLen) { bestLen = len; best = all[j]; } }
      if (best && bestLen >= 200) root = best;
    }
    var source = root ? root.localName + (root.id ? '#' + root.id : '') : 'body';
    var norm = function (el) { return (el && el.innerText || '').replace(/[^\S\n]+/g, ' ').replace(/ ?\n ?/g, '\n').replace(/\n{3,}/g, '\n\n').trim(); };
    var read = function (el) { return includeHidden ? allText(el) : norm(el); };
    var text = read(root || document.body);
    if (root && text.length < 200) { root = null; text = read(document.body); source = 'body'; }
    var notes = [];
    if (!includeHidden) {
      var everything = allText(root || document.body);
      var hidden = everything.length - text.length;
      if (hidden > 1500 && everything.length > text.length * 1.5) notes.push('[About ' + hidden + ' more characters are on the page but hidden (collapsed sections, tabs or closed menus). Pass include_hidden=true to read them too, or open them first.]');
    }
    if (!text) return { ok: false, code: 'no_text', error: 'The page has no text yet: it may still be loading, or show only images, video or a canvas. Take a screenshot instead.' + (notes.length ? ' ' + notes[0] : '') };
    var full = text.length;
    if (text.length > max) {
      var cut = text.lastIndexOf('\n', max);
      text = text.slice(0, cut > 0 ? cut : max);
      notes.push('[text cut at ' + max + ' of ' + full + ' characters. Pass a larger max_chars to read more.]');
    }
    return { ok: true, text: 'Title: ' + (document.title || '(no title)') + '\nURL: ' + location.href + '\nFrom: ' + source + (includeHidden ? ' (including hidden text)' : '') + '\n\n' + text + (notes.length ? '\n\n' + notes.join('\n') : '') };
  }

  window.__antigemAgent = {
    version: VERSION, _store: store,
    tree: tree, find: find, resolve: resolve, hitTest: hitTest, focusInfo: focusInfo, viewport: viewport,
    scrollIntoViewRef: scrollIntoViewRef, scrollAt: scrollAt, pointerEvent: pointerEvent, insertText: insertText,
    formInput: formInput, pageText: pageText
  };
  return true;
})();
