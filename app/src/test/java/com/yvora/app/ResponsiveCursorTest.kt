package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class ResponsiveCursorTest {
    @Test fun fasterOnlyWhileCool(){
        val p=ThermalPolicy(false,true)
        assertEquals(250L,p.interval(0))
        p.observe(100,true,.3f,.3f,4,false)
        assertTrue(p.boostAllowed);assertEquals(48L,p.interval(100))
        p.observe(900,true,.3f,.3f,4,false)
        assertEquals(85L,p.interval(900))
        p.environment(1000,0,390,false)
        assertFalse(p.boostAllowed)
        p.environment(1100,0,410,false)
        p.observe(1100,true,.4f,.3f,4,true)
        assertEquals(150L,p.interval(1100));assertFalse(p.boostAllowed)
        p.environment(1200,3,430,false)
        assertTrue(p.cooling);assertFalse(p.boostAllowed)
        p.environment(1300,0,360,true)
        assertFalse(p.boostAllowed)
    }
    @Test fun measuredCostStillLimitsFastPacing(){
        val p=FramePacer(66L)
        p.started(0);p.finished(80)
        assertFalse(p.canStart(107,48,true))
        assertTrue(p.canStart(108,48,true))
        assertFalse(p.canStart(159,48,false))
        assertTrue(p.canStart(160,48,false))
    }
    @Test fun responsiveFilterReducesLagAndStaysBounded(){
        val a=AdaptivePointerFilter()
        val b=AdaptivePointerFilter().apply{responsive=true}
        a.update(.2f,.4f,0);b.update(.2f,.4f,0)
        a.update(.7f,.4f,70);b.update(.7f,.4f,70)
        assertTrue(b.x>a.x);assertTrue(b.x<=.70001f)
        b.update(.2f,.4f,140);assertTrue(b.x>=.2f && b.x<.7f)
    }
    @Test fun responsiveGlideBridgesShortSampleInterval(){
        val g=CursorGlide()
        g.aim(0f,0f,100f,100f,0,50,true)
        assertFalse(g.sample(32)!!.done)
        assertTrue(g.sample(43)!!.done)
        assertNull(g.sample(100))
    }
}
