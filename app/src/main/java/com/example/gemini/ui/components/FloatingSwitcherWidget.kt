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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
    onPositionSaved: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var widgetSize by remember { mutableStateOf(IntSize.Zero) }
    var isExpanded by remember { mutableStateOf(false) }
    var lastInteractionTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isDragging by remember { mutableStateOf(false) }

    // Auto-collapse timer
    if (autoCollapseTimeoutSec > 0 && isExpanded && !isDragging) {
        LaunchedEffect(isExpanded, lastInteractionTimestamp, autoCollapseTimeoutSec) {
            delay(autoCollapseTimeoutSec * 1000L)
            isExpanded = false
        }
    }

    // Stable in-memory pixel offset position & anchors
    var isPositionInitialized by remember { mutableStateOf(false) }
    var anchorRightX by remember { mutableFloatStateOf(0f) }
    var anchorLeftX by remember { mutableFloatStateOf(0f) }
    var anchorBottomY by remember { mutableFloatStateOf(0f) }
    var anchorTopY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(screenWidthPx, screenHeightPx) {
        if (screenWidthPx > 0 && screenHeightPx > 0 && (!isPositionInitialized || !isDragging)) {
            val posX = (savedPosXRatio * screenWidthPx).coerceIn(0f, (screenWidthPx - 50f).coerceAtLeast(0f))
            val posY = (savedPosYRatio * screenHeightPx).coerceIn(0f, (screenHeightPx - 80f).coerceAtLeast(0f))
            anchorLeftX = posX
            anchorRightX = posX + (if (widgetSize.width > 0) widgetSize.width else 40)
            anchorTopY = posY
            anchorBottomY = posY + (if (widgetSize.height > 0) widgetSize.height else 40)
            isPositionInitialized = true
        }
    }

    val isVertical = orientation.equals("VERTICAL", ignoreCase = true)
    val isNearRightEdge = anchorRightX > (screenWidthPx / 2)
    val isNearBottom = anchorBottomY > (screenHeightPx / 2)

    // Directional arrows:
    // Vertical layout extends vertically -> UP / DOWN arrows (^ / v)
    // Horizontal layout extends horizontally -> LEFT / RIGHT arrows (< / >)
    val expandArrowIcon: ImageVector = if (isVertical) {
        if (isNearBottom) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore
    } else {
        if (isNearRightEdge) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight
    }

    val collapseArrowIcon: ImageVector = if (isVertical) {
        if (isNearBottom) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess
    } else {
        if (isNearRightEdge) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft
    }

    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val navBarsBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val effectiveBottomInset = maxOf(imeBottomPx, navBarsBottomPx)

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(999f)
    ) {
        Box(
            modifier = Modifier
                .offset {
                    val maxX = (screenWidthPx - widgetSize.width).coerceAtLeast(0f)
                    val maxY = (screenHeightPx - widgetSize.height - effectiveBottomInset).coerceAtLeast(0f)
                    val targetX = if (isNearRightEdge) (anchorRightX - widgetSize.width) else anchorLeftX
                    val targetY = if (isNearBottom) (anchorBottomY - widgetSize.height) else anchorTopY
                    val clampedX = targetX.coerceIn(0f, maxX)
                    val clampedY = targetY.coerceIn(0f, maxY)
                    IntOffset(clampedX.roundToInt(), clampedY.roundToInt())
                }
                .onSizeChanged { newSize ->
                    if (newSize.width > 0 && newSize.height > 0) {
                        if (!isDragging) {
                            if (isNearRightEdge) {
                                anchorLeftX = anchorRightX - newSize.width
                            } else {
                                anchorRightX = anchorLeftX + newSize.width
                            }
                            if (isNearBottom) {
                                anchorTopY = anchorBottomY - newSize.height
                            } else {
                                anchorBottomY = anchorTopY + newSize.height
                            }
                        }
                        widgetSize = newSize
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            val maxX = (screenWidthPx - widgetSize.width).coerceAtLeast(0f)
                            val maxY = (screenHeightPx - widgetSize.height - effectiveBottomInset).coerceAtLeast(0f)
                            val currentX = (if (isNearRightEdge) (anchorRightX - widgetSize.width) else anchorLeftX).coerceIn(0f, maxX)
                            val currentY = (if (isNearBottom) (anchorBottomY - widgetSize.height) else anchorTopY).coerceIn(0f, maxY)
                            anchorLeftX = currentX
                            anchorRightX = currentX + widgetSize.width
                            anchorTopY = currentY
                            anchorBottomY = currentY + widgetSize.height
                        },
                        onDragEnd = {
                            isDragging = false
                            val maxX = (screenWidthPx - widgetSize.width).coerceAtLeast(0f)
                            val maxY = (screenHeightPx - widgetSize.height - effectiveBottomInset).coerceAtLeast(0f)
                            val targetX = if (isNearRightEdge) (anchorRightX - widgetSize.width) else anchorLeftX
                            val targetY = if (isNearBottom) (anchorBottomY - widgetSize.height) else anchorTopY
                            val clampedX = targetX.coerceIn(0f, maxX)
                            val clampedY = targetY.coerceIn(0f, maxY)
                            anchorLeftX = clampedX
                            anchorRightX = clampedX + widgetSize.width
                            anchorTopY = clampedY
                            anchorBottomY = clampedY + widgetSize.height
                            val ratioX = if (screenWidthPx > 0) (clampedX / screenWidthPx).coerceIn(0f, 1f) else 0.95f
                            val ratioY = if (screenHeightPx > 0) (clampedY / screenHeightPx).coerceIn(0f, 1f) else 0.50f
                            onPositionSaved(ratioX, ratioY)
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            lastInteractionTimestamp = System.currentTimeMillis()
                            val maxX = (screenWidthPx - widgetSize.width).coerceAtLeast(0f)
                            val maxY = (screenHeightPx - widgetSize.height - effectiveBottomInset).coerceAtLeast(0f)
                            anchorLeftX = (anchorLeftX + dragAmount.x).coerceIn(0f, maxX)
                            anchorRightX = anchorLeftX + widgetSize.width
                            anchorTopY = (anchorTopY + dragAmount.y).coerceIn(0f, maxY)
                            anchorBottomY = anchorTopY + widgetSize.height
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
                    // Collapsed Compact Pill with Directional Arrow
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable {
                                lastInteractionTimestamp = System.currentTimeMillis()
                                isExpanded = true
                            }
                            .padding(horizontal = 9.dp, vertical = 9.dp),
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
                } else {
                    // Expanded Full Switcher Pill
                    if (isVertical) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Collapse Arrow Button (Up / Down for vertical)
                            IconButton(
                                onClick = {
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                    isExpanded = false
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

                            HorizontalDivider(
                                modifier = Modifier
                                    .width(22.dp)
                                    .padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )

                            // Configured Items
                            items.forEach { itemId ->
                                val isSelected = when (itemId.lowercase()) {
                                    "chat" -> currentViewMode == AppViewMode.CHAT
                                    "ide" -> currentViewMode == AppViewMode.IDE
                                    "browser" -> currentViewMode == AppViewMode.BROWSER
                                    "terminal" -> currentViewMode == AppViewMode.TERMINAL
                                    else -> false
                                }

                                val iconVector: ImageVector = when (itemId.lowercase()) {
                                    "chat" -> Icons.Outlined.ChatBubbleOutline
                                    "ide" -> Icons.Outlined.Code
                                    "terminal" -> Icons.Outlined.Terminal
                                    "browser" -> Icons.Outlined.Language
                                    else -> Icons.Outlined.ChatBubbleOutline
                                }

                                val contentDesc = when (itemId.lowercase()) {
                                    "chat" -> "Chat"
                                    "ide" -> "IDE"
                                    "terminal" -> "Terminal"
                                    "browser" -> "Browser"
                                    else -> itemId
                                }

                                FloatingSwitcherItemButton(
                                    icon = iconVector,
                                    contentDescription = contentDesc,
                                    isSelected = isSelected,
                                    onClick = {
                                        lastInteractionTimestamp = System.currentTimeMillis()
                                        when (itemId.lowercase()) {
                                            "chat" -> onNavigateToChat()
                                            "ide" -> onNavigateToIde()
                                            "browser" -> onNavigateToBrowser()
                                            "terminal" -> onNavigateToTerminal()
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        // Horizontal Layout: Icons on left, collapse arrow on right
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Configured Items
                            items.forEach { itemId ->
                                val isSelected = when (itemId.lowercase()) {
                                    "chat" -> currentViewMode == AppViewMode.CHAT
                                    "ide" -> currentViewMode == AppViewMode.IDE
                                    "browser" -> currentViewMode == AppViewMode.BROWSER
                                    "terminal" -> currentViewMode == AppViewMode.TERMINAL
                                    else -> false
                                }

                                val iconVector: ImageVector = when (itemId.lowercase()) {
                                    "chat" -> Icons.Outlined.ChatBubbleOutline
                                    "ide" -> Icons.Outlined.Code
                                    "terminal" -> Icons.Outlined.Terminal
                                    "browser" -> Icons.Outlined.Language
                                    else -> Icons.Outlined.ChatBubbleOutline
                                }

                                val contentDesc = when (itemId.lowercase()) {
                                    "chat" -> "Chat"
                                    "ide" -> "IDE"
                                    "terminal" -> "Terminal"
                                    "browser" -> "Browser"
                                    else -> itemId
                                }

                                FloatingSwitcherItemButton(
                                    icon = iconVector,
                                    contentDescription = contentDesc,
                                    isSelected = isSelected,
                                    onClick = {
                                        lastInteractionTimestamp = System.currentTimeMillis()
                                        when (itemId.lowercase()) {
                                            "chat" -> onNavigateToChat()
                                            "ide" -> onNavigateToIde()
                                            "browser" -> onNavigateToBrowser()
                                            "terminal" -> onNavigateToTerminal()
                                        }
                                    }
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier
                                    .height(22.dp)
                                    .padding(horizontal = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )

                            // Collapse Arrow Button on the Right Side
                            IconButton(
                                onClick = {
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                    isExpanded = false
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
