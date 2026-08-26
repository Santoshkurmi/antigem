package com.example.gemini.data.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * High-power Native Android Accessibility Service for AntiGem AI Automation.
 * Allows Gemini & Claude to inspect UI hierarchy, tap elements by text/description/coordinates,
 * input text, scroll, trigger system buttons, and capture screenshots.
 */
class AndroidAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomationService"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _currentForegroundPackage = MutableStateFlow<String?>(null)
        val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()

        @Volatile
        var instance: AndroidAutomationService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true
        Log.i(TAG, "AndroidAutomationService connected successfully")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _currentForegroundPackage.value = pkg
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AndroidAutomationService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        _isServiceActive.value = false
        Log.i(TAG, "AndroidAutomationService destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UI Hierarchy Inspection & Analysis
    // ─────────────────────────────────────────────────────────────────────────────

    data class UiElementInfo(
        val text: String?,
        val contentDescription: String?,
        val viewId: String?,
        val className: String?,
        val bounds: Rect,
        val isClickable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isFocused: Boolean,
        val isEnabled: Boolean,
        val childCount: Int
    ) {
        val label: String
            get() = when {
                !text.isNullOrBlank() -> text
                !contentDescription.isNullOrBlank() -> contentDescription
                !viewId.isNullOrBlank() -> viewId.substringAfterLast(":id/")
                else -> ""
            }

        val type: String
            get() = className?.substringAfterLast(".") ?: "View"
    }

    data class ScreenAnalysisResult(
        val foregroundPackage: String?,
        val elementCount: Int,
        val actionableElements: List<UiElementInfo>,
        val fullHierarchySummary: String
    )

    fun analyzeScreen(): ScreenAnalysisResult {
        val root = rootInActiveWindow ?: return ScreenAnalysisResult(
            foregroundPackage = _currentForegroundPackage.value,
            elementCount = 0,
            actionableElements = emptyList(),
            fullHierarchySummary = "(No active window content available or screen is locked)"
        )

        val elements = mutableListOf<UiElementInfo>()
        val treeBuilder = StringBuilder()

        traverseNode(root, 0, elements, treeBuilder)

        val pkg = root.packageName?.toString() ?: _currentForegroundPackage.value
        return ScreenAnalysisResult(
            foregroundPackage = pkg,
            elementCount = elements.size,
            actionableElements = elements.filter { it.isClickable || it.isEditable || it.isScrollable || !it.text.isNullOrBlank() },
            fullHierarchySummary = treeBuilder.toString().trim()
        )
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        elements: MutableList<UiElementInfo>,
        treeBuilder: StringBuilder
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName
        val className = node.className?.toString()
        val isClickable = node.isClickable
        val isCheckable = node.isCheckable
        val isChecked = node.isChecked
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isFocused = node.isFocused
        val isEnabled = node.isEnabled
        val childCount = node.childCount

        val element = UiElementInfo(
            text = text,
            contentDescription = desc,
            viewId = viewId,
            className = className,
            bounds = bounds,
            isClickable = isClickable,
            isCheckable = isCheckable,
            isChecked = isChecked,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isFocused = isFocused,
            isEnabled = isEnabled,
            childCount = childCount
        )
        elements.add(element)

        // Only format useful visible elements in tree view
        val isVisible = bounds.width() > 0 && bounds.height() > 0
        if (isVisible && (!text.isNullOrBlank() || !desc.isNullOrBlank() || isClickable || isEditable || isScrollable)) {
            val indent = "  ".repeat(depth)
            val typeShort = className?.substringAfterLast(".") ?: "View"
            val attrs = mutableListOf<String>()
            if (isClickable) attrs.add("clickable")
            if (isEditable) attrs.add("editable")
            if (isScrollable) attrs.add("scrollable")
            if (isChecked) attrs.add("checked")
            if (isFocused) attrs.add("focused")

            val attrStr = if (attrs.isNotEmpty()) " [${attrs.joinToString(", ")}]" else ""
            val centerStr = " @ (${bounds.centerX()}, ${bounds.centerY()})"

            val label = when {
                !text.isNullOrBlank() && !desc.isNullOrBlank() -> "\"$text\" (desc: \"$desc\")"
                !text.isNullOrBlank() -> "\"$text\""
                !desc.isNullOrBlank() -> "(desc: \"$desc\")"
                !viewId.isNullOrBlank() -> "id:${viewId.substringAfterLast(":id/")}"
                else -> ""
            }

            treeBuilder.appendLine("$indent• $typeShort: $label$attrStr$centerStr")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, depth + 1, elements, treeBuilder)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Node Matching & Action Execution
    // ─────────────────────────────────────────────────────────────────────────────

    fun findNodesByQuery(query: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        val qLower = query.lowercase().trim()

        fun search(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            val viewId = node.viewIdResourceName?.lowercase()

            if ((text != null && text.contains(qLower)) ||
                (desc != null && desc.contains(qLower)) ||
                (viewId != null && viewId.contains(qLower))
            ) {
                results.add(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }

        search(root)
        return results
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Try clicking node itself first
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Traverse up to find clickable parent
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }

        // If no node in hierarchy is marked clickable, fallback to dispatching a coordinate tap gesture at center bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return tapAtCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        return false
    }

    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isLongClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isLongClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            parent = parent.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return longPressAtCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        return false
    }

    fun inputTextOnNode(node: AccessibilityNodeInfo, text: String, clearFirst: Boolean = true): Boolean {
        // Focus first
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val res = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (res) return true

        // Fallback: paste into clipboard / node
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun findFirstEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var target: AccessibilityNodeInfo? = null

        fun search(node: AccessibilityNodeInfo) {
            if (target != null) return
            if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                if (node.isFocused) {
                    target = node
                    return
                } else if (target == null) {
                    target = node
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }

        search(root)
        return target
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Gestures: Tap, Long Press, Swipe, Scroll
    // ─────────────────────────────────────────────────────────────────────────────

    fun tapAtCoordinates(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPressAtCoordinates(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow

        // 1. Try finding scrollable node
        if (root != null) {
            val scrollableNodes = mutableListOf<AccessibilityNodeInfo>()
            fun findScrollable(node: AccessibilityNodeInfo) {
                if (node.isScrollable) scrollableNodes.add(node)
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    findScrollable(child)
                }
            }
            findScrollable(root)

            val target = scrollableNodes.firstOrNull()
            if (target != null) {
                val action = when (direction.lowercase()) {
                    "down", "right", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "left", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val res = target.performAction(action)
                if (res) return true
            }
        }

        // 2. Fallback: gesture swipe down / up
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val height = displayMetrics.heightPixels.toFloat()

        return when (direction.lowercase()) {
            "down", "forward" -> swipe(centerX, height * 0.75f, centerX, height * 0.25f, 350L)
            "up", "backward" -> swipe(centerX, height * 0.25f, centerX, height * 0.75f, 350L)
            "left" -> swipe(displayMetrics.widthPixels * 0.85f, height / 2f, displayMetrics.widthPixels * 0.15f, height / 2f, 350L)
            "right" -> swipe(displayMetrics.widthPixels * 0.15f, height / 2f, displayMetrics.widthPixels * 0.85f, height / 2f, 350L)
            else -> swipe(centerX, height * 0.75f, centerX, height * 0.25f, 350L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Global System Actions
    // ─────────────────────────────────────────────────────────────────────────────

    fun performSystemAction(action: String): Boolean {
        val globalAction = when (action.lowercase().trim()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog", "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen", "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else GLOBAL_ACTION_BACK
            "take_screenshot", "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else GLOBAL_ACTION_BACK
            else -> return false
        }
        return performGlobalAction(globalAction)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Native Screenshot Capture (API 30+)
    // ─────────────────────────────────────────────────────────────────────────────

    suspend fun captureScreenshotBase64(): Result<String> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext Result.failure(Exception("Screenshot API requires Android 11+ (API 30)"))
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val executor = Executors.newSingleThreadExecutor()

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.hardwareBuffer,
                            screenshotResult.colorSpace
                        )
                        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshotResult.hardwareBuffer.close()
                        deferred.complete(softwareBitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed with error code: $errorCode")
                        deferred.complete(null)
                    }
                }
            )

            val bitmap = withTimeoutOrNull(3000L) { deferred.await() }
            if (bitmap == null) {
                return@withContext Result.failure(Exception("Failed to capture screenshot or timed out"))
            }

            // Downscale for compact transmission if larger than 1080p
            val maxDim = 1280
            val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val (newW, newH) = if (ratio > 1f) Pair(maxDim, (maxDim / ratio).toInt()) else Pair((maxDim * ratio).toInt(), maxDim)
                Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            } else bitmap

            val stream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val byteArray = stream.toByteArray()
            val b64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            Result.success(b64)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            executor.shutdown()
        }
    }
}
