package com.spizganed.quickbuds.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The noise-control switcher keeps the pill on the track while it springs. A move to segment 0
 * passes below 0, and that must not mean "nothing selected".
 */
class AncSegmentedViewTest {

    /** OvershootInterpolator(1.1f) peaks at 1.0447. */
    private val peak = 1.0447f

    @Test fun moveToOneZeroAtTheOvershootPeakStopsAtZero() {
        assertEquals(0f, segmentPosition(1f, 0f, peak, 3), 0.0001f)
    }

    @Test fun overshootPeakFromEveryStartStaysOnTheTrack() {
        assertEquals(0f, segmentPosition(2f, 0f, peak, 3), 0.0001f)
        assertEquals(0f, segmentPosition(3f, 0f, peak, 3), 0.0001f)
        assertEquals(3f, segmentPosition(2f, 3f, peak, 3), 0.0001f)
        // A move between inner segments keeps the spring: only the track edges clamp.
        assertEquals(2.0447f, segmentPosition(1f, 2f, peak, 3), 0.0001f)
    }

    @Test fun everyFractionStaysOnTheTrack() {
        for (step in 0..1100) {
            val fraction = step / 1000f
            for (from in 0..3) {
                for (to in 0..3) {
                    val position = segmentPosition(from.toFloat(), to.toFloat(), fraction, 3)
                    assertTrue(
                        "from $from to $to at fraction $fraction gave $position",
                        position in 0f..3f
                    )
                }
            }
        }
    }

    @Test fun segmentZeroIsSelectedWhateverThePositionIs() {
        assertTrue(hasSelection(0))
        assertTrue(hasSelection(3))
        assertFalse(hasSelection(-1))
    }

    @Test fun materialEaseFollowsTheStandardCurve() {
        assertEquals(0f, materialEase(0f), 0.0001f)
        assertEquals(0.15625f, materialEase(0.1f), 0.0002f)
        assertEquals(0.8778f, materialEase(0.5f), 0.0005f)
        assertEquals(1f, materialEase(1f), 0.0001f)
    }

    @Test fun quickBudsEaseFollowsStandardDecelerate() {
        assertEquals(0f, quickBudsEase(0f), 0.0001f)
        assertEquals(0.8899f, quickBudsEase(0.5f), 0.0005f)
        assertEquals(1f, quickBudsEase(1f), 0.0001f)
    }

    @Test fun easingNeverGoesBack() {
        for (ease in listOf(::materialEase, ::quickBudsEase)) {
            var last = ease(0f)
            for (step in 1..100) {
                val next = ease(step / 100f)
                assertTrue("ease went back at $step", next >= last)
                last = next
            }
        }
    }

    @Test fun aTapTravelsOnlyBetweenTwoCells() {
        assertTrue(selectionTravels(tapped = true, from = 1, to = 2))
        assertFalse(selectionTravels(tapped = false, from = 1, to = 2))
        assertFalse(selectionTravels(tapped = true, from = -1, to = 2))
        assertFalse(selectionTravels(tapped = true, from = 2, to = -1))
        assertFalse(selectionTravels(tapped = true, from = 2, to = 2))
    }

    @Test fun aSecondTapStartsTheTravelAtTheCurrentPosition() {
        // Halfway from cell 1 to cell 2, then a tap on cell 0. The old target is 2.
        assertEquals(1.5f, travelStart(1.5f, from = 2, last = 3), 0.0001f)
        assertEquals(1.2f, travelStart(1.2f, from = 2, last = 3), 0.0001f)
    }

    @Test fun aTravelWithNoFrameOnScreenStartsAtTheOldCell() {
        assertEquals(2f, travelStart(-1f, from = 2, last = 3), 0.0001f)
        // A current position off the track cannot start a travel.
        assertEquals(0f, travelStart(9f, from = 0, last = 3), 0.0001f)
    }

    @Test fun aSecondTapStartsTheFillsAtTheCurrentMix() {
        val current = floatArrayOf(0f, 0.4f, 0.6f)
        assertArrayEquals(current, fadeMixes(current, to = 0, fraction = 0f), 0.0001f)
    }

    @Test fun theFillsFadeFromTheCurrentMixToTheNewTarget() {
        val current = floatArrayOf(0.1f, 0.4f, 0.6f)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f),
            fadeMixes(current, to = 0, fraction = 1f),
            0.0001f
        )
        // Halfway to cell 0: 0.1 becomes 0.55, 0.4 becomes 0.2, 0.6 becomes 0.3.
        assertArrayEquals(
            floatArrayOf(0.55f, 0.2f, 0.3f),
            fadeMixes(current, to = 0, fraction = 0.5f),
            0.0001f
        )
    }

    @Test fun everySegmentGetsItsOwnBounds() {
        assertEquals(SegmentBounds(0f, 0f, 25f, 44f), segmentBounds(0, 4, 100, 44))
        assertEquals(SegmentBounds(25f, 0f, 50f, 44f), segmentBounds(1, 4, 100, 44))
        assertEquals(SegmentBounds(75f, 0f, 100f, 44f), segmentBounds(3, 4, 100, 44))
    }

    @Test fun segmentBoundsCoverTheWholeWidthWithoutAGap() {
        var left = 0f
        for (i in 0 until 3) {
            val bounds = segmentBounds(i, 3, 101, 40)
            assertEquals(left, bounds.left, 0.0001f)
            assertTrue(bounds.right > bounds.left)
            left = bounds.right
        }
        assertEquals(101f, left, 0.0001f)
    }

    @Test fun aDisconnectFadesEveryFillToZero() {
        val current = floatArrayOf(0.2f, 0.8f, 0f)
        assertArrayEquals(current, fadeMixes(current, to = -1, fraction = 0f), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), fadeMixes(current, to = -1, fraction = 1f), 0.0001f)
    }
}
