package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class MotionSimilarityTest {
    private fun pattern(dx:Float,dy:Float,offset:Float=.3f,scale:Float=.3f):MotionPattern =
        MotionSimilarity.normalize((0..9).map{ i->MotionPoint(offset+dx*scale*i/9f,offset+dy*scale*i/9f,i*100L)},1f)!!
    @Test fun translationAndScaleInvariantButDirectionMatters() {
        val a=pattern(1f,0f);val b=pattern(1f,0f,.4f,.2f)
        assertTrue(MotionSimilarity.degree(a,b)>.99f)
        assertTrue(MotionSimilarity.degree(a,pattern(-1f,0f,.6f))<.2f)
        assertTrue(MotionSimilarity.degree(a,pattern(0f,1f))<.2f)
    }
    @Test fun threeConsistentSamplesAndAmbiguousTemplates() {
        val a=pattern(1f,0f)
        assertNotNull(MotionSimilarity.learn(listOf(a,a,a)))
        assertNull(MotionSimilarity.learn(listOf(a,a,pattern(0f,1f))))
        assertNull(MotionSimilarity.choose(a,mapOf(AiAction.SWIPE_LEFT to a,AiAction.SWIPE_RIGHT to a)))
        assertEquals(AiAction.SWIPE_RIGHT,MotionSimilarity.choose(a,mapOf(AiAction.SWIPE_RIGHT to a))?.first)
    }
    @Test fun clutchRequiresOpenPalmPinchReleaseAndFreshTracking() {
        val c=MotionClutch()
        fun hand(x:Float,open:Boolean)=ZoomSample(x,.4f,open,if(open).5f else .1f,1f)
        assertNull(c.update(hand(.3f,false),0,1f).pattern)
        c.update(hand(.3f,true),100,1f);c.update(hand(.3f,true),200,1f)
        repeat(7){i->c.update(hand(.3f+i*.035f,false),300+i*100L,1f)}
        assertNotNull(c.update(hand(.55f,true),1000,1f).pattern)
        assertNull(c.update(hand(.6f,false),1100,1f).pattern)
        c.update(null,1200,1f)
        assertNull(c.update(hand(.6f,true),1300,1f).pattern)
    }
}
