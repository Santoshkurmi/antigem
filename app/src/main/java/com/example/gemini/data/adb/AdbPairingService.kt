package com.example.gemini.data.adb

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.RemoteInput

/**
 * Keeps the process and the pairing notification alive while the user is in Developer options;
 * the code typed into the notification's reply box comes back here as [ACTION_CODE].
 */
class AdbPairingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AdbConnectManager.init(this)
        startForeground(AdbConnectManager.PAIRING_NOTIFICATION_ID, AdbConnectManager.pairingNotification(this))
        when (intent?.action) {
            ACTION_STOP -> AdbConnectManager.stopPairing(this)
            ACTION_CODE -> RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(AdbConnectManager.KEY_PAIRING_CODE)
                ?.let { AdbConnectManager.submitPairingCode(this, it.toString()) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.gemini.adb.STOP_PAIRING"
        const val ACTION_CODE = "com.example.gemini.adb.PAIRING_CODE"
    }
}
