package ai.antigem.ui.browser

import android.os.Handler
import android.os.Looper

/** Extension-owned automation leases. Expiring leases also recover input after a worker crashes. */
object BrowserAutomationController {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = mutableMapOf<String, MutableMap<String, Long>>()
    private val locks = mutableMapOf<String, MutableSet<String>>()

    fun update(tab: BrowserTabSession, owner: String, working: Boolean? = null, locked: Boolean? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (working != null) {
            val owners = busy.getOrPut(tab.id) { mutableMapOf() }
            if (working) owners[owner] = System.currentTimeMillis() + 60_000 else owners.remove(owner)
        }
        if (locked != null) {
            val owners = locks.getOrPut(tab.id) { mutableSetOf() }
            if (locked) owners.add(owner) else owners.remove(owner)
        }
        sync(tab)
        handler.postDelayed({ sync(tab) }, 61_000)
    }

    private fun sync(tab: BrowserTabSession) {
        busy[tab.id]?.entries?.removeAll { it.value < System.currentTimeMillis() }
        tab.aiBusy = !busy[tab.id].isNullOrEmpty()
        tab.aiLocked = !locks[tab.id].isNullOrEmpty()
        if (!tab.aiLocked) tab.userUnlocked = false
        BrowserSessionManager.instance.setAutomationInput(tab, tab.aiBusy)
    }

    fun release(owner: String) {
        handler.post {
            busy.values.forEach { it.remove(owner) }
            locks.values.forEach { it.remove(owner) }
            BrowserSessionManager.instance.tabs.forEach { sync(it) }
        }
    }
}
