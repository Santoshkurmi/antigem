package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyGrpcClient
import com.example.gemini.domain.model.McpServerSpec
import com.example.gemini.domain.model.McpServerState
import com.example.gemini.domain.model.McpToolInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dedicated RPC service for Model Context Protocol (MCP) server states, refreshing, and toggling.
 */
class AgyMcpService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance
) {
    companion object {
        private const val TAG = "AgyMcpService"
        val instance by lazy { AgyMcpService() }
    }

    suspend fun getMcpServerStates(hubUrl: String = AuthPreferences.currentHubUrl): Result<List<McpServerState>> = withContext(Dispatchers.IO) {
        try {
            val res = grpcClient.callUnary("GetMcpServerStates", "{}", hubUrl)
            if (res.isFailure) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("GetMcpServerStates failed"))
            }
            val body = res.getOrNull() ?: "{}"
            val json = JSONObject(body)
            val result = mutableListOf<McpServerState>()

            if (json.has("states")) {
                val statesObj = json.get("states")
                if (statesObj is JSONArray) {
                    for (i in 0 until statesObj.length()) {
                        val item = statesObj.optJSONObject(i) ?: continue
                        parseMcpServerState(item)?.let { result.add(it) }
                    }
                } else if (statesObj is JSONObject) {
                    val keys = statesObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = statesObj.optJSONObject(key) ?: continue
                        parseMcpServerState(item, fallbackName = key)?.let { result.add(it) }
                    }
                }
            }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "getMcpServerStates error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseMcpServerState(item: JSONObject, fallbackName: String = ""): McpServerState? {
        val specObj = item.optJSONObject("spec")
        val name = specObj?.optString("serverName")?.ifBlank { null }
            ?: item.optString("serverName").ifBlank { null }
            ?: fallbackName.ifBlank { "mcp_server" }

        val command = specObj?.optString("command", "") ?: ""
        val argsList = mutableListOf<String>()
        specObj?.optJSONArray("args")?.let { arr ->
            for (j in 0 until arr.length()) {
                argsList.add(arr.optString(j))
            }
        }
        val envMap = mutableMapOf<String, String>()
        specObj?.optJSONObject("env")?.let { envObj ->
            val envKeys = envObj.keys()
            while (envKeys.hasNext()) {
                val k = envKeys.next()
                envMap[k] = envObj.optString(k, "")
            }
        }
        val serverUrl = specObj?.optString("serverUrl", "") ?: ""
        val headersMap = mutableMapOf<String, String>()
        specObj?.optJSONObject("headers")?.let { hObj ->
            val hKeys = hObj.keys()
            while (hKeys.hasNext()) {
                val k = hKeys.next()
                headersMap[k] = hObj.optString(k, "")
            }
        }
        val disabled = specObj?.optBoolean("disabled", false) ?: false
        val cwd = specObj?.optString("cwd", "") ?: ""

        val spec = McpServerSpec(
            serverName = name,
            command = command,
            args = argsList,
            env = envMap,
            serverUrl = serverUrl,
            headers = headersMap,
            disabled = disabled,
            cwd = cwd
        )

        val status = item.optString("status", "MCP_SERVER_STATUS_UNKNOWN")
        val error = item.optString("error", "").ifBlank { null }
        val instructions = item.optString("instructions", "").ifBlank { null }

        val toolsList = mutableListOf<McpToolInfo>()
        item.optJSONArray("tools")?.let { toolsArr ->
            for (t in 0 until toolsArr.length()) {
                val tObj = toolsArr.optJSONObject(t) ?: continue
                val tName = tObj.optString("name", "")
                val tDesc = tObj.optString("description", "")
                val tSchema = tObj.optString("jsonSchemaString", "").ifBlank {
                    tObj.opt("inputSchema")?.toString() ?: ""
                }
                if (tName.isNotBlank()) {
                    toolsList.add(McpToolInfo(name = tName, description = tDesc, inputSchema = tSchema))
                }
            }
        }

        return McpServerState(
            name = name,
            spec = spec,
            status = status,
            error = error,
            tools = toolsList,
            instructions = instructions,
            isEnabled = !disabled
        )
    }

    suspend fun refreshMcpServers(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        val res = grpcClient.callUnary("RefreshMcpServers", "{}", hubUrl)
        if (res.isSuccess) Result.success(Unit)
        else Result.failure(res.exceptionOrNull() ?: Exception("RefreshMcpServers failed"))
    }

    suspend fun toggleMcpServer(serverName: String, enabled: Boolean, hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("serverName", serverName)
            put("enabled", enabled)
        }.toString()
        val res = grpcClient.callUnary("ToggleMcpServer", payload, hubUrl)
        if (res.isSuccess) Result.success(Unit)
        else Result.failure(res.exceptionOrNull() ?: Exception("ToggleMcpServer failed"))
    }
}

