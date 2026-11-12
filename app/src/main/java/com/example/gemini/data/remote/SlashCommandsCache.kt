package com.example.gemini.data.remote

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.services.AgyProjectService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val BUILTIN_COMMANDS = listOf(
        SlashCommandItem("goal", "/goal", "Run a long-running task overnight and be extra thorough until the goal is achieved", "command"),
        SlashCommandItem("schedule", "/schedule", "Schedule a one-shot timer or recurring cron job", "command"),
        SlashCommandItem("browser", "/browser", "Interact with web applications or search and browse the web", "command"),
        SlashCommandItem("plan", "/plan", "Create an implementation plan before taking action", "command"),
        SlashCommandItem("grill-me", "/grill-me", "Interactive interview to resolve design decisions and align on approach", "command"),
        SlashCommandItem("teamwork-preview", "/teamwork-preview", "Coordinate a team of autonomous agents working together", "command"),
        SlashCommandItem("learn", "/learn", "Persist custom setup and learned behaviors for future tasks", "command"),
        SlashCommandItem("boost", "/boost", "Deep thinking, strategic planning, and verification for complex tasks", "command"),
        SlashCommandItem("terminal", "/terminal", "Open terminal session or execute local shell commands", "command"),
        SlashCommandItem("ide", "/ide", "Open the code editor IDE", "command")
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

