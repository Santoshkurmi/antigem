package ai.antigem.data.jarvis

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ai.antigem.MainActivity
import ai.antigem.i18n.L

/**
 * The "Jarvis" Quick Settings tile: one tap talks to Jarvis from anywhere. While hands-free mode runs it starts
 * listening right away (screen off works); while AntiGem runs in the background it listens without opening the app
 * ([JarvisTalkActivity]); otherwise it opens the app and starts listening there.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        JarvisPreferences.init(this)
        qsTile?.apply {
            state = if (JarvisPreferences.enabled.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Jarvis"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (JarvisPreferences.handsFree.value) L.jarvis.tileService.handsFree else if (JarvisPreferences.enabled.value) L.jarvis.tileService.tapToTalk else L.jarvis.tileService.off
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        JarvisPreferences.init(this)
        if (JarvisController.isAttached && JarvisPreferences.enabled.value && JarvisPreferences.handsFree.value) {
            runCatching { startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_TALK)) }
            return
        }
        if (JarvisController.isAttached && JarvisPreferences.enabled.value && JarvisPreferences.voiceAssistant.value) {
            // AntiGem is running: an invisible screen starts Jarvis' microphone and closes, the app stays in the back
            val talk = Intent(this, JarvisTalkActivity::class.java).setAction(JarvisLauncher.ACTION_TALK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isLocked) unlockAndRun { open(talk, 76) } else open(talk, 76)
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TALK, JarvisPreferences.enabled.value)
        open(intent, 77)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent, code: Int) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** MainActivity starts listening when it is opened with this extra. */
        const val EXTRA_TALK = "ai.antigem.jarvis.EXTRA_TALK"
    }
}
