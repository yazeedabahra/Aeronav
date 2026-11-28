package com.yvora.app
import org.junit.Assert.*
import org.junit.Test
class PerformanceSessionTest {
    @Test fun aggregateAndStop(){
        val s=PerformanceSession(100,1000)
        s.frame(40,200);s.frame(100,300)
        s.sample(400,45f,390,false,false)
        s.sample(500,0f,410,true,true)
        s.stopForTest()
        val r=s.snapshot(600)
        assertEquals(2,r.frames);assertEquals(70.0,r.meanAge!!,0.0)
        assertEquals(109L,r.p95Age!!);assertEquals(45.0,r.meanDraw!!,0.0)
        assertEquals(410,r.peakTemp!!);assertEquals(1,r.warmSamples);assertEquals(1,r.pausedSamples)
        assertFalse(r.active)
    }
    private fun PerformanceSession.stopForTest(){finish(550);frame(200,560)}
    @Test fun deadlineAndNoSamples(){
        val s=PerformanceSession(100,1000)
        s.sample(1100,60f,350,false,false)
        val r=s.snapshot(1200)
        assertFalse(r.active);assertEquals(1000L,r.elapsed)
        assertNull(r.meanAge);assertNull(r.meanDraw)
    }
}
