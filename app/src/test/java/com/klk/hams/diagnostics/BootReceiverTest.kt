package com.klk.hams.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    private val tol = BootReceiver.BOOT_DEDUPE_TOLERANCE_MS

    @Test fun firstBoot_noPrior_isNotPhantom() {
        assertFalse(BootReceiver.isPhantomBoot(1_000_000L, Long.MIN_VALUE, tol))
    }

    @Test fun sameSession_rebroadcast_isPhantom() {
        // Re-broadcast within the same boot session: near-identical instant.
        assertTrue(BootReceiver.isPhantomBoot(1_000_500L, 1_000_000L, tol))
    }

    @Test fun ntpJitter_withinTolerance_isPhantom() {
        // A few seconds of clock correction after boot must not count as a new boot.
        assertTrue(BootReceiver.isPhantomBoot(1_000_000L, 1_000_000L + 5_000L, tol))
    }

    @Test fun genuineReboot_laterInstant_isNotPhantom() {
        // Device ran a while, then rebooted -> boot instant advances well beyond tolerance.
        assertFalse(BootReceiver.isPhantomBoot(1_600_000L, 1_000_000L, tol))
    }

    @Test fun exactlyAtTolerance_isNotPhantom() {
        assertFalse(BootReceiver.isPhantomBoot(1_000_000L + tol, 1_000_000L, tol))
    }
}
