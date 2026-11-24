package ai.antigem.data.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.concurrent.TimeUnit
import ai.antigem.i18n.L

/**
 * Root mode: commands run as root through the phone's own `su` (Magisk, KernelSU, APatch). The first `su` makes the
 * root manager ask the user to allow AntiGem; the answer is remembered by the root manager, not here.
 */
object RootShell {
    private const val PREFS = "adb_connect"

    enum class Check { UNKNOWN, CHECKING, GRANTED, DENIED, NO_SU }

    data class State(val check: Check = Check.UNKNOWN, val message: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // the last answer, so the page doesn't have to call su (and maybe pop the root prompt) just to show it
        if (prefs.getBoolean("root_granted", false)) _state.value = State(Check.GRANTED)
    }

    /** Runs `su -c id -u`: the root manager asks the user the first time. */
    suspend fun check(): Check = withContext(Dispatchers.IO) {
        _state.value = State(Check.CHECKING)
        val result = try {
            val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            p.outputStream.close()
            // the root manager's dialog waits for the user (Magisk: 10 s by default)
            val finished = p.waitFor(60, TimeUnit.SECONDS)
            val out = p.inputStream.bufferedReader().readText().trim()
            if (!finished) { p.destroy(); State(Check.DENIED, L.adb.rootShell.noAnswerFromSuTheRoot) }
            else if (p.exitValue() == 0 && out.lineSequence().lastOrNull()?.trim() == "0") State(Check.GRANTED)
            else State(Check.DENIED, out.ifBlank { "su exited with code ${p.exitValue()}" }.take(300))
        } catch (e: Exception) {
            // "Cannot run program su": not rooted (or su hidden from this app)
            State(Check.NO_SU, "su was not found: this phone is not rooted, or the root manager hides su from AntiGem.")
        }
        if (::appContext.isInitialized) prefs.edit().putBoolean("root_granted", result.check == Check.GRANTED).apply()
        _state.value = result
        result.check
    }

    fun notReadyMessage(): String? = when (_state.value.check) {
        Check.GRANTED -> null
        Check.NO_SU -> "This phone has no su (not rooted, or the root manager hides su from AntiGem)."
        Check.DENIED -> "Root was denied to AntiGem. Allow AntiGem in the root manager (Magisk / KernelSU / APatch), then tap Check root in AntiGem → Settings → ADB Connect → Root."
        else -> "Root hasn't been checked yet. Open AntiGem → Settings → ADB Connect → Root and tap Check root."
    }

    /** Starts `su -c [script]` (stdout and stderr together). */
    suspend fun start(script: String): DeviceAccess.RunningCommand = withContext(Dispatchers.IO) {
        // the first line is the root shell's pid (on a line of its own), so kill can stop what it started
        val process = try {
            ProcessBuilder("su", "-c", "echo \$\$\n$script").redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw IllegalStateException("su was not found: this phone is not rooted, or the root manager hides su from AntiGem.")
        }
        val raw = process.inputStream
        var pid = -1
        // read lazily: su may still be waiting for the user to allow it
        val out = object : InputStream() {
            private var stream: InputStream? = null
            private fun s(): InputStream = stream ?: run {
                val first = DeviceShellService.readFirstLine(raw)
                val n = first.trim().toIntOrNull()
                if (n != null) pid = n
                // not a pid: su printed something (e.g. "Permission denied") that belongs to the output
                val head = if (n == null && first.isNotEmpty()) (first + "\n").toByteArray() else ByteArray(0)
                SequenceInputStream(ByteArrayInputStream(head), raw).also { stream = it }
            }
            override fun read(): Int = s().read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = s().read(b, off, len)
            override fun close() = raw.close()
        }
        object : DeviceAccess.RunningCommand {
            override val input: OutputStream = process.outputStream
            override val output: InputStream = out
            override fun waitFor(): Int = runCatching { process.waitFor() }.getOrDefault(-1)
            override fun kill() {
                if (pid > 0) runCatching {
                    ProcessBuilder("su", "-c", "pkill -9 -P $pid; kill -9 $pid").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
                }
                runCatching { process.destroy() }
            }
        }
    }
}
