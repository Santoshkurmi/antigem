package com.example.gemini.data.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.gemini.R
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.util.Locale

class TermuxApiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TermuxApiReceiver"
        private const val API_NOTIFICATION_CHANNEL_ID = "antigem_termux_api_notifications"
        private var ttsInstance: TextToSpeech? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val apiMethod = intent.getStringExtra("api_method")
            ?: intent.getStringExtra("method")
            ?: intent.action?.substringAfterLast('.')
            ?: return

        Log.d(TAG, "Termux:API call received: $apiMethod")

        when (apiMethod.lowercase()) {
            "toast" -> handleToast(context, intent)
            "vibrate" -> handleVibrate(context, intent)
            "clipboard" -> {
                val isSet = intent.getBooleanExtra("set", false) || intent.hasExtra("text") || intent.hasExtra("clip")
                if (isSet) {
                    handleClipboardSet(context, intent)
                } else {
                    handleClipboardGet(context, intent)
                }
            }
            "clipboardset", "clipboard-set" -> handleClipboardSet(context, intent)
            "clipboardget", "clipboard-get" -> handleClipboardGet(context, intent)
            "torch" -> handleTorch(context, intent)
            "tts", "texttospeech", "tts-speak" -> handleTts(context, intent)
            "notification" -> handleNotification(context, intent)
            "notification-remove", "notificationremove" -> handleNotificationRemove(context, intent)
            "volume" -> handleVolume(context, intent)
            "batterystatus", "battery-status" -> handleBatteryStatus(context, intent)
            else -> {
                Log.w(TAG, "Unhandled Termux:API method: $apiMethod")
            }
        }
    }

    private fun handleToast(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: intent.getStringExtra("message") ?: intent.getStringExtra("content") ?: return
        val isShort = intent.getBooleanExtra("short", false)
        val duration = if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, duration).show()
        }
    }

    private fun handleVibrate(context: Context, intent: Intent) {
        val durationMs = intent.getIntExtra("duration_ms", intent.getIntExtra("duration", 1000)).toLong()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed vibrating: ${e.message}")
        }
    }

    private fun handleClipboardSet(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: intent.getStringExtra("clip") ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                val targetContext = com.example.gemini.MainActivity.currentInstance ?: context
                val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Termux Clipboard", text)
                clipboard?.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting clipboard: ${e.message}")
            }
        }
    }

    private fun handleClipboardGet(context: Context, intent: Intent) {
        Handler(Looper.getMainLooper()).post {
            try {
                val targetContext = com.example.gemini.MainActivity.currentInstance ?: context
                val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0)?.coerceToText(targetContext)?.toString() ?: ""
                } else {
                    ""
                }
                sendSocketResponse(intent, text)
            } catch (e: Exception) {
                Log.w(TAG, "Failed getting clipboard: ${e.message}")
                sendSocketResponse(intent, "")
            }
        }
    }

    private fun handleTorch(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra("enabled", true)
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                flashAvailable && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enabled)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed controlling torch: ${e.message}")
        }
    }

    private fun handleTts(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: intent.getStringExtra("message") ?: return
        Handler(Looper.getMainLooper()).post {
            if (ttsInstance == null) {
                ttsInstance = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsInstance?.language = Locale.getDefault()
                        ttsInstance?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "termux_tts")
                    }
                }
            } else {
                ttsInstance?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "termux_tts")
            }
        }
    }

    private fun handleNotification(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val id = intent.getIntExtra("id", 4242)
        val title = intent.getStringExtra("title") ?: "Termux Notification"
        val content = intent.getStringExtra("content") ?: intent.getStringExtra("text") ?: ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                API_NOTIFICATION_CHANNEL_ID,
                "Termux API Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, API_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(id, notification)
    }

    private fun handleNotificationRemove(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val id = intent.getIntExtra("id", 4242)
        notificationManager.cancel(id)
    }

    private fun handleVolume(context: Context, intent: Intent) {
        val streamName = intent.getStringExtra("stream")?.lowercase() ?: "music"
        val volume = intent.getIntExtra("volume", -1)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val streamType = when (streamName) {
            "alarm" -> AudioManager.STREAM_ALARM
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "voice_call", "call" -> AudioManager.STREAM_VOICE_CALL
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }

        if (volume >= 0) {
            audioManager.setStreamVolume(streamType, volume, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun handleBatteryStatus(context: Context, intent: Intent) {
        try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()) else -1f
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val temperature = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

            val result = """{"percentage":$batteryPct,"is_charging":$isCharging,"plugged":$plugged,"temperature":$temperature}"""
            sendSocketResponse(intent, result)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading battery status: ${e.message}")
        }
    }

    private fun sendSocketResponse(intent: Intent, data: String) {
        val socketOutput = intent.getStringExtra("socket_output")
            ?: intent.getStringExtra("output")
            ?: intent.getStringExtra("result_file")

        if (socketOutput != null) {
            try {
                if (socketOutput.startsWith("/")) {
                    val file = File(socketOutput)
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { it.write(data.toByteArray(Charsets.UTF_8)) }
                } else if (socketOutput.contains(":")) {
                    val host = socketOutput.substringBefore(":")
                    val port = socketOutput.substringAfter(":").toIntOrNull() ?: return
                    Socket(host, port).use { sock ->
                        sock.getOutputStream().write(data.toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write API response to $socketOutput: ${e.message}")
            }
        }
    }
}
