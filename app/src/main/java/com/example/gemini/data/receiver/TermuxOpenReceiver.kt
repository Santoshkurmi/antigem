package com.example.gemini.data.receiver

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class TermuxOpenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TermuxOpenReceiver"

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
                        setDataAndType(null, contentTypeExtra ?: "text/plain")
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

                try {
                    context.startActivity(finalIntent)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No app handles url $data")
                    Toast.makeText(context, "No app found to open: $data", Toast.LENGTH_SHORT).show()
                }
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

            try {
                context.startActivity(finalIntent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No app handles file: ${fileToShare.absolutePath}")
                Toast.makeText(context, "No application found to open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        handleOpen(context, intent)
    }
}
