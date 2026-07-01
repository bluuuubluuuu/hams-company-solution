package com.klk.hams.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [isPushableNetwork] — the pure capability check that decides
 * whether a network the system reported is one we should push over.
 * Wi-Fi transport + unmetered + validated.
 */
class WifiPushMonitorTest {

    @Test fun accepts_wifiUnmeteredValidated() {
        assertEquals(true, isPushableNetwork(wifi = true, unmetered = true, validated = true))
    }

    @Test fun rejects_metered() {
        assertEquals(false, isPushableNetwork(wifi = true, unmetered = false, validated = true))
    }

    @Test fun rejects_notValidated() {
        assertEquals(false, isPushableNetwork(wifi = true, unmetered = true, validated = false))
    }

    @Test fun rejects_nonWifi() {
        assertEquals(false, isPushableNetwork(wifi = false, unmetered = true, validated = true))
    }
}
