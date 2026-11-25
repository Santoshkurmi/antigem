package ai.antigem.data.jarvis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ai.antigem.MainActivity
import ai.antigem.R
import ai.antigem.data.voice.VoiceCallLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * Talking to Jarvis from outside the app: the Jarvis Quick Settings tile, the "Jarvis" app shortcut and the floating
 * orb. Outside the app Jarvis hears through its microphone service: [talkOutside] starts it (Android lets it use the
 * microphone because it starts while something of AntiGem is on screen: the invisible [JarvisTalkActivity] or the
 * floating orb) and it stops again once Jarvis has been idle a while, unless hands-free is on. When Jarvis then wants
 * to show something in the app (a chat, the IDE, settings), AntiGem comes to the front.
 */
object JarvisLauncher {
    private const val TAG = "JarvisLauncher"
    /** The tile's and the shortcut's intent action for [JarvisTalkActivity]. */
    const val ACTION_TALK = "ai.antigem.jarvis.LAUNCH_TALK"
    private const val SHORTCUT_ID = "jarvis_talk"
    /** Idle this long after a talk from outside, the microphone service stops again (hands-free off). */
    private const val SERVICE_IDLE_MS = 10_000L
    /** How long after Jarvis was used from outside a request to show something in the app still brings it forward. */
    private const val RECENT_USE_MS = 120_000L
    /** While Jarvis is still busy with something asked from outside, this long. */
    private const val BUSY_USE_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    /** A talk from outside started the microphone service (hands-free was off): it stops it again when Jarvis is idle. */
    private var startedService = false
    @Volatile private var lastUse = 0L

    /** Jarvis was used from outside the app just now (a tap, a long-press, a notice). */
    fun noteUse() {
        lastUse = SystemClock.elapsedRealtime()
    }

    /** Starts following Jarvis (once; after [JarvisController.attach]). Main thread. */
    fun attach(context: Context) {
        if (app != null || !JarvisController.isAttached) return
        val a = context.applicationContext
        app = a

        // the microphone service a talk from outside started stops again once Jarvis has been idle a while
        scope.launch {
            JarvisController.phase.collectLatest { p ->
                if (p != JarvisController.Phase.IDLE && p != JarvisController.Phase.OFF) return@collectLatest
                delay(SERVICE_IDLE_MS)
                if (startedService && !JarvisPreferences.handsFree.value && !JarvisController.panelOpen.value) {
                    startedService = false
                    runCatching { JarvisService.stop(a) }
                }
            }
        }

        // Jarvis wants to show something in the app (a chat, the IDE, settings): bring AntiGem to the front
        scope.launch {
            combine(JarvisController.settingsRequest, JarvisController.vm.requestedViewMode) { s, m -> s != null || m != null }
                .collect { wanted -> if (wanted && usedFromOutside()) openApp(a) }
        }

        // "Jarvis" in the long-press menu of the app icon while Jarvis (with its voice) is on
        scope.launch {
            combine(JarvisPreferences.enabled.state, JarvisPreferences.voiceAssistant.state) { e, v -> e && v }
                .distinctUntilChanged()
                .collect { on ->
                    runCatching {
                        if (on) ShortcutManagerCompat.pushDynamicShortcut(a, shortcut(a))
                        else ShortcutManagerCompat.removeDynamicShortcuts(a, listOf(SHORTCUT_ID))
                    }.onFailure { Log.w(TAG, "shortcut: ${it.message}") }
                }
        }
    }

    private fun usedFromOutside(): Boolean {
        if (VoiceCallLauncher.appVisible.value || lastUse == 0L) return false
        val since = SystemClock.elapsedRealtime() - lastUse
        val p = JarvisController.phase.value
        val busy = p == JarvisController.Phase.LISTENING || p == JarvisController.Phase.THINKING || p == JarvisController.Phase.SPEAKING
        return since < RECENT_USE_MS || (busy && since < BUSY_USE_MS) ||
            JarvisController.panelOpen.value || JarvisController.quickSettingsOpen.value
    }

    /**
     * Talk to Jarvis with the app in the background: through the microphone service (started if it isn't up). Call it
     * while something of AntiGem is on screen (the invisible screen, the floating orb), or Android refuses the mic.
     */
    fun talkOutside() {
        val a = app ?: return
        noteUse()
        if (JarvisService.isRunning) {
            JarvisController.onMicPressed()
            return
        }
        val started = runCatching { JarvisService.talk(a) }
        if (started.isFailure) {
            Log.w(TAG, "talk: ${started.exceptionOrNull()?.message}")
            JarvisController.showProblem(REFUSED)
            return
        }
        startedService = true
        // the service says nothing when Android refuses it: check that it came up
        scope.launch {
            delay(2500)
            if (!JarvisService.isRunning) {
                startedService = false
                JarvisController.showProblem(REFUSED)
            }
        }
    }

    private const val REFUSED = "Android didn't let Jarvis use the microphone from here. Open AntiGem, or turn on hands-free mode."

    /** [JarvisTalkActivity] is in front: talk now, or open the app when Jarvis isn't ready for that. */
    fun launch(activity: Activity) {
        when {
            // the app hasn't run yet: it starts listening once it is up (as the tile always did)
            !JarvisController.isAttached ->
                openApp(activity, Intent().putExtra(JarvisTileService.EXTRA_TALK, true))
            !JarvisPreferences.enabled.value || !JarvisPreferences.voiceAssistant.value -> {
                JarvisController.requestSettings("JARVIS")
                openApp(activity)
            }
            // the microphone permission needs the app
            !JarvisController.hasMic() ->
                openApp(activity, Intent().putExtra(JarvisTileService.EXTRA_TALK, true))
            else -> talkOutside()
        }
    }

    fun openApp(context: Context, extras: Intent? = null) {
        JarvisController.panelOpen.value = false
        JarvisController.quickSettingsOpen.value = false
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        extras?.extras?.let { intent.putExtras(it) }
        if (intent.getBooleanExtra(JarvisTileService.EXTRA_TALK, false)) {
            ai.antigem.data.security.AppIntentKey.sign(context, intent, ai.antigem.data.security.AppIntentKey.JARVIS_TALK)
        }
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "open app: ${it.message}") }
    }

    private fun shortcut(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("Jarvis")
            .setLongLabel(L.jarvis.launcher.talkToJarvis)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_jarvis))
            .setIntent(Intent(context, JarvisTalkActivity::class.java).setAction(ACTION_TALK))
            .build()

    /** Asks the launcher to put the Jarvis shortcut on the home screen. False when it can't. */
    fun pinShortcut(context: Context): Boolean = runCatching {
        ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
            ShortcutManagerCompat.requestPinShortcut(context, shortcut(context), null)
    }.getOrDefault(false)
}
