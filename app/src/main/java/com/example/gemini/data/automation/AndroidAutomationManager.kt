package com.example.gemini.data.automation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Master Android Native Automation Engine.
 * Combines Accessibility Service, App Manager, and Media Controller into a unified API.
 */
class AndroidAutomationManager(private val context: Context) {

    companion object {
        private const val TAG = "AutomationManager"
    }

    private val appManager = AndroidAppManager(context)
    private val mediaController = AndroidMediaController(context)

    private val service: AndroidAutomationService?
        get() = AndroidAutomationService.instance

    fun isAccessibilityActive(): Boolean = AndroidAutomationService.isRunning()
    fun isAccessibilityEnabledInSettings(): Boolean = appManager.isAccessibilityServiceEnabled()
    fun isUsageAccessGranted(): Boolean = appManager.isUsageAccessGranted()

    data class AutomationExecutionResult(
        val isSuccess: Boolean,
        val message: String,
        val data: Any? = null
    )

    /**
     * Executes an automation action requested by the AI.
     */
    suspend fun execute(action: String, payload: JSONObject): AutomationExecutionResult = withContext(Dispatchers.Default) {
        val act = action.lowercase().trim()
        Log.d(TAG, "Executing action '$act' with payload: $payload")

        // Actions that do not require accessibility service
        when (act) {
            "launch_app", "open_app" -> {
                val query = payload.optString("package").ifEmpty {
                    payload.optString("name").ifEmpty {
                        payload.optString("app")
                    }
                }
                if (query.isBlank()) {
                    return@withContext AutomationExecutionResult(false, "Missing 'name' or 'package' parameter for launch_app")
                }
                val res = appManager.launchApp(query)
                return@withContext if (res.isSuccess) {
                    AutomationExecutionResult(true, "✅ ${res.getOrThrow()}")
                } else {
                    AutomationExecutionResult(false, "❌ Failed to launch app: ${res.exceptionOrNull()?.message}")
                }
            }

            "list_apps", "get_installed_apps" -> {
                val query = payload.optString("query")
                val limit = payload.optInt("limit", 40)
                val apps = if (query.isNotBlank()) {
                    appManager.getInstalledApps(includeSystemApps = true).filter {
                        it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                    }
                } else {
                    appManager.getInstalledApps(includeSystemApps = false)
                }

                val formatted = apps.take(limit).joinToString("\n") { "• ${it.name} (`${it.packageName}`)" }
                return@withContext AutomationExecutionResult(
                    true,
                    "📱 Installed Applications (${apps.size} found):\n$formatted"
                )
            }

            "get_running_apps", "recent_apps" -> {
                if (!appManager.isUsageAccessGranted()) {
                    return@withContext AutomationExecutionResult(
                        false,
                        "⚠️ Usage Stats permission is not granted. Please enable Usage Access in Android Settings > Special App Access."
                    )
                }
                val recent = appManager.getRecentApps(limit = payload.optInt("limit", 8))
                val formatted = if (recent.isNotEmpty()) {
                    recent.joinToString("\n") { "• $it" }
                } else {
                    "(No recent app events found in the past hour)"
                }
                return@withContext AutomationExecutionResult(true, "🕒 Recently Used Apps:\n$formatted")
            }

            "media_control" -> {
                val cmd = payload.optString("command").lowercase().trim()
                val ok = when (cmd) {
                    "play" -> mediaController.play()
                    "pause" -> mediaController.pause()
                    "play_pause", "toggle" -> mediaController.togglePlayPause()
                    "next", "skip" -> mediaController.nextTrack()
                    "previous", "prev", "back" -> mediaController.previousTrack()
                    "stop" -> mediaController.stop()
                    "vol_up", "volume_up" -> mediaController.volumeUp()
                    "vol_down", "volume_down" -> mediaController.volumeDown()
                    "mute" -> mediaController.mute()
                    else -> false
                }
                return@withContext if (ok) {
                    AutomationExecutionResult(true, "🎵 Media command '$cmd' executed.\n${mediaController.getMediaStatus()}")
                } else {
                    AutomationExecutionResult(false, "❌ Failed to execute media command '$cmd'")
                }
            }

            "get_media_info", "media_status" -> {
                return@withContext AutomationExecutionResult(true, mediaController.getMediaStatus())
            }

            "check_status", "check_permissions" -> {
                val accRunning = isAccessibilityActive()
                val accEnabled = isAccessibilityEnabledInSettings()
                val usage = isUsageAccessGranted()
                val statusMsg = buildString {
                    appendLine("⚙️ Android Automation Status:")
                    appendLine("• Accessibility Service: ${if (accRunning) "🟢 Running & Active" else if (accEnabled) "🟡 Enabled in Settings (reconnecting...)" else "🔴 Disabled"}")
                    appendLine("• Usage Stats Access: ${if (usage) "🟢 Granted" else "🟡 Not Granted"}")
                }
                return@withContext AutomationExecutionResult(accRunning, statusMsg.trim())
            }
        }

        // Accessibility-dependent actions
        val svc = service
        if (svc == null) {
            return@withContext AutomationExecutionResult(
                false,
                "⚠️ AntiGem Accessibility Service is not active.\nPlease enable it in Android Settings > Accessibility > AntiGem AI Automation."
            )
        }

        when (act) {
            "analyze_screen", "get_screen_elements", "inspect_screen" -> {
                val result = svc.analyzeScreen()
                val summary = buildString {
                    appendLine("📱 Active App: ${result.foregroundPackage ?: "Unknown"}")
                    appendLine("Total Elements: ${result.elementCount} (${result.actionableElements.size} interactive)")
                    appendLine("\n--- Interactive Elements Hierarchy ---")
                    appendLine(result.fullHierarchySummary.ifEmpty { "(No interactive elements found on screen)" })
                }
                return@withContext AutomationExecutionResult(true, summary.trim())
            }

            "tap", "click" -> {
                // Check if coordinate tap
                if (payload.has("x") && payload.has("y")) {
                    val x = payload.getDouble("x").toFloat()
                    val y = payload.getDouble("y").toFloat()
                    val ok = svc.tapAtCoordinates(x, y)
                    return@withContext if (ok) {
                        AutomationExecutionResult(true, "✅ Tapped at coordinates ($x, $y)")
                    } else {
                        AutomationExecutionResult(false, "❌ Failed to dispatch tap gesture at ($x, $y)")
                    }
                }

                // Check text or description query
                val query = payload.optString("text").ifEmpty {
                    payload.optString("description").ifEmpty {
                        payload.optString("query").ifEmpty {
                            payload.optString("id")
                        }
                    }
                }

                if (query.isBlank()) {
                    return@withContext AutomationExecutionResult(false, "Missing 'text', 'description', or 'x'/'y' coordinates for tap")
                }

                val nodes = svc.findNodesByQuery(query)
                if (nodes.isEmpty()) {
                    return@withContext AutomationExecutionResult(
                        false,
                        "❌ Could not find any UI element matching '$query' on the current screen. Call 'analyze_screen' first to see available elements."
                    )
                }

                val target = nodes.first()
                val ok = svc.clickNode(target)
                return@withContext if (ok) {
                    AutomationExecutionResult(true, "✅ Clicked element matching '$query'")
                } else {
                    AutomationExecutionResult(false, "❌ Found element matching '$query', but click action failed.")
                }
            }

            "long_press", "long_click" -> {
                if (payload.has("x") && payload.has("y")) {
                    val x = payload.getDouble("x").toFloat()
                    val y = payload.getDouble("y").toFloat()
                    val ok = svc.longPressAtCoordinates(x, y)
                    return@withContext if (ok) {
                        AutomationExecutionResult(true, "✅ Long-pressed at coordinates ($x, $y)")
                    } else {
                        AutomationExecutionResult(false, "❌ Failed to dispatch long press at ($x, $y)")
                    }
                }

                val query = payload.optString("text").ifEmpty { payload.optString("description") }
                if (query.isBlank()) {
                    return@withContext AutomationExecutionResult(false, "Missing 'text' or coordinates for long_press")
                }

                val nodes = svc.findNodesByQuery(query)
                if (nodes.isEmpty()) {
                    return@withContext AutomationExecutionResult(false, "❌ Could not find element matching '$query'")
                }

                val ok = svc.longClickNode(nodes.first())
                return@withContext if (ok) {
                    AutomationExecutionResult(true, "✅ Long-pressed element '$query'")
                } else {
                    AutomationExecutionResult(false, "❌ Long press failed on '$query'")
                }
            }

            "type_text", "input_text" -> {
                val textToType = payload.optString("text")
                if (textToType.isBlank()) {
                    return@withContext AutomationExecutionResult(false, "Missing 'text' parameter for type_text")
                }

                // If target node query provided, click and focus it first
                val targetQuery = payload.optString("target").ifEmpty { payload.optString("field") }
                val targetNode = if (targetQuery.isNotBlank()) {
                    svc.findNodesByQuery(targetQuery).firstOrNull()
                } else {
                    svc.findFirstEditableNode()
                }

                if (targetNode == null) {
                    return@withContext AutomationExecutionResult(
                        false,
                        "❌ Could not find an active or editable text field. Try tapping the input field first with 'tap'."
                    )
                }

                val clearFirst = payload.optBoolean("clear_first", true)
                val ok = svc.inputTextOnNode(targetNode, textToType, clearFirst)
                return@withContext if (ok) {
                    AutomationExecutionResult(true, "✅ Typed \"$textToType\" into text field")
                } else {
                    AutomationExecutionResult(false, "❌ Failed to set text on input field")
                }
            }

            "scroll" -> {
                val direction = payload.optString("direction", "down")
                val ok = svc.scroll(direction)
                return@withContext if (ok) {
                    AutomationExecutionResult(true, "✅ Scrolled $direction")
                } else {
                    AutomationExecutionResult(false, "❌ Failed to scroll $direction")
                }
            }

            "swipe", "drag" -> {
                val startX = payload.getDouble("start_x").toFloat()
                val startY = payload.getDouble("start_y").toFloat()
                val endX = payload.getDouble("end_x").toFloat()
                val endY = payload.getDouble("end_y").toFloat()
                val duration = payload.optLong("duration_ms", 300L)

                val ok = svc.swipe(startX, startY, endX, endY, duration)
                return@withContext if (ok) {
                    AutomationExecutionResult(true, "✅ Swiped from ($startX, $startY) to ($endX, $endY)")
                } else {
                    AutomationExecutionResult(false, "❌ Failed to dispatch swipe gesture")
                }
            }

            "press_key", "system_action", "press_button" -> {
                val key = payload.optString("key").ifEmpty {
                    payload.optString("action").ifEmpty {
                        payload.optString("button")
                    }
                }
                if (key.isBlank()) {
                    return@withContext AutomationExecutionResult(false, "Missing 'key' parameter (e.g. 'back', 'home', 'recents', 'notifications')")
                }

                val ok = svc.performSystemAction(key)
                return@withContext if (ok) {
                    AutomationExecutionResult(true, "✅ Triggered system action: $key")
                } else {
                    AutomationExecutionResult(false, "❌ Failed to trigger system action: $key")
                }
            }

            "take_screenshot", "screenshot" -> {
                val captureRes = svc.captureScreenshotBase64()
                return@withContext if (captureRes.isSuccess) {
                    val b64 = captureRes.getOrThrow()
                    val screenshotsDir = java.io.File(context.cacheDir, "screenshots").apply { mkdirs() }
                    val timestamp = System.currentTimeMillis()
                    val file = java.io.File(screenshotsDir, "screenshot_$timestamp.jpg")
                    try {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        file.writeBytes(bytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save screenshot file", e)
                    }

                    val sizeKb = if (file.exists()) file.length() / 1024 else (b64.length * 3 / 4 / 1024)
                    val outputMsg = buildString {
                        appendLine("📸 Screenshot captured successfully ($sizeKb KB JPEG):")
                        if (file.exists()) {
                            appendLine("• File: `${file.absolutePath}`")
                            appendLine("\n![Screenshot](file://${file.absolutePath})")
                        }
                        appendLine("\n💡 Tip: Call `analyze_screen` to read all text and interact with buttons on this screen.")
                    }

                    AutomationExecutionResult(
                        true,
                        outputMsg.trim(),
                        data = file.absolutePath
                    )
                } else {
                    AutomationExecutionResult(false, "❌ Screenshot capture failed: ${captureRes.exceptionOrNull()?.message}")
                }
            }

            else -> {
                AutomationExecutionResult(false, "Unknown automation action: '$act'")
            }
        }
    }
}
