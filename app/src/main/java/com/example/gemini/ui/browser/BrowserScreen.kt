package com.example.gemini.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    initialUrl: String = "",
    initialTitle: String = "New Tab"
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var webView: WebView? = null
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
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    isVisible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = remember { mutableStateListOf<BrowserTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var addressInput by remember { mutableStateOf("") }
    var isAddressFocused by remember { mutableStateOf(false) }
    var showTabSwitcherSheet by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Initialize with first tab if empty
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val initialTab = BrowserTab(
                initialUrl = "",
                initialTitle = "New Tab"
            )
            tabs.add(initialTab)
            activeTabId = initialTab.id
        }
    }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    // Sync address input when active tab changes and address bar is not focused
    LaunchedEffect(activeTab?.url, isAddressFocused) {
        if (!isAddressFocused && activeTab != null) {
            addressInput = if (activeTab.url == "about:blank") "" else activeTab.url
        }
    }

    // Intercept back button: if sheet open -> close sheet, if webview can go back -> webview.goBack(), else -> go back to chat screen!
    BackHandler(enabled = isVisible) {
        if (showTabSwitcherSheet) {
            showTabSwitcherSheet = false
        } else if (activeTab != null && activeTab.webView?.canGoBack() == true) {
            activeTab.webView?.goBack()
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
        activeTab?.let { tab ->
            tab.url = targetUrl
            tab.webView?.loadUrl(targetUrl)
        }
    }

    fun addNewTab(url: String = "", title: String = "New Tab") {
        val newTab = BrowserTab(initialUrl = url, initialTitle = title)
        tabs.add(newTab)
        activeTabId = newTab.id
        addressInput = url
        showTabSwitcherSheet = false
        if (url.isNotBlank()) {
            newTab.webView?.loadUrl(url)
        }
    }

    fun closeTab(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index != -1) {
            val tabToRemove = tabs[index]
            try {
                tabToRemove.webView?.destroy()
            } catch (e: Exception) {
                Log.w("BrowserScreen", "Error destroying WebView", e)
            }
            tabs.removeAt(index)

            if (tabs.isEmpty()) {
                val freshTab = BrowserTab(initialUrl = "", initialTitle = "New Tab")
                tabs.add(freshTab)
                activeTabId = freshTab.id
                addressInput = ""
            } else if (activeTabId == tabId) {
                val nextIndex = (index - 1).coerceAtLeast(0)
                activeTabId = tabs[nextIndex].id
                addressInput = tabs[nextIndex].url
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            // Top Compact Search Bar & Controls Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Close / Exit Browser View Button
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

                        // Back Button (Enabled only if webview can navigate back)
                        IconButton(
                            onClick = { activeTab?.webView?.goBack() },
                            enabled = activeTab?.canGoBack == true,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (activeTab?.canGoBack == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Forward Button
                        IconButton(
                            onClick = { activeTab?.webView?.goForward() },
                            enabled = activeTab?.canGoForward == true,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (activeTab?.canGoForward == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Address Search Bar (Single Row, clean pill design)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(19.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(
                                1.dp,
                                if (isAddressFocused) ClaudeTerracotta.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
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
                                        if (addressInput.isEmpty() && !isAddressFocused) {
                                            Text(
                                                text = "Search or enter address...",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                fontSize = 13.sp
                                            )
                                        }
                                        innerTextField()
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

                        // Refresh / Stop Button
                        val hasActivePage = activeTab != null && activeTab.url.isNotBlank() && activeTab.url != "about:blank"
                        IconButton(
                            onClick = {
                                if (activeTab?.isLoading == true) {
                                    activeTab.webView?.stopLoading()
                                } else {
                                    activeTab?.webView?.reload()
                                }
                            },
                            enabled = hasActivePage,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab?.isLoading == true) "Stop" else "Reload",
                                tint = if (hasActivePage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Tab Switcher Button (Shows count badge: [ 1 ], [ 2 ], etc.)
                        Surface(
                            onClick = { showTabSwitcherSheet = true },
                            modifier = Modifier.size(32.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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

                        // New Tab "+" Button
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

            // Main Content Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Persistent WebViews: ALWAYS rendered so they never destroy or re-attach on tab switch/navigation
                tabs.forEach { tab ->
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
                                WebView(ctx).apply {
                                    tab.webView = this
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        allowFileAccess = true
                                        allowContentAccess = true
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            tab.isLoading = true
                                            if (!url.isNullOrBlank() && url != "about:blank") {
                                                tab.url = url
                                                if (!isAddressFocused && tab.id == activeTabId) {
                                                    addressInput = url
                                                }
                                            }
                                            tab.canGoBack = view?.canGoBack() == true
                                            tab.canGoForward = view?.canGoForward() == true
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            tab.isLoading = false
                                            if (!url.isNullOrBlank() && url != "about:blank") {
                                                tab.url = url
                                                if (!isAddressFocused && tab.id == activeTabId) {
                                                    addressInput = url
                                                }
                                            }
                                            val pageTitle = view?.title
                                            if (!pageTitle.isNullOrBlank()) {
                                                tab.title = pageTitle
                                            }
                                            tab.canGoBack = view?.canGoBack() == true
                                            tab.canGoForward = view?.canGoForward() == true
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val uri = request?.url ?: return false
                                            val scheme = uri.scheme?.lowercase() ?: return false
                                            if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "about") {
                                                return false
                                            }
                                            return try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                                ctx.startActivity(intent)
                                                true
                                            } catch (_: Exception) {
                                                false
                                            }
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            tab.progress = newProgress
                                            tab.isLoading = newProgress < 100
                                            tab.canGoBack = view?.canGoBack() == true
                                            tab.canGoForward = view?.canGoForward() == true
                                        }

                                        override fun onReceivedTitle(view: WebView?, title: String?) {
                                            if (!title.isNullOrBlank()) {
                                                tab.title = title
                                            }
                                        }
                                    }
                                    if (tab.url.isNotBlank() && tab.url != "about:blank") {
                                        loadUrl(tab.url)
                                    }
                                }
                            },
                            update = { wv ->
                                tab.webView = wv
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Cute Landing Page Overlay (shown only when active tab is empty / new tab)
                val isLandingPage = activeTab == null || activeTab.url.isBlank() || activeTab.url == "about:blank"
                if (isLandingPage) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
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
                }
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
