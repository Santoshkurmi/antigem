package ai.antigem.ui.ide

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import ai.antigem.ui.components.BlockSite
import ai.antigem.ui.components.DiskSite
import ai.antigem.ui.components.siteError
import java.io.ByteArrayInputStream

/** The files the IDE's eye button shows as a web page. */
internal val HTML_PREVIEW_EXTS = setOf("html", "htm")

/**
 * An HTML tab rendered as a page. The page itself is the tab's text (unsaved edits included); the files it links to
 * (style.css, app.js, pictures, other pages) are read from its folder through the bridge, like an ```html_app card
 * that names a page on disk. Each file gets its own made-up address, so its localStorage and cookies stay its own.
 * A tab with no folder (a file shared from another app, an old revision) shows only its own text.
 */
@Composable
internal fun HtmlFilePreview(path: String, html: String, modifier: Modifier = Modifier) {
    val origin = remember(path) { "https://h" + Integer.toHexString(path.hashCode()) + Integer.toHexString(path.length) + ".antigravity.local/" }
    val site: BlockSite? = remember(path) { if (path.startsWith("/")) DiskSite(path) else null }
    val entry = path.substringAfterLast('/')
    val pageKey = "$path:${html.hashCode()}:${html.length}"
    // a crashed page renderer: a new view takes over instead of the app going down
    var generation by remember { mutableIntStateOf(0) }

    key(generation) {
        AndroidView(
            factory = { ctx -> newPreviewWebView(ctx) { generation++ } },
            update = { view ->
                val client = view.tag as? HtmlPreviewClient
                if (client != null) {
                    client.origin = origin
                    client.site = site
                    client.entry = entry
                    client.html = html
                    // loaded again only when the file or its text changed
                    if (client.pageKey != pageKey) {
                        client.pageKey = pageKey
                        if (site != null) view.loadUrl(origin + Uri.encode(entry))
                        else view.loadDataWithBaseURL(origin, html, "text/html", "UTF-8", null)
                    }
                }
            },
            onRelease = { it.destroy() },
            // Compose doesn't clip a native view: without this the page could draw over the path bar and tabs above it
            modifier = modifier.clipToBounds()
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun newPreviewWebView(ctx: android.content.Context, onGone: () -> Unit): WebView = WebView(ctx).apply {
    // Android 12+'s pull-past-the-end stretch scales the whole view, past its own box: over the tabs until it springs back
    overScrollMode = View.OVER_SCROLL_NEVER
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = false
        cacheMode = WebSettings.LOAD_NO_CACHE
    }
    webChromeClient = WebChromeClient()
    val client = HtmlPreviewClient(onGone)
    webViewClient = client
    tag = client
}

/**
 * Serves the page at its own address: the page from the editor's text, every other file of that address from the
 * page's folder. The page cannot leave its address; a web link the user taps opens in the browser.
 */
private class HtmlPreviewClient(private val onGone: () -> Unit) : WebViewClient() {
    @Volatile var origin: String = ""
    @Volatile var site: BlockSite? = null
    @Volatile var entry: String = ""
    @Volatile var html: String = ""
    var pageKey: String? = null

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!request.url.toString().startsWith(origin)) return null
        val s = site ?: return null
        val path = (request.url.path ?: "").removePrefix("/")
        if (path.isEmpty() || path == entry) {
            return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        return runCatching { s.serve(path, request) }.getOrElse { siteError(500, it.message ?: "Error") }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url
        if (url.toString().startsWith(origin)) return false
        val scheme = url.scheme?.lowercase()
        if (request.hasGesture() && (scheme == "http" || scheme == "https")) {
            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return true
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone()
        return true
    }
}
