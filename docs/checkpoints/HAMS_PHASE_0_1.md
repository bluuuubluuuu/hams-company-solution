# HAMS Phase 0 + Phase 1 Checkpoint

**Date:** 2026-04-28
**Author:** WYH / Codex / Claude Code sessions
**Status:** Phase 0 COMPLETE. Phase 1 COMPLETE after manual emulator verification.

---

## Phase 0 - Complete

All Phase 0 acceptance criteria passed.

| Item | Outcome |
|---|---|
| `.\gradlew.bat :app:assembleDebug` | `BUILD SUCCESSFUL` |
| compileSdk mismatch | Fixed by downgrading `coreKtx` 1.17.0 -> 1.15.0 and `activityCompose` 1.11.0 -> 1.10.1. `compileSdk=35` kept. |
| `BuildConfig` wiring | 4 fields: `WIALON_TOKEN` (String), `IPS_HOST` (String), `IPS_PORT` (int), `DEVICE_UNIQUE_ID` (String) |
| `debug` build type | Added with `applicationIdSuffix=".debug"`, `versionNameSuffix="-debug"` |
| Manifest permissions | INTERNET, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK |
| `AppConfig.kt` | Created as single reader of `BuildConfig`; holds event codes and operational constants |
| `local.properties` in git | Not tracked |

---

## Phase 1 - Complete

Manual emulator verification passed on 2026-04-28.

### Implemented Files

| File | Description |
|---|---|
| `data/model/Task.kt` | `@Entity(tableName="tasks")`; full V6 task column set |
| `data/model/EventEntity.kt` | `@Entity(tableName="events")`; full V6 event columns including `event_code`, `battery_pct`, `hdop`, `satellites`, `work_count` |
| `data/db/TaskDao.kt` | active-task query, Flow observation, MYT-day max seq query, count updates, finalise task |
| `data/db/EventDao.kt` | event insert, pending-event query, mark-pushed support for Phase 2 |
| `data/db/AppDatabase.kt` | Room DB, version 1, `exportSchema=false` |
| `data/repository/TaskRepository.kt` | lazy task creation, plus/minus recording, manual/auto save, next task seq |
| `data/location/LocationProvider.kt` | Phase 1 lazy fetch — cached ≤5s or fresh 2s location; freshness uses Android elapsed realtime. **Superseded in Phase 2.7.5** by `LocationStream` (continuous BALANCED stream + synchronous press path); see `docs/superpowers/specs/2026-05-05-gps-streaming-design.md`. |
| `time/Clock.kt` | UTC ISO timestamp source |
| `ui/count/CountUiState.kt` | count, task labels, GPS/battery/dialog/progress state |
| `ui/count/BatteryMonitor.kt` | battery BroadcastReceiver wrapper |
| `ui/count/CountViewModel.kt` | plus/minus/New Task flow, GPS wait behavior, save confirmation |
| `ui/count/CountScreen.kt` | Compose UI, permission state machine, GPS gate, counter, buttons, Surface-based 5s New Task hold target |
| `service/HamsForegroundService.kt` | `dataSync` foreground service; synchronous `onTaskRemoved()` save |
| `HamsApp.kt` | Application class, lazy Room/repository singletons, notification channel |
| `MainActivity.kt` | Hosts `CountScreen`; starts service after GPS gate passes |
| `AndroidManifest.xml` | Declares `HamsApp`, permissions, and `HamsForegroundService` |

### Confirmed Behavior

| Behavior | Result |
|---|---|
| Lazy task creation | PASS: no task row on open; first valid `+` creates task + 281 + 279 in one transaction |
| MYT daily task numbering | PASS: `task_seq` is computed per `Asia/Kuala_Lumpur` day |
| GPS permission denied | PASS: blocking message then app closes |
| GPS permission granted + location enabled | PASS: app remains open |
| No fresh GPS fix | PASS: `+` rejected, count unchanged, "Waiting for GPS fix..." shown |
| Fresh GPS fix + first `+` | PASS: Task #1 created and count increments |
| Plus/minus counting | PASS: count increments/decrements and never goes below zero |
| New Task at count 0 | PASS: no durable zero-count task created; no save |
| New Task 5-second hold at count > 0 | PASS: progress/dialog/save/reset verified |
| Next task after save | PASS: next `+` creates the next task number |
| Swipe/kill auto-save | PASS: active counted task saved through foreground service path |
| Phase 1 scope boundary | PASS: no networking / IPS push implementation in Phase 1 source |

### Bugs Found And Fixed During Manual Testing

| Issue | Root cause | Fix |
|---|---|---|
| App closed immediately after permission grant | Boolean GPS gate rendered the "location off" branch before provider check completed | Replaced with explicit `LocationGateState` state machine |
| Fake GPS worked only briefly / stale GPS rejected | Freshness used wall-clock `Location.time`, which is unreliable for mock/emulator locations | Switched freshness to `SystemClock.elapsedRealtime()` / `Location.elapsedRealtimeNanos` |
| New Task 5-second hold did nothing | `OutlinedButton` gesture handling competed with custom `pointerInput` | Replaced with `Surface` styled like an outlined button and attached `pointerInput` directly |

### Emulator GPS Test Note

The Pixel 5 API 36 emulator did not reliably accept `adb emu geo fix` as a fresh Fused/GPS fix. Manual testing used a shell mock-location provider refreshed every 2 seconds:

```powershell
adb shell appops set shell android:mock_location allow
adb shell appops set 2000 android:mock_location allow
adb shell cmd location providers add-test-provider gps --requiresSatellite --supportsAltitude --supportsSpeed --supportsBearing --powerRequirement 3
adb shell cmd location providers set-test-provider-enabled gps true

while ($true) {
  $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  adb shell cmd location providers set-test-provider-location gps --location 2.268721,103.282985 --accuracy 5 --time $now
  Start-Sleep -Seconds 2
}
```

This loop is for emulator testing only. Real devices should receive hardware GPS updates normally.

---

## Known Follow-Ups

| Item | Detail |
|---|---|
| Runtime event-code config | `EVENT_CODE_PLUS/MINUS` are currently compile-time constants in `AppConfig`. Before production cutover from 279/280 to 179/180, add a runtime override path. |
| Retention constant | **Resolved 2026-05-15:** bumped `SQLITE_RETENTION_DAYS` 7 → 30 to align with FR-06's 30-day offline capacity. The new `TaskRepository.purgeStaleTerminalTasks` sweep (Codex finding fix) only deletes `uploaded`/`failed`/`discarded` rows older than the window; `active` and `pending` are never purged regardless of age. See CLAUDE.md "Retention sweep" subsection. |
| BuildConfig escaping | `buildConfigField` string values need escaping for quotes/backslashes/newlines before arbitrary supervisor-supplied values are allowed. |
| Continuous GPS warm-up | Phase 1 is accepted with on-demand Fused location. Before production polish, consider keeping a foreground/latest-fix listener active while app is open to reduce waiting under canopy/tree cover. |

---

## Next Actions For CC

1. Read `CLAUDE.md`, this checkpoint, and `plans/phase1_core_offline.md`.
2. Review the dirty worktree and separate source/docs/generated local files.
3. Decide the final Phase 0/1 commit boundary with WYH.
4. Start Phase 2 only after Phase 1 changes are reviewed/committed: IPS frame builder, coordinate conversion tests, push eligibility, TCP client, and Wi-Fi-triggered push engine.

---

## Commit Log Reference

| Commit | Message |
|---|---|
| `8332985` | `docs: update CLAUDE.md and CONTEXT.md - init review fixes` |
| `882a60c` | `docs: add Karpathy guidelines note to CLAUDE.md` |
| `9033f6d` | `docs: update CLAUDE.md - scaffold state, build commands, Phase 0 checklist` |
| `6438a7d` | `docs(plans): add phase-by-phase implementation plans (0-4)` |
| *(Phase 0 + Phase 1 changes)* | BuildConfig, manifest permissions, AppConfig, Room schema, repository, location provider, Count UI, foreground service, bug fixes, documentation checkpoint |
