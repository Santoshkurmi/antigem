package com.example.gemini.ui.miniapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.example.gemini.data.miniapps.MiniAppNotify
import com.example.gemini.data.miniapps.MiniAppRuntime
import com.example.gemini.data.miniapps.MiniApps
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
            if (notif != null) {
                val event = JSONObject().put("id", notif).put("action", intent.getStringExtra(MiniAppNotify.EXTRA_ACTION) ?: "tap")
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
