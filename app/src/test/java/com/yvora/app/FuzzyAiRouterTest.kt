package com.yvora.app
import org.junit.Assert.*
import org.junit.Test

class FuzzyAiRouterTest {
    @Test fun localArabicAndEnglish() {
        assertEquals(listOf(AiAction.SWIPE_RIGHT),FuzzyAiRouter.route("اسحب يمين").actions)
        assertEquals(listOf(AiAction.SWIPE_UP,AiAction.BACK),FuzzyAiRouter.route("swipe up, then go back").actions)
        assertEquals(listOf(AiAction.SWIPE_LEFT,AiAction.HOME),FuzzyAiRouter.route("اسحب لليسار ثم الشاشة الرئيسية").actions)
        assertEquals(1f,FuzzyAiRouter.route("swipe right").localDegree,.0001f)
    }
    @Test fun similarNegationAndUnknownNeverExecute() {
        val neg=FuzzyAiRouter.route("do not swipe right")
        assertNull(neg.actions);assertTrue(neg.similarity>0f)
        assertNull(FuzzyAiRouter.route("لا اسحب يمين").actions)
        assertNull(FuzzyAiRouter.route("swipe right?").actions)
        assertNull(FuzzyAiRouter.route("swipe right then buy something").actions)
        assertNull(FuzzyAiRouter.route("back then swipe right").actions)
        assertNull(FuzzyAiRouter.route("swipe right;swipe right;swipe right;swipe right;swipe right").actions)
        assertNull(FuzzyAiRouter.route("").actions)
    }
}
