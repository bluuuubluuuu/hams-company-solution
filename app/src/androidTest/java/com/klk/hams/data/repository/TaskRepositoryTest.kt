package com.klk.hams.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.klk.hams.data.db.AppDatabase
import com.klk.hams.data.model.EventEntity
import com.klk.hams.data.model.LocationSnapshot
import com.klk.hams.data.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Codex finding #2 and #3 fixes (2026-05-15).
 * Runs against an in-memory Room v2 database on a connected device or
 * emulator via `:app:connectedDebugAndroidTest`.
 *
 * Tests are added per the implementation plan
 * `docs/superpowers/plans/2026-05-15-codex-adversarial-fixes.md`:
 *   - Task 1.6 — terminal-state semantics
 *   - Task 2.3 — retention sweep (added when purgeStaleTerminalTasks lands)
 *   - Task 3.3 — callback-after-commit (added when the hoist lands)
 */
@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TaskRepository(db)
    }

    @After fun tearDown() { db.close() }

    // ---- Task 1.6 — terminal-state semantics (Codex finding #2) ----

    @Test fun markTaskTerminalState_rejectedEventYieldsFailed() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 1, eventCode = 179)
        insertEvent(taskId, pushed = 2, eventCode = 179)

        repo.markTaskTerminalState(taskId)

        assertNull(taskById(taskId, "pending"))
        assertNotNull(taskById(taskId, "failed"))
        assertNull(taskById(taskId, "uploaded"))
    }

    @Test fun markTaskTerminalState_allUploadedYieldsUploaded() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 1, eventCode = 179)
        insertEvent(taskId, pushed = 1, eventCode = 179)

        repo.markTaskTerminalState(taskId)

        assertNotNull(taskById(taskId, "uploaded"))
        assertNull(taskById(taskId, "failed"))
    }

    @Test fun markTaskTerminalState_anyPendingNoOp() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 0, eventCode = 179)

        repo.markTaskTerminalState(taskId)

        assertNotNull(taskById(taskId, "pending"))
        assertNull(taskById(taskId, "uploaded"))
        assertNull(taskById(taskId, "failed"))
    }

    // ---- Task 2.3 — retention sweep ----

    @Test fun purgeStaleTerminalTasks_deletesOldUploadedFailedDiscarded() = runBlocking {
        val oldIso = "2020-01-01T00:00:00Z"
        val recentIso = java.time.Instant.now().toString()
        val tUploadedOld = insertTaskWithCreatedAt("uploaded", oldIso)
        val tFailedOld   = insertTaskWithCreatedAt("failed",   oldIso)
        val tDiscardOld  = insertTaskWithCreatedAt("discarded", oldIso)
        val tUploadedNew = insertTaskWithCreatedAt("uploaded", recentIso)
        val tPendingOld  = insertTaskWithCreatedAt("pending",  oldIso)

        val deleted = repo.purgeStaleTerminalTasks(retentionDays = 7)

        assertEquals(3, deleted)
        assertNull(findById(tUploadedOld))
        assertNull(findById(tFailedOld))
        assertNull(findById(tDiscardOld))
        assertNotNull(findById(tUploadedNew))
        assertNotNull(findById(tPendingOld))
    }

    @Test fun purgeStaleTerminalTasks_cascadesEvents() = runBlocking {
        val oldIso = "2020-01-01T00:00:00Z"
        val taskId = insertTaskWithCreatedAt("uploaded", oldIso)
        insertEvent(taskId, pushed = 1, eventCode = 179)
        insertEvent(taskId, pushed = 1, eventCode = 179)
        assertEquals(2, db.eventDao().countForTask(taskId))

        repo.purgeStaleTerminalTasks(retentionDays = 7)

        assertEquals(0, db.eventDao().countForTask(taskId))
    }

    // ---- Task 3.3 — callback fires after commit (Codex finding #3) ----

    @Test fun saveActiveTask_callbackFiresAfterCommit() = runBlocking {
        val started = "2026-05-15T00:00:00Z"
        db.taskDao().insert(
            Task(
                deviceId = "TEST",
                taskSeq = 1,
                taskDate = "2026-05-15",
                startedAt = started,
                endedAt = null,
                plusCount = 1,
                minusCount = 0,
                netCount = 1,
                pushStatus = "active",
                saveType = null,
                createdAt = started,
                updatedAt = started,
            )
        )

        var observedPending = -1
        repo.onTaskFinalized = {
            kotlinx.coroutines.runBlocking {
                observedPending = db.taskDao().pendingTasks().size
            }
        }

        val savedId = repo.saveActiveTask(
            saveType = "manual",
            location = null,
            batteryPct = 90.0,
        )

        // Pre-fix (callback inside withTransaction) this would have been 0.
        assertEquals(1, observedPending)
        assertNotNull(savedId)
    }

    @Test fun rolloverActiveTaskIfStale_callbackFiresAfterCommit() = runBlocking {
        // Seed an active task with a stale task_date (year 2020) so rollover
        // triggers regardless of the device clock's current "today".
        val yesterdayIso = "2020-01-01T10:00:00Z"
        db.taskDao().insert(
            Task(
                deviceId = "TEST",
                taskSeq = 1,
                taskDate = "2020-01-01",
                startedAt = yesterdayIso,
                endedAt = null,
                plusCount = 1,
                minusCount = 0,
                netCount = 1,
                pushStatus = "active",
                saveType = null,
                createdAt = yesterdayIso,
                updatedAt = yesterdayIso,
            )
        )

        var observedPending = -1
        repo.onTaskFinalized = {
            kotlinx.coroutines.runBlocking {
                observedPending = db.taskDao().pendingTasks().size
            }
        }

        val savedId = repo.rolloverActiveTaskIfStale()

        // Pre-fix the callback observed 0 pending; post-fix it sees the just-
        // finalised row.
        assertEquals(1, observedPending)
        assertNotNull(savedId)
    }

    @Test fun recordPlus_usesInjectedDeviceIdProviderForNewTask() = runBlocking {
        repo = TaskRepository(
            db = db,
            deviceIdProvider = { "OC154_H099" },
        )

        repo.recordPlus(
            location = LocationSnapshot(
                latDecimal = 2.27,
                lonDecimal = 103.28,
                hdop = 1.5,
                satellites = 8,
            ),
            batteryPct = 90.0,
        )

        assertEquals("OC154_H099", db.taskDao().getActiveTask()?.deviceId)
    }

    @Test fun eventSpeedColumnRoundTrips() = runBlocking {
        val taskId = insertPendingTask()
        db.eventDao().insert(
            EventEntity(
                taskId = taskId,
                eventType = "plus",
                eventCode = 179,
                timestamp = "2026-06-29T00:00:00Z",
                latDecimal = 2.27,
                lonDecimal = 103.28,
                hdop = 1.5,
                satellites = 8,
                speed = 42,
                batteryPct = 90.0,
                workCount = 1,
                countAfter = 1,
                pushed = 0,
                createdAt = "2026-06-29T00:00:00Z",
            )
        )
        val stored = db.eventDao().getPending(10).first { it.taskId == taskId }
        assertEquals(42, stored.speed)
    }

    @Test fun recordPlus_persistsSpeedFromSnapshot() = runBlocking {
        repo.recordPlus(
            location = LocationSnapshot(
                latDecimal = 2.27,
                lonDecimal = 103.28,
                hdop = 1.5,
                satellites = 8,
                speedKmh = 27,
            ),
            batteryPct = 90.0,
        )
        val plus = db.eventDao().getPending(10).first { it.eventCode == 179 }
        assertEquals(27, plus.speed)
    }

    @Test fun diagnosticRoundTrips() = runBlocking {
        db.diagnosticDao().insert(
            com.klk.hams.data.model.DiagnosticEntity(
                type = "boot",
                timestamp = "2026-06-29T00:00:00Z",
                batteryPct = 88.0,
                createdAt = "2026-06-29T00:00:00Z",
            )
        )
        val rows = db.diagnosticDao().recent(10)
        assertEquals(1, rows.size)
        assertEquals("boot", rows.first().type)
        assertEquals(88.0, rows.first().batteryPct!!, 0.001)
    }

    @Test fun purgeStaleDiagnostics_deletesOldKeepsRecent() = runBlocking {
        val oldIso = "2020-01-01T00:00:00Z"
        val recentIso = java.time.Instant.now().toString()
        db.diagnosticDao().insert(
            com.klk.hams.data.model.DiagnosticEntity(
                type = "screen_on", timestamp = oldIso, batteryPct = 50.0, createdAt = oldIso,
            )
        )
        db.diagnosticDao().insert(
            com.klk.hams.data.model.DiagnosticEntity(
                type = "screen_off", timestamp = recentIso, batteryPct = 50.0, createdAt = recentIso,
            )
        )

        val deleted = repo.purgeStaleDiagnostics(retentionDays = 7)

        assertEquals(1, deleted)
        val rows = db.diagnosticDao().recent(10)
        assertEquals(1, rows.size)
        assertEquals("screen_off", rows.first().type)
    }

    // ---- 302 work_stranded — leftover accounting (2026-07-23) ----

    @Test fun countUnsentWork_countsTasksAndCutsSeparately() = runBlocking {
        val taskA = insertPendingTask()
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 0, eventCode = 35)    // beacon — not a cut
        val taskB = insertPendingTask()
        insertEvent(taskB, pushed = 0, eventCode = 179)
        insertEvent(taskB, pushed = 1, eventCode = 179)   // already uploaded

        val unsent = repo.countUnsentWork()

        assertEquals(2, unsent.tasks)
        assertEquals(3, unsent.cuts)   // 179 at pushed = 0 only
    }

    @Test fun countUnsentWork_ignoresAlreadyStrandedRows() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 2, eventCode = 179)

        val unsent = repo.countUnsentWork()

        // A later release must not re-report work stranded by an earlier one.
        assertEquals(0, unsent.tasks)
        assertEquals(0, unsent.cuts)
    }

    @Test fun strandUnsentWork_marksRowsRejected_andTaskFailed() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 0, eventCode = 179)
        insertEvent(taskId, pushed = 0, eventCode = 35)

        val stranded = repo.strandUnsentWork()

        assertEquals(2, stranded)                       // 35 is stranded too
        assertEquals(0, repo.countUnsentWork().cuts)
        assertEquals(0, repo.pendingTasks().size)
        assertNotNull(taskById(taskId, "failed"))
    }

    /**
     * Three-way outcome: only `pushed = 0` moves. Uploaded work must never be
     * resurrected as rejected, and rows stranded by an earlier release must not
     * be touched a second time.
     */
    @Test fun strandUnsentWork_leavesUploadedAndAlreadyStrandedRowsUntouched() = runBlocking {
        val taskId = insertPendingTask()
        val pendingRow = insertEvent(taskId, pushed = 0, eventCode = 179)
        val uploadedRow = insertEvent(taskId, pushed = 1, eventCode = 179)
        val strandedRow = insertEvent(taskId, pushed = 2, eventCode = 179)

        val stranded = repo.strandUnsentWork()

        assertEquals(1, stranded)
        assertEquals(2, pushedStateOf(pendingRow))
        assertEquals(1, pushedStateOf(uploadedRow))
        assertEquals(2, pushedStateOf(strandedRow))
        assertNotNull(taskById(taskId, "failed"))
    }

    @Test fun strandUnsentWork_noPendingRows_isNoOp() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 1, eventCode = 179)

        val stranded = repo.strandUnsentWork()

        assertEquals(0, stranded)
        assertNotNull(taskById(taskId, "uploaded"))
        assertNull(taskById(taskId, "failed"))
    }

    // ---- Approach A - snapshot accounting (2026-07-23) ----

    @Test fun pendingCutIds_returnsOnly179AtPushedZero() = runBlocking {
        val taskA = insertPendingTask()
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 1, eventCode = 179)   // already delivered
        insertEvent(taskA, pushed = 0, eventCode = 35)    // beacon, not a cut

        val snapshot = repo.pendingCutIds()

        assertEquals(2, snapshot.size)   // the two pushed=0 179 rows only
    }

    @Test fun lostAmong_countsSnapshotCutsNotUploaded_afterDeliver() = runBlocking {
        val taskA = insertPendingTask()
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 0, eventCode = 179)

        val snapshot = repo.pendingCutIds()               // taken BEFORE deliver
        assertEquals(3, snapshot.size)

        // Simulate the deliver outcome using the ids the snapshot actually returned
        // (no id arithmetic): one delivered, one rejected, one left un-sent.
        repo.markEventUploaded(snapshot[0])               // pushed -> 1 (delivered)
        repo.markEventRejected(snapshot[1], "frame rejected")  // pushed -> 2 (lost)
        // snapshot[2] stays pushed=0 (timed out mid-deliver) -> lost

        val lost = repo.lostAmong(snapshot)

        assertEquals(2, lost.cuts)    // rejected + still-unsent count as lost; delivered does not
        assertEquals(1, lost.tasks)
    }

    @Test fun lostAmong_emptySnapshot_isZero() = runBlocking {
        val lost = repo.lostAmong(emptyList())
        assertEquals(0, lost.cuts)
        assertEquals(0, lost.tasks)
    }

    @Test fun markEventUploaded_doesNotResurrectAStrandedRow() = runBlocking {
        // Review finding P1a: a stranded row (pushed=2) must never flip to 1 on a
        // late worker ack. The guarded query only moves 0 -> 1.
        val taskA = insertPendingTask()
        insertEvent(taskA, pushed = 2, eventCode = 179)   // already stranded
        val strandedId = db.eventDao().pendingCutIds()     // empty - it's not pending
        assertEquals(0, strandedId.size)

        // Find the stranded row's id and try to mark it uploaded.
        val id = db.eventDao().let {
            // the only event we inserted; id is 1 in a fresh in-memory db
            1L
        }
        repo.markEventUploaded(id)

        // It stays stranded - the guard blocked the 2 -> 1 transition.
        val lost = repo.lostAmong(listOf(id))
        assertEquals(1, lost.cuts)   // still counted as not-uploaded
    }

    // ---- helpers ----

    private fun pushedStateOf(eventId: Long): Int =
        db.query("SELECT pushed FROM events WHERE id = ?", arrayOf<Any>(eventId)).use {
            assertEquals(true, it.moveToFirst())
            it.getInt(0)
        }

    private suspend fun insertPendingTask(): Long {
        val now = "2026-05-15T00:00:00Z"
        return db.taskDao().insert(
            Task(
                deviceId = "TEST",
                taskSeq = 1,
                taskDate = "2026-05-15",
                startedAt = now,
                endedAt = now,
                plusCount = 1,
                minusCount = 0,
                netCount = 1,
                pushStatus = "pending",
                saveType = "manual",
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private suspend fun insertEvent(taskId: Long, pushed: Int, eventCode: Int): Long {
        return db.eventDao().insert(
            EventEntity(
                taskId = taskId,
                eventType = "plus",
                eventCode = eventCode,
                timestamp = "2026-05-15T00:00:00Z",
                latDecimal = 2.27,
                lonDecimal = 103.28,
                hdop = 1.5,
                satellites = 8,
                batteryPct = 90.0,
                workCount = 1,
                countAfter = 1,
                pushed = pushed,
                createdAt = "2026-05-15T00:00:00Z",
            )
        )
    }

    private suspend fun taskById(id: Long, status: String): Task? =
        db.taskDao().getByPushStatus(status).firstOrNull { it.id == id }

    private suspend fun insertTaskWithCreatedAt(status: String, createdAt: String): Long {
        val baseIso = "2026-05-15T00:00:00Z"
        return db.taskDao().insert(
            Task(
                deviceId = "TEST",
                taskSeq = 1,
                taskDate = "2026-05-15",
                startedAt = baseIso,
                endedAt = baseIso,
                plusCount = 1,
                minusCount = 0,
                netCount = 1,
                pushStatus = status,
                saveType = "manual",
                createdAt = createdAt,
                updatedAt = baseIso,
            )
        )
    }

    private suspend fun findById(id: Long): Task? =
        taskById(id, "uploaded")
            ?: taskById(id, "failed")
            ?: taskById(id, "discarded")
            ?: db.taskDao().pendingTasks().firstOrNull { it.id == id }
            ?: taskById(id, "active")
}
