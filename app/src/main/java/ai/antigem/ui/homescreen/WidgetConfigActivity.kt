package ai.antigem.ui.homescreen

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import ai.antigem.data.homescreen.HomeAction
import ai.antigem.data.homescreen.HomeChat
import ai.antigem.data.homescreen.HomeIcons
import ai.antigem.data.homescreen.HomePrefs
import ai.antigem.data.homescreen.HomeSnapshot
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.domain.model.AgentKind
import ai.antigem.theme.GeminiTheme
import ai.antigem.theme.isSystemInDarkThemeRobust
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * A widget's setup screen, opened when it is placed (and from its settings on Android 12+): which chats the Chats
 * widget lists or the Quick actions widget's buttons.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val info = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId) }.getOrNull()
        val kind = info?.provider?.takeIf { it.packageName == packageName }?.let { WidgetKind.ofProvider(it.className) }
        if (kind == null) {
            finish()
            return
        }
        if (kind == WidgetKind.SCRIPTS) {
            done()
            return
        }
        HomeSnapshot.load(this)
        HomePrefs.init(this)
        enableEdgeToEdge()
        val prefs = AuthPreferences(applicationContext)
        setContent {
            val mode by prefs.themeMode.collectAsState(initial = remember { prefs.getThemeModeSync() })
            val systemDark = isSystemInDarkThemeRobust()
            GeminiTheme(darkTheme = when (mode) { "DARK" -> true; "LIGHT" -> false; else -> systemDark }) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Header(kind)
                        when (kind) {
                            WidgetKind.CHATS -> ChatsSetup()
                            WidgetKind.ACTIONS -> ActionsSetup()
                            WidgetKind.SCRIPTS -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun done() {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private suspend fun currentState(): Preferences? = runCatching {
        val gid = GlanceAppWidgetManager(this).getGlanceIdBy(widgetId)
        getAppWidgetState(this, PreferencesGlanceStateDefinition, gid)
    }.getOrNull()

    private fun save(kind: WidgetKind, edit: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        lifecycleScope.launch {
            runCatching {
                val gid = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(widgetId)
                updateAppWidgetState(this@WidgetConfigActivity, gid) { edit(it) }
                kind.widget().update(this@WidgetConfigActivity, gid)
            }
            done()
        }
    }

    @Composable
    private fun Header(kind: WidgetKind) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L.homescreen.widgetConfigActivity.titleWidget(kind.title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(kind.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, L.common.cancel) }
        }
    }

    private fun enabledAgents(): List<AgentKind> =
        HomeSnapshot.state.value?.enabledAgents?.takeIf { it.isNotEmpty() } ?: AgentKind.entries

    // ------------------------------------------------------------------ Chats

    @Composable
    private fun ChatsSetup() {
        var filter by remember { mutableStateOf("all") }
        LaunchedEffect(Unit) { currentState()?.get(WidgetKeys.FILTER)?.let { filter = it } }
        val options = listOf(
            "all" to (L.homescreen.widgetConfigActivity.allChats to L.homescreen.widgetConfigActivity.pinnedChatsFirstThenTheNewest),
            "pinned" to (L.homescreen.widgetConfigActivity.pinnedChats to L.homescreen.widgetConfigActivity.onlyTheChatsYouPinnedIn)
        ) + enabledAgents().map { a -> a.name to (HomeIcons.agentLabel(a) to L.homescreen.widgetConfigActivity.onlyAgentlabelChatsStartsOne(HomeIcons.agentLabel(a))) }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(L.common.show, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Column {
                    options.forEach { (value, text) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = filter == value, role = Role.RadioButton) { filter = value }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = filter == value, onClick = { filter = value }, modifier = Modifier.mergedIntoRow())
                            Column(Modifier.weight(1f)) {
                                Text(text.first, fontWeight = FontWeight.Medium)
                                Text(text.second, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AgentKind.entries.firstOrNull { it.name == value }?.let {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(HomeIcons.agentColor(it))))
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { save(WidgetKind.CHATS) { it[WidgetKeys.FILTER] = filter } }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(L.common.done, fontSize = 16.sp)
            }
        }
    }

    // ------------------------------------------------------------------ Quick actions

    @Composable
    private fun ActionsSetup() {
        val chosen = remember { mutableStateListOf<HomeAction>() }
        var labels by remember { mutableStateOf(true) }
        var loaded by remember { mutableStateOf(false) }
        val available = HomeAction.entries.filter { it.availableFor(enabledAgents()) }
        LaunchedEffect(Unit) {
            val st = currentState()
            chosen.clear()
            chosen.addAll(actionsOf(st).filter { it in available })
            labels = st?.get(WidgetKeys.LABELS) != "0"
            loaded = true
        }
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    // what it will look like
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            chosen.take(6).forEach { a ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    ActionDisc(a, 42)
                                    if (labels) Text(a.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).toggleRowSemantics(labels, { labels = it }), verticalAlignment = Alignment.CenterVertically) {
                        Text(L.homescreen.widgetConfigActivity.showNamesUnderTheButtons, modifier = Modifier.weight(1f))
                        Switch(checked = labels, onCheckedChange = { labels = it }, modifier = Modifier.mergedIntoRow())
                    }
                }
                item { SectionLabel(L.homescreen.widgetConfigActivity.onTheWidgetAsManyAs) }
                items(chosen.toList(), key = { "on_" + it.id }) { a ->
                    val i = chosen.indexOf(a)
                    ActionRow(a) {
                        IconButton(onClick = { if (i > 0) { chosen.removeAt(i); chosen.add(i - 1, a) } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, L.homescreen.widgetConfigActivity.moveUp) }
                        IconButton(onClick = { if (i < chosen.size - 1) { chosen.removeAt(i); chosen.add(i + 1, a) } }, enabled = i < chosen.size - 1) { Icon(Icons.Default.KeyboardArrowDown, L.homescreen.widgetConfigActivity.moveDown) }
                        IconButton(onClick = { if (chosen.size > 1) chosen.remove(a) }, enabled = chosen.size > 1) {
                            Icon(Icons.Outlined.RemoveCircleOutline, L.common.remove, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                val rest = available.filter { it !in chosen }
                if (rest.isNotEmpty()) item { SectionLabel(L.common.add) }
                items(rest, key = { "off_" + it.id }) { a ->
                    ActionRow(a, onClick = { chosen.add(a) }) {
                        IconButton(onClick = { chosen.add(a) }) { Icon(Icons.Outlined.AddCircleOutline, L.common.add, tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            Button(
                onClick = {
                    val ids = chosen.joinToString(",") { it.id }
                    val showLabels = labels
                    save(WidgetKind.ACTIONS) {
                        it[WidgetKeys.ACTIONS] = ids
                        it[WidgetKeys.LABELS] = if (showLabels) "1" else "0"
                    }
                },
                enabled = loaded && chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
            ) { Text(L.common.done, fontSize = 16.sp) }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp))
    }

    @Composable
    private fun ActionRow(a: HomeAction, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionDisc(a, 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.label, fontWeight = FontWeight.Medium)
                Text(a.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

/** An action's icon as in the widget: its glyph on its color. */
@Composable
internal fun ActionDisc(a: HomeAction, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(a.color)), contentAlignment = Alignment.Center) {
        Icon(painterResource(a.glyph), null, tint = Color.White, modifier = Modifier.size((size * 0.52f).dp))
    }
}

/** A chat in a picker: avatar, name, agent · project · time. */
@Composable
internal fun ChatPickRow(c: HomeChat, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(HomeIcons.agentColor(c.agentKind))), contentAlignment = Alignment.Center) {
            Text(HomeIcons.initials(c.title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(HomeIcons.agentLabel(c.agentKind), c.project, ago(c.updatedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (c.running) Text(L.common.working, fontSize = 11.sp, color = Color(HomeIcons.agentColor(c.agentKind)), fontWeight = FontWeight.SemiBold)
    }
}
