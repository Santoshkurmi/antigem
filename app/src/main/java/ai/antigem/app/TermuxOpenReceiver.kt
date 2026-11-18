package ai.antigem.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.antigem.data.receiver.TermuxOpenReceiver as CoreOpenReceiver

/**
 * Direct ai.antigem.app.TermuxOpenReceiver implementation for shell scripts
 * (e.g., xdg-open, termux-open) targeting explicit component ai.antigem.app.TermuxOpenReceiver.
 */
class TermuxOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        CoreOpenReceiver.handleOpen(context, intent)
    }
}
