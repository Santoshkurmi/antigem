package com.example.gemini.ui.browser

import android.content.Context
import android.net.Uri
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The IDE bridge's /api/custom-plugins endpoints, which own every plugin file operation (install, update,
 * remove, rename, export, repositories). The app only shows the UI and loads plugins into its extension host.
 */
object PluginBridge {
    data class Installed(val plugin: BrowserExtension, val replaced: Boolean)

    private val json = "application/json".toMediaType()
    private val zip = "application/zip".toMediaType()
    // Repository installs download a whole plugin folder before answering
    private val http get() = IdeApiClient.okHttpClient.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()

    private fun url(path: String) = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/custom-plugins/" + path

    private fun call(request: Request): String = http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "IDE bridge HTTP ${response.code}")
        text
    }

    private fun post(path: String, body: JSONObject) = JSONObject(call(Request.Builder().url(url(path)).post(body.toString().toRequestBody(json)).build()))

    /** Streams a ZIP from a content or file Uri as the request body. */
    private fun zipBody(context: Context, uri: Uri) = object : RequestBody() {
        override fun contentType() = zip
        override fun writeTo(sink: BufferedSink) {
            (context.contentResolver.openInputStream(uri) ?: error("Cannot read ZIP")).use { sink.writeAll(it.source()) }
        }
    }

    suspend fun list(): List<BrowserExtension> = withContext(Dispatchers.IO) {
        val array = JSONObject(call(Request.Builder().url(url("")).get().build())).getJSONArray("plugins")
        (0 until array.length()).map { BrowserExtension.fromJson(array.getJSONObject(it)) }
    }

    suspend fun inspect(context: Context, uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(call(Request.Builder().url(url("inspect")).post(zipBody(context, uri)).build())).getJSONObject("manifest")
    }

    suspend fun install(context: Context, uri: Uri, folder: String, updateId: String?, asNew: Boolean): Installed = withContext(Dispatchers.IO) {
        val target = url("install").toHttpQuery("folder" to folder, "update_id" to updateId.orEmpty(), "as_new" to asNew.toString())
        installed(JSONObject(call(Request.Builder().url(target).post(zipBody(context, uri)).build())))
    }

    /** Progress of the repository install into [folder] while it runs; null when none is running. */
    suspend fun repoProgress(folder: String): RepoInstallProgress? = withContext(Dispatchers.IO) {
        val body = JSONObject(call(Request.Builder().url(url("repo/progress").toHttpQuery("folder" to folder)).get().build()))
        if (!body.optBoolean("active")) null else RepoInstallProgress(
            body.optString("phase"), body.optLong("bytes_done"), body.optLong("bytes_total"), body.optInt("files_done"), body.optInt("files_total")
        )
    }

    suspend fun repoInstall(repo: String, path: String, folder: String, updateId: String?, asNew: Boolean): Installed = withContext(Dispatchers.IO) {
        installed(post("repo/install", JSONObject().put("repo", repo).put("path", path).put("folder", folder).put("update_id", updateId.orEmpty()).put("as_new", asNew)))
    }

    suspend fun repoList(repo: String): RepoListing = withContext(Dispatchers.IO) {
        val body = post("repo/list", JSONObject().put("repo", repo))
        val plugins = body.optJSONArray("plugins") ?: JSONArray()
        RepoListing(body.optString("title"), (0 until plugins.length()).map { i ->
            plugins.getJSONObject(i).let { RepoPlugin(it.getString("path"), it.optString("id"), it.optString("name"), it.optString("version"), it.optString("description"), it.optString("iconUrl")) }
        }, body.optBoolean("fromIndex"))
    }

    suspend fun commit(folder: String) = withContext(Dispatchers.IO) { post("commit", JSONObject().put("folder", folder)); Unit }
    suspend fun restore(folder: String) = withContext(Dispatchers.IO) { post("restore", JSONObject().put("folder", folder)); Unit }
    suspend fun remove(id: String, deleteData: Boolean) = withContext(Dispatchers.IO) { post("remove", JSONObject().put("id", id).put("delete_data", deleteData)); Unit }
    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { post("set-enabled", JSONObject().put("id", id).put("enabled", enabled)); Unit }

    suspend fun rename(id: String, folder: String): BrowserExtension = withContext(Dispatchers.IO) {
        BrowserExtension.fromJson(post("rename", JSONObject().put("id", id).put("folder", folder)).getJSONObject("plugin"))
    }

    /** Downloads a plugin as a shareable ZIP into [directory], named like the bridge suggests (folder-version.zip). */
    suspend fun export(id: String, directory: File): File = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url("export").toHttpQuery("id" to id)).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(response.body!!.string()).optString("error") }.getOrNull() ?: "IDE bridge HTTP ${response.code}")
            val name = Regex("filename=\"([^\"]+)\"").find(response.header("Content-Disposition").orEmpty())?.groupValues?.get(1)
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "$id.zip"
            directory.mkdirs()
            File(directory, name).also { file -> file.outputStream().use { out -> response.body!!.byteStream().copyTo(out) } }
        }
    }

    // ---- developer MCP drafts (~/extension-projects) ----

    suspend fun draftProjects(): List<String> = withContext(Dispatchers.IO) {
        val array = JSONObject(call(Request.Builder().url(url("drafts")).get().build())).getJSONArray("projects")
        (0 until array.length()).map { array.getString(it) }
    }

    suspend fun draftCreate(projectId: String, files: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        post("drafts/create", JSONObject().put("project_id", projectId).put("files", files))
    }

    /** A draft's file list, or one file's full text when [path] is set. */
    suspend fun draftRead(projectId: String, path: String): JSONObject = withContext(Dispatchers.IO) {
        post("drafts/read", JSONObject().put("project_id", projectId).put("path", path))
    }

    suspend fun draftWrite(projectId: String, files: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        post("drafts/write", JSONObject().put("project_id", projectId).put("files", files))
    }

    suspend fun draftPackage(projectId: String): JSONObject = withContext(Dispatchers.IO) {
        post("drafts/package", JSONObject().put("project_id", projectId))
    }

    /** Installs a draft (packaged first) or a ZIP in the terminal home or shared storage: exactly one of the two. */
    suspend fun draftInstall(projectId: String?, zipPath: String?, folder: String, updateId: String?): Installed = withContext(Dispatchers.IO) {
        installed(post("drafts/install", JSONObject().put("project_id", projectId.orEmpty()).put("zip_path", zipPath.orEmpty())
            .put("folder", folder).put("update_id", updateId.orEmpty())))
    }

    private fun installed(body: JSONObject) = Installed(BrowserExtension.fromJson(body.getJSONObject("plugin")), body.optBoolean("replaced"))

    private fun String.toHttpQuery(vararg params: Pair<String, String>) =
        this + "?" + params.joinToString("&") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
}
