package com.example.gemini.ui.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class BrowserExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val toolCount: Int = 0,
    val skillCount: Int = 0,
    val folderName: String = "",
    val permissions: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val dirPath: String = "",
    /** Absolute path of the manifest's icon, blank if none. */
    val iconPath: String = "",
    /** Where a repository install came from; null for ZIP and bundled plugins. */
    val source: PluginSource? = null,
    /** Where the folder lives: [Location.INSTALLED] (ZIP/repository installs), built into the app, or a user folder. */
    val location: Location = Location.INSTALLED,
    val skills: List<ExtensionSkill> = emptyList()
) {
    enum class Location { INSTALLED, BUNDLED, USER_FOLDER }

    companion object {
        /** A plugin as listed by the IDE bridge's /api/custom-plugins. */
        fun fromJson(o: JSONObject) = BrowserExtension(
            id = o.getString("id"), name = o.optString("name"), version = o.optString("version"), description = o.optString("description"),
            toolCount = o.optInt("toolCount"), skillCount = o.optInt("skillCount"), folderName = o.optString("folderName"),
            permissions = o.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            isEnabled = o.optBoolean("isEnabled", true), dirPath = o.optString("dirPath"), iconPath = o.optString("iconPath"),
            source = o.optJSONObject("source")?.let { PluginSource(it.optString("repo"), it.optString("ref"), it.optString("path")) },
            location = when (o.optString("location")) { "bundled" -> Location.BUNDLED; "user_folder" -> Location.USER_FOLDER; else -> Location.INSTALLED },
            skills = o.optJSONArray("skills")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { ExtensionSkill(it.optString("name"), it.optString("title"), it.optString("description"), it.optString("path"), it.optString("detailDir").ifBlank { null }) } }
            }.orEmpty()
        )
    }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("description", description)
        put("toolCount", toolCount)
        put("skillCount", skillCount)
        put("folderName", folderName)
        put("permissions", JSONArray(permissions))
        put("isEnabled", isEnabled)
        put("dirPath", dirPath)
        put("iconPath", iconPath)
        source?.let { put("source", it.toJson()) }
    }
}

/** Written as `.antigem-source.json` into plugins installed from a repository, so updates can be found later. */
data class PluginSource(val repo: String, val ref: String, val path: String) {
    fun toJson(): JSONObject = JSONObject().put("repo", repo).put("ref", ref).put("path", path)

    companion object {
        const val FILE = ".antigem-source.json"
        fun read(dir: File): PluginSource? = runCatching {
            JSONObject(File(dir, FILE).readText()).let { PluginSource(it.getString("repo"), it.optString("ref"), it.getString("path")) }
        }.getOrNull()
    }
}

/** The manifest's icon: `icon` (one file) or the largest entry of Chrome-style `icons` ({"48": "icon48.png"}). */
object PluginIcons {
    private val TYPES = setOf("png", "jpg", "jpeg", "webp", "svg")

    fun relative(manifest: JSONObject): String? {
        manifest.optString("icon").takeIf { it.isNotBlank() }?.let { return it }
        val icons = manifest.optJSONObject("icons") ?: return null
        return icons.keys().asSequence().maxByOrNull { it.toIntOrNull() ?: 0 }?.let { icons.optString(it) }?.takeIf { it.isNotBlank() }
    }

    fun resolve(dir: File, manifest: JSONObject): String = resolve(dir, relative(manifest))

    /** Absolute path of icon file [name] inside [dir], blank if it is missing, outside the package or not an image. */
    fun resolve(dir: File, name: String?): String {
        if (name.isNullOrBlank()) return ""
        if (name.substringAfterLast('.').lowercase() !in TYPES) return ""
        val file = File(dir, name).canonicalFile
        return if (file.path.startsWith(dir.canonicalPath + File.separator) && file.isFile) file.path else ""
    }
}

class BrowserExtensionManager private constructor() {
    companion object {
        private const val TAG = "BrowserExtensionManager"
        val instance = BrowserExtensionManager()
    }

    val extensions = mutableStateListOf<BrowserExtension>()
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostMutex = Mutex()
    private val extensionMutex = Mutex()
    private var hostProcess: Process? = null
    private var packaged = false
    var lastError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    private val http = OkHttpClient.Builder().readTimeout(210, TimeUnit.SECONDS).connectTimeout(3, TimeUnit.SECONDS).build()

    fun runtimeFile(context: Context) = File(context.filesDir, "browser-runtime/extension-host.mjs")
    fun hostPort() = com.example.gemini.data.remote.AndroidLocalBridgeServer.DEFAULT_PORT + 100

    private fun deviceId() = appContext?.let { android.provider.Settings.Secure.getString(it.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.orEmpty()

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            runCatching { ensureHost() }.onFailure { lastError = it.message; Log.e(TAG, "Extension host failed", it) }
            loadExtensions()
        }
    }

    private fun copyAssets(context: Context, source: String, destination: File) {
        val entries = context.assets.list(source).orEmpty()
        if (entries.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(source).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else entries.forEach { copyAssets(context, "$source/$it", File(destination, it)) }
    }

    private fun removeLegacyRuntime(context: Context) {
        val names = context.assets.list("browser-runtime").orEmpty()
        val legacyDirectories = listOf(
            File(context.filesDir, "browser-extensions/runtime"),
            File(LocalEnvironmentManager.getHomeDir(context), "extensions/runtime")
        )
        for (directory in legacyDirectories) {
            if (File(directory, "manifest.json").exists()) continue
            // Remove only the old runtime's known files, preserving unrelated user files.
            names.forEach { name -> File(directory, name).takeIf { it.isFile }?.delete() }
            File(directory, "test/runtime.test.mjs").delete()
            File(directory, "test").delete()
            directory.delete()
        }
    }

    private fun hostRequest(path: String, body: JSONObject = JSONObject()): String {
        val request = Request.Builder().url("http://127.0.0.1:${hostPort()}$path")
            .header("X-Device-Id", deviceId()).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val client = if (path == "/health") http.newBuilder().readTimeout(1, TimeUnit.SECONDS).build() else http
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrDefault(text))
            text
        }
    }

    suspend fun ensureHost() = withContext(Dispatchers.IO) { hostMutex.withLock {
        val context = appContext ?: throw IllegalStateException("Extension manager is not initialized")
        if (!packaged) {
            copyAssets(context, "browser-runtime", runtimeFile(context).parentFile!!)
            copyAssets(context, "browser-extensions", File(context.filesDir, "browser-extensions"))
            removeLegacyRuntime(context)
            packaged = true
        }
        if (runCatching { JSONObject(hostRequest("/health")).optBoolean("ready") }.getOrDefault(false)) return@withLock
        if (hostProcess?.isAlive != true) {
            val node = File(LocalEnvironmentManager.getBinDir(context), "node")
            if (!node.canExecute()) throw IllegalStateException("Node.js is required in the local terminal to run browser extensions")
            val command = mutableListOf(node.absolutePath, "--experimental-vm-modules", runtimeFile(context).absolutePath,
                "--serve", "--port", hostPort().toString(), "--root", File(context.filesDir, "browser-extensions").absolutePath,
                "--device-id", deviceId(), "--bridge-url", "http://127.0.0.1:${com.example.gemini.data.remote.AndroidLocalBridgeServer.instance.boundPort}")
            getExtensionsDirectories(context).filter { it != File(context.filesDir, "browser-extensions") }.forEach {
                if (it.isDirectory) command.addAll(listOf("--extra-root", it.absolutePath))
            }
            val builder = ProcessBuilder(command).directory(LocalEnvironmentManager.getHomeDir(context)).redirectErrorStream(true)
            builder.environment().putAll(mapOf("HOME" to LocalEnvironmentManager.getHomeDir(context).absolutePath,
                "PREFIX" to LocalEnvironmentManager.getPrefixDir(context).absolutePath,
                "PATH" to "${LocalEnvironmentManager.getBinDir(context).absolutePath}:/system/bin",
                "TMPDIR" to LocalEnvironmentManager.getTmpDir(context).absolutePath,
                "AGY_PARENT_PID" to android.os.Process.myPid().toString()))
            hostProcess = builder.start()
            val process = hostProcess!!
            scope.launch { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { Log.d(TAG, it) } } }
        }
        repeat(80) {
            if (runCatching { JSONObject(hostRequest("/health")).optBoolean("ready") }.getOrDefault(false)) { lastError = null; return@withLock }
            if (hostProcess?.isAlive != true) throw IllegalStateException("Extension host exited; see BrowserExtensionManager logs")
            delay(100)
        }
        throw IllegalStateException("Extension host did not start in time")
    } }

    suspend fun request(path: String, body: JSONObject = JSONObject()) = withContext(Dispatchers.IO) {
        ensureHost()
        hostRequest(path, body)
    }

    fun mcpUrl(extensionId: String) = "http://127.0.0.1:${hostPort()}/mcp?extension=${java.net.URLEncoder.encode(extensionId, "UTF-8")}"

    fun mcpServerName(extensionId: String) = if (extensionId == "google-flow") "google_flow_mcp" else "extension_$extensionId"

    private fun mcpPath(extensionId: String) = "/mcp?extension=${java.net.URLEncoder.encode(extensionId, "UTF-8")}"

    suspend fun tools(extensionId: String): JSONArray = JSONObject(request(mcpPath(extensionId), JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list")))
        .getJSONObject("result").getJSONArray("tools")

    suspend fun callTool(extensionId: String, name: String, args: JSONObject): JSONObject = JSONObject(request(mcpPath(extensionId), JSONObject().put("jsonrpc", "2.0").put("id", 1)
        .put("method", "tools/call").put("params", JSONObject().put("name", name).put("arguments", args)))).getJSONObject("result")

    suspend fun prepareTab(tab: BrowserTabSession) {
        request("/tab/prepare", JSONObject().put("nativeId", tab.id))
    }

    fun isHostRunning() = hostProcess?.isAlive == true

    /**
     * Closes the host's DevTools session for [tab] (Google sign-in must load without one), then runs
     * [then] on the main thread. Never blocks navigation for long: failures and timeouts still continue.
     */
    fun releaseTab(tab: BrowserTabSession, then: () -> Unit) {
        scope.launch {
            runCatching {
                val request = Request.Builder().url("http://127.0.0.1:${hostPort()}/tab/release").header("X-Device-Id", deviceId())
                    .post(JSONObject().put("nativeId", tab.id).toString().toRequestBody("application/json".toMediaType())).build()
                http.newBuilder().readTimeout(2, TimeUnit.SECONDS).build().newCall(request).execute().close()
            }.onFailure { Log.w(TAG, "Could not release tab before sign-in", it) }
            withContext(Dispatchers.Main) { then() }
        }
    }

    fun loadWithExtensions(tab: BrowserTabSession, view: android.webkit.WebView, url: String) {
        // Navigation must never wait for Node, DevTools, or an extension worker. A newly created
        // WebView may not even have a renderer to inspect until its first load has started.
        view.loadUrl(url)
        if (BrowserIdentity.isGoogleSignInUrl(url)) return
        scope.launch {
            runCatching { prepareTab(tab) }.onFailure { lastError = it.message; Log.w(TAG, "Extension preparation failed", it) }
        }
    }

    fun getExtensionsDirectories(context: Context): List<File> {
        val list = mutableListOf<File>()
        try {
            val homeDir = LocalEnvironmentManager.getHomeDir(context)
            list.add(File(homeDir, "extensions"))
            list.add(File(LocalEnvironmentManager.getProjectsDir(context), "antiGem/extensions"))
        } catch (_: Exception) {}

        list.add(File(context.filesDir, "extensions"))
        list.add(File(context.filesDir, "browser-extensions"))
        val extStorage = File("/sdcard/antiGem/extensions")
        if (extStorage.exists()) list.add(extStorage)
        return list.distinctBy { it.absolutePath }
    }

    private fun getDisabledExtensionIds(extDir: File): Set<String> = runCatching {
        val arr = JSONObject(File(extDir, "extensions.json").readText()).optJSONArray("disabled") ?: JSONArray()
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Read-only scan of the plugin folders, used only when the IDE bridge can't be reached (e.g. at app start before it
     * runs) so installed plugins still load into the extension host. Every change goes through [PluginBridge].
     */
    private fun scanLocal(): List<BrowserExtension> {
        val ctx = appContext ?: return emptyList()
        val loaded = mutableListOf<BrowserExtension>()
        val seenIds = mutableSetOf<String>()
        for (baseDir in getExtensionsDirectories(ctx)) {
            val disabled = getDisabledExtensionIds(baseDir)
            for (sub in baseDir.listFiles().orEmpty().sortedBy { it.name }) {
                if (!sub.isDirectory || sub.name.startsWith(".") || !File(sub, "manifest.json").isFile) continue
                runCatching {
                    val manifest = JSONObject(File(sub, "manifest.json").readText())
                    val id = manifest.optString("id").ifBlank { sub.name }
                    if (!seenIds.add(id)) return@runCatching
                    val skills = ExtensionSkills.list(sub)
                    loaded += BrowserExtension(
                        id = id, name = manifest.optString("name").ifBlank { sub.name }, version = manifest.optString("version"),
                        description = manifest.optString("description"), toolCount = manifest.optJSONArray("mcp_tools")?.length() ?: 0,
                        skillCount = skills.size, folderName = sub.name,
                        permissions = manifest.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                        isEnabled = id !in disabled, dirPath = sub.absolutePath, iconPath = PluginIcons.resolve(sub, manifest),
                        source = PluginSource.read(sub), skills = skills,
                        location = when (baseDir.absolutePath) {
                            File(ctx.filesDir, "browser-extensions").absolutePath -> BrowserExtension.Location.BUNDLED
                            File(LocalEnvironmentManager.getHomeDir(ctx), "extensions").absolutePath, File(ctx.filesDir, "extensions").absolutePath -> BrowserExtension.Location.INSTALLED
                            else -> BrowserExtension.Location.USER_FOLDER
                        }
                    )
                }.onFailure { Log.w(TAG, "Failed to parse extension at ${sub.absolutePath}: ${it.message}") }
            }
        }
        return loaded
    }

    /** Reads the plugin list from the IDE bridge (or [scanLocal] if it is not running) and publishes it to [extensions]. */
    suspend fun loadExtensions(): List<BrowserExtension> {
        val list = runCatching { PluginBridge.list() }.getOrElse {
            Log.w(TAG, "IDE bridge plugin list unavailable, scanning locally: ${it.message}")
            withContext(Dispatchers.IO) { scanLocal() }
        }
        withContext(Dispatchers.Main) { extensions.clear(); extensions.addAll(list) }
        return list
    }

    private suspend fun publish() = loadExtensions()

    suspend fun refreshExtensions() = withContext(Dispatchers.IO) { extensionMutex.withLock {
        ensureHost()
        val desired = publish()
        val live = JSONArray(hostRequest("/extensions"))
        val loaded = (0 until live.length()).associate { live.getJSONObject(it).getString("id") to live.getJSONObject(it).getString("dir") }
        for ((id, dir) in loaded) if (desired.none { it.id == id && it.isEnabled && it.dirPath == dir }) {
            hostRequest("/extension/unload", JSONObject().put("id", id)); BrowserAutomationController.release(id)
        }
        for (extension in desired.filter { it.isEnabled }) if (loaded[extension.id] != extension.dirPath) {
            hostRequest("/extension/load", JSONObject().put("dir", extension.dirPath))
        }
        lastError = null
    } }

    suspend fun setEnabled(id: String, enable: Boolean) = withContext(Dispatchers.IO) { extensionMutex.withLock {
        ensureHost()
        val extension = extensions.firstOrNull { it.id == id } ?: error("Extension not found")
        if (enable) hostRequest("/extension/load", JSONObject().put("dir", extension.dirPath))
        else {
            runCatching { hostRequest("/extension/unload", JSONObject().put("id", id)) }
                .onFailure { failure ->
                    val live = JSONArray(hostRequest("/extensions"))
                    if ((0 until live.length()).any { live.getJSONObject(it).getString("id") == id }) throw failure
                }
            BrowserAutomationController.release(id)
        }
        PluginBridge.setEnabled(id, enable)
        publish()
    } }

    private fun unloadIfLoaded(id: String) {
        val live = JSONArray(hostRequest("/extensions"))
        if ((0 until live.length()).any { live.getJSONObject(it).getString("id") == id }) {
            hostRequest("/extension/unload", JSONObject().put("id", id))
        }
        BrowserAutomationController.release(id)
    }

    suspend fun reload(id: String) = withContext(Dispatchers.IO) { extensionMutex.withLock {
        ensureHost()
        val extension = extensions.firstOrNull { it.id == id } ?: error("Extension not found")
        if (extension.isEnabled) {
            BrowserAutomationController.release(id)
            unloadIfLoaded(id)
            hostRequest("/extension/load", JSONObject().put("dir", extension.dirPath))
        }
        publish()
    } }

    /** The manifest inside a plugin ZIP, to spot an already-installed plugin and show versions before installing. */
    suspend fun archiveManifest(uri: android.net.Uri): JSONObject =
        PluginBridge.inspect(appContext ?: error("Extension manager is not initialized"), uri)

    /** [asNew] installs a separate copy whose ID comes from [folderName], even if the ZIP's own ID is already installed. */
    suspend fun installArchive(uri: android.net.Uri, folderName: String, updateId: String? = null, asNew: Boolean = false): BrowserExtension = withContext(Dispatchers.IO) { extensionMutex.withLock {
        val context = appContext ?: error("Extension manager is not initialized")
        ensureHost()
        val previous = updateId?.let { id -> extensions.firstOrNull { it.id == id } ?: error("Extension not found") }
        activate(PluginBridge.install(context, uri, folderName.trim(), updateId, asNew).plugin, previous)
    } }

    /** Installs or updates folder [path] of a GitHub repository ([repo] as owner/repo[@ref]); the bridge downloads only that folder. */
    suspend fun installFromRepo(repo: String, path: String, folderName: String, updateId: String?, asNew: Boolean = false): BrowserExtension = withContext(Dispatchers.IO) { extensionMutex.withLock {
        ensureHost()
        val previous = updateId?.let { id -> extensions.firstOrNull { it.id == id } ?: error("Extension not found") }
        activate(PluginBridge.repoInstall(repo, path, folderName.trim(), updateId, asNew).plugin, previous)
    } }

    /** Installs a developer-MCP draft ([projectId]) or a local ZIP ([zipPath]) through the bridge, then loads it. */
    suspend fun installDraft(projectId: String?, zipPath: String?, folderName: String, updateId: String?): BrowserExtension = withContext(Dispatchers.IO) { extensionMutex.withLock {
        ensureHost()
        val previous = updateId?.let { id -> extensions.firstOrNull { it.id == id } ?: error("Extension not found") }
        activate(PluginBridge.draftInstall(projectId, zipPath, folderName.trim(), updateId).plugin, previous)
    } }

    /** Loads a plugin the bridge just placed; if it fails to start, the bridge puts the previous files back. */
    private suspend fun activate(installed: BrowserExtension, previous: BrowserExtension?): BrowserExtension {
        if (previous?.isEnabled == true) unloadIfLoaded(previous.id)
        if (installed.isEnabled) try {
            unloadIfLoaded(installed.id)
            hostRequest("/extension/load", JSONObject().put("dir", installed.dirPath))
        } catch (error: Exception) {
            runCatching { unloadIfLoaded(installed.id) }
            runCatching { PluginBridge.restore(installed.folderName) }
            if (previous?.isEnabled == true) runCatching { hostRequest("/extension/load", JSONObject().put("dir", previous.dirPath)) }
            publish()
            throw error
        }
        runCatching { PluginBridge.commit(installed.folderName) }
        return publish().firstOrNull { it.id == installed.id } ?: installed
    }

    suspend fun renameFolder(id: String, folderName: String): BrowserExtension = withContext(Dispatchers.IO) { extensionMutex.withLock {
        ensureHost()
        val extension = extensions.firstOrNull { it.id == id } ?: error("Extension not found")
        if (extension.isEnabled) unloadIfLoaded(id)
        val renamed = try { PluginBridge.rename(id, folderName.trim()) } catch (error: Exception) {
            if (extension.isEnabled) runCatching { hostRequest("/extension/load", JSONObject().put("dir", extension.dirPath)) }
            throw error
        }
        if (renamed.isEnabled) hostRequest("/extension/load", JSONObject().put("dir", renamed.dirPath))
        publish().first { it.id == id }
    } }

    /**
     * Removes a plugin through the IDE bridge (symlinks inside it are removed as links, never followed). Its stored
     * data (storage.local/sync) is kept for a later reinstall unless [deleteData]. If another root has a plugin with
     * the same ID (e.g. the bundled copy), that one takes over; bundled plugins come back on the app's next start.
     */
    suspend fun uninstall(id: String, deleteData: Boolean) = withContext(Dispatchers.IO) { extensionMutex.withLock {
        ensureHost()
        extensions.firstOrNull { it.id == id } ?: error("Extension not found")
        unloadIfLoaded(id)
        PluginBridge.remove(id, deleteData)
        publish().firstOrNull { it.id == id && it.isEnabled }?.let { hostRequest("/extension/load", JSONObject().put("dir", it.dirPath)) }
    } }

    /** A shareable ZIP of the plugin in the cache (the caller moves or deletes it), built by the IDE bridge. */
    suspend fun exportZip(id: String): File {
        val context = appContext ?: error("Extension manager is not initialized")
        return PluginBridge.export(id, File(context.cacheDir, "plugin-export/${java.util.UUID.randomUUID()}"))
    }

    fun toggleExtension(id: String, enable: Boolean) { scope.launch { runCatching { setEnabled(id, enable) }.onFailure { lastError = it.message } } }
    fun reloadExtension(id: String) { scope.launch { runCatching { reload(id) }.onFailure { lastError = it.message } } }
}
