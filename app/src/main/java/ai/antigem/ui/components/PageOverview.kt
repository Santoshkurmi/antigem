package ai.antigem.ui.components

import ai.antigem.AppViewMode
import ai.antigem.data.daemon.IdeDaemonManager
import ai.antigem.data.files.ExplorerPrefs
import ai.antigem.data.local.LocalTerminalManager
import ai.antigem.data.notes.NotesRepository
import ai.antigem.data.run.RunManager
import ai.antigem.data.run.RunStatus
import ai.antigem.domain.model.Conversation
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.ui.browser.BrowserSessionManager
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.antigem.i18n.L

/** Icon of the overview pill (the second floating pill that only opens the page overview). */
val PageOverviewPillIcon: ImageVector get() = Icons.Outlined.AutoAwesomeMosaic

/**
 * Pictures of the pages for the page overview. Hidden pages aren't drawn at all (they sit off screen), so a page is
 * pictured as it is left, and the shown one when the overview opens: "how it looked when you left", never live.
 * Each page's own drawing is recorded ([pageSnapshotSource]) and pictured from that, so the floating pills, the
 * switcher's panel and the overview, drawn over the pages, are never in it. Memory only, at a third of the screen size
 * (about 1 MB each).
 */
object PageSnapshots {
    private const val DOWNSCALE = 3
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val shots = mutableStateMapOf<AppViewMode, ImageBitmap>()

    /** Each composed page's recorded drawing (see [pageSnapshotSource]). */
    internal val sources = HashMap<AppViewMode, GraphicsLayer>()

    fun capture(activity: Activity, mode: AppViewMode) {
        // never pictured: its card shows just its icon and whether it is locked
        if (mode == AppViewMode.PRIVATE_SPACE) return
        val layer = sources[mode]?.takeIf { !it.isReleased && it.size.width > 0 && it.size.height > 0 }
        if (layer == null) {
            // the page hasn't been recorded yet (the overview was just turned on): a copy of the screen instead
            captureWindow(activity, mode)
            return
        }
        // Undispatched: the picture is taken now, before the page change is drawn (a hidden page may draw differently)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val full = layer.toImageBitmap().asAndroidBitmap()
                val small = withContext(Dispatchers.Default) { downscale(full) }
                shots[mode] = small.asImageBitmap()
            } catch (e: Throwable) {
                // the card keeps its old picture
            }
        }
    }

    /** The picture comes back at full size (and on Android 8+ in GPU memory): a third of it, in normal memory. */
    private fun downscale(source: Bitmap): Bitmap {
        val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val small = Bitmap.createScaledBitmap(
            readable,
            (readable.width / DOWNSCALE).coerceAtLeast(1),
            (readable.height / DOWNSCALE).coerceAtLeast(1),
            true
        )
        if (readable !== source && readable !== small) readable.recycle()
        return small
    }

    /** Fallback: a copy of the whole screen (with the pills on it). Android 8+. */
    private fun captureWindow(activity: Activity, mode: AppViewMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val window = activity.window ?: return
        val decor = window.peekDecorView() ?: return
        val w = decor.width / DOWNSCALE
        val h = decor.height / DOWNSCALE
        if (w <= 0 || h <= 0 || !decor.isAttachedToWindow) return
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return
        }
        try {
            // Copies the frame on screen now (the page change isn't drawn yet), scaled down into the bitmap
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) shots[mode] = bitmap.asImageBitmap()
            }, mainHandler)
        } catch (e: Exception) {
            // the window has no surface yet: the card keeps its old picture
        }
    }
}

/**
 * On a page's root (inside its show/hide layer): records the page's drawing for its picture in the page overview,
 * only while the overview is in use ([enabled]); otherwise it draws as before.
 */
@Composable
fun Modifier.pageSnapshotSource(mode: AppViewMode, enabled: Boolean): Modifier {
    val layer = rememberGraphicsLayer()
    DisposableEffect(mode, layer) {
        PageSnapshots.sources[mode] = layer
        onDispose { if (PageSnapshots.sources[mode] === layer) PageSnapshots.sources.remove(mode) }
    }
    if (!enabled) return this
    return this.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/**
 * The shown page as Compose state that tells [onLeave] which page is being left, before the change is drawn.
 * Every page change in MainActivity goes through it, however it was asked for.
 */
class PageChangeState(initial: AppViewMode, private val onLeave: (AppViewMode) -> Unit) : MutableState<AppViewMode> {
    private val state = mutableStateOf(initial)

    init {
        // the pages shown before a crash, for the crash report
        ai.antigem.crash.CrashBreadcrumbs.add("page ${initial.name}")
    }

    override var value: AppViewMode
        get() = state.value
        set(newValue) {
            val old = state.value
            if (newValue != old) {
                ai.antigem.crash.CrashBreadcrumbs.add("page ${old.name} -> ${newValue.name}")
                onLeave(old)
            }
            state.value = newValue
        }

    override fun component1(): AppViewMode = value
    override fun component2(): (AppViewMode) -> Unit = { value = it }
}

private data class OverviewPage(val id: String, val mode: AppViewMode, val label: String, val icon: ImageVector)

private fun overviewPage(id: String): OverviewPage? = when (id.lowercase()) {
    "chat" -> OverviewPage("chat", AppViewMode.CHAT, L.common.chat, Icons.Outlined.ChatBubbleOutline)
    "ide" -> OverviewPage("ide", AppViewMode.IDE, "IDE", Icons.Outlined.Code)
    "terminal" -> OverviewPage("terminal", AppViewMode.TERMINAL, L.common.terminal, Icons.Outlined.Terminal)
    "browser" -> OverviewPage("browser", AppViewMode.BROWSER, L.common.browser, Icons.Outlined.Language)
    "notes" -> OverviewPage("notes", AppViewMode.NOTES, L.common.notes, Icons.Outlined.EditNote)
    "files" -> OverviewPage("files", AppViewMode.FILES, L.common.files, Icons.Outlined.FolderOpen)
    "run" -> OverviewPage("run", AppViewMode.RUN, L.common.run, Icons.Outlined.PlayCircle)
    "private" -> OverviewPage("private", AppViewMode.PRIVATE_SPACE, L.privateSpace.title, Icons.Outlined.Shield)
    else -> null
}

private data class PageStatus(val text: String, val live: Boolean = false)

/** The overview's layouts (the header's switch and Settings → Appearance): id, name, icon. The first is the default. */
private val OverviewLayouts: List<Triple<String, String, ImageVector>> get() = listOf(
    Triple("LARGE", L.components.pageOverview.largeCards, Icons.Outlined.GridView),
    Triple("FIT", L.components.pageOverview.fitScreen, Icons.Outlined.Apps),
    Triple("LIST", L.common.list, Icons.AutoMirrored.Outlined.ViewList)
)

private fun knownOverviewLayout(layout: String): String =
    OverviewLayouts.firstOrNull { it.first == layout }?.first ?: OverviewLayouts.first().first

/**
 * Full-screen overview of the pages: a tile per page (the switcher's pages, in its order) with the page's last
 * picture and a live status line. Tap a tile to open that page; hold and drag it to move it (saved as the switcher's
 * order). Drawn over the pages and below the floating pills, so tapping a pill again closes it. [layout]: "LARGE" big
 * cards that scroll (default), "FIT" every page at once, "LIST" names and status only; the header switches it too.
 */
@Composable
fun PageOverview(
    visible: Boolean,
    currentViewMode: AppViewMode,
    items: List<String>,
    layout: String,
    chatWorking: StateFlow<Boolean>,
    chatConversation: StateFlow<Conversation?>,
    onOpenPage: (String) -> Unit,
    onLayoutChange: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    keepScroll: Boolean = true
) {
    // Where the cards and the list were scrolled: kept while the overview is closed, so it opens where it was left
    // ([keepScroll], Settings → Appearance; off: made new each time it opens, so at the top)
    val keptCardsScroll = rememberScrollState()
    val keptListScroll = rememberScrollState()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(120)),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(998f)
    ) {
        BackHandler(enabled = visible, onBack = onDismiss)
        val cardsScroll = if (keepScroll) keptCardsScroll else rememberScrollState()
        val listScroll = if (keepScroll) keptListScroll else rememberScrollState()
        // The keyboard would keep typing into the hidden page (also the terminal's own View, so not Compose's controller)
        val view = LocalView.current
        LaunchedEffect(Unit) {
            view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        val pagesById = remember(items) { items.mapNotNull { overviewPage(it) }.associateBy { it.id } }
        val reorder = remember { OverviewReorder(pagesById.keys.toList()) }
        LaunchedEffect(pagesById) { reorder.sync(pagesById.keys.toList()) }
        val haptic = LocalHapticFeedback.current
        reorder.onLift = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        reorder.onDropped = onReorder
        AutoScrollWhileDragging(reorder)
        // The header's switch shows the new layout at once; the saved setting follows
        var shownLayout by remember(layout) { mutableStateOf(knownOverviewLayout(layout)) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = L.components.pageOverview.pages,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = L.components.pageOverview.tapToOpenHoldAndDrag,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    LayoutSwitch(
                        selected = shownLayout,
                        onSelect = { chosen ->
                            shownLayout = chosen
                            onLayoutChange(chosen)
                        }
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = L.components.pageOverview.closePageOverview,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
                val pages = reorder.order.mapNotNull { pagesById[it] }
                val openOrClose: (OverviewPage) -> Unit = { page ->
                    // the release of a hold-and-drag is no tap
                    if (reorder.clickAllowed()) {
                        if (page.mode == currentViewMode) onDismiss() else onOpenPage(page.id)
                    }
                }
                when (shownLayout) {
                    "FIT" -> FitPages(pages, reorder) { page, modifier ->
                        FitPageCard(
                            page = page,
                            isCurrent = page.mode == currentViewMode,
                            status = pageStatus(page.id, chatWorking, chatConversation),
                            onClick = { openOrClose(page) },
                            modifier = modifier
                        )
                    }
                    "LIST" -> PageTiles(pages, reorder, columns = 1, gap = 8.dp, scrollState = listScroll) { page, modifier ->
                        PageRow(
                            page = page,
                            isCurrent = page.mode == currentViewMode,
                            status = pageStatus(page.id, chatWorking, chatConversation),
                            onClick = { openOrClose(page) },
                            modifier = modifier
                        )
                    }
                    else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // 2 cards a row on a phone, more on wider screens (cards at least 150dp wide)
                        val gap = 10.dp
                        val columns = ((maxWidth - 24.dp + gap) / (150.dp + gap)).toInt().coerceAtLeast(1)
                        PageTiles(pages, reorder, columns = columns, gap = gap, scrollState = cardsScroll) { page, modifier ->
                            PageCard(
                                page = page,
                                isCurrent = page.mode == currentViewMode,
                                status = pageStatus(page.id, chatWorking, chatConversation),
                                onClick = { openOrClose(page) },
                                modifier = modifier
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The header's layout switch: Large cards, Fit screen, List. */
@Composable
private fun LayoutSwitch(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        OverviewLayouts.forEach { (id, label, icon) ->
            val isSelected = id == selected
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) ClaudeTerracotta.copy(alpha = 0.16f) else Color.Transparent)
                    // selectable: TalkBack says which layout is on (same tap and ripple as clickable)
                    .selectable(selected = isSelected, role = Role.RadioButton) { if (!isSelected) onSelect(id) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

/**
 * Hold-and-drag reordering of the overview's tiles. The dragged tile follows the finger and takes the place of the
 * tile under it; on release the new order is handed to [onDropped] (the switcher's order). Positions are root px.
 */
private class OverviewReorder(initial: List<String>) {
    val order = mutableStateListOf<String>().apply { addAll(initial) }
    var draggedId by mutableStateOf<String?>(null)
        private set
    var finger by mutableStateOf(Offset.Zero)
        private set
    var viewport: Rect? = null
    var scroll: ScrollState? = null
    var onLift: () -> Unit = {}
    var onDropped: (List<String>) -> Unit = {}

    private var grab = Offset.Zero
    // where each tile is laid out (without the dragged tile's lift), unclipped
    private val tilePos = mutableStateMapOf<String, Offset>()
    private val tileSize = HashMap<String, IntSize>()
    private val tileCoords = HashMap<String, LayoutCoordinates>()
    private var orderAtStart: List<String> = emptyList()
    private var endedAt = 0L

    /** A new saved order (Settings, or this overview's own drop) when nothing is being dragged. */
    fun sync(ids: List<String>) {
        if (draggedId == null && order.toList() != ids) {
            order.clear()
            order.addAll(ids)
        }
    }

    fun placed(id: String, coords: LayoutCoordinates) {
        tileCoords[id] = coords
        tileSize[id] = coords.size
        val pos = coords.positionInRoot()
        if (tilePos[id] != pos) tilePos[id] = pos
    }

    fun start(id: String, at: Offset) {
        val coords = tileCoords[id]?.takeIf { it.isAttached } ?: return
        orderAtStart = order.toList()
        grab = at
        finger = coords.localToRoot(at)
        draggedId = id
        onLift()
    }

    fun move(id: String, at: Offset) {
        if (draggedId != id) return
        val coords = tileCoords[id]?.takeIf { it.isAttached } ?: return
        finger = coords.localToRoot(at)
        retarget()
    }

    /** Puts the dragged tile in the place of the tile under the finger (only tiles on screen). */
    fun retarget() {
        val id = draggedId ?: return
        if (viewport?.contains(finger) == false) return
        val target = order.firstOrNull { other ->
            if (other == id) return@firstOrNull false
            val pos = tilePos[other] ?: return@firstOrNull false
            val size = tileSize[other] ?: return@firstOrNull false
            Rect(pos, size.toSize()).contains(finger)
        } ?: return
        val from = order.indexOf(id)
        val to = order.indexOf(target)
        if (from < 0 || to < 0) return
        order.removeAt(from)
        order.add(to, id)
    }

    fun end() {
        if (draggedId == null) return
        draggedId = null
        endedAt = SystemClock.uptimeMillis()
        val now = order.toList()
        if (now != orderAtStart) onDropped(now)
    }

    /** TalkBack's "Move earlier / later": the tile swaps places with its neighbour, saved like a drop. */
    fun step(id: String, by: Int): Boolean {
        if (draggedId != null) return false
        val from = order.indexOf(id)
        val to = from + by
        if (from < 0 || to !in order.indices) return false
        order.removeAt(from)
        order.add(to, id)
        onDropped(order.toList())
        return true
    }

    /** A hold that ended without moving still releases like a tap: that one doesn't open the page. */
    fun clickAllowed(): Boolean = draggedId == null && SystemClock.uptimeMillis() - endedAt > 300

    /** How far the dragged tile is moved from its place, to stay under the finger. */
    fun liftOffset(id: String): Offset = tilePos[id]?.let { finger - grab - it } ?: Offset.Zero
}

/** A tile that can be held and dragged to a new place (see [OverviewReorder]). */
private fun Modifier.reorderTile(id: String, reorder: OverviewReorder): Modifier = this
    .zIndex(if (reorder.draggedId == id) 1f else 0f)
    // before the lift below: the tile's own place, not where it is dragged to
    .onGloballyPositioned { reorder.placed(id, it) }
    // hold-and-drag is touch only: TalkBack gets "Move earlier" / "Move later" in its actions menu
    .semantics {
        val i = reorder.order.indexOf(id)
        customActions = buildList {
            if (i > 0) add(CustomAccessibilityAction(L.components.a11y.moveEarlier) { reorder.step(id, -1) })
            if (i >= 0 && i < reorder.order.lastIndex) add(CustomAccessibilityAction(L.components.a11y.moveLater) { reorder.step(id, 1) })
        }
    }
    .pointerInput(id, reorder) {
        detectDragGesturesAfterLongPress(
            onDragStart = { at -> reorder.start(id, at) },
            onDrag = { change, _ ->
                change.consume()
                reorder.move(id, change.position)
            },
            onDragEnd = { reorder.end() },
            onDragCancel = { reorder.end() }
        )
    }
    .graphicsLayer {
        if (reorder.draggedId == id) {
            val lift = reorder.liftOffset(id)
            translationX = lift.x
            translationY = lift.y
            scaleX = 1.05f
            scaleY = 1.05f
            shadowElevation = 12.dp.toPx()
            shape = RoundedCornerShape(12.dp)
        }
    }

/** While a tile is dragged near the top or bottom edge of a scrolling overview, scroll it (faster nearer the edge). */
@Composable
private fun AutoScrollWhileDragging(reorder: OverviewReorder) {
    val density = LocalDensity.current
    val dragging = reorder.draggedId != null
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val maxStep = with(density) { 10.dp.toPx() }
        while (reorder.draggedId != null) {
            withFrameNanos { }
            val scroll = reorder.scroll
            val box = reorder.viewport
            if (scroll != null && box != null) {
                val y = reorder.finger.y
                val step = when {
                    y < box.top + edge -> -maxStep * ((box.top + edge - y) / edge).coerceIn(0f, 1f)
                    y > box.bottom - edge -> maxStep * ((y - (box.bottom - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (step != 0f) scroll.scrollBy(step)
            }
            // tiles moved under a still finger (scrolling, or the last move's new places)
            reorder.retarget()
        }
    }
}

/**
 * The overview's tiles, [columns] a row, in [reorder]'s order. All tiles are children of one layout, so a dragged
 * tile keeps its gesture when it moves to another row. [fillHeight]: the rows share the height (no scrolling);
 * [tileHeight]: every tile that tall; otherwise each row is as tall as its tallest tile.
 */
@Composable
private fun PageTiles(
    pages: List<OverviewPage>,
    reorder: OverviewReorder,
    columns: Int,
    gap: Dp,
    fillHeight: Boolean = false,
    tileHeight: Dp? = null,
    bottomPadding: Dp = 24.dp,
    scrollState: ScrollState = rememberScrollState(),
    tile: @Composable (page: OverviewPage, modifier: Modifier) -> Unit
) {
    SideEffect { reorder.scroll = if (fillHeight) null else scrollState }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { reorder.viewport = it.boundsInRoot() }
            .then(if (fillHeight) Modifier else Modifier.verticalScroll(scrollState))
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = bottomPadding)
    ) {
        Layout(
            content = {
                pages.forEach { page ->
                    key(page.id) { tile(page, Modifier.reorderTile(page.id, reorder)) }
                }
            },
            modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val cols = columns.coerceAtLeast(1)
            val gapPx = gap.roundToPx()
            val width = constraints.maxWidth
            val tileW = ((width - gapPx * (cols - 1)) / cols).coerceAtLeast(0)
            val rows = (measurables.size + cols - 1) / cols
            val fixedH = tileHeight?.roundToPx()
                ?: if (fillHeight && rows > 0) ((constraints.maxHeight - gapPx * (rows - 1)) / rows).coerceAtLeast(0)
                else null
            val placeables = measurables.map {
                it.measure(
                    if (fixedH != null) Constraints.fixed(tileW, fixedH)
                    else Constraints(minWidth = tileW, maxWidth = tileW)
                )
            }
            val rowHeights = (0 until rows).map { r ->
                (r * cols until minOf((r + 1) * cols, placeables.size)).maxOf { placeables[it].height }
            }
            val contentHeight = rowHeights.sum() + gapPx * (rows - 1).coerceAtLeast(0)
            layout(width, if (fillHeight) constraints.maxHeight else contentHeight) {
                var y = 0
                rowHeights.forEachIndexed { r, rowHeight ->
                    for (i in r * cols until minOf((r + 1) * cols, placeables.size)) {
                        placeables[i].place((i % cols) * (tileW + gapPx), y)
                    }
                    y += rowHeight + gapPx
                }
            }
        }
    }
}

@Composable
private fun PageCard(
    page: OverviewPage,
    isCurrent: Boolean,
    status: PageStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shot = PageSnapshots.shots[page.mode]
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = AppColors.card,
        border = if (isCurrent) BorderStroke(2.dp, ClaudeTerracotta)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // TalkBack: "Current page" on the page shown now (a tap on it just closes the overview)
            .semantics { if (isCurrent) stateDescription = L.components.a11y.currentPage }
            .clickable(
                onClickLabel = if (isCurrent) L.components.pageOverview.closePageOverview else L.components.pageOverview.openLabel(page.label),
                onClick = onClick
            )
    ) {
        Column {
            // The top of the page as it looked when it was left (or its icon before it has a picture)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center
            ) {
                if (shot != null) {
                    Image(
                        bitmap = shot,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = if (isCurrent) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = page.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            StatusLine(
                status = status,
                fontSize = 11.sp,
                normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp)
            )
        }
    }
}

/**
 * Every page at once, without scrolling: picks the column count whose tiles would hold the biggest picture of a whole
 * page (the screen's shape) and puts the name and status over the picture. Scrolls only if tiles would get too small.
 */
@Composable
private fun FitPages(
    pages: List<OverviewPage>,
    reorder: OverviewReorder,
    tile: @Composable (page: OverviewPage, modifier: Modifier) -> Unit
) {
    if (pages.isEmpty()) return
    val configuration = LocalConfiguration.current
    val screenW = configuration.screenWidthDp.coerceAtLeast(1).toFloat()
    val screenH = configuration.screenHeightDp.coerceAtLeast(1).toFloat()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gap = 8.dp
        // the space PageTiles leaves inside its padding
        val spaceW = maxWidth - 24.dp
        val spaceH = maxHeight - 18.dp
        val count = pages.size
        val columns = (1..count).maxByOrNull { c ->
            val rows = (count + c - 1) / c
            val tileW = (spaceW - gap * (c - 1)) / c
            val tileH = (spaceH - gap * (rows - 1)) / rows
            minOf(tileW.value / screenW, tileH.value / screenH)
        } ?: 1
        val rows = (count + columns - 1) / columns
        val tooSmall = (spaceH - gap * (rows - 1)) / rows < MIN_FIT_TILE_HEIGHT
        PageTiles(
            pages = pages,
            reorder = reorder,
            columns = columns,
            gap = gap,
            fillHeight = !tooSmall,
            tileHeight = if (tooSmall) MIN_FIT_TILE_HEIGHT else null,
            bottomPadding = 12.dp,
            tile = tile
        )
    }
}

private val MIN_FIT_TILE_HEIGHT = 96.dp

@Composable
private fun FitPageCard(
    page: OverviewPage,
    isCurrent: Boolean,
    status: PageStatus,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val shot = PageSnapshots.shots[page.mode]
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = AppColors.card,
        border = if (isCurrent) BorderStroke(2.dp, ClaudeTerracotta)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = modifier
            .clip(shape)
            // TalkBack: "Current page" on the page shown now (a tap on it just closes the overview)
            .semantics { if (isCurrent) stateDescription = L.components.a11y.currentPage }
            .clickable(
                onClickLabel = if (isCurrent) L.components.pageOverview.closePageOverview else L.components.pageOverview.openLabel(page.label),
                onClick = onClick
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (shot != null) {
                Image(
                    bitmap = shot,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 24.dp)
                        .size(34.dp)
                )
            }
            // Name and status over the bottom of the picture, on a dark fade so they read on any page
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = if (isCurrent) ClaudeTerracotta else Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = page.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusLine(status = status, fontSize = 10.sp, normalColor = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

/** The "List" layout's row: the page's icon, name and status, no picture. */
@Composable
private fun PageRow(
    page: OverviewPage,
    isCurrent: Boolean,
    status: PageStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = AppColors.card,
        border = if (isCurrent) BorderStroke(2.dp, ClaudeTerracotta)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // TalkBack: "Current page" on the page shown now (a tap on it just closes the overview)
            .semantics { if (isCurrent) stateDescription = L.components.a11y.currentPage }
            .clickable(
                onClickLabel = if (isCurrent) L.components.pageOverview.closePageOverview else L.components.pageOverview.openLabel(page.label),
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = if (isCurrent) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = page.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                StatusLine(
                    status = status,
                    fontSize = 11.5.sp,
                    normalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** A page's status text, green with a dot while it is working / running. */
@Composable
private fun StatusLine(status: PageStatus, fontSize: TextUnit, normalColor: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (status.live) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(AppColors.success)
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = status.text,
            fontSize = fontSize,
            color = if (status.live) AppColors.success else normalColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The card's status line: what the page is doing now (live, unlike its picture). */
@Composable
private fun pageStatus(
    id: String,
    chatWorking: StateFlow<Boolean>,
    chatConversation: StateFlow<Conversation?>
): PageStatus = when (id) {
    "chat" -> {
        val working by chatWorking.collectAsState()
        val conversation by chatConversation.collectAsState()
        if (working) PageStatus(L.common.working, live = true)
        else PageStatus(conversation?.title?.takeIf { it.isNotBlank() } ?: L.components.pageOverview.newChat)
    }
    "ide" -> {
        val tabPath by IdeDaemonManager.activeTabPath.collectAsState()
        val project by IdeDaemonManager.activeProject.collectAsState()
        PageStatus(
            tabPath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: project?.name?.takeIf { it.isNotBlank() }
                ?: L.components.pageOverview.noFileOpen
        )
    }
    "terminal" -> {
        val sessions by LocalTerminalManager.sessions.collectAsState()
        PageStatus(
            when (sessions.size) {
                0 -> L.components.pageOverview.noSessions
                else -> L.components.pageOverview.countSessions(sessions.size)
            }
        )
    }
    "browser" -> {
        val manager = BrowserSessionManager.instance
        val tab = manager.getActiveTab()
        val name = tab?.title?.takeIf { it.isNotBlank() && it != "New Tab" }
            ?: tab?.url?.takeIf { it.isNotBlank() }?.substringAfter("://")?.substringBefore('/')
        val count = manager.tabs.size
        PageStatus(
            listOfNotNull(name, if (count > 1) L.components.pageOverview.countTabs(count) else null).joinToString(" · ").ifEmpty { L.common.newTab }
        )
    }
    "notes" -> {
        val notes by NotesRepository.notes.collectAsState()
        PageStatus(
            when (notes.size) {
                0 -> L.components.pageOverview.noNotes
                1 -> "1 note"
                else -> "${notes.size} notes"
            }
        )
    }
    "files" -> {
        val path = ExplorerPrefs.lastPath("screen")?.trimEnd('/')
        PageStatus(
            when {
                path == null || path == "~" -> L.common.home
                path.isEmpty() -> "/"
                else -> path.substringAfterLast('/')
            }
        )
    }
    "run" -> {
        val runs by RunManager.runs.collectAsState()
        val running = runs.count { it.status == RunStatus.RUNNING }
        val last = runs.maxByOrNull { it.startedAt }
        when {
            running > 0 -> PageStatus("$running running", live = true)
            last != null -> PageStatus(
                "${last.name} · " + when (last.status) {
                    RunStatus.SUCCEEDED -> "done"
                    RunStatus.FAILED -> "failed"
                    RunStatus.STOPPED -> "stopped"
                    RunStatus.RUNNING -> "running"
                }
            )
            else -> PageStatus(L.components.pageOverview.nothingRunning)
        }
    }
    "private" -> {
        // read before the page was ever opened: whether a space exists comes from its file
        val ctx = androidx.compose.ui.platform.LocalContext.current
        remember { ai.antigem.data.privatespace.PrivateSpace.init(ctx) }
        val status by ai.antigem.data.privatespace.PrivateSpace.status.collectAsState()
        PageStatus(
            when (status) {
                ai.antigem.data.privatespace.SpaceStatus.UNLOCKED -> L.privateSpace.overview.open
                ai.antigem.data.privatespace.SpaceStatus.LOCKED -> L.privateSpace.overview.locked
                ai.antigem.data.privatespace.SpaceStatus.NOT_SET_UP -> L.privateSpace.overview.notSetUp
            }
        )
    }
    else -> PageStatus("")
}
