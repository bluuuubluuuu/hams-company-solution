# CC Handoff - Push/Wialon Debug Fixes

> Date: 2026-05-11
> Purpose: standalone note for Claude Code / CC to read later.
> Scope: records what was fixed and what still needs design thinking. This file intentionally does not update the existing plans/spec notes.

## Why This Exists

Task 2.8 push work reached real-device testing. Several issues were found and fixed during field debugging. The active plan/spec markdown files already contain many planned tasks, so this note is a separate handoff for later CC analysis instead of another edit to the current plan documents.

## Current Confirmed State

- App no longer crashes on save after the Android 14 foreground-service fixes.
- Runtime notification permission was added and granted on the test device.
- WorkManager push job schedules correctly.
- When only cellular is active, the push job waits because Task 2.8 intentionally requires `NetworkType.UNMETERED`.
- When validated Wi-Fi is active, the push can reach Wialon.
- Wialon showed at least one received message:
  - Time: `2026-05-08 14:50:45`
  - Location: `4.599785, 101.078093333`
  - Params: `hdop=20, ffb_cut=1, battery=75, event_code=179, work_count=2`

This confirms the app-to-Wialon path can work when the login unique ID matches a Wialon unit.

## Issues Fixed

### 1. Android 14+ foreground-service crash

Symptoms:
- App quit after saving a task.
- App sometimes could not reopen.
- Dropbox logs showed foreground-service type exceptions.

Fixes committed:
- `1b6dbe2 fix(push): Android 14 foreground service type crashes`
- `9b1bb24 fix(push): override WorkManager SystemForegroundService FGS type`
- `3ed6faf fix(push): runtime POST_NOTIFICATIONS request + tools:replace override`

Code-level changes:
- `HamsForegroundService` manifest type changed from `dataSync|location` to `dataSync`.
- `PushWorker` now calls the 3-arg `ForegroundInfo(...)` constructor with `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Android 14+.
- Manifest now overrides `androidx.work.impl.foreground.SystemForegroundService` with `android:foregroundServiceType="dataSync"` and `tools:replace="android:foregroundServiceType"`.
- `MainActivity` now requests `POST_NOTIFICATIONS` on Android 13+.

### 2. Push appeared stuck because phone was not on validated unmetered Wi-Fi

Symptoms:
- No push notification.
- No Wialon rows.
- DB rows stayed pending.

Evidence:
- JobScheduler showed the HAMS WorkManager job waiting on network constraints.
- Connectivity dump showed active general internet network was cellular/metered.

Conclusion:
- This was correct behaviour. The worker does not start until Android reports an unmetered validated network. No worker start means no push notification yet.

### 3. Wialon login unique ID mismatch

Symptoms:
- Worker could be scheduled, but Wialon raw messages stayed empty.
- DB rows remained pending.

Evidence from direct phone TCP test:
- `#L#HAMS_TEST_001;NA` returned `#AL#1`
- `#L#OC154_H001;NA` returned `#AL#0`

Conclusion:
- `DEVICE_UNIQUE_ID` is not just a local phone label. It is the Wialon IPS login identity.
- It must exactly match a Wialon Unit's Unique ID.
- For the current test unit:
  - Unit name: `TEST_HAMS_APP_001`
  - Wialon unit id: `601602811`
  - Unique ID: `HAMS_TEST_001`

For testing, `local.properties` should use:

```properties
DEVICE_UNIQUE_ID=HAMS_TEST_001
```

## Meaning of Wialon Fields Observed

Example Wialon row:

```text
hdop=20, ffb_cut=1, battery=75, event_code=179, work_count=2
```

- `event_code=179`: HAMS `+` press.
- `ffb_cut=1`: this row should count as one FFB cut in the V6 report/template.
- `work_count=2`: the app's displayed task count after this event was `2`; it is not a lifetime total.
- `battery=75`: phone battery percentage.
- `hdop=20`: GPS quality was poor/degraded. HAMS currently derives this from Android accuracy using roughly `accuracy / 5`, so `hdop=20` implies about 100m accuracy.

For reporting, Wialon should count rows where `ffb_cut=1`. `work_count` is mainly audit/debug context.

## Open Deployment Design Problem

The current development config uses one build-time `DEVICE_UNIQUE_ID` from `local.properties`. That is not scalable for production because one shared APK needs to support many phones.

Production likely needs:

- One Wialon Unit per phone/app install.
- Unit Unique ID exactly matches the phone/app identity, e.g. `OC154_H001`.
- Same APK for all phones.
- First-launch provisioning flow stores the assigned unique ID locally.
- Push login reads the stored runtime unique ID instead of `BuildConfig.DEVICE_UNIQUE_ID`.

Possible provisioning options for CC to analyze later:

1. Manual entry screen: supervisor types `OC154_H001`.
2. QR activation: scan QR containing the assigned unit unique ID.
3. Admin backend activation: app registers and receives an assigned unique ID.
4. Managed config / MDM: device identity injected during deployment.

The key rule: do not reuse one Wialon Unique ID across many phones unless data from all phones should intentionally merge into one Wialon unit.

## Suggested CC Follow-Up

When CC resumes this topic, please analyze before editing active plan docs:

1. Whether `DEVICE_UNIQUE_ID` should stay build-time for the pilot or move to runtime provisioning before production.
2. Where the runtime identity should be stored (`DataStore` preferred over ad hoc prefs if the project already accepts it; otherwise follow existing local-storage pattern).
3. How to protect against accidental blank/wrong identity:
   - block push until provisioned,
   - show clear app status,
   - maybe add a test-login/debug screen.
4. Whether the Wialon admin workflow can bulk-create units from a CSV and apply one common sensor/report template.
5. Whether Task 0b / battery onboarding and Task 10b / re-enqueue-on-open should be implemented before field rollout.

Do not edit the existing plan/spec markdown solely because this note exists. Treat this as a parking lot and analysis handoff.
