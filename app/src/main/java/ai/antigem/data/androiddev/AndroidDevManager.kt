package ai.antigem.data.androiddev

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
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.local.chroot.ChrootSetup
import ai.antigem.data.rea.ReaManager
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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Settings → Android Development: sets up what agents (and the user) need to build Android apps on the phone:
 * Kotlin / Java / Jetpack Compose with Gradle, Flutter and React Native, in the proot-distro Ubuntu container of the
 * terminal. It is the same container REA uses, so Java 21 and Node.js are shared.
 *
 * Nothing ships in the APK: the setup scripts live in the antigem_plugins repository
 * (android-dev/skills/android-dev/scripts) and are downloaded into $PREFIX/share/antigem-android when needed. Every
 * install step runs `android-setup.sh <step>`, the same script the Android Development skill tells agents to run.
 * Agents build with the `adev` command (a command in the container with the tools on PATH) and create projects
 * with `adev-new`.
 *
 * When AntiGem's Ubuntu chroot is the phone's runtime, the tools install into the chroot itself instead (the same
 * script, run there as root: see [ChrootSetup]), with Ubuntu's own arm64 aapt2, adb, fastboot and qemu-user.
 */
object AndroidDevManager {
    private const val TAG = "AndroidDevManager"
    private const val PREFS = "android_dev_settings"

    /** Where the setup files and the skill plugin come from (owner/repo@branch). */
    const val DEFAULT_SOURCE = ReaManager.DEFAULT_SOURCE
    private const val SCRIPTS_PATH = "android-dev/skills/android-dev/scripts"
    const val PLUGIN_ID = "android-dev"
    const val CONTAINER = ReaManager.CONTAINER
    /** Where the test builds (and, by default, agents' new projects) go, under the Termux home. */
    const val PROJECTS_DIR = "AndroidProjects"
    /** Where the setup files go in the Ubuntu chroot. */
    private val CHROOT_SHARE = ChrootSetup.shareDir("antigem-android")

    private const val JAVA_HOME = "usr/lib/jvm/java-21-openjdk-arm64"
    private const val SDK = "root/android-sdk"
    private const val PLATFORM = "android-36"

    /** What the user wants to build; each needs some [Component]s. */
    enum class Framework(val key: String, val label: String, val description: String, val size: String) {
        KOTLIN("compose", "Kotlin / Java · Jetpack Compose", "Native Android apps with Gradle: Kotlin or Java, Jetpack Compose or XML views. Flutter and React Native need this too. Apps are built for this phone (arm64-v8a only).", "≈1.2 GB with Gradle's first downloads"),
        FLUTTER("flutter", "Flutter", "Dart apps with Flutter (stable channel), debug and release builds.", "+≈1.6 GB"),
        REACT_NATIVE("rn", "React Native", "JavaScript / TypeScript apps with the React Native CLI. Needs Node.js and the NDK (React Native compiles C++); npm downloads the rest per project.", "+≈1.8 GB, ≈400 MB per project")
    }

    /**
     * One part of the setup, installed by `android-setup.sh <step>`. [needs] are installed first when missing.
     * [size] is the approximate disk use.
     */
    enum class Component(
        val step: String,
        private val termuxLabel: String,
        private val termuxDescription: String,
        val size: String,
        /** The wording when the tools install into the Ubuntu chroot (null: the same). */
        private val chrootLabel: String? = null,
        private val chrootDescription: String? = null
    ) {
        CONTAINER("container", "Ubuntu container", "proot-distro and an Ubuntu 24.04 container the tools run in (the same one REA uses).", "≈150 MB",
            "Ubuntu chroot", "This phone's Ubuntu chroot: the tools install right into it, nothing to add."),
        BASE("base", "Base tools", "curl, git, zip, unzip, xz, Python and binutils in Ubuntu. Flutter, the SDK download and stripping .so files need them.", "≈70 MB"),
        JAVA("java", "Java 21", "OpenJDK 21, headless. Gradle and the Android SDK tools run on it (shared with REA).", "≈290 MB"),
        SDK("sdk", "Android SDK", "Command-line tools, the Android 16 (API 36) platform and build-tools 36, Termux's arm64 aapt2 (Google's is x86_64 only) and Gradle settings that suit the phone.", "≈500 MB",
            chrootDescription = "Command-line tools, the Android 16 (API 36) platform and build-tools 36, Ubuntu's arm64 aapt2 (Google's is x86_64 only) and Gradle settings that suit the phone."),
        LAUNCHER("launcher", "adev commands", "adev runs a command in the container with every tool on PATH (adev ./gradlew assembleDebug); adev-new creates a working hello-world project.", "<1 MB",
            chrootDescription = "adev runs a command with every tool on PATH (adev ./gradlew assembleDebug); adev-new creates a working hello-world project."),
        FLUTTER("flutter", "Flutter", "Flutter stable, cloned from GitHub, and its Android engine files. Install again to update it.", "≈1.5 GB"),
        FLUTTER_RELEASE("flutter-release", "Flutter release builds", "Flutter's release compiler (gen_snapshot) only exists for x86-64 PCs. This runs it through qemu (Termux) with Ubuntu's x86-64 C library, so flutter build apk --release works; each release build takes a few minutes longer. Debug builds do not need it.", "≈110 MB",
            chrootDescription = "Flutter's release compiler (gen_snapshot) only exists for x86-64 PCs. This runs it through Ubuntu's qemu-user with Ubuntu's x86-64 C library, so flutter build apk --release works; each release build takes a few minutes longer. Debug builds do not need it."),
        NODE("node", "Node.js 24", "Installed with nvm (the same Node.js REA uses). React Native and Expo run on it.", "≈240 MB"),
        NDK("ndk", "C/C++ (NDK)", "The Android NDK and CMake for C/C++ code: React Native's new architecture, apps and plugins with CMake. Google builds them for x86-64 PCs only, so their programs are swapped for Ubuntu's arm64 LLVM, CMake and Ninja.", "≈1.5 GB"),
        ADB("adb", "adb", "adb in the terminal, to install and debug the apps you build on this phone. Connect it in ADB Connect.", "≈10 MB",
            "adb and fastboot", "Ubuntu's adb and fastboot, to install and debug the apps you build on this phone; no sudo needed (USB / OTG devices too). Connect adb in ADB Connect.");

        val label: String get() = chrootLabel?.takeIf { ChrootSetup.install() != null } ?: termuxLabel
        val description: String get() = chrootDescription?.takeIf { ChrootSetup.install() != null } ?: termuxDescription

        val needs: List<Component>
            get() = when (this) {
                CONTAINER, ADB -> emptyList()
                BASE, JAVA -> listOf(CONTAINER)
                SDK -> listOf(CONTAINER, BASE, JAVA)
                LAUNCHER -> listOf(CONTAINER)
                FLUTTER -> listOf(CONTAINER, BASE, JAVA, SDK, LAUNCHER)
                FLUTTER_RELEASE -> listOf(CONTAINER, BASE, JAVA, SDK, LAUNCHER, FLUTTER)
                NODE -> listOf(CONTAINER, BASE)
                NDK -> listOf(CONTAINER, BASE, JAVA, SDK)
            }
    }

    /** The parts each framework needs. */
    fun componentsFor(framework: Framework): List<Component> = when (framework) {
        Framework.KOTLIN -> listOf(Component.CONTAINER, Component.BASE, Component.JAVA, Component.SDK, Component.LAUNCHER)
        Framework.FLUTTER -> componentsFor(Framework.KOTLIN) + Component.FLUTTER + Component.FLUTTER_RELEASE
        Framework.REACT_NATIVE -> componentsFor(Framework.KOTLIN) + Component.NODE + Component.NDK
    }

    data class State(
        val ready: Boolean = false,
        val bootstrapInstalled: Boolean = false,
        /** The tools live in the Ubuntu chroot (the phone's runtime), not in a proot container of AntiGem's environment. */
        val chroot: Boolean = false,
        /** Where projects go: ~/AndroidProjects of the Termux home, or of the chroot user's home. */
        val projectsDir: String? = null,
        val source: String = DEFAULT_SOURCE,
        /** Version of the downloaded setup files, null when not downloaded. */
        val filesVersion: String? = null,
        /** Newer setup files seen in the source, or null. */
        val filesUpdate: String? = null,
        val installed: Set<Component> = emptySet(),
        val containerName: String? = null,
        val flutterVersion: String? = null,
        val nodeVersion: String? = null,
        val platforms: List<String> = emptyList(),
        /** What is running (a [Component] step, "files", "batch", "doctor", "test-<framework>", "plugin", "uninstall"…). */
        val task: String? = null,
        val installing: Component? = null,
        val batch: List<Component> = emptyList(),
        val batchIndex: Int = 0,
        val progress: String? = null,
        val log: List<String> = emptyList(),
        val logTask: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        /** APKs the test builds made, by framework key. */
        val testApks: Map<String, String> = emptyMap(),
        val pluginInstalled: Boolean = false,
        val pluginVersion: String? = null
    ) {
        val filesReady: Boolean get() = filesVersion != null
        val busy: Boolean get() = task != null
        fun ready(framework: Framework): Boolean = componentsFor(framework).all { it in installed }
        val anyReady: Boolean get() = Framework.entries.any { ready(it) }
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
    private val shareDir get() = File(prefix, "share/antigem-android")
    private val pidFile get() = File(shareDir, ".task.pid")
    private fun rootfs() = File(prefix, "var/lib/proot-distro/containers/$CONTAINER/rootfs")
    val setupScriptPath: String get() = File(shareDir, "android-setup.sh").absolutePath
    val projectsPath: String get() = File(home, PROJECTS_DIR).absolutePath

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _state.update { it.copy(source = prefs.getString("source", null) ?: DEFAULT_SOURCE) }
    }

    fun clearMessage() = _state.update { it.copy(message = null, isError = false) }

    private fun report(message: String, isError: Boolean = false) = _state.update { it.copy(message = message, isError = isError) }

    // ---------------- state ----------------

    /**
     * Re-reads what is installed and the setup files: only files, cheap enough to poll. In the Ubuntu chroot what is
     * installed comes from the setup script (a root call), at most every [CHROOT_STATUS_MS] unless [force].
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
        var status = ChrootSetup.status(appContext, install, CHROOT_SHARE, "android-setup.sh")
        // the setup files were downloaded but never copied into this chroot (e.g. it became the runtime later)
        if (status == null && File(shareDir, "android-setup.sh").exists() && copyFilesToChroot(install)) {
            status = ChrootSetup.status(appContext, install, CHROOT_SHARE, "android-setup.sh")
        }
        chrootStatus = status
        chrootStatusFor = install.path
        chrootStatusAt = System.currentTimeMillis()
        return status
    }

    /** [refreshNow] for the Ubuntu chroot: everything from the setup script's status. */
    private suspend fun refreshChroot(install: ChrootInstall, force: Boolean) {
        val status = chrootStatus(install, force).orEmpty()
        val installed = buildSet {
            // the chroot is the container
            add(Component.CONTAINER)
            Component.entries.filter { status[it.step] == "yes" }.forEach { add(it) }
        }
        val filesVersion = runCatching { JSONObject(File(shareDir, "files.json").readText()).optString("version").ifBlank { null } }
            .getOrNull()?.takeIf { File(shareDir, "android-setup.sh").exists() }
        val testApks = Framework.entries.mapNotNull { f -> status["apk_${f.key}"]?.ifBlank { null }?.let { f.key to it } }.toMap()
        val plugin = BrowserExtensionManager.instance.extensions.firstOrNull { it.id == PLUGIN_ID }
        _state.update {
            it.copy(
                ready = true,
                bootstrapInstalled = true,
                chroot = true,
                projectsDir = status["projects"]?.ifBlank { null },
                filesVersion = filesVersion,
                filesUpdate = it.filesUpdate?.takeIf { v -> v != filesVersion },
                installed = installed,
                containerName = status["os"]?.ifBlank { null } ?: install.os,
                flutterVersion = status["flutter_version"]?.ifBlank { null },
                nodeVersion = status["node_version"]?.ifBlank { null },
                platforms = status["platforms"].orEmpty().split(' ').filter { p -> p.isNotBlank() }.sorted(),
                testApks = testApks,
                pluginInstalled = plugin != null,
                pluginVersion = plugin?.version
            )
        }
    }

    /**
     * Whether the downloaded setup files know the Ubuntu chroot (1.1.0 and newer list adev-chroot). Older ones only know
     * Termux and would run their Termux steps inside the chroot.
     */
    private fun filesSupportChroot(): Boolean = runCatching {
        val arr = JSONObject(File(shareDir, "files.json").readText()).optJSONArray("files")
        (0 until (arr?.length() ?: 0)).any { arr!!.getString(it) == "adev-chroot" }
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
        val r = ChrootSetup.copyIn(appContext, install, shareDir, names.distinct(), CHROOT_SHARE) { name -> !name.endsWith(".json") }
        if (!r.ok) Log.w(TAG, "copying the setup files into the chroot: ${r.message()}")
        return r.ok
    }

    private suspend fun refreshNow(force: Boolean = false) {
        val ctx = appContext
        ChrootSetup.install()?.let { refreshChroot(it, force); return }
        val root = rootfs()
        val bin = File(prefix, "bin")
        val sdk = File(root, SDK)
        val installed = buildSet {
            if (File(root, "usr/bin/apt-get").exists()) add(Component.CONTAINER)
            if (File(root, "usr/bin/git").exists() && File(root, "usr/bin/zip").exists() && File(root, "usr/bin/objcopy").exists()) add(Component.BASE)
            if (File(root, "$JAVA_HOME/bin/javac").exists()) add(Component.JAVA)
            if (File(sdk, "cmdline-tools/latest/lib/sdkmanager-classpath.jar").exists() && File(sdk, "platforms/$PLATFORM").isDirectory &&
                File(bin, "aapt2").exists()) add(Component.SDK)
            if (File(bin, "adev").exists() && File(shareDir, "adev-new").exists()) add(Component.LAUNCHER)
            if (File(root, "root/tools/flutter/bin/cache/dart-sdk/bin/dart").exists()) add(Component.FLUTTER)
            if (File(root, "root/tools/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot").exists() &&
                File(bin, "qemu-x86_64").exists()) add(Component.FLUTTER_RELEASE)
            if (File(root, "root/tools/node/bin/node").exists()) add(Component.NODE)
            if (File(sdk, "ndk-arm64.txt").exists() && File(root, "usr/bin/cmake").exists()) add(Component.NDK)
            if (File(bin, "adb").exists()) add(Component.ADB)
        }
        val flutterVersion = runCatching {
            JSONObject(File(root, "root/tools/flutter/bin/cache/flutter.version.json").readText()).optString("frameworkVersion").ifBlank { null }
        }.getOrNull()
        val nodeVersion = runCatching { File(root, "root/tools/node").canonicalFile.name }.getOrNull()?.takeIf { it.startsWith("v") }
        val filesVersion = runCatching { JSONObject(File(shareDir, "files.json").readText()).optString("version").ifBlank { null } }
            .getOrNull()?.takeIf { File(shareDir, "android-setup.sh").exists() }
        val testApks = Framework.entries.mapNotNull { f -> testApk(f)?.takeIf { File(it).exists() }?.let { f.key to it } }.toMap()
        val plugin = BrowserExtensionManager.instance.extensions.firstOrNull { it.id == PLUGIN_ID }
        _state.update {
            it.copy(
                ready = true,
                bootstrapInstalled = LocalEnvironmentManager.isInstalled(ctx),
                chroot = false,
                projectsDir = projectsPath,
                filesVersion = filesVersion,
                filesUpdate = it.filesUpdate?.takeIf { v -> v != filesVersion },
                installed = installed,
                containerName = runCatching {
                    File(root, "etc/os-release").readLines().firstOrNull { l -> l.startsWith("PRETTY_NAME=") }
                        ?.substringAfter('=')?.trim('"')
                }.getOrNull(),
                flutterVersion = flutterVersion,
                nodeVersion = nodeVersion,
                platforms = File(sdk, "platforms").list()?.sorted().orEmpty(),
                testApks = testApks,
                pluginInstalled = plugin != null,
                pluginVersion = plugin?.version
            )
        }
    }

    /** Where `android-setup.sh test <framework>` leaves the APK. */
    fun testApk(framework: Framework): String? {
        val dir = File(home, "$PROJECTS_DIR/hello-${framework.key}")
        return when (framework) {
            Framework.KOTLIN -> File(dir, "app/build/outputs/apk/debug/app-debug.apk")
            Framework.FLUTTER -> File(dir, "build/app/outputs/flutter-apk/app-debug.apk")
            Framework.REACT_NATIVE -> File(dir, "android/app/build/outputs/apk/debug/app-debug.apk")
        }.absolutePath
    }

    // ---------------- setup files (downloaded, never bundled) ----------------

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
        // New adev / adev-new versions reach the terminal through the launcher step
        if (Component.LAUNCHER in _state.value.installed) shellTask("Updating the adev commands…", "launcher", 600)
    }

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

    /** Creates (once) and builds a hello-world app of [framework] in ~/AndroidProjects, to check the setup works. */
    fun testBuild(framework: Framework) = runTask("test-${framework.key}") {
        if (shellTask("Building a ${framework.label} hello world (the first build downloads a lot and takes several minutes)…", "test ${framework.key}", 3600)) {
            refreshNow(force = true)
            report("${framework.label} works: the test APK is ${_state.value.testApks[framework.key] ?: testApk(framework)}")
        }
    }

    /**
     * Opens Android's package installer for a built APK; first asks for "install unknown apps" when not allowed yet.
     * An APK in the Ubuntu chroot is copied out into the app's cache first (the app cannot read the chroot).
     */
    fun installApk(path: String) {
        val chroot = ChrootSetup.install()
        if (chroot != null) {
            scope.launch {
                val dest = File(appContext.cacheDir, "chroot-apk/${File(path).name}")
                val r = ChrootManager.copyOut(appContext, chroot, path, dest)
                if (!r.ok || !dest.isFile) {
                    report("Could not copy the APK out of the Ubuntu chroot: ${r.message()}", isError = true)
                    return@launch
                }
                withContext(Dispatchers.Main) { openInstaller(dest.absolutePath) }
            }
            return
        }
        openInstaller(path)
    }

    private fun openInstaller(path: String) {
        val ctx = appContext
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                report("Allow AntiGem to install apps, then tap Install again.")
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { report("Could not open the installer: ${it.message}", isError = true) }
    }

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

    fun runDoctor() = runTask("doctor") { shellTask("Checking the tools…", "doctor", 300) }

    fun cleanCache() = runTask("clean-cache") {
        if (shellTask("Deleting Gradle's caches…", "clean-cache", 600)) report("Gradle's caches were deleted. The next build downloads what it needs again.")
    }

    fun uninstallFlutter() = runTask("uninstall-flutter") {
        if (shellTask("Removing Flutter…", "uninstall-flutter", 600)) report("Flutter removed.")
    }

    fun uninstall() = runTask("uninstall") {
        if (shellTask("Removing the Android tools…", "uninstall", 900)) report("Removed the Android SDK, Flutter, Gradle's files and adev. The container, Java, Node.js and your projects stay.")
    }

    // ---------------- the skill plugin ----------------

    /** Installs (or updates) the Android Development skill plugin from the source repository. */
    fun installSkillPlugin() = runTask("plugin") {
        PluginRepositories.init(appContext)
        val wanted = PluginRepositories.parse(_state.value.source)
        _state.update { it.copy(progress = "Adding ${wanted.label} to the plugin repositories…") }
        val repo = PluginRepositories.repos.firstOrNull { it.slug.equals(wanted.slug, ignoreCase = true) }
            ?: withContext(Dispatchers.Main) {
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
            ?: throw IllegalStateException("${repo.label} has no Android Development plugin")
        val existing = PluginRepositories.installedFor(repo, plugin)
        _state.update { it.copy(progress = "Installing the Android Development skill ${plugin.version}…") }
        val result = PluginRepositories.install(repo, plugin, existing?.folderName ?: PLUGIN_ID, existing?.id)
        appendLog("✓ ${result.name} ${result.version} in ${result.dirPath}")
        report("Android Development skill ${result.version} installed. Give it to an agent with /plu: in chat, or with the Custom Plugin MCP preset in the agent's MCP settings.")
    }

    // ---------------- helpers ----------------

    private fun runTask(task: String, block: suspend () -> Unit) {
        if (job?.isActive == true) {
            report("Something is already running. Wait for it, or tap Stop.", isError = true)
            return
        }
        // REA installs into the same container: two apt runs at once would wait on each other's lock
        if (ReaManager.state.value.busy) {
            report("REA's setup is running in the same container. Wait for it to finish.", isError = true)
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

    /** Runs `android-setup.sh <step>`, streaming its output into the log; false (and an error message) on failure. */
    private suspend fun shellTask(progress: String, step: String, timeoutSeconds: Long): Boolean {
        val script = File(shareDir, "android-setup.sh")
        if (!script.exists()) throw IllegalStateException("The setup files are missing. Tap Get setup files first.")
        ChrootSetup.install()?.let { return chrootTask(it, progress, step, timeoutSeconds) }
        relocateSetupFiles()
        _state.update { it.copy(progress = progress) }
        appendLog("$ bash android-setup.sh $step")
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

    /** [shellTask] in the Ubuntu chroot: the same `android-setup.sh <step>`, run inside the chroot as root. */
    private suspend fun chrootTask(install: ChrootInstall, progress: String, step: String, timeoutSeconds: Long): Boolean {
        if (!filesSupportChroot()) throw IllegalStateException(chrootFilesError())
        if (!copyFilesToChroot(install)) throw IllegalStateException("Could not copy the setup files into the Ubuntu chroot (root access?)")
        _state.update { it.copy(progress = progress) }
        appendLog("$ bash android-setup.sh $step   (in the Ubuntu chroot, as root)")
        val r = ChrootSetup.runScript(appContext, install, CHROOT_SHARE, "android-setup.sh", step.split(' ').filter { it.isNotBlank() },
            timeoutSeconds * 1000) { line -> if (line.isNotBlank()) appendLog(line.trimEnd()) }
        if (!r.ok) {
            val last = if (_state.value.log.takeLast(40).any(StorageSpace::isOutOfSpaceLine)) StorageSpace.outOfSpaceMessage()
                else _state.value.log.lastOrNull { it.startsWith("✗") }?.removePrefix("✗ ")
            report(last ?: "The $step step failed (exit ${r.exitCode}). Check the log, then run it again.", isError = true)
            return false
        }
        return true
    }

    private fun appendLog(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(600)) }

    private fun acquireWakeLock() {
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiGem:android-dev-setup").apply {
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
