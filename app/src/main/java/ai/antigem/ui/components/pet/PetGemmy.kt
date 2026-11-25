package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin

/** Gemmy: the gumdrop gem buddy with a star antenna. Signature move: "Sparkle" (the star flares and showers sparkles). */
internal class GemmyArt(d: Float, palette: Int) : PetArt(d) {
    private val body: List<Color>
    private val limb: Color
    private val ink: Color
    private val gem: Color

    init {
        when (palette) {
            1 -> { body = listOf(Color(0xFFD2FFEE), Color(0xFF6FE3B8), Color(0xFF22A884)); limb = Color(0xFF1E9A78); ink = Color(0xFF1C4A3E); gem = Color(0xFFFFD45E) }
            2 -> { body = listOf(Color(0xFFFFDCC8), Color(0xFFFF9C7A), Color(0xFFF0607A)); limb = Color(0xFFE0566E); ink = Color(0xFF5A2236); gem = Color(0xFFFFE27A) }
            3 -> { body = listOf(Color(0xFF9AA6E6), Color(0xFF4B4F9E), Color(0xFF2A2B66)); limb = Color(0xFF3A3C88); ink = Color(0xFF14153A); gem = Color(0xFF7FF3FF) }
            else -> { body = listOf(Color(0xFFC9BCFF), Color(0xFF8F7DFF), Color(0xFF5B57E0)); limb = Color(0xFF5B52D6); ink = Color(0xFF2E2A6B); gem = Color(0xFFFFD45E) }
        }
    }

    override val face = FaceSpec(ink = ink)
    override val starColor = gem

    /** Gumdrop body, centered on (0, 0). */
    private val bodyPath = Path().apply {
        moveTo(0f, -1.05f * r)
        cubicTo(0.62f * r, -1.05f * r, 1.02f * r, -0.45f * r, 1.02f * r, 0.15f * r)
        cubicTo(1.02f * r, 0.7f * r, 0.6f * r, 0.98f * r, 0f, 0.98f * r)
        cubicTo(-0.6f * r, 0.98f * r, -1.02f * r, 0.7f * r, -1.02f * r, 0.15f * r)
        cubicTo(-1.02f * r, -0.45f * r, -0.62f * r, -1.05f * r, 0f, -1.05f * r)
        close()
    }
    private val bodyBrush = Brush.verticalGradient(colors = body, startY = -r, endY = r)
    private val antennaPath = Path()

    // ---- signature move: Sparkle ----

    override val specialDur = 1.6f

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 1.6f)
        s.jump(240f)
    }

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        if (crossed(prev, t, 0.35f)) {
            s.light()
            for (i in 0 until 8) {
                val a = i * PI_F / 4f
                s.fx(FX_GLINT, s.x, s.y - s.r * 1.7f + s.hop, cos(a) * 170f, sin(a) * 170f, 0.8f, 5f, drag = 2.5f, spin = 240f)
            }
        }
        if (crossed(prev, t, 0.9f)) s.burst(P_SPARKLE, 6)
    }

    override fun crumbs(s: PetSim) {
        s.fxBurst(FX_GLINT, 4, s.x, s.y + s.hop, 90f, 0.5f, 3f)
    }

    override fun DrawScope.drawTreat() {
        drawPath(star, Color(0xFFFFC85A))
        drawPath(star, ink, style = unitOutline)
        drawCircle(Color(0xFF9A5A1E), radius = 0.09f, center = Offset(-0.15f, -0.05f))
        drawCircle(Color(0xFF9A5A1E), radius = 0.08f, center = Offset(0.18f, 0.12f))
    }

    // ---- drawing ----

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        drawFeet(s, this@GemmyArt, limb, ink)
        drawArms(s, this@GemmyArt, mood, limb, ink, extra = if (s.trick == Trick.SPECIAL) -70f * sin(s.trickP() * PI_F) else 0f)
        drawPath(bodyPath, bodyBrush)
        if (mood == Mood.ANGRY) drawPath(bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.28f))
        drawOval(Color.White.copy(alpha = 0.16f), topLeft = Offset(-0.55f * r, 0.18f * r), size = Size(1.1f * r, 0.66f * r))
        drawPath(bodyPath, ink, style = outlineStroke)
        drawOval(Color.White.copy(alpha = 0.6f), topLeft = Offset(-0.68f * r, -0.82f * r), size = Size(0.36f * r, 0.2f * r))
        drawAntenna(s, mood)
        drawFace(s, this@GemmyArt, mood)
    }

    private fun DrawScope.drawAntenna(s: PetSim, mood: Mood) {
        val t = s.animT
        val baseY = -1.02f * r
        val len = 0.62f * r
        val ang = s.antenna.v * (PI_F / 180f)
        val tipX = sin(ang) * len
        val tipY = baseY - cos(ang) * len
        antennaPath.reset()
        antennaPath.moveTo(0f, baseY)
        antennaPath.quadraticTo(sin(ang * 0.4f) * len * 0.5f, baseY - len * 0.55f, tipX, tipY)
        drawPath(antennaPath, ink, style = featureStroke)

        val flare = if (s.trick == Trick.SPECIAL) sin(s.trickP() * PI_F) else 0f
        val pulse = when (mood) {
            Mood.THINKING -> 0.55f + 0.45f * sin(t * 7f)
            Mood.SLEEP -> 0.15f
            else -> 0.35f + 0.1f * sin(t * 2f)
        } + flare * 0.6f
        drawCircle(gem.copy(alpha = (0.35f * pulse).coerceAtMost(0.7f)), radius = 0.42f * r * (0.8f + pulse * 0.4f + flare * 0.5f), center = Offset(tipX, tipY))
        withTransform({
            translate(tipX, tipY)
            rotate(t * 40f + flare * 360f, Offset.Zero)
            scale(0.24f * r * (1f + flare * 0.7f), 0.24f * r * (1f + flare * 0.7f), Offset.Zero)
        }) {
            drawPath(star, gem)
            drawPath(star, ink, style = unitOutline)
        }

        if (mood == Mood.THINKING) {
            for (i in 0..2) {
                val o = t * 3f + i * 2.094f
                drawCircle(Color.White, radius = 0.07f * r, center = Offset(tipX + cos(o) * 0.5f * r, tipY + sin(o) * 0.22f * r))
            }
        }
    }
}
