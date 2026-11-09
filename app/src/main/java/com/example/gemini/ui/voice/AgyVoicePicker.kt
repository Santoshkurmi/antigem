package com.example.gemini.ui.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.voice.AgyVoiceCall
import com.example.gemini.data.voice.AgyVoicePreferences
import com.example.gemini.data.voice.AgyVoicePreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

private fun rgbColor(v: Int) = Color(0xFF000000.toInt() or v)

/**
 * The voice picker (as in the Antigravity desktop app): one big orb per voice; swipe to hear each one, then use it.
 * Shown before the first voice call, and from the call's Voice panel. [actionLabel] names the button for a voice.
 */
@Composable
fun AgyVoicePickerDialog(
    initialVoice: String,
    actionLabel: (String) -> String = { "Use $it" },
    subtitle: String = "Swipe to hear each voice. You can change it during a call too.",
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        PickerContent(initialVoice, actionLabel, subtitle, onChoose, onDismiss)
    }
}

@Composable
private fun PickerContent(
    initialVoice: String,
    actionLabel: (String) -> String,
    subtitle: String,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val voices = AgyVoicePreferences.voices
    val pager = rememberPagerState(initialPage = voices.indexOfFirst { it.name == initialVoice }.coerceAtLeast(0)) { voices.size }
    val scope = rememberCoroutineScope()
    val playing by AgyVoicePreview.playing.collectAsState()
    val loading by AgyVoicePreview.loading.collectAsState()
    val current = voices[pager.currentPage]

    // landing on a voice plays it
    LaunchedEffect(pager.settledPage) {
        delay(220)
        AgyVoicePreview.play(context, voices[pager.settledPage].name)
    }
    DisposableEffect(Unit) { onDispose { AgyVoicePreview.stop() } }

    // a speaking-like level for the orb while its sample plays
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }

    val deep by animateColorAsState(rgbColor(current.deep), tween(450), label = "deep")

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(deep, Color(0xFF07080C), Color(0xFF07080C))))
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White.copy(alpha = 0.8f)) }
            }
            Text(
                "Choose a voice", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.5.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = 56.dp),
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val v = voices[page]
                    val offset = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    val speaking = playing == v.name
                    val level = if (speaking) 0.22f + 0.38f * abs(sin(time * 7.3f)) * (0.55f + 0.45f * abs(sin(time * 2.1f + page))) else 0f
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            val s = lerp(1f, 0.78f, offset)
                            scaleX = s; scaleY = s
                            alpha = lerp(1f, 0.45f, offset)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        OrbCanvas(
                            voice = v, phase = AgyVoiceCall.Phase.LIVE, micLevel = 0f, voiceLevel = level,
                            modifier = Modifier.size(280.dp)
                        )
                        Text(v.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(v.tone, color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            onClick = { AgyVoicePreview.play(context, v.name) },
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.10f)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (loading == v.name) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                                else Icon(Icons.Rounded.Replay, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (speaking) "Playing…" else "Play again", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
                // arrows for those who prefer tapping
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                        enabled = pager.currentPage > 0
                    ) { Icon(Icons.Rounded.ChevronLeft, "Previous voice", tint = Color.White.copy(alpha = if (pager.currentPage > 0) 0.7f else 0.2f)) }
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(voices.lastIndex)) } },
                        enabled = pager.currentPage < voices.lastIndex
                    ) { Icon(Icons.Rounded.ChevronRight, "Next voice", tint = Color.White.copy(alpha = if (pager.currentPage < voices.lastIndex) 0.7f else 0.2f)) }
                }
            }

            // page dots
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                voices.forEachIndexed { i, v ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (on) 9.dp else 6.dp).clip(CircleShape)
                            .background(if (on) rgbColor(v.core) else Color.White.copy(alpha = 0.25f))
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                onClick = {
                    AgyVoicePreview.stop()
                    onChoose(current.name)
                },
                shape = RoundedCornerShape(50),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(actionLabel(current.name), color = Color(0xFF07080C), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
