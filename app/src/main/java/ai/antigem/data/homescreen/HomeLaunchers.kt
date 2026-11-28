package ai.antigem.data.homescreen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Which apps may be handed a shortcut's signed link ([HomeLinks]): the phone's default home-screen app, and apps that
 * came with the phone (an OEM launcher, or its separate shortcut receiver). Any installed app can declare itself a home
 * app, so "has a home screen" alone doesn't count.
 *
 * Used where the link would otherwise leave Android's control: the launcher's "AntiGem script" picker (its result) and
 * pinning on Android 7 (a broadcast). The app sees every package (QUERY_ALL_PACKAGES), on every Android version.
 */
object HomeLaunchers {
    /** The default home app's package, or null when none is chosen yet (Android would ask). */
    fun defaultHome(context: Context): String? = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it != "android" }

    /** Came with the phone (also when updated since). */
    fun isPreinstalled(context: Context, pkg: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(pkg, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    /** [pkg] may receive a shortcut's link. */
    fun isTrusted(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == context.packageName) return true
        return pkg == defaultHome(context) || isPreinstalled(context, pkg)
    }
}
