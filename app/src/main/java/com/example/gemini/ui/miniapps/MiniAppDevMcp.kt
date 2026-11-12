package com.example.gemini.ui.miniapps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniAppConsole
import com.example.gemini.data.miniapps.MiniAppInstaller
import com.example.gemini.data.miniapps.MiniAppKeyBackup
import com.example.gemini.data.miniapps.MiniAppNet
import com.example.gemini.data.miniapps.MiniAppPageHandle
import com.example.gemini.data.miniapps.MiniAppPermissions
import com.example.gemini.data.miniapps.MiniAppRuntime
import com.example.gemini.data.miniapps.MiniAppSigning
import com.example.gemini.data.miniapps.MiniApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * The Mini App Developer MCP (`/mini_app_developer`, like the plugin developer MCP): an agent builds, runs, debugs,
 * signs and installs mini apps on this phone. The guide is served from this AntiGem version itself (miniapp_guide).
 *
 * Boundaries:
 *  - files, eval, screenshot, console, data and permission tools only touch development apps (~/mini-apps). An
 *    installed (signed) app is never changed, inspected or scripted: that would defeat its signature.
 *  - installing always goes through the install screen: the user confirms on the phone.
 *  - the developer key's password is only ever typed on the phone; the agent never sees the key or the password.
 *  - permissions can be reset or denied, never granted: the user decides what an app may do.
 */
object MiniAppDevMcp {
    const val INSTRUCTIONS =
        "Build, run, debug, sign and install AntiGem mini apps (HTML/CSS/JS apps with a native AntiGem.* SDK) on this phone. " +
            "Start with miniapp_guide: it serves the guide bundled with this AntiGem version (topic README, then getting-started, manifest " +
            "and the API pages you need). Follow it instead of assuming browser, PWA or Cordova behaviour. " +
            "Loop: miniapp_create → miniapp_write_files → miniapp_validate → miniapp_open → miniapp_console / miniapp_eval / miniapp_screenshot → fix " +
            "(open pages reload by themselves when files change). Ship: miniapp_key_status → miniapp_sign → miniapp_install (the user confirms on the phone). " +
            "Development tools only work on development apps in ~/mini-apps, never on installed (signed) apps. " +
            "Never ask the user for the key password: if the key isn't backed up, call miniapp_key_backup and the user does it on the phone."

    private const val DOCS = "mini-app-docs"

    private class ToolError(message: String) : Exception(message)

    // ------------------------------------------------------------------ schema helpers

    private fun prop(type: String, description: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("type", type).put("description", description).apply(extra)

    private fun tool(name: String, description: String, required: List<String>, readOnly: Boolean, vararg props: Pair<String, JSONObject>) = JSONObject()
        .put("name", name).put("description", description)
        .put("inputSchema", JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
            .put("additionalProperties", false))
        .put("annotations", JSONObject().put("readOnlyHint", readOnly))

    private val appIdProp = "app_id" to prop("string", "The app's id (manifest \"id\"), as listed by miniapp_list.")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("miniapp_guide",
            "START HERE. The mini app guide bundled with this AntiGem version: architecture, manifest, permissions, every AntiGem.* API, " +
                "UI kit, signing, shared data, this MCP. No topic: the README with the table of contents. Use query to search all pages.",
            emptyList(), true,
            "topic" to prop("string", "Page name, e.g. README, getting-started, manifest, permissions, api-reference, storage, files, network, ui, notifications, background, intents, threads, shared-data, signing-and-distribution, developer-mcp, troubleshooting."),
            "query" to prop("string", "Search every page for this text (case-insensitive); returns matching lines with their topic."),
            "offset" to prop("integer", "Character offset for long pages (from next_offset)."),
            "limit" to prop("integer", "Characters to return (default 30000).")))
        put(tool("miniapp_list",
            "All mini apps: development apps (folders in ~/mini-apps) and installed (signed) ones, with version, permissions and their state, " +
                "shared_id group, open pages, broken manifests and id conflicts. Also says whether developer mode is on and if the developer key is backed up.",
            emptyList(), true))
        put(tool("miniapp_create",
            "Create a development app from a small working template (manifest.json, index.html, app.js, style.css using the UI kit and AntiGem.storage) " +
                "in ~/mini-apps/<folder>. Then edit it with miniapp_write_files.",
            listOf("id", "name"), false,
            "id" to prop("string", "Unique reverse-domain id, e.g. com.alex.budget. Letters, digits, '.', '_' or '-'. Data is stored under it: don't change it later."),
            "name" to prop("string", "Display name."),
            "folder" to prop("string", "Folder name in ~/mini-apps (default: from the name)."),
            "description" to prop("string", "One line shown in Mini Apps."),
            "theme_color" to prop("string", "Splash / status bar color like #2E7D32 (default: follow the AntiGem theme).")))
        put(tool("miniapp_read_file",
            "List a development app's files (no path), or read one text file (paged).",
            listOf("app_id"), true, appIdProp,
            "path" to prop("string", "File path inside the app folder, e.g. app.js. Omit to list all files."),
            "offset" to prop("integer", "Character offset (from next_offset)."),
            "limit" to prop("integer", "Characters to return (default 60000).")))
        put(tool("miniapp_write_files",
            "Create or overwrite files in a development app's folder (1–100 files). Open pages of the app reload by themselves (CSS-only changes swap stylesheets).",
            listOf("app_id", "files"), false, appIdProp,
            "files" to prop("array", "Files to write.") {
                put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("path", prop("string", "Path inside the app folder, e.g. js/list.js. No '..'."))
                    .put("content", prop("string", "The file content."))
                    .put("encoding", prop("string", "utf8 (default) or base64 for binary files such as icon.png.") { put("enum", JSONArray(listOf("utf8", "base64"))) }))
                    .put("required", JSONArray(listOf("path", "content"))))
            }))
        put(tool("miniapp_delete_file", "Delete a file or folder in a development app's folder.", listOf("app_id", "path"), false, appIdProp,
            "path" to prop("string", "Path inside the app folder."),
            "recursive" to prop("boolean", "Needed to delete a non-empty folder.")))
        put(tool("miniapp_validate",
            "Check an app before running or signing: manifest fields and id, entry and icon files, background script, shared_id, " +
                "id conflicts, remote resources. Returns errors (must fix) and warnings.",
            listOf("app_id"), true, appIdProp))
        put(tool("miniapp_open",
            "Open the app on the phone's screen (brings it forward if it's already running) and wait until its page is ready. Development apps need developer mode on.",
            listOf("app_id"), false, appIdProp,
            "path" to prop("string", "Optional page to show, e.g. settings.html or index.html#/stats.")))
        put(tool("miniapp_reload", "Reload the open pages of the app (opens it if it isn't running). Also re-reads manifest.json.", listOf("app_id"), false, appIdProp))
        put(tool("miniapp_console",
            "Console output and errors of a development app (console.*, uncaught errors, runtime messages), newest last. Pass since=<now from the last call> to get only new lines.",
            listOf("app_id"), true, appIdProp,
            "limit" to prop("integer", "Max entries (default 100, max 1000)."),
            "level" to prop("string", "Only this level and above.") { put("enum", JSONArray(listOf("debug", "log", "info", "warn", "error"))) },
            "since" to prop("integer", "Only entries after this time (ms), e.g. the \"now\" of a previous call."),
            "clear" to prop("boolean", "Clear the buffer after reading.")))
        put(tool("miniapp_eval",
            "Run JavaScript in the development app's page on screen and return the result: read the DOM, click buttons, call AntiGem.* APIs, check state. " +
                "The code is the body of an async function (use return / await); a bare expression returns its value. " +
                "Console lines printed meanwhile are returned too. Opens the app first if needed.",
            listOf("app_id", "code"), false, appIdProp,
            "code" to prop("string", "JavaScript, e.g. `return document.querySelectorAll('.item').length` or `document.querySelector('#add').click(); await new Promise(r => setTimeout(r, 300)); return document.body.innerText`."),
            "timeout_ms" to prop("integer", "Max wait for the result (default 10000, max 60000).")))
        put(tool("miniapp_screenshot",
            "A picture of the development app as it is on screen (opens / brings it forward first). Native dialogs drawn over it are not included.",
            listOf("app_id"), true, appIdProp,
            "scale" to prop("number", "Size factor 0.2–1 (default 0.5).")))
        put(tool("miniapp_permissions",
            "A development app's permissions: list (state of each, as the user set them), reset (the app asks again, like a fresh install), or deny one. " +
                "Permissions are never granted here: the user grants them in the app's prompt.",
            listOf("app_id"), false, appIdProp,
            "action" to prop("string", "list (default), reset or deny.") { put("enum", JSONArray(listOf("list", "reset", "deny"))) },
            "permission" to prop("string", "For deny: the permission name from the list, e.g. network or storage:/sdcard/Download.")))
        put(tool("miniapp_clear_data",
            "Delete a development app's data (storage, IndexedDB, files in data/, cookies, reminders, tasks, notifications, logs), like a fresh install. Open pages reload.",
            listOf("app_id"), false, appIdProp))
        put(tool("miniapp_key_status", "The phone's developer key: whether there is one, its developer name and fingerprint, and whether (and where) it is backed up.", emptyList(), true))
        put(tool("miniapp_key_create",
            "Create the phone's developer key (once; all your packages are signed with it). The phone then asks the user to back it up with a password.",
            listOf("developer"), false,
            "developer" to prop("string", "Developer or team name shown to people who install the apps.")))
        put(tool("miniapp_key_backup",
            "Open the key backup on the phone: the user types a password there and the key is saved to Download/AntiGem (kept even if AntiGem is uninstalled) " +
                "and can be shared to Drive or email. You never see the key or the password.",
            emptyList(), false))
        put(tool("miniapp_sign",
            "Package and sign a development app with the phone's developer key into ~/mini-apps-dist/<id>-<version>.agapp (the file you share or install). " +
                "Bump the manifest version for every release.",
            listOf("app_id"), false, appIdProp))
        put(tool("miniapp_install",
            "Open AntiGem's install screen on the phone for an .agapp package; the user confirms (Install / Update). Returns what the install would do " +
                "(new, update, downgrade, or why it's refused). Note: a package can't use the id of a development app in ~/mini-apps.",
            listOf("package_path"), false,
            "package_path" to prop("string", "Path of the .agapp, e.g. ~/mini-apps-dist/com.alex.budget-1.0.0.agapp (from miniapp_sign)."),
            "wait_seconds" to prop("integer", "Wait up to this long (max 180) for the user to install it, then report whether they did (default 0: return right away).")))
    }

    // ------------------------------------------------------------------ results

    private fun text(s: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", s)))
    private fun json(o: Any) = text(when (o) { is JSONObject -> o.toString(2); is JSONArray -> o.toString(2); else -> o.toString() })
    private fun error(message: String) = text(message).put("isError", true)

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject = try {
        withContext(Dispatchers.IO) {
            when (name) {
                "miniapp_guide" -> guide(context, args)
                "miniapp_list" -> json(list(context))
                "miniapp_create" -> json(create(context, args))
                "miniapp_read_file" -> readFile(context, args)
                "miniapp_write_files" -> json(writeFiles(context, args))
                "miniapp_delete_file" -> json(deleteFile(context, args))
                "miniapp_validate" -> json(validate(context, app(context, args)))
                "miniapp_open" -> json(open(context, args))
                "miniapp_reload" -> json(reload(context, args))
                "miniapp_console" -> json(console(context, args))
                "miniapp_eval" -> json(eval(context, args))
                "miniapp_screenshot" -> screenshot(context, args)
                "miniapp_permissions" -> json(permissions(context, args))
                "miniapp_clear_data" -> json(clearData(context, args))
                "miniapp_key_status" -> json(keyStatus(context))
                "miniapp_key_create" -> json(keyCreate(context, args))
                "miniapp_key_backup" -> json(keyBackup(context))
                "miniapp_sign" -> json(sign(context, args))
                "miniapp_install" -> json(install(context, args))
                else -> error("Unknown tool $name")
            }
        }
    } catch (e: ToolError) {
        error(e.message ?: "Failed")
    } catch (e: Exception) {
        error("$name failed: ${e.message ?: e.javaClass.simpleName}")
    }

    // ------------------------------------------------------------------ apps

    private fun home(context: Context) = MiniApps.home(context)
    private fun show(context: Context, f: File): String = home(context).path.let { h -> if (f.path == h || f.path.startsWith("$h/")) "~" + f.path.removePrefix(h) else f.path }

    private fun app(context: Context, args: JSONObject): MiniApp {
        val id = args.optString("app_id").trim()
        if (id.isEmpty()) throw ToolError("app_id is required (see miniapp_list)")
        return MiniApps.find(context, id) ?: MiniApps.scan(context).firstOrNull { it.id == id }
            ?: throw ToolError("No mini app with id \"$id\". Apps: ${MiniApps.scan(context).joinToString { it.id }.ifEmpty { "none" }}. " +
                "A folder with a broken manifest.json doesn't count: miniapp_list shows those.")
    }

    /** Development apps only: an installed app is never changed or inspected through the developer tools. */
    private fun devApp(context: Context, args: JSONObject): MiniApp {
        val a = app(context, args)
        if (a.signed) throw ToolError("${a.name} (${a.id}) is an installed (signed) app. The developer tools only work on development apps " +
            "(folders in ~/mini-apps): change the development copy, then sign and install a new version.")
        return a
    }

    private fun requireRunnable(context: Context, a: MiniApp) {
        if (!a.signed && !MiniApps.devMode(context)) throw ToolError("Developer mode is off, so development apps don't run. Ask the user to turn it on: Mini Apps → the </> button at the top.")
        if (a.tampered) throw ToolError("${a.name} was modified after it was installed, so it can't run. Reinstall it.")
    }

    private fun pages(appId: String): List<MiniAppPageHandle> = MiniAppRuntime.openPages(appId).filter { !it.isBackgroundTask }

    private fun target(appId: String): MiniAppPageHandle? = pages(appId).let { p -> p.lastOrNull { it.isForeground } ?: p.lastOrNull() }

    /** Opens / brings forward the app and waits for its page (and, when [foreground], for it to be on screen). */
    private suspend fun ensureOpen(context: Context, a: MiniApp, foreground: Boolean): MiniAppPageHandle {
        requireRunnable(context, a)
        val current = target(a.id)
        if (current != null && (!foreground || current.isForeground)) return current
        withContext(Dispatchers.Main) { MiniAppActivity.launch(context, a.id) }
        val page = withTimeoutOrNull(12_000) {
            while (true) {
                val p = target(a.id)
                if (p != null && (!foreground || p.isForeground)) return@withTimeoutOrNull p
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw ToolError("${a.name} didn't open within 12 s. It may be waiting for a permission prompt or blocked by a required permission: take a miniapp_screenshot or ask the user.")
        delay(700) // let it render
        return page
    }

    private fun list(context: Context): JSONObject {
        val apps = MiniApps.scan(context)
        val devMode = MiniApps.devMode(context)
        val out = JSONArray()
        for (a in apps) {
            val perms = JSONObject().apply { MiniAppPermissions.all(context, a).forEach { (n, s) -> put(n, s.js) } }
            out.put(JSONObject()
                .put("id", a.id).put("name", a.name).put("version", a.version)
                .put("kind", if (a.signed) "installed" else "development")
                .put("location", if (a.signed) "installed by AntiGem (read-only)" else show(context, a.folder))
                .put("entry", a.entry)
                .put("runs", if (a.signed) !a.tampered else devMode)
                .apply { if (a.signed) put("signed_by", "${a.developer} (${MiniAppSigning.shortFingerprint(a.signer.orEmpty())})") }
                .apply { if (a.tampered) put("tampered", true) }
                .put("permissions", perms)
                .apply { if (a.required.isNotEmpty()) put("required_permissions", JSONArray(a.required.toList())) }
                .apply { a.sharedId?.let { put("shared_id", it).put("shares_data_with", JSONArray(MiniApps.sharedGroup(context, a).map { it.id })) } }
                .apply { a.backgroundScript?.let { put("background", it) } }
                .put("open_pages", JSONArray(pages(a.id).map { JSONObject().put("path", it.path).put("on_screen", it.isForeground) })))
        }
        // folders that aren't apps because their manifest is broken
        val broken = JSONArray()
        MiniApps.appsDir(context).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && File(it, "manifest.json").isFile }?.forEach { dir ->
            runCatching { MiniApps.parse(dir, JSONObject(File(dir, "manifest.json").readText())) }
                .onFailure { broken.put(JSONObject().put("folder", show(context, dir)).put("error", it.message ?: "invalid manifest.json")) }
        }
        return JSONObject().put("developer_mode", devMode).put("apps_dir", show(context, MiniApps.appsDir(context)))
            .put("apps", out)
            .apply { if (broken.length() > 0) put("broken", broken) }
            .apply { if (MiniApps.conflicts.isNotEmpty()) put("id_conflicts", JSONArray(MiniApps.conflicts.map { (f, id) -> "~/mini-apps/$f: id $id belongs to an installed app, so this folder doesn't run" })) }
            .put("developer_key", keyStatus(context))
    }

    // ------------------------------------------------------------------ files

    private fun safeRel(path: String): String {
        val p = path.trim().replace('\\', '/').trimStart('/')
        if (p.isEmpty() || p.split('/').any { it == ".." || it.isEmpty() && p.isNotEmpty() }) throw ToolError("Bad path \"$path\": a path inside the app folder, without '..'")
        return p
    }

    private fun fileIn(a: MiniApp, path: String): File {
        val root = a.folder.canonicalFile
        val f = File(root, safeRel(path)).canonicalFile
        if (f.path != root.path && !f.path.startsWith(root.path + File.separator)) throw ToolError("\"$path\" is outside the app folder")
        return f
    }

    private fun create(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$").matches(id)) throw ToolError("id must be letters, digits, '.', '_' or '-' (e.g. com.alex.budget)")
        if (name.isEmpty()) throw ToolError("name is required")
        MiniApps.scan(context).firstOrNull { it.id == id }?.let { throw ToolError("The id $id is already used by ${it.name} (${if (it.signed) "installed" else show(context, it.folder)})") }
        val folderName = args.optString("folder").trim().ifEmpty { name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { id.substringAfterLast('.') } }
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(folderName)) throw ToolError("folder must be a simple folder name")
        val dir = File(MiniApps.appsDir(context), folderName)
        if (dir.exists()) throw ToolError("${show(context, dir)} already exists: pick another folder")
        dir.mkdirs()
        val manifest = JSONObject().put("manifest_version", 1).put("id", id).put("name", name).put("version", "1.0.0")
            .put("description", args.optString("description").trim()).put("entry", "index.html")
            .apply { args.optString("theme_color").trim().takeIf { it.isNotEmpty() }?.let { put("theme_color", it) } }
            .put("permissions", JSONObject())
        File(dir, "manifest.json").writeText(manifest.toString(2) + "\n")
        val title = android.text.Html.escapeHtml(name)
        File(dir, "index.html").writeText("""
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
              <title>$title</title>
              <link rel="stylesheet" href="/_antigem/ui.css">
              <link rel="stylesheet" href="style.css">
            </head>
            <body>
            <div class="ag-page">
              <header class="ag-appbar">
                <h1 class="ag-appbar-title">$title</h1>
              </header>
              <main class="ag-content">
                <div class="ag-card">
                  <div class="ag-card-title">Hello</div>
                  <p class="ag-muted">Edit index.html, app.js and style.css: this page reloads by itself when they change.</p>
                  <p class="ag-display" id="count">0</p>
                  <div class="ag-card-actions">
                    <button class="ag-btn ag-btn-text" id="reset">Reset</button>
                    <button class="ag-btn" id="add">Count</button>
                  </div>
                </div>
              </main>
            </div>
            <script src="app.js"></script>
            </body>
            </html>
        """.trimIndent() + "\n")
        File(dir, "app.js").writeText("""
            // AntiGem.storage is native and synchronous: it survives restarts and app updates.
            const countEl = document.getElementById('count');
            let count = AntiGem.storage.get('count', 0);
            const show = () => { countEl.textContent = count; };

            document.getElementById('add').addEventListener('click', () => {
              count++;
              AntiGem.storage.set('count', count);
              show();
            });

            document.getElementById('reset').addEventListener('click', async () => {
              if (await AntiGem.ui.confirm('Reset the counter?')) {
                count = 0;
                AntiGem.storage.set('count', 0);
                show();
                AntiGem.ui.toast('Reset');
              }
            });

            show();
        """.trimIndent() + "\n")
        File(dir, "style.css").writeText("#count {\n  text-align: center;\n  margin: 16px 0;\n}\n")
        MiniApps.scan(context)
        return JSONObject().put("created", id).put("folder", show(context, dir))
            .put("files", JSONArray(listOf("manifest.json", "index.html", "app.js", "style.css")))
            .put("next", "Write your files with miniapp_write_files, add an icon.png (square, 192 px or more; without one a letter icon is shown), " +
                "then miniapp_validate and miniapp_open. Read miniapp_guide topics getting-started and manifest first if you haven't.")
    }

    private fun readFile(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        val path = args.optString("path").trim()
        if (path.isEmpty()) {
            val root = a.folder.canonicalFile
            val files = JSONArray()
            root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }.filter { it.isFile }.take(1000).forEach {
                files.put(JSONObject().put("path", it.path.removePrefix(root.path + "/")).put("size", it.length()))
            }
            return json(JSONObject().put("app", a.id).put("folder", show(context, a.folder)).put("files", files))
        }
        val f = fileIn(a, path)
        if (!f.isFile) throw ToolError("No file $path in ${show(context, a.folder)}")
        val bytes = f.readBytes()
        if (bytes.take(8000).any { it == 0.toByte() }) return json(JSONObject().put("path", path).put("size", bytes.size).put("binary", true))
        val s = String(bytes)
        val offset = args.optInt("offset", 0).coerceIn(0, s.length)
        val limit = args.optInt("limit", 60_000).coerceIn(1, 200_000)
        val end = minOf(s.length, offset + limit)
        val body = s.substring(offset, end)
        return text(if (end < s.length) "$body\n\n[… ${s.length - end} more characters: call again with offset=$end]" else body)
    }

    private fun writeFiles(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        val files = args.optJSONArray("files") ?: throw ToolError("files is required")
        if (files.length() !in 1..100) throw ToolError("1–100 files per call")
        val written = JSONArray()
        // check them all before writing any
        val plan = (0 until files.length()).map { i ->
            val o = files.optJSONObject(i) ?: throw ToolError("files[$i] must be an object {path, content}")
            val f = fileIn(a, o.optString("path"))
            val data = if (o.optString("encoding") == "base64") runCatching { Base64.decode(o.optString("content"), Base64.DEFAULT) }.getOrElse { throw ToolError("files[$i]: invalid base64") }
                else o.optString("content").toByteArray()
            if (data.size > 10 * 1024 * 1024) throw ToolError("files[$i] is over 10 MB")
            if (f.isDirectory) throw ToolError("${o.optString("path")} is a folder")
            Triple(o.optString("path"), f, data)
        }
        for ((p, f, data) in plan) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.${System.nanoTime()}.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(f)) { f.writeBytes(data); tmp.delete() }
            written.put(JSONObject().put("path", p).put("size", data.size))
        }
        if (plan.any { it.first.trimStart('/') == "manifest.json" }) MiniApps.scan(context)
        return JSONObject().put("app", a.id).put("written", written)
            .put("note", if (pages(a.id).isNotEmpty()) "The open app reloads by itself." else "Open it with miniapp_open.")
    }

    private fun deleteFile(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        val f = fileIn(a, args.optString("path"))
        if (f.path == a.folder.canonicalPath) throw ToolError("That is the app folder itself")
        if (!f.exists()) throw ToolError("No such file")
        val ok = if (f.isDirectory) { if (args.optBoolean("recursive")) f.deleteRecursively() else f.delete() } else f.delete()
        if (!ok) throw ToolError(if (f.isDirectory) "Folder not empty: pass recursive=true" else "Couldn't delete it")
        return JSONObject().put("deleted", args.optString("path"))
    }

    // ------------------------------------------------------------------ checks

    private fun validate(context: Context, a: MiniApp): JSONObject {
        val errors = JSONArray()
        val warnings = JSONArray()
        val manifestFile = File(a.folder, "manifest.json")
        val m = runCatching { JSONObject(manifestFile.readText()) }.getOrElse { errors.put("manifest.json is not valid JSON: ${it.message}"); null }
        if (m != null) {
            runCatching { MiniApps.parse(a.folder, m) }.onFailure { errors.put("manifest.json: ${it.message}") }
            if (m.optInt("manifest_version", 0) != 1) warnings.put("manifest_version should be 1")
            if (m.optString("name").isBlank()) errors.put("\"name\" is missing")
            if (m.optString("version").isBlank()) errors.put("\"version\" is missing (e.g. \"1.0.0\")")
            else if (!Regex("^\\d+(\\.\\d+){0,2}([-+].*)?$").matches(m.optString("version"))) warnings.put("\"version\" isn't like 1.2.3: updates are ordered by it (or by version_code)")
            if (!m.has("permissions")) warnings.put("No \"permissions\": fine if the app needs none")
        }
        if (!File(a.folder, a.entry).isFile) errors.put("The entry file ${a.entry} doesn't exist")
        // what the parser ignored or found suspicious (list-form storage, unknown permissions, empty host lists…)
        runCatching { m?.let { MiniApps.parse(a.folder, it).warnings } }.getOrNull()?.forEach { warnings.put(it) }
        val iconName = m?.optString("icon").orEmpty().trim().trimStart('/')
        if (iconName.isEmpty()) warnings.put("No \"icon\": a letter icon is shown. Add a square PNG, 192 px or more")
        else {
            val f = File(a.folder, iconName)
            if (!f.isFile) errors.put("The icon $iconName doesn't exist")
            else {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth <= 0) errors.put("The icon $iconName isn't an image Android can read")
                else {
                    if (o.outWidth != o.outHeight) warnings.put("The icon is ${o.outWidth}×${o.outHeight}: make it square")
                    if (o.outWidth < 192) warnings.put("The icon is ${o.outWidth} px: use 192 px or more")
                }
            }
        }
        a.backgroundScript?.let { if (!File(a.folder, it).isFile) errors.put("The background script $it doesn't exist") }
        if (a.signed) warnings.put("This is an installed (signed) app: its files can't change")
        if (MiniApps.conflicts.any { it.second == a.id } && !a.signed) errors.put("An installed app owns the id ${a.id}, so this folder doesn't run: give it another id")
        MiniApps.devFolders(context).filter { it.second == a.id }.takeIf { it.size > 1 }?.let { errors.put("Several folders use the id ${a.id}: ${it.joinToString { f -> f.first.name }}") }
        // remote scripts / styles: need the network permission and fail offline
        if (!a.signed) {
            val remote = Regex("""<(?:script|link)[^>]+(?:src|href)\s*=\s*["'](https?://[^"']+)""", RegexOption.IGNORE_CASE)
            a.folder.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() in setOf("html", "htm") }.forEach { f ->
                remote.findAll(f.readText()).map { it.groupValues[1] }.distinct().take(5).forEach { url ->
                    warnings.put("${f.name} loads $url from the internet: it needs the network permission and breaks offline. Bundle it in the app instead")
                }
            }
            val size = a.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (size > 50L * 1024 * 1024) warnings.put("The app is ${size / 1024 / 1024} MB: large packages are slow to share")
        }
        a.sharedId?.let { sid ->
            val group = MiniApps.sharedGroup(context, a, fresh = true)
            warnings.put("shared_id \"$sid\": shares data/ with ${if (group.isEmpty()) "no other app yet" else group.joinToString { it.id }}")
        }
        return JSONObject().put("app", a.id).put("ok", errors.length() == 0).put("errors", errors).put("warnings", warnings)
    }

    // ------------------------------------------------------------------ run & debug

    private suspend fun open(context: Context, args: JSONObject): JSONObject {
        val a = app(context, args)
        val page = ensureOpen(context, a, foreground = true)
        val path = args.optString("path").trim().trimStart('/')
        if (path.isNotEmpty()) {
            evalRaw(page, "location.href = ${JSONObject.quote("/$path")}; true")
            delay(1200)
        }
        return JSONObject().put("opened", a.id).put("page", target(a.id)?.path ?: page.path)
            .put("note", "Check it with miniapp_screenshot, miniapp_console or miniapp_eval.")
    }

    private suspend fun reload(context: Context, args: JSONObject): JSONObject {
        val a = app(context, args)
        val open = pages(a.id)
        if (open.isEmpty()) { ensureOpen(context, a, foreground = false); return JSONObject().put("opened", a.id) }
        open.forEach { it.onFilesChanged(cssOnly = false, manifestChanged = true) }
        delay(1000)
        return JSONObject().put("reloaded", open.size)
    }

    private val levels = listOf("debug", "log", "info", "warn", "error")

    private fun console(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        val limit = args.optInt("limit", 100).coerceIn(1, 1000)
        val since = args.optLong("since", 0)
        val min = levels.indexOf(args.optString("level").ifBlank { "debug" }).coerceAtLeast(0)
        val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val now = System.currentTimeMillis()
        val entries = MiniAppConsole.entries(a.id).filter { it.time > since && levels.indexOf(it.level).let { l -> l < 0 || l >= min } }.takeLast(limit)
        val lines = JSONArray(entries.map { "${fmt.format(java.util.Date(it.time))} ${it.level.uppercase()} ${it.message}${if (it.source.isNotBlank()) "  (${it.source})" else ""}" })
        if (args.optBoolean("clear")) MiniAppConsole.clear(a.id)
        val out = JSONObject().put("app", a.id).put("now", now).put("entries", lines)
        if (entries.isEmpty() && since == 0L) {
            // nothing in memory (AntiGem restarted): the log file keeps the last runs
            val f = File(MiniApps.dataDir(context, a.id), "console.log")
            if (f.isFile) out.put("from_log_file", JSONArray(f.readLines().takeLast(limit)))
        }
        return out
    }

    private suspend fun evalRaw(page: MiniAppPageHandle, js: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> page.devEval(js) { r -> if (c.isActive) c.resume(r) } }
    }

    private suspend fun eval(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        val code = args.optString("code")
        if (code.isBlank()) throw ToolError("code is required")
        val timeout = args.optLong("timeout_ms", 10_000).coerceIn(500, 60_000)
        val page = ensureOpen(context, a, foreground = false)
        val started = System.currentTimeMillis()
        val id = JSONObject.quote("e" + System.nanoTime())
        // compiled in the page (the CSP allows it), so syntax errors come back as errors; a bare expression returns its value
        val start = """
            (function(){
              var R = window.__agDevEval || (window.__agDevEval = {}), id = $id, src = ${JSONObject.quote(code)};
              var AF = Object.getPrototypeOf(async function(){}).constructor, fn;
              try { fn = new AF('return (' + src + '\n)'); } catch (e) { try { fn = new AF(src); } catch (e2) { R[id] = { done: true, error: e2.name + ': ' + e2.message }; return 'started'; } }
              function ser(v) {
                if (v === undefined) return { type: 'undefined' };
                if (typeof Element !== 'undefined' && v instanceof Element) return { type: 'element', text: v.outerHTML.slice(0, 4000) };
                if (typeof NodeList !== 'undefined' && (v instanceof NodeList || v instanceof HTMLCollection)) v = Array.prototype.map.call(v, function (e) { return e.outerHTML ? e.outerHTML.slice(0, 1000) : String(e); });
                try { var s = JSON.stringify(v); return s === undefined ? { type: typeof v, text: String(v) } : { json: s }; } catch (e) { return { type: typeof v, text: String(v) }; }
              }
              R[id] = { done: false };
              Promise.resolve().then(function () { return fn(); }).then(
                function (v) { R[id] = { done: true, value: ser(v) }; },
                function (e) {
                  var head = (e && e.name ? e.name + ': ' : '') + (e && e.message !== undefined ? e.message : String(e));
                  R[id] = { done: true, error: e && e.stack ? (String(e.stack).indexOf(head) === 0 ? String(e.stack) : head + '\n' + e.stack) : head };
                });
              return 'started';
            })()
        """.trimIndent()
        if (evalRaw(page, start)?.contains("started") != true) throw ToolError("The page didn't run the code (it may be loading): try again")
        val poll = "(function(){var R=window.__agDevEval,r=R&&R[$id];if(!r||!r.done)return null;delete R[$id];return JSON.stringify(r)})()"
        val result = withTimeoutOrNull(timeout) {
            while (true) {
                val raw = evalRaw(page, poll)
                if (raw != null && raw != "null") return@withTimeoutOrNull JSONObject(JSONTokener(raw).nextValue() as String)
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        val logs = MiniAppConsole.entries(a.id).filter { it.time >= started }.map { "${it.level.uppercase()} ${it.message}" }
        val out = JSONObject().put("page", page.path)
        when {
            result == null -> out.put("error", "No result after ${timeout} ms (still running, or the page reloaded / navigated meanwhile)")
            result.has("error") -> out.put("error", result.getString("error"))
            else -> {
                val v = result.optJSONObject("value") ?: JSONObject()
                when {
                    v.has("json") -> out.put("result", runCatching { JSONTokener(v.getString("json")).nextValue() }.getOrDefault(v.getString("json")))
                    v.optString("type") == "undefined" -> out.put("result", JSONObject.NULL).put("type", "undefined")
                    else -> out.put("result", v.optString("text")).put("type", v.optString("type"))
                }
            }
        }
        if (logs.isNotEmpty()) out.put("console", JSONArray(logs.takeLast(100)))
        return out
    }

    private suspend fun screenshot(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        val page = ensureOpen(context, a, foreground = true)
        val bmp = page.capture() ?: throw ToolError("Couldn't capture the screen (the app may have gone to the background)")
        val scale = args.optDouble("scale", 0.5).coerceIn(0.2, 1.0)
        val scaled = if (scale < 0.999) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mimeType", "image/jpeg"))
            .put(JSONObject().put("type", "text").put("text", "${a.name}, page ${page.path}, ${scaled.width}×${scaled.height}")))
    }

    private fun permissions(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        when (args.optString("action").ifBlank { "list" }) {
            "list" -> {}
            "reset" -> { MiniAppPermissions.reset(context, a); pages(a.id).forEach { it.onFilesChanged(cssOnly = false, manifestChanged = false) } }
            "deny" -> {
                val n = args.optString("permission").trim()
                if (n !in a.permissions.keys) throw ToolError("${a.name} doesn't declare \"$n\". Declared: ${a.permissions.keys.joinToString().ifEmpty { "none" }}")
                MiniAppPermissions.set(context, a, n, false)
            }
            else -> throw ToolError("action is list, reset or deny")
        }
        val states = JSONArray()
        MiniAppPermissions.all(context, a).forEach { (n, s) ->
            val info = MiniAppPermissions.describe(a, n)
            states.put(JSONObject().put("permission", n).put("state", s.js).put("title", info.title).put("detail", info.detail)
                .put("required", n in a.required).put("sensitive", info.dangerous))
        }
        return JSONObject().put("app", a.id).put("permissions", states)
            .put("picked_folders", JSONArray(MiniAppPermissions.pickedFolders(context, a)))
            .put("note", "Only the user grants permissions, in the app's prompt or App info.")
    }

    private suspend fun clearData(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        MiniAppNet.forgetApp(a.id)
        MiniAppRuntime.clearData(context, a)
        pages(a.id).forEach { it.onFilesChanged(cssOnly = false, manifestChanged = false) }
        return JSONObject().put("cleared", a.id)
    }

    // ------------------------------------------------------------------ key, sign, install

    private fun keyStatus(context: Context): JSONObject {
        val k = MiniAppSigning.keyInfo(context) ?: return JSONObject().put("exists", false)
            .put("next", "Create one with miniapp_key_create (the user is then asked to back it up).")
        val b = MiniAppKeyBackup.info(context)
        return JSONObject().put("exists", true).put("developer", k.first).put("fingerprint", MiniAppSigning.shortFingerprint(k.second))
            .put("backed_up", b != null)
            .apply {
                if (b != null) put("backup", JSONObject().put("where", b.path).put("at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(b.at))))
                else put("warning", "The key is NOT backed up. If AntiGem is uninstalled, its data cleared or the phone lost, apps signed with it can never be updated. " +
                    "Call miniapp_key_backup: the user saves it on the phone with a password.")
            }
    }

    private suspend fun keyCreate(context: Context, args: JSONObject): JSONObject {
        MiniAppSigning.keyInfo(context)?.let { throw ToolError("This phone already has a developer key (${it.first}, ${MiniAppSigning.shortFingerprint(it.second)}). There is one key per phone: use it.") }
        val dev = args.optString("developer").trim()
        if (dev.isEmpty()) throw ToolError("developer is required")
        MiniAppSigning.createKey(context, dev)
        withContext(Dispatchers.Main) { context.startActivity(MiniAppsActivity.keyIntent(context, "backup")) }
        return keyStatus(context).put("note", "Key created. The phone now asks the user to back it up with a password: tell them to do it.")
    }

    private suspend fun keyBackup(context: Context): JSONObject {
        if (!MiniAppSigning.hasKey(context)) throw ToolError("There is no developer key yet: miniapp_key_create")
        withContext(Dispatchers.Main) { context.startActivity(MiniAppsActivity.keyIntent(context, "backup")) }
        return JSONObject().put("opened", "key backup")
            .put("note", "The backup screen is open on the phone. The user types a password there; the key is saved to Download/AntiGem and can be shared to Drive or email. " +
                "Ask them to do it, then check miniapp_key_status. Never ask for the password.")
    }

    private suspend fun sign(context: Context, args: JSONObject): JSONObject {
        val a = devApp(context, args)
        if (!MiniAppSigning.hasKey(context)) throw ToolError("No developer key on this phone: create one with miniapp_key_create first")
        val check = validate(context, a)
        if (!check.getBoolean("ok")) throw ToolError("Fix these first: ${check.getJSONArray("errors").let { e -> (0 until e.length()).joinToString("; ") { e.getString(it) } }}")
        val f = exportSignedPackage(context, a)
        val pkg = MiniAppSigning.verify(f)
        return JSONObject().put("package", show(context, f)).put("size", f.length())
            .put("id", pkg.id).put("version", pkg.version).put("version_code", pkg.versionCode)
            .put("developer", pkg.developer).put("fingerprint", MiniAppSigning.shortFingerprint(pkg.fingerprint))
            .apply { if (MiniAppKeyBackup.info(context) == null) put("warning", "The developer key is NOT backed up: call miniapp_key_backup so the user can save it. Without it this app can never be updated.") }
            .put("next", "Install it on this phone with miniapp_install (the user confirms; a package can't use the id of a development app in ~/mini-apps, " +
                "so test the installed version with a copy under another id, or delete the development folder), or share the file.")
    }

    private suspend fun install(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("package_path").trim()
        if (raw.isEmpty()) throw ToolError("package_path is required")
        val f = (if (raw == "~" || raw.startsWith("~/")) File(home(context), raw.removePrefix("~").trimStart('/')) else if (raw.startsWith("/")) File(raw) else File(MiniAppPackages.distDir(context), raw)).canonicalFile
        if (!f.isFile) throw ToolError("No file ${show(context, f)}")
        val pkg = runCatching { MiniAppSigning.verify(f) }.getOrElse { throw ToolError("Not a valid package: ${it.message}") }
        val plan = MiniAppInstaller.plan(context, pkg)
        val verdict = JSONObject().put("id", pkg.id).put("version", pkg.version)
            .put("action", plan.kind.name.lowercase()).apply { plan.previous?.let { put("installed_version", it.version) } }
        if (plan.blocked != null) return verdict.put("refused", plan.blocked).put("note", "The install screen wasn't opened.")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        val startedAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(context, MiniAppInstallActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, MiniAppSigning.MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        val verb = when (plan.kind) { MiniAppInstaller.Kind.NEW -> "Install"; MiniAppInstaller.Kind.UPDATE -> "Update"; MiniAppInstaller.Kind.REINSTALL -> "Reinstall"; MiniAppInstaller.Kind.DOWNGRADE -> "Downgrade" }
        verdict.put("opened", "The install screen is on the phone: the user taps $verb to confirm.")
        val wait = args.optInt("wait_seconds", 0).coerceIn(0, 180)
        if (wait > 0) {
            val done = withTimeoutOrNull(wait * 1000L) {
                while (true) {
                    val r = MiniAppInstaller.record(context, pkg.id)
                    if (r != null && r.updatedAt >= startedAt && r.versionCode == pkg.versionCode) return@withTimeoutOrNull true
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
            verdict.put("installed", done)
            if (!done) verdict.put("note", "Not installed within $wait s (the user may not have confirmed yet, or cancelled).")
        }
        return verdict
    }

    // ------------------------------------------------------------------ guide

    private fun docFiles(context: Context): List<String> = runCatching { context.assets.list(DOCS)?.filter { it.endsWith(".md") } }.getOrNull().orEmpty()
        .sortedWith(compareBy({ it != "README.md" }, { it }))

    private fun readDoc(context: Context, name: String): String? = runCatching { context.assets.open("$DOCS/$name").bufferedReader().use { it.readText() } }.getOrNull()

    private fun guide(context: Context, args: JSONObject): JSONObject {
        val files = docFiles(context)
        if (files.isEmpty()) {
            // a build without the docs folder: the short guide that is always bundled
            return text(runCatching { context.assets.open("mini-apps/README.md").bufferedReader().use { it.readText() } }.getOrDefault("The guide isn't bundled in this build."))
        }
        val topics = files.map { it.removeSuffix(".md") }
        val query = args.optString("query").trim()
        if (query.isNotEmpty()) {
            val hits = StringBuilder()
            var n = 0
            for (f in files) {
                val lines = readDoc(context, f)?.lines().orEmpty()
                lines.forEachIndexed { i, line ->
                    if (n < 80 && line.contains(query, ignoreCase = true)) { hits.append("${f.removeSuffix(".md")}:${i + 1}: ${line.trim().take(300)}\n"); n++ }
                }
            }
            return text(if (n == 0) "No match for \"$query\". Topics: ${topics.joinToString()}" else "Matches for \"$query\" (topic:line). Open a topic with miniapp_guide topic=<name>.\n\n$hits")
        }
        val topic = args.optString("topic").trim().removeSuffix(".md").ifEmpty { "README" }
        val file = files.firstOrNull { it.removeSuffix(".md").equals(topic, ignoreCase = true) }
            ?: throw ToolError("No topic \"$topic\". Topics: ${topics.joinToString()}")
        val s = readDoc(context, file).orEmpty()
        val offset = args.optInt("offset", 0).coerceIn(0, s.length)
        val limit = args.optInt("limit", 30_000).coerceIn(1000, 200_000)
        val end = minOf(s.length, offset + limit)
        val footer = buildString {
            if (end < s.length) append("\n\n[… ${s.length - end} more characters: miniapp_guide topic=${file.removeSuffix(".md")} offset=$end]")
            if (offset == 0 && file == "README.md") append("\n\n[Topics: ${topics.joinToString()}]")
        }
        return text(s.substring(offset, end) + footer)
    }
}
