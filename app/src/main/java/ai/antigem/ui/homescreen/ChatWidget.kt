package ai.antigem.ui.homescreen

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalSize
import androidx.glance.LocalState
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ai.antigem.R
import ai.antigem.data.homescreen.HomeChat
import ai.antigem.data.homescreen.HomeIcons
import ai.antigem.data.homescreen.HomeLinks
import ai.antigem.data.homescreen.HomePrefs
import ai.antigem.data.homescreen.HomeSnapshot
import ai.antigem.domain.model.AgentKind
import kotlin.math.max
import ai.antigem.i18n.L

/**
 * The One chat widget: a chat picked in its setup screen (or in the app), with its status, its latest reply, and
 * Open / Reply (Reply: the chat in the floating window over whatever app is open).
 */
class ChatWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        HomeSnapshot.load(context)
        provideContent { HomeWidgetTheme { OneChat(sample = null) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val chat = HomeSnapshot.load(context).chats.firstOrNull { it.reply.isNotBlank() } ?: SAMPLE_CHATS[1]
        provideContent { HomeWidgetTheme { OneChat(sample = chat) } }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        runCatching { HomeWidgets.forget(context, GlanceAppWidgetManager(context).getAppWidgetId(glanceId)) }
    }

    companion object {
        val KEY_ID = stringPreferencesKey("chat_id")
        val KEY_AGENT = stringPreferencesKey("chat_agent")
        val KEY_TITLE = stringPreferencesKey("chat_title")
    }
}

class ChatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChatWidget()
}

@Composable
private fun OneChat(sample: HomeChat?) {
    val ctx = LocalContext.current
    val prefs = LocalState.current as? Preferences
    val snap by HomeSnapshot.state.collectAsState()
    val hide by HomePrefs.hideTitles.collectAsState()
    val c = GlanceTheme.colors
    val id = sample?.id ?: prefs?.get(ChatWidget.KEY_ID)
    if (id == null) {
        NotChosen()
        return
    }
    val chat = sample ?: snap?.chats?.firstOrNull { it.id == id } ?: HomeChat(
        id = id,
        agent = prefs?.get(ChatWidget.KEY_AGENT) ?: AgentKind.AGY.name,
        title = prefs?.get(ChatWidget.KEY_TITLE)?.takeIf { it.isNotBlank() } ?: L.common.chat
    )
    val openChat = open(ctx, HomeLinks.Target.Chat(chat.agentKind, chat.id))
    val size = LocalSize.current
    val buttons = size.height >= 160.dp
    // what is left for the reply: header 36, gaps 16, status 18, buttons 48, padding 28
    val replySpace: Dp = size.height - 36.dp - 16.dp - 18.dp - 28.dp - (if (buttons) 48.dp else 0.dp)
    val lines = max(1, (replySpace / 18.dp).toInt())

    Column(widgetRoot(GlanceModifier.fillMaxSize()).padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth().clickable(openChat), verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(chat, 36.dp, hide)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Label(shownTitle(chat, hide), 15.sp, c.onSurface, bold = true)
                Label(
                    listOf(HomeIcons.agentLabel(chat.agentKind), chat.project, ago(chat.updatedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                    11.sp, c.onSurfaceVariant
                )
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        if (chat.running || chat.done) StatusLine(chat)
        else Label(L.homescreen.chatWidget.latestReply, 12.sp, c.onSurfaceVariant, bold = true)
        Spacer(GlanceModifier.height(6.dp))
        val text = when {
            hide -> L.homescreen.chatWidget.openTheChatToReadIt
            chat.reply.isNotBlank() -> chat.reply
            else -> L.homescreen.chatWidget.openThisChatInAntigemOnce
        }
        Text(
            text,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().clickable(openChat),
            style = TextStyle(color = if (chat.reply.isBlank() || hide) c.onSurfaceVariant else c.onSurface, fontSize = 13.sp),
            maxLines = lines
        )
        if (buttons) {
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Pill(L.homescreen.chatWidget.reply, R.drawable.ic_home_reply, c.primary, c.onPrimary,
                    open(ctx, HomeLinks.Target.Reply(chat.agentKind, chat.id)), GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(8.dp))
                Pill(L.common.open, R.drawable.ic_home_open, cardColor(), c.onSurface, openChat, GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun Pill(text: String, glyph: Int, bg: ColorProvider, fg: ColorProvider, action: Action, modifier: GlanceModifier) {
    Row(
        modifier.height(40.dp).cornerRadius(20.dp).background(bg).clickable(action),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(glyph, 16.dp, fg)
        Spacer(GlanceModifier.width(6.dp))
        Label(text, 13.sp, fg, bold = true)
    }
}

/** No chat picked yet (placed without its setup screen): a tap opens it. */
@Composable
private fun NotChosen() {
    val ctx = LocalContext.current
    val c = GlanceTheme.colors
    val glanceId = LocalGlanceId.current
    val widgetId = runCatching { GlanceAppWidgetManager(ctx).getAppWidgetId(glanceId) }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
    val pick = actionStartActivity(
        Intent(ctx, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(android.net.Uri.parse("antigem-widget://config/$widgetId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    Column(
        widgetRoot(GlanceModifier.fillMaxSize()).padding(14.dp).clickable(pick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlyphDisc(44.dp, c.primaryContainer, R.drawable.ic_home_chat, c.onPrimaryContainer)
        Spacer(GlanceModifier.height(8.dp))
        Text(L.homescreen.chatWidget.tapToChooseAChat, style = TextStyle(color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 2)
    }
}
