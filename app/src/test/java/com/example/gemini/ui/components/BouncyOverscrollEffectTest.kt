package com.example.gemini.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The iOS-style bounce on a fake list (position 0…1000, in px). Deltas and velocities are in the finger's direction:
 * positive y = finger moving down = scrolling toward the top.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BouncyOverscrollEffectTest {

    private class FakeList(var position: Float = 0f, val max: Float = 1000f) {
        val scroll: (Offset) -> Offset = { delta ->
            val new = (position - delta.y).coerceIn(0f, max)
            val consumed = position - new
            position = new
            Offset(0f, consumed)
        }
    }

    /** 60 fps in the test's virtual time, so springs run frame by frame. */
    private class TestClock(val test: TestScope) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            return onFrame(test.testScheduler.currentTime * 1_000_000L)
        }
    }

    private fun TestScope.effect() = BouncyOverscrollEffect(CoroutineScope(coroutineContext + TestClock(this)))

    private fun BouncyOverscrollEffect.drag(dy: Float, list: FakeList): Offset =
        applyToScroll(Offset(0f, dy), NestedScrollSource.UserInput, list.scroll)

    @Test
    fun pullAtTheTop_followsWithResistance_andUsesTheWholeDrag() = runTest {
        val e = effect(); val list = FakeList(0f)
        val consumed = e.drag(300f, list)
        assertEquals(300f, consumed.y, 0.001f)
        assertEquals(0f, list.position, 0f)
        assertTrue("moves with the finger", e.offsetY > 0f)
        assertTrue("but less than the finger", e.offsetY < 300f)
        assertTrue(e.isInProgress)
    }

    @Test
    fun resistanceGrowsTheFurtherItIsPulled() = runTest {
        val e = effect(); val list = FakeList(0f)
        var last = 0f; var lastStep = Float.MAX_VALUE
        repeat(10) {
            e.drag(100f, list)
            val step = e.offsetY - last
            assertTrue("each pull moves it less", step < lastStep)
            assertTrue(step > 0f)
            lastStep = step; last = e.offsetY
        }
    }

    @Test
    fun manySmallDragsEqualOneBigDrag() = runTest {
        val a = effect(); val b = effect()
        repeat(30) { a.drag(10f, FakeList(0f)) }
        b.drag(300f, FakeList(0f))
        assertEquals(b.offsetY, a.offsetY, 0.01f)
    }

    @Test
    fun draggingBackUndoesThePullExactly_thenScrollsTheList() = runTest {
        val e = effect(); val list = FakeList(0f)
        e.drag(300f, list)
        e.drag(-300f, list)
        assertEquals(0f, e.offsetY, 0.01f)
        assertEquals("the list did not move while undoing the pull", 0f, list.position, 0.01f)
        e.drag(-50f, list)
        assertEquals(50f, list.position, 0.01f)
        assertEquals(0f, e.offsetY, 0f)
    }

    @Test
    fun oneDragBackPastThePull_undoesItAndScrollsTheRest() = runTest {
        val e = effect(); val list = FakeList(0f)
        e.drag(300f, list)
        val consumed = e.drag(-400f, list)
        assertEquals(0f, e.offsetY, 0f)
        assertEquals(100f, list.position, 0.01f)
        assertEquals(-400f, consumed.y, 0.01f)
    }

    @Test
    fun pullAtTheBottomMovesTheContentUp() = runTest {
        val e = effect(); val list = FakeList(1000f)
        e.drag(-200f, list)
        assertTrue(e.offsetY < 0f)
        assertEquals(1000f, list.position, 0f)
    }

    @Test
    fun noPullInTheMiddleOfTheList() = runTest {
        val e = effect(); val list = FakeList(500f)
        e.drag(200f, list)
        assertEquals(0f, e.offsetY, 0f)
        assertEquals(300f, list.position, 0.01f)
    }

    @Test
    fun scrollFromAFlingNeverPulls() = runTest {
        val e = effect(); val list = FakeList(0f)
        e.applyToScroll(Offset(0f, 300f), NestedScrollSource.SideEffect, list.scroll)
        assertEquals(0f, e.offsetY, 0f)
    }

    @Test
    fun lettingGo_springsBackToExactlyZero_withoutCrossingTheEdge() = runTest {
        val e = effect(); val list = FakeList(0f)
        e.drag(400f, list)
        val start = e.offsetY
        val job = launch { e.applyToFling(Velocity.Zero) { Velocity.Zero } }
        val samples = mutableListOf<Float>()
        while (job.isActive) { advanceTimeBy(16); runCurrent(); samples += e.offsetY }
        assertEquals(0f, e.offsetY, 0f)
        assertTrue("never crosses to the other side", samples.all { it >= 0f })
        assertTrue("only goes back, never out", samples.zipWithNext().all { (a, b) -> b <= a + 0.001f })
        assertTrue("it moves back", samples.any { it < start })
        val ms = samples.size * 16
        assertTrue("about half a second back ($ms ms)", ms in 300..1200)
        assertTrue(!e.isInProgress)
    }

    @Test
    fun aFlingThatHitsTheTop_bouncesPastItAndBack() = runTest {
        val e = effect()
        // the fling scrolled to the top and has 3000 px/s left
        val job = launch { e.applyToFling(Velocity(0f, 5000f)) { Velocity(0f, 2000f) } }
        val samples = mutableListOf<Float>()
        while (job.isActive) { advanceTimeBy(16); runCurrent(); samples += e.offsetY }
        val peak = samples.max()
        assertTrue("it bounces out ($peak px)", peak > 5f)
        assertTrue("but not far ($peak px)", peak <= 1000f / 6f)
        assertTrue("on the right side only", samples.all { it >= 0f })
        assertEquals(0f, e.offsetY, 0f)
    }

    @Test
    fun aFlingThatStopsInsideTheList_doesNotBounce() = runTest {
        val e = effect()
        e.applyToFling(Velocity(0f, 3000f)) { it } // all of it used by the list
        assertEquals(0f, e.offsetY, 0f)
        assertTrue(!e.isInProgress)
    }

    @Test
    fun grabbingDuringTheSpringBack_takesOverWithoutAJump() = runTest {
        val e = effect(); val list = FakeList(0f, max = 1_000_000f)
        e.drag(400f, list)
        val job = launch { e.applyToFling(Velocity.Zero) { Velocity.Zero } }
        advanceTimeBy(100); runCurrent()
        val mid = e.offsetY
        assertTrue(mid > 0f)
        e.drag(1f, list) // the finger touches again and moves a hair
        advanceTimeBy(200); runCurrent()
        assertTrue("the spring stopped", !job.isActive)
        assertTrue("no jump: ${e.offsetY} vs $mid", e.offsetY >= mid && e.offsetY < mid + 1f)
        // and the drag goes on from there: pushing back up undoes it exactly
        e.drag(-10_000f, list)
        assertEquals(0f, e.offsetY, 0f)
    }

    @Test
    fun throwingFurtherOutWhenLettingGo_goesOutABitMoreThenBack() = runTest {
        val e = effect(); val list = FakeList(0f)
        e.drag(200f, list)
        val start = e.offsetY
        val job = launch { e.applyToFling(Velocity(0f, 4000f)) { Velocity.Zero } }
        val samples = mutableListOf<Float>()
        while (job.isActive) { advanceTimeBy(16); runCurrent(); samples += e.offsetY }
        assertTrue(samples.max() > start)
        assertTrue(samples.all { it >= 0f })
        assertEquals(0f, e.offsetY, 0f)
    }

    @Test
    fun throwingBackTowardTheContentWhenLettingGo_neverCrossesTheEdge() = runTest {
        val e = effect(); val list = FakeList(0f)
        e.drag(200f, list)
        val job = launch { e.applyToFling(Velocity(0f, -20_000f)) { Velocity.Zero } }
        val samples = mutableListOf<Float>()
        while (job.isActive) { advanceTimeBy(16); runCurrent(); samples += e.offsetY }
        assertTrue(samples.all { it >= 0f })
        assertEquals(0f, e.offsetY, 0f)
    }

    @Test
    fun horizontalListsBounceToo() = runTest {
        val e = effect()
        var pos = 0f
        val consumed = e.applyToScroll(Offset(150f, 0f), NestedScrollSource.UserInput) { d ->
            val new = (pos - d.x).coerceIn(0f, 500f); val c = pos - new; pos = new; Offset(c, 0f)
        }
        assertEquals(150f, consumed.x, 0.001f)
        assertTrue(e.offsetX > 0f && abs(e.offsetY) == 0f)
    }
}
