# Deliver-Before-Strand (Approach A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** At a device-initiated OTP release, deliver the phone's pending cuts to Wialon under the unit it still owns *before* stranding anything, so a release on a working network loses no harvest and `302` fires only when the gateway was genuinely unreachable.

**Architecture:** Split the release into two phases around the `client.release()` webhook call. Phase 1 (`deliverBeforeRelease`) runs while the phone still owns the unit: finalize the active task, snapshot the pending cut ids, then run a bounded single-attempt `PushEngine` drain guarded by a mutex so it never sends a row the background `PushWorker` is also sending. Phase 2 (`markAndStrand`) runs after the webhook frees the unit: count how many snapshot cuts did NOT upload, push `302`/`304` under the old unit, strand the remainder unconditionally. Payload trims to `lost_cuts` on `302` only.

**Tech Stack:** Kotlin, Room (SQLite), Jetpack Compose, kotlinx.coroutines (`Mutex`, `withTimeout`), JUnit4, AndroidX Test.

## Global Constraints

- Build from repo root as `.\gradlew.bat` (Windows PowerShell workspace).
- Branch: `feat/302-work-stranded` (this extends it; do not branch off).
- The Wialon unit id is a login credential in the IPS frame, not a phone property. A row sent under the wrong unit mis-credits harvest. Every ordering choice below protects that invariant.
- The strand (`strandUnsentWork`) stays **unconditional** — it runs whether or not the marker landed. Never re-wrap it in `if (landed)`. `ReleaseSequenceTest` guards this by mutation.
- `PushEngine` requires `backoffScheduleMs` non-empty and `maxAttempts > 0` (`PushEngine.kt:112-115`). Release-mode config: `maxAttempts = 1`, `backoffScheduleMs = listOf(0L)` (never used at 1 attempt), keep `chunkSize`/`interMessageDelayMs` at `AppConfig` defaults.
- IPS param format `name:type:value`, type `1` = int. `IPSFrameBuilder.telemetryFrame` already appends `lost_*` only when non-null — passing `null` drops the param with no builder change.
- No backend / n8n / SQL change. No change to the cut-capture path, the frame builder, or `PushEngine`'s internals.
- Instrumented tests: `connectedDebugAndroidTest` exits non-zero in this environment (UTP loopback-gRPC glitch) even when tests pass. Read the real result from `app/build/outputs/androidTest-results/connected/**/utp.0.log` (`INFO: Execute <test>: PASSED`) and `test-result.textproto`. Invoke with `-Pandroid.testInstrumentationRunnerArguments.class=<FQN>` (the task does NOT accept `--tests`).
- Commit style: conventional commits, `type(scope): subject`, subject under 72 chars, **no attribution or co-author lines**.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/klk/hams/push/PushGate.kt` | mutual exclusion between the two cut-senders | **create** — a shared `Mutex` |
| `app/src/main/java/com/klk/hams/push/PushWorker.kt` | background drain | wrap `engine.run()` in the gate |
| `app/src/main/java/com/klk/hams/data/db/EventDao.kt` | event queries | snapshot cut ids + count-not-uploaded |
| `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` | domain surface | `pendingCutIds`, `lostAmong` |
| `app/src/main/java/com/klk/hams/AppConfig.kt` | config | `DELIVER_BUDGET_MS` |
| `app/src/main/java/com/klk/hams/provisioning/ProvisioningEvents.kt` | release sequence | two-phase split, bounded guarded deliver, payload trim |
| `app/src/main/java/com/klk/hams/ui/onboarding/AdminSheet.kt` | release call site 1 | reorder deliver-before-release, progress text |
| `app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt` | release call site 2 | reorder deliver-before-release |
| `docs/HAMS_EVENT_CODE_DICTIONARY.md` | vocabulary | v1.6 payload change |

---

### Task 1: `PushGate` — one mutex for both cut-senders

**Files:**
- Create: `app/src/main/java/com/klk/hams/push/PushGate.kt`
- Modify: `app/src/main/java/com/klk/hams/push/PushWorker.kt:195`
- Test: `app/src/test/java/com/klk/hams/push/PushGateTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `object PushGate { val mutex: Mutex }`. Both `PushWorker` (via `withLock`) and the Task 4 deliver step (via `tryLock`) hold it so the same `179` row is never sent by both.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/klk/hams/push/PushGateTest.kt`:

```kotlin
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
            // worker holds the gate — that is what prevents a duplicate 179 send.
            assertFalse(PushGate.mutex.tryLock())
        }
        // Once released, the deliver step can acquire it.
        assertTrue(PushGate.mutex.tryLock())
        PushGate.mutex.unlock()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushGateTest"`
Expected: compilation FAILS — `unresolved reference: PushGate`.

- [ ] **Step 3: Create `PushGate`**

Write `app/src/main/java/com/klk/hams/push/PushGate.kt`:

```kotlin
package com.klk.hams.push

import kotlinx.coroutines.sync.Mutex

/**
 * Serialises the two cut-senders so the same pending `179` row is never sent by
 * both — which would double-count a worker's harvest in Wialon.
 *
 *   - [PushWorker] holds it (`withLock`) around its engine drain.
 *   - The release-time deliver step (ProvisioningEvents) acquires it with
 *     `tryLock`: if the worker already holds it, the deliver step SKIPS — the
 *     worker is already sending those cuts — rather than sending them again.
 *
 * Process-wide single instance; both senders live in the same app process.
 */
object PushGate {
    val mutex = Mutex()
}
```

- [ ] **Step 4: Run the gate test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushGateTest"`
Expected: PASS, 1 test.

- [ ] **Step 5: Wrap the worker's drain in the gate**

In `PushWorker.kt`, add the import near the other `androidx.work` / coroutine imports:

```kotlin
import kotlinx.coroutines.sync.withLock
```

Then change line 195 from:

```kotlin
            val result = engine.run()
```

to:

```kotlin
            // Serialise against the release-time deliver step (PushGate): the two
            // must never send the same pending 179 concurrently, or Wialon
            // double-counts the cut. The worker takes priority — it holds the lock
            // for its whole drain; the deliver step tryLocks and skips if busy.
            val result = PushGate.mutex.withLock { engine.run() }
```

- [ ] **Step 6: Build to verify the worker still compiles**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushGate.kt app/src/main/java/com/klk/hams/push/PushWorker.kt app/src/test/java/com/klk/hams/push/PushGateTest.kt
git commit -m "feat(push): PushGate mutex serialises worker and release drain"
```

---

### Task 2: EventDao — snapshot cut ids + count-not-uploaded

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/db/EventDao.kt` (append after the existing release-accounting queries)
- Test: `app/src/androidTest/java/com/klk/hams/data/db/DiagnosticDaoTest.kt` — no; use `TaskRepositoryTest` (Task 3). Add the DAO test here inline.
- Test: `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt` (exercised in Task 3)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `EventDao.pendingCutIds(): List<Long>` — ids of pending `179` rows, ordered.
  - `EventDao.countNotUploadedAmong(ids: List<Long>): Int` — of those ids, how many are NOT `pushed = 1` (i.e. not delivered).
  - `EventDao.countTasksNotUploadedAmong(ids: List<Long>): Int` — distinct tasks among the not-uploaded ids.

Why "not uploaded" not "still pending": `PushEngine` marks a rejected frame `pushed = 2`. Counting `pushed = 0` would let a rejected cut vanish into a clean `304`. Counting the snapshot ids where `pushed != 1` reports the truth — delivered vs everything else.

- [ ] **Step 1: Add the three queries**

In `EventDao.kt`, add after the `strandAllPending()` query (inside the interface, before its closing brace):

```kotlin
    // --- Deliver-before-strand snapshot accounting (Approach A, 2026-07-23) ---

    // The harvest snapshot taken BEFORE the release deliver step. Ordered like
    // getPending so a partial deliver drains oldest-first.
    @Query("SELECT id FROM events WHERE pushed = 0 AND event_code = 179 ORDER BY timestamp ASC, id ASC")
    suspend fun pendingCutIds(): List<Long>

    // Of the snapshot, how many did NOT reach Wialon (pushed != 1). Counts both
    // still-queued (0) and rejected (2), so a PushEngine rejection can't hide as
    // a clean 304. This is lost_cuts.
    @Query("SELECT COUNT(*) FROM events WHERE id IN (:ids) AND pushed != 1")
    suspend fun countNotUploadedAmong(ids: List<Long>): Int

    // Distinct tasks among the not-uploaded snapshot ids — for the operator sheet
    // ("N tasks / M cuts discarded"). Never goes on the wire.
    @Query("SELECT COUNT(DISTINCT task_id) FROM events WHERE id IN (:ids) AND pushed != 1")
    suspend fun countTasksNotUploadedAmong(ids: List<Long>): Int
```

- [ ] **Step 2: Build to verify Room generates the DAO**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Room compiles the new `@Query` methods; a malformed query fails KSP here).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/db/EventDao.kt
git commit -m "feat(db): snapshot cut-id and not-uploaded-among queries"
```

---

### Task 3: TaskRepository — `pendingCutIds` and `lostAmong`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` (after `strandUnsentWork`, around `:508`)
- Test: `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt`

**Interfaces:**
- Consumes: `EventDao.pendingCutIds`, `EventDao.countNotUploadedAmong`, `EventDao.countTasksNotUploadedAmong` (Task 2); `TaskRepository.UnsentWork` (exists).
- Produces:
  - `TaskRepository.pendingCutIds(): List<Long>`
  - `TaskRepository.lostAmong(snapshotIds: List<Long>): UnsentWork` — `(tasks = distinct not-uploaded tasks, cuts = not-uploaded cuts)`.

- [ ] **Step 1: Write the failing test**

Append inside `TaskRepositoryTest`, before the closing brace. The class provides `db` / `repo`, `runBlocking`, `assertEquals`, and the helpers `insertPendingTask(): Long` and `insertEvent(taskId, pushed, eventCode)`:

```kotlin
    // ---- Approach A — snapshot accounting (2026-07-23) ----

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.klk.hams.data.repository.TaskRepositoryTest`
Expected: compilation FAILS — `unresolved reference: pendingCutIds` / `lostAmong`. (Verify via the build error, not the UTP exit code.)

- [ ] **Step 3: Add the repository surface**

In `TaskRepository.kt`, add after `strandUnsentWork()` (around line 508):

```kotlin
    /**
     * Snapshot of the pending harvest (`179`, `pushed = 0`) taken BEFORE the
     * release deliver step. Passed to [lostAmong] after the deliver so loss is
     * measured against a fixed set, immune to rows the deliver step marks
     * uploaded or the background worker touches concurrently.
     */
    suspend fun pendingCutIds(): List<Long> = eventDao.pendingCutIds()

    /**
     * How much of [snapshotIds] did NOT reach Wialon — counted by final `pushed`
     * state (`!= 1`), so a `PushEngine`-rejected cut (`pushed = 2`) counts as
     * lost rather than hiding as a clean 304. `cuts` is the `lost_cuts` figure;
     * `tasks` is for the operator sheet only, never the wire.
     */
    suspend fun lostAmong(snapshotIds: List<Long>): UnsentWork = UnsentWork(
        tasks = eventDao.countTasksNotUploadedAmong(snapshotIds),
        cuts = eventDao.countNotUploadedAmong(snapshotIds),
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.klk.hams.data.repository.TaskRepositoryTest`
Expected: all `TaskRepositoryTest` cases PASSED in `utp.0.log`. Pre-existing cases must still pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt
git commit -m "feat(repo): pendingCutIds + lostAmong for snapshot loss counting"
```

---

### Task 4: ProvisioningEvents — two-phase release, bounded guarded deliver, payload trim

**Files:**
- Modify: `app/src/main/java/com/klk/hams/AppConfig.kt` (after `PUSH_MANUAL_TIMEOUT_MS`, `:110`)
- Modify: `app/src/main/java/com/klk/hams/provisioning/ProvisioningEvents.kt` (whole release section)
- Test: `app/src/test/java/com/klk/hams/provisioning/ReleaseSequenceTest.kt`
- Test: `app/src/test/java/com/klk/hams/push/TelemetryFrameBuilderTest.kt`

**Interfaces:**
- Consumes: `PushGate` (Task 1); `TaskRepository.pendingCutIds`, `lostAmong` (Task 3); `PushEngine`, `PushRepositoryImpl`, `WialonIPSClient` (exist).
- Produces:
  - `AppConfig.DELIVER_BUDGET_MS: Long`
  - `ProvisioningEvents.deliverBeforeRelease(app, uniqueId): List<Long>` — finalize, snapshot, guarded bounded deliver; returns the snapshot.
  - `ProvisioningEvents.markAndStrand(app, uniqueId, snapshot): ReleaseOutcome` — count-lost, marker, strand.
  - `ProvisioningEvents.runMarkAndStrand(countLost, pushMarker, strand): ReleaseOutcome` — the JVM-testable seam (replaces `runReleaseSequence`).
  - **Removed:** `flushAndRelease`, `runReleaseSequence` (both call sites move to the two-phase pair in Task 5).

- [ ] **Step 1: Add the budget constant**

In `AppConfig.kt`, after `PUSH_MANUAL_TIMEOUT_MS` (line 110):

```kotlin
    /**
     * Ceiling on the synchronous cut-delivery step at OTP release
     * (Approach A). On timeout the deliver stops, the snapshot count reflects
     * what landed, and the remainder strands. A large backlog (~180+ cuts at
     * 75ms pacing) can exceed this and partially deliver — acceptable, and the
     * office SOP prevents backlogs that large. Raise if the field strands
     * deliverable cuts on a healthy link.
     */
    const val DELIVER_BUDGET_MS: Long = 15_000L
```

- [ ] **Step 2: Write the failing seam tests**

Replace the body of `app/src/test/java/com/klk/hams/provisioning/ReleaseSequenceTest.kt` with tests over the new `runMarkAndStrand`. The mutation guard (strand runs even when the marker did not land) is preserved:

```kotlin
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
        // The regression guard: re-wrapping the strand in `if (landed)` fails HERE.
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
```

- [ ] **Step 3: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ReleaseSequenceTest"`
Expected: compilation FAILS — `unresolved reference: runMarkAndStrand`.

- [ ] **Step 4: Rewrite the release section of `ProvisioningEvents`**

Replace everything from `releaseTypeFor` (line 41) through the end of `flushAndRelease` (line 160) — i.e. `releaseTypeFor`, `recordAndPushRelease`, `ReleaseOutcome`, `runReleaseSequence`, `flushAndRelease`, but NOT `recordAndPushBound` (lines 23-33) and NOT `drainTelemetry` (lines 162-177) — with:

```kotlin
    /**
     * Pure: which marker a release emits. `302` when harvest was lost
     * (`cuts > 0`), else `304`. `tasks` never routes.
     */
    fun releaseTypeFor(unsent: TaskRepository.UnsentWork): DiagnosticType =
        if (unsent.cuts > 0) DiagnosticType.WORK_STRANDED else DiagnosticType.DEVICE_UNBOUND

    /**
     * Record + push the release marker to [uniqueId] (the unit being left).
     *
     * Payload (v1.6): `lost_tasks` is dropped from the wire entirely; `lost_cuts`
     * rides on `302` only (`304` fires exactly when `cuts == 0`, so the number
     * would always be 0 there — the code is the signal).
     *
     * Every pending telemetry row that fails to land is marked rejected, not just
     * this one — `drainTelemetry` sends the whole table, and any row left
     * `pushed = 0` here would push under the NEXT unit after the binding clears.
     *
     * @return true if the marker reached the gateway. Never gates the strand.
     */
    suspend fun recordAndPushRelease(
        app: HamsApp,
        uniqueId: String,
        unsent: TaskRepository.UnsentWork,
    ): Boolean {
        val id = app.repository.recordDiagnostic(
            type = releaseTypeFor(unsent),
            batteryPct = BindingRevalidator.readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = 0,
            lostTasks = null,                          // dropped from the wire (v1.6)
            lostCuts = unsent.cuts.takeIf { it > 0 },  // 302 only; null on 304
        )
        val pendingIds = app.repository.pendingTelemetryIds()
        drainTelemetry(app, uniqueId)
        for (rowId in pendingIds) {
            if (app.repository.diagnosticPushedState(rowId) != 1) {
                app.repository.markTelemetryRejected(rowId)
            }
        }
        // Read AFTER the rejection loop: the loop may have flipped this row to 2.
        return app.repository.diagnosticPushedState(id) == 1
    }

    /**
     * @property landed true if the 302/304 marker reached the gateway. Never
     *   gates the strand; call sites use it to tell the operator whether Wialon
     *   holds a receipt.
     * @property lost the counts measured after the deliver step — what this
     *   release could not deliver and is about to strand.
     */
    data class ReleaseOutcome(val landed: Boolean, val lost: TaskRepository.UnsentWork)

    /**
     * PHASE 1 of a device-initiated release — runs while the phone STILL owns the
     * unit and the drain lease still holds (must be called BEFORE the
     * `client.release()` webhook, per codex #3):
     *
     *   1. finalize the active task (issue A3 — else its cuts are invisible)
     *   2. snapshot the pending 179 ids (the loss is measured against this fixed set)
     *   3. deliver: if the background worker is not already draining (PushGate),
     *      run a single-attempt bounded PushEngine over pending cuts under
     *      [uniqueId]. If the worker IS draining, skip — it is already sending
     *      these cuts, and sending them again would double-count (codex #2).
     *
     * @return the snapshot of 179 ids, handed to [markAndStrand] after the webhook.
     */
    suspend fun deliverBeforeRelease(app: HamsApp, uniqueId: String): List<Long> {
        app.repository.finalizeActiveTaskForRelease()
        val snapshot = app.repository.pendingCutIds()
        if (PushGate.mutex.tryLock()) {
            try {
                val engine = buildReleaseDeliveryEngine(app, uniqueId)
                try {
                    withTimeout(AppConfig.DELIVER_BUDGET_MS) { engine.run() }
                } catch (_: TimeoutCancellationException) {
                    // Partial deliver: the snapshot count reflects what landed.
                    // Only withTimeout's own cancellation is caught here — an
                    // outer-scope cancel (Activity recreation) is not, because
                    // this runs on the application scope at the call site.
                }
            } finally {
                PushGate.mutex.unlock()
            }
        }
        // else: worker holds the gate; it is draining these cuts. Skip.
        return snapshot
    }

    /**
     * PHASE 2 — runs AFTER `client.release()` has freed the unit. Counts how much
     * of [snapshot] did not upload, pushes the marker under the old [uniqueId],
     * and strands the remainder unconditionally.
     */
    suspend fun markAndStrand(
        app: HamsApp,
        uniqueId: String,
        snapshot: List<Long>,
    ): ReleaseOutcome = runMarkAndStrand(
        countLost = { app.repository.lostAmong(snapshot) },
        pushMarker = { lost -> recordAndPushRelease(app, uniqueId, lost) },
        strandUnsentWork = { app.repository.strandUnsentWork() },
    )

    /**
     * The count → push → strand core, as injected suspend steps so it is
     * JVM-testable without an Android [HamsApp]. The strand runs on EVERY path;
     * `landed` is data, never a condition on it. `ReleaseSequenceTest` fails if
     * anyone re-wraps the strand in `if (landed)`.
     */
    suspend fun runMarkAndStrand(
        countLost: suspend () -> TaskRepository.UnsentWork,
        pushMarker: suspend (TaskRepository.UnsentWork) -> Boolean,
        strandUnsentWork: suspend () -> Unit,
    ): ReleaseOutcome {
        val lost = countLost()
        val landed = pushMarker(lost)
        strandUnsentWork()
        return ReleaseOutcome(landed = landed, lost = lost)
    }

    /**
     * Release-mode PushEngine: single attempt, no backoff (the strand is the
     * "retry"; PushEngine's 30s first-retry backoff would blow the deliver
     * budget). Keeps the default chunk size + 75ms pacing.
     */
    private fun buildReleaseDeliveryEngine(app: HamsApp, uniqueId: String) =
        com.klk.hams.push.PushEngine(
            repo = com.klk.hams.push.PushRepositoryImpl(app.repository),
            senderFactory = { WialonIPSClient(uniqueId = uniqueId) },
            maxAttempts = 1,
            backoffScheduleMs = listOf(0L),
        )
```

Add these imports at the top of `ProvisioningEvents.kt` (alongside the existing ones):

```kotlin
import com.klk.hams.AppConfig
import com.klk.hams.push.PushGate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
```

(`import com.klk.hams.data.repository.TaskRepository` already exists; `PushEngine`/`PushRepositoryImpl` are referenced fully-qualified above so no import needed for them. `WialonIPSClient` import already exists.)

- [ ] **Step 5: Run the seam tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ReleaseSequenceTest"`
Expected: PASS, 3 tests. Compilation of `AdminSheet.kt` / `PairingScreen.kt` will now FAIL (they call the removed `flushAndRelease`) — Task 5 fixes both. Do not commit a broken build without Task 5.

- [ ] **Step 6: Update the frame-builder tests for the payload trim**

In `app/src/test/java/com/klk/hams/push/TelemetryFrameBuilderTest.kt`, replace `workStranded_frame_carriesLostParams` and `deviceUnbound_clean_carriesZeroLostParams` with:

```kotlin
    @Test fun workStranded_frame_carriesLostCutsOnly() {
        val row = DiagnosticEntity(
            id = 4,
            type = "work_stranded",
            timestamp = "2026-07-23T01:17:06Z",
            batteryPct = 78.0,
            createdAt = "x",
            pushed = 0,
            latDecimal = 2.268721,
            lonDecimal = 103.282985,
            hdop = 1.5,
            satellites = 8,
            speedKmh = 0,
            lostTasks = null,      // v1.6: dropped from the wire
            lostCuts = 47,
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertEquals(
            "#D#230726;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;" +
                "event_code:1:302,battery:2:78.00,work_count:1:0,lost_cuts:1:47\r\n",
            frame,
        )
    }

    @Test fun deviceUnbound_clean_carriesNoLostParams() {
        val row = DiagnosticEntity(
            id = 5,
            type = "device_unbound",
            timestamp = "2026-07-23T01:17:06Z",
            batteryPct = 78.0,
            createdAt = "x",
            pushed = 0,
            latDecimal = 2.268721,
            lonDecimal = 103.282985,
            hdop = 1.5,
            satellites = 8,
            speedKmh = 0,
            lostTasks = null,
            lostCuts = null,       // v1.6: 304 carries neither
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertTrue(frame.contains("event_code:1:304"))
        assertTrue(frame.contains("lost_tasks").not())
        assertTrue(frame.contains("lost_cuts").not())
        assertTrue(frame.endsWith("work_count:1:0\r\n"))
    }
```

- [ ] **Step 7: Run frame tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.TelemetryFrameBuilderTest"`
Expected: PASS. `startMoving_frame_isByteExact` and `otherCodes_haveNoLostParams` must still pass (the ten other codes are unaffected). Do not commit — the build is still red until Task 5.

- [ ] **Step 8: Commit (with Task 5 — build is red until then)**

Defer the commit to Task 5, which restores a green build. If you must checkpoint, commit tests + `ProvisioningEvents` + `AppConfig` together and note the build is intentionally red pending Task 5.

---

### Task 5: Both call sites — deliver before release + progress text

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/onboarding/AdminSheet.kt:96-151`
- Modify: `app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt:304-322`

**Interfaces:**
- Consumes: `ProvisioningEvents.deliverBeforeRelease`, `markAndStrand`, `ReleaseOutcome` (Task 4).
- Produces: nothing downstream.

Both sites move from "release then flush" to "deliver → release → mark+strand." Deliver and mark+strand each run on `app.applicationScope.async {…}.await()` so an armband flip (Activity recreation) cannot cut them; the webhook call stays on the UI `scope`.

- [ ] **Step 1: Rewrite the AdminSheet release body**

In `AdminSheet.kt`, replace the whole `scope.launch { … }` block (lines 96-151) with:

```kotlin
            scope.launch {
                // Phase 1 — deliver the pending cuts to Wialon while this phone
                // still owns the unit (before the webhook frees it). Runs on the
                // application scope so an armband flip can't cut it.
                status = "Delivering cuts…"
                val snapshot = app.applicationScope.async {
                    ProvisioningEvents.deliverBeforeRelease(app, id)
                }.await()

                // Shared by Success and NotFound: free the unit, then mark+strand
                // the remainder under the old unit, then clear locally. Status is
                // already "Confirming release…" from the pre-release line below.
                suspend fun finishRelease() {
                    val outcome = app.applicationScope.async {
                        val o = ProvisioningEvents.markAndStrand(app, id, snapshot)
                        store.clear()
                        o
                    }.await()

                    val lost = outcome.lost
                    val discarded = if (lost.cuts > 0) {
                        "Released. ${lost.cuts} cuts could not be delivered."
                    } else {
                        null
                    }
                    val unconfirmed = if (!outcome.landed) {
                        "Wialon did not confirm the release."
                    } else {
                        null
                    }
                    val report = listOfNotNull(discarded, unconfirmed).joinToString(" ")
                    if (report.isEmpty()) {
                        onReset()
                    } else {
                        status = "$report Close to continue."
                        adminAction = null
                        resetOnDismiss = true
                    }
                }

                status = "Confirming release…"
                when (val release = client.release(id, fp, adminCode)) {
                    ReleaseResult.Success -> finishRelease()
                    ReleaseResult.NotFound -> finishRelease()
                    ReleaseResult.AdminAuthFailed -> {
                        // Cuts already delivered under the still-owned unit — safe
                        // (they belong there). Binding stays; retry later.
                        rememberAdminFailure(releaseFailureMessage(release))
                        busy = false
                    }
                    else -> {
                        status = releaseFailureMessage(release)
                        adminAction = null
                        busy = false
                    }
                }
            }
```

- [ ] **Step 2: Rewrite the PairingScreen ReleaseAndBind body**

In `PairingScreen.kt`, replace the `is PairingAdminAction.ReleaseAndBind -> { … }` block's `ReleaseResult.Success` arm (lines 305-335) so the deliver runs before `client.release()`:

```kotlin
                        is PairingAdminAction.ReleaseAndBind -> {
                            // Phase 1 — deliver cuts to the OLD unit before freeing
                            // it. This is the release-then-rebind sequence that
                            // produced the 2026-07-10 mis-attribution defect; a row
                            // left pending here would upload under the unit claimed
                            // below. Delivering first (still owned, lease held) and
                            // stranding the remainder makes that impossible.
                            val snapshot = app.applicationScope.async {
                                ProvisioningEvents.deliverBeforeRelease(app, action.ownedUnit)
                            }.await()
                            when (val release = client.release(action.ownedUnit, fp, code)) {
                                ReleaseResult.Success -> {
                                    app.applicationScope.async {
                                        ProvisioningEvents.markAndStrand(app, action.ownedUnit, snapshot)
                                    }.await()
                                    when (val bind = client.manualClaim(unitId, fp, code)) {
                                    is BindResult.Success -> completePairing(bind.uniqueId)
                                    BindResult.AdminAuthFailed -> {
                                        rememberAdminFailure(bindFailureMessage(bind))
                                        busy = false
                                    }
                                    else -> {
                                        error = bindFailureMessage(bind)
                                        adminAction = null
                                        busy = false
                                    }
                                    }
                                }
                                ReleaseResult.AdminAuthFailed -> {
                                    rememberAdminFailure(releaseFailureMessage(release))
                                    busy = false
                                }
                                else -> {
                                    error = releaseFailureMessage(release)
                                    adminAction = null
```

(Leave the block's closing braces after line 342 as they are — you are replacing the opening of the `ReleaseAndBind` arm through the `else ->` line, matching the existing structure.)

- [ ] **Step 3: Build to verify a green tree**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. `git grep -n flushAndRelease` must return nothing under `app/`.

- [ ] **Step 4: Full unit suite + lint**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures (PushGateTest + ReleaseSequenceTest + updated frame tests + all pre-existing).

Run: `.\gradlew.bat :app:lintDebug`
Expected: 0 errors. Note any new warning above the 49 baseline.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/AppConfig.kt app/src/main/java/com/klk/hams/provisioning/ProvisioningEvents.kt app/src/main/java/com/klk/hams/ui/onboarding/AdminSheet.kt app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt app/src/test/java/com/klk/hams/provisioning/ReleaseSequenceTest.kt app/src/test/java/com/klk/hams/push/TelemetryFrameBuilderTest.kt
git commit -m "feat(provisioning): deliver cuts before strand at OTP release"
```

---

### Task 6: Dictionary v1.6

**Files:**
- Modify: `docs/HAMS_EVENT_CODE_DICTIONARY.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Update the master-table 302/304 rows**

In the "All Event Codes at a Glance" table, change the `302` and `304` rows to:

```markdown
| **302** | `work_stranded` (OTP release; cuts could not be delivered) — carries `lost_cuts` | Provisioning (3xx) | **Yes** |
| **304** | `device_unbound` (OTP release; all cuts delivered) — no params | Provisioning | **Yes** |
```

- [ ] **Step 2: Replace the payload notes block**

Find the notes bullets added in v1.5 about `lost_tasks` / `lost_cuts` and both markers carrying counts. Replace them with:

```markdown
- **`302` carries `lost_cuts` only; `304` carries no params.** As of the
  deliver-before-strand change (2026-07-23), a release first delivers pending
  cuts under the unit being left, then counts what did not land. `302` fires only
  when `lost_cuts > 0` and carries that one integer. `304` means everything was
  delivered — the code is the signal, no param. `lost_tasks` is no longer sent on
  the wire (it triggered nothing and the beacon-only case made `304 lost_tasks=1`
  read as a false alarm).
- **`lost_cuts` counts snapshot `179` rows that did not upload** (`pushed != 1`
  after the deliver step), matching the harvest rule. A cut the gateway rejected
  counts as lost, not as a clean `304`.
- **`302` is now rare.** On any working network the deliver step empties the queue
  and the release is a clean `304`. `302` means the Wialon gateway was genuinely
  unreachable at release time (or, on an ack-timeout, over-reports a cut that
  actually landed — never under-reports).
- **Historical `302` payloads.** `binding_taken` `302`s (pre-2026-07-07) carry no
  params. `work_stranded` `302`s from 2026-07-23 carry `lost_tasks` AND `lost_cuts`
  (v1.5). From v1.6 they carry `lost_cuts` only. Filter by date, not param shape.
```

- [ ] **Step 3: Add the changelog row**

Append to the version history table:

```markdown
| 1.6 | 2026-07-23 | Deliver-before-strand: a release now delivers pending cuts under the unit being left before stranding, so `302` fires only on genuine gateway failure. Payload trimmed — `302` carries `lost_cuts` only, `304` carries no params, `lost_tasks` dropped from the wire. |
```

- [ ] **Step 4: Commit**

```bash
git add docs/HAMS_EVENT_CODE_DICTIONARY.md
git commit -m "docs(event-codes): v1.6 — deliver-before-strand, lost_cuts only"
```

---

## Device Verification

Run on `ALI-NX1` after Task 6. The acceptance test is the before/after pair.

- [ ] **DV1 — Wi-Fi-on release now delivers (the whole point).** Pair to a test unit, record 3 cuts, **do NOT wait** for the background push, immediately OTP-release. Before this change that produced `302 lost_cuts=3`. Now expect: a clean `304` on the unit, all 3 cuts delivered, admin sheet quiet. Confirm on device: `SELECT pushed, COUNT(*) FROM events WHERE event_code=179` → all `pushed=1`.
- [ ] **DV2 — gateway-miss still strands (V7 equivalent).** Pair, record 3 cuts, **airplane mode ON** (n8n over `adb reverse`, Wialon unreachable), OTP-release. Expect: `302, lost_cuts=3`, all 3 cuts `pushed=2`, admin sheet says "3 cuts could not be delivered. Wialon did not confirm." No `lost_tasks` param in the frame.
- [ ] **DV3 — no duplicate under concurrency.** Pair, record cuts, enable Wi-Fi so the background worker starts, then OTP-release within the same few seconds. Confirm in Wialon: each cut appears **once**, not twice.
- [ ] **DV4 — rebind path (A1 regression).** Record cuts offline, then **Release and bind** straight to a different unit with Wi-Fi on. Confirm: cuts on the OLD unit (delivered by the deliver step), none on the new unit.
- [ ] **DV5 — payload shape.** Confirm a `304` in Wialon has no `lost_*` params, and a `302` has `lost_cuts` only (no `lost_tasks`).

---

## Out of Scope

- **Approach B — unit-stamped per-task push.** The structural endgame (cuts follow their recorded unit on every push). Documented next phase; most of its release-path value is captured by A.
- **Count-screen lock during release** (codex #7). Verify first whether the modal `AdminSheet` already blocks `+` presses behind it; add a lock only if a press can land during the ~35s release. Implementer judgement, low stakes.
- **Backend drain-lease stamp / `provisioning_events` audit.** Admin-console release path, covered by the office-only SOP. Non-critical, gated on company Postgres.
- **The ~35s worst-case wait bound.** Two serial Wialon sessions; progress text mitigates. Tune `DELIVER_BUDGET_MS` if the field shows it.

---

*Written 2026-07-23 by WYH. Implements `docs/superpowers/specs/2026-07-23-deliver-before-strand-design.md` (Approach A, hardened via /plan-eng-review + codex outside voice). Extends `docs/superpowers/plans/2026-07-23-work-stranded-302.md`.*
