package ai.antigem.netcaptest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs the traffic tests. From adb:
 *   am start -n ai.antigem.netcaptest/.MainActivity --es server 192.168.1.71 --es tests all [--ei rps 20 --ei seconds 15]
 * Each result goes to logcat (tag NCT) and all of them to files/results.json (run-as ai.antigem.netcaptest cat …).
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 12f; setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(out) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val server = intent?.getStringExtra("server") ?: getSharedPreferences("t", MODE_PRIVATE).getString("server", null)
        if (server == null) {
            out.text = "Start from adb:\nam start -n ai.antigem.netcaptest/.MainActivity --es server <pc ip> --es tests all"
            return
        }
        getSharedPreferences("t", MODE_PRIVATE).edit().putString("server", server).apply()
        val rps = intent?.getIntExtra("rps", 20) ?: 20
        val seconds = intent?.getIntExtra("seconds", 15) ?: 15
        val suite = Suite(server, rps, seconds)
        val wanted = (intent?.getStringExtra("tests") ?: "all").let { if (it == "all") suite.all else it.split(',').map { s -> s.trim() } }
        job?.cancel()
        out.text = "server $server · ${wanted.size} tests\n"
        job = scope.launch {
            val results = JSONArray()
            val file = File(filesDir, "results.json")
            withContext(Dispatchers.IO) { file.writeText(JSONObject().put("running", true).toString()) }
            var passed = 0
            for (name in wanted) {
                out.append("… $name\n")
                val r = suite.run(name)
                if (r.ok) passed++
                Log.i("NCT", "${if (r.ok) "PASS" else "FAIL"} ${r.name} ${r.ms}ms ${r.detail}")
                out.append("${if (r.ok) "✓" else "✗"} ${r.name} (${r.ms} ms) ${r.detail}\n")
                results.put(r.json())
            }
            val summary = JSONObject().put("running", false).put("passed", passed).put("total", wanted.size).put("results", results)
            withContext(Dispatchers.IO) { file.writeText(summary.toString(2)) }
            Log.i("NCT", "DONE $passed/${wanted.size}")
            out.append("done: $passed/${wanted.size} passed\n")
        }
    }

    override fun onDestroy() {
        scope.launch { }.cancel()
        super.onDestroy()
    }
}
