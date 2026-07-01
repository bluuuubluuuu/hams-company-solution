# Push Integration — Validation Checklist

> **Purpose:** ground-truth reference for Task 2.8 implementation and post-impl debugging.
> Created 2026-05-08 from a pre-implementation audit of the existing code base. Update if any of these values drift.

---

## 1. Build-time configuration (BuildConfig)

These values are written into `BuildConfig` from `local.properties` at build time. **Never read `local.properties` at runtime.**

| Constant | Source | Default in `app/build.gradle.kts` | Used by |
|---|---|---|---|
| `WIALON_TOKEN` | `local.properties → buildConfigField` | (empty — must be set in `local.properties`) | Reserved for future REST verification; not used by IPS push |
| `IPS_HOST` | `local.properties → buildConfigField` | `"185.213.1.24"` | `WialonIPSClient.host` default |
| `IPS_PORT` | `local.properties → buildConfigField` | `20332` | `WialonIPSClient.port` default |
| `DEVICE_UNIQUE_ID` | `local.properties → buildConfigField` | `"HAMS_TEST_001"` | `WialonIPSClient.uniqueId` default; appears in `#L#<id>;NA\r\n` login frame |

**Verified present in this machine's `local.properties`:** `WIALON_TOKEN`, `IPS_HOST`, `IPS_PORT`, `DEVICE_UNIQUE_ID` all set.

**Debug command:** confirm these values are baked into APK after a build:
```bash
javap -p -classpath app/build/intermediates/javac/debug/classes/com/klk/hams/BuildConfig.class
```

---

## 2. AppConfig runtime constants (verified 2026-05-08, pre-Task-2.8)

| Constant | Value | Used by | Will Task 2.8 change? |
|---|---|---|---|
| `BATCH_SIZE` | `10` | `PushEngine.chunkSize` default | no |
| `BATCH_DELAY_MS` | `75` | `PushEngine.interMessageDelayMs` default | no |
| `MAX_RETRY_ATTEMPTS` | `5` | `PushEngine.maxAttempts` default | no |
| `EVENT_CODE_PLUS` | `179` | outbound + frames | no |
| `EVENT_CODE_MINUS` | `180` | outbound − frames (when productive) | no |
| `EVENT_CODE_HEARTBEAT` | `35` | outbound 35 frames | no |
| `EVENT_CODE_NEW_TASK` | `281` | local-only marker | no |
| `EVENT_CODE_AUTO_SAVE_KILL` | `283` | local-only | no |
| `EVENT_CODE_AUTO_SAVE_WIFI` | `284` | local-only (deprecated path under 2.8) | no |
| `EVENT_CODE_BATTERY_WARN` | `291` | local-only | no |
| `EVENT_CODE_BATTERY_CRITICAL` | `292` | local-only | no |
| `EVENT_CODE_GPS_DEGRADED` | `293` | local-only | no |
| `HEARTBEAT_INTERVAL_MINUTES` | `10` (current) → **`1` (after Task 2)** | `HeartbeatScheduler` | **YES — Task 2** |
| `LOCATION_STREAM_INTERVAL_MS` | `1000` | `LocationStream` request | no |
| `LOCATION_STREAM_FASTEST_MS` | `500` | `LocationStream` request | no |
| `LOCATION_STREAM_STALENESS_MS` | `10000` | press gate + freshness check | no |
| `LOCATION_STREAM_WATCHDOG_CHECK_MS` | `3000` | watchdog tick cadence | no |
| `LOCATION_STREAM_WATCHDOG_MS` | `6000` | watchdog poke threshold | no |
| **NEW: `PUSH_MANUAL_TIMEOUT_MS`** | `1_800_000L` (30 min) | manual push session budget | **YES — Task 2** |
| **NEW: `PUSH_RETRY_BACKOFF_MS`** | `[10_000, 30_000, 60_000, 120_000]` | reserved for future fine-grained retry; **note: `PushEngine.DEFAULT_BACKOFF_MS` is independently `[30_000, 60_000, 120_000, 240_000, 300_000]` and Task 2.8 does NOT replace it.** | **YES — Task 2** |

---

## 3. Existing engine surface — DO NOT change in Task 2.8

### `PushEngine` constructor (verified file `app/src/main/java/com/klk/hams/push/PushEngine.kt:73-84`)

```kotlin
class PushEngine(
    private val repo: PushRepository,
    private val senderFactory: () -> IpsSender,                                    // ← named senderFactory, NOT ipsSenderFactory
    private val frameBuilder: (EventEntity) -> Result<String> = IPSFrameBuilder::dataFrame,
    private val batchLimit: Int = Int.MAX_VALUE,
    private val chunkSize: Int = AppConfig.BATCH_SIZE,                             // ← named chunkSize, NOT batchSize
    private val interMessageDelayMs: Long = AppConfig.BATCH_DELAY_MS,
    private val maxAttempts: Int = AppConfig.MAX_RETRY_ATTEMPTS,
    private val backoffScheduleMs: List<Long> = DEFAULT_BACKOFF_MS,                // ← internal default, unchanged
    private val delayer: suspend (Long) -> Unit = ::defaultDelay,
    private val preFlight: suspend () -> Unit = NO_PRE_FLIGHT,                     // ← Task 2.8 leaves this at default; we don't deprecate the param
)
```

### `WialonIPSClient` constructor (file `WialonIPSClient.kt:29-36`)

```kotlin
class WialonIPSClient(
    private val host: String = AppConfig.IPS_HOST,
    private val port: Int = AppConfig.IPS_PORT,
    private val uniqueId: String = AppConfig.DEVICE_UNIQUE_ID,                     // ← named uniqueId, NOT deviceId / deviceUniqueId
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val socketFactory: () -> Socket = ::Socket,
) : IpsSender
```

### `PushRepository` interface (file `PushEngine.kt:12-24`)

```kotlin
interface PushRepository {
    suspend fun pendingPushableEvents(limit: Int = Int.MAX_VALUE): List<EventEntity>
    suspend fun markEventUploaded(eventId: Long)
    suspend fun markEventRejected(eventId: Long, reason: String)
    suspend fun markTaskUploadedIfAllPushableEventsUploaded(taskId: Long)
}
```

`PushRepositoryImpl` (Task 6) implements exactly these four methods, no more.

### `IpsSender` interface (file `PushEngine.kt:32-36`)

```kotlin
interface IpsSender {
    suspend fun openAndLogin(): Result<Unit>
    suspend fun sendDataFrame(frame: String): Result<Unit>
    fun close()
}
```

`WialonIPSClient` already implements this. Task 2.8 does NOT modify the client.

### `EventDao.getPending` allow-list (file `EventDao.kt:23-30`)

```sql
SELECT * FROM events
WHERE pushed = 0
AND event_code IN (179, 180, 35)
AND NOT (event_code = 180 AND work_count <= 0)
ORDER BY timestamp ASC, id ASC LIMIT :limit
```

Outbound policy is enforced here. Task 2.8 does NOT touch this.

---

## 4. Frame format ground truth

Every pushed event becomes one V6 16-field `#D#` frame. Canonical example from `CLAUDE.md`:

```
#D#230426;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;ffb_cut:1:1,battery:2:91.00,event_code:1:179,work_count:1:1\r\n
```

Decoded:

| Position | Field | Example | Source |
|---|---|---|---|
| 1 | Date `DDMMYY` | `230426` | event UTC timestamp |
| 2 | Time `HHMMSS` | `011706` | event UTC timestamp |
| 3 | Latitude `DDMM.MMMM` | `0216.1233` | `CoordinateConverter.decimalToDDMM(lat, 2)` |
| 4 | Lat hemisphere | `N` | sign of latitude |
| 5 | Longitude `DDDMM.MMMM` | `10316.9791` | `CoordinateConverter.decimalToDDMM(lon, 3)` |
| 6 | Lon hemisphere | `E` | sign of longitude |
| 7 | Speed (km/h) | `0` | always 0 (not reported) |
| 8 | Course | `0` | **always 0** (V6 — was `1` in V5 hack) |
| 9 | Altitude (m) | `10` | hard-coded |
| 10 | Satellites | `8` | from `LocationSnapshot.satellites`, default 0 |
| 11 | HDOP | `1.5` | from `LocationSnapshot.hdop`, default 0 |
| 12 | Inputs | `0` | always 0 |
| 13 | Outputs | `0` | always 0 |
| 14 | ADC | empty | `;;` |
| 15 | ibutton | `NA` | always `NA` |
| 16 | Params block | `ffb_cut:1:1,battery:2:91.00,event_code:1:179,work_count:1:1` | derived from event_code + battery + count |

**Trailer:** `\r\n`

**Login frame (sent once per TCP session):**
```
#L#<DEVICE_UNIQUE_ID>;NA\r\n
```
Expected response: `#AL#1\r\n` (success). Anything else → fail-fast.

**Data frame ack:**
- `#AD#1\r\n` → success → mark `pushed=1`
- `#AD#-1` / `#AD#0` / `#AD#10..15` → permanent reject → mark `pushed=2`

---

## 5. Plan correction notes

The plan `docs/superpowers/plans/2026-05-08-push-trigger-impl.md` (Task 9 — PushWorker) used incorrect `PushEngine` constructor parameter names. The implementer subagent for Task 9 must use the correct signature documented in §3 above:

```kotlin
val engine = PushEngine(
    repo = PushRepositoryImpl(repo),
    senderFactory = { WialonIPSClient() },        // not ipsSenderFactory
    chunkSize = AppConfig.BATCH_SIZE,             // not batchSize
    interMessageDelayMs = AppConfig.BATCH_DELAY_MS,
    maxAttempts = AppConfig.MAX_RETRY_ATTEMPTS,
)
```

`WialonIPSClient()` with no args picks up `AppConfig.IPS_HOST` / `IPS_PORT` / `DEVICE_UNIQUE_ID` defaults — no need to pass them explicitly. The plan will be amended in this commit.

---

## 6. Logging contract

Task 2.8 introduces logs under three tags. Use `adb logcat -s HAMS_UI HAMS_PUSH WM-WorkerWrapper` to follow end-to-end.

| Tag | Source | Logs |
|---|---|---|
| `HAMS_UI` | `CountViewModel`, `TaskRepository` | `onPlus ACCEPTED/REJECTED`, `onMinus ...`, `observeActiveTask emitted ...`, `tick rollover: finalized stale task id=N`, `manual push triggered`, `manual push cancelled by user` |
| `HAMS_PUSH` | `PushWorker`, `PushController`, `PushEngine` | `doWork: <N> pending tasks`, `doWork: engine returned <PushState>`, `doWork: <error> — retrying`, controller state transitions |
| `WM-WorkerWrapper` | Android WorkManager | scheduling, constraint satisfaction, retry, success/failure |

**No live token, GPS, or PII appears in logs.** Battery and event counts only.

---

## 7. Pre-impl debug checklist for the user

When Task 2.8 lands and you observe behaviour, reference this list to triage.

### A. Auto-push didn't fire when Wi-Fi connected

1. Confirm there's at least one task with `push_status='pending'`:
   ```bash
   adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "SELECT id, task_seq, push_status, save_type FROM tasks WHERE push_status='pending';"
   ```
   - 0 rows → no work to do, expected.
   - >0 rows → continue.
2. Confirm WorkManager job is enqueued with `UNMETERED` constraint:
   ```bash
   adb logcat -d -s WM-WorkerWrapper | grep -i "hams-push\|enqueue\|constraint"
   ```
3. Confirm Wi-Fi has `NET_CAPABILITY_VALIDATED` (captive portals fail this):
   ```bash
   adb shell dumpsys connectivity | grep -A2 -i "validated\|wifi"
   ```
4. If still nothing, force-trigger via the manual push button (5 s hold).

### B. Manual push button doesn't respond

1. Hold target reached but no dialog: check `HAMS_UI` for `pushHoldProgress` updates.
2. Dialog appears but Yes does nothing: check for `manual push triggered` log + WorkManager enqueue.
3. UI doesn't dim: confirm `manualPushActive` flips true via `adb logcat -s HAMS_UI`.

### C. Push starts but Wialon never receives messages

1. Confirm engine logged successful login:
   ```bash
   adb logcat -d -s HAMS_PUSH | grep -i "openAndLogin\|login\|#AL"
   ```
   - `#AL#1` → login OK, transport works.
   - `#AL#0` / `#AL#01` → uniqueId or password mismatch — check `BuildConfig.DEVICE_UNIQUE_ID`.
2. Confirm frames sent:
   ```bash
   adb logcat -d -s HAMS_PUSH | grep "sendDataFrame\|#D#"
   ```
3. Confirm acks:
   ```bash
   adb logcat -d -s HAMS_PUSH | grep "#AD#"
   ```
   - `#AD#1` → row marked `pushed=1` in DB.
   - `#AD#-1` / `#AD#15` → frame rejected; row marked `pushed=2`. Inspect frame text in log to debug.

### D. Confirm Wialon-side reception

After a push session that logs `#AD#1`s, query Wialon REST `messages/load_interval` for unit `TEST_HAMS_APP_001`:

```bash
curl -X POST "https://hst-api.wialon.com/wialon/ajax.html" \
     -d 'svc=core/login&params={"token":"<WIALON_TOKEN>"}'
# capture eid, then:
curl -X POST "https://hst-api.wialon.com/wialon/ajax.html" \
     -d 'svc=messages/load_interval&params={"itemId":601602811,"timeFrom":<unix_start>,"timeTo":<unix_end>,"flags":0,"flagsMask":65281,"loadCount":100}&sid=<eid>'
```

Each pushed message should appear with the four params: `ffb_cut`, `battery`, `event_code`, `work_count`.

### E. Day rollover didn't fire

1. Confirm tick runs:
   ```bash
   adb logcat -d -s HAMS_UI | grep "tick rollover"
   ```
2. Confirm task_date in DB matches today:
   ```bash
   adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "SELECT id, task_date, push_status FROM tasks WHERE push_status IN ('active','pending') ORDER BY id DESC LIMIT 5;"
   ```
3. If yesterday's task still says `active`: app was force-killed before any tick fired AND today's launch hasn't happened — open the app fresh.

---

## 8. Acceptance gate before tagging Task 2.8 complete

| Gate | How to verify |
|---|---|
| All 17 plan tasks committed | `git log --oneline phase/2-ips-push \| head -25` shows ~17 new commits with `feat(push):` prefix |
| Build green | `./gradlew.bat :app:assembleDebug` |
| Unit tests pass | `./gradlew.bat :app:testDebugUnitTest` (new `PushUiStateTest`, `PushControllerTest`, `PushNotifierTest` plus existing suite) |
| Lint green | `./gradlew.bat :app:lintDebug` |
| Manual scenarios from Spec §15 walked | Walk all 7, capture screenshots + DB snapshots |
| `#AD#1` observed against test unit `TEST_HAMS_APP_001` | logcat + Wialon UI confirms |
| Spec status flipped to "Implemented" | `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md` first line edited |
| `plans/phase2_ips_push.md` Task 2.8 marked `🟡 IMPLEMENTED — pending field verification` | grep confirms |

---

## 9. Auto + Manual cooperation rules (binding)

Source: spec §17. These are the invariants the code must enforce; verify them while reviewing Task 8 / 13 / 16 commits.

| Invariant | Verify by |
|---|---|
| One worker at most for `WORK_NAME = "hams-push"` | `adb shell dumpsys jobscheduler \| grep hams-push` shows ≤ 1 entry at any moment |
| Auto uses `ExistingWorkPolicy.KEEP` | `git grep "ExistingWorkPolicy" app/src/main/java/com/klk/hams/push/PushController.kt` — `enqueueAuto` line shows `KEEP` |
| Manual skips REPLACE if state is `Pushing` | unit test `triggerManual_whilePushing_doesNotEnqueueAndFlipsFlagOnly` in `PushControllerTest` |
| Cancel does NOT call `cancelUniqueWork` from UI | `git grep "cancelUniqueWork" app/src/main/java/com/klk/hams/` — only legitimate call sites are non-UI fatal paths (none today) |
| `manualPushActive` flag clears on terminal `PushUiState` | `CountViewModel.collect(uiStateFlow)` body — `Completed | Failed | Idle` branch flips flag false |
| 30-min budget is UI-side only (worker keeps running past budget) | `PushController.triggerManual` sets a coroutine `delay(AppConfig.PUSH_MANUAL_TIMEOUT_MS)`; no `cancelUniqueWork` in that timer |
| Three trigger paths all funnel through `enqueueAuto` (KEEP) or `triggerManual` (rule-#2 gated REPLACE) | `git grep -n "enqueueUniqueWork\|enqueueAuto\|triggerManual" app/src/main/java/com/klk/hams/` |

### Conflict matrix (copy of spec §17.6) — debug shortcut

If push behaviour looks wrong, locate the cell that matches `pushUiState.value` × user action and confirm the observed effect matches:

| state ↓ \ user → | None | Hold push button | Cancel overlay | Save / rollover |
|---|---|---|---|---|
| Idle | — | enqueue REPLACE + lock | n/a | enqueue KEEP |
| PendingWifi | — | enqueue REPLACE (no-op) + lock | unlock; worker scheduled | KEEP no-op |
| Pushing | — | **lock only**, no enqueue | unlock; worker continues | KEEP no-op |
| Completed | auto → Idle 5 s | n/a | unlock + ack | KEEP for new pending |
| Failed | persist | enqueue REPLACE + lock | unlock + ack | KEEP no-op |

If you see an extra `WM-WorkerWrapper` job-id appear during a manual hold while a `Pushing` was already in flight → rule #2 was violated. Re-inspect `PushController.triggerManual`.

---

## 10. Force-stop / battery-restriction recovery (debug)

Source: spec §18.

### Quick triage

```bash
# Did force-stop wipe WorkManager queue?
adb shell dumpsys jobscheduler | grep -A2 -i "com.klk.hams"

# Did SQLite preserve pending rows?
adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db \
  "SELECT id, push_status, save_type FROM tasks WHERE push_status='pending';"

# Did Task 10b re-enqueue auto-push on next launch?
adb logcat -d -s HAMS_PUSH | grep "onCreate.*pending tasks found"

# Is battery-optimisation exempt? (Task 0b)
adb shell dumpsys deviceidle | grep -A1 "Whitelist user apps"
```

### Expected sequence after force-stop → relaunch (Task 10b active)

1. User taps launcher icon.
2. `HamsApp.onCreate` runs → `PushNotifier.ensureChannel` → `repository.onTaskFinalized` wired → applicationScope `first()` reads pending count.
3. If `> 0`: `Log.d("HAMS_PUSH", "onCreate: N pending tasks found; enqueuing auto-push")` → `pushController.enqueueAuto()` → `WM-WorkerWrapper` schedules with `UNMETERED`.
4. When validated Wi-Fi appears → worker fires → notification → drains.
5. No manual button hold required.

If step 3 doesn't appear in logcat after a force-stop launch but `tasks` table has `push_status='pending'` rows, Task 10b is the regression site.

### What's still unrecoverable

- An active task that was being filled at force-stop time stays `active` (no `onTaskRemoved` fires on force-stop). It's finalized later by NEW TASK hold or daily rollover. No data loss; just a UX delay.
- Reboot alone does not un-stop a force-stopped app. User must explicitly tap the launcher icon.
