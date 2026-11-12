package com.example.gemini.data.miniapps

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Native IndexedDB of one mini app: every IDB database of the app lives in one SQLite file.
 * The JS side (assets/miniapp-sdk/idb.js) encodes keys into strings whose ASCII order is IDB key order, works out
 * key paths and index keys, and batches requests; this class only stores rows and answers range queries.
 *
 * One `idb.exec` message = { tx, first, ops: [...], end?: "commit" | "abort" }.
 * Reply = { r: [[0, value] | [1, errorName, message], ...] }, or { fail: [name, message] } when the transaction is gone.
 * At most one IDB transaction is open in SQLite at a time; messages of other transactions wait in a FIFO queue.
 */
class MiniAppIdb(private val dbFile: File) {
    private class IdbError(val type: String, message: String) : Exception(message)
    private class Msg(val pageId: String, val params: JSONObject, val reply: (Result<JSONObject>) -> Unit)
    private class Active(val tx: String, val pageId: String) {
        @Volatile var touched = System.currentTimeMillis()
    }

    private val worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "miniapp-idb").apply { isDaemon = true } }
    private var db: SQLiteDatabase? = null
    private var active: Active? = null
    private val queue = ArrayDeque<Msg>()
    /** Transactions ended natively (watchdog, page closed), so their next message gets a clear reason. */
    private val ended = LinkedHashMap<String, Pair<String, String>>()
    private var watchdogArmed = false

    /** Runs one idb.exec message. May call reply on any thread. */
    fun exec(pageId: String, params: JSONObject, reply: (Result<JSONObject>) -> Unit) {
        worker.execute { safely(reply) { handle(Msg(pageId, params, reply)) } }
    }

    /** A page (WebView) closed: roll back its active transaction and drop its queued ones. */
    fun pageClosed(pageId: String) {
        worker.execute {
            queue.removeAll { it.pageId == pageId }
            if (active?.pageId == pageId) {
                rollback("AbortError", "The page was closed")
                drain()
            }
        }
    }

    /** Close the SQLite database (before "clear data" deletes the file). */
    fun close() {
        val done = worker.submit(Runnable {
            if (active != null) rollback("AbortError", "The database was closed")
            while (queue.isNotEmpty()) queue.removeFirst().reply(Result.success(failure("AbortError", "The database was closed")))
            runCatching { db?.close() }
            db = null
        })
        runCatching { done.get(10, TimeUnit.SECONDS) }
    }

    private inline fun safely(noinline reply: (Result<JSONObject>) -> Unit, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            Log.w(TAG, "idb.exec failed", e)
            reply(Result.success(failure("UnknownError", e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun failure(name: String, message: String) = JSONObject().put("fail", JSONArray().put(name).put(message))

    private fun handle(m: Msg) {
        val tx = m.params.optString("tx")
        val cur = active
        when {
            cur != null && cur.tx == tx -> run(m)
            !m.params.optBoolean("first") -> {
                val why = ended.remove(tx) ?: ("AbortError" to "The transaction is no longer active")
                m.reply(Result.success(failure(why.first, why.second)))
            }
            cur == null -> {
                open().beginTransaction()
                active = Active(tx, m.pageId)
                run(m)
            }
            else -> queue.addLast(m)
        }
    }

    private fun run(m: Msg) {
        val a = active ?: return
        a.touched = System.currentTimeMillis()
        val d = open()
        val ops = m.params.optJSONArray("ops") ?: JSONArray()
        val results = JSONArray()
        for (i in 0 until ops.length()) {
            results.put(
                try {
                    JSONArray().put(0).put(runOp(d, ops.getJSONObject(i)) ?: JSONObject.NULL)
                } catch (e: IdbError) {
                    JSONArray().put(1).put(e.type).put(e.message ?: e.type)
                } catch (e: Exception) {
                    JSONArray().put(1).put("UnknownError").put(e.message ?: e.javaClass.simpleName)
                }
            )
        }
        val out = JSONObject().put("r", results)
        when (m.params.optString("end")) {
            "commit" -> {
                active = null
                try {
                    d.setTransactionSuccessful()
                    d.endTransaction()
                    m.reply(Result.success(out))
                } catch (e: Exception) {
                    runCatching { if (d.inTransaction()) d.endTransaction() }
                    m.reply(Result.success(failure("UnknownError", "Commit failed: ${e.message}")))
                }
                drain()
            }
            "abort" -> {
                active = null
                runCatching { d.endTransaction() }
                m.reply(Result.success(out))
                drain()
            }
            else -> {
                m.reply(Result.success(out))
                armWatchdog()
            }
        }
    }

    private fun drain() {
        while (active == null && queue.isNotEmpty()) handle(queue.removeFirst())
    }

    private fun rollback(name: String, reason: String) {
        val a = active ?: return
        active = null
        runCatching { db?.endTransaction() }
        ended[a.tx] = name to reason
        while (ended.size > 64) ended.remove(ended.keys.first())
    }

    /** Rolls back a transaction whose page went quiet (no message for IDLE_MS), so other pages are not stuck. */
    private fun armWatchdog() {
        if (watchdogArmed) return
        watchdogArmed = true
        worker.schedule(Runnable {
            watchdogArmed = false
            val a = active ?: return@Runnable
            val idle = System.currentTimeMillis() - a.touched
            if (idle >= IDLE_MS) {
                Log.w(TAG, "Rolling back idle IndexedDB transaction ${a.tx}")
                rollback("TimeoutError", "The transaction was idle for too long")
                drain()
            } else armWatchdog()
        }, IDLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun open(): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        dbFile.parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        d.enableWriteAheadLogging()
        SCHEMA.forEach { d.execSQL(it) }
        db = d
        return d
    }

    // ---------- ops ----------

    private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

    private fun runOp(d: SQLiteDatabase, op: JSONObject): Any? {
        val db = op.optString("db")
        val s = op.optString("s")
        return when (val o = op.getString("o")) {
            "info" -> info(d, db)
            "ver" -> { d.execSQL("INSERT OR REPLACE INTO idb_db(name,version) VALUES(?,?)", arrayOf<Any>(db, op.getLong("v"))); null }
            "dropDb" -> dropDb(d, db)
            "dbs" -> JSONArray().also { out ->
                d.rawQuery("SELECT name, version FROM idb_db ORDER BY name", null).use { c ->
                    while (c.moveToNext()) out.put(JSONArray().put(c.getString(0)).put(c.getLong(1)))
                }
            }
            "mkS" -> {
                d.execSQL("INSERT INTO idb_store(db,name,kp,ai,nk) VALUES(?,?,?,?,1)", arrayOf<Any?>(db, s, op.str("kp"), op.optInt("ai")))
                null
            }
            "rmS" -> {
                for (t in listOf("idb_ix", "idb_rec", "idb_index")) d.execSQL("DELETE FROM $t WHERE db=? AND s=?", arrayOf<Any>(db, s))
                d.execSQL("DELETE FROM idb_store WHERE db=? AND name=?", arrayOf<Any>(db, s))
                null
            }
            "mvS" -> {
                val to = op.getString("to")
                for (t in listOf("idb_ix", "idb_rec", "idb_index")) d.execSQL("UPDATE $t SET s=? WHERE db=? AND s=?", arrayOf<Any>(to, db, s))
                d.execSQL("UPDATE idb_store SET name=? WHERE db=? AND name=?", arrayOf<Any>(to, db, s))
                null
            }
            "mkI" -> {
                d.execSQL("INSERT INTO idb_index(db,s,name,kp,uq,me) VALUES(?,?,?,?,?,?)", arrayOf<Any>(db, s, op.getString("i"), op.getString("kp"), op.optInt("u"), op.optInt("m")))
                null
            }
            "rmI" -> {
                d.execSQL("DELETE FROM idb_ix WHERE db=? AND s=? AND i=?", arrayOf<Any>(db, s, op.getString("i")))
                d.execSQL("DELETE FROM idb_index WHERE db=? AND s=? AND name=?", arrayOf<Any>(db, s, op.getString("i")))
                null
            }
            "mvI" -> {
                val to = op.getString("to")
                d.execSQL("UPDATE idb_ix SET i=? WHERE db=? AND s=? AND i=?", arrayOf<Any>(to, db, s, op.getString("i")))
                d.execSQL("UPDATE idb_index SET name=? WHERE db=? AND s=? AND name=?", arrayOf<Any>(to, db, s, op.getString("i")))
                null
            }
            "all" -> JSONArray().also { out ->
                d.rawQuery("SELECT k, v FROM idb_rec WHERE db=? AND s=? ORDER BY k", arrayOf(db, s)).use { c ->
                    while (c.moveToNext()) out.put(JSONArray().put(c.getString(0)).put(c.getString(1)))
                }
            }
            "ixAdd" -> {
                val i = op.getString("i")
                val unique = op.optInt("u") == 1
                val e = op.getJSONArray("e")
                for (n in 0 until e.length()) {
                    val x = e.getJSONArray(n) // [ik, ikj, k]
                    if (unique && conflict(d, db, s, i, x.getString(0), x.getString(2))) throw IdbError("ConstraintError", "Index \"$i\" is unique and a key appears more than once.")
                    d.execSQL("INSERT OR IGNORE INTO idb_ix(db,s,i,ik,k,ikj) VALUES(?,?,?,?,?,?)", arrayOf<Any>(db, s, i, x.getString(0), x.getString(2), x.getString(1)))
                }
                null
            }
            "put" -> put(d, db, s, op)
            "q" -> query(d, db, s, op)
            "n" -> {
                val args = arrayListOf(db, s)
                val i = op.str("i")
                val w = StringBuilder("db=? AND s=?")
                if (i != null) { w.append(" AND i=?"); args += i }
                range(w, args, if (i != null) "ik" else "k", op.optJSONObject("r"))
                d.rawQuery("SELECT COUNT(*) FROM ${if (i != null) "idb_ix" else "idb_rec"} WHERE $w", args.toTypedArray()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            }
            "del" -> {
                val args = arrayListOf(db, s)
                val w = StringBuilder("db=? AND s=?")
                range(w, args, "k", op.optJSONObject("r"))
                d.execSQL("DELETE FROM idb_ix WHERE db=? AND s=? AND k IN (SELECT k FROM idb_rec WHERE $w)", (listOf(db, s) + args).toTypedArray<Any>())
                d.execSQL("DELETE FROM idb_rec WHERE $w", args.toTypedArray<Any>())
                null
            }
            "clr" -> {
                d.execSQL("DELETE FROM idb_ix WHERE db=? AND s=?", arrayOf<Any>(db, s))
                d.execSQL("DELETE FROM idb_rec WHERE db=? AND s=?", arrayOf<Any>(db, s))
                null
            }
            else -> throw IdbError("NotSupportedError", "Unknown op $o")
        }
    }

    private fun info(d: SQLiteDatabase, db: String): JSONObject {
        var version = 0L
        d.rawQuery("SELECT version FROM idb_db WHERE name=?", arrayOf(db)).use { if (it.moveToFirst()) version = it.getLong(0) }
        val stores = JSONArray()
        val byName = HashMap<String, JSONObject>()
        d.rawQuery("SELECT name, kp, ai FROM idb_store WHERE db=? ORDER BY name", arrayOf(db)).use { c ->
            while (c.moveToNext()) {
                val st = JSONObject().put("n", c.getString(0)).put("kp", if (c.isNull(1)) JSONObject.NULL else c.getString(1))
                    .put("ai", c.getInt(2)).put("ix", JSONArray())
                stores.put(st)
                byName[c.getString(0)] = st
            }
        }
        d.rawQuery("SELECT s, name, kp, uq, me FROM idb_index WHERE db=? ORDER BY name", arrayOf(db)).use { c ->
            while (c.moveToNext()) {
                byName[c.getString(0)]?.getJSONArray("ix")?.put(
                    JSONObject().put("n", c.getString(1)).put("kp", c.getString(2)).put("u", c.getInt(3)).put("m", c.getInt(4))
                )
            }
        }
        return JSONObject().put("v", version).put("s", stores)
    }

    private fun dropDb(d: SQLiteDatabase, db: String): Long {
        var version = 0L
        d.rawQuery("SELECT version FROM idb_db WHERE name=?", arrayOf(db)).use { if (it.moveToFirst()) version = it.getLong(0) }
        for (t in listOf("idb_ix", "idb_rec", "idb_index", "idb_store")) d.execSQL("DELETE FROM $t WHERE db=?", arrayOf<Any>(db))
        d.execSQL("DELETE FROM idb_db WHERE name=?", arrayOf<Any>(db))
        return version
    }

    private fun conflict(d: SQLiteDatabase, db: String, s: String, i: String, ik: String, k: String): Boolean =
        d.rawQuery("SELECT 1 FROM idb_ix WHERE db=? AND s=? AND i=? AND ik=? AND k<>? LIMIT 1", arrayOf(db, s, i, ik, k)).use { it.moveToFirst() }

    /** Stores one record and its index entries; returns the key as JSON (generated when op.auto == 1). */
    private fun put(d: SQLiteDatabase, db: String, s: String, op: JSONObject): String {
        var k = ""
        var kj = ""
        var v = op.getString("v")
        var newNext = -1L // the generator only moves once the record is really stored
        d.rawQuery("SELECT ai, nk FROM idb_store WHERE db=? AND name=?", arrayOf(db, s)).use { c ->
            if (!c.moveToFirst()) throw IdbError("NotFoundError", "No object store named \"$s\"")
            val autoInc = c.getInt(0) != 0
            val next = c.getLong(1)
            if (op.optInt("auto") == 1) {
                if (next > MAX_KEY) throw IdbError("ConstraintError", "The key generator has reached its maximum value.")
                k = encNum(next.toDouble())
                kj = next.toString()
                newNext = next + 1
                op.str("inj")?.let { v = inject(v, it, next) }
            } else {
                k = op.getString("k")
                kj = op.getString("kj")
                // an explicit numeric key moves the generator past it
                val n = if (autoInc) kj.toDoubleOrNull() else null
                if (n != null && n >= next) newNext = minOf(floor(n) + 1, (MAX_KEY + 1).toDouble()).toLong()
            }
        }
        if (op.optInt("add") == 1) {
            val exists = d.rawQuery("SELECT 1 FROM idb_rec WHERE db=? AND s=? AND k=?", arrayOf(db, s, k)).use { it.moveToFirst() }
            if (exists) throw IdbError("ConstraintError", "A record with this key already exists in the object store.")
        }
        val e = op.optJSONArray("e") ?: JSONArray()
        for (n in 0 until e.length()) {
            val x = e.getJSONArray(n) // [index, ik, ikj, unique]
            if (x.optInt(3) == 1 && conflict(d, db, s, x.getString(0), x.getString(1), k)) {
                throw IdbError("ConstraintError", "Unique index \"${x.getString(0)}\" already has a record with this key.")
            }
        }
        if (newNext >= 0) d.execSQL("UPDATE idb_store SET nk=? WHERE db=? AND name=?", arrayOf<Any>(newNext, db, s))
        d.execSQL("INSERT OR REPLACE INTO idb_rec(db,s,k,kj,v) VALUES(?,?,?,?,?)", arrayOf<Any>(db, s, k, kj, v))
        d.execSQL("DELETE FROM idb_ix WHERE db=? AND s=? AND k=?", arrayOf<Any>(db, s, k))
        for (n in 0 until e.length()) {
            val x = e.getJSONArray(n)
            d.execSQL("INSERT OR IGNORE INTO idb_ix(db,s,i,ik,k,ikj) VALUES(?,?,?,?,?,?)", arrayOf<Any>(db, s, x.getString(0), x.getString(1), k, x.getString(2)))
        }
        return kj
    }

    /**
     * Rows in cursor order. Store rows: [k, kj, v?]. Index rows: [ik, ikj, k, kj, v?].
     * `from` continues after (x=0) or at (x=1) a position; unique directions keep the lowest primary key per index key.
     */
    private fun query(d: SQLiteDatabase, db: String, s: String, op: JSONObject): JSONArray {
        val ix = op.str("i")
        val dir = op.optString("d", "next")
        val desc = dir.startsWith("prev")
        val ord = if (desc) "DESC" else "ASC"
        val limit = op.optInt("n", 0).let { if (it > 0) it else -1 }
        val withV = op.optString("kv") != "k"
        val r = op.optJSONObject("r")
        val from = op.optJSONObject("from")
        val args = ArrayList<String>()
        val sql: String
        if (ix == null) {
            val w = StringBuilder("db=? AND s=?"); args += db; args += s
            range(w, args, "k", r)
            if (from != null) bound(w, args, "k", from.getString("k"), desc, from.optInt("x") == 1)
            sql = "SELECT k, kj${if (withV) ", v" else ""} FROM idb_rec WHERE $w ORDER BY k $ord LIMIT $limit"
        } else if (dir.endsWith("unique")) {
            val w = StringBuilder("db=? AND s=? AND i=?"); args += db; args += s; args += ix
            range(w, args, "ik", r)
            if (from != null) bound(w, args, "ik", from.getString("ik"), desc, from.optInt("x") == 1)
            args += db; args += s
            sql = "SELECT g.ik, g.ikj, g.k, r.kj${if (withV) ", r.v" else ""} FROM " +
                "(SELECT ik, ikj, MIN(k) AS k FROM idb_ix WHERE $w GROUP BY ik) g " +
                "JOIN idb_rec r ON r.db=? AND r.s=? AND r.k=g.k ORDER BY g.ik $ord LIMIT $limit"
        } else {
            val w = StringBuilder("x.db=? AND x.s=? AND x.i=?"); args += db; args += s; args += ix
            range(w, args, "x.ik", r)
            if (from != null) {
                val fik = from.getString("ik")
                val incl = from.optInt("x") == 1
                val fk = from.str("k")
                if (fk == null) bound(w, args, "x.ik", fik, desc, incl)
                else {
                    val gt = if (desc) "<" else ">"
                    w.append(" AND (x.ik $gt ? OR (x.ik = ? AND x.k $gt${if (incl) "=" else ""} ?))")
                    args += fik; args += fik; args += fk
                }
            }
            sql = "SELECT x.ik, x.ikj, x.k, r.kj${if (withV) ", r.v" else ""} FROM idb_ix x " +
                "JOIN idb_rec r ON r.db=x.db AND r.s=x.s AND r.k=x.k WHERE $w ORDER BY x.ik $ord, x.k $ord LIMIT $limit"
        }
        val out = JSONArray()
        d.rawQuery(sql, args.toTypedArray()).use { c ->
            val cols = c.columnCount
            while (c.moveToNext()) {
                val row = JSONArray()
                for (i in 0 until cols) row.put(c.getString(i))
                out.put(row)
            }
        }
        return out
    }

    private fun range(w: StringBuilder, args: MutableList<String>, col: String, r: JSONObject?) {
        if (r == null) return
        r.str("l")?.let { w.append(" AND $col ${if (r.optInt("lo") == 1) ">" else ">="} ?"); args += it }
        r.str("u")?.let { w.append(" AND $col ${if (r.optInt("uo") == 1) "<" else "<="} ?"); args += it }
    }

    private fun bound(w: StringBuilder, args: MutableList<String>, col: String, value: String, desc: Boolean, inclusive: Boolean) {
        w.append(" AND $col ${if (desc) "<" else ">"}${if (inclusive) "=" else ""} ?")
        args += value
    }

    /** Writes a generated key into the stored value (tagged JSON) at a dotted key path, creating objects on the way. */
    private fun inject(v: String, path: String, key: Long): String {
        val root = runCatching { JSONTokener(v).nextValue() }.getOrNull() as? JSONObject ?: return v
        var obj = unwrap(root) ?: return v
        val segs = path.split('.')
        for (seg in segs.dropLast(1)) {
            obj = when (val next = obj.opt(seg)) {
                null -> JSONObject().also { obj.put(seg, it) }
                is JSONObject -> unwrap(next) ?: return v
                else -> return v
            }
        }
        obj.put(segs.last(), key)
        return root.toString()
    }

    /** A plain object in the value encoding: untagged, or {"$t":"o","v":{...}} when it had a "$t" key itself. */
    private fun unwrap(o: JSONObject): JSONObject? = when {
        !o.has("\$t") -> o
        o.optString("\$t") == "o" -> o.optJSONObject("v")
        else -> null
    }

    companion object {
        private const val TAG = "MiniAppIdb"
        private const val IDLE_MS = 30_000L
        /** 2^53: the key generator's limit. */
        private const val MAX_KEY = 9_007_199_254_740_992L

        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS idb_db(name TEXT PRIMARY KEY, version INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS idb_store(db TEXT NOT NULL, name TEXT NOT NULL, kp TEXT, ai INTEGER NOT NULL, nk INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(db, name))",
            "CREATE TABLE IF NOT EXISTS idb_index(db TEXT NOT NULL, s TEXT NOT NULL, name TEXT NOT NULL, kp TEXT NOT NULL, uq INTEGER NOT NULL, me INTEGER NOT NULL, PRIMARY KEY(db, s, name))",
            "CREATE TABLE IF NOT EXISTS idb_rec(db TEXT NOT NULL, s TEXT NOT NULL, k TEXT NOT NULL, kj TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY(db, s, k)) WITHOUT ROWID",
            "CREATE TABLE IF NOT EXISTS idb_ix(db TEXT NOT NULL, s TEXT NOT NULL, i TEXT NOT NULL, ik TEXT NOT NULL, k TEXT NOT NULL, ikj TEXT NOT NULL, PRIMARY KEY(db, s, i, ik, k)) WITHOUT ROWID",
            "CREATE INDEX IF NOT EXISTS idb_ix_by_rec ON idb_ix(db, s, k)",
        )

        /** Same as encNum in idb.js: "1" + the float64 bits made sortable, as 16 lowercase hex chars. */
        fun encNum(d: Double): String {
            var bits = java.lang.Double.doubleToRawLongBits(if (d == 0.0) 0.0 else d)
            bits = if (bits < 0) bits.inv() else bits xor Long.MIN_VALUE
            return "1" + java.lang.Long.toHexString(bits).padStart(16, '0')
        }
    }
}
