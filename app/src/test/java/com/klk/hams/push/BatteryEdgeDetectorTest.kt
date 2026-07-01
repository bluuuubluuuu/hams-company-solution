package com.klk.hams.push

import com.klk.hams.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Battery edge bucket logic under dictionary v1.2.
 *
 * Buckets:
 *   - NORMAL   when pct >= AppConfig.BATTERY_WARN_THRESHOLD_PCT (20)
 *   - WARN     when 10 <= pct < 20
 *   - CRITICAL when pct < AppConfig.BATTERY_CRITICAL_THRESHOLD_PCT (10)
 *
 * Edges:
 *   - NORMAL -> WARN     -> 291
 *   - NORMAL -> CRITICAL -> 292 (steep drop skips 291)
 *   - WARN   -> CRITICAL -> 292
 *   - any upward / same  -> null
 */
class BatteryEdgeDetectorTest {

    // -- bucketOf -----------------------------------------------------------------

    @Test fun `100 percent is normal`() {
        assertEquals(BatteryEdgeDetector.BUCKET_NORMAL, BatteryEdgeDetector.bucketOf(100))
    }

    @Test fun `21 percent is normal`() {
        assertEquals(BatteryEdgeDetector.BUCKET_NORMAL, BatteryEdgeDetector.bucketOf(21))
    }

    @Test fun `20 percent is normal -- threshold is exclusive on the low side`() {
        // pct < 20 is WARN, so pct == 20 stays NORMAL.
        assertEquals(BatteryEdgeDetector.BUCKET_NORMAL, BatteryEdgeDetector.bucketOf(20))
    }

    @Test fun `19 percent is warn`() {
        assertEquals(BatteryEdgeDetector.BUCKET_WARN, BatteryEdgeDetector.bucketOf(19))
    }

    @Test fun `10 percent is warn -- critical threshold is exclusive on the low side`() {
        assertEquals(BatteryEdgeDetector.BUCKET_WARN, BatteryEdgeDetector.bucketOf(10))
    }

    @Test fun `9 percent is critical`() {
        assertEquals(BatteryEdgeDetector.BUCKET_CRITICAL, BatteryEdgeDetector.bucketOf(9))
    }

    @Test fun `0 percent is critical`() {
        assertEquals(BatteryEdgeDetector.BUCKET_CRITICAL, BatteryEdgeDetector.bucketOf(0))
    }

    // -- edgeEvent -- downward transitions ---------------------------------------

    @Test fun `normal to warn fires 291`() {
        assertEquals(
            AppConfig.EVENT_CODE_BATTERY_WARN,
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_NORMAL, BatteryEdgeDetector.BUCKET_WARN
            )
        )
    }

    @Test fun `normal to critical fires 292 -- steep drop skips 291`() {
        assertEquals(
            AppConfig.EVENT_CODE_BATTERY_CRITICAL,
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_NORMAL, BatteryEdgeDetector.BUCKET_CRITICAL
            )
        )
    }

    @Test fun `warn to critical fires 292`() {
        assertEquals(
            AppConfig.EVENT_CODE_BATTERY_CRITICAL,
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_WARN, BatteryEdgeDetector.BUCKET_CRITICAL
            )
        )
    }

    // -- edgeEvent -- first observation (null prev bucket) -----------------------

    @Test fun `first observation at any bucket is silent`() {
        // No persisted bucket yet -- caller stores newBucket as baseline and
        // does not emit. This prevents fresh installs / cleared prefs from
        // spuriously firing 291 or 292 just because the app first wakes up at
        // an already-low battery.
        assertNull(BatteryEdgeDetector.edgeEvent(null, BatteryEdgeDetector.BUCKET_NORMAL))
        assertNull(BatteryEdgeDetector.edgeEvent(null, BatteryEdgeDetector.BUCKET_WARN))
        assertNull(BatteryEdgeDetector.edgeEvent(null, BatteryEdgeDetector.BUCKET_CRITICAL))
    }

    @Test fun `downward transitions still fire after baseline is set`() {
        // First observation establishes baseline silently...
        assertNull(BatteryEdgeDetector.edgeEvent(null, BatteryEdgeDetector.BUCKET_NORMAL))
        // ...then a downward transition from that baseline must still fire.
        assertEquals(
            AppConfig.EVENT_CODE_BATTERY_WARN,
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_NORMAL, BatteryEdgeDetector.BUCKET_WARN
            )
        )
        assertEquals(
            AppConfig.EVENT_CODE_BATTERY_CRITICAL,
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_WARN, BatteryEdgeDetector.BUCKET_CRITICAL
            )
        )
    }

    // -- edgeEvent -- non-events --------------------------------------------------

    @Test fun `same bucket does not fire`() {
        assertNull(
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_NORMAL, BatteryEdgeDetector.BUCKET_NORMAL
            )
        )
        assertNull(
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_WARN, BatteryEdgeDetector.BUCKET_WARN
            )
        )
        assertNull(
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_CRITICAL, BatteryEdgeDetector.BUCKET_CRITICAL
            )
        )
    }

    @Test fun `upward transitions never fire`() {
        // critical -> warn (charging recovery)
        assertNull(
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_CRITICAL, BatteryEdgeDetector.BUCKET_WARN
            )
        )
        // critical -> normal
        assertNull(
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_CRITICAL, BatteryEdgeDetector.BUCKET_NORMAL
            )
        )
        // warn -> normal
        assertNull(
            BatteryEdgeDetector.edgeEvent(
                BatteryEdgeDetector.BUCKET_WARN, BatteryEdgeDetector.BUCKET_NORMAL
            )
        )
    }

    // -- realistic walks ---------------------------------------------------------

    @Test fun `100 to 25 to 21 stays silent -- same bucket throughout`() {
        // 100 -> 25
        var prev = BatteryEdgeDetector.bucketOf(100)
        var curr = BatteryEdgeDetector.bucketOf(25)
        assertNull(BatteryEdgeDetector.edgeEvent(prev, curr))
        // 25 -> 21
        prev = curr
        curr = BatteryEdgeDetector.bucketOf(21)
        assertNull(BatteryEdgeDetector.edgeEvent(prev, curr))
    }

    @Test fun `21 to 19 fires 291 once -- continued drop in same bucket stays silent`() {
        var prev = BatteryEdgeDetector.bucketOf(21)
        var curr = BatteryEdgeDetector.bucketOf(19)
        assertEquals(AppConfig.EVENT_CODE_BATTERY_WARN, BatteryEdgeDetector.edgeEvent(prev, curr))
        // 19 -> 15
        prev = curr
        curr = BatteryEdgeDetector.bucketOf(15)
        assertNull(BatteryEdgeDetector.edgeEvent(prev, curr))
        // 15 -> 11
        prev = curr
        curr = BatteryEdgeDetector.bucketOf(11)
        assertNull(BatteryEdgeDetector.edgeEvent(prev, curr))
    }

    @Test fun `11 to 9 fires 292`() {
        val prev = BatteryEdgeDetector.bucketOf(11)
        val curr = BatteryEdgeDetector.bucketOf(9)
        assertEquals(AppConfig.EVENT_CODE_BATTERY_CRITICAL, BatteryEdgeDetector.edgeEvent(prev, curr))
    }

    @Test fun `25 to 18 fires 291 even after a prior recovery`() {
        // simulates: ... -> 9 (CRITICAL) -> charge to 25 (NORMAL) -> drop to 18 (WARN)
        val prev = BatteryEdgeDetector.bucketOf(25)
        val curr = BatteryEdgeDetector.bucketOf(18)
        assertEquals(AppConfig.EVENT_CODE_BATTERY_WARN, BatteryEdgeDetector.edgeEvent(prev, curr))
    }
}
