package com.yvora.app

import org.junit.Test
import org.junit.Assert.assertEquals

class PinchDragEngineTest {
    @Test fun shortPinchTapsOnceOnRelease() {
        val e = PinchDragEngine()
        assertEquals(PinchAction.NONE, e.update(.1f, 0, true, true))
        assertEquals(PinchAction.PENDING, e.update(.04f, 100, true, true))
        assertEquals(PinchAction.TAP, e.update(.1f, 250, true, true))
        assertEquals(PinchAction.NONE, e.update(.1f, 300, true, true))
    }
    @Test fun holdMoveDropNeverTaps() {
        val e = PinchDragEngine()
        e.update(.1f, 0, true, true)
        e.update(.04f, 100, true, true)
        assertEquals(PinchAction.START_DRAG, e.update(.04f, 450, true, true))
        assertEquals(PinchAction.MOVE_DRAG, e.update(.07f, 550, false, true))
        assertEquals(PinchAction.DROP, e.update(.09f, 600, false, true))
    }
    @Test fun blockedThreeFingerPinchRequiresRelease() {
        val e = PinchDragEngine()
        e.update(.1f, 0, true, true)
        assertEquals(PinchAction.NONE, e.update(.04f, 100, false, true))
        assertEquals(PinchAction.NONE, e.update(.04f, 200, true, true))
        e.update(.1f, 250, true, true)
        assertEquals(PinchAction.PENDING, e.update(.04f, 300, true, true))
    }
    @Test fun staleTrackingCancelsAndDoesNotRetap() {
        val e = PinchDragEngine()
        e.update(.1f, 0, true, true)
        e.update(.04f, 100, true, true)
        e.update(.04f, 450, true, true)
        assertEquals(PinchAction.CANCEL, e.update(.04f, 1200, true, true))
        assertEquals(PinchAction.NONE, e.update(.1f, 1300, true, true))
    }
    @Test fun missedHoldThresholdCannotLeavePendingPinch() {
        val e = PinchDragEngine()
        e.update(.1f, 0, true, true)
        e.update(.04f, 100, true, true)
        assertEquals(PinchAction.CANCEL, e.update(.1f, 500, true, true))
    }
    @Test fun olderAndroidKeepsTapOnRelease() {
        val e = PinchDragEngine()
        e.update(.1f, 0, true, false)
        e.update(.04f, 100, true, false)
        assertEquals(PinchAction.PENDING, e.update(.04f, 500, true, false))
        assertEquals(PinchAction.TAP, e.update(.1f, 550, true, false))
    }
}
