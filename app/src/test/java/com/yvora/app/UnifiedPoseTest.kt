package com.yvora.app
import org.junit.Assert.*
import org.junit.Test
class UnifiedPoseTest {
    private val a=PoseSignature(8,.7f,.8f,.1f)
    private val b=PoseSignature(16,.7f,.8f,.1f)
    @Test fun conflictAndLearning(){
        assertNotNull(PoseRegistry.learn(List(4){a}))
        assertNull(PoseRegistry.learn(listOf(a,a,a,b)))
        assertTrue(PoseRegistry.conflicts(ControlPose.TAP,List(4){a},mapOf(ControlPose.DRAG to a),emptyMap(),0).isNotEmpty())
        assertTrue(PoseRegistry.defaults(PoseSignature(31,.7f,.8f,.1f),emptyMap(),ControlPose.TAP).isNotEmpty())
    }
    @Test fun tapReleaseAndDragHoldAreDistinct(){
        val t=PoseTouch()
        assertEquals(PinchAction.NONE,t.update(ControlPose.TAP,false,0,true,true))
        t.update(null,true,100,true,true)
        assertEquals(PinchAction.PENDING,t.update(ControlPose.TAP,false,200,true,true))
        assertEquals(PinchAction.TAP,t.update(null,true,400,true,true))
        t.update(null,true,500,true,true)
        assertEquals(PinchAction.PENDING,t.update(ControlPose.DRAG,false,600,true,true))
        assertEquals(PinchAction.START_DRAG,t.update(ControlPose.DRAG,false,920,true,true))
        assertEquals(PinchAction.CANCEL,t.update(null,false,1000,false,true))
    }
    @Test fun pairLatchAndAmbiguity(){
        val gate=PairGate();val p=PairPose(a,b);val neutral=PairPose(b,a)
        val m=mapOf(RegistryAction.ZOOM_IN to p)
        assertNull(gate.update(neutral,m,true,100))
        assertNull(gate.update(p,m,false,200))
        assertEquals(RegistryAction.ZOOM_IN,gate.update(p,m,false,510))
        assertNull(gate.update(p,m,false,820))
        gate.reset();gate.update(neutral,m,true,900)
        val ambiguous=m+(RegistryAction.ZOOM_OUT to p)
        assertNull(gate.update(p,ambiguous,false,1000))
        assertNull(gate.update(p,ambiguous,false,1310))
    }
}
