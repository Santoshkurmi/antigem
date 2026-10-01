package com.example.gemini.data.receiver

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.gemini.R
import java.io.File

open class TermuxOpenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TermuxOpenReceiver"
        private const val CHANNEL_ID = "antigem_termux_open_channel"
        private const val NOTIFICATION_ID = 1338

        fun handleOpen(context: Context, intent: Intent) {
            val data = intent.data ?: run {
                val filePath = intent.getStringExtra("file") ?: intent.getStringExtra("path")
                if (filePath != null) Uri.fromFile(File(filePath)) else null
            }

            if (data == null) {
                Log.w(TAG, "Called without intent data")
                return
            }

            val contentTypeExtra = intent.getStringExtra("content-type") ?: intent.getStringExtra("type")
            val useChooser = intent.getBooleanExtra("chooser", false)
            val intentAction = when (intent.action) {
                Intent.ACTION_SEND -> Intent.ACTION_SEND
                else -> Intent.ACTION_VIEW
            }

            val scheme = data.scheme
            // Non-file URLs (e.g. https, http, mailto, tel, etc.)
            if (scheme != null && scheme != "file" && !scheme.startsWith("/")) {
                val urlIntent = Intent(intentAction, data).apply {
                    if (intentAction == Intent.ACTION_SEND) {
                        putExtra(Intent.EXTRA_TEXT, data.toString())
                        type = contentTypeExtra ?: "text/plain"
                    } else if (contentTypeExtra != null) {
                        setDataAndType(data, contentTypeExtra)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val finalIntent = if (useChooser) {
                    Intent.createChooser(urlIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } else {
                    urlIntent
                }

                launchSafely(context, finalIntent, "Open Link", data.toString())
                return
            }

            // File path handling
            val rawPath = if (scheme == "file") data.path else data.toString()
            if (rawPath.isNullOrBlank()) {
                Log.w(TAG, "Empty file path")
                return
            }

            val fileToShare = File(rawPath)
            if (!fileToShare.exists() || !fileToShare.canRead()) {
                Log.w(TAG, "Not a readable file: ${fileToShare.absolutePath}")
                Toast.makeText(context, "File not found or unreadable: ${fileToShare.name}", Toast.LENGTH_SHORT).show()
                return
            }

            val contentTypeToUse = if (!contentTypeExtra.isNullOrBlank()) {
                contentTypeExtra
            } else {
                val name = fileToShare.name
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty()) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                } else {
                    "application/octet-stream"
                }
            }

            val authority = "${context.packageName}.fileprovider"
            val uriToShare = try {
                FileProvider.getUriForFile(context, authority, fileToShare)
            } catch (e: Exception) {
                Log.w(TAG, "FileProvider error, falling back to Uri.fromFile: ${e.message}")
                Uri.fromFile(fileToShare)
            }

            val openIntent = Intent(intentAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (intentAction == Intent.ACTION_SEND) {
                    putExtra(Intent.EXTRA_STREAM, uriToShare)
                    type = contentTypeToUse
                } else {
                    setDataAndType(uriToShare, contentTypeToUse)
                }
            }

            val finalIntent = if (useChooser) {
                Intent.createChooser(openIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                openIntent
            }

            launchSafely(context, finalIntent, "Open File: ${fileToShare.name}", fileToShare.absolutePath)
        }

        private fun launchSafely(context: Context, intent: Intent, title: String, description: String) {
            // Priority 1: If user is actively inside the app, launch directly from foreground Activity to bypass BAL
            val foregroundActivity = com.example.gemini.MainActivity.currentInstance
            if (foregroundActivity != null) {
                try {
                    foregroundActivity.startActivity(intent)
                    return
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No app handles intent: ${e.message}")
                    Toast.makeText(foregroundActivity, "No application found to handle this request", Toast.LENGTH_SHORT).show()
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Activity.startActivity failed: ${e.message}")
                }
            }

            // Priority 2: When in background, prepare ActivityOptions with background launch permission
            val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }.toBundle()
            } else {
                null
            }

            // Create high-priority pending intent
            val pendingIntent = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() and 0xFFFF).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Try direct launch using PendingIntent with background activity start mode
            var directLaunched = false
            try {
                if (optionsBundle != null) {
                    pendingIntent.send(context, 0, null, null, null, null, optionsBundle)
                    directLaunched = true
                }
            } catch (e: Exception) {
                Log.d(TAG, "PendingIntent.send background launch failed: ${e.message}")
            }

            if (!directLaunched) {
                try {
                    context.startActivity(intent, optionsBundle)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No app handles intent: ${e.message}")
                    Toast.makeText(context, "No application found to handle this request", Toast.LENGTH_SHORT).show()
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Direct startActivity failed: ${e.message}")
                }
            }

            // High-priority notification fallback in case background activity start was suppressed by OS
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(
                            CHANNEL_ID,
                            "Termux Open",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            this.description = "Terminal open requests"
                            enableLights(true)
                            enableVibration(true)
                        }
                        nm.createNotificationChannel(channel)
                    }

                    val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(description)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_EVENT)
                        .build()

                    nm.notify(NOTIFICATION_ID, notif)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Notification fallback failed: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        handleOpen(context, intent)
    }
}
