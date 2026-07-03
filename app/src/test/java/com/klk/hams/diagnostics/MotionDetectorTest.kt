package com.klk.hams.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionDetectorTest {
    private fun detector() = MotionDetector(
        startKmh = 1.5,
        stopKmh = 0.5,
        debounceMs = 15_000,
    )

    @Test
    fun sustainedSpeed_emitsStartMoving_once() {
        val detector = detector()

        assertNull(detector.onSpeed(3, 0))
        assertNull(detector.onSpeed(3, 10_000))
        assertEquals(DiagnosticType.START_MOVING, detector.onSpeed(3, 15_000))
        assertNull(detector.onSpeed(3, 20_000))
    }

    @Test
    fun sustainedStop_emitsStopMoving() {
        val detector = detector()
        detector.onSpeed(3, 0)
        detector.onSpeed(3, 15_000)

        assertNull(detector.onSpeed(0, 16_000))
        assertEquals(DiagnosticType.STOP_MOVING, detector.onSpeed(0, 31_000))
    }

    @Test
    fun brief_spike_does_not_emit() {
        val detector = detector()

        assertNull(detector.onSpeed(3, 0))
        assertNull(detector.onSpeed(0, 5_000))
        assertNull(detector.onSpeed(0, 30_000))
    }

    @Test
    fun null_speed_is_ignored() {
        val detector = detector()

        assertNull(detector.onSpeed(null, 0))
        assertNull(detector.onSpeed(null, 30_000))
    }

    @Test
    fun first_observation_initialises_silently() {
        val detector = detector()

        assertNull(detector.onSpeed(10, 0))
    }
}
