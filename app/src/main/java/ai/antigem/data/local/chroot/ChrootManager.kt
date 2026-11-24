package ai.antigem.data.local.chroot

import ai.antigem.data.preferences.AuthPreferences
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import ai.antigem.i18n.L

/**
 * The rooted Ubuntu chroot runtime (the third way to run the bridge and the agents, next to Termux and a server).
 *
 * Everything that touches mounts, processes or files of the rootfs goes through chrootctl.sh (assets/chroot), run
 * as root with `su -c`. The app never reads the rootfs itself: root-owned folders under /data/local are not readable
 * by the app on every phone, so what it needs (installs, status, sizes) comes from chrootctl's output.
 */
object ChrootManager {
    private const val TAG = "ChrootManager"
    private const val PREFS = "antigem_chroot"
    private const val KEY_INSTALLS = "installs"
    private const val KEY_ACTIVE = "active_path"
    private const val KEY_EXIT = "exit_behavior"
    private const val KEY_BRIDGE_UNPACKED = "linux_bridge_unpacked_for"

    const val BASE_DIR = "/data/local/antigem"
    const val LEGACY_DIR = "/data/local/ubuntu"
    const val BRIDGE_IN_CHROOT = "/usr/local/bin/agy_ide_bridge"
    private const val CTL_ASSET = "chroot/chrootctl.sh"
    private const val LINUX_BRIDGE_ASSET = "bin/agy_ide_bridge_linux.xz"

    private val PLAIN_PATH = Regex("^/[A-Za-z0-9._/-]*$")
    private val USER_NAME = Regex("^[a-z_][a-z0-9_-]{0,30}$")
    private val INSTALL_NAME = Regex("^[A-Za-z0-9._-]{1,40}$")
    private val DNS = Regex("^[0-9a-fA-F.:]{2,45}$")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _installs = MutableStateFlow<List<ChrootInstall>>(emptyList())
    val installs: StateFlow<List<ChrootInstall>> = _installs.asStateFlow()

    private val _activePath = MutableStateFlow<String?>(null)
    val activePath: StateFlow<String?> = _activePath.asStateFlow()

    private val _status = MutableStateFlow<ChrootStatus?>(null)
    val status: StateFlow<ChrootStatus?> = _status.asStateFlow()

    private val _usage = MutableStateFlow<ChrootUsage?>(null)
    val usage: StateFlow<ChrootUsage?> = _usage.asStateFlow()

    private val _probe = MutableStateFlow<ChrootProbe?>(null)
    val probe: StateFlow<ChrootProbe?> = _probe.asStateFlow()

    private val _found = MutableStateFlow<List<ChrootFound>?>(null)
    /** The last scan of the phone (null: not scanned yet). */
    val found: StateFlow<List<ChrootFound>?> = _found.asStateFlow()

    private val _task = MutableStateFlow<ChrootTask?>(null)
    val task: StateFlow<ChrootTask?> = _task.asStateFlow()

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            val p = prefs(appContext)
            var list = ChrootInstall.listFromJson(p.getString(KEY_INSTALLS, null))
            var active = p.getString(KEY_ACTIVE, null)
            // the first chroot version: rootfs at /data/local/ubuntu, chosen with environment_type, nothing saved here
            if (list.isEmpty() && AuthPreferences(appContext).isChrootModeSync()) {
                list = listOf(
                    ChrootInstall(
                        path = LEGACY_DIR, name = "ubuntu", os = "Ubuntu", arch = defaultArch(),
                        user = AuthPreferences(appContext).getChrootUsernameSync(), uid = -1,
                        source = L.local.chrootManager.firstChrootVersion, createdAt = 0L, legacy = true
                    )
                )
                active = LEGACY_DIR
                p.edit().putString(KEY_INSTALLS, ChrootInstall.listToJson(list)).putString(KEY_ACTIVE, active).apply()
            }
            _installs.value = list
            _activePath.value = active?.takeIf { a -> list.any { it.path == a } }
            initialized = true
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveInstalls(context: Context, list: List<ChrootInstall>) {
        _installs.value = list
        prefs(context).edit().putString(KEY_INSTALLS, ChrootInstall.listToJson(list)).apply()
        // the file picker's "AntiGem Ubuntu" location follows the installs
        ai.antigem.data.files.AntiGemDocumentsProvider.notifyRootsChanged(context)
    }

    // ------------------------------------------------------------------ the active runtime

    fun activeInstall(context: Context): ChrootInstall? {
        init(context)
        val path = _activePath.value ?: return null
        return _installs.value.firstOrNull { it.path == path }
    }

    /** The chroot is the phone's runtime and one is installed: the bridge and the terminals run in it. */
    fun isActive(context: Context): Boolean =
        AuthPreferences(context).isChrootModeSync() && activeInstall(context) != null

    /** [isActive] for code without a context (false before [init], which the Application calls). */
    fun isActiveNow(): Boolean = initialized && isActive(appContext)

    /** [activeInstall] for code without a context. */
    fun activeInstallNow(): ChrootInstall? = if (initialized) activeInstall(appContext) else null

    /** Makes [install] the runtime of this phone (Termux stays installed; switching back is one tap). */
    suspend fun activate(context: Context, install: ChrootInstall) {
        init(context)
        if (_installs.value.none { it.path == install.path }) saveInstalls(context, _installs.value + install)
        _activePath.value = install.path
        prefs(context).edit().putString(KEY_ACTIVE, install.path).apply()
        ai.antigem.data.files.AntiGemDocumentsProvider.notifyRootsChanged(context)
        val auth = AuthPreferences(context)
        auth.setEnvironmentType(ENV_CHROOT)
        auth.applyLocalRuntimeUrls(chroot = true)
        auth.setChrootUsername(install.user)
        auth.setLocalToolsInstalled(true, "chroot:${install.name}")
        auth.setLocalToolsEnabled(true)
        auth.setTerminalToolEnabled(true)
    }

    /** Back to the Termux environment (the chroot keeps its files). */
    suspend fun useTermux(context: Context) {
        val auth = AuthPreferences(context)
        auth.setEnvironmentType(ENV_TERMUX)
        auth.applyLocalRuntimeUrls(chroot = false)
    }

    fun setSelected(context: Context, path: String) {
        init(context)
        if (_installs.value.any { it.path == path }) {
            _activePath.value = path
            prefs(context).edit().putString(KEY_ACTIVE, path).apply()
            ai.antigem.data.files.AntiGemDocumentsProvider.notifyRootsChanged(context)
        }
    }

    /** Removes an install from AntiGem's list without touching its files. */
    fun forget(context: Context, path: String) {
        init(context)
        saveInstalls(context, _installs.value.filterNot { it.path == path })
        if (_activePath.value == path) {
            _activePath.value = _installs.value.firstOrNull()?.path
            prefs(context).edit().putString(KEY_ACTIVE, _activePath.value).apply()
            ai.antigem.data.files.AntiGemDocumentsProvider.notifyRootsChanged(context)
        }
    }

    fun exitBehavior(context: Context): ChrootExitBehavior =
        runCatching { ChrootExitBehavior.valueOf(prefs(context).getString(KEY_EXIT, null) ?: "") }
            .getOrDefault(ChrootExitBehavior.KEEP_RUNNING)

    fun setExitBehavior(context: Context, behavior: ChrootExitBehavior) {
        prefs(context).edit().putString(KEY_EXIT, behavior.name).apply()
    }

    // ------------------------------------------------------------------ checks

    fun isPlainPath(path: String) = PLAIN_PATH.matches(path) && !path.contains("..") && !path.contains("//")
    fun isUserName(name: String) = USER_NAME.matches(name)
    fun isInstallName(name: String) = INSTALL_NAME.matches(name) && name != "." && name != ".."
    fun isDnsServer(value: String) = DNS.matches(value)

    /** A phone folder to share: absolute, any name (spaces too) but none of the separators of chroot.conf. */
    fun isBindSource(path: String): Boolean {
        val p = path.trimEnd('/')
        return p.startsWith("/") && p.length in 2..400 && p.none { it == '|' || it == ';' || it.code < 32 } &&
            p.split('/').none { it == ".." || it == "." }
    }

    /** Why [target] cannot hold a shared folder inside the chroot (null: it can). */
    fun bindTargetProblem(target: String, user: String?): String? {
        val t = target.trimEnd('/')
        if (!t.startsWith("/") || !isPlainPath(t)) return L.local.chrootManager.useAFullPathInsideUbuntu
        if (t.split('/').any { it == "." || it == ".." }) return L.local.chrootManager.noOrInThePath
        if (t in CHROOT_SYSTEM_DIRS || (user != null && t == "/home/$user")) return L.local.chrootManager.tIsAFolderUbuntuNeeds(t, if (user != null) "/home/$user/photos" else "/mnt/photos")
        val top = "/" + t.split('/').getOrElse(1) { "" }
        if (top in CHROOT_NO_BIND_TOPS) return L.local.chrootManager.nothingCanBeSharedInsideTop(top)
        return null
    }

    private val CHROOT_SYSTEM_DIRS = setOf("", "/", "/bin", "/boot", "/dev", "/etc", "/lib", "/lib32", "/lib64", "/libx32", "/proc", "/run",
        "/sbin", "/sys", "/usr", "/var", "/tmp", "/home", "/root", "/mnt", "/media", "/opt", "/srv", "/sdcard", "/storage")
    private val CHROOT_NO_BIND_TOPS = setOf("/bin", "/boot", "/dev", "/etc", "/lib", "/lib32", "/lib64", "/libx32", "/proc", "/run",
        "/sbin", "/sys", "/usr", "/sdcard", "/storage")

    /** A folder a rootfs may be installed in (chrootctl checks it again, with its list of system folders). */
    fun validateRootPath(path: String): String? {
        val p = path.trimEnd('/')
        return when {
            !isPlainPath(p) -> L.local.chrootManager.useOnlyLettersDigitsAndIn
            !p.startsWith("/data/") -> L.local.chrootManager.theRootfsMustBeOnData
            p.split('/').size < 4 -> L.local.chrootManager.pickAFolderOfItsOwn(BASE_DIR)
            p == BASE_DIR || p.startsWith("/data/media") || p.startsWith("/data/local/tmp") || p.startsWith("/data/adb") ->
                L.local.chrootManager.thatFolderIsUsedByAndroid
            else -> null
        }
    }

    /** The app-storage location: deleted with the app (uninstall or Clear data). */
    fun appStorageDir(context: Context, name: String): String = File(context.filesDir, "chroot/$name").absolutePath

    fun isInAppStorage(context: Context, path: String): Boolean {
        val pkg = context.packageName
        return path.startsWith(context.filesDir.parentFile!!.absolutePath + "/") ||
            path.startsWith("/data/data/$pkg/") || Regex("^/data/user/\\d+/${Regex.escape(pkg)}/").containsMatchIn(path)
    }

    fun defaultArch(): String = if (ai.antigem.BuildConfig.TARGET_ABI == "x86_64") "x86_64" else "aarch64"

    // ------------------------------------------------------------------ root

    data class SuResult(val exitCode: Int, val out: List<String>, val err: String) {
        val ok: Boolean get() = exitCode == 0
        fun values(): Map<String, String> = out.mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0 && !line.startsWith("MOUNT|") && !line.startsWith("PROC|")) line.substring(0, i) to line.substring(i + 1) else null
        }.toMap()
        fun message(): String = err.lineSequence().map { it.removePrefix("ERROR: ").trim() }.filter { it.isNotEmpty() }
            .lastOrNull() ?: out.lastOrNull() ?: "exit code $exitCode"
    }

    fun b64(text: String): String = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** chrootctl.sh in the app's files (rewritten when the APK's copy changed). */
    @Synchronized
    fun ctlFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "chroot").apply { mkdirs() }
        val file = File(dir, "chrootctl.sh")
        val bytes = context.assets.open(CTL_ASSET).use { it.readBytes() }
        if (!file.exists() || !file.readBytes().contentEquals(bytes)) {
            val tmp = File(dir, "chrootctl.sh.tmp")
            tmp.writeBytes(bytes)
            tmp.setReadable(true, false)
            tmp.renameTo(file)
        }
        return file
    }

    /** The shell line su runs. Every argument is a checked path / name / number or base64: nothing to quote. */
    fun ctlLine(context: Context, args: List<String>): String {
        args.forEach { a -> require(a.none { it == '\'' || it == '"' || it == '\\' || it == '$' || it == '`' || it.isWhitespace() || it == ';' || it == '&' || it == '|' }) { "unsafe argument: $a" } }
        return "sh ${ctlFile(context).absolutePath} ${args.joinToString(" ")}"
    }

    /**
     * Runs chrootctl as root. [stdin] writes the process' input (closed afterwards); [onLine] gets every output line
     * (stdout and stderr) as it comes.
     */
    suspend fun runCtl(
        context: Context,
        args: List<String>,
        stdin: ((OutputStream) -> Unit)? = null,
        onLine: ((String) -> Unit)? = null,
        timeoutMs: Long = 120_000L
    ): SuResult = withContext(Dispatchers.IO) {
        val line = try {
            ctlLine(context, args)
        } catch (e: Exception) {
            return@withContext SuResult(-1, emptyList(), e.message ?: "bad argument")
        }
        val process = try {
            ProcessBuilder("su", "-c", line).start()
        } catch (e: IOException) {
            return@withContext SuResult(-1, emptyList(), "su is not available: this phone is not rooted, or AntiGem has no root access")
        }
        val out = Collections.synchronizedList(mutableListOf<String>())
        val err = StringBuffer()
        val tOut = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { out.add(it); onLine?.invoke(it) }
            } catch (_: IOException) {
            }
        }.apply { start() }
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { err.append(it).append('\n'); onLine?.invoke(it) }
            } catch (_: IOException) {
            }
        }.apply { start() }
        var stdinError: String? = null
        try {
            if (stdin != null) process.outputStream.use { stdin(it) } else process.outputStream.close()
        } catch (e: IOException) {
            stdinError = e.message
        }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            tOut.join(1000); tErr.join(1000)
            return@withContext SuResult(-2, out.toList(), (err.toString() + "\ntimed out").trim())
        }
        tOut.join(3000); tErr.join(3000)
        val code = process.exitValue()
        val errText = err.toString().trim().ifEmpty { if (code != 0 && stdinError != null) "input closed: $stdinError" else "" }
        SuResult(code, out.toList(), errText)
    }

    /** Asks the root manager (the first time it shows its prompt). */
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val text = withTimeoutOrNull(60_000L) { p.inputStream.bufferedReader().readText() }
            p.waitFor(5, TimeUnit.SECONDS)
            text?.trim()?.lines()?.lastOrNull()?.trim() == "0"
        } catch (_: Exception) {
            false
        }
    }

    suspend fun probe(context: Context): ChrootProbe {
        if (!checkRoot()) {
            return ChrootProbe(false, null, null, false, null, null, null,
                L.local.chrootManager.noRootAccessARootedPhone).also { _probe.value = it }
        }
        val r = runCtl(context, listOf("probe"), timeoutMs = 30_000L)
        val v = r.values()
        return ChrootProbe(
            rootOk = r.ok,
            rootManager = v["root"],
            busybox = v["busybox"]?.takeIf { it.isNotBlank() },
            canPrivateNs = !v["unshare"].isNullOrBlank() && !v["nsenter"].isNullOrBlank(),
            selinux = v["selinux"],
            kernel = v["kernel"],
            machine = v["machine"],
            error = if (r.ok) null else r.message()
        ).also { _probe.value = it }
    }

    // ------------------------------------------------------------------ discovery

    /** Looks for rootfs folders on the phone (the registry, the folders in /data/local/antigem, the first version's folder, [extraPath]). */
    suspend fun scan(context: Context, extraPath: String? = null): List<ChrootFound> {
        init(context)
        val args = mutableListOf("scan")
        if (extraPath != null && validateRootPath(extraPath) == null) args += listOf("--extra", extraPath.trimEnd('/'))
        val r = runCtl(context, args, timeoutMs = 60_000L)
        val found = mutableListOf<ChrootFound>()
        var path: String? = null
        var os = ""
        var arch = ""
        var legacyUser: String? = null
        var running = false
        var mounted = false
        var procs = 0
        var hasConf = false
        val manifest = mutableMapOf<String, String>()
        for (line in r.out) {
            val i = line.indexOf('=')
            if (line == "END") {
                path?.let { found += ChrootFound(it, os, arch.ifBlank { defaultArch() }, manifest.toMap(), legacyUser, running, mounted || running, procs, hasConf) }
                path = null; os = ""; arch = ""; legacyUser = null; running = false; mounted = false; procs = 0; hasConf = false; manifest.clear()
                continue
            }
            if (i <= 0) continue
            val k = line.substring(0, i)
            val v = line.substring(i + 1)
            when {
                k == "ROOTFS" -> path = v
                k == "OS" -> os = v
                k == "ARCH" -> arch = v
                k == "LEGACY_USER" -> legacyUser = v.trim().takeIf { isUserName(it) }
                k == "RUNNING" -> running = v == "1"
                k == "MOUNTED" -> mounted = v == "1"
                k == "PROCS" -> procs = v.toIntOrNull() ?: 0
                k == "CONF" -> hasConf = v == "1"
                k.startsWith("M_") -> manifest[k.removePrefix("M_")] = v
            }
        }
        _found.value = found
        // what AntiGem already knew: refresh its details from the manifests
        val known = _installs.value.map { inst ->
            val f = found.firstOrNull { it.path == inst.path } ?: return@map inst
            if (f.hasManifest) f.toInstall() else inst.copy(os = f.os.ifBlank { inst.os }, arch = f.arch, legacy = true)
        }
        if (known != _installs.value) saveInstalls(context, known)
        return found
    }

    /**
     * Takes over a rootfs that has no AntiGem manifest (the first chroot version's /data/local/ubuntu, or another
     * tool's): writes the manifest and the settings, registers it and moves its user to AntiGem's uid.
     */
    suspend fun adopt(context: Context, found: ChrootFound, user: String, onLine: ((String) -> Unit)? = null): Result<ChrootInstall> {
        init(context)
        if (!isUserName(user)) return Result.failure(IllegalArgumentException("Invalid user name: $user"))
        val base = found.toInstall()
        val install = base.copy(user = user, uid = android.os.Process.myUid(), legacy = false,
            name = base.name.takeIf { isInstallName(it) } ?: "ubuntu",
            source = if (found.hasManifest) base.source else "adopted",
            createdAt = if (base.createdAt > 0) base.createdAt else System.currentTimeMillis())
        // its processes run as the old uid: stop them before the user moves
        stop(context, install)
        writeMeta(context, install.path, "manifest", install.toManifest()).let { if (!it.ok) return Result.failure(IOException(it.message())) }
        if (!found.hasManifest) {
            val conf = writeMeta(context, install.path, "chroot.conf", ChrootConfig().toConf())
            if (!conf.ok) return Result.failure(IOException(conf.message()))
        }
        runCtl(context, listOf("register", "--root", install.path))
        val script = ChrootScripts.userScript(install.user, install.uid, password = "", sudoNoPassword = true, keepSudoers = true)
        val r = execAsRoot(context, install.path, script, onLine)
        if (!r.ok) return Result.failure(IOException("Could not set up user ${install.user}: ${r.message()}"))
        saveInstalls(context, _installs.value.filterNot { it.path == install.path } + install)
        return Result.success(install)
    }

    fun addOrUpdate(context: Context, install: ChrootInstall) {
        init(context)
        saveInstalls(context, _installs.value.filterNot { it.path == install.path } + install)
    }

    // ------------------------------------------------------------------ lifecycle

    /** The phone's DNS servers (written to the chroot's resolv.conf when its DNS is automatic). */
    fun androidDns(context: Context): List<String> = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.getLinkProperties(cm.activeNetwork)
        lp?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') }
            .filter { isDnsServer(it) && !it.startsWith("fe80", ignoreCase = true) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun dnsArg(context: Context): String =
        (androidDns(context) + listOf("1.1.1.1", "8.8.8.8")).distinct().take(4).joinToString(",")

    private fun tzArg(): String? = java.util.TimeZone.getDefault().id.takeIf { Regex("^[A-Za-z0-9_+/-]{1,64}$").matches(it) }

    data class StartResult(val ok: Boolean, val message: String, val bridgeUpdated: Boolean, val bridgePid: Int?)

    /**
     * Mounts the chroot (when not mounted), writes its DNS and time zone, and with [withBridge] puts this app
     * version's bridge into it. Moves the user to AntiGem's uid first when the app's uid changed (reinstall).
     */
    suspend fun start(context: Context, install: ChrootInstall? = activeInstall(context), withBridge: Boolean = false,
                      onLine: ((String) -> Unit)? = null): StartResult {
        install ?: return StartResult(false, L.local.chrootManager.noUbuntuChrootIsInstalled, false, null)
        val myUid = android.os.Process.myUid()
        if (!install.legacy && install.uid != myUid && install.uid >= 0) {
            // AntiGem was reinstalled with a new uid: the chroot's user follows it (its processes must be stopped)
            stop(context, install)
            val r = execAsRoot(context, install.path,
                ChrootScripts.userScript(install.user, myUid, password = "", sudoNoPassword = true, keepSudoers = true), onLine)
            if (r.ok) {
                val moved = install.copy(uid = myUid)
                writeMeta(context, moved.path, "manifest", moved.toManifest())
                addOrUpdate(context, moved)
            } else {
                Log.w(TAG, "uid migration failed: ${r.message()}")
            }
        }
        val args = mutableListOf("start", "--root", install.path, "--dns", dnsArg(context))
        tzArg()?.let { args += listOf("--tz", it) }
        if (withBridge) {
            val bridge = linuxBridgeFile(context) ?: return StartResult(false,
                if (ai.antigem.BuildConfig.HAS_CHROOT) L.local.chrootManager.thisAppHasNoLinuxBridge
                else L.local.chrootManager.thisIsTheNormalBuildOf, false, null)
            args += listOf("--bridge", bridge.absolutePath)
        }
        val r = runCtl(context, args, onLine = onLine, timeoutMs = 60_000L)
        val v = r.values()
        return StartResult(r.ok, if (r.ok) "started" else r.message(), v["bridge"] == "updated", v["bridge_pid"]?.toIntOrNull())
    }

    /** Stops every process of the chroot and unmounts it. */
    suspend fun stop(context: Context, install: ChrootInstall? = activeInstall(context)): SuResult {
        install ?: return SuResult(-1, emptyList(), L.local.chrootManager.noUbuntuChrootIsInstalled)
        return runCtl(context, listOf("stop", "--root", install.path), timeoutMs = 60_000L).also { refreshStatus(context, install) }
    }

    suspend fun refreshStatus(context: Context, install: ChrootInstall? = activeInstall(context)): ChrootStatus? {
        install ?: return null.also { _status.value = null }
        val r = runCtl(context, listOf("status", "--root", install.path), timeoutMs = 30_000L)
        if (!r.ok) {
            val s = ChrootStatus(false, ChrootNsMode.PRIVATE, null, null, null, 0, emptyList(), emptyList(), ChrootConfig(), emptyMap(), r.message(), install.path)
            _status.value = s
            return s
        }
        val v = r.values()
        val mounts = r.out.filter { it.startsWith("MOUNT|") }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size >= 5) ChrootMount(p[1], p[2], p[3], p[4]) else null
        }
        val procs = r.out.filter { it.startsWith("PROC|") }.mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size == 4) ChrootProcess(p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: -1, p[3]) else null
        }
        val conf = v.filterKeys { it.startsWith("C_") }.mapKeys { it.key.removePrefix("C_") }
        val manifest = v.filterKeys { it.startsWith("M_") }.mapKeys { it.key.removePrefix("M_") }
        val holder = v["holder"]?.toIntOrNull()
        val s = ChrootStatus(
            running = holder != null || mounts.isNotEmpty(),
            nsMode = ChrootNsMode.of(v["ns_mode"]),
            holderPid = holder,
            sinceEpochSec = v["since"]?.toLongOrNull(),
            bridgePid = v["bridge"]?.toIntOrNull(),
            processCount = v["procs"]?.toIntOrNull() ?: procs.size,
            mounts = mounts,
            processes = procs,
            config = ChrootConfig.parse(conf),
            manifest = manifest,
            path = install.path
        )
        _status.value = s
        return s
    }

    suspend fun refreshUsage(context: Context, install: ChrootInstall? = activeInstall(context)): ChrootUsage? {
        install ?: return null
        val r = runCtl(context, listOf("du", "--root", install.path), timeoutMs = 300_000L)
        if (!r.ok) return null
        val v = r.values()
        val u = ChrootUsage(v["total"]?.toLongOrNull() ?: 0L,
            v.filterKeys { it.startsWith("du_") }.mapKeys { it.key.removePrefix("du_") }.mapValues { it.value.toLongOrNull() ?: 0L })
        _usage.value = u
        return u
    }

    suspend fun unmount(context: Context, mountPoint: String, install: ChrootInstall? = activeInstall(context)): SuResult {
        install ?: return SuResult(-1, emptyList(), L.local.chrootManager.noChroot)
        return runCtl(context, listOf("umount", "--root", install.path, "--path", mountPoint)).also { refreshStatus(context, install) }
    }

    /** Every mount under [install] in every mount namespace on the phone (what Delete unmounts first): pid to mount point. */
    suspend fun mountsEverywhere(context: Context, install: ChrootInstall): Result<List<Pair<Int, String>>> {
        val r = runCtl(context, listOf("mounts-all", "--root", install.path), timeoutMs = 120_000L)
        if (!r.ok) return Result.failure(IOException(r.message()))
        return Result.success(r.out.filter { it.startsWith("MOUNTANY|") }.mapNotNull { line ->
            val p = line.split('|', limit = 3)
            if (p.size == 3) (p[1].toIntOrNull() ?: 0) to p[2] else null
        })
    }

    suspend fun killProcess(context: Context, pid: Int, install: ChrootInstall? = activeInstall(context)): SuResult {
        install ?: return SuResult(-1, emptyList(), L.local.chrootManager.noChroot)
        return runCtl(context, listOf("kill", "--root", install.path, "--pid", pid.toString())).also { refreshStatus(context, install) }
    }

    /** Deletes the rootfs (chrootctl refuses while anything inside it is mounted, in any namespace). */
    suspend fun delete(context: Context, install: ChrootInstall, onLine: ((String) -> Unit)? = null): SuResult {
        val r = runCtl(context, listOf("delete", "--root", install.path), onLine = onLine, timeoutMs = 30 * 60_000L)
        if (r.ok) {
            forget(context, install.path)
            if (AuthPreferences(context).isChrootModeSync() && activeInstall(context) == null) {
                useTermux(context)
                // the chroot marked the phone's environment as installed: without AntiGem's own one it is not
                if (!ai.antigem.data.local.LocalEnvironmentManager.isTermuxInstalled(context)) AuthPreferences(context).setLocalToolsInstalled(false)
            }
            _status.value = null
            _usage.value = null
        }
        return r
    }

    /**
     * Reopens the app, e.g. after the chroot in use was deleted: the bridge, agents and terminals then start in AntiGem's
     * environment. Writes the settings saved with apply() first, which exit() would otherwise lose.
     */
    fun reopenApp(context: Context) {
        prefs(context).edit().commit()
        AuthPreferences(context).flushSync()
        ai.antigem.data.pc.PcConnect.reopen(context)
    }

    suspend fun writeMeta(context: Context, root: String, name: String, content: String): SuResult =
        runCtl(context, listOf("put-meta", "--root", root, "--name", name), stdin = { it.write(content.toByteArray()) })

    suspend fun saveConfig(context: Context, install: ChrootInstall, config: ChrootConfig): SuResult =
        writeMeta(context, install.path, "chroot.conf", config.toConf()).also { refreshStatus(context, install) }

    /** Runs [script] as root inside the chroot (mounting it first); [onLine] gets its output. */
    suspend fun execAsRoot(context: Context, root: String, script: String, onLine: ((String) -> Unit)? = null,
                           timeoutMs: Long = 60 * 60_000L): SuResult =
        runCtl(context, listOf("exec", "--root", root, "--as-root", "--term", "dumb", "--cmd", b64(script)), onLine = onLine, timeoutMs = timeoutMs)

    /**
     * Copies the file [path] of the chroot (read as root) to [dest] in the app's own storage, e.g. an APK built in the
     * chroot for Android's installer: the app cannot read the rootfs itself.
     */
    suspend fun copyOut(context: Context, install: ChrootInstall, path: String, dest: File): SuResult {
        val quoted = "'" + path.replace("'", "'\\''") + "'"
        dest.parentFile?.mkdirs()
        val r = runCtlStreaming(context, listOf("exec", "--root", install.path, "--as-root", "--term", "dumb", "--cmd", b64("exec cat -- $quoted")), null) { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        if (!r.ok) dest.delete()
        return r
    }

    /** Runs [script] as root in the chroot and hands its (binary) output to [consume], e.g. a tar of a folder. */
    suspend fun streamAsRoot(context: Context, install: ChrootInstall, script: String, consume: (java.io.InputStream) -> Unit): SuResult =
        runCtlStreaming(context, listOf("exec", "--root", install.path, "--as-root", "--term", "dumb", "--cmd", b64(script)), null, consume)

    /** Runs a root task with its log shown on the chroot page. */
    fun runTask(context: Context, title: String, block: suspend ((String) -> Unit) -> SuResult) {
        if (_task.value?.running == true) return
        _task.value = ChrootTask(title)
        scope.launch {
            val r = try {
                block { line -> _task.value = _task.value?.let { t -> t.copy(lines = (t.lines + line).takeLast(2000)) } }
            } catch (e: Exception) {
                SuResult(-1, emptyList(), e.message ?: e.toString())
            }
            if (!r.ok && r.err.isNotBlank()) {
                _task.value = _task.value?.let { it.copy(lines = (it.lines + "ERROR: ${r.message()}").takeLast(2000)) }
            }
            _task.value = _task.value?.copy(running = false, exitCode = r.exitCode)
            refreshStatus(context)
        }
    }

    fun clearTask() {
        if (_task.value?.running != true) _task.value = null
    }

    // ------------------------------------------------------------------ the bridge

    /** This app's Linux build of the bridge (a static binary; the Termux one needs Android's linker), unpacked once per app version. */
    @Synchronized
    fun linuxBridgeFile(context: Context): File? {
        val dir = File(context.applicationContext.filesDir, "chroot").apply { mkdirs() }
        val file = File(dir, "agy_ide_bridge_linux")
        val stamp = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        } catch (_: Exception) {
            ai.antigem.BuildConfig.VERSION_NAME
        }
        val p = prefs(context)
        if (file.exists() && p.getString(KEY_BRIDGE_UNPACKED, null) == stamp) return file
        return try {
            val tmp = File(dir, "agy_ide_bridge_linux.tmp")
            context.assets.open(LINUX_BRIDGE_ASSET).use { raw ->
                org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(raw, 64 * 1024)).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(file)) throw IOException("rename failed")
            p.edit().putString(KEY_BRIDGE_UNPACKED, stamp).apply()
            file
        } catch (e: Exception) {
            Log.e(TAG, "Linux bridge unpack failed", e)
            null
        }
    }

    /** Starts the bridge in the chroot as its user, detached (it outlives the app when the exit setting keeps it). */
    suspend fun startBridge(context: Context, command: String, install: ChrootInstall? = activeInstall(context)): SuResult {
        install ?: return SuResult(-1, emptyList(), L.local.chrootManager.noUbuntuChrootIsInstalled)
        return runCtl(context, listOf("bridge-start", "--root", install.path, "--user", install.user, "--cmd", b64(command)), timeoutMs = 60_000L)
    }

    suspend fun stopBridge(context: Context, install: ChrootInstall? = activeInstall(context)): SuResult {
        install ?: return SuResult(-1, emptyList(), L.local.chrootManager.noChroot)
        return runCtl(context, listOf("bridge-stop", "--root", install.path), timeoutMs = 30_000L)
    }

    // ------------------------------------------------------------------ terminals

    /**
     * Arguments of a terminal session (/system/bin/sh) that runs [command] in the chroot as its user, or a login
     * shell when there is none. [cwd] is a folder inside the chroot.
     */
    fun terminalArgs(context: Context, cwd: String?, command: String?, execShellAfterCommand: Boolean, term: String = "xterm-256color"): Array<String> {
        val install = activeInstall(context) ?: error("No Ubuntu chroot is installed")
        val args = mutableListOf("exec", "--root", install.path, "--user", install.user, "--term", term)
        if (!cwd.isNullOrBlank()) args += listOf("--cwd", b64(cwd))
        if (!command.isNullOrBlank()) {
            val full = if (execShellAfterCommand) "$command\nexec \"\${SHELL:-/bin/bash}\" -l" else command
            args += listOf("--cmd", b64(full))
        }
        return arrayOf("sh", "-c", "exec su -c '${ctlLine(context, args)}'")
    }

    /** A terminal that follows the bridge's log (the Server Runner session while the bridge runs detached). */
    fun bridgeLogArgs(context: Context): Array<String> {
        val install = activeInstall(context) ?: error("No Ubuntu chroot is installed")
        return arrayOf("sh", "-c", "exec su -c '${ctlLine(context, listOf("bridge-log", "--root", install.path))}'")
    }

    // ------------------------------------------------------------------ backups

    enum class BackupFormat(val ext: String, val mime: String, val label: String) {
        GZIP("tar.gz", "application/gzip", ".tar.gz (fast)"),
        XZ("tar.xz", "application/x-xz", ".tar.xz (smaller, slow)")
    }

    /** Counts and hashes what is written (the file's size and sha256). */
    private class CountingOutputStream(out: OutputStream, private val digest: java.security.MessageDigest) : java.io.FilterOutputStream(out) {
        @Volatile var count = 0L
        override fun write(b: Int) { out.write(b); digest.update(b.toByte()); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); digest.update(b, off, len); count += len }
    }

    /** chrootctl as root with its stdout (binary) handed to [consume]; its stderr lines go to [onLine]. */
    private suspend fun runCtlStreaming(context: Context, args: List<String>, onLine: ((String) -> Unit)?, consume: (java.io.InputStream) -> Unit): SuResult =
        withContext(Dispatchers.IO) {
            val line = try { ctlLine(context, args) } catch (e: Exception) { return@withContext SuResult(-1, emptyList(), e.message ?: "bad argument") }
            val process = try { ProcessBuilder("su", "-c", line).start() } catch (e: IOException) {
                return@withContext SuResult(-1, emptyList(), L.local.chrootManager.suIsNotAvailable)
            }
            process.outputStream.close()
            val err = StringBuffer()
            val tErr = Thread {
                try { process.errorStream.bufferedReader().forEachLine { err.append(it).append('\n'); onLine?.invoke(it) } } catch (_: IOException) {}
            }.apply { start() }
            var failure: String? = null
            try { consume(process.inputStream) } catch (e: Exception) { failure = e.message ?: e.toString(); process.destroy() }
            process.waitFor()
            tErr.join(3000)
            SuResult(if (failure != null) -3 else process.exitValue(), emptyList(), (err.toString() + (failure?.let { "\n$it" } ?: "")).trim())
        }

    /**
     * Backs [install] up into [dest] (a document the user picked): chrootctl streams a tar of the rootfs (owners kept,
     * without /proc, /sdcard, shared folders …), compressed here. [clean]: a shareable image without homes and logins.
     * [shaDest] gets "<sha256>  <name>". A failed backup's file is removed.
     */
    suspend fun backup(context: Context, install: ChrootInstall, dest: Uri, format: BackupFormat, clean: Boolean,
                       totalKbHint: Long?, shaDest: Uri? = null, shaName: String? = null, onLine: (String) -> Unit): SuResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val raw = try { resolver.openOutputStream(dest, "w") } catch (e: Exception) { null }
            ?: return@withContext SuResult(-1, emptyList(), L.local.chrootManager.cannotWriteTheChosenFile)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val counting = CountingOutputStream(java.io.BufferedOutputStream(raw, 1 shl 20), digest)
        var read = 0L
        val started = System.currentTimeMillis()
        onLine(if (clean) L.local.chrootManager.readingPathCleanImageNoHomes(install.path) else L.local.chrootManager.readingPath(install.path))
        val args = listOf("backup", "--root", install.path) + if (clean) listOf("--clean") else emptyList()
        val r = runCtlStreaming(context, args, onLine) { input ->
            val compressor: OutputStream = when (format) {
                BackupFormat.GZIP -> java.util.zip.GZIPOutputStream(counting, 1 shl 16)
                BackupFormat.XZ -> org.tukaani.xz.XZOutputStream(counting, org.tukaani.xz.LZMA2Options(6))
            }
            compressor.use { c ->
                val buf = ByteArray(1 shl 18)
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    c.write(buf, 0, n)
                    read += n
                    val now = System.currentTimeMillis()
                    if (now - last > 3000) {
                        last = now
                        val pct = totalKbHint?.takeIf { it > 0 }?.let { " (~${(read * 100 / (it * 1024)).coerceAtMost(99)}%)" }.orEmpty()
                        onLine(L.local.chrootManager.valueMbReadPctValue2Mb(read / (1 shl 20), pct, counting.count / (1 shl 20)))
                    }
                }
            }
        }
        if (!r.ok || read == 0L) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, dest) }
            return@withContext if (r.ok) SuResult(1, emptyList(), L.local.chrootManager.nothingWasReadFromTheRootfs) else r
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (shaDest != null) {
            runCatching { resolver.openOutputStream(shaDest, "w")?.use { it.write("$sha  ${shaName ?: "rootfs"}\n".toByteArray()) } }
                .onFailure { onLine(L.local.chrootManager.couldNotWriteTheSha256File(it.message)) }
        }
        val secs = (System.currentTimeMillis() - started) / 1000
        onLine(L.local.chrootManager.doneInValueMinValue2S(secs / 60, secs % 60, counting.count / (1 shl 20), read / (1 shl 20), sha))
        r
    }

    /**
     * A clean image for the "AntiGem Ubuntu (prebuilt)" source: [ChrootInstaller.prebuiltAsset] plus its .sha256,
     * created in the folder [treeUri] the user picked.
     */
    suspend fun exportCleanImage(context: Context, install: ChrootInstall, treeUri: Uri, totalKbHint: Long?, onLine: (String) -> Unit): SuResult {
        val resolver = context.contentResolver
        val name = ChrootInstaller.prebuiltAsset()
        val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri))
        val archive = runCatching { android.provider.DocumentsContract.createDocument(resolver, dir, "application/octet-stream", name) }.getOrNull()
            ?: return SuResult(-1, emptyList(), L.local.chrootManager.cannotCreateNameInThatFolder(name))
        val sha = runCatching { android.provider.DocumentsContract.createDocument(resolver, dir, "application/octet-stream", "$name.sha256") }.getOrNull()
        onLine(L.local.chrootManager.writingNameAndNameSha256Upload(name, ChrootInstaller.PREBUILT_TAG))
        if (!install.os.contains("24.04")) onLine(L.local.chrootManager.thePrebuiltSourceIsNamedFor(install.os))
        val r = backup(context, install, archive, BackupFormat.XZ, clean = true, totalKbHint = totalKbHint, shaDest = sha, shaName = name, onLine = onLine)
        if (!r.ok) sha?.let { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, it) } }
        return r
    }

    // ------------------------------------------------------------------ app exit

    /**
     * AntiGem is exiting (notification / drawer Exit): applies the chosen exit behavior. Blocks for a few seconds at
     * most (it runs right before the process ends).
     */
    /** A choice made in the Exit dialog for this one exit (null: the saved setting). */
    @Volatile
    var exitOverride: ChrootExitBehavior? = null

    fun onAppExit(context: Context) {
        if (!isActive(context)) return
        val behavior = exitOverride ?: exitBehavior(context)
        exitOverride = null
        if (behavior == ChrootExitBehavior.KEEP_RUNNING) return
        runBlocking {
            withTimeoutOrNull(6_000L) {
                when (behavior) {
                    ChrootExitBehavior.STOP_BRIDGE -> stopBridge(context)
                    ChrootExitBehavior.STOP_ALL -> stop(context)
                    else -> Unit
                }
            }
        }
    }

    /** Stops everything of the chroot (before the app's data is cleared, a reset, or switching to Termux). */
    fun stopAllBlocking(context: Context, timeoutMs: Long = 8_000L) {
        if (activeInstall(context) == null) return
        runBlocking { withTimeoutOrNull(timeoutMs) { stop(context) } }
    }

    const val ENV_TERMUX = "TERMUX"
    const val ENV_CHROOT = "UBUNTU_CHROOT"
}
