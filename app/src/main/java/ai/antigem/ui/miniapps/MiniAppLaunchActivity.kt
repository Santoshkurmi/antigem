package ai.antigem.ui.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import ai.antigem.data.miniapps.MiniAppNotify
import ai.antigem.data.miniapps.MiniAppRuntime
import ai.antigem.data.miniapps.MiniApps
import org.json.JSONObject

/**
 * Invisible entry point for home-screen shortcuts and notification taps / buttons. It brings the mini app forward
 * the way Recents does (same page, pushed pages kept) or starts it, hands over the notification event, and goes.
 */
class MiniAppLaunchActivity : Activity() {
    companion object {
        fun intent(context: Context, appId: String): Intent = Intent(context, MiniAppLaunchActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("antigem-miniapp://app/" + Uri.encode(appId)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // antigem-miniapp://app/<id>[/<path>][?query]: a deep link (also how shortcuts open apps)
        val link = intent.data?.takeIf { it.scheme == "antigem-miniapp" }
        val appId = intent.getStringExtra(MiniAppNotify.EXTRA_APP) ?: link?.pathSegments?.firstOrNull()
        val app = appId?.let { MiniApps.find(this, it) }
        if (app != null) {
            val notif = intent.getStringExtra(MiniAppNotify.EXTRA_NOTIF)
            val notifAction = intent.getStringExtra(MiniAppNotify.EXTRA_ACTION) ?: "tap"
            // a notification event only from the app's own notification (signed for this app, tag and action): an
            // intent:// link on a website reaches this screen too, and must not press the app's buttons
            if (notif != null && ai.antigem.data.security.AppIntentKey.isTrusted(
                    this, intent, ai.antigem.data.security.AppIntentKey.miniAppNotification(app.id, notif, notifAction))
            ) {
                val event = JSONObject().put("id", notif).put("action", notifAction)
                // the app is coming forward: the event waits for its page to be on screen
                MiniAppRuntime.deliverNotificationEvent(this, app, event, preferBackground = false)
            }
            if (link != null && link.pathSegments.size > 1) {
                // a link into the app: it decides where to go (AntiGem.on('intent', …) with action "view")
                val params = JSONObject().apply { link.queryParameterNames.forEach { put(it, link.getQueryParameter(it)) } }
                MiniAppRuntime.deliverIntent(app.id, JSONObject().put("action", "view").put("url", link.toString())
                    .put("path", link.pathSegments.drop(1).joinToString("/")).put("params", params).put("from", JSONObject().put("id", "link").put("name", "Link")))
            }
            MiniAppActivity.launch(this, app.id)
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
