package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class FuzzyTrackingTest {
    private fun hand(label:String,x:Float)=HandObservation(label,.95f,x,.5f)
    @Test fun arrayReorderingDoesNotSwapSlots() {
        val id=FuzzyHandIdentity()
        assertArrayEquals(intArrayOf(0,1),id.update(listOf(hand("Right",.2f),hand("Left",.8f)),0).indices)
        assertArrayEquals(intArrayOf(1,0),id.update(listOf(hand("Left",.79f),hand("Right",.21f)),100).indices)
    }
    @Test fun labelSwapAndOverlapAbstain() {
        val id=FuzzyHandIdentity()
        id.update(listOf(hand("Right",.2f),hand("Left",.8f)),0)
        val r=id.update(listOf(hand("Left",.2f),hand("Right",.8f)),100)
        assertTrue(r.ambiguous);assertArrayEquals(intArrayOf(-1,-1),r.indices)
        assertTrue(id.update(listOf(hand("Right",.49f),hand("Left",.51f)),200).ambiguous)
    }
    @Test fun predictionIsBoundedAndDoesNotLeadReversal() {
        val p=FuzzyMotionPredictor()
        p.update(.2f,.5f,0,60,1f)
        p.update(.3f,.5f,100,60,1f)
        val lead=p.update(.4f,.5f,200,60,1f)
        assertTrue(lead.x>.4f);assertTrue(lead.lead<=.01201f)
        assertEquals(.3f,p.update(.3f,.5f,300,60,1f).x,.0001f)
        p.reset();assertEquals(.8f,p.update(.8f,.5f,400,60,1f).x,.0001f)
    }
    @Test fun conflictsPreserveOwnerAndAbstainOnTie() {
        val a=IntentCandidate(0,false,.85f);val b=IntentCandidate(1,false,.83f)
        assertNull(FuzzyConflict.choose(null,listOf(a,b)))
        assertEquals(1,FuzzyConflict.choose(1,listOf(a,b)))
        assertEquals(0,FuzzyConflict.choose(null,listOf(a,b.copy(score=.50f))))
        assertEquals(1,FuzzyConflict.choose(null,listOf(a,b.copy(pinch=true))))
    }
}
