# 2026-05-22 — Push Reliability Upgrade (background-service push monitor)

**Repo:** `HAMS_task_recorder` · branch `phase/push-reliability` (off `main`)
**Status:** design approved 2026-05-22

---

## 1. Problem

The Phase 2.8 push trigger is a WorkManager `OneTimeWorkRequest` with an
`UNMETERED` network constraint. The design assumed it fires when Wi-Fi
appears even with the app closed. On aggressive-OEM devices (Honor Magic OS,
Oppo ColorOS, Xiaomi MIUI) the OS **purges WorkManager's constraint-deferred
jobs** after the app idles. Field-observed: a worker creates tasks offline,
1–2 h pass, Wi-Fi connects — nothing pushes until the app is manually
reopened (which re-arms the job via the Task 10b recovery path).

Force-stop wipes the WorkManager queue entirely; that case is unrecoverable
by any design (Android behaviour) and is an accepted limitation.

## 2. Settled decisions (pre-design, not re-litigated)

1. **Keep WorkManager** `OneTimeWorkRequest` as the cold-start (process-dead)
   fallback.
2. **Type `HamsForegroundService` as `location`** — no 6 h/24 h cap (unlike
   `dataSync`); the service genuinely keeps GPS alive for counting. Manifest
   already declares `FOREGROUND_SERVICE_LOCATION`.
3. **Service stays alive while `pending > 0`** (or a task is active).
4. **Monitor always armed** while `pending > 0` — not gated on the manual
   button. The bug is auto-push failing; gating the fix on a button press
   would not fix it.
5. **Approach A1** — the service *triggers*; WorkManager still *runs* the
   push. The service's Wi-Fi callback calls `pushController.enqueueAuto()`;
   `PushEngine`/`PushWorker` are unchanged. The unreliability was
   *deferred-job purging* — A1 sidesteps it by enqueueing only when Wi-Fi is
   already connected, so there is no long-lived deferred job to purge.

## 3. Components

| Component | Change |
|---|---|
| `service/HamsForegroundService.kt` | Retyped `location`. Dual-mode (counting + push-monitor). New lifecycle + stop logic. Owns a `WifiPushMonitor`. |
| `service/WifiPushMonitor.kt` *(new)* | Wraps `ConnectivityManager.registerNetworkCallback`. On validated unmetered Wi-Fi `onAvailable` → invokes a callback. |
| `push/PushController.kt` | `acknowledgeCompletion()` also cancels the terminal notification (id 2002). |
| `HamsApp.kt` | `onCreate`: if `pending > 0`, start the service (cold-start recovery), alongside the existing `enqueueAuto` Task 10b call. |
| `MainActivity.kt` | Starts the service on GPS-gate-pass — unchanged. |
| `ui/count/CountScreen.kt` + `CountViewModel.kt` | Manual overlay reflects waiting→pushing→success; OK clears the in-app panel **and** notification 2002. |
| `AndroidManifest.xml` | `HamsForegroundService` `foregroundServiceType` `dataSync` → `location`. |
| `push/PushEngine.kt`, `push/PushWorker.kt` | **Unchanged** (A1 keeps the existing push code path). |

## 4. Service — dual mode

The service is alive if **either** condition holds:

- **Counting mode** — a task is active. Holds `LocationStream(REASON_TASK_ACTIVE)`
  and runs `HeartbeatScheduler`.
- **Push-monitor mode** — `pending > 0`. Holds `WifiPushMonitor`.

The service observes the active-task flow (`repository.observeActiveTask()`)
and `repository.observePendingTaskCount()`.

**GPS + heartbeat run only in counting mode.** Currently they run for the
service's entire life; under the upgrade the service can be long-lived as a
pure push-monitor, and holding `HIGH_ACCURACY` GPS + a 1-min heartbeat with
no active task would drain battery. So: start `LocationStream(TASK_ACTIVE)` +
heartbeat when a task becomes active; stop them when no task is active.

`WifiPushMonitor` is cheap (a registered callback, no polling) — keep it
registered for the whole service life.

**Stop condition:** `stopSelf()` when **no active task AND `pending == 0`**.

**`onTaskRemoved` (app swiped away):** save the active task synchronously
(as today); then — if `pending > 0`, **do not** `stopSelf()` (the service
continues as a pure push-monitor); if `pending == 0`, `stopSelf()`. This is
the core fix: the service survives the swipe and keeps watching Wi-Fi.

A pure decision helper `shouldStopService(hasActiveTask: Boolean,
pendingCount: Int): Boolean` is extracted so the stop logic is unit-testable
without Android.

## 5. Trigger flow

```
WifiPushMonitor.onAvailable (validated, unmetered, Wi-Fi)
  → HamsForegroundService → pushController.enqueueAuto()
  → WorkManager runs PushWorker immediately (constraint already satisfied)
  → PushEngine drains pending tasks (unchanged)
```

WorkManager's own `UNMETERED`-constrained request still exists as the
cold-start fallback (process was fully dead, no service running).

## 6. Notification choreography — one ongoing + one terminal

| Notification | id | Role |
|---|---|---|
| Service / lifecycle | `1001` | Single ongoing notification. Text follows state: `"Recording counts"` (counting) → `"N tasks waiting for Wi-Fi"` (pending, idle) → `"Uploading X of N"` (pushing). The service updates it by observing `PushController.uiStateFlow`. |
| Terminal banner | `2002` | Dismissable success/failure banner (unchanged from the 2026-05-15 notification work). |
| Worker FGS | `2001` | Used by `PushWorker.setForeground` **only in the cold-start case** — when no service is alive. When the service is alive the worker skips `setForeground`, so 1001 is the single visible ongoing notification (no 1001/2001 double-notification). |

`PushWorker` checks whether `HamsForegroundService` is running; if so it does
not call `setForeground`. The service's process is already foreground (it is
a foreground service), so the worker running in that process is protected
without its own foreground notification.

Net: **waiting → progress is one evolving notification** (1001); the terminal
is the dismissable banner (2002).

## 7. In-app overlay — manual only

The dim + counting-lock overlay (`PushStatusOverlay`, gated on
`manualPushActive`) stays **manual-triggered only**. Auto-push must NOT dim
the screen or lock counting while the worker is harvesting — it is silent
in-app, notification-only.

The manual overlay reflects the full sequence: `PendingWifi` (waiting panel)
→ `Pushing` (progress bar) → `Completed` (success + OK button) / `Failed`
(reason + Close). The OK/Close action calls a controller method that:

1. clears the in-app overlay (`manualPushActive = false`, existing behaviour),
2. resets `_completedAt` (existing `acknowledgeCompletion`),
3. **also cancels notification 2002** (new).

## 8. Error handling

- **Wi-Fi flap mid-push:** `PushEngine` already retries with backoff; on
  give-up the terminal reads "X of N · will retry on Wi-Fi". When Wi-Fi
  returns, `WifiPushMonitor.onAvailable` fires again → re-triggers. No new
  code.
- **Trigger dedup:** every trigger (service callback, manual button, the
  Phase-3.3 chain re-enqueue, cold-start recovery) funnels through
  `enqueueAuto` (`ExistingWorkPolicy.KEEP`) or `triggerManual` (the Spec §17
  cooperation contract). A flapping Wi-Fi firing `onAvailable` repeatedly is
  repeated `enqueueAuto(KEEP)` — deduped by WorkManager. No new dedup
  mechanism is introduced.
- **Service start races:** `startForegroundService` is idempotent for our
  purposes — a second start while the service runs just re-delivers
  `onStartCommand`. The service guards re-entry of monitor registration.

## 9. Implementation phases (each independently testable)

The plan is split into 4 phases so each can be verified on-device before the
next begins.

### Phase 1 — Service dual-mode lifecycle + `location` retype
Retype the service `location`. Make it dual-mode: GPS/heartbeat only while a
task is active; `WifiPushMonitor` not added yet (stub or skip). New stop
logic + `onTaskRemoved` change. Extract `shouldStopService`.
**Test:** swipe the app away with pending tasks → service stays alive
(`adb shell dumpsys activity services com.klk.hams`); swipe with zero pending
→ service stops. GPS/heartbeat stop when no task active.

### Phase 2 — `WifiPushMonitor` + trigger + cold-start service start
Add `WifiPushMonitor`; wire `onAvailable → enqueueAuto`. `HamsApp.onCreate`
starts the service when `pending > 0`.
**Test (the core bug fix):** create tasks offline, swipe the app away, wait,
connect Wi-Fi → push fires **without reopening the app**.

### Phase 3 — Notification choreography
Service notification (1001) reflects waiting/progress by observing
`uiStateFlow`. `PushWorker` skips `setForeground` when the service is alive.
**Test:** observe one evolving notification waiting → progress; terminal
banner at 2002; no double-notification.

### Phase 4 — In-app overlay + OK-clears-notification
Manual overlay reflects the sequence; OK/Close cancels notification 2002.
**Test:** manual push → waiting panel + waiting notification together →
progress in both → success panel with OK → OK clears panel and banner.

## 10. Testing summary

- **JVM unit:** `WifiPushMonitor` capability-match → callback mapping (fake
  `ConnectivityManager`); `shouldStopService` truth table.
- **Instrumented:** service survives `onTaskRemoved` with `pending > 0`,
  stops at `pending == 0`.
- **Manual field (Honor X9b):** the Phase 2 core-fix scenario above; the
  Phase 4 manual-overlay flow.

## 11. Non-goals

- No change to `PushEngine` / `PushWorker` push logic, the IPS protocol, or
  the SQLite schema.
- No new notification for auto-push beyond the service's own 1001 — auto-push
  stays silent in-app.
- Force-stop recovery is not solved (Android does not allow it) — unchanged.
- No user-facing setting for any of this.
