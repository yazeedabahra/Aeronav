package com.yvora.app

import org.junit.Assert.*
import org.junit.Test

class AiCommandGateTest {
    @Test fun onlyExactBoundedNavigationTokens() {
        assertEquals(listOf(AiAction.SWIPE_LEFT,AiAction.HOME), AiCommandGate.parse("SWIPE_LEFT\nHOME"))
        for (bad in listOf("UNSUPPORTED","TAP 10 20","BACK\nDELETE","HOME\nHOME\nHOME\nHOME\nHOME","Here is BACK","", "back")) assertNull(AiCommandGate.parse(bad))
    }
}
