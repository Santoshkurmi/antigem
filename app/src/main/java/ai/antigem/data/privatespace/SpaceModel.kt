package ai.antigem.data.privatespace

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** What an item is: decides its icon, its starting fields and what the list shows under its title. */
enum class SpaceItemType(val id: String) {
    LOGIN("login"),
    API_KEY("api_key"),
    NOTE("note"),
    SSH_KEY("ssh_key"),
    SERVER("server"),
    CARD("card"),
    WIFI("wifi");

    companion object {
        fun from(id: String?): SpaceItemType = entries.firstOrNull { it.id == id } ?: NOTE
    }
}

/** How a field's value is shown and typed. Secret kinds are hidden (dots) until the user reveals them. */
enum class SpaceFieldKind(val id: String, val secret: Boolean = false, val multiline: Boolean = false) {
    TEXT("text"),
    SECRET("secret", secret = true),
    URL("url"),
    MULTILINE("multiline", multiline = true),
    SECRET_MULTILINE("secret_multiline", secret = true, multiline = true),
    /** A 2FA secret (base32, or an otpauth:// link): shown as the current 6-digit code. */
    TOTP("totp", secret = true);

    companion object {
        fun from(id: String?): SpaceFieldKind = entries.firstOrNull { it.id == id } ?: TEXT
    }
}

/**
 * One field. [key] names a built-in field (its label is shown in the app's language); custom fields have no key and
 * show [label] as the user typed it.
 */
data class SpaceField(
    val id: String = newSpaceId(),
    val key: String? = null,
    val label: String = "",
    val value: String = "",
    val kind: SpaceFieldKind = SpaceFieldKind.TEXT
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("key", key ?: JSONObject.NULL)
        .put("label", label)
        .put("value", value)
        .put("kind", kind.id)

    companion object {
        fun fromJson(o: JSONObject) = SpaceField(
            id = o.optString("id").ifBlank { newSpaceId() },
            key = o.optString("key").takeIf { !o.isNull("key") && it.isNotBlank() },
            label = o.optString("label"),
            value = o.optString("value"),
            kind = SpaceFieldKind.from(o.optString("kind"))
        )
    }
}

/** An older value of a secret field, kept when it changes (newest first, at most [SpaceItem.HISTORY_MAX]). */
data class SpaceHistoryEntry(val fieldLabel: String, val value: String, val changedAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("label", fieldLabel).put("value", value).put("at", changedAt)

    companion object {
        fun fromJson(o: JSONObject) = SpaceHistoryEntry(o.optString("label"), o.optString("value"), o.optLong("at"))
    }
}

/**
 * One thing in the Private Space. [id] is a random UUID made when it is created, the same in every backup, so a
 * backup restored into another space merges item by item ([mergeSpaceItems]). [deletedAt] set: in the trash.
 */
data class SpaceItem(
    val id: String = newSpaceId(),
    val type: SpaceItemType = SpaceItemType.LOGIN,
    val title: String = "",
    val fields: List<SpaceField> = emptyList(),
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val history: List<SpaceHistoryEntry> = emptyList()
) {
    val inTrash: Boolean get() = deletedAt != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.id)
        .put("title", title)
        .put("fields", JSONArray().apply { fields.forEach { put(it.toJson()) } })
        .put("notes", notes)
        .put("tags", JSONArray().apply { tags.forEach { put(it) } })
        .put("fav", favorite)
        .put("created", createdAt)
        .put("updated", updatedAt)
        .put("deleted", deletedAt ?: JSONObject.NULL)
        .put("history", JSONArray().apply { history.forEach { put(it.toJson()) } })

    companion object {
        const val HISTORY_MAX = 10

        fun fromJson(o: JSONObject): SpaceItem {
            val fields = o.optJSONArray("fields") ?: JSONArray()
            val tags = o.optJSONArray("tags") ?: JSONArray()
            val history = o.optJSONArray("history") ?: JSONArray()
            val created = o.optLong("created", System.currentTimeMillis())
            return SpaceItem(
                id = o.optString("id").ifBlank { newSpaceId() },
                type = SpaceItemType.from(o.optString("type")),
                title = o.optString("title"),
                fields = (0 until fields.length()).mapNotNull { fields.optJSONObject(it)?.let(SpaceField::fromJson) },
                notes = o.optString("notes"),
                tags = (0 until tags.length()).map { tags.optString(it) }.filter { it.isNotBlank() },
                favorite = o.optBoolean("fav"),
                createdAt = created,
                updatedAt = o.optLong("updated", created),
                deletedAt = if (o.isNull("deleted") || !o.has("deleted")) null else o.optLong("deleted"),
                history = (0 until history.length()).mapNotNull { history.optJSONObject(it)?.let(SpaceHistoryEntry::fromJson) }
            )
        }

        /** A new item of [type] with its built-in fields, empty. */
        fun blank(type: SpaceItemType): SpaceItem = SpaceItem(type = type, fields = templateFields(type))

        private fun f(key: String, kind: SpaceFieldKind = SpaceFieldKind.TEXT) = SpaceField(key = key, kind = kind)

        fun templateFields(type: SpaceItemType): List<SpaceField> = when (type) {
            SpaceItemType.LOGIN -> listOf(f("username"), f("password", SpaceFieldKind.SECRET), f("website", SpaceFieldKind.URL), f("totp", SpaceFieldKind.TOTP))
            SpaceItemType.API_KEY -> listOf(f("service"), f("key", SpaceFieldKind.SECRET), f("envVar"), f("expires"))
            SpaceItemType.NOTE -> emptyList()
            SpaceItemType.SSH_KEY -> listOf(f("host"), f("username"), f("privateKey", SpaceFieldKind.SECRET_MULTILINE), f("publicKey", SpaceFieldKind.MULTILINE), f("passphrase", SpaceFieldKind.SECRET))
            SpaceItemType.SERVER -> listOf(f("host"), f("port"), f("username"), f("password", SpaceFieldKind.SECRET), f("database"))
            SpaceItemType.CARD -> listOf(f("cardholder"), f("cardNumber", SpaceFieldKind.SECRET), f("expiry"), f("cvv", SpaceFieldKind.SECRET), f("cardPin", SpaceFieldKind.SECRET))
            SpaceItemType.WIFI -> listOf(f("network"), f("password", SpaceFieldKind.SECRET), f("security"))
        }
    }
}

/** Settings kept inside the encrypted space (they travel with backups and can't be changed while it is locked). */
data class SpaceSettings(
    /** Lock this long after leaving the page or the app; 0 = at once. */
    val autoLockSeconds: Int = 60,
    /** A copied secret is wiped from the clipboard after this long. */
    val clipboardSeconds: Int = 30
) {
    fun toJson(): JSONObject = JSONObject().put("autoLock", autoLockSeconds).put("clipboard", clipboardSeconds)

    companion object {
        val AUTO_LOCK_CHOICES = listOf(0, 30, 60, 300, 900, 3600)
        val CLIPBOARD_CHOICES = listOf(15, 30, 60, 120)

        fun fromJson(o: JSONObject?): SpaceSettings {
            if (o == null) return SpaceSettings()
            return SpaceSettings(
                autoLockSeconds = o.optInt("autoLock", 60).let { v -> AUTO_LOCK_CHOICES.firstOrNull { it == v } ?: 60 },
                clipboardSeconds = o.optInt("clipboard", 30).let { v -> CLIPBOARD_CHOICES.firstOrNull { it == v } ?: 30 }
            )
        }
    }
}

/** The decrypted contents of a space or a backup. */
internal data class SpacePayload(val items: List<SpaceItem>, val settings: SpaceSettings) {
    fun toJson(): JSONObject = JSONObject()
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("settings", settings.toJson())

    companion object {
        fun fromJson(o: JSONObject): SpacePayload {
            val arr = o.optJSONArray("items") ?: JSONArray()
            return SpacePayload(
                items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(SpaceItem::fromJson) }.distinctBy { it.id },
                settings = SpaceSettings.fromJson(o.optJSONObject("settings"))
            )
        }
    }
}

data class MergeResult(val added: Int, val updated: Int, val unchanged: Int)

/**
 * [incoming] (a restored backup) merged into [current] by item id: new ids are added, an id both have keeps the
 * newer edit (moving to or out of the trash counts as an edit), the rest stays.
 */
internal fun mergeSpaceItems(current: List<SpaceItem>, incoming: List<SpaceItem>): Pair<List<SpaceItem>, MergeResult> {
    val byId = LinkedHashMap<String, SpaceItem>()
    current.forEach { byId[it.id] = it }
    var added = 0
    var updated = 0
    var unchanged = 0
    incoming.forEach { item ->
        val mine = byId[item.id]
        when {
            mine == null -> { byId[item.id] = item; added++ }
            item.updatedAt > mine.updatedAt -> { byId[item.id] = item; updated++ }
            else -> unchanged++
        }
    }
    return byId.values.toList() to MergeResult(added, updated, unchanged)
}

fun newSpaceId(): String = UUID.randomUUID().toString()
