package com.example.gemini.ui.miniapps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Key
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniApps
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.GeminiTheme
import com.example.gemini.theme.isSystemInDarkThemeRobust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Mini Apps drawer: every app in ~/mini-apps as an icon grid. Tap opens, long-press for App info. */
class MiniAppsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KEY = "miniapps_key"
        /** Debug builds: open "Get apps" with another catalog URL (testing a catalog through adb reverse). */
        const val EXTRA_STORE_CATALOG = "store_catalog"
        /** Opens Mini Apps with the developer key dialog ("backup": straight at the backup), e.g. from the developer MCP. */
        fun keyIntent(context: android.content.Context, step: String): android.content.Intent =
            android.content.Intent(context, MiniAppsActivity::class.java).putExtra(EXTRA_KEY, step)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    private val prefs by lazy { AuthPreferences(applicationContext) }
    /** The key dialog to open (from [keyIntent]). */
    internal var openKey by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var pickCallback: ((android.net.Uri?) -> Unit)? = null
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> pickCallback?.invoke(uri); pickCallback = null }
    internal fun pickFile(onPicked: (android.net.Uri?) -> Unit) { pickCallback = onPicked; picker.launch("*/*") }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_KEY)?.let { openKey = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_KEY)?.let { openKey = it }
        if (savedInstanceState == null && com.example.gemini.BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_STORE_CATALOG)?.let {
            startActivity(android.content.Intent(this, MiniAppStoreActivity::class.java).putExtra(MiniAppStoreActivity.EXTRA_CATALOG, it))
        }
        setContent {
            val initial = remember { prefs.getThemeModeSync() }
            val mode by prefs.themeMode.collectAsState(initial = initial)
            val systemDark = isSystemInDarkThemeRobust()
            GeminiTheme(darkTheme = when (mode) { "DARK" -> true; "LIGHT" -> false; else -> systemDark }) {
                MiniAppsScreen(this, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MiniAppsScreen(activity: MiniAppsActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<MiniApp>?>(null) }
    var info by remember { mutableStateOf<MiniApp?>(null) }
    val prefs = remember { context.getSharedPreferences("mini_apps", android.content.Context.MODE_PRIVATE) }
    var devMode by remember { mutableStateOf(prefs.getBoolean("dev_mode", true)) }
    var installing by remember { mutableStateOf<InstallSource?>(null) }
    var installMenu by remember { mutableStateOf(false) }
    var askLink by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var keyStart by remember { mutableStateOf<String?>(null) }
    var backupNeeded by remember { mutableStateOf(false) }
    LaunchedEffect(activity.openKey) { activity.openKey?.let { keyStart = it; keyDialog = true; activity.openKey = null } }

    fun refresh() = scope.launch {
        apps = withContext(Dispatchers.IO) { MiniApps.installSamples(context); MiniApps.scan(context) }
        backupNeeded = com.example.gemini.data.miniapps.MiniAppKeyBackup.needed(context)
    }

    // rescan whenever the screen comes back (an agent may have added or changed apps meanwhile)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    Scaffold(
        topBar = { Column {
            TopAppBar(
                title = { Text("Mini Apps") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { context.startActivity(android.content.Intent(context, MiniAppStoreActivity::class.java)) }) {
                        Icon(Icons.Outlined.Storefront, "Get apps")
                    }
                    IconButton(onClick = {
                        devMode = !devMode
                        prefs.edit().putBoolean("dev_mode", devMode).apply()
                        android.widget.Toast.makeText(context, if (devMode) "Developer mode on: development apps run, with developer tools" else "Developer mode off: only installed (signed) apps run", android.widget.Toast.LENGTH_LONG).show()
                    }) { Icon(Icons.Outlined.Code, "Developer mode", tint = if (devMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box {
                        IconButton(onClick = { installMenu = true }) { Icon(Icons.Outlined.InstallMobile, "Install or update an app (.agapp)") }
                        androidx.compose.material3.DropdownMenu(expanded = installMenu, onDismissRequest = { installMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Install from a file") }, onClick = {
                                installMenu = false
                                activity.pickFile { uri -> if (uri != null) installing = InstallSource.FromUri(uri) }
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Install from a link") }, onClick = { installMenu = false; askLink = true })
                        }
                    }
                    IconButton(onClick = { keyDialog = true }) { Icon(Icons.Outlined.Key, "Developer key") }
                    IconButton(onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            // the developer key lives only in AntiGem's private storage until it is backed up
            if (backupNeeded) androidx.compose.material3.Card(
                onClick = { keyStart = "backup"; keyDialog = true },
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                androidx.compose.foundation.layout.Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your developer key isn't backed up", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("If it's lost, the apps you signed can never be updated.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    androidx.compose.material3.TextButton(onClick = { keyStart = "backup"; keyDialog = true }) { Text("Back up") }
                }
            }
        } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val list = apps
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad))
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Apps, null, Modifier.padding(bottom = 16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No mini apps yet", style = MaterialTheme.typography.titleMedium)
                FolderProblems()
                androidx.compose.material3.Button(onClick = { context.startActivity(android.content.Intent(context, MiniAppStoreActivity::class.java)) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Get apps")
                }
                Text(
                    "Ask an agent to build one, e.g. \"make me a habit tracker mini app\". Apps live in ~/mini-apps/<name>/ (see ~/mini-apps/README.md).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(92.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (MiniApps.conflicts.isNotEmpty() || MiniApps.problems.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    FolderProblems()
                }
                items(list, key = { it.id }) { app ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .combinedClickable(onClick = { MiniAppActivity.launch(context, app.id) }, onLongClick = { info = app })
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.alpha(if (!app.signed && !devMode || app.tampered) 0.4f else 1f)) { AppIcon(app, 58) }
                        Text(app.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            when { app.tampered -> "modified"; app.signed -> app.version; else -> "DEV · ${app.version}" },
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = if (app.tampered) MaterialTheme.colorScheme.error else if (app.signed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
    info?.let { app -> AppInfoSheet(app, onDismiss = { info = null; refresh() }, onClearData = {}) }
    installing?.let { src -> androidx.compose.runtime.key(src) { PackageInstaller(src) { installing = null; refresh() } } }
    if (askLink) AskText("Install from a link", "An https:// link to an .agapp file (a release page download, your server, a cloud drive's direct link).", false, "Download") { url ->
        askLink = false
        if (url != null) installing = InstallSource.FromLink(url)
    }
    if (keyDialog) DeveloperKeyDialog(onDismiss = { keyDialog = false; keyStart = null; refresh() }, pickKeyFile = { cb -> activity.pickFile(cb) }, startWith = keyStart)
    LaunchedEffect(Unit) { refresh() }
}

/** Folders in ~/mini-apps that don't show up as apps, and why (so a typo in a manifest isn't a silent mystery). */
@Composable
private fun FolderProblems() {
    val lines = MiniApps.conflicts.map { (folder, id) -> "~/mini-apps/$folder doesn't run: its id $id belongs to an installed (signed) app" } +
        MiniApps.problems.map { (folder, why) -> "~/mini-apps/$folder isn't an app: $why" }
    if (lines.isNotEmpty()) Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
}
