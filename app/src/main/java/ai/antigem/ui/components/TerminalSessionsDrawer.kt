package ai.antigem.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.local.LocalPtySession
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import ai.antigem.i18n.L

/** Same width as Termux's drawer. */
private val DRAWER_WIDTH = 240.dp

/**
 * Open/closed state of the terminal's sessions drawer (Termux's left drawer). [fraction]: 0 = closed, 1 = open; it
 * follows the finger while dragged. The px sizes are fixed when it is remembered (see [rememberTerminalDrawerState]).
 */
@Stable
class TerminalDrawerState internal constructor(
    private val scope: CoroutineScope,
    internal val widthPx: Float,
    /** How far from the left edge a swipe may start. */
    internal val edgePx: Float,
    /** Height of the left-edge band kept from gesture navigation's Back swipe (Android allows 200dp per edge). */
    internal val exclusionHeightPx: Float,
    private val flingVelocityPx: Float,
    /** The marker showing that band: its width, height and distance from the edge. */
    internal val markerWidthPx: Float,
    internal val markerHeightPx: Float,
    internal val markerInsetPx: Float
) {
    var fraction by mutableFloatStateOf(0f)
        private set
    private var animation: Job? = null

    /** Open, opening, being dragged or closing. */
    val isShown: Boolean get() = fraction > 0f

    fun open() = animateTo(1f)
    fun close() = animateTo(0f)

    /** Closed at once, no animation (the terminal went off screen). */
    fun snapClosed() {
        animation?.cancel()
        fraction = 0f
    }

    internal fun dragBy(deltaPx: Float) {
        animation?.cancel()
        fraction = (fraction + deltaPx / widthPx).coerceIn(0f, 1f)
    }

    /** End of a drag: a fling decides, else whichever side is nearer. */
    internal fun settle(velocityPx: Float) {
        val open = when {
            velocityPx > flingVelocityPx -> true
            velocityPx < -flingVelocityPx -> false
            else -> fraction > 0.5f
        }
        animateTo(if (open) 1f else 0f)
    }

    private fun animateTo(target: Float) {
        animation?.cancel()
        animation = scope.launch {
            animate(fraction, target, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ ->
                fraction = value
            }
        }
    }
}

@Composable
fun rememberTerminalDrawerState(): TerminalDrawerState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density) {
        with(density) {
            TerminalDrawerState(
                scope = scope,
                widthPx = DRAWER_WIDTH.toPx(),
                edgePx = 32.dp.toPx(),
                exclusionHeightPx = 200.dp.toPx(),
                flingVelocityPx = 400.dp.toPx(),
                markerWidthPx = 3.dp.toPx(),
                markerHeightPx = 44.dp.toPx(),
                markerInsetPx = 1.dp.toPx()
            )
        }
    }
}

/**
 * Top and bottom (px, within a [height] tall terminal) of the left-edge band where a swipe always opens the drawer.
 * [area]: "TOP" (just below the status bar, the default), "MIDDLE" or "BOTTOM"; [topInsetPx] = the status bar height.
 */
private fun swipeBand(state: TerminalDrawerState, area: String, height: Float, topInsetPx: Float): Pair<Float, Float> {
    val band = minOf(state.exclusionHeightPx, height)
    val top = when (area.uppercase()) {
        "MIDDLE" -> (height - band) / 2f
        "BOTTOM" -> height - band
        else -> topInsetPx.coerceAtMost(height - band)
    }.coerceAtLeast(0f)
    return top to top + band
}

/**
 * Swipe right from the left edge opens the drawer, like Termux. The gesture is watched before the terminal sees it
 * (Initial pass) and only taken once the finger clearly moves right, so taps, long presses and vertical scrolls
 * near the edge still reach the terminal.
 *
 * With gesture navigation a swipe from the edge is Back (which would leave the terminal). Android lets an app keep
 * only 200dp of an edge from it, so that band sits where the user chose ([area], Settings → Terminal) and a faint
 * marker on the edge shows it. Elsewhere on the edge the swipe still works with 3-button navigation.
 */
fun Modifier.terminalDrawerEdgeSwipe(state: TerminalDrawerState, area: String, topInsetPx: Float): Modifier = this
    .systemGestureExclusion { coords ->
        val (top, bottom) = swipeBand(state, area, coords.size.height.toFloat(), topInsetPx)
        Rect(0f, top, state.edgePx, bottom)
    }
    .drawWithContent {
        drawContent()
        val (top, bottom) = swipeBand(state, area, size.height, topInsetPx)
        val markerHeight = minOf(state.markerHeightPx, bottom - top)
        drawRoundRect(
            // mid gray: visible on dark and light terminal themes
            color = Color.Gray.copy(alpha = 0.45f),
            topLeft = Offset(state.markerInsetPx, (top + bottom - markerHeight) / 2f),
            size = Size(state.markerWidthPx, markerHeight),
            cornerRadius = CornerRadius(state.markerWidthPx / 2f)
        )
    }
    .pointerInput(state) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (state.isShown || down.position.x > state.edgePx) return@awaitEachGesture
            val velocity = VelocityTracker().apply { addPointerInputChange(down) }
            var totalX = 0f
            var totalY = 0f
            var dragging = false
            var settled = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: break
                velocity.addPointerInputChange(change)
                if (!change.pressed) {
                    if (dragging) {
                        change.consume()
                        state.settle(velocity.calculateVelocity().x)
                        settled = true
                    }
                    break
                }
                val delta = change.positionChange()
                if (dragging) {
                    state.dragBy(delta.x)
                    // consumed: the terminal gets a cancel instead of a tap / selection
                    change.consume()
                } else {
                    totalX += delta.x
                    totalY += delta.y
                    if (totalX > slop && totalX > abs(totalY)) {
                        dragging = true
                        state.dragBy(totalX - slop)
                        change.consume()
                    } else if (abs(totalY) > slop || totalX < -slop) {
                        break // not a swipe to the right: the terminal's own gesture
                    }
                }
            }
            // the finger went away without an up (gesture cancelled): don't leave the drawer half open
            if (dragging && !settled) state.settle(0f)
        }
    }

/**
 * Termux's left drawer: the gear (terminal settings) at the top, the sessions (tap = switch; each row has Rename and
 * Close session icons), and KEYBOARD and NEW SESSION at the bottom. Draw it above the terminal, filling it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalSessionsDrawer(
    state: TerminalDrawerState,
    sessions: List<LocalPtySession>,
    activeSessionId: String?,
    canCreateSession: Boolean,
    onSelectSession: (LocalPtySession) -> Unit,
    onNewSession: () -> Unit,
    onNewBashSession: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onRenameSession: (LocalPtySession, String) -> Unit,
    onCloseSession: (LocalPtySession) -> Unit,
    /** The gear; null = no gear (no settings screen where this terminal runs). */
    onOpenSettings: (() -> Unit)?
) {
    var renaming by remember { mutableStateOf<LocalPtySession?>(null) }
    val isShown by remember(state) { derivedStateOf { state.isShown } }

    if (isShown) {
        val dragState = rememberDraggableState { delta -> state.dragBy(delta) }
        // The draggable sits on this full-size box, which doesn't move: on the sliding panel the finger's
        // position would be measured against a moving origin.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(dragState, Orientation.Horizontal, onDragStopped = { velocity -> state.settle(velocity) })
        ) {
            // Scrim: darkens the terminal as the drawer opens; a tap on it closes the drawer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.Black.copy(alpha = 0.5f * state.fraction)) }
                    .pointerInput(state) { detectTapGestures { state.close() } }
            )
            Surface(
                modifier = Modifier
                    .width(DRAWER_WIDTH)
                    .fillMaxHeight()
                    .offset { IntOffset(((state.fraction - 1f) * state.widthPx).roundToInt(), 0) },
                color = AppColors.editorPanel,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    if (onOpenSettings != null) {
                        IconButton(
                            onClick = {
                                state.snapClosed()
                                onOpenSettings()
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = L.components.terminalSessionsDrawer.terminalSettings,
                                tint = AppColors.editorText.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        Box(modifier = Modifier.height(8.dp))
                    }

                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(sessions, key = { it.id }) { sess ->
                            DrawerSessionRow(
                                session = sess,
                                isActive = sess.id == activeSessionId,
                                onClick = {
                                    onSelectSession(sess)
                                    state.close()
                                },
                                onRename = { renaming = sess },
                                onClose = { onCloseSession(sess) }
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = AppColors.editorText.copy(alpha = 0.15f))
                    Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        DrawerButton(
                            label = "KEYBOARD",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                state.close()
                                onToggleKeyboard()
                            }
                        )
                        DrawerButton(
                            label = "NEW SESSION",
                            modifier = Modifier.weight(1f),
                            enabled = canCreateSession,
                            onClick = {
                                onNewSession()
                                state.close()
                            },
                            onLongClick = {
                                onNewBashSession()
                                state.close()
                            }
                        )
                    }
                }
            }
        }
    }

    renaming?.let { sess ->
        RenameSessionDialog(
            session = sess,
            onRename = { name -> onRenameSession(sess, name) },
            onDismiss = { renaming = null }
        )
    }
}

@Composable
private fun DrawerSessionRow(
    session: LocalPtySession,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit
) {
    val title by session.title.collectAsState()
    val exited by session.isExited.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(if (isActive) ClaudeTerracotta.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClickLabel = L.components.terminalSessionsDrawer.switchToThisSession, onClick = onClick)
            .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // like Termux: an ended session is struck through, in red
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (exited) AppColors.danger else if (isActive) ClaudeTerracotta else Color.White,
            textDecoration = if (exited) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = L.components.terminalSessionsDrawer.renameSession,
                tint = AppColors.editorText.copy(alpha = 0.75f),
                modifier = Modifier.size(18.dp)
            )
        }
        // asks first (the terminal's "Close Tab?" dialog)
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = L.components.terminalSessionsDrawer.closeSession,
                tint = AppColors.danger.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) ClaudeTerracotta else Color.Gray,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun RenameSessionDialog(
    session: LocalPtySession,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // a tmux tab shows as "T 2: name": edit just the name
    val current = remember(session.id) { session.title.value.replace(Regex("^T \\d+(: )?"), "") }
    var text by remember(session.id) { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val submit = {
        if (text.text.isNotBlank()) {
            onRename(text.text.trim())
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = AppColors.editorBg,
        title = { Text(L.components.terminalSessionsDrawer.renameSession, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(L.components.terminalSessionsDrawer.sessionName, color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = ClaudeTerracotta,
                    unfocusedBorderColor = AppColors.editorText.copy(alpha = 0.3f),
                    cursorColor = ClaudeTerracotta
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = text.text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(L.common.rename, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text(L.common.cancel, color = Color.Gray, fontSize = 13.sp)
            }
        }
    )
}
