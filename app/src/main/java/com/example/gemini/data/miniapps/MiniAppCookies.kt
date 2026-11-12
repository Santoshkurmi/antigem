package com.example.gemini.data.miniapps

import android.database.sqlite.SQLiteDatabase
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * One mini app's cookies, like a browser profile (cookies.db in the app's data folder):
 *  - persistent cookies are saved and deleted when they expire; session cookies (no expiry) live in memory
 *    until AntiGem's process ends
 *  - browser-like limits: 4 KB per cookie, 180 per domain, 3000 per app
 *  - never shared between apps; "Clear data" removes them
 * The network layer applies them on every request hop (see MiniAppNet), so an API that logs in with a cookie
 * keeps working after restarts.
 */
class MiniAppCookies(private val file: File) {
    private data class Key(val name: String, val domain: String, val path: String)

    private val cookies = LinkedHashMap<Key, Cookie>()
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "miniapp-cookies").apply { isDaemon = true } }
    private val loadLock = Any()
    @Volatile private var loaded = false
    private var db: SQLiteDatabase? = null

    companion object {
        const val MAX_BYTES = 4096
        const val MAX_PER_DOMAIN = 180
        const val MAX_TOTAL = 3000
        /** Cookies set by the app itself without an expiry (e.g. pasted by the user) last this long: Chrome's cap. */
        const val DEFAULT_DAYS = 400
    }

    private fun Cookie.key() = Key(name, domain, path)

    private fun open(): SQLiteDatabase = db ?: run {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).also {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS cookies (name TEXT NOT NULL, domain TEXT NOT NULL, path TEXT NOT NULL, value TEXT NOT NULL, " +
                    "expires INTEGER NOT NULL, secure INTEGER NOT NULL, http_only INTEGER NOT NULL, host_only INTEGER NOT NULL, PRIMARY KEY (name, domain, path))",
            )
            db = it
        }
    }

    private fun load() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val now = System.currentTimeMillis()
            val rows = exec.submit<List<Cookie>> {
                val d = open()
                d.execSQL("DELETE FROM cookies WHERE expires <= ?", arrayOf<Any>(now))
                d.rawQuery("SELECT name, domain, path, value, expires, secure, http_only, host_only FROM cookies", null).use { c ->
                    buildList {
                        while (c.moveToNext()) runCatching {
                            val b = Cookie.Builder().name(c.getString(0)).value(c.getString(3)).path(c.getString(2)).expiresAt(c.getLong(4))
                            if (c.getInt(7) == 1) b.hostOnlyDomain(c.getString(1)) else b.domain(c.getString(1))
                            if (c.getInt(5) == 1) b.secure()
                            if (c.getInt(6) == 1) b.httpOnly()
                            add(b.build())
                        }
                    }
                }
            }.get()
            synchronized(cookies) { rows.forEach { cookies.putIfAbsent(it.key(), it) } }
            loaded = true
        }
    }

    private fun persist(add: List<Cookie>, remove: List<Key>) {
        if (add.isEmpty() && remove.isEmpty()) return
        exec.execute {
            val d = open()
            d.beginTransaction()
            try {
                remove.forEach { d.execSQL("DELETE FROM cookies WHERE name = ? AND domain = ? AND path = ?", arrayOf(it.name, it.domain, it.path)) }
                add.forEach {
                    d.execSQL(
                        "INSERT OR REPLACE INTO cookies (name, domain, path, value, expires, secure, http_only, host_only) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(it.name, it.domain, it.path, it.value, it.expiresAt, if (it.secure) 1 else 0, if (it.httpOnly) 1 else 0, if (it.hostOnly) 1 else 0),
                    )
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    /** Cookies to send to [url] (expired ones are dropped on the way). */
    fun forRequest(url: HttpUrl): List<Cookie> {
        load()
        val now = System.currentTimeMillis()
        val expired = ArrayList<Key>()
        val out = synchronized(cookies) {
            val it = cookies.entries.iterator()
            val list = ArrayList<Cookie>()
            while (it.hasNext()) {
                val (k, c) = it.next()
                if (c.expiresAt <= now) { it.remove(); expired += k; continue }
                if (c.matches(url)) list += c
            }
            // longest path first, like browsers
            list.sortedByDescending { it.path.length }
        }
        persist(emptyList(), expired)
        return out
    }

    /** Set-Cookie from a response (or a cookie the app set): store, replace, or delete when already expired. */
    fun save(list: List<Cookie>) {
        if (list.isEmpty()) return
        load()
        val now = System.currentTimeMillis()
        val add = ArrayList<Cookie>()
        val remove = ArrayList<Key>()
        synchronized(cookies) {
            for (c in list) {
                val k = c.key()
                if (c.name.length + c.value.length > MAX_BYTES) continue
                // a cookie set with a date in the past is how servers delete one
                if (c.expiresAt <= now) { if (cookies.remove(k) != null) remove += k; continue }
                cookies.remove(k)
                cookies[k] = c
                if (c.persistent) add += c else remove += k
                // limits: drop the oldest of that domain, then the oldest overall
                val sameDomain = cookies.keys.filter { it.domain == c.domain }
                if (sameDomain.size > MAX_PER_DOMAIN) sameDomain.take(sameDomain.size - MAX_PER_DOMAIN).forEach { cookies.remove(it); remove += it }
                while (cookies.size > MAX_TOTAL) { val first = cookies.keys.first(); cookies.remove(first); remove += first }
            }
        }
        persist(add, remove)
    }

    fun all(): List<Cookie> { load(); val now = System.currentTimeMillis(); return synchronized(cookies) { cookies.values.filter { it.expiresAt > now } } }

    /** Removes cookies matching [name] (all names when null) whose domain matches [host] (all hosts when null). */
    fun delete(host: String?, name: String?, path: String? = null): Int {
        load()
        val gone = synchronized(cookies) {
            val keys = cookies.keys.filter { k ->
                (name == null || k.name == name) && (path == null || k.path == path) &&
                    (host == null || host == k.domain || host.endsWith(".${k.domain}"))
            }
            keys.forEach { cookies.remove(it) }
            keys
        }
        persist(emptyList(), gone)
        return gone.size
    }

    fun toJson(list: List<Cookie>): JSONArray = JSONArray().apply {
        list.forEach { c ->
            put(JSONObject().put("name", c.name).put("value", c.value).put("domain", c.domain).put("path", c.path)
                .put("expires", if (c.persistent) c.expiresAt else JSONObject.NULL).put("session", !c.persistent)
                .put("secure", c.secure).put("httpOnly", c.httpOnly).put("hostOnly", c.hostOnly))
        }
    }

    fun close() {
        runCatching { exec.submit { db?.close(); db = null }.get() }
        exec.shutdown()
    }
}
