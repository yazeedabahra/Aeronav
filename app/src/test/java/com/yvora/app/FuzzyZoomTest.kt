package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class FuzzyZoomTest {
    private fun hand(x:Float,open:Boolean=true,quality:Float=1f)=ZoomSample(x,.5f,open,if(open) .5f else .12f,quality)
    private fun arm(z:FuzzyZoom) {
        z.update(hand(.3f),hand(.7f),0,1f)
        z.update(hand(.3f),hand(.7f),100,1f)
    }
    @Test fun onlyArmedTwoPinchesCanZoomAndLatch() {
        val z=FuzzyZoom();arm(z)
        assertNull(z.update(hand(.3f,false),hand(.7f,false),200,1f).action)
        assertEquals(ZoomAction.IN,z.update(hand(.2f,false),hand(.8f,false),400,1f).action)
        assertNull(z.update(hand(.15f,false),hand(.85f,false),500,1f).action)
    }
    @Test fun inwardMovementZoomsOut() {
        val z=FuzzyZoom();arm(z)
        z.update(hand(.3f,false),hand(.7f,false),200,1f)
        assertEquals(ZoomAction.OUT,z.update(hand(.36f,false),hand(.64f,false),400,1f).action)
    }
    @Test fun oneHandOrLossOrTranslationNeverExecutes() {
        val z=FuzzyZoom();arm(z)
        z.update(hand(.3f,false),hand(.7f,false),200,1f)
        assertNull(z.update(hand(.4f,false),hand(.8f,false),350,1f).action)
        assertNull(z.update(hand(.4f,false),null,400,1f).action)
        assertNull(z.update(hand(.1f,false),hand(.9f,false),500,1f).action)
    }
}
