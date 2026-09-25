package com.example.gemini.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.core.view.WindowCompat
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    return getPreviousValidHistoryIndex(webView) >= 0
}

fun handleBrowserBack(webView: WebView?, onClose: () -> Unit) {
    if (webView == null) {
        onClose()
        return
    }
    val targetIndex = getPreviousValidHistoryIndex(webView)
    if (targetIndex >= 0) {
        val currentIndex = webView.copyBackForwardList().currentIndex
        val steps = targetIndex - currentIndex
        webView.goBackOrForward(steps)
    } else {
        onClose()
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

private fun buildErrorHtml(failingUrl: String, errorDescription: String, isDark: Boolean): String {
    return BrowserSessionManager.buildErrorHtml(failingUrl, errorDescription, isDark)
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
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
    var addressInput by remember { mutableStateOf("") }
    var isAddressFocused by remember { mutableStateOf(false) }
    var showTabSwitcherSheet by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(sessionManager.activeTabId) {
        if (sessionManager.activeTabId != null) {
            activeTabId = sessionManager.activeTabId
        }
    }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    // Sync status bar color and light/dark icons with header
    val view = LocalView.current
    val headerColor = if (isDarkTheme) MaterialTheme.colorScheme.surface else Color.White
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        if (activity != null && activity !is com.example.gemini.ui.bubble.FloatingChatActivity && isVisible) {
            DisposableEffect(isDarkTheme, isVisible, headerColor) {
                val window = activity.window
                val insetsController = WindowCompat.getInsetsController(window, view)
                val originalStatusBarColor = window.statusBarColor
                val originalIsLightStatusBars = insetsController.isAppearanceLightStatusBars

                window.statusBarColor = headerColor.toArgb()
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
            addressInput = if (activeTab.url == "about:blank") "" else activeTab.url
        }
    }

    // Manage WebViews pause/resume without interrupting background AI tasks
    LaunchedEffect(isVisible, activeTabId, tabs.size) {
        tabs.forEach { tab ->
            val wv = tab.webView ?: return@forEach
            val isTabActiveAndVisible = isVisible && (tab.id == activeTabId)
            if (isTabActiveAndVisible || tab.isBackgroundActive) {
                wv.onResume()
            } else {
                wv.onPause()
            }
        }
        val anyWebView = tabs.firstNotNullOfOrNull { it.webView }
        if (isVisible || tabs.any { it.isBackgroundActive }) {
            anyWebView?.resumeTimers()
        } else {
            anyWebView?.pauseTimers()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isVisible, activeTabId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    tabs.forEach { if (!it.isBackgroundActive) it.webView?.onPause() }
                }
                Lifecycle.Event.ON_RESUME -> {
                    tabs.forEach { tab ->
                        val wv = tab.webView ?: return@forEach
                        if ((isVisible && tab.id == activeTabId) || tab.isBackgroundActive) {
                            wv.onResume()
                        } else {
                            wv.onPause()
                        }
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

    // Intercept back button: if sheet open -> close sheet, if active tab has preceding browsing history -> navigate back, else -> go back to chat screen!
    BackHandler(enabled = isVisible) {
        if (showTabSwitcherSheet) {
            showTabSwitcherSheet = false
        } else if (activeTab?.webView != null && canBrowserTabGoBack(activeTab.webView)) {
            handleBrowserBack(activeTab.webView, onClose)
        } else {
            onClose()
        }
    }

    fun navigateToUrl(rawUrl: String) {
        val targetUrl = formatBrowserUrl(rawUrl)
        if (targetUrl.isBlank()) return
        addressInput = targetUrl
        focusManager.clearFocus()
        keyboardController?.hide()
        sessionManager.openUrl(targetUrl, tabId = activeTabId, newTab = false)
    }

    fun addNewTab(url: String = "", title: String = "New Tab") {
        val newTab = sessionManager.addNewTab(url = url, title = title, activate = true)
        activeTabId = newTab.id
        addressInput = url
        showTabSwitcherSheet = false
    }

    fun closeTab(tabId: String) {
        sessionManager.closeTab(tabId)
        activeTabId = sessionManager.activeTabId
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // Top Compact Search Bar & Controls Header extending behind status bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = headerColor,
                tonalElevation = if (isDarkTheme) 2.dp else 0.dp,
                shadowElevation = 1.dp,
                border = if (!isDarkTheme) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)) else null
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
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 1. New Tab "+" Button
                        IconButton(
                            onClick = { addNewTab() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Tab",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 2. Tab Switcher Button (Shows count badge: [ 1 ], [ 2 ], etc.)
                        Surface(
                            onClick = { showTabSwitcherSheet = true },
                            modifier = Modifier.size(32.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color(0xFFF3F4F6),
                            border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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

                        // 3. Address Search Bar (Single Row, clean pill design)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(19.dp),
                            color = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f) else Color(0xFFF3F4F6),
                            border = BorderStroke(
                                1.dp,
                                if (isAddressFocused) ClaudeTerracotta.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isHttps = activeTab?.url?.startsWith("https://", ignoreCase = true) == true
                                val isLocal = activeTab?.url?.contains("localhost") == true || activeTab?.url?.contains("127.0.0.1") == true
                                Icon(
                                    imageVector = if (isHttps) Icons.Default.Lock else if (isLocal) Icons.Default.Computer else Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isHttps) Color(0xFF4CAF50) else if (isLocal) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                BasicTextField(
                                    value = addressInput,
                                    onValueChange = { addressInput = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { isAddressFocused = it.isFocused },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(ClaudeTerracotta),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = { navigateToUrl(addressInput) }
                                    ),
                                    decorationBox = { innerTextField ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (addressInput.isEmpty() && !isAddressFocused) {
                                                Text(
                                                    text = "Search or enter address...",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    fontSize = 13.sp
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )

                                if (addressInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            addressInput = ""
                                            focusRequester.requestFocus()
                                        },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Clear",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 4. Refresh / Stop Button
                        val hasActivePage = activeTab != null && activeTab.url.isNotBlank() && activeTab.url != "about:blank"
                        IconButton(
                            onClick = {
                                if (activeTab?.isLoading == true) {
                                    activeTab.stopLoading(sessionManager.getMainHandler())
                                } else if (hasActivePage) {
                                    sessionManager.reloadTab(activeTab.id)
                                }
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab?.isLoading == true) "Stop" else "Reload",
                                tint = if (activeTab?.isLoading == true || hasActivePage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 5. Close / Exit Browser View Button
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onClose()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Browser",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Loading Progress Indicator (Fixed height to prevent header jitter)
                    val animatedProgress by animateFloatAsState(
                        targetValue = if (activeTab?.isLoading == true) (activeTab.progress / 100f).coerceIn(0.05f, 1f) else 0f,
                        label = "progress"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                    ) {
                        if (activeTab?.isLoading == true && animatedProgress > 0f) {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxSize(),
                                color = ClaudeTerracotta,
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Main Content Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .background(if (isDarkTheme) MaterialTheme.colorScheme.background else Color(0xFFFAF9F6))
        ) {
            // Persistent WebViews: ALWAYS rendered so they never destroy or re-attach on tab switch/navigation
            tabs.forEach { tab ->
                key(tab.id) {
                    val isActive = tab.id == activeTabId
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (isActive) 1f else 0f
                                translationX = if (isActive) 0f else -20000f
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
                                wv
                            },
                            update = { wv ->
                                tab.webView = wv
                                wv.setBackgroundColor(if (isDarkTheme) 0xFF181513.toInt() else android.graphics.Color.WHITE)
                                @Suppress("DEPRECATION")
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    wv.settings.isAlgorithmicDarkeningAllowed = isDarkTheme
                                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    wv.settings.forceDark = if (isDarkTheme) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                                }
                                if (isActive && isVisible) {
                                    wv.onResume()
                                } else if (!tab.isBackgroundActive) {
                                    wv.onPause()
                                }
                            },
                            onRelease = { wv ->
                                (wv.parent as? ViewGroup)?.removeView(wv)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Cute Landing Page Overlay (shown only when active tab is empty / new tab)
            val isLandingPage = activeTab == null || activeTab.url.isBlank() || activeTab.url == "about:blank"
            if (isLandingPage) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isDarkTheme) MaterialTheme.colorScheme.background else Color(0xFFFAF9F6))
                ) {
                    NewTabLandingView(
                        onLaunchPort = { port ->
                            navigateToUrl("http://localhost:$port")
                        },
                        onNavigateUrl = { url ->
                            navigateToUrl(url)
                        }
                    )
                }
            } else if (activeTab != null && (activeTab.lastError != null || activeTab.isStalled)) {
                val currentTab = activeTab
                // In-App Diagnostic Error Overlay
                BrowserDiagnosticOverlay(
                    tab = currentTab,
                    isDark = isDarkTheme,
                    onReload = {
                        currentTab.clearError()
                        currentTab.isLoading = true
                        currentTab.webView?.reload() ?: navigateToUrl(currentTab.url)
                    },
                    onSearchGoogle = {
                        val currentUrl = currentTab.url
                        val encoded = try { URLEncoder.encode(currentUrl, "UTF-8") } catch (_: Exception) { currentUrl }
                        navigateToUrl("https://www.google.com/search?q=$encoded")
                    },
                    onDismiss = {
                        currentTab.clearError()
                    }
                )
            }
        }
    }

    // Modal BottomSheet for Tab Switcher
    if (showTabSwitcherSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTabSwitcherSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open Tabs (${tabs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { addNewTab() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ClaudeTerracotta,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Tab", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        TextButton(
                            onClick = { showTabSwitcherSheet = false },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Done", fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val isSelected = tab.id == activeTabId
                        Surface(
                            onClick = {
                                activeTabId = tab.id
                                addressInput = if (tab.url == "about:blank") "" else tab.url
                                showTabSwitcherSheet = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Language,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .clickable { closeTab(tab.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Column {
                                    Text(
                                        text = tab.title.ifBlank { if (tab.url.isBlank()) "New Tab" else tab.url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = if (tab.url.isBlank()) "Empty Tab" else tab.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
 * Cute, friendly landing view when opening a new tab.
 * Gives the user a clean, instant port launcher and helpful instructions.
 */
@Composable
private fun NewTabLandingView(
    onLaunchPort: (String) -> Unit,
    onNavigateUrl: (String) -> Unit
) {
    var portInput by remember { mutableStateOf("3000") }
    var customUrlInput by remember { mutableStateOf("") }

    val commonPorts = listOf(
        "3000" to "React / Next.js",
        "5173" to "Vite / Vue",
        "8080" to "HTTP Server",
        "8000" to "FastAPI / Django"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Cute Glowing Icon
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(ClaudeTerracotta.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.RocketLaunch,
                contentDescription = null,
                tint = ClaudeTerracotta,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Web Preview",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Preview your React, Vite, or web server apps running locally on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(26.dp))

        // Port Launcher Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Text(
                    text = "Launch Localhost Server",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Port Input Row with localhost prefix
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "http://localhost:",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = ClaudeTerracotta
                        )
                    )

                    BasicTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { ch -> ch.isDigit() }.take(5) },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(ClaudeTerracotta),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (portInput.isNotBlank()) onLaunchPort(portInput)
                            }
                        )
                    )

                    Button(
                        onClick = {
                            if (portInput.isNotBlank()) onLaunchPort(portInput)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClaudeTerracotta,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Open", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Common Port Suggestion Pills
                Text(
                    text = "Quick Presets:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    commonPorts.forEach { (port, label) ->
                        Surface(
                            onClick = {
                                portInput = port
                                onLaunchPort(port)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = ":$port",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = ClaudeTerracotta
                                )
                                Text(
                                    text = label.substringBefore(" / "),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
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
