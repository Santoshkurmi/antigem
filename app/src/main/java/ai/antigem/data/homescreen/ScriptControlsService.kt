package ai.antigem.data.homescreen

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.templates.StatelessTemplate
import androidx.annotation.RequiresApi
import ai.antigem.R
import ai.antigem.data.run.RunStatus
import ai.antigem.data.voice.VoiceCallLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Flow
import java.util.function.Consumer
import ai.antigem.i18n.L

/**
 * The scripts of ~/.shortcuts as buttons in Android's Device controls (Android 11+: the power menu; 12+: the Device
 * controls tile in Quick Settings), like Termux:Widget's. The user picks which ones in Android's own "Add controls"
 * screen. A script in a `tasks` folder runs in the background and its button shows Running… → ✓ Done / ✗ Failed; any
 * other script opens AntiGem with a new terminal tab (when Android doesn't let the app come to the front by itself, a
 * notification opens it). Long-pressing a button opens AntiGem's Run or Terminal page.
 *
 * Safety: only Android's system UI can bind this service (BIND_CONTROLS); a locked phone has to be unlocked first
 * (authRequired, and checked again here); the scripts go through [HomeScripts]'s own checks, as from the widget.
 * Control ids are [HomeShortcuts.scriptShortcutId]s.
 */
@RequiresApi(30)
class ScriptControlsService : ControlsProviderService() {

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        val app = applicationContext
        val pub = ControlsPublisher()
        scope.launch {
            // the Ubuntu chroot's list is read through root: fresh when the user is choosing
            val listing = runCatching { HomeScripts.refresh(app) }.getOrElse { HomeScripts.listing(app) }
            listing.scripts.forEach { s -> pub.emit(control(app, s, stateful = false)) }
            pub.complete()
        }
        return pub
    }

    override fun createPublisherFor(controlIds: MutableList<String>): Flow.Publisher<Control> {
        val app = applicationContext
        val pub = ControlsPublisher(controlIds.toSet())
        live += pub
        pub.onCancel = { live -= pub }
        scope.launch {
            var listing = HomeScripts.listing(app)
            if (listing.chroot && !listing.read) listing = runCatching { HomeScripts.refresh(app) }.getOrDefault(listing)
            controlIds.forEach { id -> pub.emit(controlFor(app, id, listing)) }
        }
        return pub
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val app = applicationContext
        if (action !is CommandAction) {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }
        // Android asks for the unlock first (authRequired); a locked phone never runs a script anyway
        if (getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            setState(app, controlId, L.homescreen.scriptControls.unlockThePhoneFirst)
            return
        }
        consumer.accept(ControlAction.RESPONSE_OK)
        main.post { run(app, controlId) }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val main = Handler(Looper.getMainLooper())
        /** The status line of each button (null: idle). */
        private val states = ConcurrentHashMap<String, String>()
        /** The buttons Android shows now (one publisher per panel). */
        private val live = CopyOnWriteArraySet<ControlsPublisher>()

        private fun parse(id: String): HomeLinks.Target.Script? {
            if (!id.startsWith("script:")) return null
            val rest = id.removePrefix("script:")
            val chroot = rest.startsWith("chroot:")
            val path = if (chroot) rest.removePrefix("chroot:") else rest
            return if (path.startsWith("/")) HomeLinks.Target.Script(path, background = false, chroot = chroot) else null
        }

        private fun find(listing: HomeScripts.Listing, t: HomeLinks.Target.Script): HomeScripts.Script? =
            listing.scripts.firstOrNull { it.path == t.path && it.chroot == t.chroot }

        private fun run(app: Context, id: String) {
            val t = parse(id) ?: return
            val s = find(HomeScripts.listing(app), t)
            if (s == null) {
                setState(app, id, L.homescreen.scriptControls.notInShortcutsAnyMore)
                return
            }
            // where the script is now decides how it runs, as on the widget
            val target = HomeLinks.Target.Script(s.path, s.background, s.chroot)
            if (s.background) {
                HomeScripts.runInBackground(app, target) { st ->
                    setState(
                        app, id, when (st) {
                            HomeScripts.RunState.Started -> L.homescreen.scriptControls.running
                            HomeScripts.RunState.Refused -> L.homescreen.scriptControls.couldntRunIt
                            is HomeScripts.RunState.Ended -> when (st.status) {
                                RunStatus.SUCCEEDED -> L.homescreen.scriptControls.done
                                RunStatus.STOPPED -> L.homescreen.scriptControls.stopped
                                else -> st.exit?.let { L.homescreen.scriptControls.failedExitCode(it) } ?: L.homescreen.scriptControls.failed
                            }
                        }
                    )
                }
            } else {
                openTerminal(app, id, s, target)
            }
        }

        /**
         * A terminal tab needs the app on screen. Android lets an app come to the front from here only in some cases
         * (with "Display over other apps", before Android 15); when it didn't, a notification opens it.
         */
        private fun openTerminal(app: Context, id: String, s: HomeScripts.Script, target: HomeLinks.Target.Script) {
            setState(app, id, L.homescreen.scriptControls.opening)
            runCatching { app.startActivity(HomeLinks.intent(app, target)) }
            main.postDelayed({
                if (VoiceCallLauncher.appVisible.value) {
                    setState(app, id, null)
                } else {
                    HomeScripts.notifyTapToOpen(app, s.name, target)
                    setState(app, id, L.homescreen.scriptControls.tapTheNotificationToOpenIt)
                }
            }, 3000)
        }

        private fun setState(app: Context, id: String, text: String?) {
            if (text == null) states.remove(id) else states[id] = text
            val listing = HomeScripts.listing(app)
            live.filter { id in it.ids }.forEach { it.emit(controlFor(app, id, listing)) }
        }

        private fun controlFor(app: Context, id: String, listing: HomeScripts.Listing): Control {
            val t = parse(id)
            val s = t?.let { find(listing, it) }
            if (s != null) return control(app, s, stateful = true)
            val name = t?.path?.substringAfterLast('/') ?: id
            return Control.StatefulBuilder(id, pageIntent(app, id, background = false))
                .setTitle(name)
                .setStatus(Control.STATUS_NOT_FOUND)
                .setStatusText(L.homescreen.scriptControls.notInShortcutsAnyMore)
                .build()
        }

        /** Android 12+: a terminal or a clock instead of Android's "routine" icon (a thermometer and a bulb). */
        private fun icon(app: Context, background: Boolean): Icon =
            Icon.createWithResource(app, if (background) R.drawable.ic_home_task else R.drawable.ic_home_terminal)
                .setTint(0xFF7986CB.toInt())

        private fun control(app: Context, s: HomeScripts.Script, stateful: Boolean): Control {
            val id = HomeShortcuts.scriptShortcutId(s)
            val subtitle = if (s.background) L.homescreen.scriptsWidget.runsInTheBackground else L.homescreen.scriptsWidget.opensATerminalTab
            val intent = pageIntent(app, id, s.background)
            if (!stateful) {
                return Control.StatelessBuilder(id, intent)
                    .setTitle(s.label)
                    .setSubtitle(subtitle)
                    .setStructure(L.homescreen.scriptsWidget.scripts)
                    .apply { if (s.chroot) setZone(L.homescreen.scriptControls.ubuntu) }
                    .setDeviceType(DeviceTypes.TYPE_ROUTINE)
                    .apply { if (Build.VERSION.SDK_INT >= 31) setCustomIcon(icon(app, s.background)) }
                    .build()
            }
            return Control.StatefulBuilder(id, intent)
                .setTitle(s.label)
                .setSubtitle(subtitle)
                .setStructure(L.homescreen.scriptsWidget.scripts)
                .apply { if (s.chroot) setZone(L.homescreen.scriptControls.ubuntu) }
                .setDeviceType(DeviceTypes.TYPE_ROUTINE)
                .setStatus(Control.STATUS_OK)
                .setControlTemplate(StatelessTemplate(id))
                .setStatusText(states[id] ?: L.homescreen.scriptControls.tapToRun)
                .apply {
                    if (Build.VERSION.SDK_INT >= 31) {
                        setAuthRequired(true)
                        setCustomIcon(icon(app, s.background))
                    }
                }
                .build()
        }

        /** Long-press: the page the script's output is on (never the script itself). */
        private fun pageIntent(app: Context, id: String, background: Boolean): PendingIntent =
            PendingIntent.getActivity(
                app, id.hashCode(),
                HomeLinks.intent(app, HomeLinks.Target.OpenPage(if (background) HomeLinks.Page.RUN else HomeLinks.Page.TERMINAL)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

/**
 * Hands controls to Android's system UI (java.util.concurrent.Flow, which the controls API speaks), honouring what it
 * asks for. [complete] ends it (the "Add controls" list); otherwise it stays open for status updates until the
 * system UI cancels it.
 */
@RequiresApi(30)
private class ControlsPublisher(val ids: Set<String> = emptySet()) : Flow.Publisher<Control> {
    private val lock = Any()
    private var subscriber: Flow.Subscriber<in Control>? = null
    private val queue = ArrayDeque<Control>()
    private var demand = 0L
    private var completed = false
    private var done = false
    private var draining = false
    var onCancel: (() -> Unit)? = null

    private sealed interface Step {
        class Next(val s: Flow.Subscriber<in Control>, val c: Control) : Step
        class Finish(val s: Flow.Subscriber<in Control>) : Step
        data object Stop : Step
    }

    override fun subscribe(s: Flow.Subscriber<in Control>) {
        val first = synchronized(lock) { if (subscriber == null) { subscriber = s; true } else false }
        if (!first) {
            s.onSubscribe(object : Flow.Subscription {
                override fun request(n: Long) {}
                override fun cancel() {}
            })
            s.onError(IllegalStateException("one subscriber only"))
            return
        }
        s.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) {
                if (n <= 0) return
                synchronized(lock) { demand = if (Long.MAX_VALUE - demand < n) Long.MAX_VALUE else demand + n }
                drain()
            }

            override fun cancel() {
                synchronized(lock) {
                    done = true
                    queue.clear()
                }
                onCancel?.invoke()
            }
        })
        drain()
    }

    fun emit(c: Control) {
        synchronized(lock) {
            if (done) return
            queue.addLast(c)
        }
        drain()
    }

    fun complete() {
        synchronized(lock) { completed = true }
        drain()
    }

    private fun drain() {
        synchronized(lock) {
            if (draining) return
            draining = true
        }
        while (true) {
            // the decision to stop and the end of draining happen together: an emit right after starts its own drain
            val step = synchronized(lock) {
                val s = subscriber
                when {
                    s == null || done -> { draining = false; Step.Stop }
                    queue.isNotEmpty() && demand > 0 -> {
                        if (demand != Long.MAX_VALUE) demand--
                        Step.Next(s, queue.removeFirst())
                    }
                    queue.isEmpty() && completed -> { done = true; draining = false; Step.Finish(s) }
                    else -> { draining = false; Step.Stop }
                }
            }
            when (step) {
                is Step.Next -> runCatching { step.s.onNext(step.c) }.onFailure {
                    synchronized(lock) { done = true; draining = false }
                    return
                }
                is Step.Finish -> { runCatching { step.s.onComplete() }; return }
                Step.Stop -> return
            }
        }
    }
}
