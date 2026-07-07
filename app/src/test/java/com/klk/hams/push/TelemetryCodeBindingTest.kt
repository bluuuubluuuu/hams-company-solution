package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryCodeBindingTest {
    @Test fun binding_released_maps_to_301() {
        assertEquals(301, TelemetryCode.eventCodeFor("binding_released"))
    }

    @Test fun device_bound_maps_to_303() {
        assertEquals(303, TelemetryCode.eventCodeFor("device_bound"))
    }

    @Test fun device_unbound_maps_to_304() {
        assertEquals(304, TelemetryCode.eventCodeFor("device_unbound"))
    }

    @Test fun removed_binding_taken_has_no_code() {
        assertEquals(null, TelemetryCode.eventCodeFor("binding_taken"))
    }
}
