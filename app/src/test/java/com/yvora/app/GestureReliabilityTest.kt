package com.yvora.app

import org.junit.Test
import org.junit.Assert.*

class GestureReliabilityTest {
    @Test fun palmRelativeSwipeWorksForSmallHandAndSlowMovement() {
        val e = HorizontalSwipeEngine()
        assertNull(e.update(3, .4f, .5f, 0, .09f))
        assertEquals("RIGHT", e.update(3, .44f, .505f, 600, .09f).let { first ->
            // 600ms gap is deliberately not bridged; use consecutive samples below.
            assertNull(first)
            e.update(3, .48f, .505f, 900, .09f)
        })
    }
    @Test fun oneBadFingerSampleDoesNotEraseArmedMotion() {
        val e = HorizontalSwipeEngine()
        e.update(3, .4f, .5f, 0)
        e.update(2, .42f, .5f, 100)
        assertEquals("RIGHT", e.update(3, .48f, .5f, 200))
    }
    @Test fun palmRelativePinchHasReleaseHysteresis() {
        val e = PinchDragEngine(300, .23f, .34f)
        e.update(.5f, 0, true, true)
        assertEquals(PinchAction.PENDING, e.update(.15f, 100, true, true))
        assertEquals(PinchAction.START_DRAG, e.update(.27f, 400, true, true))
        assertEquals(PinchAction.MOVE_DRAG, e.update(.30f, 500, false, true))
        assertEquals(PinchAction.DROP, e.update(.40f, 600, false, true))
    }
    @Test fun allTenSyntheticThreeFingerCombinations() {
        for (mask in 0..31) {
            if (Integer.bitCount(mask) != 3) continue
            val g = HandPoseGeometry()
            g.set(0, 0f, 100f)
            val thumb = mask and 1 != 0
            g.set(1,-20f,80f); g.set(2,-30f,65f)
            g.set(3, if(thumb) -48f else -18f, if(thumb) 50f else 55f)
            g.set(4, if(thumb) -80f else -4f, if(thumb) 36f else 65f)
            for (f in 1..4) {
                val i=f*4+1; val x=(f-2.5f)*24f
                val extended=mask and (1 shl f) != 0
                g.set(i,x,40f); g.set(i+1,x,15f)
                g.set(i+2,x,if(extended) -8f else 28f)
                g.set(i+3,x,if(extended) -30f else 52f)
            }
            assertEquals("mask $mask", mask, g.updateMask())
        }
    }
}
