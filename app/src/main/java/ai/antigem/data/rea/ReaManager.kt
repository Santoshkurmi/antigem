package ai.antigem.data.rea

import android.content.Context
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import ai.antigem.BuildConfig
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.StorageSpace
import ai.antigem.data.local.TermuxRelocation
import ai.antigem.data.local.chroot.ChrootInstall
import ai.antigem.data.local.chroot.ChrootSetup
import ai.antigem.ui.browser.BrowserExtensionManager
import ai.antigem.ui.browser.PluginRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

/**
 * Settings → REA: sets up REA (Reverse Engineer Anything, github.com/morluto/rea), an MCP server that lets agents
 * take apart APKs, Flutter and React Native apps and native code, in a proot-distro Ubuntu container of the
 * terminal. When AntiGem's Ubuntu chroot is the phone's runtime, REA installs into the chroot itself instead (the same
 * scripts, run there as root: see [ChrootSetup]), since the agents run there.
 *
 * Nothing of REA ships in the APK: the setup scripts live in the antigem_plugins repository
 * (rea/skills/rea/scripts) and are downloaded into $PREFIX/share/antigem-rea when the user asks for them. Every
 * install step runs `rea-setup.sh <step>`, the same script the REA skill plugin tells agents to run.
 *
 * Agents start the `rea-mcp` launcher, which shows only the tools of the chosen tool groups
 * (~/.config/antigem/rea/groups.json, edited on the Tool groups page).
 */
object ReaManager {
    private const val TAG = "ReaManager"
    private const val PREFS = "rea_settings"

    /** Where the setup files and the skill plugin come from (owner/repo@branch). */
    const val DEFAULT_SOURCE = "Santoshkurmi/antigem_plugins@main"
    private const val SCRIPTS_PATH = "rea/skills/rea/scripts"
    const val PLUGIN_ID = "rea"
    const val CONTAINER = "ubuntu"
    const val REA_HOMEPAGE = "https://github.com/morluto/rea"

    private const val TERMUX = "/data/data/${BuildConfig.APPLICATION_ID}/files"
    const val TERMUX_LAUNCHER = "$TERMUX/usr/bin/rea-mcp"
    /** rea-mcp in the Ubuntu chroot (the setup's launcher step writes it). */
    const val CHROOT_LAUNCHER = "/usr/local/bin/rea-mcp"
    /** The command agents start: rea-mcp of the runtime the agents run in (AntiGem's environment or the Ubuntu chroot). */
    val LAUNCHER: String get() = if (ChrootSetup.install() != null) CHROOT_LAUNCHER else TERMUX_LAUNCHER
    /** Where the setup files go in the Ubuntu chroot. */
    private val CHROOT_SHARE = ChrootSetup.shareDir("antigem-rea")
    // Ubuntu names the JDK folder after the CPU: -arm64 on phones, -amd64 in the x86_64 build
    private val JAVA_BIN = "usr/lib/jvm/java-21-openjdk-${if (BuildConfig.TARGET_ABI == "x86_64") "amd64" else "arm64"}/bin/java"
    private const val JADX_JAR = "root/tools/jadx-headless-mcp-0.7.1-all.jar"
    private const val GROUPS_ENV = "REA_GROUPS"
    private const val MODE_ENV = "REA_MODE"
    private const val DIRECT_GROUPS_ENV = "REA_DIRECT_GROUPS"
    private const val OUTPUT_SCHEMA_ENV = "REA_MCP_OUTPUT_SCHEMA_PROFILE"
    /** Context of on-demand mode (4 gateway tools + instructions) before tools.json measured it, in bytes. */
    private const val LAZY_BYTES_FALLBACK = 2800
    /** Full output schemas make REA's tool definitions about this much bigger (measured on REA 6.8.0). */
    const val FULL_SCHEMA_FACTOR = 12

    /**
     * One part of the setup, installed by `rea-setup.sh <step>`. [needs] are installed first when missing.
     * [size] is the approximate disk use; [inUbuntu] says where it goes (the container or Termux).
     */
    enum class Component(
        val step: String,
        private val termuxLabel: String,
        private val termuxDescription: String,
        val size: String,
        val required: Boolean,
        /** The wording when REA installs into the Ubuntu chroot (null: the same). */
        private val chrootLabel: String? = null,
        private val chrootDescription: String? = null
    ) {
        CONTAINER("container", "Ubuntu container", "proot-distro and an Ubuntu 24.04 container REA runs in. Other Linux tools you install can use it too (proot-distro login ubuntu).", "≈150 MB", true,
            "Ubuntu chroot", "This phone's Ubuntu chroot: REA installs right into it, nothing to add."),
        BASE("base", "Base tools", "curl, Python, git, unzip and procps in Ubuntu. Node, JADX, apktool and hermes-dec need them.", "≈60 MB", true),
        JAVA("java", "Java 21", "OpenJDK 21, headless (no desktop libraries). JADX and apktool run on it.", "≈290 MB", true),
        NODE("node", "Node.js 24", "Installed with nvm, not apt (Ubuntu's Node is too old and pulls many packages). REA runs on it.", "≈240 MB", true),
        REA("rea", "REA", "REA's command and MCP server (npm rea-agents). Install again to update it.", "≈60 MB", true),
        JADX("jadx", "JADX decompiler", "jadx-headless-mcp: Java/Kotlin source of an APK's classes and methods, manifest and references.", "≈45 MB", true),
        APKTOOL("apktool", "apktool", "The official apktool jar (latest): manifest, resources and strings.", "≈16 MB", true),
        LAUNCHER("launcher", "MCP launcher", "rea-mcp (the command agents start), rea-shell and the tool group filter.", "<1 MB", true),
        NATIVE("native", "Native code tools", "objdump and rizin for .so libraries (libapp.so, libflutter.so, JNI code).", "≈40 MB", false,
            chrootDescription = "objdump and rizin for .so libraries (libapp.so, libflutter.so, JNI code). Ubuntu has no rizin package, so it comes from the rizin project's own Ubuntu repository (added for you)."),
        ADB("adb", "adb", "adb in the terminal for REA's phone tools (installed apps, logcat, screenshots). Connect it in ADB Connect.", "≈10 MB", false,
            "adb and fastboot", "Ubuntu's adb and fastboot for REA's phone tools (installed apps, logcat, screenshots); no sudo needed. Connect adb in ADB Connect."),
        HERMES("hermes", "React Native (hermes-dec)", "Decompiles Hermes bytecode, the index.android.bundle of most React Native apps.", "≈40 MB", false),
        DEBUG("debug", "Debugger (gdb)", "GDB for debugging native programs in the container.", "≈70 MB", false),
        FIRMWARE("firmware", "Firmware (binwalk)", "binwalk to find and extract firmware images.", "≈30 MB", false);

        val label: String get() = chrootLabel?.takeIf { ChrootSetup.install() != null } ?: termuxLabel
        val description: String get() = chrootDescription?.takeIf { ChrootSetup.install() != null } ?: termuxDescription

        val needs: List<Component>
            get() = when (this) {
                CONTAINER, ADB -> emptyList()
                BASE, JAVA, LAUNCHER, NATIVE, DEBUG, FIRMWARE -> listOf(CONTAINER)
                NODE -> listOf(CONTAINER, BASE)
                REA -> listOf(CONTAINER, BASE, NODE)
                JADX, APKTOOL -> listOf(CONTAINER, BASE, JAVA)
                HERMES -> listOf(CONTAINER, BASE)
            }
    }

    /**
     * One REA tool, from tools.json (`rea-mcp --list-tools`). [bytes] is what its definition adds to an agent's
     * context; [changes] marks tools that change things (REA's destructive hint: installing apps, debuggers, Frida…).
     */
    data class Tool(val name: String, val title: String, val description: String, val required: List<String>, val bytes: Int, val changes: Boolean)

    /**
     * How an REA server offers its tools. [DIRECT]: every allowed tool is an MCP tool (each costs context on every
     * request). [ON_DEMAND]: four small gateway tools (rea_tools, rea_tool_info, rea_call, rea_call_change).
     */
    enum class Mode(val env: String) { ON_DEMAND("lazy"), DIRECT("direct") }

    data class Group(val id: String, val name: String, val description: String, val builtin: Boolean, val tools: List<String>)

    data class State(
        val ready: Boolean = false,
        val bootstrapInstalled: Boolean = false,
        /** REA lives in the Ubuntu chroot (the phone's runtime), not in a proot container of AntiGem's environment. */
        val chroot: Boolean = false,
        val source: String = DEFAULT_SOURCE,
        /** Version of the downloaded setup files, null when not downloaded. */
        val filesVersion: String? = null,
        /** Newer setup files seen in the source, or null. */
        val filesUpdate: String? = null,
        val installed: Set<Component> = emptySet(),
        val reaVersion: String? = null,
        val nodeVersion: String? = null,
        val containerName: String? = null,
        /** What is running (a [Component] step, "files", "batch", "doctor", "tools", "plugin", "uninstall", "remove-container"). */
        val task: String? = null,
        /** The component being installed right now (also during a batch). */
        val installing: Component? = null,
        val batch: List<Component> = emptyList(),
        val batchIndex: Int = 0,
        val progress: String? = null,
        val log: List<String> = emptyList(),
        val logTask: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val tools: List<Tool> = emptyList(),
        val toolsServer: String? = null,
        /** Bytes of REA's own server instructions (direct mode sends them to the agent). */
        val instructionsBytes: Int = 0,
        /** Bytes of on-demand mode: the gateway tools and their short instructions. */
        val lazyBytes: Int = 0,
        val groups: List<Group> = emptyList(),
        val defaultGroups: List<Group> = emptyList(),
        val defaultSelection: List<String> = emptyList(),
        val doctorLog: List<String> = emptyList(),
        val pluginInstalled: Boolean = false,
        val pluginVersion: String? = null,
        /** Website analysis on: the "web" tool group exists and agents are told to use REA on the browser's tabs. */
        val webEnabled: Boolean = false
    ) {
        val filesReady: Boolean get() = filesVersion != null
        val requiredDone: Boolean get() = Component.entries.filter { it.required }.all { it in installed }
        val usable: Boolean get() = Component.REA in installed && Component.LAUNCHER in installed && Component.CONTAINER in installed
        val busy: Boolean get() = task != null
        val toolsByName: Map<String, Tool> get() = tools.associateBy { it.name }

        /** Every tool name known: from REA's list, else from the groups. */
        val allToolNames: List<String>
            get() = (tools.map { it.name } + groups.flatMap { it.tools } + defaultGroups.flatMap { it.tools }).distinct().sorted()

        /** Tools in no group (new REA tools land here). */
        val ungrouped: List<String>
            get() {
                val grouped = groups.flatMap { it.tools }.toSet()
                return allToolNames.filter { it !in grouped }
            }

        /** The tool names the given group ids show; all tools for "all" or nothing selected. */
        fun toolsFor(groupIds: Collection<String>): Set<String> =
            if (groupIds.isEmpty() || "all" in groupIds) allToolNames.toSet()
            else groups.filter { it.id in groupIds }.flatMap { it.tools }.toSet()

        /** Rough tokens these tools' definitions add to every agent request (≈4 bytes per token); null before REA is installed. */
        fun tokensFor(groupIds: Collection<String>): Int? {
            if (tools.isEmpty()) return null
            val byName = toolsByName
            return toolsFor(groupIds).sumOf { byName[it]?.bytes ?: 0 } / 4
        }

        /**
         * Tokens a server costs on every request: in [Mode.DIRECT] its tools plus REA's instructions; in
         * [Mode.ON_DEMAND] the gateway tools plus the tools of [directGroups] (within the scope). Null when unknown.
         */
        fun serverTokens(mode: Mode, scope: Collection<String>, directGroups: Collection<String>, fullSchemas: Boolean): Int? {
            val factor = if (fullSchemas) FULL_SCHEMA_FACTOR else 1
            val byName = toolsByName
            return when (mode) {
                Mode.DIRECT -> {
                    if (tools.isEmpty()) return null
                    (toolsFor(scope).sumOf { byName[it]?.bytes ?: 0 } * factor + instructionsBytes) / 4
                }
                Mode.ON_DEMAND -> {
                    val shown = toolsFor(scope).intersect(if (directGroups.isEmpty()) emptySet() else toolsFor(directGroups))
                    if (shown.isNotEmpty() && tools.isEmpty()) return null
                    ((if (lazyBytes > 0) lazyBytes else LAZY_BYTES_FALLBACK) + shown.sumOf { byName[it]?.bytes ?: 0 } * factor) / 4
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- paths ----------------

    private val prefix get() = LocalEnvironmentManager.getPrefixDir(appContext)
    private val home get() = LocalEnvironmentManager.getHomeDir(appContext)
    private val shareDir get() = File(prefix, "share/antigem-rea")
    private val confDir get() = File(home, ".config/antigem/rea")
    private val groupsFile get() = File(confDir, "groups.json")
    private val toolsFile get() = File(confDir, "tools.json")
    private val pidFile get() = File(shareDir, ".task.pid")
    private fun rootfs(container: String = CONTAINER) = File(prefix, "var/lib/proot-distro/containers/$container/rootfs")
    val setupScriptPath: String get() = File(shareDir, "rea-setup.sh").absolutePath

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _state.update { it.copy(source = prefs.getString("source", null) ?: DEFAULT_SOURCE, webEnabled = prefs.getBoolean(WEB_PREF, false)) }
    }

    fun clearMessage() = _state.update { it.copy(message = null, isError = false) }

    private fun report(message: String, isError: Boolean = false) = _state.update { it.copy(message = message, isError = isError) }

    // ---------------- state ----------------

    /**
     * Re-reads what is installed, the setup files, tools and groups: only files, cheap enough to poll. In the Ubuntu
     * chroot what is installed comes from the setup script (a root call), at most every [CHROOT_STATUS_MS] unless [force].
     */
    fun refresh(force: Boolean = false) = scope.launch { refreshNow(force) }

    private const val CHROOT_STATUS_MS = 15_000L
    @Volatile private var chrootStatus: Map<String, String>? = null
    @Volatile private var chrootStatusFor: String? = null
    @Volatile private var chrootStatusAt = 0L

    /** The setup script's `status` in [install] (cached); null when the chroot can't be asked. */
    private suspend fun chrootStatus(install: ChrootInstall, force: Boolean): Map<String, String>? {
        val fresh = chrootStatusFor == install.path && System.currentTimeMillis() - chrootStatusAt < CHROOT_STATUS_MS
        if (!force && fresh) return chrootStatus
        var status = ChrootSetup.status(appContext, install, CHROOT_SHARE, "rea-setup.sh")
        // the setup files were downloaded but never copied into this chroot (e.g. it became the runtime later)
        if (status == null && File(shareDir, "rea-setup.sh").exists() && copyFilesToChroot(install)) {
            status = ChrootSetup.status(appContext, install, CHROOT_SHARE, "rea-setup.sh")
        }
        chrootStatus = status
        chrootStatusFor = install.path
        chrootStatusAt = System.currentTimeMillis()
        return status
    }

    private suspend fun refreshNow(force: Boolean = false) {
        val ctx = appContext
        val chroot = ChrootSetup.install()
        if (chroot != null) {
            refreshChroot(chroot, force)
            return
        }
        val root = rootfs()
        val bin = File(prefix, "bin")
        val installed = buildSet {
            if (File(root, "usr/bin/apt-get").exists()) add(Component.CONTAINER)
            if (File(root, "usr/bin/curl").exists() && File(root, "usr/bin/python3").exists()) add(Component.BASE)
            if (File(root, JAVA_BIN).exists()) add(Component.JAVA)
            if (File(root, "root/tools/node/bin/node").exists()) add(Component.NODE)
            if (File(root, "root/tools/node/lib/node_modules/rea-agents/package.json").exists()) add(Component.REA)
            if (File(root, JADX_JAR).exists()) add(Component.JADX)
            if (File(root, "root/tools/apktool.jar").exists() && File(root, "usr/local/bin/apktool").exists()) add(Component.APKTOOL)
            if (File(bin, "rea-mcp").exists() && File(shareDir, "rea-filter.mjs").exists()) add(Component.LAUNCHER)
            if (File(root, "usr/bin/objdump").exists() && File(bin, "rizin").exists()) add(Component.NATIVE)
            if (File(bin, "adb").exists()) add(Component.ADB)
            if (File(root, "root/tools/hermes/bin/hbc-decompiler").exists()) add(Component.HERMES)
            if (File(root, "usr/bin/gdb").exists()) add(Component.DEBUG)
            if (File(root, "usr/bin/binwalk").exists()) add(Component.FIRMWARE)
        }
        val reaVersion = runCatching {
            JSONObject(File(root, "root/tools/node/lib/node_modules/rea-agents/package.json").readText()).optString("version").ifBlank { null }
        }.getOrNull()
        val nodeVersion = runCatching { File(root, "root/tools/node").canonicalFile.name }.getOrNull()?.takeIf { it.startsWith("v") }
        val filesVersion = runCatching { JSONObject(File(shareDir, "files.json").readText()).optString("version").ifBlank { null } }
            .getOrNull()?.takeIf { File(shareDir, "rea-setup.sh").exists() }
        val (defaults, selection) = readDefaultGroups()
        val catalog = readTools()
        var groups = readGroups(defaults)
        // Website analysis keeps its group (it comes back after "Reset all groups"; turning the setting off removes it)
        if (_state.value.webEnabled && groups.isNotEmpty() && groups.none { it.id == WEB_GROUP }) {
            groups = groups + webGroup(catalog.tools)
            runCatching { writeGroups(groups, removedBuiltins()) }
        }
        val plugin = BrowserExtensionManager.instance.extensions.firstOrNull { it.id == PLUGIN_ID }
        _state.update {
            it.copy(
                ready = true,
                bootstrapInstalled = LocalEnvironmentManager.isInstalled(ctx),
                chroot = false,
                filesVersion = filesVersion,
                filesUpdate = it.filesUpdate?.takeIf { v -> v != filesVersion },
                installed = installed,
                reaVersion = reaVersion,
                nodeVersion = nodeVersion,
                containerName = runCatching {
                    File(root, "etc/os-release").readLines().firstOrNull { l -> l.startsWith("PRETTY_NAME=") }
                        ?.substringAfter('=')?.trim('"')
                }.getOrNull(),
                tools = catalog.tools,
                toolsServer = catalog.server,
                instructionsBytes = catalog.instructionsBytes,
                lazyBytes = catalog.lazyBytes,
                groups = groups,
                defaultGroups = defaults,
                defaultSelection = selection,
                pluginInstalled = plugin != null,
                pluginVersion = plugin?.version
            )
        }
    }

    /** [refreshNow] for the Ubuntu chroot: what is installed from the setup script, the rest from the app's own files. */
    private suspend fun refreshChroot(install: ChrootInstall, force: Boolean) {
        val status = chrootStatus(install, force).orEmpty()
        val installed = buildSet {
            // the chroot is the container
            add(Component.CONTAINER)
            Component.entries.filter { status[it.step] == "yes" }.forEach { add(it) }
        }
        val filesVersion = runCatching { JSONObject(File(shareDir, "files.json").readText()).optString("version").ifBlank { null } }
            .getOrNull()?.takeIf { File(shareDir, "rea-setup.sh").exists() }
        val (defaults, selection) = readDefaultGroups()
        val catalog = readTools()
        var groups = readGroups(defaults)
        if (_state.value.webEnabled && groups.isNotEmpty() && groups.none { it.id == WEB_GROUP }) {
            groups = groups + webGroup(catalog.tools)
            runCatching { writeGroups(groups, removedBuiltins()) }
        }
        val plugin = BrowserExtensionManager.instance.extensions.firstOrNull { it.id == PLUGIN_ID }
        _state.update {
            it.copy(
                ready = true,
                bootstrapInstalled = true,
                chroot = true,
                filesVersion = filesVersion,
                filesUpdate = it.filesUpdate?.takeIf { v -> v != filesVersion },
                installed = installed,
                reaVersion = status["rea_version"]?.ifBlank { null },
                nodeVersion = status["node_version"]?.ifBlank { null },
                containerName = status["os"]?.ifBlank { null } ?: install.os,
                tools = catalog.tools,
                toolsServer = catalog.server,
                instructionsBytes = catalog.instructionsBytes,
                lazyBytes = catalog.lazyBytes,
                groups = groups,
                defaultGroups = defaults,
                defaultSelection = selection,
                pluginInstalled = plugin != null,
                pluginVersion = plugin?.version
            )
        }
    }

    /**
     * Whether the downloaded setup files know the Ubuntu chroot (1.1.0 and newer list rea-mcp-chroot). Older ones only know
     * Termux and would run their Termux steps inside the chroot.
     */
    private fun filesSupportChroot(): Boolean = runCatching {
        val arr = JSONObject(File(shareDir, "files.json").readText()).optJSONArray("files")
        (0 until (arr?.length() ?: 0)).any { arr!!.getString(it) == "rea-mcp-chroot" }
    }.getOrDefault(false)

    private fun chrootFilesError(): String {
        val v = runCatching { JSONObject(File(shareDir, "files.json").readText()).optString("version") }.getOrNull()?.ifBlank { null }
        return "These setup files (${v ?: "unknown version"}) are from before the Ubuntu chroot was supported. Get the setup files again " +
            "once their source has version 1.1.0 or newer."
    }

    /** The downloaded setup files, copied into the chroot's [CHROOT_SHARE] (they run there). */
    private suspend fun copyFilesToChroot(install: ChrootInstall): Boolean {
        if (!filesSupportChroot()) return false
        val names = runCatching {
            val arr = JSONObject(File(shareDir, "files.json").readText()).optJSONArray("files")
            (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
        }.getOrDefault(emptyList()).filter { Regex("[A-Za-z0-9._-]+").matches(it) } + "files.json"
        val r = ChrootSetup.copyIn(appContext, install, shareDir, names.distinct(), CHROOT_SHARE) { name ->
            !name.endsWith(".json") && !name.endsWith(".mjs")
        }
        if (!r.ok) Log.w(TAG, "copying the setup files into the chroot: ${r.message()}")
        return r.ok
    }

    /** The app's tool groups (its own copy is the one the pages edit) into the chroot user's ~/.config/antigem/rea. */
    private suspend fun pushGroupsToChroot(install: ChrootInstall) {
        val bytes = runCatching { groupsFile.readBytes() }.getOrNull() ?: return
        val r = ChrootSetup.asUser(appContext, install,
            "mkdir -p ~/.config/antigem/rea && cat > ~/.config/antigem/rea/groups.json.part && mv -f ~/.config/antigem/rea/groups.json.part ~/.config/antigem/rea/groups.json",
            stdin = bytes)
        if (!r.ok) Log.w(TAG, "copying the tool groups into the chroot: ${r.message()}")
    }

    /** REA's tool list, written in the chroot by the setup's tools step, into the app's copy (the pages read it). */
    private suspend fun pullToolsFromChroot(install: ChrootInstall) {
        val r = ChrootSetup.asUser(appContext, install, "cat ~/.config/antigem/rea/tools.json", timeoutMs = 60_000L)
        if (!r.ok || r.out.isEmpty()) return
        val text = r.out.joinToString("\n")
        if (runCatching { JSONObject(text) }.isFailure) return
        confDir.mkdirs()
        val partial = File(confDir, "tools.json.part")
        partial.writeText(text)
        if (!partial.renameTo(toolsFile)) { partial.copyTo(toolsFile, overwrite = true); partial.delete() }
    }

    // ---------------- setup files (downloaded, never bundled) ----------------

    /** The source as owner/repo@branch: what the user set, else [DEFAULT_SOURCE]. */
    fun setSource(source: String) {
        val value = source.trim().ifBlank { DEFAULT_SOURCE }
        val parsed = PluginRepositories.parse(value)
        val label = if (parsed.ref.isBlank()) "${parsed.slug}@main" else parsed.label
        prefs.edit().putString("source", label).apply()
        _state.update { it.copy(source = label, filesUpdate = null) }
    }

    private fun rawUrl(file: String): String {
        val repo = PluginRepositories.parse(_state.value.source)
        val ref = repo.ref.ifBlank { "main" }
        return "https://raw.githubusercontent.com/${repo.slug}/$ref/$SCRIPTS_PATH/$file"
    }

    /** Downloads the setup files (rea-setup.sh, rea-mcp, rea-shell, the filter and the default groups). */
    fun downloadSetupFiles() = runTask("files") { fetchSetupFiles() }

    private suspend fun fetchSetupFiles() {
        _state.update { it.copy(progress = "Reading the file list…") }
        val listUrl = rawUrl("files.json")
        appendLog("Source: ${_state.value.source}")
        appendLog(listUrl)
        val index = JSONObject(httpGet(listUrl))
        val version = index.optString("version").ifBlank { throw IllegalStateException("files.json has no version") }
        val files = index.optJSONArray("files") ?: throw IllegalStateException("files.json lists no files")
        shareDir.mkdirs()
        for (i in 0 until files.length()) {
            val name = files.getString(i)
            require(Regex("[A-Za-z0-9._-]+").matches(name)) { "Bad file name in files.json: $name" }
            _state.update { it.copy(progress = "Downloading $name (${i + 1}/${files.length()})…") }
            val target = File(shareDir, name)
            val partial = File(shareDir, "$name.part")
            partial.writeText(httpGet(rawUrl(name)))
            if (!partial.renameTo(target)) { partial.copyTo(target, overwrite = true); partial.delete() }
            if (!name.endsWith(".json")) runCatching { Os.chmod(target.absolutePath, 493) }
            appendLog("✓ $name")
        }
        File(shareDir, "files.json").writeText(index.toString(1))
        relocateSetupFiles()
        appendLog("✓ Setup files $version in ${shareDir.absolutePath}")
        ChrootSetup.install()?.let { install ->
            if (!filesSupportChroot()) throw IllegalStateException(chrootFilesError())
            if (!copyFilesToChroot(install)) throw IllegalStateException("Could not copy the setup files into the Ubuntu chroot")
            appendLog("✓ Copied into the Ubuntu chroot: $CHROOT_SHARE")
        }
        _state.update { it.copy(filesUpdate = null) }
        // A changed filter or launcher only reaches agents through the launcher step
        if (Component.LAUNCHER in _state.value.installed) {
            shellTask("Updating the launcher…", "launcher", 600)
        }
    }

    /** Checks the source for newer setup files (shown as an update on the page). */
    fun checkForUpdate() = scope.launch {
        runCatching { JSONObject(httpGet(rawUrl("files.json"))).optString("version") }
            .onSuccess { remote ->
                val local = _state.value.filesVersion
                // only a newer version is an update (a repository behind the downloaded files is no downgrade offer)
                val newer = remote.isNotBlank() && (local == null || (ai.antigem.ui.browser.PluginVersions.compare(remote, local) ?: 0) > 0)
                _state.update { it.copy(filesUpdate = remote.takeIf { newer }) }
            }
            .onFailure { Log.w(TAG, "update check failed: ${it.message}") }
    }

    // ---------------- install ----------------

    /** [wanted] plus whatever they need that is missing, in install order. */
    fun plan(wanted: Collection<Component>, reinstall: Boolean = false): List<Component> {
        val installed = _state.value.installed
        val result = mutableSetOf<Component>()
        for (c in wanted) {
            c.needs.filter { it !in installed }.forEach { result.add(it) }
            if (reinstall || c !in installed) result.add(c)
        }
        // The launcher goes with REA: agents start REA through it
        if (Component.REA in result && Component.LAUNCHER !in installed) result.add(Component.LAUNCHER)
        return result.sortedBy { it.ordinal }
    }

    /** Installs one component (and what it needs); installs it again when it is already there (repair / update). */
    fun install(component: Component) = installAll(plan(listOf(component), reinstall = true), single = component)

    /** Installs [components] (already planned) one step after another, stopping at the first failure. */
    fun installAll(components: List<Component>, single: Component? = null) {
        if (components.isEmpty()) {
            report("Everything selected is already installed.")
            return
        }
        runTask(single?.step ?: "batch") {
            // Enough storage for every part (their listed sizes, plus room for apt / npm while they install)?
            val chroot = ChrootSetup.install()
            val needed = components.sumOf { if (chroot != null && it == Component.CONTAINER) 0L else StorageSpace.parseSize(it.size) }
            StorageSpace.shortage(chroot?.let { File(it.path) } ?: appContext.filesDir, needed + StorageSpace.MARGIN_BYTES,
                if (components.size == 1) components[0].label else "these ${components.size} parts")?.let {
                report(it, isError = true)
                return@runTask
            }
            if (!_state.value.filesReady) fetchSetupFiles()
            _state.update { it.copy(batch = components, batchIndex = 0) }
            for ((i, c) in components.withIndex()) {
                _state.update { it.copy(installing = c, batchIndex = i) }
                val label = if (components.size > 1) "Step ${i + 1} of ${components.size} · ${c.label}" else c.label
                if (!shellTask("$label…", c.step, 3600)) return@runTask
                refreshNow(force = true)
            }
            report(if (components.size == 1) "${components[0].label} installed." else "Installed ${components.size} parts.")
        }
    }

    /** Stops the running step: kills the setup script and everything it started (apt, proot, downloads). */
    fun stop() {
        val chroot = ChrootSetup.install()
        if (chroot != null) {
            scope.launch { ChrootSetup.stopScript(appContext, chroot, CHROOT_SHARE) }
        } else {
            val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
            if (pid != null) scope.launch { killTree(pid) }
        }
        job?.cancel()
        report("Stopped. Run the step again to finish it: it picks up where it left off.", isError = true)
    }

    fun runDoctor() = runTask("doctor") {
        _state.update { it.copy(doctorLog = emptyList()) }
        shellTask("Running rea doctor…", "doctor", 300)
        _state.update { it.copy(doctorLog = it.log) }
    }

    fun refreshTools() = runTask("tools") {
        if (shellTask("Reading REA's tool list…", "tools", 300)) report("Tool list updated: ${_state.value.tools.size} tools.")
    }

    fun uninstallRea() = runTask("uninstall") {
        if (shellTask("Removing REA…", "uninstall", 600)) report("REA removed. The Ubuntu container, Java and Node.js stay.")
    }

    fun removeContainer() = runTask("remove-container") {
        if (shellTask("Deleting the Ubuntu container…", "remove-container", 600)) report("The Ubuntu container was deleted.")
    }

    // ---------------- tools & groups ----------------

    private data class Catalog(val tools: List<Tool>, val server: String?, val instructionsBytes: Int, val lazyBytes: Int)

    private fun readTools(): Catalog = runCatching {
        val json = JSONObject(toolsFile.readText())
        val arr = json.optJSONArray("tools") ?: JSONArray()
        val tools = (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            Tool(
                name = t.getString("name"),
                title = t.optString("title"),
                description = t.optString("description"),
                required = t.optJSONArray("required")?.let { r -> (0 until r.length()).map { r.getString(it) } } ?: emptyList(),
                bytes = t.optInt("bytes", 0),
                changes = t.optBoolean("changes", false)
            )
        }
        val server = json.optJSONObject("server")?.let { s -> "${s.optString("name")} ${s.optString("version")}".trim() }
        Catalog(tools, server, json.optInt("instructions_bytes", 0), json.optInt("lazy_bytes", 0))
    }.getOrDefault(Catalog(emptyList(), null, 0, 0))

    private fun parseGroups(arr: JSONArray?): List<Group> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optString("id").ifBlank { return@mapNotNull null }
            Group(
                id = id,
                name = g.optString("name").ifBlank { id },
                description = g.optString("description"),
                builtin = g.optBoolean("builtin", false),
                tools = g.optJSONArray("tools")?.let { t -> (0 until t.length()).map { t.getString(it) } }?.distinct() ?: emptyList()
            )
        }
    }

    private fun readDefaultGroups(): Pair<List<Group>, List<String>> = runCatching {
        val json = JSONObject(File(shareDir, "groups.default.json").readText())
        val selection = json.optJSONArray("defaultSelection")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList()
        parseGroups(json.optJSONArray("groups")) to selection
    }.getOrDefault(emptyList<Group>() to emptyList())

    /** The user's groups; created from the defaults the first time, and given new built-in groups the user never removed. */
    private fun readGroups(defaults: List<Group>): List<Group> {
        val file = groupsFile
        if (!file.exists()) {
            if (defaults.isEmpty()) return emptyList()
            writeGroups(defaults, emptyList())
            return defaults
        }
        return runCatching {
            val json = JSONObject(file.readText())
            val removed = json.optJSONArray("removedBuiltins")?.let { r -> (0 until r.length()).map { r.getString(it) } } ?: emptyList()
            val groups = parseGroups(json.optJSONArray("groups"))
            val missing = defaults.filter { d -> d.builtin && groups.none { it.id == d.id } && d.id !in removed }
            if (missing.isNotEmpty()) (groups + missing).also { writeGroups(it, removed) } else groups
        }.getOrElse {
            Log.w(TAG, "groups.json unreadable: ${it.message}")
            emptyList()
        }
    }

    private fun removedBuiltins(): List<String> = runCatching {
        JSONObject(groupsFile.readText()).optJSONArray("removedBuiltins")?.let { r -> (0 until r.length()).map { r.getString(it) } }
    }.getOrNull() ?: emptyList()

    private fun writeGroups(groups: List<Group>, removed: List<String>) {
        confDir.mkdirs()
        val json = JSONObject().apply {
            put("version", 1)
            put("groups", JSONArray().apply {
                groups.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.id); put("name", g.name); put("description", g.description); put("builtin", g.builtin)
                        put("tools", JSONArray(g.tools.distinct().sorted()))
                    })
                }
            })
            if (removed.isNotEmpty()) put("removedBuiltins", JSONArray(removed.distinct()))
        }
        val partial = File(confDir, "groups.json.part")
        partial.writeText(json.toString(1))
        if (!partial.renameTo(groupsFile)) { partial.copyTo(groupsFile, overwrite = true); partial.delete() }
        // rea-mcp in the Ubuntu chroot reads the user's own copy
        ChrootSetup.install()?.let { install -> scope.launch { pushGroupsToChroot(install) } }
    }

    private fun saveGroups(groups: List<Group>, removed: List<String> = removedBuiltins()) {
        _state.update { it.copy(groups = groups) }
        scope.launch { runCatching { writeGroups(groups, removed) }.onFailure { report("Could not save the tool groups: ${it.message}", isError = true) } }
    }

    /** A new, empty group; returns its id. */
    fun createGroup(name: String, description: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "group" }.take(40)
        val taken = _state.value.groups.map { it.id }.toSet() + "all"
        var id = base
        var n = 2
        while (id in taken) id = "$base-${n++}"
        saveGroups(_state.value.groups + Group(id, name.trim().ifBlank { id }, description.trim(), builtin = false, tools = emptyList()))
        return id
    }

    fun updateGroup(id: String, name: String, description: String) =
        saveGroups(_state.value.groups.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, description = description.trim()) else it })

    fun setGroupTools(id: String, tools: Collection<String>) =
        saveGroups(_state.value.groups.map { if (it.id == id) it.copy(tools = tools.distinct().sorted()) else it })

    fun toggleTool(id: String, tool: String) {
        val group = _state.value.groups.firstOrNull { it.id == id } ?: return
        setGroupTools(id, if (tool in group.tools) group.tools - tool else group.tools + tool)
    }

    fun deleteGroup(id: String) {
        val group = _state.value.groups.firstOrNull { it.id == id } ?: return
        val removed = removedBuiltins().let { if (group.builtin) it + id else it }
        saveGroups(_state.value.groups.filter { it.id != id }, removed)
    }

    /** Puts a built-in group back to its defaults (name, description and tools). */
    fun resetGroup(id: String) {
        val default = _state.value.defaultGroups.firstOrNull { it.id == id } ?: return
        saveGroups(_state.value.groups.map { if (it.id == id) default else it })
    }

    /** All groups back to the defaults (custom groups are removed). */
    fun resetAllGroups() {
        val defaults = _state.value.defaultGroups
        if (defaults.isEmpty()) {
            report("Download the setup files first: they carry the default groups.", isError = true)
            return
        }
        saveGroups(defaults, emptyList())
    }

    // ---------------- websites (the browser's tabs through ReaWebBridge) ----------------

    const val WEB_GROUP = "web"
    private const val WEB_PREF = "web_enabled"

    /** REA's tools for live pages in a Chromium browser and for what they capture (REA 6.8). */
    private val WEB_TOOLS = listOf(
        "list_browser_targets", "inspect_web_page", "analyze_web_bundle", "capture_web_screenshot", "observe_web_session",
        "inspect_web_event_listeners", "discover_webmcp_tools", "observe_web_execution", "compare_web_captures",
        "compare_web_screenshots", "export_web_scripts", "trace_web_module_imports", "trace_web_source_location",
        "inspect_web_network_capture"
    )

    /** What agents read about the group (rea_tools {group: "web"}): the endpoint and how to work with the browser. */
    private val WEB_DESCRIPTION =
        "Websites open in AntiGem's browser, through its DevTools endpoint http://127.0.0.1:${ReaWebBridge.DEFAULT_PORT} " +
            "(the cdp_endpoint argument). Do the analysis with these tools first, screenshots included " +
            "(capture_web_screenshot works for background tabs too): list_browser_targets finds the tab's target id, then " +
            "inspect_web_page (DOM, accessibility, scripts, resources, network, console, storage), analyze_web_bundle " +
            "(routes, API endpoints, libraries), observe_web_session while the page is used. Open pages, click and type with " +
            "AntiGem's browser MCP tools (REA only observes), and use them for anything these tools could not do. If the " +
            "endpoint is unreachable, ask the user to turn on Settings → REA → Websites → Browser connection."

    private fun webGroup(catalog: List<Tool>): Group {
        val known = catalog.map { it.name }.toSet()
        val tools = if (known.isEmpty()) WEB_TOOLS else WEB_TOOLS.filter { it in known }
        return Group(WEB_GROUP, "Websites", WEB_DESCRIPTION, builtin = false, tools = tools)
    }

    /** Turns website analysis on (adds the "web" tool group) or off (removes it and stops the browser connection). */
    fun setWebEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(WEB_PREF, enabled).apply()
        _state.update { it.copy(webEnabled = enabled) }
        val groups = _state.value.groups
        if (enabled) {
            val web = webGroup(_state.value.tools)
            saveGroups(if (groups.any { it.id == WEB_GROUP }) groups.map { if (it.id == WEB_GROUP) web else it } else groups + web)
        } else {
            ReaWebBridge.stop()
            if (groups.any { it.id == WEB_GROUP }) saveGroups(groups.filter { it.id != WEB_GROUP })
        }
    }

    /** Starts or stops the loopback DevTools bridge REA connects to. */
    fun setBrowserConnection(context: Context, on: Boolean) {
        if (on) ReaWebBridge.start(context) else ReaWebBridge.stop()
    }

    /**
     * A sentence for the browser MCP's instructions while website analysis is on: analyze with REA first. Reads the
     * setting directly, so it works before the REA page was opened.
     */
    fun browserMcpNote(context: Context): String {
        val on = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WEB_PREF, false)
        if (!on) return ""
        return " Website analysis with REA is on: to analyze a website (structure, scripts, network, technologies, " +
            "accessibility, screenshots…), open it with these tools, then use REA's website tools (tool group \"web\", " +
            "DevTools endpoint http://127.0.0.1:${ReaWebBridge.DEFAULT_PORT}) for the analysis, screenshots included. " +
            "Use these browser tools to open pages and act on them, and for anything REA could not do."
    }

    // ---------------- MCP server settings (agent-neutral) ----------------
    //
    // Every agent (Antigravity, Claude Code, OpenCode…) runs REA the same way: a stdio MCP server with the command
    // [LAUNCHER] and a few env variables. Each agent's own MCP page turns these into its config.

    /** Whether an MCP server command starts REA. */
    fun isReaCommand(command: String?): Boolean =
        command != null && (command == LAUNCHER || command.endsWith("/rea-mcp") || command == "rea-mcp")

    /** The group ids an REA server allows (empty means all tools). */
    fun groupsOf(env: Map<String, String>): List<String> =
        env[GROUPS_ENV].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "all" }

    fun fullOutputSchemas(env: Map<String, String>): Boolean = env[OUTPUT_SCHEMA_ENV] == "full"

    fun modeOf(env: Map<String, String>): Mode = if (env[MODE_ENV] == Mode.ON_DEMAND.env) Mode.ON_DEMAND else Mode.DIRECT

    /** In on-demand mode, the groups whose tools the server also offers directly. */
    fun directGroupsOf(env: Map<String, String>): List<String> =
        env[DIRECT_GROUPS_ENV].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The env of an REA MCP server: the agent may use the tools of [groupIds] (all tools when empty), offered as
     * [mode]; in on-demand mode the tools of [directGroups] are offered directly too. Other keys of [base] stay.
     */
    fun serverEnv(
        groupIds: List<String>,
        fullOutputSchemas: Boolean,
        mode: Mode,
        directGroups: List<String> = emptyList(),
        base: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val env = base.toMutableMap()
        env[GROUPS_ENV] = if (groupIds.isEmpty()) "all" else groupIds.joinToString(",")
        env[MODE_ENV] = mode.env
        if (mode == Mode.ON_DEMAND && directGroups.isNotEmpty()) env[DIRECT_GROUPS_ENV] = directGroups.joinToString(",") else env.remove(DIRECT_GROUPS_ENV)
        if (fullOutputSchemas) env[OUTPUT_SCHEMA_ENV] = "full" else env.remove(OUTPUT_SCHEMA_ENV)
        return env
    }

    // ---------------- the REA skill plugin ----------------

    /** Installs (or updates) the REA skill plugin from the source repository through the plugin repositories. */
    fun installSkillPlugin() = runTask("plugin") {
        PluginRepositories.init(appContext)
        val wanted = PluginRepositories.parse(_state.value.source)
        _state.update { it.copy(progress = "Adding ${wanted.label} to the plugin repositories…") }
        val repo = PluginRepositories.repos.firstOrNull { it.slug.equals(wanted.slug, ignoreCase = true) }
            ?: withContext(Dispatchers.Main) {
                // The default branch is what Custom AntiGem Plugins shows; "main" is spelled out only when asked
                val plain = if (wanted.ref == "main") wanted.copy(ref = "") else wanted
                PluginRepositories.add(plain)
                plain
            }
        appendLog("Repository: ${repo.label}")
        _state.update { it.copy(progress = "Reading ${repo.label}…") }
        PluginRepositories.refresh(repo)
        val listing = PluginRepositories.listings[repo.label]
            ?: throw IllegalStateException(PluginRepositories.errors[repo.label] ?: "Could not read ${repo.label}")
        val plugin = listing.plugins.firstOrNull { it.id == PLUGIN_ID || it.path.substringAfterLast('/') == PLUGIN_ID }
            ?: throw IllegalStateException("${repo.label} has no REA plugin")
        val existing = PluginRepositories.installedFor(repo, plugin)
        _state.update { it.copy(progress = "Installing the REA skill ${plugin.version}…") }
        val result = PluginRepositories.install(repo, plugin, existing?.folderName ?: PLUGIN_ID, existing?.id)
        appendLog("✓ ${result.name} ${result.version} in ${result.dirPath}")
        report("REA skill ${result.version} installed. Connect it in your agent's MCP settings (Custom Plugin MCP → REA), or pick it in chat with /plu:.")
    }

    // ---------------- helpers ----------------

    private fun runTask(task: String, block: suspend () -> Unit) {
        if (job?.isActive == true) {
            report("Something is already running. Wait for it, or tap Stop.", isError = true)
            return
        }
        job = scope.launch {
            _state.update {
                it.copy(task = task, logTask = task, log = emptyList(), progress = null, message = null, isError = false,
                    installing = null, batch = emptyList(), batchIndex = 0)
            }
            acquireWakeLock()
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$task failed", e)
                appendLog("✗ ${e.message ?: e.javaClass.simpleName}")
                report(e.message ?: "Failed", isError = true)
            } finally {
                releaseWakeLock()
                pidFile.delete()
                // also after Stop (the job is cancelled; reading the chroot's status still has to happen)
                withContext(NonCancellable) { runCatching { refreshNow(force = true) } }
                _state.update { it.copy(task = null, progress = null, installing = null) }
            }
        }
    }

    /**
     * The setup files are written for com.termux (/data/data/com.termux/files/…). In ai.antigem (and the ai.antidev
     * debug build) they are rewritten for this app's package, like Termux packages, else the scripts look for the
     * container and their tools in com.termux's folder. Runs before every step too, for files downloaded before.
     */
    private fun relocateSetupFiles() {
        runCatching { TermuxRelocation.relocateIfNeeded(appContext, shareDir) }
            .onFailure { Log.w(TAG, "relocating the setup files: ${it.message}") }
    }

    /** Runs `rea-setup.sh <step>`, streaming its output into the log; false (and an error message) on failure. */
    private suspend fun shellTask(progress: String, step: String, timeoutSeconds: Long): Boolean {
        val script = File(shareDir, "rea-setup.sh")
        if (!script.exists()) throw IllegalStateException("The setup files are missing. Tap Get setup files first.")
        ChrootSetup.install()?.let { return chrootTask(it, progress, step, timeoutSeconds) }
        relocateSetupFiles()
        _state.update { it.copy(progress = progress) }
        appendLog("$ bash rea-setup.sh $step")
        // $$ is this shell, and exec keeps its PID for the script: Stop kills that process tree
        val command = "echo \$\$ > '${pidFile.absolutePath}'; exec bash '${script.absolutePath}' $step < /dev/null"
        val result = LocalEnvironmentManager.executeCommand(
            command, appContext, timeoutSeconds = timeoutSeconds,
            customEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            onOutputLine = { line -> if (line.isNotBlank()) appendLog(line.trimEnd()) }
        )
        if (result.exitCode != 0) {
            val last = if (_state.value.log.takeLast(40).any(StorageSpace::isOutOfSpaceLine)) StorageSpace.outOfSpaceMessage()
                else _state.value.log.lastOrNull { it.startsWith("✗") }?.removePrefix("✗ ")
            report(last ?: "The $step step failed (exit ${result.exitCode}). Check the log, then run it again.", isError = true)
            return false
        }
        return true
    }

    /**
     * [shellTask] in the Ubuntu chroot: the same `rea-setup.sh <step>`, run inside the chroot as root (the setup files
     * copied in first). Afterwards the tool list comes back to the app and the app's tool groups go to the user.
     */
    private suspend fun chrootTask(install: ChrootInstall, progress: String, step: String, timeoutSeconds: Long): Boolean {
        if (!filesSupportChroot()) throw IllegalStateException(chrootFilesError())
        if (!copyFilesToChroot(install)) throw IllegalStateException("Could not copy the setup files into the Ubuntu chroot (root access?)")
        _state.update { it.copy(progress = progress) }
        appendLog("$ bash rea-setup.sh $step   (in the Ubuntu chroot, as root)")
        val r = ChrootSetup.runScript(appContext, install, CHROOT_SHARE, "rea-setup.sh", step.split(' ').filter { it.isNotBlank() },
            timeoutSeconds * 1000) { line -> if (line.isNotBlank()) appendLog(line.trimEnd()) }
        if (!r.ok) {
            val last = if (_state.value.log.takeLast(40).any(StorageSpace::isOutOfSpaceLine)) StorageSpace.outOfSpaceMessage()
                else _state.value.log.lastOrNull { it.startsWith("✗") }?.removePrefix("✗ ")
            report(last ?: "The $step step failed (exit ${r.exitCode}). Check the log, then run it again.", isError = true)
            return false
        }
        if (step in setOf("launcher", "rea", "tools")) {
            pullToolsFromChroot(install)
            if (groupsFile.exists()) pushGroupsToChroot(install)
        }
        return true
    }

    private fun appendLog(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(600)) }

    private fun acquireWakeLock() {
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiGem:rea-setup").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        }.onFailure { Log.w(TAG, "wake lock: ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** SIGTERM to [root] and every process under it (read from /proc; all run as this app's UID), SIGKILL to survivors. */
    private suspend fun killTree(root: Int) {
        fun children(): Map<Int, List<Int>> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@mapNotNull null
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            ppid to pid
        }.groupBy({ it.first }, { it.second })

        fun tree(): List<Int> {
            val map = children()
            val out = mutableListOf(root)
            var i = 0
            while (i < out.size) { out.addAll(map[out[i]].orEmpty()); i++ }
            return out
        }
        tree().forEach { runCatching { Os.kill(it, OsConstants.SIGTERM) } }
        delay(1500)
        tree().forEach { pid -> if (File("/proc/$pid").exists()) runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "AntiGem/${BuildConfig.VERSION_NAME}").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) throw IllegalStateException("Not found: $url (is the repository pushed and the source right?)")
            if (!response.isSuccessful) throw IllegalStateException("Download failed (HTTP ${response.code}): $url")
            response.body?.string() ?: throw IllegalStateException("Empty response: $url")
        }
    }
}
