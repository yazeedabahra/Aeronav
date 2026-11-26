package com.yvora.app

import org.junit.Assert.*
import org.junit.Test

class ThermalPolicyTest {
    @Test fun idleAndMotionChangeRate() {
        val p = ThermalPolicy(false)
        assertEquals(250L, p.interval(0))
        p.observe(100, true, .3f, .3f, 4, false)
        assertEquals(90L, p.interval(100))
        p.observe(900, true, .3f, .3f, 4, false)
        assertEquals(160L, p.interval(900))
        p.observe(1000, true, .3f, .3f, 4, true)
        assertEquals(90L, p.interval(1000))
    }
    @Test fun severeStopsAndRecoveryNeedsSustainedCooling() {
        val p = ThermalPolicy(false)
        p.environment(0, 3, 390, false)
        assertTrue(p.cooling)
        p.environment(2000, 1, 390, false)
        p.environment(16000, 1, 390, false)
        assertTrue(p.cooling)
        p.environment(17000, 1, 390, false)
        assertFalse(p.cooling)
    }
    @Test fun batteryAndSaverAffectPolicy() {
        val p = ThermalPolicy(true)
        p.environment(0, 0, 410, false)
        assertTrue(p.warm)
        assertEquals(320L, p.interval(0))
        p.environment(1000, 0, 430, false)
        assertTrue(p.cooling)
        p.environment(2000, 0, 390, true)
        assertTrue(p.warm)
        assertTrue(p.cooling)
    }
    @Test fun warmGesturesAreStillSampled() {
        val p = ThermalPolicy(false)
        p.environment(0, 2, null, false)
        p.observe(100, true, .3f, .3f, 3, true)
        assertEquals(150L, p.interval(100))
    }
}
