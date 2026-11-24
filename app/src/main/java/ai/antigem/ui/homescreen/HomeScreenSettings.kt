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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AppShortcut
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
import ai.antigem.data.homescreen.HomePrefs
import ai.antigem.data.homescreen.HomeScripts
import ai.antigem.data.homescreen.HomeShortcuts
import ai.antigem.data.homescreen.HomeSnapshot
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.chat.ChatViewModelHolder
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
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
    val scripts = remember(scriptsTick) { HomeScripts.list(context) }
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
    // "widget" or "shortcut": the chat picker is open for that
    var picking by remember { mutableStateOf<String?>(null) }
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
                        OutlinedButton(onClick = { if (k == WidgetKind.CHAT) picking = "widget" else HomeWidgets.pin(context, k) }) { Text(L.common.add) }
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
            OutlinedButton(onClick = { picking = "shortcut" }) { Text(L.homescreen.settings.chooseAChat) }
            Spacer(Modifier.height(4.dp))
            Hint(L.homescreen.settings.alsoOnAChatInThe)
        }

        Section(Icons.Outlined.TouchApp, L.homescreen.settings.appIconMenu) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.homescreen.settings.newestChats, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Hint(L.homescreen.settings.longPressTheAntigemIconYour)
                }
                Switch(checked = recentOn, onCheckedChange = { HomePrefs.setRecentInMenu(it) })
            }
        }

        Section(Icons.Outlined.Terminal, L.homescreen.settings.scriptsShortcuts) {
            Hint(
                "Like Termux:Widget: files in ~/.shortcuts open a terminal tab that runs them; files in ~/.shortcuts/tasks run " +
                    "in the background and notify you when they end. The first line picks the interpreter (#!/usr/bin/env python3), " +
                    "otherwise bash. Use the Scripts widget, or add one script as its own icon:"
            )
            Spacer(Modifier.height(6.dp))
            if (!HomeScripts.dir(context).isDirectory) {
                OutlinedButton(onClick = {
                    if (HomeScripts.ensureFolders(context)) {
                        AppToastHelper.showToast(L.homescreen.settings.madeShortcutsAndShortcutsTasks, ChatToastType.SUCCESS)
                        HomeWidgets.refreshScripts(context)
                    } else {
                        AppToastHelper.showToast(L.homescreen.settings.setUpTheTerminalFirst, ChatToastType.INFO)
                    }
                }) { Text(L.homescreen.settings.makeTheFolders) }
            } else if (scripts.isEmpty()) {
                Text(L.homescreen.settings.noScriptsYet, fontSize = 12.5.sp, color = subtle())
            }
            scripts.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (s.background) L.homescreen.settings.inTheBackground else L.homescreen.settings.inATerminalTab, fontSize = 11.5.sp, color = subtle())
                    }
                    TextButton(onClick = { HomeShortcuts.pinScript(context, s) }) { Text(L.homescreen.settings.addIcon) }
                }
            }
            if (HomeScripts.dir(context).isDirectory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { HomeWidgets.refreshScripts(context) }) { Icon(Icons.Outlined.Refresh, L.homescreen.settings.lookAgain) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.homescreen.settings.hideChatNames, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Hint(L.homescreen.settings.widgetsShowClaudeCodeChatAnd)
                }
                Switch(checked = hideTitles, onCheckedChange = { HomePrefs.setHideTitles(it); HomeWidgets.refreshAll(context) })
            }
        }
    }

    picking?.let { mode ->
        ChatPickerDialog(
            title = if (mode == "widget") L.homescreen.settings.whichChatShouldTheWidgetShow else L.homescreen.settings.whichChatShouldGetAnIcon,
            chats = snap?.chats.orEmpty(),
            onDismiss = { picking = null }
        ) { c ->
            picking = null
            if (mode == "widget") HomeWidgets.pin(context, WidgetKind.CHAT, Triple(c.id, c.agentKind, c.title))
            else HomeShortcuts.pinChat(context, c.id, c.agentKind, c.title)
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
