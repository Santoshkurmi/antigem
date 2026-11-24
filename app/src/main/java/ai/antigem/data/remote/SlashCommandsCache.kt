package ai.antigem.data.remote

import android.util.Log
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.services.AgyProjectService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ai.antigem.i18n.L

data class SlashCommandItem(
    val name: String,
    val command: String,
    val description: String,
    val type: String = "skill", // "skill" or "command"
    val pluginName: String? = null,
    val path: String = "",
    /** What the command expects after its name (Claude's `argumentHint`); blank = it runs as is. */
    val argumentHint: String = ""
)

object SlashCommandsCache {
    private const val TAG = "SlashCommandsCache"
    private val mutex = Mutex()
    private var inMemoryCache: List<SlashCommandItem>? = null

    // Standard built-in Antigravity slash commands
    val BUILTIN_COMMANDS get() = listOf(
        SlashCommandItem("goal", "/goal", L.remote.slashCommandsCache.runALongRunningTaskOvernight, "command"),
        SlashCommandItem("schedule", "/schedule", L.remote.slashCommandsCache.scheduleAOneShotTimerOr, "command"),
        SlashCommandItem("browser", "/browser", L.remote.slashCommandsCache.interactWithWebApplicationsOrSearch, "command"),
        SlashCommandItem("plan", "/plan", L.remote.slashCommandsCache.createAnImplementationPlanBeforeTaking, "command"),
        SlashCommandItem("grill-me", "/grill-me", L.remote.slashCommandsCache.interactiveInterviewToResolveDesign, "command"),
        SlashCommandItem("teamwork-preview", "/teamwork-preview", L.remote.slashCommandsCache.coordinateATeamOfAutonomousAgents, "command"),
        SlashCommandItem("learn", "/learn", L.remote.slashCommandsCache.persistCustomSetupAndLearnedBehaviors, "command"),
        SlashCommandItem("boost", "/boost", L.remote.slashCommandsCache.deepThinkingStrategicPlanningAnd, "command"),
        SlashCommandItem("terminal", "/terminal", L.remote.slashCommandsCache.openTerminalSessionOrExecuteLocal, "command"),
        SlashCommandItem("ide", "/ide", L.remote.slashCommandsCache.openTheCodeEditorIde, "command")
    )
    private val BUILTIN_COMMANDS_SORTED get() = BUILTIN_COMMANDS.sortedBy { it.name.lowercase() }

    private val _commands = kotlinx.coroutines.flow.MutableStateFlow(BUILTIN_COMMANDS_SORTED)
    /** Antigravity's "/" menu: its skills (from the hub) and built-in commands; updated by every fetch. */
    val commands: kotlinx.coroutines.flow.StateFlow<List<SlashCommandItem>> = _commands

    /**
     * Returns cached slash commands & skills immediately from RAM.
     * When cache is empty, queries the gRPC AGY Hub API (/exa.language_server_pb.LanguageServerService/GetAllSkills),
     * merges skills and built-in slash commands, and caches the result in RAM.
     */
    suspend fun getCommands(
        hubUrl: String = AuthPreferences.currentHubUrl,
        forceRefresh: Boolean = false
    ): List<SlashCommandItem> {
        // Fast-path: RAM Cache hit (0ms)
        if (!forceRefresh && inMemoryCache != null && inMemoryCache!!.isNotEmpty()) {
            return inMemoryCache!!
        }

        return mutex.withLock {
            if (!forceRefresh && inMemoryCache != null && inMemoryCache!!.isNotEmpty()) {
                return@withLock inMemoryCache!!
            }

            val items = mutableListOf<SlashCommandItem>()
            val seenNames = mutableSetOf<String>()
            var fetched = false

            // 1. Fetch live skills from AGY Hub gRPC API (GetAllSkills)
            try {
                val skillResult = AgyProjectService.instance.fetchAllSkills(hubUrl)
                if (skillResult.isSuccess) {
                    val hubSkills = skillResult.getOrThrow()
                    fetched = true
                    for (s in hubSkills) {
                        if (seenNames.add(s.name.lowercase())) {
                            val cleanDesc = s.description.trim().lines().firstOrNull { it.isNotBlank() } ?: s.description.trim()
                            items.add(
                                SlashCommandItem(
                                    name = s.name,
                                    command = "/${s.name}",
                                    description = cleanDesc,
                                    type = "skill",
                                    pluginName = s.pluginName,
                                    path = s.path
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GetAllSkills RPC failed: ${e.message}")
            }

            // 2. Add built-in slash commands (only skills the hub reports are listed: none are made up)
            for (cmd in BUILTIN_COMMANDS) {
                if (seenNames.add(cmd.name.lowercase())) {
                    items.add(cmd)
                }
            }

            items.sortBy { it.name.lowercase() }
            // a failed fetch is not kept, so the next one asks the hub again
            if (fetched) inMemoryCache = items
            _commands.value = items
            items
        }
    }

    /** Asks the hub again (skills or plugins were added or removed, or the "/" menu opened). */
    suspend fun refresh(hubUrl: String = AuthPreferences.currentHubUrl): List<SlashCommandItem> = getCommands(hubUrl, forceRefresh = true)

    fun getCachedSync(): List<SlashCommandItem> {
        return inMemoryCache ?: BUILTIN_COMMANDS_SORTED
    }

    fun clearCache() {
        inMemoryCache = null
    }
}

