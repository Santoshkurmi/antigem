package com.example.gemini.data.agent

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.Conversation
import com.example.gemini.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Notices when a chat stops working: it was shown as running in the sidebar and no longer is (for a short settle time,
 * so a flicker between steps does not count). Gives a subtle vibration and publishes [events] for Jarvis' announcements.
 */
object ChatCompletionMonitor {
    // one thread: the monitor's state is only touched from here
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var started = false
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var app: Application

    private val _vibrate = MutableStateFlow(true)
    /** A light buzz once when a chat finishes. */
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()

    private val _events = MutableSharedFlow<Conversation>(extraBufferCapacity = 8)
    /** A chat that has just finished working. */
    val events: SharedFlow<Conversation> = _events.asSharedFlow()

    fun setVibrate(on: Boolean) {
        _vibrate.value = on
        prefs?.edit()?.putBoolean("vibrate_on_complete", on)?.apply()
    }

    @Synchronized
    fun start(application: Application, vm: ChatViewModel) {
        if (started) return
        started = true
        app = application
        prefs = application.getSharedPreferences("chat_alerts", Context.MODE_PRIVATE)
        _vibrate.value = prefs?.getBoolean("vibrate_on_complete", true) ?: true
        scope.launch { loop(vm) }
    }

    /**
     * How long a chat must stay idle before it counts as finished. Claude's running flag is the turn itself (it only
     * clears at the turn's result), so a short wait is enough; Antigravity's can blink off between steps.
     */
    private fun settleMs(c: Conversation): Long = if (c.agent == AgentKind.CLAUDE) 300L else 1200L

    private suspend fun loop(vm: ChatViewModel) {
        // all on one thread (see [scope]), so the maps need no locking
        val busyState = HashMap<String, Boolean>()
        val pending = HashMap<String, Job>()
        // reacts the moment the running state changes, instead of polling (that added up to ~4 s before the buzz)
        combine(vm.allConversations, vm.systemConnectionState) { convs, conn -> convs to conn.isHubOnline }
            .collect { (convs, hubOnline) ->
                val seen = HashSet<String>()
                for (c in convs) {
                    seen += c.id
                    // the same signal the sidebar's running dot uses
                    val busy = (c.isRunning && (hubOnline || c.agent == AgentKind.CLAUDE)) || (c.agent == AgentKind.AGY && hubOnline && c.notFullyIdle)
                    when {
                        busy -> {
                            busyState[c.id] = true
                            pending.remove(c.id)?.cancel()
                        }
                        busyState[c.id] == true && pending[c.id] == null -> {
                            pending[c.id] = scope.launch {
                                delay(settleMs(c))
                                pending.remove(c.id)
                                busyState[c.id] = false
                                onFinished(c)
                            }
                        }
                        else -> busyState.putIfAbsent(c.id, false)
                    }
                }
                pending.keys.filter { it !in seen }.forEach { pending.remove(it)?.cancel() }
                busyState.keys.retainAll(seen)
            }
    }

    private fun onFinished(c: Conversation) {
        // Jarvis' own chat finishing is part of every voice exchange: no buzz and no announcement for it
        if (JarvisPreferences.isJarvisChat(c.id)) return
        if (_vibrate.value) buzz()
        _events.tryEmit(c)
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        runCatching {
            val v = app.getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(28, 60)) else v.vibrate(28)
        }
    }
}
