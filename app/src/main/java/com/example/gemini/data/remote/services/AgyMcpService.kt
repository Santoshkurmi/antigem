package com.example.gemini.data.remote.services

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.domain.model.McpServerSpec
import com.example.gemini.domain.model.McpServerState
import com.example.gemini.domain.model.McpToolInfo
import exa.language_server_pb.GetMcpServerStatesRequest
import exa.language_server_pb.RefreshMcpServersRequest
import exa.language_server_pb.ToggleMcpServerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated RPC service for Model Context Protocol (MCP) server states, refreshing, and toggling.
 * Uses typed Square Wire AgyLanguageService gRPC client.
 */
class AgyMcpService {
    companion object {
        private const val TAG = "AgyMcpService"
        val instance by lazy { AgyMcpService() }
    }

    suspend fun getMcpServerStates(hubUrl: String = AuthPreferences.currentHubUrl): Result<List<McpServerState>> = withContext(Dispatchers.IO) {
        val req = GetMcpServerStatesRequest()
        AgyLanguageService.GetMcpServerStates().executeSafely(req).map { res ->
            res.states.map { state ->
                val spec = state.spec
                val name = spec?.server_name?.takeIf { it.isNotBlank() } ?: "mcp_server"
                val envMap = spec?.env ?: emptyMap()
                val headersMap = spec?.headers ?: emptyMap()
                val disabled = spec?.disabled ?: false

                val domainSpec = McpServerSpec(
                    serverName = name,
                    command = spec?.command ?: "",
                    args = spec?.args ?: emptyList(),
                    env = envMap,
                    serverUrl = spec?.server_url ?: "",
                    headers = headersMap,
                    disabled = disabled,
                    cwd = spec?.cwd ?: ""
                )

                val toolsList = state.tools.map { t ->
                    McpToolInfo(
                        name = t.name,
                        description = t.description,
                        inputSchema = t.json_schema_string
                    )
                }

                McpServerState(
                    name = name,
                    spec = domainSpec,
                    status = state.status.name,
                    error = state.error.takeIf { it.isNotBlank() },
                    tools = toolsList,
                    instructions = state.instructions.takeIf { it.isNotBlank() },
                    isEnabled = !disabled
                )
            }
        }
    }

    suspend fun refreshMcpServers(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        val req = RefreshMcpServersRequest()
        AgyLanguageService.RefreshMcpServers().executeSafely(req).map { }
    }

    suspend fun toggleMcpServer(serverName: String, enabled: Boolean, hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        val req = ToggleMcpServerRequest(
            server_name = serverName,
            enabled = enabled
        )
        AgyLanguageService.ToggleMcpServer().executeSafely(req).map { }
    }
}
