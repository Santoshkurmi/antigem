package ai.antigem.data.security

import java.util.concurrent.ConcurrentHashMap

/**
 * The ports AntiGem's own servers on 127.0.0.1 actually got (a server moves on when its default port is taken, some
 * take a random one). Mini apps with the "localhost" permission may never reach them (MiniAppPermissions).
 * Entries stay until the app ends: a port AntiGem used once just stays closed to mini apps.
 */
object LocalPorts {
    private val ports = ConcurrentHashMap.newKeySet<Int>()

    fun add(port: Int) {
        if (port > 0) ports.add(port)
    }

    fun all(): Set<Int> = ports.toSet()
}
