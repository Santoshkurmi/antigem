package com.example.gemini.data.miniapps

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Native key-value store behind localStorage and AntiGem.storage (one SQLite file per app, kv.db).
 * Reads come from an in-memory copy (handed to each page at load, so JS reads are synchronous and instant);
 * writes update the copy at once and reach SQLite on a background thread.
 */
class MiniAppKv(private val file: File) {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "miniapp-kv").apply { isDaemon = true } }
    private val cache = LinkedHashMap<String, String>()
    @Volatile private var loaded = false
    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase = db ?: run {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).also {
            it.enableWriteAheadLogging()
            it.execSQL("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            db = it
        }
    }

    private val loadLock = Any()

    private fun load() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            // the first read waits for the disk once; afterwards everything is memory.
            // (Rows are collected on the db thread and copied in here: the db thread never takes the cache lock.)
            val rows = exec.submit<List<Pair<String, String>>> {
                open().rawQuery("SELECT k, v FROM kv", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            }.get()
            synchronized(cache) { rows.forEach { (k, v) -> if (!cache.containsKey(k)) cache[k] = v } }
            loaded = true
        }
    }

    fun snapshot(): JSONObject {
        load()
        return synchronized(cache) { JSONObject(cache as Map<*, *>) }
    }

    /** ops: [{op:"set",k,v} | {op:"del",k} | {op:"clear",prefix}] */
    fun apply(ops: JSONArray) {
        load()
        val writes = ArrayList<Triple<String, String?, String?>>()
        synchronized(cache) {
            for (i in 0 until ops.length()) {
                val o = ops.optJSONObject(i) ?: continue
                when (o.optString("op")) {
                    "set" -> { val k = o.optString("k"); val v = o.optString("v"); cache[k] = v; writes += Triple("set", k, v) }
                    "del" -> { val k = o.optString("k"); cache.remove(k); writes += Triple("del", k, null) }
                    "clear" -> { val prefix = o.optString("prefix"); cache.keys.removeAll { it.startsWith(prefix) }; writes += Triple("clear", prefix, null) }
                }
            }
        }
        if (writes.isEmpty()) return
        exec.execute {
            val d = open()
            d.beginTransaction()
            try {
                for ((op, k, v) in writes) when (op) {
                    "set" -> d.execSQL("INSERT OR REPLACE INTO kv (k, v) VALUES (?, ?)", arrayOf(k, v))
                    "del" -> d.execSQL("DELETE FROM kv WHERE k = ?", arrayOf(k))
                    "clear" -> d.execSQL("DELETE FROM kv WHERE substr(k, 1, ?) = ?", arrayOf<Any>(k!!.length, k))
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
    }

    fun close() {
        runCatching { exec.submit { db?.close(); db = null }.get() }
        exec.shutdown()
    }
}
