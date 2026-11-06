package com.example.gemini

import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.widget.Toast
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.updater.PluginUpdateCheckWorker
import com.example.gemini.data.updater.UpdateCheckWorker
import com.telemetrydeck.sdk.TelemetryDeck
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AntiGemApplication : Application(), coil.ImageLoaderFactory {
    /**
     * Images that live on the bridge (a server's files, attachments) need the security header, which Coil's
     * default client does not send. Only requests to the bridge address get it.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val bridge = com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.toHttpUrlOrNull()
                val sameServer = bridge != null && req.url.host == bridge.host && req.url.port == bridge.port
                chain.proceed(
                    if (sameServer) req.newBuilder()
                        .header(com.example.gemini.data.preferences.AuthPreferences.currentFramedHeader, "true")
                        .apply { com.example.gemini.data.preferences.AuthPreferences.edgeHeader()?.let { (n, v) -> header(n, v) } }
                        .build()
                    else req
                )
            }
            .build()
        return coil.ImageLoader.Builder(this).okHttpClient(client).build()
    }

    override fun onCreate() {
        super.onCreate()
        // loads the saved server addresses, Remote mode and the SSH key into memory before anything connects
        com.example.gemini.data.preferences.AuthPreferences(this)
        UpdateCheckWorker.schedule(this)
        PluginUpdateCheckWorker.schedule(this)
        // Reconnects adb when Wireless debugging turns back on (it gets a new port every time)
        AdbConnectManager.init(this)
        com.example.gemini.data.ssh.SshSessionFactory.init(this)
        // Forks build with their own key, so only official release APKs report real usage.
        if (!BuildConfig.DEBUG && !isOfficialRelease()) {
            Toast.makeText(
                this,
                "Unofficial build: analytics disabled. Developer: set your own TelemetryDeck app ID and RELEASE_CERT_SHA256 in AntiGemApplication.kt.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        TelemetryDeck.start(
            this,
            TelemetryDeck.Builder()
                .appID("D98008E6-EEE4-4BBC-97A2-E3790251AECF")
                .namespace("com.santoshkurmi")
                .defaultUser(SimpleTelemetryId.getUniqueUserId(this))
                .testMode(BuildConfig.DEBUG)
                .showDebugLogs(BuildConfig.DEBUG)
                .sendNewSessionBeganSignal(true)
        )

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
