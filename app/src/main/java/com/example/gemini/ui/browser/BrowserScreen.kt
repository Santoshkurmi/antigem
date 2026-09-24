package com.example.gemini.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    initialUrl: String = "http://localhost:3000",
    initialTitle: String = "localhost:3000"
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var webView: WebView? = null
}

private val QUICK_PRESETS = listOf(
    "localhost:3000" to "React / Next.js",
    "localhost:5173" to "Vite / Vue",
    "localhost:8080" to "HTTP Server",
    "localhost:8000" to "FastAPI / Django",
    "localhost:4200" to "Angular",
    "127.0.0.1:5000" to "Flask / Python"
)

private fun formatBrowserUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return "about:blank"
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
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Initialize with first tab if empty
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val initialTab = BrowserTab(
                initialUrl = "http://localhost:3000",
                initialTitle = "localhost:3000"
            )
            tabs.add(initialTab)
            activeTabId = initialTab.id
            addressInput = initialTab.url
        }
    }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    // Sync address input when active tab changes and address bar is not focused
    LaunchedEffect(activeTab?.url, isAddressFocused) {
        if (!isAddressFocused && activeTab != null) {
            addressInput = if (activeTab.url == "about:blank") "" else activeTab.url
        }
    }

    // Intercept back button only when webview can navigate backwards
    BackHandler(enabled = isVisible && activeTab?.canGoBack == true) {
        activeTab?.webView?.goBack()
    }

    fun navigateToUrl(rawUrl: String) {
        val targetUrl = formatBrowserUrl(rawUrl)
        addressInput = targetUrl
        focusManager.clearFocus()
        keyboardController?.hide()
        activeTab?.let { tab ->
            tab.url = targetUrl
            tab.webView?.loadUrl(targetUrl)
        }
    }

    fun addNewTab(url: String = "http://localhost:3000", title: String = "localhost:3000") {
        val newTab = BrowserTab(initialUrl = url, initialTitle = title)
        tabs.add(newTab)
        activeTabId = newTab.id
        addressInput = url
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
                val freshTab = BrowserTab(
                    initialUrl = "http://localhost:3000",
                    initialTitle = "localhost:3000"
                )
                tabs.add(freshTab)
                activeTabId = freshTab.id
                addressInput = freshTab.url
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
        ) {
            // Top Navigation & Address Bar Row
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

                        // Back Button
                        IconButton(
                            onClick = { activeTab?.webView?.goBack() },
                            enabled = activeTab?.canGoBack == true,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (activeTab?.canGoBack == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Forward Button
                        IconButton(
                            onClick = { activeTab?.webView?.goForward() },
                            enabled = activeTab?.canGoForward == true,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (activeTab?.canGoForward == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Refresh / Stop Button
                        IconButton(
                            onClick = {
                                if (activeTab?.isLoading == true) {
                                    activeTab.webView?.stopLoading()
                                } else {
                                    activeTab?.webView?.reload()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab?.isLoading == true) "Stop" else "Reload",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Address Search Bar
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
                                Icon(
                                    imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isHttps) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
                                                text = "Enter URL or search term...",
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

                        // New Tab Button
                        IconButton(
                            onClick = { addNewTab() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Tab",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Loading Progress Indicator
                    val animatedProgress by animateFloatAsState(
                        targetValue = if (activeTab?.isLoading == true) (activeTab.progress / 100f).coerceIn(0.05f, 1f) else 0f,
                        label = "progress"
                    )

                    if (activeTab?.isLoading == true && animatedProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = ClaudeTerracotta,
                            trackColor = Color.Transparent
                        )
                    }

                    // Tab Bar Row (Scrollable horizontally)
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(tabs, key = { it.id }) { tab ->
                            val isSelected = tab.id == activeTabId
                            Surface(
                                onClick = {
                                    activeTabId = tab.id
                                    addressInput = if (tab.url == "about:blank") "" else tab.url
                                    focusManager.clearFocus()
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.45f)) else null,
                                modifier = Modifier
                                    .height(30.dp)
                                    .widthIn(min = 90.dp, max = 170.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (tab.isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.8.dp,
                                            color = ClaudeTerracotta
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.Public,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Text(
                                        text = tab.title.ifBlank { tab.url },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .clickable { closeTab(tab.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close Tab",
                                            modifier = Modifier.size(11.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            IconButton(
                                onClick = { addNewTab() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Tab",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Quick Localhost Presets Chips (Visible when editing or previewing)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Quick:",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )

                        QUICK_PRESETS.forEach { (host, label) ->
                            val isCurrent = activeTab?.url?.contains(host) == true
                            Surface(
                                onClick = { navigateToUrl("http://$host") },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isCurrent) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(
                                    0.8.dp,
                                    if (isCurrent) ClaudeTerracotta.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier.height(22.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = host,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrent) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // WebViews Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White)
            ) {
                if (tabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No open tabs. Tap '+' to create one.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else {
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
                                            databaseEnabled = true
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
                                                if (!url.isNullOrBlank()) {
                                                    tab.url = url
                                                }
                                                tab.canGoBack = view?.canGoBack() == true
                                                tab.canGoForward = view?.canGoForward() == true
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                tab.isLoading = false
                                                if (!url.isNullOrBlank()) {
                                                    tab.url = url
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
                                        loadUrl(tab.url)
                                    }
                                },
                                update = { wv ->
                                    tab.webView = wv
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
