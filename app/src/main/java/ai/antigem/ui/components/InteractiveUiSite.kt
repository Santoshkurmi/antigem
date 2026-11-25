package ai.antigem.ui.components

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.AgyBridgeService
import java.io.ByteArrayInputStream
import java.net.URLEncoder

/**
 * The files of an html_app card with several files, served at the card's own made-up address
 * (https://<id>.antigravity.local/…): relative links, ES module imports and fetch('data.json') then just work.
 * Requests come from WebView's IO thread, so [serve] may block.
 */
internal sealed class BlockSite {
    /** The page to open first, relative to the card's address. */
    abstract val start: String

    /** [path]: the request's path without its leading "/" ("" = the start page). */
    abstract fun serve(path: String, request: WebResourceRequest): WebResourceResponse

    /** [start] as a URL path (each part encoded). */
    val startUrlPath: String get() = start.split('/').joinToString("/") { Uri.encode(it) }
}

/** Files written in the chat (```html_app:index.html, ```css:style.css …). [themed]: wraps an HTML fragment into a full page. */
internal class InlineSite(
    private val files: Map<String, String>,
    private val entry: String,
    private val themed: (String) -> String
) : BlockSite() {
    override val start = entry

    override fun serve(path: String, request: WebResourceRequest): WebResourceResponse {
        val p = when {
            path.isEmpty() -> entry
            path.endsWith("/") -> path + "index.html"
            else -> path
        }
        val name = listOf(p, "$p.html", "$p/index.html").firstOrNull { it in files } ?: return siteError(404, "Not in this app: $p")
        val text = files.getValue(name)
        val body = if (name.endsWith(".html", true) || name.endsWith(".htm", true)) themed(text) else text
        return siteResponse(siteMime(name), 200, "OK", ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
    }
}

/**
 * A site on disk (```html_app with only /abs/path/page.html): each file is read through the bridge's /api/fs/site,
 * which serves only files inside the page's folder. A bridge without that route (older, or another one) is read
 * through /api/fs/raw with the same checks done here.
 */
internal class DiskSite(htmlPath: String) : BlockSite() {
    private val root = htmlPath.substringBeforeLast('/').ifEmpty { "/" }
    override val start = htmlPath.substringAfterLast('/')

    override fun serve(path: String, request: WebResourceRequest): WebResourceResponse {
        val rel = when {
            path.isEmpty() -> start
            path.endsWith("/") -> path + "index.html"
            else -> path
        }
        if (rel.split('/').any { it == ".." || it.startsWith(".") }) return siteError(403, "Not served: $rel")
        val base = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
        return runCatching {
            val resp = fetch("$base/api/fs/site?root=${enc(root)}&path=${enc(rel)}", request)
            if (resp.code == 404 && resp.header("X-Antigem-Site") == null) {
                resp.close()
                toResponse(fetch("$base/api/fs/raw?path=${enc("$root/$rel")}", request), rel)
            } else toResponse(resp, rel)
        }.getOrElse { siteError(502, "Could not reach the bridge: ${it.message}") }
    }

    private fun fetch(url: String, request: WebResourceRequest): okhttp3.Response {
        val b = okhttp3.Request.Builder().url(url).get()
        // media seeking
        request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.let { b.header("Range", it.value) }
        return AgyBridgeService.instance.client.newCall(b.build()).execute()
    }

    private fun toResponse(resp: okhttp3.Response, rel: String): WebResourceResponse {
        if (!resp.isSuccessful) {
            val msg = runCatching { org.json.JSONObject(resp.body?.string().orEmpty()).optString("error") }.getOrNull()
            val code = resp.code
            resp.close()
            return siteError(if (code in 300..399) 502 else code, msg?.ifBlank { null } ?: "Could not load $rel")
        }
        val type = resp.header("Content-Type")?.takeIf { !it.startsWith("application/octet-stream") } ?: siteMime(rel)
        val headers = buildMap {
            put("Cache-Control", "no-store")
            resp.header("Content-Range")?.let { put("Content-Range", it) }
            resp.header("Accept-Ranges")?.let { put("Accept-Ranges", it) }
            resp.header("Content-Length")?.let { put("Content-Length", it) }
        }
        val body = resp.body ?: return siteError(502, "Empty answer for $rel")
        // the WebView closes the stream when it is done, which closes the response
        return WebResourceResponse(
            type.substringBefore(';').trim(),
            charsetOf(type),
            resp.code,
            resp.message.ifBlank { "OK" },
            headers,
            body.byteStream()
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

private val SITE_TYPES = mapOf(
    "html" to "text/html", "htm" to "text/html", "css" to "text/css",
    "js" to "text/javascript", "mjs" to "text/javascript", "cjs" to "text/javascript",
    "json" to "application/json", "map" to "application/json", "webmanifest" to "application/manifest+json",
    "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "avif" to "image/avif", "ico" to "image/x-icon",
    "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "mp4" to "video/mp4", "webm" to "video/webm",
    "wasm" to "application/wasm", "txt" to "text/plain", "md" to "text/plain", "xml" to "application/xml",
    "csv" to "text/csv", "glsl" to "text/plain", "vert" to "text/plain", "frag" to "text/plain"
)

/** The content type of a file by its extension (text types say UTF-8). */
internal fun siteMime(name: String): String {
    val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    val type = SITE_TYPES[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    val text = type.startsWith("text/") || type == "application/json" || type == "image/svg+xml" || type == "application/xml"
    return if (text) "$type; charset=utf-8" else type
}

/** The code language for a file's Code tab. */
internal fun siteLanguage(name: String): String = when (val ext = name.substringAfterLast('.', "").lowercase()) {
    "htm" -> "html"
    "js", "mjs", "cjs" -> "javascript"
    "svg" -> "xml"
    "md" -> "markdown"
    else -> ext
}

private fun charsetOf(type: String): String? =
    Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()

private fun siteResponse(type: String, code: Int, reason: String, body: java.io.InputStream) = WebResourceResponse(
    type.substringBefore(';').trim(), charsetOf(type), code, reason, mapOf("Cache-Control" to "no-store"), body
)

internal fun siteError(code: Int, message: String): WebResourceResponse =
    siteResponse("text/plain; charset=utf-8", code, if (code == 404) "Not Found" else "Error", ByteArrayInputStream(message.toByteArray()))
