package ai.antigem.data.privatespace

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

enum class SpaceStatus { NOT_SET_UP, LOCKED, UNLOCKED }

/** What the lock screen offers besides the master password. */
data class QuickUnlockState(
    val bioEnabled: Boolean = false,
    val pinEnabled: Boolean = false,
    val pinLength: Int = 0,
    val pinTriesLeft: Int = 0,
    /** PIN tries the security chip counted but AntiGem's PIN pad didn't make (something called the key itself). */
    val pinOutsideTries: Int = 0,
    /** The master password is due ([masterEveryDays] since it was last typed): fingerprint and PIN wait for it. */
    val masterDue: Boolean = false,
    val masterEveryDays: Int = PrivateSpace.MASTER_EVERY_DEFAULT,
    /** Shown until dismissed: why a quick unlock was turned off, or a warning about something else on the phone. */
    val notice: QuickNotice? = null,
    /** The number [notice] is about (PIN tries made outside AntiGem). */
    val noticeCount: Int = 0
)

enum class QuickNotice {
    FINGERPRINT_CHANGED,
    PIN_LOCKED_OUT,
    /** A chip key under a Private Space name wasn't one Private Space made: fingerprint and PIN were turned off. */
    KEYS_TAMPERED,
    /** The chip counted PIN tries that weren't typed on AntiGem's PIN pad. */
    PIN_OUTSIDE_TRIES
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val triesLeft: Int) : PinResult
    data object LockedOut : PinResult
    data object Unavailable : PinResult
}

/** An opened backup file: who it belongs to and when it was made (before the password is asked). */
class SpaceBackupFile internal constructor(internal val json: JSONObject) {
    val spaceId: String get() = json.optString("id")
    val exportedAt: Long get() = json.optLong("exported", json.optLong("created"))
    val hasRecoveryKey: Boolean get() = json.optJSONObject("rk") != null
}

/** A backup opened with its password or recovery key. */
class OpenedBackup internal constructor(internal val file: SpaceBackupFile, internal val payload: SpacePayload, internal val key: ByteArray) {
    val items: List<SpaceItem> get() = payload.items
    val activeCount: Int get() = payload.items.count { !it.inTrash }
    val isThisSpace: Boolean get() = file.spaceId == PrivateSpace.spaceId
}

/**
 * Private Space: passwords, API keys, 2FA, SSH keys and private notes, encrypted on the phone and opened only by the
 * user. Text only.
 *
 * Kept away from agents on purpose: it has no MCP, no bridge endpoint, no chat context and no search hook; nothing
 * outside the Private Space page calls into it. Its files live in the app's no_backup folder (outside usr/ and home/,
 * so the terminal's home, rootfs backups and the file picker never hold them), and all they hold is ciphertext:
 * - space.json: the encrypted space (Argon2id + AES-256-GCM, see [SpaceCrypto]); the same format as a backup file.
 * - device.json: this phone's quick unlocks (fingerprint / PIN keys in the security chip, [SpaceHardwareKeys]);
 *   never in backups, so a backup only opens with the master password or the recovery key.
 * Unlocked, the space key and the decrypted items exist only in this process's memory until [lock].
 */
object PrivateSpace {
    private const val TAG = "PrivateSpace"
    private const val FORMAT = "antigem.privatespace"
    private const val VERSION = 1
    private const val SAVE_DELAY_MS = 250L
    private const val TRASH_KEEP_MS = 30L * 24 * 3600 * 1000
    private const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    const val MASTER_EVERY_DEFAULT = 14
    val MASTER_EVERY_CHOICES = listOf(1, 3, 7, 14, 30)
    const val BACKUP_EXTENSION = "agspace"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var appContext: Context? = null
    private var dir: File? = null

    private val _status = MutableStateFlow(SpaceStatus.NOT_SET_UP)
    val status: StateFlow<SpaceStatus> = _status.asStateFlow()

    private val _items = MutableStateFlow<List<SpaceItem>>(emptyList())
    val items: StateFlow<List<SpaceItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(SpaceSettings())
    val settings: StateFlow<SpaceSettings> = _settings.asStateFlow()

    private val _quick = MutableStateFlow(QuickUnlockState())
    val quick: StateFlow<QuickUnlockState> = _quick.asStateFlow()

    /** Opened with the recovery key: a new master password has to be set before anything else. */
    private val _needsNewPassword = MutableStateFlow(false)
    val needsNewPassword: StateFlow<Boolean> = _needsNewPassword.asStateFlow()

    /** The Private Space page is on screen (AndroidAutomation won't read or picture AntiGem's screen then). */
    @Volatile var pageOpen: Boolean = false
        private set

    // ---- state behind the lock ----
    private var header: JSONObject? = null // the space file without "data"
    private var device: JSONObject = JSONObject()
    private var spaceKey: ByteArray? = null
    private var saveJob: Job? = null
    private var autoLockJob: Job? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    internal val spaceId: String? get() = header?.optString("id")

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            dir = File(context.applicationContext.noBackupFilesDir, "private_space").apply { mkdirs() }
            header = readSpaceFile()?.apply { remove("data") }
            device = readJson(deviceFile()) ?: JSONObject()
            if (header != null && device.optString("spaceId") != header?.optString("id")) device = JSONObject()
            _status.value = if (header == null) SpaceStatus.NOT_SET_UP else SpaceStatus.LOCKED
            publishQuick()
        }
    }

    private fun spaceFile() = File(dir, "space.json")
    private fun prevFile() = File(dir, "space.json.prev")
    private fun deviceFile() = File(dir, "device.json")

    private fun readJson(f: File): JSONObject? = try {
        if (f.isFile) JSONObject(f.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable ${f.name}: ${e.javaClass.simpleName}")
        null
    }

    /** The space file, or the one before it if the last write was cut off. */
    private fun readSpaceFile(): JSONObject? =
        readJson(spaceFile())?.takeIf { it.optString("format") == FORMAT } ?: readJson(prevFile())?.takeIf { it.optString("format") == FORMAT }

    private fun writeAtomically(target: File, text: String, keepPrevious: Boolean) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (keepPrevious && target.isFile) {
            val prev = prevFile()
            prev.delete()
            target.renameTo(prev)
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Could not write ${target.name}")
        }
    }

    private fun aad(id: String, part: String) = "$FORMAT/$VERSION/$part/$id"

    // ---------------- set up ----------------

    /** Makes the space protected by [password] and opens it. Returns the recovery key to show the user once. */
    suspend fun create(password: CharArray): String = withContext(Dispatchers.Default) {
        require(PasswordTools.masterPasswordOk(password))
        val id = newSpaceId()
        val key = SpaceCrypto.randomBytes(SpaceCrypto.KEY_BYTES)
        val kdf = SpaceCrypto.KdfParams.fresh()
        val kek = SpaceCrypto.passwordKey(password, kdf)
        val recovery = SpaceCrypto.newRecoveryKey()
        val h = JSONObject()
            .put("format", FORMAT).put("v", VERSION).put("kind", "space")
            .put("id", id).put("created", System.currentTimeMillis())
            .put("kdf", kdf.toJson())
            .put("pw", SpaceCrypto.seal(kek, key, aad(id, "pw")))
            .put("rk", recoveryBox(id, key, recovery))
        kek.fill(0)
        synchronized(lock) {
            header = h
            device = JSONObject().put("spaceId", id).put("lastMaster", System.currentTimeMillis()).put("masterEvery", MASTER_EVERY_DEFAULT)
            spaceKey = key
            _items.value = emptyList()
            _settings.value = SpaceSettings()
            persistNow()
            saveDevice()
            onUnlocked()
        }
        recovery
    }

    private fun recoveryBox(id: String, key: ByteArray, recovery: String): JSONObject {
        val salt = SpaceCrypto.randomBytes(16)
        val rkKey = SpaceCrypto.recoveryKeyBytes(SpaceCrypto.normalizeRecoveryKey(recovery)!!, salt)
        val box = SpaceCrypto.seal(rkKey, key, aad(id, "rk")).put("salt", SpaceCrypto.b64(salt))
        rkKey.fill(0)
        return box
    }

    // ---------------- unlock ----------------

    private fun keyFromPassword(h: JSONObject, password: CharArray): ByteArray? {
        val kdf = SpaceCrypto.KdfParams.fromJson(h.getJSONObject("kdf"))
        val kek = SpaceCrypto.passwordKey(password, kdf)
        return try {
            SpaceCrypto.open(kek, h.getJSONObject("pw"), aad(h.getString("id"), "pw"))
        } catch (e: AEADBadTagException) {
            null
        } finally {
            kek.fill(0)
        }
    }

    private fun keyFromRecovery(h: JSONObject, typed: String): ByteArray? {
        val normalized = SpaceCrypto.normalizeRecoveryKey(typed) ?: return null
        val rk = h.optJSONObject("rk") ?: return null
        val rkKey = SpaceCrypto.recoveryKeyBytes(normalized, SpaceCrypto.unb64(rk.getString("salt")))
        return try {
            SpaceCrypto.open(rkKey, rk, aad(h.getString("id"), "rk"))
        } catch (e: AEADBadTagException) {
            null
        } finally {
            rkKey.fill(0)
        }
    }

    /** Decrypts the space's contents with [key] and opens it; false if the file doesn't match the key. */
    private fun openWith(key: ByteArray): Boolean {
        val file = readSpaceFile() ?: return false
        val id = file.getString("id")
        val payload = try {
            SpacePayload.fromJson(JSONObject(String(SpaceCrypto.open(key, file.getJSONObject("data"), aad(id, "data")), Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "Space data unreadable: ${e.javaClass.simpleName}")
            return false
        }
        synchronized(lock) {
            header = file.apply { remove("data") }
            spaceKey = key
            val now = System.currentTimeMillis()
            // the trash keeps things 30 days
            _items.value = payload.items.filter { it.deletedAt == null || now - it.deletedAt < TRASH_KEEP_MS }
            _settings.value = payload.settings
            onUnlocked()
        }
        return true
    }

    suspend fun unlockWithPassword(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        val h = header ?: return@withContext false
        val key = keyFromPassword(h, password) ?: return@withContext false
        if (!openWith(key)) return@withContext false
        synchronized(lock) {
            device.put("lastMaster", System.currentTimeMillis())
            // "unlock with the master password" notices are done now; warnings about tampering stay until dismissed
            val n = device.optString("notice")
            if (n == QuickNotice.FINGERPRINT_CHANGED.name || n == QuickNotice.PIN_LOCKED_OUT.name) device.remove("notice")
            saveDevice()
            publishQuick()
        }
        true
    }

    /** Opens with the recovery key; the user then has to pick a new master password ([setNewPasswordAfterRecovery]). */
    suspend fun unlockWithRecoveryKey(typed: String): Boolean = withContext(Dispatchers.Default) {
        val h = header ?: return@withContext false
        val key = keyFromRecovery(h, typed) ?: return@withContext false
        if (!openWith(key)) return@withContext false
        _needsNewPassword.value = true
        true
    }

    suspend fun unlockWithPin(pin: CharArray): PinResult = withContext(Dispatchers.Default) {
        val h = header ?: return@withContext PinResult.Unavailable
        val p = device.optJSONObject("pin") ?: return@withContext PinResult.Unavailable
        if (_quick.value.masterDue) return@withContext PinResult.Unavailable
        val alias = p.getString("alias")
        val recorded = p.optInt("tries", SpaceHardwareKeys.PIN_TRIES)
        val chip = SpaceHardwareKeys.pinUsesLeft(alias)
        if (chip == SpaceHardwareKeys.UNLIMITED) {
            // Private Space never makes a PIN key without a try limit: it was replaced outside AntiGem
            keysTampered()
            return@withContext PinResult.Unavailable
        }
        // the chip's count is the truth; fewer than this app used means something else guessed (see pinUsesLeft)
        val known = chip?.takeIf { it <= SpaceHardwareKeys.PIN_TRIES }
        val outside = p.optInt("outside") + if (known != null && known < recorded) recorded - known else 0
        val salt = SpaceCrypto.unb64(p.getString("salt"))
        val answer = SpaceHardwareKeys.pinAnswer(alias, salt, pin)
        if (answer == null) {
            // the key is gone although this app still had tries left: used up (or deleted) by something else
            pinLockedOut(p, outside = if (chip == null) p.optInt("outside") + recorded else outside)
            return@withContext PinResult.LockedOut
        }
        val wrapKey = SpaceCrypto.hkdf(answer, salt, "antigem.privatespace.pin.v1")
        answer.fill(0)
        val key = try {
            SpaceCrypto.open(wrapKey, p.getJSONObject("box"), aad(h.getString("id"), "pin"))
        } catch (e: AEADBadTagException) {
            null
        } finally {
            wrapKey.fill(0)
        }
        if (key == null) {
            val left = ((known ?: recorded) - 1).coerceAtLeast(0)
            if (left == 0) {
                pinLockedOut(p, outside)
                return@withContext PinResult.LockedOut
            }
            synchronized(lock) {
                p.put("tries", left).put("outside", outside)
                saveDevice()
                publishQuick()
            }
            return@withContext PinResult.Wrong(left)
        }
        if (!openWith(key)) return@withContext PinResult.Unavailable
        // a fresh chip key with all its tries, and the old one deleted
        runCatching { wrapWithPin(pin) }.onFailure { Log.w(TAG, "PIN key renewal failed: ${it.javaClass.simpleName}") }
        if (outside > 0) {
            synchronized(lock) {
                device.put("notice", QuickNotice.PIN_OUTSIDE_TRIES.name).put("noticeCount", outside)
                saveDevice()
                publishQuick()
            }
        }
        PinResult.Ok
    }

    /**
     * A chip key under a Private Space name wasn't made by Private Space: something running as the app (an agent in
     * the terminal) replaced it. Fingerprint and PIN both turn off, so only the master password opens the space.
     */
    private fun keysTampered() {
        Log.w(TAG, "A Private Space key was replaced outside AntiGem: fingerprint and PIN turned off")
        SpaceHardwareKeys.deleteAll()
        synchronized(lock) {
            device.remove("pin")
            device.remove("bio")
            device.put("notice", QuickNotice.KEYS_TAMPERED.name)
            saveDevice()
            publishQuick()
        }
    }

    /** The PIN's tries are used up. [outside]: how many of them weren't typed on AntiGem's PIN pad (then that is the notice). */
    private fun pinLockedOut(p: JSONObject, outside: Int) {
        SpaceHardwareKeys.deletePinKey(p.optString("alias"))
        synchronized(lock) {
            device.remove("pin")
            if (outside > 0) device.put("notice", QuickNotice.PIN_OUTSIDE_TRIES.name).put("noticeCount", outside)
            else device.put("notice", QuickNotice.PIN_LOCKED_OUT.name)
            saveDevice()
            publishQuick()
        }
    }

    sealed interface BioCipher {
        class Ready(val cipher: Cipher) : BioCipher
        data object Changed : BioCipher
        data object Unavailable : BioCipher
    }

    /** The Cipher to hand to the fingerprint dialog for unlocking. */
    fun bioUnlockCipher(): BioCipher {
        val bio = device.optJSONObject("bio") ?: return BioCipher.Unavailable
        if (_quick.value.masterDue) return BioCipher.Unavailable
        return try {
            SpaceHardwareKeys.bioDecryptCipher(SpaceCrypto.unb64(bio.getString("iv")))?.let { BioCipher.Ready(it) } ?: run {
                clearBio(QuickNotice.FINGERPRINT_CHANGED)
                BioCipher.Changed
            }
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            clearBio(QuickNotice.FINGERPRINT_CHANGED)
            BioCipher.Changed
        } catch (e: SpaceHardwareKeys.KeyTamperedException) {
            keysTampered()
            BioCipher.Changed
        } catch (e: Exception) {
            Log.w(TAG, "Fingerprint key unusable: ${e.javaClass.simpleName}")
            clearBio(QuickNotice.FINGERPRINT_CHANGED)
            BioCipher.Changed
        }
    }

    /** After the fingerprint dialog succeeded with [bioUnlockCipher]'s Cipher. */
    suspend fun finishBioUnlock(cipher: Cipher): Boolean = withContext(Dispatchers.Default) {
        val bio = device.optJSONObject("bio") ?: return@withContext false
        val key = try {
            cipher.doFinal(SpaceCrypto.unb64(bio.getString("ct")))
        } catch (e: Exception) {
            return@withContext false
        }
        openWith(key)
    }

    private fun clearBio(notice: QuickNotice?) {
        SpaceHardwareKeys.deleteBioKey()
        synchronized(lock) {
            device.remove("bio")
            if (notice != null) device.put("notice", notice.name)
            saveDevice()
            publishQuick()
        }
    }

    fun dismissNotice() {
        synchronized(lock) {
            device.remove("notice")
            device.remove("noticeCount")
            saveDevice()
            publishQuick()
        }
    }

    private fun onUnlocked() {
        _status.value = SpaceStatus.UNLOCKED
        registerScreenOff()
        if (!pageOpen) scheduleAutoLock()
    }

    /** Wipes the key and everything decrypted from memory (changes not saved yet are written first). */
    fun lock() {
        synchronized(lock) {
            if (_status.value != SpaceStatus.UNLOCKED) return
            if (saveJob?.isActive == true) {
                saveJob?.cancel()
                runCatching { persistNow() }
            }
            spaceKey?.fill(0)
            spaceKey = null
            _items.value = emptyList()
            _settings.value = SpaceSettings()
            _needsNewPassword.value = false
            autoPromptArmed = false
            _status.value = SpaceStatus.LOCKED
            autoLockJob?.cancel()
            unregisterScreenOff()
            publishQuick()
        }
    }

    // ---------------- auto-lock ----------------

    private val _pageShows = MutableStateFlow(0)
    /** Counts the page coming on screen (opened, or the app back in front): the lock screen offers the fingerprint then. */
    val pageShows: StateFlow<Int> = _pageShows.asStateFlow()
    @Volatile private var autoPromptArmed = false
    @Volatile private var lastAutoPromptAt = 0L

    fun onPageShown() {
        pageOpen = true
        autoLockJob?.cancel()
        if (_status.value == SpaceStatus.LOCKED) autoPromptArmed = true
        publishQuick()
        _pageShows.value++
    }

    /**
     * True once per time the page came on screen locked: the fingerprint dialog opens by itself then, but not right
     * after the user tapped "Lock now".
     */
    fun consumeAutoPrompt(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!autoPromptArmed || now - lastAutoPromptAt < 3000) return false
        autoPromptArmed = false
        lastAutoPromptAt = now
        return true
    }

    fun onPageHidden() {
        pageOpen = false
        scheduleAutoLock()
    }

    @Volatile private var externalPickUntil = 0L

    /**
     * The space opens Android's file picker (saving a backup or the recovery key, restoring a backup): leaving for it
     * doesn't lock at once, for at most 3 minutes.
     */
    fun beginExternalPick() {
        externalPickUntil = android.os.SystemClock.elapsedRealtime() + 3 * 60_000L
    }

    fun endExternalPick() {
        externalPickUntil = 0L
    }

    private fun scheduleAutoLock() {
        autoLockJob?.cancel()
        if (_status.value != SpaceStatus.UNLOCKED) return
        val pickLeft = externalPickUntil - android.os.SystemClock.elapsedRealtime()
        if (pickLeft > 0) {
            autoLockJob = scope.launch {
                delay(maxOf(pickLeft, _settings.value.autoLockSeconds * 1000L))
                lock()
            }
            return
        }
        val seconds = _settings.value.autoLockSeconds
        if (seconds <= 0 || _needsNewPassword.value) {
            lock()
            return
        }
        autoLockJob = scope.launch {
            delay(seconds * 1000L)
            lock()
        }
    }

    private fun registerScreenOff() {
        if (screenOffReceiver != null) return
        val ctx = appContext ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = lock()
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenOffReceiver = receiver
        }
    }

    private fun unregisterScreenOff() {
        val r = screenOffReceiver ?: return
        screenOffReceiver = null
        runCatching { appContext?.unregisterReceiver(r) }
    }

    // ---------------- items ----------------

    private fun requireUnlocked(): ByteArray = spaceKey ?: throw IllegalStateException("Private Space is locked")

    /** Adds or replaces [item] (by id); a changed secret field keeps its old value in the item's history. */
    fun save(item: SpaceItem) {
        synchronized(lock) {
            requireUnlocked()
            val now = System.currentTimeMillis()
            val old = _items.value.firstOrNull { it.id == item.id }
            var history = item.history
            if (old != null) {
                old.fields.forEach { of ->
                    if (!of.kind.secret || of.kind == SpaceFieldKind.TOTP || of.value.isBlank()) return@forEach
                    val nf = item.fields.firstOrNull { it.id == of.id }
                    if (nf == null || nf.value != of.value) {
                        history = listOf(SpaceHistoryEntry(of.label.ifBlank { of.key.orEmpty() }, of.value, now)) + history
                    }
                }
            }
            val saved = item.copy(
                title = item.title.trim(),
                fields = item.fields.map { it.copy(label = it.label.trim()) },
                tags = item.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                history = history.take(SpaceItem.HISTORY_MAX),
                updatedAt = now
            )
            _items.value = if (old == null) _items.value + saved else _items.value.map { if (it.id == item.id) saved else it }
            scheduleSave()
        }
    }

    fun get(id: String): SpaceItem? = _items.value.firstOrNull { it.id == id }

    private fun edit(id: String, transform: (SpaceItem) -> SpaceItem) {
        synchronized(lock) {
            requireUnlocked()
            _items.value = _items.value.map { if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it }
            scheduleSave()
        }
    }

    fun toggleFavorite(id: String) = edit(id) { it.copy(favorite = !it.favorite) }
    fun moveToTrash(id: String) = edit(id) { it.copy(deletedAt = System.currentTimeMillis()) }
    fun restoreFromTrash(id: String) = edit(id) { it.copy(deletedAt = null) }

    fun deleteForever(id: String) {
        synchronized(lock) {
            requireUnlocked()
            _items.value = _items.value.filterNot { it.id == id }
            scheduleSave()
        }
    }

    fun emptyTrash() {
        synchronized(lock) {
            requireUnlocked()
            _items.value = _items.value.filterNot { it.inTrash }
            scheduleSave()
        }
    }

    fun updateSettings(transform: (SpaceSettings) -> SpaceSettings) {
        synchronized(lock) {
            requireUnlocked()
            _settings.value = transform(_settings.value)
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            synchronized(lock) { runCatching { persistNow() }.onFailure { Log.e(TAG, "Save failed", it) } }
        }
    }

    /** Encrypts the items and settings with the space key and writes the space file. Call under [lock]. */
    private fun persistNow() {
        val key = spaceKey ?: return
        val h = header ?: return
        val id = h.getString("id")
        val payload = SpacePayload(_items.value, _settings.value).toJson().toString().toByteArray(Charsets.UTF_8)
        val file = JSONObject(h.toString()).put("kind", "space").put("data", SpaceCrypto.seal(key, payload, aad(id, "data")))
        payload.fill(0)
        writeAtomically(spaceFile(), file.toString(), keepPrevious = true)
    }

    private fun saveDevice() {
        runCatching { writeAtomically(deviceFile(), device.toString(), keepPrevious = false) }
            .onFailure { Log.e(TAG, "Saving device.json failed", it) }
    }

    private fun publishQuick() {
        val last = device.optLong("lastMaster", 0)
        val every = device.optInt("masterEvery", MASTER_EVERY_DEFAULT)
        val due = System.currentTimeMillis() - last > every * 24L * 3600 * 1000
        val pin = device.optJSONObject("pin")
        val recorded = pin?.optInt("tries", SpaceHardwareKeys.PIN_TRIES) ?: 0
        // tries left as the security chip counts them (Android 12+), so tries made past AntiGem's PIN pad show too;
        // a count above what a fresh key has means this phone doesn't report it, then the app's own count is used
        val chip = pin?.optString("alias")?.takeIf { it.isNotBlank() }
            ?.let { SpaceHardwareKeys.pinUsesLeft(it) }
            ?.takeIf { it in 0..SpaceHardwareKeys.PIN_TRIES }
        _quick.value = QuickUnlockState(
            bioEnabled = device.optJSONObject("bio") != null,
            pinEnabled = pin != null && SpaceHardwareKeys.pinSupported,
            pinLength = pin?.optInt("len") ?: 0,
            pinTriesLeft = chip ?: recorded,
            pinOutsideTries = if (pin == null) 0 else pin.optInt("outside") + if (chip != null && chip < recorded) recorded - chip else 0,
            masterDue = due,
            masterEveryDays = every,
            notice = device.optString("notice").takeIf { it.isNotBlank() }?.let { n -> QuickNotice.entries.firstOrNull { it.name == n } },
            noticeCount = device.optInt("noticeCount")
        )
    }

    // ---------------- security settings ----------------

    suspend fun verifyPassword(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        val h = header ?: return@withContext false
        val key = keyFromPassword(h, password) ?: return@withContext false
        key.fill(0)
        true
    }

    private fun rewrapPassword(newPassword: CharArray) {
        val key = requireUnlocked()
        val h = header ?: return
        val kdf = SpaceCrypto.KdfParams.fresh()
        val kek = SpaceCrypto.passwordKey(newPassword, kdf)
        val box = SpaceCrypto.seal(kek, key, aad(h.getString("id"), "pw"))
        kek.fill(0)
        synchronized(lock) {
            h.put("kdf", kdf.toJson()).put("pw", box)
            persistNow()
            device.put("lastMaster", System.currentTimeMillis())
            saveDevice()
            publishQuick()
        }
    }

    suspend fun changePassword(current: CharArray, newPassword: CharArray): Boolean = withContext(Dispatchers.Default) {
        require(PasswordTools.masterPasswordOk(newPassword))
        if (!verifyPassword(current)) return@withContext false
        rewrapPassword(newPassword)
        true
    }

    suspend fun setNewPasswordAfterRecovery(newPassword: CharArray) = withContext(Dispatchers.Default) {
        require(PasswordTools.masterPasswordOk(newPassword))
        rewrapPassword(newPassword)
        _needsNewPassword.value = false
    }

    /** A new recovery key (the old one stops working): see [replaceKeys]. Null if [password] is wrong. */
    suspend fun newRecoveryKey(password: CharArray): String? = replaceKeys(password, null)

    /**
     * After a leak: a new space key (everything is encrypted again with it), a new recovery key (returned, to show
     * once) and the master password wrapped anew ([newPassword], or the same one). Re-wrapping alone isn't enough: a copy
     * of the space file (or a backup) made before, plus the old password or recovery key, gives the old space key, and
     * that would open every later file too. Fingerprint and PIN held the old key, so they turn off. Null if [password]
     * is wrong.
     */
    suspend fun replaceKeys(password: CharArray, newPassword: CharArray?): String? = withContext(Dispatchers.Default) {
        if (newPassword != null) require(PasswordTools.masterPasswordOk(newPassword))
        if (!verifyPassword(password)) return@withContext null
        requireUnlocked()
        val newKey = SpaceCrypto.randomBytes(SpaceCrypto.KEY_BYTES)
        val kdf = SpaceCrypto.KdfParams.fresh()
        val kek = SpaceCrypto.passwordKey(newPassword ?: password, kdf)
        val recovery = SpaceCrypto.newRecoveryKey()
        try {
            synchronized(lock) {
                val h = header ?: return@withContext null
                val oldKey = spaceKey ?: return@withContext null
                val id = h.getString("id")
                val newHeader = JSONObject(h.toString())
                    .put("kdf", kdf.toJson())
                    .put("pw", SpaceCrypto.seal(kek, newKey, aad(id, "pw")))
                    .put("rk", recoveryBox(id, newKey, recovery))
                saveJob?.cancel()
                header = newHeader
                spaceKey = newKey
                try {
                    persistNow()
                } catch (e: Exception) {
                    // nothing written: the space stays as it was
                    header = h
                    spaceKey = oldKey
                    newKey.fill(0)
                    throw e
                }
                // the file before this one holds everything under the old key
                prevFile().delete()
                oldKey.fill(0)
                SpaceHardwareKeys.deleteAll()
                device.remove("pin")
                device.remove("bio")
                device.put("lastMaster", System.currentTimeMillis())
                saveDevice()
                publishQuick()
            }
        } finally {
            kek.fill(0)
        }
        recovery
    }

    private fun wrapWithPin(pin: CharArray) {
        val key = requireUnlocked()
        val h = header ?: return
        val old = device.optJSONObject("pin")?.optString("alias")
        val alias = SpaceHardwareKeys.newPinKey()
        val salt = SpaceCrypto.randomBytes(16)
        val answer = SpaceHardwareKeys.pinAnswer(alias, salt, pin) ?: throw IllegalStateException("New PIN key unusable")
        val wrapKey = SpaceCrypto.hkdf(answer, salt, "antigem.privatespace.pin.v1")
        answer.fill(0)
        val box = SpaceCrypto.seal(wrapKey, key, aad(h.getString("id"), "pin"))
        wrapKey.fill(0)
        synchronized(lock) {
            device.put("pin", JSONObject()
                .put("alias", alias)
                .put("salt", SpaceCrypto.b64(salt))
                .put("box", box)
                .put("tries", SpaceHardwareKeys.PIN_TRIES)
                .put("len", pin.size))
            if (device.optString("notice") == QuickNotice.PIN_LOCKED_OUT.name) device.remove("notice")
            saveDevice()
            publishQuick()
        }
        if (old != null && old != alias) SpaceHardwareKeys.deletePinKey(old)
    }

    suspend fun setPin(pin: CharArray): Boolean = withContext(Dispatchers.Default) {
        if (!PasswordTools.pinOk(pin) || !SpaceHardwareKeys.pinSupported) return@withContext false
        runCatching { wrapWithPin(pin) }.onFailure { Log.e(TAG, "Setting the PIN failed", it) }.isSuccess
    }

    fun removePin() {
        val alias = device.optJSONObject("pin")?.optString("alias")
        SpaceHardwareKeys.deletePinKey(alias)
        synchronized(lock) {
            device.remove("pin")
            saveDevice()
            publishQuick()
        }
    }

    /** A new fingerprint key and its Cipher, for the fingerprint dialog that turns fingerprint unlock on. */
    fun bioEnableCipher(): Cipher {
        requireUnlocked()
        return SpaceHardwareKeys.bioEncryptCipher()
    }

    fun finishBioEnable(cipher: Cipher): Boolean {
        val key = spaceKey ?: return false
        return try {
            val ct = cipher.doFinal(key)
            synchronized(lock) {
                device.put("bio", JSONObject().put("iv", SpaceCrypto.b64(cipher.iv)).put("ct", SpaceCrypto.b64(ct)))
                if (device.optString("notice") == QuickNotice.FINGERPRINT_CHANGED.name) device.remove("notice")
                saveDevice()
                publishQuick()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Turning fingerprint on failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun disableBio() = clearBio(null)

    fun setMasterEveryDays(days: Int) {
        synchronized(lock) {
            device.put("masterEvery", MASTER_EVERY_CHOICES.firstOrNull { it == days } ?: MASTER_EVERY_DEFAULT)
            saveDevice()
            publishQuick()
        }
    }

    /** Deletes the space, its chip keys and this phone's unlock settings. Needs the master password. */
    suspend fun deleteSpace(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        if (!verifyPassword(password)) return@withContext false
        erase()
        true
    }

    /**
     * The lock screen's "start over" for a user who lost both the master password and the recovery key: everything is
     * deleted for good. No password needed: the files can be deleted anyway by whoever can reach them, but never read.
     */
    fun eraseForgotten() = erase()

    private fun erase() {
        synchronized(lock) {
            lock()
            SpaceHardwareKeys.deleteAll()
            dir?.listFiles()?.forEach { it.delete() }
            header = null
            device = JSONObject()
            _status.value = SpaceStatus.NOT_SET_UP
            publishQuick()
        }
    }

    // ---------------- backups ----------------

    fun suggestedBackupName(): String =
        "AntiGem-Private-Space-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".$BACKUP_EXTENSION"

    /**
     * Writes an encrypted backup to [out]: the space as it is now, protected by the master password and the recovery
     * key only (never the PIN or fingerprint, those stay on this phone). Needs the master password, so whoever makes a
     * backup knows what opens it.
     */
    suspend fun exportBackup(password: CharArray, out: OutputStream): Boolean = withContext(Dispatchers.Default) {
        if (!verifyPassword(password)) return@withContext false
        val text = synchronized(lock) {
            val key = requireUnlocked()
            val h = header ?: return@withContext false
            val id = h.getString("id")
            val payload = SpacePayload(_items.value, _settings.value).toJson().toString().toByteArray(Charsets.UTF_8)
            val json = JSONObject(h.toString())
                .put("kind", "backup")
                .put("exported", System.currentTimeMillis())
                .put("data", SpaceCrypto.seal(key, payload, aad(id, "data")))
            payload.fill(0)
            json.toString()
        }
        withContext(Dispatchers.IO) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        true
    }

    /** Reads a backup file (nothing is decrypted yet); null if it isn't one. */
    suspend fun readBackup(input: InputStream): SpaceBackupFile? = withContext(Dispatchers.IO) {
        try {
            val bytes = input.readBytesLimited(MAX_BACKUP_BYTES) ?: return@withContext null
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.optString("format") != FORMAT || json.optInt("v") > VERSION) return@withContext null
            if (json.optJSONObject("data") == null || json.optJSONObject("pw") == null || json.optJSONObject("kdf") == null) return@withContext null
            SpaceBackupFile(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun InputStream.readBytesLimited(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Opens [file] with its master password or (when [isRecoveryKey]) its recovery key; null if it doesn't open. */
    suspend fun openBackup(file: SpaceBackupFile, secret: CharArray, isRecoveryKey: Boolean): OpenedBackup? = withContext(Dispatchers.Default) {
        val json = file.json
        val key = try {
            if (isRecoveryKey) keyFromRecovery(json, String(secret)) else keyFromPassword(json, secret)
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        val payload = try {
            SpacePayload.fromJson(JSONObject(String(SpaceCrypto.open(key, json.getJSONObject("data"), aad(json.getString("id"), "data")), Charsets.UTF_8)))
        } catch (e: Exception) {
            key.fill(0)
            return@withContext null
        }
        OpenedBackup(file, payload, key)
    }

    /** Adds the backup's items to this space, item by item (see [mergeSpaceItems]). */
    fun mergeBackup(backup: OpenedBackup): MergeResult {
        synchronized(lock) {
            requireUnlocked()
            val (merged, result) = mergeSpaceItems(_items.value, backup.payload.items)
            _items.value = merged
            scheduleSave()
            backup.key.fill(0)
            return result
        }
    }

    /** This space's contents become the backup's (this space keeps its own master password and recovery key). */
    fun replaceWithBackup(backup: OpenedBackup) {
        synchronized(lock) {
            requireUnlocked()
            _items.value = backup.payload.items
            scheduleSave()
            backup.key.fill(0)
        }
    }

    /**
     * No space on this phone yet: the backup becomes it, as it was (same master password and recovery key), and opens.
     */
    fun adoptBackup(backup: OpenedBackup, openedWithRecoveryKey: Boolean) {
        synchronized(lock) {
            val json = JSONObject(backup.file.json.toString()).put("kind", "space")
            json.remove("exported")
            writeAtomically(spaceFile(), json.toString(), keepPrevious = false)
            header = json.apply { remove("data") }
            device = JSONObject().put("spaceId", json.getString("id")).put("lastMaster", System.currentTimeMillis()).put("masterEvery", MASTER_EVERY_DEFAULT)
            saveDevice()
            spaceKey = backup.key
            _items.value = backup.payload.items
            _settings.value = backup.payload.settings
            if (openedWithRecoveryKey) _needsNewPassword.value = true
            onUnlocked()
            publishQuick()
        }
    }

    fun discardBackup(backup: OpenedBackup) {
        backup.key.fill(0)
    }

    // ---------------- clipboard ----------------

    private const val CLIP_LABEL = "AntiGem Private Space"
    /** In the copied clip's extras: a Private Space secret (see [isPrivateClip]). */
    private const val CLIP_MARK = "ai.antigem.privatespace.SECRET"
    // ClipDescription.EXTRA_IS_SENSITIVE (API 33); older Android versions ignore it
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val CLIP_CLEAR_WORK = "private_space_clipboard_clear"
    private var clipClear: Runnable? = null

    /**
     * Whether the clipboard holds a secret copied from Private Space. AntiGem's own ways for other code to read the
     * clipboard (the terminal's clipboard command, mini apps) return nothing for it: while AntiGem is in front, an
     * agent could otherwise read every password the user copies.
     */
    fun isPrivateClip(description: ClipDescription?): Boolean =
        description != null && (description.label?.toString() == CLIP_LABEL || description.extras?.getBoolean(CLIP_MARK) == true)

    /**
     * Copies a secret: marked sensitive (Android 13+ hides it in the copy preview and keyboards skip their history),
     * marked as Private Space's ([isPrivateClip]) and wiped from the clipboard after the chosen time, also when the app
     * is closed before then (WorkManager).
     */
    fun copySecret(context: Context, value: String) {
        val app = context.applicationContext
        val clip = ClipData.newPlainText(CLIP_LABEL, value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
            putBoolean(CLIP_MARK, true)
        }
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
        val seconds = _settings.value.clipboardSeconds
        clipClear?.let { main.removeCallbacks(it) }
        val r = Runnable {
            clearClipboardNow(app)
            runCatching { WorkManager.getInstance(app).cancelUniqueWork(CLIP_CLEAR_WORK) }
        }
        clipClear = r
        main.postDelayed(r, seconds * 1000L)
        runCatching {
            WorkManager.getInstance(app).enqueueUniqueWork(
                CLIP_CLEAR_WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SpaceClipboardClearWorker>().setInitialDelay(seconds + 2L, TimeUnit.SECONDS).build()
            )
        }.onFailure { Log.w(TAG, "Clipboard clear backup not scheduled: ${it.javaClass.simpleName}") }
    }

    /** Wipes the clipboard if it holds a Private Space secret, or if it can't be looked at (Android 10+, app not in front). */
    internal fun clearClipboardNow(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val description = runCatching { cm.primaryClipDescription }.getOrNull()
        if (description == null || isPrivateClip(description)) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
                else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}

/** Clears a copied Private Space secret when the app was closed or killed before its own timer ran out. */
class SpaceClipboardClearWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        Handler(Looper.getMainLooper()).post { PrivateSpace.clearClipboardNow(app) }
        return Result.success()
    }
}
