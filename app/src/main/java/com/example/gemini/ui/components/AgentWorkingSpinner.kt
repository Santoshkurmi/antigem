package com.example.gemini.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// The "working" row of the Claude Code VS Code extension: a pulsing star glyph and a playful verb that is "decoded"
// in with a running cursor. Same glyphs, verbs and timings as the extension.

private val GLYPHS = listOf("·", "✢", "*", "✶", "✻", "✽").let { it + it.reversed() }

private val VERBS = listOf(
    "Accomplishing", "Actioning", "Actualizing", "Baking", "Booping", "Brewing", "Calculating", "Cerebrating",
    "Channeling", "Churning", "Clauding", "Coalescing", "Cogitating", "Computing", "Combobulating", "Concocting",
    "Considering", "Contemplating", "Cooking", "Crafting", "Creating", "Crunching", "Deciphering", "Deliberating",
    "Determining", "Discombobulating", "Doing", "Effecting", "Elucidating", "Enchanting", "Envisioning", "Finagling",
    "Flibbertigibbeting", "Forging", "Forming", "Frolicking", "Generating", "Germinating", "Hatching", "Herding",
    "Honking", "Ideating", "Imagining", "Incubating", "Inferring", "Manifesting", "Marinating", "Meandering",
    "Moseying", "Mulling", "Mustering", "Musing", "Noodling", "Percolating", "Perusing", "Philosophizing",
    "Pontificating", "Pondering", "Processing", "Puttering", "Puzzling", "Reticulating", "Ruminating", "Schlepping",
    "Shimmying", "Simmering", "Smooshing", "Spelunking", "Spinning", "Stewing", "Sussing", "Synthesizing", "Thinking",
    "Tinkering", "Transmuting", "Unfurling", "Unraveling", "Vibing", "Wandering", "Whirring", "Wibbling", "Working",
    "Wrangling"
)

/** Room for the longest verb plus "...": the row never changes width. */
private val TEXT_WIDTH = VERBS.maxOf { it.length } + 3

private const val GLYPH_STEP_MS = 120L
private const val DECODE_FRAME_MS = 40L
/** The verb changes after 2 s, then 3 s, then every 5 s. */
private val WORD_DELAYS_MS = longArrayOf(2_000, 3_000)
private const val WORD_DELAY_MS = 5_000L

/**
 * The agent is working.
 *
 * Built so a tick costs as little as possible: the row has one fixed size (tallest glyph, widest verb), so it
 * never re-lays out the chat around it; the animation state is read only while drawing (no recomposition or
 * layout per tick); and it draws in its own graphics layer, so only this row is repainted. Glyphs and text sit
 * on fixed baselines, so nothing moves up and down when a glyph or character comes from another font.
 */
@Composable
fun AgentWorkingSpinner(color: Color, modifier: Modifier = Modifier, compacting: Boolean = false) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    // a quiet status line (like VS Code's softened foreground); the accent-colored star is what draws the eye
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val glyphStyle = remember(color) { TextStyle(color = color, fontSize = 21.sp, fontFamily = FontFamily.Monospace) }
    val wordStyle = remember(textColor) { TextStyle(color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
    val density = LocalDensity.current

    // measured once: every glyph, and the widest text the row can show
    val glyphLayouts = remember(measurer, glyphStyle) { GLYPHS.map { measurer.measure(it, glyphStyle) } }
    val wordRef = remember(measurer, wordStyle) { measurer.measure("Compacting...", wordStyle) }
    val wordWidth = remember(measurer, wordStyle) {
        (VERBS + "Compacting").maxOf { measurer.measure("$it...", wordStyle).size.width } + measurer.measure("▌", wordStyle).size.width
    }
    val glyphBox = remember(glyphLayouts) { glyphLayouts.maxOf { it.size.width } }
    val gapPx = with(density) { 4.dp.roundToPx() }
    val heightPx = maxOf(glyphLayouts.maxOf { it.size.height }, wordRef.size.height)
    val glyphBaseline = (heightPx - glyphLayouts[GLYPHS.indexOf("✻")].size.height) / 2f + glyphLayouts[GLYPHS.indexOf("✻")].firstBaseline
    val wordBaseline = (heightPx - wordRef.size.height) / 2f + wordRef.firstBaseline

    val glyphStep = remember { mutableIntStateOf(0) }
    val shown = remember { mutableStateOf(" ".repeat(TEXT_WIDTH)) }
    GlyphTicker(glyphStep)
    WordDecoder(shown, compacting)

    Canvas(
        modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .size(with(density) { (glyphBox + gapPx + wordWidth).toDp() }, with(density) { heightPx.toDp() })
            .graphicsLayer()
    ) {
        // state is read here only: a tick redraws this layer and nothing else
        val glyph = glyphLayouts[glyphStep.intValue]
        drawText(glyph, topLeft = Offset((glyphBox - glyph.size.width) / 2f, glyphBaseline - glyph.firstBaseline))
        val word = measurer.measure(shown.value, wordStyle, softWrap = false, maxLines = 1)
        drawText(word, topLeft = Offset((glyphBox + gapPx).toFloat(), wordBaseline - word.firstBaseline))
    }
}

@Composable
private fun GlyphTicker(step: androidx.compose.runtime.MutableIntState) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(GLYPH_STEP_MS)
            step.intValue = (step.intValue + 1) % GLYPHS.size
        }
    }
}

@Composable
private fun WordDecoder(shown: androidx.compose.runtime.MutableState<String>, compacting: Boolean) {
    var verb by remember { mutableStateOf(VERBS.random()) }
    LaunchedEffect(Unit) {
        var changes = 0
        while (true) {
            delay(WORD_DELAYS_MS.getOrElse(changes) { WORD_DELAY_MS })
            changes++
            verb = VERBS.random()
        }
    }
    // A cursor runs over the old text; the three characters behind it flicker through '.' and '_' before settling.
    LaunchedEffect(verb, compacting) {
        val target = ((if (compacting) "Compacting" else verb) + "...").padEnd(TEXT_WIDTH)
        var cursor = 0
        while (cursor - 3 < target.length) {
            val next = StringBuilder(shown.value)
            for (behind in 0..3) {
                val k = cursor - behind
                if (k < 0 || k >= target.length) continue
                val want = target[k]
                next.setCharAt(
                    k,
                    when {
                        want == ' ' -> ' '
                        behind == 3 -> want
                        behind == 0 -> '▌'
                        else -> charArrayOf('.', '_', want).random()
                    }
                )
            }
            shown.value = next.toString()
            cursor++
            delay(DECODE_FRAME_MS)
        }
    }
}
