package com.example.gemini.data.miniapps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Mini app intents, like Android's share / open-with: an app declares what it accepts
 * ("intents": [{"action": "share", "types": ["image/jpeg"]}], wildcards like image/… allowed), another sends one, the user picks the target.
 */
object MiniAppIntents {
    fun mimeMatches(pattern: String, type: String): Boolean {
        val p = pattern.lowercase().trim(); val t = type.lowercase().trim()
        return p == "*/*" || p == "*" || p == t || (p.endsWith("/*") && t.startsWith(p.removeSuffix("*")))
    }

    /** Apps that can run now and accept [action] with [type]. */
    fun targets(context: Context, action: String, type: String): List<MiniApp> = MiniApps.scan(context).filter { app ->
        !app.tampered && (app.signed || MiniApps.devMode(context)) &&
            app.intents.any { (a, types) -> a == action && types.any { mimeMatches(it, type) } }
    }
}

/** Network and battery, for AntiGem.device.status() and the 'network' / 'battery' events. */
object MiniAppDeviceStatus {
    fun network(context: Context): JSONObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return JSONObject().put("online", online).put("type", type)
            .put("metered", caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    fun battery(context: Context, sticky: Intent? = null): JSONObject {
        val i = sticky ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && s > 0) l * 100 / s else null }
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return JSONObject().put("level", level ?: JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("saver", context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
    }
}
