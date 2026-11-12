package com.example.gemini.data.miniapps

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Installed (signed) mini apps, with Android's rules:
 *  - the first install records the developer's key; an update must be signed with the same key
 *  - a lower version needs the user's explicit OK
 *  - an id used by a development app (a folder in ~/mini-apps) can't be taken by a package
 *  - installed apps live in AntiGem's private files, and every file they load is checked against the signed hashes
 *    when it is served: a changed or extra file never runs under the developer's name
 * Development apps (folders in ~/mini-apps, unsigned, editable) are separate: they never come from a package.
 */
object MiniAppInstaller {
    fun root(context: Context) = File(context.filesDir, "miniapps/installed").also { it.mkdirs() }
    private fun registryFile(context: Context) = File(context.filesDir, "miniapps/installed.json")

    class Record(val id: String, val developer: String, val publicKey: String, val fingerprint: String,
                 val version: String, val versionCode: Long, val files: Map<String, String>, val installedAt: Long, val updatedAt: Long) {
        fun toJson(): JSONObject = JSONObject().put("developer", developer).put("publicKey", publicKey).put("fingerprint", fingerprint)
            .put("version", version).put("versionCode", versionCode).put("files", JSONObject(files as Map<*, *>))
            .put("installedAt", installedAt).put("updatedAt", updatedAt)
        companion object {
            fun from(id: String, o: JSONObject) = Record(id, o.optString("developer"), o.optString("publicKey"), o.optString("fingerprint"),
                o.optString("version"), o.optLong("versionCode"),
                o.optJSONObject("files")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) } }.orEmpty(),
                o.optLong("installedAt"), o.optLong("updatedAt"))
        }
    }

    @Volatile private var cache: Map<String, Record>? = null

    @Synchronized
    fun records(context: Context): Map<String, Record> = cache ?: run {
        val o = runCatching { JSONObject(registryFile(context).readText()) }.getOrDefault(JSONObject())
        o.keys().asSequence().associateWith { Record.from(it, o.getJSONObject(it)) }.also { cache = it }
    }

    fun record(context: Context, id: String) = records(context)[id]

    @Synchronized
    private fun save(context: Context, all: Map<String, Record>) {
        val o = JSONObject().apply { all.forEach { (k, v) -> put(k, v.toJson()) } }
        val f = registryFile(context)
        val tmp = File(f.parentFile, "installed.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        cache = all
    }

    fun appDir(context: Context, id: String) = File(root(context), id)

    /** The signed package an app was installed from, kept so it can be shared on exactly as the developer signed it. */
    fun packageFile(context: Context, id: String) = File(context.filesDir, "miniapps/packages/$id.agapp")

    /** A copy of the installed package named for people ("Notes-1.2.0.agapp"), in the cache, to share. */
    fun shareablePackage(context: Context, app: MiniApp): File? {
        val src = packageFile(context, app.id).takeIf { it.isFile } ?: return null
        val dir = File(context.cacheDir, "miniapp-share").also { it.deleteRecursively(); it.mkdirs() }
        val name = app.name.replace(Regex("[^A-Za-z0-9._ -]"), "").trim().ifBlank { app.id }
        return src.copyTo(File(dir, "$name-${app.version}.agapp"), overwrite = true)
    }

    // ------------------------------------------------------------------ install plan

    enum class Kind { NEW, UPDATE, REINSTALL, DOWNGRADE }

    class Plan(
        val pkg: MiniAppSigning.Verified, val kind: Kind, val previous: Record?,
        /** Why it can't be installed (shown to the user), or null. */
        val blocked: String?,
        /** Permissions the update asks for that the installed version didn't. */
        val newPermissions: List<String>,
        /** Data left by an app with this id that isn't installed any more: deleted on a new install. */
        val orphanData: Boolean,
        /** Installed apps (same shared_id, same key) whose data/ it will be able to use, and that will use its. */
        val sharesWith: List<MiniApp> = emptyList(),
    )

    fun plan(context: Context, pkg: MiniAppSigning.Verified): Plan {
        val prev = record(context, pkg.id)
        val devFolder = MiniApps.devFolders(context).firstOrNull { it.second == pkg.id }?.first?.name
        val parsed = runCatching { MiniApps.parse(File("/"), pkg.manifest) }
        // shared_id: every installed app already in the group must be signed with this package's key
        val sharedId = parsed.getOrNull()?.sharedId
        val group = if (sharedId == null) emptyList() else MiniApps.scan(context).filter { it.signed && it.id != pkg.id && it.sharedId == sharedId }
        val stranger = group.firstOrNull { it.signer != pkg.fingerprint }
        val kind = when {
            prev == null -> Kind.NEW
            pkg.versionCode > prev.versionCode -> Kind.UPDATE
            pkg.versionCode == prev.versionCode -> Kind.REINSTALL
            else -> Kind.DOWNGRADE
        }
        val blocked = when {
            parsed.isFailure -> "Its manifest.json is invalid: ${parsed.exceptionOrNull()?.message}"
            prev != null && prev.fingerprint != pkg.fingerprint ->
                "This package is signed by a different developer than the installed ${pkg.manifest.optString("name")} (${prev.developer}, key ${MiniAppSigning.shortFingerprint(prev.fingerprint)}). " +
                    "Uninstall the installed app first; its data will be deleted."
            prev == null && devFolder != null ->
                "A development app with the id ${pkg.id} is in ~/mini-apps/$devFolder. A package can't take its id (it would get that app's data): " +
                    "give the development app another id, or delete it, then install."
            stranger != null ->
                "It shares data with the apps of \"$sharedId\", and ${stranger.name} there is signed by ${stranger.developer} " +
                    "(key ${MiniAppSigning.shortFingerprint(stranger.signer.orEmpty())}), not by this package's developer. Only apps signed with the same key can share data."
            else -> null
        }
        val oldPerms = prev?.let { r -> runCatching { MiniApps.parse(appDir(context, r.id), JSONObject(File(appDir(context, r.id), "manifest.json").readText())).permissions.keys }.getOrNull() }.orEmpty()
        val newPerms = parsed.getOrNull()?.permissions?.keys.orEmpty()
        return Plan(pkg, kind, prev, blocked, if (prev == null) emptyList() else (newPerms - oldPerms).toList(),
            orphanData = prev == null && MiniApps.dataDir(context, pkg.id).exists(),
            sharesWith = group.filter { it.signer == pkg.fingerprint })
    }

    // ------------------------------------------------------------------ install / uninstall

    /** Installs a verified, user-approved package: unpack (checking every file again), then swap it in at once. */
    @Synchronized
    fun install(context: Context, file: File, plan: Plan) {
        if (plan.blocked != null) throw MiniAppError("SecurityError", plan.blocked)
        val pkg = MiniAppSigning.verify(file) // again: the file may have changed since it was shown
        if (pkg.fingerprint != plan.pkg.fingerprint || pkg.versionCode != plan.pkg.versionCode) throw MiniAppError("SecurityError", "The package changed while it was being installed")
        val id = pkg.id
        val staging = File(root(context), ".staging-$id-${System.nanoTime()}")
        try {
            ZipFile(file).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory || e.name.startsWith("META-INF/")) continue
                    if (!MiniAppSigning.safePath(e.name)) throw MiniAppError("SecurityError", "Unsafe path ${e.name}")
                    val out = File(staging, e.name).canonicalFile
                    if (!out.path.startsWith(staging.canonicalPath + File.separator)) throw MiniAppError("SecurityError", "Unsafe path ${e.name}")
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    if (out.inputStream().use { MiniAppSigning.sha256Hex(it) } != pkg.files[e.name]) throw MiniAppError("SecurityError", "${e.name} doesn't match its signature")
                }
            }
            val target = appDir(context, id)
            val trash = File(root(context), ".trash-$id-${System.nanoTime()}")
            if (target.exists() && !target.renameTo(trash)) throw MiniAppError("InvalidStateError", "Couldn't replace the old version")
            if (!staging.renameTo(target)) { trash.renameTo(target); throw MiniAppError("InvalidStateError", "Couldn't put the new version in place") }
            trash.deleteRecursively()
        } finally { staging.deleteRecursively() }

        val now = System.currentTimeMillis()
        if (plan.kind == Kind.NEW) {
            // a new app starts clean: nothing left by an earlier app with this id (data, permissions, schedules)
            MiniAppPermissions.reset(context, MiniApps.parse(appDir(context, id), pkg.manifest))
            MiniAppRuntime.clearData(context, MiniApps.parse(appDir(context, id), pkg.manifest))
        }
        val all = records(context).toMutableMap()
        all[id] = Record(id, pkg.developer, pkg.publicKey, pkg.fingerprint, pkg.version, pkg.versionCode, pkg.files,
            plan.previous?.installedAt ?: now, now)
        save(context, all)
        packageFile(context, id).let { runCatching { it.parentFile?.mkdirs(); file.copyTo(it, overwrite = true) } }
        verifiedCache.keys.removeIf { it.startsWith(appDir(context, id).path) }
        MiniApps.scan(context)
        // open pages of the app load the new version
        MiniAppRuntime.state(context, id).pages.forEach { it.onFilesChanged(cssOnly = false, manifestChanged = true) }
    }

    /** Like uninstalling an Android app: its files, data, permissions, reminders, tasks and notifications go. */
    @Synchronized
    fun uninstall(context: Context, app: MiniApp) {
        val rec = record(context, app.id) ?: throw MiniAppError("InvalidStateError", "${app.name} is a development app, not an installed one")
        MiniAppRuntime.clearData(context, app)
        MiniAppPermissions.reset(context, app)
        appDir(context, rec.id).deleteRecursively()
        packageFile(context, rec.id).delete()
        save(context, records(context) - rec.id)
        runCatching { androidx.core.content.pm.ShortcutManagerCompat.disableShortcuts(context, listOf("miniapp:${app.id}"), "${app.name} was uninstalled") }
        MiniApps.scan(context)
    }

    // ------------------------------------------------------------------ integrity

    private val verifiedCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Is [f] (relative path [rel] of an installed app) exactly the file that was signed? Hashes are cached per
     * path + size + modification time, so this is cheap after the first load.
     */
    fun isIntact(context: Context, app: MiniApp, rel: String, f: File): Boolean {
        val rec = record(context, app.id) ?: return false
        val expected = rec.files[rel] ?: return false
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        return verifiedCache.getOrPut(key) { runCatching { f.inputStream().use { MiniAppSigning.sha256Hex(it) } == expected }.getOrDefault(false) }
    }
}
