package ai.antigem

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.widget.Toast
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.updater.PluginUpdateCheckWorker
import ai.antigem.data.updater.UpdateCheckWorker
import com.telemetrydeck.sdk.Signal
import com.telemetrydeck.sdk.SignalCache
import com.telemetrydeck.sdk.TelemetryDeck
import com.telemetrydeck.sdk.TelemetryDeckSessionManagerProvider
import com.telemetrydeck.sdk.TelemetryDeckSignalProcessor
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ai.antigem.i18n.L

class AntiGemApplication : Application(), coil.ImageLoaderFactory {
    /**
     * Images that live on the bridge (a server's files, attachments) need the security header, which Coil's
     * default client does not send. Only requests to the bridge address get it.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val bridge = ai.antigem.data.preferences.AuthPreferences.currentBridgeHttpUrl.toHttpUrlOrNull()
                val sameServer = bridge != null && req.url.host == bridge.host && req.url.port == bridge.port
                chain.proceed(
                    if (sameServer) req.newBuilder()
                        .header(ai.antigem.data.preferences.AuthPreferences.currentFramedHeader, "true")
                        .apply { ai.antigem.data.preferences.AuthPreferences.edgeHeader()?.let { (n, v) -> header(n, v) } }
                        .build()
                    else req
                )
            }
            .build()
        return coil.ImageLoader.Builder(this).okHttpClient(client).build()
    }

    override fun onCreate() {
        super.onCreate()
        // a crash opens the crash screen (with the report) instead of the app just vanishing
        ai.antigem.crash.CrashHandler.install(this)
        // the crash screen's own process needs none of the startup below
        if (ai.antigem.crash.CrashHandler.isCrashProcess(this)) return
        // loads the saved server addresses, Remote mode and the SSH key into memory before anything connects
        ai.antigem.data.preferences.AuthPreferences(this)
        // the chosen light / dark themes and the custom ones, before the first screen draws
        ai.antigem.theme.ThemeManager.init(this)
        // the app language (Settings → Language), from its cache, before the first screen draws
        ai.antigem.i18n.LanguageManager.init(this)
        UpdateCheckWorker.schedule(this)
        PluginUpdateCheckWorker.schedule(this)
        // mini app reminders and exact-time tasks: alarms again (Android may have dropped them), off the main thread
        ai.antigem.data.miniapps.MiniAppScheduler.rearmAsync(this)
        Thread { ai.antigem.ui.miniapps.MiniAppShortcuts.migrate(this) }.start()
        // Reconnects adb when Wireless debugging turns back on (it gets a new port every time)
        AdbConnectManager.init(this)
        // ADB / Shizuku / root: which one Android Automation uses, and Shizuku's binder listeners
        ai.antigem.data.adb.DeviceAccess.init(this)
        // the rooted Ubuntu chroot runtime: its installs and which one is in use (no root call here)
        ai.antigem.data.local.chroot.ChrootManager.init(this)
        ai.antigem.data.ssh.SshSessionFactory.init(this)
        // Connect to PC: the paired PCs and the one in use (AuthPreferences reads the latter on its own too)
        ai.antigem.data.pc.PcConnect.init(this)
        // the cloud project new Antigravity chats start in (Remote Agents), before the first chat is created
        ai.antigem.data.agent.agy.AgyCloud.init(this)
        // Forks build with their own key, so only official release APKs report real usage.
        if (!BuildConfig.DEBUG && !isOfficialRelease()) {
            Toast.makeText(
                this,
                L.app.antiGemApplication.unofficialBuildAnalyticsDisabled,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        TelemetryDeck.start(
            this,
            TelemetryDeck.Builder()
                .appID("5BE26330-812A-4293-AFB7-F5C98EAE4B82")
                .namespace("ai.antigem")
                .defaultUser(SimpleTelemetryId.getUniqueUserId(this))
                .testMode(BuildConfig.DEBUG)
                .showDebugLogs(BuildConfig.DEBUG)
                // Only installs and updates are reported (the free plan counts every signal): nothing per app open,
                // and no session tracking (the SDK's would send a signal per open and time how long the app is used)
                .sendNewSessionBeganSignal(false)
                .sessionProvider(NoSessionTracking())
                // the SDK's own file cache crashes the app when storage is full (see SafeSignalCache)
                .signalCache(SafeSignalCache(cacheDir))
        )
        reportInstallOrUpdate()
    }

    /**
     * One signal per install ("App.installed") and per update ("App.updated"); TelemetryDeck adds the app version,
     * Android version and device to it. The version last reported is remembered, so each one is sent only once.
     */
    private fun reportInstallOrUpdate() = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        val prefs = getSharedPreferences("telemetry_device_prefs", MODE_PRIVATE)
        val reported = prefs.getLong("reported_version_code", -1L)
        if (reported == version) return@runCatching
        // nothing reported yet: a fresh install, or an update from a version that didn't report updates
        val installed = reported == -1L && info.firstInstallTime == info.lastUpdateTime
        val params = if (reported == -1L) emptyMap() else mapOf("previousVersionCode" to reported.toString())
        TelemetryDeck.signal(if (installed) "App.installed" else "App.updated", params, null, null)
        prefs.edit().putLong("reported_version_code", version).apply()
    }

    /** Session tracking that does nothing: keeps one session ID per app start, sends no signals. */
    private class NoSessionTracking : TelemetryDeckSessionManagerProvider {
        private var sessionId: UUID = UUID.randomUUID()
        override fun register(ctx: Context?, client: TelemetryDeckSignalProcessor) {}
        override fun stop() {}
        override fun getCurrentSessionID(): UUID = sessionId
        override fun startNewSession(sessionID: UUID) { sessionId = sessionID }
        override fun setFirstSessionID(sessionID: UUID) { sessionId = sessionID }
    }

    /**
     * Stands in for the SDK's PersistentSignalCache, which writes cache/telemetrydeck.json at start and on every send
     * tick without catching errors: with storage full (ENOSPC) its background timer threw and crashed the app.
     * Same file and format (signals queued by older versions are still sent), but a failed read or write is ignored
     * (the signals stay in memory), and the file is written only when the queue changes.
     */
    private class SafeSignalCache(dir: File) : SignalCache {
        private val file = File(dir, "telemetrydeck.json")
        private val serializer = ListSerializer(Signal.serializer())
        private val queue: MutableList<Signal> =
            runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()).toMutableList()

        @Synchronized override fun add(signal: Signal) {
            queue += signal
            save()
        }

        @Synchronized override fun empty(): List<Signal> {
            if (queue.isEmpty()) return emptyList()
            val all = queue.toList()
            queue.clear()
            save()
            return all
        }

        @Synchronized override fun count(): Int = queue.size

        private fun save() {
            runCatching { file.writeText(Json.encodeToString(serializer, queue)) }
        }
    }

    private fun isOfficialRelease(): Boolean = runCatching {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return false
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().any { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02X".format(it) } == RELEASE_CERT_SHA256
        }
    }.getOrDefault(false)

    private companion object {
        // SHA-256 of the release.jks certificate (alias "antigem"). Public, safe to commit.
        const val RELEASE_CERT_SHA256 = "CCF2C681063BA941641519E4EAC7F024E92880CCD06EED5070DAA9717380F315"
    }
}
