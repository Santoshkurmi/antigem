package ai.antigem.data.remote.core

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The proxy choice for the app's connections to the bridge, the AGY hub and other servers on this phone or the local
 * network: those always go direct; everything else follows the phone's proxy setting exactly as before.
 *
 * Android installs a mobile network's proxy (set in the APN by some carriers, or by a proxy app) only while that network
 * is in use, and its default proxy selector has no exception for 127.0.0.1. OkHttp then sent the requests to the
 * phone's own bridge through the carrier's proxy, which cannot reach them: the bridge ran, but the app showed it offline
 * on mobile data and online on Wi-Fi. A proxy can never reach this phone's or the LAN's addresses, so skipping it there
 * changes nothing for anyone else. Only literal addresses and `localhost` count as local (no DNS lookup here).
 */
object LocalDirectProxySelector : ProxySelector() {
    private val direct = listOf(Proxy.NO_PROXY)

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host?.removePrefix("[")?.removeSuffix("]")
        if (host != null && isLocalAddress(host)) return direct
        val system = getDefault()
        if (system == null || system === this) return direct
        return system.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val system = getDefault() ?: return
        if (system !== this && uri != null && sa != null && ioe != null) runCatching { system.connectFailed(uri, sa, ioe) }
    }

    /** This phone (loopback), the local network (private and link-local ranges) or Tailscale's 100.64.0.0/10. */
    fun isLocalAddress(host: String): Boolean {
        val h = host.trim().lowercase().removeSuffix(".")
        if (h == "localhost" || h.endsWith(".localhost") || h == "ip6-localhost") return true
        val literal = h.isNotEmpty() && (h.all { it.isDigit() || it == '.' } || (':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }))
        if (!literal) return false
        val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
        val b = addr.address
        return when (b.size) {
            4 -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64 // 100.64.0.0/10
            16 -> (b[0].toInt() and 0xFE) == 0xFC // fc00::/7 (unique local)
            else -> false
        }
    }

    /** The proxy the phone's setting would use for [url] right now, or null for none (for the Troubleshoot page). */
    fun systemProxyFor(url: String): Proxy? = runCatching {
        val system = getDefault() ?: return null
        system.select(URI(url)).firstOrNull { it.type() != Proxy.Type.DIRECT }
    }.getOrNull()
}
