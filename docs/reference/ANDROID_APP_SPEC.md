<!-- DRAFT — production-content review required. -->
# Android Application Specification

This is a code-derived specification of the Android application as it exists in this repository. It does not assert live Wialon, n8n, PostgreSQL, or handset facts that cannot be established from source.

## 1. Product identity and platform

| Item | Current implementation |
|---|---|
| Application | HAMS Task Recorder |
| Application ID | `com.klk.hams` |
| Release version | `1.1 (2)` |
| Debug application ID | `com.klk.hams.debug` |
| Minimum Android version | API 33 (Android 13) |
| Target / compile Android version | API 35 (Android 15) |
| UI technology | Kotlin, Jetpack Compose, Material 3 |
| Orientation | Sensor portrait (0° or 180°) |
| On-device database | Room v6, `hams.db` |
| Approved production handset | realme C85 |

**Operational decision:** the approved production handset is **realme C85**. This is a deployment decision supplied by the project owner, not a fact inferred from Android source.

**Still to confirm:** handset Android version, screen size, ruggedness/IP rating, SIM/network plan, and live production endpoint ownership. These are needed before a field user manual is published.

## 2. App flow

```text
Launch
  -> location permission / location-service gate
  -> pairing screen when no active binding
  -> battery-optimisation onboarding when needed
  -> count screen and foreground location service
```

The count screen shows task and GPS state, allows plus/minus count adjustment, starts a new task through a protected hold gesture, and exposes push/provisioning controls. The app requests notification permission on Android 13+ for its user-visible notifications.

## 3. Counting and task rules

- A count action needs a fresh GPS snapshot. The configured freshness limit is 10 seconds.
- The location stream requests high accuracy while held active, targets a one-second interval (500 ms fastest), and uses a watchdog after a six-second silence.
- Each count capture records UTC time, decimal GPS coordinates, HDOP/satellites when supplied, speed when supplied, battery percentage, event code, and the net task count after the action.
- The maximum net count per task is 9,999.
- A plus action creates/updates a task and creates code `179` rows. A minus action creates code `180` rows; a decrement to zero is retained locally but does not queue for delivery.
- A scheduled heartbeat uses code `35` with the current net task count. Its configured interval is one minute.
- Task dates use Malaysia Time (UTC+8) for rollover and sequence boundaries.

The precise mapping is in [Event-code dictionary](EVENT_CODE_DICTIONARY.md).

## 4. Storage and retention

Room database version 6 contains the following logical records:

| Record | Purpose |
|---|---|
| `tasks` | Task start/end, plus/minus/net counts, task date, save type, and push status. |
| `events` | Count actions, heartbeat records, GPS/battery audit markers, location/battery details, and per-row delivery state. |
| `diagnostics` | Device and provisioning telemetry, optional location details, delivery state, and stranded-work metadata. |

Migrations are present from v1 to v6. Version 6 adds nullable `lost_tasks` and `lost_cuts` to diagnostics. Terminal records are configured for a 30-day retention sweep; queued records are not described as disposable by that sweep.

## 5. Delivery behaviour

The app uses WorkManager for automatic and manual delivery. Current configuration uses `PUSH_ALLOW_METERED=true`, so delivery requires `NetworkType.CONNECTED` and may use a metered connection. If the setting changes to false, delivery instead requires an unmetered network.

| Delivery control | Current source value |
|---|---:|
| Task-frame batch size | 10 records |
| Inter-frame delay | 75 ms |
| Maximum retry attempts | 5 |
| Manual push timeout | 30 minutes |
| Release delivery budget | 15 seconds |

Task events queue only for codes 179, productive 180, and 35. Diagnostics use a separate telemetry queue. A gate serialises release/manual/background delivery to avoid duplicate uploads.

## 6. Wialon IPS interface

The app reads its IPS host/port and fallback identity from build-time configuration. It opens a TCP session, sends `#L#<unique_id>;NA\r\n`, then sends a 16-field `#D#` data message and waits for acknowledgement.

Task frames use decimal-degree conversion to `DDMM.MMMM` latitude and `DDDMM.MMMM` longitude. The named parameter block contains `ffb_cut`, `battery`, `event_code`, and `work_count`:

- Code 179 sets `ffb_cut=1`.
- Codes 180 and 35 set `ffb_cut=0`.
- The code and post-event work count are carried as parameters.

Telemetry frames do not represent a cut. A stranded-work record may include `lost_cuts`.

## 7. Provisioning and binding protection

Pairing and release call configured company endpoints; endpoint operation is outside the mobile source. The app uses `Settings.Secure.ANDROID_ID` as the device fingerprint, with build `DEVICE_UNIQUE_ID` only as a development fallback.

The app checks binding at startup, before delivery, and periodically. On a device-initiated release, it attempts to deliver pending work only while the current phone still owns the unit. After release, remaining queued events are marked stranded (`pushed = 2`) so a future binding cannot send them under a different unit.

## 8. Configuration and secret boundary

Real values belong in ignored `local.properties` or secure CI/secret storage. The safe template is [local.properties.example](../../local.properties.example).

The APK may receive the specific IPS and provisioning values that it needs at build time. `WIALON_TOKEN` is explicitly excluded from the APK and is intended only for server-side/admin tooling. Do not put secrets in source, Markdown, workflow snapshots, screenshots, or committed test data.

## 9. Required confirmation before a field manual

Before publishing an operator-facing manual, record and approve:

1. The production handset Android version (the approved model is realme C85).
2. The allowed network policy (whether metered cellular delivery remains enabled).
3. The company owner and support route for provisioning endpoints and Wialon rules.
4. The approved pairing/replacement procedure and responsible role.
5. The exact user-visible build/version being deployed.
