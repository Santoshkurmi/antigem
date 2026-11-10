package com.example.gemini.ui.browser

/** Input and viewport control shared by all browser extensions. */
object BrowserAutomationScripts {
    fun inputLock(enabled: Boolean) = """
        (function () {
            function freezeZoom(on) {
              var state = window.__antigemInputLock;
              if (!on) {
                if (!state) return { ok: true };
                state.observer.disconnect();
                document.removeEventListener('focusin', state.onFocus, true);
                if (window.visualViewport) window.visualViewport.removeEventListener('resize', state.applyViewport);
                state.fields.forEach(function (original, el) {
                  if (original == null) el.removeAttribute('inputmode');
                  else el.setAttribute('inputmode', original);
                });
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                  if (state.createdViewport) meta.remove();
                  else if (state.originalViewport == null) meta.removeAttribute('content');
                  else meta.setAttribute('content', state.originalViewport);
                }
                window.__antigemInputLock = null;
                return { ok: true };
              }
              if (state) {
                state.applyViewport();
                return { ok: true, scale: state.scale };
              }
              var meta = document.querySelector('meta[name="viewport"]');
              state = { originalViewport: meta && meta.getAttribute('content'), createdViewport: !meta, fields: new Map() };
              window.__antigemInputLock = state;
              function protect(el) {
                if (!el || !el.matches || !el.matches('input, textarea, [contenteditable="true"], [contenteditable="plaintext-only"]')) return;
                if (!state.fields.has(el)) state.fields.set(el, el.getAttribute('inputmode'));
                if (el.getAttribute('inputmode') !== 'none') el.setAttribute('inputmode', 'none');
              }
              function protectFields() {
                document.querySelectorAll('input, textarea, [contenteditable="true"], [contenteditable="plaintext-only"]').forEach(protect);
              }
              state.applyViewport = function () {
                var current = document.querySelector('meta[name="viewport"]');
                if (!current) {
                  current = document.createElement('meta');
                  current.name = 'viewport';
                  (document.head || document.documentElement).appendChild(current);
                }
                var width = 1280, vv = window.visualViewport;
                var physicalWidth = vv ? vv.width * vv.scale : window.innerWidth;
                state.scale = Math.max(0.01, physicalWidth / width).toFixed(6);
                var locked = 'width=' + width + ', initial-scale=' + state.scale + ', minimum-scale=' + state.scale + ', maximum-scale=' + state.scale + ', user-scalable=no';
                if (current.getAttribute('content') !== locked) current.setAttribute('content', locked);
              };
              state.onFocus = function (event) { protect(event.target); };
              state.observer = new MutationObserver(function () { state.applyViewport(); protectFields(); });
              state.observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'inputmode'] });
              document.addEventListener('focusin', state.onFocus, true);
              if (window.visualViewport) window.visualViewport.addEventListener('resize', state.applyViewport);
              protectFields();
              state.applyViewport();
              return { ok: true, scale: state.scale };
            }
          return freezeZoom($enabled);
        })();
    """.trimIndent()

    /**
     * Browser MCP lock: text fields get inputmode=none, so focusing one never asks for the soft keyboard. With [pinZoom]
     * the zoom is also pinned with the page's whole width on screen (used while the agent holds the tab and the user
     * cannot touch it). Unlike [inputLock] it keeps the page's own layout width, so mobile pages stay mobile.
     * Idempotent and switchable; enabled=false restores the page.
     */
    fun agentInputLock(enabled: Boolean, pinZoom: Boolean = false) = """
        (function (on, pinZoom) {
          var FIELDS = 'input, textarea, select, [contenteditable]:not([contenteditable="false"])';
          var S = window.__antigemAgentLock;
          function restoreMeta() {
            var meta = document.querySelector('meta[name="viewport"]');
            if (meta) {
              if (S.created) meta.remove();
              else if (S.original == null) meta.removeAttribute('content');
              else meta.setAttribute('content', S.original);
            }
          }
          if (!on) {
            if (!S) return true;
            S.observer.disconnect();
            document.removeEventListener('focusin', S.onFocus, true);
            if (window.visualViewport) window.visualViewport.removeEventListener('resize', S.pin);
            S.fields.forEach(function (original, el) {
              if (original == null) el.removeAttribute('inputmode'); else el.setAttribute('inputmode', original);
            });
            if (S.pinning) restoreMeta();
            window.__antigemAgentLock = null;
            return true;
          }
          if (!S) {
            S = { fields: new Map(), pinning: false, width: Math.max(1, Math.round(window.innerWidth)) };
            window.__antigemAgentLock = S;
            var protect = function (el) {
              if (!el || el.nodeType !== 1 || !el.matches || !el.matches(FIELDS)) return;
              if (!S.fields.has(el)) S.fields.set(el, el.getAttribute('inputmode'));
              if (el.getAttribute('inputmode') !== 'none') el.setAttribute('inputmode', 'none');
            };
            S.protectAll = function () { document.querySelectorAll(FIELDS).forEach(protect); };
            S.pin = function () {
              if (!S.pinning) return;
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                (document.head || document.documentElement).appendChild(meta);
              }
              var vv = window.visualViewport;
              var screenWidth = vv ? vv.width * vv.scale : window.innerWidth;
              var scale = screenWidth / S.width;
              scale = Math.abs(scale - 1) < 0.01 ? 1 : Math.max(0.05, Math.round(scale * 10000) / 10000);
              var content = 'width=' + S.width + ', initial-scale=' + scale + ', minimum-scale=' + scale + ', maximum-scale=' + scale + ', user-scalable=no';
              if (meta.getAttribute('content') !== content) meta.setAttribute('content', content);
            };
            S.setPin = function (want) {
              if (want && !S.pinning) {
                var meta0 = document.querySelector('meta[name="viewport"]');
                S.original = meta0 && meta0.getAttribute('content');
                S.created = !meta0;
                S.width = Math.max(1, Math.round(window.innerWidth));
                S.pinning = true;
              } else if (!want && S.pinning) {
                S.pinning = false;
                restoreMeta();
              }
              S.pin();
            };
            S.onFocus = function (e) { protect(e.composedPath ? e.composedPath()[0] : e.target); };
            S.observer = new MutationObserver(function (records) {
              var repin = false;
              records.forEach(function (r) {
                if (r.type === 'attributes') {
                  if (r.attributeName === 'content' && r.target.name === 'viewport') repin = true;
                  else if (r.attributeName === 'inputmode' && S.fields.has(r.target)) protect(r.target);
                  return;
                }
                r.addedNodes.forEach(function (n) {
                  if (n.nodeType !== 1) return;
                  protect(n);
                  if (n.querySelectorAll) n.querySelectorAll(FIELDS).forEach(protect);
                  if (n.localName === 'meta') repin = true;
                });
              });
              if (repin) S.pin();
            });
            S.observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'inputmode'] });
            document.addEventListener('focusin', S.onFocus, true);
            if (window.visualViewport) window.visualViewport.addEventListener('resize', S.pin);
          }
          S.protectAll();
          S.setPin(pinZoom);
          return true;
        })($enabled, $pinZoom);
    """.trimIndent()
}
