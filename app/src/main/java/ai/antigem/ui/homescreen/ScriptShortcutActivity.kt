package ai.antigem.ui.homescreen

import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import ai.antigem.data.homescreen.HomeLaunchers
import ai.antigem.data.homescreen.HomeScripts
import ai.antigem.data.homescreen.HomeShortcuts
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.GeminiTheme
import ai.antigem.theme.isSystemInDarkThemeRobust
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * "AntiGem script" in the launcher's list of widgets and shortcuts, like Termux:Widget's shortcut: pick a script of
 * ~/.shortcuts and the launcher places its icon (with ~/.shortcuts/icons/<name>.png when there is one). The launcher
 * starts this screen (ACTION_CREATE_SHORTCUT) and gets the shortcut back as the result; it works on launchers that
 * can't take a shortcut the app asks for, Android 7 ones too.
 */
class ScriptShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (intent?.action != Intent.ACTION_CREATE_SHORTCUT) {
            finish()
            return
        }
        enableEdgeToEdge()
        val prefs = AuthPreferences(applicationContext)
        setContent {
            val mode by prefs.themeMode.collectAsState(initial = remember { prefs.getThemeModeSync() })
            val systemDark = isSystemInDarkThemeRobust()
            GeminiTheme(darkTheme = when (mode) { "DARK" -> true; "LIGHT" -> false; else -> systemDark }) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Picker()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Picker() {
        val ctx = this
        val scope = rememberCoroutineScope()
        val tick by HomeScripts.changes.collectAsState()
        val listing = remember(tick) { HomeScripts.listing(ctx) }
        var reading by remember { mutableStateOf(false) }
        // the Ubuntu chroot's list is read through root
        LaunchedEffect(Unit) {
            if (listing.chroot) {
                reading = true
                HomeScripts.refresh(ctx)
                reading = false
            }
        }
        val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.homescreen.scriptShortcut.addAScriptShortcut, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(HomeScripts.whereLabel(ctx), fontSize = 13.sp, color = subtle)
                }
                IconButton(onClick = {
                    scope.launch {
                        reading = true
                        HomeScripts.refresh(ctx)
                        reading = false
                    }
                }) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
            }
            Spacer(Modifier.height(12.dp))
            when {
                reading && listing.scripts.isEmpty() -> Text(L.common.loading, color = subtle)
                listing.error != null -> Text(
                    L.homescreen.homeScripts.couldNotReadTheUbuntuChroot(listing.error),
                    color = MaterialTheme.colorScheme.error, fontSize = 14.sp
                )
                listing.scripts.isEmpty() -> Text(L.homescreen.scriptShortcut.noScriptsInShortcutsYetAdd, color = subtle, fontSize = 14.sp)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listing.scripts, key = { it.path }) { s ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        modifier = Modifier.fillMaxWidth().clickable { pick(s) }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (s.background) Icons.Outlined.Schedule else Icons.Outlined.Terminal, null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (s.background) L.homescreen.scriptsWidget.runsInTheBackground else L.homescreen.scriptsWidget.opensATerminalTab,
                                    fontSize = 12.sp, color = subtle
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) { Text(L.common.cancel) }
        }
    }

    /**
     * The launcher gets [s]'s shortcut. Any app can open this screen for a result, so the result only carries the
     * signed link (androidx's old-style extras, which launchers of Android 7 and some later ones read) when the caller
     * is the home screen app ([HomeLaunchers]). Others get Android's own pin request on Android 8+ (Android removes the
     * link from it, and only the default launcher can accept it) and nothing on Android 7.
     */
    private fun pick(s: HomeScripts.Script) {
        val caller = callingPackage
        lifecycleScope.launch {
            val ctx = this@ScriptShortcutActivity
            val info = runCatching { HomeShortcuts.scriptInfo(ctx, s) }.getOrNull()
            val result: Intent? = when {
                info == null -> null
                HomeLaunchers.isTrusted(ctx, caller) -> ShortcutManagerCompat.createShortcutResultIntent(ctx, info)
                Build.VERSION.SDK_INT >= 26 -> runCatching {
                    getSystemService(ShortcutManager::class.java)?.createShortcutResultIntent(info.toShortcutInfo())
                }.getOrNull()
                else -> null
            }
            if (result != null) setResult(RESULT_OK, result)
            else Toast.makeText(ctx, L.homescreen.scriptShortcut.onlyYourHomeScreenAppCan, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
