package com.klk.hams.push

import com.klk.hams.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GPS-degraded detector — local-only 293 fires once on the leading edge of
 * "HDOP > threshold" and stays silent until the fix recovers.
 */
class GpsDegradedDetectorTest {

    @Test fun `null hdop does not fire`() {
        val det = GpsDegradedDetector()
        assertNull(det.observe(null))
        assertNull(det.observe(null))
    }

    @Test fun `hdop at threshold is not degraded`() {
        // observe is strictly greater-than: 5.0 == threshold should not fire.
        val det = GpsDegradedDetector(threshold = 5.0)
        assertNull(det.observe(5.0))
    }

    @Test fun `hdop just above threshold fires 293 once`() {
        val det = GpsDegradedDetector(threshold = 5.0)
        assertEquals(AppConfig.EVENT_CODE_GPS_DEGRADED, det.observe(5.1))
        // continued degraded -- silent
        assertNull(det.observe(7.0))
        assertNull(det.observe(99.0))
    }

    @Test fun `recovery resets the latch and re-degrade fires again`() {
        val det = GpsDegradedDetector(threshold = 5.0)
        assertEquals(AppConfig.EVENT_CODE_GPS_DEGRADED, det.observe(6.0))
        // recover
        assertNull(det.observe(2.0))
        // re-degrade
        assertEquals(AppConfig.EVENT_CODE_GPS_DEGRADED, det.observe(8.0))
    }

    @Test fun `null hdop after degraded counts as recovery`() {
        // Conservative: an unknown fix is treated as not-degraded so the
        // latch resets. The next genuine bad fix will then fire again.
        val det = GpsDegradedDetector(threshold = 5.0)
        assertEquals(AppConfig.EVENT_CODE_GPS_DEGRADED, det.observe(6.0))
        assertNull(det.observe(null))
        assertEquals(AppConfig.EVENT_CODE_GPS_DEGRADED, det.observe(6.0))
    }

    @Test fun `default threshold matches AppConfig`() {
        val det = GpsDegradedDetector()
        // Must clear AppConfig.GPS_HDOP_DEGRADED_THRESHOLD (5.0) to fire.
        assertNull(det.observe(AppConfig.GPS_HDOP_DEGRADED_THRESHOLD))
        assertEquals(
            AppConfig.EVENT_CODE_GPS_DEGRADED,
            det.observe(AppConfig.GPS_HDOP_DEGRADED_THRESHOLD + 0.1)
        )
    }
}
