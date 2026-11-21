package ai.antigem.data.local.chroot

import org.json.JSONArray
import org.json.JSONObject

/** Where the chroot's mounts live (see chrootctl.sh). */
enum class ChrootNsMode(val conf: String, val label: String) {
    PRIVATE("private", "Private (recommended)"),
    GLOBAL("global", "Global (whole phone)");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.conf == value } ?: PRIVATE
    }
}

enum class ChrootDnsMode(val conf: String) {
    AUTO("auto"), CUSTOM("custom");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.conf == value } ?: AUTO
    }
}

/** What happens to the chroot when AntiGem is exited (notification or drawer Exit). */
enum class ChrootExitBehavior(val label: String, val description: String) {
    KEEP_RUNNING("Keep running", "The bridge and the agents keep working while AntiGem is closed; AntiGem reconnects when opened."),
    STOP_BRIDGE("Stop the bridge and agents", "The chroot's mounts stay, so the next start is quick."),
    STOP_ALL("Stop everything", "Every process of the chroot is stopped and its folders are unmounted.")
}

/** A host folder shown inside the chroot. */
data class ChrootBind(val source: String, val target: String, val readOnly: Boolean) {
    companion object {
        /** AntiGem's Termux home (files/home: projects, configs, everything) inside the chroot at ~/antigem_home. */
        fun termuxHome(context: android.content.Context, user: String) = ChrootBind(
            ai.antigem.data.local.LocalEnvironmentManager.getHomeDir(context).absolutePath, "/home/$user/antigem_home", false
        )
    }
}

/** The chroot's own settings: .antigem/chroot.conf inside the rootfs (read by chrootctl.sh at every start). */
data class ChrootConfig(
    val nsMode: ChrootNsMode = ChrootNsMode.PRIVATE,
    val mountSdcard: Boolean = true,
    val sdcardReadOnly: Boolean = false,
    /** /storage inside the chroot: the phone's storage and every USB drive / SD card, also those plugged in later. */
    val mountStorage: Boolean = true,
    val shmMb: Int = 256,
    val dnsMode: ChrootDnsMode = ChrootDnsMode.AUTO,
    val customDns: List<String> = emptyList(),
    val binds: List<ChrootBind> = emptyList()
) {
    fun toConf(): String = buildString {
        appendLine("NS_MODE=${nsMode.conf}")
        appendLine("SDCARD=${if (mountSdcard) 1 else 0}")
        appendLine("SDCARD_RO=${if (sdcardReadOnly) 1 else 0}")
        appendLine("STORAGE=${if (mountStorage) 1 else 0}")
        appendLine("SHM_MB=${shmMb.coerceIn(16, 8192)}")
        appendLine("DNS_MODE=${dnsMode.conf}")
        appendLine("DNS_CUSTOM=${customDns.filter { ChrootManager.isDnsServer(it) }.joinToString(",")}")
        // sorted by the path inside: a folder shared inside another shared one is mounted after it
        appendLine("BINDS=${binds.filter { ChrootManager.isBindSource(it.source) && ChrootManager.bindTargetProblem(it.target, null) == null }
            .sortedBy { it.target }
            .joinToString(";") { "${it.source.trimEnd('/')}|${it.target.trimEnd('/')}|${if (it.readOnly) "ro" else "rw"}" }}")
    }

    companion object {
        fun parse(values: Map<String, String>): ChrootConfig = ChrootConfig(
            nsMode = ChrootNsMode.of(values["NS_MODE"]),
            mountSdcard = values["SDCARD"] != "0",
            sdcardReadOnly = values["SDCARD_RO"] == "1",
            mountStorage = values["STORAGE"] != "0",
            shmMb = values["SHM_MB"]?.toIntOrNull() ?: 256,
            dnsMode = ChrootDnsMode.of(values["DNS_MODE"]),
            customDns = values["DNS_CUSTOM"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            binds = values["BINDS"].orEmpty().split(';').mapNotNull { entry ->
                val parts = entry.split('|')
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
                else ChrootBind(parts[0], parts[1], parts.getOrNull(2) == "ro")
            }
        )
    }
}

/** An installed rootfs AntiGem knows about. */
data class ChrootInstall(
    val path: String,
    val name: String,
    val os: String,
    val arch: String,
    val user: String,
    /** The normal user's uid inside the chroot: AntiGem's own uid; -1 when unknown (an install of the first version). */
    val uid: Int,
    val source: String,
    val createdAt: Long,
    /** No AntiGem manifest yet: installed by the first chroot version (or another tool) and not adopted. */
    val legacy: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path).put("name", name).put("os", os).put("arch", arch).put("user", user).put("uid", uid)
        .put("source", source).put("createdAt", createdAt).put("legacy", legacy)

    /** .antigem/manifest */
    fun toManifest(): String = buildString {
        appendLine("ANTIGEM_MANIFEST=1")
        appendLine("NAME=$name")
        appendLine("OS=${os.replace('\n', ' ')}")
        appendLine("ARCH=$arch")
        appendLine("USER=$user")
        appendLine("UID=$uid")
        appendLine("SOURCE=${source.replace('\n', ' ')}")
        appendLine("CREATED=$createdAt")
    }

    companion object {
        fun fromJson(o: JSONObject) = ChrootInstall(
            path = o.optString("path"), name = o.optString("name"), os = o.optString("os"), arch = o.optString("arch"),
            user = o.optString("user", "ubuntu"), uid = o.optInt("uid", -1), source = o.optString("source"),
            createdAt = o.optLong("createdAt"), legacy = o.optBoolean("legacy")
        )

        fun listToJson(list: List<ChrootInstall>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<ChrootInstall> = try {
            val a = JSONArray(text ?: "[]")
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.filter { it.path.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** A rootfs found on the phone by `chrootctl scan`. */
data class ChrootFound(
    val path: String,
    val os: String,
    val arch: String,
    val manifest: Map<String, String>,
    val legacyUser: String?,
    /** Processes run inside it. */
    val running: Boolean,
    /** Something is mounted in it (with nothing running: mounts an older launcher left behind). */
    val mounted: Boolean = running,
    val processes: Int = 0,
    /** It carries its own settings (.antigem/chroot.conf): an AntiGem install or a backup of one. */
    val hasConf: Boolean = false
) {
    val hasManifest: Boolean get() = manifest["ANTIGEM_MANIFEST"] == "1"

    fun toInstall(): ChrootInstall = ChrootInstall(
        path = path,
        name = manifest["NAME"] ?: path.substringAfterLast('/'),
        os = manifest["OS"]?.takeIf { it.isNotBlank() } ?: os,
        arch = manifest["ARCH"]?.takeIf { it.isNotBlank() } ?: arch,
        user = manifest["USER"]?.takeIf { it.isNotBlank() } ?: legacyUser ?: "ubuntu",
        uid = manifest["UID"]?.toIntOrNull() ?: -1,
        source = manifest["SOURCE"] ?: "found on this phone",
        createdAt = manifest["CREATED"]?.toLongOrNull() ?: 0L,
        legacy = !hasManifest
    )
}

/** What the root manager and the phone offer (`chrootctl probe`). */
data class ChrootProbe(
    val rootOk: Boolean,
    val rootManager: String?,
    val busybox: String?,
    val canPrivateNs: Boolean,
    val selinux: String?,
    val kernel: String?,
    val machine: String?,
    val error: String? = null
)

data class ChrootMount(val mountPoint: String, val fsType: String, val source: String, val options: String) {
    val readOnly: Boolean get() = options.split(',').contains("ro")
}

data class ChrootProcess(val pid: Int, val uid: Int, val command: String)

data class ChrootStatus(
    val running: Boolean,
    val nsMode: ChrootNsMode,
    val holderPid: Int?,
    val sinceEpochSec: Long?,
    val bridgePid: Int?,
    val processCount: Int,
    val mounts: List<ChrootMount>,
    val processes: List<ChrootProcess>,
    val config: ChrootConfig,
    val manifest: Map<String, String>,
    val error: String? = null,
    /** The install this status is of. */
    val path: String = ""
)

data class ChrootUsage(val totalKb: Long, val partsKb: Map<String, Long>)

/** A root task started from the chroot page (packages, repair, ...): its live log. */
data class ChrootTask(
    val title: String,
    val lines: List<String> = emptyList(),
    val running: Boolean = true,
    val exitCode: Int? = null
)
