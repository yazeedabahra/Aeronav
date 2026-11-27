package com.yvora.app
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FuzzyRotationTest {
    private fun sample(x:Float,y:Float,open:Boolean)=ZoomSample(x,y,open,if(open) .5f else .12f,1f)
    private fun frame(r:FuzzyRotation,angle:Double,time:Long,open:Boolean,rad:Float=.20f):RotationDecision {
        val a=angle*Math.PI/180
        val dx=rad*cos(a).toFloat();val dy=rad*sin(a).toFloat()
        return r.update(sample(.5f-dx,.5f-dy,open),sample(.5f+dx,.5f+dy,open),time,1f)
    }
    private fun arm(r:FuzzyRotation) {
        frame(r,0.0,0,true);frame(r,0.0,100,true);frame(r,0.0,200,false)
    }
    @Test fun rotationBothDirectionsAndOneShot() {
        val r=FuzzyRotation();arm(r)
        assertEquals(RotationAction.CLOCKWISE,frame(r,40.0,400,false).action)
        assertNull(frame(r,60.0,500,false).action)
        r.reset();arm(r)
        assertEquals(RotationAction.COUNTERCLOCKWISE,frame(r,-40.0,400,false).action)
    }
    @Test fun radialMovementOrMissingHandAbstains() {
        val r=FuzzyRotation();arm(r)
        assertNull(frame(r,40.0,400,false,.30f).action)
        assertNull(r.update(null,sample(.8f,.5f,false),450,1f).action)
        assertNull(frame(r,45.0,500,false).action)
    }
    @Test fun pureTranslationDoesNotRotate() {
        val r=FuzzyRotation();arm(r)
        assertNull(r.update(sample(.4f,.6f,false),sample(.8f,.6f,false),400,1f).action)
    }
}
