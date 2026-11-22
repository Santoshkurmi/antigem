package ai.antigem.data.adb

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * AntiGem's helper inside Shizuku (a Shizuku "user service"): Shizuku starts it from this APK in a process of its own,
 * as the shell user (uid 2000), or as root when Shizuku itself runs as root. It starts commands for the app and hands
 * back pipes to them.
 *
 * Nothing of the app exists in that process (no Application, no Context, none of the app's singletons), so this class
 * stays self-contained. A plain Binder with hand-written transactions instead of AIDL: [ShizukuShell] is its only client.
 */
class DeviceShellService : Binder() {

    private class Running(val process: Process) {
        @Volatile var pid: Int = -1
    }

    private val running = ConcurrentHashMap<Int, Running>()
    private val nextId = AtomicInteger(1)

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_DESTROY -> {
                // Shizuku asks this when the app unbinds for good (or a newer version of the app replaces it)
                running.values.forEach { kill(it) }
                exitProcess(0)
            }
            TRANSACTION_RUN -> {
                data.enforceInterface(DESCRIPTOR)
                val script = data.readString().orEmpty()
                try {
                    val (id, fds) = start(script)
                    reply?.writeNoException()
                    reply?.writeInt(id)
                    // the parcel gets its own copies of the descriptors; ours are closed once it is written
                    reply?.writeTypedArray(fds, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                } catch (e: Exception) {
                    // only a few exception types cross binder: report it as an IllegalStateException
                    reply?.writeException(IllegalStateException(e.message ?: e.javaClass.simpleName))
                }
                return true
            }
            TRANSACTION_KILL -> {
                data.enforceInterface(DESCRIPTOR)
                running.remove(data.readInt())?.let { kill(it) }
                reply?.writeNoException()
                return true
            }
            TRANSACTION_PING -> {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(android.os.Process.myUid())
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * Starts `sh -c [script]` (stdout and stderr together) and returns its id and three pipe ends for the app: the
     * command's input, its output, and a status pipe that gets the exit code as text when it ends.
     */
    private fun start(script: String): Pair<Int, Array<ParcelFileDescriptor>> {
        // the first output line is the shell's pid, so kill can also stop the commands it started (on a line of its
        // own: a syntax error further on must not stop it from being printed)
        val process = ProcessBuilder("/system/bin/sh", "-c", "echo \$\$\n$script").redirectErrorStream(true).start()
        val run = Running(process)
        val id = nextId.getAndIncrement()
        running[id] = run
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        val status = ParcelFileDescriptor.createPipe()

        thread(name = "device-shell-in-$id", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(input[0]).use { it.copyTo(process.outputStream) }
            } catch (_: Exception) {
            } finally {
                runCatching { process.outputStream.close() }
            }
        }
        thread(name = "device-shell-out-$id", isDaemon = true) {
            val stream = process.inputStream
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output[1]).use { out ->
                    val first = readFirstLine(stream)
                    val pid = first.trim().toIntOrNull()
                    if (pid != null) run.pid = pid else if (first.isNotEmpty()) out.write((first + "\n").toByteArray())
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // the app stopped reading (it timed out or was closed): nobody gets the output any more
                kill(run)
            }
            val exit = runCatching { process.waitFor() }.getOrDefault(-1)
            running.remove(id)
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(status[1]).use { it.write("$exit\n".toByteArray()) } }
        }
        return id to arrayOf(input[1], output[0], status[0])
    }

    private fun kill(run: Running) {
        val pid = run.pid
        if (pid > 0) {
            // its direct children first: `sh -c` would otherwise leave them running
            runCatching { Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "pkill -9 -P $pid; kill -9 $pid")).waitFor() }
        }
        runCatching { run.process.destroy() }
    }

    companion object {
        const val DESCRIPTOR = "ai.antigem.data.adb.DeviceShellService"
        /** Shizuku's own code for a user service's destroy() (FIRST_CALL_TRANSACTION + 16777114). */
        const val TRANSACTION_DESTROY = 16777115
        const val TRANSACTION_RUN = FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_KILL = FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_PING = FIRST_CALL_TRANSACTION + 3

        /**
         * The first output line (the "pid" line the start script prints), without its newline. At most 32 bytes are
         * read: anything else is output, which the caller passes on.
         */
        fun readFirstLine(stream: InputStream): String {
            val line = java.io.ByteArrayOutputStream()
            while (line.size() < 32) {
                val b = stream.read()
                if (b < 0 || b == '\n'.code) break
                line.write(b)
            }
            return String(line.toByteArray(), Charsets.UTF_8)
        }
    }
}
