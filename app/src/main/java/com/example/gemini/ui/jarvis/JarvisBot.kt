package com.example.gemini.ui.jarvis

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Jarvis' face: a small robot head (antenna, ears, a glass visor with two glowing eyes and a mouth), so the orb and every
 * Jarvis mark read as "a bot" at a glance. [eyeOpen] 0..1 (blink), [lookX] -1..1 (eyes glance sideways while thinking),
 * [mouth] 0..1 (opens with the voice while speaking), [eyeGrow] makes the eyes bigger (listening).
 */
fun DrawScope.drawJarvisBot(
    center: Offset,
    box: Float,
    color: Color,
    eyeOpen: Float = 1f,
    lookX: Float = 0f,
    mouth: Float = 0f,
    eyeGrow: Float = 1f,
    visor: Color = Color(0xFF07101C)
) {
    val hw = box * 0.70f
    val hh = box * 0.52f
    val headTop = center.y - hh / 2 + box * 0.07f
    val headLeft = center.x - hw / 2
    val stroke = (box * 0.055f).coerceAtLeast(1.2f)
    val r = hh * 0.36f

    // antenna with a glowing tip
    val tipY = headTop - box * 0.17f
    drawLine(color.copy(alpha = 0.9f), Offset(center.x, headTop), Offset(center.x, tipY + box * 0.05f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawCircle(color.copy(alpha = 0.28f), radius = box * 0.10f, center = Offset(center.x, tipY))
    drawCircle(color, radius = box * 0.055f, center = Offset(center.x, tipY))

    // ears
    val earW = box * 0.075f
    val earH = hh * 0.44f
    val earTop = headTop + (hh - earH) / 2
    drawRoundRect(color.copy(alpha = 0.85f), Offset(headLeft - earW * 0.85f, earTop), Size(earW, earH), CornerRadius(earW / 2))
    drawRoundRect(color.copy(alpha = 0.85f), Offset(headLeft + hw - earW * 0.15f, earTop), Size(earW, earH), CornerRadius(earW / 2))

    // head and visor
    drawRoundRect(color.copy(alpha = 0.16f), Offset(headLeft, headTop), Size(hw, hh), CornerRadius(r))
    drawRoundRect(color, Offset(headLeft, headTop), Size(hw, hh), CornerRadius(r), style = Stroke(width = stroke))
    val inset = box * 0.085f
    drawRoundRect(
        visor.copy(alpha = 0.9f), Offset(headLeft + inset, headTop + inset), Size(hw - inset * 2, hh - inset * 2),
        CornerRadius((r - inset * 0.6f).coerceAtLeast(1f))
    )

    // eyes (pills that blink, glance and grow)
    val eyeCy = headTop + hh * 0.44f
    val eyeDx = hw * 0.19f
    val eyeW = box * 0.105f * eyeGrow
    val eyeH = (box * 0.15f * eyeGrow * eyeOpen.coerceIn(0.08f, 1f))
    val shift = lookX.coerceIn(-1f, 1f) * hw * 0.07f
    for (side in listOf(-1f, 1f)) {
        val cx = center.x + side * eyeDx + shift
        drawCircle(color.copy(alpha = 0.22f), radius = eyeW * 1.15f, center = Offset(cx, eyeCy))
        drawRoundRect(color, Offset(cx - eyeW / 2, eyeCy - eyeH / 2), Size(eyeW, eyeH), CornerRadius(eyeW / 2))
        if (eyeOpen > 0.5f) drawCircle(Color.White.copy(alpha = 0.85f), radius = eyeW * 0.2f, center = Offset(cx - eyeW * 0.12f, eyeCy - eyeH * 0.2f))
    }

    // mouth: a soft line, a speaking bar when it talks
    val mouthW = hw * 0.26f
    val mouthY = headTop + hh * 0.74f
    val open = mouth.coerceIn(0f, 1f)
    if (open < 0.05f) {
        drawLine(color.copy(alpha = 0.8f), Offset(center.x - mouthW / 2, mouthY), Offset(center.x + mouthW / 2, mouthY), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
    } else {
        val mh = box * (0.03f + open * 0.08f)
        drawRoundRect(color, Offset(center.x - mouthW / 2, mouthY - mh / 2), Size(mouthW, mh), CornerRadius(mh / 2))
    }
}

/** The Jarvis mark for lists, headers and cards: the robot face (it blinks now and then when [animated]). */
@Composable
fun JarvisBotIcon(size: Dp, color: Color = Cyan, modifier: Modifier = Modifier, animated: Boolean = false, visor: Color = Color(0xFF07101C)) {
    if (!animated) {
        Canvas(modifier.size(size)) { drawJarvisBot(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension, color, visor = visor) }
        return
    }
    val t = rememberInfiniteTransition(label = "bot")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 3800; 1f at 3500; 0.1f at 3620; 1f at 3760 }),
        label = "blink"
    )
    val look by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "look")
    Canvas(modifier.size(size)) {
        drawJarvisBot(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension, color, eyeOpen = blink, lookX = look * 0.6f, visor = visor)
    }
}

/** The bot on a round glass badge (headers, the event card). */
@Composable
fun JarvisBotBadge(size: Dp, color: Color = Cyan, animated: Boolean = false) {
    androidx.compose.foundation.layout.Box(Modifier.size(size), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r = this.size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0xFF16233A), Color(0xFF0A0F1C)), c, r), radius = r, center = c)
            drawCircle(color.copy(alpha = 0.55f), radius = r * 0.94f, center = c, style = Stroke(width = r * 0.07f))
        }
        JarvisBotIcon(size * 0.74f, color, Modifier, animated)
    }
}
