package com.example.gemini.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.gemini.theme.ClaudeTerracotta
import java.net.URLEncoder

/**
 * Tab model representing a live web session with its own state and WebView.
 */
typealias BrowserTab = BrowserTabSession

private fun isBrowserUserUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val trimmed = url.trim()
    if (trimmed == "about:blank" || trimmed.startsWith("about:", ignoreCase = true)) return false
    if (trimmed.startsWith("data:", ignoreCase = true)) return false
    if (trimmed.startsWith("javascript:", ignoreCase = true)) return false
    return true
}

private fun getPreviousValidHistoryIndex(webView: WebView?): Int {
    if (webView == null) return -1
    val list = webView.copyBackForwardList()
    val currentIndex = list.currentIndex
    if (currentIndex <= 0) return -1

    val currentItem = list.getItemAtIndex(currentIndex)
    val currentUrl = currentItem?.url?.trim() ?: ""
    val currentOrigUrl = currentItem?.originalUrl?.trim() ?: ""
    val isCurrentError = !isBrowserUserUrl(currentUrl)

    val startSearchIndex = if (isCurrentError) (currentIndex - 2) else (currentIndex - 1)
    if (startSearchIndex < 0) return -1

    for (i in startSearchIndex downTo 0) {
        val item = list.getItemAtIndex(i) ?: continue
        val itemUrl = item.url.trim()
        val itemOrigUrl = item.originalUrl?.trim() ?: ""

        val isValid = isBrowserUserUrl(itemUrl) || isBrowserUserUrl(itemOrigUrl)
        if (!isValid) continue

        if (isCurrentError || (itemUrl != currentUrl && itemOrigUrl != currentUrl && itemUrl != currentOrigUrl)) {
            return i
        }
    }
    return -1
}

fun canBrowserTabGoBack(webView: WebView?): Boolean {
    return getPreviousValidHistoryIndex(webView) >= 0 || webView?.canGoBack() == true
}

fun handleBrowserBack(webView: WebView?, onExhausted: () -> Unit) {
    if (webView == null) {
        onExhausted()
        return
    }
    val targetIndex = getPreviousValidHistoryIndex(webView)
    if (targetIndex >= 0) {
        val currentIndex = webView.copyBackForwardList().currentIndex
        val steps = targetIndex - currentIndex
        webView.goBackOrForward(steps)
    } else if (webView.canGoBack()) {
        webView.goBack()
    } else {
        onExhausted()
    }
}

private fun formatBrowserUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("file://", ignoreCase = true) ||
        trimmed.startsWith("about:", ignoreCase = true)) {
        return trimmed
    }
    // Check if it's localhost or an IP/host with optional port
    if (trimmed.startsWith("localhost", ignoreCase = true) ||
        trimmed.startsWith("127.0.0.1") ||
        trimmed.startsWith("192.168.") ||
        trimmed.startsWith("10.0.") ||
        trimmed.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$")) ||
        (trimmed.contains(":") && trimmed.substringBefore(":").all { it.isLetterOrDigit() || it == '.' })) {
        return "http://$trimmed"
    }
    val encoded = try { URLEncoder.encode(trimmed, "UTF-8") } catch (_: Exception) { trimmed }
    return "https://www.google.com/search?q=$encoded"
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun BrowserScreen(
    isVisible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionManager = BrowserSessionManager.instance
    val tabs = sessionManager.tabs
    val isDarkTheme = com.example.gemini.theme.isAppInDarkTheme()
    var activeTabId by remember { mutableStateOf(sessionManager.activeTabId) }
    
    // Use TextFieldValue to support select-all on click / focus
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isAddressFocused by remember { mutableStateOf(false) }
    var shouldSelectAllOnNextValueChange by remember { mutableStateOf(false) }
    var showTabOverview by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showExitConfirmationDialog by remember { mutableStateOf(false) }
    var isControlsVisible by remember { mutableStateOf(true) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val defaultMaxFooterOffsetPx = remember(density) { with(density) { 100.dp.toPx() } }
    var footerHeightPx by remember { mutableFloatStateOf(0f) }
    var footerOffsetPx by remember { mutableFloatStateOf(0f) }
    var ignoreScrollUntilTouch by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Initialize speed dial shortcuts and typed history from storage
    LaunchedEffect(Unit) {
        sessionManager.loadShortcutsIfNeeded(context)
        sessionManager.loadHistoryIfNeeded(context)
    }

    // Ensure at least one tab exists when opening browser
    LaunchedEffect(isVisible) {
        if (isVisible && sessionManager.tabs.isEmpty()) {
            val newTab = sessionManager.addNewTab(url = "", title = "New Tab", activate = true)
            activeTabId = newTab.id
        }
    }

    LaunchedEffect(sessionManager.activeTabId) {
        if (sessionManager.activeTabId != null) {
            activeTabId = sessionManager.activeTabId
        }
    }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    val isHomePage = activeTab == null || activeTab.url.isBlank() || activeTab.url == "about:blank"

    // When on home page or tab overview or address focused, always show controls
    LaunchedEffect(isHomePage, showTabOverview, isAddressFocused) {
        if (isHomePage || showTabOverview || isAddressFocused) {
            isControlsVisible = true
        }
    }

    // Sync status bar color with header
    val view = LocalView.current
    val barBackgroundColor = MaterialTheme.colorScheme.background
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        if (activity != null && activity !is com.example.gemini.ui.bubble.FloatingChatActivity && isVisible) {
            DisposableEffect(isDarkTheme, isVisible, barBackgroundColor) {
                val window = activity.window
                val insetsController = WindowCompat.getInsetsController(window, view)
                val originalStatusBarColor = window.statusBarColor
                val originalIsLightStatusBars = insetsController.isAppearanceLightStatusBars

                window.statusBarColor = barBackgroundColor.toArgb()
                insetsController.isAppearanceLightStatusBars = !isDarkTheme

                onDispose {
                    window.statusBarColor = originalStatusBarColor
                    insetsController.isAppearanceLightStatusBars = originalIsLightStatusBars
                }
            }
        }
    }

    // Sync address input when active tab changes and address bar is not focused
    LaunchedEffect(activeTab?.url, isAddressFocused) {
        if (!isAddressFocused && activeTab != null) {
            val displayUrl = if (activeTab.url == "about:blank") "" else activeTab.url
            textFieldValue = TextFieldValue(text = displayUrl)
        }
    }

    // Manage WebViews pause/resume
    LaunchedEffect(isVisible, activeTabId, tabs.size) {
        tabs.forEach { tab ->
            val wv = tab.webView ?: return@forEach
            wv.onResume()
        }
        val anyWebView = tabs.firstNotNullOfOrNull { it.webView }
        anyWebView?.resumeTimers()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isVisible, activeTabId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    tabs.forEach { tab ->
                        tab.webView?.onResume()
                    }
                    tabs.firstNotNullOfOrNull { it.webView }?.resumeTimers()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Back button behavior:
    // If exit dialog is shown -> dismiss it.
    // If address bar is focused -> clear focus.
    // If tab overview is shown -> dismiss overview.
    // If active tab WebView has history -> navigate back in history.
    // If no history left -> show exit confirmation dialog (Close Tab, Go to Chat, Stay Here)
    BackHandler(enabled = isVisible) {
        if (showExitConfirmationDialog) {
            showExitConfirmationDialog = false
        } else if (isAddressFocused) {
            focusManager.clearFocus()
            keyboardController?.hide()
        } else if (showTabOverview) {
            showTabOverview = false
        } else if (canBrowserTabGoBack(activeTab?.webView)) {
            footerOffsetPx = 0f
            ignoreScrollUntilTouch = true
            handleBrowserBack(activeTab?.webView) {
                showExitConfirmationDialog = true
            }
        } else {
            showExitConfirmationDialog = true
        }
    }

    fun navigateToUrl(rawUrl: String) {
        val targetUrl = formatBrowserUrl(rawUrl)
        if (targetUrl.isBlank()) return
        // Save to typed history
        sessionManager.addHistory(context, rawUrl, targetUrl)
        textFieldValue = TextFieldValue(text = targetUrl)
        focusManager.clearFocus()
        keyboardController?.hide()
        isControlsVisible = true
        sessionManager.openUrl(targetUrl, tabId = activeTabId, newTab = false)
    }

    fun addNewTab(url: String = "", title: String = "New Tab") {
        activeTab?.capturePreview()
        val newTab = sessionManager.addNewTab(url = url, title = title, activate = true)
        activeTabId = newTab.id
        textFieldValue = TextFieldValue(text = url)
        showTabOverview = false
        isControlsVisible = true
    }

    fun closeTab(tabId: String) {
        sessionManager.closeTab(tabId)
        activeTabId = sessionManager.activeTabId
        if (sessionManager.tabs.isEmpty()) {
            showTabOverview = false
            onClose()
        }
    }



    LaunchedEffect(isAddressFocused, showTabOverview, isHomePage, activeTabId, activeTab?.url) {
        footerOffsetPx = 0f
        ignoreScrollUntilTouch = true
    }

    if (showTabOverview) {
        // Tab Overview Grid Screen (Screenshot 2)
        TabOverviewScreen(
            tabs = tabs,
            activeTabId = activeTabId,
            isDarkTheme = isDarkTheme,
            onSelectTab = { tabId ->
                activeTabId = tabId
                sessionManager.activeTabId = tabId
                showTabOverview = false
            },
            onCloseTab = { tabId ->
                closeTab(tabId)
            },
            onNewTab = {
                addNewTab()
            },
            onDeleteAllTabs = {
                sessionManager.closeAllTabs()
                showTabOverview = false
                onClose()
            },
            onCloseOverview = {
                showTabOverview = false
            }
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = barBackgroundColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = barBackgroundColor,
                shadowElevation = 2.dp,
                border = if (!isDarkTheme) BorderStroke(0.5.dp, Color(0xFFE5E7EB)) else null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 1. DevTools / Eruda Web Inspector Button (Left 1)
                        val hasActivePage = activeTab != null && activeTab.url.isNotBlank() && activeTab.url != "about:blank"
                        val isDevToolsOn = activeTab?.isDevToolsEnabled == true
                        IconButton(
                            onClick = {
                                if (activeTab != null) {
                                    val wv = activeTab.webView ?: sessionManager.ensureWebViewAttached(context, activeTab, isDarkTheme)
                                    if (!isDevToolsOn) {
                                        activeTab.isDevToolsEnabled = true
                                        ErudaHelper.inject(wv, showImmediately = true)
                                    } else {
                                        ErudaHelper.toggle(wv)
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = "Inspect DevTools",
                                tint = if (isDevToolsOn) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = if (hasActivePage) 0.85f else 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 2. Desktop / Phone UA Mode Toggle Button (Left 2)
                        val isDesktopMode = activeTab?.isDesktopMode == true
                        IconButton(
                            onClick = {
                                sessionManager.toggleDesktopMode(activeTab, context)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isDesktopMode) Icons.Default.DesktopWindows else Icons.Default.Smartphone,
                                contentDescription = if (isDesktopMode) "Desktop mode enabled" else "Mobile mode enabled",
                                tint = if (isDesktopMode) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 3. Search / URL Pill (Center pill with select-all on click & clear button)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (!isAddressFocused) {
                                        shouldSelectAllOnNextValueChange = true
                                        focusRequester.requestFocus()
                                    }
                                },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isDarkTheme) Color(0xFF2B2B2B) else Color(0xFFEFEFEF),
                            border = BorderStroke(
                                1.dp,
                                if (isAddressFocused) ClaudeTerracotta.copy(alpha = 0.7f)
                                else if (isDarkTheme) Color(0xFF383838) else Color(0xFFE0E0E0)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isHttps = activeTab?.url?.startsWith("https://", ignoreCase = true) == true
                                val isLocal = activeTab?.url?.contains("localhost") == true || activeTab?.url?.contains("127.0.0.1") == true
                                Icon(
                                    imageVector = if (isHttps) Icons.Default.Lock else if (isLocal) Icons.Default.Computer else Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (isHttps) Color(0xFF22C55E) else if (isLocal) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                BasicTextField(
                                    value = textFieldValue,
                                    onValueChange = { newVal ->
                                        if (shouldSelectAllOnNextValueChange) {
                                            shouldSelectAllOnNextValueChange = false
                                            if (newVal.text == textFieldValue.text && newVal.text.isNotEmpty()) {
                                                textFieldValue = newVal.copy(selection = TextRange(0, newVal.text.length))
                                            } else {
                                                textFieldValue = newVal
                                            }
                                        } else {
                                            textFieldValue = newVal
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { focusState ->
                                            val wasFocused = isAddressFocused
                                            isAddressFocused = focusState.isFocused
                                            if (focusState.isFocused && !wasFocused) {
                                                shouldSelectAllOnNextValueChange = true
                                                if (textFieldValue.text.isNotEmpty()) {
                                                    textFieldValue = textFieldValue.copy(
                                                        selection = TextRange(0, textFieldValue.text.length)
                                                    )
                                                }
                                            } else if (!focusState.isFocused) {
                                                shouldSelectAllOnNextValueChange = false
                                            }
                                        },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(ClaudeTerracotta),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = { navigateToUrl(textFieldValue.text) }
                                    ),
                                    decorationBox = { innerTextField ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (textFieldValue.text.isEmpty() && !isAddressFocused) {
                                                Text(
                                                    text = "Search or type URL",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                                    fontSize = 13.5.sp
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )

                                if (textFieldValue.text.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            textFieldValue = TextFieldValue("")
                                            focusRequester.requestFocus()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Clear",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 4. Refresh / Stop Loading Button (Right 1)
                        IconButton(
                            onClick = {
                                if (activeTab?.isLoading == true) {
                                    activeTab.stopLoading(sessionManager.getMainHandler())
                                } else if (hasActivePage) {
                                    sessionManager.reloadTab(activeTab.id)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab?.isLoading == true) "Stop" else "Reload",
                                tint = if (activeTab?.isLoading == true || hasActivePage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 5. Close / Exit to Chat Button (Right 2)
                        IconButton(
                            onClick = {
                                onClose()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close to Chat",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        if (coords.size.height > 0) {
                            footerHeightPx = coords.size.height.toFloat()
                        }
                    }
                    .graphicsLayer {
                        translationY = footerOffsetPx
                    },
                color = barBackgroundColor,
                shadowElevation = 8.dp,
                border = if (!isDarkTheme) BorderStroke(0.5.dp, Color(0xFFE5E7EB)) else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(52.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canGoBack = canBrowserTabGoBack(activeTab?.webView)
                    val canGoForward = activeTab?.webView?.canGoForward() == true

                    // 1. Back in WebView (<)
                    IconButton(
                        onClick = {
                            footerOffsetPx = 0f
                            ignoreScrollUntilTouch = true
                            handleBrowserBack(activeTab?.webView) {
                                // If back history exhausted, navigate to home speed dial
                                sessionManager.openUrl("about:blank", tabId = activeTabId, newTab = false)
                                textFieldValue = TextFieldValue("")
                            }
                        },
                        enabled = canGoBack,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 2. Forward in WebView (>)
                    IconButton(
                        onClick = {
                            footerOffsetPx = 0f
                            ignoreScrollUntilTouch = true
                            activeTab?.webView?.goForward()
                        },
                        enabled = canGoForward,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 3. Home Button (🏠)
                    IconButton(
                        onClick = {
                            sessionManager.openUrl("about:blank", tabId = activeTabId, newTab = false)
                            textFieldValue = TextFieldValue("")
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Home,
                            contentDescription = "Home",
                            tint = if (isHomePage) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 4. Tabs Badge Switcher ([ N ])
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                activeTab?.capturePreview()
                                showTabOverview = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(26.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.8.dp, MaterialTheme.colorScheme.onSurface)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${tabs.size.coerceAtLeast(1)}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // 5. More Menu Button (⋮)
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New Tab") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    addNewTab()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (activeTab?.isDesktopMode == true) "Mobile Site" else "Desktop Site") },
                                leadingIcon = { Icon(Icons.Default.DesktopWindows, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    sessionManager.toggleDesktopMode(activeTab, context)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("DevTools Inspector") },
                                leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    if (activeTab != null) {
                                        val wv = activeTab.webView ?: sessionManager.ensureWebViewAttached(context, activeTab, isDarkTheme)
                                        if (activeTab.isDevToolsEnabled) {
                                            ErudaHelper.toggle(wv)
                                        } else {
                                            activeTab.isDevToolsEnabled = true
                                            ErudaHelper.inject(wv, showImmediately = true)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Tab Cache") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    activeTab?.webView?.clearCache(true)
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Close Browser", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMoreMenu = false
                                    onClose()
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Persistent WebViews in fixed full-screen overlay architecture
            tabs.forEach { tab ->
                key(tab.id) {
                    val isActive = tab.id == activeTabId
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (isActive && !isHomePage) 1f else 0f
                                translationX = if (isActive && !isHomePage) 0f else -20000f
                            }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val wv = sessionManager.ensureWebViewAttached(ctx, tab, isDarkTheme)
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                wv.layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                SwipeRefreshLayout(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setColorSchemeColors(ClaudeTerracotta.toArgb(), 0xFFD97706.toInt())
                                    setProgressBackgroundColorSchemeColor(if (isDarkTheme) 0xFF2A2826.toInt() else android.graphics.Color.WHITE)
                                    setOnRefreshListener {
                                        val currentWv = tab.webView ?: wv
                                        currentWv.reload()
                                    }
                                    addView(wv)
                                }
                            },
                            update = { swipeRefresh ->
                                val wv = sessionManager.ensureWebViewAttached(swipeRefresh.context, tab, isDarkTheme)
                                if (wv.parent != swipeRefresh) {
                                    (wv.parent as? ViewGroup)?.removeView(wv)
                                    swipeRefresh.removeAllViews()
                                    swipeRefresh.addView(wv)
                                }
                                tab.webView = wv
                                wv.setBackgroundColor(android.graphics.Color.WHITE)
                                @Suppress("DEPRECATION")
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    wv.settings.isAlgorithmicDarkeningAllowed = false
                                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    wv.settings.forceDark = WebSettings.FORCE_DARK_OFF
                                }

                                swipeRefresh.setProgressBackgroundColorSchemeColor(if (isDarkTheme) 0xFF2A2826.toInt() else android.graphics.Color.WHITE)
                                swipeRefresh.setColorSchemeColors(ClaudeTerracotta.toArgb(), 0xFFD97706.toInt())

                                val isHome = tab.url.isBlank() || tab.url == "about:blank"
                                swipeRefresh.isEnabled = !isHome && !showTabOverview && !isAddressFocused
                                if (!swipeRefresh.isEnabled || !tab.isLoading) {
                                    swipeRefresh.isRefreshing = false
                                }

                                wv.setOnTouchListener { _, event ->
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                        ignoreScrollUntilTouch = false
                                    }
                                    false
                                }

                                wv.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                                    tab.scrollY = scrollY
                                    val isHomeNow = tab.url.isBlank() || tab.url == "about:blank"
                                    if (isHomeNow || showTabOverview || isAddressFocused) {
                                        if (footerOffsetPx != 0f) footerOffsetPx = 0f
                                        return@setOnScrollChangeListener
                                    }
                                    if (ignoreScrollUntilTouch) {
                                        return@setOnScrollChangeListener
                                    }
                                    if (scrollY <= 10) {
                                        footerOffsetPx = 0f
                                    } else {
                                        val dy = scrollY - oldScrollY
                                        val maxOffset = if (footerHeightPx > 0f) footerHeightPx else defaultMaxFooterOffsetPx
                                        footerOffsetPx = (footerOffsetPx + dy.toFloat()).coerceIn(0f, maxOffset)
                                    }
                                }

                                if (isActive && isVisible && !isHomePage) {
                                    wv.onResume()
                                } else if (!tab.isBackgroundActive) {
                                    wv.onPause()
                                }
                            },
                            onRelease = { swipeRefresh ->
                                swipeRefresh.removeAllViews()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Progress Bar (Pinned right below header)
            val animatedProgress by animateFloatAsState(
                targetValue = if (activeTab?.isLoading == true) (activeTab.progress / 100f).coerceIn(0.06f, 1f) else 0f,
                label = "progress"
            )

            if (activeTab?.isLoading == true && animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .height(2.5.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = ClaudeTerracotta,
                        trackColor = Color.Transparent
                    )
                }
            }

            // Speed Dial Home View
            if (isHomePage) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = paddingValues.calculateBottomPadding())
                ) {
                    SpeedDialHomeView(
                        shortcuts = sessionManager.shortcuts,
                        isDark = isDarkTheme,
                        onOpenShortcut = { shortcut ->
                            navigateToUrl(shortcut.url)
                        },
                        onAddShortcut = { title, url ->
                            sessionManager.addShortcut(context, title, url)
                        },
                        onRemoveShortcut = { id ->
                            sessionManager.removeShortcut(context, id)
                        }
                    )
                }
            }

            // Search & Typed History Dropdown Overlay (Shown on search bar focus)
            if (isAddressFocused) {
                SearchHistoryOverlay(
                    currentInput = textFieldValue.text,
                    historyItems = sessionManager.typedHistory,
                    isDark = isDarkTheme,
                    onSelectHistory = { targetUrl ->
                        navigateToUrl(targetUrl)
                    },
                    onInsertHistory = { queryOrUrl ->
                        textFieldValue = TextFieldValue(
                            text = queryOrUrl,
                            selection = TextRange(queryOrUrl.length)
                        )
                    },
                    onRemoveHistory = { id ->
                        sessionManager.removeHistory(context, id)
                    },
                    onClearAllHistory = {
                        sessionManager.clearHistory(context)
                    },
                    onDismiss = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                )
            }

            // Exit Confirmation Dialog (On back press when no history remains)
            if (showExitConfirmationDialog) {
                AlertDialog(
                    onDismissRequest = { showExitConfirmationDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Leave Browser?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = "Do you want to close this tab, return to chat, or stay here?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showExitConfirmationDialog = false
                                onClose()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text("Go to Chat")
                        }
                    },
                    dismissButton = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showExitConfirmationDialog = false }
                            ) {
                                Text("Stay Here")
                            }
                            TextButton(
                                onClick = {
                                    showExitConfirmationDialog = false
                                    activeTabId?.let { tabId -> closeTab(tabId) }
                                    onClose()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Close Tab")
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Search and Typed History Overlay (Popup when clicking/focusing search bar).
 * Displays search query suggestions, visited/entered URLs, and instant completion.
 */
@Composable
private fun SearchHistoryOverlay(
    currentInput: String,
    historyItems: List<BrowserSessionManager.TypedHistoryItem>,
    isDark: Boolean,
    onSelectHistory: (String) -> Unit,
    onInsertHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trimmed = currentInput.trim()
    val filteredHistory = remember(trimmed, historyItems.size) {
        if (trimmed.isBlank()) {
            historyItems.take(15)
        } else {
            historyItems.filter {
                it.query.contains(trimmed, ignoreCase = true) || it.url.contains(trimmed, ignoreCase = true)
            }.take(15)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clickable(enabled = false) {}, // prevent click-through
            shape = RoundedCornerShape(16.dp),
            color = if (isDark) Color(0xFF222222) else Color.White,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, if (isDark) Color(0xFF333333) else Color(0xFFE5E7EB))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Header if typing something
                if (trimmed.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectHistory(trimmed) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Search Google for \"$trimmed\"",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Go",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = 135f } // North-East Arrow
                        )
                    }
                    HorizontalDivider(color = if (isDark) Color(0xFF2E2E2E) else Color(0xFFF0F0F0))
                }

                if (filteredHistory.isEmpty()) {
                    if (trimmed.isBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No recent search history",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (trimmed.isBlank()) "Recent Searches" else "History Suggestions",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        if (trimmed.isBlank() && historyItems.isNotEmpty()) {
                            Text(
                                text = "Clear All",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onClearAllHistory() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        items(filteredHistory, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectHistory(item.url) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.query,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.url != item.query) {
                                        Text(
                                            text = item.url,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Copy into input button
                                IconButton(
                                    onClick = { onInsertHistory(item.query) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = "Insert",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .size(14.dp)
                                            .graphicsLayer { rotationZ = 45f } // North-West Arrow
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Remove individual item
                                IconButton(
                                    onClick = { onRemoveHistory(item.id) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Speed Dial Home View (Clean circular layout matching home page design)
 * 5-column circular shortcut items with padding, clean iconography, and add shortcut button.
 */
@Composable
private fun SpeedDialHomeView(
    shortcuts: List<BrowserSessionManager.SpeedDialShortcut>,
    isDark: Boolean,
    onOpenShortcut: (BrowserSessionManager.SpeedDialShortcut) -> Unit,
    onAddShortcut: (String, String) -> Unit,
    onRemoveShortcut: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var shortcutToDelete by remember { mutableStateOf<BrowserSessionManager.SpeedDialShortcut?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val itemsList = shortcuts
        val totalCount = itemsList.size + 1 // shortcuts + 1 add button
        val columns = 5
        val rows = (totalCount + columns - 1) / columns

        for (rowIndex in 0 until rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (colIndex in 0 until columns) {
                    val itemIndex = rowIndex * columns + colIndex
                    if (itemIndex < itemsList.size) {
                        val shortcut = itemsList[itemIndex]
                        ShortcutItemView(
                            shortcut = shortcut,
                            isDark = isDark,
                            onClick = { onOpenShortcut(shortcut) },
                            onLongClick = { shortcutToDelete = shortcut }
                        )
                    } else if (itemIndex == itemsList.size) {
                        AddShortcutItemView(
                            isDark = isDark,
                            onClick = { showAddDialog = true }
                        )
                    } else {
                        Spacer(modifier = Modifier.width(62.dp))
                    }
                }
            }
        }
    }

    // Add Shortcut Dialog
    if (showAddDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newUrl by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Shortcut", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. GitHub") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://github.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitle.isNotBlank() && newUrl.isNotBlank()) {
                            onAddShortcut(newTitle.trim(), newUrl.trim())
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Shortcut Confirmation Dialog
    shortcutToDelete?.let { sc ->
        AlertDialog(
            onDismissRequest = { shortcutToDelete = null },
            title = { Text("Remove Shortcut") },
            text = { Text("Remove '${sc.title}' from your home shortcuts?") },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveShortcut(sc.id)
                        shortcutToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { shortcutToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Individual Speed Dial Circular Shortcut Item (with padded circle and clean typography)
 */
@Composable
private fun ShortcutItemView(
    shortcut: BrowserSessionManager.SpeedDialShortcut,
    isDark: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val circleBg = if (isDark) Color(0xFF242426) else Color(0xFFF3F4F6)
    val circleBorder = if (isDark) Color(0xFF38383A) else Color(0xFFE5E7EB)

    val isGoogle = shortcut.title.equals("google", ignoreCase = true)
    val isYouTube = shortcut.title.equals("youtube", ignoreCase = true)

    Column(
        modifier = Modifier
            .width(62.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(circleBg)
                .border(BorderStroke(1.dp, circleBorder), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            when {
                isGoogle -> {
                    Text(
                        text = "G",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4285F4),
                        fontFamily = FontFamily.SansSerif
                    )
                }
                isYouTube -> {
                    Text(
                        text = "▶",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF0000)
                    )
                }
                else -> {
                    Text(
                        text = shortcut.title.take(1).uppercase(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = shortcut.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * "+" Button for Adding Speed Dial Shortcut
 */
@Composable
private fun AddShortcutItemView(
    isDark: Boolean,
    onClick: () -> Unit
) {
    val circleBg = if (isDark) Color(0xFF242426) else Color(0xFFF3F4F6)
    val circleBorder = if (isDark) Color(0xFF38383A) else Color(0xFFE5E7EB)

    Column(
        modifier = Modifier
            .width(62.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(circleBg)
                .border(BorderStroke(1.dp, circleBorder), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add shortcut",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = "Add",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Tab Overview Screen (Matches Screenshot 2)
 * Features a 2-column card grid with preview thumbnails, active outline, and bottom "Delete all" / "New tab".
 */
@Composable
private fun TabOverviewScreen(
    tabs: List<BrowserTabSession>,
    activeTabId: String?,
    isDarkTheme: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDeleteAllTabs: () -> Unit,
    onCloseOverview: () -> Unit
) {
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var recentlyClosedTab by remember { mutableStateOf<BrowserTabSession?>(null) }
    var showUndoSnackbar by remember { mutableStateOf(false) }

    fun handleCloseTab(tabId: String) {
        val tab = tabs.find { it.id == tabId }
        if (tab != null) {
            recentlyClosedTab = tab
            showUndoSnackbar = true
        }
        onCloseTab(tabId)
    }

    LaunchedEffect(showUndoSnackbar) {
        if (showUndoSnackbar) {
            kotlinx.coroutines.delay(4000)
            showUndoSnackbar = false
            recentlyClosedTab = null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (isDarkTheme) Color(0xFF141211) else Color(0xFFF2F2F7),
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDarkTheme) Color(0xFF1E1E1E) else Color.White,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onCloseOverview) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(26.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.8.dp, MaterialTheme.colorScheme.onSurface)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${tabs.size}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Text(
                            text = "Tabs (${tabs.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextButton(onClick = onCloseOverview) {
                        Text("Done", fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDarkTheme) Color(0xFF1E1E1E) else Color.White,
                shadowElevation = 8.dp,
                border = if (!isDarkTheme) BorderStroke(0.5.dp, Color(0xFFE5E7EB)) else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(54.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // "Delete all" on bottom left
                    TextButton(
                        onClick = { showDeleteAllConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete all", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // "+" New Tab on bottom right
                    Button(
                        onClick = onNewTab,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClaudeTerracotta,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New tab", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val isSelected = tab.id == activeTabId
                    TabCardView(
                        tab = tab,
                        isSelected = isSelected,
                        isDark = isDarkTheme,
                        onClick = { onSelectTab(tab.id) },
                        onClose = { handleCloseTab(tab.id) }
                    )
                }
            }

            // Floating Undo Banner for closed tab
            AnimatedVisibility(
                visible = showUndoSnackbar && recentlyClosedTab != null,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDarkTheme) Color(0xFF2C2C2C) else Color(0xFF323232),
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, if (isDarkTheme) Color(0xFF444444) else Color(0xFF555555))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Tab closed",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Undo",
                            color = ClaudeTerracotta,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val toRestore = recentlyClosedTab
                                    if (toRestore != null) {
                                        BrowserSessionManager.instance.addNewTab(
                                            url = toRestore.url,
                                            title = toRestore.title,
                                            activate = true,
                                            desktopMode = toRestore.isDesktopMode
                                        )
                                    }
                                    showUndoSnackbar = false
                                    recentlyClosedTab = null
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("Close All Tabs") },
            text = { Text("Are you sure you want to close all open tabs and return to chat?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        onDeleteAllTabs()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Close All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Individual Tab Card in 2-Column Overview Grid (Screenshot 2)
 */
@Composable
private fun TabCardView(
    tab: BrowserTabSession,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .graphicsLayer {
                translationX = offsetX
                alpha = (1f - (kotlin.math.abs(offsetX) / 280f)).coerceIn(0f, 1f)
            }
            .pointerInput(tab.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(offsetX) > 90f) {
                            onClose()
                        } else {
                            offsetX = 0f
                        }
                    },
                    onDragCancel = {
                        offsetX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX += dragAmount
                    }
                )
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = if (isDark) Color(0xFF222222) else Color.White,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) ClaudeTerracotta else if (isDark) Color(0xFF333333) else Color(0xFFE5E7EB)
        ),
        shadowElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Card Header: Favicon/Icon + Title + Close Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(if (isDark) Color(0xFF2C2C2C) else Color(0xFFF3F4F6))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (tab.url.startsWith("https://")) Icons.Default.Lock else Icons.Outlined.Language,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (tab.url.startsWith("https://")) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tab.title.ifBlank { if (tab.url.isBlank()) "Home" else tab.url },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close tab",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Card Body: Live Preview Screenshot or Placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) Color(0xFF181818) else Color(0xFFFAFAFA)),
                contentAlignment = Alignment.TopCenter
            ) {
                val preview = tab.previewBitmap
                if (preview != null && !preview.isRecycled) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (tab.url.isBlank() || tab.url == "about:blank") Icons.Outlined.Home else Icons.Outlined.Language,
                            contentDescription = null,
                            tint = ClaudeTerracotta.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (tab.url.isBlank() || tab.url == "about:blank") "Home" else tab.url.substringAfter("://").substringBefore("/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Diagnostic Error & Stall Overlay shown when a web page fails to load or hangs.
 */
@Composable
fun BrowserDiagnosticOverlay(
    tab: BrowserTabSession,
    isDark: Boolean,
    onReload: () -> Unit,
    onSearchGoogle: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    val lastErr = tab.lastError
    val isStalled = tab.isStalled
    val targetUrl = lastErr?.url ?: tab.url

    val title = when {
        isStalled -> "Page Loading Stalled"
        lastErr?.description?.contains("SSL", ignoreCase = true) == true -> "SSL Security Warning"
        lastErr?.errorCode == -1 -> "Renderer Process Crashed"
        else -> "Unable to Load Page"
    }

    val description = when {
        isStalled -> "The page is taking unusually long to respond (timeout > 15s). The local server or remote site might be unresponsive."
        !lastErr?.description.isNullOrBlank() -> lastErr?.description ?: "An unexpected network or connection error occurred."
        else -> "Could not connect to the server or display the requested URL."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDark) Color(0xFF141211).copy(alpha = 0.94f)
                else Color(0xFFFAF9F6).copy(alpha = 0.96f)
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) Color(0xFF23201D) else Color.White
            ),
            border = BorderStroke(1.dp, if (isDark) Color(0xFF383430) else Color(0xFFE5E7EB)),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isStalled) Icons.Outlined.HourglassEmpty else Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFEDE8DF) else Color(0xFF181513),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Failing URL Pill
                if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isDark) Color(0xFF2C2825) else Color(0xFFF3F4F6),
                        border = BorderStroke(0.8.dp, if (isDark) Color(0xFF383430) else Color(0xFFE5E7EB)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = targetUrl,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (isDark) Color(0xFFEDE8DF) else Color(0xFF181513)
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Error Description
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) Color(0xFFB8B2A8) else Color(0xFF525252),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Expandable Telemetry / Console Logs
                val logCount = tab.consoleLogs.size
                val netCount = tab.networkErrors.size
                val recentLogs = remember(logCount, netCount) {
                    tab.consoleLogs.toList().takeLast(5)
                }
                if (recentLogs.isNotEmpty()) {
                    Surface(
                        onClick = { showDetails = !showDetails },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (showDetails) "Hide Diagnostic Logs" else "Show Diagnostic Logs (${recentLogs.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = ClaudeTerracotta
                            )
                            Icon(
                                imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    AnimatedVisibility(visible = showDetails) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDark) Color(0xFF100E0D) else Color(0xFFF3F4F6),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                for (log in recentLogs) {
                                    val errColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    Text(
                                        text = "[${log.level}] ${log.message}",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = if (log.level == "ERROR") Color(0xFFEF4444) else errColor
                                        ),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onReload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClaudeTerracotta,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reload", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onSearchGoogle,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(42.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Search", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Dismiss", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
