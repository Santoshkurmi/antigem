package ai.antigem.data.netcap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.antigem.MainActivity
import ai.antigem.R
import ai.antigem.i18n.L
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The capture VPN: only the chosen apps go through it (addAllowedApplication), so the rest of the phone and AntiGem
 * itself (agents, the engine's own connections) stay outside. The tunnel goes to the engine ([NetcapManager.sendTun]).
 */
class NetcapVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tunnel: ParcelFileDescriptor? = null
    private var apps: List<String> = emptyList()
    private var statsJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val list = intent.getStringArrayListExtra(EXTRA_APPS).orEmpty()
                startForeground(NOTIF_ID, notification(list, 0))
                scope.launch { begin(list) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun begin(list: List<String>) {
        NetcapManager.init(applicationContext)
        val result = runCatching {
            closeTunnel()
            val b = Builder()
                .setSession(L.netcap.service.channel)
                .setMtu(MTU)
                .addAddress("10.111.222.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.111.222.53")
            // IPv6 too, so apps can't get around the capture over it (an unreachable IPv6 server gets a reset and the
            // app falls back to IPv4)
            runCatching {
                b.addAddress("fd00:a9e:c0de::1", 64)
                b.addRoute("::", 0)
                b.addDnsServer("fd00:a9e:c0de::53")
            }
            list.forEach { b.addAllowedApplication(it) }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            b.setBlocking(false)
            b.setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags()))
            val pfd = b.establish() ?: throw IllegalStateException(L.netcap.service.vpnDenied)
            tunnel = pfd
            apps = list
            NetcapManager.sendTun(pfd.fileDescriptor, list, MTU)
            waitForVpnNetwork(5_000)
        }
        pendingStart?.complete(result)
        pendingStart = null
        if (result.isFailure) {
            Log.w(TAG, "capture did not start", result.exceptionOrNull())
            shutdown()
            return
        }
        watchNetwork()
        statsJob?.cancel()
        statsJob = scope.launch {
            NetcapManager.state.collectLatest { st ->
                getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification(apps, st.flows))
            }
        }
    }

    /**
     * Android brings the VPN network up a moment after establish(): until then the captured apps' lookups and first
     * connections fail. The capture counts as started once the network is there (at most [timeoutMs]).
     */
    private suspend fun waitForVpnNetwork(timeoutMs: Long) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION")
            val up = cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true && cm.getLinkProperties(n)?.dnsServers?.isNotEmpty() == true
            }
            if (up) {
                kotlinx.coroutines.delay(500) // routes and DNS settle right after
                return
            }
            kotlinx.coroutines.delay(100)
        }
    }

    /** The phone's DNS servers change with the network: tell the engine. */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = NetcapManager.sendDnsServers()
            override fun onAvailable(network: Network) = NetcapManager.sendDnsServers()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    override fun onRevoke() {
        // the user turned the VPN off, or another VPN started
        shutdown()
    }

    override fun onDestroy() {
        closeTunnel()
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        scope.cancel()
        running = false
        super.onDestroy()
    }

    private fun closeTunnel() {
        tunnel?.let { runCatching { it.close() } }
        tunnel = null
    }

    private fun shutdown() {
        statsJob?.cancel()
        closeTunnel()
        NetcapManager.tunStopped()
        running = false
        pendingStart?.complete(Result.failure(IllegalStateException(L.netcap.service.vpnDenied)))
        pendingStart = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun notification(list: List<String>, flows: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, L.netcap.service.channel, NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, NetcapVpnService::class.java).setAction(ACTION_STOP), pendingFlags())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), pendingFlags())
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(L.netcap.service.capturing)
            .setContentText(L.netcap.service.status(list.joinToString(", ").ifEmpty { "…" }, flows))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, L.common.stop, stop)
            .build()
    }

    companion object {
        private const val TAG = "NetcapVpn"
        private const val CHANNEL = "netcap_capture"
        private const val NOTIF_ID = 7310
        // the tunnel never leaves the phone, so a big MTU is safe: fewer, larger packets between the apps and the
        // engine (every packet written to the tunnel costs a pass through the kernel's network stack)
        private const val MTU = 9000
        const val ACTION_START = "ai.antigem.netcap.START"
        const val ACTION_STOP = "ai.antigem.netcap.STOP"
        const val EXTRA_APPS = "apps"

        @Volatile var running = false
            private set

        @Volatile private var pendingStart: CompletableDeferred<Result<Unit>>? = null

        /** Starts capturing [apps]: asks for the VPN permission when needed, then waits until the engine has the tunnel. */
        suspend fun startCapture(context: Context, apps: List<String>): Result<Unit> {
            // the engine first (it may have to be downloaded): the VPN waits for it only 30 s
            if (!NetcapManager.ensureStarted(context)) {
                return Result.failure(IllegalStateException(NetcapManager.state.value.error ?: "the engine did not start"))
            }
            if (VpnService.prepare(context) != null && !NetcapConsentActivity.ask(context)) {
                return Result.failure(IllegalStateException(L.netcap.service.vpnDenied))
            }
            val d = CompletableDeferred<Result<Unit>>()
            pendingStart?.complete(Result.failure(IllegalStateException("replaced by a new capture")))
            pendingStart = d
            running = true
            ContextCompat.startForegroundService(context,
                Intent(context, NetcapVpnService::class.java).setAction(ACTION_START).putStringArrayListExtra(EXTRA_APPS, ArrayList(apps)))
            val r = withTimeoutOrNull(30_000) { d.await() } ?: Result.failure(IllegalStateException("the VPN did not start in 30 s"))
            if (r.isSuccess) NetcapManager.Prefs.setLastApps(context, apps)
            return r
        }

        fun stop(context: Context) {
            if (!running) return
            runCatching { context.startService(Intent(context, NetcapVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
