package com.example.gemini.ui.browser

import android.webkit.WebView

/**
 * Helper to inject and manage Eruda Mobile DevTools within Android WebViews via CDN.
 * Eruda provides live Console, DOM Elements inspector, Network monitor,
 * Resources/Storage editor, and Sources viewer directly inside the WebView.
 */
object ErudaHelper {

    private const val CDN_URL = "https://cdn.jsdelivr.net/npm/eruda"

    /**
     * Injects Eruda into the WebView from CDN and initializes the floating entry icon.
     */
    fun inject(webView: WebView?, showImmediately: Boolean = true) {
        if (webView == null) return

        val scriptJs = """
            (function() {
                try {
                    if (typeof window.eruda !== 'undefined') {
                        if (!window.__eruda_inited) {
                            window.eruda.init();
                            window.__eruda_inited = true;
                        }
                        ${if (showImmediately) "window.eruda.show();" else ""}
                        return;
                    }
                    var s = document.createElement('script');
                    s.src = '$CDN_URL';
                    s.onload = function() {
                        if (typeof window.eruda !== 'undefined') {
                            window.eruda.init();
                            window.__eruda_inited = true;
                            ${if (showImmediately) "window.eruda.show();" else ""}
                        }
                    };
                    (document.head || document.documentElement || document.body).appendChild(s);
                } catch(e) {
                    console.error("Eruda CDN injection error:", e);
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(scriptJs, null)
        }
    }

    /**
     * Toggles the Eruda inspector: if open -> hides to floating icon; if hidden -> opens panel.
     */
    fun toggle(webView: WebView?) {
        if (webView == null) return
        val toggleJs = """
            (function() {
                try {
                    if (typeof window.eruda === 'undefined') {
                        return false;
                    }
                    if (!window.__eruda_inited) {
                        window.eruda.init();
                        window.__eruda_inited = true;
                        window.eruda.show();
                        return true;
                    }
                    var container = document.querySelector('.eruda-container') || document.getElementById('eruda');
                    var isVisible = false;
                    if (container) {
                        var display = window.getComputedStyle(container).display;
                        isVisible = (display !== 'none');
                    }
                    if (isVisible) {
                        window.eruda.hide();
                    } else {
                        window.eruda.show();
                    }
                    return true;
                } catch(e) {
                    console.error("Eruda toggle error:", e);
                    return false;
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(toggleJs) { result ->
                if (result == "false" || result == "null") {
                    // Not loaded yet, inject from CDN
                    inject(webView, showImmediately = true)
                }
            }
        }
    }

    /**
     * Completely destroys Eruda and removes all floating UI from the page.
     */
    fun destroy(webView: WebView?) {
        if (webView == null) return
        val destroyJs = """
            (function() {
                try {
                    if (typeof window.eruda !== 'undefined') {
                        window.eruda.destroy();
                        window.__eruda_inited = false;
                    }
                } catch(e) {
                    console.error("Eruda destroy error:", e);
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(destroyJs, null)
        }
    }
}
