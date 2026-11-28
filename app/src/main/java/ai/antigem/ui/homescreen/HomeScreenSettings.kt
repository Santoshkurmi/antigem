package ai.antigem.ui.homescreen

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.antigem.data.homescreen.HomeAction
import ai.antigem.data.homescreen.HomeChat
import ai.antigem.data.homescreen.HomeLinks
import ai.antigem.data.homescreen.HomePrefs
import ai.antigem.data.homescreen.HomeScripts
import ai.antigem.data.homescreen.HomeShortcuts
import ai.antigem.data.homescreen.HomeSnapshot
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.chat.ChatViewModelHolder
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics
import kotlinx.coroutines.flow.MutableStateFlow
import ai.antigem.i18n.L

/**
 * Settings → Home screen: AntiGem's widgets, shortcuts (pages, new chats, one chat, scripts), the app icon's menu and
 * how the widgets look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreenSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { HomePrefs.init(context); HomeSnapshot.load(context) }
    val snap by HomeSnapshot.state.collectAsState()
    val enabledFlow = remember { ChatViewModelHolder.peek()?.enabledAgents ?: MutableStateFlow(snap?.enabledAgents?.toSet() ?: AgentKind.entries.toSet()) }
    val enabled by enabledFlow.collectAsState()
    val recentOn by HomePrefs.recentInMenu.collectAsState()
    val colors by HomePrefs.colors.collectAsState()
    val opacity by HomePrefs.opacity.collectAsState()
    val hideTitles by HomePrefs.hideTitles.collectAsState()
    val scriptsTick by HomeScripts.changes.collectAsState()
    val scriptList = remember(scriptsTick) { HomeScripts.listing(context) }
    val scripts = scriptList.scripts
    // the Ubuntu chroot's ~/.shortcuts is read through root: once when the page opens, then with ⟳
    var readingScripts by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (HomeScripts.chrootInstall(context) != null) {
            readingScripts = true
            HomeScripts.refresh(context)
            readingScripts = false
        }
    }
    var addingScript by remember { mutableStateOf(false) }
    // how many of each widget are placed: counted again whenever this page comes back
    var counts by remember { mutableStateOf(WidgetKind.entries.associateWith { HomeWidgets.count(context, it) }) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                counts = WidgetKind.entries.associateWith { HomeWidgets.count(context, it) }
                HomeScripts.touch()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // the chat picker for a chat icon is open
    var pickingChat by remember { mutableStateOf(false) }
    var askNewKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canPinWidgets = HomeWidgets.canPin(context)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(Icons.Outlined.Widgets, L.homescreen.settings.widgets) {
            Hint(L.homescreen.settings.liveAntigemOnYourHomeScreen)
            Spacer(Modifier.height(8.dp))
            WidgetKind.entries.forEach { k ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(k.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            val n = counts[k] ?: 0
                            if (n > 0) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                                    Text(if (n == 1) L.homescreen.settings.onHomeScreen else L.homescreen.settings.nOnHomeScreen(n), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
                                }
                            }
                        }
                        Text(k.description, fontSize = 12.sp, color = subtle())
                    }
                    Spacer(Modifier.width(8.dp))
                    if (canPinWidgets) {
                        OutlinedButton(onClick = { HomeWidgets.pin(context, k) }) { Text(L.common.add) }
                    }
                }
            }
            if (!canPinWidgets) {
                Spacer(Modifier.height(4.dp))
                Text(
                    L.homescreen.settings.toAddOneLongPressAn,
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary
                )
            } else {
                Hint(L.homescreen.settings.orLongPressTheHomeScreen)
            }
        }

        Section(Icons.Outlined.AppShortcut, L.homescreen.settings.shortcuts) {
            Hint(L.homescreen.settings.anIconThatOpensOneThing)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HomeAction.entries.filter { it.availableFor(enabled) }.forEach { a ->
                    Column(
                        Modifier.width(74.dp).clickable { HomeShortcuts.pinAction(context, a) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ActionDisc(a, 44)
                        Text(a.label, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        Section(Icons.AutoMirrored.Outlined.Chat, L.homescreen.settings.aChatOnTheHomeScreen) {
            Hint(
                L.homescreen.settings.anIconThatOpensOneChat
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { pickingChat = true }) { Text(L.homescreen.settings.chooseAChat) }
            Spacer(Modifier.height(4.dp))
            Hint(L.homescreen.settings.alsoOnAChatInThe)
        }

        Section(Icons.Outlined.TouchApp, L.homescreen.settings.appIconMenu) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleRowSemantics(recentOn, { HomePrefs.setRecentInMenu(it) })) {
                Column(Modifier.weight(1f)) {
                    Text(L.homescreen.settings.newestChats, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Hint(L.homescreen.settings.longPressTheAntigemIconYour)
                }
                Switch(checked = recentOn, onCheckedChange = { HomePrefs.setRecentInMenu(it) }, modifier = Modifier.mergedIntoRow())
            }
        }

        Section(Icons.Outlined.Terminal, L.homescreen.settings.scriptsShortcuts) {
            Hint(L.homescreen.settings.likeTermuxWidgetFilesInShortcuts)
            if (scriptList.chroot) {
                Spacer(Modifier.height(4.dp))
                Hint(L.homescreen.settings.inTheUbuntuChrootTheScripts)
            }
            Spacer(Modifier.height(6.dp))
            when {
                readingScripts && !scriptList.read -> Hint(L.homescreen.settings.readingUbuntusShortcuts)
                scriptList.error != null -> Text(
                    L.homescreen.homeScripts.couldNotReadTheUbuntuChroot(scriptList.error),
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error
                )
                scriptList.read && !scriptList.hasFolder -> OutlinedButton(onClick = {
                    scope.launch {
                        if (HomeScripts.ensureFolders(context)) {
                            AppToastHelper.showToast(L.homescreen.settings.madeShortcutsAndShortcutsTasks, ChatToastType.SUCCESS)
                            HomeWidgets.refreshScripts(context)
                        } else {
                            AppToastHelper.showToast(L.homescreen.settings.setUpTheTerminalFirst, ChatToastType.INFO)
                        }
                    }
                }) { Text(L.homescreen.settings.makeTheFolders) }
                scripts.isEmpty() -> Text(L.homescreen.settings.noScriptsYet, fontSize = 12.5.sp, color = subtle())
            }
            scripts.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (s.background) L.homescreen.settings.inTheBackground else L.homescreen.settings.inATerminalTab, fontSize = 11.5.sp, color = subtle())
                    }
                    if (s.link) {
                        // a link added here (or by ln -s): only the link goes, the script stays
                        TextButton(onClick = {
                            scope.launch {
                                if (HomeScripts.removeLink(context, s)) {
                                    AppToastHelper.showToast(L.homescreen.settings.removedNameTheScriptItself(s.label), ChatToastType.SUCCESS)
                                    HomeWidgets.refreshScripts(context)
                                } else {
                                    AppToastHelper.showToast(L.homescreen.settings.couldNotRemoveName(s.label), ChatToastType.ERROR)
                                }
                            }
                        }) { Text(L.common.remove) }
                    }
                    TextButton(onClick = { scope.launch { HomeShortcuts.pinScript(context, s) } }) { Text(L.homescreen.settings.addIcon) }
                }
            }
            if (scriptList.hasFolder || scriptList.chroot) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scriptList.hasFolder) {
                        OutlinedButton(onClick = { addingScript = true }) { Text(L.homescreen.settings.addAScript) }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = {
                        scope.launch {
                            readingScripts = true
                            HomeScripts.refresh(context)
                            HomeWidgets.refreshScripts(context)
                            readingScripts = false
                        }
                    }) { Icon(Icons.Outlined.Refresh, L.homescreen.settings.lookAgain) }
                    Hint(L.homescreen.settings.addedOrRenamedScriptsLookAgain)
                }
            }
        }

        Section(Icons.Outlined.Palette, L.homescreen.settings.widgetLook) {
            Text(L.homescreen.settings.colors, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = colors == HomePrefs.COLORS_APP, onClick = { HomePrefs.setColors(HomePrefs.COLORS_APP); HomeWidgets.refreshAll(context) },
                    label = { Text(L.homescreen.settings.antigemTheme) })
                if (Build.VERSION.SDK_INT >= 31) {
                    FilterChip(selected = colors == HomePrefs.COLORS_WALLPAPER, onClick = { HomePrefs.setColors(HomePrefs.COLORS_WALLPAPER); HomeWidgets.refreshAll(context) },
                        label = { Text(L.homescreen.settings.wallpaperColors) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(L.homescreen.settings.background, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100 to L.homescreen.settings.solid, 85 to L.homescreen.settings.glass, 60 to L.common.clear).forEach { (v, label) ->
                    FilterChip(selected = opacity == v, onClick = { HomePrefs.setOpacity(v); HomeWidgets.refreshAll(context) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleRowSemantics(hideTitles, { HomePrefs.setHideTitles(it); HomeWidgets.refreshAll(context) })) {
                Column(Modifier.weight(1f)) {
                    Text(L.homescreen.settings.hideChatNames, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Hint(L.homescreen.settings.widgetsShowClaudeCodeChatAnd)
                }
                Switch(checked = hideTitles, onCheckedChange = { HomePrefs.setHideTitles(it); HomeWidgets.refreshAll(context) }, modifier = Modifier.mergedIntoRow())
            }
        }

        Section(Icons.Outlined.Key, L.homescreen.settings.linkKey) {
            Hint(L.homescreen.settings.shortcutsAndWidgetsCarryAKey)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { askNewKey = true }) { Text(L.homescreen.settings.newKey) }
        }
    }

    if (askNewKey) {
        AlertDialog(
            onDismissRequest = { askNewKey = false },
            title = { Text(L.homescreen.settings.makeANewKeyForHome, fontSize = 18.sp) },
            text = { Text(L.homescreen.settings.shortcutsAndWidgetsOnAndroid8) },
            confirmButton = {
                TextButton(onClick = {
                    askNewKey = false
                    scope.launch {
                        withContext(Dispatchers.IO) { HomeLinks.resetKey(context.applicationContext) }
                        AppToastHelper.showToast(L.homescreen.settings.newKeyMadeShortcutsAndWidgets, ChatToastType.SUCCESS)
                    }
                }) { Text(L.homescreen.settings.newKey) }
            },
            dismissButton = { TextButton(onClick = { askNewKey = false }) { Text(L.common.cancel) } }
        )
    }

    if (addingScript) {
        AddScriptDialog(onDismiss = { addingScript = false }) { name ->
            addingScript = false
            AppToastHelper.showToast(L.homescreen.settings.nameIsOnTheScriptsWidget(name), ChatToastType.SUCCESS)
            HomeWidgets.refreshScripts(context)
        }
    }

    if (pickingChat) {
        ChatPickerDialog(
            title = L.homescreen.settings.whichChatShouldGetAnIcon,
            chats = snap?.chats.orEmpty(),
            onDismiss = { pickingChat = false }
        ) { c ->
            pickingChat = false
            HomeShortcuts.pinChat(context, c.id, c.agentKind, c.title)
        }
    }
}

@Composable
private fun ChatPickerDialog(title: String, chats: List<HomeChat>, onDismiss: () -> Unit, onPick: (HomeChat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 18.sp) },
        text = {
            if (chats.isEmpty()) {
                Text(L.homescreen.settings.noChatsYet)
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(chats, key = { it.id }) { c -> ChatPickRow(c) { onPick(c) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/**
 * Picks a script anywhere in the home (AntiGem's, or the Ubuntu chroot's) and puts a link to it in ~/.shortcuts, or in
 * ~/.shortcuts/tasks to run in the background: the script stays where it is, no copy.
 */
@Composable
private fun AddScriptDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home = remember { HomeScripts.pickerHome(context) }
    // null: the home
    var dir by remember { mutableStateOf<String?>(null) }
    var browse by remember { mutableStateOf<HomeScripts.Browse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(dir) {
        loading = true
        browse = HomeScripts.browse(context, dir)
        loading = false
    }
    fun tilde(path: String): String =
        if (home != null && (path == home || path.startsWith("$home/"))) "~" + path.removePrefix(home) else path

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (picked == null) L.homescreen.settings.pickAScript else L.homescreen.settings.addToTheScriptsWidget, fontSize = 18.sp) },
        text = {
            val chosen = picked
            if (chosen == null) {
                val cur = browse?.dir ?: dir ?: home.orEmpty()
                Column {
                    Text(tilde(cur), fontSize = 12.5.sp, color = subtle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(
                        Modifier.fillMaxWidth().toggleRowSemantics(showHidden, { showHidden = it }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(L.homescreen.settings.showHiddenFiles, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = showHidden, onCheckedChange = { showHidden = it }, modifier = Modifier.mergedIntoRow())
                    }
                    val entries = browse?.entries.orEmpty().filter { showHidden || !it.name.startsWith(".") }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        if (home != null && cur != home && cur.startsWith("$home/")) {
                            item {
                                PickRow(Icons.Outlined.ArrowUpward, L.homescreen.settings.upOneFolder) { dir = cur.substringBeforeLast('/') }
                            }
                        }
                        items(entries, key = { (if (it.dir) "d:" else "f:") + it.name }) { e ->
                            PickRow(if (e.dir) Icons.Outlined.Folder else Icons.Outlined.Description, e.name + if (e.dir) "/" else "") {
                                if (e.dir) dir = "$cur/${e.name}"
                                else {
                                    picked = "$cur/${e.name}"
                                    name = e.name
                                    problem = null
                                }
                            }
                        }
                    }
                    when {
                        loading -> Hint(L.common.loading)
                        browse?.error != null -> Text(browse?.error.orEmpty(), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                        entries.isEmpty() -> Hint(L.homescreen.settings.nothingHere)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tilde(chosen), fontSize = 12.5.sp, color = subtle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Hint(L.homescreen.settings.aLinkToItGoesIn)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; problem = null }, singleLine = true,
                        label = { Text(L.common.name) }, modifier = Modifier.fillMaxWidth()
                    )
                    listOf(false to L.homescreen.scriptsWidget.opensATerminalTab, true to L.homescreen.scriptsWidget.runsInTheBackground).forEach { (bg, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = background == bg, role = Role.RadioButton) { background = bg }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = background == bg, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 13.5.sp)
                        }
                    }
                    problem?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            val chosen = picked
            if (chosen != null) {
                TextButton(
                    enabled = !busy && HomeScripts.isGoodName(name.trim()),
                    onClick = {
                        busy = true
                        scope.launch {
                            val n = name.trim()
                            val r = HomeScripts.addLink(context, chosen, n, background)
                            busy = false
                            if (r == null) onAdded(n) else problem = r
                        }
                    }
                ) { Text(L.common.add) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (picked != null) { picked = null; problem = null } else onDismiss() }) {
                Text(if (picked != null) L.common.back else L.common.cancel)
            }
        }
    )
}

@Composable
private fun PickRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.5.sp, color = subtle())
}

@Composable
private fun subtle() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
