package com.klk.hams.provisioning

import com.klk.hams.data.repository.TaskRepository
import com.klk.hams.diagnostics.DiagnosticType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTypeTest {

    @Test fun cleanRelease_emits304() {
        assertEquals(
            DiagnosticType.DEVICE_UNBOUND,
            ProvisioningEvents.releaseTypeFor(TaskRepository.UnsentWork(tasks = 0, cuts = 0)),
        )
    }

    @Test fun strandedCuts_emit302() {
        assertEquals(
            DiagnosticType.WORK_STRANDED,
            ProvisioningEvents.releaseTypeFor(TaskRepository.UnsentWork(tasks = 3, cuts = 47)),
        )
    }

    @Test fun heartbeatsOnly_emit304_becauseNoHarvestIsLost() {
        // A task holding only unsent beacons has no harvest to lose. 302 means
        // "cuts lost"; emitting it here would raise a false alarm.
        assertEquals(
            DiagnosticType.DEVICE_UNBOUND,
            ProvisioningEvents.releaseTypeFor(TaskRepository.UnsentWork(tasks = 1, cuts = 0)),
        )
    }
}
