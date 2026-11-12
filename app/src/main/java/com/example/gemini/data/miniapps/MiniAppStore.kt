package com.example.gemini.data.miniapps

import android.content.Context
import com.example.gemini.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Get apps": a catalog of signed mini app packages hosted on GitHub (antigem-mini-apps.json, written by
 * `agapp.mjs publish`). Each entry names its package's sha256 and the signing key's fingerprint, and a download that
 * doesn't match is refused before the install screen. The packages themselves are installed like any other .agapp.
 */
object MiniAppStore {
    const val REPO = "Santoshkurmi/antigem_mini_apps"
    const val INDEX = "antigem-mini-apps.json"
    /** HEAD = the repository's default branch, whatever it is called. */
    const val CATALOG = "https://raw.githubusercontent.com/$REPO/HEAD/$INDEX"
    const val PAGE = "https://github.com/$REPO"

    class Entry(
        val id: String, val name: String, val version: String, val versionCode: Long, val description: String,
        val developer: String, val fingerprint: String, val packageUrl: String, val sha256: String, val size: Long,
        val iconUrl: String?, val permissions: Any?, val updated: String,
    )

    class Catalog(val name: String, val url: String, val apps: List<Entry>)

    enum class State { INSTALL, UPDATE, OPEN, OTHER_DEVELOPER, DEV_COPY }

    private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    /** https only; debug builds also take a catalog on this computer through adb reverse (testing a repo before pushing). */
    fun allowed(url: HttpUrl): Boolean = url.isHttps || (BuildConfig.DEBUG && (url.host == "127.0.0.1" || url.host == "localhost"))

    suspend fun load(url: String = CATALOG): Catalog = withContext(Dispatchers.IO) {
        val u = url.toHttpUrlOrNull()?.takeIf(::allowed) ?: throw MiniAppError("TypeError", "Not a catalog URL: $url")
        client.newCall(Request.Builder().url(u).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (r.code == 404) throw MiniAppError("NotFoundError", "The catalog isn't published yet (no $INDEX in $REPO)")
            if (!r.isSuccessful) throw MiniAppError("NetworkError", "The catalog answered ${r.code}")
            val body = r.body?.string() ?: throw MiniAppError("NetworkError", "Empty catalog")
            val o = runCatching { JSONObject(body) }.getOrElse { throw MiniAppError("DataError", "The catalog isn't valid JSON") }
            if (o.optInt("format", 1) > 1) throw MiniAppError("NotSupportedError", "This catalog needs a newer AntiGem")
            val arr = o.optJSONArray("apps") ?: JSONArray()
            Catalog(o.optString("name").ifBlank { "Mini apps" }, u.toString(),
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { a -> runCatching { entry(u, a) }.getOrNull() })
        }
    }

    private fun entry(base: HttpUrl, a: JSONObject): Entry {
        val id = a.getString("id").trim()
        require(ID.matches(id))
        val pkg = base.resolve(a.getString("package"))?.takeIf(::allowed) ?: error("bad package URL")
        val sha = a.optString("sha256").trim().lowercase()
        val fp = a.optString("fingerprint").trim().lowercase()
        require(HEX64.matches(sha) && HEX64.matches(fp))
        return Entry(
            id, a.optString("name").ifBlank { id }, a.optString("version").ifBlank { "0.0.0" }, a.optLong("version_code"),
            a.optString("description"), a.optString("developer").ifBlank { "Unknown developer" }, fp, pkg.toString(), sha, a.optLong("size"),
            a.optString("icon").takeIf { it.isNotBlank() }?.let { base.resolve(it)?.takeIf(::allowed)?.toString() },
            a.opt("permissions"), a.optString("updated"),
        )
    }

    /** What the store offers for [e] on this phone, and a detail (the installed version, or the development folder). */
    fun state(context: Context, e: Entry): Pair<State, String?> {
        MiniAppInstaller.record(context, e.id)?.let { rec ->
            return when {
                !rec.fingerprint.equals(e.fingerprint, ignoreCase = true) -> State.OTHER_DEVELOPER to rec.developer
                e.versionCode > rec.versionCode -> State.UPDATE to rec.version
                else -> State.OPEN to rec.version
            }
        }
        MiniApps.devFolders(context).firstOrNull { it.second == e.id }?.let { return State.DEV_COPY to it.first.name }
        return State.INSTALL to null
    }

    /** What an entry will ask for, as the permission prompt says it ("Internet", "Notifications", …). */
    fun permissionTitles(e: Entry): List<String> = runCatching {
        val app = MiniApps.parse(java.io.File("/"), JSONObject().put("id", e.id).put("permissions", e.permissions ?: JSONObject()))
        app.permissions.keys.map { MiniAppPermissions.describe(app, it).title }.distinct()
    }.getOrDefault(emptyList())
}
