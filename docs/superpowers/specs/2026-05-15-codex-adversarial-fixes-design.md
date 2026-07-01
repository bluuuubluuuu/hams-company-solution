# 2026-05-15 — Codex adversarial-review fixes (Phase 3.3 follow-up)

**Branch:** `phase/2-ips-push`
**Trigger:** `/codex:adversarial-review` on the Phase 3.3 commits (`bb7d014`, `f1a4b3f`, `f90b57d`) returned `needs-attention` with three findings.
**Scope:** address Findings #2 and #3 from the Codex review. Finding #1 is deliberately deferred — see rationale below.

---

## 1. Background — Codex findings recap

| # | Severity | Summary | Root file |
|---|---|---|---|
| 1 | critical | v1→v2 migration strands legacy 279/280 count events; can silently mark unsynced data as uploaded across app upgrade. | `AppDatabase.kt:27-31` (`MIGRATION_1_2`) |
| 2 | high | Rejected events (`pushed=2`) cause tasks to flip to `push_status='uploaded'` because the predicate only checks "no pending rows remain" instead of "all rows accepted". | `TaskRepository.kt:393-400` |
| 3 | high | `onTaskFinalized()` fires inside `db.withTransaction { ... }`. With Wi-Fi already up, WorkManager can start `PushWorker` before the transaction commits → worker sees 0 pending → `Result.success(0)` and the task is stranded until a later trigger. | `TaskRepository.kt:191-192`, `:355` |

---

## 2. Finding #1 — deliberately skipped

**Status:** not fixed. Documented here so it doesn't resurface unaddressed.

`main` shipped `EVENT_CODE_PLUS=279` and `EVENT_CODE_MINUS=280`. The branch flipped to 179/180 (commit `2bea469`, "v1.2 dictionary policy"). Codex correctly identified that an upgrade from a `main`-built install would strand 279/280 rows because `pendingPushableEvents` filters on `event_code IN (179, 180, 35)`.

**Why the fix is unnecessary in practice:**

- `main` was never deployed to a production user. The dev device (Honor X9b, WYH personal) is the only environment that ever ran `main` code. The target production device (Oppo A5i, plantation worker handset) has not been deployed at all.
- The first production install on Oppo A5i will ship from `phase/2-ips-push` (or its descendant), creating a fresh v2 SQLite with 179/180 only. No 279/280 row ever exists on a production device.
- Dev devices will be cleared by `adb uninstall` + reinstall before the next round of testing, eliminating any local v1 row.

**Future-proofing note:** every schema or event-semantic change after this branch must carry its own migration. Codex's underlying recommendation — "add a migration test alongside every schema change" — is sound general practice. Future schema work (Phase 4+) MUST follow it. This spec does not retroactively backfill that for the 279/280 case.

---

## 3. Finding #2 — distinguish `failed` from `uploaded`

### 3.1 Problem

`markTaskUploadedIfAllPushableEventsUploaded(taskId)` currently flips a pending task to `'uploaded'` whenever `EventDao.countPushablePendingForTask(taskId) == 0`. That count is based on `WHERE pushed=0 AND event_code IN (179,180,35) AND NOT (event_code=180 AND work_count<=0)`. Rejected events (`pushed=2`) are skipped by this query, so a task that had one frame permanently rejected by Wialon passes the predicate and is reported to the user as fully uploaded — even though the rejected event was never accepted.

### 3.2 Fix

Replace the existing method with `markTaskTerminalState(taskId)` that distinguishes two terminal states:

- `'failed'` if any event for the task has `pushed=2`.
- `'uploaded'` if no rows remain pending and no rows are rejected.
- No-op if there are still pending rows.

A new `EventDao.countRejectedForTask(taskId): Int` query supplies the rejected-event count (`WHERE task_id=:taskId AND pushed=2`).

```kotlin
suspend fun markTaskTerminalState(taskId: Long) {
    db.withTransaction {
        val pending  = eventDao.countPushablePendingForTask(taskId)
        if (pending > 0) return@withTransaction
        val rejected = eventDao.countRejectedForTask(taskId)
        val status   = if (rejected > 0) "failed" else "uploaded"
        taskDao.setPushStatusIfPending(taskId, status, clock.nowUtcIso())
    }
}
```

`setPushStatusIfPending` already only acts on rows currently in `push_status='pending'`, so already-terminal tasks are left alone.

### 3.3 Callers

The only caller is `PushEngine.finalizeTouchedTasks` via the `PushRepository.markTaskUploadedIfAllPushableEventsUploaded(...)` interface method, and `PushWorker.doWork` (per-task sweep after `engine.run()`).

- Rename the interface method to `markTaskTerminalState(taskId)` for honesty. Both impls (`PushRepositoryImpl`, fakes in tests) update.
- The existing engine logic that calls `finalizeTouchedTasks` does not change — it still walks `touchedTaskIds` after each attempt; the predicate is just smarter now.

### 3.4 Cache-viewer / UI impact

Cache viewer (Phase 3.5, not yet built) will render `'failed'` with a red pill alongside `'uploaded'` (green). No notification change — the existing Outcome D ("X rejected by server — check device setup") already alerts the user at reject time. Failed tasks are informational only; no required supervisor action.

### 3.5 Chain observer

`HamsApp.onCreate`'s chain-re-enqueue observer guards on `pushController.pendingCountFlow.value > 0`. `pendingTasks()` queries `WHERE push_status='pending'`, so neither `'uploaded'` nor `'failed'` count. The infinite-loop guard (`state.tasks > 0`) is therefore unchanged.

---

## 4. Retention sweep (new)

### 4.1 Why this is in scope

`AppConfig.SQLITE_RETENTION_DAYS` (bumped 7 → 30 on 2026-05-15 to align with FR-06's 30-day offline capacity; see resolved follow-up in `docs/HAMS_PHASE0_PHASE1_CHECKPOINT.md`) is a constant nothing reads. Without a sweep, `'failed'` tasks pile up on the device indefinitely. Option D (the chosen Finding #2 lifecycle) requires retention to be honest. Storage cost is trivial: worst-case ~10 MB on a 64 GB device at FR-06 peak volume.

### 4.2 Surface

```kotlin
suspend fun purgeStaleTerminalTasks(retentionDays: Int = AppConfig.SQLITE_RETENTION_DAYS): Int
```

Deletes `tasks` rows where `push_status IN ('uploaded','failed','discarded')` AND `created_at < now − retentionDays`. Events are deleted by foreign-key cascade (already configured in schema). Returns the number of task rows deleted. Pure data, no notification, no log noise beyond a single info line.

`'active'` and `'pending'` tasks are NEVER purged regardless of age — they represent in-progress or unsynced work.

### 4.3 Invocation

One call site: `HamsApp.onCreate`, after the existing pending-count read and before the chain observer starts. Runs once per process launch. Bounded by retention age, so the SQL `DELETE` is a single statement against an indexed column.

### 4.4 DAO additions

- `TaskDao.deleteStaleTerminal(cutoffIso: String): Int` — `DELETE FROM tasks WHERE push_status IN ('uploaded','failed','discarded') AND created_at < :cutoffIso`. Returns rows affected.

Schema is unchanged. No migration needed (already on v2).

---

## 5. Finding #3 — callback outside the transaction

### 5.1 Problem

`TaskRepository.saveActiveTask`:

```kotlin
return db.withTransaction {
    // ... writes ...
    onTaskFinalized?.invoke()    // line 192 — INSIDE transaction
    savedId
}
```

`HamsApp` wires `repository.onTaskFinalized = { pushController.enqueueAuto() }`. `enqueueAuto` calls `WorkManager.enqueueUniqueWork`. If Wi-Fi is up and WorkManager schedules immediately, the worker may invoke `repo.pendingTasks()` before the transaction this callback is nested inside commits. Worker sees zero pending → `Result.success(0)` → task stranded.

Same shape in `rolloverActiveTaskIfStale` (line 355).

### 5.2 Fix

Move the callback outside the `withTransaction` block. The repo function captures whether finalization happened (a boolean or the saved id), and invokes the callback *after* the transaction returns.

```kotlin
suspend fun saveActiveTask(...): Long? {
    val savedId = db.withTransaction {
        // ... writes ...
        finalRowId
    }
    if (savedId != null) onTaskFinalized?.invoke()
    return savedId
}
```

Same pattern for `rolloverActiveTaskIfStale`.

This is the textbook fix: side effects belong outside transactions.

### 5.3 Why not setInitialDelay or post-commit listeners

- A small `setInitialDelay` would mask the race on fast devices but flake on slow ones; doesn't fix the root cause.
- Room has no post-commit listener API short of `InvalidationTracker`, which is heavier than required and observes table invalidations, not transaction boundaries.

### 5.4 Side-effect ordering

`onTaskFinalized` only fires when a real save happened (`savedId != null`). The current code already returns `null` for no-op paths (no active task, count <= 0, etc.) and the callback was inside the same conditional. Moving the call out preserves "fire only on real finalize" semantics.

---

## 6. Test plan

### 6.1 Unit tests (JVM, no Android)

- **`PushEngineTest.taskWithRejectedFinalEvent_marksTaskFailedNotUploaded`** — Fake repo records `markTaskTerminalState(taskId)` calls. Inject one event that returns `WialonError.FrameRejected`. After `engine.run()`, assert the repo was told to set state to `'failed'`, not `'uploaded'`. (Fake `PushRepository` extended with a captured-call list.)
- **`PushEngineTest.taskWithMixedAcceptsAndRejects_marksTaskFailed`** — Two events, one `#AD#1`, one `#AD#-1`. Assert `'failed'`.
- **`PushEngineTest.taskWithAllAccepts_marksTaskUploaded`** — Regression: existing happy path still yields `'uploaded'`.

### 6.2 Integration tests (Android instrumented, Room)

- **`TaskRepositoryTest.markTaskTerminalState_rejectedEventYieldsFailed`** — Insert a task, two events (one `pushed=1`, one `pushed=2`), call `markTaskTerminalState(taskId)`, assert `tasks.push_status='failed'`.
- **`TaskRepositoryTest.markTaskTerminalState_allUploadedYieldsUploaded`** — All events `pushed=1` → `'uploaded'`.
- **`TaskRepositoryTest.markTaskTerminalState_anyPendingNoOp`** — One event `pushed=0` → no state change.
- **`TaskRepositoryTest.purgeStaleTerminalTasks_deletesOldUploadedAndFailed`** — Insert 5 tasks across `'uploaded'/'failed'/'active'/'pending'/'discarded'` states with mixed `created_at`. Run purge with 7-day cutoff. Assert only `'uploaded'/'failed'/'discarded'` rows older than cutoff are deleted; `'active'` and `'pending'` are preserved regardless of age.
- **`TaskRepositoryTest.purgeStaleTerminalTasks_cascadesEvents`** — Verify event rows for purged tasks are deleted via FK cascade.
- **`TaskRepositoryTest.saveActiveTask_callbackFiresAfterCommit`** — Wire a fake `onTaskFinalized` that calls `pendingTasks()` inside its body. Save a task with `netCount > 0`. Assert the callback sees the newly-saved row in the pending list (proves transaction visibility).
- **`TaskRepositoryTest.rolloverActiveTaskIfStale_callbackFiresAfterCommit`** — Same shape against the rollover path.

### 6.3 Manual field verification (Honor X9b → Oppo A5i later)

- Backlog 5 tasks; force one event into a permanent reject (temporary inject `#AD#-1` on first send to a chosen event id, revert after).
- Confirm: notification outcome D fires; cache-viewer (when Phase 3.5 lands) shows that task in red, others in green.
- Confirm: 24-hour-aged failed task disappears after the next app launch (retention sweep).
- Confirm: rapid auto-save → push (Wi-Fi already up) does not drop the new task (no zero-pending worker run for a freshly-finalized task).

---

## 7. Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/klk/hams/data/db/EventDao.kt` | Add `countRejectedForTask(taskId): Int`. |
| `app/src/main/java/com/klk/hams/data/db/TaskDao.kt` | Add `deleteStaleTerminal(cutoffIso): Int`. |
| `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` | Rename `markTaskUploadedIfAllPushableEventsUploaded` → `markTaskTerminalState`; new logic per §3.2. Add `purgeStaleTerminalTasks`. Move `onTaskFinalized?.invoke()` outside `withTransaction` in `saveActiveTask` and `rolloverActiveTaskIfStale`. |
| `app/src/main/java/com/klk/hams/push/PushEngine.kt` | Rename `PushRepository.markTaskUploadedIfAllPushableEventsUploaded` interface method → `markTaskTerminalState`. Update `finalizeTouchedTasks` call site. |
| `app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt` | Update interface impl. |
| `app/src/main/java/com/klk/hams/push/PushWorker.kt` | Update the per-task sweep call site name. |
| `app/src/main/java/com/klk/hams/HamsApp.kt` | Invoke `repository.purgeStaleTerminalTasks()` once during `onCreate`, before the chain observer starts. |
| `app/src/test/java/com/klk/hams/push/PushEngineTest.kt` | Update `FakeRepo` method name; add three new tests per §6.1. |
| `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt` (may need to be created) | Add 7 integration tests per §6.2. |

Total: ~9 files, est. ~250 lines incl. tests.

---

## 8. Non-goals

- No new schema columns. The existing `push_status` text field already supports `'failed'`.
- No notification wording changes. Outcome D already says "rejected by server".
- No supervisor UI / admin tooling. Cache viewer (Phase 3.5) is the supervisor surface; nothing required from supervisors for `'failed'` tasks beyond optional inspection.
- No retry-with-cap mechanism for rejected events. Permanent rejects are almost always code bugs; auto-retry would mask them and spam the gateway.
- No backfill or migration for the 279/280 case (see §2).

---

## 9. Commit shape

1. `feat(push): mark tasks failed (not uploaded) when events were rejected`
   — DAO + repo + tests for the terminal-state predicate.
2. `feat(retention): purge stale terminal tasks (uploaded/failed/discarded) on app start`
   — DAO + repo + HamsApp wiring + tests.
3. `fix(repo): invoke onTaskFinalized after the save transaction commits`
   — saveActiveTask + rolloverActiveTaskIfStale + tests.

Three commits keep each concern reviewable in isolation.
