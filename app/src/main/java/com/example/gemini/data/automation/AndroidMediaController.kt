package com.example.gemini.data.automation

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Native Android media playback and hardware audio controller.
 * Dispatches standard Android media key events to control active players (Spotify, YouTube, Music players).
 */
class AndroidMediaController(private val context: Context) {

    companion object {
        private const val TAG = "AndroidMediaController"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Checks if music / media audio is currently actively playing.
     */
    fun isMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    /**
     * Dispatch standard media key event.
     */
    fun sendMediaKeyEvent(keyCode: Int): Boolean {
        return try {
            val eventTime = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(downEvent)

            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(upEvent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media key event: $keyCode", e)
            false
        }
    }

    fun play(): Boolean = sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause(): Boolean = sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun togglePlayPause(): Boolean = sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun nextTrack(): Boolean = sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previousTrack(): Boolean = sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun stop(): Boolean = sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP)

    fun volumeUp(): Boolean {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            true
        } catch (_: Exception) { false }
    }

    fun volumeDown(): Boolean {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            true
        } catch (_: Exception) { false }
    }

    fun mute(): Boolean {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_TOGGLE_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
            true
        } catch (_: Exception) { false }
    }

    fun getMediaStatus(): String {
        val isPlaying = isMusicActive()
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volPct = ((currentVol.toFloat() / maxVol.toFloat()) * 100).toInt()

        return "Playback status: ${if (isPlaying) "Playing ▶️" else "Paused / Inactive ⏸️"}\nMedia volume: $volPct% ($currentVol/$maxVol)"
    }
}
