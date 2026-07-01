package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PushNotifierTest {

    @Test fun idleProducesNoNotification() {
        assertNull(PushNotifier.contentFor(PushUiState.Idle))
    }

    @Test fun pendingWifiProducesNoNotification() {
        assertNull(PushNotifier.contentFor(PushUiState.PendingWifi(5)))
    }

    @Test fun pushingShowsTaskLevelProgress() {
        // total/done are TASKS now (2026-05-13 field-feedback change).
        // "task 2 of 4" when 1 task has been done so far (current = done + 1).
        val c = PushNotifier.contentFor(PushUiState.Pushing(total = 4, done = 1))!!
        assertEquals("HAMS — Uploading task 2 of 4", c.title)
        assertEquals(true, c.persistent)
        assertEquals(1, c.progressDone)
        assertEquals(4, c.progressTotal)
        assertTrue("post-first progress updates must be silent", c.silent)
    }

    @Test fun pushingFirstUpdateAlertsOnce() {
        // 2026-05-15 visibility tweak: the first progress post per run
        // (done = 0) is non-silent so the worker notices the upload start.
        val c = PushNotifier.contentFor(PushUiState.Pushing(total = 5, done = 0))!!
        assertEquals("HAMS — Uploading task 1 of 5", c.title)
        assertFalse("first progress update must alert once", c.silent)
    }

    @Test fun pushingCurrentTaskCappedAtTotal() {
        // When done == total (the last task just finished), the engine still
        // reports a final progress callback before the terminal — current
        // should not display "task 5 of 4".
        val c = PushNotifier.contentFor(PushUiState.Pushing(total = 4, done = 4))!!
        assertEquals("HAMS — Uploading task 4 of 4", c.title)
    }

    @Test fun completedShowsCheckmarkAndAlerts() {
        // Outcome A — all clean. 2026-05-15 (second iteration): unified
        // dismissable-banner model — no auto-dismiss; user clears manually.
        val c = PushNotifier.contentFor(PushUiState.Completed(tasks = 3, at = Instant.EPOCH))!!
        assertEquals("HAMS — 3 tasks uploaded successfully ✓", c.title)
        assertFalse(c.persistent)
        assertFalse(c.silent)
        assertNull(c.timeoutMs)
    }

    @Test fun failedShowsReasonAndAlerts() {
        val c = PushNotifier.contentFor(PushUiState.Failed(reason = "timeout", pending = 2))!!
        assertEquals("HAMS — Upload paused: timeout (2 left)", c.title)
        assertTrue(c.persistent)
        assertFalse(c.silent)
    }

    @Test fun terminalAllCleanFormat() {
        val c = PushNotifier.terminalAllClean(tasksUploaded = 2)
        assertEquals("HAMS — 2 tasks uploaded successfully ✓", c.title)
        assertFalse(c.persistent)
        assertNull(c.timeoutMs)
        assertFalse(c.silent)
    }

    @Test fun terminalPartialRetryFormat() {
        val c = PushNotifier.terminalPartialRetry(tasksUploaded = 1, tasksTotal = 2, tasksRemaining = 1)
        assertEquals(
            "HAMS — 1 of 2 tasks uploaded · 1 remaining · will retry on Wi-Fi",
            c.title
        )
        assertFalse(c.persistent)
        assertNull(c.timeoutMs)
        assertFalse(c.silent)
    }

    @Test fun terminalAllPausedFormat() {
        val c = PushNotifier.terminalAllPaused(tasksUnsent = 2)
        assertEquals(
            "HAMS — Upload paused · 2 tasks unsent · will retry on Wi-Fi",
            c.title
        )
        assertFalse(c.persistent)
        assertNull(c.timeoutMs)
    }

    @Test fun terminalRejectedFormat() {
        val c = PushNotifier.terminalRejected(tasksUploaded = 1, tasksTotal = 2, tasksRejected = 1)
        assertEquals(
            "HAMS — 1 of 2 tasks uploaded · 1 rejected by server — check device setup",
            c.title
        )
        assertFalse(c.persistent)
        assertFalse(c.silent)
        assertNull(c.timeoutMs)
        assertNotNull(c)  // sanity
    }
}
