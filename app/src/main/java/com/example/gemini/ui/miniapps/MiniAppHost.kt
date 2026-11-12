package com.example.gemini.ui.miniapps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniAppError
import com.example.gemini.data.miniapps.MiniAppPermissions
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.ThemeManager

/** What a page (MiniAppPage) needs from whatever shows it: an activity on screen, or nothing (a background task). */
interface MiniAppHost {
    val context: Context
    val isDark: Boolean
    val backgroundColor: Int
    val isPushed: Boolean
    /** On screen right now (resumed). */
    val isForeground: Boolean
    fun onPageVisible(p: MiniAppPage)
    fun onRendererGone(p: MiniAppPage)
    fun onManifestChanged(newApp: MiniApp)
    fun toast(text: String, long: Boolean)
    suspend fun showDialog(d: NativeDialog): Any?
    suspend fun requestPermission(app: MiniApp, name: String): MiniAppPermissions.State
    suspend fun androidPermissions(perms: Array<String>): Boolean
    suspend fun pickFiles(accept: String, multiple: Boolean): List<Uri>
    /** A photo (or a video) taken with the camera app for <input type=file capture>, or null when cancelled. */
    suspend fun capture(video: Boolean): Uri?
    suspend fun pickDate(value: String, title: String): String?
    suspend fun pickTime(value: String): String?
    /** A folder on this phone, chosen in AntiGem's file explorer (real path), or null when cancelled. */
    suspend fun pickFolder(title: String): java.io.File?
    fun setStatusBar(color: String)
    fun push(path: String)
    fun closePage()
    fun closeApp()
    fun showConsole()
    fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideFullscreen()
    fun startActivity(intent: Intent)
}

/** A background task has no screen: anything that needs the user fails with NotSupportedError. */
class BackgroundTaskHost(override val context: Context, private val onCrash: () -> Unit) : MiniAppHost {
    override val isDark: Boolean = runCatching {
        when (AuthPreferences(context).getThemeModeSync()) {
            "DARK" -> true
            "LIGHT" -> false
            else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }.getOrDefault(false)
    override val backgroundColor: Int = (ThemeManager.resolvedColors(ThemeManager.themeFor(isDark))["background"] ?: 0xFF000000).toInt()
    override val isPushed = false
    override val isForeground = false

    private fun noUi(what: String): Nothing = throw MiniAppError("NotSupportedError", "$what is not available in a background task")

    override fun onPageVisible(p: MiniAppPage) {}
    override fun onRendererGone(p: MiniAppPage) = onCrash()
    override fun onManifestChanged(newApp: MiniApp) {}
    override fun toast(text: String, long: Boolean) {}
    override suspend fun showDialog(d: NativeDialog): Any? = noUi("A dialog")
    override suspend fun requestPermission(app: MiniApp, name: String) = MiniAppPermissions.state(context, app, name)
    override suspend fun androidPermissions(perms: Array<String>) = false
    override suspend fun pickFiles(accept: String, multiple: Boolean): List<Uri> = noUi("Picking files")
    override suspend fun capture(video: Boolean): Uri? = noUi("The camera")
    override suspend fun pickDate(value: String, title: String): String? = noUi("A date picker")
    override suspend fun pickTime(value: String): String? = noUi("A time picker")
    override suspend fun pickFolder(title: String): java.io.File? = noUi("Picking a folder")
    override fun setStatusBar(color: String) {}
    override fun push(path: String) = noUi("Opening a page")
    override fun closePage() {}
    override fun closeApp() {}
    override fun showConsole() {}
    override fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = callback.onCustomViewHidden()
    override fun hideFullscreen() {}
    override fun startActivity(intent: Intent) = noUi("Opening another app")
}
