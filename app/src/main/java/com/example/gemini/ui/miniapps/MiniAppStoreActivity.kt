package com.example.gemini.ui.miniapps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.gemini.BuildConfig
import com.example.gemini.data.miniapps.MiniAppStore
import com.example.gemini.data.miniapps.MiniApps
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.GeminiTheme
import com.example.gemini.theme.isSystemInDarkThemeRobust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Get apps": the mini apps published in AntiGem's mini app repository (a GitHub repo with signed packages and a
 * catalog). Install, update or open each; installing goes through the normal install screen (signature, version,
 * permissions), after the download was checked against the catalog.
 */
class MiniAppStoreActivity : ComponentActivity() {
    companion object {
        /** Debug builds: another catalog URL (e.g. http://127.0.0.1:8099/antigem-mini-apps.json through adb reverse). */
        const val EXTRA_CATALOG = "catalog"
    }

    private val prefs by lazy { AuthPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val catalog = intent.getStringExtra(EXTRA_CATALOG)?.takeIf { BuildConfig.DEBUG } ?: MiniAppStore.CATALOG
        setContent {
            val initial = remember { prefs.getThemeModeSync() }
            val mode by prefs.themeMode.collectAsState(initial = initial)
            val systemDark = isSystemInDarkThemeRobust()
            GeminiTheme(darkTheme = when (mode) { "DARK" -> true; "LIGHT" -> false; else -> systemDark }) {
                StoreScreen(catalog, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreScreen(catalogUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<MiniAppStore.Catalog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // bumped after an install / uninstall elsewhere: the buttons follow what's installed
    var tick by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf<InstallSource?>(null) }
    var replace by remember { mutableStateOf<Pair<MiniAppStore.Entry, String>?>(null) }

    fun load() = scope.launch {
        loading = true
        runCatching { MiniAppStore.load(catalogUrl) }
            .onSuccess { catalog = it; error = null }
            .onFailure { error = it.message ?: "Couldn't load the catalog" }
        withContext(Dispatchers.IO) { MiniApps.scan(context) }
        loading = false
        tick++
    }
    LaunchedEffect(Unit) { load() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Get mini apps")
                        Text(catalog?.name ?: MiniAppStore.REPO, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MiniAppStore.PAGE))) } }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open the repository")
                    }
                    IconButton(enabled = !loading, onClick = { load() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val c = catalog
        when {
            c == null && loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            c == null -> Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Couldn't load the apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = { load() }, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
            }
            c.apps.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No apps in this catalog yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (error != null) item { Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp)) }
                items(c.apps, key = { it.id }) { e ->
                    val (state, detail) = remember(e, tick) { MiniAppStore.state(context, e) }
                    StoreCard(e, state, detail,
                        onInstall = { installing = InstallSource.FromStore(e) },
                        onOpen = { MiniAppActivity.launch(context, e.id) },
                        onReplace = { replace = e to detail.orEmpty() })
                }
            }
        }
    }
    installing?.let { src -> key(src) { PackageInstaller(src) { installing = null; scope.launch { withContext(Dispatchers.IO) { MiniApps.scan(context) }; tick++ } } } }
    replace?.let { (e, folder) ->
        AlertDialog(
            onDismissRequest = { replace = null },
            title = { Text("Replace the development copy?") },
            text = {
                Text("~/mini-apps/$folder has the same id (${e.id}), so the published ${e.name} can't be installed next to it. " +
                    "Delete that folder and its data, then install the published app?")
            },
            confirmButton = {
                TextButton(onClick = {
                    replace = null
                    scope.launch {
                        withContext(Dispatchers.IO) { java.io.File(MiniApps.appsDir(context), folder).deleteRecursively(); MiniApps.scan(context) }
                        tick++
                        installing = InstallSource.FromStore(e)
                    }
                }) { Text("Delete and install", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { replace = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StoreCard(e: MiniAppStore.Entry, state: MiniAppStore.State, detail: String?, onInstall: () -> Unit, onOpen: () -> Unit, onReplace: () -> Unit) {
    val context = LocalContext.current
    val perms = remember(e) { MiniAppStore.permissionTitles(e) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(e.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (e.iconUrl != null) AsyncImage(model = e.iconUrl, contentDescription = null, modifier = Modifier.size(56.dp))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${e.developer} · ${e.version} · ${Formatter.formatShortFileSize(context, e.size)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.padding(start = 8.dp)) {
                    when (state) {
                        MiniAppStore.State.INSTALL -> FilledTonalButton(onClick = onInstall) { Text("Install") }
                        MiniAppStore.State.UPDATE -> Button(onClick = onInstall) { Text("Update") }
                        MiniAppStore.State.OPEN -> OutlinedButton(onClick = onOpen) { Text("Open") }
                        MiniAppStore.State.DEV_COPY -> OutlinedButton(onClick = onReplace) { Text("Replace") }
                        MiniAppStore.State.OTHER_DEVELOPER -> {}
                    }
                }
            }
            if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            if (perms.isNotEmpty()) Text("Asks for: " + perms.joinToString(", "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            val note = when (state) {
                MiniAppStore.State.UPDATE -> "Installed: $detail → ${e.version}"
                MiniAppStore.State.OPEN -> "Installed"
                MiniAppStore.State.DEV_COPY -> "A development copy is in ~/mini-apps/$detail"
                MiniAppStore.State.OTHER_DEVELOPER -> "Installed from another developer ($detail): uninstall it to get this one"
                MiniAppStore.State.INSTALL -> null
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = if (state == MiniAppStore.State.OTHER_DEVELOPER || state == MiniAppStore.State.DEV_COPY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
