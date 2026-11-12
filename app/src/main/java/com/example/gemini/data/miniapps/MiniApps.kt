package com.example.gemini.data.miniapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One installed mini app: a folder in ~/mini-apps with a manifest.json. */
data class MiniApp(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val folder: File,
    val entry: String,
    val iconFile: File?,
    /** Status bar / splash color from the manifest; null = follow the AntiGem theme. */
    val themeColor: Int?,
    /**
     * Declared permissions, normalized: name → spec, e.g. "network" → {"hosts": [...]}, "mcp:notes_mcp" → {},
     * "storage:/sdcard/Download" → {"path", "access"}, "storage:all" → {"full": true, "access"}. Each storage folder is
     * its own permission, so the user can allow some folders and not others.
     */
    val permissions: Map<String, JSONObject>,
    /** Permissions without which the app doesn't open at all (manifest "required_permissions", expanded). */
    val required: Set<String> = emptySet(),
    /** Access ("read" | "write" | "readwrite") for folders the user picks at runtime (AntiGem.fs.pickFolder); null = not allowed. */
    val storagePick: String? = null,
    /** What other apps may send it: [action → mime patterns], from the manifest's "intents". */
    val intents: List<Pair<String, List<String>>> = emptyList(),
    /** Installed from a signed package (else a development app: a folder in ~/mini-apps). */
    val signed: Boolean = false,
    val developer: String? = null,
    /** SHA-256 of the signing key. */
    val signer: String? = null,
    /** Installed, but its files no longer match the signature: it must not run. */
    val tampered: Boolean = false,
    /** Script that runs background tasks with the app closed ("background": "background.js"), relative to the folder. */
    val backgroundScript: String? = null,
    val backgroundModule: Boolean = false,
    /**
     * Manifest "shared_id" (like Android's sharedUserId): apps with the same one use each other's private data/
     * folder (path app:<id>/…). Installed apps only with apps signed by the same key (a package that would join a
     * group signed by another key isn't installed); development apps only with other development apps.
     */
    val sharedId: String? = null,
    /** Manifest entries that were ignored or look like a mistake (shown in the app's console and by miniapp_validate). */
    val warnings: List<String> = emptyList(),
    /** MCP servers whose calls need the user's OK each time ("ask"): server → those tools, or null for all of them. */
    val mcpAsk: Map<String, Set<String>?> = emptyMap(),
) {
    val host: String get() = MiniApps.hostFor(id)
    val origin: String get() = "https://$host"
}

/**
 * Where mini apps live and how they are found.
 * Apps: ~/mini-apps/<folder>/manifest.json. Data (outside the app folder, so the agent's edits never touch it):
 * ~/.antigem/mini-apps/<id>/ — kv.db, idb.db, files/ (the app's private "data/" folder), console.log, net-cache/.
 */
object MiniApps {
    private const val TAG = "MiniApps"
    const val SDK_VERSION = 1
    /** Every app gets its own origin under this (reserved, never resolvable) domain, so web storage never mixes. */
    const val DOMAIN = "antigem.invalid"
    private val ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    val KNOWN_PERMISSIONS = listOf("network", "localhost", "storage", "notifications", "background", "clipboard", "camera", "microphone")

    fun home(context: Context): File = LocalEnvironmentManager.getHomeDir(context)
    fun appsDir(context: Context) = File(home(context), "mini-apps")
    fun dataRoot(context: Context) = File(home(context), ".antigem/mini-apps")
    fun dataDir(context: Context, id: String) = File(dataRoot(context), id)
    fun filesDir(context: Context, id: String) = File(dataDir(context, id), "files").also { it.mkdirs() }

    fun hostFor(id: String): String {
        var slug = id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.length > 50) slug = slug.take(40) + "-" + Integer.toHexString(id.hashCode())
        return "${slug.ifEmpty { "app" }}.$DOMAIN"
    }

    @Volatile private var cached: List<MiniApp> = emptyList()

    /** Reads every ~/mini-apps/<folder>/manifest.json. Folders with a broken manifest are skipped (and logged). */
    /** Development folders whose id belongs to an installed (signed) app: they don't run. (folder name, id) */
    @Volatile var conflicts: List<Pair<String, String>> = emptyList()
    /** Folders in ~/mini-apps that aren't apps right now, and why (bad manifest, duplicate id): shown in Mini Apps. */
    @Volatile var problems: List<Pair<String, String>> = emptyList()
        private set

    /**
     * Installed (signed) apps first, they own their ids; then development apps from ~/mini-apps. An installed app whose
     * manifest no longer matches its signature is marked tampered (it won't run).
     */
    fun scan(context: Context): List<MiniApp> {
        val apps = LinkedHashMap<String, MiniApp>()
        for ((id, rec) in MiniAppInstaller.records(context)) {
            val dir = MiniAppInstaller.appDir(context, id)
            val manifest = File(dir, "manifest.json")
            runCatching { parse(dir, JSONObject(manifest.readText())) }
                .onSuccess { app ->
                    val intact = app.id == id && MiniAppInstaller.isIntact(context, app, "manifest.json", manifest)
                    apps[id] = app.copy(id = id, signed = true, developer = rec.developer, signer = rec.fingerprint, tampered = !intact)
                }
                .onFailure { Log.w(TAG, "Installed app $id is broken: ${it.message}") }
        }
        val clashes = ArrayList<Pair<String, String>>()
        val broken = ArrayList<Pair<String, String>>()
        for ((folder, _) in devFolders(context)) {
            runCatching { parse(folder, JSONObject(File(folder, "manifest.json").readText())) }
                .onSuccess { app ->
                    when {
                        apps[app.id]?.signed == true -> clashes += folder.name to app.id
                        apps.containsKey(app.id) -> {
                            Log.w(TAG, "Duplicate app id ${app.id} in ${folder.name}, ignored")
                            broken += folder.name to "its id ${app.id} is already used by ~/mini-apps/${apps[app.id]?.folder?.name}"
                        }
                        else -> apps[app.id] = app
                    }
                }
                .onFailure {
                    Log.w(TAG, "Bad manifest in ${folder.name}: ${it.message}")
                    broken += folder.name to "manifest.json: ${it.message ?: "invalid"}"
                }
        }
        conflicts = clashes
        problems = broken
        return apps.values.toList().also { cached = it }
    }

    /** Development app folders in ~/mini-apps with the id their manifest declares. */
    fun devFolders(context: Context): List<Pair<File, String>> =
        appsDir(context).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && File(it, "manifest.json").isFile }
            ?.sortedBy { it.name.lowercase() }
            // a manifest that isn't valid JSON still counts as a folder (with no id), so scan reports it
            ?.map { f -> f to runCatching { JSONObject(File(f, "manifest.json").readText()).optString("id") }.getOrDefault("") }
            .orEmpty()

    /** Developer mode (Mini Apps → </>): development apps run, with the developer tools. On by default in the beta. */
    fun devMode(context: Context) = context.getSharedPreferences("mini_apps", Context.MODE_PRIVATE).getBoolean("dev_mode", true)

    fun find(context: Context, id: String): MiniApp? {
        val app = cached.firstOrNull { it.id == id } ?: scan(context).firstOrNull { it.id == id } ?: return null
        // an installed app is checked every time it is looked up (cheap: the hash is cached per size + mtime), so a
        // manifest edited after install is caught even when the list above is old
        if (app.signed && !app.tampered && !MiniAppInstaller.isIntact(context, app, "manifest.json", File(app.folder, "manifest.json"))) return app.copy(tampered = true)
        return app
    }

    /**
     * May [app] use [other]'s data/ folder? Same "shared_id", and both installed with the same signing key, or both
     * development apps (a development app never reaches an installed app's data, nor the other way round).
     */
    fun sharesData(app: MiniApp, other: MiniApp): Boolean {
        val sid = app.sharedId ?: return false
        if (other.id == app.id || other.sharedId != sid || app.tampered || other.tampered) return false
        return if (app.signed) other.signed && app.signer != null && other.signer == app.signer else !other.signed
    }

    /** The other apps whose data/ folder [app] can use ([fresh]: rescan, for screens; else the last scan). */
    fun sharedGroup(context: Context, app: MiniApp, fresh: Boolean = false): List<MiniApp> =
        (if (fresh || cached.isEmpty()) scan(context) else cached).filter { sharesData(app, it) }

    /** Re-reads one app's manifest (after the agent edited it). */
    fun reload(context: Context, app: MiniApp): MiniApp = if (app.signed) (scan(context).firstOrNull { it.id == app.id } ?: app) else
        runCatching { parse(app.folder, JSONObject(File(app.folder, "manifest.json").readText())) }.getOrNull()?.takeIf { it.id == app.id } ?: app

    fun parse(folder: File, m: JSONObject): MiniApp {
        val id = m.optString("id").trim()
        require(ID_RE.matches(id)) { "\"id\" must be letters, digits, '.', '_' or '-' (got \"$id\")" }
        val entry = m.optString("entry").ifBlank { "index.html" }.trimStart('/')
        val warnings = ArrayList<String>()
        val mcpAsk = LinkedHashMap<String, Set<String>?>()
        val perms = parsePermissions(m.opt("permissions"), warnings, mcpAsk)
        val icon = m.optString("icon").trim().trimStart('/').takeIf { it.isNotEmpty() }?.let { File(folder, it) }?.takeIf { it.isFile }
        return MiniApp(
            id = id,
            name = m.optString("name").ifBlank { folder.name },
            version = m.optString("version").ifBlank { "0.0.0" },
            description = m.optString("description"),
            folder = folder,
            entry = entry,
            iconFile = icon,
            themeColor = m.optString("theme_color").let { if (it.isBlank() || it == "auto") null else parseColor(it) },
            permissions = perms,
            required = parseRequired(m.optJSONArray("required_permissions"), perms),
            storagePick = m.optJSONObject("permissions")?.optJSONObject("storage")?.opt("pick")
                ?.takeIf { it != false && it != JSONObject.NULL }?.let { if (it == true) "read" else normalizeAccess(it.toString()) },
            // "background": "background.js" or {"script": "background.js", "type": "module"}
            backgroundScript = (m.optJSONObject("background")?.optString("script") ?: m.optString("background")).trim().trimStart('/')
                .takeIf { it.isNotEmpty() && !it.split('/').any { p -> p == ".." || p.startsWith(".") } },
            backgroundModule = m.optJSONObject("background")?.optString("type") == "module",
            warnings = warnings,
            mcpAsk = mcpAsk,
            sharedId = m.optString("shared_id").trim().takeIf { it.isNotEmpty() }?.also {
                require(ID_RE.matches(it)) { "\"shared_id\" must be letters, digits, '.', '_' or '-' (got \"$it\")" }
            },
            // "intents": [{"action": "share", "types": ["image/*", "text/plain"]}]
            intents = m.optJSONArray("intents")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
                    o.optString("action").ifBlank { "share" } to (o.optJSONArray("types")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: listOf("*/*"))
                }
            }.orEmpty(),
        )
    }

    /** "read" | "write" | "readwrite" (also "rw", "both", "read-write"); anything else is read only. */
    fun normalizeAccess(a: String?): String = when (a?.trim()?.lowercase()) {
        "write", "w" -> "write"
        "readwrite", "read-write", "rw", "both", "read_write" -> "readwrite"
        else -> "read"
    }

    /**
     * Both forms: ["network", "notifications"] or {"network": {"hosts": [...]}, "mcp": ["notes_mcp"], ...}.
     * "storage": true | "full" | {"full": true, "access"} → "storage:all"; {"folders": [{"path", "access"}]} → one
     * "storage:<path>" per folder.
     */
    private fun parsePermissions(raw: Any?, warnings: MutableList<String>, mcpAsk: MutableMap<String, Set<String>?> = LinkedHashMap()): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        val nameRe = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
        /**
         * One MCP server: true / "*" (every tool), "read" (its read-only tools), ["tool", …] (those tools, each its own
         * permission), or {"tools": any of those, "ask": true | ["tool", …]} (the user's OK before each call).
         */
        fun mcpServer(server: String, mode: Any?) {
            val sv = server.trim()
            if (!nameRe.matches(sv)) { warnings += "MCP server name \"$server\" is invalid: ignored"; return }
            if (sv in MiniAppMcpAccess.BLOCKED) { warnings += "\"$sv\" can't be used by mini apps: ignored"; return }
            when (mode) {
                true, "*", "all" -> out["mcp:$sv"] = JSONObject()
                "read" -> out["mcp:$sv"] = JSONObject().put("readOnly", true)
                is JSONArray -> {
                    if (mode.length() == 0) warnings += "\"mcp\" → \"$sv\" lists no tools: nothing is declared"
                    for (i in 0 until mode.length()) {
                        val t = mode.optString(i).trim()
                        if (nameRe.matches(t)) out["mcp:$sv/$t"] = JSONObject() else warnings += "MCP tool name \"$t\" is invalid: ignored"
                    }
                }
                is JSONObject -> {
                    mcpServer(sv, mode.opt("tools") ?: true)
                    when (val ask = mode.opt("ask")) {
                        true -> mcpAsk[sv] = null
                        is JSONArray -> mcpAsk[sv] = (0 until ask.length()).map { ask.optString(it).trim() }.filter { it.isNotEmpty() }.toSet()
                        null, false, JSONObject.NULL -> {}
                        else -> warnings += "\"ask\" for \"$sv\" must be true or a list of tools: ignored"
                    }
                }
                false, null, JSONObject.NULL -> {}
                else -> warnings += "\"mcp\" → \"$sv\" must be true, \"read\", a list of tools or {\"tools\", \"ask\"}: ignored"
            }
        }
        fun storage(spec: Any?, listForm: Boolean) {
            // ["storage"] would silently mean "all files, read and write": folders must be named
            if (listForm) { warnings += "\"storage\" in the list form is ignored: use {\"storage\": {\"folders\": [{\"path\": …, \"access\": …}]}} (or \"storage\": true for all files)"; return }
            when (spec) {
                true, "full", "all" -> out["storage:all"] = JSONObject().put("full", true).put("access", "readwrite")
                is JSONObject -> {
                    val full = spec.opt("full")
                    if (full == true || full is String && full.isNotBlank())
                        out["storage:all"] = JSONObject().put("full", true).put("access", normalizeAccess(if (full is String) full else spec.optString("access", "readwrite")))
                    val folders = spec.optJSONArray("folders")
                    if (folders != null) for (i in 0 until folders.length()) {
                        val o = folders.opt(i)
                        val path = (if (o is JSONObject) o.optString("path") else o?.toString()).orEmpty().trim().let { if (it.length > 1) it.trimEnd('/') else it }
                        if (path.isEmpty()) continue
                        out["storage:$path"] = JSONObject().put("path", path).put("access", normalizeAccess((o as? JSONObject)?.optString("access")))
                    }
                }
            }
        }
        fun add(name: String, spec: Any?, listForm: Boolean = false) {
            val n = name.trim()
            when {
                n == "mcp" -> when (spec) {
                    // ["notes_mcp"]: whole servers (prefer naming the tools: {"notes_mcp": ["notes_list"]})
                    is JSONArray -> for (i in 0 until spec.length()) spec.optString(i).trim().takeIf { it.isNotEmpty() }?.let { mcpServer(it, true) }
                    is String -> if (spec.isNotBlank()) mcpServer(spec, true)
                    is JSONObject -> spec.keys().forEach { mcpServer(it, spec.opt(it)) }
                    false, null, JSONObject.NULL -> {}
                    else -> warnings += "\"mcp\" needs server names, e.g. \"mcp\": {\"notes_mcp\": [\"notes_list\"]}: ignored"
                }
                n.startsWith("mcp:") && n.length > 4 -> when (spec) {
                    false, null, JSONObject.NULL -> {}
                    else -> n.removePrefix("mcp:").let { rest -> if ('/' in rest) mcpServer(rest.substringBefore('/'), JSONArray().put(rest.substringAfter('/'))) else mcpServer(rest, if (spec is JSONArray || spec == "read") spec else true) }
                }
                n == "storage" -> storage(spec, listForm)
                n in KNOWN_PERMISSIONS -> when (spec) {
                    false, null, JSONObject.NULL -> {}
                    is JSONObject -> {
                        out[n] = spec
                        if (n == "network" && spec.optJSONArray("hosts")?.length() == 0) warnings += "network.hosts is empty: the app can't reach any host (leave \"hosts\" out for any host)"
                        if (n == "localhost" && spec.optJSONArray("ports")?.length() == 0) warnings += "localhost.ports is empty: no local port is reachable (leave \"ports\" out for any port)"
                    }
                    else -> out[n] = JSONObject()
                }
                else -> warnings += "Unknown permission \"$n\" ignored (known: ${(KNOWN_PERMISSIONS + "mcp").joinToString()})"
            }
        }
        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) raw.optString(i).takeIf { it.isNotBlank() }?.let { add(it, true, listForm = true) }
            is JSONObject -> raw.keys().forEach { add(it, raw.opt(it)) }
        }
        return out
    }

    /** "storage" means every declared folder (and full access), "mcp" every declared MCP server. */
    private fun parseRequired(raw: JSONArray?, perms: Map<String, JSONObject>): Set<String> {
        if (raw == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until raw.length()) {
            val n = raw.optString(i).trim()
            when {
                n == "storage" -> out += perms.keys.filter { it.startsWith("storage:") }
                n == "mcp" -> out += perms.keys.filter { it.startsWith("mcp:") }
                n in perms -> out += n
            }
        }
        return out
    }

    fun parseColor(text: String): Int? = runCatching {
        val t = text.trim()
        if (t.startsWith("#") && t.length == 4) {
            // #rgb → #rrggbb
            android.graphics.Color.parseColor("#" + t.drop(1).map { "$it$it" }.joinToString(""))
        } else if (t.startsWith("#") && t.length == 9) {
            // CSS order #rrggbbaa → Android #aarrggbb
            android.graphics.Color.parseColor("#" + t.substring(7, 9) + t.substring(1, 7))
        } else android.graphics.Color.parseColor(t)
    }.getOrNull()

    /** The app's icon, or a rounded letter tile in its color. */
    fun iconBitmap(app: MiniApp, sizePx: Int = 192): Bitmap {
        app.iconFile?.let { f ->
            runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { return Bitmap.createScaledBitmap(it, sizePx, sizePx, true) }
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = app.themeColor ?: 0xFF5B5BD6.toInt() }
        c.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), sizePx * 0.26f, sizePx * 0.26f, p)
        p.color = android.graphics.Color.WHITE; p.textSize = sizePx * 0.5f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        val letter = app.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        c.drawText(letter, sizePx / 2f, sizePx / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /**
     * Copies the bundled sample apps (assets/mini-apps/<folder>) into ~/mini-apps once each, so deleting a sample
     * keeps it deleted. README.md (the agent guide) is refreshed whenever the bundled copy changes.
     */
    fun installSamples(context: Context) {
        val assets = context.assets
        val dir = appsDir(context).also { it.mkdirs() }
        val prefs = context.getSharedPreferences("mini_apps", Context.MODE_PRIVATE)
        val done = prefs.getStringSet("installed_samples", emptySet()).orEmpty().toMutableSet()
        val names = runCatching { assets.list("mini-apps")?.toList() }.getOrNull().orEmpty()
        for (name in names) {
            val path = "mini-apps/$name"
            val children = runCatching { assets.list(path) }.getOrNull().orEmpty()
            if (children.isEmpty()) {
                // a file at the top level (README.md)
                runCatching {
                    val bytes = assets.open(path).use { it.readBytes() }
                    val target = File(dir, name)
                    if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
                }
                continue
            }
            // the PC signing tool follows this AntiGem version (it isn't an app the user owns)
            if (name == "tools") { runCatching { syncAssetDir(context, path, File(dir, name)) }; continue }
            if (name in done) continue
            val target = File(dir, name)
            if (!target.exists()) runCatching { copyAssetDir(context, path, target) }.onFailure { Log.w(TAG, "Sample $name: ${it.message}") }
            done += name
        }
        prefs.edit().putStringSet("installed_samples", done).apply()
        // the full guide, in step with this version of AntiGem (the developer MCP serves the same pages)
        runCatching { syncAssetDir(context, "mini-app-docs", File(dir, "docs")) }.onFailure { Log.w(TAG, "Docs: ${it.message}") }
    }

    /** Copies an asset folder, rewriting only files whose contents changed. */
    private fun syncAssetDir(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            val bytes = context.assets.open(path).use { it.readBytes() }
            if (!target.isFile || !target.readBytes().contentEquals(bytes)) { target.parentFile?.mkdirs(); target.writeBytes(bytes) }
            return
        }
        children.forEach { syncAssetDir(context, "$path/$it", File(target, it)) }
    }

    private fun copyAssetDir(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { copyAssetDir(context, "$path/$it", File(target, it)) }
    }
}

/** An error that reaches JS as a DOMException with this name (NotAllowedError, NotFoundError, …). */
class MiniAppError(val errorName: String, message: String) : Exception(message)
