package ai.antigem.data.context

import android.content.Context
import android.content.SharedPreferences
import ai.antigem.BuildConfig
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.PromptExpansion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What every chat is told about the app, for every agent alike, hidden in front of the user's message (the chat never
 * shows it). Two parts, both in Settings → Agents → Global context:
 *
 * - The built-in context, written by the app: where the agent runs (Termux on Android, local mode only) and how to
 *   show images, videos and files in this chat. Read-only, on by default, can be turned off.
 * - The user's own entries. Each goes either into the first message of a chat or into every message, and is sent to
 *   Jarvis only if the user says so (Jarvis has strict rules that a note meant for normal chats could break).
 *
 * The first-message part goes once per chat: the app remembers which version each chat got and sends it again only
 * when it changed (app update, edited entry), after Claude compacted the chat, or when the first message was edited.
 */
object GlobalContext {

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val title: String,
        val text: String,
        val enabled: Boolean = true,
        /** Sent with every message instead of only the first one of a chat. */
        val everyMessage: Boolean = false,
        /** Also told to Jarvis (its chats and the chats it starts for tasks). */
        val forJarvis: Boolean = false
    )

    private const val MAX_REMEMBERED_CHATS = 600
    /** Kept instead of a fingerprint for a chat whose first prompt was undone: its next message is a first message again. */
    private const val RESTARTED = "restart"

    private var sp: SharedPreferences? = null
    private var filesDir: String = "/data/data/${BuildConfig.APPLICATION_ID}/files"

    private val _builtInEnabled = MutableStateFlow(true)
    val builtInEnabled: StateFlow<Boolean> = _builtInEnabled.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** chat id → fingerprint of the first-message context it got (oldest first, trimmed). */
    private val sent = LinkedHashMap<String, String>()

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("global_context", Context.MODE_PRIVATE)
        filesDir = context.applicationContext.filesDir.absolutePath
        ai.antigem.data.render.RenderBundle.init(context)
        _builtInEnabled.value = p.getBoolean("builtin_enabled", true)
        _entries.value = runCatching {
            val arr = JSONArray(p.getString("entries", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = o.optString("title"),
                    text = o.optString("text"),
                    enabled = o.optBoolean("enabled", true),
                    everyMessage = o.optBoolean("everyMessage", false),
                    forJarvis = o.optBoolean("forJarvis", false)
                )
            }
        }.getOrDefault(emptyList())
        runCatching {
            val o = JSONObject(p.getString("sent", "{}") ?: "{}")
            synchronized(sent) { o.keys().forEach { k -> sent[k] = o.getString(k) } }
        }
        sp = p
    }

    // ------------------------------------------------------------------ settings

    fun setBuiltInEnabled(on: Boolean) {
        _builtInEnabled.value = on
        sp?.edit()?.putBoolean("builtin_enabled", on)?.apply()
    }

    fun upsert(entry: Entry) {
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) list[i] = entry else list += entry
        saveEntries(list)
    }

    fun delete(id: String) = saveEntries(_entries.value.filter { it.id != id })

    private fun saveEntries(list: List<Entry>) {
        _entries.value = list
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("title", e.title).put("text", e.text).put("enabled", e.enabled)
                    .put("everyMessage", e.everyMessage).put("forJarvis", e.forJarvis)
            )
        }
        sp?.edit()?.putString("entries", arr.toString())?.apply()
    }

    // ------------------------------------------------------------------ the built-in text

    /** Where the agents run right now: what the built-in context tells them. */
    enum class RunPlace { ANTIGEM, CHROOT, SERVER, PC }

    val runPlace: RunPlace
        get() = when {
            AuthPreferences.pcOverride != null -> RunPlace.PC
            AuthPreferences.isRemoteEndpoint() -> RunPlace.SERVER
            ai.antigem.data.local.chroot.ChrootManager.isActiveNow() -> RunPlace.CHROOT
            else -> RunPlace.ANTIGEM
        }

    /** The bridge runs on this phone: AntiGem's built-in environment or the Ubuntu chroot. */
    val runsOnPhone: Boolean
        get() = runPlace == RunPlace.ANTIGEM || runPlace == RunPlace.CHROOT

    /** The agents can read files in the app's own folder: only in AntiGem's built-in environment (not the chroot, a server or a PC). */
    val canReadAppFiles: Boolean
        get() = runPlace == RunPlace.ANTIGEM

    /** Every place: root only when the user asks. */
    private const val ROOT_RULE = "- Root: never use `sudo`, `su` or any other root access on your own. Only when the user asks you to; if a task needs it, ask the user first.\n"

    /** The built-in context for the current setup (Settings shows exactly this). [h]: the heading level of its sections. */
    fun builtInText(h: String = "##"): String = buildString {
        append("$h Where you run\n")
        when (runPlace) {
            RunPlace.ANTIGEM -> {
                append("- Android, in the AntiGem app's built-in Linux environment (Termux packages), as the app's own user. There is no sudo; /tmp, /usr and /etc are not usable.\n")
                append("- Temporary files: use \$TMPDIR ($filesDir/usr/tmp), never /tmp. Install packages with `pkg install <name>`.\n")
                append("- Home is \$HOME ($filesDir/home); phone storage is /sdcard.\n")
                append("- Root & phone access: the phone may be rooted (`su -c '…'`), connected to adb (`adb shell …`) or have Shizuku (`rish -c '…'`). Never use any of them on your own; only when the user asks you to.\n\n")
            }
            RunPlace.CHROOT -> {
                val inst = ai.antigem.data.local.chroot.ChrootManager.activeInstallNow()
                val user = inst?.user ?: "ubuntu"
                append("- An Android phone, inside a rooted Ubuntu chroot (${inst?.os?.takeIf { it.isNotBlank() } ?: "Ubuntu"}): a normal glibc Ubuntu where apt, /tmp, /usr and /etc work. It shares Android's kernel and has no systemd (`systemctl` does not work): start services yourself.\n")
                append("- You run as user `$user` (home /home/$user). `sudo` (often without a password) gives root of the WHOLE PHONE, not only of Ubuntu.\n")
                append("- Root: never use `sudo`, `su` or root on your own; only when the user asks you to. A task needs a package? Ask the user before running `sudo apt install <name>`.\n")
                append("- Phone storage is /sdcard and USB drives are under /storage, when the user shared them into the chroot. Folders shared into the chroot are the phone's real folders: what you delete there is deleted on the phone.\n")
                append("- Android's own tools (`termux-*`, `am`, `pm`) are not available here. The phone may be reachable through adb (`adb shell …`), Shizuku (`rish -c '…'`) or its root (`android-su -c '…'`): use them only when the user asks you to.\n\n")
            }
            RunPlace.SERVER -> {
                append("- On the user's own server (not the phone): the user reads and answers in the AntiGem app on their phone. Paths are paths on the server.\n")
                append(ROOT_RULE)
                append("\n")
            }
            RunPlace.PC -> {
                val pc = AuthPreferences.pcOverride?.pcName?.takeIf { it.isNotBlank() }
                append("- On the user's computer${pc?.let { " ($it)" } ?: ""}, through AntiGem for VS Code: right now the user reads and answers on their phone (AntiGem's Connect to PC). Paths are paths on that computer.\n")
                append(ROOT_RULE)
                append("\n")
            }
        }
        append("$h Showing things in this chat (paths absolute, no spaces, each on its own line)\n")
        append("- Image: `![caption](/abs/path/pic.png)` · Video: `![caption](/abs/path/clip.mp4)` (plays in the chat)\n")
        append("- File: `[name](/abs/path/file.ext)` (the user taps to open)\n")
        append("Show images, videos and documents you create this way instead of only naming the path.\n\n")
        append("$h Asking the user\n")
        append("Request vague or ambiguous? Don't guess: ask with your question tool (e.g. AskUserQuestion), all at once, with a few options.\n\n")
        append("$h Node projects\n")
        append("New projects: use pnpm (faster, less disk) unless the user says otherwise. Existing npm projects (package-lock.json) stay on npm.")
    }.trimEnd()

    // ------------------------------------------------------------------ what goes where

    private fun active(): List<Entry> = _entries.value.filter { it.enabled && it.text.isNotBlank() }

    private fun entriesBlock(list: List<Entry>): String = list.joinToString("\n\n") { e ->
        (if (e.title.isNotBlank()) "### ${e.title.trim()}\n" else "") + e.text.trim()
    }

    /** The first-message context of a normal chat, or null when there is nothing to send. */
    fun firstMessageBody(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += builtInText()
        // Settings → STEM & diagrams: the extra blocks this chat renders (normal chats only, not Jarvis)
        ai.antigem.data.render.RenderBundle.promptSection(canReadFiles = canReadAppFiles)?.let { parts += it }
        // Settings → Language → AI replies: sent even with the built-in context off
        ai.antigem.i18n.LanguageManager.aiReplyContext()?.let { parts += it }
        val own = active().filter { !it.everyMessage }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** What every message of a normal chat carries, or null. */
    fun everyMessageBody(): String? {
        val own = active().filter { it.everyMessage }
        if (own.isEmpty()) return null
        return "[The user's standing instructions, sent with every message]\n" + entriesBlock(own)
    }

    /** Changes whenever the first-message context changes. */
    fun fingerprint(): String = Integer.toHexString((firstMessageBody() ?: "").hashCode())

    /**
     * A normal chat message with the hidden context in front. [chatId]: the chat on screen (blank for a new one);
     * [hasEarlierMessages]: the chat already has messages. Returns the prompt and, when the first-message context was
     * included, its fingerprint (record it with [markSent] once the chat id is known).
     */
    fun wrap(chatId: String?, hasEarlierMessages: Boolean, text: String): Pair<String, String?> {
        val first = firstMessageBody()
        val fp = fingerprint()
        val known = chatId?.takeIf { it.isNotBlank() }?.let { id -> synchronized(sent) { sent[id] } }
        // a chat whose first prompt was undone starts over, even while its old messages may still be listed
        val fresh = !hasEarlierMessages || known == RESTARTED
        val sendFirst = first != null && (fresh || known != fp)
        val every = everyMessageBody()
        if (!sendFirst && every == null) return text to null
        val note = buildString {
            if (sendFirst) {
                append(
                    when {
                        fresh -> "[AntiGem app context: from the app the user chats through, not written by the user. Follow it for this whole chat; it is not repeated.]"
                        known != null -> "[AntiGem app context, updated: it replaces the earlier copy in this chat. Follow it from now on.]"
                        else -> "[AntiGem app context: from the app the user chats through. Follow it from now on in this chat.]"
                    }
                ).append("\n\n").append(first)
            }
            if (every != null) {
                if (sendFirst) append("\n\n")
                append(every)
            }
        }
        if (sendFirst && !chatId.isNullOrBlank()) markSent(chatId, fp)
        return (PromptExpansion.hidden(note) + text) to (if (sendFirst) fp else null)
    }

    fun markSent(chatId: String, fp: String) {
        if (chatId.isBlank()) return
        synchronized(sent) {
            sent.remove(chatId)
            sent[chatId] = fp
            while (sent.size > MAX_REMEMBERED_CHATS) sent.remove(sent.keys.first())
        }
        persistSent()
    }

    /** The chat's first prompt was undone: it starts over, so its next message carries the context as a first message. */
    fun restart(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        markSent(chatId, RESTARTED)
    }

    /** The chat lost its first-message context (compacted, first message edited): send it again with the next message. */
    fun forget(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        val removed = synchronized(sent) { sent.remove(chatId) } != null
        if (removed) persistSent()
    }

    private fun persistSent() {
        val o = JSONObject()
        synchronized(sent) { sent.forEach { (k, v) -> o.put(k, v) } }
        sp?.edit()?.putString("sent", o.toString())?.apply()
    }

    // ------------------------------------------------------------------ Jarvis

    private const val JARVIS_PRIORITY =
        "These are the user's notes for normal chats, shared with Jarvis. Follow them where they fit; if one conflicts with the Jarvis guide above, the Jarvis guide wins."

    /** A section of the Jarvis guide (first message of a Jarvis chat): the built-in context and the user's Jarvis-enabled first-message entries. */
    fun jarvisGuideSection(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += "## AntiGem app context (for you and every chat you start)\n" + builtInText("###")
        ai.antigem.i18n.LanguageManager.aiReplyContext()?.let { parts += it }
        val own = active().filter { it.forJarvis && !it.everyMessage }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n$JARVIS_PRIORITY\n\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** The user's Jarvis-enabled every-message entries, for the note in front of every Jarvis message. */
    fun jarvisEveryMessage(): String? {
        val own = active().filter { it.forJarvis && it.everyMessage }
        if (own.isEmpty()) return null
        return "user's standing instructions (the Jarvis rules win on conflict): " + own.joinToString(" | ") { e ->
            (if (e.title.isNotBlank()) "${e.title.trim()}: " else "") + e.text.trim().replace("\n", " ")
        }
    }

    /** Changes whenever what Jarvis' guide carries from here changes (the guide is then sent again). */
    fun jarvisFingerprint(): String = Integer.toHexString(((jarvisGuideSection() ?: "") + (jarvisEveryMessage() ?: "")).hashCode())

    /** The context for a chat Jarvis starts for a task: the built-in context and the user's Jarvis-enabled entries. */
    fun taskBody(): String? {
        val parts = mutableListOf<String>()
        if (_builtInEnabled.value) parts += "[AntiGem app context: from the app, not the user. Follow it for this whole chat.]\n\n" + builtInText()
        ai.antigem.i18n.LanguageManager.aiReplyContext()?.let { parts += it }
        val own = active().filter { it.forJarvis }
        if (own.isNotEmpty()) parts += "## The user's own instructions\n" + entriesBlock(own)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** A follow-up Jarvis sends to a task chat: the user's Jarvis-enabled every-message entries in front. */
    fun taskFollowUp(text: String): String {
        val own = active().filter { it.forJarvis && it.everyMessage }
        if (own.isEmpty()) return text
        return PromptExpansion.hidden("[The user's standing instructions, sent with every message]\n" + entriesBlock(own)) + text
    }
}
