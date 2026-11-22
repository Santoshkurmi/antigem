package ai.antigem.data.voice

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ai.antigem.MainActivity
import ai.antigem.R
import ai.antigem.ui.chat.ChatViewModelHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-tap voice calls from outside the app: the Voice call Quick Settings tile and the "Voice call" launcher shortcut.
 * Both open [VoiceCallLaunchActivity], an invisible screen: Android lets a call use the microphone in the background
 * only when it started while the app was on screen, and that screen counts for the moment it is open.
 *
 * When the app is already running (server up, microphone allowed, a voice picked) the call starts right there, in the
 * last chat that had a call, and nothing of the app shows. Otherwise the app opens and its chat screen starts the
 * call once the server is up ([pending]).
 */
object VoiceCallLauncher {
    private const val TAG = "VoiceCallLauncher"
    /** MainActivity: start a call in the last chat that had one. */
    const val EXTRA_START_CALL = "ai.antigem.voicecall.EXTRA_START_CALL"
    /** The tile's and the shortcut's intent action for [VoiceCallLaunchActivity]. */
    const val ACTION_CALL = "ai.antigem.voicecall.CALL"
    private const val SHORTCUT_ID = "voice_call"
    /** A start request older than this is dropped (the chat screen never came up, e.g. setup was on screen). */
    private const val PENDING_MAX_MS = 3 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _pending = MutableStateFlow(0L)
    /** When the app was asked to start a call (elapsedRealtime), 0 = not asked. The chat screen takes it. */
    val pending: StateFlow<Long> = _pending.asStateFlow()

    private val _appVisible = MutableStateFlow(false)
    /** MainActivity is on screen: the floating call window stays away then (the app shows the call itself). */
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
    }

    /** MainActivity was opened with [EXTRA_START_CALL]. */
    fun requestStart() {
        _pending.value = SystemClock.elapsedRealtime()
    }

    /** Takes the start request: false when there is none, or it is too old. */
    fun consumePending(): Boolean {
        val at = _pending.value
        _pending.value = 0L
        return at > 0L && SystemClock.elapsedRealtime() - at < PENDING_MAX_MS
    }

    /** [VoiceCallLaunchActivity] is in front: starts the call here, or opens the app to start it. */
    fun launch(activity: Activity) {
        val app = activity.applicationContext
        AgyVoicePreferences.init(app)
        if (AgyVoiceCall.state.value.active) {
            // a call is on (or its failure is still up): show it
            AgyVoiceCall.setMinimized(false)
            openApp(activity, startCall = false)
            return
        }
        val vm = ChatViewModelHolder.peek()
        val micAllowed = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // the first call needs the app (voice picker, microphone permission); so does a server that is not up yet
        if (vm == null || !vm.systemConnectionState.value.canSend || !micAllowed || !AgyVoicePreferences.picked.value) {
            openApp(activity, startCall = true)
            return
        }
        // the floating call window (when on) opens as the panel, so the call is seen connecting
        ai.antigem.ui.voice.AgyVoiceBubble.openPanelForNextCall()
        // the call service starts in here, while the invisible screen is in front: that lets it use the microphone
        AgyVoiceCall.start(app, "Voice call") { vm.prepareVoiceCallInLastCallChat() }
        reportFailure(app)
    }

    private fun openApp(activity: Activity, startCall: Boolean) {
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (startCall) intent.putExtra(EXTRA_START_CALL, true)
        runCatching { activity.startActivity(intent) }.onFailure { Log.w(TAG, "open app: ${it.message}") }
    }

    /** A call started out of sight that fails says so (the call screen in the app has the details). */
    private fun reportFailure(app: Context) {
        scope.launch {
            val st = withTimeoutOrNull(90_000) {
                AgyVoiceCall.state.first { it.phase == AgyVoiceCall.Phase.LIVE || it.phase == AgyVoiceCall.Phase.FAILED || !it.active }
            } ?: return@launch
            if (st.phase == AgyVoiceCall.Phase.FAILED && !_appVisible.value) {
                Toast.makeText(app, "Voice call failed: " + (st.error ?: "open AntiGem for details"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shortcut(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("Voice call")
            .setLongLabel("Antigravity voice call")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_voice_call))
            .setIntent(Intent(context, VoiceCallLaunchActivity::class.java).setAction(ACTION_CALL))
            .build()

    /** "Voice call" in the launcher's long-press menu of the app icon (it can be dragged to the home screen from there). */
    fun publishShortcut(context: Context) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context)) }
            .onFailure { Log.w(TAG, "shortcut: ${it.message}") }
    }

    /** Asks the launcher to put the Voice call shortcut on the home screen. False when it can't. */
    fun pinShortcut(context: Context): Boolean = runCatching {
        ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
            ShortcutManagerCompat.requestPinShortcut(context, shortcut(context), null)
    }.getOrDefault(false)

    /** Android 13+ can add the Voice call tile to Quick Settings from the app (older ones: edit the tiles). */
    val canRequestTile: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Asks Android to add the Voice call tile to Quick Settings (Android 13+). False when it can't. */
    fun requestAddTile(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val sbm = context.getSystemService(StatusBarManager::class.java) ?: return false
            sbm.requestAddTileService(
                ComponentName(context, VoiceCallTileService::class.java),
                "Voice call",
                Icon.createWithResource(context, R.drawable.ic_voice_call),
                context.mainExecutor
            ) { result -> Log.i(TAG, "add tile: $result") }
            true
        }.getOrDefault(false)
    }
}
