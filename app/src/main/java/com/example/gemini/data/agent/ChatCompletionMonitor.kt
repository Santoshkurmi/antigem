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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Notices when a chat stops working: it was shown as running in the sidebar and no longer is (for at least two seconds,
 * so a flicker between steps does not count). Gives a subtle vibration and publishes [events] for Jarvis' announcements.
 */
object ChatCompletionMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    private suspend fun loop(vm: ChatViewModel) {
        val busyState = HashMap<String, Boolean>()
        val idleSince = HashMap<String, Long>()
        while (scope.isActive) {
            delay(1500)
            try {
                val now = System.currentTimeMillis()
                val convs = vm.allConversations.value
                val hubOnline = vm.systemConnectionState.value.isHubOnline
                val seen = HashSet<String>()
                for (c in convs) {
                    seen += c.id
                    // the same signal the sidebar's running dot uses
                    val busy = (c.isRunning && (hubOnline || c.agent == AgentKind.CLAUDE)) || (c.agent == AgentKind.AGY && hubOnline && c.notFullyIdle)
                    when {
                        busy -> { busyState[c.id] = true; idleSince.remove(c.id) }
                        busyState[c.id] == true -> {
                            val since = idleSince.getOrPut(c.id) { now }
                            if (now - since >= 2000) {
                                busyState[c.id] = false
                                idleSince.remove(c.id)
                                onFinished(c)
                            }
                        }
                        else -> busyState.putIfAbsent(c.id, false)
                    }
                }
                busyState.keys.retainAll(seen)
                idleSince.keys.retainAll(seen)
            } catch (_: Exception) {
            }
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
