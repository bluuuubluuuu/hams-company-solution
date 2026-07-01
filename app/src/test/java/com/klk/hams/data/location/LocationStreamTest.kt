package com.klk.hams.data.location

import com.klk.hams.AppConfig
import com.klk.hams.data.model.LocationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for [LocationStream] freshness logic. The Android-dependent
 * pieces (FusedLocationProviderClient, ref-count start/stop, callback wiring)
 * are exercised on a real device in the manual emulator pass for Task 2.7.5.
 */
class LocationStreamTest {

    private fun snapshotAt(capturedAtMs: Long) = LocationSnapshot(
        latDecimal = 2.268721,
        lonDecimal = 103.282985,
        hdop = 1.5,
        satellites = 8,
        capturedAtMs = capturedAtMs
    )

    @Test fun nullSnapshotIsNotFresh() {
        assertFalse(LocationStream.isFreshAt(null, nowMs = 1_000_000))
    }

    @Test fun zeroAgeSnapshotIsFresh() {
        val now = 1_000_000L
        assertTrue(LocationStream.isFreshAt(snapshotAt(now), nowMs = now))
    }

    @Test fun snapshotJustBelowStalenessThresholdIsFresh() {
        val now = 1_000_000L
        val captured = now - (AppConfig.LOCATION_STREAM_STALENESS_MS - 1)
        assertTrue(LocationStream.isFreshAt(snapshotAt(captured), nowMs = now))
    }

    @Test fun snapshotAtExactlyStalenessThresholdIsFresh() {
        val now = 1_000_000L
        val captured = now - AppConfig.LOCATION_STREAM_STALENESS_MS
        assertTrue(LocationStream.isFreshAt(snapshotAt(captured), nowMs = now))
    }

    @Test fun snapshotPastStalenessThresholdIsStale() {
        val now = 1_000_000L
        val captured = now - (AppConfig.LOCATION_STREAM_STALENESS_MS + 1)
        assertFalse(LocationStream.isFreshAt(snapshotAt(captured), nowMs = now))
    }

    @Test fun futureSnapshotIsNotFresh() {
        // Defensive: capturedAtMs in the future (clock skew on a multi-core device)
        // should be rejected, not treated as instantly fresh.
        val now = 1_000_000L
        val captured = now + 50
        assertFalse(LocationStream.isFreshAt(snapshotAt(captured), nowMs = now))
    }

    @Test fun speedKmhNullWhenNoSpeed() {
        assertNull(LocationStream.speedKmh(hasSpeed = false, speedMs = 5f))
    }

    @Test fun speedKmhZeroWhenStationary() {
        assertEquals(0, LocationStream.speedKmh(hasSpeed = true, speedMs = 0f))
    }

    @Test fun speedKmhConvertsMsToKmh() {
        // 10 m/s = 36 km/h
        assertEquals(36, LocationStream.speedKmh(hasSpeed = true, speedMs = 10f))
    }

    @Test fun speedKmhRoundsToNearest() {
        // 1.5 m/s = 5.4 km/h -> 5 ; 2.0 m/s = 7.2 km/h -> 7
        assertEquals(5, LocationStream.speedKmh(hasSpeed = true, speedMs = 1.5f))
        assertEquals(7, LocationStream.speedKmh(hasSpeed = true, speedMs = 2.0f))
    }
}
