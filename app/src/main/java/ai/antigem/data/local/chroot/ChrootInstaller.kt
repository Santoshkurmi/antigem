package ai.antigem.data.local.chroot

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import ai.antigem.i18n.L

enum class UbuntuRelease(val version: String, val label: String) {
    NOBLE("24.04", "Ubuntu 24.04 LTS (Noble)"),
    RESOLUTE("26.04", "Ubuntu 26.04 LTS"),
    JAMMY("22.04", "Ubuntu 22.04 LTS (Jammy)")
}

sealed class ChrootSource {
    /** Ubuntu Base from cdimage.ubuntu.com: the newest point release, checked against Ubuntu's SHA256SUMS. */
    data class Official(val release: UbuntuRelease) : ChrootSource()
    /** AntiGem's preconfigured Ubuntu from its GitHub release. */
    object Prebuilt : ChrootSource()
    data class Url(val url: String, val sha256: String?) : ChrootSource()
    data class LocalFile(val uri: Uri, val displayName: String) : ChrootSource()
}

enum class ChrootPackageBundle(private val titleText: () -> String, private val descriptionText: () -> String, val packages: List<String>, val nodeJs: Boolean = false) {
    ESSENTIALS({ L.local.chrootInstaller.essentials }, { L.local.chrootInstaller.sudoCertificatesCurlWgetGitRipgrep },
        listOf("sudo", "ca-certificates", "curl", "wget", "git", "ripgrep", "procps", "tzdata", "less", "nano", "unzip", "xz-utils", "gnupg", "openssh-client")),
    BUILD({ L.local.chrootInstaller.buildTools }, { "build-essential, pkg-config, cmake, make" }, listOf("build-essential", "pkg-config", "cmake", "make")),
    PYTHON({ "Python" }, { "python3, pip, venv" }, listOf("python3", "python3-pip", "python3-venv")),
    NODE({ "Node.js 22 LTS" }, { L.local.chrootInstaller.fromNodesourceUbuntusOwnNodeJs }, emptyList(), nodeJs = true),
    TOOLS({ L.local.chrootInstaller.commandLineExtras }, { "htop, tmux, jq, zip, file, vim, ping, dig" }, listOf("htop", "tmux", "jq", "zip", "file", "vim", "iputils-ping", "dnsutils")),
    ANDROID({ L.local.chrootInstaller.androidTools }, { L.local.chrootInstaller.adbFastbootLsusbFlashOrDebug }, listOf("adb", "fastboot", "usbutils"));
    val title: String get() = titleText()
    val description: String get() = descriptionText()
}

data class ChrootInstallRequest(
    val source: ChrootSource,
    val path: String,
    val name: String,
    val user: String,
    val password: String,
    val sudoNoPassword: Boolean,
    val config: ChrootConfig,
    val bundles: Set<ChrootPackageBundle>,
    val extraPackages: List<String>,
    val activate: Boolean = true
)

enum class ChrootInstallStep(private val labelText: () -> String) {
    CHECK({ L.local.chrootInstaller.checkRootAndSpace }),
    DOWNLOAD({ L.local.chrootInstaller.download }),
    VERIFY({ L.local.chrootInstaller.verifyTheChecksum }),
    EXTRACT({ L.local.chrootInstaller.extractTheRootfs }),
    CONFIGURE({ L.local.chrootInstaller.setUpUbuntuAndTheUser }),
    PACKAGES({ L.local.chrootInstaller.installPackages }),
    FINISH({ L.local.chrootInstaller.finish });
    val label: String get() = labelText()
}

data class ChrootInstallState(
    val running: Boolean = false,
    val step: ChrootInstallStep? = null,
    /** 0..1, or null while the step cannot tell. */
    val progress: Float? = null,
    val detail: String = "",
    val done: Set<ChrootInstallStep> = emptySet(),
    val error: String? = null,
    val failedStep: ChrootInstallStep? = null,
    val result: ChrootInstall? = null
) {
    val finished: Boolean get() = result != null
}

/** Installs an Ubuntu chroot (the full-page setup in Settings and at first launch). */
object ChrootInstaller {
    private const val TAG = "ChrootInstaller"
    const val PREBUILT_REPO = "Santoshkurmi/antigem"
    const val PREBUILT_TAG = "chroot-ubuntu-v1"
    const val PREBUILT_PAGE = "https://github.com/$PREBUILT_REPO/releases/tag/$PREBUILT_TAG"
    private const val MIN_FREE_BYTES = 1L shl 30

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(ChrootInstallState())
    val state: StateFlow<ChrootInstallState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var job: Job? = null
    private var lastRequest: ChrootInstallRequest? = null
    /** The rootfs folder this installer created (so a retry may clear its own partial extraction). */
    private var createdPath: String? = null
    private var downloaded: Pair<String, File>? = null
    /** The archive was an AntiGem backup (its manifest came with it): its user, settings and packages are kept. */
    private var restoredBackup: ChrootFound? = null

    private fun log(line: String) {
        Log.d(TAG, line)
        _logs.value = (_logs.value + line).takeLast(3000)
    }

    private fun set(step: ChrootInstallStep, detail: String, progress: Float? = null) {
        _state.value = _state.value.copy(running = true, step = step, detail = detail, progress = progress, error = null, failedStep = null)
    }

    private fun done(step: ChrootInstallStep) {
        _state.value = _state.value.copy(done = _state.value.done + step)
    }

    private class InstallError(message: String) : Exception(message)

    fun prebuiltAsset(): String = "ubuntu-24.04-antigem-${if (ChrootManager.defaultArch() == "x86_64") "amd64" else "arm64"}.tar.xz"

    /**
     * sha256 of each published prebuilt image, by asset name. The image is only offered once its hash is here: the
     * `.sha256` next to it on the release would come from the same account, so it only catches a broken download.
     * Add the line when the image is published (GitHub shows the digest on the release page).
     */
    private val PREBUILT_SHA256: Map<String, String> = mapOf()
    fun prebuiltUrl(): String = "https://github.com/$PREBUILT_REPO/releases/download/$PREBUILT_TAG/${prebuiltAsset()}"

    /** Whether AntiGem's prebuilt image is published for this CPU (null: no answer). */
    suspend fun prebuiltAvailable(): Boolean? = withContext(Dispatchers.IO) {
        if (PREBUILT_SHA256[prebuiltAsset()] == null) return@withContext false
        try {
            http.newCall(Request.Builder().url(prebuiltUrl()).head().build()).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            null
        }
    }

    fun start(context: Context, request: ChrootInstallRequest) {
        if (job?.isActive == true) return
        lastRequest = request
        restoredBackup = null
        _logs.value = emptyList()
        _state.value = ChrootInstallState(running = true)
        job = ChrootManager.scope.launch { run(context.applicationContext, request, ChrootInstallStep.CHECK) }
    }

    /** Runs again from the failed step when the rootfs is already extracted, else from the start. */
    fun retry(context: Context) {
        val request = lastRequest ?: return
        if (job?.isActive == true) return
        val failed = _state.value.failedStep
        val from = if (failed != null && failed.ordinal >= ChrootInstallStep.CONFIGURE.ordinal) failed else ChrootInstallStep.CHECK
        _state.value = _state.value.copy(running = true, error = null, failedStep = null,
            done = _state.value.done.filter { it.ordinal < from.ordinal }.toSet())
        job = ChrootManager.scope.launch { run(context.applicationContext, request, from) }
    }

    /** Stops between steps and during the download (a running extraction or apt finishes on its own). */
    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(running = false, error = L.local.chrootInstaller.cancelled, failedStep = _state.value.step)
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.value = ChrootInstallState()
        _logs.value = emptyList()
    }

    private suspend fun run(context: Context, req: ChrootInstallRequest, from: ChrootInstallStep) {
        var step = from
        try {
            if (from <= ChrootInstallStep.CHECK) { step = ChrootInstallStep.CHECK; check(context, req); done(step) }
            var archive: File? = null
            var sha: String? = null
            if (from <= ChrootInstallStep.EXTRACT && req.source !is ChrootSource.LocalFile) {
                step = ChrootInstallStep.DOWNLOAD
                val (file, expected) = download(context, req.source)
                archive = file; sha = expected
                done(step)
                step = ChrootInstallStep.VERIFY
                verify(file, expected)
                done(step)
            } else if (from <= ChrootInstallStep.EXTRACT) {
                done(ChrootInstallStep.DOWNLOAD); done(ChrootInstallStep.VERIFY)
            }
            if (from <= ChrootInstallStep.EXTRACT) { step = ChrootInstallStep.EXTRACT; extract(context, req, archive); done(step) }
            if (from <= ChrootInstallStep.CONFIGURE) { step = ChrootInstallStep.CONFIGURE; configure(context, req); done(step) }
            if (from <= ChrootInstallStep.PACKAGES) { step = ChrootInstallStep.PACKAGES; packages(context, req); done(step) }
            step = ChrootInstallStep.FINISH
            val install = finish(context, req)
            done(step)
            // the archive is only kept for a retry
            downloaded?.second?.delete(); downloaded = null
            _state.value = _state.value.copy(running = false, step = null, progress = 1f, detail = L.local.chrootInstaller.ready, result = install)
            log("✅ Ubuntu chroot ready at ${install.path} (user ${install.user}, uid ${install.uid})")
            if (sha != null) Log.d(TAG, "installed from an archive with sha256 $sha")
        } catch (e: kotlinx.coroutines.CancellationException) {
            log("Cancelled.")
            _state.value = _state.value.copy(running = false, error = L.local.chrootInstaller.cancelled, failedStep = step)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            log("❌ $msg")
            _state.value = _state.value.copy(running = false, error = msg, failedStep = step)
        }
    }

    // ------------------------------------------------------------------ steps

    private suspend fun check(context: Context, req: ChrootInstallRequest) {
        set(ChrootInstallStep.CHECK, L.local.chrootInstaller.askingForRootAccess)
        log("Checking root access (accept the root manager's prompt)…")
        val probe = ChrootManager.probe(context)
        if (!probe.rootOk) throw InstallError(probe.error ?: "No root access.")
        log("Root: ${probe.rootManager ?: "granted"} · busybox: ${probe.busybox ?: "none (toybox)"} · SELinux: ${probe.selinux} · kernel ${probe.kernel}")
        if (req.config.nsMode == ChrootNsMode.PRIVATE && !probe.canPrivateNs) {
            throw InstallError(L.local.chrootInstaller.thisPhoneHasNoUnshareNsenter)
        }
        ChrootManager.validateRootPath(req.path)?.let { throw InstallError(it) }
        if (!ChrootManager.isUserName(req.user)) throw InstallError(L.local.chrootInstaller.theUserNameMayOnlyHave)
        if (req.config.nsMode == ChrootNsMode.GLOBAL && ChrootManager.isInAppStorage(context, req.path)) {
            throw InstallError(L.local.chrootInstaller.aChrootInTheAppsStorage)
        }
        val free = try { StatFs("/data").availableBytes } catch (_: Exception) { -1L }
        if (free in 0 until MIN_FREE_BYTES) throw InstallError(L.local.chrootInstaller.onlyValueMbFreeOnData(free / (1 shl 20)))
        log("Free on /data: ${if (free >= 0) "${free / (1 shl 20)} MB" else "unknown"}")
    }

    /** Downloads the source (unless the same verified archive is still there): the file and its expected sha256. */
    private suspend fun download(context: Context, source: ChrootSource): Pair<File, String?> {
        set(ChrootInstallStep.DOWNLOAD, L.local.chrootInstaller.findingTheRelease)
        val (url, sha) = when (source) {
            is ChrootSource.Official -> resolveOfficial(source.release)
            is ChrootSource.Prebuilt -> {
                val pinned = PREBUILT_SHA256[prebuiltAsset()]
                    ?: throw InstallError(L.local.chrootInstaller.antigemsPrebuiltUbuntuIsNotPublished(PREBUILT_PAGE))
                prebuiltUrl() to pinned
            }
            is ChrootSource.Url -> {
                val pinned = source.sha256?.trim()?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                // the rootfs runs as root: a plain http:// download is only taken with a hash the user got elsewhere
                if (!source.url.startsWith("https://", ignoreCase = true) && pinned == null) {
                    throw InstallError(L.local.chrootInstaller.aPlainHttpLinkNeedsIts)
                }
                source.url to pinned
            }
            is ChrootSource.LocalFile -> error("no download for a local file")
        }
        log("Source: $url")
        sha?.let { log("Expected sha256: $it") }
        downloaded?.let { (u, f) -> if (u == url && f.exists()) { log("Using the archive downloaded before."); return f to sha } }
        val ext = when {
            url.endsWith(".tar.xz") || url.endsWith(".txz") -> "tar.xz"
            url.endsWith(".tar.gz") || url.endsWith(".tgz") -> "tar.gz"
            else -> "archive"
        }
        val dir = File(context.cacheDir, "chroot").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "rootfs.$ext")
        val part = File(dir, "rootfs.$ext.part")
        val response = try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "AntiGem-Chroot").build()).execute()
        } catch (e: IOException) {
            throw InstallError(L.local.chrootInstaller.downloadFailedMessage(e.message))
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw InstallError(L.local.chrootInstaller.downloadFailedHttpCodeForUrl(resp.code, url))
            val body = resp.body ?: throw InstallError(L.local.chrootInstaller.downloadFailedEmptyAnswer)
            val total = body.contentLength()
            var read = 0L
            val started = System.currentTimeMillis()
            var last = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - last > 200) {
                            last = now
                            val speed = read / ((now - started).coerceAtLeast(1) / 1000.0) / (1 shl 20)
                            set(ChrootInstallStep.DOWNLOAD,
                                "${read / (1 shl 20)} MB${if (total > 0) " of ${total / (1 shl 20)} MB" else ""} · ${"%.1f".format(speed)} MB/s",
                                if (total > 0) read.toFloat() / total else null)
                        }
                    }
                }
            }
            if (total > 0 && read != total) throw InstallError(L.local.chrootInstaller.downloadIncompleteReadOfTotalBytes(read, total))
        }
        if (!part.renameTo(file)) throw InstallError(L.local.chrootInstaller.couldNotSaveTheDownload)
        log("Downloaded ${file.length() / (1 shl 20)} MB")
        downloaded = url to file
        return file to sha
    }

    private fun resolveOfficial(release: UbuntuRelease): Pair<String, String> {
        val arch = if (ChrootManager.defaultArch() == "x86_64") "amd64" else "arm64"
        val dir = "https://cdimage.ubuntu.com/ubuntu-base/releases/${release.version}/release/"
        val sums = httpText(dir + "SHA256SUMS") ?: throw InstallError(L.local.chrootInstaller.couldNotReachCdimageUbuntuCom)
        val pattern = Regex("^([0-9a-f]{64}) \\*?(ubuntu-base-(${Regex.escape(release.version)}(?:\\.\\d+)?)-base-$arch\\.tar\\.gz)$")
        val best = sums.lines().mapNotNull { pattern.find(it.trim()) }
            .maxByOrNull { m -> m.groupValues[3].split('.').map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(2) { 0 } } }
            ?: throw InstallError(L.local.chrootInstaller.noUbuntuVersionBaseImageFor(release.version, arch))
        log("Newest point release: ${best.groupValues[2]}")
        return (dir + best.groupValues[2]) to best.groupValues[1]
    }

    private fun httpText(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "AntiGem-Chroot").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun verify(file: File, expected: String?) {
        if (expected == null) {
            set(ChrootInstallStep.VERIFY, L.local.chrootInstaller.noChecksumGivenSkipped)
            log("No checksum to verify against.")
            return
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length().coerceAtLeast(1)
        var read = 0L
        var last = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                read += n
                val now = System.currentTimeMillis()
                if (now - last > 200) { last = now; set(ChrootInstallStep.VERIFY, L.local.chrootInstaller.checking, read.toFloat() / total) }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete(); downloaded = null
            throw InstallError(L.local.chrootInstaller.checksumMismatchTheDownloadIsDamaged(actual))
        }
        log("✅ sha256 matches")
    }

    private suspend fun extract(context: Context, req: ChrootInstallRequest, archive: File?) {
        set(ChrootInstallStep.EXTRACT, L.local.chrootInstaller.preparingPath(req.path))
        var prep = ChrootManager.runCtl(context, listOf("prepare", "--root", req.path))
        if (!prep.ok && createdPath == req.path) {
            // our own partial extraction from the failed attempt
            log("Removing the partial extraction from the last attempt…")
            val del = ChrootManager.runCtl(context, listOf("delete", "--root", req.path), timeoutMs = 30 * 60_000L)
            if (!del.ok) throw InstallError(L.local.chrootInstaller.couldNotClearPathMessage(req.path, del.message()))
            prep = ChrootManager.runCtl(context, listOf("prepare", "--root", req.path))
        }
        if (!prep.ok) throw InstallError(prep.message())
        createdPath = req.path
        // a manifest right away, so a half-extracted folder can still be deleted by chrootctl
        val partial = ChrootInstall(req.path, req.name, "installing", ChrootManager.defaultArch(), req.user,
            android.os.Process.myUid(), sourceLabel(req.source), System.currentTimeMillis(), false)
        ChrootManager.writeMeta(context, req.path, "manifest", partial.toManifest() + "INSTALLING=1\n")
            .let { if (!it.ok) throw InstallError(L.local.chrootInstaller.couldNotWriteTheManifestMessage(it.message())) }

        val (raw, total) = openSource(context, req.source, archive)
        val counting = CountingInputStream(raw)
        val tarStream = decompress(BufferedInputStream(counting, 256 * 1024))
        log("Extracting into ${req.path} (as root, owners and permissions kept)…")
        var last = 0L
        val r = ChrootManager.runCtl(context, listOf("extract", "--root", req.path), stdin = { out ->
            tarStream.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    val now = System.currentTimeMillis()
                    if (now - last > 250) {
                        last = now
                        val mb = counting.count / (1 shl 20)
                        set(ChrootInstallStep.EXTRACT, if (total > 0) L.local.chrootInstaller.mbMbOfTotalMbRead(mb, total / (1 shl 20)) else L.local.chrootInstaller.mbMbRead(mb),
                            if (total > 0) counting.count.toFloat() / total else null)
                    }
                }
            }
        }, onLine = { log(it) }, timeoutMs = 60 * 60_000L)
        if (!r.ok) throw InstallError(L.local.chrootInstaller.extractionFailedMessage(r.message()))
        log("✅ Extracted")
        // a backup made by AntiGem brings its manifest (the one written before extracting says INSTALLING)
        val f = ChrootManager.scan(context, req.path).firstOrNull { it.path == req.path }
        restoredBackup = f?.takeIf { it.hasManifest && it.manifest["INSTALLING"] == null && !it.manifest["USER"].isNullOrBlank() }
        restoredBackup?.let {
            log("This is an AntiGem backup of ${it.manifest["NAME"] ?: "an Ubuntu chroot"} (user ${it.manifest["USER"]}): " +
                "its user, home, settings and packages are kept; the user moves to this app's uid.")
        }
    }

    private fun openSource(context: Context, source: ChrootSource, archive: File?): Pair<InputStream, Long> = when (source) {
        is ChrootSource.LocalFile -> {
            val size = try {
                context.contentResolver.query(source.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
            } catch (_: Exception) { -1L }
            (context.contentResolver.openInputStream(source.uri) ?: throw InstallError(L.local.chrootInstaller.cannotOpenDisplayname(source.displayName))) to size
        }
        else -> {
            val f = archive ?: throw InstallError(L.local.chrootInstaller.nothingWasDownloaded)
            f.inputStream() to f.length()
        }
    }

    /** gzip, xz, bzip2 or a plain tar, by its first bytes. */
    private fun decompress(input: BufferedInputStream): InputStream {
        input.mark(8)
        val head = ByteArray(6)
        val n = input.read(head)
        input.reset()
        fun b(i: Int) = head[i].toInt() and 0xff
        return when {
            n >= 2 && b(0) == 0x1f && b(1) == 0x8b -> java.util.zip.GZIPInputStream(input, 256 * 1024)
            n >= 6 && b(0) == 0xfd && b(1) == 0x37 && b(2) == 0x7a && b(3) == 0x58 && b(4) == 0x5a && b(5) == 0x00 ->
                org.tukaani.xz.XZInputStream(input)
            n >= 3 && b(0) == 0x42 && b(1) == 0x5a && b(2) == 0x68 ->
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(input, true)
            n >= 4 && b(0) == 0x28 && b(1) == 0xb5 && b(2) == 0x2f && b(3) == 0xfd ->
                throw InstallError(L.local.chrootInstaller.zstdArchivesAreNotSupportedUse)
            n >= 2 && b(0) == 0x50 && b(1) == 0x4b -> throw InstallError(L.local.chrootInstaller.thatIsAZipFileThe)
            else -> input
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** The user of the new install: a restored backup's own, else the one chosen on the setup page. */
    private fun userOf(req: ChrootInstallRequest): String = restoredBackup?.manifest?.get("USER")?.takeIf { ChrootManager.isUserName(it) } ?: req.user

    private suspend fun configure(context: Context, req: ChrootInstallRequest) {
        val backup = restoredBackup
        if (backup?.hasConf == true) {
            log("Keeping the backup's settings (mounts, DNS, shared folders): change them on its page.")
        } else {
            set(ChrootInstallStep.CONFIGURE, L.local.chrootInstaller.writingTheChrootsSettings)
            ChrootManager.writeMeta(context, req.path, "chroot.conf", req.config.toConf())
                .let { if (!it.ok) throw InstallError(L.local.chrootInstaller.couldNotSaveTheSettingsMessage(it.message())) }
        }
        set(ChrootInstallStep.CONFIGURE, L.local.chrootInstaller.mountingAndSettingUpDns)
        val draft = ChrootInstall(req.path, req.name, "installing", ChrootManager.defaultArch(), userOf(req),
            android.os.Process.myUid(), sourceLabel(req.source), System.currentTimeMillis(), false)
        val s = ChrootManager.start(context, draft, withBridge = false, onLine = { log(it) })
        if (!s.ok) throw InstallError(L.local.chrootInstaller.couldNotStartTheChrootMessage(s.message))
        set(ChrootInstallStep.CONFIGURE, L.local.chrootInstaller.networkGroupsAndUserUserof(userOf(req)))
        val r = ChrootManager.execAsRoot(context, req.path,
            ChrootScripts.userScript(userOf(req), android.os.Process.myUid(), req.password, req.sudoNoPassword, keepSudoers = backup != null),
            onLine = { log(it) })
        if (!r.ok) throw InstallError(L.local.chrootInstaller.settingUpUbuntuFailedMessage(r.message()))
    }

    private suspend fun packages(context: Context, req: ChrootInstallRequest) {
        if (restoredBackup != null) {
            set(ChrootInstallStep.PACKAGES, L.local.chrootInstaller.keptFromTheBackup)
            log("Packages: the backup's are kept (none installed now).")
            return
        }
        val bundles = req.bundles + ChrootPackageBundle.ESSENTIALS
        val names = bundles.flatMap { it.packages } + req.extraPackages.filter { ChrootScripts.isPackageName(it) }
        set(ChrootInstallStep.PACKAGES, L.local.chrootInstaller.aptNamescountPackagesValue(names.size, if (bundles.any { it.nodeJs }) " + Node.js" else ""))
        var lastLine = ""
        val r = ChrootManager.execAsRoot(context, req.path, ChrootScripts.packagesScript(names, bundles.any { it.nodeJs }, upgrade = false),
            onLine = { line ->
                log(line)
                if (line.isNotBlank() && line != lastLine) {
                    lastLine = line
                    set(ChrootInstallStep.PACKAGES, line.take(120))
                }
            })
        if (!r.ok) throw InstallError(L.local.chrootInstaller.installingPackagesFailedMessageCheckThe(r.message()))
    }

    private suspend fun finish(context: Context, req: ChrootInstallRequest): ChrootInstall {
        set(ChrootInstallStep.FINISH, L.local.chrootInstaller.registeringTheInstall)
        val found = ChrootManager.scan(context, req.path).firstOrNull { it.path == req.path }
        val install = ChrootInstall(
            path = req.path, name = req.name, os = found?.os?.takeIf { it.isNotBlank() } ?: "Ubuntu",
            arch = found?.arch ?: ChrootManager.defaultArch(), user = userOf(req), uid = android.os.Process.myUid(),
            source = restoredBackup?.let { "backup of ${it.manifest["NAME"] ?: "a chroot"} (${sourceLabel(req.source)})" } ?: sourceLabel(req.source),
            createdAt = System.currentTimeMillis(), legacy = false
        )
        ChrootManager.writeMeta(context, install.path, "manifest", install.toManifest())
            .let { if (!it.ok) throw InstallError(L.local.chrootInstaller.couldNotWriteTheManifestMessage(it.message())) }
        ChrootManager.runCtl(context, listOf("register", "--root", install.path))
        ChrootManager.addOrUpdate(context, install)
        set(ChrootInstallStep.FINISH, L.local.chrootInstaller.puttingTheBridgeIntoTheChroot)
        val s = ChrootManager.start(context, install, withBridge = true, onLine = { log(it) })
        if (!s.ok) log("⚠️ ${s.message}")
        if (req.activate) ChrootManager.activate(context, install)
        createdPath = null
        return install
    }

    fun sourceLabel(source: ChrootSource): String = when (source) {
        is ChrootSource.Official -> "official ${source.release.label}"
        is ChrootSource.Prebuilt -> "AntiGem prebuilt ($PREBUILT_TAG)"
        is ChrootSource.Url -> "url ${source.url.take(200)}"
        is ChrootSource.LocalFile -> "file ${source.displayName.take(120)}"
    }
}
