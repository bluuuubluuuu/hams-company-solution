package com.klk.hams.provisioning

import com.klk.hams.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one policy the human ranked explicitly: **the strand is
 * unconditional**. Misfiling harvest onto the wrong worker is worse than losing
 * it, so [ProvisioningEvents.runReleaseSequence] must strand every unsent row
 * whether or not the 302/304 marker reached the gateway.
 *
 * `notLanded_stillStrands` is the regression guard. Re-wrapping the strand in
 * `if (landed)` compiles, passes lint, and passes every other test in the suite;
 * it must fail here. There is no OTA update path for these handsets — a
 * regression that ships means physically reflashing every device.
 */
class ReleaseSequenceTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        var landed = true
        var unsent = TaskRepository.UnsentWork(tasks = 0, cuts = 0)

        fun run(): ProvisioningEvents.ReleaseOutcome = runBlocking {
            ProvisioningEvents.runReleaseSequence(
                finalizeActiveTask = { calls += "finalize" },
                countUnsentWork = { calls += "count"; unsent },
                pushMarker = { u -> calls += "push(${u.tasks}/${u.cuts})"; landed },
                strandUnsentWork = { calls += "strand" },
            )
        }
    }

    @Test fun markerLanded_strands() {
        val r = Recorder().apply {
            landed = true
            unsent = TaskRepository.UnsentWork(tasks = 3, cuts = 47)
        }
        val outcome = r.run()

        assertTrue("strand must run when the marker landed", "strand" in r.calls)
        assertTrue(outcome.landed)
        assertEquals(TaskRepository.UnsentWork(tasks = 3, cuts = 47), outcome.lost)
    }

    @Test fun notLanded_stillStrands() {
        // REGRESSION GUARD. Gateway unreachable: no Wialon receipt exists, and the
        // rows must still be stranded. Leaving them pushed = 0 would upload one
        // worker's harvest under the next unit this handset binds to.
        val r = Recorder().apply {
            landed = false
            unsent = TaskRepository.UnsentWork(tasks = 3, cuts = 47)
        }
        val outcome = r.run()

        assertTrue(
            "strand MUST run even when the marker did not land — the strand is unconditional",
            "strand" in r.calls,
        )
        assertTrue("the flag reports the receipt, it does not gate the strand", !outcome.landed)
        assertEquals(TaskRepository.UnsentWork(tasks = 3, cuts = 47), outcome.lost)
    }

    @Test fun cleanRelease_withNothingPending_stillStrands() {
        val r = Recorder().apply {
            landed = false
            unsent = TaskRepository.UnsentWork(tasks = 0, cuts = 0)
        }
        r.run()

        assertTrue("strand MUST run even with nothing counted", "strand" in r.calls)
    }

    @Test fun order_isFinalizeThenCountThenPushThenStrand() {
        // The ordering is load-bearing: an active task is invisible to the count
        // and the flush until it is finalized (issue A3); the marker must reach
        // the OLD unit before the caller clears or re-binds; the strand comes
        // last so the counts describe what was actually discarded.
        val r = Recorder().apply { unsent = TaskRepository.UnsentWork(tasks = 1, cuts = 9) }
        r.run()

        assertEquals(listOf("finalize", "count", "push(1/9)", "strand"), r.calls)
    }
}
