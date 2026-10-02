package com.example.gemini.data.preferences

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object TerminalLauncherManager {
    const val STYLE_ANTITERM = "ANTITERM"
    const val STYLE_TERMUX = "TERMUX"

    private const val TAG = "TerminalLauncherMgr"

    fun getAntiTermComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "com.example.gemini.TermuxAntiTermAlias")

    fun getTermuxClassicComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "com.example.gemini.TermuxClassicAlias")

    fun getDirectActivityComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "com.example.gemini.TermuxActivity")

    /**
     * Inspects the Android PackageManager directly to determine if any standalone terminal launcher alias/activity is enabled.
     */
    fun isLauncherEnabled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val antiTermState = pm.getComponentEnabledSetting(getAntiTermComponent(context))
            val classicState = pm.getComponentEnabledSetting(getTermuxClassicComponent(context))
            val directState = pm.getComponentEnabledSetting(getDirectActivityComponent(context))

            val isAntiTermActive = antiTermState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (antiTermState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && classicState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED && directState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            val isClassicActive = classicState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val isDirectActive = directState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

            isAntiTermActive || isClassicActive || isDirectActive
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Inspects the Android PackageManager directly to determine which style alias is active.
     */
    fun getLauncherStyle(context: Context): String {
        return try {
            val pm = context.packageManager
            val classicState = pm.getComponentEnabledSetting(getTermuxClassicComponent(context))
            if (classicState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                STYLE_TERMUX
            } else {
                STYLE_ANTITERM
            }
        } catch (_: Exception) {
            STYLE_ANTITERM
        }
    }

    /**
     * Applies the launcher alias state based on user preference.
     *
     * @param context Application context
     * @param enabled Whether the standalone terminal icon should appear in launcher / app drawer
     * @param style Either [STYLE_ANTITERM] or [STYLE_TERMUX]
     */
    fun applyLauncherSetting(context: Context, enabled: Boolean, style: String) {
        val pm = context.packageManager
        val antiTerm = getAntiTermComponent(context)
        val classic = getTermuxClassicComponent(context)
        val direct = getDirectActivityComponent(context)

        try {
            // Ensure direct activity is not acting as a duplicate launcher if aliases exist
            try {
                val directState = pm.getComponentEnabledSetting(direct)
                if (directState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        direct,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (_: Exception) {}

            if (!enabled) {
                // Disable both aliases
                setComponentSafe(pm, antiTerm, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                setComponentSafe(pm, classic, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                Log.d(TAG, "Standalone Terminal Launcher icon disabled")
            } else {
                if (style == STYLE_TERMUX) {
                    setComponentSafe(pm, antiTerm, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    setComponentSafe(pm, classic, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    Log.d(TAG, "Enabled Termux Classic launcher alias (Termux)")
                } else {
                    setComponentSafe(pm, classic, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    setComponentSafe(pm, antiTerm, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    Log.d(TAG, "Enabled AntiTerm launcher alias (AntiTerm)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating launcher component settings: ${e.message}", e)
        }
    }

    private fun setComponentSafe(pm: PackageManager, component: ComponentName, state: Int) {
        try {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            Log.w(TAG, "Component ${component.shortClassName} update warning: ${e.message}")
        }
    }
}
