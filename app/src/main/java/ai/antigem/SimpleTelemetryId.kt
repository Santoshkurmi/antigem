package ai.antigem

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

object SimpleTelemetryId {

    // App salt used to make the hash unique to AntiGem.
    // The raw Android ID is never sent. TelemetryDeck receives only this hash,
    // then hashes it again with its own salt.
    private const val APP_SALT = "k9X#mP2\$vL9!zQ_ANTIGEM_STABLE_SALT_5tG"

    fun getUniqueUserId(context: Context): String {
        // 1. Retrieve persistent Android ID. On Android 8+ this stays the same
        // across reinstalls of an app signed with the same key.
        var androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        // 2. If Android ID is null, blank, or the known emulator dummy id ("9774d56d682e549c"),
        // fall back to a random UUID stored persistently in SharedPreferences.
        // That fallback is wiped on uninstall, so it only covers devices with no Android ID.
        if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") {
            val prefs = context.getSharedPreferences("telemetry_device_prefs", Context.MODE_PRIVATE)
            androidId = prefs.getString("fallback_device_id", null) ?: run {
                val newId = UUID.randomUUID().toString()
                prefs.edit().putString("fallback_device_id", newId).apply()
                newId
            }
        }

        // 3. Gather permanent hardware characteristics
        val hardwareMetrics = "${Build.BOARD}-${Build.BRAND}-${Build.MODEL}-${Build.DEVICE}"

        // 4. Combine and hash with SHA-256
        return sha256(androidId + APP_SALT + hardwareMetrics)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
