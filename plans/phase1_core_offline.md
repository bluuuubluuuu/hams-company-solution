# Phase 1 — Core Offline Counting App

> **Prereq:** Phase 0 complete (`BuildConfig` wired, permissions declared, `AppConfig` exists, scaffold build green).
> **Read first:** `CLAUDE.md` §§ "What This App Is", "SQLite Schema", "Task Lifecycle"; `docs/HAMS_V2_APP_REQUIREMENTS.md` FR-01…FR-06, FR-10, NF-01, NF-02.
> **Read also, if touching coord/IPS/Wialon code:** `CONTEXT.md`. Phase 1 does **not** touch any of those — stay out.

---

## Objective

A worker can open the app, press `+` to increment an FFB counter, press `−` to decrement, hold "New Task" for 5 s to finish the current task and start the next one, and **never lose a press** — every event and task is durable in local SQLite (Room), including when the OS kills the process or the worker swipes the app away. App operates fully offline. No networking of any kind in this phase.

Output of this phase is a buildable, installable APK that passes the Phase 1 testing checklist from the requirements doc.

---

## Acceptance Criteria (verbatim from `docs/HAMS_V2_APP_REQUIREMENTS.md`)

### FR-01 Count Display
- Count displays as a large number (minimum 72pt equivalent), centered on screen.
- Count value range: 0 to 9999. If 9999, `+` is disabled and a warning is shown.
- Count persists through app backgrounding and screen rotation.
- On new task creation, display resets to 0; previous task count auto-saved to local cache.

### FR-02 Add Count (+)
- Single tap adds exactly 1.
- Each press generates a local record: timestamp (device clock, UTC), GPS lat/lon, count after increment.
- Haptic (vibration) + visual (brief colour change) feedback.
- Zero perceptible lag even offline. Touch target ≥ 80dp × 80dp.
- **C9 resolved:** GPS is mandatory per press. If location permission or device location services are unavailable on app launch, show a blocking message and close the app. The `+` button must not increment or insert a cut without a valid GPS snapshot.

### FR-03 Subtract Count (−)
- Single tap subtracts exactly 1.
- Count cannot go below 0. If 0, `−` disabled or shows brief warning.
- Same event record (timestamp + GPS).
- Visually distinct from `+` (colour/position) and smaller/less prominent.

### FR-04 Battery Display
- Battery % in header at all times, updates in real time.
- Red below 20%, prominent warning overlay below 10%.

### FR-05 New Task
- 5-second continuous hold required.
- Visual progress indicator during hold (filling ring / progress).
- After 5 s, confirm dialog: "Create new task and save current task? Yes / No".
- Yes → current task saved (`push_status="pending"`), count resets to 0, new task with `task_seq + 1`.
- Release before 5 s → nothing happens.
- Saved task includes: task seq, device id, plus/minus/net count, all events, start & end timestamps.

### FR-06 Full Offline
- App launches and operates with no network. No crash/freeze/degradation.
- SQLite holds ≥ 30 days of data (~50 tasks/day, ~500 presses/day).
- GPS via device hardware (no network-assisted GPS required).
- If GPS permission is denied or location services are off, the app exits after showing a clear message. Offline operation still assumes device GPS is enabled.

### FR-10 Auto-save on App Removal
- Detect via `Service.onTaskRemoved()` on a foreground service (preferred) or `Activity.onDestroy()`.
- If current task has `count > 0` and is unsaved, save with `save_type="auto_killed"` **synchronously** before process dies.
- On next launch, that task appears in cache as `pending`. No data loss from swipe-away, system kill, or cache clear.

### NF-01 Android Compatibility — minSdk 34, target device Android 15 (already satisfied).
### NF-02 Performance
- Button press → display update < 50 ms.
- DB write per event < 100 ms.
- Cold start to ready < 3 s. Memory < 100 MB. No UI jank.
- GPS first fix < 10 s; subsequent < 2 s.

---

## Prerequisites

- Phase 0 tasks complete. `.\gradlew.bat :app:assembleDebug` succeeds. `AppConfig.kt` exists.
- Working tree clean. Branch: `phase/1-core-offline`.

---

## File Structure (what this phase creates)

All under `app/src/main/java/com/klk/hams/`:

```
data/
  model/
    Task.kt                 @Entity (tasks)
    EventEntity.kt          @Entity (events)  — name avoids clash with UI events
    TaskWithEvents.kt       @Relation for reads
  db/
    AppDatabase.kt          RoomDatabase, single instance
    TaskDao.kt              insert/update/query tasks
    EventDao.kt             insert/query events
    Converters.kt           ISO-8601 <-> TEXT
  repository/
    TaskRepository.kt       facade over DAOs; coroutine API
  location/
    LocationProvider.kt     Fused Location Provider wrapper, returns LocationSnapshot?
  time/
    Clock.kt                object for `nowUtcIso()` (so tests can swap)
ui/count/
  CountViewModel.kt         state holder, press handling, long-press timer
  CountScreen.kt            Compose: counter, +, −, battery, new-task button
  CountUiState.kt           sealed / data class
  BatteryMonitor.kt         BroadcastReceiver wrapper exposing StateFlow<Int>
  LocationGate.kt           launch-time permission/provider gate
service/
  HamsForegroundService.kt  holds active task in memory; onTaskRemoved flushes
MainActivity.kt             (modified) hosts CountScreen inside the theme
HamsApp.kt                  (new) Application subclass; initialises Room singleton
```

Tests under `app/src/test/java/com/klk/hams/`:
```
data/repository/TaskRepositoryTest.kt
ui/count/CountViewModelTest.kt
time/ClockTest.kt
```

---

## Task Breakdown

> Each task follows TDD where practical (writing-plans skill): write failing test, run it, implement, run it green, commit. For UI and Android-framework-bound code (Service, BroadcastReceiver, Compose), instrumented/manual test is acceptable instead.

### Task 1.1 — Add dependencies to the version catalogue — **COMPLETE**

**Files**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Steps**

- [x] Step 1 — Add to `[versions]` in `libs.versions.toml`:
  ```toml
  room = "2.6.1"
  ksp = "2.0.21-1.0.27"
  fusedLocation = "21.3.0"
  coroutines = "1.8.1"
  lifecycleViewmodelCompose = "2.9.4"
  ```
- [x] Step 2 — Added all library entries to `[libraries]`.
- [x] Step 3 — Added KSP plugin entry to `[plugins]`.
- [x] Step 4 — Added `alias(libs.plugins.ksp)` and all dependency declarations to `app/build.gradle.kts`.
- [x] Step 5 — `.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`. Committed: `build: add room, fused location, coroutines`.

### Task 1.2 — Room entities + DAOs — **COMPLETE**

**Files**
- Created: `data/model/Task.kt`, `data/model/EventEntity.kt`
- Created: `data/db/TaskDao.kt`, `data/db/EventDao.kt`, `data/db/AppDatabase.kt`

All V6 column names match the CLAUDE.md schema exactly. `TaskWithEvents.kt` deferred — not yet needed. Converters not needed (all timestamps stored as ISO-8601 `String`).

**Steps**

- [x] Steps 1–9 — Entities, DAOs, and AppDatabase implemented. Full V6 schema (event_code, battery_pct, hdop, satellites, work_count, lat_decimal, lon_decimal). TaskDao: `getActiveTask()`, `observeActiveTask(): Flow<Task?>`, `getMaxTaskSeqForDay()`, `updateCounts()`, `finalizeTask()`. EventDao: `insert()`, `getPending()` (`WHERE event_code != 0 AND pushed = 0`), `markPushed()`. AppDatabase version=1, exportSchema=false. Committed: `feat(db): add Room schema for tasks and events`.

### Task 1.3 — `TaskRepository` — **COMPLETE** (lazy creation variant)

**Files**
- Created: `data/repository/TaskRepository.kt`

**Note:** API changed from the plan. `startNewTask()` was removed. Lazy creation means the repository has no explicit "create task" call — the task row is created inside `recordPlus()` when no active task exists. Original Phase 1 implementation used transaction: ① task row insert ② event_code=281 (pushed=1) ③ event_code=279 (pushed=0). This is now superseded by the 2026-04-30 v1.2 event-code policy: 281 remains local-only, but outbound plus should be redesigned to use approved Wialon event_code 179. `saveActiveTask(saveType, location, batteryPct)` replaces `finishActiveTask()` to match the richer signature needed for local auto-save state.

**Steps**

- [x] Steps 1–7 — Implemented. Key behaviour: `recordPlus()` lazy-creates task row on first call; `recordMinus()` returns null if net_count≤0; self-cancelling minus (result=0) gets `pushed=1`; `saveActiveTask()` skips zero-count tasks; `getNextTaskSeqInternal()` uses `ZoneId.of("Asia/Kuala_Lumpur")` day boundary. Committed: `feat(data): add TaskRepository with atomic +/− recording`.

### Task 1.4 — `LocationProvider` — **COMPLETE**

**Files**
- Created: `data/location/LocationProvider.kt`

**Note:** Implemented using `FusedLocationProviderClient` from `play-services-location`. HDOP is approximated as `(accuracy / 5.0).coerceIn(0.5, 99.9)` because Android does not expose direct HDOP. Satellites are read from `extras?.getInt("satellites")` when present. Staleness check: `lastKnown()` valid if ≤5 s old; otherwise `currentSingleFix(2000ms)`.

**SUPERSEDED 2026-05-05:** Phase 2.7.5 replaces this lazy-fetch model with a continuous BALANCED location stream owned app-scope (`LocationStream`), exposing `StateFlow<LocationSnapshot?>`. The press path becomes synchronous and is gated on `GpsLockState.Locked` (snapshot age ≤ 5 s). Driver: field UX showed visible per-press latency under Scenario C cadence on cache misses. Spec: `docs/superpowers/specs/2026-05-05-gps-streaming-design.md`. The HDOP/satellites/snapshot-mapping logic in this Phase 1 implementation is preserved verbatim in the new stream.

- [x] Step 1 — Implemented `LocationProvider` with `getValidLocation()` (combines lastKnown + fallback).
- [x] Step 2 — Smoke test deferred to manual device test.
- [x] Step 3 — Committed: `feat(location): add LocationProvider wrapper`.

### Task 1.5 — `CountViewModel` — **COMPLETE**

**Files**
- Created: `ui/count/CountViewModel.kt`, `ui/count/CountUiState.kt`, `ui/count/BatteryMonitor.kt`

**Note:** `BatteryMonitor` (Task 1.6 in the plan) was implemented alongside the ViewModel as both are wired in `init {}`. `CountUiState` is a plain `data class` with computed properties: `canIncrement` (count<9999 && gpsAvailable), `canDecrement` (count>0), `taskLabel` ("Task #N" or "Next Task #N"). Long-press timer: 100 ticks × 50ms coroutine loop; `newTaskProgress` exposed as `Float` (0f..1f).

- [x] Steps 1–5 — Implemented. GPS gate: `setGpsAvailable(true/false)` called from screen; + press blocked when `!gpsAvailable`. `CountViewModel.Factory` constructs via `HamsApp.repository`. Committed: `feat(ui): add CountViewModel with +/−, cap, and new-task flow`.

### Task 1.6 — `CountScreen` (Compose) — **COMPLETE** (manual emulator verification passed)

**Files**
- Created: `ui/count/CountScreen.kt` (BatteryMonitor was implemented in Task 1.5)
- Modified: `MainActivity.kt` — replaced Greeting stub with CountScreen

Long-press uses `Modifier.pointerInput` + `detectTapGestures(onPress = { ... tryAwaitRelease() })` on a `Surface`-based hold target. GPS gate state machine in CountScreen runs permission check + `LocationManager.isProviderEnabled` check.

- [x] Step 1 — `BatteryMonitor` implemented (see Task 1.5).
- [x] Step 2 — `CountScreen` implemented with permission state machine.
- [x] Step 3 — `MainActivity.kt` updated.
- [x] Step 4 — Manual emulator verification passed: permission grant keeps app open; + waits/rejects without GPS fix; fake GPS lets + create Task #1; plus/minus and New Task flows work.
- [x] Step 5 — Commit pending WYH/CC final commit decision.

#### GPS gate close bug — RESOLVED and manually verified (2026-04-28)

**Symptom:** On Pixel 5 emulator API 36, app exits immediately after granting fine location permission. No FATAL EXCEPTION; logcat shows `visibilityChanged oldVisibility=true newVisibility=false`.

**Root cause:** `locationServicesOk` initialises as `false`. The `when` block's `!locationServicesOk` branch renders `BlockingMessage` before `LaunchedEffect(permissionGranted)` completes its provider check. `BlockingMessage` calls `onExitRequired()` → `finishAffinity()` synchronously via its own `LaunchedEffect(Unit)`.

**Fix applied:** Replaced the three boolean state variables with a sealed interface state machine:
```kotlin
sealed interface GateState {
    object CheckingPermission : GateState
    object RequestingPermission : GateState
    object PermissionDenied : GateState
    object CheckingLocationServices : GateState
    object LocationServicesOff : GateState
    object Ready : GateState
}
```
Only `LocationServicesOff` and `PermissionDenied` call `onExitRequired()`. `CheckingPermission` and `CheckingLocationServices` show a spinner — they never call `onExitRequired()` prematurely.

**Status:** Code fix applied in `CountScreen.kt` and manually verified on Pixel 5 API 36 emulator. App no longer exits after permission grant.

#### GPS freshness / emulator mock location — RESOLVED for Phase 1 testing (2026-04-28)

**Symptom:** `adb emu geo fix` appeared to succeed but the app still showed "Waiting for GPS fix..." after pressing `+`.

**Root cause:** The emulator location stack was not receiving a fresh usable Fused/GPS fix from `adb emu geo fix`, and the app originally checked location freshness against wall-clock `Location.time`. Mock/emulator locations can have stale or inconsistent wall-clock values.

**Fix applied:** `LocationSnapshot.capturedAtMs` and `LocationProvider.getValidLocation()` now use `SystemClock.elapsedRealtime()` / `Location.elapsedRealtimeNanos`, which is the correct Android freshness source. Manual emulator testing used a shell mock-location provider refreshed every 2 seconds.

**Status:** Verified. First valid `+` creates Task #1 and records the GPS-backed event when the mock provider is refreshed.

#### New Task 5-second hold — RESOLVED and manually verified (2026-04-28)

**Symptom:** Holding `NEW TASK` for 5 seconds did not show progress or open the confirmation dialog, even at count > 0.

**Root cause:** `OutlinedButton` internal gesture handling competed with the custom `pointerInput` long-press handler.

**Fix applied:** Replaced the New Task `OutlinedButton` with a `Surface` styled like an outlined button, with `pointerInput` attached directly to the surface.

**Status:** Verified. 5-second hold shows progress/dialog, Yes saves the counted task, count resets to 0, and the next task label advances.

### Task 1.7 — `HamsApp` Application class + Room singleton — **COMPLETE**

**Files**
- Created: `HamsApp.kt`
- Modified: `AndroidManifest.xml` — added `android:name=".HamsApp"` to `<application>`

**Note:** Plan step 3 said "verify a task row is created on app open." This is **no longer correct** — lazy creation means no task row is created on open. Verification is instead: "first + press creates the task row (verify via adb after pressing +)."

- [x] Steps 1–2 — `HamsApp` implemented; manifest wired. `HamsApp.onCreate()` also creates the notification channel.
- [x] Step 3 — Note updated: task row created on first valid + press, not on app open.
- [x] Step 4 — Committed: `feat(app): add application class with Room singleton`.

### Task 1.8 — `HamsForegroundService` for `onTaskRemoved()` (FR-10) — **COMPLETE**

**Files**
- Created: `service/HamsForegroundService.kt`
- Modified: `AndroidManifest.xml` — declared service with `android:foregroundServiceType="dataSync"`

`onTaskRemoved()` reads battery via `BatteryManager`, best-effort location via `LocationManager` (nullable), then calls `runBlocking { repo.saveActiveTask("auto_killed", location, battery) }`. Zero-count tasks are silently skipped. `stopSelf()` called after write.

Service is started from `MainActivity` via `onGatePassed` callback (called when GPS gate passes in `CountScreen`), not directly from `onCreate`.

- [x] Step 1 — Notification channel created in `HamsApp.onCreate`.
- [x] Step 2 — Service implemented; started after GPS gate passes (via `onGatePassed` in `MainActivity`).
- [x] Step 3 — Manual emulator test passed after GPS gate/freshness fixes; swipe/kill auto-save behavior verified by WYH.
- [x] Step 4 — Committed: `feat(service): add foreground service and onTaskRemoved auto-save`.

### Task 1.9 — Runtime permission request for fine location — **COMPLETE**

**Files**
- Implemented inside `ui/count/CountScreen.kt`

Permission launcher (`rememberLauncherForActivityResult`) requests `ACCESS_FINE_LOCATION` on first composition. `LocationManager.isProviderEnabled` checked after grant. Both denial and location-off paths call `onExitRequired()`.

- [x] Step 1 — Permission launcher implemented.
- [x] Step 2 — Provider check implemented.
- [x] Step 3 — Verified on emulator: grant + location on -> app usable; deny -> message then app closes; location off path retained per gate logic.
- [x] Step 4 — Commit pending WYH/CC final commit decision.

---

## Phase 1 Status: **COMPLETE** (manual emulator verification passed 2026-04-28)

**What works:**
- All source files created and compile cleanly (`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`)
- Full V6 Room schema (tasks + events, correct column names)
- Lazy task creation in single Room transaction (task row + 281 marker + outbound plus event atomically on first + press; outbound plus value is 179 under v1.2 — was 279 in the pre-v1.2 build)
- task_seq per MYT day (ZoneId Asia/Kuala_Lumpur)
- CountViewModel: +/−, GPS gate, battery live, 5s long-press timer, new task dialog, count cap at 9999
- HamsForegroundService: dataSync type, onTaskRemoved synchronous auto-save
- HamsApp: lazy Room singleton, notification channel

**Manual verification passed:**
- Permission denied -> blocking message then app closes
- Permission granted + location enabled -> app stays open
- No/freshness-invalid GPS -> `+` rejected, count unchanged, "Waiting for GPS fix..." shown
- Mock GPS refreshed -> first `+` creates active Task #1 and count increments
- `+` / `-` counting works and count never goes below 0
- New Task at count 0 -> no durable row, no save; "No count to save." behavior retained
- New Task 5-second hold at count > 0 -> confirm dialog -> Yes saves current task and resets count to 0
- Next `+` creates the next task number for the same MYT day
- Swipe/kill auto-save path verified by WYH manual test

**Next action:** CC can resume tomorrow from this checkpoint. Recommended first action is a quick review of the dirty worktree, then start Phase 2 planning/implementation after WYH confirms the final Phase 1 commit boundary.

---

## Karpathy-Style Loop-Verifiable Success Criteria

| # | Check | Command | Expected |
|---|---|---|---|
| 1 | Unit tests pass | `.\gradlew.bat :app:testDebugUnitTest` | `BUILD SUCCESSFUL`, 0 failures |
| 2 | Instrumented DB tests pass | `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.klk.hams.data.*"` | 0 failures |
| 3 | APK builds | `.\gradlew.bat :app:assembleDebug` | `BUILD SUCCESSFUL` |
| 4 | Lint clean | `.\gradlew.bat :app:lintDebug` | 0 errors |
| 5 | Cold start | `adb shell am force-stop com.klk.hams.debug && adb shell am start -n com.klk.hams.debug/com.klk.hams.MainActivity` with logcat `TAG=ActivityTaskManager` showing "Displayed" time | < 3000 ms |
| 6 | Press latency | Logcat instrumented in `CountViewModel.onPlus` records `System.nanoTime()` delta from call to emit | < 50 ms median over 20 presses |
| 7 | Airplane-mode workflow | Airplane mode on, press `+` 100 times in the UI, verify counter reads 100 | counter = 100 |
| 8 | Auto-save on kill | Swipe app from recents; `adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "select save_type,net_count from tasks order by id desc limit 1;"` | `auto_killed|100` |
| 9 | Location permission denied exits | Deny permission on first launch | blocking message shown, app closes |
| 10 | Location services off exits | Disable location services, launch app | blocking message shown, app closes |
| 11 | No event without GPS | Disable mock/real fix and tap `+` | count unchanged, no event inserted |
| 12 | No networking code reachable | `grep -rn "Socket\|OkHttp\|HttpURLConnection\|InetAddress" app/src/main/java` | empty |
| 13 | No IPS strings reachable | `grep -rn "#L#\|#D#\|#AL#\|#AD#\|185.213.1.24\|20332" app/src/main/java` | empty |

If checks 9 or 10 fail, Phase 1 has leaked Phase 2 scope — revert the offending file.

---

## Do Not

- **Do not** import `java.net.*`, `okhttp3.*`, `retrofit2.*`, `com.google.gson.*`, `kotlinx.serialization.*`, or any networking / JSON lib. Phase 1 is offline-only.
- **Do not** implement Wi-Fi detection, `ConnectivityManager.NetworkCallback`, or any `BroadcastReceiver` for connectivity. That's Phase 2.
- **Do not** implement coordinate conversion (`decimalToDDMM`). That's Phase 2. Store raw decimal-degree doubles in Room, nothing more.
- **Do not** push, send, transmit, or serialise anything to anywhere. The word `Wialon` should appear nowhere in Phase 1 source files.
- **Do not** build a cache viewer UI, settings screen, push notification, or progress bar. Phase 3.
- **Do not** deviate from the `tasks` and `events` column names in `CLAUDE.md` § "SQLite Schema". Phase 2 reads against that exact schema.
- **Do not** add any IPS push logic in Phase 1. Minus rows are recorded locally with V6 metadata; Phase 2 applies the V6 push policy (`work_count > 0` after decrement).
- **Do not** add Hilt / Dagger. A single `HamsApp.repository` singleton is enough. Wire VMs with a small `ViewModelProvider.Factory`. YAGNI.
- **Do not** add multi-module structure, feature modules, or a `:core` module.
- **Do not** add analytics, Crashlytics, Firebase, or any telemetry.
- **Do not** add kiosk mode, device-admin APIs, or lock-task mode. Phase 3, and only if C11 is confirmed.
- **Do not** add internationalisation / multiple locales — single-locale app.
- **Do not** touch files under `docs/` or `CLAUDE.md` / `CONTEXT.md`.
- **Do not** invent columns or tables beyond what `CLAUDE.md` specifies. If you think a column is missing, **stop and ask** — probably it belongs in Phase 2 or Phase 3.
- **Do not** pre-emptively implement retry / backoff / push_attempts logic. Those fields get populated in Phase 2; in Phase 1 leave them at their schema defaults.
