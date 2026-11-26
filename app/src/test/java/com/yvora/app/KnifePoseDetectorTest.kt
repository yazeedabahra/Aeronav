package com.yvora.app

import org.junit.Assert.*
import org.junit.Test

class KnifePoseDetectorTest {
    private fun hand(d: KnifePoseDetector, edge:Boolean, curled:Boolean=false, dx:Float=0f) {
        fun pt(i:Int,x:Float,y:Float,z:Float=0f) {
            if(edge) d.set(i,z+dx,y,-x) else d.set(i,x+dx,y,z)
        }
        pt(0,0f,1f)
        for(f in 1..4) {
            val base=f*4+1; val x=(f-2.5f)*.3f
            pt(base,x,0f); pt(base+1,x,-.3f)
            pt(base+2,x,if(curled) -.1f else -.6f)
            pt(base+3,x,if(curled) .2f else -.9f)
        }
    }
    @Test fun openPalmAndFistDoNotArm() {
        val d=KnifePoseDetector()
        repeat(4) { hand(d,false); assertFalse(d.update()) }
        repeat(4) { hand(d,true,true); assertFalse(d.update()) }
    }
    @Test fun edgeArmsAfterTwoSamplesAndFaceReleases() {
        val d=KnifePoseDetector()
        hand(d,true); assertFalse(d.update())
        hand(d,true); assertTrue(d.update())
        hand(d,false); assertTrue(d.update())
        hand(d,false); assertFalse(d.update())
    }
    @Test fun onlyArmedHorizontalMovementSwipes() {
        val e=HorizontalSwipeEngine()
        assertNull(e.updatePose(false,.4f,.5f,0))
        assertNull(e.updatePose(false,.7f,.5f,100))
        assertNull(e.updatePose(true,.4f,.5f,200))
        assertEquals("RIGHT",e.updatePose(true,.55f,.5f,350))
        assertNull(e.updatePose(true,.8f,.5f,500))
    }
}
