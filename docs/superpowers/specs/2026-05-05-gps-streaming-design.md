# GPS Streaming — Design Spec

> **Status:** Implemented 2026-05-06. Revised after on-device validation.
> **Replaces:** Phase 1 lazy-fetch GPS strategy (`LocationProvider.getValidLocation()` cached-5s + 2s single-fix fallback).
> **Drives:** Phase 2.8 location work; precedes `WifiMonitor` wiring.

## Revision — 2026-05-06 (post-field-validation)

Initial implementation used `PRIORITY_BALANCED_POWER_ACCURACY` at 2 s interval. On-device evidence (Honor `AWCX6R3B15001045`, indoor/window): 31 consecutive presses across 68 s shared one identical coordinate — BALANCED coalesced updates and the indicator visibly flapped Locked → Stale → Locked every few seconds. Decision: switch the stream to **dynamic priority**:

- While `REASON_TASK_ACTIVE` is held → `PRIORITY_HIGH_ACCURACY` at 1 s interval. This is the harvesting state and "very alert" UX takes precedence over battery.
- While only `REASON_FOREGROUND` is held → `PRIORITY_BALANCED_POWER_ACCURACY` (battery-friendly when nothing is being recorded).

`LOCATION_STREAM_STALENESS_MS` widened **5 000 → 10 000** so a single delayed callback does not flip the indicator. New `LOCATION_STREAM_FASTEST_MS = 500`. New watchdog: every 3 s, if no callback has arrived in 6 s, fire a one-shot `getCurrentLocation(HIGH_ACCURACY)` to wake a stalled stream. New stream-start seed via `lastLocation` so the press path doesn't wait for the first callback.

---

## 1. Motivation

Field UX observation (Oppo A5i, oil-palm harvester scenario, Scenario C cadence — multiple `+` presses per minute while moving):

- Current `LocationProvider.getValidLocation()` (`app/src/main/java/com/klk/hams/data/location/LocationProvider.kt:57`) suspends the press coroutine for up to **2 s** on cache miss while it calls `getCurrentLocation(PRIORITY_HIGH_ACCURACY)`.
- During that window the UI gives no feedback. The harvester does not know whether the press registered, was queued, or was ignored.
- With Scenario C cadence and a worker walking ~1 m/s, the fallback 5 s cache also lets a single fix back-fill 5+ presses across ~5 m of movement, putting cuts at the wrong tree (and at field boundaries, the wrong geofence).

The product requirement from WYH (2026-05-05): **GPS must feel "very alert," with no per-press latency, and the user must always be able to tell at a glance whether the next press will record correctly.**

Battery constraint (Scenario A): the device must run a full shift on one charge, no plug, no swap.

## 2. Strategy

Switch from per-press lazy fetch to a **continuous low-power location stream** held by an app-scoped owner, exposed as a `StateFlow<LocationSnapshot?>`. The `+` and `−` buttons read the latest snapshot **synchronously** at press time; they never suspend on the GPS API.

A persistent **GPS lock indicator** in the count screen header tells the user the current snapshot freshness state before any press.

The stream lives for the entire foreground window, and continues to run inside `HamsForegroundService` while a task is active even if the app is backgrounded — so when the harvester pulls the phone out of a pocket, the snapshot is already fresh.

## 3. High-level architecture

```
HamsApp (Application, app-scope)
  └── LocationStream (single instance)
         ├── FusedLocationProviderClient.requestLocationUpdates(...)
         │     PRIORITY_BALANCED_POWER_ACCURACY, interval ≈ 2 s, fastest 1 s
         ├── _snapshotFlow: MutableStateFlow<LocationSnapshot?>
         └── snapshotFlow: StateFlow<LocationSnapshot?>          [public read-only]

CountViewModel
  └── observes LocationStream.snapshotFlow
        derives gpsLockState: Locked | Stale | Acquiring | NoPermission
        onPlus() / onMinus() reads snapshotFlow.value synchronously

CountScreen
  └── shows persistent GPS indicator from gpsLockState
       + button enabled iff gpsLockState == Locked

HamsForegroundService
  └── on start  → LocationStream.start(reason = "task_active")
     on stop   → LocationStream.stop(reason = "task_active")
     onTaskRemoved → reads LocationStream.snapshotFlow.value
                     (replaces current LocationManager.getLastKnownLocation path)

MainActivity (foreground lifecycle)
  └── onResume → LocationStream.start(reason = "foreground")
     onPause  → LocationStream.stop(reason = "foreground")
```

The stream uses **reference counting** on start/stop reasons — the underlying `requestLocationUpdates` only stops once both reasons have released. This keeps the stream alive when the app goes background while a task is still active.

## 4. Component contracts

### 4.1 `LocationStream` (new file: `app/src/main/java/com/klk/hams/data/location/LocationStream.kt`)

```kotlin
class LocationStream(context: Context) {
    val snapshotFlow: StateFlow<LocationSnapshot?>
    fun start(reason: String)   // ref-counted; idempotent per reason
    fun stop(reason: String)    // ref-counted; idempotent per reason
    fun isStreaming(): Boolean  // diagnostic only
}
```

- Internally holds a `LocationCallback` and a single `FusedLocationProviderClient`.
- Each `LocationResult` callback updates `_snapshotFlow.value` to the latest `LocationSnapshot`. The mapping logic (`hdop`, `satellites`, `capturedAtMs`) is **identical to the current `LocationProvider.toSnapshot()`** to preserve the IPS frame contract.
- Uses `Priority.PRIORITY_BALANCED_POWER_ACCURACY`. Outdoors on a Snapdragon/Mediatek class device this is the sweet spot — fixes well under 5 m horizontal at modest steady current.
- `LocationRequest`:
  - `intervalMillis = AppConfig.LOCATION_STREAM_INTERVAL_MS` (default **2000**).
  - `minUpdateIntervalMillis = 1000` (cap fastest delivery).
  - `minUpdateDistanceMeters = 0f` (we WANT periodic ticks even when stationary, so the staleness-guard timer always sees fresh entries).
- Reason set is a `MutableSet<String>` guarded by a single mutex. `start("foreground")` adds to set and starts updates if the set was empty. `stop("foreground")` removes and stops updates if the set is now empty.

### 4.2 GPS lock state (new in `CountUiState.kt`)

```kotlin
enum class GpsLockState {
    NoPermission,   // GPS gate not yet passed
    Acquiring,      // gate passed, no snapshot yet
    Locked,         // snapshot age ≤ AppConfig.LOCATION_STREAM_STALENESS_MS
    Stale           // snapshot age > AppConfig.LOCATION_STREAM_STALENESS_MS
}
```

Computed every snapshot tick AND on a `Handler` recheck (so the indicator turns yellow even if no new fix arrives).

`canIncrement` becomes:
```kotlin
val canIncrement: Boolean
    get() = count < AppConfig.MAX_COUNT_PER_TASK && gpsLockState == GpsLockState.Locked
```

`canDecrement` likewise gates on `Locked`.

### 4.3 `CountViewModel` press path

```kotlin
fun onPlus() {
    val state = uiState.value
    if (!state.canIncrement) return                 // button disabled — defensive
    val snapshot = locationStream.snapshotFlow.value ?: return
    if (!isFresh(snapshot)) return                  // staleness double-check
    val battery = batteryMonitor.currentPct().toDouble()
    viewModelScope.launch { repository.recordPlus(snapshot, battery) }
}
```

- **No `getValidLocation()` call.** No suspend on the press path before the Room write.
- The `recordPlus` Room write still runs on a coroutine — Room itself is suspending — but the user-facing count increment can be reflected optimistically by the StateFlow update inside the repo, identical to today.

### 4.4 GPS indicator in `CountScreen`

Replaces the existing red "Waiting for GPS fix..." banner (which was reactive to a per-press miss) with a **persistent indicator** in the header row:

| State | Indicator | + button |
|---|---|---|
| `Locked` | green dot + "GPS" | enabled |
| `Stale` | yellow dot + "GPS — re-acquiring" | disabled |
| `Acquiring` | yellow dot + "GPS — acquiring" | disabled |
| `NoPermission` | (gate screen handles it) | n/a |

The press never silently swallows. If `Stale` somehow occurs at exactly the moment of press (race), the press is a no-op and the indicator is the user's signal — no toast, no banner flash.

## 5. Lifecycle & service integration

| Trigger | Reason added | Reason removed |
|---|---|---|
| `MainActivity.onResume` (gate passed) | `"foreground"` | — |
| `MainActivity.onPause` | — | `"foreground"` |
| `HamsForegroundService.onStartCommand` | `"task_active"` | — |
| `HamsForegroundService.onDestroy` | — | `"task_active"` |

`HamsForegroundService.onTaskRemoved` reads `LocationStream.snapshotFlow.value` and passes it into `repo.saveActiveTask("auto_killed", location, battery)`. The current `LocationManager.getLastKnownLocation(GPS_PROVIDER)` block (`HamsForegroundService.kt:62-79`) is dropped — the stream snapshot is strictly fresher and avoids the second permissions surface.

## 6. Manifest changes

- Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />`. Required by Android 14+ (target API 35) when a foreground service of type `location` is running.
- Change `<service ... foregroundServiceType="dataSync" />` to `foregroundServiceType="dataSync|location"`. Both types are needed because the service still does periodic heartbeats and pre-push work (dataSync) **and** now hosts the location stream.

## 7. Config keys (new in `AppConfig.kt`)

```kotlin
const val LOCATION_STREAM_INTERVAL_MS: Long = 2_000   // request cadence
const val LOCATION_STREAM_FASTEST_MS: Long = 1_000    // floor on delivery
const val LOCATION_STREAM_STALENESS_MS: Long = 5_000  // press blocked above this age
```

These are fixed `const val` for now (matching the existing pattern in `AppConfig.kt`); a runtime-tunable `config.json` is out of scope here.

## 8. GPS gate rule — updated wording

The existing rule in `CLAUDE.md` (capture pipeline section) says "cached ≤5s old is allowed". After this change, the wording becomes:

> The press path reads the latest snapshot from `LocationStream.snapshotFlow.value`. If the snapshot is `null` or older than `LOCATION_STREAM_STALENESS_MS`, the `+` and `−` buttons are disabled by `canIncrement` / `canDecrement`. No cut event ever lands without a coordinate fresher than the staleness threshold.

The doc-level invariant — "no event row with missing/fake coordinates" — is preserved.

## 9. Battery model

Rough envelope (BALANCED outdoors on this class of hardware):

- Continuous request at 2 s interval ≈ 30–80 mA steady while the GPS chip is locked.
- Today's lazy fetch under Scenario C costs roughly the same in burst (a single-shot HIGH_ACCURACY fix is ~80–150 mA peak for ~1 s) once the press rate exceeds ~1 press / 5 s.
- Net cost difference at C cadence is small; UX gain is large.

If field testing shows battery doesn't last a shift, two recoverable knobs:

1. Increase `LOCATION_STREAM_INTERVAL_MS` to 3000–5000.
2. Add a `"task_active"`-only mode (drop the `"foreground"` reason; stream only runs while a task is active in the service). Indicator shows `Acquiring` for the first ~3 s after the very first `+`.

Neither requires a redesign — both are config / lifecycle tweaks.

## 10. Testing

- New unit test `LocationStreamTest` — pure JVM, fakes `FusedLocationProviderClient` via injected callback driver. Covers: ref-count start/stop, snapshot freshness derivation, callback-driven flow updates.
- Existing `CountViewModel` tests (if/when added) gain a fake `LocationStream` and assert: button-disabled when `Stale`, press is synchronous, no coroutine wait before Room write.
- Manual emulator validation: mock-location refresh at 2 s; verify indicator green during stream, yellow within 5 s of stopping the mock, button correctly disabled.
- Phase 4 acceptance addition: walk a real boundary route on the Oppo A5i, confirm Wialon report assigns each `+` to the field where it was actually pressed.

## 11. Out of scope

- Stream-aware adaptive battery (e.g. drop to 5 s interval when count rate is low) — not until field data warrants.
- Replacing the FusedLocationProvider with a raw `LocationManager` GPS-only stream — not warranted for this hardware tier.
- Dynamic config via `config.json` — `AppConfig const val` is the existing convention.
- Wialon-side reporting changes — none needed; on-the-wire frame is unchanged.
