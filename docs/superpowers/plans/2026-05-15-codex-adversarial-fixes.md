# Codex Adversarial Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address Findings #2 and #3 from the 2026-05-15 Codex adversarial review on `phase/2-ips-push`: distinguish `'failed'` from `'uploaded'` for tasks with rejected events, add a retention sweep so failed tasks self-clean, and move `onTaskFinalized` outside the save transaction to close the auto-push race.

**Architecture:** Three small commits, one per concern. Commit 1 adds a new DAO query + repo method `markTaskTerminalState` and renames callers. Commit 2 adds a retention DAO query + repo method + a single invocation in `HamsApp.onCreate`. Commit 3 hoists one callback line out of `withTransaction` in two places.

**Tech Stack:** Kotlin, Room (SQLite), JUnit 4 (JVM unit tests), AndroidX Room instrumented tests, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-05-15-codex-adversarial-fixes-design.md`

**Branch:** `phase/2-ips-push` (already has Phase 3.3a/b/c committed).

---

## File map

| File | Created / Modified | Responsibility |
|---|---|---|
| `app/src/main/java/com/klk/hams/data/db/EventDao.kt` | M | Add `countRejectedForTask(taskId): Int` query. |
| `app/src/main/java/com/klk/hams/data/db/TaskDao.kt` | M | Add `deleteStaleTerminal(cutoffIso): Int` query. |
| `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` | M | Rename `markTaskUploadedIfAllPushableEventsUploaded` → `markTaskTerminalState` (Task 1). Add `purgeStaleTerminalTasks(retentionDays)` (Task 2). Move `onTaskFinalized?.invoke()` out of `withTransaction` in `saveActiveTask` and `rolloverActiveTaskIfStale` (Task 3). |
| `app/src/main/java/com/klk/hams/push/PushEngine.kt` | M | Rename interface method in `PushRepository`; update single call site in `finalizeTouchedTasks`. |
| `app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt` | M | Update interface impl. |
| `app/src/main/java/com/klk/hams/push/PushWorker.kt` | M | Update per-task sweep call site. |
| `app/src/main/java/com/klk/hams/HamsApp.kt` | M | Invoke `repository.purgeStaleTerminalTasks()` once in `onCreate`, before the chain observer launches. |
| `app/src/test/java/com/klk/hams/push/PushEngineTest.kt` | M | Update `FakeRepo` method name. Add 3 JVM tests covering the new terminal-state semantics. |
| `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt` | C | New Room-backed integration tests (7 cases per spec §6.2). Runs via `connectedDebugAndroidTest`. |

---

## Task 1 — Finding #2: distinguish `'failed'` from `'uploaded'`

### Task 1.1 — Add `EventDao.countRejectedForTask`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/db/EventDao.kt`

- [ ] **Step 1: Add the query**

Append before the closing brace of the `EventDao` interface:

```kotlin
    // Used by markTaskTerminalState (Codex finding #2 fix, 2026-05-15) to
    // detect tasks that have permanently-rejected events. Mirrors getPending's
    // allow-list — rejection is only meaningful for outbound-approved codes.
    @Query(
        "SELECT COUNT(*) FROM events " +
        "WHERE task_id = :taskId " +
        "AND pushed = 2 " +
        "AND event_code IN (179, 180, 35) " +
        "AND NOT (event_code = 180 AND work_count <= 0)"
    )
    suspend fun countRejectedForTask(taskId: Long): Int
```

- [ ] **Step 2: Compile check**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

### Task 1.2 — Rename + rewrite `TaskRepository.markTaskUploadedIfAllPushableEventsUploaded` → `markTaskTerminalState`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt`

- [ ] **Step 1: Replace the method**

In `TaskRepository.kt`, replace the existing block (currently around lines 389–400):

```kotlin
    /**
     * Transitions a pending task to "uploaded" iff no pushable pending events
     * remain for it. Active or already-terminal tasks are left alone.
     */
    suspend fun markTaskUploadedIfAllPushableEventsUploaded(taskId: Long) {
        db.withTransaction {
            val pending = eventDao.countPushablePendingForTask(taskId)
            if (pending == 0) {
                taskDao.setPushStatusIfPending(taskId, "uploaded", clock.nowUtcIso())
            }
        }
    }
```

With:

```kotlin
    /**
     * Transitions a pending task to its terminal state (Codex finding #2 fix,
     * 2026-05-15):
     *   - `'failed'`   if any event for the task was permanently rejected
     *                   (`pushed = 2`). Wialon never accepted that data; the
     *                   task must NOT be reported as uploaded.
     *   - `'uploaded'` if no pending rows remain and no rejected rows exist.
     *   - no-op        if pending rows still remain for the task.
     *
     * Active or already-terminal tasks are left alone (the guard lives in
     * `taskDao.setPushStatusIfPending`).
     */
    suspend fun markTaskTerminalState(taskId: Long) {
        db.withTransaction {
            val pending = eventDao.countPushablePendingForTask(taskId)
            if (pending > 0) return@withTransaction
            val rejected = eventDao.countRejectedForTask(taskId)
            val status = if (rejected > 0) "failed" else "uploaded"
            taskDao.setPushStatusIfPending(taskId, status, clock.nowUtcIso())
        }
    }
```

- [ ] **Step 2: Compile check**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: FAIL — references to the old name in `PushEngine`, `PushRepositoryImpl`, `PushWorker`, and `PushEngineTest` won't compile yet. That's the cue for the next steps.

### Task 1.3 — Rename the `PushRepository` interface method + impl

**Files:**
- Modify: `app/src/main/java/com/klk/hams/push/PushEngine.kt`
- Modify: `app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt`

- [ ] **Step 1: Rename in the interface**

In `PushEngine.kt`, update the `PushRepository` interface (around line 23):

```kotlin
    /** Flip the task's `push_status` to `uploaded` or `failed` per spec §3.2. */
    suspend fun markTaskTerminalState(taskId: Long)
```

- [ ] **Step 2: Update the docstring reference inside `PushEngine`**

In `PushEngine.kt`, around line 67, replace:
```
 * `markTaskUploadedIfAllPushableEventsUploaded` after every attempt that
```
With:
```
 * `markTaskTerminalState` after every attempt that
```

- [ ] **Step 3: Update the call site in `finalizeTouchedTasks`**

In `PushEngine.kt`, around line 251, replace:
```kotlin
        for (id in snapshot) repo.markTaskUploadedIfAllPushableEventsUploaded(id)
```
With:
```kotlin
        for (id in snapshot) repo.markTaskTerminalState(id)
```

- [ ] **Step 4: Update `PushRepositoryImpl`**

In `PushRepositoryImpl.kt`, replace the `markTaskUploadedIfAllPushableEventsUploaded` override (lines 27–29) with:

```kotlin
    override suspend fun markTaskTerminalState(taskId: Long) {
        repo.markTaskTerminalState(taskId)
    }
```

### Task 1.4 — Update `PushWorker` call site

**Files:**
- Modify: `app/src/main/java/com/klk/hams/push/PushWorker.kt`

- [ ] **Step 1: Rename the call**

In `PushWorker.kt`, around line 125, replace:
```kotlin
                repo.markTaskUploadedIfAllPushableEventsUploaded(taskId)
```
With:
```kotlin
                repo.markTaskTerminalState(taskId)
```

- [ ] **Step 2: Compile check**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (tests still to update).

### Task 1.5 — Update `PushEngineTest.FakeRepo` + write the failing tests (TDD)

**Files:**
- Modify: `app/src/test/java/com/klk/hams/push/PushEngineTest.kt`

- [ ] **Step 1: Update `FakeRepo` to support the new predicate**

In `PushEngineTest.kt`, locate the `FakeRepo` class (around line 544). Replace the `markTaskUploadedIfAllPushableEventsUploaded` override and the `taskFinalizeCalls` field, plus add a way to seed rejected events per task.

Find:
```kotlin
        val taskFinalizeCalls: MutableList<Long> = mutableListOf()
```
Replace with:
```kotlin
        // Per-task terminal-state intent recorded by FakeRepo. Maps task_id to
        // the status the engine asked us to set ("uploaded" or "failed").
        // Codex finding #2 fix (2026-05-15): the engine now distinguishes
        // these two outcomes via markTaskTerminalState.
        val taskFinalizeCalls: MutableList<Long> = mutableListOf()
        val taskTerminalStates: MutableMap<Long, String> = mutableMapOf()
        /** task_ids whose events are seeded as rejected for terminal-state tests. */
        val rejectedTaskIds: MutableSet<Long> = mutableSetOf()
```

Find:
```kotlin
        override suspend fun markTaskUploadedIfAllPushableEventsUploaded(taskId: Long) {
            taskFinalizeCalls.add(taskId)
        }
```
Replace with:
```kotlin
        override suspend fun markTaskTerminalState(taskId: Long) {
            taskFinalizeCalls.add(taskId)
            // Mirror real repo logic: if any rejected events exist for the
            // task in our fake state, the terminal state is "failed".
            // Otherwise — and the engine only calls us after a non-empty
            // attempt — it's "uploaded". The fake doesn't distinguish "still
            // pending"; tests must check that case via uploaded/rejected
            // counts directly.
            taskTerminalStates[taskId] =
                if (taskId in rejectedTaskIds) "failed" else "uploaded"
        }
```

- [ ] **Step 2: Add three new JVM tests (TDD — will fail until Task 1.2 lands; they should pass now since Task 1.2 already landed)**

Append before the `// ---- helpers ----` line:

```kotlin
    // ---- 3.4: terminal-state semantics (Codex finding #2 fix) ----

    @Test fun taskWithRejectedFinalEvent_marksTaskFailedNotUploaded() {
        // One event for taskId=1; sender returns FrameRejected → engine marks
        // it pushed=2 (rejected). The fake's rejectedTaskIds reflects that.
        val event = plus(id = 10, taskId = 1)
        val repo = FakeRepo(pending = listOf(event))
        val sender = FakeSender(dataResults = listOf(
            Result.failure(WialonException(WialonError.FrameRejected))
        ))
        val engine = newEngine(repo, sender)
        // Seed the rejection so FakeRepo.markTaskTerminalState renders "failed".
        repo.rejectedTaskIds.add(1L)

        runBlocking { engine.run() }

        assertEquals("failed", repo.taskTerminalStates[1L])
        assertTrue(repo.uploaded.isEmpty())
        assertEquals(listOf(10L to "WialonError.FrameRejected"), repo.rejected)
    }

    @Test fun taskWithMixedAcceptsAndRejects_marksTaskFailed() {
        // taskId=1 has two events: first succeeds (pushed=1), second rejected
        // (pushed=2). Terminal state must be "failed", not "uploaded".
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b))
        val sender = FakeSender(dataResults = listOf(
            Result.success(Unit),
            Result.failure(WialonException(WialonError.FrameRejected)),
        ))
        val engine = newEngine(repo, sender)
        repo.rejectedTaskIds.add(1L)

        runBlocking { engine.run() }

        assertEquals("failed", repo.taskTerminalStates[1L])
        assertEquals(listOf(1L), repo.uploaded)
        assertEquals(1, repo.rejected.size)
    }

    @Test fun taskWithAllAccepts_marksTaskUploaded() {
        // Regression: happy path still yields "uploaded".
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b))
        val sender = FakeSender(dataResults = listOf(
            Result.success(Unit), Result.success(Unit)
        ))
        val engine = newEngine(repo, sender)

        runBlocking { engine.run() }

        assertEquals("uploaded", repo.taskTerminalStates[1L])
        assertEquals(listOf(1L, 2L), repo.uploaded)
        assertTrue(repo.rejected.isEmpty())
    }
```

- [ ] **Step 3: Run the test suite — all green expected**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushEngineTest"`
Expected: BUILD SUCCESSFUL. The pre-existing PushEngineTest entries (which use `taskFinalizeCalls`) still pass because we preserved the list; the three new tests pass because the engine now calls `markTaskTerminalState`.

### Task 1.6 — Optional instrumented coverage stub

**Files:**
- Create: `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt`

- [ ] **Step 1: Create the test file with three terminal-state cases**

(These run when a device is connected. They can be skipped during desk-only iterations and run during the cable-attached field-verify pass.)

```kotlin
package com.klk.hams.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.klk.hams.data.db.AppDatabase
import com.klk.hams.data.model.EventEntity
import com.klk.hams.data.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Codex finding #2 + #3 fixes (2026-05-15).
 * Runs against an in-memory Room v2 database on a connected device or
 * emulator via `:app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        repo = TaskRepository(db)
    }

    @After fun tearDown() { db.close() }

    @Test fun markTaskTerminalState_rejectedEventYieldsFailed() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 1, eventCode = 179)
        insertEvent(taskId, pushed = 2, eventCode = 179)

        repo.markTaskTerminalState(taskId)

        val task = db.taskDao().pendingTasks().firstOrNull { it.id == taskId }
        assertNull(task) // no longer in pending
        val terminal = db.taskDao().tasksWithStatus("failed").firstOrNull { it.id == taskId }
        assertEquals(taskId, terminal?.id)
    }

    @Test fun markTaskTerminalState_allUploadedYieldsUploaded() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 1, eventCode = 179)
        insertEvent(taskId, pushed = 1, eventCode = 179)

        repo.markTaskTerminalState(taskId)

        val terminal = db.taskDao().tasksWithStatus("uploaded").firstOrNull { it.id == taskId }
        assertEquals(taskId, terminal?.id)
    }

    @Test fun markTaskTerminalState_anyPendingNoOp() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 0, eventCode = 179)

        repo.markTaskTerminalState(taskId)

        val stillPending = db.taskDao().pendingTasks().firstOrNull { it.id == taskId }
        assertEquals(taskId, stillPending?.id)
    }

    // ---- Task helpers used by these and future instrumented tests ----

    private suspend fun insertPendingTask(): Long {
        val now = "2026-05-15T00:00:00Z"
        return db.taskDao().insert(
            Task(
                id = 0,
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

    private suspend fun insertEvent(taskId: Long, pushed: Int, eventCode: Int) {
        db.eventDao().insert(
            EventEntity(
                id = 0,
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
}
```

- [ ] **Step 2: Verify `TaskDao.tasksWithStatus` exists**

Run: `grep -n "tasksWithStatus" app/src/main/java/com/klk/hams/data/db/TaskDao.kt`
Expected: a `tasksWithStatus(status: String): List<Task>` query is present (it's the existing line 51 query already used by `pendingTasks`-style callers). If not, this assertion approach must be replaced with `pendingTasks()` + a direct SELECT in the test.

If the helper does NOT exist, add this to `TaskDao.kt`:
```kotlin
    @Query("SELECT * FROM tasks WHERE push_status = :status")
    suspend fun tasksWithStatus(status: String): List<Task>
```

- [ ] **Step 3: Compile (do not run yet — no device)**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

### Task 1.7 — Commit Task 1

- [ ] **Step 1: Stage and commit**

```bash
git add app/src/main/java/com/klk/hams/data/db/EventDao.kt \
        app/src/main/java/com/klk/hams/data/db/TaskDao.kt \
        app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt \
        app/src/main/java/com/klk/hams/push/PushEngine.kt \
        app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt \
        app/src/main/java/com/klk/hams/push/PushWorker.kt \
        app/src/test/java/com/klk/hams/push/PushEngineTest.kt \
        app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt

git commit -m "feat(push): mark tasks failed (not uploaded) when events were rejected

Codex adversarial-review finding #2 (2026-05-15). The previous predicate
markTaskUploadedIfAllPushableEventsUploaded only checked 'no pending rows
remain', so a task whose final outbound event got #AD#-1 (pushed=2) was
silently flipped to push_status='uploaded' even though Wialon never
accepted the data.

Rename to markTaskTerminalState and split the outcome:
- 'failed' if any event for the task is pushed=2
- 'uploaded' if no rejected and no pending rows
- no-op if any pending row remains

New EventDao.countRejectedForTask query. Three JVM tests cover all-rejects,
mixed accepts-and-rejects, and the all-accepts regression. Three Room
instrumented tests cover the same shapes against a real DB; run via
:app:connectedDebugAndroidTest when a device is attached.

Spec: docs/superpowers/specs/2026-05-15-codex-adversarial-fixes-design.md §3."
```

- [ ] **Step 2: Verify clean tree**

Run: `git status --short`
Expected: empty.

---

## Task 2 — Retention sweep: `purgeStaleTerminalTasks`

### Task 2.1 — Add `TaskDao.deleteStaleTerminal`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/db/TaskDao.kt`

- [ ] **Step 1: Append the delete query**

Add before the closing brace of `TaskDao`:

```kotlin
    // Retention sweep (2026-05-15) — deletes terminal-state tasks older than
    // the configured cutoff. Active and pending tasks are NEVER purged.
    // Events cascade-delete via the FK in EventEntity.
    @Query(
        "DELETE FROM tasks " +
        "WHERE push_status IN ('uploaded','failed','discarded') " +
        "AND created_at < :cutoffIso"
    )
    suspend fun deleteStaleTerminal(cutoffIso: String): Int
```

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

### Task 2.2 — Add `TaskRepository.purgeStaleTerminalTasks`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt`

- [ ] **Step 1: Append the method**

Add after `markTaskTerminalState` (just below the block from Task 1.2):

```kotlin
    /**
     * Retention sweep (Codex adversarial-fix design §4, 2026-05-15). Deletes
     * `tasks` rows in a terminal state (`uploaded` / `failed` / `discarded`)
     * whose `created_at` is older than [retentionDays] from the current UTC
     * instant. Events cascade-delete via the FK in [EventEntity].
     *
     * `active` and `pending` tasks are NEVER purged, regardless of age — they
     * represent in-progress or unsynced work.
     *
     * Returns the number of task rows deleted (caller may log).
     */
    suspend fun purgeStaleTerminalTasks(
        retentionDays: Int = AppConfig.SQLITE_RETENTION_DAYS
    ): Int {
        require(retentionDays > 0) { "retentionDays must be positive, got $retentionDays" }
        val cutoff = java.time.Instant.now()
            .minus(retentionDays.toLong(), java.time.temporal.ChronoUnit.DAYS)
            .toString()
        return taskDao.deleteStaleTerminal(cutoff)
    }
```

- [ ] **Step 2: Add the `AppConfig` import if not already present**

In `TaskRepository.kt`, ensure the imports include:
```kotlin
import com.klk.hams.AppConfig
```
(It may already be imported — check before adding.)

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

### Task 2.3 — Add instrumented coverage

**Files:**
- Modify: `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt`

- [ ] **Step 1: Append two tests + a helper**

Insert before the `// ---- Task helpers ----` comment block:

```kotlin
    @Test fun purgeStaleTerminalTasks_deletesOldUploadedFailedDiscarded() = runBlocking {
        // 5 tasks: 3 terminal-old, 1 terminal-recent, 1 still-pending.
        val oldIso = "2020-01-01T00:00:00Z"
        val recentIso = java.time.Instant.now().toString()
        val tUploadedOld = insertTaskWithCreatedAt("uploaded", oldIso)
        val tFailedOld   = insertTaskWithCreatedAt("failed",   oldIso)
        val tDiscardOld  = insertTaskWithCreatedAt("discarded", oldIso)
        val tUploadedNew = insertTaskWithCreatedAt("uploaded", recentIso)
        val tPendingOld  = insertTaskWithCreatedAt("pending",  oldIso)

        val deleted = repo.purgeStaleTerminalTasks(retentionDays = 7)

        assertEquals(3, deleted)
        assertNull(taskById(tUploadedOld))
        assertNull(taskById(tFailedOld))
        assertNull(taskById(tDiscardOld))
        assertEquals(tUploadedNew, taskById(tUploadedNew)?.id)
        assertEquals(tPendingOld, taskById(tPendingOld)?.id)
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
```

Then add to the helpers block:

```kotlin
    private suspend fun insertTaskWithCreatedAt(status: String, createdAt: String): Long {
        val baseIso = "2026-05-15T00:00:00Z"
        return db.taskDao().insert(
            Task(
                id = 0,
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

    private suspend fun taskById(id: Long): Task? =
        db.taskDao().tasksWithStatus("uploaded").firstOrNull { it.id == id }
            ?: db.taskDao().tasksWithStatus("failed").firstOrNull { it.id == id }
            ?: db.taskDao().tasksWithStatus("discarded").firstOrNull { it.id == id }
            ?: db.taskDao().pendingTasks().firstOrNull { it.id == id }
            ?: db.taskDao().tasksWithStatus("active").firstOrNull { it.id == id }
```

- [ ] **Step 2: Add a `TaskDao.insert` if not exposed**

Run: `grep -n "fun insert" app/src/main/java/com/klk/hams/data/db/TaskDao.kt`
Expected: an `@Insert` method exists. If it doesn't (or it returns Unit, not Long), add:
```kotlin
    @androidx.room.Insert
    suspend fun insert(task: Task): Long
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

### Task 2.4 — Wire `HamsApp.onCreate` to invoke the sweep

**Files:**
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt`

- [ ] **Step 1: Add the purge call**

In `HamsApp.kt`, find the existing `applicationScope.launch { ... pending ... }` block that does the Task 10b force-stop recovery (immediately after `repository.onTaskFinalized = { pushController.enqueueAuto() }`).

Insert a new sibling launch BEFORE that block:

```kotlin
        // Retention sweep (Codex adversarial-fix design §4, 2026-05-15) —
        // deletes 'uploaded'/'failed'/'discarded' tasks older than the
        // configured retention. Runs once per process launch on a background
        // dispatcher; no UI blocking. Active and pending tasks are never
        // touched.
        applicationScope.launch {
            try {
                val deleted = repository.purgeStaleTerminalTasks()
                if (deleted > 0) {
                    Log.d("HAMS_PUSH", "onCreate: retention sweep deleted $deleted stale task(s)")
                }
            } catch (t: Throwable) {
                Log.w("HAMS_PUSH", "onCreate: retention sweep failed: $t", t)
            }
        }
```

- [ ] **Step 2: Compile + run JVM tests**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

### Task 2.5 — Commit Task 2

- [ ] **Step 1: Stage and commit**

```bash
git add app/src/main/java/com/klk/hams/data/db/TaskDao.kt \
        app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt \
        app/src/main/java/com/klk/hams/HamsApp.kt \
        app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt

git commit -m "feat(retention): purge stale terminal tasks (uploaded/failed/discarded) on app start

Adds TaskDao.deleteStaleTerminal and TaskRepository.purgeStaleTerminalTasks.
Runs once from HamsApp.onCreate on a background dispatcher. Honours the
existing AppConfig.SQLITE_RETENTION_DAYS = 7 constant (previously unread).

Active and pending tasks are never deleted. Events cascade-delete via the
FK declared in EventEntity. Pairs with the Codex finding #2 fix so failed
tasks self-clean rather than accumulating in the cache viewer.

Two instrumented tests cover the status-and-age filter and the FK cascade.

Spec: docs/superpowers/specs/2026-05-15-codex-adversarial-fixes-design.md §4."
```

- [ ] **Step 2: Verify clean tree**

Run: `git status --short`
Expected: empty.

---

## Task 3 — Finding #3: callback outside the transaction

### Task 3.1 — Move `onTaskFinalized` out of `saveActiveTask`'s transaction

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt`

- [ ] **Step 1: Read the surrounding block to preserve return semantics**

Locate `saveActiveTask` (around line 156). The shape currently is:
```kotlin
return db.withTransaction {
    // writes, including onTaskFinalized?.invoke() and a returned id
}
```

- [ ] **Step 2: Hoist the callback**

Replace the relevant block so it looks like this (preserve all existing inner writes; the ONLY changes are: move `onTaskFinalized?.invoke()` outside, and ensure the transaction's last expression is the return value):

```kotlin
        val savedId = db.withTransaction {
            val task = taskDao.getActiveTask() ?: return@withTransaction null
            if (task.netCount <= 0) return@withTransaction null
            // ... existing writes preserved verbatim ...
            // (was) onTaskFinalized?.invoke()    ← remove this line
            taskId   // existing returned id var
        }
        if (savedId != null) onTaskFinalized?.invoke()
        return savedId
```

**Important:** the existing function returns `Long?`. The hoist must NOT swallow the nullability — `savedId` is `Long?`.

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If you accidentally returned the wrong type, the compiler will complain — fix the local var type to `Long?` before continuing.)

### Task 3.2 — Same hoist for `rolloverActiveTaskIfStale`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt`

- [ ] **Step 1: Hoist the callback in rollover**

Locate `rolloverActiveTaskIfStale` (around line 347). The current shape:
```kotlin
return db.withTransaction {
    val active = taskDao.getActiveTask() ?: return@withTransaction null
    if (active.taskDate == today) return@withTransaction null
    // ... finalisation writes ...
    onTaskFinalized?.invoke()
    finalRowId
}
```

Replace with:
```kotlin
        val savedId = db.withTransaction {
            val active = taskDao.getActiveTask() ?: return@withTransaction null
            if (active.taskDate == today) return@withTransaction null
            // ... finalisation writes preserved verbatim ...
            // (was) onTaskFinalized?.invoke()   ← remove this line
            finalRowId   // existing returned id var
        }
        if (savedId != null) onTaskFinalized?.invoke()
        return savedId
```

- [ ] **Step 2: Compile + run JVM tests**

Run: `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

### Task 3.3 — Instrumented test for callback ordering

**Files:**
- Modify: `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt`

- [ ] **Step 1: Add the ordering test**

Append before the `// ---- Task helpers ----` block (right after Task 2.3's tests):

```kotlin
    @Test fun saveActiveTask_callbackFiresAfterCommit() = runBlocking {
        // Set up a real active task with count > 0 so save will actually fire.
        val started = "2026-05-15T00:00:00Z"
        db.taskDao().insert(
            Task(
                id = 0,
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

        var observedPendingCountInsideCallback = -1
        repo.onTaskFinalized = {
            // Block until we synchronously read the DB from the callback.
            kotlinx.coroutines.runBlocking {
                observedPendingCountInsideCallback = db.taskDao().pendingTasks().size
            }
        }

        val savedId = repo.saveActiveTask(
            saveType = "manual",
            location = null,
            batteryPct = 90.0,
        )

        // Callback must have observed the just-saved row as 'pending'. Pre-fix
        // (callback inside withTransaction) it would have observed 0.
        assertEquals(1, observedPendingCountInsideCallback)
        assertEquals(true, savedId != null)
    }
```

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

Note: if `saveActiveTask`'s real signature differs from `(saveType, location, batteryPct)`, adjust the call to match. Reference the function's current signature in `TaskRepository.kt` line ~156.

- [ ] **Step 3: Add the rollover variant of the ordering test**

Append immediately after the `saveActiveTask_callbackFiresAfterCommit` test:

```kotlin
    @Test fun rolloverActiveTaskIfStale_callbackFiresAfterCommit() = runBlocking {
        // Seed an active task with yesterday's task_date so rollover triggers.
        val yesterdayIso = "2026-05-14T10:00:00Z"
        db.taskDao().insert(
            Task(
                id = 0,
                deviceId = "TEST",
                taskSeq = 1,
                taskDate = "2026-05-14",
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

        var observedPendingFromCallback = -1
        repo.onTaskFinalized = {
            kotlinx.coroutines.runBlocking {
                observedPendingFromCallback = db.taskDao().pendingTasks().size
            }
        }

        val savedId = repo.rolloverActiveTaskIfStale()

        // Pre-fix the callback would have run inside the rollover transaction
        // and seen 0 pending. Post-fix it sees 1.
        assertEquals(1, observedPendingFromCallback)
        assertEquals(true, savedId != null)
    }
```

Note: this test depends on the system clock's "today" being later than `2026-05-14`. If running on a machine whose clock is rolled back to that date, the test reads as a no-op and asserts will fail — adjust the seed date relative to today or skip the test in that environment.

### Task 3.4 — Commit Task 3

- [ ] **Step 1: Stage and commit**

```bash
git add app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt \
        app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt

git commit -m "fix(repo): invoke onTaskFinalized after the save transaction commits

Codex adversarial-review finding #3 (2026-05-15). saveActiveTask and
rolloverActiveTaskIfStale fired the onTaskFinalized callback from inside
db.withTransaction. With Wi-Fi already up, WorkManager could start
PushWorker before the surrounding transaction committed; the worker then
read zero pending tasks and returned success(0), stranding the freshly-
finalised task until a later trigger.

Hoist the callback so it fires AFTER withTransaction returns. Side-effect
ordering is preserved (callback only fires when a real save happened —
savedId != null). One instrumented test (TaskRepositoryTest.saveActiveTask_
callbackFiresAfterCommit) proves the callback sees the new row in pending.

Spec: docs/superpowers/specs/2026-05-15-codex-adversarial-fixes-design.md §5."
```

- [ ] **Step 2: Verify clean tree**

Run: `git status --short`
Expected: empty.

---

## Final verification

- [ ] **Step 1: Full unit-test suite green**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full debug build clean**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 (deferred — when cable is connected): instrumented tests**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL with 6 new tests passing (3 from Task 1.6, 2 from Task 2.3, 1 from Task 3.3).

- [ ] **Step 4: Confirm three commits exist**

Run: `git log --oneline -3`
Expected: top three lines match commit messages from Task 1.7, 2.5, 3.4.

---

## Out of scope (do not implement under this plan)

- Finding #1 (279/280 migration): deliberately skipped — see spec §2 for rationale.
- Cache viewer (Phase 3.5): pending future plan; this plan only ensures the underlying state is correct.
- Notification wording: Outcome D already says "rejected by server"; no change needed.
- Retention configuration UI: 7-day default is hardcoded via `AppConfig.SQLITE_RETENTION_DAYS` (Option B from spec: no user-facing settings).
