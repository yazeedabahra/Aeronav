package com.yvora.app
import org.junit.Assert.*
import org.junit.Test
class GestureRegistryTest {
    private fun pattern(dx:Float,dy:Float)=MotionSimilarity.normalize((0..9).map{i->MotionPoint(.5f+dx*.3f*i/9f,.5f+dy*.3f*i/9f,i*100L)},1f)!!
    @Test fun sameGestureForDifferentActionIsBlockedAndNamed(){
        val a=pattern(1f,0f)
        val r=GestureRegistry.review(RegistryAction.BACK,List(4){a},mapOf(RegistryAction.SWIPE_RIGHT to a))
        assertTrue(r.blocked);assertEquals(RegistryAction.SWIPE_RIGHT,r.conflicts.first().action);assertEquals(1f,r.conflicts.first().degree,.001f)
    }
    @Test fun independentTestAndDistinctGestureRequired(){
        val a=pattern(1f,0f);val b=pattern(0f,1f)
        val existing=mapOf(RegistryAction.HOME to b)
        assertFalse(GestureRegistry.review(RegistryAction.BACK,List(4){a},existing).blocked)
        assertTrue(GestureRegistry.review(RegistryAction.BACK,listOf(a,a,a,b),existing).blocked)
        assertTrue(GestureRegistry.review(RegistryAction.BACK,listOf(a,a,a),existing).blocked)
    }
    @Test fun replacingSameCommandAllowedButAmbiguityNeverExecutes(){
        val a=pattern(1f,0f)
        assertFalse(GestureRegistry.review(RegistryAction.HOME,List(4){a},mapOf(RegistryAction.HOME to a)).blocked)
        assertNull(GestureRegistry.choose(a,mapOf(RegistryAction.HOME to a,RegistryAction.BACK to a)))
        assertEquals(RegistryAction.ZOOM_IN,GestureRegistry.choose(a,mapOf(RegistryAction.ZOOM_IN to a))?.first)
    }
}
