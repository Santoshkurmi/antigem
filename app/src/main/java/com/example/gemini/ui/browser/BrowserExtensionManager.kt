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
    val folderName: String = "",
    val permissions: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val dirPath: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("description", description)
        put("toolCount", toolCount)
        put("folderName", folderName)
        put("permissions", JSONArray(permissions))
        put("isEnabled", isEnabled)
        put("dirPath", dirPath)
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
            withContext(Dispatchers.Main) { loadExtensions() }
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

    private fun getConfigFile(extDir: File): File = File(extDir, "extensions.json")

    private fun getDisabledExtensionIds(extDir: File): Set<String> {
        val file = getConfigFile(extDir)
        if (!file.exists()) return emptySet()
        return try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("disabled") ?: JSONArray()
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (e: Exception) {
            Log.w(TAG, "Error reading extensions config: ${e.message}")
            emptySet()
        }
    }

    private fun saveDisabledExtensionIds(extDir: File, disabled: Set<String>) {
        require(extDir.mkdirs() || extDir.isDirectory) { "Cannot create extension directory" }
        val file = getConfigFile(extDir)
        val temporary = File(extDir, ".extensions-${java.util.UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(JSONObject().put("disabled", JSONArray(disabled.toList())).toString(2))
            check(temporary.renameTo(file)) { "Cannot save extension settings" }
        } finally { temporary.delete() }
    }

    fun loadExtensions(): List<BrowserExtension> {
        val ctx = appContext ?: return emptyList()
        val dirs = getExtensionsDirectories(ctx)
        val loaded = mutableListOf<BrowserExtension>()
        val seenIds = mutableSetOf<String>()

        for (baseDir in dirs) {
            if (!baseDir.exists() || !baseDir.isDirectory) continue
            val disabled = getDisabledExtensionIds(baseDir)

            val children = baseDir.listFiles() ?: continue
            for (sub in children) {
                if (!sub.isDirectory || sub.name.startsWith(".")) continue
                val manifestFile = File(sub, "manifest.json")
                if (!manifestFile.exists()) continue

                try {
                    val manifestStr = manifestFile.readText()
                    val manifestJson = JSONObject(manifestStr)

                    val id = manifestJson.optString("id", sub.name)
                    if (!seenIds.add(id)) continue
                    val name = manifestJson.optString("name", sub.name)
                    val version = manifestJson.optString("version", "1.0.0")
                    val description = manifestJson.optString("description", "")

                    // Permissions
                    val perms = mutableListOf<String>()
                    val permArr = manifestJson.optJSONArray("permissions")
                    if (permArr != null) {
                        for (i in 0 until permArr.length()) {
                            perms.add(permArr.getString(i))
                        }
                    }

                    val ext = BrowserExtension(
                        id = id,
                        name = name,
                        version = version,
                        description = description,
                        toolCount = manifestJson.optJSONArray("mcp_tools")?.length() ?: 0,
                        folderName = sub.name,
                        permissions = perms,
                        isEnabled = !disabled.contains(id),
                        dirPath = sub.absolutePath
                    )
                    loaded.add(ext)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse extension at ${sub.absolutePath}: ${e.message}")
                }
            }
        }

        extensions.clear()
        extensions.addAll(loaded)
        return loaded
    }

    private fun managedRoot() = File(LocalEnvironmentManager.getHomeDir(appContext!!), "extensions").apply { mkdirs() }

    private fun checkedFolder(name: String): String {
        val value = name.trim()
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(value) && value != "." && value != "..") { "Use 1–64 letters, numbers, dots, underscores or hyphens for the folder name" }
        require(value != "runtime") { "The folder name runtime is reserved" }
        return value
    }

    internal fun validatePackage(root: File): JSONObject {
        val manifest = JSONObject(File(root, "manifest.json").readText())
        require(manifest.optInt("manifest_version") in 2..3) { "Only manifest versions 2 and 3 are supported" }
        require(manifest.optString("name").isNotBlank()) { "Extension name is required" }
        val tools = manifest.optJSONArray("mcp_tools") ?: error("manifest.json must declare mcp_tools")
        require(tools.length() > 0) { "Extension must declare at least one MCP tool" }
        val names = mutableSetOf<String>()
        for (i in 0 until tools.length()) {
            val name = tools.getJSONObject(i).getString("name")
            require(name.isNotBlank() && names.add(name)) { "Duplicate or empty MCP tool name" }
        }
        val background = manifest.optJSONObject("background") ?: error("Extension background worker is required")
        val files = background.optString("service_worker").takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: background.optJSONArray("scripts")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
        require(files.isNotEmpty()) { "Extension background worker is required" }
        for (relative in files) {
            val file = File(root, relative).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) { "Missing or invalid background file: $relative" }
        }
        return manifest
    }

    private suspend fun publish() = withContext(Dispatchers.Main) { loadExtensions() }

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
        val base = File(extension.dirPath).parentFile!!
        val disabled = getDisabledExtensionIds(base).toMutableSet()
        if (enable) disabled.remove(id) else disabled.add(id)
        saveDisabledExtensionIds(base, disabled)
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

    suspend fun installArchive(uri: android.net.Uri, folderName: String, updateId: String? = null): BrowserExtension = withContext(Dispatchers.IO) { extensionMutex.withLock {
        val context = appContext ?: error("Extension manager is not initialized")
        val folder = checkedFolder(folderName)
        ensureHost()
        val previous = updateId?.let { id -> extensions.firstOrNull { it.id == id } ?: error("Extension not found") }
        val stage = File(context.cacheDir, "webview-extension-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val root = context.contentResolver.openInputStream(uri)?.use { WebViewExtensionArchive.extract(it, File(stage, "unpacked")) } ?: error("Cannot read ZIP")
            require(previous == null || folder == previous.folderName) { "Use Rename folder to change the folder before updating" }
            val manifest = validatePackage(root)
            val id = previous?.id ?: manifest.optString("id").ifBlank { folder.replace('.', '_') }
            require(Regex("[A-Za-z0-9_-]+").matches(id)) { "Invalid extension ID" }
            require(previous != null || extensions.none { it.id == id }) { "Extension already exists; use Update ZIP" }
            manifest.put("id", id)
            File(root, "manifest.json").writeText(manifest.toString(2))
            val target = File(managedRoot(), folder)
            require(previous != null || !target.exists()) { "Folder already exists" }
            require(previous == null || !target.exists() || target.absolutePath == previous.dirPath) { "Folder belongs to another extension" }
            val backup = File(stage, "previous")
            val enabled = previous?.isEnabled ?: true
            if (previous?.isEnabled == true) unloadIfLoaded(id)
            val existed = target.exists()
            try {
                if (existed) require(target.renameTo(backup)) { "Cannot stage previous extension" }
                root.copyRecursively(target, overwrite = false)
                if (enabled) hostRequest("/extension/load", JSONObject().put("dir", target.absolutePath))
                val disabled = getDisabledExtensionIds(managedRoot()).toMutableSet()
                if (enabled) disabled.remove(id) else disabled.add(id)
                saveDisabledExtensionIds(managedRoot(), disabled)
            } catch (error: Exception) {
                runCatching { hostRequest("/extension/unload", JSONObject().put("id", id)) }
                if (!existed || backup.exists()) target.deleteRecursively()
                if (backup.exists()) check(backup.renameTo(target)) { "Failed to restore previous extension" }
                if (previous?.isEnabled == true) hostRequest("/extension/load", JSONObject().put("dir", previous.dirPath))
                throw error
            }
            publish().first { it.id == id }
        } finally { stage.deleteRecursively() }
    } }

    suspend fun renameFolder(id: String, folderName: String): BrowserExtension = withContext(Dispatchers.IO) { extensionMutex.withLock {
        val name = checkedFolder(folderName)
        ensureHost()
        val extension = extensions.firstOrNull { it.id == id } ?: error("Extension not found")
        val source = File(extension.dirPath)
        val target = File(managedRoot(), name)
        if (source.canonicalFile == target.canonicalFile) return@withLock extension
        require(!target.exists()) { "Folder already exists" }
        if (extension.isEnabled) unloadIfLoaded(id)
        val move = source.parentFile?.canonicalFile == managedRoot().canonicalFile
        try {
            if (move) require(source.renameTo(target)) { "Cannot rename extension folder" } else source.copyRecursively(target)
            val manifest = JSONObject(File(target, "manifest.json").readText()).put("id", id)
            File(target, "manifest.json").writeText(manifest.toString(2))
            if (extension.isEnabled) hostRequest("/extension/load", JSONObject().put("dir", target.absolutePath))
            val disabled = getDisabledExtensionIds(managedRoot()).toMutableSet()
            if (extension.isEnabled) disabled.remove(id) else disabled.add(id)
            saveDisabledExtensionIds(managedRoot(), disabled)
        } catch (error: Exception) {
            if (move && target.exists()) target.renameTo(source) else target.deleteRecursively()
            if (extension.isEnabled) hostRequest("/extension/load", JSONObject().put("dir", source.absolutePath))
            throw error
        }
        publish().first { it.id == id }
    } }

    fun toggleExtension(id: String, enable: Boolean) { scope.launch { runCatching { setEnabled(id, enable) }.onFailure { lastError = it.message } } }
    fun reloadExtension(id: String) { scope.launch { runCatching { reload(id) }.onFailure { lastError = it.message } } }
}
