package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class DualHandTest {
    @Test fun handednessNotArrayOrderDeterminesSlot() {
        assertArrayEquals(intArrayOf(1,0),HandSlots.assign(listOf("Left","Right")))
        assertArrayEquals(intArrayOf(0,1),HandSlots.assign(listOf("Right","Left")))
        assertArrayEquals(intArrayOf(-1,0),HandSlots.assign(listOf("Left")))
        assertArrayEquals(intArrayOf(-1,-1),HandSlots.assign(listOf("Left","Left")))
        assertArrayEquals(intArrayOf(-1,-1),HandSlots.assign(listOf(null)))
    }
    @Test fun otherHandCannotStealAnActiveDrag() {
        val a=TouchArbiter()
        assertTrue(a.claim(0,100))
        assertFalse(a.claim(1,200))
        a.release(1,250)
        assertEquals(0,a.owner)
        a.release(0,300)
        assertFalse(a.claim(1,500))
        assertTrue(a.claim(1,650))
        a.reset()
        assertNull(a.owner)
    }
}
