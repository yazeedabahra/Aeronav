package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class PersonalGestureTest {
    @Test fun fuzzyJaccard() {
        val a=PoseFeatures(.8f,.7f,.9f);val b=PoseFeatures(.7f,.7f,.8f)
        assertEquals(1f,a.similarity(a),.0001f)
        assertEquals(2.2f/2.4f,a.similarity(b),.0001f)
        assertEquals(a.similarity(b),b.similarity(a),.0001f)
    }
    @Test fun noPinchOrUnsafePoseOrStaleTracking() {
        val a=PoseFeatures(.8f,.7f,.9f)
        assertEquals(0f,PersonalGesture.degree(a,a,1f,false),.0001f)
        assertEquals(0f,PersonalGesture.degree(PoseFeatures(.1f,.8f,.9f),a,1f,true),.0001f)
        assertEquals(0f,PersonalGesture.degree(a,a,0f,true),.0001f)
        assertEquals(.9f,PersonalGesture.degree(a,a,.9f,true),.0001f)
    }
    @Test fun onlySufficientApprovedPoseSamples() {
        val training=PoseTraining(0,0)
        val tr=FuzzyHandTrace(present=true,quality=.9f,pinchRatio=.5f,features=PoseFeatures(.8f,.7f,.9f))
        repeat(12){training.add(tr,it*200L)}
        assertEquals(12,training.count)
        assertNotNull(training.finish(2500))
        assertNull(training.finish(13000))
        val empty=PoseTraining(1,0)
        repeat(12){empty.add(tr.copy(pinchRatio=.1f),it*200L)}
        assertNull(empty.finish(2500))
    }
}
