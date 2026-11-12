package com.example.gemini.ui.miniapps

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.MimeTypeMap
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniAppConsole
import com.example.gemini.data.miniapps.MiniAppCookies
import com.example.gemini.data.miniapps.MiniAppScheduler
import com.example.gemini.data.miniapps.MiniAppIntents
import com.example.gemini.data.miniapps.MiniAppDeviceStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.example.gemini.data.miniapps.MiniAppError
import com.example.gemini.data.miniapps.MiniAppFs
import com.example.gemini.data.miniapps.MiniAppNet
import com.example.gemini.data.miniapps.MiniAppNotify
import com.example.gemini.data.miniapps.MiniAppPageHandle
import com.example.gemini.data.miniapps.MiniAppMcpAccess
import com.example.gemini.data.miniapps.MiniAppPermissions
import com.example.gemini.data.miniapps.MiniAppRuntime
import com.example.gemini.data.miniapps.MiniApps
import com.example.gemini.theme.ThemeManager
import com.example.gemini.ui.browser.BrowserExtensionManager
import com.example.gemini.ui.browser.ErudaHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * One page of a mini app: a WebView that only renders. Everything else goes through [handle]:
 *  - files of the app are served from disk on the app's own https origin (no server), with the SDK injected first
 *  - a strict CSP: the page itself can only connect to its own origin, so all networking goes through native code
 *  - JS ⇄ native messages over a WebMessageListener (origin-locked), replies matched by id, events pushed back
 */
@SuppressLint("SetJavaScriptEnabled")
/** A background task run in this page: [onDone] gets (ok, error) when the app's handler finishes. */
class BackgroundTask(val name: String, val reason: String, val runId: String, val data: String? = null, val onDone: (Boolean, String) -> Unit)

class MiniAppPage(private val host: MiniAppHost, app: MiniApp, override val path: String, private val task: BackgroundTask? = null) : MiniAppPageHandle {
    override val pageId: String = UUID.randomUUID().toString()
    var app: MiniApp = app
        private set
    override val appId: String get() = app.id
    override val isForeground: Boolean get() = !destroyed && task == null && host.isForeground
    /** The page has loaded (its scripts ran): events can be delivered. */
    @Volatile private var ready = false

    private val ctx: Context = host.context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    /** sessionStorage of this page: survives reloads, dies with the page (like a browser tab). */
    private val session = LinkedHashMap<String, String>()
    private var mainProxy: JavaScriptReplyProxy? = null
    private var legacy = false
    @Volatile private var devtools = false
    @Volatile private var destroyed = false
    val webView: WebView = createWebView()

    init { MiniAppRuntime.attach(ctx, app, this) }

    // ---------------------------------------------------------------- WebView

    private fun createWebView(): WebView = WebView(host.context).apply {
        // MATCH_PARENT: with Compose's default WRAP_CONTENT the WebView sizes CSS viewport units from its content,
        // so 100vh came out as 0
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = false // localStorage / sessionStorage are native (core.js)
            @Suppress("DEPRECATION") databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false
            textZoom = 100
            useWideViewPort = true; loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString AntiGemMiniApp/${MiniApps.SDK_VERSION}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(host.backgroundColor)
        webViewClient = Client()
        webChromeClient = Chrome()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "__antigemNative", setOf(app.origin)) { _, message, _, isMainFrame, proxy ->
                val text = message.data ?: return@addWebMessageListener
                onMessage(text, isMainFrame) { json -> proxy.postMessage(json) }
                if (isMainFrame && mainProxy !== proxy && text.contains("\"m\":\"hello\"")) mainProxy = proxy
            }
        } else {
            legacy = true
            addJavascriptInterface(LegacyBridge(), "__antigemNativeLegacy")
        }
        applyDark(this, host.isDark)
    }

    fun applyDark(dark: Boolean) = applyDark(webView, dark)

    @Suppress("DEPRECATION")
    private fun applyDark(view: WebView, dark: Boolean) {
        // targetSdk 28: this is what makes `prefers-color-scheme: dark` match, without darkening the page itself
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY))
            WebSettingsCompat.setForceDarkStrategy(view.settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
            WebSettingsCompat.setForceDark(view.settings, if (dark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
    }

    fun load() {
        logManifestWarnings()
        webView.loadUrl(app.origin + "/" + path.trimStart('/'))
    }

    /** Manifest entries that were ignored or look wrong, once per load of the app's first page. */
    private fun logManifestWarnings() {
        if (task == null && !host.isPushed) app.warnings.forEach { log("warn", "manifest.json: $it") }
    }

    fun reload() = webView.reload()

    override val isBackgroundTask: Boolean get() = task != null

    override fun devEval(js: String, cb: (String?) -> Unit) = onMain { if (destroyed) cb(null) else webView.evaluateJavascript(js) { cb(it) } }

    /** The page's window as it is on screen (PixelCopy, so the WebView's GPU layers are included). */
    override suspend fun capture(): Bitmap? = kotlinx.coroutines.withContext(Dispatchers.Main) {
        if (destroyed) return@withContext null
        val window = (host.context as? android.app.Activity)?.window
        val view = window?.decorView
        if (window != null && view != null && view.width > 0 && android.os.Build.VERSION.SDK_INT >= 26) {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            kotlinx.coroutines.suspendCancellableCoroutine { c ->
                android.view.PixelCopy.request(window, bmp, { r -> if (c.isActive) c.resumeWith(Result.success(if (r == android.view.PixelCopy.SUCCESS) bmp else null)) }, main)
            }
        } else runCatching {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { webView.draw(android.graphics.Canvas(it)) }
        }.getOrNull()
    }

    private inner class LegacyBridge {
        @JavascriptInterface
        fun postMessage(text: String) = main.post { onMessage(text, true) { json -> evalReply(json) } }
    }

    private fun evalReply(json: String) {
        if (!destroyed) webView.evaluateJavascript("window.__AG&&__AG._recv(${JSONObject.quote(json)})", null)
    }

    private fun onMain(block: () -> Unit) = if (Looper.myLooper() == Looper.getMainLooper()) block() else { main.post(block); Unit }

    override fun emit(event: String, data: Any?) {
        val json = JSONObject().put("ev", event).put("d", data ?: JSONObject.NULL).toString()
        onMain {
            if (destroyed) return@onMain
            if (legacy) evalReply(json) else runCatching { mainProxy?.postMessage(json) }
        }
    }

    /** Notification events that waited for the app to open (or come back on screen). */
    fun flushPendingEvents() {
        if (!ready || task != null || destroyed) return
        // to every open page of the app, not just the one that came back first
        MiniAppRuntime.takePendingEvents(app.id).forEach { MiniAppRuntime.broadcast(app.id, it.optString("ev"), it.optJSONObject("d")) }
    }

    fun log(level: String, message: String) = MiniAppConsole.add(ctx, app.id, level, message, "runtime")

    override fun onFilesChanged(cssOnly: Boolean, manifestChanged: Boolean) = onMain {
        if (destroyed || task != null) return@onMain
        if (manifestChanged) { app = MiniApps.reload(ctx, app); host.onManifestChanged(app); logManifestWarnings() }
        if (cssOnly) { emit("hotcss", null); log("info", "↻ CSS updated") } else { log("info", "↻ Reloaded: files changed"); webView.reload() }
    }

    fun openDevtools() {
        devtools = true
        ErudaHelper.inject(webView, true)
    }

    /** Back: the app's onBack handler first, then the page history, else [done] gets false (leave the page). */
    fun handleBack(done: (Boolean) -> Unit) {
        webView.evaluateJavascript("(function(){try{return !!(window.__AG&&__AG._back&&__AG._back())}catch(e){return false}})()") { r ->
            when {
                r == "true" -> done(true)
                webView.canGoBack() -> { webView.goBack(); done(true) }
                else -> done(false)
            }
        }
    }

    fun evaluate(code: String, result: (String) -> Unit) {
        webView.evaluateJavascript("(function(){try{var r=eval(${JSONObject.quote(code)});try{return typeof r==='string'?r:JSON.stringify(r)}catch(e){return String(r)}}catch(e){return 'Uncaught '+e}})()") { r ->
            result(runCatching { JSONArray("[$r]").get(0).toString() }.getOrDefault(r ?: "undefined"))
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        MiniAppRuntime.detach(this)
        MiniAppNet.pageClosed(pageId)
        scope.cancel()
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    // ---------------------------------------------------------------- serving files

    private val csp = listOf(
        "default-src 'self'",
        "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: https:",
        "style-src 'self' 'unsafe-inline' https:",
        "img-src 'self' data: blob: https:",
        "font-src 'self' data: https:",
        "media-src 'self' data: blob: https:",
        // fetch / XHR / WebSocket only to the app itself: everything else goes through the native network layer
        "connect-src 'self' data: blob:",
        "frame-src 'self' blob: data:",
        "worker-src 'self' blob:",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
    ).joinToString("; ")

    // No COOP/COEP: Android WebView doesn't grant cross-origin isolation (so no SharedArrayBuffer) even with them.
    private fun headers(extra: Map<String, String> = emptyMap()) = mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to csp) + extra

    private fun text(mime: String, code: Int, reason: String, body: String) =
        WebResourceResponse(mime, "utf-8", code, reason, headers(), ByteArrayInputStream(body.toByteArray()))

    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null // data:, blob:
        if (scheme == "https" && url.host == app.host) return serveLocal(request)
        return MiniAppNet.proxy(ctx, app, request, devtools) { log("warn", it) }
    }

    private fun serveLocal(request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        val path = url.path ?: "/"
        return try {
            when {
                path == "/_antigem/sdk.js" -> text("text/javascript", 200, "OK", sdkScript())
                path == "/_antigem/worker-sdk.js" -> text("text/javascript", 200, "OK", workerSdkScript())
                path == "/_antigem/ui.css" -> text("text/css", 200, "OK", Sdk.asset(ctx, "ui.css"))
                path.startsWith("/_antigem/net/") -> MiniAppNet.openStream(path.removePrefix("/_antigem/net/")) ?: text("text/plain", 404, "Not Found", "Gone")
                path == "/_antigem/fs" -> MiniAppFs.serve(ctx, app, url.getQueryParameter("p").orEmpty(), request.requestHeaders?.entries?.firstOrNull { it.key.equals("range", true) }?.value,
                    url.getQueryParameter("thumb")?.toIntOrNull())
                path == "/_antigem/background.html" && task != null -> text("text/html", 200, "OK", inject(backgroundHtml()))
                path == "/_antigem/icon" -> app.iconFile?.let { WebResourceResponse(MiniAppNet.guessMime(it.name), null, 200, "OK", headers(), FileInputStream(it)) } ?: text("text/plain", 404, "Not Found", "")
                path.startsWith("/_antigem/") -> text("text/plain", 404, "Not Found", "")
                else -> serveFile(path, url.getQueryParameter("__agw"))
            }
        } catch (e: Exception) {
            log("error", "Serving $path failed: ${e.message}")
            text("text/plain", 500, "Error", e.message.orEmpty())
        }
    }

    /** [worker]: "c" / "m" when the file is loaded as a classic / module worker (threads.js marks it): the SDK goes first. */
    private fun serveFile(rawPath: String, worker: String? = null): WebResourceResponse {
        val root = app.folder.canonicalFile
        var rel = rawPath.trimStart('/')
        if (rel.isEmpty()) rel = app.entry
        if (rel.split('/').any { it.startsWith(".") }) return notFound(rawPath)
        var f = File(root, rel).canonicalFile
        if (f.path != root.path && !f.path.startsWith(root.path + File.separator)) return notFound(rawPath)
        if (f.isDirectory) f = File(f, "index.html")
        // single-page apps: unknown routes without an extension get the entry page
        if (!f.isFile && !rel.substringAfterLast('/').contains('.')) f = File(root, app.entry)
        if (!f.isFile) return notFound(rawPath)
        // an installed app only ever runs the files its developer signed
        if (app.signed) {
            val rel = f.path.removePrefix(root.path + File.separator)
            if (!com.example.gemini.data.miniapps.MiniAppInstaller.isIntact(ctx, app, rel, f)) {
                log("error", "Refused /$rel: it isn't the file ${app.developer} signed (modified after install)")
                return text("text/plain", 403, "Forbidden", "Modified file")
            }
        }
        val mime = MiniAppNet.guessMime(f.name)
        if (mime == "text/html") return text("text/html", 200, "OK", inject(f.readText()))
        if (worker != null && mime == "text/javascript") return text("text/javascript", 200, "OK", injectWorker(f.readText(), module = worker == "m"))
        val charset = if (mime.startsWith("text/") || mime == "application/json") "utf-8" else null
        return WebResourceResponse(mime, charset, 200, "OK", headers(), FileInputStream(f))
    }

    private fun notFound(path: String): WebResourceResponse {
        // the WebView asks for a favicon on its own: not the app's mistake, so no console noise
        if (path == "/favicon.ico") return text("text/plain", 404, "Not Found", "")
        log("warn", "404 $path")
        return text("text/html", 404, "Not Found", "<!doctype html><meta name=viewport content='width=device-width'><body style='font:16px system-ui;padding:24px'><h3>Not found</h3><p>$path</p>")
    }

    private val headRe = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val htmlRe = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val doctypeRe = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)
    private val viewportRe = Regex("<meta[^>]+name\\s*=\\s*[\"']?viewport", RegexOption.IGNORE_CASE)

    /** Puts the SDK (and a viewport, when missing) first in <head>, before any app script runs. */
    private fun inject(html: String): String {
        val viewport = if (viewportRe.containsMatchIn(html)) "" else "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover\">"
        val tags = "$viewport<script src=\"/_antigem/sdk.js\"></script>"
        val at = (headRe.find(html) ?: htmlRe.find(html) ?: doctypeRe.find(html))?.range?.last?.plus(1) ?: 0
        return html.substring(0, at) + tags + html.substring(at)
    }

    /**
     * The SDK on the first line of a worker file (same line, so error line numbers stay right; after a
     * "use strict" directive so it stays a directive).
     */
    private fun injectWorker(js: String, module: Boolean): String {
        val load = if (module) "import \"/_antigem/worker-sdk.js\";" else "importScripts(\"/_antigem/worker-sdk.js\");"
        val directive = Regex("^\\s*(['\"])use strict\\1;?").find(js)
        return if (directive != null) js.substring(0, directive.range.last + 1) + load + js.substring(directive.range.last + 1) else load + js
    }

    private fun workerSdkScript(): String {
        val boot = JSONObject()
            .put("sdk", MiniApps.SDK_VERSION)
            .put("app", JSONObject().put("id", app.id).put("name", app.name).put("version", app.version).put("origin", app.origin))
            .put("perms", permsJson())
        return "globalThis.__AG_BOOT=$boot;\n" + Sdk.workerBundle(ctx)
    }

    /** The (invisible) page a background task runs in: just the app's background script, after the SDK. */
    private fun backgroundHtml(): String {
        val src = "/" + (app.backgroundScript ?: "background.js")
        val type = if (app.backgroundModule) " type=\"module\"" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>${app.name} background</title></head><body><script$type src=\"$src\"></script></body></html>"
    }

    private fun sdkScript(): String {
        val state = MiniAppRuntime.state(ctx, app.id)
        val q = path.substringAfter('?', "")
        val params = JSONObject()
        if (q.isNotEmpty()) Uri.parse("x://x/?$q").let { u -> u.queryParameterNames.forEach { params.put(it, u.getQueryParameter(it)) } }
        val boot = JSONObject()
            .put("sdk", MiniApps.SDK_VERSION)
            .put("app", JSONObject().put("id", app.id).put("name", app.name).put("version", app.version).put("origin", app.origin))
            .put("page", JSONObject().put("id", pageId).put("path", path.substringBefore('?')).put("params", params).put("root", !host.isPushed).put("background", task != null))
            .put("task", task?.let { t ->
                JSONObject().put("name", t.name).put("reason", t.reason).put("runId", t.runId)
                    .put("data", t.data?.let { d -> runCatching { JSONObject(d) }.getOrNull() } ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("kv", state.kv.snapshot())
            .put("ss", JSONObject(session as Map<*, *>))
            .put("theme", themeJson())
            .put("perms", permsJson())
            .put("legacy", legacy)
        return "window.__AG_BOOT=$boot;\n" + Sdk.bundle(ctx)
    }

    // ---------------------------------------------------------------- theme & permissions

    fun themeJson(): JSONObject {
        val dark = host.isDark
        val colors = JSONObject()
        ThemeManager.resolvedColors(ThemeManager.themeFor(dark)).forEach { (k, v) -> colors.put(k, cssColor(v)) }
        return JSONObject().put("dark", dark).put("colors", colors)
            .put("appColor", app.themeColor?.let { cssColor(it.toLong() and 0xFFFFFFFFL) } ?: colors.optString("background"))
    }

    private fun cssColor(argb: Long): String {
        val a = (argb shr 24) and 0xFF
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return if (a == 0xFFL) "#%02x%02x%02x".format(r, g, b) else "rgba($r, $g, $b, ${"%.3f".format(Locale.US, a / 255.0)})"
    }

    fun pushTheme() {
        applyDark(host.isDark)
        emit("theme", themeJson())
    }

    /** Every declared permission's state, plus "storage" for all storage folders together (so query('storage') works). */
    private fun parseDate(t: String): Long? = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mmX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
        "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE MMM dd yyyy HH:mm:ss 'GMT'Z",
    ).firstNotNullOfOrNull { f -> runCatching { java.text.SimpleDateFormat(f, java.util.Locale.US).apply { isLenient = false }.parse(t.substringBefore(" (")) }.getOrNull()?.time }

    private fun permsJson() = JSONObject().apply {
        MiniAppPermissions.all(ctx, app).forEach { (k, v) -> put(k, v.js) }
        if (!has("storage")) MiniAppPermissions.state(ctx, app, "storage").takeIf { it != MiniAppPermissions.State.UNDECLARED }?.let { put("storage", it.js) }
    }

    fun pushPermissions() = emit("perms", permsJson())

    // ---------------------------------------------------------------- messages

    private fun onMessage(text: String, isMainFrame: Boolean, reply: (String) -> Unit) {
        if (destroyed) return
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = msg.opt("i")?.takeIf { it != JSONObject.NULL }
        val method = msg.optString("m")
        val params = msg.optJSONObject("p") ?: JSONObject()
        if (method == "hello") { if (id != null) reply(JSONObject().put("i", id).put("r", JSONObject.NULL).toString()); return }
        scope.launch {
            val result = try { Result.success(handle(method, params)) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
            if (id == null) {
                result.exceptionOrNull()?.let { log("error", "$method: ${it.message}") }
                return@launch
            }
            val out = JSONObject().put("i", id)
            result.fold(
                onSuccess = { out.put("r", it ?: JSONObject.NULL) },
                onFailure = { e ->
                    out.put("e", JSONObject().put("name", (e as? MiniAppError)?.errorName ?: "Error").put("message", e.message ?: e.javaClass.simpleName))
                },
            )
            val json = out.toString()
            onMain { if (!destroyed) reply(json) }
        }
    }

    private fun require(name: String) = MiniAppPermissions.require(ctx, app, name)

    private suspend fun handle(m: String, p: JSONObject): Any? = when (m) {
        // ---- storage
        "kv" -> { kv(p); null }
        "idb.exec" -> suspendCancellableCoroutine { c -> MiniAppRuntime.state(ctx, app.id).idb.exec(pageId, p) { r -> if (c.isActive) c.resumeWith(r) } }
        // ---- network
        "net.fetch" -> suspendCancellableCoroutine<Any?> { c -> MiniAppNet.fetch(ctx, app, pageId, p) { r -> if (c.isActive) c.resumeWith(r) } }
        "net.abort" -> { MiniAppNet.abort(p.optString("rid")); null }
        "net.drop" -> { MiniAppNet.cancelStream(p.optString("sid")); null }
        "ws.open" -> { MiniAppNet.wsOpen(ctx, app, pageId, p) { emit("ws", it) }; null }
        "ws.send" -> { MiniAppNet.wsSend(p); null }
        "ws.close" -> { MiniAppNet.wsClose(p); null }
        // ---- files
        "fs.read", "fs.write", "fs.list", "fs.stat", "fs.exists", "fs.mkdir", "fs.delete", "fs.move" -> withContext(Dispatchers.IO) { MiniAppFs.call(ctx, app, m, p) }
        "fs.pickFolder" -> pickFolder()
        "fs.folders" -> withContext(Dispatchers.IO) { MiniAppFs.folders(ctx, app) }
        "fs.forgetFolder" -> { MiniAppPermissions.removePickedFolder(ctx, app, p.optString("path").ifBlank { null }); pushPermissions(); null }
        "fs.pick" -> {
            val uris = host.pickFiles(p.optString("accept").ifBlank { "*/*" }, p.optBoolean("multiple"))
            withContext(Dispatchers.IO) { MiniAppFs.importUris(ctx, app, uris) }
        }
        // ---- permissions
        "perm.all" -> permsJson()
        "perm.request" -> host.requestPermission(app, p.optString("name")).js
        // ---- native UI
        "ui.toast" -> { host.toast(p.optString("text"), p.optBoolean("long")); null }
        "ui.alert" -> { host.showDialog(NativeDialog.Alert(p.optString("title"), p.optString("message"), p.optString("ok").ifBlank { "OK" })); null }
        "ui.confirm" -> host.showDialog(NativeDialog.Confirm(p.optString("title"), p.optString("message"), p.optString("ok").ifBlank { "OK" }, p.optString("cancel").ifBlank { "Cancel" }, p.optBoolean("destructive"))) == true
        "ui.prompt" -> host.showDialog(NativeDialog.Prompt(p.optString("title"), p.optString("message"), p.optString("value"), p.optString("placeholder"), p.optString("ok").ifBlank { "OK" }, p.optString("cancel").ifBlank { "Cancel" })) ?: JSONObject.NULL
        "ui.sheet" -> {
            val items = p.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { NativeDialog.SheetItem(it.optString("id"), it.optString("label"), it.optString("icon"), it.optBoolean("destructive")) } }.orEmpty()
            host.showDialog(NativeDialog.Sheet(p.optString("title"), items)) ?: JSONObject.NULL
        }
        "ui.date" -> host.pickDate(p.optString("value"), p.optString("title")) ?: JSONObject.NULL
        "ui.time" -> host.pickTime(p.optString("value")) ?: JSONObject.NULL
        "intent.send" -> sendIntent(p)
        "device.status" -> JSONObject().put("network", MiniAppDeviceStatus.network(ctx)).put("battery", MiniAppDeviceStatus.battery(ctx))
        "ui.viewMedia" -> {
            val items = withContext(Dispatchers.IO) { mediaItems(p.optJSONArray("items") ?: throw MiniAppError("TypeError", "\"items\" is required")) }
            if (items.isEmpty()) throw MiniAppError("TypeError", "Nothing to show")
            JSONObject().put("index", host.showDialog(NativeDialog.MediaViewer(items, p.optInt("index"))) as? Int ?: 0)
        }
        "ui.statusBar" -> { host.setStatusBar(p.optString("color")); null }
        // ---- device
        "device.haptic" -> { haptic(p.optString("kind")); null }
        "device.share" -> { share(p); null }
        "device.copy" -> { clipboard().setPrimaryClip(ClipData.newPlainText(app.name, p.optString("text"))); null }
        "device.paste" -> { require("clipboard"); clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty() }
        "device.openUrl" -> { openUrl(p.optString("url")); null }
        "device.info" -> JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT).put("dark", host.isDark).put("locale", Locale.getDefault().toLanguageTag())
        // ---- notifications
        "notify.show" -> { require("notifications"); withContext(Dispatchers.IO) { MiniAppNotify.show(ctx, app, p) } }
        "notify.channel" -> { require("notifications"); withContext(Dispatchers.IO) { MiniAppNotify.createChannel(ctx, app, p) } }
        "notify.deleteChannel" -> { MiniAppNotify.deleteChannel(ctx, app, p.optString("id")); null }
        "notify.active" -> withContext(Dispatchers.IO) { MiniAppNotify.active(ctx, app.id) }
        "notify.dismiss" -> { MiniAppNotify.cancel(ctx, app.id, p.optString("id").ifBlank { null }); null }
        "notify.schedule" -> { require("notifications"); withContext(Dispatchers.IO) { MiniAppScheduler.scheduleReminder(ctx, app, p) } }
        "notify.list" -> withContext(Dispatchers.IO) { MiniAppScheduler.listReminders(ctx, app.id) }
        "notify.cancel" -> { withContext(Dispatchers.IO) { MiniAppScheduler.cancelReminder(ctx, app.id, p.optString("id").ifBlank { p.optString("tag") }) }; null }
        "notify.cancelAll" -> { withContext(Dispatchers.IO) { MiniAppScheduler.cancelReminder(ctx, app.id, null) }; null }
        // ---- cookies (the app's own jar; only for hosts it may reach)
        "cookies.list", "cookies.set", "cookies.import", "cookies.delete", "cookies.clear" -> withContext(Dispatchers.IO) { cookies(m, p) }
        // ---- background tasks
        "bg.register" -> { require("background"); withContext(Dispatchers.IO) { MiniAppScheduler.registerTask(ctx, app, p.optString("name"), p.optJSONObject("opts") ?: JSONObject()) } }
        "bg.list" -> withContext(Dispatchers.IO) { MiniAppScheduler.listTasks(ctx, app.id) }
        "bg.cancel" -> { withContext(Dispatchers.IO) { MiniAppScheduler.cancelTask(ctx, app.id, p.optString("name").ifBlank { null }) }; null }
        "bg.run" -> {
            require("background")
            if (app.backgroundScript == null) throw MiniAppError("InvalidStateError", "The manifest has no \"background\" script")
            MiniAppScheduler.runNow(ctx, app.id, p.optString("name"), "manual"); null
        }
        "bg.done" -> { if (task != null && p.optString("runId") == task.runId) task.onDone(p.optBoolean("ok"), p.optString("error")); null }
        // ---- navigation
        "nav.push" -> { host.push(p.optString("path")); null }
        "nav.back" -> { host.closePage(); null }
        "nav.close" -> { host.closeApp(); null }
        // ---- AntiGem MCP tools
        "mcp.list", "mcp.call" -> mcp(m, p)
        // ---- dev
        "dev.console" -> { host.showConsole(); null }
        "dev.devtools" -> { openDevtools(); null }
        "app.reload" -> { webView.reload(); null }
        else -> throw MiniAppError("NotSupportedError", "Unknown method $m")
    }

    /** ops: [{a: "l" (localStorage) | "s" (AntiGem.storage) | "ss" (sessionStorage), op: set|del|clear, k, v}] */
    private fun kv(p: JSONObject) {
        val ops = p.optJSONArray("ops") ?: return
        val shared = JSONArray()
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            val area = o.optString("a")
            if (area == "ss") {
                when (o.optString("op")) { "set" -> session[o.optString("k")] = o.optString("v"); "del" -> session.remove(o.optString("k")); "clear" -> session.clear() }
                continue
            }
            val prefix = "$area:"
            shared.put(when (o.optString("op")) {
                "set" -> JSONObject().put("op", "set").put("k", prefix + o.optString("k")).put("v", o.optString("v"))
                "del" -> JSONObject().put("op", "del").put("k", prefix + o.optString("k"))
                else -> JSONObject().put("op", "clear").put("prefix", prefix)
            })
        }
        if (shared.length() == 0) return
        MiniAppRuntime.state(ctx, app.id).kv.apply(shared)
        // other pages of this app keep their copies in sync (and fire `storage` events)
        MiniAppRuntime.broadcast(app.id, "kv", JSONObject().put("ops", ops), except = this)
    }

    /**
     * AntiGem.intent.send: the user picks which app gets it (mini apps that declared they accept it, or Android's
     * own share sheet). A file goes along as a one-day read grant for that file only.
     */
    private suspend fun sendIntent(p: JSONObject): Any? {
        val action = p.optString("action").ifBlank { "share" }.take(40)
        val file = p.optString("path").takeIf { it.isNotBlank() }?.let { path ->
            withContext(Dispatchers.IO) { MiniAppPermissions.resolve(ctx, app, path, MiniAppPermissions.Need.READ) }
                .also { if (!it.isFile) throw MiniAppError("NotFoundError", "No such file: $path") }
        }
        val url = p.optString("url").takeIf { it.isNotBlank() }
        val text = p.optString("text").takeIf { it.isNotBlank() }
        val type = p.optString("type").ifBlank { file?.let { MiniAppNet.guessMime(it.name) } ?: if (url != null) "text/uri-list" else "text/plain" }
        val targets = withContext(Dispatchers.IO) { MiniAppIntents.targets(ctx, action, type) }.filter { it.id != app.id }
        val android = action in setOf("share", "view", "open") && (file != null || text != null || url != null)
        val items = targets.map { NativeDialog.SheetItem(it.id, it.name, if (it.signed) "▣" else "◇", false) } +
            (if (android) listOf(NativeDialog.SheetItem("__android__", "Other apps…", "📤", false)) else emptyList())
        if (items.isEmpty()) throw MiniAppError("NotFoundError", "No app accepts \"$action\" for $type")
        val title = when (action) { "share" -> "Share with"; "open", "view" -> "Open with"; "edit" -> "Edit with"; else -> action.replaceFirstChar { it.uppercase() } + " with" }
        val pick = host.showDialog(NativeDialog.Sheet(title, items)) as? String ?: return JSONObject.NULL
        if (pick == "__android__") {
            when {
                file != null -> com.example.gemini.ui.miniapps.MiniAppPackages.share(host.context, file, type)
                action != "share" && url != null -> openUrl(url)
                else -> host.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, listOfNotNull(text, url).joinToString("\n")).putExtra(Intent.EXTRA_SUBJECT, p.optString("title")), title))
            }
            return JSONObject().put("to", "android")
        }
        val target = targets.first { it.id == pick }
        val intent = JSONObject().put("action", action).put("type", type)
            .put("from", JSONObject().put("id", app.id).put("name", app.name).put("signed", app.signed))
        text?.let { intent.put("text", it) }; url?.let { intent.put("url", it) }
        p.optString("title").takeIf { it.isNotBlank() }?.let { intent.put("title", it) }
        p.optJSONObject("data")?.let { intent.put("data", it) }
        if (file != null) { MiniAppPermissions.grantFile(ctx, target, file); intent.put("path", file.path) }
        MiniAppRuntime.deliverIntent(target.id, intent)
        MiniAppActivity.launch(host.context, target.id)
        return JSONObject().put("to", target.id)
    }

    /** Items for the native viewer: paths are permission-checked like AntiGem.fs, URLs like the network layer. */
    private fun mediaItems(a: JSONArray): List<MediaItem> = (0 until minOf(a.length(), 5000)).mapNotNull { i ->
        val o = a.opt(i)
        val src = (if (o is JSONObject) o.optString("src") else o?.toString()).orEmpty().trim()
        if (src.isEmpty()) return@mapNotNull null
        val type = (o as? JSONObject)?.optString("type").orEmpty()
        val title = (o as? JSONObject)?.optString("title").orEmpty().ifBlank { src.substringAfterLast('/').substringBefore('?') }
        val video = type == "video" || type != "image" && MiniAppNet.guessMime(src.substringBefore('?')).startsWith("video/")
        when {
            src.startsWith("https://") || src.startsWith("http://") -> {
                val url = src.toHttpUrlOrNull() ?: return@mapNotNull null
                MiniAppPermissions.checkNetwork(ctx, app, url.host, url.port)?.let { throw MiniAppError("NotAllowedError", "$src: $it") }
                // the native viewer loads it itself: a name that points to this phone needs the localhost permission too
                val local = runCatching { java.net.InetAddress.getAllByName(url.host).any { MiniAppNet.isLocalAddress(it) } }.getOrDefault(false)
                if (local) MiniAppPermissions.checkLoopback(ctx, app, url.port)?.let { throw MiniAppError("NotAllowedError", "$src: $it") }
                MediaItem(null, src, video, title)
            }
            src == "data" || src.startsWith("data/") || src.startsWith("app:") || src.startsWith("~/") || src.startsWith("/") ->
                MediaItem(MiniAppPermissions.resolve(ctx, app, src, MiniAppPermissions.Need.READ), null, video, title)
            else -> {
                // a file of the app itself (for an installed app, only the one that was signed)
                val root = app.folder.canonicalFile
                val f = File(root, src).canonicalFile
                if (!f.path.startsWith(root.path + File.separator)) throw MiniAppError("NotAllowedError", "$src is outside the app")
                if (app.signed && !com.example.gemini.data.miniapps.MiniAppInstaller.isIntact(ctx, app, f.path.removePrefix(root.path + File.separator), f))
                    throw MiniAppError("SecurityError", "$src doesn't match the app's signature")
                MediaItem(f, null, video, title)
            }
        }
    }

    /** AntiGem.fs.pickFolder: the user chooses a folder in AntiGem's file explorer; the app gets the manifest's "pick" access to it. */
    private suspend fun pickFolder(): Any? {
        val access = app.storagePick ?: throw MiniAppError("NotAllowedError", "The manifest doesn't allow picking folders (permissions.storage.pick)")
        val folder = host.pickFolder("Choose a folder for ${app.name}") ?: return JSONObject.NULL
        if (!folder.isDirectory) throw MiniAppError("NotFoundError", "Not a folder: ${folder.path}")
        if (MiniAppPermissions.isProtected(ctx, folder)) throw MiniAppError("NotAllowedError", "${folder.path} is protected")
        // a whole storage root or home: the typed confirmation, as for "all files"
        if (MiniAppPermissions.isBroadFolder(ctx, folder) && host.showDialog(NativeDialog.TypedConfirm(app, "picked:${folder.path}")) != true)
            throw MiniAppError("NotAllowedError", "Access to ${folder.path} was not confirmed")
        MiniAppPermissions.addPickedFolder(ctx, app, folder)
        pushPermissions()
        return JSONObject().put("path", folder.path).put("access", access)
    }

    private fun cookieUrl(p: JSONObject): okhttp3.HttpUrl? = p.optString("url").takeIf { it.isNotBlank() }?.let { u ->
        val url = u.toHttpUrlOrNull() ?: throw MiniAppError("TypeError", "Invalid URL: $u")
        MiniAppPermissions.checkNetwork(ctx, app, url.host, url.port)?.let { throw MiniAppError("NotAllowedError", "Cookies for ${url.host}: $it") }
        url
    }

    private fun cookies(m: String, p: JSONObject): Any? {
        val jar = MiniAppNet.cookies(ctx, app.id)
        val url = cookieUrl(p)
        val now = System.currentTimeMillis()
        fun expiry(): Long = when {
            p.has("maxAge") -> now + p.optLong("maxAge") * 1000
            p.has("expires") -> when (val e = p.opt("expires")) {
                is Number -> e.toLong()
                // a Date that arrived as text (e.g. from a worker), or an ISO / HTTP date
                else -> e.toString().trim().let { t -> t.toLongOrNull() ?: parseDate(t) }
                    ?: throw MiniAppError("TypeError", "\"expires\" must be a Date or epoch ms (got $e)")
            }
            else -> now + MiniAppCookies.DEFAULT_DAYS * 86_400_000L
        }
        return when (m) {
            "cookies.list" -> jar.toJson(if (url != null) jar.forRequest(url) else jar.all())
            "cookies.set" -> {
                url ?: throw MiniAppError("TypeError", "\"url\" is required")
                val b = runCatching {
                    okhttp3.Cookie.Builder().name(p.getString("name")).value(p.optString("value")).path(p.optString("path").ifBlank { "/" }).expiresAt(expiry())
                }.getOrElse { throw MiniAppError("TypeError", "Bad cookie: ${it.message}") }
                val domain = p.optString("domain").trimStart('.')
                if (domain.isNotEmpty()) {
                    if (url.host != domain && !url.host.endsWith(".$domain")) throw MiniAppError("NotAllowedError", "$domain doesn't match ${url.host}")
                    b.domain(domain)
                } else b.hostOnlyDomain(url.host)
                if (p.optBoolean("secure")) b.secure()
                if (p.optBoolean("httpOnly")) b.httpOnly()
                val c = b.build()
                jar.save(listOf(c)); jar.toJson(listOf(c)).optJSONObject(0)
            }
            "cookies.import" -> {
                // a "Cookie:" header copied from a browser: "a=1; b=2"
                url ?: throw MiniAppError("TypeError", "\"url\" is required")
                val until = now + (if (p.has("days")) p.optLong("days") else MiniAppCookies.DEFAULT_DAYS.toLong()) * 86_400_000L
                val list = p.optString("header").removePrefix("Cookie:").split(';').mapNotNull { part ->
                    val k = part.substringBefore('=').trim(); val v = part.substringAfter('=', "").trim()
                    if (k.isEmpty()) null else runCatching { okhttp3.Cookie.Builder().name(k).value(v).hostOnlyDomain(url.host).path("/").expiresAt(until).build() }.getOrNull()
                }
                jar.save(list); list.size
            }
            "cookies.delete" -> jar.delete(url?.host, p.optString("name").ifBlank { null }, p.optString("path").ifBlank { null })
            else -> jar.delete(url?.host, null)
        }
    }

    /** Each server's tools, once per page: the checks need their names and read-only hints. */
    private val mcpTools = HashMap<String, JSONArray>()

    private suspend fun mcpRaw(server: String, list: Boolean, tool: String = "", args: JSONObject = JSONObject()): Any? {
        if (server.startsWith("extension_")) {
            val ext = BrowserExtensionManager.instance
            val id = server.removePrefix("extension_")
            return withContext(Dispatchers.IO) { if (list) ext.tools(id) else ext.callTool(id, tool, args) }
        }
        val req = JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("method", if (list) "tools/list" else "tools/call")
            .put("params", if (list) JSONObject() else JSONObject().put("name", tool).put("arguments", args))
        val res = withContext(Dispatchers.IO) { com.example.gemini.data.remote.AndroidLocalBridgeServer.instance.callMcpInProcess(ctx, server, req) }
            ?: throw MiniAppError("NotFoundError", "No MCP server named $server")
        res.optJSONObject("error")?.let { throw MiniAppError("OperationError", it.optString("message")) }
        val result = res.optJSONObject("result") ?: JSONObject()
        return if (list) result.optJSONArray("tools") ?: JSONArray() else result
    }

    /**
     * AntiGem.mcp: only what the manifest declared and the user allowed (the whole server, its read-only tools, or
     * single tools), and, where asked for, the user's OK before each call.
     */
    private suspend fun mcp(m: String, p: JSONObject): Any? {
        val server = p.optString("server").trim()
        if (server in MiniAppMcpAccess.BLOCKED) throw MiniAppError("NotAllowedError", "$server can't be used by mini apps")
        if (!MiniAppMcpAccess.declaresServer(app, server)) throw MiniAppError("NotAllowedError", "\"$server\" isn't in the manifest's mcp permissions")
        val all = mcpTools[server] ?: (mcpRaw(server, list = true) as? JSONArray ?: JSONArray()).also { mcpTools[server] = it }
        if (m == "mcp.list") return MiniAppMcpAccess.allowedTools(ctx, app, server, all)
        val tool = p.optString("tool").trim()
        val def = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.firstOrNull { it.optString("name") == tool }
            ?: throw MiniAppError("NotFoundError", "$server has no tool \"$tool\"")
        val perm = MiniAppMcpAccess.permissionFor(app, server, tool)
            ?: throw MiniAppError("NotAllowedError", "\"$tool\" of $server isn't in the manifest (declare it: \"mcp\": {\"$server\": [\"$tool\"]})")
        var state = MiniAppPermissions.state(ctx, app, perm)
        if (state == MiniAppPermissions.State.PROMPT) state = host.requestPermission(app, perm)
        if (state != MiniAppPermissions.State.GRANTED) throw MiniAppError("NotAllowedError", "\"$perm\" permission is ${state.js}")
        if (!MiniAppMcpAccess.allows(ctx, app, server, def)) throw MiniAppError("NotAllowedError", "$tool changes things: ${app.name} may only use $server's read-only tools")
        val args = p.optJSONObject("args") ?: JSONObject()
        if (MiniAppMcpAccess.asks(ctx, app, server, tool)) {
            val answer = runCatching { host.showDialog(NativeDialog.McpCall(app, server, tool, def.optString("description"), args.toString(2))) }
                .getOrElse { throw MiniAppError("NotAllowedError", "$tool needs your OK before each call, which a background task can't ask for (App info → Ask before each call)") }
            when (answer) {
                "always" -> MiniAppMcpAccess.allowAlways(ctx, app, server, tool)
                "once" -> {}
                else -> throw MiniAppError("NotAllowedError", "The user didn't allow $tool")
            }
        }
        return mcpRaw(server, list = false, tool = tool, args = args)
    }

    private fun haptic(kind: String) {
        val c = when (kind) {
            "medium" -> HapticFeedbackConstants.VIRTUAL_KEY
            "heavy" -> HapticFeedbackConstants.LONG_PRESS
            "selection" -> HapticFeedbackConstants.CLOCK_TICK
            "success" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            "warning", "error" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        webView.performHapticFeedback(c)
    }

    private fun share(p: JSONObject) {
        val text = listOf(p.optString("text"), p.optString("url")).filter { it.isNotBlank() }.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        p.optString("title").takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        host.startActivity(Intent.createChooser(send, p.optString("title").ifBlank { null }))
    }

    private fun clipboard() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Opening a link hands data to the outside world: apps without the network permission ask the user first. */
    private suspend fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https", "mailto", "tel")) throw MiniAppError("TypeError", "Only http(s), mailto and tel links")
        if (!MiniAppPermissions.isGranted(ctx, app, "network")) {
            val ok = host.showDialog(NativeDialog.Confirm("Open link?", "${app.name} wants to open:\n$url", "Open", "Cancel", false)) == true
            if (!ok) throw MiniAppError("NotAllowedError", "The user did not open the link")
        }
        host.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    // ---------------------------------------------------------------- clients

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "https" && u.host == app.host) return false
            // leaving the app: only a tap on a link may do it, and it opens outside
            if (request.isForMainFrame && request.hasGesture()) {
                scope.launch { runCatching { openUrl(u.toString()) }.onFailure { log("warn", "Did not open $u: ${it.message}") } }
            } else log("warn", "Blocked navigation to $u")
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { ready = false }
        override fun onPageCommitVisible(view: WebView, url: String) = host.onPageVisible(this@MiniAppPage)
        override fun onPageFinished(view: WebView, url: String) {
            host.onPageVisible(this@MiniAppPage)
            ready = true
            flushPendingEvents()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            log("error", "The page crashed (renderer ${if (detail.didCrash()) "crashed" else "was killed"}); reopening")
            host.onRendererGone(this@MiniAppPage)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            val level = when (m.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                else -> "log"
            }
            val src = m.sourceId().orEmpty().substringAfter(app.host).ifBlank { m.sourceId().orEmpty() }
            MiniAppConsole.add(ctx, app.id, level, m.message(), if (src.isBlank()) "" else "$src:${m.lineNumber()}")
            return true
        }

        // In a background task there is nobody to answer: alert() returns at once, confirm() and prompt() cancel
        // (instead of the page waiting until the task's time limit).
        private suspend fun dialog(d: NativeDialog, what: String): Any? = runCatching { host.showDialog(d) }
            .onFailure { log("warn", "$what() isn't available here (${it.message}): it returned at once") }.getOrNull()

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            scope.launch { dialog(NativeDialog.Alert(app.name, message.orEmpty(), "OK"), "alert"); result.confirm() }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            scope.launch { if (dialog(NativeDialog.Confirm(app.name, message.orEmpty(), "OK", "Cancel", false), "confirm") == true) result.confirm() else result.cancel() }
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            scope.launch {
                val v = dialog(NativeDialog.Prompt(app.name, message.orEmpty(), defaultValue.orEmpty(), "", "OK", "Cancel"), "prompt")
                if (v is String) result.confirm(v) else result.cancel()
            }
            return true
        }

        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val accept = params.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { t ->
                if (t.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.drop(1)) ?: "*/*" else t
            } ?: "*/*"
            scope.launch {
                // <input capture>: the camera, when the app declares "camera" and the user allows it
                val camera = params.isCaptureEnabled && (accept.startsWith("image/") || accept.startsWith("video/") || accept == "*/*") && run {
                    val ok = host.requestPermission(app, "camera") == MiniAppPermissions.State.GRANTED &&
                        host.androidPermissions(arrayOf(android.Manifest.permission.CAMERA))
                    if (!ok) log("warn", "<input capture> needs the \"camera\" permission: the file picker opened instead")
                    ok
                }
                val uris = if (camera) listOfNotNull(runCatching { host.capture(video = accept.startsWith("video/")) }.getOrNull())
                    else runCatching { host.pickFiles(accept, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) }.getOrDefault(emptyList())
                callback.onReceiveValue(uris.toTypedArray())
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = request.resources.mapNotNull {
                when (it) { PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"; else -> null }
            }
            if (wanted.isEmpty() || wanted.size != request.resources.size) return request.deny()
            scope.launch {
                val ok = wanted.all { host.requestPermission(app, it) == MiniAppPermissions.State.GRANTED } &&
                    host.androidPermissions(wanted.map { if (it == "camera") android.Manifest.permission.CAMERA else android.Manifest.permission.RECORD_AUDIO }.toTypedArray())
                if (ok) request.grant(request.resources) else request.deny()
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)

        // no grey "play" image before a video starts
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.showFullscreen(view, callback)
        override fun onHideCustomView() = host.hideFullscreen()
    }

    /** The SDK files from assets, read once. */
    private object Sdk {
        private val cache = HashMap<String, String>()
        fun asset(context: Context, name: String): String = synchronized(cache) {
            cache.getOrPut(name) { runCatching { context.assets.open("miniapp-sdk/$name").bufferedReader().use { it.readText() } }.getOrDefault("") }
        }
        fun bundle(context: Context) = listOf("core.js", "net.js", "idb.js", "threads.js").joinToString("\n;\n") { asset(context, it) }
        fun workerBundle(context: Context) = listOf("worker-core.js", "net.js", "idb.js", "threads.js").joinToString("\n;\n") { asset(context, it) }
    }
}
