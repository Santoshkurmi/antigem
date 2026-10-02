package com.example.gemini.data.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class AntiGemDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "AntiGem Device Admin Protection ENABLED")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "AntiGem Device Admin Protection DISABLED")
    }

    companion object {
        private const val TAG = "AntiGemDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, AntiGemDeviceAdminReceiver::class.java)
        }

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
            return dpm.isAdminActive(getComponentName(context))
        }

        fun createActivationIntent(context: Context): Intent {
            return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Enabling Device Admin protects AntiGem from accidental uninstallation so your Linux environment and coding projects are not lost."
                )
            }
        }

        fun createDeviceAdminSettingsIntent(): Intent {
            return Intent("android.settings.DEVICE_ADMIN_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun deactivateAdmin(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            try {
                dpm.removeActiveAdmin(getComponentName(context))
                Log.d(TAG, "Removed AntiGem active Device Admin")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove Device Admin: ${e.message}", e)
            }
        }
    }
}
