# Phase 3 — Notifications & UI Polish

> **Prereq:** Phase 2 complete. `PushEngine.state: StateFlow<PushState>` emits `Idle/Running/Success/Partial/Failed`.
> **Read first:** `docs/HAMS_V2_APP_REQUIREMENTS.md` FR-09, § "Phase 3 — Notifications & Polish", § "Phase 3 — Notification Tests".
> **Read also:** `CLAUDE.md` § "Push Engine Rules" ("Progress bar + single rolling Android notification during push").

---

## Audit (2026-05-15) — reconciliation with Phase 2.8 reality

Phase 2.8 already shipped the notification pipeline and field-verified it (commit `0eb46dc`). Reconciliation:

| Task | Original plan | Actual on `phase/2-ips-push` |
|---|---|---|
| 3.1 Notification channel + manager | `ui/notification/PushNotificationManager.kt`, 4 states | **✅ DONE** as `push/PushNotifier.kt`. Channel `hams_push_channel`, single `NOTIFICATION_ID=2001`, `ensureChannel`, `build`, `contentFor(PushUiState)`, and **four terminal outcomes** A/B/C/D (`terminalAllClean` / `terminalPartialRetry` / `terminalAllPaused` / `terminalRejected`). Adds silent-during-progress + alert-at-terminal rule (2026-05-13 field feedback) — richer than original spec. |
| 3.2 Service → notification routing | `HamsForegroundService` observes `PushEngine.state` | **✅ DONE** via `PushWorker` + `PushController` (WorkManager-owned, not service-owned). One rolling notification per worker run, field-verified. |
| 3.3–3.7 | DataStore, cache viewer, settings screen, nav | **❌ Pending** — unchanged. |
| 3.8 Kiosk | Only if C11=Yes | **⏭ Skip** unless C11 confirmed. |

Phase 3 work remaining (revised 2026-05-15):

- **3.3 — Task-cap with cross-run campaign tracking** (replaces original SettingsStore work)
- **3.4 — ~~PushEngine reads settings~~** — **DROPPED** (Option B: no user-facing settings)
- **3.5 — Cache viewer** — pending
- **3.6 — ~~Settings screen~~** — **DROPPED**
- **3.7 — Navigation** — pending, simplified to 2 tabs (Count / Cache)
- **3.8 — Kiosk mode** — skip unless C11 = Yes

Tasks 3.1 and 3.2 are retained below for historical reference; do not re-implement.

### Rationale for dropping 3.4 / 3.6 (Option B, approved 2026-05-15)
Harvesters have no basis to tune `batchSize` / `maxRetryAttempts`. Supervisor config is out of scope per `CLAUDE.md` (AR-01 deferred). Defaults from `AppConfig` survived field verification 2026-05-14. Any future tuning is a rebuild, not a runtime knob.

---

## Objective (revised)

1. **One rolling Android notification** — DONE (Task 3.1/3.2 under Phase 2.8).
2. **Resilient push pacing** — cap each worker run at 10 tasks while keeping the user-facing denominator stable across runs (Task 3.3).
3. **Cache viewer** — read-only scrollable task history with status pills (Task 3.5).
4. **Navigation** — bottom nav: Count / Cache (Task 3.7).
5. **Optional kiosk mode** — only if confirmation item **C11** is confirmed as "yes" (Task 3.8).

No change to the IPS protocol, no new Room columns, no new networking.

---

## Acceptance Criteria (verbatim from `docs/HAMS_V2_APP_REQUIREMENTS.md`)

### FR-09 Push Notification
Single rolling Android notification that updates through push lifecycle.

**States:**
- **Uploading:** `"Uploading tasks... X / Y"` — persistent, not dismissable.
- **Success:** `"All Y tasks uploaded successfully."` — auto-dismiss after 10 s, tap to dismiss.
- **Partial:** `"X/Y uploaded. Z pending — retrying..."` — persistent, not dismissable.
- **Failed:** `"Upload failed after N attempts. Z pending."` — persistent, tap to dismiss.

**Implementation:** reuse the same Android notification ID for all updates. Worker sees one notification, never a flood.

### Phase 3 Tasks (from requirements doc)
| Step | Req | What to Build |
|------|-----|---------------|
| 3.1 | FR-09 | Rolling notification: 4 states, single notification ID |
| 3.2 | —   | Cache viewer: saved tasks, status, count, timestamp |
| 3.3 | —   | Settings screen: batch size, retry count, device ID, version |
| 3.4 | —   | Kiosk mode (**if C11 confirmed**) |

### Phase 3 Testing Checklist
- Single notification during push, updates per batch.
- Success notification auto-dismisses after 10 s.
- Partial failure notification stays persistent.
- Failed notification stays until tapped.
- Cache viewer shows all tasks with correct status.
- Settings: batch size change takes effect on next push.

---

## Prerequisites

- Phase 2 merged. `PushEngine.state` observable from `HamsApp.pushState`.
- Branch: `phase/3-ui-polish`.
- `POST_NOTIFICATIONS` already declared in the manifest (Phase 0).

---

## File Structure (what this phase creates / modifies)

Create:
```
app/src/main/java/com/klk/hams/
  ui/
    notification/
      PushNotificationManager.kt      single notification id, 4 render functions
      NotificationChannels.kt         channel ids + creation helper
    cache/
      CacheScreen.kt                  Compose: LazyColumn of tasks
      CacheViewModel.kt               Flow<List<Task>> from repo
    settings/
      SettingsScreen.kt               Compose: batch size slider, retry count, device id
      SettingsViewModel.kt            backed by SettingsStore
  data/
    preferences/
      SettingsStore.kt                DataStore wrapper, exposes StateFlow<Settings>
      Settings.kt                     data class: batchSize, maxRetryAttempts
```

Modify:
- `data/repository/TaskRepository.kt` — add `observeRecentTasks(limit: Int = 100): Flow<List<Task>>`.
- `push/PushEngine.kt` — read `batchSize` / `maxAttempts` from `SettingsStore` if present, fall back to `AppConfig` constants.
- `service/HamsForegroundService.kt` — own a `PushNotificationManager`, collect from `PushEngine.state` and call the right render function.
- `MainActivity.kt` — add a simple bottom nav or TopAppBar menu with three destinations: Count, Cache, Settings.

Tests under `app/src/test/java/com/klk/hams/`:
```
ui/notification/PushNotificationManagerTest.kt    state → notification mapping (use shadows if needed, else pure map logic)
data/preferences/SettingsStoreTest.kt             DataStore round-trip
```

---

## Task Breakdown

### Task 3.1 — Notification channels + manager (FR-09) — **✅ DONE (Phase 2.8, commit `0eb46dc`). Implemented as `push/PushNotifier.kt`. Do not re-do.**

**Files**
- Create: `ui/notification/NotificationChannels.kt`, `ui/notification/PushNotificationManager.kt`

**Channels**
- `channel_push_status` — default importance, for push lifecycle notifications. Created in `HamsApp.onCreate`.
- `channel_service_running` — low importance, already created in Phase 1 for the foreground service's persistent notification. **Do not merge** — keep separate so the worker can silence the service pip if they want.

**Manager surface**
```kotlin
class PushNotificationManager(private val context: Context) {
    companion object { const val NOTIF_ID_PUSH = 1001 }
    fun showRunning(pushed: Int, total: Int)
    fun showSuccess(total: Int)       // schedules self-dismiss after 10_000 ms via Handler
    fun showPartial(pushed: Int, pending: Int)
    fun showFailed(attempts: Int, pending: Int)
    fun clear()
}
```

All methods build a `NotificationCompat.Builder` with `setOnlyAlertOnce(true)` (prevents flood), `setOngoing(true)` except on `showSuccess` / `showFailed`. Same `NOTIF_ID_PUSH` every time.

**Steps**

- [ ] Step 1 — Write `PushNotificationManagerTest` (JVM, no Android) for a thin pure-Kotlin `NotificationContent` data class that maps `PushState → (title, text, ongoing, autoDismissMs?)`. Test the mapping directly; keep Android-specific notification building untested (trust AOSP).
- [ ] Step 2 — Implement `NotificationContent.from(state: PushState): NotificationContent` in `PushNotificationManager.kt` companion. Tests PASS.
- [ ] Step 3 — Implement the Android-facing methods using `NotificationContent`.
- [ ] Step 4 — Commit: `feat(notify): add single-id rolling push notification`.

### Task 3.2 — Service observes state → notification — **✅ DONE (Phase 2.8). Owned by `PushWorker`/`PushController`, not `HamsForegroundService`. Do not re-do.**

**Files**
- Modify: `service/HamsForegroundService.kt`

**Steps**

- [ ] Step 1 — In `onCreate`, after constructing `PushEngine`, collect `engine.state` and call the appropriate `PushNotificationManager` method.
- [ ] Step 2 — Manual verify: airplane mode → 25 presses → hold New Task and confirm save → Wi-Fi on → observe single notification ticking 1/25 → 25/25 → auto-dismiss ~10 s later.
- [ ] Step 3 — Manual verify partial: force `WialonIPSClient` to return `FrameRejected` for event #3 (temporary code change, revert before commit). Observe `24/25 uploaded. 1 pending — retrying...`.
- [ ] Step 4 — Commit: `feat(service): route PushState to rolling notification`.

### Task 3.3 — Task-cap with cross-run campaign tracking (revised 2026-05-15)

**Goal:** Cap each `PushWorker` run at 10 tasks for resilience to Wi-Fi flapping, while showing the user a stable denominator (e.g. `X / 43`) across multiple runs until the backlog drains.

**Behavior contract**
- Worker run processes at most 10 task IDs' worth of events. Inside a run, TCP session chunk size stays at 10 events (unchanged).
- A "campaign" snapshot (`total = pending tasks at first run`) persists across worker re-enqueues via `SharedPreferences`. Cleared only when pending hits 0.
- Notification denominator = `campaign.total` (stable). Numerator = `campaign.done + currentChunkProgress`.
- New tasks finalized mid-campaign do NOT inflate the denominator — they're picked up in the next campaign.
- Run numbers ("Run 2 of 5") are NOT displayed — implementation detail.

**Example (43 pending)**
- Run 1: campaign locked at 43. Process 10. Ticks `1/43 → 10/43`. Terminal B: "10 of 43 uploaded · 33 remaining · will retry on Wi-Fi". Worker self-enqueues next run (UNMETERED, KEEP).
- Run 2: campaign still 43. Process 10. Ticks `11/43 → 20/43`. Terminal B.
- ... Run 5: process last 3. Ticks `41/43 → 43/43`. Terminal A: "43 tasks uploaded successfully ✓". Campaign cleared.

**Files to create**
- `push/PushCampaign.kt` — small SharedPreferences-backed tracker. ~30 lines. Methods: `start(total)`, `recordCompletion()`, `snapshot(): Pair<Int,Int>?`, `clear()`. Never overwrites an active campaign.

**Files to modify**
- `push/PushEngine.kt` — add `taskBatchLimit: Int = 10` ctor param. Change pending fetch to take events only for the first `taskBatchLimit` distinct oldest task IDs. Inside, keep `chunkSize = 10 events per TCP session` unchanged.
- `push/PushWorker.kt` — at start, if no active campaign, count total pending tasks → `PushCampaign.start(total)`. Wrap `onProgress` so each task completion calls `recordCompletion()` and updates the notification with the cumulative `(done, total)`. At end of run: if pending > 0 AND this run uploaded ≥ 1 task, self-enqueue another `OneTimeWorkRequest` (UNMETERED, KEEP). If pending == 0, clear campaign.
- `push/PushNotifier.kt` — `contentFor(PushUiState.Pushing)` already takes `(done, total)`; verify it accepts campaign-cumulative numbers without changes. Adjust title format if needed.

**Sanity check on resume** — at `PushWorker.start`, if `currentPendingTasks > campaign.total - campaign.done` (i.e. more pending than the campaign expected), reset the campaign. Prevents stale campaign state after force-stop.

**Self-enqueue guard** — only re-enqueue if `tasksUploadedThisRun ≥ 1`. Prevents infinite re-enqueue loop on a perpetually-rejected task.

**Tests** (JVM, no Android)
- `PushEngine_honorsTaskBatchLimit` — with 25 pending tasks across many events, one `run()` pulls events for ≤ 10 task IDs.
- `PushCampaign_startOnceOnly` — second `start()` is a no-op while active.
- `PushCampaign_clearResetsState` — after clear, next `start()` accepts new total.
- `PushCampaign_persistsAcrossInstances` — using a fresh `SharedPreferences` mock.

**Commits**
1. `feat(push): cap worker run at 10 tasks (PushEngine.taskBatchLimit)`
2. `feat(push): PushCampaign tracker for cross-run progress`
3. `feat(push): PushWorker uses campaign-cumulative progress + self-enqueue`

**Out of scope**
- DataStore (use SharedPreferences — lighter, 2 ints only).
- Settings UI.
- Changing TCP session chunk size.
- Changing terminal notification wording (A/B/C/D outcomes unchanged).

---

### Task 3.3 (OBSOLETE) — `SettingsStore` with DataStore (TDD)
**DROPPED 2026-05-15 (Option B).** Kept for historical reference only — do not implement.

**Files**
- Create: `data/preferences/Settings.kt`, `data/preferences/SettingsStore.kt`
- Create test: `app/src/test/java/com/klk/hams/data/preferences/SettingsStoreTest.kt`
- Modify: `gradle/libs.versions.toml` / `app/build.gradle.kts` — add `androidx.datastore:datastore-preferences:1.1.1`.

**Shape**
```kotlin
data class Settings(
    val batchSize: Int = AppConfig.BATCH_SIZE,
    val maxRetryAttempts: Int = AppConfig.MAX_RETRY_ATTEMPTS,
)

class SettingsStore(context: Context) {
    val settings: StateFlow<Settings>
    suspend fun setBatchSize(value: Int)          // clamped to 5..50 (NF-06)
    suspend fun setMaxRetryAttempts(value: Int)   // clamped to 1..20
}
```

**Steps**

- [ ] Step 1 — Add the DataStore dependency. Build. Commit.
- [ ] Step 2 — Test `setBatchSize_clampsToFive_whenLower()` and `setBatchSize_clampsToFifty_whenHigher()`. FAIL. Implement. PASS.
- [ ] Step 3 — Test `defaultsComeFromAppConfig()`. PASS.
- [ ] Step 4 — Commit: `feat(settings): add DataStore-backed SettingsStore`.

### Task 3.4 — Hook `PushEngine` to `SettingsStore` — **DROPPED 2026-05-15 (Option B).** Kept for reference only.

**Files**
- Modify: `push/PushEngine.kt`

Replace the constructor defaults with optional injection from `SettingsStore.settings.value`. Easiest: `HamsApp` constructs `PushEngine` with a `() -> Settings` provider; `PushEngine` reads it at the start of each `runOnce()`.

**Steps**

- [ ] Step 1 — Change `PushEngine` to read `batchSize` and `maxAttempts` at the start of each cycle via an injected `Settings` provider.
- [ ] Step 2 — Add a push engine test: change settings mid-run? **Do not** — settings take effect "on next push" per the requirements checklist. Simpler test: `runOnce_usesCurrentSettings()`.
- [ ] Step 3 — Commit: `feat(push): read batch size and retries from settings`.

### Task 3.5 — `CacheScreen` (cache viewer)

**Files**
- Create: `ui/cache/CacheViewModel.kt`, `ui/cache/CacheScreen.kt`
- Modify: `data/repository/TaskRepository.kt` — add `observeRecentTasks(limit: Int = 100): Flow<List<Task>>` with `ORDER BY id DESC LIMIT ?`.

Rows show: `task_seq`, `started_at` (local time), `net_count`, `push_status` with a coloured pill (`active`→grey, `pending`→orange, `uploading`→blue, `uploaded`→green, `failed`→red).

No delete, no edit, no force-push button. Read-only viewer.

**Steps**

- [ ] Step 1 — Add repo method + instrumented test that inserts 5 tasks, asserts `observeRecentTasks` emits them in reverse chronological order.
- [ ] Step 2 — Implement `CacheViewModel` and `CacheScreen`.
- [ ] Step 3 — Manual verify: launch, create 3 tasks with different statuses, open Cache screen, confirm rendering.
- [ ] Step 4 — Commit: `feat(ui): add cache viewer screen`.

### Task 3.6 — `SettingsScreen` — **DROPPED 2026-05-15 (Option B).** Kept for reference only.

**Files**
- Create: `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`

Render:
- **Batch size** — `Slider` from 5 to 50, integer steps, label shows current value.
- **Max retry attempts** — `Slider` from 1 to 20.
- **Device ID** — `Text(AppConfig.DEVICE_UNIQUE_ID)` read-only.
- **App version** — `Text(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")` via `AppConfig` (add two more `const val`s).

On slider release, call `settingsStore.setBatchSize(...)`.

**Steps**

- [ ] Step 1 — Add `APP_VERSION_NAME` and `APP_VERSION_CODE` `const val`s to `AppConfig.kt` sourced from `BuildConfig`.
- [ ] Step 2 — Implement `SettingsViewModel` and `SettingsScreen`.
- [ ] Step 3 — Manual verify: change batch size to 20, trigger a push with 40 pending events, confirm notification reaches `20/40` then `40/40`. If the batch size is still 10, re-check Task 3.4 wiring.
- [ ] Step 4 — Commit: `feat(ui): add settings screen for batch size and retry count`.

### Task 3.7 — Navigation (revised)

**Files**
- Modify: `MainActivity.kt`

Use the simplest possible navigation: a `Scaffold` with a `BottomNavigationBar` (**2 tabs: Count / Cache** — Settings tab dropped under Option B). No navigation library, no deep links, no type-safe routing. A `remember { mutableStateOf(Screen.Count) }` is fine.

**Steps**

- [ ] Step 1 — Implement tabs.
- [ ] Step 2 — Verify each screen renders and state survives rotation.
- [ ] Step 3 — Commit: `feat(ui): add bottom nav for count / cache / settings`.

### Task 3.8 — Kiosk mode (CONDITIONAL — only if C11 confirmed)

**Files (if executed)**
- Create: `ui/kiosk/KioskMode.kt` — uses `Activity.startLockTask()` (screen pinning) — no device admin APK needed.

**Steps**

- [ ] Step 1 — **Check `C11` status.** If still pending/default ("No"), **skip this task entirely** and commit nothing. Do not scaffold "just in case."
- [ ] Step 2 — If confirmed "Yes", add a toggle in `SettingsScreen` that calls `startLockTask()` / `stopLockTask()`.
- [ ] Step 3 — Commit: `feat(kiosk): add opt-in screen pinning`.

---

## Karpathy-Style Loop-Verifiable Success Criteria

| # | Check | Command / Observation | Expected |
|---|---|---|---|
| 1 | Unit tests green | `.\gradlew.bat :app:testDebugUnitTest` | 0 failures |
| 2 | Instrumented tests green | `.\gradlew.bat :app:connectedDebugAndroidTest` | 0 failures |
| 3 | Build succeeds | `.\gradlew.bat :app:assembleDebug` | SUCCESS |
| 4 | Only **one** notification id used for push | `grep -rn "NOTIF_ID_PUSH\|\.notify(" app/src/main/java/com/klk/hams/ui/notification` | single constant, single `notify` site |
| 5 | No new Room entity / column | `git diff phase/2-ips-push..HEAD -- app/src/main/java/com/klk/hams/data/model` | empty |
| 6 | `PushEngine.state` untouched | `git diff phase/2-ips-push..HEAD -- app/src/main/java/com/klk/hams/push/PushState.kt` | empty |
| 7 | No new networking | `grep -rn "java.net\|Socket\|OkHttp" app/src/main/java/com/klk/hams` outside `push/` | empty |
| 8 | Manual push 25 events | Airplane → 25 `+` → New Task save → Wi-Fi on | exactly one notification observed, ticks 1–25, auto-dismisses on success |
| 9 | Manual: change batch size in settings | Set to 20, trigger push of 40 events | 2 batches × 20 (notif ticks 20/40 then 40/40) |
| 10 | Kiosk code absent if C11 unconfirmed | `find app/src/main/java/com/klk/hams/ui/kiosk -type f` | empty (directory does not exist) |

---

## Do Not

- **Do not** add new `tasks` or `events` columns. The schema was finalised in Phase 1.
- **Do not** modify `PushState`, `PushEngine`'s public surface, or the IPS protocol logic. Phase 3 only reads.
- **Do not** open any new TCP / HTTP connection. No networking in this phase.
- **Do not** create more than one notification channel for push (one channel = one notification ID = one rolling notification — FR-09's whole point).
- **Do not** surface a "retry now" button, a "delete task" button, or a "force push" button. Cache viewer is **read-only**; push is automatic (FR-07).
- **Do not** add pull-to-refresh, swipe-to-dismiss, or any gesture affordance on the cache list — it's a read-only status view.
- **Do not** introduce a nav library (`androidx.navigation.compose`, Voyager, Compose Destinations). A `when` on a `MutableState<Screen>` is enough.
- **Do not** build kiosk mode unless **C11 is explicitly confirmed** by KC. Check the confirmation list in `docs/HAMS_V2_APP_REQUIREMENTS.md`; default is "No" (C11 row). If in doubt, skip.
- **Do not** implement device-admin APK, owner/profile provisioning, or anything that requires factory reset. Only `startLockTask()` / `stopLockTask()` on a single activity — and only if C11 = Yes.
- **Do not** add analytics, crash reporting, or uptime telemetry.
- **Do not** extend settings beyond batch size and retry count. Device ID is read-only; OC code, worker id, token are **not** in the UI.
- **Do not** expose the Wialon token in any screen, log, or notification. It is never user-visible.
- **Do not** localise strings to multiple languages. Single locale.
- **Do not** modify `CLAUDE.md` / `CONTEXT.md` / files under `docs/`.
- **Do not** introduce Dagger / Hilt / Koin if Phase 1 and 2 did not. A growing number of singletons in `HamsApp` is fine for this app's size.
