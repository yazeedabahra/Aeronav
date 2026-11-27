package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class CursorGlideTest {
    @Test fun smoothlyReachesTargetWithoutOvershoot(){
        val g=CursorGlide()
        assertTrue(g.aim(10f,20f,110f,220f,100,100))
        val mid=g.sample(132)!!
        assertTrue(mid.x>10f && mid.x<110f)
        assertTrue(mid.y>20f && mid.y<220f)
        val done=g.sample(165)!!
        assertEquals(110f,done.x,0f);assertEquals(220f,done.y,0f)
        assertTrue(done.done);assertFalse(g.active);assertNull(g.sample(180))
    }
    @Test fun newTargetStartsFromDisplayedPositionAndCanReverse(){
        val g=CursorGlide()
        g.aim(0f,0f,100f,0f,0,100)
        val shown=g.sample(32)!!
        g.aim(shown.x,shown.y,0f,0f,40,100)
        assertEquals(shown.x,g.sample(40)!!.x,0f)
        assertTrue(g.sample(70)!!.x<shown.x)
        assertEquals(0f,g.sample(105)!!.x,0f)
    }
    @Test fun durationBoundAndCancellation(){
        val g=CursorGlide()
        g.aim(0f,0f,100f,100f,0,500)
        assertTrue(g.sample(95)!!.done)
        g.aim(100f,100f,0f,0f,100,90)
        g.cancel();assertNull(g.sample(130))
        assertFalse(g.aim(1f,1f,1.1f,1.1f,200,90))
        assertFalse(g.aim(1f,1f,Float.NaN,0f,200,90))
    }
}
