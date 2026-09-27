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
    var scrollY by mutableStateOf(0)
    var webView: WebView? = null
    var isBackgroundActive by mutableStateOf(true)
    var lastError by mutableStateOf<BrowserNetworkError?>(null)
    var isStalled by mutableStateOf(false)
    var isDevToolsEnabled by mutableStateOf(false)
    var isDesktopMode by mutableStateOf(false)
    var defaultUserAgent: String? = null
    var previewBitmap by mutableStateOf<Bitmap?>(null)

    // Circular buffers for real-time telemetry (capped to 300 entries each)
    val consoleLogs = ConcurrentLinkedDeque<BrowserConsoleMessage>()
    val networkErrors = ConcurrentLinkedDeque<BrowserNetworkError>()

    private var stallWatchdogRunnable: Runnable? = null

    fun capturePreview() {
        val wv = webView ?: return
        try {
            val w = wv.width
            val h = wv.height
            if (w > 0 && h > 0) {
                val scale = 0.4f
                val scaledW = (w * scale).toInt().coerceAtLeast(1)
                val scaledH = (h * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                wv.draw(canvas)
                previewBitmap = bitmap
            }
        } catch (e: Throwable) {
            Log.d("BrowserTabSession", "capturePreview skipped: ${e.message}")
        }
    }

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
        put("isDesktopMode", isDesktopMode)
        put("viewMode", if (isDesktopMode) "desktop" else "mobile")
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
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        /**
         * Injects script to spoof Desktop screen width/height, platform, client hints, and desktop viewport meta tag.
         * This prevents responsive sites (like YouTube, Google, Facebook, Twitter, Reddit) from falling back to mobile.
         */
        fun injectDesktopSpoofing(view: WebView?) {
            val js = """
            (function() {
                try {
                    // 1. Override screen dimensions to full desktop resolution
                    var screenProps = {
                        width: 1920,
                        height: 1080,
                        availWidth: 1920,
                        availHeight: 1040,
                        colorDepth: 24,
                        pixelDepth: 24
                    };
                    for (var p in screenProps) {
                        try {
                            Object.defineProperty(window.screen, p, {
                                get: (function(val) { return function() { return val; }; })(screenProps[p]),
                                configurable: true
                            });
                        } catch(e) {}
                    }

                    // 2. Override navigator platform and touch points
                    try {
                        Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; }, configurable: true });
                    } catch(e) {}
                    try {
                        Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 1; }, configurable: true });
                    } catch(e) {}

                    // 3. Override navigator.userAgentData (Chromium Client Hints)
                    try {
                        if (navigator.userAgentData) {
                            Object.defineProperty(navigator, 'userAgentData', {
                                get: function() {
                                    return {
                                        mobile: false,
                                        platform: 'Windows',
                                        brands: [
                                            { brand: 'Chromium', version: '128' },
                                            { brand: 'Google Chrome', version: '128' },
                                            { brand: 'Not;A=Brand', version: '24' }
                                        ],
                                        getHighEntropyValues: function() {
                                            return Promise.resolve({
                                                architecture: 'x86',
                                                bitness: '64',
                                                mobile: false,
                                                model: '',
                                                platform: 'Windows',
                                                platformVersion: '15.0.0'
                                            });
                                        }
                                    };
                                },
                                configurable: true
                            });
                        }
                    } catch(e) {}

                    // 4. Force 1280px desktop viewport width with pinch zoom-in and zoom-out fully enabled
                    function applyDesktopViewport() {
                        try {
                            var meta = document.querySelector('meta[name="viewport"]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                (document.head || document.documentElement).appendChild(meta);
                            }
                            meta.setAttribute('content', 'width=1280, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes');
                        } catch(e) {}
                    }

                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', applyDesktopViewport);
                    }
                    applyDesktopViewport();
                } catch(e) {}
            })();
            """.trimIndent()
            try {
                view?.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }

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
    }

    data class SpeedDialShortcut(
        val id: String = java.util.UUID.randomUUID().toString(),
        val title: String,
        val url: String,
        val iconUrl: String? = null
    )

    data class TypedHistoryItem(
        val id: String = java.util.UUID.randomUUID().toString(),
        val query: String,
        val url: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val defaultShortcuts = listOf(
        SpeedDialShortcut(id = "google", title = "Google", url = "https://www.google.com"),
        SpeedDialShortcut(id = "youtube", title = "YouTube", url = "https://www.youtube.com")
    )

    val shortcuts = mutableStateListOf<SpeedDialShortcut>().apply {
        addAll(defaultShortcuts)
    }

    val typedHistory = mutableStateListOf<TypedHistoryItem>()

    private var shortcutsLoaded = false
    private var historyLoaded = false

    fun loadShortcutsIfNeeded(context: Context) {
        if (shortcutsLoaded) return
        shortcutsLoaded = true
        val prefs = context.applicationContext.getSharedPreferences("antigem_browser_shortcuts", Context.MODE_PRIVATE)
        val json = prefs.getString("shortcuts_json", null) ?: return
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<SpeedDialShortcut>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SpeedDialShortcut(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        url = obj.getString("url")
                    )
                )
            }
            if (list.isNotEmpty()) {
                shortcuts.clear()
                shortcuts.addAll(list)
            }
        } catch (_: Exception) {}
    }

    fun loadHistoryIfNeeded(context: Context) {
        if (historyLoaded) return
        historyLoaded = true
        val prefs = context.applicationContext.getSharedPreferences("antigem_browser_history", Context.MODE_PRIVATE)
        val json = prefs.getString("typed_history_json", null) ?: return
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<TypedHistoryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TypedHistoryItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        query = obj.getString("query"),
                        url = obj.getString("url"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            if (list.isNotEmpty()) {
                typedHistory.clear()
                typedHistory.addAll(list)
            }
        } catch (_: Exception) {}
    }

    fun addHistory(context: Context, query: String, finalUrl: String) {
        val trimmedQuery = query.trim()
        val trimmedUrl = finalUrl.trim()
        if (trimmedQuery.isBlank() || trimmedUrl.isBlank() || trimmedUrl == "about:blank") return

        loadHistoryIfNeeded(context)
        // Remove duplicate if already exists
        typedHistory.removeAll { it.url.equals(trimmedUrl, ignoreCase = true) || it.query.equals(trimmedQuery, ignoreCase = true) }
        typedHistory.add(0, TypedHistoryItem(query = trimmedQuery, url = trimmedUrl))
        // Cap history to 50 most recent items
        while (typedHistory.size > 50) {
            typedHistory.removeAt(typedHistory.lastIndex)
        }
        saveHistory(context)
    }

    fun removeHistory(context: Context, id: String) {
        typedHistory.removeAll { it.id == id }
        saveHistory(context)
    }

    fun clearHistory(context: Context) {
        typedHistory.clear()
        saveHistory(context)
    }

    private fun saveHistory(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("antigem_browser_history", Context.MODE_PRIVATE)
        try {
            val arr = JSONArray()
            typedHistory.forEach { item ->
                arr.put(JSONObject().apply {
                    put("id", item.id)
                    put("query", item.query)
                    put("url", item.url)
                    put("timestamp", item.timestamp)
                })
            }
            prefs.edit().putString("typed_history_json", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun addShortcut(context: Context, title: String, url: String) {
        val formatted = formatUrl(url)
        val sc = SpeedDialShortcut(title = title, url = formatted)
        shortcuts.add(sc)
        saveShortcuts(context)
    }

    fun removeShortcut(context: Context, id: String) {
        shortcuts.removeAll { it.id == id }
        saveShortcuts(context)
    }

    private fun saveShortcuts(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("antigem_browser_shortcuts", Context.MODE_PRIVATE)
        try {
            val arr = JSONArray()
            shortcuts.forEach { sc ->
                arr.put(JSONObject().apply {
                    put("id", sc.id)
                    put("title", sc.title)
                    put("url", sc.url)
                })
            }
            prefs.edit().putString("shortcuts_json", arr.toString()).apply()
        } catch (_: Exception) {}
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
        loadShortcutsIfNeeded(context)
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

    fun addNewTab(url: String = "", title: String = "New Tab", activate: Boolean = true, desktopMode: Boolean? = null): BrowserTabSession {
        val formatted = if (url.isNotBlank()) formatUrl(url) else ""
        val newTab = BrowserTabSession(
            initialUrl = formatted,
            initialTitle = if (formatted.isNotBlank()) formatted else title
        )
        if (desktopMode != null) {
            newTab.isDesktopMode = desktopMode
        }
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

    fun closeAllTabs() {
        mainHandler.post {
            tabs.forEach { tabToRemove ->
                try {
                    tabToRemove.webView?.onPause()
                    tabToRemove.webView?.let { wv ->
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    }
                    tabToRemove.webView = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying WebView", e)
                }
            }
        }
        tabs.clear()
        activeTabId = null
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
            activeTabId = null
        } else if (activeTabId == tabId) {
            val nextIndex = (index - 1).coerceAtLeast(0)
            activeTabId = tabs[nextIndex].id
        }
        return true
    }

    /**
     * Opens a URL in an existing tab or creates a new tab.
     */
    fun openUrl(url: String, tabId: String? = null, newTab: Boolean = false, desktopMode: Boolean? = null): BrowserTabSession {
        val formatted = formatUrl(url)
        val targetTab = if (newTab || tabs.isEmpty()) {
            addNewTab(formatted, formatted, activate = true, desktopMode = desktopMode)
        } else {
            val t = getTab(tabId) ?: addNewTab(formatted, formatted, activate = true, desktopMode = desktopMode)
            if (desktopMode != null && t.isDesktopMode != desktopMode) {
                setDesktopMode(t, desktopMode)
            }
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
        var effectiveUrl = url
        if (tab.isDesktopMode) {
            if (effectiveUrl.contains("://m.youtube.com")) {
                effectiveUrl = effectiveUrl.replace("://m.youtube.com", "://www.youtube.com")
                if (!effectiveUrl.contains("app=desktop")) {
                    effectiveUrl += if (effectiveUrl.contains("?")) "&app=desktop" else "?app=desktop"
                }
            } else if (effectiveUrl.contains("://m.facebook.com")) {
                effectiveUrl = effectiveUrl.replace("://m.facebook.com", "://www.facebook.com")
            } else if (effectiveUrl.contains("://mobile.twitter.com")) {
                effectiveUrl = effectiveUrl.replace("://mobile.twitter.com", "://twitter.com")
            } else if (effectiveUrl.contains("://m.wikipedia.org")) {
                effectiveUrl = effectiveUrl.replace("://m.wikipedia.org", "://en.wikipedia.org")
            }
        }
        tab.clearError()
        tab.url = effectiveUrl
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
                    wv.loadUrl(effectiveUrl)
                } catch (e: Throwable) {
                    Log.w(TAG, "WebView loadUrl failed on existing instance, recreating WebView...", e)
                    wv = createWebView(effectiveCtx, tab, isDark = true)
                    wv.loadUrl(effectiveUrl)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Fatal error loading URL: $effectiveUrl", e)
                tab.cancelLoadingWatchdog(mainHandler)
                tab.isLoading = false
                tab.lastError = BrowserNetworkError(
                    url = effectiveUrl,
                    errorCode = -999,
                    description = "Failed to load page: ${e.message ?: "Unknown WebView Error"}"
                )
            }
        }
    }

    fun toggleDesktopMode(tab: BrowserTabSession?, context: Context? = null) {
        val t = tab ?: getActiveTab() ?: return
        setDesktopMode(t, !t.isDesktopMode, context)
    }

    fun setDesktopMode(tab: BrowserTabSession, enable: Boolean, context: Context? = null) {
        tab.isDesktopMode = enable
        mainHandler.post {
            val effectiveCtx = context ?: appContext
            val wv = tab.webView ?: run {
                if (effectiveCtx != null) ensureWebViewAttached(effectiveCtx, tab, isDark = true) else null
            } ?: return@post

            if (tab.defaultUserAgent == null) {
                tab.defaultUserAgent = wv.settings.userAgentString
            }

            if (enable) {
                wv.settings.userAgentString = DESKTOP_USER_AGENT
                wv.settings.useWideViewPort = true
                wv.settings.loadWithOverviewMode = true
                wv.settings.setSupportZoom(true)
                wv.settings.builtInZoomControls = true
                wv.settings.displayZoomControls = false
            } else {
                wv.settings.userAgentString = tab.defaultUserAgent ?: WebSettings.getDefaultUserAgent(wv.context)
                wv.settings.useWideViewPort = true
                wv.settings.loadWithOverviewMode = true
            }

            val currentUrl = tab.url
            if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                if (enable) {
                    var targetUrl = currentUrl
                    if (targetUrl.contains("://m.youtube.com")) {
                        targetUrl = targetUrl.replace("://m.youtube.com", "://www.youtube.com")
                        if (!targetUrl.contains("app=desktop")) {
                            targetUrl += if (targetUrl.contains("?")) "&app=desktop" else "?app=desktop"
                        }
                    } else if (targetUrl.contains("://m.facebook.com")) {
                        targetUrl = targetUrl.replace("://m.facebook.com", "://www.facebook.com")
                    } else if (targetUrl.contains("://mobile.twitter.com")) {
                        targetUrl = targetUrl.replace("://mobile.twitter.com", "://twitter.com")
                    } else if (targetUrl.contains("://m.wikipedia.org")) {
                        targetUrl = targetUrl.replace("://m.wikipedia.org", "://en.wikipedia.org")
                    }

                    if (targetUrl != currentUrl) {
                        tab.url = targetUrl
                        wv.loadUrl(targetUrl)
                        return@post
                    }
                } else {
                    if (currentUrl.contains("app=desktop")) {
                        val cleanedUrl = currentUrl
                            .replace("?app=desktop&", "?")
                            .replace("?app=desktop", "")
                            .replace("&app=desktop", "")
                        if (cleanedUrl != currentUrl) {
                            tab.url = cleanedUrl
                            wv.loadUrl(cleanedUrl)
                            return@post
                        }
                    }
                }
                wv.reload()
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

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context, tab: BrowserTabSession, isDark: Boolean): WebView {
        try {
            tab.webView?.let { old ->
                (old.parent as? ViewGroup)?.removeView(old)
                old.destroy()
            }
        } catch (_: Exception) {}
        tab.webView = null

        val wv = WebView(context).apply {
            setBackgroundColor(Color.WHITE)
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

                if (tab.isDesktopMode) {
                    userAgentString = DESKTOP_USER_AGENT
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    forceDark = WebSettings.FORCE_DARK_OFF
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

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
                    if (tab.isDesktopMode) {
                        injectDesktopSpoofing(view)
                    }
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
                    if (tab.isDesktopMode) {
                        injectDesktopSpoofing(view)
                    }
                    if (tab.isDevToolsEnabled && view != null) {
                        ErudaHelper.inject(view, showImmediately = false)
                    }
                    view?.postDelayed({
                        tab.capturePreview()
                    }, 400)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    if (tab.isDesktopMode) {
                        injectDesktopSpoofing(view)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
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
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
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
                    super.onReceivedSslError(view, handler, error)
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
                    return super.onRenderProcessGone(view, detail)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "about" || scheme == "data") {
                        if (tab.isDesktopMode) {
                            val host = uri.host?.lowercase() ?: ""
                            if (host == "m.youtube.com") {
                                var desktopUrl = uri.toString().replace("://m.youtube.com", "://www.youtube.com")
                                if (!desktopUrl.contains("app=desktop")) {
                                    desktopUrl += if (desktopUrl.contains("?")) "&app=desktop" else "?app=desktop"
                                }
                                tab.url = desktopUrl
                                view?.loadUrl(desktopUrl)
                                return true
                            } else if (host == "m.facebook.com") {
                                val desktopUrl = uri.toString().replace("://m.facebook.com", "://www.facebook.com")
                                tab.url = desktopUrl
                                view?.loadUrl(desktopUrl)
                                return true
                            } else if (host == "mobile.twitter.com") {
                                val desktopUrl = uri.toString().replace("://mobile.twitter.com", "://twitter.com")
                                tab.url = desktopUrl
                                view?.loadUrl(desktopUrl)
                                return true
                            } else if (host == "m.wikipedia.org") {
                                val desktopUrl = uri.toString().replace("://m.wikipedia.org", "://en.wikipedia.org")
                                tab.url = desktopUrl
                                view?.loadUrl(desktopUrl)
                                return true
                            }
                        }
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
