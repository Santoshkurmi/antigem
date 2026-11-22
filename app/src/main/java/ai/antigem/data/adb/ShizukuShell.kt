package ai.antigem.data.adb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import ai.antigem.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

/**
 * Shizuku mode: commands run as the shell user (or root, when Shizuku was started with root) through Shizuku, with no
 * adb pairing. AntiGem asks Shizuku for permission once; then Shizuku starts [DeviceShellService] (from this APK) in a
 * process of its own, which runs the commands.
 *
 * Shizuku itself has to be running: without root it stops at every reboot and is started again in the Shizuku app.
 */
object ShizukuShell {
    private const val TAG = "ShizukuShell"
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4320
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

    data class Status(
        val installed: Boolean = false,
        /** Shizuku's service is running (its binder answers). */
        val running: Boolean = false,
        /** Shizuku older than v11: its API is not supported. */
        val tooOld: Boolean = false,
        val granted: Boolean = false,
        /** The user tapped Deny in Shizuku's permission dialog (cleared by the next request). */
        val denied: Boolean = false,
        /** 0 when Shizuku runs as root, 2000 as the shell user, -1 unknown. */
        val uid: Int = -1,
        val version: Int = 0
    ) {
        val ready: Boolean get() = running && !tooOld && granted
        val userLabel: String get() = when (uid) { 0 -> "root"; 2000 -> "shell"; -1 -> "unknown"; else -> "uid $uid" }
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context
    private val bindLock = Mutex()
    @Volatile private var service: IBinder? = null
    private var connection: ServiceConnection? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        runCatching {
            // Shizuku hands its binder to the app's ShizukuProvider when either of them starts
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                service = null
                refresh()
            }
            Shizuku.addRequestPermissionResultListener { requestCode, result ->
                if (requestCode == PERMISSION_REQUEST) refresh(denied = result != PackageManager.PERMISSION_GRANTED)
            }
        }.onFailure { Log.w(TAG, "Shizuku listeners: ${it.message}") }
        refresh()
    }

    fun isInstalled(context: Context = appContext): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    fun refresh(denied: Boolean? = null) {
        if (!::appContext.isInitialized) return
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val tooOld = running && runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val usable = running && !tooOld
        val granted = usable && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        _status.value = Status(
            installed = isInstalled(),
            running = running,
            tooOld = tooOld,
            granted = granted,
            denied = !granted && (denied ?: _status.value.denied),
            uid = if (usable) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1,
            version = if (usable) runCatching { Shizuku.getVersion() }.getOrDefault(0) else 0
        )
    }

    /** Shows Shizuku's permission dialog. False when Shizuku isn't running (nothing to ask). */
    fun requestPermission(): Boolean {
        refresh(denied = false)
        val s = _status.value
        if (!s.running || s.tooOld) return false
        if (s.granted) return true
        return runCatching { Shizuku.requestPermission(PERMISSION_REQUEST); true }
            .onFailure { Log.w(TAG, "requestPermission: ${it.message}") }.getOrDefault(false)
    }

    /** Opens the Shizuku app, or its download page when it isn't installed. */
    fun openShizuku(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Why commands can't run through Shizuku now, or null when they can. */
    fun notReadyMessage(): String? {
        refresh()
        val s = _status.value
        return when {
            !s.installed -> "Shizuku is not installed. Install it (${DOWNLOAD_URL}), start it, then grant AntiGem in AntiGem → Settings → ADB Connect → Shizuku."
            !s.running -> "Shizuku is not running. Open the Shizuku app and start it (after every reboot unless the phone is rooted)."
            s.tooOld -> "This Shizuku is too old (before v11). Update Shizuku."
            !s.granted -> "AntiGem has no Shizuku permission yet. Open AntiGem → Settings → ADB Connect → Shizuku and tap Grant."
            else -> null
        }
    }

    // ---------------- the helper service ----------------

    private fun serviceArgs(): Shizuku.UserServiceArgs {
        // a new APK (update or debug install) must replace a helper still running the old code
        val updated = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return Shizuku.UserServiceArgs(ComponentName(appContext.packageName, DeviceShellService::class.java.name))
            .tag("device_shell")
            .processNameSuffix("device_shell")
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .version(((updated / 1000) % Int.MAX_VALUE).toInt())
    }

    /** The running helper, started (and waited for) when needed. */
    private suspend fun helper(): IBinder = bindLock.withLock {
        service?.takeIf { it.pingBinder() }?.let { return it }
        notReadyMessage()?.let { throw IllegalStateException(it) }
        val binder = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<IBinder> { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null || !binder.pingBinder()) return
                        service = binder
                        if (cont.isActive) cont.resume(binder)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                }
                connection = conn
                // Shizuku delivers the connection on the main thread; binding from it too keeps the order simple
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { Shizuku.bindUserService(serviceArgs(), conn) }
                        .onFailure { e -> if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("Shizuku: ${e.message}"))) }
                }
            }
        }
        binder ?: throw IllegalStateException("Shizuku didn't start AntiGem's helper within 20 seconds. Restart Shizuku and try again.")
    }

    /** Stops the helper process (it is started again by the next command). */
    fun stopHelper() {
        val conn = connection ?: return
        connection = null
        service = null
        runCatching { Shizuku.unbindUserService(serviceArgs(), conn, true) }
    }

    /** The user the helper runs as (2000 shell, 0 root), asked from the helper itself. */
    suspend fun helperUid(): Int = withContext(Dispatchers.IO) {
        val b = helper()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DeviceShellService.DESCRIPTOR)
            b.transact(DeviceShellService.TRANSACTION_PING, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** Starts `sh -c [script]` in the helper. */
    suspend fun start(script: String): DeviceAccess.RunningCommand = withContext(Dispatchers.IO) {
        val b = helper()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val (id, fds) = try {
            data.writeInterfaceToken(DeviceShellService.DESCRIPTOR)
            data.writeString(script)
            b.transact(DeviceShellService.TRANSACTION_RUN, data, reply, 0)
            reply.readException()
            val id = reply.readInt()
            id to (reply.createTypedArray(ParcelFileDescriptor.CREATOR) ?: throw IllegalStateException("The Shizuku helper returned no pipes"))
        } finally {
            data.recycle(); reply.recycle()
        }
        val status = ParcelFileDescriptor.AutoCloseInputStream(fds[2])
        object : DeviceAccess.RunningCommand {
            override val input: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(fds[0])
            override val output: InputStream = ParcelFileDescriptor.AutoCloseInputStream(fds[1])
            override fun waitFor(): Int = status.use { it.bufferedReader().readLine()?.trim()?.toIntOrNull() ?: -1 }
            override fun kill() {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                try {
                    d.writeInterfaceToken(DeviceShellService.DESCRIPTOR)
                    d.writeInt(id)
                    b.transact(DeviceShellService.TRANSACTION_KILL, d, r, 0)
                } catch (_: Exception) {
                } finally {
                    d.recycle(); r.recycle()
                }
            }
        }
    }
}
