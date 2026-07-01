# Phase 2 — Wialon IPS Push Engine and V6 Event Capture

> **Prereq:** Phase 1 complete. Room DB uses the V6 `tasks` and `events` schema from `CLAUDE.md`, offline counting works end to end, and every local event row has `event_code`, `battery_pct`, `work_count`, GPS fields, and `pushed`.
> **Read first, mandatory:** `CLAUDE.md` sections "IPS Message Format (Critical)", "Event Capture Rules", and "Push Engine Rules"; `CONTEXT.md` Section 3 (IPS Protocol Reference V6), Section 6 (Confirmed Technical Decisions), and Section 7 (Known Issues); `docs/HAMS_EVENT_CODE_DICTIONARY.md`.
> **Do not use older V5 instructions.** HAMS V2 now uses Wialon IPS v1.1 full 16-field `#D#` frames with named params. The old `course=1` hack and 10-field short frame are superseded.

---

## Checkpoint — 2026-05-04

**Branch:** `phase/2-ips-push`
**Latest commit:** `2e529f7 feat(push): complete PushEngine batching and retry orchestration`

### Status snapshot

- **Tasks 2.1–2.7** — ✅ COMPLETE and committed.
  - 2.1–2.3 (`2a770e4`): `CoordinateConverter`, V6 16-field `IPSFrameBuilder`, `PushEligibility`.
  - 2.4 (`2bea469`, `b9cab91`): `TaskRepository` push-flow surface and v1.2 event-code policy alignment.
  - 2.5 (`be0093a`, `5d827af`): local health/heartbeat capture + physical-device DB validation.
  - 2.6 (`10b0ba5`): `WialonIPSClient` — TCP login, pre-built `#D#` frame send, typed `WialonError` mapping, session-close on timeout/transport.
  - 2.7A (`efa7595`): `PushEngine` core orchestration + `PushRepository` / `IpsSender` interfaces + terminal `PushState`.
  - 2.7B (`2e529f7`): chunking by `AppConfig.BATCH_SIZE` with fresh sender per chunk, configurable inter-message delay, retry/backoff up to `AppConfig.MAX_RETRY_ATTEMPTS` (`30/60/120/240/300s`, `LoginRejected` fail-fast), pre-flight hook, `WialonIPSClient` declared `: IpsSender`.
- **Task 2.7.5 — GPS streaming + armband UI + DB v2 + daily rollover** — 🟡 IMPLEMENTED 2026-05-05/06, **pending field verification 2026-05-07**. Bundle scope:
  - `LocationStream` replaces Phase 1 lazy-fetch (`LocationProvider` deleted). App-scoped `StateFlow<LocationSnapshot?>`, ref-counted by `"foreground"` + `"task_active"`. **Dynamic priority** (revised 2026-05-06 after BALANCED produced visible flap on Honor `AWCX6R3B15001045`): `HIGH_ACCURACY` while task active, `BALANCED` otherwise. Watchdog poke fires `getCurrentLocation` after 6 s of silence. New `AppConfig` constants: `LOCATION_STREAM_INTERVAL_MS=1000`, `LOCATION_STREAM_FASTEST_MS=500`, `LOCATION_STREAM_STALENESS_MS=10000`, `LOCATION_STREAM_WATCHDOG_CHECK_MS=3000`, `LOCATION_STREAM_WATCHDOG_MS=6000`. Spec: `docs/superpowers/specs/2026-05-05-gps-streaming-design.md` (with revision section).
  - Armband portrait UI rewrite. `MainActivity` locked to `screenOrientation="sensorPortrait"` (system Auto-rotate must be ON to flip 180° for the alternative armband mount). `CountScreen.kt` rewritten: 3 status pills (BATT %, GPS color-only, TASK `#N · MMM d`), fixed 180 dp count card with 88sp mono digits + `softWrap=false / overflow=Visible` (prevents leading-0 clipping), equal-size +/− action row taking the only `weight(1f)`, NEW TASK bar with 5 s hold + linear progress indicator. Field-instrument palette (`Color.kt`), mono+sans typography (`Type.kt`). Spec: `docs/superpowers/specs/2026-05-06-armband-ui-design.md`.
  - DB v2: `tasks.task_date TEXT NOT NULL DEFAULT ''` added via `MIGRATION_1_2`; backfill via `substr(datetime(started_at, '+8 hours'), 1, 10)`. New tasks set `task_date` from MYT day at insert time.
  - Daily rollover: `TaskRepository.rolloverActiveTaskIfStale()` invoked at `CountViewModel.init`. Stale (yesterday-or-older) active tasks are finalized with `save_type="auto_rollover"`, `push_status="pending"` (if `netCount>0`) or `"discarded"` (if `netCount==0`). No audit event row. Today's first `+` lazy-creates a fresh task at `task_seq=1`.
  - Manifest: `FOREGROUND_SERVICE_LOCATION`, `foregroundServiceType="dataSync|location"`.
  - Logcat: `Log.d("HAMS_UI", ...)` instrumentation in `CountViewModel.onPlus/onMinus`, `observeActiveTask`, and rollover. Tail with `adb logcat -s HAMS_UI`.
  - Field verification 2026-05-07 will confirm: (a) rollover fires on day change, (b) GPS green/amber color is unambiguous, (c) count display always shows 4 digits, (d) press cadence has no perceived latency.
- **Task 2.8 — Push trigger via WorkManager + manual button** — 🟢 **IMPLEMENTED 2026-05-14, field-verified on Honor `AWCX6R3B15001045`**. End-to-end push to Wialon confirmed (10 tasks/85 events across May 8/13/14, all `push_status='uploaded'`, all events `pushed=1`). Spec: `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md` (status flipped to Implemented). See the "Implementation log — Task 2.8" section at the end of this file for the full commit chain, findings, and known limitations. Headline design points from the spec:
  - `WorkManager` (`OneTimeWorkRequest` + `NetworkType.UNMETERED`) replaces the earlier `WifiMonitor` / `BroadcastReceiver` / `ConnectivityManager.NetworkCallback` approach. Survives app close, swipe, reboot.
  - **Push only operates on `push_status='pending'` tasks.** Active tasks are insulated. The legacy `repo.autoSaveActiveOnWifi(...)` pre-flight hook is **deprecated** under this spec (PushEngine's `preFlight` becomes a no-op). Lifecycle stays worker-controlled (NEW TASK 5s) or Android-controlled (app swipe → `auto_killed`, day rollover → `auto_rollover`, periodic 1 s tick → `auto_rollover`).
  - **Two flows:** auto (silent, notifications only) and manual (5 s push-button hold + confirm + UI lock + status panel `Pending → Pushing → Completed/Failed` + 30-min hard timeout + Cancel during PendingWifi).
  - **Active-task rollover guarantee** (spec §4.1): `staleTick` runs `rolloverActiveTaskIfStale()` every 1 s so an app left open across MYT midnight still rolls yesterday's task.
  - Heartbeat cadence: `HEARTBEAT_INTERVAL_MINUTES` 10 → 1.
  - New files: `push/PushController.kt`, `push/PushWorker.kt`, `push/PushRepositoryImpl.kt`, `push/PushNotifier.kt`. Modified: `HamsApp.kt` (push controller singleton + push notification channel), `TaskDao.kt` (`observePendingTaskCount()`), `TaskRepository.kt` (`pendingTasks()` + deprecate `autoSaveActiveOnWifi`), `CountUiState.kt` (push state + pending count fields), `CountViewModel.kt` (observe controller + lock UI in manual mode + add rollover to staleTick), `CountScreen.kt` (push button with 5s hold + progressive border + status overlay sheet + pending badge + dim-when-locked), `AppConfig.kt` (heartbeat 1 min, `PUSH_MANUAL_TIMEOUT_MS`, `PUSH_RETRY_BACKOFF_MS`), `app/build.gradle.kts` + `gradle/libs.versions.toml` (add `androidx.work:work-runtime-ktx`).
  - **Plan amendment 2026-05-08:** added **Task 0b** (battery-optimisation exemption onboarding — manifest permission + `BatteryOnboardingScreen` + Oppo ColorOS guidance) and **Task 10b** (re-enqueue auto-push on `HamsApp.onCreate` if `pendingCount > 0`, closing the post-force-stop gap). Tasks 8 / 13 / 16 amended to encode the auto + manual cooperation contract: auto uses `ExistingWorkPolicy.KEEP`, manual skips `REPLACE` when state is `Pushing`, Cancel is UI-only and never calls `WorkManager.cancelUniqueWork`, `manualPushActive` flag clears on terminal `PushUiState`, 30-min budget is a UI-side coroutine timer that does not stop the worker. Spec §17 + §18 are binding.
- **Task 2.9** — ⏭ NOT STARTED. In-app progress bar (`CountScreen`).
- **Live Wialon push** — ✅ **CONFIRMED 2026-05-14.** Auto-push fires when validated Wi-Fi appears, manual push (3 s hold) works for operator-triggered uploads, and per-task progress notifications light up correctly (silent during, alerting at terminal). 85 events received on the Wialon test unit.

### Verification (latest)

- `.\gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL (full suite incl. 19 `PushEngineTest` cases and 18 `WialonIPSClientTest` cases).
- `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL.
- `.\gradlew.bat :app:lintDebug` → BUILD SUCCESSFUL.
- `git diff --check` → clean (CRLF warnings only).

### Test gap (acknowledged)

No JVM unit tests exist for `TaskRepository` itself — it requires Room (Android-only). Behaviour preservation relies on the independently tested `PushEligibility`, `IPSFrameBuilder`, `WialonIPSClient`, and `PushEngine`. `PushEngine` runs end-to-end in tests against fakes (`PushRepository` / `IpsSender`); the `TaskRepository → PushRepository` adapter is a thin pass-through and lands with Task 2.8 wiring.

### Boundary — do NOT start in this checkpoint

- Wi-Fi monitor / `ConnectivityManager.NetworkCallback`
- Wiring `PushEngine` into `HamsApp` / `HamsForegroundService`
- Any live Wialon push, frame transmission, or REST integration test

### Manual emulator DB validation — 2026-04-30

Tester ran the v1.2 build on the emulator, exercised plus/minus, and inspected
the SQLite events table directly. Result: **PASS.**

- Latest plus rows insert with `event_code = 179` and `pushed = 0`.
- Latest productive minus rows insert with `event_code = 180` and `pushed = 0`.
- Self-cancelling minus rows (`work_count = 0` after decrement) insert with
  `event_code = 180` and `pushed = 1`; the EventDao allow-list excludes them.
- `EventDao.getPending` returned only `179` and productive `180` rows.
- No `279`, `280`, `281`, `283` rows appeared in the pending query.
- Pre-existing rows from the pre-v1.2 DB state still carry `event_code = 279`
  / `280` (historical only, not produced by the current build). The current
  EventDao allow-list correctly skips them; they will be drained naturally as
  rows age out, or can be marked `pushed = 2` if a manual cleanup is wanted.

This validates the v1.2 alignment for Phase 1 boundary, Tasks 2.2, 2.3, and 2.4
end-to-end at the device DB layer. TCP / Wi-Fi monitor / PushEngine remain
out-of-scope for this checkpoint.

### Event-code policy addendum (2026-04-30)

After Task 2.4 was paused, `docs/HAMS_EVENT_CODE_DICTIONARY.md` was promoted to
v1.2. The policy changed: **only 179, 180, and 35 are approved outbound Wialon
`event_code` values.** HAMS-custom values 279/280/281/283/284/291/292/293 are
local/internal unless a future Wialon admin task gives them server-side meaning.

Implications for the remaining Phase 2 tasks:

- **Task 2.4** — current uncommitted repository work was based on the old
  "many pushable app event codes" model. Do **not** commit it as-is. Redesign
  pending-push eligibility to include only 179, productive 180, and 35.
- **Tasks 2.5–2.9** — do not add push flows for 283/284/291/292/293 as custom
  Wialon `event_code` values. Keep lifecycle/health signals local unless KC
  explicitly requests Wialon rules/reports for them.
- **Development testing** — preferred strategy is isolated test units/resources
  using 179/180. The old 279/280 dev-code push strategy is suspended unless
  explicitly re-approved with matching Wialon test reporting.

---

## Objective

When the device connects to Wi-Fi, the app:

1. Saves any active task with `net_count > 0` before push using local task/save
   state; do not create a custom outbound 284 event.
2. Reads pending V6 rows that are approved for Wialon push (`event_code IN
   (179, 180, 35) AND pushed = 0`, with 180 requiring `work_count > 0`).
3. Sends one Wialon IPS v1.1 full `#D#` data frame per pushable event to `AppConfig.IPS_HOST:AppConfig.IPS_PORT`.
4. Carries the V6 params block on every pushed message: `ffb_cut`, `battery`, approved `event_code`, `work_count`.
5. Marks accepted events as `pushed = 1` on `#AD#1`.
6. Marks permanently rejected events as `pushed = 2` on structural Wialon errors (`#AD#-1` / `#AD#15`) and continues where safe.
7. Retries transient transport failures with exponential backoff.

Output of this phase is a buildable APK that can push V6 events to `TEST_HAMS_APP_001` without touching Wialon REST from inside the app.

---

## V6 Protocol Contract

### Login frame

Every TCP session starts with:

```text
#L#<DEVICE_UNIQUE_ID>;NA\r\n
```

Expected response: `#AL#1`.

### Data frame

Every pushed event sends the full 16-field V6 form:

```text
#D#DDMMYY;HHMMSS;DDMM.MMMM;N;DDDMM.MMMM;E;speed;course;alt;sats;hdop;inputs;outputs;adc;ibutton;params\r\n
```

HAMS fixed/default values:

| Field | Value |
|---|---|
| speed | `0` |
| course | `0` (real/no heading, not the old V5 signal) |
| altitude | `10` |
| inputs | `0` |
| outputs | `0` |
| adc | empty field |
| ibutton | `NA` |

Params block order for byte-exact tests:

```text
ffb_cut:1:<0-or-1>,battery:2:<pct-2dp>,event_code:1:<code>,work_count:1:<count>
```

Canonical frame from `CLAUDE.md` / `CONTEXT.md`:

```text
#D#230426;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;ffb_cut:1:1,battery:2:91.00,event_code:1:179,work_count:1:1\r\n
```

---

## Event Push Eligibility

`IPSFrameBuilder` derives `ffb_cut` from approved outbound `event_code` values:
`179` maps to `1`; `180` and `35` map to `0`. `PushEligibility` owns the
push/no-push policy table and must not treat HAMS-local lifecycle/health codes
as Wialon-pushable.

| Event | Code(s) | `ffb_cut` | Push policy |
|---|---:|---:|---|
| Plus press | 179 | 1 | Always push |
| Minus press | 180 | 0 | Push only if `work_count > 0` after decrement |
| Periodic beacon | 35 | 0 | Always push |
| New task | 281 local/internal | n/a | Never push; insert with `pushed = 1` or other local-only marker |
| Auto-save on kill | local task/save state | n/a | Do not push as custom event_code |
| Auto-save pre-push | local task/save state | n/a | Do not push as custom event_code |
| Battery warning/critical | local telemetry | n/a | Do not push as custom event_code; use `battery` param on 179/180/35 |
| GPS degraded | local telemetry | n/a | Do not push unless Wialon reporting is explicitly designed |

Rows with missing `lat_decimal` or `lon_decimal` must not be converted to fake `0.0,0.0` coordinates. The frame builder should return a typed error, and the push engine should leave the event pending or mark it failed with a diagnostic according to the final repository API. Do not silently push invalid coordinates.

---

## File Structure

Create under `app/src/main/java/com/klk/hams/push/`:

```text
CoordinateConverter.kt   decimal degrees -> DDMM.MMMM
IPSFrameBuilder.kt       login frame + V6 16-field data frame
WialonIPSClient.kt       TCP socket session: connect, login, send, close
PushEngine.kt            batch orchestration, retry, pushed flag updates
PushState.kt             Idle / Running / Success / Partial / Failed
WifiMonitor.kt           Wi-Fi connectivity observer
```

Modify:

```text
data/repository/TaskRepository.kt
service/HamsForegroundService.kt
ui/count/CountScreen.kt
HamsApp.kt
```

Tests under `app/src/test/java/com/klk/hams/push/`:

```text
CoordinateConverterTest.kt
IPSFrameBuilderTest.kt
PushEligibilityTest.kt
PushEngineTest.kt
BatteryEdgeDetectionTest.kt
```

---

## Task Breakdown

### Task 2.1 — Coordinate conversion (TDD)

**Files**
- Create: `push/CoordinateConverter.kt`
- Create: `push/CoordinateConverterTest.kt`

**Tests**

```kotlin
assertEquals("0216.1233", CoordinateConverter.decimalToDDMM(2.268721, 2))
assertEquals("10316.9791", CoordinateConverter.decimalToDDMM(103.282985, 3))
assertEquals("0000.0000", CoordinateConverter.decimalToDDMM(0.0, 2))
assertEquals("0300.0000", CoordinateConverter.decimalToDDMM(3.0, 2))
assertEquals("01000.0000", CoordinateConverter.decimalToDDMM(10.0, 3))
```

Use `Locale.US` formatting so decimal separators never follow device locale.

### Task 2.2 — V6 `IPSFrameBuilder` (TDD)

**Files**
- Create: `push/IPSFrameBuilder.kt`
- Create: `push/IPSFrameBuilderTest.kt`

Public surface:

```kotlin
object IPSFrameBuilder {
    fun loginFrame(uniqueId: String): String
    fun dataFrame(event: EventEntity): Result<String>
}
```

Tests:

- `loginFrame("HAMS_TEST_001") == "#L#HAMS_TEST_001;NA\r\n"`.
- The canonical event row produces the exact frame shown in this plan.
- Plus event maps to `ffb_cut:1:1`.
- Non-plus events map to `ffb_cut:1:0`.
- Missing latitude/longitude returns failure and does not format `0.0`.
- Battery uses two decimals: `91.0` -> `91.00`.
- Course field remains `0`.

### Task 2.3 — Push eligibility helper (TDD)

**Files**
- Create: `push/PushEligibility.kt` or keep as internal helpers in `PushEngine.kt`
- Create: `push/PushEligibilityTest.kt`

Rules:

- Code 281 always local-only (`pushed = 1` on insert or equivalent local marker).
- Code 179 always pushes.
- Code 180 pushes only when `work_count > 0`.
- Code 35 always pushes.
- Codes 279/280/283/284/291/292/293 do **not** push unless explicitly re-approved with Wialon-side reporting config.
- `ffb_cut` is not a SQLite column; tests should prove it is derived correctly in `IPSFrameBuilder`.

### Task 2.4 — Repository additions

**Files**
- Modify: `data/repository/TaskRepository.kt`
- Modify DAOs as needed

Add:

```kotlin
suspend fun pendingPushableEvents(limit: Int = Int.MAX_VALUE): List<EventEntity>
suspend fun markEventUploaded(eventId: Long)
suspend fun markEventRejected(eventId: Long, reason: String)
suspend fun markTaskUploadedIfAllPushableEventsUploaded(taskId: Long)
suspend fun autoSaveActiveOnWifi(batteryPct: Double, location: LocationSnapshot?): Unit // or local save result TBD
```

`pendingPushableEvents` must include only outbound-approved Wialon rows: 179, productive 180, and 35. It must exclude new-task rows, self-cancelling minus rows, and all HAMS-local lifecycle/health rows. It should return events ordered by `timestamp`, then `id`.

The exact return type of `autoSaveActiveOnWifi` must be revisited during the
Task 2.4 redesign. It should not imply that an outbound 284 event was created.

### Task 2.5 — Battery/GPS/heartbeat event capture

**Files**
- Create or modify event capture components used by `CountViewModel` / service

Implement:

- Battery threshold edge detection should remain local telemetry for now; do not push 291/292 as Wialon event_code values.
- GPS degraded detection should remain local telemetry for now; do not push 293 unless Wialon reporting is explicitly designed.
- Heartbeat event 35 on `AppConfig.HEARTBEAT_INTERVAL_MINUTES` (default **1** under Task 2.8 spec), with `0` disabling heartbeats. Active-task scoped only.
- Auto-save active task as the first step in `PushEngine` using task/save state; do not push custom event 284.

Each event row must use the same V6 shape and battery snapshot rules as button events.

**Manual physical-device validation — 2026-05-04 — PASSED.**
App run on a real Android phone over ADB. Device DB pulled via `adb exec-out` /
`run-as` (the phone has no `sqlite3` binary). Real-GPS `lat_decimal` /
`lon_decimal` values matched the tester's company location. Latest rows
confirmed the v1.2 `pushed` policy end-to-end:

- `event_code=281` (new_task) — `pushed=1` (local-only marker)
- `event_code=179` (plus) — `pushed=0` (queued for push)
- `event_code=180` productive minus (`work_count > 0`) — `pushed=0`
- `event_code=180` self-cancelling minus (`work_count = 0`) — `pushed=1`
- `event_code=283` (auto_save) — `pushed=1`
- `battery_pct` captured on every row from `BatteryManager`

No TCP client, Wi-Fi monitor, `PushEngine`, or live Wialon push was started
during this validation — only local capture + DB inspection.

### Task 2.6 — `WialonIPSClient`

**Files**
- Create: `push/WialonIPSClient.kt`

Surface:

```kotlin
class WialonIPSClient(
    private val host: String = AppConfig.IPS_HOST,
    private val port: Int = AppConfig.IPS_PORT,
    private val uniqueId: String = AppConfig.DEVICE_UNIQUE_ID,
) {
    suspend fun openAndLogin(): Result<Unit>
    suspend fun sendDataFrame(frame: String): Result<Unit>
    fun close()
}
```

Use `java.net.Socket` on `Dispatchers.IO`, a 5s connect timeout, and a 3s read timeout. Distinguish typed failures:

```kotlin
sealed class WialonError {
    data class Transport(val cause: Throwable) : WialonError()
    data object LoginRejected : WialonError()
    data object FrameRejected : WialonError()
    data object ParamsRejected : WialonError()
    data object Timeout : WialonError()
}
```

### Task 2.7 — `PushEngine` (TDD)

**Files**
- Create: `push/PushEngine.kt`
- Create: `push/PushState.kt`
- Create: `push/PushEngineTest.kt`

Surface:

```kotlin
sealed class PushState {
    data object Idle : PushState()
    data class Running(val pushed: Int, val total: Int) : PushState()
    data class Success(val total: Int) : PushState()
    data class Partial(val pushed: Int, val pending: Int) : PushState()
    data class Failed(val attempts: Int, val pending: Int) : PushState()
}
```

Algorithm:

1. Capture battery + location snapshot.
2. `repo.autoSaveActiveOnWifi(...)` or equivalent local save-state method; it must not create an outbound custom 284 event.
3. Query pending pushable events (179, productive 180, 35 only).
4. Process chunks of `AppConfig.BATCH_SIZE` events.
5. Open a fresh TCP session and login for each chunk.
6. Send each event frame, delaying `AppConfig.BATCH_DELAY_MS` between frames.
7. On `#AD#1`, mark uploaded.
8. On data/params rejection, mark rejected and continue.
9. On transport timeout/failure, close socket and retry with 30/60/120/240/300s backoff up to `AppConfig.MAX_RETRY_ATTEMPTS`.
10. When a task has no remaining pushable pending events, mark it uploaded.

Tests:

- 25 events send in 3 TCP sessions at batch size 10.
- Canonical frame builder is used by the engine.
- A single frame rejection does not stop the rest of the batch.
- Transport failure retries with exponential backoff.
- Login rejection fails fast and does not spin.
- New-task rows never reach the client.
- Self-cancelling minus rows never reach the client.

### Task 2.8 — Push trigger via WorkManager + manual button

> **Superseded** by `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md`. The text below is retained as a historical sketch only. See the spec for the authoritative scope, file list, state machine, and acceptance criteria.

**Original sketch (do not implement from this — implement from the spec):**
- Create: `push/WifiMonitor.kt`
- Modify: `service/HamsForegroundService.kt`
- Modify: `HamsApp.kt`

Use `ConnectivityManager.NetworkCallback` with Wi-Fi transport and validated network capability. On Wi-Fi connected, call `PushEngine.runWithRetry()`. On disconnect, cancel the in-flight push cycle if possible and reset retry state on the next connect.

**Why superseded:** the in-process `NetworkCallback` cannot survive the app being swiped from recents (`onTaskRemoved` fires and the foreground service stops). WorkManager with `setRequiredNetworkType(NetworkType.UNMETERED)` is the Android-native way to defer work until Wi-Fi appears, and it survives app close, swipe, and reboot. Spec 2026-05-08 covers the full WorkManager + manual-button design.

### Task 2.9 — In-app progress bar

**Files**
- Modify: `ui/count/CountScreen.kt`

Render progress only when `pushState is Running`: `"Uploading tasks... X / Y"` plus a `LinearProgressIndicator`. Worker counting must remain available while upload runs.

---

## Loop-Verifiable Success Criteria

| # | Check | Command / Observation | Expected |
|---|---|---|---|
| 1 | Coordinate tests | `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.CoordinateConverterTest"` | all pass |
| 2 | Frame builder tests | `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.IPSFrameBuilderTest"` | canonical frame byte-exact |
| 3 | Eligibility tests | `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushEligibilityTest"` | all pass |
| 4 | Push engine tests | `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushEngineTest"` | all pass |
| 5 | Battery edge tests | `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.BatteryEdgeDetectionTest"` | all pass |
| 6 | Full unit tests | `.\gradlew.bat :app:testDebugUnitTest` | success |
| 7 | APK builds | `.\gradlew.bat :app:assembleDebug` | success |
| 8 | Lint | `.\gradlew.bat :app:lintDebug` | 0 errors |
| 9 | Only push package uses sockets | `rg "java\\.net\\.Socket" app/src/main/java` | only `push/WialonIPSClient.kt` |
| 10 | No V5 short-frame implementation | `rg "course=1|10-field|#SD#|0213\\.5233" app/src/main/java app/src/test/java` | empty |
| 11 | No REST in app | `rg "hst-api\\.wialon\\.eu|ajax\\.html|token/login|messages/load_interval" app/src/main/java` | empty |
| 12 | No forbidden ports | `rg "21416|20963|#L#2\\.0" app/src/main/java` | empty |

---

## Do Not

- **Do not** use the old V5 `course=1` signal. V6 uses `ffb_cut` in the params block and `course=0`.
- **Do not** build 10-field or 14-field `#D#` frames. Only 16-field V6 frames are valid for HAMS V2 app code.
- **Do not** use IPS v2.0 or `#L#2.0`; port 20332 supports the needed V6 behavior through IPS v1.1.
- **Do not** use port 21416 or 20963.
- **Do not** call Wialon REST from the app. REST is for external verification and N8N only.
- **Do not** push `new_task` event 281.
- **Do not** push HAMS-local custom event codes 279/280/283/284/291/292/293 unless Wialon-side reporting config is explicitly approved.
- **Do not** push self-cancelling minus events where `work_count` is 0 after decrement.
- **Do not** fake coordinates with `0.0,0.0`.
- **Do not** hardcode event codes inline. Use `AppConfig` defaults and `docs/HAMS_EVENT_CODE_DICTIONARY.md`.
- **Do not** expose or log `WIALON_TOKEN`. IPS push uses device unique ID login, not the REST token.
- **Do not** change Wialon production resources or production notification rules in this phase.

---

## Implementation log — Task 2.8 (2026-05-08 → 2026-05-14)

23 plan tasks landed. Commits in branch order (most-recent last):

| Group | Commits |
|---|---|
| **Foundation (1–4)** | `55ce061` WorkManager dep · `d4337be` AppConfig (heartbeat 1m, manual timeout, retry backoff) · `a05af8e` TaskDao queries · `e062797` TaskRepository pass-through + `@Deprecated autoSaveActiveOnWifi` |
| **State + plumbing (5–7)** | `20ab8dd` PushUiState · `7c97bf8` PushRepositoryImpl · `47d9dd7` PushNotifier |
| **Engine driver (8–9)** | `5c9ebda` PushController + cooperation contract · `ccf9e5c` PushWorker |
| **Wiring (10–11)** | `957340b` HamsApp lazy + push channel · `c656d6b` auto-enqueue on task finalization |
| **Recovery + onboarding** | `afb991e` Task 10b re-enqueue on app open · `2ee792d` Task 0b generic battery onboarding |
| **UI chain (12–16)** | `d14b96d` CountUiState push fields · `aa1b215` VM observes controller · `e81a64d` PendingBadge · `d4e84ef` PushButton 5s hold · `08ce82b` PushStatusOverlay + dim |
| **Field-feedback UI (18–20)** | `c59476c` hold-to-repeat +/− · `675f326` notification re-grant chip · `5b8076c` GPS hysteresis + foreground HIGH_ACCURACY + location-off handling |
| **Bug fixes** | `1b6dbe2` Android 14+ FGS type · `9b1bb24` WorkManager merged-manifest override · `3ed6faf` POST_NOTIFICATIONS runtime request + tools:replace · `d7bfe19` lint InvalidFragmentVersionForActivityResult suppress · `5b72411` pointerInput gesture-replay (the +→− phantom undo) |
| **Field refinements (21 + follow-ups)** | `c9210a3` 5s → 3s hold · `a4abcbb` status-pill detox + push-button badge + HOLD 3s + per-event progress · `70435df` drop "Next " prefix + center badge digit · `a61e319` task-level progress + silent updates + terminal-with-sound |
| **Critical external fix** | corrected `DEVICE_UNIQUE_ID` in `local.properties` (user) — was the root cause of "no Wialon data" despite the pipeline being wired |

### Verification (final sweep 2026-05-14)

- `.\gradlew.bat clean :app:assembleDebug` → BUILD SUCCESSFUL
- `.\gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL (all suites including `PushUiStateTest` 4, `PushControllerTest` 9, `PushNotifierTest` 11, `PushEngineTest` 19, `WialonIPSClientTest` 18, `GpsLockHysteresisTest` 12)
- `.\gradlew.bat :app:lintDebug` → BUILD SUCCESSFUL (0 errors)
- DB on test device shows 10 tasks all `uploaded`, 85 events all `pushed=1` (May 8/13/14 data combined)
- Wialon UI confirms received messages for the corrected unit

### Findings worth recording

1. **Compose `pointerInput(state)` recomposition** can replay an in-flight pointer as a new down event on a freshly-keyed gesture detector. We had a + → auto-undo bug because tapping `+` flipped the sibling's `canDecrement` from false→true, re-keying its `pointerInput` mid-gesture. Fix everywhere: key on `Unit` and read state via `rememberUpdatedState` inside the gesture. Applied to `BigActionButton`, `PushButton`, `NewTaskBar`, `NotificationAlertChip`.
2. **Android 14+ FGS rules are strict.** Three separate fixes were needed: (a) `HamsForegroundService.foregroundServiceType` had to drop `location` (the app isn't background-launch-eligible on every cold start), (b) `PushWorker.setForeground` must pass `FOREGROUND_SERVICE_TYPE_DATA_SYNC` explicitly, (c) WorkManager 2.9.x bundles `SystemForegroundService` with no `foregroundServiceType`, so the app must override via `tools:replace` in its own manifest.
3. **Honor MagicOS redacts non-system app logs.** `adb logcat` produced near-empty output even when the app was running normally; dropbox `data_app_crash` entries and `dumpsys jobscheduler` were the only reliable diagnostic sources. Build runs that crashed during early Task 9–11 debug only produced their stack traces in the dropbox dump, not in the live logcat stream.
4. **WorkManager `NetworkType.UNMETERED` requires validated, unmetered Wi-Fi.** Mobile data and captive-portal Wi-Fi don't trigger the worker. We added explicit logging at `HAMS_PUSH onCreate: N pending task(s) found; enqueuing auto-push` so post-force-stop recovery is observable in logcat (when not OEM-redacted).
5. **`local.properties` BuildConfig values get baked at build time.** A wrong `DEVICE_UNIQUE_ID` produced `#AL#0` login rejects on every push; the engine retried five times then `Result.failure`, removed from JobScheduler, leaving "no Wialon data" with no in-app error visible. Always rebuild after editing `local.properties`.
6. **Notification progress should match the user's mental model.** Per-event progress (`5/15 events`) was too granular; switched to per-task (`task 1 of 2`) with silent updates and a single alerting terminal notification at run end. Engine snapshots task counts once on the first non-empty pending query and stays stable across retries.

### Potential bugs / known limitations

1. **WorkManager retries alert per-run.** If a push run ends in B/C (transport halted, will retry) and WorkManager fires a fresh worker invocation 10+ min later, that retry's terminal also rings. Acceptable for now; can be suppressed via a `SharedPreferences` "last terminal already alerted" flag if the user reports it as noisy.
2. **`pushed=2` rejected events block their task forever.** If the server rejects even one event of a multi-event task (frame format, device id), the task stays `push_status='pending'` because retries skip `pushed=2` rows. There is no in-app UI to clear or re-queue these. Operator must investigate via supervisor / DB query.
3. **Force-stop leaves an active task in limbo.** A task being filled at force-stop time keeps `push_status='active'` (no `onTaskRemoved` fires on force-stop). Daily rollover OR the next NEW TASK hold finalises it; until then the app shows the previous count on relaunch. Acceptable, but document.
4. **Battery-onboarding "Skip" cannot be re-nudged.** The one-shot SharedPreferences flag is set on either Allow or Skip. A worker who skips and later loses upload reliability has no in-app affordance to re-trigger the system battery-exemption dialog. Workaround: Settings → Apps → HAMS → Battery → "Unrestricted". Future task could surface a "re-grant" tile if `isIgnoringBatteryOptimizations` returns false.
5. **Notification chip drag offset is in-memory only.** `CountUiState.notificationChipOffsetX/Y` lives in the VM and doesn't persist across process death. Acceptable for an indicator that's only present until the worker grants notification permission.
6. **Per-task progress assumes events arrive in task order.** `EventDao.getPending` orders by `timestamp ASC, id ASC`, so events for a given task are contiguous in practice. If that ordering ever changes (or if heartbeats from a later task get interleaved with plus events from an earlier one), the per-task progress could jitter. Currently safe; flag for future review if the query is reworked.
7. **`HamsForegroundService` no longer declares `location` FGS type.** Background location streaming is therefore unsupported — the location stream only works while a task is active and the foreground service has `dataSync` only. Acceptable for HAMS V2 (worker uses the app foregrounded), but flag if a "log location passively in the background" feature is ever added.
8. **HAMS_V2_APP_REQUIREMENTS.md still says "5-second long press"** for NEW TASK. The implementation is now 3s per field feedback. Requirements doc needs an SV-account update; defer to next doc-review pass.

### Acceptance gate — final (Task 17)

| Gate | Status |
|---|---|
| Clean assembleDebug | ✅ green |
| Full testDebugUnitTest | ✅ green |
| lintDebug | ✅ green (1 false-positive InvalidFragmentVersionForActivityResult suppressed at the call site with comment) |
| Spec status flipped to Implemented | ✅ done |
| Device walk (B–L of the Task 17 manual test plan) | ✅ confirmed by operator 2026-05-13/14 |
| Optional VM flag-clear unit test (Task 13 amendment) | ⏭ Skipped — flag-clear logic is straightforward inside the `uiStateFlow.collect` block; covered by integration testing on device. Documented here and move on. |
