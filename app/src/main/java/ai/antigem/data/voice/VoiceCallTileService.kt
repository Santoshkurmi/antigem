package ai.antigem.data.voice

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ai.antigem.i18n.L

/**
 * The "Voice call" Quick Settings tile: one tap calls Antigravity in the last chat that had a call (the app stays in
 * the background when it is running); during a call a tap ends it. See [VoiceCallLauncher].
 */
class VoiceCallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        showState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (AgyVoiceCall.state.value.inCall) {
            AgyVoiceCall.end()
            showState()
            return
        }
        val open = {
            val intent = Intent(this, VoiceCallLaunchActivity::class.java)
                .setAction(VoiceCallLauncher.ACTION_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 78, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun showState() {
        val inCall = AgyVoiceCall.state.value.inCall
        qsTile?.apply {
            state = if (inCall) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = L.voice.callTileService.voiceCall
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (inCall) L.voice.callTileService.inCallTapToEnd else L.voice.callTileService.tapToCall
            updateTile()
        }
    }

    companion object {
        /** A call started or ended: the tile shows it (when Quick Settings is open). */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, VoiceCallTileService::class.java)) }
        }
    }
}
