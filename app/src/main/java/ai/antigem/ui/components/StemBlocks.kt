package ai.antigem.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.render.RenderBundle
import ai.antigem.data.render.RenderComponent
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.QuotaGreen
import ai.antigem.theme.isAppInDarkTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import ai.antigem.i18n.L

/** The STEM blocks a fenced code block can be (by its language), each drawn from the downloaded bundle. */
enum class StemKind(val component: RenderComponent, val label: String, val lang: String, val interactive: Boolean) {
    SMILES(RenderComponent.SMILES, "MOLECULE", "smiles", false),
    FUNCTION_PLOT(RenderComponent.FUNCTION_PLOT, "FUNCTION PLOT", "functionplot", true),
    JSXGRAPH(RenderComponent.JSXGRAPH, "GEOMETRY", "jsxgraph", true),
    MATTER(RenderComponent.MATTER, "SIMULATION", "matter", true),
    TIKZ(RenderComponent.TIKZ, "TIKZ", "tikz", false);

    companion object {
        fun fromLang(lang: String): StemKind? = when (lang.trim().lowercase()) {
            "smiles" -> SMILES
            "functionplot", "function-plot", "function_plot" -> FUNCTION_PLOT
            "jsxgraph" -> JSXGRAPH
            "matter", "matterjs", "matter-js", "matter.js" -> MATTER
            "tikz", "circuitikz", "tikzpicture" -> TIKZ
            else -> null
        }
    }
}

/**
 * A STEM block in the chat: Diagram / Code tabs, copy, fullscreen (and restart for simulations). When its part is
 * switched off or the bundle isn't installed, it is an ordinary code block.
 */
@Composable
fun StemBlockView(kind: StemKind, code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { RenderBundle.init(context) }
    val active by RenderBundle.active.collectAsState()
    if (kind.component !in active) {
        CodeBlock(code = code, language = kind.lang, modifier = modifier)
        return
    }
    val isDark = isAppInDarkTheme()
    var selectedTab by remember { mutableIntStateOf(0) }
    var isCopied by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var restartKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2500)
            isCopied = false
        }
    }
    val copy = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(kind.label, code))
        isCopied = true
        Toast.makeText(context, L.components.stemBlocks.codeCopied, Toast.LENGTH_SHORT).show()
    }
    // TikZ: the compiled SVG once known (fullscreen shows it without compiling again)
    var tikzSvg by remember(code) { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Science, null, tint = ClaudeTerracotta, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(kind.label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta, letterSpacing = 0.5.sp)
                Spacer(Modifier.weight(1f))
                StemTabs(selectedTab) { selectedTab = it }
                Spacer(Modifier.width(4.dp))
                if (kind == StemKind.MATTER && selectedTab == 0) {
                    StemIconButton(Icons.Outlined.RestartAlt, L.common.restart) { restartKey++ }
                }
                StemIconButton(if (isCopied) Icons.Default.Check else Icons.Outlined.ContentCopy, L.common.copy, if (isCopied) QuotaGreen else null) { copy() }
                StemIconButton(Icons.Outlined.OpenInFull, L.common.expand) { isFullscreen = true }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            if (selectedTab == 0) {
                if (kind == StemKind.TIKZ) {
                    TikzBody(code = code, isDark = isDark, onSvg = { tikzSvg = it })
                } else {
                    val html = remember(kind, code, isDark) { StemHtml.page(kind, code, isDark, fullscreen = false) }
                    StemWebView(html = html, reloadKey = restartKey, interactive = kind.interactive)
                }
            } else {
                CodeBlock(code = code, language = kind.lang)
            }
        }
    }

    if (isFullscreen) {
        val html = remember(kind, code, isDark, tikzSvg) {
            if (kind == StemKind.TIKZ) tikzSvg?.let { StemHtml.tikzSvgPage(it, isDark) }
            else StemHtml.page(kind, code, isDark, fullscreen = true)
        }
        StemFullscreenDialog(kind = kind, code = code, html = html, onCopy = copy, onDismiss = { isFullscreen = false })
    }
}

@Composable
private fun StemTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp)
    ) {
        listOf(L.components.stemBlocks.diagram, L.common.code).forEachIndexed { i, label ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected == i) ClaudeTerracotta else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected == i) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StemIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, label, tint = tint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
    }
}

// ---------------------------------------------------------------------------------------------- WebView

/** What a block's page tells the app: its height, and for TikZ the SVG or the TeX log of a failure. */
private class StemBridge {
    private val main = Handler(Looper.getMainLooper())
    var onHeight: ((Int) -> Unit)? = null
    var onSvg: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @JavascriptInterface fun height(px: Int) { main.post { onHeight?.invoke(px) } }
    @JavascriptInterface fun svg(markup: String) { main.post { onSvg?.invoke(markup) } }
    @JavascriptInterface fun error(log: String) { main.post { onError?.invoke(log) } }
}

/** A block's web view with its bridge, so a view kept by [ChatWebViewCache] can be bound to the block again. */
private class StemPageView(context: Context) : WebView(context) {
    val bridge = StemBridge()
}

/**
 * A WebView for one block page, loaded on https://appassets.androidplatform.net/render/ so the bundle's files load
 * offline; everything else is blocked. Its height follows the page unless [fill]. In a chat it is kept alive while
 * the chat is open ([KeptWebView]) unless [keep] is false (fullscreen pages are never kept).
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun StemWebView(
    html: String,
    modifier: Modifier = Modifier,
    reloadKey: Int = 0,
    interactive: Boolean = false,
    fill: Boolean = false,
    keep: Boolean = true,
    onSvg: ((String) -> Unit)? = null,
    onError: ((String) -> Unit)? = null
) {
    val kept = keep && !fill
    val cache = LocalChatWebViewCache.current
    val slot = if (kept) keptWebViewSlot("stem") else null
    // a kept page comes back at its last height instead of 200dp
    var heightDp by remember(slot) { mutableIntStateOf(slot?.let { cache?.height(it) } ?: 200) }
    ChatWebViewCache.dlog("StemWebView compose slot=${slot} heightDp=${heightDp} kept=${kept}")
    val onHeight: (Int) -> Unit = { px ->
        ChatWebViewCache.dlog("AG.height slot=${slot} px=${px} (was ${heightDp})")
        heightDp = px.coerceIn(48, 1200)
        if (slot != null) cache?.setHeight(slot, heightDp)
    }
    KeptWebView(
        part = "stem",
        keep = kept,
        factory = { ctx ->
            StemPageView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = fill
                isHorizontalScrollBarEnabled = fill
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                if (fill) {
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
                webViewClient = RenderBundle.webViewClient(allowNetwork = false)
                addJavascriptInterface(this.bridge, "AG")
                if (interactive && !fill) {
                    // dragging a point / body moves it instead of scrolling the chat
                    setOnTouchListener { v, ev ->
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                }
            }
        },
        update = { wv ->
            (wv as? StemPageView)?.bridge?.let {
                it.onHeight = onHeight
                it.onSvg = onSvg
                it.onError = onError
            }
            val key = "$reloadKey:${html.hashCode()}:${html.length}"
            ChatWebViewCache.dlog("Stem update slot=${slot} load=${wv.tag != key} ${ChatWebViewCache.wvInfo(wv)}")
            if (wv.tag != key) {
                wv.tag = key
                wv.loadDataWithBaseURL(RenderBundle.BASE_URL, html, "text/html", "UTF-8", null)
            } else {
                // a kept page handed back: it reports its real height again (the saved one may be stale)
                wv.evaluateJavascript("try{__h()}catch(e){}", null)
            }
        },
        modifier = if (fill) modifier.fillMaxSize() else modifier.fillMaxWidth().height(heightDp.dp)
    )
}

// ---------------------------------------------------------------------------------------------- TikZ

/** One TeX run at a time: each one loads the whole TeX engine (~6 MB core) into memory. */
private object TikzQueue {
    val mutex = Mutex()
}

/** A TikZ block: the cached SVG when there is one, else compiled (queued) and then cached. */
@Composable
private fun TikzBody(code: String, isDark: Boolean, onSvg: (String) -> Unit) {
    val prepared = remember(code) { TikzSource.prepare(code) }
    val cacheFile = remember(prepared) { File(RenderBundle.tikzCacheDir(), "${prepared.key}.svg") }
    var svg by remember(prepared) { mutableStateOf<String?>(null) }
    var phase by remember(prepared) { mutableStateOf("loading") } // loading, queued, compiling, done, error
    var errorLog by remember(prepared) { mutableStateOf("") }
    var retry by remember(prepared) { mutableIntStateOf(0) }
    val result = remember(prepared, retry) { CompletableDeferred<Result<String>>() }

    LaunchedEffect(prepared, retry) {
        val cached = withContext(Dispatchers.IO) { cacheFile.takeIf { it.isFile }?.readText() }
        if (cached != null) {
            svg = cached
            onSvg(cached)
            phase = "done"
            return@LaunchedEffect
        }
        phase = "queued"
        TikzQueue.mutex.withLock {
            phase = "compiling"
            val r = withTimeoutOrNull(180_000) { result.await() }
                ?: Result.failure(IllegalStateException("TeX took longer than 3 minutes. Simplify the picture (fewer samples / loops) and retry."))
            r.onSuccess { markup ->
                withContext(Dispatchers.IO) { runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(markup) } }
                svg = markup
                onSvg(markup)
                phase = "done"
            }.onFailure {
                errorLog = it.message.orEmpty()
                phase = "error"
            }
        }
    }

    when (phase) {
        "done" -> {
            val html = remember(svg, isDark) { StemHtml.tikzSvgPage(svg.orEmpty(), isDark) }
            StemWebView(html = html)
        }
        "compiling" -> Box(Modifier.fillMaxWidth()) {
            val html = remember(prepared, isDark) { StemHtml.tikzCompilePage(prepared, isDark) }
            StemWebView(
                html = html,
                keep = false, // the TeX engine is heavy; only the finished picture is kept
                onSvg = { result.complete(Result.success(it)) },
                onError = { result.complete(Result.failure(IllegalStateException(it.ifBlank { L.components.stemBlocks.texFailedWithoutALog }))) }
            )
            StemStatus(L.components.stemBlocks.compilingWithTexFirstTimeTakes, Modifier.align(Alignment.BottomCenter))
        }
        "error" -> Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(L.components.stemBlocks.couldntCompileThisTikzPicture, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    errorLog.lines().takeLast(25).joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                )
            }
            TextButton(onClick = { retry++ }) { Text(L.common.retry) }
        }
        "queued" -> StemStatus(L.components.stemBlocks.waitingForAnotherTikzPictureTo, Modifier.padding(16.dp))
        else -> Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun StemStatus(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A TikZ block made ready for TikZJax: preamble lines turned into its data attributes, used packages added. */
internal object TikzSource {
    data class Prepared(val body: String, val packages: Map<String, String>, val libraries: List<String>, val preamble: String) {
        val key: String by lazy {
            val raw = body + "\u0000" + packages + "\u0000" + libraries + "\u0000" + preamble + "\u0000" + RenderBundle.BUNDLE_TAG
            MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    // TikZJax loads tikz and xcolor (svgnames) itself: asking again (other options) would clash
    private val builtIn = setOf("tikz", "xcolor", "pgf")
    // Android's regex engine (ICU) needs } and ] escaped even outside a character class
    private val usePackage = Regex("""^\\usepackage\s*(?:\[([^\]]*)\])?\s*\{([^\}]*)\}""")
    private val useLibrary = Regex("""^\\usetikzlibrary\s*\{([^\}]*)\}""")

    fun prepare(code: String): Prepared {
        val packages = LinkedHashMap<String, String>()
        val libraries = LinkedHashSet<String>()
        val preamble = StringBuilder()
        val body = StringBuilder()
        for (raw in code.lines()) {
            val line = raw.trim()
            val up = usePackage.find(line)
            val lib = useLibrary.find(line)
            when {
                up != null -> up.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in builtIn }
                    .forEach { packages[it] = up.groupValues[1].trim() }
                lib != null -> lib.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { libraries += it }
                line.startsWith("\\documentclass") || line == "\\begin{document}" || line == "\\end{document}" -> Unit
                line.startsWith("\\pgfplotsset") || line.startsWith("\\definecolor") || line.startsWith("\\newcommand") ||
                    line.startsWith("\\renewcommand") || line.startsWith("\\ctikzset") -> preamble.append(raw).append('\n')
                else -> body.append(raw).append('\n')
            }
        }
        val all = body.toString() + preamble
        fun need(pkg: String, vararg marks: String) {
            if (marks.any { all.contains(it) }) packages.putIfAbsent(pkg, "")
        }
        need("circuitikz", "circuitikz", "\\ctikzset", "to[R", "to[C", "to[L", "to[battery", "to[V", "to[I")
        need("pgfplots", "\\begin{axis}", "\\addplot", "\\pgfplotsset", "\\begin{semilogxaxis}", "\\begin{loglogaxis}", "\\begin{polaraxis}")
        need("chemfig", "\\chemfig", "\\setchemfig", "\\schemestart")
        need("tikz-cd", "\\begin{tikzcd}")
        need("tikz-3dplot", "\\tdplot")
        need("amsmath", "\\text{", "\\dfrac", "\\tfrac", "\\boxed", "\\begin{align", "\\binom", "\\operatorname")
        need("amssymb", "\\mathbb", "\\therefore", "\\because", "\\checkmark")
        if ("pgfplots" in packages && !preamble.contains("compat=")) preamble.insert(0, "\\pgfplotsset{compat=1.18}\n")
        return Prepared(body.toString().trim(), packages, libraries.toList(), preamble.toString().trim())
    }
}

// ---------------------------------------------------------------------------------------------- pages

internal object StemHtml {

    /** A JS string literal that is safe inside <script>. */
    private fun js(s: String): String = JSONObject.quote(s)
        .replace("</", "<\\/")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    private fun attr(s: String): String = s.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;")
        .replace("<", "&lt;").replace(">", "&gt;")

    private fun shell(isDark: Boolean, head: String, body: String, script: String): String {
        val fg = if (isDark) "#ECECF1" else "#1A1A1A"
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{margin:0;padding:0;background:transparent;color:$fg;font:14px system-ui,-apple-system,sans-serif;overflow-x:hidden}
#err{display:none;white-space:pre-wrap;color:#e5534b;font:12px/1.4 monospace;padding:8px 10px;margin:0}
.paper{background:#fff;border-radius:10px}
</style>
$head
</head><body>
$body
<pre id="err"></pre>
<script>
function __h(){try{AG.height(Math.ceil(document.body.getBoundingClientRect().height))}catch(e){}}
function __fail(m){var e=document.getElementById('err');e.style.display='block';e.textContent=String(m&&m.message?m.message:m);__h();}
window.addEventListener('error',function(ev){__fail(ev.message||'Script error')});
window.addEventListener('load',__h);
try{new ResizeObserver(__h).observe(document.body)}catch(e){}
var CODE=${'$'}CODE;var DARK=$isDark;var FULL=${'$'}FULL;
function __height(def){var m=CODE.match(/^\s*\/\/\s*height\s*[:=]\s*(\d+)/m);if(FULL)return Math.max(200,window.innerHeight-16);return m?Math.min(+m[1],900):def;}
</script>
$script
</body></html>"""
    }

    fun page(kind: StemKind, code: String, isDark: Boolean, fullscreen: Boolean): String {
        val html = when (kind) {
            StemKind.SMILES -> shell(
                isDark,
                """<script src="smiles/smiles-drawer.min.js"></script>
<style>#wrap{display:flex;flex-wrap:wrap;gap:10px;justify-content:center;padding:8px}figure{margin:0;text-align:center}figcaption{font-size:12.5px;opacity:.8;margin-top:2px}.bad{color:#e5534b;font:12px monospace;padding:6px}</style>""",
                """<div id="wrap"></div>""",
                """<script>
(function(){
 var lines=CODE.split('\n').map(function(s){return s.trim()}).filter(function(s){return s&&s.indexOf('//')!==0});
 var W=Math.min(window.innerWidth-20,lines.length>1&&!FULL?260:420),H=Math.round(W*0.72);
 var drawer=new SmilesDrawer.SvgDrawer({width:W,height:H});
 var wrap=document.getElementById('wrap');
 lines.forEach(function(line){
  var sp=line.search(/\s/),smi=sp<0?line:line.slice(0,sp),cap=sp<0?'':line.slice(sp).trim();
  var fig=document.createElement('figure');
  var svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
  svg.setAttribute('width',W);svg.setAttribute('height',H);
  fig.appendChild(svg);
  if(cap){var c=document.createElement('figcaption');c.textContent=cap;fig.appendChild(c);}
  wrap.appendChild(fig);
  try{
   SmilesDrawer.parse(smi,function(tree){try{drawer.draw(tree,svg,DARK?'dark':'light');}catch(e){bad(e)}__h();},bad);
  }catch(e){bad(e)}
  function bad(err){svg.remove();var p=document.createElement('div');p.className='bad';p.textContent='Invalid SMILES "'+smi+'": '+(err&&err.message?err.message:err);fig.insertBefore(p,fig.firstChild);__h();}
 });
 __h();
})();
</script>"""
            )
            StemKind.FUNCTION_PLOT -> {
                val ink = if (isDark) "#c9c9d1" else "#333"
                val grid = if (isDark) "rgba(255,255,255,.12)" else "rgba(0,0,0,.08)"
                shell(
                    isDark,
                    """<script src="functionplot/function-plot.js"></script>
<style>#plot{padding:6px 4px}
.function-plot text{fill:$ink!important}
.function-plot .domain,.function-plot .tick line{stroke:$ink!important}
.function-plot .grid .tick line{stroke:$grid!important}
.function-plot .title{fill:$ink!important}</style>""",
                    """<div id="plot"></div>""",
                    """<script>
(function(){
 var o;
 try{o=JSON.parse(CODE)}catch(e){try{o=(new Function('return ('+CODE+')'))()}catch(e2){__fail('Not valid JSON: '+e.message);return;}}
 if(Array.isArray(o))o={data:o};
 if(!o.data&&(o.fn||o.fnType))o={data:[o]};
 var W=window.innerWidth-12;
 o.target='#plot';o.width=W;
 if(FULL)o.height=Math.max(220,window.innerHeight-24);else if(!o.height)o.height=Math.round(Math.min(W*0.75,360));
 if(o.grid===undefined)o.grid=true;
 try{functionPlot(o)}catch(e){__fail(e)}
 __h();
})();
</script>"""
                )
            }
            StemKind.JSXGRAPH -> shell(
                isDark,
                """<link rel="stylesheet" href="jsxgraph/jsxgraph.css"><script src="jsxgraph/jsxgraphcore.js"></script>
<style>#box{margin:6px auto;border:none}</style>""",
                """<div id="box" class="jxgbox paper"></div>""",
                """<script>
(function(){
 var W=window.innerWidth-12,H=__height(Math.min(W,380));
 var box=document.getElementById('box');box.style.width=W+'px';box.style.height=H+'px';
 var board=null;
 if(!/initBoard\s*\(/.test(CODE)){
  board=JXG.JSXGraph.initBoard('box',{boundingbox:[-6,6,6,-6],axis:true,keepAspectRatio:true,showCopyright:false,showNavigation:true,
   pan:{enabled:true,needTwoFingers:true,needShift:false},zoom:{enabled:true,wheel:true,needShift:false,pinch:true}});
 }
 // a block of its own: the code may declare its own `board`
 try{(new Function('JXG','board','{\n'+CODE+'\n}'))(JXG,board)}catch(e){__fail(e)}
 __h();
})();
</script>"""
            )
            StemKind.MATTER -> shell(
                isDark,
                """<script src="matter/matter.min.js"></script><style>#stage{display:flex;justify-content:center;padding:6px}canvas{border-radius:10px;touch-action:none}</style>""",
                """<div id="stage"></div>""",
                """<script>
(function(){
 var W=window.innerWidth-12,H=__height(Math.round(Math.min(W*0.8,360)));
 var stage=document.getElementById('stage');
 if(/Engine\.create\s*\(/.test(CODE)){
  // the code sets up its own engine and renderer: give it the size and the element, and let it run itself
  try{(new Function('Matter','width','height','element','{\n'+CODE+'\n}'))(Matter,W,H,stage)}catch(e){__fail(e)}
  __h();return;
 }
 var engine=Matter.Engine.create(),world=engine.world;
 var render=Matter.Render.create({element:stage,engine:engine,
  options:{width:W,height:H,wireframes:false,background:DARK?'#16161a':'#fafafa',pixelRatio:window.devicePixelRatio||1}});
 var runner=Matter.Runner.create();
 var mouse=Matter.Mouse.create(render.canvas);
 Matter.Composite.add(world,Matter.MouseConstraint.create(engine,{mouse:mouse,constraint:{stiffness:0.2,render:{visible:false}}}));
 render.mouse=mouse;
 try{(new Function('Matter','engine','world','render','runner','width','height','{\n'+CODE+'\n}'))(Matter,engine,world,render,runner,W,H)}catch(e){__fail(e)}
 Matter.Render.run(render);Matter.Runner.run(runner,engine);
 __h();
})();
</script>"""
            )
            StemKind.TIKZ -> tikzCompilePage(TikzSource.prepare(code), isDark)
        }
        return html.replace("\$CODE", js(code)).replace("\$FULL", fullscreen.toString())
    }

    private fun tikzStyle(isDark: Boolean) = """<link rel="stylesheet" href="tikzjax/fonts.css">
<style>#out{display:flex;justify-content:center;padding:10px;overflow-x:auto}#out svg{max-width:100%;height:auto}
${if (isDark) "#out svg{filter:invert(.88) hue-rotate(180deg)}" else ""}</style>"""

    /** Runs TeX on the block (TikZJax) and reports the SVG, or the end of the TeX log when it fails. */
    fun tikzCompilePage(p: TikzSource.Prepared, isDark: Boolean): String {
        val packages = JSONObject().apply { p.packages.forEach { (k, v) -> put(k, v) } }.toString()
        val body = p.body.replace("</script", "<\\/script", ignoreCase = true)
        return shell(
            isDark,
            tikzStyle(isDark) + """
<script>var __log=[];console.log=function(){try{__log.push(Array.prototype.join.call(arguments,' '));if(__log.length>300)__log.shift();}catch(e){}};</script>
<script src="tikzjax/tikzjax.js"></script>""",
            """<div id="out"><script type="text/tikz" data-show-console="true" data-disable-cache="true"""" +
                (if (p.packages.isNotEmpty()) """ data-tex-packages="${attr(packages)}"""" else "") +
                (if (p.libraries.isNotEmpty()) """ data-tikz-libraries="${attr(p.libraries.joinToString(","))}"""" else "") +
                (if (p.preamble.isNotEmpty()) """ data-add-to-preamble="${attr(p.preamble)}"""" else "") +
                ">\n$body\n</script></div>",
            """<script>
document.addEventListener('tikzjax-load-finished',function(ev){var t=ev.target;if(t&&t.tagName&&t.tagName.toLowerCase()==='svg'){try{AG.svg(t.outerHTML)}catch(e){}}__h();});
new MutationObserver(function(){if(document.querySelector('#out img')){try{AG.error(__log.slice(-60).join('\n'))}catch(e){}}}).observe(document.getElementById('out'),{childList:true,subtree:true});
</script>"""
        ).replace("\$CODE", js(p.body)).replace("\$FULL", "false")
    }

    /** A compiled TikZ picture: only its SVG and the TeX fonts (no TeX engine). */
    fun tikzSvgPage(svg: String, isDark: Boolean): String =
        shell(isDark, tikzStyle(isDark), """<div id="out">$svg</div>""", "")
            .replace("\$CODE", "\"\"").replace("\$FULL", "false")
}

// ---------------------------------------------------------------------------------------------- fullscreen

@Composable
private fun StemFullscreenDialog(kind: StemKind, code: String, html: String?, onCopy: () -> Unit, onDismiss: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var restartKey by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, L.common.close) }
                    Spacer(Modifier.width(6.dp))
                    Text(kind.component.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    StemTabs(selectedTab) { selectedTab = it }
                    Spacer(Modifier.width(4.dp))
                    if (selectedTab == 0) StemIconButton(Icons.Outlined.RestartAlt, L.common.reload) { restartKey++ }
                    StemIconButton(Icons.Outlined.ContentCopy, L.common.copy) { onCopy() }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                if (selectedTab == 0) {
                    if (html != null) {
                        StemWebView(html = html, reloadKey = restartKey, interactive = kind.interactive, fill = true)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(L.components.stemBlocks.thePictureIsStillCompiling, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(12.dp)) { CodeBlock(code = code, language = kind.lang, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
    }
}
