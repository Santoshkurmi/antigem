package com.example.gemini.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.gemini.data.local.TermuxStorageSetupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TermuxSystemReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when {
            action.endsWith(".app.request_storage_permissions") || action == "com.termux.app.request_storage_permissions" -> {
                if (TermuxStorageSetupManager.hasStoragePermission(context)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val success = TermuxStorageSetupManager.setupStorageSymlinks(context)
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                context,
                                if (success) "Storage symlinks setup in ~/storage" else "Failed setting up storage symlinks",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    TermuxStorageSetupManager.requestStoragePermission(context)
                }
            }
            action.endsWith(".app.reload_style") || action == "com.termux.app.reload_style" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    TermuxStorageSetupManager.setupStorageSymlinks(context)
                }
            }
        }
    }
}
