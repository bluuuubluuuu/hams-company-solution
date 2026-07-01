# Task 2.8 — Push Trigger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the existing `PushEngine` into a WorkManager-driven push pipeline that fires when validated Wi-Fi connects (auto) or when the user holds a 5 s push button (manual), with full UI/notification state machine and active-task insulation.

**Architecture:** A new `PushController` (app-scope) owns a `WorkManager` `OneTimeWorkRequest<PushWorker>` enqueued on every `pending`-task creation. WorkManager handles Wi-Fi-constraint scheduling natively (survives app close/swipe/reboot). `PushWorker` runs `PushEngine.run()` as a foreground worker with notifications. `PushController` aggregates engine `PushState` + WorkManager `WorkInfo` + repo pending count into a new `PushUiState` flow consumed by `CountViewModel` for in-app status. Active tasks are insulated — push only operates on `push_status='pending'` rows.

**Tech Stack:** `androidx.work:work-runtime-ktx` 2.9.x, existing Room + Compose stack, Kotlin coroutines.

**Spec:** `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md` (governs).

**Plan amendment 2026-05-08:** added Task 0b (battery-optimisation onboarding) and Task 10b (re-enqueue auto-push on app open if pending count > 0). Task 8 now encodes the §17 cooperation contract (KEEP for auto, skip-REPLACE-if-Pushing for manual, UI-only Cancel, 30-min UI-side budget timer). Task 13 clears `manualPushActive` on terminal `PushUiState`. Task 16 wording updated: Cancel only dismisses the overlay; the worker keeps running.

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `app/src/main/java/com/klk/hams/push/PushUiState.kt` | Sealed interface for UI-facing controller state (`Idle / PendingWifi / Pushing / Completed / Failed`). Distinct from engine's per-run `PushState`. |
| `app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt` | Adapter: `TaskRepository` → `PushRepository` interface defined in `PushEngine.kt`. |
| `app/src/main/java/com/klk/hams/push/PushController.kt` | App-scope owner. Holds `WorkManager` reference. Exposes `uiStateFlow: StateFlow<PushUiState>`, `pendingCountFlow: StateFlow<Int>`. Methods: `enqueueAuto()`, `triggerManual()`, `cancelManual()`. Maps `WorkInfo` + repo state → `PushUiState`. |
| `app/src/main/java/com/klk/hams/push/PushNotifier.kt` | Push-channel creation + state-driven notification builders. Pure data-class output for testability; final `Notification` build separated. |
| `app/src/main/java/com/klk/hams/push/PushWorker.kt` | `CoroutineWorker`. Thin glue: builds `PushEngine` from app singletons, calls `setForeground` with `PushNotifier`, runs engine, returns `Result.success()` / `Result.retry()` / `Result.failure()`. |
| `app/src/test/java/com/klk/hams/push/PushUiStateTest.kt` | Pure JVM. Equality + state-transition helper tests. |
| `app/src/test/java/com/klk/hams/push/PushControllerTest.kt` | Pure JVM. Maps fake `WorkInfo` flow + fake pending count to `PushUiState`. Verifies state-machine transitions. |
| `app/src/test/java/com/klk/hams/push/PushNotifierTest.kt` | Pure JVM. Notification text builders for each state. |

### Modified files

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | Add `workManager = "2.9.1"` and `androidx-work-runtime-ktx` library alias. |
| `app/build.gradle.kts` | Add `implementation(libs.androidx.work.runtime.ktx)`. |
| `app/src/main/java/com/klk/hams/AppConfig.kt` | `HEARTBEAT_INTERVAL_MINUTES` 10 → 1. New: `PUSH_MANUAL_TIMEOUT_MS = 30 * 60_000L`, `PUSH_RETRY_BACKOFF_MS = listOf(10_000L, 30_000L, 60_000L, 120_000L)`. |
| `app/src/main/java/com/klk/hams/data/db/TaskDao.kt` | Add `observePendingTaskCount(): Flow<Int>` and `pendingTasks(): List<Task>`. |
| `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` | Add pass-through `observePendingTaskCount()` and `pendingTasks()`. Mark `autoSaveActiveOnWifi` `@Deprecated`. After every `finalizeTask` call (manual save / auto_killed / auto_rollover), enqueue auto-push via `pushController.enqueueAuto()`. |
| `app/src/main/java/com/klk/hams/HamsApp.kt` | `val pushController by lazy { PushController(this) }`. Create `hams_push_channel` notification channel in `onCreate`. |
| `app/src/main/java/com/klk/hams/ui/count/CountUiState.kt` | Add `pushUiState: PushUiState = PushUiState.Idle`, `pendingTaskCount: Int = 0`, `manualPushLocked: Boolean = false`. |
| `app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt` | Observe `pushController.uiStateFlow` + `pendingCountFlow`; expose `triggerManualPush()`, `cancelManualPush()`. Add `repository.rolloverActiveTaskIfStale()` to `staleTick` (Spec §4.1). Recompute `manualPushLocked` from `pushUiState` + manual mode flag. |
| `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt` | Add `PendingBadge` near TASK pill (visible only when count > 0). Add `PushButton` in status strip top-right (5 s hold + progressive border). Add `PushStatusOverlay` modal sheet for manual flow. Apply `alpha = 0.4f` + `enabled = false` to +/−/NEW TASK when `manualPushLocked`. |

---

## Task 0b: Battery-optimisation exemption onboarding

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/klk/hams/ui/onboarding/BatteryOnboardingScreen.kt`
- Modify: `app/src/main/java/com/klk/hams/MainActivity.kt`

**Spec:** Spec §18.3.

- [ ] **Step 1: Add the permission**

In `AndroidManifest.xml`, add inside `<manifest>` block, alongside existing permissions:
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

- [ ] **Step 2: Add a one-shot onboarding gate**

In `MainActivity.onCreate`, before `setContent { ... }`, check a `SharedPreferences` flag (`prefs.getBoolean("battery_onboarding_shown", false)`). If false and `PowerManager.isIgnoringBatteryOptimizations(packageName)` is also false, route to `BatteryOnboardingScreen` first; otherwise route to the normal count screen. After the user dismisses or the system grants the exemption, write `prefs.putBoolean("battery_onboarding_shown", true)`.

- [ ] **Step 3: Implement `BatteryOnboardingScreen`**

Create a Compose screen that:
- Explains in plain plantation-worker copy: "HAMS uploads your task data when Wi-Fi is available. Android may stop background uploads to save battery. Please tap Allow on the next screen so HAMS can keep uploading even when the app is closed."
- A primary button "Allow" launches `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))`.
- A secondary "Skip for now" button (writes the flag and proceeds; user can re-trigger via a Settings deeplink later).
- For Oppo ColorOS, append a small footer note: "On Oppo, also enable: Settings → Battery → HAMS → Allow auto-launch and Allow background activity."

- [ ] **Step 4: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/klk/hams/ui/onboarding/BatteryOnboardingScreen.kt app/src/main/java/com/klk/hams/MainActivity.kt
git commit -m "feat(push): battery-optimisation exemption onboarding (Task 0b)"
```

---

## Task 1: Add WorkManager dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version + library alias to libs.versions.toml**

In the `[versions]` block, after the existing entries, add:
```toml
workManager = "2.9.1"
```

In the `[libraries]` block, add:
```toml
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
```

- [ ] **Step 2: Reference the library in `app/build.gradle.kts`**

In the `dependencies { }` block, after the existing `implementation(libs....)` lines, add:
```kotlin
implementation(libs.androidx.work.runtime.ktx)
```

- [ ] **Step 3: Sync + build to confirm**

Run:
```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. No new lint warnings.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(push): add WorkManager dependency for Task 2.8"
```

---

## Task 2: Update AppConfig constants

**Files:**
- Modify: `app/src/main/java/com/klk/hams/AppConfig.kt`

- [ ] **Step 1: Edit AppConfig.kt**

Find the line:
```kotlin
const val HEARTBEAT_INTERVAL_MINUTES: Int = 10
```

Change to:
```kotlin
const val HEARTBEAT_INTERVAL_MINUTES: Int = 1
```

After the location-stream constants (`LOCATION_STREAM_WATCHDOG_MS = 6_000`), add:
```kotlin
// Manual push session config (Task 2.8 spec).
const val PUSH_MANUAL_TIMEOUT_MS: Long = 30L * 60_000L

/**
 * Backoff schedule between retry attempts during a single push session.
 * Applied while inside the manual-mode 30-minute budget; auto-mode uses
 * WorkManager's own backoff (handled by Result.retry()).
 */
val PUSH_RETRY_BACKOFF_MS: List<Long> = listOf(10_000L, 30_000L, 60_000L, 120_000L)
```

- [ ] **Step 2: Build to confirm**

Run:
```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/AppConfig.kt
git commit -m "feat(push): heartbeat 1 min + manual push budget constants"
```

---

## Task 3: Add `observePendingTaskCount` and `pendingTasks` to TaskDao

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/db/TaskDao.kt`

- [ ] **Step 1: Add the queries**

In `TaskDao` (interface), after the existing methods, add:
```kotlin
@Query("SELECT COUNT(*) FROM tasks WHERE push_status = 'pending'")
fun observePendingTaskCount(): kotlinx.coroutines.flow.Flow<Int>

@Query("SELECT * FROM tasks WHERE push_status = 'pending' ORDER BY ended_at ASC")
suspend fun pendingTasks(): List<Task>
```

- [ ] **Step 2: Build to confirm Room generates the impl**

Run:
```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. KSP runs Room codegen without errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/db/TaskDao.kt
git commit -m "feat(push): TaskDao queries for pending count and list"
```

---

## Task 4: Add pass-through methods in TaskRepository + deprecate `autoSaveActiveOnWifi`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt`

- [ ] **Step 1: Add pass-throughs**

After `fun observeActiveTask(): Flow<Task?> = taskDao.observeActiveTask()`, add:
```kotlin
fun observePendingTaskCount(): Flow<Int> = taskDao.observePendingTaskCount()

suspend fun pendingTasks(): List<Task> = taskDao.pendingTasks()
```

- [ ] **Step 2: Deprecate `autoSaveActiveOnWifi`**

Find:
```kotlin
suspend fun autoSaveActiveOnWifi(
    batteryPct: Double,
    location: LocationSnapshot?
): Long? = saveActiveTask("auto_wifi", location, batteryPct)
```

Replace with:
```kotlin
@Deprecated(
    "Task 2.8 spec: push and task lifecycle are independent. Push only operates on " +
        "tasks already in push_status='pending'. Active-task finalization happens via " +
        "manual save (NEW TASK 5s), app swipe (auto_killed), or day rollover (auto_rollover). " +
        "Do not call from new code.",
    level = DeprecationLevel.WARNING
)
suspend fun autoSaveActiveOnWifi(
    batteryPct: Double,
    location: LocationSnapshot?
): Long? = saveActiveTask("auto_wifi", location, batteryPct)
```

- [ ] **Step 3: Build (expect deprecation warning, not error)**

Run:
```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. May emit one deprecation warning at the call site in `PushEngineTest` or wherever `autoSaveActiveOnWifi` is referenced; that's fine for now (cleaned up in Task 11).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt
git commit -m "feat(push): pending-task accessors + deprecate autoSaveActiveOnWifi"
```

---

## Task 5: Define `PushUiState` (controller-layer)

**Files:**
- Create: `app/src/main/java/com/klk/hams/push/PushUiState.kt`
- Create: `app/src/test/java/com/klk/hams/push/PushUiStateTest.kt`

- [ ] **Step 1: Write the failing test first**

Create `app/src/test/java/com/klk/hams/push/PushUiStateTest.kt`:
```kotlin
package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PushUiStateTest {

    @Test fun idleEqualsItself() {
        assertEquals(PushUiState.Idle, PushUiState.Idle)
    }

    @Test fun pendingWifiHoldsTaskCount() {
        val s = PushUiState.PendingWifi(pendingTasks = 3)
        assertEquals(3, s.pendingTasks)
    }

    @Test fun pushingEqualsByTotalAndDone() {
        assertEquals(PushUiState.Pushing(10, 4), PushUiState.Pushing(10, 4))
        assertNotEquals(PushUiState.Pushing(10, 4), PushUiState.Pushing(10, 5))
    }

    @Test fun isLockableHelperFlagsPushingAndPendingWifi() {
        assertTrue(PushUiState.PendingWifi(1).isLockable)
        assertTrue(PushUiState.Pushing(1, 0).isLockable)
        assertFalse(PushUiState.Idle.isLockable)
        assertFalse(PushUiState.Completed(1, Instant.EPOCH).isLockable)
        assertFalse(PushUiState.Failed("x", 1).isLockable)
    }
}
```

- [ ] **Step 2: Run test, verify FAIL with "unresolved reference: PushUiState"**

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushUiStateTest" --no-daemon 2>&1 | tail -10
```

Expected: compilation FAIL.

- [ ] **Step 3: Create the type**

Create `app/src/main/java/com/klk/hams/push/PushUiState.kt`:
```kotlin
package com.klk.hams.push

import java.time.Instant

/**
 * Controller-layer push state surfaced to the UI and to system notifications.
 * Distinct from [PushState], which is the engine's per-run terminal result.
 *
 * Transitions (Task 2.8 spec §5):
 *   Idle ──new pending task──> PendingWifi
 *   PendingWifi ──Wi-Fi up + worker starts──> Pushing
 *   Pushing ──all done──> Completed
 *   Pushing ──network drop──> PendingWifi
 *   Pushing ──fatal──> Failed
 *   Completed ──5s──> Idle
 *   Failed ──user retry──> PendingWifi
 */
sealed interface PushUiState {

    /** Helper for UI: should the screen lock/dim during this state? */
    val isLockable: Boolean
        get() = this is PendingWifi || this is Pushing

    data object Idle : PushUiState

    data class PendingWifi(val pendingTasks: Int) : PushUiState

    data class Pushing(val total: Int, val done: Int) : PushUiState

    data class Completed(val tasks: Int, val at: Instant) : PushUiState

    data class Failed(val reason: String, val pending: Int) : PushUiState
}
```

- [ ] **Step 4: Run test, verify PASS**

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushUiStateTest" --no-daemon 2>&1 | tail -10
```

Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushUiState.kt app/src/test/java/com/klk/hams/push/PushUiStateTest.kt
git commit -m "feat(push): add PushUiState sealed interface"
```

---

## Task 6: Implement `PushRepositoryImpl`

**Files:**
- Create: `app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt`

- [ ] **Step 1: Create the adapter**

Create `app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt`:
```kotlin
package com.klk.hams.push

import com.klk.hams.data.model.EventEntity
import com.klk.hams.data.repository.TaskRepository

/**
 * Adapts [TaskRepository] to [PushRepository] for [PushEngine].
 *
 * All methods delegate. Task 2.8 keeps the engine ignorant of Room/Android —
 * everything DB-shaped lives behind this interface.
 */
class PushRepositoryImpl(
    private val repo: TaskRepository
) : PushRepository {

    override suspend fun pendingPushableEvents(limit: Int): List<EventEntity> =
        repo.pendingPushableEvents(limit)

    override suspend fun markEventUploaded(eventId: Long) {
        repo.markEventUploaded(eventId)
    }

    override suspend fun markEventRejected(eventId: Long, reason: String) {
        repo.markEventRejected(eventId, reason)
    }

    override suspend fun markTaskUploadedIfAllPushableEventsUploaded(taskId: Long) {
        repo.markTaskUploadedIfAllPushableEventsUploaded(taskId)
    }
}
```

- [ ] **Step 2: Build to confirm interface match**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. If the existing `PushRepository` in `PushEngine.kt` has a different signature than above, fix the adapter to match the interface (the interface is the source of truth).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushRepositoryImpl.kt
git commit -m "feat(push): TaskRepository -> PushRepository adapter"
```

---

## Task 7: Implement `PushNotifier` (with tests)

**Files:**
- Create: `app/src/main/java/com/klk/hams/push/PushNotifier.kt`
- Create: `app/src/test/java/com/klk/hams/push/PushNotifierTest.kt`

- [ ] **Step 1: Write tests for the pure builder logic first**

Create `app/src/test/java/com/klk/hams/push/PushNotifierTest.kt`:
```kotlin
package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PushNotifierTest {

    @Test fun idleProducesNoNotification() {
        assertNull(PushNotifier.contentFor(PushUiState.Idle))
    }

    @Test fun pendingWifiProducesNoNotification() {
        // Pending Wi-Fi is silent (spec §6) — the chip in-app is the only signal.
        assertNull(PushNotifier.contentFor(PushUiState.PendingWifi(5)))
    }

    @Test fun pushingShowsProgress() {
        val c = PushNotifier.contentFor(PushUiState.Pushing(total = 10, done = 4))!!
        assertEquals("HAMS — Uploading 4/10 events", c.title)
        assertEquals(true, c.persistent)
        assertEquals(4, c.progressDone)
        assertEquals(10, c.progressTotal)
    }

    @Test fun completedShowsCheckmark() {
        val c = PushNotifier.contentFor(PushUiState.Completed(tasks = 3, at = Instant.EPOCH))!!
        assertEquals("HAMS — 3 tasks uploaded ✓", c.title)
        assertFalse(c.persistent)
        assertEquals(0, c.progressTotal)
    }

    @Test fun failedShowsReason() {
        val c = PushNotifier.contentFor(PushUiState.Failed(reason = "timeout", pending = 2))!!
        assertEquals("HAMS — Upload paused: timeout (2 left)", c.title)
        assertTrue(c.persistent)
        assertEquals(0, c.progressTotal)
    }
}
```

- [ ] **Step 2: Run tests, verify FAIL with unresolved references**

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushNotifierTest" --no-daemon 2>&1 | tail -10
```

Expected: compilation FAIL.

- [ ] **Step 3: Implement `PushNotifier`**

Create `app/src/main/java/com/klk/hams/push/PushNotifier.kt`:
```kotlin
package com.klk.hams.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.klk.hams.MainActivity

/**
 * Builds notifications for push state transitions.
 *
 * Pure builder logic ([contentFor]) is separated from the Android Notification
 * API ([build]) so the text and persistence rules can be unit-tested without
 * an instrumented harness.
 */
object PushNotifier {

    const val CHANNEL_ID = "hams_push_channel"
    const val NOTIFICATION_ID = 2001

    /** Notification content + persistence rule for a state, or null when no notification. */
    data class Content(
        val title: String,
        val persistent: Boolean,
        val progressDone: Int = 0,
        val progressTotal: Int = 0
    )

    fun contentFor(state: PushUiState): Content? = when (state) {
        is PushUiState.Idle -> null
        is PushUiState.PendingWifi -> null  // silent — chip in-app is the only signal
        is PushUiState.Pushing -> Content(
            title = "HAMS — Uploading ${state.done}/${state.total} events",
            persistent = true,
            progressDone = state.done,
            progressTotal = state.total
        )
        is PushUiState.Completed -> Content(
            title = "HAMS — ${state.tasks} tasks uploaded ✓",
            persistent = false
        )
        is PushUiState.Failed -> Content(
            title = "HAMS — Upload paused: ${state.reason} (${state.pending} left)",
            persistent = true
        )
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAMS push status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Wialon push progress and completion"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, content: Content): Notification {
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(content.title)
            .setContentIntent(tap)
            .setOngoing(content.persistent)
            .setAutoCancel(!content.persistent)
        if (content.progressTotal > 0) {
            builder.setProgress(content.progressTotal, content.progressDone, false)
        }
        return builder.build()
    }
}
```

- [ ] **Step 4: Run tests, verify PASS**

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushNotifierTest" --no-daemon 2>&1 | tail -10
```

Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushNotifier.kt app/src/test/java/com/klk/hams/push/PushNotifierTest.kt
git commit -m "feat(push): PushNotifier with state-driven content"
```

---

## Task 8: Implement `PushController` (with tests)

> **Amendment 2026-05-08 (binding for this task):** Spec §17 cooperation contract is the source of truth for the four `PushController` methods. The original code below is superseded where it conflicts. Specifically:
> - **`enqueueAuto()`** uses `ExistingWorkPolicy.KEEP` (already correct in original).
> - **`triggerManual()`** must read `uiStateFlow.value` first. If it is `Pushing`, **skip the WorkManager enqueue** and only flip `manualPushActive = true`. Otherwise enqueue with `REPLACE`. (Rule #2.)
> - **Rename `cancelManual()` → `dismissManualOverlay()`.** It must NOT call `workManager.cancelUniqueWork(WORK_NAME)`. It only sets `_manualPushActive.value = false` and cancels the budget timer. (Rule #3.)
> - **Add a 30-min budget timer** inside `triggerManual()` using `AppConfig.PUSH_MANUAL_TIMEOUT_MS`. Timer flips `_manualPushActive` to false when it fires; worker is unaffected. (§17.7.)
> - **Expose `manualPushActive: StateFlow<Boolean>`** so the VM can drive the lock without recomputing it from `pushUiState`.
> - **Expose `dismissManualOverlay()` as the cancel handler**; do not expose any method that calls `cancelUniqueWork` to UI.
>
> Add corresponding tests in `PushControllerTest`:
> - `triggerManual_whilePushing_doesNotEnqueueAndFlipsFlagOnly` (rule #2)
> - `dismissManualOverlay_doesNotCancelWorker` (rule #3 — assert `cancelUniqueWork` was never invoked)
> - `manualBudgetTimer_clearsFlagAfterTimeout` (§17.7 — use a controllable test dispatcher)

**Files:**
- Create: `app/src/main/java/com/klk/hams/push/PushController.kt`
- Create: `app/src/test/java/com/klk/hams/push/PushControllerTest.kt`

- [ ] **Step 1: Write tests for the state-mapping logic first**

Create `app/src/test/java/com/klk/hams/push/PushControllerTest.kt`:
```kotlin
package com.klk.hams.push

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PushControllerTest {

    private fun fakeWorkInfo(state: WorkInfo.State, progressDone: Int = 0, progressTotal: Int = 0): WorkInfo {
        val data = androidx.work.Data.Builder()
            .putInt("done", progressDone)
            .putInt("total", progressTotal)
            .build()
        return WorkInfo(
            UUID.randomUUID(),
            state,
            emptySet(),
            androidx.work.Data.EMPTY,
            data,
            0,
            0
        )
    }

    @Test fun noWork_zeroPending_isIdle() {
        val mapped = PushController.mapToUiState(workInfo = null, pendingCount = 0, completedAt = null)
        assertEquals(PushUiState.Idle, mapped)
    }

    @Test fun noWork_pendingExist_isPendingWifi() {
        val mapped = PushController.mapToUiState(workInfo = null, pendingCount = 5, completedAt = null)
        assertEquals(PushUiState.PendingWifi(5), mapped)
    }

    @Test fun enqueued_isPendingWifi() {
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.ENQUEUED),
            pendingCount = 3,
            completedAt = null
        )
        assertEquals(PushUiState.PendingWifi(3), mapped)
    }

    @Test fun running_withProgress_isPushing() {
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.RUNNING, progressDone = 4, progressTotal = 10),
            pendingCount = 6,
            completedAt = null
        )
        assertEquals(PushUiState.Pushing(total = 10, done = 4), mapped)
    }

    @Test fun succeeded_recentlyCompleted_isCompleted() {
        val now = Instant.now()
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.SUCCEEDED),
            pendingCount = 0,
            completedAt = now
        )
        assertTrue(mapped is PushUiState.Completed)
    }

    @Test fun failed_isFailed() {
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.FAILED),
            pendingCount = 2,
            completedAt = null
        )
        val expected = PushUiState.Failed(reason = "see logs", pending = 2)
        assertEquals(expected, mapped)
    }
}
```

- [ ] **Step 2: Run tests, verify FAIL**

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushControllerTest" --no-daemon 2>&1 | tail -10
```

Expected: compilation FAIL (`PushController.mapToUiState` unresolved).

- [ ] **Step 3: Implement `PushController`**

Create `app/src/main/java/com/klk/hams/push/PushController.kt`:
```kotlin
package com.klk.hams.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.klk.hams.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/**
 * App-scope owner of the push pipeline state.
 *
 * Aggregates three sources into [uiStateFlow]:
 *   1. WorkManager's `WorkInfo` for the unique-named push work
 *   2. The pending-task count from the repository
 *   3. The completion timestamp (controller-local; reset on the next enqueue)
 *
 * Pure mapping logic lives in [mapToUiState] for unit testability.
 */
class PushController(
    context: Context,
    private val repository: TaskRepository
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _completedAt = MutableStateFlow<Instant?>(null)

    val pendingCountFlow: StateFlow<Int> =
        repository.observePendingTaskCount().stateIn(scope, SharingStarted.Eagerly, 0)

    val uiStateFlow: StateFlow<PushUiState> = combine(
        workInfoFlow(),
        pendingCountFlow,
        _completedAt
    ) { info, pending, completedAt ->
        mapToUiState(info, pending, completedAt)
    }.stateIn(scope, SharingStarted.Eagerly, PushUiState.Idle)

    fun enqueueAuto() {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .addTag(TAG_AUTO)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun triggerManual() {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .addTag(TAG_MANUAL)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelManual() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    fun acknowledgeCompletion() {
        _completedAt.value = null
    }

    private fun workInfoFlow(): kotlinx.coroutines.flow.Flow<WorkInfo?> =
        kotlinx.coroutines.flow.callbackFlow {
            val obs = workManager.getWorkInfosForUniqueWorkLiveData(WORK_NAME)
            // Simple fallback: poll every 1s. (Live observation via LiveData -> Flow
            // adapter requires lifecycle owner; for a singleton controller we keep
            // this minimal. Production migration to androidx-lifecycle-livedata-ktx
            // can use asFlow() if needed.)
            val job = launch {
                while (isActive) {
                    val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                    val latest = infos.maxByOrNull { it.runAttemptCount }
                    trySend(latest)
                    if (latest?.state == WorkInfo.State.SUCCEEDED) {
                        _completedAt.value = Instant.now()
                    }
                    kotlinx.coroutines.delay(1_000)
                }
            }
            awaitClose { job.cancel() }
        }

    companion object {
        const val WORK_NAME = "hams-push"
        const val TAG_AUTO = "hams-push-auto"
        const val TAG_MANUAL = "hams-push-manual"
        const val PROGRESS_DONE_KEY = "done"
        const val PROGRESS_TOTAL_KEY = "total"

        /** Pure state-mapping function, no Android dependencies. Testable. */
        fun mapToUiState(
            workInfo: WorkInfo?,
            pendingCount: Int,
            completedAt: Instant?
        ): PushUiState = when {
            workInfo == null && pendingCount == 0 -> PushUiState.Idle
            workInfo == null -> PushUiState.PendingWifi(pendingCount)
            workInfo.state == WorkInfo.State.RUNNING -> {
                val total = workInfo.progress.getInt(PROGRESS_TOTAL_KEY, 0)
                val done = workInfo.progress.getInt(PROGRESS_DONE_KEY, 0)
                if (total > 0) PushUiState.Pushing(total, done) else PushUiState.PendingWifi(pendingCount)
            }
            workInfo.state == WorkInfo.State.SUCCEEDED -> {
                val tasks = workInfo.outputData.getInt("tasks", 0)
                PushUiState.Completed(tasks = tasks, at = completedAt ?: Instant.EPOCH)
            }
            workInfo.state == WorkInfo.State.FAILED ->
                PushUiState.Failed(reason = "see logs", pending = pendingCount)
            else -> PushUiState.PendingWifi(pendingCount)
        }
    }
}
```

- [ ] **Step 4: Run tests, verify PASS**

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.PushControllerTest" --no-daemon 2>&1 | tail -10
```

Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushController.kt app/src/test/java/com/klk/hams/push/PushControllerTest.kt
git commit -m "feat(push): PushController with WorkInfo -> PushUiState mapping"
```

---

## Task 9: Implement `PushWorker`

**Files:**
- Create: `app/src/main/java/com/klk/hams/push/PushWorker.kt`

- [ ] **Step 1: Create the worker**

Create `app/src/main/java/com/klk/hams/push/PushWorker.kt`:
```kotlin
package com.klk.hams.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.klk.hams.AppConfig
import com.klk.hams.HamsApp

/**
 * WorkManager worker that runs [PushEngine.run] on a background thread,
 * with foreground-service notification for visibility while pushing.
 *
 * Result mapping:
 *   - [PushState.Success] / [PushState.Partial] -> [Result.success]
 *   - [PushState.Failed] -> [Result.retry] (WorkManager handles backoff)
 *   - Any thrown exception -> [Result.retry]
 *
 * The engine is built fresh per worker run to avoid stale singletons across
 * worker process restarts.
 */
class PushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as HamsApp
        val repo = app.repository
        val pendingTaskIdsBefore = repo.pendingTasks().map { it.id }

        if (pendingTaskIdsBefore.isEmpty()) {
            Log.d(TAG, "doWork: no pending tasks, returning success(0)")
            return Result.success(workDataOf("tasks" to 0))
        }

        // Promote to foreground BEFORE long work so the OS doesn't kill us at the
        // 10s expedited deadline.
        val pushingInitial = PushUiState.Pushing(total = pendingTaskIdsBefore.size, done = 0)
        val content = PushNotifier.contentFor(pushingInitial)!!
        setForeground(
            ForegroundInfo(PushNotifier.NOTIFICATION_ID, PushNotifier.build(applicationContext, content))
        )

        // Constructor parameter names verified against PushEngine.kt:73-84 and
        // WialonIPSClient.kt:29-36 (see docs/superpowers/specs/2026-05-08-push-integration-checklist.md §3).
        val engine = PushEngine(
            repo = PushRepositoryImpl(repo),
            senderFactory = { WialonIPSClient() },        // defaults to AppConfig.IPS_HOST/PORT/DEVICE_UNIQUE_ID
            chunkSize = AppConfig.BATCH_SIZE,
            interMessageDelayMs = AppConfig.BATCH_DELAY_MS,
            maxAttempts = AppConfig.MAX_RETRY_ATTEMPTS,
        )

        return try {
            val result = engine.run()
            Log.d(TAG, "doWork: engine returned $result")

            // Per-task push_status sweep.
            for (taskId in pendingTaskIdsBefore) {
                repo.markTaskUploadedIfAllPushableEventsUploaded(taskId)
            }

            when (result) {
                is PushState.Success -> Result.success(workDataOf("tasks" to pendingTaskIdsBefore.size))
                is PushState.Partial -> Result.success(workDataOf("tasks" to pendingTaskIdsBefore.size))
                is PushState.Failed -> Result.retry()
                else -> Result.retry()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "doWork: $t — retrying via WorkManager backoff", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HAMS_PUSH"
    }
}
```

- [ ] **Step 2: Build to confirm**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. The `PushEngine` constructor signature must match — if the existing engine has different parameter names or order, fix the worker to match (engine is the source of truth).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushWorker.kt
git commit -m "feat(push): PushWorker — foreground CoroutineWorker"
```

---

## Task 10: Wire `PushController` + push notification channel into `HamsApp`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt`

- [ ] **Step 1: Add the lazy controller**

Find:
```kotlin
val locationStream: LocationStream by lazy { LocationStream(applicationContext) }
```

After it, add:
```kotlin
val pushController: com.klk.hams.push.PushController by lazy {
    com.klk.hams.push.PushController(applicationContext, repository)
}
```

- [ ] **Step 2: Add the push channel creation**

Find `private fun createNotificationChannel()` and rename it to `createServiceChannel()`. Then add a second call in `onCreate`:

```kotlin
override fun onCreate() {
    super.onCreate()
    createServiceChannel()
    com.klk.hams.push.PushNotifier.ensureChannel(this)
}
```

- [ ] **Step 3: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/klk/hams/HamsApp.kt
git commit -m "feat(push): wire PushController + push channel in HamsApp"
```

---

## Task 10b: Re-enqueue auto-push on app open if pending count > 0

**Files:**
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt`

**Spec:** Spec §18.2, §18.3 (Task 10b).

Goal: close the post-force-stop gap. After `Application.onCreate` runs (Task 10 already wires `PushController` and the push channel), check the pending count once and trigger an auto-enqueue if any rows are stranded.

- [ ] **Step 1: Edit `HamsApp.onCreate`**

After `repository.onTaskFinalized = { pushController.enqueueAuto() }` (Task 11 line; if Task 11 hasn't been merged yet, place this block after `PushNotifier.ensureChannel(this)`), add:

```kotlin
// Spec §18: closes the post-force-stop / fresh-launch gap.
// If SQLite holds pending rows but WorkManager's queue is empty (force-stop wiped it),
// schedule an auto-push on first launch.
applicationScope.launch {
    val pending = repository.observePendingTaskCount().first()
    if (pending > 0) {
        Log.d("HAMS_PUSH", "onCreate: $pending pending tasks found; enqueuing auto-push")
        pushController.enqueueAuto()
    }
}
```

If `applicationScope` doesn't exist on `HamsApp` yet, add:
```kotlin
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

Required imports:
```kotlin
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/HamsApp.kt
git commit -m "feat(push): re-enqueue auto-push on app open if pending > 0 (Task 10b)"
```

---

## Task 11: Enqueue auto-push on every task finalization

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt`
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt`

The repo doesn't know about `PushController` (would create a circular dependency). Instead, the repo exposes a callback that `HamsApp` wires.

- [ ] **Step 1: Add a finalization callback to TaskRepository**

In `TaskRepository`, add a property after `private val eventDao = db.eventDao()`:
```kotlin
/** Set by HamsApp to enqueue auto-push after every task finalization. */
@Volatile
var onTaskFinalized: (() -> Unit)? = null
```

In `saveActiveTask`, after `taskDao.finalizeTask(...)` (the line that sets push_status='pending'), append:
```kotlin
onTaskFinalized?.invoke()
```

In `rolloverActiveTaskIfStale`, after `taskDao.finalizeTask(...)` lines (both the netCount > 0 and == 0 branches), append:
```kotlin
onTaskFinalized?.invoke()
```

- [ ] **Step 2: Wire in HamsApp**

In `HamsApp.onCreate`, after `PushNotifier.ensureChannel(this)`, add:
```kotlin
repository.onTaskFinalized = { pushController.enqueueAuto() }
```

- [ ] **Step 3: Wire onTaskRemoved in HamsForegroundService**

The service's `onTaskRemoved` calls `app.repository.saveActiveTask("auto_killed", ...)`. With the callback above, this auto-enqueues. No service-side change needed.

- [ ] **Step 4: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt app/src/main/java/com/klk/hams/HamsApp.kt
git commit -m "feat(push): auto-enqueue on every task finalization"
```

---

## Task 12: Extend `CountUiState` with push fields

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountUiState.kt`

- [ ] **Step 1: Add fields**

In the `CountUiState` data class, add these fields just before the closing parenthesis:
```kotlin
val pushUiState: com.klk.hams.push.PushUiState = com.klk.hams.push.PushUiState.Idle,
val pendingTaskCount: Int = 0,
val manualPushActive: Boolean = false,
```

After the existing computed properties, add:
```kotlin
/** True while a manual push is in progress (UI must dim and lock counting). */
val isManualPushLocked: Boolean
    get() = manualPushActive && pushUiState.isLockable
```

Update `canIncrement` and `canDecrement` to also block when locked:
```kotlin
val canDecrement: Boolean get() = count > 0 && gpsLockState == GpsLockState.Locked && !isManualPushLocked
val canIncrement: Boolean
    get() = count < AppConfig.MAX_COUNT_PER_TASK && gpsLockState == GpsLockState.Locked && !isManualPushLocked
```

- [ ] **Step 2: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/count/CountUiState.kt
git commit -m "feat(push): CountUiState push state fields + lock"
```

---

## Task 13: `CountViewModel` observes controller + adds rollover to staleTick

> **Amendment 2026-05-08 (binding):** Spec §17 rule #5 — `manualPushActive` flag clears automatically when `pushUiState` reaches a terminal value. In the `pushController.uiStateFlow.collect { ... }` block, after writing the new state, add:
>
> ```kotlin
> if (pushUi is PushUiState.Completed || pushUi is PushUiState.Failed || pushUi is PushUiState.Idle) {
>     _uiState.update { it.copy(manualPushActive = false) }
> }
> ```
>
> Rename `cancelManualPush()` → `dismissManualPushOverlay()` and call `pushController.dismissManualOverlay()` (not `cancelManual()`). The doc tag `Log.d(TAG, "manual push cancelled by user")` becomes `"manual overlay dismissed; worker continues"`.

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt`

- [ ] **Step 1: Observe push state**

In the `init` block, add a new observation launch:
```kotlin
viewModelScope.launch {
    val app = application as HamsApp
    app.pushController.uiStateFlow.collect { pushUi ->
        _uiState.update { it.copy(pushUiState = pushUi) }
    }
}

viewModelScope.launch {
    val app = application as HamsApp
    app.pushController.pendingCountFlow.collect { count ->
        _uiState.update { it.copy(pendingTaskCount = count) }
    }
}
```

- [ ] **Step 2: Add manual push triggers**

After `fun onMinus()`, add:
```kotlin
fun onPushButtonConfirmed() {
    val app = getApplication<HamsApp>()
    _uiState.update { it.copy(manualPushActive = true) }
    app.pushController.triggerManual()
    Log.d(TAG, "manual push triggered")
}

fun cancelManualPush() {
    val app = getApplication<HamsApp>()
    app.pushController.cancelManual()
    _uiState.update { it.copy(manualPushActive = false) }
    Log.d(TAG, "manual push cancelled by user")
}

fun acknowledgePushOutcome() {
    val app = getApplication<HamsApp>()
    app.pushController.acknowledgeCompletion()
    _uiState.update { it.copy(manualPushActive = false) }
}
```

- [ ] **Step 3: Add rollover to staleTick (Spec §4.1)**

Find:
```kotlin
private val staleTick = object : Runnable {
    override fun run() {
        recomputeGpsLock()
        refreshTodayDate()
        staleHandler.postDelayed(this, STALE_RECHECK_MS)
    }
}
```

Replace with:
```kotlin
private val staleTick = object : Runnable {
    override fun run() {
        recomputeGpsLock()
        refreshTodayDate()
        viewModelScope.launch {
            // Spec §4.1: closes the midnight-cross gap when app stays open.
            // Cheap no-op when active.taskDate == today's MYT day.
            repository.rolloverActiveTaskIfStale()?.also { rolledId ->
                Log.d(TAG, "tick rollover: finalized stale task id=$rolledId")
            }
        }
        staleHandler.postDelayed(this, STALE_RECHECK_MS)
    }
}
```

- [ ] **Step 4: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt
git commit -m "feat(push): VM observes push state + periodic rollover in staleTick"
```

---

## Task 14: `PendingBadge` composable

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt`

- [ ] **Step 1: Add the composable**

Just before `private fun BlockingMessage(...)`, add:
```kotlin
@Composable
private fun PendingBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val label = if (count > 99) "99+" else count.toString()
    Surface(
        modifier = modifier.heightIn(min = 28.dp),
        shape = RoundedCornerShape(50),
        color = FieldForest,
        contentColor = FieldForestOn
    ) {
        Text(
            text = "↑ $label",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
```

- [ ] **Step 2: Use it in StatusStrip**

Find the TASK pill in `StatusStrip` and wrap it together with the badge in a Column or Row that puts the badge on top of (or beside) the pill. Replace:
```kotlin
StatusPill(
    value = taskValue(state),
    accent = FieldForest,
    modifier = Modifier.weight(1.4f)
)
```

with:
```kotlin
Box(modifier = Modifier.weight(1.4f)) {
    StatusPill(
        value = taskValue(state),
        accent = FieldForest,
        modifier = Modifier.fillMaxWidth()
    )
    PendingBadge(
        count = state.pendingTaskCount,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp)
    )
}
```

- [ ] **Step 3: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/count/CountScreen.kt
git commit -m "feat(push): PendingBadge in TASK pill (visible when count > 0)"
```

---

## Task 15: `PushButton` (5 s hold + progressive border)

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt`

- [ ] **Step 1: Add ViewModel field for hold progress**

In `CountViewModel`, add:
```kotlin
private var pushHoldJob: Job? = null

fun onPushPressStart() {
    pushHoldJob?.cancel()
    pushHoldJob = viewModelScope.launch {
        repeat(100) { i ->
            delay(50)
            _uiState.update { it.copy(pushHoldProgress = (i + 1) / 100f) }
        }
        _uiState.update { it.copy(showPushConfirmDialog = true) }
    }
}

fun onPushPressCancel() {
    pushHoldJob?.cancel()
    pushHoldJob = null
    _uiState.update { state ->
        if (state.showPushConfirmDialog) state else state.copy(pushHoldProgress = 0f)
    }
}

fun onPushConfirmDismissed() {
    _uiState.update { it.copy(showPushConfirmDialog = false, pushHoldProgress = 0f) }
}
```

In `CountUiState` add:
```kotlin
val pushHoldProgress: Float = 0f,
val showPushConfirmDialog: Boolean = false,
```

- [ ] **Step 2: Add the PushButton composable**

In `CountScreen.kt`, just below `PendingBadge`, add:
```kotlin
@Composable
private fun PushButton(
    enabled: Boolean,
    holdProgress: Float,
    onPressStart: () -> Unit,
    onPressCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (enabled) FieldForest else FieldSlate
    Surface(
        modifier = modifier
            .size(40.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        val released = tryAwaitRelease()
                        if (!released) onPressCancel()
                    }
                )
            },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, borderColor.copy(alpha = 0.3f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Progressive border via a sweep arc.
            Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                if (holdProgress > 0f) {
                    drawArc(
                        color = borderColor,
                        startAngle = -90f,
                        sweepAngle = 360f * holdProgress,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                    )
                }
            }
            Text(
                text = "↑",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = borderColor
            )
        }
    }
}
```

Add the imports:
```kotlin
import androidx.compose.foundation.Canvas
```

- [ ] **Step 3: Add the confirmation dialog**

In `CountingContent`, after the existing `if (state.showNewTaskDialog) NewTaskDialog(...)`, add:
```kotlin
if (state.showPushConfirmDialog) {
    AlertDialog(
        onDismissRequest = { vm.onPushConfirmDismissed() },
        title = { Text("Push all pending data now?", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Text(
                "${state.pendingTaskCount} task(s) will be sent to the server when Wi-Fi is available. " +
                    "Counting will be locked until upload finishes.",
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            TextButton(onClick = {
                vm.onPushConfirmDismissed()
                vm.onPushButtonConfirmed()
            }) { Text("Yes, push", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = { vm.onPushConfirmDismissed() }) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = FieldInk,
        textContentColor = FieldInkSoft
    )
}
```

- [ ] **Step 4: Place the PushButton in the StatusStrip**

In `StatusStrip`, change the outer `Row` to leave room for a button at the trailing edge. After the TASK pill `Box(...)`, add:
```kotlin
PushButton(
    enabled = state.pendingTaskCount > 0 && !state.isManualPushLocked,
    holdProgress = state.pushHoldProgress,
    onPressStart = { /* set via lambda hoist below */ },
    onPressCancel = { /* set via lambda hoist below */ }
)
```

Pass `onPushPressStart`/`onPushPressCancel` from `CountingContent` into `StatusStrip`. Update `StatusStrip`'s signature:
```kotlin
@Composable
private fun StatusStrip(
    state: CountUiState,
    onPushPressStart: () -> Unit,
    onPushPressCancel: () -> Unit,
    modifier: Modifier = Modifier
)
```

And the call site in `CountingContent`:
```kotlin
StatusStrip(
    state = state,
    onPushPressStart = vm::onPushPressStart,
    onPushPressCancel = vm::onPushPressCancel,
    modifier = Modifier.fillMaxWidth()
)
```

- [ ] **Step 5: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/count/CountScreen.kt app/src/main/java/com/klk/hams/ui/count/CountUiState.kt app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt
git commit -m "feat(push): PushButton with 5s hold + progressive arc border"
```

---

## Task 16: `PushStatusOverlay` (manual-mode sheet)

> **Amendment 2026-05-08 (binding):** Spec §17 rule #3 — Cancel is UI-only. Update the overlay copy and the call wiring:
> - The `Cancel` button label stays as **Cancel**, but its handler is `vm::dismissManualPushOverlay` (not the old `cancelManualPush`).
> - When `state is PushUiState.PendingWifi`, the body text becomes: *"${state.pendingTasks} task(s) queued. Will push as soon as Wi-Fi connects. **Cancel only closes this view; uploads continue in the background.**"*
> - The `Cancel` button is shown for **both** `PendingWifi` and `Pushing` (no longer hidden during `Pushing`) — Cancel is now safe at any phase because it never touches the worker.
> - Bind the dim alpha and counting lock to `state.manualPushActive` directly (drives `state.isManualPushLocked` already in Task 12). When the controller flips `manualPushActive` to false (rule #5 cleared on terminal), the overlay closes naturally.

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt`

- [ ] **Step 1: Add the overlay composable**

After `PushButton`, add:
```kotlin
@Composable
private fun PushStatusOverlay(
    state: PushUiState,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (title, body, action) = when (state) {
        is PushUiState.PendingWifi -> Triple(
            "Waiting for Wi-Fi",
            "${state.pendingTasks} task(s) queued. Will push as soon as Wi-Fi connects.",
            "Cancel" to onCancel
        )
        is PushUiState.Pushing -> Triple(
            "Uploading…",
            "Sending event ${state.done + 1} of ${state.total}.",
            null
        )
        is PushUiState.Completed -> Triple(
            "Upload complete",
            "${state.tasks} task(s) uploaded.",
            "OK" to onAcknowledge
        )
        is PushUiState.Failed -> Triple(
            "Upload paused",
            "${state.reason}. ${state.pending} task(s) remain in cache. Try again later or tap Retry.",
            "Close" to onAcknowledge
        )
        is PushUiState.Idle -> return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, FieldHairline)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = FieldInk)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = FieldInkSoft)
            if (state is PushUiState.Pushing && state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = FieldForest,
                    trackColor = FieldHairline
                )
            }
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = action.second) {
                    Text(action.first, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Render it in CountingContent when manual mode is active**

In `CountingContent`'s outer `Box`, after the main `Column` and before its closing brace, add:
```kotlin
if (state.manualPushActive) {
    PushStatusOverlay(
        state = state.pushUiState,
        onCancel = vm::cancelManualPush,
        onAcknowledge = vm::acknowledgePushOutcome,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
    )
}
```

- [ ] **Step 3: Apply UI dimming when locked**

Wrap the existing main `Column` content with a `Modifier.alpha(...)` based on lock state:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 16.dp)
        .alpha(if (state.isManualPushLocked) 0.4f else 1f),
    ...
)
```

Add import:
```kotlin
import androidx.compose.ui.draw.alpha
```

The `enabled` check on +/− buttons already comes from `state.canIncrement` / `state.canDecrement`, both of which now include `!isManualPushLocked` (Task 12).

- [ ] **Step 4: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/count/CountScreen.kt
git commit -m "feat(push): PushStatusOverlay sheet + UI dim when manual-locked"
```

---

## Task 17: Final build, lint, test sweep

**Files:** `app/src/test/java/com/klk/hams/ui/count/CountViewModelTest.kt` (new, added below) — otherwise verification only.

- [ ] **Step 0: Add the deferred VM flag-clear unit test**

Create `app/src/test/java/com/klk/hams/ui/count/CountViewModelTest.kt` with a focused JVM test of the Task 13 amendment logic: feed a sequence of `PushUiState` values through whatever the VM exposes to drive `manualPushActive`, and assert the flag flips `false` on the terminal states (`Completed`, `Failed`, `Idle`) while staying as-set on `PendingWifi` / `Pushing`. Since `CountViewModel` is an `AndroidViewModel` and reaches `getApplication<HamsApp>().pushController`, a full instantiation needs a fake. Cleanest: extract the flag-clear decision into a pure helper on `CountViewModel.Companion` (e.g. `fun shouldClearManualLock(state: PushUiState): Boolean = state is PushUiState.Completed || state is PushUiState.Failed || state is PushUiState.Idle`) during Task 17, refactor the `uiStateFlow.collect` block to call it, and unit-test the helper directly — mirrors the `PushController.shouldEnqueueManual` pattern from Task 8. If that refactor is too invasive at sweep time, document why and skip (the logic is simple enough to verify by inspection). Run:
```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.ui.count.CountViewModelTest" --no-daemon 2>&1 | tail -10
```

- [ ] **Step 1: Full clean build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: All unit tests**

```bash
./gradlew.bat :app:testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. New tests in `PushUiStateTest`, `PushControllerTest`, `PushNotifierTest` all pass alongside existing suite.

- [ ] **Step 3: Lint**

```bash
./gradlew.bat :app:lintDebug --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. Review `app/build/reports/lint-results-debug.html` for new warnings; address only those introduced by Task 2.8.

- [ ] **Step 4: Install on device and run the manual test plan from Spec §15**

```bash
./gradlew.bat :app:installDebug
adb logcat -c && adb logcat -s HAMS_UI HAMS_PUSH WM-WorkerWrapper
```

Walk through the 7 manual test scenarios. Capture logs + DB snapshots after each.

- [ ] **Step 5: Update spec status**

Edit `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md` first line:
```
> **Status:** Implemented 2026-05-08, pending field verification.
```

- [ ] **Step 6: Update plans/phase2_ips_push.md Task 2.8 status**

Change the 2.8 entry from `⏭ NOT STARTED` to `🟡 IMPLEMENTED — pending field verification`.

- [ ] **Step 7: Final commit**

```bash
git add docs/superpowers/specs/2026-05-08-push-and-wifi-design.md plans/phase2_ips_push.md
git commit -m "docs(push): mark Task 2.8 implemented, pending verification"
```

> **Note (amendment 2026-05-13):** Task 17 is the final gate — run it AFTER Tasks 0b, 10b, 12–16, AND 18–20 below. If executing tasks out of order, defer Task 17's verification sweep until everything else is merged.

---

## Task 18: Hold-to-repeat on +/− buttons

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt`
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt` (only if a repeat-loop helper is cleaner there; the loop can also live in the composable driving `vm::onPlus` / `vm::onMinus`)

**Field feedback (2026-05-13):** workers want continuous +/− while holding, not one-tap-per-cut for big corrections.

**Behaviour (binding):**
- Press-and-hold `+` or `−` → after a **400 ms** initial delay, auto-fire at **5 events/sec** (200 ms interval) until release.
- A single quick tap still fires exactly once (the 400 ms delay means a tap never triggers the repeat).
- Each auto-fire goes through the existing `onPlus()` / `onMinus()` path — same GPS-gate check, same Room write. If `canIncrement` / `canDecrement` becomes false mid-hold (GPS went stale, hit `MAX_COUNT_PER_TASK`, or `isManualPushLocked`), the repeat loop stops immediately.
- Flat rate, not accelerating (predictable for a field instrument).

- [ ] **Step 1: Wrap the +/− buttons with a press-hold gesture**

In `CountScreen.kt`, the `+` and `−` `Button`s (currently `Button(onClick = ...)`): replace the click handling with a `Box`/`Surface` carrying:
```kotlin
.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    detectTapGestures(
        onPress = {
            val firedOnce = onAction()           // immediate single fire
            val held = tryAwaitRelease()         // suspends until release/cancel
            // (held == true means a normal release; we don't need the value —
            //  the repeat loop below is cancelled either way when this scope ends)
        }
    )
}
```
Then add the repeat loop. The cleanest shape: launch the loop inside `onPress` before `tryAwaitRelease`, cancel it after:
```kotlin
onPress = {
    onAction()                                   // fire #1 (the tap)
    val repeatJob = scope.launch {
        delay(400)
        while (isActive) {
            onAction()
            delay(200)                           // 5/sec
        }
    }
    try { tryAwaitRelease() } finally { repeatJob.cancel() }
}
```
where `scope` is a `rememberCoroutineScope()` and `onAction` is `vm::onPlus` (or `onMinus`), and `enabled` is `state.canIncrement` (or `canDecrement`). The loop re-checks nothing itself — `onPlus`/`onMinus` are already no-ops when their gate is false, so a stale-GPS mid-hold just stops mutating the count; optionally break the loop when `!enabled` to stop the wasted iterations.

- [ ] **Step 2: Keep the button visuals**

Preserve the existing button shape/size/colour — only the gesture handling changes. The `+`/`−` glyphs and the equal-weight action row layout stay as-is.

- [ ] **Step 3: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`. No new lint warnings.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/count/CountScreen.kt app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt
git commit -m "feat(ui): hold-to-repeat on +/- buttons (5/sec, 400ms delay)"
```

No unit test — gesture timing isn't JVM-testable. The existing implicit guarantee (`onPlus()` called N times → count +N) already covers the mutation side.

---

## Task 19: Notification re-grant — floating chip + panel

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountUiState.kt`
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt`
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt`
- Modify: `app/src/main/java/com/klk/hams/MainActivity.kt`

**Supersedes:** the auto-memory note `project_notification_permission_flow.md`'s "manual-activation UI pending" item. (Also see that note for the related rework: move the initial `POST_NOTIFICATIONS` prompt to fire AFTER the location gate — do that as part of this task.)

**Behaviour (binding):**
- On launch, the notification prompt fires **only after** the location gate passes (location first; if location denied → app terminates as before; if notification denied → app continues, worker just won't see push-status notifications).
- If `POST_NOTIFICATIONS` is not granted, show a **small floating chip** (a ⚠/bell-slash icon) overlaid near the NEW TASK bar. The chip is **draggable** — the worker can move it out of the way (`Modifier.offset { … }` driven by `detectDragGestures`, offset persisted in `CountUiState` so it survives recomposition).
- Tap the chip → a small panel/card: *"Notifications are off — you won't see upload status. Turn them on?"* with two buttons:
  - **Ignore** → close the panel; chip stays.
  - **Grant access** → if `shouldShowRequestPermissionRationale(POST_NOTIFICATIONS)` is true → call the permission launcher; else (permanently denied) → open `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE`. The VM can't do either — it returns a sealed `NotificationGrantAction` (`{ RequestRuntime, OpenSettings }`) that `MainActivity` acts on.
- On `onResume`, re-check the permission. If now granted → hide the panel, hide the chip, restore the NEW TASK bar to its original full shape (no layout shift if the chip was a pure overlay, which is the point of the floating design).

**Fallback design (if the draggable chip conflicts with another gesture handler on the count screen):** a static alert icon inline to the left of the NEW TASK bar, shrinking the bar slightly. Document which design shipped in the commit message.

- [ ] **Step 1: CountUiState fields**

Add:
```kotlin
val notificationPermissionDenied: Boolean = false,
val showNotificationPanel: Boolean = false,
val notificationChipOffsetX: Float = 0f,   // dp, persisted drag offset
val notificationChipOffsetY: Float = 0f,
```

- [ ] **Step 2: CountViewModel**

Add (sketch):
```kotlin
sealed interface NotificationGrantAction { object RequestRuntime : NotificationGrantAction; object OpenSettings : NotificationGrantAction }

fun setNotificationPermissionDenied(denied: Boolean) { _uiState.update { it.copy(notificationPermissionDenied = denied, showNotificationPanel = if (!denied) false else it.showNotificationPanel) } }
fun onNotificationChipClicked() { _uiState.update { it.copy(showNotificationPanel = true) } }
fun onNotificationPanelIgnore() { _uiState.update { it.copy(showNotificationPanel = false) } }
fun onNotificationChipDragged(dx: Float, dy: Float) { _uiState.update { it.copy(notificationChipOffsetX = it.notificationChipOffsetX + dx, notificationChipOffsetY = it.notificationChipOffsetY + dy) } }
// MainActivity decides RequestRuntime vs OpenSettings based on shouldShowRequestPermissionRationale; VM just closes the panel after the action is dispatched.
fun onNotificationGrantDispatched() { _uiState.update { it.copy(showNotificationPanel = false) } }
```

- [ ] **Step 3: MainActivity**

- Move `requestNotificationPermissionIfNeeded()` so it runs from the `onGatePassed` callback (after location), not unconditionally in `onCreate`.
- In `onResume`, compute `ContextCompat.checkSelfPermission(POST_NOTIFICATIONS) == GRANTED` (only on API 33+; on lower, always treat as granted) and call `vm.setNotificationPermissionDenied(!granted)`.
- Provide a callback to `CountScreen` for "grant access": if permanently denied → start `Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)`; else → `notificationPermissionLauncher.launch(POST_NOTIFICATIONS)`. After launching either, call `vm.onNotificationGrantDispatched()`.

- [ ] **Step 4: CountScreen**

- When `state.notificationPermissionDenied`, render a small floating `Surface` (icon button) at `Modifier.offset { IntOffset(state.notificationChipOffsetX.dp.roundToPx() + base, state.notificationChipOffsetY.dp.roundToPx() + base) }` with `.pointerInput { detectDragGestures { change, drag -> change.consume(); vm.onNotificationChipDragged(drag.x.toDp().value, drag.y.toDp().value) } }` and a tap handler → `vm.onNotificationChipClicked()`. Base position: near the NEW TASK bar's top-right.
- When `state.showNotificationPanel`, render an `AlertDialog` (or a small `Card`) with the copy + Ignore / Grant access buttons wired to `vm.onNotificationPanelIgnore()` and the grant callback.
- NEW TASK bar layout is unchanged (the chip is a pure overlay) — this is why the floating design is preferred.

- [ ] **Step 5: Build**

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/count/CountUiState.kt app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt app/src/main/java/com/klk/hams/ui/count/CountScreen.kt app/src/main/java/com/klk/hams/MainActivity.kt
git commit -m "feat(ui): notification re-grant floating chip + panel; notification prompt now after location gate"
```

After this lands, delete the auto-memory note `project_notification_permission_flow.md` (its content is now implemented) — or update it to point at this task as the implementation.

---

## Task 20: GPS lock hysteresis + foreground HIGH_ACCURACY + mid-session location-off handling

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/location/LocationStream.kt`
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt` (`recomputeGpsLock` + `staleTick`)
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountUiState.kt` (new `GpsLockState` variant)
- Modify: `app/src/main/java/com/klk/hams/ui/count/CountScreen.kt` (blocking overlay for location-off)
- Modify: `app/src/main/java/com/klk/hams/MainActivity.kt` (permission-revoked-while-running fallback)
- Modify: `app/src/main/java/com/klk/hams/AppConfig.kt` (hysteresis thresholds)

**Field feedback (2026-05-13):** GPS pill flickers yellow↔green rapidly; the `+` button keeps disabling/enabling — unusable for workers. Also need graceful handling if the worker toggles location off from quick settings mid-session.

### 20.1 Hysteresis (fixes the flicker)

Two thresholds instead of one. Add to `AppConfig`:
```kotlin
// GPS lock-state hysteresis (Task 20). Going Stale needs a real gap; returning
// to Locked is quick. Both must be < LOCATION_STREAM_STALENESS_MS's old single value.
const val GPS_LOCK_STALE_AFTER_MS: Long = 8_000   // age above this -> Stale (yellow)
const val GPS_LOCK_RELOCK_BELOW_MS: Long = 3_000  // age below this -> Locked (green)
```
In `recomputeGpsLock()`: if current state is `Locked`, only flip to `Stale` when `age > GPS_LOCK_STALE_AFTER_MS`. If current state is `Stale`/`Acquiring`, flip to `Locked` when `age < GPS_LOCK_RELOCK_BELOW_MS`. Ages between the two thresholds → keep the current state (no flip). Keep `LOCATION_STREAM_STALENESS_MS` as the press-gate freshness check (the `+` button still requires a fix fresher than that), or align the press gate to `GPS_LOCK_STALE_AFTER_MS` — pick one and document; recommend the press gate uses `GPS_LOCK_STALE_AFTER_MS` so "pill is green" ⇔ "button works".

### 20.2 Foreground HIGH_ACCURACY (makes gaps rare)

In `LocationStream`, treat the `"foreground"` ref-count reason as also warranting `PRIORITY_HIGH_ACCURACY` (currently only `"task_active"` does; `"foreground"` alone falls back to `BALANCED`, which coalesces fixes and causes the gaps). Net effect: while the screen is on, fixes arrive every 1–2 s, age never approaches 8 s, pill stays solid green. Battery cost while screen-on is negligible (screen dominates). When neither reason is held (app fully backgrounded with no active task) the stream can stop entirely as before.

### 20.3 Mid-session location-OFF detection

- Add a `GpsLockState.LocationDisabled` variant (or a `locationServicesOff: Boolean` flag on `CountUiState`).
- In the existing 1-second `staleTick`: also call `(getSystemService(LocationManager::class.java)).isLocationEnabled`. If false → set `GpsLockState.LocationDisabled`.
- `CountScreen` reacts to `LocationDisabled` with a **blocking overlay** (dim + intercept): "GPS is turned off. Recording is paused. Turn GPS back on to continue." + a **"Open location settings"** button → `startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))`. `+`/`−`/NEW TASK disabled while shown.
- When `isLocationEnabled` flips back true on a later tick → clear the variant, pill goes Acquiring→Locked, counting resumes. **App does NOT close.**

### 20.4 Permission-revoked-while-running fallback

If `ACCESS_FINE_LOCATION` is revoked while the app runs (rare; Android may kill the process, but if it survives): on `onResume`, re-check `ContextCompat.checkSelfPermission(ACCESS_FINE_LOCATION)`. If denied → fall back to the launch-gate behaviour (blocking message → `finishAffinity()`), consistent with CLAUDE.md's "GPS is mandatory" rule. This is distinct from 20.3 (services toggled off ≠ permission revoked).

- [ ] **Step 1:** Add the two hysteresis constants to `AppConfig`.
- [ ] **Step 2:** Rewrite `recomputeGpsLock()` with the two-threshold logic (current-state-aware). Add a JVM unit test in `CountViewModelTest` (or a new `GpsLockHysteresisTest`) feeding a sequence of ages and asserting the transitions: e.g. `[1s, 9s, 4s, 9s, 9s, 2s]` from `Locked` → stays `Locked` at 9s only if a prior tick already went `Stale`? No — first 9s flips to `Stale`; 4s flips back to `Locked`; etc. Pin the exact expected sequence in the test.
- [ ] **Step 3:** In `LocationStream`, make `"foreground"` request HIGH_ACCURACY.
- [ ] **Step 4:** Add `GpsLockState.LocationDisabled`; wire the `isLocationEnabled` check into `staleTick`; render the blocking overlay in `CountScreen`; wire the "Open location settings" intent.
- [ ] **Step 5:** Add the `onResume` permission-revoked re-check + fallback-to-close in `MainActivity`.
- [ ] **Step 6:** Build + run the hysteresis unit test.

```bash
./gradlew.bat :app:assembleDebug --no-daemon 2>&1 | tail -5
./gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.ui.count.*GpsLock*" --no-daemon 2>&1 | tail -10
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/klk/hams/AppConfig.kt app/src/main/java/com/klk/hams/data/location/LocationStream.kt app/src/main/java/com/klk/hams/ui/count/CountViewModel.kt app/src/main/java/com/klk/hams/ui/count/CountUiState.kt app/src/main/java/com/klk/hams/ui/count/CountScreen.kt app/src/main/java/com/klk/hams/MainActivity.kt app/src/test/java/com/klk/hams/ui/count/GpsLockHysteresisTest.kt
git commit -m "feat(gps): lock hysteresis + foreground HIGH_ACCURACY + mid-session location-off handling"
```

> **Note:** if Task 20 conflicts with the `LocationStream` priority logic from Task 2.7.5 (which already does dynamic priority), reconcile by reading the current `LocationStream` first — Task 20's change is "add `foreground` to the HIGH_ACCURACY set", not a rewrite.

---

## Self-Review

### Spec coverage check

| Spec section | Implemented in Task |
|---|---|
| §2 WorkManager constraint | 1 (dep), 8 (controller), 9 (worker) |
| §3 Architecture diagram | 8, 9, 10, 11 |
| §4 Pending-only push target | 3, 4, 9, 11 |
| §4.1 Active-task rollover guarantee (1 s tick) | 13 |
| §5 PushState machine | 5 (PushUiState), 8 (mapping) |
| §6 Auto-push flow | 11 (enqueue on save), 9 (worker), 7 (notifier) |
| §7 Manual-push flow | 14, 15, 16 |
| §8 Pending-count badge | 14 |
| §9 Notifications | 7 (notifier), 9 (worker setForeground), 10 (channel) |
| §10 Heartbeat 1 min | 2 |
| §11 Files affected | matches implementation tasks 1–16 |
| §12 AppConfig diff | 2 |
| §14 Acceptance criteria | covered by 17 manual test plan |

No gaps.

### Placeholder scan

- No "TBD" / "TODO" / "implement later" in any task body.
- No "similar to Task N" — every task is self-contained.
- All test code is concrete.

### Type consistency

- `PushUiState` defined in Task 5; used by Tasks 7, 8, 12, 13, 16.
- `PushController.mapToUiState` signature matches between Task 8 test and Task 8 implementation.
- `PushController.WORK_NAME` / tags / progress keys defined once in Task 8 companion object.
- `repository.onTaskFinalized` typed as `(() -> Unit)?` consistently in Tasks 11.
- `state.isManualPushLocked` / `manualPushActive` / `pushHoldProgress` / `showPushConfirmDialog` all introduced in their tasks and referenced consistently.

### Scope check

Single, focused implementation plan. No decomposition needed.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-08-push-trigger-impl.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Best for this plan because each task has clean test/build verification points, and subagents can't poison context across tasks.

2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints. Lower overhead per task but uses more of this session's token budget.

Which approach?
