package com.klk.hams.push

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushGateTest {
    @Test fun tryLock_failsWhileHeld_succeedsAfterRelease() = runTest {
        PushGate.mutex.withLock {
            // A second acquirer (the deliver step) must be turned away while the
            // worker holds the gate - that is what prevents a duplicate 179 send.
            assertFalse(PushGate.mutex.tryLock())
        }
        // Once released, the deliver step can acquire it.
        assertTrue(PushGate.mutex.tryLock())
        PushGate.mutex.unlock()
    }
}
