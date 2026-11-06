package com.example.gemini.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * iOS-style edge bounce for every scrolling list inside [content] (verticalScroll, LazyColumn, horizontal rows…),
 * in place of Android's stretch: past the start or end the content follows the finger with growing resistance
 * (rubber band), springs back when let go, and a fling that hits the end bounces a little past it and back.
 * Text fields are not affected (they do not use this).
 */
@Composable
fun ProvideBouncyOverscroll(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val factory = remember(scope) { BouncyOverscrollFactory(scope) }
    CompositionLocalProvider(LocalOverscrollFactory provides factory, content = content)
}

private class BouncyOverscrollFactory(private val scope: CoroutineScope) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = BouncyOverscrollEffect(scope)
    override fun equals(other: Any?): Boolean = other is BouncyOverscrollFactory && other.scope == scope
    override fun hashCode(): Int = scope.hashCode()
}

/**
 * The effect of one scrolling list. [offsetX] / [offsetY] are how far the content is shown past its edge, in px, in
 * the finger's direction (positive = moved down / right). They are plain state, changed in the same frame as the
 * drag, so the content never lags or jumps.
 */
internal class BouncyOverscrollEffect(private val scope: CoroutineScope) : OverscrollEffect {
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    /** Size of the list on screen (the rubber band's scale); set on every layout. */
    private var width = 0f
    private var height = 0f
    /** The spring back (or the bounce after a fling) while it runs; a new drag stops it and takes over from there. */
    private var releaseJob: Job? = null

    override val isInProgress: Boolean get() = offsetX != 0f || offsetY != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        // flings are handled in applyToFling (the list stops at its end and hands back the speed left)
        if (source != NestedScrollSource.UserInput) return performScroll(delta)
        stopRelease()

        // 1) dragging back toward the content first takes the pull back, with the same resistance as pulling out
        val (newX, backX) = takeBack(offsetX, delta.x, width)
        val (newY, backY) = takeBack(offsetY, delta.y, height)
        offsetX = newX
        offsetY = newY
        val takenBack = Offset(backX, backY)

        // 2) the rest scrolls the list as usual
        val left = delta - takenBack
        val scrolled = if (left != Offset.Zero) performScroll(left) else Offset.Zero

        // 3) what the list could not scroll (it is at its end) pulls the content past the edge
        val over = left - scrolled
        var pulled = Offset.Zero
        if (abs(over.x) > 0.5f) {
            offsetX = shown(raw(offsetX, width) + over.x, width)
            pulled += Offset(over.x, 0f)
        }
        if (abs(over.y) > 0.5f) {
            offsetY = shown(raw(offsetY, height) + over.y, height)
            pulled += Offset(0f, over.y)
        }
        return takenBack + scrolled + pulled
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        stopRelease()
        val bounce: Velocity
        if (isInProgress) {
            // let go while pulled: no fling, the content springs back. A throw further outward carries it a bit
            // more before it returns; a throw back toward the content is left out, so it never springs past its edge.
            performFling(Velocity.Zero)
            bounce = Velocity(
                if (offsetX != 0f && sign(velocity.x) == sign(offsetX)) velocity.x * PULL_RELEASE_VELOCITY else 0f,
                if (offsetY != 0f && sign(velocity.y) == sign(offsetY)) velocity.y * PULL_RELEASE_VELOCITY else 0f
            )
        } else {
            // a normal fling; when it hits the end, the speed left makes the content bounce past it and back
            val left = velocity - performFling(velocity)
            bounce = Velocity(
                if (abs(left.x) > MIN_BOUNCE_VELOCITY) left.x * FLING_BOUNCE_VELOCITY else 0f,
                if (abs(left.y) > MIN_BOUNCE_VELOCITY) left.y * FLING_BOUNCE_VELOCITY else 0f
            )
        }
        val startX = offsetX
        val startY = offsetY
        if (startX == 0f && startY == 0f && bounce == Velocity.Zero) return
        val job = scope.launch {
            coroutineScope {
                if (startX != 0f || bounce.x != 0f) launch { springTo0(startX, bounce.x, width) { offsetX = it } }
                if (startY != 0f || bounce.y != 0f) launch { springTo0(startY, bounce.y, height) { offsetY = it } }
            }
        }
        releaseJob = job
        job.join()
    }

    /** Springs from [from] back to 0 (with [velocity], if any), never further out than a sixth of the list. */
    private suspend fun springTo0(from: Float, velocity: Float, size: Float, set: (Float) -> Unit) {
        val limit = (if (size > 0f) size else FALLBACK_SIZE) / 6f
        animate(from, 0f, velocity, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SPRING_STIFFNESS)) { value, _ ->
            set(value.coerceIn(-maxOf(limit, abs(from)), maxOf(limit, abs(from))))
        }
        set(0f)
    }

    private fun stopRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
        // while moved, the content is cut at the list's own edges: it slides inside its area and never draws over
        // what is above or below the list (a top bar, a header); the gap shows what is behind the list
        override fun ContentDrawScope.draw() {
            if (isInProgress) clipRect { this@draw.drawContent() } else drawContent()
        }

        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            width = placeable.width.toFloat()
            height = placeable.height.toFloat()
            return layout(placeable.width, placeable.height) {
                // read in the layer block: a change only moves the layer (no new layout or composition)
                placeable.placeWithLayer(0, 0) {
                    translationX = offsetX
                    translationY = offsetY
                }
            }
        }
    }

    private companion object {
        /** iOS's rubber-band constant: how strongly the content resists being pulled past its edge. */
        const val RUBBER_BAND = 0.55f
        /** Critically damped spring, about half a second back from a full pull (like iOS). */
        const val SPRING_STIFFNESS = 200f
        const val FLING_BOUNCE_VELOCITY = 0.3f
        const val PULL_RELEASE_VELOCITY = 0.3f
        const val MIN_BOUNCE_VELOCITY = 50f
        /** Before the first layout the size is unknown; any screen-like size works. */
        const val FALLBACK_SIZE = 1000f

        /** Finger distance past the edge → how far the content is shown past it (iOS: (1 - 1/(x·c/d + 1))·d). */
        fun shown(raw: Float, size: Float): Float {
            val d = if (size > 0f) size else FALLBACK_SIZE
            val x = abs(raw)
            return sign(raw) * (1f - 1f / (x * RUBBER_BAND / d + 1f)) * d
        }

        /** The inverse of [shown]: how far the finger went for the content to be shown at [shown] past the edge. */
        fun raw(shown: Float, size: Float): Float {
            val d = if (size > 0f) size else FALLBACK_SIZE
            val y = min(abs(shown) / d, 0.999f)
            return sign(shown) * (1f / (1f - y) - 1f) * d / RUBBER_BAND
        }

        /**
         * A drag of [delta] against a pull of [offset] takes the pull back first. Returns the new offset and how much
         * of [delta] that used (all of it, unless the pull is fully undone and the rest goes on to scroll the list).
         */
        fun takeBack(offset: Float, delta: Float, size: Float): Pair<Float, Float> {
            if (offset == 0f || delta == 0f || sign(delta) == sign(offset)) return offset to 0f
            val r = raw(offset, size)
            val newRaw = r + delta
            return if (newRaw == 0f || sign(newRaw) != sign(r)) 0f to -r else shown(newRaw, size) to delta
        }
    }
}
