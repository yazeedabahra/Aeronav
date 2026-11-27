package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class AssistedIntentTest {
    @Test fun faceOnAndFistAndWideSpreadDoNotArm() {
        assertEquals(0f,FuzzyPose.score(.05f,4f,1f),.001f)
        assertEquals(0f,FuzzyPose.score(.95f,.2f,1f),.001f)
        assertEquals(0f,FuzzyPose.score(.95f,4f,2.2f),.001f)
    }
    @Test fun partiallyExtendedNearEdgeHandCanArmOnlyWhenOptedIn() {
        val score=FuzzyPose.score(.68f,2.7f,1.3f)
        assertTrue(score>=FuzzyPose.threshold(0))
        val g=FuzzyIntentGate()
        assertFalse(g.update(false,score,.61f,true))
        assertFalse(g.update(true,score,.61f,true))
        assertTrue(g.update(true,score,.61f,true))
        assertFalse(g.update(true,score,.61f,false))
        assertFalse(g.update(true,score,.61f,true))
    }
    @Test fun boundedSensitivityFeedback() {
        assertEquals(.72f,FuzzyPose.threshold(-100),.001f)
        assertEquals(.50f,FuzzyPose.threshold(100),.001f)
    }
    @Test fun planExecutionRequiresTerminalGlobalNavigation() {
        assertTrue(AiCommandGate.executable(listOf(AiAction.SWIPE_RIGHT,AiAction.HOME)))
        assertTrue(AiCommandGate.executable(listOf(AiAction.BACK)))
        assertFalse(AiCommandGate.executable(listOf(AiAction.BACK,AiAction.SWIPE_UP)))
        assertFalse(AiCommandGate.executable(emptyList()))
        assertFalse(AiCommandGate.executable(List(5){AiAction.SWIPE_UP}))
    }
}
