package com.yvora.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticGateTest {
    @Test fun exactActionAndExplanationRequired() {
        assertEquals(RepairAction.INVESTIGATE_CODE,DiagnosticGate.parse("INVESTIGATE_CODE\nInspect tests")?.action)
        assertNull(DiagnosticGate.parse("DELETE\nDo something"))
        assertNull(DiagnosticGate.parse("LOWER_CAMERA_DETAIL"))
        assertNull(DiagnosticGate.parse("LOWER_CAMERA_DETAIL;HOME\nTest"))
    }
    @Test fun onlyAllowedReversibleSetting() {
        assertTrue(DiagnosticGate.canLowerDetail(RepairAction.LOWER_CAMERA_DETAIL,true))
        assertFalse(DiagnosticGate.canLowerDetail(RepairAction.LOWER_CAMERA_DETAIL,false))
        assertFalse(DiagnosticGate.canLowerDetail(RepairAction.INVESTIGATE_CODE,true))
        assertFalse(DiagnosticGate.canLowerDetail(RepairAction.KEEP_SETTINGS,true))
    }
}
