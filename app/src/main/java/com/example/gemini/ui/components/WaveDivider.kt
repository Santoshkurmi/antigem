package com.example.gemini.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A label between two muted wavy lines (as in the Claude Code VS Code extension): a new day, or a model switch.
 * Static: drawn once, no animation.
 */
@Composable
fun WaveDivider(label: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Wave(color, Modifier.weight(1f))
        Text(
            text = label,
            fontSize = 12.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp)
        )
        Wave(color, Modifier.weight(1f))
    }
}

/** One smooth curve repeated across the width: 14 x 7 dp per wave, 1 dp line. */
@Composable
private fun Wave(color: Color, modifier: Modifier) {
    Canvas(modifier.widthIn(min = 32.dp).height(7.dp)) {
        val period = 14.dp.toPx()
        val mid = size.height / 2f
        val half = period / 2f
        val path = Path().apply {
            moveTo(0f, mid)
            var x = 0f
            var up = true
            while (x < size.width) {
                // a half wave: up to the top edge, then (next one) down to the bottom edge
                quadraticTo(x + half / 2f, if (up) 0f else size.height, x + half, mid)
                x += half
                up = !up
            }
        }
        // the last wave may run past the end: keep it out of the label's gap
        clipRect { drawPath(path, color, style = Stroke(width = 1.dp.toPx())) }
    }
}
