package com.yvora.app
import org.junit.Assert.*
import org.junit.Test
class AiCapturePolicyTest {
    @Test fun onlyEmptyUnfocusedForegroundAllowsCapture(){
        for(foreground in listOf(false,true))
            for(hasKey in listOf(false,true))
                for(focused in listOf(false,true))
                    assertEquals(!(foreground && !hasKey && !focused),AiCapturePolicy.protect(foreground,hasKey,focused))
    }
}
