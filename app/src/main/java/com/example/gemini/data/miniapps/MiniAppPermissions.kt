package com.example.gemini.data.miniapps

import android.content.Context
import com.example.gemini.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The mini app permission system (a small Android): an app can only ask for what its manifest declares, the user
 * grants or denies each one, and every native module checks here before doing anything.
 *
 * A grant is tied to the exact declaration it was given for: if the manifest later widens it (more hosts, more
 * folders), the permission goes back to "prompt".
 */
object MiniAppPermissions {
    enum class State(val js: String) { GRANTED("granted"), DENIED("denied"), PROMPT("prompt"), UNDECLARED("undeclared") }

    /** AntiGem's own local servers: never reachable from a mini app, even with the localhost permission. */
    val BLOCKED_PORTS: Set<Int> get() = setOf(
        BuildConfig.BRIDGE_PORT, BuildConfig.HUB_PORT, BuildConfig.BROWSER_MCP_PORT, BuildConfig.BROWSER_MCP_PORT + 100,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("mini_app_permissions", Context.MODE_PRIVATE)
    private fun stored(context: Context, app: MiniApp): JSONObject =
        runCatching { JSONObject(prefs(context).getString(app.id, "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun state(context: Context, app: MiniApp, name: String): State {
        // "storage" on its own: all storage entries together (any prompt → prompt, all granted → granted)
        if (name == "storage" && name !in app.permissions) {
            val parts = app.permissions.keys.filter { it.startsWith("storage:") }.map { state(context, app, it) }
            return when {
                parts.isEmpty() -> if (pickedFolders(context, app).isNotEmpty()) State.GRANTED else State.UNDECLARED
                State.PROMPT in parts -> State.PROMPT
                // only when every folder is allowed: with some denied the app can't count on "storage" (it can ask per folder)
                parts.all { it == State.GRANTED } -> State.GRANTED
                else -> State.DENIED
            }
        }
        val spec = app.permissions[name] ?: return State.UNDECLARED
        val entry = stored(context, app).optJSONObject(name) ?: return State.PROMPT
        val f = entry.optString("f")
        if (f != spec.toString()) {
            // the manifest changed: the choice still holds if the permission now asks for the same or less
            val then = runCatching { JSONObject(f) }.getOrNull() ?: return State.PROMPT
            if (!covers(name, then, spec)) return State.PROMPT
        }
        return if (entry.optString("s") == "granted") State.GRANTED else State.DENIED
    }

    /**
     * Does a choice made for the spec [then] cover the spec [now]? Narrowing (fewer hosts, ports or access) and
     * reordering keep it; anything wider asks the user again.
     */
    private fun covers(name: String, then: JSONObject, now: JSONObject): Boolean {
        fun strs(o: JSONObject, k: String): Set<String>? = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() }.toSet() }
        fun ints(o: JSONObject, k: String): Set<Int>? = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() }
        // a ⊆ b for "read" | "write" | "readwrite"
        fun accessWithin(a: String, b: String) = a == b || b == "readwrite"
        return when {
            name == "network" -> {
                val was = strs(then, "hosts"); val isNow = strs(now, "hosts")
                when {
                    was == null || "*" in was -> true // any host was allowed
                    isNow == null || "*" in isNow -> false
                    else -> isNow.all { h -> h in was || was.any { p -> p.startsWith("*.") && h.endsWith(p.substring(1)) } }
                }
            }
            name == "localhost" -> {
                val was = ints(then, "ports"); val isNow = ints(now, "ports")
                was == null || (isNow != null && was.containsAll(isNow))
            }
            // a whole server covers its read-only part
            name.startsWith("mcp:") && '/' !in name -> !then.optBoolean("readOnly") || now.optBoolean("readOnly")
            name.startsWith("storage:") -> then.optString("path") == now.optString("path") && then.optBoolean("full") == now.optBoolean("full") &&
                accessWithin(MiniApps.normalizeAccess(now.optString("access")), MiniApps.normalizeAccess(then.optString("access")))
            else -> canonical(then) == canonical(now)
        }
    }

    /** JSON with sorted keys and sorted arrays: two specs that only differ in order compare equal. */
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.opt(it)) }
        is org.json.JSONArray -> (0 until v.length()).map { canonical(v.opt(it)) }.sorted().joinToString(",", "[", "]")
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    fun isGranted(context: Context, app: MiniApp, name: String) = state(context, app, name) == State.GRANTED

    fun set(context: Context, app: MiniApp, name: String, granted: Boolean) {
        val spec = app.permissions[name] ?: return
        val all = stored(context, app)
        all.put(name, JSONObject().put("s", if (granted) "granted" else "denied").put("f", spec.toString()))
        prefs(context).edit().putString(app.id, all.toString()).apply()
    }

    fun reset(context: Context, app: MiniApp) {
        prefs(context).edit().remove(app.id).apply()
        MiniAppMcpAccess.reset(context, app.id)
        grantPrefs(context).edit().remove(app.id).apply()
        removePickedFolder(context, app, null)
    }

    fun all(context: Context, app: MiniApp): Map<String, State> = app.permissions.keys.associateWith { state(context, app, it) }

    fun pending(context: Context, app: MiniApp): List<String> = app.permissions.keys.filter { state(context, app, it) == State.PROMPT }

    /** Required permissions not granted yet: the app must not run until they are. */
    fun missingRequired(context: Context, app: MiniApp): List<String> = app.required.filter { state(context, app, it) != State.GRANTED }

    /** The phrase the user types to give an app broad file access. */
    const val DANGER_PHRASE = "iknowiamstupid"

    /**
     * Needs the typed confirmation: all files, or a folder that is (or contains) the home folder or the root of shared
     * storage, e.g. "~/" or "/sdcard". Granting those exposes everything below them.
     */
    fun needsTypedConfirm(context: Context, app: MiniApp, name: String): Boolean {
        if (name == "storage:all") return true
        // tools that run commands, drive the phone or read the browser
        if (name.startsWith("mcp:")) return MiniAppMcpAccess.isSensitive(name.removePrefix("mcp:").substringBefore('/'))
        val path = app.permissions[name]?.optString("path")?.takeIf { name.startsWith("storage:") } ?: return false
        return isBroadFolder(context, runCatching { toFile(context, app, path) }.getOrNull() ?: return true)
    }

    fun isBroadFolder(context: Context, f: File): Boolean {
        val home = MiniApps.home(context).canonicalFile
        return listOf(home, sharedRoot).any { root -> f.path == root.path || within(root, f) } || f.path == "/storage" || f.path == "/"
    }

    fun require(context: Context, app: MiniApp, name: String) {
        if (!isGranted(context, app, name)) throw MiniAppError("NotAllowedError", "\"$name\" permission is ${state(context, app, name).js}")
    }

    // ---------- descriptions for the prompts and App info ----------

    class Info(val title: String, val detail: String, val dangerous: Boolean)

    fun describe(app: MiniApp, name: String): Info {
        val spec = app.permissions[name] ?: JSONObject()
        return when {
            name == "network" -> {
                // no list: any host; an empty list: none (the same rule as checkNetwork)
                val hosts = spec.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                when {
                    hosts == null || "*" in hosts -> Info("Internet", "Connect to any website or server", true)
                    hosts.isEmpty() -> Info("Internet", "No website (the app's list of sites is empty)", false)
                    else -> Info("Internet", "Only: " + hosts.joinToString(", "), false)
                }
            }
            name == "localhost" -> {
                val ports = spec.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
                Info("Local servers", when {
                    ports == null -> "Connect to servers running on this phone (localhost)"
                    ports.isEmpty() -> "No local server (the app's list of ports is empty)"
                    else -> "localhost ports: " + ports.joinToString(", ")
                }, false)
            }
            name == "storage:all" -> Info("All files", "${accessText(spec.optString("access"))} everything in your shared storage and home folder", true)
            name.startsWith("storage:") -> {
                val path = spec.optString("path")
                Info("Folder $path", accessText(spec.optString("access")), spec.optString("access") != "read")
            }
            name == "notifications" -> Info("Notifications", "Show notifications and reminders", false)
            name == "background" -> Info("Run in the background", "Do tasks while closed, up to ${MiniAppScheduler.MAX_RUNS_PER_DAY} times a day", false)
            name == "clipboard" -> Info("Clipboard", "Read what you copied", false)
            name == "camera" -> Info("Camera", "Take pictures and video", true)
            name == "microphone" -> Info("Microphone", "Record audio", true)
            name.startsWith("mcp:") -> {
                val server = name.removePrefix("mcp:").substringBefore('/')
                val tool = name.substringAfter('/', "")
                val sensitive = MiniAppMcpAccess.isSensitive(server)
                when {
                    tool.isNotEmpty() -> Info("$server: $tool", if (sensitive) "Use the $tool tool (it can act on your phone)" else "Use the $tool tool", sensitive)
                    spec.optBoolean("readOnly") -> Info("AntiGem tools: $server", "Only its read-only tools", sensitive)
                    else -> Info("AntiGem tools: $server", if (sensitive) "Every $server tool: it can control parts of your phone" else "Every $server tool", sensitive)
                }
            }
            else -> Info(name, "", false)
        }
    }

    fun accessText(access: String?) = when (MiniApps.normalizeAccess(access)) { "write" -> "Write (not read)"; "readwrite" -> "Read and change"; else -> "Read only" }

    // ---------- network ----------

    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        return h == "localhost" || h.endsWith(".localhost") || h == "::1" || h == "0.0.0.0" || h.startsWith("127.")
    }

    /** Null when the app may connect to host:port, else why not. */
    fun checkNetwork(context: Context, app: MiniApp, host: String, port: Int): String? {
        if (isLoopbackHost(host)) return checkLoopback(context, app, port)
        if (!isGranted(context, app, "network")) return "the app has no \"network\" permission"
        // no "hosts" list: any host; a list (even an empty one): only those
        val hosts = app.permissions["network"]?.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase().trim() } } ?: return null
        if ("*" in hosts) return null
        if (hosts.isEmpty()) return "the manifest's network hosts list is empty"
        val h = host.lowercase()
        val ok = hosts.any { p -> if (p.startsWith("*.")) h.endsWith(p.substring(1)) else h == p }
        return if (ok) null else "$host is not in the manifest's network hosts"
    }

    fun checkLoopback(context: Context, app: MiniApp, port: Int): String? {
        if (!isGranted(context, app, "localhost")) return "the app has no \"localhost\" permission"
        if (port in BLOCKED_PORTS) return "port $port belongs to AntiGem"
        // no "ports" list: any port (but AntiGem's own); a list (even an empty one): only those
        val ports = app.permissions["localhost"]?.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: return null
        return if (port in ports) null else if (ports.isEmpty()) "the manifest's localhost ports list is empty" else "localhost port $port is not in the manifest"
    }

    // ---------- storage ----------

    private val sharedRoot: File by lazy { runCatching { File("/storage/emulated/0").canonicalFile }.getOrDefault(File("/storage/emulated/0")) }

    private fun within(f: File, root: File) = f.path == root.path || f.path.startsWith(root.path + File.separator)

    /** What a file operation needs from the folder it touches. META (exists / stat) is fine with either. */
    enum class Need { READ, WRITE, META }

    private fun allows(access: String, need: Need) = when (need) {
        Need.READ -> access == "read" || access == "readwrite"
        Need.WRITE -> access == "write" || access == "readwrite"
        Need.META -> true
    }

    /** A path from JS as a real file: "data/…" (own folder), "~/…" (home), "/sdcard/…" or another absolute path. Symlinks resolved. */
    fun toFile(context: Context, app: MiniApp, raw: String): File {
        val p = raw.trim()
        val f = when {
            p == "data" || p.startsWith("data/") -> File(MiniApps.filesDir(context, app.id), p.removePrefix("data").trimStart('/'))
            // app:<id>/…: the data/ folder of an app with the same shared_id (checked in resolve, like any other path)
            p.startsWith("app:") -> {
                val id = p.removePrefix("app:").substringBefore('/')
                if (!APP_ID.matches(id)) throw MiniAppError("TypeError", "app:<id>/… needs an app id (got \"$id\")")
                val rest = p.removePrefix("app:$id").trimStart('/')
                if (id == app.id) File(MiniApps.filesDir(context, app.id), rest) else File(File(MiniApps.dataDir(context, id), "files"), rest)
            }
            p == "~" || p.startsWith("~/") -> File(MiniApps.home(context), p.removePrefix("~").trimStart('/'))
            p.startsWith("/") -> File(p)
            else -> throw MiniAppError("TypeError", "Paths start with data/ (the app's own folder), app:<id>/ (an app sharing data with it), ~/ or /")
        }
        // canonical: "..", and every symlink on the way, resolved to where the path really points
        return f.canonicalFile
    }

    /** Never reachable, whatever was granted: other apps' data and code, and the dot folders in home (keys, logins). */
    fun isProtected(context: Context, f: File): Boolean {
        val home = MiniApps.home(context).canonicalFile
        if (within(f, MiniApps.dataRoot(context).canonicalFile)) return true
        if (within(f, MiniApps.appsDir(context).canonicalFile)) return true
        if (within(f, home) && f.path != home.path) {
            val first = f.path.removePrefix(home.path + File.separator).substringBefore(File.separator)
            if (first.startsWith(".")) return true
        }
        return !within(f, home) && !within(f, sharedRoot)
    }

    class Grant(val root: File, val access: String, val source: String, val name: String)

    /** Every folder the app may use outside its own: granted manifest folders, all files, and folders the user picked. */
    fun grants(context: Context, app: MiniApp): List<Grant> {
        val out = ArrayList<Grant>()
        for ((name, spec) in app.permissions) {
            if (!name.startsWith("storage:") || state(context, app, name) != State.GRANTED) continue
            val access = MiniApps.normalizeAccess(spec.optString("access"))
            if (name == "storage:all") {
                out += Grant(MiniApps.home(context).canonicalFile, access, "manifest", name)
                out += Grant(sharedRoot, access, "manifest", name)
            } else runCatching { toFile(context, app, spec.optString("path")) }.getOrNull()?.let { out += Grant(it, access, "manifest", name) }
        }
        pickedFolders(context, app).forEach { out += Grant(File(it.optString("path")), it.optString("access"), "picked", "picked:" + it.optString("path")) }
        return out
    }

    // ---------- one-file grants (a file another app shared with this one, like a content URI grant) ----------

    private fun grantPrefs(context: Context) = context.getSharedPreferences("mini_app_file_grants", Context.MODE_PRIVATE)

    /** [app] may read exactly [f] for a day (the user sent it there). */
    fun grantFile(context: Context, app: MiniApp, f: File) {
        val now = System.currentTimeMillis()
        val a = runCatching { JSONArray(grantPrefs(context).getString(app.id, "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optLong("until") > now && it.optString("path") != f.path }?.let { out.put(it) }
        out.put(JSONObject().put("path", f.path).put("until", now + 86_400_000L))
        grantPrefs(context).edit().putString(app.id, out.toString()).apply()
    }

    private fun hasFileGrant(context: Context, app: MiniApp, f: File): Boolean {
        val a = runCatching { JSONArray(grantPrefs(context).getString(app.id, "[]")) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        return (0 until a.length()).any { a.optJSONObject(it)?.let { g -> g.optString("path") == f.path && g.optLong("until") > now } == true }
    }

    /** Checks a file access; returns the real file (symlinks resolved) or throws NotAllowedError. */
    fun resolve(context: Context, app: MiniApp, raw: String, need: Need): File {
        val f = toFile(context, app, raw)
        val own = MiniApps.filesDir(context, app.id).canonicalFile
        if (within(f, own)) return f
        // the data/ folder of an app in the same shared group: like its own (same developer, same trust)
        if (sharedOwner(context, app, f) != null) return f
        if (need != Need.WRITE && hasFileGrant(context, app, f)) return f
        if (isProtected(context, f)) throw MiniAppError("NotAllowedError", "$raw is protected")
        val inside = grants(context, app).filter { within(f, it.root) }
        if (inside.any { allows(it.access, need) }) return f
        throw MiniAppError("NotAllowedError", when {
            inside.isNotEmpty() -> "$raw: the app may only ${if (need == Need.WRITE) "read" else "write"} there"
            app.permissions.keys.none { it.startsWith("storage:") } && app.storagePick == null -> "Reading or writing $raw needs a \"storage\" folder in the manifest"
            else -> "$raw is not in a folder you allowed this app"
        })
    }

    private val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    /** What both [a] and [b] allow ("read" | "write" | "readwrite"), or null when they have nothing in common. */
    private fun intersectAccess(a: String, b: String): String? = when {
        a == b -> a
        a == "readwrite" -> b
        b == "readwrite" -> a
        else -> null
    }

    /** The other app is still really there with this shared_id (the app list can be older than the disk). */
    private fun stillShares(context: Context, other: MiniApp): Boolean =
        if (other.signed) MiniAppInstaller.record(context, other.id) != null
        else runCatching {
            val m = JSONObject(File(other.folder, "manifest.json").readText())
            m.optString("id") == other.id && m.optString("shared_id").trim() == other.sharedId
        }.getOrDefault(false)

    /** The app of [app]'s shared group whose data/ folder holds [f] (canonical), or null. */
    fun sharedOwner(context: Context, app: MiniApp, f: File): MiniApp? {
        if (app.sharedId == null) return null
        val root = MiniApps.dataRoot(context).canonicalFile
        if (!within(f, root) || f.path == root.path) return null
        val parts = f.path.removePrefix(root.path + File.separator).split(File.separator)
        if (parts.size < 2 || parts[1] != "files") return null
        val other = MiniApps.find(context, parts[0]) ?: return null
        if (!MiniApps.sharesData(app, other) || !stillShares(context, other)) return null
        return other.takeIf { within(f, File(MiniApps.dataDir(context, other.id), "files").canonicalFile) }
    }

    /** Roots that may never be deleted or moved as a whole. */
    fun isRoot(context: Context, app: MiniApp, f: File): Boolean {
        if (sharedOwner(context, app, f)?.let { f.path == File(MiniApps.dataDir(context, it.id), "files").canonicalPath } == true) return true
        if (f.path == MiniApps.filesDir(context, app.id).canonicalPath || f.path == sharedRoot.path || f.path == MiniApps.home(context).canonicalPath) return true
        return grants(context, app).any { it.root.path == f.path }
    }

    // ---------- folders the user picked at runtime (AntiGem.fs.pickFolder) ----------

    private fun pickPrefs(context: Context) = context.getSharedPreferences("mini_app_picked_folders", Context.MODE_PRIVATE)

    /** [{path, access, at}], absolute real paths. The access is the manifest's "pick" access at the time. */
    fun pickedFolders(context: Context, app: MiniApp): List<JSONObject> {
        val a = runCatching { JSONArray(pickPrefs(context).getString(app.id, "[]")) }.getOrDefault(JSONArray())
        val pick = app.storagePick ?: return emptyList() // the manifest no longer allows picking: none count
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull {
            // the access the folder was picked with, and never more than the manifest allows now: an update that
            // changes "pick" from read to write doesn't make folders picked for reading writable
            val access = intersectAccess(it.optString("access"), pick) ?: return@mapNotNull null
            JSONObject(it.toString()).put("access", access)
        }
    }

    fun addPickedFolder(context: Context, app: MiniApp, folder: File) {
        val a = runCatching { JSONArray(pickPrefs(context).getString(app.id, "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("path") != folder.path }?.let { out.put(it) }
        out.put(JSONObject().put("path", folder.path).put("access", app.storagePick ?: "read").put("at", System.currentTimeMillis()))
        pickPrefs(context).edit().putString(app.id, out.toString()).apply()
    }

    fun removePickedFolder(context: Context, app: MiniApp, path: String?) {
        if (path == null) { pickPrefs(context).edit().remove(app.id).apply(); return }
        val a = runCatching { JSONArray(pickPrefs(context).getString(app.id, "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("path") != path }?.let { out.put(it) }
        pickPrefs(context).edit().putString(app.id, out.toString()).apply()
    }

    /** Is [f] a symbolic link itself (not where it points)? */
    fun isSymlink(f: File): Boolean = runCatching { android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.path).st_mode) }.getOrDefault(false)
}
