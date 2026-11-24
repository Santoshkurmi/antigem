package ai.antigem.ui.homescreen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import ai.antigem.R
import ai.antigem.data.homescreen.HomeLinks
import ai.antigem.data.homescreen.HomeScripts
import java.io.File
import ai.antigem.i18n.L

/**
 * The Scripts widget, like Termux:Widget: the files in ~/.shortcuts (each opens a terminal tab running it) and in
 * ~/.shortcuts/tasks (each runs in the background, with a notification when it ends). ⟳ reads the folder again.
 */
class ScriptsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { HomeWidgetTheme { ScriptsContent(sample = null) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = listOf(
            HomeScripts.Script(File("/preview/backup.sh"), false, "backup.sh"),
            HomeScripts.Script(File("/preview/git-pull-all.sh"), false, "git-pull-all.sh"),
            HomeScripts.Script(File("/preview/tasks/sync-notes.sh"), true, "sync-notes.sh")
        )
        provideContent { HomeWidgetTheme { ScriptsContent(sample) } }
    }
}

class ScriptsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScriptsWidget()
}

/** ⟳ on the Scripts widget. */
class RefreshScriptsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HomeScripts.touch()
        ScriptsWidget().update(context, glanceId)
    }
}

@Composable
private fun ScriptsContent(sample: List<HomeScripts.Script>?) {
    val ctx = LocalContext.current
    val tick by HomeScripts.changes.collectAsState()
    val scripts = sample ?: remember(tick) { HomeScripts.list(ctx) }
    val c = GlanceTheme.colors
    Column(widgetRoot(GlanceModifier.fillMaxSize()).padding(10.dp)) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphDisc(30.dp, fixed(0xFF334155), R.drawable.ic_home_terminal)
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Label(L.homescreen.scriptsWidget.scripts, 15.sp, c.onSurface, bold = true)
                Label("~/.shortcuts", 11.sp, c.onSurfaceVariant)
            }
            Box(GlanceModifier.clickable(actionRunCallback<RefreshScriptsAction>())) {
                GlyphDisc(32.dp, cardColor(), R.drawable.ic_home_refresh, c.onSurfaceVariant)
            }
        }
        if (scripts.isEmpty()) {
            Column(
                GlanceModifier.fillMaxSize().clickable(open(ctx, HomeLinks.Target.Settings("HOME_SCREEN"))),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(L.homescreen.scriptsWidget.noScriptsYet, style = TextStyle(color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1)
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    L.homescreen.scriptsWidget.putScriptsInShortcutsATerminal,
                    style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center), maxLines = 3
                )
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(scripts, itemId = { it.file.absolutePath.hashCode().toLong() }) { s -> ScriptRow(s) }
            }
        }
    }
}

@Composable
private fun ScriptRow(s: HomeScripts.Script) {
    val ctx = LocalContext.current
    val c = GlanceTheme.colors
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().cornerRadius(14.dp).background(cardColor())
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(open(ctx, HomeLinks.Target.Script(s.file.absolutePath, s.background))),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlyphDisc(
                30.dp, fixed(if (s.background) 0xFF0F766E else 0xFF334155),
                if (s.background) R.drawable.ic_home_task else R.drawable.ic_home_run
            )
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Label(s.label, 14.sp, c.onSurface, bold = true)
                Label(if (s.background) L.homescreen.scriptsWidget.runsInTheBackground else L.homescreen.scriptsWidget.opensATerminalTab, 11.sp, c.onSurfaceVariant)
            }
        }
    }
}
