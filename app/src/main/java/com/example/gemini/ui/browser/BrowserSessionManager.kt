package com.example.gemini.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.coroutines.resume

/**
 * Diagnostic Console Log entry captured from a live WebView tab.
 */
data class BrowserConsoleMessage(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // "DEBUG", "LOG", "INFO", "WARN", "ERROR"
    val message: String,
    val sourceId: String = "",
    val lineNumber: Int = 0
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("level", level)
        put("message", message)
        put("sourceId", sourceId)
        put("lineNumber", lineNumber)
    }
}

/**
 * Diagnostic Network/Navigation Error entry captured from WebViewClient.
 */
data class BrowserNetworkError(
    val timestamp: Long = System.currentTimeMillis(),
    val url: String,
    val errorCode: Int = 0,
    val description: String,
    val isMainFrame: Boolean = true
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("url", url)
        put("errorCode", errorCode)
        put("description", description)
        put("isMainFrame", isMainFrame)
    }
}

/**
 * Model representing a persistent browser tab.
 */
class BrowserTabSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    initialUrl: String = "",
    initialTitle: String = "New Tab"
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var webView: WebView? = null
    var isBackgroundActive by mutableStateOf(true)
    var lastError by mutableStateOf<BrowserNetworkError?>(null)
    var isStalled by mutableStateOf(false)
    var isDevToolsEnabled by mutableStateOf(false)

    // Circular buffers for real-time telemetry (capped to 300 entries each)
    val consoleLogs = ConcurrentLinkedDeque<BrowserConsoleMessage>()
    val networkErrors = ConcurrentLinkedDeque<BrowserNetworkError>()

    private var stallWatchdogRunnable: Runnable? = null

    fun startLoadingWatchdog(handler: Handler, timeoutMs: Long = 15000L) {
        cancelLoadingWatchdog(handler)
        isStalled = false
        val r = Runnable {
            if (isLoading) {
                isStalled = true
                if (lastError == null) {
                    lastError = BrowserNetworkError(
                        url = url,
                        errorCode = -1001,
                        description = "Page load timed out (took longer than ${timeoutMs / 1000}s). The web server or host might be unreachable or stalled."
                    )
                }
            }
        }
        stallWatchdogRunnable = r
        handler.postDelayed(r, timeoutMs)
    }

    fun cancelLoadingWatchdog(handler: Handler) {
        stallWatchdogRunnable?.let { handler.removeCallbacks(it) }
        stallWatchdogRunnable = null
    }

    fun clearError() {
        lastError = null
        isStalled = false
    }

    fun stopLoading(handler: Handler? = null) {
        try {
            webView?.stopLoading()
        } catch (_: Throwable) {}
        isLoading = false
        if (handler != null) {
            cancelLoadingWatchdog(handler)
        }
    }

    fun addConsoleLog(msg: BrowserConsoleMessage) {
        if (consoleLogs.size >= 300) consoleLogs.pollFirst()
        consoleLogs.addLast(msg)
    }

    fun addNetworkError(err: BrowserNetworkError) {
        if (networkErrors.size >= 200) networkErrors.pollFirst()
        networkErrors.addLast(err)
    }

    fun clearLogs() {
        consoleLogs.clear()
        networkErrors.clear()
    }

    fun toSummaryJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("isLoading", isLoading)
        put("progress", progress)
        put("canGoBack", canGoBack)
        put("canGoForward", canGoForward)
        put("hasActiveWebView", webView != null)
        put("consoleErrorCount", consoleLogs.count { it.level == "ERROR" })
        put("networkErrorCount", networkErrors.size)
    }
}

/**
 * Singleton BrowserSessionManager that maintains persistent browser sessions,
 * offscreen headless rendering for AI inspection, screenshot capture, DOM tree querying,
 * interaction dispatching, and console log aggregation.
 */
class BrowserSessionManager private constructor() {

    companion object {
        private const val TAG = "BrowserSessionManager"
        val instance: BrowserSessionManager by lazy { BrowserSessionManager() }

        private const val MAX_LOGS_PER_QUERY = 150
        private const val DEFAULT_TIMEOUT_MS = 15000L

        /**
         * Formats raw user/agent input into a valid HTTP/HTTPS/File URL or search query.
         */
        fun formatUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return ""
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true) ||
                trimmed.startsWith("about:", ignoreCase = true) ||
                trimmed.startsWith("data:", ignoreCase = true)
            ) {
                return trimmed
            }
            if (trimmed.startsWith("localhost", ignoreCase = true) ||
                trimmed.startsWith("127.0.0.1") ||
                trimmed.startsWith("192.168.") ||
                trimmed.startsWith("10.0.") ||
                trimmed.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$")) ||
                (trimmed.contains(":") && trimmed.substringBefore(":").all { it.isLetterOrDigit() || it == '.' })
            ) {
                return "http://$trimmed"
            }
            val encoded = try { URLEncoder.encode(trimmed, "UTF-8") } catch (_: Exception) { trimmed }
            return "https://www.google.com/search?q=$encoded"
        }

        fun buildErrorHtml(failingUrl: String, errorDescription: String, isDark: Boolean): String {
            val escapedUrl = android.text.TextUtils.htmlEncode(failingUrl)
            val escapedError = android.text.TextUtils.htmlEncode(errorDescription)
            val encodedUrl = try { URLEncoder.encode(failingUrl, "UTF-8") } catch (_: Exception) { failingUrl }
            val bg = if (isDark) "#181513" else "#FAF6F0"
            val cardBg = if (isDark) "#23201D" else "#FFFFFF"
            val textPrimary = if (isDark) "#EDE8DF" else "#181513"
            val textSecondary = if (isDark) "#B8B2A8" else "#525252"
            val accent = if (isDark) "#D97706" else "#C86446"
            val border = if (isDark) "#383430" else "#E5E7EB"
            val codeBg = if (isDark) "#2C2825" else "#F3F4F6"

            return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                      padding: 32px 16px;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                      background-color: $bg;
                      color: $textPrimary;
                      display: flex;
                      flex-direction: column;
                      align-items: center;
                      justify-content: center;
                      min-height: 90vh;
                      text-align: center;
                    }
                    .card {
                      background-color: $cardBg;
                      border: 1px solid $border;
                      border-radius: 18px;
                      padding: 26px 20px;
                      max-width: 400px;
                      width: 100%;
                      display: flex;
                      flex-direction: column;
                      align-items: center;
                      box-shadow: 0 6px 20px rgba(0,0,0,0.12);
                    }
                    .icon-circle {
                      width: 58px;
                      height: 58px;
                      border-radius: 50%;
                      background-color: rgba(200, 100, 70, 0.16);
                      display: flex;
                      align-items: center;
                      justify-content: center;
                      margin-bottom: 16px;
                    }
                    .icon-circle svg {
                      width: 30px;
                      height: 30px;
                      fill: $accent;
                    }
                    h2 {
                      font-size: 19px;
                      font-weight: 700;
                      margin-bottom: 8px;
                      color: $textPrimary;
                    }
                    p {
                      font-size: 14px;
                      line-height: 1.55;
                      color: $textSecondary;
                      margin-bottom: 16px;
                    }
                    .url-box {
                      background-color: $codeBg;
                      border: 1px solid $border;
                      padding: 10px 14px;
                      border-radius: 10px;
                      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
                      font-size: 12px;
                      color: $textPrimary;
                      word-break: break-all;
                      margin-bottom: 20px;
                      width: 100%;
                      text-align: left;
                    }
                    .url-box small {
                      display: block;
                      margin-top: 6px;
                      color: $textSecondary;
                      font-size: 11.5px;
                    }
                    .btn-retry {
                      background-color: $accent;
                      color: #ffffff;
                      border: none;
                      padding: 12px 28px;
                      font-size: 14px;
                      font-weight: 600;
                      border-radius: 10px;
                      cursor: pointer;
                      outline: none;
                      box-shadow: 0 2px 8px rgba(200, 100, 70, 0.3);
                    }
                    .btn-retry:active {
                      opacity: 0.85;
                    }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="icon-circle">
                      <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>
                    </div>
                    <h2>Webpage not available</h2>
                    <p>Could not connect to the server or network host.</p>
                    <div class="url-box">
                      <strong>URL:</strong> $escapedUrl
                      <small><strong>Error:</strong> $escapedError</small>
                    </div>
                    <button class="btn-retry" onclick="window.location.href = decodeURIComponent('$encodedUrl');">Retry Connection</button>
                  </div>
                </body>
                </html>
            """.trimIndent()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    val tabs = mutableStateListOf<BrowserTabSession>().apply {
        add(BrowserTabSession(initialUrl = "", initialTitle = "New Tab"))
    }

    var activeTabId by mutableStateOf<String?>(tabs.firstOrNull()?.id)

    /**
     * Headless offscreen container to keep WebViews measured and layout-computed
     * even when the browser UI screen is hidden (e.g., user is chatting).
     */
    private var offscreenContainer: ViewGroup? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getActiveTab(): BrowserTabSession? {
        return tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    }

    fun getTab(tabId: String?): BrowserTabSession? {
        if (tabId.isNullOrBlank()) return getActiveTab()
        return tabs.find { it.id == tabId } ?: getActiveTab()
    }

    fun listTabsJson(): JSONArray {
        val array = JSONArray()
        tabs.forEach { tab ->
            val json = tab.toSummaryJson()
            json.put("isActive", tab.id == activeTabId)
            array.put(json)
        }
        return array
    }

    fun getMainHandler(): Handler = mainHandler

    fun addNewTab(url: String = "", title: String = "New Tab", activate: Boolean = true): BrowserTabSession {
        val formatted = if (url.isNotBlank()) formatUrl(url) else ""
        val newTab = BrowserTabSession(
            initialUrl = formatted,
            initialTitle = if (formatted.isNotBlank()) formatted else title
        )
        tabs.add(newTab)
        if (activate || activeTabId == null) {
            activeTabId = newTab.id
        }
        if (formatted.isNotBlank()) {
            loadUrlInTab(newTab, formatted)
        }
        return newTab
    }

    fun switchTab(tabId: String): Boolean {
        val tab = tabs.find { it.id == tabId } ?: return false
        activeTabId = tab.id
        return true
    }

    fun closeTab(tabId: String): Boolean {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return false
        val tabToRemove = tabs[index]
        mainHandler.post {
            try {
                tabToRemove.webView?.onPause()
                tabToRemove.webView?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
                tabToRemove.webView = null
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying WebView for tab $tabId", e)
            }
        }
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            val fresh = BrowserTabSession(initialUrl = "", initialTitle = "New Tab")
            tabs.add(fresh)
            activeTabId = fresh.id
        } else if (activeTabId == tabId) {
            val nextIndex = (index - 1).coerceAtLeast(0)
            activeTabId = tabs[nextIndex].id
        }
        return true
    }

    /**
     * Opens a URL in an existing tab or creates a new tab.
     */
    fun openUrl(url: String, tabId: String? = null, newTab: Boolean = false): BrowserTabSession {
        val formatted = formatUrl(url)
        val targetTab = if (newTab || tabs.isEmpty()) {
            addNewTab(formatted, formatted, activate = true)
        } else {
            val t = getTab(tabId) ?: addNewTab(formatted, formatted, activate = true)
            t.url = formatted
            t.title = formatted
            loadUrlInTab(t, formatted)
            t
        }
        return targetTab
    }

    fun reloadTab(tabId: String? = null) {
        val tab = getTab(tabId) ?: return
        mainHandler.post {
            try {
                tab.clearError()
                tab.isLoading = true
                tab.startLoadingWatchdog(mainHandler)
                tab.webView?.reload() ?: run {
                    if (tab.url.isNotBlank()) loadUrlInTab(tab, tab.url)
                }
            } catch (e: Throwable) {
                if (tab.url.isNotBlank()) loadUrlInTab(tab, tab.url)
            }
        }
    }

    fun goBackTab(tabId: String? = null): Boolean {
        val tab = getTab(tabId) ?: return false
        val wv = tab.webView ?: return false
        if (wv.canGoBack()) {
            mainHandler.post { wv.goBack() }
            return true
        }
        return false
    }

    fun goForwardTab(tabId: String? = null): Boolean {
        val tab = getTab(tabId) ?: return false
        val wv = tab.webView ?: return false
        if (wv.canGoForward()) {
            mainHandler.post { wv.goForward() }
            return true
        }
        return false
    }

    fun loadUrlInTab(tab: BrowserTabSession, url: String, context: Context? = null) {
        val ctx = context ?: appContext
        tab.clearError()
        tab.url = url
        tab.isLoading = true
        tab.startLoadingWatchdog(mainHandler)

        mainHandler.post {
            try {
                val effectiveCtx = ctx ?: tab.webView?.context ?: return@post
                var wv = tab.webView
                if (wv == null) {
                    wv = ensureWebViewAttached(effectiveCtx, tab, isDark = true)
                }

                try {
                    wv.loadUrl(url)
                } catch (e: Throwable) {
                    Log.w(TAG, "WebView loadUrl failed on existing instance, recreating WebView...", e)
                    wv = createWebView(effectiveCtx, tab, isDark = true)
                    wv.loadUrl(url)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Fatal error loading URL: $url", e)
                tab.cancelLoadingWatchdog(mainHandler)
                tab.isLoading = false
                tab.lastError = BrowserNetworkError(
                    url = url,
                    errorCode = -999,
                    description = "Failed to load page: ${e.message ?: "Unknown WebView Error"}"
                )
            }
        }
    }

    /**
     * Ensures the WebView instance is created, configured, and offscreen-measured if headless.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebViewAttached(context: Context, tab: BrowserTabSession, isDark: Boolean): WebView {
        tab.webView?.let { return it }
        return createWebView(context, tab, isDark)
    }

    class BrowserMediaJsInterface(
        private val tab: BrowserTabSession,
        private val getWebView: () -> WebView?
    ) {
        @JavascriptInterface
        fun onMediaState(state: Int, title: String, url: String, currentTimeMs: Long, durationMs: Long) {
            val wv = getWebView() ?: return
            val context = wv.context ?: return
            com.example.gemini.data.media.YouTubeMediaSessionManager.onBrowserMediaState(
                context = context,
                webView = wv,
                state = state,
                title = title,
                url = url,
                currentTimeMs = currentTimeMs,
                durationMs = durationMs
            )
        }

        @JavascriptInterface
        fun onTimeUpdate(currentTimeMs: Long, durationMs: Long) {
            com.example.gemini.data.media.YouTubeMediaSessionManager.onBrowserTimeUpdate(currentTimeMs, durationMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context, tab: BrowserTabSession, isDark: Boolean): WebView {
        try {
            tab.webView?.let { old ->
                (old.parent as? ViewGroup)?.removeView(old)
                old.destroy()
            }
        } catch (_: Exception) {}
        tab.webView = null

        val wv = com.example.gemini.data.media.YouTubeMediaSessionManager.KeepAliveWebView(context).apply {
            setBackgroundColor(if (isDark) 0xFF181513.toInt() else Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(1080, 1920)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = isDark
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

            fun injectMediaTracker(view: WebView?) {
                val js = """
                (function() {
                  try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; } });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
                    Object.defineProperty(document, 'webkitHidden', { get: function() { return false; } });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; } });
                  } catch(e) {}
                  ['visibilitychange', 'webkitvisibilitychange', 'blur', 'focusout', 'pagehide'].forEach(function(evt) {
                    window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
                    document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
                  });

                  if (window.__antiGemMediaInjected) return;
                  window.__antiGemMediaInjected = true;

                  function reportMedia(v, isPlay) {
                    if (!window.AndroidBrowserMedia) return;
                    var title = document.title || 'Browser Video';
                    var dur = (v && v.duration && !isNaN(v.duration)) ? v.duration * 1000 : 0;
                    var cur = (v && v.currentTime && !isNaN(v.currentTime)) ? v.currentTime * 1000 : 0;
                    window.AndroidBrowserMedia.onMediaState(isPlay ? 1 : 2, title, window.location.href, cur, dur);
                  }

                  document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                      reportMedia(e.target, true);
                    }
                  }, true);

                  document.addEventListener('pause', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                      var anyPlaying = false;
                      var vids = document.querySelectorAll('video');
                      for (var i = 0; i < vids.length; i++) {
                        if (!vids[i].paused && !vids[i].ended && vids[i].currentTime > 0) {
                          anyPlaying = true; break;
                        }
                      }
                      if (!anyPlaying) {
                        reportMedia(e.target, false);
                      }
                    }
                  }, true);

                  document.addEventListener('timeupdate', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO' && !e.target.paused) {
                      if (window.AndroidBrowserMedia) {
                        var dur = (e.target.duration && !isNaN(e.target.duration)) ? e.target.duration * 1000 : 0;
                        window.AndroidBrowserMedia.onTimeUpdate(e.target.currentTime * 1000, dur);
                      }
                    }
                  }, true);
                })();
                """.trimIndent()
                try {
                    view?.evaluateJavascript(js, null)
                } catch (_: Exception) {}
            }

            addJavascriptInterface(
                BrowserMediaJsInterface(tab) { tab.webView },
                "AndroidBrowserMedia"
            )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    tab.clearError()
                    tab.isLoading = true
                    tab.startLoadingWatchdog(mainHandler)
                    if (!url.isNullOrBlank() && url != "about:blank" && !url.startsWith("data:")) {
                        tab.url = url
                    }
                    tab.canGoBack = view?.canGoBack() == true
                    tab.canGoForward = view?.canGoForward() == true
                    injectMediaTracker(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    tab.isLoading = false
                    tab.cancelLoadingWatchdog(mainHandler)
                    if (!url.isNullOrBlank() && url != "about:blank" && !url.startsWith("data:")) {
                        tab.url = url
                    }
                    view?.title?.let { if (it.isNotBlank()) tab.title = it }
                    tab.canGoBack = view?.canGoBack() == true
                    tab.canGoForward = view?.canGoForward() == true
                    if (tab.isDevToolsEnabled && view != null) {
                        ErudaHelper.inject(view, showImmediately = false)
                    }
                    injectMediaTracker(view)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    injectMediaTracker(view)
                    if (url == null || url.startsWith("data:") || url.contains("chromewebdata") || tab.lastError != null) {
                        val color = if (isDark) "#EDE8DF" else "#181513"
                        val bg = if (isDark) "#181513" else "#FAF6F0"
                        try {
                            view?.evaluateJavascript(
                                "(function(){ try { document.documentElement.style.backgroundColor='$bg'; if(document.body){ document.body.style.backgroundColor='$bg'; document.body.style.color='$color'; } var els=document.querySelectorAll('body, body *'); for(var i=0;i<els.length;i++){ els[i].style.color='$color'; } } catch(e){} })();",
                                null
                            )
                        } catch (_: Exception) {}
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val descriptionStr = error?.description?.toString() ?: ""
                        if (descriptionStr.isNotBlank()) descriptionStr else "Network Error (${error?.errorCode})"
                    } else "Network Connection Failure"
                    val reqUrl = request?.url?.toString() ?: tab.url
                    val isMain = request?.isForMainFrame ?: true

                    val err = BrowserNetworkError(
                        url = reqUrl,
                        errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: 0 else 0,
                        description = desc,
                        isMainFrame = isMain
                    )
                    tab.addNetworkError(err)
                    if (isMain) {
                        tab.cancelLoadingWatchdog(mainHandler)
                        tab.lastError = err
                        tab.isLoading = false
                        try {
                            val color = if (isDark) "#EDE8DF" else "#181513"
                            val bg = if (isDark) "#181513" else "#FAF6F0"
                            view?.evaluateJavascript(
                                "(function(){ try { document.documentElement.style.backgroundColor='$bg'; if(document.body){ document.body.style.backgroundColor='$bg'; document.body.style.color='$color'; } var els=document.querySelectorAll('body, body *'); for(var i=0;i<els.length;i++){ els[i].style.color='$color'; } } catch(e){} })();",
                                null
                            )
                            view?.loadDataWithBaseURL(null, buildErrorHtml(reqUrl, desc, isDark), "text/html", "UTF-8", null)
                        } catch (_: Exception) {}
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    val reqUrl = request?.url?.toString() ?: tab.url
                    val status = errorResponse?.statusCode ?: 0
                    val reason = errorResponse?.reasonPhrase ?: "HTTP Error"
                    val isMain = request?.isForMainFrame ?: false

                    val err = BrowserNetworkError(
                        url = reqUrl,
                        errorCode = status,
                        description = "HTTP $status: $reason",
                        isMainFrame = isMain
                    )
                    tab.addNetworkError(err)
                    if (isMain && status >= 400) {
                        tab.cancelLoadingWatchdog(mainHandler)
                        tab.lastError = err
                        tab.isLoading = false
                        try {
                            view?.loadDataWithBaseURL(null, buildErrorHtml(reqUrl, "HTTP $status: $reason", isDark), "text/html", "UTF-8", null)
                        } catch (_: Exception) {}
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    val reqUrl = error?.url ?: tab.url
                    val isLocal = reqUrl.contains("localhost") || reqUrl.contains("127.0.0.1") || reqUrl.contains("192.168.") || reqUrl.contains("10.0.")
                    
                    // For local development on localhost/127.0.0.1, automatically bypass self-signed SSL errors so dev servers load seamlessly
                    if (isLocal) {
                        handler?.proceed()
                        return
                    }

                    val primaryMsg = when (error?.primaryError) {
                        android.net.http.SslError.SSL_UNTRUSTED -> "Untrusted Certificate Authority (Self-signed or invalid CA)"
                        android.net.http.SslError.SSL_EXPIRED -> "SSL Certificate Expired"
                        android.net.http.SslError.SSL_IDMISMATCH -> "Hostname Mismatch"
                        android.net.http.SslError.SSL_NOTYETVALID -> "Certificate Not Yet Valid"
                        else -> "SSL Handshake / Security Failure"
                    }
                    val err = BrowserNetworkError(
                        url = reqUrl,
                        errorCode = error?.primaryError ?: 0,
                        description = "SSL Certificate Warning: $primaryMsg",
                        isMainFrame = true
                    )
                    tab.addNetworkError(err)
                    tab.cancelLoadingWatchdog(mainHandler)
                    tab.lastError = err
                    tab.isLoading = false
                    handler?.cancel()
                    try {
                        view?.loadDataWithBaseURL(null, buildErrorHtml(reqUrl, "SSL Warning: $primaryMsg", isDark), "text/html", "UTF-8", null)
                    } catch (_: Exception) {}
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    val didCrash = detail?.didCrash() == true
                    val msg = if (didCrash) "WebView render process crashed unexpectedly." else "WebView render process was terminated by OS (Memory Pressure)."
                    val err = BrowserNetworkError(
                        url = tab.url,
                        errorCode = -1,
                        description = msg,
                        isMainFrame = true
                    )
                    tab.addNetworkError(err)
                    tab.cancelLoadingWatchdog(mainHandler)
                    tab.lastError = err
                    tab.isLoading = false
                    try {
                        view?.loadDataWithBaseURL(null, buildErrorHtml(tab.url, msg, isDark), "text/html", "UTF-8", null)
                    } catch (_: Exception) {}
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "about" || scheme == "data") {
                        return false
                    }
                    return try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    tab.progress = newProgress
                    tab.isLoading = newProgress < 100
                    tab.canGoBack = view?.canGoBack() == true
                    tab.canGoForward = view?.canGoForward() == true
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) tab.title = title
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage != null) {
                        val level = when (consoleMessage.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                            ConsoleMessage.MessageLevel.WARNING -> "WARN"
                            ConsoleMessage.MessageLevel.LOG -> "LOG"
                            ConsoleMessage.MessageLevel.TIP -> "INFO"
                            ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                            else -> "LOG"
                        }
                        tab.addConsoleLog(
                            BrowserConsoleMessage(
                                level = level,
                                message = consoleMessage.message() ?: "",
                                sourceId = consoleMessage.sourceId() ?: "",
                                lineNumber = consoleMessage.lineNumber()
                            )
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }

        tab.webView = wv
        // Measure and layout offscreen so dimensions are valid even without UI attachment
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, 1080, 1920)

        if (tab.url.isNotBlank() && tab.url != "about:blank") {
            wv.loadUrl(tab.url)
        }
        return wv
    }

    /**
     * Waits until the page finishes loading or timeout occurs.
     */
    suspend fun waitForPageLoad(tabId: String? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val tab = getTab(tabId) ?: return false
        if (!tab.isLoading && tab.progress >= 100) return true

        return withTimeoutOrNull(timeoutMs) {
            while (tab.isLoading || tab.progress < 100) {
                kotlinx.coroutines.delay(100)
            }
            true
        } ?: false
    }

    /**
     * Executes arbitrary JavaScript on the target tab's WebView and returns the raw string result.
     */
    suspend fun evaluateJs(tabId: String?, script: String): Result<String> = withContext(Dispatchers.Main) {
        val tab = getTab(tabId) ?: return@withContext Result.failure(Exception("Tab not found"))
        val wv = tab.webView ?: return@withContext Result.failure(Exception("WebView not initialized for tab"))

        try {
            suspendCancellableCoroutine { continuation ->
                wv.evaluateJavascript(script) { result ->
                    val cleanResult = if (result != null && result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
                        // Unescape JSON string if returned as JSON string
                        try {
                            org.json.JSONTokener(result).nextValue().toString()
                        } catch (_: Exception) {
                            result
                        }
                    } else {
                        result ?: "null"
                    }
                    if (continuation.isActive) {
                        continuation.resume(Result.success(cleanResult))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Captures a screenshot of the viewport or entire scrollable page.
     */
    suspend fun captureScreenshot(
        tabId: String? = null,
        fullPage: Boolean = false,
        quality: Int = 80
    ): Result<Bitmap> = withContext(Dispatchers.Main) {
        val tab = getTab(tabId) ?: return@withContext Result.failure(Exception("Tab not found"))
        val wv = tab.webView ?: return@withContext Result.failure(Exception("WebView not initialized"))

        try {
            val width = if (wv.width > 0) wv.width else 1080
            val height = if (wv.height > 0) wv.height else 1920

            if (wv.width == 0 || wv.height == 0) {
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, width, height)
            }

            val bitmap = if (fullPage) {
                val scale = wv.scale.coerceAtLeast(1f)
                val contentHeight = (wv.contentHeight * scale).toInt().coerceIn(height, 8000)
                val bmp = Bitmap.createBitmap(width, contentHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                wv.draw(canvas)
                bmp
            } else {
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                wv.draw(canvas)
                bmp
            }

            Result.success(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed", e)
            Result.failure(e)
        }
    }

    /**
     * Captures a screenshot and returns Base64 data URI (image/jpeg).
     */
    suspend fun captureScreenshotBase64(
        tabId: String? = null,
        fullPage: Boolean = false,
        quality: Int = 80
    ): Result<String> = withContext(Dispatchers.IO) {
        val bmpResult = captureScreenshot(tabId, fullPage, quality)
        if (bmpResult.isFailure) return@withContext Result.failure(bmpResult.exceptionOrNull()!!)

        val bmp = bmpResult.getOrNull()!!
        try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)
            val bytes = baos.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Result.success("data:image/jpeg;base64,$base64")
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Captures a screenshot and saves it to a file.
     */
    suspend fun captureScreenshotToFile(
        tabId: String? = null,
        outputFile: File,
        fullPage: Boolean = false,
        quality: Int = 80
    ): Result<File> = withContext(Dispatchers.IO) {
        val bmpResult = captureScreenshot(tabId, fullPage, quality)
        if (bmpResult.isFailure) return@withContext Result.failure(bmpResult.exceptionOrNull()!!)

        val bmp = bmpResult.getOrNull()!!
        try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), fos)
                fos.flush()
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Injected JavaScript that parses interactive elements, layout tree, and text content
     * formatted specifically for LLM consumption.
     */
    private val domInspectorScript = """
        (function() {
            try {
                const doc = document;
                const win = window;
                const vw = win.innerWidth || doc.documentElement.clientWidth;
                const vh = win.innerHeight || doc.documentElement.clientHeight;
                const scrollX = win.scrollX || win.pageXOffset || 0;
                const scrollY = win.scrollY || win.pageYOffset || 0;
                const pageW = Math.max(doc.body.scrollWidth, doc.documentElement.scrollWidth);
                const pageH = Math.max(doc.body.scrollHeight, doc.documentElement.scrollHeight);

                function isVisible(el) {
                    if (!el) return false;
                    const style = win.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) === 0) return false;
                    const rect = el.getBoundingClientRect();
                    return rect.width > 0 && rect.height > 0;
                }

                function getSelector(el) {
                    if (el.id) return '#' + el.id;
                    if (el.name) return el.tagName.toLowerCase() + '[name="' + el.name + '"]';
                    let path = [];
                    while (el && el.nodeType === Node.ELEMENT_NODE) {
                        let selector = el.tagName.toLowerCase();
                        if (el.id) {
                            path.unshift('#' + el.id);
                            break;
                        } else {
                            let sibling = el;
                            let nth = 1;
                            while (sibling = sibling.previousElementSibling) {
                                if (sibling.tagName === el.tagName) nth++;
                            }
                            if (nth > 1) selector += ':nth-of-type(' + nth + ')';
                        }
                        path.unshift(selector);
                        el = el.parentElement;
                        if (path.length > 4) break;
                    }
                    return path.join(' > ');
                }

                const interactiveSelectors = [
                    'button', 'a[href]', 'input', 'textarea', 'select',
                    '[role="button"]', '[role="link"]', '[role="tab"]', '[role="checkbox"]',
                    '[onclick]', '[contenteditable="true"]', 'details', 'summary'
                ];

                const allElements = doc.querySelectorAll(interactiveSelectors.join(','));
                const interactiveItems = [];

                allElements.forEach((el, index) => {
                    if (!isVisible(el)) return;
                    const rect = el.getBoundingClientRect();
                    const text = (el.innerText || el.textContent || el.value || el.placeholder || el.getAttribute('aria-label') || '').trim().substring(0, 100);
                    
                    interactiveItems.push({
                        idx: index,
                        tag: el.tagName.toLowerCase(),
                        id: el.id || undefined,
                        name: el.name || undefined,
                        type: el.type || undefined,
                        role: el.getAttribute('role') || undefined,
                        text: text || undefined,
                        href: el.getAttribute('href') || undefined,
                        placeholder: el.placeholder || undefined,
                        value: (el.tagName.toLowerCase() === 'input' || el.tagName.toLowerCase() === 'textarea') ? el.value : undefined,
                        checked: el.checked || undefined,
                        disabled: el.disabled || undefined,
                        selector: getSelector(el),
                        rect: {
                            top: Math.round(rect.top),
                            left: Math.round(rect.left),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height),
                            inViewport: (rect.top >= 0 && rect.top <= vh && rect.left >= 0 && rect.left <= vw)
                        }
                    });
                });

                // Headings for structure
                const headings = [];
                doc.querySelectorAll('h1, h2, h3, h4').forEach(h => {
                    if (isVisible(h)) {
                        headings.push({
                            level: h.tagName.toLowerCase(),
                            text: (h.innerText || h.textContent || '').trim().substring(0, 80)
                        });
                    }
                });

                return JSON.stringify({
                    url: win.location.href,
                    title: doc.title,
                    viewport: { width: vw, height: vh, scrollX: Math.round(scrollX), scrollY: Math.round(scrollY) },
                    pageSize: { width: pageW, height: pageH },
                    headings: headings.slice(0, 15),
                    interactiveElements: interactiveItems.slice(0, 80)
                });
            } catch (err) {
                return JSON.stringify({ error: err.toString() });
            }
        })();
    """.trimIndent()

    /**
     * Inspects DOM layout and returns structured interactive elements.
     */
    suspend fun inspectDom(tabId: String? = null): Result<JSONObject> {
        val res = evaluateJs(tabId, domInspectorScript)
        if (res.isFailure) return Result.failure(res.exceptionOrNull()!!)

        return try {
            val jsonStr = res.getOrNull() ?: "{}"
            Result.success(JSONObject(jsonStr))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clicks an element by CSS selector or by (X, Y) coordinates.
     */
    suspend fun clickElement(
        tabId: String? = null,
        selector: String? = null,
        x: Float? = null,
        y: Float? = null
    ): Result<Boolean> {
        val script = if (!selector.isNullOrBlank()) {
            val escaped = JSONObject.quote(selector)
            """
            (function() {
                try {
                    const el = document.querySelector($escaped);
                    if (!el) return { success: false, error: "Element not found for selector: " + $escaped };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus && el.focus();
                    ['mousedown', 'mouseup', 'click'].forEach(evtType => {
                        el.dispatchEvent(new MouseEvent(evtType, { bubbles: true, cancelable: true, view: window }));
                    });
                    if (el.tagName.toLowerCase() === 'a' && el.href) {
                        el.click();
                    }
                    return { success: true };
                } catch(e) {
                    return { success: false, error: e.toString() };
                }
            })();
            """.trimIndent()
        } else if (x != null && y != null) {
            """
            (function() {
                try {
                    const el = document.elementFromPoint($x, $y);
                    if (!el) return { success: false, error: "No element at point ($x, $y)" };
                    el.focus && el.focus();
                    ['mousedown', 'mouseup', 'click'].forEach(evtType => {
                        el.dispatchEvent(new MouseEvent(evtType, { bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y }));
                    });
                    return { success: true, clickedTag: el.tagName };
                } catch(e) {
                    return { success: false, error: e.toString() };
                }
            })();
            """.trimIndent()
        } else {
            return Result.failure(Exception("Either selector or (x, y) coordinates must be provided"))
        }

        val res = evaluateJs(tabId, script)
        if (res.isFailure) return Result.failure(res.exceptionOrNull()!!)

        return try {
            val json = JSONObject(res.getOrNull() ?: "{}")
            if (json.optBoolean("success", false)) {
                Result.success(true)
            } else {
                Result.failure(Exception(json.optString("error", "Click failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Types text into an input or textarea element by selector.
     */
    suspend fun typeText(
        tabId: String? = null,
        selector: String,
        text: String,
        clearFirst: Boolean = false
    ): Result<Boolean> {
        val escapedSel = JSONObject.quote(selector)
        val escapedVal = JSONObject.quote(text)

        val script = """
            (function() {
                try {
                    const el = document.querySelector($escapedSel);
                    if (!el) return { success: false, error: "Input element not found: " + $escapedSel };
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    el.focus();
                    if ($clearFirst) {
                        el.value = '';
                    }
                    el.value = ($clearFirst ? '' : el.value) + $escapedVal;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return { success: true, currentValue: el.value };
                } catch(e) {
                    return { success: false, error: e.toString() };
                }
            })();
        """.trimIndent()

        val res = evaluateJs(tabId, script)
        if (res.isFailure) return Result.failure(res.exceptionOrNull()!!)

        return try {
            val json = JSONObject(res.getOrNull() ?: "{}")
            if (json.optBoolean("success", false)) {
                Result.success(true)
            } else {
                Result.failure(Exception(json.optString("error", "Typing failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Scrolls the window or scrolls an element into view.
     */
    suspend fun scrollPage(
        tabId: String? = null,
        dx: Int = 0,
        dy: Int = 0,
        selector: String? = null
    ): Result<Boolean> {
        val script = if (!selector.isNullOrBlank()) {
            val escaped = JSONObject.quote(selector)
            """
            (function() {
                try {
                    const el = document.querySelector($escaped);
                    if (!el) return { success: false, error: "Element not found: " + $escaped };
                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    return { success: true };
                } catch(e) {
                    return { success: false, error: e.toString() };
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                try {
                    window.scrollBy({ left: $dx, top: $dy, behavior: 'instant' });
                    return { success: true, scrollX: window.scrollX, scrollY: window.scrollY };
                } catch(e) {
                    return { success: false, error: e.toString() };
                }
            })();
            """.trimIndent()
        }

        val res = evaluateJs(tabId, script)
        if (res.isFailure) return Result.failure(res.exceptionOrNull()!!)

        return try {
            val json = JSONObject(res.getOrNull() ?: "{}")
            if (json.optBoolean("success", false)) {
                Result.success(true)
            } else {
                Result.failure(Exception(json.optString("error", "Scroll failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves captured console logs for a tab.
     */
    fun getConsoleLogs(
        tabId: String? = null,
        levelFilter: String? = null,
        limit: Int = MAX_LOGS_PER_QUERY
    ): List<BrowserConsoleMessage> {
        val tab = getTab(tabId) ?: return emptyList()
        val all = tab.consoleLogs.toList()
        val filtered = if (!levelFilter.isNullOrBlank() && levelFilter != "ALL") {
            all.filter { it.level.equals(levelFilter, ignoreCase = true) }
        } else {
            all
        }
        return filtered.takeLast(limit.coerceIn(1, 300))
    }

    /**
     * Retrieves captured network and HTTP errors for a tab.
     */
    fun getNetworkErrors(
        tabId: String? = null,
        limit: Int = MAX_LOGS_PER_QUERY
    ): List<BrowserNetworkError> {
        val tab = getTab(tabId) ?: return emptyList()
        return tab.networkErrors.toList().takeLast(limit.coerceIn(1, 200))
    }
}
