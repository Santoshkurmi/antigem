package ai.antigem.ui.components

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Collections
import java.util.WeakHashMap

/** The web view cache of the chat on screen; null outside a chat (web views are then built and destroyed as usual). */
val LocalChatWebViewCache = staticCompositionLocalOf<ChatWebViewCache?> { null }

/** The feed key of the chat item being drawn; web views inside it are kept under this key. Null: not kept. */
val LocalWebViewSlot = staticCompositionLocalOf<String?> { null }

/**
 * Keeps the web views of one chat's blocks (Mermaid, STEM blocks, interactive apps) alive while that chat is open, so
 * a block scrolled away and back doesn't build, load and draw its page again. A block's view is parked (paused, off
 * screen) when it scrolls away and handed back when it returns.
 *
 * So it can't run the phone out of memory: at most [maxParked] views are parked (the least recently seen go first:
 * [MAX_PARKED] on a phone with a large heap, half that on a smaller one);
 * every parked view is let go when Android says memory is low, or before a new view is built while memory is low; a
 * view whose renderer died is dropped (and built again if it is on screen) instead of crashing the app. Everything is
 * destroyed by [close], when the chat is left.
 *
 * Main thread only.
 */
class ChatWebViewCache(context: Context) {
    private class Entry(val view: WebView, var owner: Any?) {
        /** On screen (owned by a build of its block); null owner: parked. */
        val inUse get() = owner != null
    }

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    /** Parked views kept for this phone: [MAX_PARKED] with a large heap of 512 MB or more, half that below it. */
    private val maxParked = activityManager?.let { if (it.isLowRamDevice || it.largeMemoryClass < 512) MAX_PARKED / 2 else MAX_PARKED } ?: MAX_PARKED

    /** Access order: the first entries are the least recently seen. */
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** Last page height (dp) of each block, so a block comes back at its size (kept even after its view is let go). */
    private val heights = HashMap<String, Int>()

    /** Bumped when a slot's view died while on screen: the block builds a new one. */
    private val generations = mutableStateMapOf<String, Int>()
    private var closed = false

    private val memoryCallbacks = object : ComponentCallbacks2 {
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> dropParked(0)
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Unit
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> dropParked(0)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> dropParked(maxParked / 2)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = dropParked(0)

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    init {
        appContext.registerComponentCallbacks(memoryCallbacks)
    }

    /**
     * The kept view of [slot] for [owner] (one build of the block), or a new one from [create] (kept under [slot]).
     * A view is free when it is parked, or when it has no parent: Compose put its item aside for reuse (it detaches
     * the view but doesn't release it yet), so the block coming back takes it instead of building a second one.
     */
    fun obtain(slot: String, owner: Any, create: () -> WebView): WebView {
        val entry = entries[slot]
        if (entry != null && (!entry.inUse || entry.view.parent == null)) {
            val wasParked = !entry.inUse
            entry.owner = owner
            val oldParent = entry.view.parent
            (entry.view.parent as? ViewGroup)?.removeView(entry.view)
            entry.view.onResume()
            dlog("obtain REUSE(${if (wasParked) "parked" else "put-aside"}) slot=$slot ${wvInfo(entry.view)} oldParent=${oldParent?.let { it.javaClass.simpleName + "@" + wvId(it) }} savedH=${heights[slot]}")
            return entry.view
        }
        // the slot's view is already on screen (should not happen): a view of its own, not kept
        if (entry != null || closed) {
            val view = create()
            watch(view)
            dlog("obtain UNCACHED slot=${slot} closed=${closed} busy=${entry?.let { wvInfo(it.view) }} new=${wvId(view)}")
            return view
        }
        if (isMemoryLow()) dropParked(0)
        val view = create()
        watch(view)
        entries[slot] = Entry(view, owner)
        owners[view] = this
        dlog("obtain NEW slot=${slot} view=${wvId(view)} cached=${entries.size}")
        return view
    }

    /**
     * [owner]'s build of the block of [slot] is gone: its view is paused and parked (or destroyed if it isn't the kept
     * one). Ignored when another build has taken the view since.
     */
    fun release(slot: String, view: WebView, owner: Any) {
        val entry = entries[slot]
        if (closed || entry == null || entry.view !== view) {
            dlog("release DESTROY slot=${slot} ${wvInfo(view)} closed=${closed} entry=${entry?.let { wvId(it.view) }}")
            destroyView(view)
            return
        }
        if (entry.owner !== owner) {
            dlog("release STALE slot=${slot} ${wvInfo(view)} (taken by another build)")
            return
        }
        entry.owner = null
        runCatching { view.onPause() }
        dlog("release PARK slot=${slot} ${wvInfo(view)} parked=${entries.values.count { !it.inUse }}")
        dropParked(if (isMemoryLow()) 0 else maxParked)
    }

    fun height(slot: String): Int? = heights[slot]

    fun setHeight(slot: String, dp: Int) {
        if (heights[slot] != dp) dlog("height slot=$slot ${heights[slot]} -> $dp")
        heights[slot] = dp
    }

    /** Read in composition: changes when the slot's view must be built again. */
    fun generation(slot: String): Int = generations[slot] ?: 0

    /** Destroys every view; called when the chat is left. */
    fun close() {
        if (closed) return
        dlog("close views=${entries.size}")
        closed = true
        runCatching { appContext.unregisterComponentCallbacks(memoryCallbacks) }
        entries.values.forEach { destroyView(it.view) }
        entries.clear()
        heights.clear()
    }

    /** Lets go of the least recently seen parked views until at most [keep] are parked. */
    private fun dropParked(keep: Int) {
        var parked = entries.values.count { !it.inUse }
        if (parked <= keep) return
        val iter = entries.values.iterator()
        while (iter.hasNext() && parked > keep) {
            val entry = iter.next()
            if (entry.inUse) continue
            dlog("dropParked keep=${keep} ${wvInfo(entry.view)}")
            iter.remove()
            destroyView(entry.view)
            parked--
        }
    }

    private fun dropDead(view: WebView) {
        val slot = entries.entries.firstOrNull { it.value.view === view }?.key
        dlog("rendererGone slot=${slot} ${wvInfo(view)}")
        if (slot != null) {
            val entry = entries.remove(slot)
            if (entry?.inUse == true) generations[slot] = generation(slot) + 1
        }
        destroyView(view, rendererAlive = false)
    }

    private fun isMemoryLow(): Boolean {
        val am = activityManager ?: return false
        return runCatching { ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory }.getOrDefault(false)
    }

    companion object {
        // ------------------------------------------------------------------ debug (temporary)
        const val TAG = "AGWebKeep"

        fun dlog(msg: String) {
            Log.d(TAG, msg)
        }

        fun wvId(o: Any): String = Integer.toHexString(System.identityHashCode(o))

        fun wvInfo(v: View): String =
            "view=${wvId(v)} ${v.width}x${v.height} attached=${v.isAttachedToWindow} shown=${v.isShown} " +
                "parent=${v.parent?.let { it.javaClass.simpleName + "@" + wvId(it) }} tag=${(v.tag as? String)?.take(24)}"

        /** Logs every attach / detach / resize of [v]. */
        fun watch(v: View) {
            v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = dlog("attached ${wvInfo(view)}")
                override fun onViewDetachedFromWindow(view: View) = dlog("detached ${wvInfo(view)}")
            })
            v.addOnLayoutChangeListener { view, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) dlog("resized ${wvId(view)} ${or - ol}x${ob - ot} -> ${r - l}x${b - t}")
            }
        }

        /** Parked (off screen) views kept per chat on a phone with a large heap (see maxParked). */
        const val MAX_PARKED = 12

        private val owners = WeakHashMap<WebView, ChatWebViewCache>()
        private val destroyed: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

        /**
         * Call from a WebViewClient's onRenderProcessGone (and return true there): the view can't be used again, so it
         * is taken off screen and destroyed; a kept block on screen builds a new one.
         */
        fun onRendererGone(view: WebView) {
            val owner = owners[view]
            if (owner != null) owner.dropDead(view) else destroyView(view, rendererAlive = false)
        }

        /** Takes [view] off screen and destroys it, once. */
        fun destroyView(view: WebView, rendererAlive: Boolean = true) {
            if (!destroyed.add(view)) return
            owners.remove(view)
            runCatching {
                (view.parent as? ViewGroup)?.removeView(view)
                if (rendererAlive) {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                }
                view.destroy()
            }
        }
    }
}

/**
 * A web view that, inside a chat block, is kept alive while the chat is open ([ChatWebViewCache]); [part] names it
 * within its block. With [keep] false or outside a chat it is an ordinary web view, destroyed when it leaves.
 * [update] runs again on every view handed back, so it must bind callbacks and load only when the page changed.
 */
@Composable
fun KeptWebView(
    part: String,
    factory: (Context) -> WebView,
    modifier: Modifier = Modifier,
    keep: Boolean = true,
    update: (WebView) -> Unit = {}
) {
    val cache = LocalChatWebViewCache.current
    val item = LocalWebViewSlot.current
    if (!keep || cache == null || item == null) {
        ChatWebViewCache.dlog("KeptWebView plain part=${part} keep=${keep} cache=${cache != null} item=${item}")
        AndroidView(factory = factory, update = update, onRelease = { ChatWebViewCache.destroyView(it) }, modifier = modifier)
        return
    }
    val slot = "$item/$part"
    key(cache, slot, cache.generation(slot)) {
        // this build of the block: a release from an older build (put aside by Compose) can't park our view
        val owner = remember { Any() }
        AndroidView(
            factory = { ctx -> cache.obtain(slot, owner) { factory(ctx) } },
            update = { wv ->
                ChatWebViewCache.dlog("update slot=${slot} ${ChatWebViewCache.wvInfo(wv)}")
                update(wv)
            },
            onRelease = { cache.release(slot, it, owner) },
            modifier = modifier
        )
    }
}

/** The kept slot of [part] in the chat block being drawn, or null outside a chat. */
@Composable
fun keptWebViewSlot(part: String): String? = LocalWebViewSlot.current?.let { "$it/$part" }
