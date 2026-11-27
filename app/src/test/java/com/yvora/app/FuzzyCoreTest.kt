package com.yvora.app

import org.junit.Assert.*
import org.junit.Test

class FuzzyCoreTest {
    @Test fun dualOperatorsAndDeMorgan() {
        for(i in 0..10) for(j in 0..10) {
            val a=i/10f;val b=j/10f
            for((t,s) in listOf(TNorm.MINIMUM to SNorm.MAXIMUM,TNorm.PRODUCT to SNorm.ALGEBRAIC_SUM)) {
                assertEquals(FuzzyCore.not(FuzzyCore.and(a,b,t)),FuzzyCore.or(FuzzyCore.not(a),FuzzyCore.not(b),s),.0001f)
                assertEquals(FuzzyCore.not(FuzzyCore.or(a,b,s)),FuzzyCore.and(FuzzyCore.not(a),FuzzyCore.not(b),t),.0001f)
                assertEquals(a,FuzzyCore.and(a,1f,t),.0001f)
                assertEquals(a,FuzzyCore.or(a,0f,s),.0001f)
            }
        }
    }
    @Test fun maxMinAlphaAndHeight() {
        val v=FuzzyCore.compose(floatArrayOf(.8f,.7f),arrayOf(floatArrayOf(.9f,.2f),floatArrayOf(.4f,.8f)))
        assertArrayEquals(floatArrayOf(.8f,.7f),v,.0001f)
        assertEquals(.8f,FuzzyCore.height(v),.0001f)
        assertTrue(FuzzyCore.alphaCut(v[0],.8f))
        assertFalse(FuzzyCore.alphaCut(Float.NaN,.5f))
    }
    @Test fun inferenceAndFallback() {
        assertEquals(.5f,FuzzyCore.sugeno(floatArrayOf(.5f,.5f),floatArrayOf(0f,1f),.2f),.0001f)
        assertEquals(.2f,FuzzyCore.sugeno(floatArrayOf(0f),floatArrayOf(1f),.2f),.0001f)
        assertEquals(.5f,FuzzyCore.mamdani(floatArrayOf(1f),arrayOf(floatArrayOf(0f,1f,0f)),floatArrayOf(0f,.5f,1f),0f),.0001f)
    }
    @Test fun controllersBoundedAndQualityRejectsStale() {
        assertEquals(.095f,FuzzyControllers.cursorTau(0f),.0001f)
        assertEquals(.018f,FuzzyControllers.cursorTau(1f),.0001f)
        for(i in 0..100) {
            val speed=i/100f
            assertEquals(1f,FuzzyControllers.speedDegrees(speed).sum(),.0001f)
            assertTrue(FuzzyControllers.thermalInterval(90,speed,false) in 90..130)
        }
        assertEquals(0f,FuzzyControllers.quality(.95f,500),.0001f)
        assertEquals(0f,FuzzyControllers.assistedScore(.99f,.99f,false),.0001f)
        assertTrue(FuzzyControllers.thermalInterval(90,FuzzyControllers.heatDegree(390,0),false)>90)
        assertEquals(150L,FuzzyControllers.thermalInterval(150,1f,true))
    }
}
