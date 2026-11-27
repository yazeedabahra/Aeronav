package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class HandRangeTest {
    @Test fun independentProfilesAndReversibleLink() {
        val old=ControlRange(50,70,35,50)
        val initial=HandRanges(shared=old)
        val independent=initial.link(false,0).edit(0,ControlRange(20,40,25,40))
        assertEquals(old,independent.effective(1))
        assertEquals(20,independent.effective(0).widthPercent)
        val linked=independent.link(true,0)
        assertEquals(linked.effective(0),linked.effective(1))
        assertEquals(old,linked.link(false,0).effective(1))
        assertEquals(20,linked.link(false,0).effective(0).widthPercent)
    }
    @Test fun eachHandCanReachAllEdges() {
        val r=ControlRange(10,20,20,80)
        assertEquals(0f,r.pointerX(r.left),.0001f)
        assertEquals(1f,r.pointerX(r.right),.0001f)
        assertEquals(0f,r.pointerY(r.top),.0001f)
        assertEquals(1f,r.pointerY(r.bottom),.0001f)
        assertEquals(.5f,r.pointerX((r.left+r.right)/2),.0001f)
    }
    @Test fun autoCaptureRejectsSparseStationaryOrExpiredSamples() {
        val c=RangeCapture(0,0)
        repeat(20){ c.add(.5f,.5f,it*150L) }
        assertNull(c.finish(3000))
        assertNull(c.finish(16000))
    }
    @Test fun autoCaptureMeasuresBothAxes() {
        val c=RangeCapture(1,0)
        repeat(30){ i -> val k=i/29f;c.add(.2f+.5f*k,.3f+.4f*k,i*100L) }
        val r=c.finish(3000)!!
        assertTrue(r.widthPercent in 40..50)
        assertTrue(r.heightPercent in 32..40)
        assertEquals(45,r.horizontalCenter)
        assertEquals(50,r.verticalCenter)
    }
}
