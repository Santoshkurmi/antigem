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
    val path: String = ""
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

    // Fallback known skills
    val DEFAULT_SKILLS = listOf(
        SlashCommandItem("android-cli", "/android-cli", "Android CLI tools, emulator, SDK management, APK building", "skill", "android-cli-plugin"),
        SlashCommandItem("chrome-extensions", "/chrome-extensions", "Build and publish Chrome Extensions using Manifest V3 best practices", "skill", "modern-web-guidance-plugin"),
        SlashCommandItem("modern-web-guidance", "/modern-web-guidance", "Search tool for modern web development best practices (HTML/CSS/JS/React/Vue)", "skill", "modern-web-guidance-plugin"),
        SlashCommandItem("agy-customizations", "/agy-customizations", "Comprehensive guide for skills, rules, plugins, hooks, MCP servers", "skill"),
        SlashCommandItem("antigravity-guide", "/antigravity-guide", "Comprehensive guide and quick reference for Google Antigravity (AGY CLI, 2.0, IDE, SDK)", "skill"),
        SlashCommandItem("generative_ui", "/generative_ui", "Render rich interactive HTML widgets inline in chat or as artifacts", "skill"),
        SlashCommandItem("migrate-workflows", "/migrate-workflows", "Automatically migrate legacy workflows to modern skills", "skill"),
        SlashCommandItem("permissioned-github", "/permissioned-github", "GitHub integration, PRs, issues, repository operations", "skill")
    )

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

            // 1. Fetch live skills from AGY Hub gRPC API (GetAllSkills)
            try {
                val skillResult = AgyProjectService.instance.fetchAllSkills(hubUrl)
                if (skillResult.isSuccess) {
                    val hubSkills = skillResult.getOrThrow()
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

            // 2. Add default fallback skills if not already found
            for (s in DEFAULT_SKILLS) {
                if (seenNames.add(s.name.lowercase())) {
                    items.add(s)
                }
            }

            // 3. Add built-in slash commands
            for (cmd in BUILTIN_COMMANDS) {
                if (seenNames.add(cmd.name.lowercase())) {
                    items.add(cmd)
                }
            }

            items.sortBy { it.name.lowercase() }
            inMemoryCache = items
            items
        }
    }

    fun getCachedSync(): List<SlashCommandItem> {
        return inMemoryCache ?: (DEFAULT_SKILLS + BUILTIN_COMMANDS).sortedBy { it.name.lowercase() }
    }

    fun clearCache() {
        inMemoryCache = null
    }
}

