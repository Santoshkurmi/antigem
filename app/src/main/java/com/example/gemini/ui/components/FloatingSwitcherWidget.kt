package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.gemini.AppViewMode
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun FloatingSwitcherWidget(
    currentViewMode: AppViewMode,
    orientation: String, // "VERTICAL" or "HORIZONTAL"
    items: List<String>, // e.g. ["chat", "ide", "terminal", "browser"]
    autoCollapseTimeoutSec: Int, // 0 = never, >0 = seconds
    savedPosXRatio: Float, // 0.0 .. 1.0 (default ~0.95f)
    savedPosYRatio: Float, // 0.0 .. 1.0 (default ~0.50f)
    onNavigateToChat: () -> Unit,
    onNavigateToIde: () -> Unit,
    onNavigateToBrowser: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToNotes: () -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
    onPositionSaved: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    var isExpanded by remember { mutableStateOf(false) }
    var lastInteractionTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isDragging by remember { mutableStateOf(false) }

    // Position model:
    // - pillX/pillY = top-left of the COLLAPSED pill inside the container (this is what is persisted).
    // - anchorRight/anchorBottom = which corner of the pill the expanded panel grows from. They are only
    //   re-evaluated while collapsed (init, drag end while collapsed, collapse), never from the expanded size,
    //   so expanding/collapsing can't flip sides and the arrow stays under the finger.
    val defaultPillPx = with(density) { 44.dp.toPx() }
    var collapsedSize by remember { mutableStateOf(IntSize(defaultPillPx.roundToInt(), defaultPillPx.roundToInt())) }
    var widgetSize by remember { mutableStateOf(IntSize.Zero) }
    var pillX by remember { mutableFloatStateOf(0f) }
    var pillY by remember { mutableFloatStateOf(0f) }
    var anchorRight by remember { mutableStateOf(savedPosXRatio > 0.5f) }
    var anchorBottom by remember { mutableStateOf(savedPosYRatio > 0.5f) }
    var isPositionInitialized by remember { mutableStateOf(false) }

    val isVertical = orientation.equals("VERTICAL", ignoreCase = true)

    // Directional arrows: expand points toward the growth direction, collapse points back to the anchor.
    val expandArrowIcon: ImageVector = if (isVertical) {
        if (anchorBottom) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore
    } else {
        if (anchorRight) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight
    }
    val collapseArrowIcon: ImageVector = if (isVertical) {
        if (anchorBottom) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess
    } else {
        if (anchorRight) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft
    }

    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val navBarsBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val effectiveBottomInset = maxOf(imeBottomPx, navBarsBottomPx)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(999f)
    ) {
        val containerW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 0f
        val containerH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else 0f
        // Area used for the persisted ratio (ignores the keyboard so saving while typing doesn't skew it).
        val ratioH = (containerH - navBarsBottomPx).coerceAtLeast(0f)

        val currentContainerW by rememberUpdatedState(containerW)
        val currentContainerH by rememberUpdatedState(containerH)
        val currentRatioH by rememberUpdatedState(ratioH)
        val currentBottomInset by rememberUpdatedState(effectiveBottomInset)
        val currentOnPositionSaved by rememberUpdatedState(onPositionSaved)

        fun maxXFor(w: Int) = (currentContainerW - w).coerceAtLeast(0f)
        fun maxYFor(h: Int) = (currentContainerH - h - currentBottomInset).coerceAtLeast(0f)

        // Top-left of a widget of size w x h, positioned from the anchored corner of the pill, clamped on screen.
        fun widgetTopLeft(w: Int, h: Int): Pair<Float, Float> {
            val rawX = if (anchorRight) pillX + collapsedSize.width - w else pillX
            val rawY = if (anchorBottom) pillY + collapsedSize.height - h else pillY
            return rawX.coerceIn(0f, maxXFor(w)) to rawY.coerceIn(0f, maxYFor(h))
        }

        fun recomputeAnchors() {
            anchorRight = pillX + collapsedSize.width / 2f > currentContainerW / 2f
            anchorBottom = pillY + collapsedSize.height / 2f > currentRatioH / 2f
        }

        fun savePosition() {
            val rangeX = (currentContainerW - collapsedSize.width).coerceAtLeast(1f)
            val rangeY = (currentRatioH - collapsedSize.height).coerceAtLeast(1f)
            currentOnPositionSaved(
                (pillX / rangeX).coerceIn(0f, 1f),
                (pillY / rangeY).coerceIn(0f, 1f)
            )
        }

        fun collapse() {
            isExpanded = false
            // Pill returns to its anchored corner (where the collapse arrow sits); re-pick sides for next expand.
            recomputeAnchors()
        }

        // Restore from saved ratio on first layout, on rotation/resize, and when the saved value changes
        // externally (e.g. "Reset position" in settings). Re-applying our own saved value is a no-op.
        LaunchedEffect(containerW, ratioH, savedPosXRatio, savedPosYRatio, collapsedSize) {
            if (containerW > 0f && ratioH > 0f && !isDragging) {
                val rangeX = (containerW - collapsedSize.width).coerceAtLeast(0f)
                val rangeY = (ratioH - collapsedSize.height).coerceAtLeast(0f)
                pillX = savedPosXRatio.coerceIn(0f, 1f) * rangeX
                pillY = savedPosYRatio.coerceIn(0f, 1f) * rangeY
                if (!isExpanded) recomputeAnchors()
                isPositionInitialized = true
            }
        }

        // Auto-collapse timer
        if (autoCollapseTimeoutSec > 0 && isExpanded && !isDragging) {
            LaunchedEffect(isExpanded, lastInteractionTimestamp, autoCollapseTimeoutSec) {
                delay(autoCollapseTimeoutSec * 1000L)
                collapse()
            }
        }

        val collapseButton: @Composable () -> Unit = {
            IconButton(
                onClick = {
                    lastInteractionTimestamp = System.currentTimeMillis()
                    collapse()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = collapseArrowIcon,
                    contentDescription = "Collapse Switcher",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        val switcherItems: @Composable () -> Unit = {
            items.forEach { itemId ->
                val id = itemId.lowercase()
                val isSelected = when (id) {
                    "chat" -> currentViewMode == AppViewMode.CHAT
                    "ide" -> currentViewMode == AppViewMode.IDE
                    "browser" -> currentViewMode == AppViewMode.BROWSER
                    "terminal" -> currentViewMode == AppViewMode.TERMINAL
                    "notes" -> currentViewMode == AppViewMode.NOTES
                    "files" -> currentViewMode == AppViewMode.FILES
                    else -> false
                }
                val iconVector: ImageVector = when (id) {
                    "chat" -> Icons.Outlined.ChatBubbleOutline
                    "ide" -> Icons.Outlined.Code
                    "terminal" -> Icons.Outlined.Terminal
                    "browser" -> Icons.Outlined.Language
                    "notes" -> Icons.Outlined.EditNote
                    "files" -> Icons.Outlined.FolderOpen
                    else -> Icons.Outlined.ChatBubbleOutline
                }
                val contentDesc = when (id) {
                    "chat" -> "Chat"
                    "ide" -> "IDE"
                    "terminal" -> "Terminal"
                    "browser" -> "Browser"
                    "notes" -> "Notes"
                    "files" -> "Files"
                    else -> itemId
                }
                FloatingSwitcherItemButton(
                    icon = iconVector,
                    contentDescription = contentDesc,
                    isSelected = isSelected,
                    onClick = {
                        lastInteractionTimestamp = System.currentTimeMillis()
                        when (id) {
                            "chat" -> onNavigateToChat()
                            "ide" -> onNavigateToIde()
                            "browser" -> onNavigateToBrowser()
                            "terminal" -> onNavigateToTerminal()
                            "notes" -> onNavigateToNotes()
                            "files" -> onNavigateToFiles()
                        }
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                // Position using the size measured in THIS frame, so expand/collapse never jumps for a frame.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(placeable.width, placeable.height) {
                        if (isPositionInitialized) {
                            val (x, y) = widgetTopLeft(placeable.width, placeable.height)
                            placeable.place(x.roundToInt(), y.roundToInt())
                        }
                    }
                }
                .onSizeChanged { newSize ->
                    if (newSize.width > 0 && newSize.height > 0) {
                        widgetSize = newSize
                        if (!isExpanded && newSize != collapsedSize) collapsedSize = newSize
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                        },
                        onDragEnd = {
                            isDragging = false
                            if (!isExpanded) recomputeAnchors()
                            savePosition()
                        },
                        onDragCancel = {
                            isDragging = false
                            if (!isExpanded) recomputeAnchors()
                            savePosition()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            lastInteractionTimestamp = System.currentTimeMillis()
                            val w = widgetSize.width
                            val h = widgetSize.height
                            val (curX, curY) = widgetTopLeft(w, h)
                            val newX = (curX + dragAmount.x).coerceIn(0f, maxXFor(w))
                            val newY = (curY + dragAmount.y).coerceIn(0f, maxYFor(h))
                            // Map the widget's new top-left back to the pill's anchored corner.
                            pillX = if (anchorRight) newX + w - collapsedSize.width else newX
                            pillY = if (anchorBottom) newY + h - collapsedSize.height else newY
                        }
                    )
                }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
                shadowElevation = 8.dp,
                modifier = Modifier.shadow(8.dp, RoundedCornerShape(24.dp))
            ) {
                if (!isExpanded) {
                    // Collapsed Compact Pill with Directional Arrow.
                    // 44dp = same footprint as the expanded panel's arrow cell, so the arrow doesn't shift on toggle.
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable {
                                lastInteractionTimestamp = System.currentTimeMillis()
                                isExpanded = true
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = expandArrowIcon,
                            contentDescription = "Expand Switcher",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else if (isVertical) {
                    // Collapse arrow sits at the anchored end (top when growing down, bottom when growing up).
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val divider: @Composable () -> Unit = {
                            HorizontalDivider(
                                modifier = Modifier
                                    .width(22.dp)
                                    .padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        }
                        if (!anchorBottom) {
                            collapseButton()
                            divider()
                            switcherItems()
                        } else {
                            switcherItems()
                            divider()
                            collapseButton()
                        }
                    }
                } else {
                    // Collapse arrow sits at the anchored end (right when growing left, left when growing right).
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val divider: @Composable () -> Unit = {
                            VerticalDivider(
                                modifier = Modifier
                                    .height(22.dp)
                                    .padding(horizontal = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )
                        }
                        if (anchorRight) {
                            switcherItems()
                            divider()
                            collapseButton()
                        } else {
                            collapseButton()
                            divider()
                            switcherItems()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingSwitcherItemButton(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.20f) else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.5f)) else null,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
