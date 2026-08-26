package com.klk.hams.provisioning

import com.klk.hams.data.repository.TaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSequenceTest {

    @Test fun strandsAndReportsCounts_whenMarkerLanded() = runTest {
        var stranded = false
        val outcome = ProvisioningEvents.runMarkAndStrand(
            countLost = { TaskRepository.UnsentWork(tasks = 2, cuts = 3) },
            pushMarker = { true },
            strandUnsentWork = { stranded = true },
        )
        assertTrue(stranded)
        assertTrue(outcome.landed)
        assertEquals(3, outcome.lost.cuts)
    }

    @Test fun strandsEvenWhenMarkerDidNotLand() = runTest {
        var stranded = false
        val outcome = ProvisioningEvents.runMarkAndStrand(
            countLost = { TaskRepository.UnsentWork(tasks = 1, cuts = 3) },
            pushMarker = { false },
            strandUnsentWork = { stranded = true },
        )
        assertTrue(stranded)
        assertEquals(false, outcome.landed)
    }

    @Test fun orderIsCountThenPushThenStrand() = runTest {
        val order = mutableListOf<String>()
        ProvisioningEvents.runMarkAndStrand(
            countLost = { order.add("count"); TaskRepository.UnsentWork(0, 0) },
            pushMarker = { order.add("push"); true },
            strandUnsentWork = { order.add("strand") },
        )
        assertEquals(listOf("count", "push", "strand"), order)
    }
}
