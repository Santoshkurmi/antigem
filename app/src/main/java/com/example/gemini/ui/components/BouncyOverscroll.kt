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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
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

/**
 * The bounce for one list, to pass to `LazyColumn(overscrollEffect = …)` or `verticalScroll(…, overscrollEffect = …)`.
 * Pair it with [bounceWhenNotScrollable] so the list bounces even when its content fits.
 */
@Composable
fun rememberBouncyOverscrollEffect(): BouncyOverscrollEffect {
    val scope = rememberCoroutineScope()
    return remember(scope) { BouncyOverscrollEffect(scope) }
}

/**
 * Compose only hands a drag to a list's overscroll effect when the list can scroll, so a list whose content fits
 * (a few chats, a short settings page) would never bounce. While [active] (the list cannot scroll: turn its own
 * scrolling off then, `userScrollEnabled` / `enabled`), a vertical drag on it pulls [effect], and letting go springs
 * back or, after a flick, bounces. Taps, long presses and sideways swipes are left alone (it only takes a drag once
 * the finger has moved up or down past the touch slop).
 */
fun Modifier.bounceWhenNotScrollable(active: Boolean, effect: BouncyOverscrollEffect): Modifier =
    if (!active) this else pointerInput(effect) {
        try {
            val tracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onDragEnd = { effect.release(tracker.calculateVelocity()) },
                onDragCancel = { effect.release(Velocity.Zero) },
                onVerticalDrag = { change, dy ->
                    tracker.addPointerInputChange(change)
                    change.consume()
                    effect.applyToScroll(Offset(0f, dy), NestedScrollSource.UserInput) { Offset.Zero }
                }
            )
        } finally {
            // taken off in the middle of a drag (the list became scrollable): never leave the content moved
            effect.settle()
        }
    }

/**
 * `verticalScroll` with the iOS bounce at both ends, also when the content fits and nothing can scroll (a short page):
 * a drag then still pulls the whole content and springs back.
 */
@Composable
fun Modifier.bouncyVerticalScroll(state: ScrollState): Modifier {
    val bounce = rememberBouncyOverscrollEffect()
    val canScroll = state.canScrollForward || state.canScrollBackward
    return this
        .bounceWhenNotScrollable(!canScroll, bounce)
        .verticalScroll(state, enabled = canScroll, overscrollEffect = bounce)
}

/** What a LazyColumn needs for the bounce (see [rememberBouncyLazyScroll]). */
class BouncyLazyScroll(val modifier: Modifier, val userScrollEnabled: Boolean, val overscrollEffect: OverscrollEffect)

/**
 * The iOS bounce for a LazyColumn, also when its items fit: add [BouncyLazyScroll.modifier] to its modifier and pass
 * [BouncyLazyScroll.userScrollEnabled] and [BouncyLazyScroll.overscrollEffect] to it, with the same [state].
 */
@Composable
fun rememberBouncyLazyScroll(state: LazyListState): BouncyLazyScroll {
    val bounce = rememberBouncyOverscrollEffect()
    val canScroll = state.canScrollForward || state.canScrollBackward
    return BouncyLazyScroll(Modifier.bounceWhenNotScrollable(!canScroll, bounce), canScroll, bounce)
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
class BouncyOverscrollEffect internal constructor(private val scope: CoroutineScope) : OverscrollEffect {
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
        val job = if (isInProgress) {
            // let go while pulled: no fling, the content springs back
            performFling(Velocity.Zero)
            startRelease(pullReleaseBounce(velocity))
        } else {
            // a normal fling; when it hits the end, the speed left makes the content bounce past it and back
            startRelease(flingBounce(velocity - performFling(velocity)))
        }
        job?.join()
    }

    /**
     * Lets go of a drag that did not go through the list's own scrolling ([bounceWhenNotScrollable]: the content fits,
     * so the list cannot scroll): springs back when pulled, or bounces with the flick's [velocity].
     */
    fun release(velocity: Velocity) {
        stopRelease()
        startRelease(if (isInProgress) pullReleaseBounce(velocity) else flingBounce(velocity))
    }

    /** Springs back from where it is, unless that is already happening (never leaves the content moved). */
    fun settle() {
        if (releaseJob?.isActive != true && isInProgress) release(Velocity.Zero)
    }

    /**
     * Speed to spring back with after letting go while pulled: a throw further outward carries it a bit more before it
     * returns; a throw back toward the content is left out, so it never springs past its edge.
     */
    private fun pullReleaseBounce(velocity: Velocity) = Velocity(
        if (offsetX != 0f && sign(velocity.x) == sign(offsetX)) velocity.x * PULL_RELEASE_VELOCITY else 0f,
        if (offsetY != 0f && sign(velocity.y) == sign(offsetY)) velocity.y * PULL_RELEASE_VELOCITY else 0f
    )

    /** Speed to bounce out with when a fling with [left] speed hits the end. */
    private fun flingBounce(left: Velocity) = Velocity(
        if (abs(left.x) > MIN_BOUNCE_VELOCITY) left.x * FLING_BOUNCE_VELOCITY else 0f,
        if (abs(left.y) > MIN_BOUNCE_VELOCITY) left.y * FLING_BOUNCE_VELOCITY else 0f
    )

    /** Starts the spring back to 0 from where the content is now, with [bounce] speed; null when there is nothing to do. */
    private fun startRelease(bounce: Velocity): Job? {
        val startX = offsetX
        val startY = offsetY
        if (startX == 0f && startY == 0f && bounce == Velocity.Zero) return null
        val job = scope.launch {
            coroutineScope {
                if (startX != 0f || bounce.x != 0f) launch { springTo0(startX, bounce.x, width) { offsetX = it } }
                if (startY != 0f || bounce.y != 0f) launch { springTo0(startY, bounce.y, height) { offsetY = it } }
            }
        }
        releaseJob = job
        return job
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
