package ai.antigem.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import ai.antigem.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import ai.antigem.i18n.L

class InteractiveUiJsBridge(
    private val context: Context,
    /** The block's WebView: copying needs a tap on it first (a block can't fill the clipboard on its own). */
    webView: android.view.View? = null,
    private val onToast: (String) -> Unit
) {
    private val touch = TouchClock().also { c -> webView?.let { c.attach(it) } }

    @JavascriptInterface
    fun showToast(message: String) {
        onToast(message)
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        if (!touch.recent()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Interactive UI", text)
        clipboard.setPrimaryClip(clip)
        onToast(L.components.interactiveUiView.copiedToClipboard)
    }
}

/**
 * An ```html_app card. One page ([htmlCode]), a site of several files written in the chat ([files], opened at
 * [entry]), or a site on disk ([sitePath], read through the bridge). [complete]: false while the block (or one of its
 * files) is still streaming in: the card waits instead of reloading the page on every token.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveUiView(
    htmlCode: String,
    title: String = L.components.interactiveUiView.interactiveApp,
    files: Map<String, String> = emptyMap(),
    entry: String = "",
    sitePath: String = "",
    complete: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    var isFullscreen by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = App, 1 = Code
    var reloadKey by remember { mutableIntStateOf(0) }
    var isCopied by remember { mutableStateOf(false) }
    // "Run now" on the waiting card (e.g. a reply that stopped before its closing ```)
    var runAnyway by remember { mutableStateOf(false) }
    val ready = complete || runAnyway
    val multi = files.isNotEmpty() && entry.isNotEmpty()
    val onDisk = sitePath.isNotEmpty()

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    // each block its own origin, so one chat's block can't read another's localStorage or cookies. From its code (a
    // site: its first page, so a file arriving later keeps the origin), or from its path on disk (stable across edits)
    val blockOrigin = remember(htmlCode, sitePath) {
        if (onDisk) "https://d" + Integer.toHexString(sitePath.hashCode()) + Integer.toHexString(sitePath.length) + ".antigravity.local/"
        else "https://b" + Integer.toHexString(htmlCode.hashCode()) + Integer.toHexString(htmlCode.length) + ".antigravity.local/"
    }
    val fullHtml = remember(htmlCode, isDark, reloadKey) { themedPage(htmlCode, isDark) }
    val site: BlockSite? = remember(files, entry, sitePath, isDark) {
        when {
            onDisk -> DiskSite(sitePath)
            multi -> InlineSite(files, entry) { themedPage(it, isDark) }
            else -> null
        }
    }
    val pageKey = "$reloadKey:$isDark:" + when {
        onDisk -> sitePath
        multi -> "${files.hashCode()}:${files.size}"
        else -> "${fullHtml.hashCode()}:${fullHtml.length}"
    }
    val load: (WebView) -> Unit = { view ->
        if (site != null) view.loadUrl(blockOrigin + site.startUrlPath)
        else view.loadDataWithBaseURL(blockOrigin, fullHtml, "text/html", "UTF-8", null)
    }
    val subtitle = when {
        onDisk -> sitePath.substringAfterLast('/')
        multi -> L.components.interactiveUiView.countFiles(files.size)
        else -> null
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Icon(
                        imageVector = Icons.Default.SmartButton,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (subtitle != null) {
                        Text(
                            text = " · $subtitle",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(6.dp))
                    // Tab Selector: App vs Code
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedTab == 0) ClaudeTerracotta else Color.Transparent)
                                .selectable(selected = selectedTab == 0, role = Role.Tab) { selectedTab = 0 }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = L.components.interactiveUiView.app,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 0) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedTab == 1) ClaudeTerracotta else Color.Transparent)
                                .selectable(selected = selectedTab == 1, role = Role.Tab) { selectedTab = 1 }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = L.common.code,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 1) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Reload Button
                    IconButton(
                        onClick = { reloadKey++ },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = L.components.interactiveUiView.restartApp,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Fullscreen / Expand Button
                    IconButton(
                        onClick = {
                            runAnyway = true
                            isFullscreen = true
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = L.components.interactiveUiView.openFullscreen,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Body Area
            if (selectedTab == 0 && !ready) {
                // still streaming in: one load at the end instead of one per token
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = ClaudeTerracotta
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = L.components.interactiveUiView.buildingTheApp,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    if (multi) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = files.keys.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { runAnyway = true }) {
                        Text(L.components.interactiveUiView.runNow, color = ClaudeTerracotta, fontSize = 12.sp)
                    }
                }
            } else if (selectedTab == 0) {
                // Interactive Web App Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                ) {
                    // kept alive while the chat is open (ChatWebViewCache): scrolling back keeps the app's state
                    KeptWebView(
                        part = "app",
                        factory = { ctx -> newBlockWebView(ctx, blockOrigin, kept = true, zoom = false) },
                        update = { view -> updateBlockWebView(view, blockOrigin, site, pageKey, load) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                // Source Code Inspector
                Column(modifier = Modifier.fillMaxWidth()) {
                    when {
                        multi -> {
                            var shown by remember(files.keys) { mutableStateOf(entry) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                files.keys.forEach { name ->
                                    val on = name == shown
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (on) ClaudeTerracotta else MaterialTheme.colorScheme.surface)
                                            .clickable { shown = name }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = name,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (on) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                        )
                                    }
                                }
                            }
                            CodeBlock(code = files[shown] ?: files.getValue(entry), language = siteLanguage(shown))
                        }
                        onDisk -> CodeBlock(code = sitePath, language = "text")
                        else -> CodeBlock(code = htmlCode, language = "html")
                    }
                }
            }
        }
    }

    // Fullscreen Immersive Dialog for Games & Large Apps
    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Fullscreen Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            Icon(
                                imageVector = Icons.Default.SmartButton,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = title + (subtitle?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { reloadKey++ }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = L.components.interactiveUiView.restartApp,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = { isFullscreen = false }) {
                                Icon(
                                    imageVector = Icons.Default.FullscreenExit,
                                    contentDescription = L.components.interactiveUiView.exitFullscreen,
                                    tint = ClaudeTerracotta
                                )
                            }
                        }
                    }

                    // Fullscreen WebView Canvas
                    Box(modifier = Modifier.fillMaxSize()) {
                        key(reloadKey, isDark) {
                            AndroidView(
                                factory = { ctx -> newBlockWebView(ctx, blockOrigin, kept = false, zoom = true) },
                                update = { view -> updateBlockWebView(view, blockOrigin, site, pageKey, load) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A one-page block (or a site's .html fragment) as a full page, with the chat's theme colours as CSS variables. */
private fun themedPage(code: String, isDark: Boolean): String {
    if (code.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) || code.trimStart().startsWith("<html", ignoreCase = true)) {
        return code
    }
    val primaryHex = if (isDark) "#E07A5F" else "#D97757"
    val bgHex = if (isDark) "#1E1E1E" else "#F7F7F8"
    val textHex = if (isDark) "#ECECF1" else "#1A1A1A"
    val cardBgHex = if (isDark) "#2A2A2A" else "#FFFFFF"
    val borderHex = if (isDark) "#3A3A3A" else "#E5E5E5"
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
            <style>
                :root {
                    --primary: $primaryHex;
                    --bg: $bgHex;
                    --text: $textHex;
                    --card-bg: $cardBgHex;
                    --border: $borderHex;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    background-color: var(--bg);
                    color: var(--text);
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                    padding: 12px;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                }
                button, input, select, textarea {
                    font-family: inherit;
                }
            </style>
        </head>
        <body>
            $code
        </body>
        </html>
    """.trimIndent()
}

/** What a block's WebView serves and shows now: a kept view outlives the composition that made it. */
private class BlockState(val client: BlockClient) {
    var pageKey: String? = null
}

@SuppressLint("SetJavaScriptEnabled")
private fun newBlockWebView(ctx: Context, origin: String, kept: Boolean, zoom: Boolean): WebView = WebView(ctx).apply {
    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = zoom
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_NO_CACHE
    }
    webChromeClient = WebChromeClient()
    val client = BlockClient(origin, kept)
    webViewClient = client
    tag = BlockState(client)
    installBlockBridge(this, ctx, origin)
}

/** Points the view at the current files and loads the page only when it changed (a kept view comes back running). */
private fun updateBlockWebView(view: WebView, origin: String, site: BlockSite?, pageKey: String, load: (WebView) -> Unit) {
    val state = view.tag as? BlockState
    if (state == null) {
        load(view)
        return
    }
    state.client.origin = origin
    state.client.site = site
    if (state.pageKey != pageKey) {
        state.pageKey = pageKey
        load(view)
    }
}

/**
 * The block page stays the block page: it can't navigate itself (or be navigated by a script) to another site, where
 * it would keep the antiGemini bridge. A web link the user taps opens in the browser; any other scheme is dropped.
 * Frames inside the block load as before. [kept]: the chat's own view (a dead renderer is handed to the cache).
 * [site]: a site of several files, served at [origin] (pages of the site navigate inside the block).
 */
private class BlockClient(@Volatile var origin: String, private val kept: Boolean) : WebViewClient() {
    @Volatile var site: BlockSite? = null

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val s = site ?: return null
        if (!request.url.toString().startsWith(origin)) return null
        val path = (request.url.path ?: "").removePrefix("/")
        return runCatching { s.serve(path, request) }.getOrElse { siteError(500, it.message ?: "Error") }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url
        if (url.toString().startsWith(origin)) return false
        val scheme = url.scheme?.lowercase()
        if (request.hasGesture() && (scheme == "http" || scheme == "https")) {
            runCatching {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return true
    }

    // the chat's view: a dead renderer would otherwise crash the app (the fullscreen view keeps WebView's default)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (!kept) return super.onRenderProcessGone(view, detail)
        ChatWebViewCache.onRendererGone(view)
        return true
    }
}

/**
 * window.antiGemini (showToast, copyToClipboard) for the block's own page only. Where the WebView can, it is a message
 * channel limited to the block's origin and its top frame, so iframes inside the block (other sites, or srcdoc) never
 * get it; old WebViews fall back to the JavascriptInterface (every frame, but it only toasts, and copies after a tap).
 */
private fun installBlockBridge(view: WebView, context: Context, origin: String) {
    val bridge = InteractiveUiJsBridge(context, view) { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    val rule = setOf(origin.trimEnd('/'))
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER) &&
        androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
    ) {
        androidx.webkit.WebViewCompat.addWebMessageListener(view, "__agBlock", rule) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            val o = runCatching { org.json.JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            when (o.optString("m")) {
                "toast" -> bridge.showToast(o.optString("t"))
                "copy" -> bridge.copyToClipboard(o.optString("t"))
            }
        }
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
            view,
            "window.antiGemini={showToast:function(t){__agBlock.postMessage(JSON.stringify({m:'toast',t:String(t)}))}," +
                "copyToClipboard:function(t){__agBlock.postMessage(JSON.stringify({m:'copy',t:String(t)}))}};",
            rule
        )
    } else {
        view.addJavascriptInterface(bridge, "antiGemini")
    }
}
