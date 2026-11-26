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
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.LocalState
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import ai.antigem.data.homescreen.HomeAction
import ai.antigem.data.homescreen.HomeData
import ai.antigem.data.homescreen.HomeIcons
import ai.antigem.data.homescreen.HomeSnapshot
import ai.antigem.domain.model.AgentKind
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The Quick actions widget: the buttons picked in its setup screen (long-press the widget → its settings to change
 * them), as many as fit, in rows. "New chat" takes the color of the new-chat agent.
 */
class ActionsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        HomeSnapshot.load(context)
        provideContent { HomeWidgetTheme { ActionsContent() } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { HomeWidgetTheme { ActionsContent() } }
    }
}

class ActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActionsWidget()
}

/** The buttons saved in a Quick actions widget's state ("" or none: the defaults). */
internal fun actionsOf(prefs: Preferences?): List<HomeAction> =
    prefs?.get(WidgetKeys.ACTIONS)?.split(',')?.mapNotNull { HomeAction.byId(it.trim()) }?.takeIf { it.isNotEmpty() }
        ?: HomeAction.DEFAULT_WIDGET

@Composable
private fun ActionsContent() {
    val prefs = LocalState.current as? Preferences
    val actions = actionsOf(prefs)
    val snap by HomeSnapshot.widgetState.collectAsState()
    val size = LocalSize.current
    val pad = 8.dp
    val labels = prefs?.get(WidgetKeys.LABELS) != "0" && size.height >= 76.dp
    val cellW = 62.dp
    val cellH = if (labels) 74.dp else 56.dp
    val fitCols = max(1, ((size.width - pad * 2) / cellW).toInt())
    val fitRows = max(1, ((size.height - pad * 2) / cellH).toInt())
    val shown = actions.take(fitCols * fitRows)
    // the buttons spread over the rows they need, evenly (7 buttons where 5 fit a row: 4 + 3)
    val rowsNeeded = max(1, ceil(shown.size / fitCols.toFloat()).toInt())
    val cols = min(fitCols, max(1, ceil(shown.size / rowsNeeded.toFloat()).toInt()))

    Column(
        widgetRoot(GlanceModifier.fillMaxSize()).padding(pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        shown.chunked(cols).forEach { row ->
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { a -> ActionCell(a, labels, snap, GlanceModifier.defaultWeight()) }
                repeat(cols - row.size) { Spacer(GlanceModifier.defaultWeight()) }
            }
        }
    }
}

@Composable
private fun ActionCell(action: HomeAction, label: Boolean, data: HomeData?, modifier: GlanceModifier) {
    val ctx = LocalContext.current
    val color = if (action == HomeAction.NEW_CHAT) {
        data?.defaultAgent?.let { d -> AgentKind.entries.firstOrNull { it.name == d } }?.let { HomeIcons.agentColor(it).toLong() and 0xFFFFFFFFL }
            ?: action.color
    } else action.color
    Column(
        modifier.clickable(open(ctx, action.target)).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphDisc(46.dp, fixed(color), action.glyph)
        if (label) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                action.label,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp, textAlign = TextAlign.Center),
                maxLines = 1
            )
        }
    }
}
