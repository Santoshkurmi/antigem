package ai.antigem.data.homescreen

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Home screen: the app icon's menu and how the widgets look. */
object HomePrefs {
    /** Widget colors: the AntiGem theme's (its light and dark side follow the phone) or the wallpaper's (Material You). */
    const val COLORS_APP = "app"
    const val COLORS_WALLPAPER = "wallpaper"

    private var sp: SharedPreferences? = null

    private val _recentInMenu = MutableStateFlow(true)
    /** The newest chats in the long-press menu of the AntiGem icon. */
    val recentInMenu: StateFlow<Boolean> = _recentInMenu.asStateFlow()

    private val _colors = MutableStateFlow(COLORS_APP)
    val colors: StateFlow<String> = _colors.asStateFlow()

    private val _opacity = MutableStateFlow(100)
    /** The widgets' background, 100 = solid; lower lets the wallpaper show through. */
    val opacity: StateFlow<Int> = _opacity.asStateFlow()

    private val _hideTitles = MutableStateFlow(false)
    /** Widgets show "Claude Code chat" instead of the chat's name, and no reply text (for a home screen others see). */
    val hideTitles: StateFlow<Boolean> = _hideTitles.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("home_screen", Context.MODE_PRIVATE)
        sp = p
        _recentInMenu.value = p.getBoolean("recent_in_menu", true)
        _colors.value = p.getString("colors", COLORS_APP) ?: COLORS_APP
        _opacity.value = p.getInt("opacity", 100).coerceIn(30, 100)
        _hideTitles.value = p.getBoolean("hide_titles", false)
    }

    fun setRecentInMenu(on: Boolean) {
        _recentInMenu.value = on
        sp?.edit()?.putBoolean("recent_in_menu", on)?.apply()
    }

    fun setColors(value: String) {
        _colors.value = value
        sp?.edit()?.putString("colors", value)?.apply()
    }

    fun setOpacity(value: Int) {
        _opacity.value = value.coerceIn(30, 100)
        sp?.edit()?.putInt("opacity", _opacity.value)?.apply()
    }

    fun setHideTitles(on: Boolean) {
        _hideTitles.value = on
        sp?.edit()?.putBoolean("hide_titles", on)?.apply()
    }
}
