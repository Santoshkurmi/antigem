package com.example.gemini.ui.browser

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The developer MCP is separate from each installed extension's normal MCP server. SDK docs and the draft template
 * come from the app's assets; draft files (~/extension-projects) are read, written and packaged by the IDE bridge.
 */
object WebViewExtensionDeveloper {
    private const val DOCS = "extension-docs"
    private val draftsMutex = Mutex()
    private val manager get() = BrowserExtensionManager.instance

    fun tools(context: Context) = context.assets.open("$DOCS/developer-tools.json").bufferedReader().use { JSONArray(it.readText()) }

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            val value = when (name) {
                "webview_extension_docs" -> docs(context, args)
                "webview_extension_list" -> list(args)
                "webview_extension_create" -> draftsMutex.withLock { create(context, args) }
                "webview_extension_read_file" -> draftsMutex.withLock { readFile(args) }
                "webview_extension_write_files" -> draftsMutex.withLock { writeFiles(args) }
                "webview_extension_package" -> draftsMutex.withLock { PluginBridge.draftPackage(projectId(args.getString("project_id"))) }
                "webview_extension_install" -> draftsMutex.withLock { install(args) }
                "webview_extension_set_enabled" -> {
                    reloadMetadata(); manager.setEnabled(args.getString("extension_id"), args.getBoolean("enabled"))
                    metadata(args.getString("extension_id"))
                }
                "webview_extension_reload" -> {
                    reloadMetadata(); manager.reload(args.getString("extension_id")); metadata(args.getString("extension_id"))
                }
                "webview_extension_rename" -> {
                    reloadMetadata(); manager.renameFolder(args.getString("extension_id"), args.getString("folder_name")).toJsonObject()
                }
                "webview_extension_test" -> {
                    val id = args.getString("extension_id")
                    val tool = args.optString("tool_name").takeIf { it.isNotBlank() }
                    if (tool != null) return@withContext manager.callTool(id, tool, args.optJSONObject("arguments") ?: JSONObject())
                    JSONObject().put("extension_id", id).put("mcp_url", manager.mcpUrl(id)).put("tools", manager.tools(id))
                        .put("tested", "Worker loaded and tools discovered; no tool was invoked")
                }
                "webview_extension_logs" -> {
                    val limit = args.optInt("limit", 50)
                    require(limit in 1..200) { "limit must be 1–200" }
                    JSONObject(manager.request("/extension/logs", JSONObject().put("id", args.optString("extension_id")).put("limit", limit)))
                }
                else -> error("Unknown extension developer tool: $name")
            }
            response(value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            response(JSONObject().put("error", error.message ?: error.javaClass.simpleName), failed = true)
        }
    }

    private fun response(value: Any, failed: Boolean = false) = JSONObject().apply {
        if (failed) put("isError", true)
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString())))
    }

    private fun assetFiles(context: Context, path: String = ""): List<String> {
        val entries = context.assets.list(if (path.isEmpty()) DOCS else "$DOCS/$path").orEmpty()
        return entries.flatMap { child ->
            val name = if (path.isEmpty()) child else "$path/$child"
            if (context.assets.list("$DOCS/$name").orEmpty().isEmpty()) listOf(name) else assetFiles(context, name)
        }.sorted()
    }

    private fun page(text: String, args: JSONObject): JSONObject {
        val offset = args.optInt("offset", 0)
        val limit = args.optInt("limit", 16_000)
        require(offset >= 0 && limit in 1..40_000) { "offset must be non-negative and limit must be 1–40000 characters" }
        val end = minOf(text.length.toLong(), offset.toLong() + limit).toInt()
        return JSONObject().put("content", if (offset >= text.length) "" else text.substring(offset, end))
            .put("offset", offset).put("total_characters", text.length)
            .put("next_offset", if (end < text.length) end else JSONObject.NULL)
    }

    private fun docs(context: Context, args: JSONObject): JSONObject {
        val files = assetFiles(context)
        val result = JSONObject().put("files", JSONArray(files))
        if (args.optBoolean("list_only")) return result
        val path = args.optString("path", "README.md")
        require(path in files) { "Unknown SDK document. Use list_only=true to list available paths" }
        val text = context.assets.open("$DOCS/$path").bufferedReader().use { it.readText() }
        val content = page(text, args)
        content.keys().forEach { key -> result.put(key, content.get(key)) }
        return result.put("path", path)
    }

    private fun projectId(value: String): String {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(value)) { "project_id must contain 1–64 letters, numbers, underscores or hyphens" }
        return value
    }

    private suspend fun create(context: Context, args: JSONObject): JSONObject {
        val id = projectId(args.getString("project_id"))
        val name = args.getString("name").trim()
        require(name.isNotEmpty()) { "name is required" }
        val tool = projectId(args.optString("tool_name", "hello"))
        val manifest = context.assets.open("$DOCS/examples/hello-mcp/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        manifest.put("id", id).put("name", name).put("description", args.optString("description", "MCP extension for $name"))
        manifest.getJSONArray("mcp_tools").getJSONObject(0).put("name", tool)
        val worker = context.assets.open("$DOCS/examples/hello-mcp/worker.js").bufferedReader().use { it.readText() }
        val files = JSONArray()
            .put(JSONObject().put("path", "manifest.json").put("content", manifest.toString(2)))
            .put(JSONObject().put("path", "worker.js").put("content", worker.replace("message.tool !== 'hello'", "message.tool !== '$tool'")))
        return PluginBridge.draftCreate(id, files)
            .put("next_steps", "Edit with webview_extension_write_files, then webview_extension_install(project_id). Read developer-mcp.md for the complete loop.")
    }

    private suspend fun readFile(args: JSONObject): JSONObject {
        val id = projectId(args.getString("project_id"))
        val path = args.optString("path")
        val result = PluginBridge.draftRead(id, path)
        if (path.isEmpty()) return result
        return page(result.getString("content"), args).put("path", path)
    }

    private suspend fun writeFiles(args: JSONObject): JSONObject =
        PluginBridge.draftWrite(projectId(args.getString("project_id")), args.getJSONArray("files"))

    private suspend fun reloadMetadata() = manager.loadExtensions()
    private fun metadata(id: String) = manager.extensions.first { it.id == id }.toJsonObject().put("mcp_url", manager.mcpUrl(id))

    private suspend fun list(args: JSONObject): JSONObject {
        if (args.optBoolean("refresh")) manager.refreshExtensions() else reloadMetadata()
        val installed = withContext(Dispatchers.Main) { manager.extensions.toList() }
        var hostError: String? = null
        val live = try { JSONArray(manager.request("/extensions")) } catch (e: Exception) { hostError = e.message; JSONArray() }
        val loaded = (0 until live.length()).associate { live.getJSONObject(it).getString("id") to live.getJSONObject(it) }
        return JSONObject().put("extensions", JSONArray().apply {
            installed.forEach { put(it.toJsonObject().put("loaded", loaded[it.id]?.optString("dir") == it.dirPath).put("mcp_url", manager.mcpUrl(it.id))) }
        }).put("projects", JSONArray(runCatching { PluginBridge.draftProjects() }.getOrElse { hostError = hostError ?: it.message; emptyList() }))
            .put("host_error", hostError ?: JSONObject.NULL)
    }

    private suspend fun install(args: JSONObject): JSONObject {
        val id = args.optString("project_id").takeIf { it.isNotBlank() }?.let(::projectId)
        val path = args.optString("zip_path").takeIf { it.isNotBlank() }
        require((id == null) != (path == null)) { "Provide exactly one of project_id or zip_path" }
        reloadMetadata()
        val updateId = args.optString("extension_id").takeIf { it.isNotBlank() }
        val previous = updateId?.let { target -> manager.extensions.firstOrNull { it.id == target } ?: error("Installed extension not found: $target") }
        val folder = previous?.folderName ?: args.optString("folder_name").ifBlank { id ?: path!!.substringAfterLast('/').substringBeforeLast('.') }
        val extension = manager.installDraft(id, path, folder, updateId)
        return extension.toJsonObject().put("mcp_url", manager.mcpUrl(extension.id)).put("updated", previous != null)
            .put("next_steps", "Use webview_extension_test(extension_id) to discover tools, then pass tool_name and arguments for a real test call. The model can test through this developer MCP without adding another server.")
    }
}
