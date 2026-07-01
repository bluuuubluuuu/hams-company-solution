# Push Reliability Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make push fire reliably when Wi-Fi appears even with the app closed, by turning `HamsForegroundService` into a dual-mode location-typed service that survives app-swipe while tasks are pending and triggers the push the moment validated Wi-Fi connects.

**Architecture:** Approach A1 — the service *triggers*, WorkManager still *runs* the push. The service registers a `ConnectivityManager.NetworkCallback`; on validated unmetered Wi-Fi it calls `pushController.enqueueAuto()`, which runs the existing `PushWorker`/`PushEngine` unchanged. WorkManager's own constrained request stays as the cold-start fallback.

**Tech Stack:** Kotlin, Android foreground services, `ConnectivityManager`, WorkManager, JUnit 4 (JVM), AndroidX Room instrumented tests.

**Spec:** `docs/superpowers/specs/2026-05-22-push-reliability-upgrade-design.md`
**Branch:** `phase/push-reliability` (already created, spec committed at `569b187`).

---

## File map

| File | Phase | Responsibility |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | 1 | Retype `HamsForegroundService` `dataSync` → `location`. |
| `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt` | 1,2,3 | Dual-mode service: GPS gated on active task, conditional `onTaskRemoved` stop, hosts `WifiPushMonitor`, stateful notification. |
| `app/src/main/java/com/klk/hams/service/ServiceLifecycle.kt` *(new)* | 1 | Pure `shouldStopService` helper — JVM-testable. |
| `app/src/main/java/com/klk/hams/service/WifiPushMonitor.kt` *(new)* | 2 | Wraps `ConnectivityManager.registerNetworkCallback`; pure capability-match helper. |
| `app/src/main/java/com/klk/hams/HamsApp.kt` | 2 | `onCreate`: start the service headless when `pending > 0`. |
| `app/src/main/java/com/klk/hams/push/PushWorker.kt` | 3 | Skip `setForeground` when the service is alive. |
| `app/src/main/java/com/klk/hams/push/PushController.kt` | 4 | `acknowledgeCompletion()` also cancels notification 2002. |
| `app/src/test/java/com/klk/hams/service/ServiceLifecycleTest.kt` *(new)* | 1 | `shouldStopService` truth table. |
| `app/src/test/java/com/klk/hams/service/WifiPushMonitorTest.kt` *(new)* | 2 | Capability-match logic. |

`PushEngine.kt` is **not** touched.

---

## Phase 1 — Service dual-mode lifecycle + `location` retype

Goal: the service survives app-swipe while tasks are pending, and stops when there is no work. GPS is gated on an active task. No Wi-Fi monitor yet.

### Task 1.1 — Retype the service `location`

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt`

- [ ] **Step 1: Manifest — change the foreground service type**

In `AndroidManifest.xml`, the `HamsForegroundService` `<service>` element currently reads:
```xml
<service
    android:name=".service.HamsForegroundService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```
Change `dataSync` to `location`:
```xml
<service
    android:name=".service.HamsForegroundService"
    android:exported="false"
    android:foregroundServiceType="location" />
```
Leave the separate WorkManager `SystemForegroundService` override block (further down, with `tools:replace`) **unchanged** — that belongs to `PushWorker`'s cold-start path.

- [ ] **Step 2: Service — pass the type explicitly in `startForeground`**

In `HamsForegroundService.kt`, `onStartCommand` currently calls:
```kotlin
startForeground(NOTIFICATION_ID, buildNotification())
```
Replace with the typed form (required so the OS attributes the service as `location` on Android 10+):
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(
        NOTIFICATION_ID,
        buildNotification(),
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    )
} else {
    startForeground(NOTIFICATION_ID, buildNotification())
}
```
Add `import android.os.Build` if not present.

- [ ] **Step 3: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

### Task 1.2 — `shouldStopService` pure helper (TDD)

**Files:**
- Create: `app/src/main/java/com/klk/hams/service/ServiceLifecycle.kt`
- Create test: `app/src/test/java/com/klk/hams/service/ServiceLifecycleTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/klk/hams/service/ServiceLifecycleTest.kt`:
```kotlin
package com.klk.hams.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [shouldStopService] — the pure stop-decision used by
 * HamsForegroundService. No Android dependency.
 */
class ServiceLifecycleTest {

    @Test fun stops_whenNoActiveTaskAndNoPending() {
        assertEquals(true, shouldStopService(hasActiveTask = false, pendingCount = 0))
    }

    @Test fun staysAlive_whenTaskActive() {
        assertEquals(false, shouldStopService(hasActiveTask = true, pendingCount = 0))
    }

    @Test fun staysAlive_whenPendingTasksExist() {
        assertEquals(false, shouldStopService(hasActiveTask = false, pendingCount = 3))
    }

    @Test fun staysAlive_whenBothTaskActiveAndPending() {
        assertEquals(false, shouldStopService(hasActiveTask = true, pendingCount = 5))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.service.ServiceLifecycleTest"`
Expected: FAIL — `shouldStopService` unresolved.

- [ ] **Step 3: Implement the helper**

Create `app/src/main/java/com/klk/hams/service/ServiceLifecycle.kt`:
```kotlin
package com.klk.hams.service

/**
 * Pure stop-decision for [HamsForegroundService] (push-reliability upgrade,
 * 2026-05-22). The service has two reasons to be alive — a task is active
 * (counting mode) or unsynced tasks exist (push-monitor mode). When neither
 * holds there is no work to do and the service should stop.
 *
 * Kept as a free function with no Android types so it is unit-testable.
 */
fun shouldStopService(hasActiveTask: Boolean, pendingCount: Int): Boolean =
    !hasActiveTask && pendingCount == 0
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.service.ServiceLifecycleTest"`
Expected: PASS — 4 tests.

### Task 1.3 — Gate GPS on the active task

**Files:**
- Modify: `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt`

Background: today `onCreate` starts `LocationStream(REASON_TASK_ACTIVE)` unconditionally and `onDestroy` stops it. Once the service can be long-lived as a headless push-monitor, holding `HIGH_ACCURACY` GPS with no active task wastes battery. Gate the GPS stream on the active task. (`HeartbeatScheduler` is left running for the service's life — it already self-guards on an active task in the repository and its cost is a single 1-minute timer; gating it would require making it restartable, which is out of scope.)

- [ ] **Step 1: Add a coroutine scope + active-task observer**

In `HamsForegroundService.kt`, add fields near the top of the class:
```kotlin
private val serviceScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
)
private var gpsHeld = false
```

- [ ] **Step 2: Replace unconditional GPS start in `onCreate`**

In `onCreate`, the current body is:
```kotlin
override fun onCreate() {
    super.onCreate()
    (application as HamsApp).locationStream.start(LocationStream.REASON_TASK_ACTIVE)
    batteryEdgeObserver.register()
    heartbeat.start()
}
```
Replace with:
```kotlin
override fun onCreate() {
    super.onCreate()
    batteryEdgeObserver.register()
    heartbeat.start()
    observeActiveTaskForGps()
}

/**
 * Hold the HIGH_ACCURACY GPS stream only while a task is active. When no
 * task is active the service may still be alive as a push-monitor — it must
 * not keep GPS warm then.
 */
private fun observeActiveTaskForGps() {
    val app = application as HamsApp
    serviceScope.launch {
        app.repository.observeActiveTask().collect { task ->
            val shouldHold = task != null
            if (shouldHold && !gpsHeld) {
                app.locationStream.start(LocationStream.REASON_TASK_ACTIVE)
                gpsHeld = true
            } else if (!shouldHold && gpsHeld) {
                app.locationStream.stop(LocationStream.REASON_TASK_ACTIVE)
                gpsHeld = false
            }
        }
    }
}
```
Add imports: `kotlinx.coroutines.flow.collect` is not needed (member), but add `kotlinx.coroutines.launch`.

- [ ] **Step 3: Update `onDestroy`**

Current `onDestroy`:
```kotlin
override fun onDestroy() {
    heartbeat.stop()
    batteryEdgeObserver.unregister()
    (application as HamsApp).locationStream.stop(LocationStream.REASON_TASK_ACTIVE)
    super.onDestroy()
}
```
Replace with:
```kotlin
override fun onDestroy() {
    heartbeat.stop()
    batteryEdgeObserver.unregister()
    if (gpsHeld) {
        (application as HamsApp).locationStream.stop(LocationStream.REASON_TASK_ACTIVE)
        gpsHeld = false
    }
    serviceScope.cancel()
    super.onDestroy()
}
```
Add import `kotlinx.coroutines.cancel`.

- [ ] **Step 4: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

### Task 1.4 — Conditional stop on `onTaskRemoved`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt`

- [ ] **Step 1: Rewrite `onTaskRemoved`**

Current:
```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    val app = application as HamsApp
    val battery = readBatteryPct()
    val location = app.locationStream.snapshotFlow.value
    runBlocking {
        app.repository.saveActiveTask("auto_killed", location, battery)
    }
    stopSelf()
}
```
Replace with:
```kotlin
/**
 * App swiped from recents. Save the active task synchronously, then decide:
 * if unsynced tasks remain, KEEP RUNNING as a headless push-monitor (the
 * core push-reliability fix — the service must outlive the swipe so its
 * Wi-Fi monitor still fires). If nothing is pending, stop.
 */
override fun onTaskRemoved(rootIntent: Intent?) {
    val app = application as HamsApp
    val battery = readBatteryPct()
    val location = app.locationStream.snapshotFlow.value
    val pendingAfterSave = runBlocking {
        app.repository.saveActiveTask("auto_killed", location, battery)
        app.repository.observePendingTaskCount().first()
    }
    // After the save there is no active task. shouldStopService collapses to
    // "pendingCount == 0".
    if (shouldStopService(hasActiveTask = false, pendingCount = pendingAfterSave)) {
        android.util.Log.d(TAG, "onTaskRemoved: no pending work — stopping service")
        stopSelf()
    } else {
        android.util.Log.d(TAG, "onTaskRemoved: $pendingAfterSave pending — staying alive as push-monitor")
    }
}
```
Add imports: `kotlinx.coroutines.flow.first`. Add a `TAG` constant to the companion object:
```kotlin
private const val TAG = "HAMS_PUSH"
```

- [ ] **Step 2: Build + unit tests**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

### Task 1.5 — Commit + on-device verification

- [ ] **Step 1: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/com/klk/hams/service/HamsForegroundService.kt \
        app/src/main/java/com/klk/hams/service/ServiceLifecycle.kt \
        app/src/test/java/com/klk/hams/service/ServiceLifecycleTest.kt
git commit -m "feat(service): dual-mode lifecycle — survive app-swipe while tasks pending

Phase 1 of the push-reliability upgrade. Retype HamsForegroundService as
'location' (no 6h/24h cap; it genuinely keeps GPS alive). Gate the
HIGH_ACCURACY GPS stream on an active task so a headless push-monitor
session does not drain battery. onTaskRemoved now keeps the service alive
when pending tasks remain instead of always stopping — the service must
outlive the app-swipe so its Wi-Fi monitor (Phase 2) can fire.

New pure shouldStopService helper + 4 JVM tests.

Spec: docs/superpowers/specs/2026-05-22-push-reliability-upgrade-design.md
phase 1."
```

- [ ] **Step 2: On-device test**

```
.\gradlew.bat :app:installDebug
```
1. Open the app, pass the GPS gate.
2. Press `+` once, hold NEW TASK 3 s, confirm — one pending task now exists.
3. Swipe the app away from recents.
4. Check the service is still alive:
   ```
   adb shell dumpsys activity services com.klk.hams.debug | findstr "HamsForegroundService"
   ```
   **Expected:** the service is listed (still running).
5. Re-open the app, push the pending task (Wi-Fi on), wait until `pending = 0`.
6. Swipe the app away again.
7. Re-run the `dumpsys` command.
   **Expected:** `HamsForegroundService` is **not** listed (stopped — no work).

If step 4 shows the service gone, Phase 1 failed — do not proceed to Phase 2.

---

## Phase 2 — `WifiPushMonitor` + trigger + cold-start start (the core fix)

Goal: when validated unmetered Wi-Fi connects, push fires without the app being reopened.

### Task 2.1 — `WifiPushMonitor` (TDD on the capability-match helper)

**Files:**
- Create: `app/src/main/java/com/klk/hams/service/WifiPushMonitor.kt`
- Create test: `app/src/test/java/com/klk/hams/service/WifiPushMonitorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/klk/hams/service/WifiPushMonitorTest.kt`:
```kotlin
package com.klk.hams.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [isPushableNetwork] — the pure capability check that decides
 * whether a network the system reported is one we should push over.
 * Wi-Fi transport + unmetered + validated.
 */
class WifiPushMonitorTest {

    @Test fun accepts_wifiUnmeteredValidated() {
        assertEquals(true, isPushableNetwork(wifi = true, unmetered = true, validated = true))
    }

    @Test fun rejects_metered() {
        assertEquals(false, isPushableNetwork(wifi = true, unmetered = false, validated = true))
    }

    @Test fun rejects_notValidated() {
        assertEquals(false, isPushableNetwork(wifi = true, unmetered = true, validated = false))
    }

    @Test fun rejects_nonWifi() {
        assertEquals(false, isPushableNetwork(wifi = false, unmetered = true, validated = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.service.WifiPushMonitorTest"`
Expected: FAIL — `isPushableNetwork` unresolved.

- [ ] **Step 3: Implement `WifiPushMonitor`**

Create `app/src/main/java/com/klk/hams/service/WifiPushMonitor.kt`:
```kotlin
package com.klk.hams.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Pure capability check — extracted so the accept/reject decision is
 * unit-testable without Android. A network is pushable when it is Wi-Fi
 * transport, unmetered, and internet-validated.
 */
fun isPushableNetwork(wifi: Boolean, unmetered: Boolean, validated: Boolean): Boolean =
    wifi && unmetered && validated

/**
 * Push-reliability upgrade (2026-05-22). Registers a [ConnectivityManager]
 * network callback for validated unmetered Wi-Fi and invokes [onPushableUp]
 * each time such a network becomes available. Owned by
 * [HamsForegroundService]; lives as long as the service.
 *
 * This is the live replacement for WorkManager's constraint-deferred job,
 * which aggressive OEMs purge during idle. A registered callback in a live
 * foreground-service process is not purged the same way.
 */
class WifiPushMonitor(
    private val context: Context,
    private val onPushableUp: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (isPushableNetwork(wifi, unmetered, validated)) {
                Log.d("HAMS_PUSH", "WifiPushMonitor: pushable Wi-Fi up — triggering push")
                onPushableUp()
            }
        }
    }

    fun register() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        cm?.registerNetworkCallback(request, callback)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { cm?.unregisterNetworkCallback(callback) }
        registered = false
    }
}
```
Note: the `NetworkRequest` filters Wi-Fi + unmetered; `onCapabilitiesChanged` additionally checks `VALIDATED` because validation arrives slightly after the network appears. Using `onCapabilitiesChanged` (not `onAvailable`) means we react once the network is actually validated.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.service.WifiPushMonitorTest"`
Expected: PASS — 4 tests.

### Task 2.2 — Service registers the monitor → triggers push

**Files:**
- Modify: `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt`

- [ ] **Step 1: Add the monitor field + register/unregister**

In `HamsForegroundService.kt`, add a field:
```kotlin
private val wifiPushMonitor by lazy {
    WifiPushMonitor(applicationContext) {
        (application as HamsApp).pushController.enqueueAuto()
    }
}
```
In `onCreate`, after `observeActiveTaskForGps()`, add:
```kotlin
    wifiPushMonitor.register()
```
In `onDestroy`, before `serviceScope.cancel()`, add:
```kotlin
    wifiPushMonitor.unregister()
```

- [ ] **Step 2: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

### Task 2.3 — Headless stop when pending drains

**Files:**
- Modify: `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt`

Background: after `onTaskRemoved` keeps the service alive headless, a push will eventually drain `pending` to 0. The service must then stop itself. (During a foreground session — app open — the service does NOT self-stop; `MainActivity` owns its lifetime. The flag below ensures the stop-observer only acts headless.)

- [ ] **Step 1: Add a headless flag + pending observer**

Add a field:
```kotlin
@Volatile private var headless = false
```
In the `onTaskRemoved` `else` branch (the "staying alive" path from Task 1.4), set the flag and start the observer. Replace the `else` branch body:
```kotlin
    } else {
        android.util.Log.d(TAG, "onTaskRemoved: $pendingAfterSave pending — staying alive as push-monitor")
        headless = true
        observePendingForHeadlessStop()
    }
```
Add the observer method:
```kotlin
/**
 * While headless (app swiped away, service alive only as a push-monitor),
 * stop the service once the backlog has fully drained.
 */
private fun observePendingForHeadlessStop() {
    val app = application as HamsApp
    serviceScope.launch {
        app.repository.observePendingTaskCount().collect { count ->
            if (headless && shouldStopService(hasActiveTask = false, pendingCount = count)) {
                android.util.Log.d(TAG, "headless: backlog drained — stopping service")
                stopSelf()
            }
        }
    }
}
```

- [ ] **Step 2: Build + tests**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

### Task 2.4 — Cold-start: `HamsApp` starts the service when pending exists

**Files:**
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt`

- [ ] **Step 1: Start the service in the existing pending-recovery block**

In `HamsApp.kt` `onCreate`, the existing Task 10b block is:
```kotlin
applicationScope.launch {
    val pending = repository.observePendingTaskCount().first()
    if (pending > 0) {
        Log.d("HAMS_PUSH", "onCreate: $pending pending task(s) found; enqueuing auto-push")
        pushController.enqueueAuto()
    }
}
```
Add a service start inside the `if`:
```kotlin
applicationScope.launch {
    val pending = repository.observePendingTaskCount().first()
    if (pending > 0) {
        Log.d("HAMS_PUSH", "onCreate: $pending pending task(s) found; enqueuing auto-push + starting monitor service")
        pushController.enqueueAuto()
        // Cold-start recovery — bring up the push-monitor service so its
        // Wi-Fi callback is live even though no Activity was opened.
        val intent = android.content.Intent(this@HamsApp, HamsForegroundService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this@HamsApp, intent)
    }
}
```
`HamsForegroundService` is already imported in `HamsApp.kt`.

- [ ] **Step 2: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

### Task 2.5 — Commit + on-device verification (the core fix)

- [ ] **Step 1: Commit**

```bash
git add app/src/main/java/com/klk/hams/service/WifiPushMonitor.kt \
        app/src/test/java/com/klk/hams/service/WifiPushMonitorTest.kt \
        app/src/main/java/com/klk/hams/service/HamsForegroundService.kt \
        app/src/main/java/com/klk/hams/HamsApp.kt
git commit -m "feat(service): Wi-Fi monitor triggers push without reopening the app

Phase 2 — the core push-reliability fix. HamsForegroundService now hosts a
WifiPushMonitor (ConnectivityManager NetworkCallback for validated unmetered
Wi-Fi). When such a network appears it calls pushController.enqueueAuto(),
which runs the existing PushWorker immediately — the constraint is already
satisfied, so there is no deferred job for an aggressive OEM to purge.

Headless service stops itself once the backlog drains. HamsApp.onCreate
starts the monitor service on cold start when pending tasks exist.

New pure isPushableNetwork helper + 4 JVM tests.

Spec: docs/superpowers/specs/2026-05-22-push-reliability-upgrade-design.md
phase 2."
```

- [ ] **Step 2: On-device test — the bug scenario**

```
.\gradlew.bat :app:installDebug
```
1. Turn **Wi-Fi OFF** on the phone.
2. Open the app, build 3 pending tasks (`+`, hold NEW TASK 3 s, confirm — ×3).
3. **Swipe the app away from recents.** Do not reopen it.
4. Wait ~1 minute (simulating idle).
5. Turn **Wi-Fi ON**.
6. Watch the notification shade — **without touching the app**.
   **Expected:** within a few seconds of Wi-Fi connecting, the push runs and the success notification appears. The app was never reopened.
7. Confirm the DB drained:
   ```
   adb shell am force-stop com.klk.hams.debug
   ```
   ```
   cmd /c "adb exec-out run-as com.klk.hams.debug cat databases/hams.db > hams.db"
   sqlite3 hams.db "SELECT push_status, COUNT(*) FROM tasks GROUP BY push_status;"
   ```
   **Expected:** no `pending` rows.

This is the bug from the field report. If push does not fire at step 6 without reopening the app, Phase 2 failed.

---

## Phase 3 — Notification choreography

Goal: one evolving ongoing notification for waiting → progress; no 1001/2001 double-notification.

### Task 3.1 — `isRunning` flag on the service

**Files:**
- Modify: `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt`

- [ ] **Step 1: Add a companion `isRunning` flag**

In `HamsForegroundService.kt` companion object, add:
```kotlin
/** True while the service is alive — read by PushWorker to decide whether
 *  it needs its own foreground notification. */
@Volatile var isRunning: Boolean = false
    private set
```
In `onCreate`, first line after `super.onCreate()`:
```kotlin
isRunning = true
```
In `onDestroy`, first line:
```kotlin
isRunning = false
```

- [ ] **Step 2: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

### Task 3.2 — `PushWorker` skips `setForeground` when the service is alive

**Files:**
- Modify: `app/src/main/java/com/klk/hams/push/PushWorker.kt`

- [ ] **Step 1: Guard the initial `setForeground`**

In `PushWorker.kt` `doWork`, the initial foreground promotion currently calls `setForeground(ForegroundInfo(PushNotifier.NOTIFICATION_ID, ...))`. Wrap it:
```kotlin
val serviceAlive = com.klk.hams.service.HamsForegroundService.isRunning
if (!serviceAlive) {
    setForeground(
        ForegroundInfo(
            PushNotifier.NOTIFICATION_ID,
            PushNotifier.build(applicationContext, initialContent),
            fgsType
        )
    )
}
```
And in the `onProgress` callback, wrap the progress `setForeground` call the same way:
```kotlin
if (!serviceAlive && progressContent != null) {
    setForeground(
        ForegroundInfo(
            PushNotifier.NOTIFICATION_ID,
            PushNotifier.build(applicationContext, progressContent),
            fgsType
        )
    )
}
```
Capture `serviceAlive` once near the top of `doWork` so both sites use the same value. Keep `setProgress(...)` (the WorkInfo progress publish) **unconditional** — the in-app overlay still needs it.

- [ ] **Step 2: Build + unit tests**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

### Task 3.3 — Service notification reflects push state

**Files:**
- Modify: `app/src/main/java/com/klk/hams/service/HamsForegroundService.kt`

- [ ] **Step 1: Make `buildNotification` take a text argument**

Replace the current parameterless `buildNotification()` with:
```kotlin
private fun buildNotification(text: String): Notification {
    val tapIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("HAMS Task Recorder")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentIntent(tapIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}
```
Update the `onStartCommand` `startForeground` calls (Task 1.1 Step 2) to pass an initial text — `buildNotification("Recording FFB counts")`.

- [ ] **Step 2: Observe push UI state and update the notification**

Add an observer method and call it from `onCreate` (after `wifiPushMonitor.register()`):
```kotlin
/**
 * Drive the service's single ongoing notification (id 1001) through the
 * push lifecycle so the worker sees one evolving notification:
 * "waiting for Wi-Fi" -> "Uploading X of N" -> back to the counting text.
 */
private fun observePushStateForNotification() {
    val app = application as HamsApp
    serviceScope.launch {
        app.pushController.uiStateFlow.collect { state ->
            val text = when (state) {
                is com.klk.hams.push.PushUiState.PendingWifi ->
                    "${state.pendingTasks} task(s) waiting for Wi-Fi"
                is com.klk.hams.push.PushUiState.Pushing ->
                    "Uploading task ${(state.done + 1).coerceAtMost(state.total.coerceAtLeast(1))} of ${state.total}"
                else -> "Recording FFB counts"
            }
            val nm = androidx.core.app.NotificationManagerCompat.from(this@HamsForegroundService)
            runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
        }
    }
}
```
Call it in `onCreate`:
```kotlin
    observePushStateForNotification()
```
`NOTIFICATION_ID` is currently declared `private const val NOTIFICATION_ID = 1001` in the companion object. Remove the `private` modifier — `const val NOTIFICATION_ID = 1001` — so the observer method can reference it. (The observer and `startForeground` both post to this same id, which is the point — one notification slot.)

- [ ] **Step 3: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

### Task 3.4 — Commit + on-device verification

- [ ] **Step 1: Commit**

```bash
git add app/src/main/java/com/klk/hams/service/HamsForegroundService.kt \
        app/src/main/java/com/klk/hams/push/PushWorker.kt
git commit -m "feat(notify): one evolving service notification for waiting/progress

Phase 3. The service's ongoing notification (id 1001) now follows the push
lifecycle — 'Recording counts' -> 'N tasks waiting for Wi-Fi' -> 'Uploading
X of N' — by observing PushController.uiStateFlow. PushWorker skips its own
setForeground when the service is alive (HamsForegroundService.isRunning),
so there is no 1001/2001 double-notification; the worker still uses its FGS
notification in the cold-start case where no service exists.

Spec: docs/superpowers/specs/2026-05-22-push-reliability-upgrade-design.md
phase 3."
```

- [ ] **Step 2: On-device test**

```
.\gradlew.bat :app:installDebug
```
1. Wi-Fi OFF, build 3 pending tasks, swipe the app away.
   **Expected:** the ongoing notification reads `3 task(s) waiting for Wi-Fi`.
2. Turn Wi-Fi ON.
   **Expected:** the same notification updates to `Uploading task X of 3` — one notification, not two — then the dismissable terminal banner appears.

---

## Phase 4 — In-app overlay clears the notification

Goal: the manual-push success "OK" clears both the in-app panel and the terminal notification.

### Task 4.1 — `acknowledgeCompletion` cancels notification 2002

**Files:**
- Modify: `app/src/main/java/com/klk/hams/push/PushController.kt`

- [ ] **Step 1: Cancel the terminal notification in `acknowledgeCompletion`**

`PushController`'s constructor receives `context: Context` but does **not** retain it (verified — only `workManager` is built from it). First retain the application context. The class starts:
```kotlin
class PushController(
    context: Context,
    private val repository: TaskRepository
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
```
Add an `appContext` property immediately after the `workManager` line:
```kotlin
    private val appContext = context.applicationContext
```
Then replace the current method:
```kotlin
fun acknowledgeCompletion() {
    _completedAt.value = null
}
```
with:
```kotlin
fun acknowledgeCompletion() {
    _completedAt.value = null
    // Push-reliability upgrade (2026-05-22): the in-app "OK" also clears the
    // terminal notification banner so the worker dismisses the outcome once,
    // in one place.
    runCatching {
        androidx.core.app.NotificationManagerCompat
            .from(appContext)
            .cancel(com.klk.hams.push.PushNotifier.NOTIFICATION_ID_TERMINAL)
    }
}
```

- [ ] **Step 2: Build + unit tests**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (`PushControllerTest` still green — `acknowledgeCompletion` still clears `_completedAt`).

### Task 4.2 — Commit + on-device verification

- [ ] **Step 1: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushController.kt
git commit -m "feat(notify): manual-push OK clears the terminal notification too

Phase 4. PushController.acknowledgeCompletion now also cancels the terminal
banner (NOTIFICATION_ID_TERMINAL, 2002). The in-app overlay's OK / Close
button (CountViewModel.acknowledgePushOutcome) already calls this, so one
tap clears both the in-app success panel and the shade banner.

Spec: docs/superpowers/specs/2026-05-22-push-reliability-upgrade-design.md
phase 4."
```

- [ ] **Step 2: On-device test**

```
.\gradlew.bat :app:installDebug
```
1. Wi-Fi OFF, build 2 pending tasks. Wi-Fi ON.
2. On the count screen, hold the push button 3 s, confirm — the manual overlay appears.
3. Let the push complete — the overlay shows the success panel with **OK**, and the terminal banner is in the shade.
4. Tap **OK** in the app.
   **Expected:** the in-app panel closes **and** the terminal notification disappears from the shade.

---

## Final verification

- [ ] **Step 1: Full unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (including the 8 new JVM tests).

- [ ] **Step 2: Full debug build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm the four phase commits**

Run: `git log --oneline -5`
Expected: the four `feat(...)` commits from phases 1–4 plus the spec commit.

---

## Out of scope (do not implement here)

- No change to `PushEngine` push logic, the IPS protocol, or the SQLite schema.
- Auto-push stays silent in-app — no overlay, no screen dim. Only the manual button shows the overlay.
- Force-stop recovery is not solved — Android does not permit it; the app must be reopened once after a force-stop.
- No user-facing settings.

## Known limitation (documented, accepted)

If the worker presses Home (rather than swiping the app away) and never
returns, `onTaskRemoved` does not fire, so the service is not flipped to
`headless` and will not auto-stop even after the backlog drains. It keeps
running as a foreground service (GPS off — no active task — so the cost is
just the Wi-Fi callback and a 1-min heartbeat timer). This is acceptable for
a field tool that is actively used then swiped away; force-stop always stops
it. Revisit only if battery telemetry shows it matters.
