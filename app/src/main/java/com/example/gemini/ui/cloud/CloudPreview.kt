package com.example.gemini.ui.cloud

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.agent.agy.AgyCloud
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.theme.GeminiBlue
import kotlinx.coroutines.runBlocking

/** The address the preview page is served from; nothing goes to the internet for it (every request is intercepted). */
private const val PREVIEW_HOST = "preview.cloud.antigem"
private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0")

/** Ports a web server in the chat probably listens on (from the agent's commands and their output), most recent first. */
fun cloudPreviewPorts(messages: List<ChatMessage>): List<Int> {
    val patterns = listOf(
        Regex("""(?:--port|-p|PORT=|--listen)[ =]?(\d{2,5})\b"""),
        Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0):(\d{2,5})\b"""),
        Regex("""http\.server\s+(\d{2,5})\b"""),
        Regex("""(?:port|Port|PORT)\s+(\d{2,5})\b""")
    )
    val found = LinkedHashSet<Int>()
    messages.asReversed().forEach { m ->
        m.toolCalls.asReversed().forEach { t ->
            val text = t.command + "\n" + t.output.takeLast(4000)
            patterns.forEach { re -> re.findAll(text).forEach { r -> r.groupValues[1].toIntOrNull()?.takeIf { it in 80..65535 }?.let(found::add) } }
        }
    }
    return found.toList().take(6)
}

/**
 * A web app running in the cloud chat's VM, shown in the app: every request of the page goes through the hub to the
 * chosen port in the VM (and `localhost:<other port>` calls of the page to that port). Hot reload does not work (it
 * needs a WebSocket); reload to see changes.
 */
private val DEFAULT_PORTS = listOf(5173, 3000, 8080, 8000, 4200, 5000)

/**
 * Before a preview: which port, and where to show it (in the app, or in the system browser through
 * [com.example.gemini.data.agent.agy.CloudPreviewServer] on this phone).
 */
@Composable
fun CloudPreviewChooser(
    suggestedPorts: List<Int>,
    onOpenInApp: (Int) -> Unit,
    onOpenInBrowser: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val ports = remember(suggestedPorts) { (suggestedPorts + DEFAULT_PORTS).distinct().take(8) }
    var portText by remember { mutableStateOf(ports.first().toString()) }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Cloud, null, tint = GeminiBlue) },
        title = { Text("Preview the cloud VM") },
        text = {
            Column {
                Text(
                    if (suggestedPorts.isNotEmpty()) "Ports from the agent's commands are marked with a dot."
                    else "Pick the port your server listens on (ask the agent to start it on 0.0.0.0).",
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ports) { p ->
                        val on = p == port
                        Surface(
                            onClick = { portText = p.toString() },
                            shape = RoundedCornerShape(50),
                            color = if (on) GeminiBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        ) {
                            Text(
                                ":$p" + if (p in suggestedPorts) " ·" else "",
                                fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                                color = if (on) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "In the browser it opens as http://127.0.0.1:… on this phone. Live reload does not work: reload to see changes.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = port != null, onClick = { port?.let(onOpenInApp) }) { Text("Open in app") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(enabled = port != null, onClick = { port?.let(onOpenInBrowser) }) { Text("Open in browser") }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudPreviewDialog(conversationId: String, suggestedPorts: List<Int>, initialPort: Int? = null, onDismiss: () -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val ports = remember(suggestedPorts, initialPort) { (listOfNotNull(initialPort) + suggestedPorts + DEFAULT_PORTS).distinct().take(8) }
    var port by remember { mutableIntStateOf(ports.first()) }
    var portText by remember { mutableStateOf(port.toString()) }
    var path by remember { mutableStateOf("/") }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val client = remember { SandboxClient(conversationId) { loading = it } }
    client.port = port

    fun open() {
        val p = path.trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        webView?.loadUrl("https://$PREVIEW_HOST$p")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                    Icon(Icons.Outlined.Cloud, null, tint = GeminiBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Preview", fontSize = 16.sp, modifier = Modifier.weight(1f))
                    // the same page in the system browser (served on this phone through the hub)
                    IconButton(onClick = {
                        val p = path.trim().ifBlank { "/" }.removePrefix("/")
                        uriHandler.openUri(com.example.gemini.data.agent.agy.CloudPreviewServer.urlFor(conversationId, port) + p)
                    }) { Icon(Icons.Outlined.OpenInBrowser, "Open in browser") }
                    IconButton(onClick = { reloadKey++; open() }) { Icon(Icons.Outlined.Refresh, "Reload") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { portText.toIntOrNull()?.let { port = it; client.port = it; open() } }),
                        modifier = Modifier.width(96.dp)
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("Path") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { portText.toIntOrNull()?.let { port = it; client.port = it }; open() }),
                        modifier = Modifier.weight(1f)
                    )
                }
                LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ports) { p ->
                        val on = p == port
                        Surface(
                            onClick = { port = p; portText = p.toString(); client.port = p; open() },
                            shape = RoundedCornerShape(50),
                            color = if (on) GeminiBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        ) {
                            Text(
                                ":$p" + if (p in suggestedPorts) " ·" else "",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = if (on) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Box(Modifier.height(2.dp).fillMaxWidth()) { if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = GeminiBlue) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            // the page is https://preview…; calls of the page to http://localhost:<port> are served too
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            webViewClient = client
                            webView = this
                            loadUrl("https://$PREVIEW_HOST/")
                        }
                    }
                )
            }
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy(); webView = null } }
}

/** Serves the preview page's requests from the cloud VM (WebView asks on a background thread). */
private class SandboxClient(private val conversationId: String, private val onLoading: (Boolean) -> Unit) : WebViewClient() {
    @Volatile var port: Int = 5173

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) = onLoading(true)
    override fun onPageFinished(view: WebView?, url: String?) = onLoading(false)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host ?: return null
        val targetPort = when {
            host == PREVIEW_HOST -> port
            host in LOCAL_HOSTS -> if (url.port > 0) url.port else 80
            else -> return null // other sites (CDNs, fonts) load from the internet as usual
        }
        val path = (url.encodedPath?.ifBlank { "/" } ?: "/") + (url.encodedQuery?.let { "?$it" } ?: "")
        // no conditional requests: a 304 cannot be handed to WebView from here
        val skip = setOf("host", "origin", "referer", "accept-encoding", "connection", "if-none-match", "if-modified-since")
        val headers = request.requestHeaders.filterKeys { it.lowercase() !in skip } + ("Accept-Encoding" to "identity")
        val result = runBlocking { AgyCloud.fetchPort(conversationId, targetPort, path, request.method ?: "GET", headers) }
        return result.fold(
            onSuccess = { r -> toResponse(r, request.isForMainFrame) },
            onFailure = { e -> page(502, "Could not reach port $targetPort in the cloud VM", e.message ?: "", request.isForMainFrame) }
        )
    }

    private fun toResponse(r: exa.language_server_pb.FetchSandboxPortResponse, mainFrame: Boolean): WebResourceResponse {
        val hdrs = LinkedHashMap<String, String>()
        r.headers.forEach { h ->
            val k = h.name
            if (k.lowercase() in setOf("content-length", "content-encoding", "transfer-encoding", "connection")) return@forEach
            hdrs[k] = hdrs[k]?.let { "$it, ${h.value_}" } ?: h.value_
        }
        val status = r.status_code
        if (status in 300..399) {
            // WebView cannot take a redirect from here: send the page there instead
            val loc = hdrs.entries.firstOrNull { it.key.equals("location", true) }?.value.orEmpty()
            val target = runCatching {
                val u = Uri.parse(loc)
                if (u.host == null || u.host in LOCAL_HOSTS) "https://$PREVIEW_HOST" + (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "") else loc
            }.getOrDefault("/")
            return html(200, "<meta http-equiv=\"refresh\" content=\"0;url=${target.replace("\"", "%22")}\">")
        }
        val type = hdrs.entries.firstOrNull { it.key.equals("content-type", true) }?.value.orEmpty()
        val mime = type.substringBefore(';').trim().ifBlank { "application/octet-stream" }
        val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()
        val code = if (status in 100..299 || status in 400..599) status else 200
        if (r.truncated && mainFrame) android.util.Log.w("AgyCloud", "preview body truncated")
        return WebResourceResponse(mime, charset, code, reason(code), hdrs, r.body.toByteArray().inputStream())
    }

    private fun page(code: Int, title: String, detail: String, mainFrame: Boolean): WebResourceResponse =
        if (!mainFrame) WebResourceResponse("text/plain", "utf-8", code, reason(code), emptyMap(), detail.byteInputStream())
        else html(
            code,
            "<html><body style=\"font-family:sans-serif;padding:24px;color:#444\"><h3>$title</h3>" +
                "<p>${detail.replace("<", "&lt;")}</p><p>Is the server running? Ask the agent to start it " +
                "(listening on 0.0.0.0), then reload.</p></body></html>"
        )

    private fun html(code: Int, body: String) =
        WebResourceResponse("text/html", "utf-8", code, reason(code), emptyMap(), body.byteInputStream())

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "Status"
    }
}
