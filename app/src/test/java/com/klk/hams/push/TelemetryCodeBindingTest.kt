package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryCodeBindingTest {
    @Test fun binding_released_maps_to_301() {
        assertEquals(301, TelemetryCode.eventCodeFor("binding_released"))
    }

    @Test fun binding_taken_maps_to_302() {
        assertEquals(302, TelemetryCode.eventCodeFor("binding_taken"))
    }
}
