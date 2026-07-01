# Push + Wi-Fi Trigger Design — Task 2.8

> **Status:** ✅ **Implemented 2026-05-14, field-verified on Honor `AWCX6R3B15001045`.** All 23 plan tasks landed (1–17, 18–20, 0b, 10b, 21). Build + unit tests + lint all green on the final sweep (Task 17). 10 tasks across May 8/13/14 pushed end-to-end through the real Wialon test unit with `#AL#1` login and `#AD#1` acks; all events flipped to `pushed=1`; all tasks to `push_status='uploaded'`. No crashes since the FGS / lint / gesture-replay / FGS-merged-manifest / notification-channel-permission fixes. Cooperation contract (§17) and force-stop recovery (§18) honoured. See `plans/phase2_ips_push.md` for the implementation log and `docs/superpowers/notes/2026-05-11-cc-push-wialon-debug-handoff.md` for the field-debug record.
>
> **Replaces:** previous in-app `BroadcastReceiver` plan (couldn't survive app close).
>
> **Drove:** Task 2.8 implementation. Subsequent UI/notification iterations (5s → 3s hold, status-pill detox, push-button badge, task-level notification progress, terminal-with-sound) layered on top per field feedback through 2026-05-13.

---

## 1. Context

Tasks 2.1–2.7 built the push primitives:
- `IPSFrameBuilder` (V6 16-field frame)
- `WialonIPSClient` (TCP login + send + ack parsing)
- `PushEngine` (chunking, retry/backoff, terminal `PushState`)
- `PushRepository` / `IpsSender` interfaces

What's missing: a **trigger** that fires the engine when Wi-Fi is available, **survives app close**, and exposes progress to both the UI and the system notification bar. Task 2.8 fills that gap.

## 2. Hard constraint — why `WorkManager`

| Mechanism | Survives app close? | Survives swipe? | Survives reboot? | Verdict |
|---|---|---|---|---|
| In-app `BroadcastReceiver` | ❌ | ❌ | ❌ | Out |
| Manifest `BroadcastReceiver` for `CONNECTIVITY_ACTION` | ❌ (Android 7+ stopped delivering) | — | — | Out |
| Foreground service with `NetworkCallback` | ✅ | ❌ (`onTaskRemoved`) | ❌ | Out |
| **`WorkManager` w/ `NetworkType.UNMETERED` constraint** | ✅ | ✅ | ✅ | **Chosen** |

WorkManager runs in a brief OS-managed worker process on demand. The app itself doesn't need to "stay awake."

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         Triggers (enqueue)                       │
│                                                                  │
│  [5s hold NEW TASK]──save("manual")────┐                         │
│                                         │                        │
│  [App swipe]──onTaskRemoved("auto_killed")──┐                    │
│                                              │                   │
│  [Day rollover at app launch]──auto_rollover──┐                  │
│                                                │                 │
│  [Push button 5s hold]──manual mode──┐         │                 │
│                                       v         v                │
│                          WorkManager.enqueueUniqueWork(          │
│                              "hams-push", REPLACE,               │
│                              OneTimeWorkRequest<PushWorker>(     │
│                                  setRequiredNetworkType(UNMETERED)│
│                              )                                   │
│                          )                                       │
└──────────────────────────────────────────────────────────────────┘
                                        │
                                        v
                  ┌──────────────────────────────────────┐
                  │  OS waits for validated Wi-Fi        │
                  │  When connected → spawn worker proc  │
                  └──────────────────────────────────────┘
                                        │
                                        v
┌──────────────────────────────────────────────────────────────────┐
│                          PushWorker.doWork()                     │
│                                                                  │
│  1. setForeground(notification("Pushing…"))                      │
│  2. Query repo.pendingTasks() (push_status = 'pending')          │
│  3. For each task: stream its 179/180/35 events through engine   │
│  4. On chunk progress → update notification + workDataOf()       │
│  5. On task complete → flip task push_status = 'uploaded'        │
│  6. On all-success → notification "Uploaded N tasks ✓"           │
│  7. On failure → return Retry() with exponential backoff         │
│  8. On 30-min budget exceeded (manual mode only) → return Failed │
└──────────────────────────────────────────────────────────────────┘
                                        │
                                        v
                  ┌──────────────────────────────────────┐
                  │  WorkInfo flow → PushController      │
                  │     ↓                                │
                  │  StateFlow<PushState> for UI         │
                  └──────────────────────────────────────┘
```

## 4. What gets pushed

Only tasks with **`push_status = 'pending'`**. Everything else is filtered:

| Task state | Pushed? | Why |
|---|---|---|
| `active` | ❌ | Worker is still using it. Push never auto-saves an active task. |
| `pending` | ✅ | All `save_type` variants (`manual`, `auto_killed`, `auto_rollover`) treated identically. |
| `discarded` | ❌ | Zero-count rollover; no events to send. |
| `uploaded` | ❌ | Terminal. |
| `failed` | ❌ | Permanent failure (e.g. `LoginRejected`). User must clear manually. |

The existing `repo.autoSaveActiveOnWifi(...)` pre-flight hook is **deprecated**. Push never finalizes active tasks. Active-task lifecycle is fully controlled by the worker (NEW TASK hold) or by Android lifecycle (app swipe → `auto_killed`, app launch on new day → `auto_rollover`).

### 4.1 Active-task rollover guarantee

An active task with `count > 0` reaches `pending` (and therefore eventually pushes) through one of four doors:

| # | Trigger | `save_type` written | When |
|---|---|---|---|
| 1 | Worker holds NEW TASK 5 s + confirms | `manual` | Voluntary, mid-shift |
| 2 | Worker swipes app from recents (`onTaskRemoved`) | `auto_killed` | End-of-shift gesture |
| 3 | App opens on a new MYT day (`rolloverActiveTaskIfStale` at `init`) | `auto_rollover` | Implicit, next launch |
| 4 | **Periodic in-process rollover (every 1 s)** | `auto_rollover` | Implicit, app stays open across midnight |

Door #4 closes the failure mode where the app process stays alive across `00:00 MYT` and the active task lingers with yesterday's `task_date`. The existing `staleHandler.staleTick` runnable (currently fires every `STALE_RECHECK_MS = 1_000` for GPS-lock + `todayDate` refresh) gains a third call:

```kotlin
private val staleTick = object : Runnable {
    override fun run() {
        recomputeGpsLock()
        refreshTodayDate()
        viewModelScope.launch {
            repository.rolloverActiveTaskIfStale()  // cheap no-op when today
        }
        staleHandler.postDelayed(this, STALE_RECHECK_MS)
    }
}
```

`rolloverActiveTaskIfStale()` is a Room transaction that early-returns when `active.taskDate == mytDateOf(now)` — > 99 % of ticks. Only at the first tick after midnight does it finalize yesterday's task. The next `+` lazy-creates today's task at `task_seq=1`. Behaviour is logged under tag `HAMS_UI` for diagnostics.

Acceptance: keep the app foregrounded during a real 23:59 → 00:01 MYT crossing; verify (a) yesterday's task in DB has `push_status='pending'` and `save_type='auto_rollover'`, (b) the next `+` produces a new task with `task_seq=1` and `task_date=` today, (c) WorkManager has been re-enqueued for push.

## 5. State machine

```kotlin
sealed interface PushState {
    data object Idle : PushState                                  // no pending tasks, no work in flight
    data class PendingWifi(val pendingTasks: Int) : PushState     // pending tasks exist, waiting for Wi-Fi
    data class Pushing(val total: Int, val done: Int) : PushState // worker actively uploading
    data class Completed(val tasks: Int, val at: Instant) : PushState  // last run succeeded; auto-decays to Idle
    data class Failed(val reason: String, val pending: Int) : PushState // last run failed; pending count preserved
}
```

Transition rules:

```
Idle ──new pending task──> PendingWifi(n)
PendingWifi ──Wi-Fi connects, worker starts──> Pushing(n, 0)
Pushing ──chunk acked──> Pushing(n, k+1)
Pushing ──all done──> Completed(n, now)
Pushing ──network drop──> PendingWifi(n - done)        [worker retries with backoff]
Pushing ──fatal (LoginRejected, max retries)──> Failed(reason, n - done)
Pushing ──manual-mode 30min budget exceeded──> Failed("timeout", n - done)
Completed ──5s timer──> Idle
Failed ──user taps notification or button──> PendingWifi(n)   [retry]
```

## 6. Auto-push flow (silent, app-closed friendly)

**Trigger:** any new `pending` task is created → enqueue `OneTimeWorkRequest<PushWorker>` with `existingWorkPolicy = KEEP` and tag `"hams-push-auto"`. **Auto uses KEEP, not REPLACE** — auto must never preempt a healthy run, including a manual-mode run already in flight. See §17 cooperation contract.

**Behaviour while app is closed/swiped:**
- WorkManager waits for `NetworkType.UNMETERED` (validated Wi-Fi).
- On Wi-Fi → spawns worker process → `setForeground(...)` shows the system notification (since worker takes > 10 s).
- Worker drains all `pending` tasks. Notification updates per chunk: `Uploading 5/12 tasks…`.
- Success → `Uploaded 12 tasks ✓` (auto-dismiss after 5 s).
- Failure (network drop, ack timeout) → `Upload paused — will retry` (persistent until next attempt).

**Behaviour while app is open:**
- Same notification flow.
- In-app status pill (top-right of CountScreen) shows `↑ 12 pending` / `↑ pushing 5/12` / `✓ uploaded` based on `PushController.stateFlow.value`.
- Counting / +/− / NEW TASK remain fully usable. Worker keeps recording into the active task while old tasks drain in the background.

**No 30-min timeout in auto mode** — WorkManager has its own backoff. If Wi-Fi never appears, work stays enqueued indefinitely. User sees `↑ N pending` chip; harmless.

## 7. Manual-push flow (button-triggered, UI-locked)

**Trigger UI:**
- Small icon button in the status strip top-right (or near the TASK pill — to be confirmed during impl).
- 3-second hold gesture (same `pointerInput + tryAwaitRelease` mechanic as NEW TASK; reduced from 5s per field feedback 2026-05-13).
- During hold: a forest-green border draws progressively around the icon (4dp stroke, growing arc/rectangle from 0 % to 100 %).
- At 100 % (5 s reached) → confirmation dialog: **"Push all pending data now?"** with Yes/Cancel.
- Cancelling the hold mid-way → border resets, no dialog.

**On confirm:**
- Enqueue same `OneTimeWorkRequest<PushWorker>` with tag `"hams-push-manual"` (REPLACE policy).
- Bind UI to that work via `WorkManager.getWorkInfoByIdFlow(...)`.
- **UI lock:** +/− and NEW TASK and the push button itself become disabled and dimmed (`alpha = 0.4f`). Counting still runs but is read-only.
- **Status panel** appears (modal overlay or sticky sheet at the bottom):
  - `PendingWifi` → `"Waiting for Wi-Fi… (12 tasks queued)"` + `Cancel` button + countdown to timeout (e.g. `28:45`).
  - `Pushing(total, done)` → `"Uploading task ${done+1} of ${total}…"` + linear progress bar. `Cancel` button hidden (would corrupt mid-batch state).
  - `Completed` → `"All ${tasks} tasks uploaded ✓"` + `OK` button. UI re-enables on dismiss or auto after 5 s.
  - `Failed` → `"Upload failed: ${reason}. ${pending} tasks remain in cache. Try again later or tap Retry."` + `Retry` and `Close` buttons. UI re-enables on close.

**Cancel during PendingWifi or Pushing (manual overlay):**
- **Does NOT cancel the worker.** Cancel is a UI-only action — the underlying worker continues silently as auto-push so pending data still drains on its own.
- Pending tasks stay in DB unchanged — they remain `push_status='pending'`.
- UI re-enables (`manualPushActive = false`). The badge keeps reflecting the live pending count from the controller.
- See §17 rule #3 for the precise contract; the only path that calls `WorkManager.cancelUniqueWork` is a fatal terminal failure inside the worker itself, never a user gesture.

**Connection resilience inside Pushing:**
- If TCP fails mid-batch → engine returns to PushController as `Failed(IOException)`.
- Controller decides: if elapsed time < 30 min budget → re-enqueue with backoff (10 s, 30 s, 60 s, 120 s, capped). State flips back to `PendingWifi`.
- If elapsed ≥ 30 min budget → terminal `Failed("timeout")`.
- Cache is never corrupted: events are only marked `pushed=1` after a successful `#AD#1` ack, inside a Room transaction.

**Force-kill resilience:**
- WorkManager job continues regardless. State persists in WorkManager's own database.
- On next app launch: `PushController` reads `getWorkInfosByTag("hams-push-manual")`:
  - State `RUNNING` or `ENQUEUED` → reattach UI, restore the dim + status panel.
  - State `SUCCEEDED` or `FAILED` (since last app launch) → show one-shot dialog: *"Last push N tasks: success / failed (M unsent). [OK]"*.

## 8. Pending-count badge

- Visible **only when count > 0**. Hidden at 0 to avoid persistent UI clutter.
- Position: small pill near the TASK pill in the status strip, or as a leading element of the manual-push button.
- Format: `↑ 12` (up-arrow + integer; max 4 digits, otherwise `99+`).
- Updates: `PushController` exposes `pendingTaskCount: StateFlow<Int>` derived from a Room query (`COUNT(*) FROM tasks WHERE push_status = 'pending'`) wrapped in a Flow.

## 9. Notifications

| Channel | Importance | Used for |
|---|---|---|
| `hams_service_channel` (existing) | LOW | Foreground service for active task — already in use |
| `hams_push_channel` (new) | DEFAULT | Push state notifications |

Notification states (channel: `hams_push_channel`, group: `hams_push`):

```
[Pushing]   "HAMS — Uploading 5/12 tasks"     [progress bar 0–100]   [persistent]
[Completed] "HAMS — 12 tasks uploaded ✓"      [no progress]          [auto-dismiss 5s]
[Failed]    "HAMS — Upload paused, will retry" [no progress]         [persistent]
[Failed final] "HAMS — Upload error: <reason>. Tap to retry."        [persistent]
[PendingWifi] (no notification — silent)
```

Tapping any notification opens MainActivity. Tapping `Failed final` → MainActivity routes to a small "Push status" screen (deferred to Phase 3) for now just opens the count screen.

POST_NOTIFICATIONS permission already in manifest. No new permission needed.

## 10. Heartbeat cadence change

`AppConfig.HEARTBEAT_INTERVAL_MINUTES`: **10 → 1**.

`HeartbeatScheduler` already supports any positive integer. The faster cadence:
- Captures worker movement between `+` presses with finer resolution
- Costs ~720 heartbeats per 12-hour shift = ~108 KB on the wire
- Each heartbeat is one IPS frame (~150 bytes) plus a Room write (~1 ms)
- Battery cost: negligible (the GPS stream is already running for `+` presses; heartbeat just snapshots `snapshotFlow.value`)

Heartbeats remain **active-task scoped** (Q-F = A). They fire only while a task is in `push_status='active'`. Idle periods (no task) produce no heartbeats. The first `+` of the next task is Wialon's next position fix.

## 11. Files affected

| File | Change |
|---|---|
| `app/build.gradle.kts` | Add `androidx.work:work-runtime-ktx` dependency (~150 KB APK) |
| `gradle/libs.versions.toml` | Add `workmanager` version + alias |
| `app/src/main/AndroidManifest.xml` | (no change — WorkManager auto-registers its provider) |
| `app/src/main/java/com/klk/hams/AppConfig.kt` | `HEARTBEAT_INTERVAL_MINUTES = 1`; new `PUSH_MANUAL_TIMEOUT_MS = 30 * 60_000L`, `PUSH_RETRY_BACKOFF_MS = listOf(10_000, 30_000, 60_000, 120_000)` |
| `app/src/main/java/com/klk/hams/push/PushWorker.kt` (new) | `CoroutineWorker` subclass. `doWork()` calls `PushEngine.run()`, handles `setForeground`, returns `Result.success()` / `Result.retry()` / `Result.failure()` |
| `app/src/main/java/com/klk/hams/push/PushController.kt` (new) | App-scope owner. Holds `WorkManager` reference; exposes `stateFlow: StateFlow<PushState>`, `pendingTaskCount: StateFlow<Int>`, `triggerManualPush()`, `cancel()`. Observes `WorkManager.getWorkInfosByTagFlow(...)` to derive `PushState`. |
| `app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt` (new) | Adapts `TaskRepository` → `PushRepository` interface (already defined in Task 2.7A). |
| `app/src/main/java/com/klk/hams/push/PushNotifier.kt` (new) | Notification channel creation + per-state notification builders. |
| `app/src/main/java/com/klk/hams/data/db/TaskDao.kt` | Add `observePendingTaskCount(): Flow<Int>` |
| `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` | Add `observePendingTaskCount()` passthrough; add `pendingTasks(): List<Task>` for the worker; **deprecate** `autoSaveActiveOnWifi` (mark `@Deprecated`, remove from preFlight). |
| `app/src/main/java/com/klk/hams/HamsApp.kt` | `val pushController by lazy { PushController(...) }`; create push notification channel. |
| `app/src/main/java/com/klk/hams/push/PushEngine.kt` | Drop the `preFlight` hook (or pass a no-op). Worker integration done at `PushWorker` level instead. |
| `app/src/main/java/com/klk/hams/ui/count/CountUiState.kt` | Add `pushState: PushState` and `pendingCount: Int` fields. |
| `app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt` | Observe `pushController.stateFlow` and `pendingTaskCount`; expose `triggerManualPush()` / `cancelManualPush()`; lock UI when state ∈ {PendingWifi-manual, Pushing}. **`staleTick` adds a `repository.rolloverActiveTaskIfStale()` call (Section 4.1).** |
| `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt` | Add **PushButton** in status strip (5s hold, progressive border draw); add **PendingBadge** when count > 0; add **PushStatusOverlay** sheet for manual mode (state-driven copy, Cancel/Retry/OK buttons); apply `alpha = 0.4f` + `enabled = false` to +/−/NEW TASK during manual lock. |
| Tests | `PushWorkerTest`, `PushControllerTest` (using fake `WorkManager` and fake engine). Existing `PushEngineTest` keeps working — engine logic unchanged. |

## 12. AppConfig diff (proposed)

```kotlin
const val HEARTBEAT_INTERVAL_MINUTES: Int = 1   // was 10

// Manual push session config
const val PUSH_MANUAL_TIMEOUT_MS: Long = 30 * 60_000L           // 30 min
val PUSH_RETRY_BACKOFF_MS: List<Long> = listOf(10_000, 30_000, 60_000, 120_000)
```

## 13. Out of scope (deferred)

- **Push status screen** (history of past pushes, last-error detail). Phase 3 / Task 2.9.
- **Per-task retry counter** persisted in DB. For now, retries are session-scoped (WorkManager backoff).
- **Cellular fallback** ("if no Wi-Fi for 24 h, push over cellular"). Out — explicit Wi-Fi-only policy stands.
- **Periodic heartbeat outside active tasks.** Q-F = A; rejected for this iteration.
- **Multi-device push contention** (e.g. two phones with same `device_unique_id`). Operational issue, not architectural.

## 14. Acceptance criteria

| # | Scenario | Expected |
|---|---|---|
| 1 | Worker saves a task (NEW TASK 5s hold), no Wi-Fi present | Task `push_status='pending'`, work enqueued. No notification. UI shows `↑ 1` pending badge. App can be closed; nothing in memory. |
| 2 | Same worker arrives at office Wi-Fi, app fully closed | OS triggers PushWorker. Notification appears: `Uploading 1/1 tasks`. Within ~5 s: `Uploaded 1 task ✓`. DB: task `push_status='uploaded'`, all events `pushed=1`. Worker doesn't open app. |
| 3 | Worker holds Push button 5 s, confirms | UI dims; status overlay shows `Waiting for Wi-Fi (1 task queued)` + Cancel + 30-min countdown. When Wi-Fi appears, transitions to `Uploading` → `Completed`. UI re-enables on dismiss. |
| 4 | During Pushing, Wi-Fi drops | Engine errors. Controller transitions back to PendingWifi after backoff. UI shows resumed waiting. When Wi-Fi reappears, push continues from the next un-acked task. No double-push of already-acked events. |
| 5 | Manual mode hits 30-min timeout | UI shows Failed: `"Timeout. 1 task remains in cache. Try again later."` Worker stops. Pending task stays `pending`. Auto-push remains active and will retry on next Wi-Fi. |
| 6 | Worker force-kills app during Pushing | WorkManager continues. Push completes. On next launch, dialog: `"Last push: 1 task uploaded successfully."` |
| 7 | Worker has 0 pending tasks | Pending badge hidden. Push button still tappable (5s hold) but on confirm, status overlay shows `Completed (0 tasks)` immediately and dismisses. |
| 8 | Active task in progress + Wi-Fi connects | Auto-push fires for whatever's `pending`. Active task is **untouched**. Worker keeps pressing `+` into the active task. Notification shows push progress for the pending tasks only. |
| 9 | Day rollover at app open | Yesterday's task → `pending` → enqueued → drains as soon as Wi-Fi appears. New day's task #1 starts fresh. |
| 10 | Heartbeats during 1-hour active task | ~60 heartbeat events written; all push successfully when Wi-Fi connects. |

## 15. Manual test plan (post-implementation)

1. **Cold install + offline.** Create 3 tasks (3 different counts). Verify all 3 in DB as `pending`. No notification. UI shows `↑ 3`.
2. **Wi-Fi connect (auto).** Within 30 s of Wi-Fi appearing, notification appears, all 3 push, DB confirms `uploaded`.
3. **Manual button + Wi-Fi already on.** Hold push button 5 s, confirm, status overlay flashes briefly through `Pushing`, completes.
4. **Manual + no Wi-Fi.** Hold + confirm. Status: `Waiting…` with countdown. Toggle airplane mode off → Wi-Fi connects → Pushing → Completed.
5. **Mid-push Wi-Fi drop.** During Pushing, kill Wi-Fi. Status returns to PendingWifi with backoff timer. Re-enable Wi-Fi → resumes.
6. **Force-kill mid-push.** Swipe app away during Pushing. Wait. Reopen app — confirm completion dialog shows.
7. **Day rollover.** Set device clock to next day, reopen app. Verify yesterday's task auto-rolls and pushes.
8. **Cooperation — manual during auto.** With Wi-Fi on, save 3 tasks. As auto-push starts (`Pushing` notification appears), hold the manual button. Verify: UI dims, status overlay attaches to the in-flight progress (no second worker, no restart), `Pushing(3, k)` continues from where auto was. Confirm only one `WM-WorkerWrapper` job-id in logcat for the whole run.
9. **Cancel preserves data.** Trigger manual push, hit Cancel mid-`PendingWifi`. Verify: overlay closes, UI unlocks, pending count in DB unchanged, WorkManager job still scheduled (`adb shell dumpsys jobscheduler | grep hams-push`). When Wi-Fi returns, auto pushes silently.
10. **Force-stop recovery.** Save 2 tasks offline, force-stop the app from Settings, reopen. Verify: `HamsApp.onCreate` re-enqueues auto-push if `pendingCount > 0` (Task 10b), no manual button hold required.

---

## 17. Auto + Manual cooperation contract

Auto-push and manual-push share one WorkManager unique-work queue and one SQLite cache. They must never produce two concurrent workers, an enqueue storm, a dead cycle, or data loss. The five rules below are the binding contract; their concrete code locations appear in §17.6 and the plan amendments.

### 17.1 Rule #1 — Single queue, single worker

- Both modes call `enqueueUniqueWork(WORK_NAME, ...)` where `WORK_NAME = "hams-push"`. WorkManager guarantees at most one worker for that name.
- Auto: `ExistingWorkPolicy.KEEP` — never preempt anything.
- Manual: `ExistingWorkPolicy.REPLACE` is **only** allowed when the controller's current `uiStateFlow.value` is **not** `Pushing`. See rule #2.

### 17.2 Rule #2 — Manual is a UI overlay, not a second pipeline

`PushController.triggerManual()` reads `uiStateFlow.value` and decides:

| Current state | Action | Reason |
|---|---|---|
| `Idle` | No-op (button disabled at `pendingCount == 0`). | Nothing to push. |
| `PendingWifi` | Enqueue REPLACE; flip `manualPushActive = true`. | No-op effect on the queue (constraints identical), but ensures the worker's tag is `"hams-push-manual"` for audit. |
| `Pushing` | **Skip enqueue.** Only flip `manualPushActive = true`. | Manual attaches the lock + overlay to the existing run. Never disrupts a healthy auto-run. |
| `Completed` | No-op (button disabled). | Nothing to push. |
| `Failed` | Enqueue REPLACE; flip `manualPushActive = true`. | Fresh attempt; previous failure was terminal. |

This rule eliminates the deadlock case where manual REPLACE kills a half-uploaded auto-batch.

### 17.3 Rule #3 — Cancel is UI-only

`PushController.dismissManualOverlay()` (renamed from `cancelManual`) **never calls `cancelUniqueWork`**. It only:

1. Sets `manualPushActive = false`.
2. Cancels the 30-min budget timer coroutine.

The worker keeps running. Pending data still drains. The user merely stops watching.

The single exception: a user-initiated permanent abort would need a separate "Stop and discard" button — explicitly **not** in scope for Task 2.8. If we ever add it, that path is the only legitimate caller of `cancelUniqueWork` plus a DB sweep to flip rows back to `pushed=0`.

### 17.4 Rule #4 — Single dedupe point

Three independent triggers can request a push:

| Trigger | Code site | Policy |
|---|---|---|
| Task finalized (`onTaskFinalized` callback) | `TaskRepository.saveActiveTask` / `rolloverActiveTaskIfStale` | `enqueueAuto()` → KEEP |
| App open with `pendingCount > 0` (Task 10b) | `HamsApp.onCreate` | `enqueueAuto()` → KEEP |
| Manual button confirmed | `CountViewModel.onPushButtonConfirmed` | `triggerManual()` → REPLACE-or-skip per rule #2 |

KEEP makes triggers #1 and #2 idempotent. WorkManager dedupes any number of fires into one worker. No enqueue storm is possible.

### 17.5 Rule #5 — `manualPushActive` flag has one rule

- **Set true** only when the user confirms the manual button.
- **Set false** when any of:
  - User taps Cancel/Close on the overlay.
  - The 30-min budget timer fires (`PUSH_MANUAL_TIMEOUT_MS`).
  - `uiStateFlow.value` transitions to `Completed` (Task 13 observer drives this).
  - `uiStateFlow.value` transitions to `Failed` and user taps Close (or after 5 s if untouched).
  - App goes to background for > 5 s (defensive; prevents UI lock from outliving the screen).

The flag never gates the worker. It only gates the lock + overlay. Worker behaviour is identical regardless of the flag's value.

### 17.6 Conflict matrix — all 16 cells must be safe

| Auto state ↓ \ User action → | None | Hold push button (5 s) | Cancel overlay | Save task / rollover |
|---|---|---|---|---|
| **Idle** | — | Enqueue REPLACE + lock | n/a (overlay closed) | Enqueue KEEP |
| **PendingWifi** | — | Enqueue REPLACE (no-op effect) + lock | Unlock; worker stays scheduled | KEEP no-op |
| **Pushing** | — | **Lock only** (no enqueue) | Unlock; worker continues | KEEP no-op |
| **Completed** | auto-Idle after 5 s | n/a (button disabled) | Unlock + ack | Enqueue KEEP for new pending |
| **Failed** | persist | Enqueue REPLACE + lock (retry) | Unlock + ack | KEEP no-op (worker stays Failed until retry) |

No row produces two workers. No row produces an infinite re-enqueue loop. Cancel never destroys data. Save-during-push never disrupts an in-flight upload.

### 17.7 30-minute manual budget — implementation

The 30-min cap (`AppConfig.PUSH_MANUAL_TIMEOUT_MS`) is a **UI-side timer**, not a worker-side one:

```kotlin
// PushController.triggerManual()
manualBudgetJob?.cancel()
manualBudgetJob = scope.launch {
    delay(AppConfig.PUSH_MANUAL_TIMEOUT_MS)
    if (_manualPushActive.value) {
        _manualPushActive.value = false
        // overlay observer flips to Failed("timeout") in the next combine() emission
    }
}
```

The worker keeps running past 30 min. Auto behaviour is unaffected. The user simply loses the in-app overlay after the budget elapses, with the Failed("timeout") explanation shown once before the overlay closes.

### 17.8 Code locations (binding for plan amendments)

| Rule | File | Method | Plan task |
|---|---|---|---|
| #1 KEEP for auto | `PushController.kt` | `enqueueAuto()` | Task 8 |
| #2 manual skips REPLACE if Pushing | `PushController.kt` | `triggerManual()` | Task 8 |
| #3 cancel is UI-only | `PushController.kt` | `dismissManualOverlay()` (renamed) | Task 8, Task 13, Task 16 |
| #4 single dedupe | already correct via unique work | — | — |
| #5 flag clears on terminal | `CountViewModel.kt` | `pushController.uiStateFlow` collector | Task 13 |
| 30-min UI-side timer | `PushController.kt` | `triggerManual()` body | Task 8 |

---

## 18. Force-stop and battery-restriction recovery

Android offers users three escalation levels against a "battery-draining app." Each has different effects on the push pipeline; HAMS must recover from all three without data loss.

### 18.1 Threat model

| User action | WorkManager queue | SQLite cache | Active task | Default recovery |
|---|---|---|---|---|
| **Force Stop** (Settings → App → Force stop) | All scheduled work cancelled. App enters Android "stopped" state — no broadcasts until launched. | Safe. Rows stay `push_status='pending'`. | Killed without `onTaskRemoved` firing (no `auto_killed` row written; the active task lingers). | User must reopen app via launcher icon. Without Task 10b, pending tasks stay stranded until the next finalization. |
| **Restrict background** (Settings → Battery → Restricted) | Stays scheduled. `NetworkType.UNMETERED` jobs may be deferred indefinitely or run only in Doze maintenance windows. | Safe. | Foreground service still allowed while user has the app open. | Often invisible — push silently doesn't fire. |
| **Battery-optimised** (default on most OEMs incl. Oppo ColorOS) | Constraints honoured but with longer doze windows. | Safe. | Normal. | Typically OK; OEM-specific aggression varies. |

### 18.2 Recovery paths

**Universal handle:** the launcher icon. Tapping the HAMS icon un-stops the app, runs `Application.onCreate`, reinitialises WorkManager, and re-binds UI. Every recovery path starts here.

| Path | User effort | What happens |
|---|---|---|
| Open app → manual push button (5 s hold) | High | `PushController.triggerManual()` enqueues a fresh worker. UI overlay shows progress. |
| Open app → silent re-enqueue (Task 10b) | None | `HamsApp.onCreate` checks `repository.observePendingTaskCount().first() > 0` once at startup and calls `pushController.enqueueAuto()`. Worker fires when validated Wi-Fi appears. |
| Open app → start a new task → save | Indirect | Existing `onTaskFinalized` callback (Task 11) re-enqueues. Works but requires extra worker action. |

### 18.3 Required mitigations (now in plan)

- **Task 0b** (new): one-shot onboarding screen on first launch. Requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Shows OEM-specific copy for Oppo ColorOS ("Allow auto-launch", "Allow background activity"). Adds the permission to manifest. Persists a `SharedPreferences` flag so onboarding fires once.
- **Task 10b** (new): in `HamsApp.onCreate`, after `repository.onTaskFinalized` is wired, query pending count once. If `> 0`, call `pushController.enqueueAuto()`. Closes the post-force-stop gap without user action.

### 18.4 What is NOT recoverable automatically

- The active task's last few presses immediately before a force-stop **are** safe — they were written to SQLite synchronously per the GPS gate rule. But the active task itself is not finalized to `pending` (no `onTaskRemoved` fires on force-stop). It stays `active` until the user opens the app, when the daily-rollover tick or NEW TASK hold finalizes it normally.
- A reboot does **not** un-stop a force-stopped app. The user must explicitly tap the launcher icon at least once after a force-stop.

### 18.5 Acceptance — added to §15 manual test plan as scenario #10

Save 2 pending tasks offline → force-stop from Settings → reopen via launcher icon → verify: (a) WorkManager job re-enqueued via Task 10b, (b) when Wi-Fi appears, both tasks push silently, (c) no manual button hold was needed.
