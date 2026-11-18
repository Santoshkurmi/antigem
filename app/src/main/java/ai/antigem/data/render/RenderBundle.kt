package ai.antigem.data.render

import android.content.Context
import android.content.SharedPreferences
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * The parts of chat rendering that can be turned on: chemistry `\ce{}` (native, always available) and the blocks
 * that need the downloaded bundle. [version] is exactly what the bundle carries (told to the model as is).
 */
enum class RenderComponent(
    val id: String,
    val title: String,
    val summary: String,
    val library: String,
    val version: String,
    val fromBundle: Boolean
) {
    CHEM("chem", "Chemical equations", "\\ce{…} reactions and formulas inside \$…\$, drawn natively", "mhchem syntax", "built in", false),
    SMILES("smiles", "Molecule structures", "```smiles blocks: 2D structures from SMILES strings", "SmilesDrawer", "2.4.1", true),
    FUNCTION_PLOT("functionplot", "Function plots", "```functionplot blocks: graphs, derivatives, tangents, areas", "function-plot", "1.26.0", true),
    JSXGRAPH("jsxgraph", "Interactive geometry", "```jsxgraph blocks: draggable geometry, loci, graphs", "JSXGraph", "1.14.3", true),
    MATTER("matter", "Physics simulations", "```matter blocks: 2D rigid-body simulations you can touch", "Matter.js", "0.20.0", true),
    TIKZ("tikz", "TikZ, circuits & figures", "```tikz blocks: TikZ, circuitikz, pgfplots, chemfig, tikz-cd (TeX, compiled once and cached)", "TikZJax", "1.0.0-beta24", true),
    MERMAID("mermaid", "Offline Mermaid", "Mermaid diagrams use the bundled copy instead of loading it from the internet", "Mermaid", "10.9.8", true);
}

/**
 * Settings → STEM & diagrams: one zip from the app's GitHub release (like the bootstrap), extracted into the app's
 * private storage, and served offline to the chat's WebViews on https://appassets.androidplatform.net/render/.
 */
object RenderBundle {

    // ------------------------------------------------------------------ the pinned bundle

    const val BUNDLE_TAG = "render-v1"
    private const val ZIP_NAME = "antigem-render-bundle-v1.zip"
    const val BUNDLE_URL = "https://github.com/Santoshkurmi/antigem/releases/download/$BUNDLE_TAG/$ZIP_NAME"
    /** SHA-256 of [ZIP_NAME] as built by scripts/render_bundle/build_render_bundle.sh: anything else is refused. */
    private const val BUNDLE_SHA256 = "c5e97bbeba24d2970c8c4a7ad502ac8c4292b9ef3db5eac64287913ce954f34a"
    /** Its size in bytes (progress when the server sends no length, and shown before downloading). */
    const val BUNDLE_SIZE = 9_736_582L
    /** What the TikZ bundle adds to TikZJax's own packages. */
    const val TIKZ_EXTRAS = "circuitikz 1.0, pgfplots 1.18.1, chemfig 1.4, tikz-cd 1.0, amsmath, amssymb"
    /** Mermaid from the internet when the bundled copy isn't used: pinned to the bundle's version, so agents are told one exact version. */
    const val MERMAID_CDN = "https://cdn.jsdelivr.net/npm/mermaid@10.9.8/dist/mermaid.min.js"

    // ------------------------------------------------------------------ serving

    const val HOST = "appassets.androidplatform.net"
    /** Base URL of the bundle's files in a WebView (relative paths like "tikzjax/tikzjax.js" resolve against it). */
    const val BASE_URL = "https://$HOST/render/"

    // ------------------------------------------------------------------ state

    sealed class Status {
        object Idle : Status()
        data class Downloading(val done: Long, val total: Long) : Status()
        object Extracting : Status()
        data class Failed(val message: String) : Status()
    }

    private var sp: SharedPreferences? = null
    private var filesDir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _enabled = MutableStateFlow<Set<RenderComponent>>(emptySet())
    /** The components switched on (whether or not the bundle is there). */
    val enabled: StateFlow<Set<RenderComponent>> = _enabled.asStateFlow()

    private val _active = MutableStateFlow<Set<RenderComponent>>(emptySet())
    /** What renders right now: switched on, and installed when it needs the bundle. */
    val active: StateFlow<Set<RenderComponent>> = _active.asStateFlow()

    private val _tellAgents = MutableStateFlow(true)
    /** Tell agents (not Jarvis) in the first message which of these blocks this chat renders. */
    val tellAgents: StateFlow<Boolean> = _tellAgents.asStateFlow()

    fun init(context: Context) {
        if (sp != null) return
        val app = context.applicationContext
        val p = app.getSharedPreferences("render_bundle", Context.MODE_PRIVATE)
        filesDir = app.filesDir
        // everything starts off: the user switches on what they want (bundle parts only once it's downloaded)
        _enabled.value = RenderComponent.entries.filter { p.getBoolean("on_${it.id}", false) }.toSet()
        _tellAgents.value = p.getBoolean("tell_agents", true)
        _installed.value = File(bundleDir(), ".complete").isFile
        sp = p
        recompute()
        if (_installed.value) scope.launch { runCatching { writeGuide(app) } }
    }

    fun isActive(c: RenderComponent): Boolean = c in _active.value

    fun setEnabled(c: RenderComponent, on: Boolean) {
        if (on && c.fromBundle && !_installed.value) return // needs the bundle first
        _enabled.value = if (on) _enabled.value + c else _enabled.value - c
        sp?.edit()?.putBoolean("on_${c.id}", on)?.apply()
        recompute()
    }

    fun setTellAgents(on: Boolean) {
        _tellAgents.value = on
        sp?.edit()?.putBoolean("tell_agents", on)?.apply()
    }

    private fun recompute() {
        _active.value = _enabled.value.filter { !it.fromBundle || _installed.value }.toSet()
    }

    private fun root(): File = File(filesDir ?: File("/data/data/${ai.antigem.BuildConfig.APPLICATION_ID}/files"), "render-bundle")
    /** Where the current bundle is extracted. */
    fun bundleDir(): File = File(root(), BUNDLE_TAG)
    /** Compiled TikZ pictures (SVG), kept across bundle updates until cleared. */
    fun tikzCacheDir(): File = File(root(), "cache/tikz").also { it.mkdirs() }
    /** The full syntax guide agents can read (written when the bundle is installed). */
    fun guideFile(): File = File(bundleDir(), "GUIDE.md")

    fun cacheSizeBytes(): Long = File(root(), "cache").walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    fun clearCache() { File(root(), "cache").deleteRecursively() }

    // ------------------------------------------------------------------ install / remove

    fun download(context: Context) {
        init(context)
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { install(app) }
    }

    fun cancel() {
        job?.cancel()
        _status.value = Status.Idle
    }

    fun remove() {
        cancel()
        _installed.value = false
        recompute()
        root().listFiles()?.forEach { it.deleteRecursively() }
    }

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private suspend fun install(app: Context) {
        val root = root().also { it.mkdirs() }
        val part = File(root, "$ZIP_NAME.part")
        val staging = File(root, "$BUNDLE_TAG.tmp")
        try {
            _status.value = Status.Downloading(0, BUNDLE_SIZE)
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(BUNDLE_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Download failed: empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: BUNDLE_SIZE
                body.byteStream().use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - shown > 128 * 1024) {
                                shown = done
                                _status.value = Status.Downloading(done, total)
                            }
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(BUNDLE_SHA256, ignoreCase = true)) {
                throw IOException("The downloaded file isn't the expected bundle (checksum mismatch). Try again later.")
            }

            _status.value = Status.Extracting
            staging.deleteRecursively()
            staging.mkdirs()
            val base = staging.canonicalPath + File.separator
            ZipInputStream(FileInputStream(part).buffered()).use { zin ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zin.nextEntry ?: break
                    val f = File(staging, entry.name).canonicalFile
                    if (!f.path.startsWith(base)) throw IOException("Bad path in the bundle: ${entry.name}")
                    if (entry.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { zin.copyTo(it) }
                    }
                }
            }
            if (!File(staging, "manifest.json").isFile) throw IOException("The bundle has no manifest.json")

            val target = bundleDir()
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Couldn't move the bundle into place")
            writeGuide(app)
            File(target, ".complete").writeText(BUNDLE_TAG)
            // older bundles (other tags) go; the TikZ cache stays
            root.listFiles()?.filter { it.isDirectory && it.name != BUNDLE_TAG && it.name != "cache" }?.forEach { it.deleteRecursively() }
            _installed.value = true
            recompute()
            _status.value = Status.Idle
        } catch (e: kotlinx.coroutines.CancellationException) {
            _status.value = Status.Idle
            throw e
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            part.delete()
            staging.deleteRecursively()
        }
    }

    /** The guide ships with the app (it describes how this app reads each block) and is copied next to the bundle. */
    private fun writeGuide(app: Context) {
        val text = app.assets.open("render/GUIDE.md").bufferedReader().use { it.readText() }
            .replace("{{BUNDLE_DIR}}", bundleDir().absolutePath)
        val f = guideFile()
        if (!f.parentFile!!.isDirectory) return
        if (!f.isFile || f.readText() != text) f.writeText(text)
    }

    /** The guide's text for the in-app viewer (from the app, so it works before the bundle is installed). */
    fun guideText(context: Context): String =
        context.assets.open("render/GUIDE.md").bufferedReader().use { it.readText() }
            .replace("{{BUNDLE_DIR}}", bundleDir().absolutePath)

    // ------------------------------------------------------------------ WebView serving

    private fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        "html" -> "text/html"
        else -> "application/octet-stream" // .gz: TikZJax inflates them itself, so no Content-Encoding
    }

    private val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")

    private fun empty(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, corsHeaders, ByteArrayInputStream(ByteArray(0)))

    /**
     * Serves https://appassets.androidplatform.net/render/<path> from the bundle. Anything else: null (normal network)
     * when [allowNetwork], else refused, so blocks from the chat run fully offline and can't call out.
     */
    fun intercept(request: WebResourceRequest, allowNetwork: Boolean): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null // data:, blob:
        if (url.host != HOST) return if (allowNetwork) null else empty(403, "Offline")
        val path = url.path ?: return empty(404, "Not Found")
        if (!path.startsWith("/render/")) return empty(404, "Not Found")
        if (path == "/render/favicon.ico") return empty(404, "Not Found")
        return try {
            val dir = bundleDir().canonicalFile
            val f = File(dir, path.removePrefix("/render/")).canonicalFile
            if (!f.path.startsWith(dir.path + File.separator) || !f.isFile) return empty(404, "Not Found")
            val m = mime(f.name)
            WebResourceResponse(m, if (m.startsWith("text/")) "utf-8" else null, 200, "OK", corsHeaders, FileInputStream(f))
        } catch (_: Exception) {
            empty(500, "Error")
        }
    }

    /** A WebViewClient that serves the bundle; [allowNetwork] false blocks everything else, and page navigation. */
    fun webViewClient(allowNetwork: Boolean): WebViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            intercept(request, allowNetwork)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowNetwork

        // the renderer died (crash, or killed for memory): drop this view instead of letting the app crash
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            ai.antigem.ui.components.ChatWebViewCache.onRendererGone(view)
            return true
        }
    }

    // ------------------------------------------------------------------ what agents are told

    /**
     * The first-message section for normal chats (not Jarvis): the blocks this chat renders, with exact versions.
     * [canReadFiles]: the agent runs on this phone and can read the full guide. Null when there is nothing to tell.
     */
    fun promptSection(canReadFiles: Boolean): String? {
        if (!_tellAgents.value) return null
        val on = _active.value
        // nothing switched on: nothing to tell (every part starts off)
        if (on.isEmpty()) return null
        val lines = mutableListOf<String>()
        if (RenderComponent.CHEM in on) lines += "- Chemistry: `\\ce{...}` (mhchem syntax) inside `\$...\$` or `\$\$...\$\$`, e.g. `\$\\ce{N2 + 3H2 <=>[Fe][450 °C] 2NH3}\$`, `\$\\ce{SO4^2-}\$`, `\$\\ce{CuSO4*5H2O}\$`; units: `\\pu{25 kJ/mol}`"
        if (RenderComponent.SMILES in on) lines += "- ```smiles: one molecule per line: a SMILES string, then an optional caption (SmilesDrawer ${RenderComponent.SMILES.version})"
        if (RenderComponent.FUNCTION_PLOT in on) lines += "- ```functionplot: function-plot ${RenderComponent.FUNCTION_PLOT.version} options as JSON without `target`, e.g. `{\"data\":[{\"fn\":\"x^3-3x\"},{\"fn\":\"3x^2-3\",\"color\":\"red\"}]}`"
        if (RenderComponent.JSXGRAPH in on) lines += "- ```jsxgraph: JavaScript for JSXGraph ${RenderComponent.JSXGRAPH.version}; `board` is ready (boundingbox [-6,6,6,-6], axes) unless you call `JXG.JSXGraph.initBoard('box', {...})` yourself; first line `// height: 300` sets the height"
        if (RenderComponent.MATTER in on) lines += "- ```matter: JavaScript for Matter.js ${RenderComponent.MATTER.version}; `Matter`, `engine`, `world`, `render`, `runner`, `width`, `height` are ready and it starts itself (don't call Render.run / Runner.run); touch-drag works"
        if (RenderComponent.TIKZ in on) lines += "- ```tikz: a `tikzpicture` / `circuitikz` picture, compiled by TeX (TikZJax ${RenderComponent.TIKZ.version}); `\\usepackage` / `\\usetikzlibrary` lines allowed; packages: $TIKZ_EXTRAS"
        // Mermaid always renders (bundled copy, or the same version from the internet)
        lines += "- ```mermaid: Mermaid ${RenderComponent.MERMAID.version}" + if (RenderComponent.MERMAID in on) " (offline)" else " (loaded from the internet)"
        return buildString {
            append("## Extra blocks this chat renders (AntiGem $BUNDLE_TAG)\n")
            append(lines.joinToString("\n"))
            if (canReadFiles && _installed.value && guideFile().isFile) {
                append("\nFull syntax, examples and limits: ${guideFile().absolutePath} (read it before writing a block you are unsure of).")
            }
        }
    }
}
