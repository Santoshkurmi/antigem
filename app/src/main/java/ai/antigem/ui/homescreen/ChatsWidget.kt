package ai.antigem.ui.homescreen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.LocalState
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import ai.antigem.R
import ai.antigem.data.homescreen.HomeChat
import ai.antigem.data.homescreen.HomeData
import ai.antigem.data.homescreen.HomeIcons
import ai.antigem.data.homescreen.HomeLinks
import ai.antigem.data.homescreen.HomePrefs
import ai.antigem.data.homescreen.HomeSnapshot
import ai.antigem.domain.model.AgentKind
import ai.antigem.i18n.L

/**
 * The Chats widget: pinned chats first, then the newest (or only pinned ones, or one agent's: its setup screen), each
 * with its agent, project and time, a spinner while the agent works and a ✓ when it finished out of sight. A tap opens
 * the chat; + starts a new one (with the filtered agent).
 */
class ChatsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        HomeSnapshot.load(context)
        provideContent { HomeWidgetTheme { ChatsContent(preview = false) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        HomeSnapshot.load(context)
        provideContent { HomeWidgetTheme { ChatsContent(preview = true) } }
    }
}

class ChatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChatsWidget()
}

/** Chats for the widget picker's preview when there are none yet. */
internal val SAMPLE_CHATS get() = listOf(
    HomeChat("s1", AgentKind.CLAUDE.name, L.homescreen.chatsWidget.fixTheLoginBug, "my-app", System.currentTimeMillis() - 60_000, running = true, pinned = true),
    HomeChat("s2", AgentKind.AGY.name, L.homescreen.chatsWidget.darkModeForSettings, "my-app", System.currentTimeMillis() - 25 * 60_000, done = true),
    HomeChat("s3", AgentKind.CODEX.name, L.homescreen.chatsWidget.writeReleaseNotes, "website", System.currentTimeMillis() - 3 * 3_600_000),
    HomeChat("s4", AgentKind.OPENCODE.name, L.homescreen.chatsWidget.planTheNewApi, "server", System.currentTimeMillis() - 26 * 3_600_000)
)

@Composable
private fun ChatsContent(preview: Boolean) {
    val ctx = LocalContext.current
    val snap by HomeSnapshot.widgetState.collectAsState()
    val hide by HomePrefs.hideTitles.collectAsState()
    val data = snap ?: HomeData()
    val filter = (LocalState.current as? Preferences)?.get(WidgetKeys.FILTER) ?: "all"
    val agentFilter = AgentKind.entries.firstOrNull { it.name.equals(filter, true) }
    val chats = if (preview && data.chats.isEmpty()) SAMPLE_CHATS else filtered(data, filter, agentFilter)
    val size = LocalSize.current
    val compact = size.width < 210.dp
    val c = GlanceTheme.colors
    val title = when {
        filter == "pinned" -> L.homescreen.chatsWidget.pinnedChats
        agentFilter != null -> HomeIcons.agentLabel(agentFilter)
        else -> L.homescreen.chatsWidget.chats
    }
    val working = chats.count { it.running }
    val finished = chats.count { it.done }
    val subtitle = when {
        !data.loaded && !preview -> L.homescreen.chatsWidget.openAntigemOnce
        working > 0 && finished > 0 -> L.homescreen.chatsWidget.workingWorkingFinishedDone(working, finished)
        working > 0 -> "$working working"
        finished > 0 -> "$finished done"
        else -> "AntiGem"
    }

    Column(widgetRoot(GlanceModifier.fillMaxSize()).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                ImageProvider(R.mipmap.ic_launcher_round), "AntiGem",
                modifier = GlanceModifier.size(26.dp).clickable(open(ctx, HomeLinks.Target.OpenPage(HomeLinks.Page.CHAT)))
            )
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Label(title, 15.sp, c.onSurface, bold = true)
                if (size.height >= 120.dp) Label(subtitle, 11.sp, if (working > 0) c.primary else c.onSurfaceVariant)
            }
            Box(GlanceModifier.clickable(open(ctx, HomeLinks.Target.NewChat(agentFilter)))) {
                GlyphDisc(34.dp, c.primary, R.drawable.ic_home_add, c.onPrimary, glyphScale = 0.6f)
            }
        }
        if (chats.isEmpty()) {
            Empty(data, filter)
        } else {
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(chats, itemId = { it.id.hashCode().toLong() }) { chat -> ChatRow(chat, compact, hide) }
            }
        }
    }
}

private fun filtered(data: HomeData, filter: String, agent: AgentKind?): List<HomeChat> {
    val list = when {
        filter == "pinned" -> data.chats.filter { it.pinned }
        agent != null -> data.chats.filter { it.agentKind == agent }
        else -> data.chats
    }
    // pinned on top, like the sidebar
    return list.sortedWith(compareByDescending<HomeChat> { it.pinned }.thenByDescending { it.updatedAt }).take(25)
}

@Composable
private fun ChatRow(chat: HomeChat, compact: Boolean, hide: Boolean) {
    val ctx = LocalContext.current
    val c = GlanceTheme.colors
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(cardColor())
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(open(ctx, HomeLinks.Target.Chat(chat.agentKind, chat.id))),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatAvatar(chat, 34.dp, hide)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label(shownTitle(chat, hide), 14.sp, c.onSurface, bold = true, modifier = GlanceModifier.defaultWeight())
                    if (chat.pinned) {
                        Spacer(GlanceModifier.width(4.dp))
                        Glyph(R.drawable.ic_home_pin, 12.dp, c.onSurfaceVariant)
                    }
                    if (!compact) {
                        Spacer(GlanceModifier.width(6.dp))
                        Label(ago(chat.updatedAt), 11.sp, c.onSurfaceVariant)
                    }
                }
                Spacer(GlanceModifier.height(2.dp))
                StatusLine(chat)
            }
        }
    }
}

/** Working (spinner), finished out of sight (✓), or the agent and project. */
@Composable
internal fun StatusLine(chat: HomeChat) {
    val accent = fixed(HomeIcons.agentColor(chat.agentKind))
    val green = fixed(0xFF22C55E)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            chat.running -> {
                CircularProgressIndicator(GlanceModifier.size(12.dp), color = accent)
                Spacer(GlanceModifier.width(6.dp))
                Label(L.common.working, 12.sp, accent, bold = true)
            }
            chat.done -> {
                Glyph(R.drawable.ic_home_check, 13.dp, green)
                Spacer(GlanceModifier.width(4.dp))
                Label(L.homescreen.chatsWidget.doneNewReply, 12.sp, green, bold = true)
            }
            else -> Label(
                listOf(HomeIcons.agentLabel(chat.agentKind), chat.project).filter { it.isNotBlank() }.joinToString(" · "),
                12.sp, GlanceTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Empty(data: HomeData, filter: String) {
    val ctx = LocalContext.current
    val c = GlanceTheme.colors
    val (head, sub) = when {
        !data.loaded -> L.homescreen.chatsWidget.openAntigemOnce to L.homescreen.chatsWidget.yourChatsShowUpHereLive
        filter == "pinned" -> L.homescreen.chatsWidget.noPinnedChats to L.homescreen.chatsWidget.inTheSidebarOnAChat
        else -> L.homescreen.chatsWidget.noChatsYet to L.homescreen.chatsWidget.tapToStartOne
    }
    Column(
        GlanceModifier.fillMaxSize().clickable(open(ctx, HomeLinks.Target.OpenPage(HomeLinks.Page.CHAT))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlyphDisc(44.dp, c.primaryContainer, R.drawable.ic_home_chat, c.onPrimaryContainer)
        Spacer(GlanceModifier.height(8.dp))
        Text(head, style = TextStyle(color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1)
        Text(sub, style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center), maxLines = 2)
    }
}
