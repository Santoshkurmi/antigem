package com.example.gemini.ui.browser.mcp

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.ui.browser.BrowserAutomationScripts
import com.example.gemini.ui.browser.BrowserSessionManager
import com.example.gemini.ui.browser.BrowserTabSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Keeps the user's touches, the soft keyboard and page zoom out of the browser MCP's way.
 *
 * - Light lock: every MCP call on a tab blocks the keyboard before touching the page and keeps it blocked until
 *   [AUTO_MS] after the last call. The user can still touch and pinch-zoom the page.
 * - Agent lock (browser_lock): one lock for the agent's whole task, with no timeout. It covers the tab it was called on,
 *   every tab the agent uses while it is on, and every tab opened meanwhile (new tabs, popups from clicks). Locked tabs
 *   also block the user's touches, pin the zoom and show an Unlock pill. browser_lock locked=false releases them all.
 *   A tab the user unlocks (or the agent releases by id) stays unlocked until the agent locks that tab again.
 * Tabs owned by a browser extension (Flow) keep the extension's own lock and are left alone.
 */
object BrowserAgentLock {
    private const val AUTO_MS = 3_000L
    /** How long after its last call an agent still counts as driving a tab (page dialogs are kept for it). */
    private const val ACTIVE_MS = 60_000L

    /** The agent lock is on: tabs the agent uses or opens get locked. */
    @Volatile var agentLockOn = false
        private set

    private val running = ConcurrentHashMap<String, Int>()
    private val releaseAt = ConcurrentHashMap<String, Long>()
    private val lastUse = ConcurrentHashMap<String, Long>()
    /** Tabs the agent lock must not take: the user unlocked them (true) or the agent released them by id (false). */
    private val keptOpen = ConcurrentHashMap<String, Boolean>()
    /** Tabs whose user unlock the agent has not been told about yet. */
    private val userUnlockedNews = ConcurrentHashMap<String, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager get() = BrowserSessionManager.instance

    private fun now() = SystemClock.uptimeMillis()
    private fun extensionOwns(tab: BrowserTabSession) = tab.aiBusy || tab.aiLocked

    fun isAgentActive(tab: BrowserTabSession): Boolean =
        tab.agentLocked || (running[tab.id] ?: 0) > 0 || now() - (lastUse[tab.id] ?: Long.MIN_VALUE / 2) < ACTIVE_MS

    /** Start of an MCP call on [tab]: lock before anything reaches the page. */
    suspend fun begin(tab: BrowserTabSession) {
        lastUse[tab.id] = now()
        if (extensionOwns(tab)) return
        running.compute(tab.id) { _, n -> (n ?: 0) + 1 }
        if (agentLockOn && !tab.agentLocked && !keptOpen.containsKey(tab.id)) {
            withContext(Dispatchers.Main) { tab.agentLocked = true }
        }
        apply(tab, true)
    }

    /** End of the call: the light lock lingers so back-to-back calls stay locked, then releases unless the tab is locked. */
    fun end(tab: BrowserTabSession) {
        lastUse[tab.id] = now()
        if (extensionOwns(tab)) return
        running.compute(tab.id) { _, n -> ((n ?: 0) - 1).takeIf { it > 0 } }
        releaseAt[tab.id] = now() + AUTO_MS
        scope.launch {
            delay(AUTO_MS + 100)
            recheck(tab)
        }
    }

    /** A tab opened by [opener] (popup, target=_blank) is locked like it. Main thread, before the tab loads. */
    fun inherit(child: BrowserTabSession, opener: BrowserTabSession) {
        if (!opener.agentLocked) return
        child.agentLocked = true
        child.agentInputLock = true
        child.agentZoomPinned = true
        lastUse[child.id] = now()
    }

    /** browser_lock: turns the agent lock on and locks [tab] now. Returns the tabs locked afterwards. */
    suspend fun lock(tab: BrowserTabSession): List<BrowserTabSession> {
        agentLockOn = true
        keptOpen.remove(tab.id)
        userUnlockedNews.remove(tab.id)
        lastUse[tab.id] = now()
        withContext(Dispatchers.Main) { tab.agentLocked = true }
        apply(tab, true)
        return lockedTabs()
    }

    /** browser_lock locked=false without a tab: the agent lock off and every locked tab released. Returns them. */
    suspend fun unlockAll(): List<BrowserTabSession> {
        agentLockOn = false
        val released = lockedTabs()
        withContext(Dispatchers.Main) { released.forEach { it.agentLocked = false } }
        keptOpen.clear()
        released.forEach { recheck(it) }
        return released
    }

    /** browser_lock locked=false for one tab: released, and kept out of the agent lock until locked again. */
    suspend fun unlockOne(tab: BrowserTabSession): Boolean {
        val was = tab.agentLocked
        if (agentLockOn) keptOpen[tab.id] = false
        withContext(Dispatchers.Main) { tab.agentLocked = false }
        recheck(tab)
        return was
    }

    /** The user's Unlock button (main thread). The agent hears about it on its next call on that tab. */
    fun unlockByUser(tab: BrowserTabSession) {
        tab.agentLocked = false
        keptOpen[tab.id] = true
        userUnlockedNews[tab.id] = true
        releaseAt.remove(tab.id)
        scope.launch { recheck(tab) }
    }

    /** True once after the user unlocked [tabId] themselves. */
    fun takeUserUnlocked(tabId: String): Boolean = userUnlockedNews.remove(tabId) == true

    fun lockedTabs(): List<BrowserTabSession> = manager.tabs.toList().filter { it.agentLocked }

    fun forget(tabId: String) {
        running.remove(tabId); releaseAt.remove(tabId); lastUse.remove(tabId); keptOpen.remove(tabId); userUnlockedNews.remove(tabId)
    }

    private suspend fun recheck(tab: BrowserTabSession) {
        val wanted = tab.agentLocked || (running[tab.id] ?: 0) > 0 || now() < (releaseAt[tab.id] ?: 0L)
        apply(tab, wanted)
    }

    /**
     * Turns the keyboard block on or off for [tab]. The zoom is pinned only while the tab is agent-locked (the user
     * cannot touch it then); during the light lock the user keeps pinch-zoom.
     */
    private suspend fun apply(tab: BrowserTabSession, on: Boolean) {
        val pin = on && tab.agentLocked
        val changed = withContext(Dispatchers.Main) {
            if (tab.agentInputLock == on && tab.agentZoomPinned == pin) return@withContext false
            val keyboardChanged = tab.agentInputLock != on
            tab.agentInputLock = on
            tab.agentZoomPinned = pin
            if (keyboardChanged) tab.webView?.let { view ->
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                // Only hide a keyboard the page opened: the user may be typing elsewhere in the app
                if (on && view.hasFocus()) imm?.hideSoftInputFromWindow(view.windowToken, 0)
                // Drops (or allows again) the page's input connection, as WebView asks for it only when needed
                imm?.restartInput(view)
            }
            true
        }
        if (on || changed) withTimeoutOrNull(5_000) { manager.evaluateJs(tab.id, BrowserAutomationScripts.agentInputLock(on, pin)) }
    }
}

/**
 * Shown while an agent holds the explicit lock: blocks touches on the page and offers a draggable Unlock pill
 * (drag it anywhere so it never hides what you want to see).
 */
@Composable
fun BrowserAgentLockOverlay(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var pillW by remember { mutableIntStateOf(0) }
    var pillH by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
    ) {
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .onSizeChanged { pillW = it.width; pillH = it.height }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val maxX = ((areaW - pillW) / 2f).coerceAtLeast(0f)
                        dx = (dx + drag.x).coerceIn(-maxX, maxX)
                        dy = (dy + drag.y).coerceIn(0f, (areaH - pillH - 20.dp.toPx()).coerceAtLeast(0f))
                    }
                }
                .shadow(6.dp, RoundedCornerShape(50))
                .background(Color(0xF2202124), RoundedCornerShape(50))
                .padding(start = 4.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
        ) {
            Icon(Icons.Filled.DragIndicator, contentDescription = "Drag", tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("AI controls this tab", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(2.dp))
            TextButton(onClick = onUnlock, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(30.dp)) {
                Text("Unlock", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = com.example.gemini.theme.AppColors.warning)
            }
        }
    }
}

/** A page dialog the tab is waiting on, answerable by the user (the agent uses browser_handle_dialog). */
@Composable
fun BrowserJsDialogCard(tab: BrowserTabSession, modifier: Modifier = Modifier) {
    val dialog = tab.pendingDialog ?: return
    var reply by remember(dialog) { mutableStateOf(dialog.defaultText) }
    val host = remember(dialog) { runCatching { Uri.parse(dialog.url).host }.getOrNull().orEmpty() }
    fun answer(accept: Boolean) {
        dialog.answer(accept, reply)
        if (tab.pendingDialog === dialog) tab.pendingDialog = null
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = Modifier.padding(24.dp).widthIn(max = 380.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    when (dialog.type) {
                        "confirm" -> "This page asks"
                        "prompt" -> "This page asks for input"
                        "beforeunload" -> "Leave this page?"
                        else -> "This page says"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (host.isNotEmpty()) Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(dialog.message.ifBlank { if (dialog.type == "beforeunload") "Changes you made may not be saved." else "" }, style = MaterialTheme.typography.bodyMedium)
                }
                if (dialog.type == "prompt") {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = reply, onValueChange = { reply = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (dialog.type != "alert") TextButton(onClick = { answer(false) }) { Text(if (dialog.type == "beforeunload") "Stay" else "Cancel") }
                    TextButton(onClick = { answer(true) }) { Text(if (dialog.type == "beforeunload") "Leave" else "OK") }
                }
            }
        }
    }
}
