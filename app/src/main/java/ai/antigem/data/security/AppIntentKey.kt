package ai.antigem.data.security

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tells "AntiGem itself sent this intent" from "another app sent it" on exported screens. `getReferrer()` can be faked
 * and `getCallingPackage()` is empty for a plain startActivity, so the app signs its own intents with a secret other
 * apps can't know: [EXTRA_SIG] = HMAC-SHA256(key, purpose).
 *
 * The purpose ties a signature to one action, so a leaked one unlocks only that: a home-screen link's URI, "jarvis-talk",
 * "voice-call", "miniapp-notif:<app>:<tag>:<action>", "open-own-uri". Signatures only travel in explicit intents and
 * PendingIntents to AntiGem's own components, never through a chooser (other apps would see them).
 *
 * The key is the home-screen links' key (prefs `home_links` / `key`): [rotate] renews it for everything at once.
 */
object AppIntentKey {
    const val EXTRA_SIG = "ai.antigem.extra.SIG"

    const val JARVIS_TALK = "jarvis-talk"
    const val VOICE_CALL = "voice-call"
    const val OPEN_OWN_URI = "open-own-uri"
    fun miniAppNotification(appId: String, tag: String, action: String) = "miniapp-notif:$appId:$tag:$action"

    private const val PREFS = "home_links"
    private const val KEY = "key"

    @Volatile private var cachedKey: String? = null

    /** The raw key (only [ai.antigem.data.homescreen.HomeLinks] needs it, to recognize links made before signatures). */
    @Synchronized
    internal fun key(context: Context): String {
        cachedKey?.let { return it }
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = p.getString(KEY, null) ?: newKey().also { p.edit().putString(KEY, it).commit() }
        cachedKey = k
        return k
    }

    /** A new key: every signature made before stops working (home-screen links are signed again by their owner). */
    @Synchronized
    fun rotate(context: Context) {
        val k = newKey()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, k).commit()
        cachedKey = k
    }

    private fun newKey(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun signature(context: Context, purpose: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key(context).toByteArray(), "HmacSHA256"))
        return mac.doFinal(purpose.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Puts the signature for [purpose] in [intent] (an explicit intent to one of AntiGem's own components). */
    fun sign(context: Context, intent: Intent, purpose: String): Intent = intent.putExtra(EXTRA_SIG, signature(context, purpose))

    fun isTrusted(context: Context, intent: Intent?, purpose: String): Boolean {
        val given = intent?.getStringExtra(EXTRA_SIG) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), signature(context, purpose).toByteArray())
    }
}
