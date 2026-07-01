package com.klk.hams.diagnostics

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticTypeTest {
    @Test fun maps_known_actions() {
        assertEquals(DiagnosticType.SCREEN_ON, DiagnosticType.fromAction(Intent.ACTION_SCREEN_ON))
        assertEquals(DiagnosticType.SCREEN_OFF, DiagnosticType.fromAction(Intent.ACTION_SCREEN_OFF))
        assertEquals(DiagnosticType.POWER_CONNECTED, DiagnosticType.fromAction(Intent.ACTION_POWER_CONNECTED))
        assertEquals(DiagnosticType.POWER_DISCONNECTED, DiagnosticType.fromAction(Intent.ACTION_POWER_DISCONNECTED))
        assertEquals(DiagnosticType.SHUTDOWN, DiagnosticType.fromAction(Intent.ACTION_SHUTDOWN))
        assertEquals(DiagnosticType.BOOT, DiagnosticType.fromAction(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test fun unknown_action_is_null() {
        assertNull(DiagnosticType.fromAction("com.example.OTHER"))
        assertNull(DiagnosticType.fromAction(null))
    }

    @Test fun wire_strings_are_stable() {
        assertEquals("screen_on", DiagnosticType.SCREEN_ON.wire)
        assertEquals("boot", DiagnosticType.BOOT.wire)
    }
}
