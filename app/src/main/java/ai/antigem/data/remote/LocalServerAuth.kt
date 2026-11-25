package ai.antigem.data.remote

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The key of the app's local MCP / REST server ([AndroidLocalBridgeServer]), which its clients send in the
 * `X-Device-Id` header: the agents' AntiGem MCP presets, rish / android-su and the browser-plugin host.
 *
 *  - An install that existed before this key keeps its old value (the phone's ANDROID_ID), so the MCP servers already
 *    added to the agents keep working. A new install gets a random key.
 *  - [rotate] makes a new random key. MCP servers added with the old one are not changed: they stop working until the
 *    user adds them again from the presets.
 */
object LocalServerAuth {

    private const val PREFS = "local_server_auth"
    private const val KEY = "key"

    @Volatile
    private var cached: String? = null

    fun key(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: run {
                val legacy = if (isFreshInstall(context)) "" else androidId(context)
                legacy.takeIf { it.length > 5 } ?: randomKey()
            }.also { prefs.edit().putString(KEY, it).commit() }
            cached = key
            return key
        }
    }

    /** Replaces the key with a new random one and returns it. */
    fun rotate(context: Context): String = synchronized(this) {
        val key = randomKey()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key).commit()
        cached = key
        key
    }

    /** Constant-time check of a presented `X-Device-Id` value. */
    fun matches(context: Context, presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), key(context).toByteArray(Charsets.UTF_8))
    }

    private fun randomKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Installed and never updated: no MCP server can have been added with an older key. Unknown counts as updated. */
    @Suppress("DEPRECATION")
    private fun isFreshInstall(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

    private fun androidId(context: Context): String = runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty()
}
