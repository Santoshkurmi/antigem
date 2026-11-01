package com.example.gemini.data.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.example.gemini.data.adb.AdbConnectManager
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * android_automation_mcp: lets the model see and drive the phone through the accessibility service,
 * with a few adb-only extras (keys, install, uninstall, force stop, shell). Served by AndroidLocalBridgeServer at /android.
 */
object AndroidAutomation {

    const val INSTRUCTIONS = "Controls this Android phone. Look first: android_get_ui_tree lists on-screen elements with ids like e12 and their centers; " +
        "android_screenshot shows what it looks like. Then act on element ids (android_tap, android_type_text, android_scroll), or on screen-pixel " +
        "coordinates when an element has no id. Ids are only valid for the latest tree, so read it again after the screen changes. " +
        "For anything the tools don't cover, use android_adb_shell (or adb in the terminal). Ask the user before anything destructive or " +
        "outward-facing: uninstalling, deleting, sending messages, payments, changing security settings. " +
        "If a tool says a permission or ADB is missing, tell the user exactly what to do and stop."

    private const val ADB_NEEDED = "This needs ADB. Ask the user to open AntiGem → Settings → Utilities → ADB Connect and connect " +
        "(pair once, with Wireless debugging on), then try again."

    /** Elements of the latest android_get_ui_tree, by id. */
    private val elements = ConcurrentHashMap<String, AccessibilityNodeInfo>()

    // ---------------- readiness ----------------

    private fun component(context: Context) = ComponentName(context, AutomationAccessibilityService::class.java)

    fun isServiceRunning(): Boolean = AutomationAccessibilityService.instance != null

    fun isServiceEnabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val c = component(context)
        return enabled.split(':').any { it.equals(c.flattenToString(), true) || it.equals(c.flattenToShortString(), true) }
    }

    fun canTakeScreenshots(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Why the MCP can't be used yet, or null when it can. */
    fun notReadyMessage(context: Context): String? = when {
        isServiceRunning() -> null
        isServiceEnabledInSettings(context) -> "Android Automation's accessibility service is turned on but not running. " +
            "Ask the user to turn \"AntiGem Android Automation\" off and on again in Android Settings → Accessibility, then reconnect this MCP server."
        else -> "Android Automation is not set up. Ask the user to open AntiGem → Settings → Utilities → Android Automation and grant the permissions there " +
            "(the Accessibility service; on Android 13+ they may first need App info → ⋮ → Allow restricted settings). Then reconnect this MCP server."
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Turns the accessibility service on through adb, which also works when Android blocks it as a restricted setting. */
    suspend fun enableViaAdb(context: Context): Result<Unit> {
        val device = AdbConnectManager.ensureConnected() ?: return Result.failure(IllegalStateException("ADB is not connected"))
        val name = component(context).flattenToString()
        val current = AdbConnectManager.deviceShell(device, "settings get secure enabled_accessibility_services").output.trim()
        val services = current.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (services.none { it.equals(name, true) }) services += name
        AdbConnectManager.deviceShell(device, "settings put secure enabled_accessibility_services ${services.joinToString(":")}")
        AdbConnectManager.deviceShell(device, "settings put secure accessibility_enabled 1")
        repeat(20) {
            if (isServiceRunning()) return Result.success(Unit)
            delay(250)
        }
        return if (isServiceEnabledInSettings(context)) Result.success(Unit) else Result.failure(IllegalStateException("Android didn't accept the change"))
    }

    // ---------------- tool list ----------------

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val elementId = "element_id" to prop("string", "Element id from the latest android_get_ui_tree, e.g. e12")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("android_get_status", "Phone state: screen size, app in front, screen on/locked, and whether screenshots and ADB are available."))
        put(tool("android_get_device_info",
            "Device details: battery (level, charging, temperature, health), device and Android build, RAM, storage, CPU, display, " +
                "network, audio volumes and ringer mode, brightness, airplane/Do Not Disturb/location state, locale, time zone and uptime.",
            emptyList(),
            "sections" to prop("string", "Comma-separated subset: battery, device, memory, storage, display, network, audio, system (default: all)")
        ))
        put(tool("android_get_ui_tree",
            "List the on-screen elements (text, description, resource id, type, center point, bounds, and clickable/editable/scrollable/checked flags), " +
                "each with an id like e12 for the other tools. Ids are replaced on every call.",
            emptyList(),
            "filter" to prop("string", "Only list elements whose text, description, hint or resource id contains this (case-insensitive)"),
            "all_windows" to prop("boolean", "Include every window (status bar, keyboard, dialogs, other split-screen app), not just the active one. Default false"),
            "max_elements" to prop("integer", "Maximum elements to list (default 300)")
        ))
        put(tool("android_screenshot",
            "Screenshot of the whole screen as an image. Black if the app blocks screenshots. Tap coordinates are screen pixels; the result says how to convert if the image was scaled.",
            emptyList(),
            "max_width" to prop("integer", "Scale down to at most this width in pixels (default 1080)"),
            "quality" to prop("integer", "JPEG quality 10-100 (default 70)")
        ))
        put(tool("android_tap", "Tap (or long-press) an element by id, or a point in screen pixels.", emptyList(),
            elementId,
            "x" to prop("number", "Screen x in pixels, when no element_id"),
            "y" to prop("number", "Screen y in pixels, when no element_id"),
            "long_press" to prop("boolean", "Long-press instead of tap")
        ))
        put(tool("android_type_text", "Type into a text field: the given element, or the focused field. Replaces its text unless append is true.",
            listOf("text"),
            "text" to prop("string", "Text to type"),
            elementId,
            "append" to prop("boolean", "Add to the existing text instead of replacing it"),
            "submit" to prop("boolean", "Press the keyboard's enter/search/send action afterwards")
        ))
        put(tool("android_scroll", "Scroll a list or page. direction is where to move the view: down shows content further down.",
            listOf("direction"),
            "direction" to prop("string", "Which way to scroll", listOf("up", "down", "left", "right")),
            elementId.first to prop("string", "Scrollable element (or a child of one) from the latest tree; omit to swipe across the middle of the screen"),
            "distance" to prop("number", "When swiping: fraction of the screen to move, 0.1-0.9 (default 0.5)")
        ))
        put(tool("android_swipe", "Swipe from one point to another in screen pixels (drag, pull to refresh, sliders).",
            listOf("x1", "y1", "x2", "y2"),
            "x1" to prop("number", "Start x"), "y1" to prop("number", "Start y"),
            "x2" to prop("number", "End x"), "y2" to prop("number", "End y"),
            "duration_ms" to prop("integer", "How long the swipe takes (default 300; longer = slower drag)")
        ))
        put(tool("android_global_action", "System navigation: back, home, recent apps, notification shade, quick settings, power menu, lock screen, split screen.",
            listOf("action"),
            "action" to prop("string", "Action", listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen", "split_screen"))
        ))
        put(tool("android_list_apps", "List installed apps that can be opened, as label and package name.", emptyList(),
            "filter" to prop("string", "Only apps whose label or package contains this")
        ))
        put(tool("android_launch_app", "Open an app by package name or by its label.", emptyList(),
            "package" to prop("string", "Package name, e.g. com.android.settings"),
            "name" to prop("string", "App label as shown in the launcher, e.g. Settings")
        ))
        put(tool("android_open_intent", "Start an activity with an intent: open a link or deep link (https:, tel:, geo:, market:…) or a settings page (action android.settings.WIFI_SETTINGS).",
            emptyList(),
            "action" to prop("string", "Intent action (default android.intent.action.VIEW)"),
            "uri" to prop("string", "Data URI"),
            "package" to prop("string", "Only open in this app")
        ))
        put(tool("android_wait_for", "Wait until text appears (or disappears) on screen, or, without text, until the screen stops changing.", emptyList(),
            "text" to prop("string", "Text or description to wait for"),
            "gone" to prop("boolean", "Wait for the text to disappear instead"),
            "timeout_ms" to prop("integer", "Give up after this long (default 5000, max 30000)")
        ))
        put(tool("android_press_key", "Press a hardware or keyboard key, e.g. ENTER, DEL, VOLUME_UP, POWER, MEDIA_PLAY_PAUSE, or a key code number. BACK, HOME and APP_SWITCH work without ADB; other keys need ADB.",
            listOf("key"),
            "key" to prop("string", "Key name (KEYCODE_ prefix optional) or key code")
        ))
        put(tool("android_install_apk", "Install an APK file from the terminal's storage. Needs ADB.", listOf("path"),
            "path" to prop("string", "APK path, absolute or relative to the terminal home")
        ))
        put(tool("android_uninstall_app", "Uninstall an app. With ADB it's removed directly; without, Android's uninstall dialog opens and you must tap OK. Always ask the user first.",
            listOf("package"),
            "package" to prop("string", "Package name"),
            "keep_data" to prop("boolean", "Keep the app's data and cache (ADB only)")
        ))
        put(tool("android_force_stop_app", "Force-stop an app. Needs ADB.", listOf("package"),
            "package" to prop("string", "Package name")
        ))
        put(tool("android_adb_shell", "Run a command in `adb shell` on this phone (shell user: pm, am, settings, dumpsys, input, cmd, svc, wm…). Needs ADB.",
            listOf("command"),
            "command" to prop("string", "Shell command"),
            "timeout_seconds" to prop("integer", "Default 30, max 300")
        ))
    }

    // ---------------- results ----------------

    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun error(message: String) = text(message).put("isError", true)
    private fun staleElement(id: String) = error("Element $id isn't in the latest UI tree (the screen changed). Call android_get_ui_tree again.")

    // ---------------- calls ----------------

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject {
        notReadyMessage(context)?.let { return error(it) }
        val svc = AutomationAccessibilityService.instance ?: return error(notReadyMessage(context) ?: "Accessibility service not running")
        return runCatching {
            when (name) {
                "android_get_status" -> status(context, svc)
                "android_get_device_info" -> text(deviceInfo(context, args.optString("sections")).toString(2))
                "android_get_ui_tree" -> text(uiTree(svc, args.optString("filter").ifBlank { null }, args.optBoolean("all_windows"), args.optInt("max_elements", 300).coerceIn(10, 2000)))
                "android_screenshot" -> screenshot(context, svc, args.optInt("max_width", 1080).coerceIn(200, 4000), args.optInt("quality", 70).coerceIn(10, 100))
                "android_tap" -> tap(svc, args)
                "android_type_text" -> typeText(svc, args)
                "android_scroll" -> scroll(context, svc, args)
                "android_swipe" -> {
                    val path = Path().apply {
                        moveTo(args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat())
                        lineTo(args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat())
                    }
                    if (!svc.gesture(path, args.optLong("duration_ms", 300))) return error("The swipe was cancelled (the screen may be off or locked).")
                    svc.awaitIdle()
                    text("Swiped. ${foreground(svc)}")
                }
                "android_global_action" -> globalAction(svc, args.getString("action"))
                "android_list_apps" -> listApps(context, args.optString("filter"))
                "android_launch_app" -> launchApp(context, svc, args)
                "android_open_intent" -> openIntent(svc, args)
                "android_wait_for" -> waitFor(svc, args)
                "android_press_key" -> pressKey(svc, args.getString("key"))
                "android_install_apk" -> withAdb { d ->
                    val out = AdbConnectManager.runAdb(d, "install -r ${AdbConnectManager.shellQuote(args.getString("path"))}", 240).output.trim()
                    if (out.contains("Success")) text("Installed.\n$out") else error(out.ifBlank { "adb install failed" })
                }
                "android_uninstall_app" -> uninstall(context, svc, args)
                "android_force_stop_app" -> withAdb { d ->
                    val pkg = packageArg(args)
                    AdbConnectManager.deviceShell(d, "am force-stop $pkg")
                    text("Force-stopped $pkg.")
                }
                "android_adb_shell" -> withAdb { d ->
                    val seconds = args.optInt("timeout_seconds", 30).coerceIn(1, 300)
                    val result = AdbConnectManager.deviceShell(d, args.getString("command"), seconds)
                    val out = result.output.ifBlank { "(no output)" }.let { if (it.length > 60_000) it.take(60_000) + "\n…(truncated)" else it }
                    if (result.exitCode == 0) text(out) else error("Exit code ${result.exitCode}\n$out")
                }
                else -> error("Unknown tool $name")
            }
        }.getOrElse { error("$name failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private suspend fun withAdb(block: suspend (AdbConnectManager.Device) -> JSONObject): JSONObject {
        val device = AdbConnectManager.ensureConnected() ?: return error(ADB_NEEDED)
        return block(device)
    }

    private fun packageArg(args: JSONObject): String {
        val pkg = args.getString("package").trim()
        require(Regex("^[A-Za-z0-9_.]+$").matches(pkg)) { "invalid package name \"$pkg\"" }
        return pkg
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun foreground(svc: AutomationAccessibilityService): String {
        val pkg = svc.rootInActiveWindow?.packageName ?: svc.foregroundPackage
        return "In front: ${pkg ?: "unknown"}"
    }

    private fun status(context: Context, svc: AutomationAccessibilityService): JSONObject {
        val (w, h) = screenSize(context)
        val adb = AdbConnectManager.state.value.connectedDevice
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return text(JSONObject().apply {
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("screen_px", "${w}x$h")
            put("screen_on", power.isInteractive)
            put("locked", keyguard.isKeyguardLocked)
            put("foreground_package", svc.rootInActiveWindow?.packageName ?: svc.foregroundPackage ?: JSONObject.NULL)
            put("foreground_activity", svc.foregroundClass ?: JSONObject.NULL)
            put("screenshots", if (canTakeScreenshots()) "yes" else if (adb != null) "via ADB" else "no (needs Android 11+ or ADB)")
            put("adb", adb?.serial ?: "not connected (adb-only tools will fail)")
        }.toString(2))
    }

    // ---------------- device info ----------------

    private fun deviceInfo(context: Context, sections: String): JSONObject {
        val wanted = sections.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        fun want(name: String) = wanted.isEmpty() || name in wanted
        val gb = 1024.0 * 1024 * 1024
        fun gib(bytes: Long) = "%.2f GB".format(bytes / gb)
        val info = JSONObject()

        if (want("battery")) info.put("battery", JSONObject().apply {
            val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val bm = context.getSystemService(android.os.BatteryManager::class.java)
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            put("level_percent", if (level >= 0) level * 100 / scale.coerceAtLeast(1) else bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("status", when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            })
            put("plugged", when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)) {
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "unplugged"
                else -> "other"
            })
            put("health", when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                android.os.BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            })
            intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { put("temperature_c", it / 10.0) }
            intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.let { put("voltage_mv", it) }
            intent?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY)?.let { put("technology", it) }
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE && it != 0 }?.let { put("current_now_ua", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { put("charge_time_remaining_min", it / 60_000) }
            put("power_save_mode", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
        })

        if (want("device")) info.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            put("android_version", Build.VERSION.RELEASE)
            put("api_level", Build.VERSION.SDK_INT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) put("security_patch", Build.VERSION.SECURITY_PATCH)
            put("build", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("cpu_cores", Runtime.getRuntime().availableProcessors())
        })

        if (want("memory")) info.put("memory", JSONObject().apply {
            val mi = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            put("ram_total", gib(mi.totalMem))
            put("ram_available", gib(mi.availMem))
            put("ram_used_percent", ((mi.totalMem - mi.availMem) * 100 / mi.totalMem.coerceAtLeast(1)).toInt())
            put("low_memory", mi.lowMemory)
        })

        if (want("storage")) info.put("storage", JSONObject().apply {
            fun stat(dir: java.io.File) = runCatching { android.os.StatFs(dir.path) }.getOrNull()?.let {
                JSONObject().put("total", gib(it.totalBytes)).put("free", gib(it.availableBytes))
                    .put("used_percent", ((it.totalBytes - it.availableBytes) * 100 / it.totalBytes.coerceAtLeast(1)).toInt())
            }
            stat(android.os.Environment.getDataDirectory())?.let { put("internal", it) }
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, dir -> stat(dir)?.let { put("sd_card_${i + 1}", it) } }
        })

        if (want("display")) info.put("display", JSONObject().apply {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            put("resolution_px", "${metrics.widthPixels}x${metrics.heightPixels}")
            put("density_dpi", metrics.densityDpi)
            put("refresh_rate_hz", display.refreshRate.toInt())
            put("rotation_degrees", display.rotation * 90)
            put("font_scale", context.resources.configuration.fontScale)
            put("dark_mode", (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()?.let { put("brightness_0_255", it) }
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrNull()?.let { put("auto_brightness", it) }
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.let { put("screen_timeout_s", it / 1000) }
            put("screen_on", context.getSystemService(PowerManager::class.java).isInteractive)
            put("locked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        })

        if (want("network")) info.put("network", JSONObject().apply {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.getNetworkCapabilities(cm.activeNetwork) else null
            put("connected", caps != null)
            if (caps != null) {
                put("type", when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                    else -> "other"
                })
                put("vpn", caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
                put("internet_validated", caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                put("metered", cm.isActiveNetworkMetered)
                put("down_kbps", caps.linkDownstreamBandwidthKbps)
                put("up_kbps", caps.linkUpstreamBandwidthKbps)
            }
            put("ip_addresses", JSONArray(runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { nif -> nif.inetAddresses.toList().filter { !it.isLinkLocalAddress }.map { "${nif.name}: ${it.hostAddress}" } }
            }.getOrDefault(emptyList())))
            put("airplane_mode", Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            put("wireless_debugging", Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1)
        })

        if (want("audio")) info.put("audio", JSONObject().apply {
            val am = context.getSystemService(android.media.AudioManager::class.java)
            put("ringer_mode", when (am.ringerMode) {
                android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
                android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            listOf(
                "media" to android.media.AudioManager.STREAM_MUSIC, "ring" to android.media.AudioManager.STREAM_RING,
                "notification" to android.media.AudioManager.STREAM_NOTIFICATION, "alarm" to android.media.AudioManager.STREAM_ALARM,
                "call" to android.media.AudioManager.STREAM_VOICE_CALL
            ).forEach { (label, stream) -> put("${label}_volume", "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}") }
            put("music_playing", am.isMusicActive)
            put("headphones_connected", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                } else am.isWiredHeadsetOn)
        })

        if (want("system")) info.put("system", JSONObject().apply {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            put("do_not_disturb", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL else false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                put("location_enabled", context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled)
            put("developer_options", Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
            put("locale", java.util.Locale.getDefault().toLanguageTag())
            put("time_zone", java.util.TimeZone.getDefault().id)
            put("local_time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()))
            put("uptime", android.os.SystemClock.elapsedRealtime().let { "%dd %02dh %02dm".format(it / 86_400_000, it / 3_600_000 % 24, it / 60_000 % 60) })
            put("adb", AdbConnectManager.state.value.connectedDevice?.serial ?: "not connected")
        })

        if (info.length() == 0) info.put("error", "No known sections in \"$sections\". Use battery, device, memory, storage, display, network, audio, system.")
        return info
    }

    // ---------------- UI tree ----------------

    private fun uiTree(svc: AutomationAccessibilityService, filter: String?, allWindows: Boolean, max: Int): String {
        elements.clear()
        val roots = if (allWindows) svc.windows.mapNotNull { w -> w.root?.let { "${w.title ?: it.packageName} (${windowType(w.type)})" to it } }
        else listOfNotNull(svc.rootInActiveWindow?.let { "${it.packageName}" to it })
        if (roots.isEmpty()) return "No window content available (the screen may be off, or showing a secure lock screen)."
        val out = StringBuilder()
        var count = 0
        var truncated = false
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (!node.isVisibleToUser) return
            val label = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
            val resId = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
            val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable || node.isCheckable
            val matches = filter == null || listOf(label, desc, hint, resId).any { it.contains(filter, ignoreCase = true) }
            var childDepth = depth
            if ((actionable || label.isNotEmpty() || desc.isNotEmpty()) && matches) {
                if (count >= max) { truncated = true; return }
                val id = "e${++count}"
                elements[id] = node
                node.getBoundsInScreen(rect)
                out.append("  ".repeat(depth)).append('[').append(id).append("] ").append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                if (label.isNotEmpty()) out.append(" \"").append(label.take(120).replace('\n', ' ')).append('"')
                if (desc.isNotEmpty() && desc != label) out.append(" desc=\"").append(desc.take(120).replace('\n', ' ')).append('"')
                if (hint.isNotEmpty() && hint != label) out.append(" hint=\"").append(hint.take(60)).append('"')
                if (resId.isNotEmpty()) out.append(" id=").append(resId)
                out.append(" @").append(rect.centerX()).append(',').append(rect.centerY())
                out.append(" [").append(rect.left).append(',').append(rect.top).append(',').append(rect.right).append(',').append(rect.bottom).append(']')
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isLongClickable) add("long-clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isSelected) add("selected")
                    if (node.isFocused) add("focused")
                    if (!node.isEnabled) add("disabled")
                    if (node.isPassword) add("password")
                }
                if (flags.isNotEmpty()) out.append(' ').append(flags.joinToString(","))
                out.append('\n')
                if (filter == null) childDepth = depth + 1
            }
            for (i in 0 until node.childCount) {
                if (truncated) return
                node.getChild(i)?.let { walk(it, childDepth) }
            }
        }

        roots.forEach { (title, root) ->
            out.append("Window: ").append(title).append('\n')
            walk(root, 1)
        }
        if (count == 0) out.append(if (filter != null) "No elements match \"$filter\".\n" else "No readable elements; try android_screenshot.\n")
        if (truncated) out.append("…more elements not shown; use filter or max_elements.\n")
        return out.toString()
    }

    private fun windowType(type: Int) = when (type) {
        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        else -> "other"
    }

    private fun element(args: JSONObject): Pair<String?, AccessibilityNodeInfo?> {
        val id = args.optString("element_id").trim().ifBlank { null } ?: return null to null
        return id to elements[id]?.takeIf { it.refresh() }
    }

    // ---------------- actions ----------------

    private suspend fun tapAt(svc: AutomationAccessibilityService, x: Float, y: Float, long: Boolean): Boolean =
        svc.gesture(Path().apply { moveTo(x, y) }, if (long) 700 else 60)

    private suspend fun tap(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val long = args.optBoolean("long_press")
        val (id, node) = element(args)
        val what: String
        if (id != null) {
            node ?: return staleElement(id)
            val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            val target = generateSequence(node) { it.parent }.firstOrNull { if (long) it.isLongClickable else it.isClickable }
            if (target?.performAction(action) != true) {
                // Not clickable through accessibility (custom views, games): touch its center instead
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (!tapAt(svc, r.exactCenterX(), r.exactCenterY(), long)) return error("Couldn't tap $id (the screen may be off or locked).")
            }
            what = id
        } else {
            if (!args.has("x") || !args.has("y")) return error("Give element_id, or x and y.")
            val x = args.getDouble("x").toFloat()
            val y = args.getDouble("y").toFloat()
            if (!tapAt(svc, x, y, long)) return error("The tap was cancelled (the screen may be off or locked).")
            what = "${x.toInt()},${y.toInt()}"
        }
        svc.awaitIdle()
        return text("${if (long) "Long-pressed" else "Tapped"} $what. ${foreground(svc)}")
    }

    private suspend fun typeText(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val (id, node) = element(args)
        if (id != null && node == null) return staleElement(id)
        val target = node ?: svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return error("No text field is focused. Tap one first, or pass its element_id.")
        if (!target.isEditable) return error("${id ?: "The focused element"} is not a text field.")
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target.isShowingHintText
        val existing = if (args.optBoolean("append") && !showingHint) target.text?.toString().orEmpty() else ""
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + args.getString("text"))
        })
        if (!ok) return error("The field didn't accept text. Try tapping it first, or android_adb_shell with `input text`.")
        var note = ""
        if (args.optBoolean("submit")) {
            val submitted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                target.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
            note = when {
                submitted -> " Submitted."
                AdbConnectManager.ensureConnected()?.let { AdbConnectManager.deviceShell(it, "input keyevent 66").exitCode == 0 } == true -> " Pressed enter."
                else -> " Couldn't press enter (needs Android 11+ or ADB); tap the send/search button instead."
            }
        }
        svc.awaitIdle()
        return text("Typed.$note ${foreground(svc)}")
    }

    private suspend fun scroll(context: Context, svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val direction = args.getString("direction").lowercase()
        val (id, node) = element(args)
        if (id != null) {
            node ?: return staleElement(id)
            val scrollable = generateSequence(node) { it.parent }.firstOrNull { it.isScrollable }
                ?: return error("$id isn't inside anything scrollable. Omit element_id to swipe instead.")
            val specific = when (direction) {
                "up" -> AccessibilityAction.ACTION_SCROLL_UP
                "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                else -> AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            val generic = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val ok = scrollable.performAction(specific.id) || scrollable.performAction(generic)
            svc.awaitIdle()
            return if (ok) text("Scrolled $direction.") else error("It can't scroll $direction any further.")
        }
        val (w, h) = screenSize(context)
        val d = args.optDouble("distance", 0.5).coerceIn(0.1, 0.9).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // Swipe opposite to the direction of travel: to see content below, the finger moves up
        val (dx, dy) = when (direction) {
            "down" -> 0f to -h * d / 2
            "up" -> 0f to h * d / 2
            "right" -> -w * d / 2 to 0f
            else -> w * d / 2 to 0f
        }
        val path = Path().apply { moveTo(cx - dx, cy - dy); lineTo(cx + dx, cy + dy) }
        if (!svc.gesture(path, 350)) return error("The swipe was cancelled (the screen may be off or locked).")
        svc.awaitIdle()
        return text("Scrolled $direction.")
    }

    private suspend fun globalAction(svc: AutomationAccessibilityService, action: String): JSONObject {
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else return error("lock_screen needs Android 9+")
            else -> return error("Unknown action $action")
        }
        if (!svc.performGlobalAction(code)) return error("Android refused $action.")
        svc.awaitIdle()
        return text("Done: $action. ${foreground(svc)}")
    }

    private fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    private fun listApps(context: Context, filter: String): JSONObject {
        val apps = launchableApps(context).filter { filter.isBlank() || it.first.contains(filter, true) || it.second.contains(filter, true) }
        if (apps.isEmpty()) return text("No apps match \"$filter\".")
        return text(apps.take(500).joinToString("\n") { "${it.first} — ${it.second}" })
    }

    private suspend fun launchApp(context: Context, svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        val pkg = args.optString("package").trim().ifBlank {
            if (name.isBlank()) return error("Give package or name.")
            val apps = launchableApps(context)
            (apps.firstOrNull { it.first.equals(name, true) } ?: apps.firstOrNull { it.first.contains(name, true) })?.second
                ?: return error("No app called \"$name\". Use android_list_apps to see what's installed.")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return error("$pkg isn't installed or can't be opened.")
        // Started from the accessibility service, which Android lets open activities from the background
        svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        svc.awaitIdle(500, 4_000)
        return text("Opened $pkg. ${foreground(svc)}")
    }

    private suspend fun openIntent(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val intent = Intent(args.optString("action").ifBlank { Intent.ACTION_VIEW })
        args.optString("uri").ifBlank { null }?.let { intent.data = Uri.parse(it) }
        args.optString("package").ifBlank { null }?.let { intent.setPackage(it) }
        if (intent.action == Intent.ACTION_VIEW && intent.data == null) return error("ACTION_VIEW needs a uri.")
        return try {
            svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            svc.awaitIdle(500, 4_000)
            text("Started. ${foreground(svc)}")
        } catch (e: android.content.ActivityNotFoundException) {
            error("No app can handle that intent.")
        }
    }

    private suspend fun waitFor(svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val timeout = args.optLong("timeout_ms", 5_000).coerceIn(100, 30_000)
        val wanted = args.optString("text").ifBlank { null }
        if (wanted == null) {
            svc.awaitIdle(500, timeout)
            return text("The screen is idle. ${foreground(svc)}")
        }
        val gone = args.optBoolean("gone")
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            val found = svc.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
                .any { root -> root.findAccessibilityNodeInfosByText(wanted).any { it.isVisibleToUser } }
            if (found != gone) return text(if (gone) "\"$wanted\" is gone." else "\"$wanted\" is on screen.")
            delay(300)
        }
        return error("Timed out after ${timeout}ms: \"$wanted\" ${if (gone) "is still on screen" else "never appeared"}.")
    }

    private suspend fun pressKey(svc: AutomationAccessibilityService, key: String): JSONObject {
        val name = key.trim().uppercase().removePrefix("KEYCODE_")
        if (!Regex("^[A-Z0-9_]+$").matches(name)) return error("Invalid key \"$key\".")
        val device = AdbConnectManager.ensureConnected()
        if (device == null) {
            val global = when (name) {
                "BACK", "4" -> AccessibilityService.GLOBAL_ACTION_BACK
                "HOME", "3" -> AccessibilityService.GLOBAL_ACTION_HOME
                "APP_SWITCH", "187" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                else -> return error("Pressing $name needs ADB. ${ADB_NEEDED.substringAfter("This needs ADB. ")}")
            }
            svc.performGlobalAction(global)
        } else {
            val code = if (name.all(Char::isDigit)) name else "KEYCODE_$name"
            val result = AdbConnectManager.deviceShell(device, "input keyevent $code", 15)
            if (result.exitCode != 0) return error(result.output.ifBlank { "input keyevent failed" })
        }
        svc.awaitIdle()
        return text("Pressed $name. ${foreground(svc)}")
    }

    private suspend fun uninstall(context: Context, svc: AutomationAccessibilityService, args: JSONObject): JSONObject {
        val pkg = packageArg(args)
        if (pkg == context.packageName) return error("Refusing to uninstall AntiGem itself.")
        val device = AdbConnectManager.ensureConnected()
        if (device != null) {
            val keep = if (args.optBoolean("keep_data")) "-k " else ""
            val out = AdbConnectManager.deviceShell(device, "pm uninstall $keep$pkg", 60).output.trim()
            return if (out.contains("Success")) text("Uninstalled $pkg.") else error(out.ifBlank { "pm uninstall failed" })
        }
        @Suppress("DEPRECATION")
        svc.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        svc.awaitIdle(500, 3_000)
        return text("ADB isn't connected, so Android's uninstall dialog is open for $pkg. Read the UI tree and tap OK/Uninstall to confirm.")
    }

    // ---------------- screenshot ----------------

    private suspend fun screenshot(context: Context, svc: AutomationAccessibilityService, maxWidth: Int, quality: Int): JSONObject {
        val bitmap: Bitmap = if (canTakeScreenshots()) {
            svc.screenshot().getOrElse { return error("Screenshot failed: ${it.message}") }
        } else {
            val device = AdbConnectManager.ensureConnected()
                ?: return error("Screenshots need Android 11+ or ADB. ${ADB_NEEDED.substringAfter("This needs ADB. ")}")
            val b64 = AdbConnectManager.runAdb(device, "exec-out screencap -p | base64 | tr -d '\\n'", 30).output.trim()
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return error("adb screencap failed")
        }
        val (screenW, screenH) = screenSize(context)
        val scaled = if (bitmap.width > maxWidth) Bitmap.createScaledBitmap(bitmap, maxWidth, bitmap.height * maxWidth / bitmap.width, true) else bitmap
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        val factor = screenW.toFloat() / scaled.width
        val note = if (factor in 0.99f..1.01f) "Screenshot ${scaled.width}x${scaled.height}; image pixels are screen pixels."
            else "Screenshot ${scaled.width}x${scaled.height} of a ${screenW}x$screenH screen: multiply image coordinates by ${"%.3f".format(factor)} for android_tap/android_swipe."
        return JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))
            .put(JSONObject().put("type", "text").put("text", "$note ${foreground(svc)}")))
    }
}
