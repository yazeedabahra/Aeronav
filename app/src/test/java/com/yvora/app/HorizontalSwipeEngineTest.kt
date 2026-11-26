package com.yvora.app

import org.junit.Assert.*
import org.junit.Test

class HorizontalSwipeEngineTest {
    @Test fun stationaryAndVerticalMotionDoNotSwipe() {
        val e = HorizontalSwipeEngine()
        assertNull(e.update(3, .5f, .5f, 0))
        assertNull(e.update(3, .505f, .51f, 100))
        assertNull(e.update(3, .52f, .75f, 200))
        assertTrue(e.blocksOtherGestures)
    }
    @Test fun bothDirectionsAndOneShotUntilRelease() {
        val e = HorizontalSwipeEngine()
        assertNull(e.update(3, .4f, .5f, 0))
        assertEquals("RIGHT", e.update(3, .55f, .51f, 150))
        assertNull(e.update(3, .8f, .5f, 250))
        assertNull(e.update(2, .8f, .5f, 350))
        assertTrue(e.blocksOtherGestures)
        assertNull(e.update(3, .8f, .5f, 450))
        assertNull(e.update(2, .8f, .5f, 550))
        assertNull(e.update(2, .8f, .5f, 650))
        assertFalse(e.blocksOtherGestures)
        assertNull(e.update(3, .8f, .5f, 750))
        assertEquals("LEFT", e.update(3, .65f, .49f, 900))
    }
    @Test fun otherFingerCountsNeverArm() {
        val e = HorizontalSwipeEngine()
        for (n in listOf(0, 1, 2, 4, 5)) {
            assertNull(e.update(n, .1f, .5f, 0))
            assertNull(e.update(n, .9f, .5f, 100))
            assertFalse(e.blocksOtherGestures)
        }
    }
    @Test fun lostTrackingDoesNotRepeatCompletedSwipe() {
        val e = HorizontalSwipeEngine()
        e.update(3, .2f, .5f, 0)
        assertEquals("RIGHT", e.update(3, .4f, .5f, 150))
        e.trackingLost()
        assertNull(e.update(3, .4f, .5f, 800))
        assertNull(e.update(3, .6f, .5f, 950))
    }
    @Test fun longGapAndSlowDriftAreRejected() {
        val e = HorizontalSwipeEngine()
        e.update(3, .2f, .5f, 0)
        assertNull(e.update(3, .4f, .5f, 500))
        for (i in 1..5) assertNull(e.update(3, .4f + .006f*i, .5f, 500L+100L*i))
    }
}
