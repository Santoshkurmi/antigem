package com.example.gemini.data.automation

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Dispatcher for AI Android Automation Tool Calls.
 */
class AutomationToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AutomationToolExecutor"
    }

    private val manager = AndroidAutomationManager(context)

    suspend fun execute(rawPayload: String): Pair<String, Boolean> {
        return try {
            val json = if (rawPayload.trim().startsWith("{")) {
                JSONObject(rawPayload)
            } else {
                JSONObject().apply {
                    put("action", rawPayload.trim())
                }
            }

            val action = json.optString("action").ifEmpty {
                // If action is not explicit, infer from keys
                when {
                    json.has("text") && !json.has("content") -> "tap"
                    json.has("app") || json.has("package") -> "launch_app"
                    json.has("command") -> "media_control"
                    json.has("direction") -> "scroll"
                    json.has("key") -> "press_key"
                    else -> "analyze_screen"
                }
            }

            val result = manager.execute(action, json)
            Pair(result.message, result.isSuccess)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing automation tool", e)
            Pair("❌ Automation Error: ${e.localizedMessage}", false)
        }
    }
}
