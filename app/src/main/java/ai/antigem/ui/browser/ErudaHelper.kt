package ai.antigem.ui.browser

import android.webkit.WebView

/**
 * Helper to inject and manage Eruda Mobile DevTools within Android WebViews.
 * Eruda provides live Console, DOM Elements inspector, Network monitor,
 * Resources/Storage editor, and Sources viewer directly inside the WebView.
 *
 * Eruda is bundled in the app (assets/eruda, a fixed version), never loaded from a CDN: it runs with the page's full
 * rights (a mini app's bridge too), so a changed npm release or CDN must not reach it.
 */
object ErudaHelper {

    private const val ASSET = "eruda/eruda.js"

    @Volatile
    private var source: String? = null

    private fun source(webView: WebView): String? = source ?: runCatching {
        webView.context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()?.also { source = it }

    /**
     * Injects the bundled Eruda into the WebView and initializes the floating entry icon.
     */
    fun inject(webView: WebView?, showImmediately: Boolean = true) {
        if (webView == null) return
        val eruda = source(webView) ?: return

        // module/exports/define are shadowed so Eruda's UMD wrapper always sets window.eruda, also on pages with AMD
        val scriptJs = """
            (function() {
                try {
                    if (typeof window.eruda === 'undefined') {
                        (function() { var module, exports, define;
                            $eruda
                        }).call(window);
                    }
                    if (typeof window.eruda === 'undefined') return;
                    if (!window.__eruda_inited) {
                        window.eruda.init();
                        window.__eruda_inited = true;
                    }
                    ${if (showImmediately) "window.eruda.show();" else ""}
                } catch(e) {
                    console.error("Eruda injection error:", e);
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
                    // Not loaded yet: inject the bundled copy
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
