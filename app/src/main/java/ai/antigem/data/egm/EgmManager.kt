package ai.antigem.data.egm

import android.content.Context
import android.util.Log
import ai.antigem.BuildConfig
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.remote.SlashCommandItem
import ai.antigem.ui.browser.PluginVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Settings → Agents → EGM: Google's Elephant-Goldfish Model (github.com/google/elephant-goldfish-model, Apache-2.0).
 * The agent (the Elephant) plans a design with the user and writes it down; fresh reviewers (Goldfish) that know
 * nothing of the conversation try to break it; code is written only after the user approves the design.
 *
 * Nothing of EGM ships in the APK: Install downloads EGM's own release ZIP, unchanged (or, if the user picks it, the
 * main branch at one commit, cut down to what a release holds), into ~/.antigem/egm, where every agent on this phone
 * can read it. The app adds three slash commands (/egm, /egm-lite, /egm-review) that
 * [PromptExpansion][ai.antigem.data.remote.PromptExpansion] turns into instructions when a message is sent:
 *
 * - In a normal chat the agent is the Elephant and runs the Goldfish with its own subagent tool.
 * - In a Jarvis chat Jarvis manages the run and starts every Goldfish as a separate task chat. The Elephant is a
 *   planner task chat (or Jarvis itself). The planner writes each reviewer's prompt to a file and asks Jarvis for the
 *   reviewers; Jarvis starts them with a fixed goal and adds nothing of its own, so they stay fresh.
 *
 * The rules for the planner and the reviewers are files the app writes into ~/.antigem/egm-antigem from the settings
 * here, so Jarvis passes only short goals that point at them.
 */
object EgmManager {
    private const val TAG = "EgmManager"
    private const val PREFS = "egm_settings"
    private const val KEY_VERSION = "installed_version"
    private const val KEY_SOURCE = "installed_source"
    private const val KEY_COMMIT = "installed_commit"

    const val REPO = "google/elephant-goldfish-model"
    const val HOMEPAGE = "https://github.com/$REPO"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MAIN_COMMIT_URL = "https://api.github.com/repos/$REPO/commits/main"
    /** EGM's runtime ZIP (skills, personas, standards, docs) without a host's plugin folder that the app does not use. */
    private const val ASSET_PREFIX = "egm-openai-"
    /**
     * What a download must hold to be EGM (the skills read the personas and standards while they run). Not egm-lite:
     * releases up to 0.1.1 have none ([State.hasLite] turns /egm-lite on when the installed copy has it).
     */
    private val REQUIRED = listOf(
        "skills/egm-brainstorm/SKILL.md", "skills/egm-draft/SKILL.md", "skills/egm-review/SKILL.md",
        "agents/comprehension.md", "resources/document_standards"
    )
    private const val LITE_SKILL = "skills/egm-lite/SKILL.md"
    /** What EGM's release ZIPs hold; a main-branch download (the whole repository) is cut down to these. */
    private val RUNTIME = setOf("skills", "agents", "resources", "docs", "assets", "plugin.json", "LICENSE", "README.md")

    /** EGM's eight Goldfish reviewer personas (agents/[file]); the synthesizer and the bootstrap orchestrator are left out. */
    enum class Persona(val file: String, val label: String, val description: String) {
        ZOOM_OUT("zoom_out.md", "Zoom out", "Skeptical architect: a simpler design, no extras, reuse of existing code"),
        INVENTIVE("inventive_catalyst.md", "Inventive catalyst", "Looks for a different approach that removes the core trade-off"),
        COMPREHENSION("comprehension.md", "Comprehension", "Naive reader: explains the design back, flags undefined terms and gaps"),
        DESIGN("design_critic.md", "Design critic", "Hostile critic: architecture, failure cases, scaling and cost"),
        STRUCTURAL("structural.md", "Structure", "The document's layout: sections, headings, missing parts"),
        STYLE("style.md", "Style", "Copy editor: clear, precise, no filler"),
        READINESS("implementation_readiness.md", "Implementation readiness", "Tech lead: can the plan be built step by step without guessing"),
        CODEBASE("codebase_details.md", "Codebase details", "Checks the plan against the real code: files, APIs, names");

        /** The persona's name in EGM's skills (egm-design, egm-zoom-out, …). */
        val egmName: String get() = "egm-" + if (this == DESIGN) "design" else file.removeSuffix(".md").replace('_', '-')
    }

    enum class Preset(val label: String, val reviewers: Set<Persona>) {
        QUICK("Quick · 2", setOf(Persona.COMPREHENSION, Persona.DESIGN)),
        STANDARD("Standard · 3", setOf(Persona.COMPREHENSION, Persona.DESIGN, Persona.READINESS)),
        FULL("Full · 8", Persona.entries.toSet())
    }

    /** Who is the Elephant when /egm runs in a Jarvis chat. */
    enum class Elephant(val label: String, val description: String) {
        PLANNER_CHAT("A separate planner chat", "Jarvis starts a planner chat that brainstorms, writes and fixes the design; Jarvis relays and runs the reviewers. Keeps Jarvis' own chat light."),
        JARVIS("Jarvis itself", "Jarvis brainstorms and writes the design in its own chat and runs the reviewers. One chat less, but Jarvis' context fills up.")
    }

    /** Where Install gets EGM from. */
    enum class Source(val label: String, val description: String) {
        RELEASE("Newest release", "EGM's published release, checked against its checksum. The stable choice."),
        MAIN("Development version (main)", "EGM's main branch as it is now, with skills not released yet (egm-lite, as of release 0.1.1). It changes almost daily and has no checksum to check it against.")
    }

    enum class Command(val token: String, val description: String, val argumentHint: String) {
        FULL("egm", "EGM: brainstorm → design doc → Goldfish review → your approval → code", "task"),
        LITE("egm-lite", "EGM lite: checklist → one Goldfish review → code → diff review", "task"),
        REVIEW("egm-review", "EGM: Goldfish review of an existing design document", "design doc path")
    }

    data class Settings(
        /** The slash commands work (EGM stays installed when off). */
        val enabled: Boolean = true,
        val reviewers: Set<Persona> = Preset.STANDARD.reviewers,
        /** egm-review stops after each stage for the user (else it runs all stages, then reports once). */
        val checkpoints: Boolean = false,
        /** How often a review stage may run again after the document was fixed. */
        val rounds: Int = 1,
        /** Goldfish reviewers running at the same time. */
        val parallel: Int = 2,
        /** Where design documents and reports go, relative to the project (one folder per topic inside). */
        val artifactDir: String = DEFAULT_ARTIFACT_DIR,
        val elephant: Elephant = Elephant.PLANNER_CHAT,
        /** What Install and Update download. */
        val source: Source = Source.RELEASE,
        val chatTemplate: String = DEFAULT_CHAT_TEMPLATE,
        val jarvisTemplate: String = DEFAULT_JARVIS_TEMPLATE
    )

    data class State(
        /** The installed EGM version (its plugin.json), null when not installed. */
        val version: String? = null,
        /** Where the installed copy came from. */
        val installedSource: Source? = null,
        /** The main-branch commit installed (7 characters), for [Source.MAIN]. */
        val installedCommit: String? = null,
        /** The newest release seen by the last check. */
        val latest: String? = null,
        /** The main branch's newest commit seen by the last check (7 characters). */
        val latestCommit: String? = null,
        /** The installed copy has egm-lite (EGM added it after release 0.1.1). */
        val hasLite: Boolean = false,
        val task: String? = null,
        val progress: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val log: List<String> = emptyList()
    ) {
        val busy: Boolean get() = task != null
        val hasUpdate: Boolean get() = if (installedSource == Source.MAIN) {
            installedCommit != null && latestCommit != null && latestCommit != installedCommit
        } else {
            version != null && latest != null && (PluginVersions.compare(latest, version) ?: 0) > 0
        }
        /** What is installed, for the page: "v0.1.1", or "main 70406c4" for the development version. */
        val label: String? get() = version?.let { v -> if (installedSource == Source.MAIN) "main ${installedCommit ?: v}" else "v$v" }
    }

    const val DEFAULT_ARTIFACT_DIR = ".egm"
    const val MAX_ROUNDS = 3
    const val MAX_PARALLEL = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private lateinit var appContext: Context
    private var job: Job? = null

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- paths ----------------

    private val home get() = LocalEnvironmentManager.getHomeDir(appContext)
    /** EGM's own files, exactly as released. */
    val rootDir: File get() = File(home, ".antigem/egm")
    /** The app's rules for the planner and the reviewers of a Jarvis run (rewritten from the settings). */
    val adapterDir: File get() = File(home, ".antigem/egm-antigem")
    private val plannerFile get() = File(adapterDir, "planner.md")
    private val goldfishFile get() = File(adapterDir, "goldfish.md")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _settings.value = load()
        _state.update { it.copy(version = prefs.getString(KEY_VERSION, null), installedSource = installedSourcePref(), installedCommit = prefs.getString(KEY_COMMIT, null)) }
        refresh()
    }

    fun clearMessage() = _state.update { it.copy(message = null, isError = false) }

    private fun report(message: String, isError: Boolean = false) = _state.update { it.copy(message = message, isError = isError) }

    private fun progress(text: String) = _state.update { it.copy(progress = text) }

    private fun appendLog(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(200)) }

    // ---------------- install, update, remove ----------------

    /** Re-reads what is installed (the folder may have been removed in the terminal) and rewrites the rule files. */
    fun refresh() = scope.launch {
        if (_state.value.busy) return@launch
        val version = if (File(rootDir, "skills").isDirectory) readVersion() ?: prefs.getString(KEY_VERSION, null) ?: "?" else null
        if (version == null) prefs.edit().remove(KEY_VERSION).remove(KEY_SOURCE).remove(KEY_COMMIT).apply()
        else prefs.edit().putString(KEY_VERSION, version).apply()
        _state.update {
            it.copy(
                version = version,
                installedSource = installedSourcePref(),
                installedCommit = if (version == null) null else prefs.getString(KEY_COMMIT, null),
                hasLite = version != null && hasLiteSkill()
            )
        }
        if (version != null) runCatching { writeAdapterFiles() }.onFailure { Log.w(TAG, "rule files: ${it.message}") }
    }

    /** Looks up the newest version of the chosen source (shown as an update on the page). */
    fun checkForUpdate() = scope.launch {
        when (_settings.value.source) {
            Source.RELEASE -> runCatching { latestRelease() }
                .onSuccess { r -> _state.update { it.copy(latest = r.version) } }
                .onFailure { Log.w(TAG, "update check: ${it.message}") }
            Source.MAIN -> runCatching { latestMainCommit() }
                .onSuccess { sha -> _state.update { it.copy(latestCommit = sha.take(7)) } }
                .onFailure { Log.w(TAG, "update check: ${it.message}") }
        }
    }

    /**
     * Installs EGM from the chosen source ([Settings.source]), or replaces the installed copy with the newest one of
     * that source (Update, or a switch between release and main).
     */
    fun install() = runTask("install") {
        val source = _settings.value.source
        val work = File(home, ".antigem/egm-download")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val zip = File(work, "egm.zip")
            // (release version, main commit): one of them
            val got: Pair<String?, String?> = when (source) {
                Source.RELEASE -> {
                    progress("Looking up the newest EGM release…")
                    val release = latestRelease()
                    appendLog("Release ${release.tag}: ${release.zipName.ifBlank { "source archive" }}")
                    progress("Downloading EGM ${release.version}…")
                    download(release.zipUrl, zip)
                    appendLog("✓ Downloaded ${zip.length() / 1024} KB")
                    release.sumsUrl?.let { url ->
                        val expected = httpGet(url).lineSequence()
                            .map { it.trim().split(Regex("\\s+")) }
                            .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == release.zipName }
                            ?.first()
                        if (expected == null) {
                            appendLog("! The release lists no checksum for ${release.zipName}; not checked")
                        } else {
                            if (!sha256(zip).equals(expected, ignoreCase = true)) {
                                throw IllegalStateException("The download does not match the release checksum. Try again.")
                            }
                            appendLog("✓ Checksum matches")
                        }
                    }
                    release.version to null
                }
                Source.MAIN -> {
                    progress("Looking up EGM's main branch…")
                    val sha = latestMainCommit()
                    appendLog("Main branch at ${sha.take(7)} (development version: unreleased, no checksum)")
                    progress("Downloading EGM main ${sha.take(7)}…")
                    // That exact commit, so what is installed is what was looked up
                    download("https://codeload.github.com/$REPO/zip/$sha", zip)
                    appendLog("✓ Downloaded ${zip.length() / 1024} KB")
                    null to sha.take(7)
                }
            }
            val released = got.first
            val commit = got.second
            progress("Unpacking…")
            val staging = File(work, "unpacked")
            staging.mkdirs()
            unzip(zip, staging)
            val bundle = bundleRoot(staging) ?: throw IllegalStateException("The download has no EGM skills folder.")
            val missing = REQUIRED.filterNot { File(bundle, it).exists() }
            if (missing.isNotEmpty()) throw IllegalStateException("The download is missing ${missing.joinToString()}.")
            // The main branch comes as the whole repository (tests, site, CI…): keep what a release ZIP holds
            if (source == Source.MAIN) bundle.listFiles()?.filter { it.name !in RUNTIME }?.forEach { it.deleteRecursively() }
            // The old copy goes as a whole: files a release dropped must not stay behind (EGM's own advice)
            rootDir.deleteRecursively()
            rootDir.parentFile?.mkdirs()
            if (!bundle.renameTo(rootDir)) bundle.copyRecursively(rootDir, overwrite = true)
            val version = readVersion() ?: released ?: "?"
            val edit = prefs.edit().putString(KEY_VERSION, version).putString(KEY_SOURCE, source.name)
            if (commit != null) edit.putString(KEY_COMMIT, commit) else edit.remove(KEY_COMMIT)
            edit.apply()
            _state.update {
                it.copy(
                    version = version, installedSource = source, installedCommit = commit,
                    latest = released ?: it.latest, latestCommit = commit ?: it.latestCommit, hasLite = hasLiteSkill()
                )
            }
            writeAdapterFiles()
            val label = _state.value.label ?: version
            appendLog("✓ EGM $label in ${rootDir.absolutePath}")
            if (!_state.value.hasLite) appendLog("! EGM $label has no egm-lite: /egm-lite stays off (the development version has it)")
            val commands = Command.entries.filter { available(it) }.joinToString { "/${it.token}" }
            report("EGM $label installed. Type $commands in any chat.")
        } finally {
            work.deleteRecursively()
        }
    }

    /** Removes EGM and the rule files; design documents in projects stay. */
    fun uninstall() = runTask("remove") {
        progress("Removing EGM…")
        rootDir.deleteRecursively()
        adapterDir.deleteRecursively()
        prefs.edit().remove(KEY_VERSION).remove(KEY_SOURCE).remove(KEY_COMMIT).apply()
        _state.update { it.copy(version = null, installedSource = null, installedCommit = null, hasLite = false) }
        appendLog("✓ Removed ${rootDir.absolutePath}")
        report("EGM removed. Its slash commands are gone; design documents in your projects stay.")
    }

    private fun runTask(task: String, block: suspend () -> Unit) {
        if (job?.isActive == true) {
            report("Something is already running. Wait for it to finish.", isError = true)
            return
        }
        job = scope.launch {
            _state.update { it.copy(task = task, log = emptyList(), progress = null, message = null, isError = false) }
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$task failed", e)
                appendLog("✗ ${e.message ?: e.javaClass.simpleName}")
                report(e.message ?: "Failed", isError = true)
            } finally {
                _state.update { it.copy(task = null, progress = null) }
            }
        }
    }

    private data class Release(val tag: String, val version: String, val zipUrl: String, val zipName: String, val sumsUrl: String?)

    private suspend fun latestRelease(): Release {
        val json = JSONObject(httpGet(LATEST_URL))
        val tag = json.optString("tag_name").ifBlank { throw IllegalStateException("The newest EGM release has no version tag.") }
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var zipUrl: String? = null
        var zipName = ""
        var sumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue
            if (name.startsWith(ASSET_PREFIX) && name.endsWith(".zip")) { zipUrl = url; zipName = name }
            if (name == "SHA256SUMS") sumsUrl = url
        }
        // A release without the runtime ZIP: the tag's source archive (one top folder, plus files EGM does not need)
        val url = zipUrl ?: json.optString("zipball_url").ifBlank { null }
            ?: throw IllegalStateException("The release $tag has nothing to download.")
        return Release(tag, tag.removePrefix("v"), url, zipName, if (zipUrl != null) sumsUrl else null)
    }

    private fun hasLiteSkill(): Boolean = File(rootDir, LITE_SKILL).isFile

    /** Where the installed copy came from; a copy installed before this was recorded is a release. */
    private fun installedSourcePref(): Source? =
        if (prefs.getString(KEY_VERSION, null) == null) null
        else prefs.getString(KEY_SOURCE, null)?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: Source.RELEASE

    /** The main branch's newest commit (the full SHA). */
    private suspend fun latestMainCommit(): String {
        val sha = httpGet(MAIN_COMMIT_URL, accept = "application/vnd.github.sha").trim()
        if (!Regex("[0-9a-f]{40}").matches(sha)) throw IllegalStateException("Could not read EGM's main branch from GitHub.")
        return sha
    }

    private fun readVersion(): String? = runCatching {
        JSONObject(File(rootDir, "plugin.json").readText()).optString("version").ifBlank { null }
    }.getOrNull()

    /** The folder of [dir] that holds EGM: [dir] itself, or its single top folder (a source archive). */
    private fun bundleRoot(dir: File): File? {
        if (File(dir, "skills").isDirectory) return dir
        val only = dir.listFiles()?.singleOrNull { it.isDirectory } ?: return null
        return only.takeIf { File(it, "skills").isDirectory }
    }

    /** Unpacks [zip] into [target]; an entry that would land outside it stops the install. */
    private fun unzip(zip: File, target: File) {
        val base = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(base, entry.name).canonicalFile
                if (out.path != base.path && !out.path.startsWith(base.path + File.separator)) {
                    throw IllegalStateException("Unsafe path in the download: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun download(url: String, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "AntiGem/${BuildConfig.VERSION_NAME}").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Download failed (HTTP ${response.code}): $url")
            val body = response.body ?: throw IllegalStateException("Empty response: $url")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress("Downloading… ${done * 100 / total}%")
                    }
                }
            }
        }
    }

    private suspend fun httpGet(url: String, accept: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", "AntiGem/${BuildConfig.VERSION_NAME}")
        if (accept != null) builder.header("Accept", accept)
        val request = builder.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 403) throw IllegalStateException("GitHub refused the request (HTTP 403, maybe its hourly limit). Try again later.")
            if (!response.isSuccessful) throw IllegalStateException("Download failed (HTTP ${response.code}): $url")
            response.body?.string() ?: throw IllegalStateException("Empty response: $url")
        }
    }

    // ---------------- settings ----------------

    fun update(transform: (Settings) -> Settings) {
        if (!::appContext.isInitialized) return
        val next = normalized(transform(_settings.value))
        _settings.value = next
        save(next)
        if (_state.value.version != null) scope.launch { runCatching { writeAdapterFiles() } }
    }

    private fun normalized(s: Settings) = s.copy(
        reviewers = s.reviewers.ifEmpty { Preset.STANDARD.reviewers },
        rounds = s.rounds.coerceIn(0, MAX_ROUNDS),
        parallel = s.parallel.coerceIn(1, MAX_PARALLEL),
        artifactDir = s.artifactDir.trim().trimEnd('/').ifBlank { DEFAULT_ARTIFACT_DIR },
        chatTemplate = s.chatTemplate.ifBlank { DEFAULT_CHAT_TEMPLATE },
        jarvisTemplate = s.jarvisTemplate.ifBlank { DEFAULT_JARVIS_TEMPLATE }
    )

    private fun load(): Settings {
        val p = prefs
        val d = Settings()
        val reviewers = p.getStringSet("reviewers", null)
            ?.mapNotNull { name -> runCatching { Persona.valueOf(name) }.getOrNull() }?.toSet()
        return normalized(
            Settings(
                enabled = p.getBoolean("enabled", d.enabled),
                reviewers = reviewers ?: d.reviewers,
                checkpoints = p.getBoolean("checkpoints", d.checkpoints),
                rounds = p.getInt("rounds", d.rounds),
                parallel = p.getInt("parallel", d.parallel),
                artifactDir = p.getString("artifact_dir", null) ?: d.artifactDir,
                elephant = p.getString("elephant", null)?.let { runCatching { Elephant.valueOf(it) }.getOrNull() } ?: d.elephant,
                source = p.getString("source", null)?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: d.source,
                // A template the user never changed is not stored, so a newer app's default reaches them
                chatTemplate = p.getString("chat_template", null) ?: d.chatTemplate,
                jarvisTemplate = p.getString("jarvis_template", null) ?: d.jarvisTemplate
            )
        )
    }

    private fun save(s: Settings) {
        prefs.edit().apply {
            putBoolean("enabled", s.enabled)
            putStringSet("reviewers", s.reviewers.map { it.name }.toSet())
            putBoolean("checkpoints", s.checkpoints)
            putInt("rounds", s.rounds)
            putInt("parallel", s.parallel)
            putString("artifact_dir", s.artifactDir)
            putString("elephant", s.elephant.name)
            putString("source", s.source.name)
            if (s.chatTemplate == DEFAULT_CHAT_TEMPLATE) remove("chat_template") else putString("chat_template", s.chatTemplate)
            if (s.jarvisTemplate == DEFAULT_JARVIS_TEMPLATE) remove("jarvis_template") else putString("jarvis_template", s.jarvisTemplate)
        }.apply()
    }

    // ---------------- slash commands ----------------

    /** EGM is installed and its commands are on. */
    val isActive: Boolean get() = ::appContext.isInitialized && _settings.value.enabled && _state.value.version != null

    /** Whether the installed EGM has what [command] runs (egm-lite is missing from releases up to 0.1.1). */
    fun available(command: Command, s: State = _state.value): Boolean = command != Command.LITE || s.hasLite

    /** The EGM command [token] (without the slash) names, when EGM is active and has it. */
    fun command(token: String): Command? =
        if (!isActive) null else Command.entries.firstOrNull { it.token.equals(token, ignoreCase = true) && available(it) }

    /** The commands in the chat's `/` list. */
    fun slashItems(): List<SlashCommandItem> = if (!isActive) emptyList() else Command.entries.filter { available(it) }.map {
        SlashCommandItem(it.token, "/${it.token}", it.description, "custom", argumentHint = it.argumentHint)
    }

    /**
     * The instructions [command] sends, with [args] (the rest of the message: the task, or the document for a review).
     * [jarvis]: the message goes to a Jarvis chat, which manages the run with task chats instead of subagents.
     */
    fun expansion(command: Command, args: String, jarvis: Boolean): String {
        val s = _settings.value
        return if (jarvis) {
            listOf(setupBlock(s), jarvisRunBlock(command, args, s), s.jarvisTemplate.trim()).joinToString("\n\n")
        } else {
            listOf(setupBlock(s), chatTaskBlock(command, args), s.chatTemplate.trim()).joinToString("\n\n")
        }
    }

    private val root get() = rootDir.absolutePath

    private fun skill(name: String) = "$root/skills/$name/SKILL.md"

    /** The facts every EGM run needs, from the settings: where EGM is, which reviewers, the limits. */
    private fun setupBlock(s: Settings): String = buildString {
        val version = _state.value.label?.let { " $it" }.orEmpty()
        appendLine("EGM (Elephant-Goldfish Model$version, $HOMEPAGE) is installed by AntiGem. It makes you plan and defend a design before any code: the Elephant (the main agent, with the whole context) writes a design document, and fresh Goldfish reviewers that know nothing of the conversation try to break it.")
        appendLine("- EGM root: $root (skills/<name>/SKILL.md = the workflows, agents/<file>.md = the Goldfish reviewer personas, resources/document_standards/ = the review standards, docs/ = notes per agent).")
        appendLine("- EGM is not installed as a plugin of your agent here: its skills are not in your skill list and its personas are not registered subagent types. Read the files and follow them; never call a Skill tool for them. Where an EGM file says \${CLAUDE_PLUGIN_ROOT}, \"the plugin root\" or a path like agents/…, resources/… or docs/…, use the EGM root. A skill's references/ folder is next to its SKILL.md.")
        appendLine("- If the EGM root does not exist where you run (an agent on a PC or a server), stop and tell the user that EGM is installed on the phone only.")
        appendLine("- EGM artifact directory: ${s.artifactDir}/<short-topic-name>/ in the project folder (one folder per topic; create it). Drafts, checklists and every review report are files there, not long text in the chat.")
        val chosen = Persona.entries.filter { it in s.reviewers }
        if (chosen.size == Persona.entries.size) {
            appendLine("- Goldfish reviewers: all eight personas, as EGM's skills list them.")
        } else {
            appendLine("- Goldfish reviewers: only ${chosen.joinToString { "${it.egmName} (agents/${it.file})" }}. Wherever an EGM skill lists reviewers (the brainstorm lenses, the review stages, egm-review-loop), keep only these and skip the others; skip a review stage that has none of them and keep the order of the rest. The user chose this in Settings → Agents → EGM. egm-lite has its own single reviewer and is not affected.")
        }
        appendLine("- Do not use the egm-synthesizer persona: dispatch the reviewers of a stage directly and merge their findings yourself (EGM's direct-dispatch fallback).")
        if (s.checkpoints) {
            appendLine("- Review mode: for egm-review use Step = interactive (check in with the user after each stage); do not ask the preset question.")
        } else {
            appendLine("- Review mode: for egm-review use Step = uninterrupted (run the stages without stopping, then write consolidated_review_report.md); do not ask the preset question.")
        }
        appendLine("- Re-reviews: after the document is fixed, run a review stage again at most ${s.rounds} time(s); then record what is left as accepted trade-offs.")
        appendLine("- At most ${s.parallel} Goldfish reviewer(s) at the same time (this runs on a phone).")
        append("- No project code is written or changed before the user explicitly approves the design (in egm-lite: before the user says exec or Proceed).")
    }

    /** What the user asked for, in a normal chat. */
    private fun chatTaskBlock(command: Command, args: String): String = when (command) {
        Command.FULL -> buildString {
            appendLine("The user started the full EGM flow.")
            appendLine(if (args.isBlank()) "Task: none given yet. Ask what they want to build, then start." else "Task: $args")
            appendLine("Phases, in order:")
            appendLine("1. Brainstorm: follow ${skill("egm-brainstorm")} with the user.")
            appendLine("2. Draft: follow ${skill("egm-draft")} to write the design document.")
            appendLine("3. Review: follow ${skill("egm-review")} on that document; fix or contest findings with ${skill("egm-resolve")}.")
            appendLine("4. Approval: give the user the final document's path and what is still open, and ask them to approve it.")
            append("5. Only after the approval: implement exactly the approved design, then tell the user what changed.")
        }
        Command.LITE -> buildString {
            appendLine("The user started EGM lite. Follow ${skill("egm-lite")} (its phase commands: checklist, goldfish / review, exec).")
            append(if (args.isBlank()) "Task: none given yet. Ask what they want to do." else "Task: $args")
        }
        Command.REVIEW -> buildString {
            appendLine("The user started an EGM review of a design document. Follow ${skill("egm-review")}.")
            appendLine(if (args.isBlank()) "TargetFile: none given. Ask the user for the document's path." else "TargetFile: $args (relative to the project folder unless absolute)")
            append("When the review is done, ask the user whether to fix the findings (${skill("egm-resolve")}) before anything else.")
        }
    }

    private fun phases(command: Command): String = when (command) {
        Command.FULL -> "Brainstorm, Draft, Review, Approval, Implement"
        Command.LITE -> "Checklist, Spec review, Approval (exec), Implement, Diff review"
        Command.REVIEW -> "Review, Fixes, Approval"
    }

    /** What the planner does, for its goal. */
    private fun plannerFlow(command: Command, args: String): String = when (command) {
        Command.FULL -> "Run the full EGM flow: ${skill("egm-brainstorm")}, then ${skill("egm-draft")}, then ${skill("egm-review")} (fix or contest findings with ${skill("egm-resolve")}). Then wait for the user's approval; implement only after APPROVED. " +
            (if (args.isBlank()) "The user gave no task yet: ask for it first." else "The user's task: $args")
        Command.LITE -> "Follow ${skill("egm-lite")}. " + (if (args.isBlank()) "The user gave no task yet: ask for it first." else "The user's task: $args")
        Command.REVIEW -> "Run ${skill("egm-review")}. " + (if (args.isBlank()) "The user gave no document yet: ask for its path first." else "TargetFile: $args")
    }

    /** The run's facts for Jarvis: the command, the Elephant, and the exact goals to start the planner and reviewers with. */
    private fun jarvisRunBlock(command: Command, args: String, s: Settings): String = buildString {
        appendLine("This is a Jarvis chat: you (Jarvis) manage this EGM run, and every Goldfish reviewer is a separate task chat that you start (not a subagent).")
        val what = when (command) {
            Command.FULL -> "the full EGM flow"
            Command.LITE -> "EGM lite"
            Command.REVIEW -> "an EGM review of a design document"
        }
        appendLine("- Command: $what. " + if (args.isBlank()) "The user gave no task or document yet: ask for it first." else "User's input: $args")
        appendLine("- Phases (the mission steps): ${phases(command)}.")
        appendLine("- Rules files the app wrote for this run: ${plannerFile.absolutePath} (the Elephant's rules) and ${goldfishFile.absolutePath} (the reviewers' rules).")
        when (s.elephant) {
            Elephant.PLANNER_CHAT -> {
                appendLine("- Elephant: a separate planner task chat. Start it with jarvis_task_start(title = \"EGM planner\", project = the user's project, goal = the text in <planner_goal>, verbatim).")
                appendLine("<planner_goal>")
                appendLine("You are the EGM Elephant (planner) of a run that Jarvis manages. First read ${plannerFile.absolutePath} and follow it. ${plannerFlow(command, args)}")
                appendLine("</planner_goal>")
            }
            Elephant.JARVIS -> {
                appendLine("- Elephant: you, in this chat. Read ${plannerFile.absolutePath} and follow it yourself, with this flow: ${plannerFlow(command, args)}")
                appendLine("  Where it says to reply GOLDFISH REQUEST, start those reviewers yourself (as below), save their reports, then go on. Talk to the user directly instead of through PHASE lines.")
            }
        }
        appendLine("- Goldfish goal: one reviewer task per prompt file, with jarvis_task_start(title = \"Goldfish <reviewer>\", project = the same project, goal = the text in <goldfish_goal> with PROMPT_FILE replaced by that file's absolute path). Nothing else goes into the goal.")
        appendLine("<goldfish_goal>")
        appendLine("Read ${goldfishFile.absolutePath} first, then do the review described in PROMPT_FILE.")
        appendLine("</goldfish_goal>")
        append("- At most ${s.parallel} reviewer task(s) running at the same time.")
    }

    /** Writes the planner's and the reviewers' rules for Jarvis runs (they follow the settings). */
    private fun writeAdapterFiles() {
        adapterDir.mkdirs()
        val s = _settings.value
        plannerFile.writeText(plannerRules(s))
        goldfishFile.writeText(GOLDFISH_RULES)
    }

    private fun plannerRules(s: Settings): String = """
# EGM in AntiGem: the Elephant (planner) of a run that Jarvis manages

Written by AntiGem from Settings → Agents → EGM and rewritten when those settings change.

${setupBlock(s)}

## You are the Elephant

You keep the project context, work with the user (through Jarvis) and write the documents. Jarvis manages the run and starts every Goldfish reviewer as a separate, fresh chat.

## Goldfish reviewers

- Do not spawn subagents yourself (no Agent, Task, spawn_agent, invoke_subagent or task tool), and never review your own document in place of a Goldfish.
- Whenever an EGM skill says to spawn a Goldfish or a subagent, write, for each reviewer, the exact prompt the skill says to pass into its own file: <EGM artifact directory>/goldfish/r<round>-<reviewer>.prompt.md, with absolute paths inside. Its first line: "Read $root/agents/<persona file> first and follow it." Then the prompt from the skill: the mode, the target document's absolute path and line range, the standard file, the context files. Nothing from your conversation: no summary, no opinions, no hints about what you think is wrong.
- egm-lite's Goldfish (the spec review, the diff review) works the same way: the prompt file holds the whole reviewer prompt the skill describes (the checklist, the change list, the rubric), without a persona line.
- Then end your turn with a reply whose first line is GOLDFISH REQUEST, followed by the prompt files, one absolute path per line, and stop. Jarvis answers GOLDFISH REPORTS with one report per prompt file (the same name ending in .report.md instead of .prompt.md). Read them all and continue the skill where you stopped: merge, deduplicate, write the stage report, resolve.

## Talking to the user

- The user does not watch this chat: Jarvis relays both ways. Ask one question at a time (with your question tool, or as plain text in your reply) and stop; the answer comes back word for word.
- Start every reply with a line PHASE: <phase> (Brainstorm, Draft, Review, Fixes, Waiting for approval, Implement, Diff review or Done) so Jarvis can show it.
- When the design is final, reply PHASE: Waiting for approval with the design document's path and what is still open, and stop. Change no project code until Jarvis sends APPROVED.
""".trimStart()

    private const val GOLDFISH_RULES = """# EGM Goldfish reviewer (AntiGem)

You are a Goldfish: a fresh reviewer in the Elephant-Goldfish Model (EGM). You know nothing about the conversation that produced the document, and that is the point.

- Your whole task is in the prompt file named in your message. Read it, then the persona file it names (if any), and follow them exactly. Read the files they name, and any project files you need to check facts.
- Read-only: do not create, edit, move or delete files, and run no builds, installs, git commands or anything else that changes the project or the system.
- Put your complete report in your reply; it is saved to a file for you. If a required file cannot be read, say the review is incomplete instead of giving a verdict.
- Do not ask questions (nobody will answer them): state your assumptions in the report.
- No small talk: findings only, in the format the persona or the prompt asks for.
"""

    const val DEFAULT_CHAT_TEMPLATE = """How to run EGM in this chat:
- You are the Elephant: you keep the project context, work with the user and write the documents.
- Goldfish reviewers run in your own fresh-context subagent tool (Claude Code: Agent; Codex: spawn_agent; Antigravity: invoke_subagent; OpenCode: task). For each reviewer spawn a general-purpose subagent and pass the prompt the skill gives, starting with "Read <EGM root>/agents/<persona file> first and follow it.", with absolute paths. Never pass a summary of this conversation, your opinions or hints about what you think is wrong.
- Tell every reviewer it is read-only (no file changes, no builds or commands that change anything) and must put its whole report in its reply. You write the report files.
- If you have no fresh-context subagent tool, stop before any Goldfish step and tell the user: a review in this same chat is not a Goldfish review. They can type the same command in a Jarvis chat, where Jarvis starts every reviewer as a separate chat.
- Ask the user one question at a time (your question tool if you have one, else plain chat), as EGM says."""

    const val DEFAULT_JARVIS_TEMPLATE = """How to manage this EGM run:
1. Mission: start with jarvis_mission_set (goal = the user's task, done_when = what ends this command, steps = the phases above, one per line). Then save what you will need later with jarvis_mission_update(notes = …): the planner task id, the EGM artifact folder, the review round, and the two goal texts. The mission is repeated to you with every message, so these survive a long run. Mark each step doing / done as the phases change: that is what the user sees in the Jarvis tab.
2. Elephant: when it is a planner chat, start it with the planner goal, verbatim. Relay its questions to the user and the user's answers back word for word (jarvis_task_reply, or jarvis_task_answer for its question cards); never add your own ideas about the design. Its replies start with PHASE: …; update the mission from them.
3. GOLDFISH REQUEST (a list of prompt files from the planner): start one task per file with the Goldfish goal, at most the number of reviewers at once given above; start the next as one finishes. Add nothing to the goal: anything more from you spoils the fresh review. Do not wait or poll: the app tells you when a task ends. Then read its whole reply with jarvis_read_chat(id, role = "assistant", limit = 1, max_chars = 30000), save it unchanged next to the prompt file (the same name ending in .report.md instead of .prompt.md) and close the task with jarvis_task_close. When all are saved, send the planner GOLDFISH REPORTS and the report paths with jarvis_task_reply. If you cannot write files, send the reports' full text instead. If a reviewer failed, changed files or asked something, tell the user.
4. Approval: when the planner says Waiting for approval, give the user the design document's path, what changed in review and what is still open, and ask: approve, change something, or one more review round (in a typed chat ask in your reply; by voice with jarvis_ask). Pass the answer on: APPROVED, or the user's changes word for word. Never let implementation start without the user's explicit approval.
5. End: when the planner reports Done, mark the mission done and tell the user where the design, the reports and the changes are."""
}
