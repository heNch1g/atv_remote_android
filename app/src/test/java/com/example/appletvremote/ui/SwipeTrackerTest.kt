package com.example.appletvremote.ui

import com.example.appletvremote.protocol.companion.TouchPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the swipe, checked without a phone or a TV.
 *
 * The thresholds match the ones [Touchpad] uses, with one unit of the virtual
 * surface per pixel of finger travel so the numbers in the tests can be read
 * as travel.
 */
class SwipeTrackerTest {

    private val centre = 500

    @Test
    fun aThumbThatRollsBackwardsNeverMovesTheFocusBackwards() {
        val rig = Rig()
        rig.begin()
        // The thumb flattens first and pushes the contact patch to the right,
        // then the real swipe to the left begins.
        rig.moveTo(202f, 200f)
        rig.moveTo(204f, 200f)
        rig.moveTo(200f, 200f)
        rig.moveTo(194f, 200f)
        rig.moveTo(188f, 200f)
        rig.moveTo(182f, 200f)
        rig.moveTo(180f, 200f)
        rig.end()

        val holds = rig.samples.filter { it.phase == TouchPhase.HOLD }
        assertTrue("the swipe never reached the TV", holds.isNotEmpty())
        assertTrue(
            "a sample went backwards: ${holds.map { it.x }}",
            holds.all { it.x <= centre },
        )
        assertEquals(centre, rig.samples.first().x)
        assertEquals(TouchPhase.PRESS, rig.samples.first().phase)
        assertEquals(TouchPhase.RELEASE, rig.samples.last().phase)
        assertTrue(rig.samples.last().x < centre)
    }

    @Test
    fun theFirstMovementIsTheSameInAllFourDirections() {
        val directions = listOf(
            -1f to 0f, // left
            1f to 0f, // right
            0f to -1f, // up
            0f to 1f, // down
        )
        for ((dirX, dirY) in directions) {
            val rig = Rig()
            rig.begin()
            repeat(6) { step ->
                val travel = 4f * (step + 1)
                rig.moveTo(200f + dirX * travel, 200f + dirY * travel)
            }
            val first = rig.samples.first { it.phase == TouchPhase.HOLD }
            assertEquals("x for $dirX/$dirY", (centre + dirX * 12f).toInt(), first.x)
            assertEquals("y for $dirX/$dirY", (centre + dirY * 12f).toInt(), first.y)
        }
    }

    @Test
    fun aTinyDriftOnlyEverBecomesAReleaseInTheCentre() {
        val rig = Rig()
        rig.begin()
        rig.moveTo(201f, 200f)
        rig.moveTo(199f, 201f)
        rig.moveTo(202f, 198f)
        rig.moveTo(200f, 200f)
        rig.end()

        assertEquals(2, rig.samples.size)
        assertEquals(TouchPhase.PRESS, rig.samples[0].phase)
        assertEquals(centre, rig.samples[0].x)
        assertEquals(centre, rig.samples[0].y)
        assertEquals(TouchPhase.RELEASE, rig.samples[1].phase)
        assertEquals(centre, rig.samples[1].x)
        assertEquals(centre, rig.samples[1].y)
    }

    @Test
    fun aWobbleMidSwipeIsHeldBackUntilTheFingerReturns() {
        val rig = Rig()
        rig.begin()
        repeat(4) { step -> rig.moveTo(200f - 4f * (step + 1), 200f) }
        rig.samples.clear()

        // Three pixels back the way the finger came: below the reversal
        // threshold, so nothing is sent at all.
        rig.moveTo(187f, 200f)
        assertTrue(rig.samples.isEmpty())

        // Carrying on left counts from where the finger had reached, not from
        // where it wobbled to.
        rig.moveTo(181f, 200f)
        assertEquals(1, rig.samples.size)
        assertEquals(centre - 22, rig.samples[0].x)
    }

    @Test
    fun aDeliberateReversalTurnsTheFingerAround() {
        val rig = Rig()
        rig.begin()
        repeat(4) { step -> rig.moveTo(200f - 4f * (step + 1), 200f) }
        val before = rig.samples.last().x

        // Eight pixels back: a real reversal, so the lock flips and the
        // withheld travel is given back.
        rig.moveTo(200f - 16f + 8f, 200f)
        val after = rig.samples.last()
        assertEquals(TouchPhase.HOLD, after.phase)
        assertTrue("the finger did not turn around: $before -> ${after.x}", after.x > before)
    }

    @Test
    fun aSlowSwipeIsForwardedOnceTheWindowRunsOut() {
        val rig = Rig()
        rig.begin()
        // One pixel every 16 ms: too slow to reach the confirm distance, so
        // only the window can start the touch.
        rig.samples.clear()
        repeat(4) { step ->
            rig.moveTo(200f - (step + 1), 200f)
            assertTrue("sent before the window ran out", rig.samples.isEmpty())
        }
        rig.moveTo(195f, 200f)
        assertEquals(1, rig.samples.size)
        assertEquals(centre - 5, rig.samples[0].x)
        assertEquals(TouchPhase.HOLD, rig.samples[0].phase)
    }

    @Test
    fun aSlowSwipeKeepsItsWholeTravel() {
        val rig = Rig()
        rig.begin()
        // One pixel per sample, twenty samples: the jitter filter must delay
        // the samples but not lose the distance.
        var x = 200f
        repeat(20) {
            x -= 1f
            rig.moveTo(x, 200f)
        }
        rig.end()
        assertEquals(centre - 20, rig.samples.last().x)
    }

    @Test
    fun slidingOffAnEdgeLiftsTheFingerThere() {
        val rig = Rig()
        rig.begin()
        var x = 200f
        while (x > -400f) {
            x -= 20f
            rig.moveTo(x, 200f)
        }

        val last = rig.samples.last()
        assertEquals(TouchPhase.RELEASE, last.phase)
        assertEquals(0, last.x)
        assertTrue(!rig.tracker.isTouching)
        assertNull(rig.tracker.move(x, 200f, rig.now() + 16_000_000L))
        assertNull(rig.tracker.end())
    }

    @Test
    fun theSurvivingSamplesKeepTheirOrderAndPhase() {
        val rig = Rig()
        rig.begin()
        repeat(10) { step -> rig.moveTo(200f - 5f * (step + 1), 200f) }
        rig.end()

        assertNotNull(rig.samples.first { it.phase == TouchPhase.PRESS })
        assertEquals(TouchPhase.RELEASE, rig.samples.last().phase)
        val holds = rig.samples.filter { it.phase == TouchPhase.HOLD }
        assertTrue(holds.zipWithNext().all { (a, b) -> b.x < a.x })
    }

    /** A tracker wired like the pad, with the finger positions in "pixels". */
    private class Rig(
        private val holdIntervalNs: Long = 16_000_000L,
    ) {
        val tracker = SwipeTracker(
            unitsPerPx = 1f,
            armConfirmPx = 9f,
            armWindowNs = 70_000_000L,
            adoptMinPx = 2.5f,
            reverseConfirmPx = 7f,
            holdIntervalNs = holdIntervalNs,
            minStepUnits = 4f,
        )
        val samples = mutableListOf<SwipeSample>()
        private var clock = 1_000_000_000L
        private var x = 200f
        private var y = 200f

        fun now(): Long = clock

        fun begin() {
            samples += tracker.begin(x, y, clock)
        }

        fun moveTo(nx: Float, ny: Float, afterNs: Long = 16_000_000L) {
            clock += afterNs
            x = nx
            y = ny
            tracker.move(x, y, clock)?.let { samples += it }
        }

        fun end() {
            tracker.end()?.let { samples += it }
        }
    }
}
