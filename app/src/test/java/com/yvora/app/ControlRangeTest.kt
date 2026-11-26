package com.yvora.app

import org.junit.Test
import org.junit.Assert.assertEquals

class ControlRangeTest {
    @Test fun fullRangePreservesExistingCoordinates() {
        val r = ControlRange()
        assertEquals(0.2f, r.mapX(0.2f), 0.0001f)
        assertEquals(0.8f, r.mapY(0.8f), 0.0001f)
    }
    @Test fun reducedRangeMapsEdgesAndClamps() {
        val r = ControlRange(60, 40)
        assertEquals(0f, r.mapX(0.2f), 0.0001f)
        assertEquals(1f, r.mapX(0.8f), 0.0001f)
        assertEquals(0.5f, r.mapY(0.5f), 0.0001f)
        assertEquals(0f, r.mapY(-0.2f), 0.0001f)
        assertEquals(1f, r.mapY(1.2f), 0.0001f)
    }
    @Test fun offsetRangeStaysInsideFrame() {
        val r = ControlRange(30, 50, 0, 100)
        assertEquals(0f, r.left, 0.0001f)
        assertEquals(0.5f, r.top, 0.0001f)
        assertEquals(1f, r.bottom, 0.0001f)
        assertEquals(0.5f, r.mapX(0.15f), 0.0001f)
    }
    @Test fun invalidSavedValuesAreBounded() {
        val r = ControlRange(0, 1000, -100, 400)
        assertEquals(0.3f, r.right, 0.0001f)
        assertEquals(1f, r.bottom, 0.0001f)
    }
}
