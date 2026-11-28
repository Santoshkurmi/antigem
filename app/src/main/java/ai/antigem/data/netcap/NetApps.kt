package ai.antigem.data.netcap

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Installed apps for the capture picker and the list's app icons (icons are loaded off the main thread and cached). */
object NetApps {
    @Immutable
    data class App(val pkg: String, val label: String, val system: Boolean)

    private val icons = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val missing = HashSet<String>()
    private val labels = HashMap<String, String>()
    private val listMutex = Mutex()
    private var cache: List<App>? = null

    /** Every installed app with a label (system apps flagged), sorted by label. */
    suspend fun all(context: Context, refresh: Boolean = false): List<App> = listMutex.withLock {
        cache?.takeIf { !refresh }?.let { return it }
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val own = context.packageName
            @Suppress("DEPRECATION")
            val list = pm.getInstalledApplications(PackageManager.GET_META_DATA).asSequence()
                .filter { it.packageName != own }
                .map { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                    val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    App(info.packageName, label, system)
                }
                .sortedBy { it.label.lowercase() }
                .toList()
            synchronized(labels) { list.forEach { labels[it.pkg] = it.label } }
            cache = list
            list
        }
    }

    /** The app's name, when known ([all] or [icon] loaded it), else the package. */
    fun label(context: Context, pkg: String): String {
        if (pkg.isEmpty()) return ""
        synchronized(labels) { labels[pkg] }?.let { return it }
        val l = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        synchronized(labels) { labels[pkg] = l }
        return l
    }

    fun cachedIcon(pkg: String): ImageBitmap? = icons.get(pkg)

    /** The app's icon at [sizePx] (null for unknown apps, such as uids without a package). */
    suspend fun icon(context: Context, pkg: String, sizePx: Int = 96): ImageBitmap? {
        if (pkg.isEmpty()) return null
        icons.get(pkg)?.let { return it }
        synchronized(missing) { if (pkg in missing) return null }
        return withContext(Dispatchers.IO) {
            val bmp = runCatching {
                context.packageManager.getApplicationIcon(pkg).toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
            if (bmp == null) synchronized(missing) { missing.add(pkg) } else icons.put(pkg, bmp)
            bmp
        }
    }
}
