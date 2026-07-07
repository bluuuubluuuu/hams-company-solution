package com.klk.hams.provisioning

import com.klk.hams.provisioning.ProvisioningClient.Companion.parseVerifyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyResponseParserTest {
    @Test fun bound_status_maps_to_Bound() {
        assertEquals(VerifyResult.Bound, parseVerifyResponse(200, """{"bound":true,"status":"bound"}"""))
    }

    @Test fun released_status_maps_to_Released() {
        assertEquals(VerifyResult.Released, parseVerifyResponse(200, """{"bound":false,"status":"released"}"""))
    }

    @Test fun bound_other_status_maps_to_BoundOther() {
        assertEquals(VerifyResult.BoundOther, parseVerifyResponse(200, """{"bound":false,"status":"bound_other"}"""))
    }

    @Test fun not_found_status_maps_to_Keep() {
        assertTrue(parseVerifyResponse(200, """{"bound":false,"status":"not_found"}""") is VerifyResult.Keep)
    }

    @Test fun unauthorized_maps_to_Keep() {
        assertTrue(parseVerifyResponse(401, """{"error":"unauthorized"}""") is VerifyResult.Keep)
    }

    @Test fun network_failure_code_maps_to_Keep() {
        assertTrue(parseVerifyResponse(-1, "timeout") is VerifyResult.Keep)
    }
}
