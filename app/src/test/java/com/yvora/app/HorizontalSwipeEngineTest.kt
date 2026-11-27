package com.yvora.app

import org.junit.Assert.*
import org.junit.Test

class HorizontalSwipeEngineTest {
    @Test fun firstKnifeCandidateAnchorsButCannotExecuteUntilConfirmed() {
        val e=HorizontalSwipeEngine()
        assertNull(e.updatePose(false,.35f,.5f,0,candidate=true))
        assertNull(e.updatePose(false,.42f,.5f,100,candidate=true))
        assertEquals("RIGHT",e.updatePose(true,.46f,.5f,200))
        assertNull(e.updatePose(true,.65f,.5f,300))
        assertTrue(e.waitingForRelease)
        e.updatePose(false,.65f,.5f,400)
        e.updatePose(false,.65f,.5f,500)
        assertFalse(e.waitingForRelease)
        assertNull(e.updatePose(false,.65f,.5f,600,candidate=true))
        assertEquals("LEFT",e.updatePose(true,.52f,.5f,750))
    }
    @Test fun unconfirmedCandidateNeverExecutesAndReleaseClearsAnchor() {
        val e=HorizontalSwipeEngine()
        e.updatePose(false,.2f,.5f,0,candidate=true)
        assertNull(e.updatePose(false,.6f,.5f,100,candidate=true))
        e.updatePose(false,.6f,.5f,200)
        e.updatePose(false,.6f,.5f,300)
        assertNull(e.updatePose(true,.6f,.5f,400))
        assertNull(e.updatePose(true,.61f,.5f,500))
    }
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
