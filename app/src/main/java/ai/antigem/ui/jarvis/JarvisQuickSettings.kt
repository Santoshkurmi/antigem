package ai.antigem.ui.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.jarvis.JarvisController
import ai.antigem.data.jarvis.JarvisPreferences
import ai.antigem.i18n.L

/** The switches that can sit in the Jarvis panel's quick row (pinned in the quick settings sheet). */
enum class QuickSwitch(val id: String, private val titleText: () -> String) {
    HANDS_FREE("handsfree", { L.jarvis.quickSettings.handsFree }),
    VOICE("voice", { L.common.voice }),
    SEND("send", { L.common.send }),
    PAUSE("pause", { L.jarvis.quickSettings.pauseToSend }),
    ANSWERS("answers", { L.jarvis.quickSettings.answers }),
    LISTEN("listen", { L.jarvis.quickSettings.listenAfter }),
    WAKE("wake", { L.jarvis.quickSettings.wake }),
    SCREEN("screen", { L.jarvis.quickSettings.screen }),
    SPEED("speed", { L.common.speed }),
    BUSY("busy", { L.jarvis.quickSettings.whileItWorks });
    val title: String get() = titleText()

    companion object {
        fun pinned(raw: String): List<QuickSwitch> = raw.split(',').mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }.distinct()
    }
}

private fun nextOf(values: List<Int>, cur: Int): Int = values.firstOrNull { it > cur } ?: values.first()

/** One quick switch as a chip: shows its value, a tap toggles it or steps to the next value. */
@Composable
fun QuickSwitchChip(q: QuickSwitch) {
    val P = JarvisPreferences
    when (q) {
        QuickSwitch.HANDS_FREE -> {
            val on by P.handsFree.state.collectAsState()
            JarvisQuickChip(q.title, if (on) L.common.on else L.common.off, on) { JarvisController.setHandsFree(!on) }
        }
        QuickSwitch.VOICE -> {
            val quiet by P.quietMode.state.collectAsState()
            JarvisQuickChip(q.title, if (quiet) L.jarvis.quickSettings.quiet else L.jarvis.quickSettings.speaking, !quiet) { P.quietMode.set(!quiet) }
        }
        QuickSwitch.SEND -> {
            val auto by P.autoSend.state.collectAsState()
            JarvisQuickChip(q.title, if (auto) L.jarvis.quickSettings.autoOnPause else L.common.manual, auto) { P.autoSend.set(!auto) }
        }
        QuickSwitch.PAUSE -> {
            val ms by P.silenceMs.state.collectAsState()
            val auto by P.autoSend.state.collectAsState()
            JarvisQuickChip(q.title, "%.1f s".format(ms / 1000f), auto) { P.silenceMs.set(nextOf(listOf(1000, 1500, 2000, 3000, 4000), ms)) }
        }
        QuickSwitch.ANSWERS -> {
            val a by P.answerSend.state.collectAsState()
            JarvisQuickChip(q.title, if (a == "auto") L.jarvis.quickSettings.sendOnPause else L.jarvis.quickSettings.likeSend, a == "auto") { P.answerSend.set(if (a == "auto") "same" else "auto") }
        }
        QuickSwitch.LISTEN -> {
            val l by P.listenAfter.state.collectAsState()
            val order = listOf("auto", "always", "never")
            JarvisQuickChip(q.title, when (l) { "always" -> L.common.always; "never" -> L.common.never; else -> L.jarvis.quickSettings.whenAsked }, l != "never") {
                P.listenAfter.set(order[(order.indexOf(l) + 1).mod(order.size)])
            }
        }
        QuickSwitch.WAKE -> {
            val h by P.hotword.state.collectAsState()
            JarvisQuickChip(q.title, if (h == "phrase") L.jarvis.quickSettings.phrase else L.jarvis.quickSettings.button, h == "phrase") { P.hotword.set(if (h == "phrase") "push" else "phrase") }
        }
        QuickSwitch.SCREEN -> {
            val s by P.keepScreenOn.state.collectAsState()
            val order = listOf("always", "active", "off")
            JarvisQuickChip(q.title, when (s) { "off" -> L.jarvis.quickSettings.canSleep; "active" -> L.jarvis.quickSettings.onWhileActive; else -> L.jarvis.quickSettings.alwaysOn }, s != "off") {
                P.keepScreenOn.set(order[(order.indexOf(s) + 1).mod(order.size)])
            }
        }
        QuickSwitch.SPEED -> {
            val r by P.ttsRate.state.collectAsState()
            val steps = listOf(80, 100, 120, 150)
            JarvisQuickChip(q.title, "%.1f×".format(r), r != 1f) { P.ttsRate.set(nextOf(steps, Math.round(r * 100)) / 100f) }
        }
        QuickSwitch.BUSY -> {
            val b by P.busyBehavior.state.collectAsState()
            val order = listOf("queue", "interrupt", "smart")
            JarvisQuickChip(q.title, when (b) { "interrupt" -> L.jarvis.quickSettings.interrupt; "smart" -> L.jarvis.quickSettings.myPhrases; else -> L.jarvis.quickSettings.wait }, b != "queue") {
                P.busyBehavior.set(order[(order.indexOf(b) + 1).mod(order.size)])
            }
        }
    }
}

@Composable
fun JarvisQuickChip(title: String, value: String, on: Boolean, onClick: () -> Unit) {
    val c = if (on) Cyan else Color.White.copy(alpha = 0.5f)
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Cyan.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, c.copy(alpha = if (on) 0.5f else 0.2f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp)
        Text(value, color = if (on) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Quick settings (the tune icon on the Jarvis card and in the panel): how sending works and how long the pause is, what
 * happens after Jarvis speaks, the voice, hands-free and what shows in the panel's quick row. Changes apply at once,
 * from the next time the microphone opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JarvisQuickSettings(onClose: () -> Unit, floating: Boolean = false) {
    BackHandler(onBack = onClose)
    val P = JarvisPreferences
    val auto by P.autoSend.state.collectAsState()
    val silence by P.silenceMs.state.collectAsState()
    val sendMode by P.sendMode.state.collectAsState()
    val answers by P.answerSend.state.collectAsState()
    val listenAfter by P.listenAfter.state.collectAsState()
    val follow by P.followUpSeconds.state.collectAsState()
    val quiet by P.quietMode.state.collectAsState()
    val rate by P.ttsRate.state.collectAsState()
    val handsFree by P.handsFree.state.collectAsState()
    val hotword by P.hotword.state.collectAsState()
    val screen by P.keepScreenOn.state.collectAsState()
    val busy by P.busyBehavior.state.collectAsState()
    val pinsRaw by P.quickPins.state.collectAsState()
    val pins = remember(pinsRaw) { QuickSwitch.pinned(pinsRaw) }

    Box(
        // floating: it fills its own window over other apps (the floating orb's sheet), without the dimmed backdrop
        if (floating) Modifier.fillMaxSize()
        else Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // tap outside closes (not a clickable: that would merge the sheet into one TalkBack item; Close is in the header)
            .pointerInput(onClose) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .fillMaxHeight(if (floating) 1f else 0.8f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFA111A2C), Color(0xFC070B14))))
                .border(1.dp, Brush.verticalGradient(listOf(Cyan.copy(alpha = 0.5f), Color.Transparent)), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                // taps inside the sheet stay inside (not a clickable: that would make the whole sheet one TalkBack item)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                JarvisBotIcon(28.dp, Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.common.quickSettings, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(L.jarvis.quickSettings.appliesFromTheNextTimeThe, color = Color.White.copy(alpha = 0.5f), fontSize = 11.5.sp)
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, L.common.close, tint = Color.White.copy(alpha = 0.7f))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                QsTitle(L.jarvis.quickSettings.sending)
                QsChoice(listOf("manual" to L.jarvis.quickSettings.manualSendPhrase, "auto" to L.jarvis.quickSettings.autoWhenIPause), if (auto) "auto" else "manual") { P.autoSend.set(it == "auto") }
                QsHint(if (auto) L.jarvis.quickSettings.whatYouSayIsSentAfter
                else L.jarvis.quickSettings.nothingIsSentWhileYouThink)
                QsSlider(L.jarvis.quickSettings.pauseBeforeSending, silence / 1000f, 0.6f..4f, "%.1f s".format(silence / 1000f)) { P.silenceMs.set((it * 1000).toInt()) }
                QsChoice(listOf("smart" to L.jarvis.quickSettings.smartPause, "pause" to L.jarvis.quickSettings.exactPause), if (sendMode == "pause") "pause" else "smart") { P.sendMode.set(it) }
                QsHint(if (sendMode == "pause") L.jarvis.quickSettings.alwaysExactlyThisPause else L.jarvis.quickSettings.longerWhenYouSoundUnfinishedAnd)
                Text(L.jarvis.quickSettings.answersToJarvisQuestions, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                QsChoice(listOf("same" to L.jarvis.quickSettings.likeEverythingElse, "auto" to L.jarvis.quickSettings.sendOnPause), answers) { P.answerSend.set(it) }

                QsTitle(L.jarvis.quickSettings.afterJarvisSpeaks)
                QsChoice(listOf("auto" to L.jarvis.quickSettings.listenWhenItAsks, "always" to L.jarvis.quickSettings.alwaysListen, "never" to L.jarvis.quickSettings.dontListen), listenAfter) { P.listenAfter.set(it) }
                if (listenAfter != "never") QsSlider(L.jarvis.quickSettings.waitForMyReply, follow.toFloat(), 3f..20f, "$follow s") { P.followUpSeconds.set(it.toInt()) }

                QsTitle(L.common.voice)
                QsChoice(listOf("speak" to L.jarvis.quickSettings.speaking, "quiet" to L.jarvis.quickSettings.quietShowText), if (quiet) "quiet" else "speak") { P.quietMode.set(it == "quiet") }
                QsSlider(L.common.speed, rate, 0.5f..2f, "%.1f×".format(rate)) { P.ttsRate.set((Math.round(it * 20) / 20f)) }

                QsTitle(L.jarvis.quickSettings.handsFree)
                QsChoice(listOf("off" to L.common.off, "on" to L.jarvis.quickSettings.onMicStaysReady), if (handsFree) "on" else "off") { JarvisController.setHandsFree(it == "on") }
                QsChoice(listOf("push" to L.jarvis.quickSettings.wakeWithButton, "phrase" to L.jarvis.quickSettings.wakePhrase), if (hotword == "phrase") "phrase" else "push") { P.hotword.set(it) }
                QsChoice(listOf("always" to L.jarvis.quickSettings.screenAlwaysOn, "active" to L.jarvis.quickSettings.onWhileActive, "off" to L.jarvis.quickSettings.canSleep), screen) { P.keepScreenOn.set(it) }

                QsTitle(L.jarvis.quickSettings.whenITalkWhileJarvisWorks)
                QsChoice(listOf("queue" to L.jarvis.quickSettings.waitForIt, "interrupt" to L.jarvis.quickSettings.interruptIt, "smart" to L.jarvis.quickSettings.onlyMyInterruptPhrases), busy) { P.busyBehavior.set(it) }

                QsTitle(L.jarvis.quickSettings.quickRowInThePanel)
                QsHint(L.jarvis.quickSettings.tapToShowOrHideA)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickSwitch.entries.forEach { q ->
                        val on = q in pins
                        Text(
                            (if (on) "✓ " else "+ ") + q.title,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Cyan.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f))
                                .border(1.dp, if (on) Cyan.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                                // a checkbox for TalkBack: its name without the "✓ " / "+ " mark
                                .semantics { contentDescription = q.title }
                                .toggleable(value = on, role = Role.Checkbox) {
                                    val next = if (on) pins - q else pins + q
                                    P.quickPins.set(next.joinToString(",") { it.id })
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QsLink(L.jarvis.quickSettings.phrasesShortcuts) { onClose(); JarvisController.requestSettings("JARVIS_LISTEN") }
                    QsLink(L.jarvis.quickSettings.allJarvisSettings) { onClose(); JarvisController.requestSettings("JARVIS") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun QsTitle(text: String) {
    Text(
        text.uppercase(), color = Cyan.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() }
    )
}

@Composable
private fun QsHint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QsChoice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp).selectableGroup()) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label, color = if (on) Color.White else Color.White.copy(alpha = 0.65f), fontSize = 12.5.sp,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Cyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
                    .border(1.dp, if (on) Cyan.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun QsSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        // the slider below says both
        Row(Modifier.clearAndSetSemantics { }) {
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(shown, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
            modifier = Modifier.semantics { contentDescription = label; stateDescription = shown },
            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Color.White.copy(alpha = 0.15f))
        )
    }
}

@Composable
private fun QsLink(label: String, onClick: () -> Unit) {
    Text(
        label, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(50)).clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
