package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryCodeTest {
    @Test fun mappings_matchGprsSpec() {
        assertEquals(29, TelemetryCode.eventCodeFor("boot"))
        assertEquals(40, TelemetryCode.eventCodeFor("shutdown"))
        assertEquals(24, TelemetryCode.eventCodeFor("gps_lost"))
        assertEquals(25, TelemetryCode.eventCodeFor("gps_recovery"))
        assertEquals(41, TelemetryCode.eventCodeFor("stop_moving"))
        assertEquals(42, TelemetryCode.eventCodeFor("start_moving"))
        assertEquals(26, TelemetryCode.eventCodeFor("screen_off"))
        assertEquals(27, TelemetryCode.eventCodeFor("screen_on"))
        assertEquals(43, TelemetryCode.eventCodeFor("power_connected"))
        assertEquals(44, TelemetryCode.eventCodeFor("power_disconnected"))
    }

    @Test fun unknownType_isNull() {
        assertNull(TelemetryCode.eventCodeFor("nope"))
    }

    @Test fun codes_areUnique() {
        val types = listOf(
            "boot",
            "shutdown",
            "screen_on",
            "screen_off",
            "power_connected",
            "power_disconnected",
            "start_moving",
            "stop_moving",
            "gps_lost",
            "gps_recovery",
        )
        val codes = types.map { TelemetryCode.eventCodeFor(it)!! }
        assertEquals(codes.size, codes.toSet().size)
    }
}
