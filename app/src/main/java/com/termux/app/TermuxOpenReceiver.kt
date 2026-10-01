package com.termux.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.gemini.data.receiver.TermuxOpenReceiver as CoreOpenReceiver

/**
 * Direct com.termux.app.TermuxOpenReceiver implementation for shell scripts
 * (e.g., xdg-open, termux-open) targeting explicit component com.termux.app.TermuxOpenReceiver.
 */
class TermuxOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        CoreOpenReceiver.handleOpen(context, intent)
    }
}
