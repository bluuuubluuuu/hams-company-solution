
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# CLAUDE.md — HAMS Task Recorder (V6)

> This file is the primary instruction set for Claude Code (terminal) and Codex CLI.
> Both tools must read this file and CONTEXT.md before generating any code or plans.
> Do not modify this file without updating CONTEXT.md in sync.
>
> **Note:** Claude Code does NOT automatically load `CONTEXT.md`. Read it explicitly at the start of any task that touches IPS, REST, coordinate conversion, or Wialon configuration.

---

## Project Identity

**Project:** HAMS Task Recorder — Android Mobile App
**Repo:** `D:\HAMS_company_solution`
**Version:** V2 (protocol layer at V6)
**Owner:** KLK (Kuala Lumpur Kepong) — Oil Palm Plantation Operations
**Developer account:** yhwoo516@gmail.com (Claude Pro)
**Status:** App feature-complete and field-verified. Live Wialon push proven. Provisioning backend
(n8n + Postgres) live; binding revalidation shipped 2026-07-07. Handover to company IT in progress.

---

## Account & Token Usage Policy

### Account assignments

| Account | Owner | Primary purpose |
|---|---|---|
| `yhwoo516@gmail.com` (Claude Pro) | WYH (dev) | Code generation, cross-checking, reading docs, integration testing |
| Supervisor enterprise account | SV | Documentation updates, file rewrites, long-form doc generation |

### Working model

**WYH account (this session):**
- Code only — Kotlin, SQL, N8N, curl, build scripts
- Read and verify documentation (audit prompts)
- Cross-check API outputs and test results
- Short doc edits only (banners, single-line fixes, config stubs)
- Do NOT generate full document rewrites — hand off to SV account

**SV enterprise account:**
- Full document rewrites when needed
- Receives: full conversation export + all project docs + a handoff prompt
- Handoff prompt format: describe what changed in the conversation, what decisions were made, what files need updating, and what the new content should say
- Produces: updated replacement files for CLAUDE.md, CONTEXT.md, HAMS_EVENT_CODE_DICTIONARY.md, HAMS_API_TESTING.md

### Handoff trigger — when to escalate to SV account

Escalate to SV account when ANY of the following is true:

- A new architectural decision is made that changes more than 3 lines across docs
- A new event code, config key, or protocol field is added
- A phase milestone completes (Phase 0 done → update checkpoint)
- A V6 decision status changes (OPEN → RESOLVED)
- KC confirms a rollout decision that was previously OPEN
- A new integration test produces results that update the checkpoint
- Any full section rewrite is needed

Do NOT escalate for:
- Single-line path fixes
- Adding a new TODO checkbox
- Rotating a token reference
- Cosmetic wording improvements

### Handoff prompt format (copy this template)

When escalating to SV account, provide:
HANDOFF CONTEXT:
Project: HAMS V2 — Android App + Wialon + N8N pipeline
Last session: [date]
Account handing off from: WYH (yhwoo516@gmail.com)
WHAT CHANGED THIS SESSION:
[bullet list of decisions made, things proven, things approved]
DECISIONS TO RECORD:
[list of specific decision IDs and their new status e.g. D10: OPEN → RESOLVED]
FILES TO UPDATE:
[list of files with brief description of what section changes]
NEW CONTENT TO ADD:
[paste the actual new content, or describe it precisely]
DO NOT CHANGE:
[anything that must be preserved unchanged]
After updating, produce full replacement files ready for drop-in.

### Fallback — if SV account is slow or unavailable

If SV account does not respond within the session, WYH account may generate
documentation updates under these conditions only:

- The change is urgent (blocks Codex from proceeding)
- The change is self-contained (one file, one section)
- The generated doc is marked with a comment:
  at the top of the changed section

Draft docs must be reviewed and the comment removed by SV account before
the next phase begins.

---

## What Changed in V6 (2026-04-23)

HAMS V2 moved from a `course=1` hack in a 10-field IPS frame (V5) to a full 16-field IPS v1.1 frame with native named parameters (V6). The V5 conclusion that "IPS v1.1 rejects custom params" was proven wrong on 2026-04-23 via direct PowerShell testing — the gateway accepts `#AD#1` and the message stores with params intact, verified via both the REST API and Wialon UI (see `docs/checkpoints/HAMS_API_TESTING.md` Phase F).

**Net impact for Codex and Claude Code:**
- Rewrite `#D#` frame builder for 16-field form with params block
- Add `BatteryManager` read to the event capture path
- Add `work_count` tracker (current task displayed/net count after the event)
- Keep local event capture for task lifecycle/health, but outbound Wialon `event_code` is now restricted to the approved reporting values `179`, `180`, and `35` (see `docs/HAMS_EVENT_CODE_DICTIONARY.md` v1.2)
- Push engine now needs a Task 2.4 redesign before commit: socket transport stays data-driven, but `IPSFrameBuilder`/`PushEligibility` must stop treating HAMS-local lifecycle/health values as pushable Wialon event codes

**What did NOT change:**
- Android scaffold (Kotlin DSL, Compose, Version Catalog)
- SQLite core tables (extended with new columns, no renames)
- Push engine architecture (Wi-Fi trigger, batch, retry)
- Coordinate conversion (still DDMM.MMMM)
- Wialon host/port/auth
- Task lifecycle (FR-05) — new/save mechanics identical
- Build/lint/test commands

See `docs/HAMS_EVENT_CODE_DICTIONARY.md` for the complete event code list and rules.
See `CONTEXT.md` for the protocol-layer spec and environment data.

---

## Current Repo State (Provisioning — manual pairing + n8n admin)

Device identity is assigned by **office manual pairing**, not a compile-time constant. On first
launch the app shows `PairingScreen`: an admin keys in the Wialon unit id and a short-lived
supervisor **OTP**, and the app binds that unit to its `Settings.Secure.ANDROID_ID` fingerprint
via the n8n `manual-claim` webhook. Release / re-bind is office-only through the OTP-gated
`release` + `manual-claim` path (admin sheet on the battery pill). `DEVICE_UNIQUE_ID` in
`local.properties` is only a dev fallback. Cut data still goes phone → Wialon IPS directly; the
backend only does identity + admin. Backend: `provisioning/` (SQL functions + importable n8n
workflow JSONs). Start at `COMPANY_HANDOFF.md`, then `provisioning/README.md` + `LOCAL-RUN.md`.

### Binding revalidation (shipped 2026-07-07, field-verified)

The app re-checks its binding against the registry at launch, before every push, and every ~15 min
(`BindingCheckWorker`) via the n8n `verify` webhook → `check_binding()`. Four answers: `bound`
(proceed), `released` (flush pending cuts + push `301`, then log out), `bound_other` (log out, no
push), `not_found` (conservative — keep last-known-good, do not wipe). A **drain lease**
(`units.drain_until` / `drain_fingerprint`, 5-min TTL) blocks other devices from claiming the unit
while the departing phone flushes. Provisioning event codes: `301 binding_released`,
`303 device_bound`, `304 device_unbound` (pushed inline at OTP bind/unbind).

**Deliver-before-strand (shipped 2026-07-24, device-verified DV1/DV2 on `ALI-NX1`).** A
device-initiated OTP release now *delivers* its pending cuts to Wialon under the unit still being
held — before anything is stranded. Sequence: preflight `verify` (deliver only if this phone still
owns the unit — no misfiling under a reassigned unit), then a bounded single-attempt `PushEngine`
drain (`AppConfig.DELIVER_BUDGET_MS`, ~15 s) serialised against the background worker via `PushGate`
(no duplicate `179`), then `client.release()`, then count-what-did-not-land, marker, strand. On any
working network the queue empties and the release is a clean **`304`** (DV1). Only when delivery
genuinely fails does it emit **`302 work_stranded`** with `lost_cuts` (DV2). The still-pending event
rows are stranded (`pushed = 2`) **unconditionally**, whether or not the marker reached the gateway,
so they can never upload under the next unit. `304` carries no params; `302` carries `lost_cuts` only
(`lost_tasks` dropped from the wire, v1.6). **`302` is a local diagnostic, best-effort push** — it
most often fires *because* Wialon was unreachable, in which case it cannot land and the only record
is local (`pushed = 2` on the phone). Do not rely on Wialon to surface `302`; office visibility of
stranded work belongs to a future n8n-side count. See `docs/HAMS_EVENT_CODE_DICTIONARY.md` v1.6.
Plans: `docs/superpowers/plans/2026-07-07-binding-revalidation.md`,
`docs/superpowers/plans/2026-07-23-work-stranded-302.md`,
`docs/superpowers/plans/2026-07-23-deliver-before-strand.md`.

Two admin workflows beyond the original four: `list-units` (read-only registry dump) and
`admin-release` (office force-free a unit without the phone or an OTP).

**Backend is PostgreSQL today, but the engine is not final** — HQ is ruling on Postgres vs SQL
Server. See `database/` for the review pack and `n8n_handover/` for the company deployment pack.

---

## Current Repo State (2026-05-14 — Phase 2.8 complete, field-verified)

**Phase 2 fully complete on the `phase/2-ips-push` branch.** End-to-end push to Wialon confirmed: auto-push when validated Wi-Fi appears (worker survives app close, swipe, and reboot), manual push via 3 s hold on the count screen, task-level progress notifications (silent during, alerting at terminal with one of four outcomes A/B/C/D), force-stop recovery on app open, and battery-optimisation onboarding for OEM-aggressive devices. Critical pieces: cooperation contract (Spec §17) keeps auto + manual on one worker queue; gesture handling uses `pointerInput(Unit)` + `rememberUpdatedState` everywhere after the +→− phantom-undo bug. See `plans/phase2_ips_push.md` "Implementation log" for the full commit chain, findings, and known limitations.

---

## Repo State pre-2.8 (2026-04-28 — kept for historical context)

**Phase 0: COMPLETE.** **Phase 1: COMPLETE after emulator/manual verification on 2026-04-28. See `docs/checkpoints/HAMS_PHASE_0_1.md` for details.**

- Android scaffold is in place (Kotlin DSL, Version Catalog, Jetpack Compose + Material3).
- Dependency versions pinned in `gradle/libs.versions.toml` (AGP 8.10.1, Kotlin 2.0.21, KSP 2.0.21-1.0.27, Compose BOM 2024.09.00, `compileSdk=35`, `minSdk=34`). Always add new libs via the catalog, not inline.
- JVM target for `:app` is 11. Gradle itself can run on a newer JDK — if you hit a toolchain mismatch, adjust `org.gradle.java.home`, don't bump the module target.
- Do not assume the working tree is clean; run `git status --short` at the start of every editing task.

### Phase 0 / Phase 1 — ALL COMPLETE
Details in `docs/checkpoints/HAMS_PHASE_0_1.md`. Summary: scaffold + `BuildConfig` wiring, manifest permissions, `AppConfig.kt`, Room schema with V6 event columns, `TaskRepository` with lazy task creation, `LocationProvider`, `CountScreen`/`CountViewModel`, `HamsForegroundService` (`dataSync`), notification channel, GPS gate, manual emulator verification 2026-04-28.

### Phase 2 status (2026-05-05)

- Branch: `phase/2-ips-push`.
- Tasks 2.1–2.7 — ✅ COMPLETE and committed.
  - 2.1–2.5: `CoordinateConverter`, V6 16-field `IPSFrameBuilder`, `PushEligibility`, `TaskRepository` push flow under v1.2 policy, local health/heartbeat capture, physical-device validation.
  - 2.6 (`10b0ba5`): `WialonIPSClient` — single TCP session, login frame, pre-built `#D#` frame send, typed `WialonError` mapping for `#AL#` / `#AD#` acks, session-close on transport/timeout failures.
  - 2.7A (`efa7595`): `PushEngine` core orchestration with `PushRepository` / `IpsSender` interfaces, terminal `PushState`, per-event mark-uploaded / mark-rejected, single-session abort on transport failure.
  - 2.7B (`2e529f7`): chunking by `AppConfig.BATCH_SIZE` with a fresh sender per chunk, configurable inter-message delay, retry-with-backoff up to `AppConfig.MAX_RETRY_ATTEMPTS` (`30/60/120/240/300s`, `LoginRejected` fail-fast), pre-flight hook for the 2.8 wiring of `repo.autoSaveActiveOnWifi(...)`. `WialonIPSClient` now declares `: IpsSender`.
- Event-code policy v1.2 (`docs/HAMS_EVENT_CODE_DICTIONARY.md`) unchanged: only `179`, productive `180`, and `35` are approved outbound Wialon `event_code` values. `279/280/281/283/284/291/292/293` are HAMS-local; never pushed unless Wialon-side reporting config is explicitly added.
- No Wi-Fi monitor and no runtime call site yet. Nothing in `HamsApp` / `HamsForegroundService` instantiates `PushEngine` or `WialonIPSClient`. No live Wialon push has been performed from the app.
- **Task 2.7.5 — GPS streaming + armband UI + DB v2 + daily rollover** — 🟡 IMPLEMENTED, **pending field verification 2026-05-07**. Bundles four related changes shipped together over 2026-05-05/06:
  - **GPS streaming (`LocationStream`)** replaces Phase 1 lazy-fetch. App-scope `StateFlow<LocationSnapshot?>`, ref-counted by `"foreground"` (Activity) and `"task_active"` (`HamsForegroundService`). Dynamic priority: `HIGH_ACCURACY` while `task_active` is held, `BALANCED_POWER_ACCURACY` otherwise (revised 2026-05-06 after BALANCED produced visible per-press flap). Watchdog poke fires `getCurrentLocation` after 6 s of silence. Press path reads `snapshotFlow.value` synchronously. Spec: `docs/superpowers/specs/2026-05-05-gps-streaming-design.md`.
  - **Armband portrait UI** — `MainActivity` locked to `screenOrientation="sensorPortrait"` (allows 0° + 180° for either armband mount orientation; system Auto-rotate must be ON). Rewrote `CountScreen.kt` with status pills (BATT / GPS-color / TASK), 4-digit count card, equal-size +/− action row, NEW TASK bar with 5 s hold + linear progress. Field-instrument palette in `Color.kt`; mono digits, sans elsewhere. Spec: `docs/superpowers/specs/2026-05-06-armband-ui-design.md`.
  - **DB v2 (`task_date`)** — Room migrated v1 → v2 adding `tasks.task_date` (MYT calendar day, backfilled from `started_at + 8h`). See "Schema migrations" above.
  - **Daily rollover** — `TaskRepository.rolloverActiveTaskIfStale()` runs at `CountViewModel.init`. Yesterday's stale active task is finalized (`save_type="auto_rollover"`, `push_status="pending"` if non-zero else `"discarded"`). Today's first `+` lazy-creates a fresh task at `task_seq=1`. See "Daily rollover" under Task Lifecycle.
  - Manifest: adds `FOREGROUND_SERVICE_LOCATION` + `foregroundServiceType="dataSync|location"`. `AppConfig`: new `LOCATION_STREAM_INTERVAL_MS=1000`, `LOCATION_STREAM_FASTEST_MS=500`, `LOCATION_STREAM_STALENESS_MS=10000`, `LOCATION_STREAM_WATCHDOG_CHECK_MS=3000`, `LOCATION_STREAM_WATCHDOG_MS=6000`. No on-the-wire IPS frame change.
- Next recommended task: **Task 2.8 — Push trigger via WorkManager + manual button** (spec approved, pending implementation). Replaces the earlier `WifiMonitor`/`BroadcastReceiver` plan. WorkManager `OneTimeWorkRequest` with `NetworkType.UNMETERED` survives app close/swipe/reboot. Two flows: auto (silent, notifications only) and manual (5 s button hold + confirm + UI lock + status panel + 30-min timeout). Push targets `push_status='pending'` tasks only — active tasks insulated. Heartbeat cadence drops 10 → 1 min. Periodic in-process rollover added (1 s tick) so apps left open across midnight still finalize yesterday's task. New `PushController`, `PushWorker`, `PushNotifier`, `PushRepositoryImpl`. New AppConfig: `PUSH_MANUAL_TIMEOUT_MS`, `PUSH_RETRY_BACKOFF_MS`. Spec: `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md`. Only after 2.8 lands does live Wialon push become possible.

---

## Build, Lint, Test

The Gradle wrapper is in place. Run from repo root. In this PowerShell workspace, prefer `.\gradlew.bat`.

| Action | Command |
|---|---|
| Debug build | `.\gradlew.bat :app:assembleDebug` |
| Install on connected device | `.\gradlew.bat :app:installDebug` |
| Lint (debug variant) | `.\gradlew.bat :app:lintDebug` |
| Unit tests (all) | `.\gradlew.bat :app:testDebugUnitTest` |
| Single unit test | `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.CoordConversionTest"` |
| Instrumented tests | `.\gradlew.bat :app:connectedDebugAndroidTest` |
| Clean | `.\gradlew.bat clean` |
| Debug logcat (UI press path + rollover) | `adb logcat -s HAMS_UI` |
| Pull device DB (debug build) | `adb exec-out run-as com.klk.hams.debug cat databases/hams.db > hams.db` (also `-wal` and `-shm` if present) |
| Quick live DB query | `adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "<SQL>"` |

Notes:
- Build files are Kotlin DSL (`.kts`), not Groovy. Dependency coordinates live in `gradle/libs.versions.toml` — add libs there, reference via `libs.<alias>` in `app/build.gradle.kts`.
- Coordinate conversion (`decimalToDDMM`) must be unit-tested first before any IPS push integration — see CONTEXT.md Section 3.
- Frame builder canonical example (see IPS Message Format section below) must be unit-tested before any push engine integration.
- `ExampleUnitTest.kt` and `ExampleInstrumentedTest.kt` are template stubs from Android Studio; replace them with real tests, don't pile on top.

---

## What This App Is

An Android app that **replaces the MeiTrack P99L hardware GPS tracker** used by oil palm harvesters. Workers press `+` for each FFB (Fresh Fruit Bunch) cut. Each press records GPS + battery + timestamp locally (SQLite). When Wi-Fi is available, the app batch-pushes events as Wialon IPS v1.1 `#D#` messages with native named parameters (`ffb_cut`, `battery`, `event_code`, `work_count`) to the Wialon cloud platform.

**This app is the data source layer only.** It does not do geofence matching, reporting, or pipeline logic. That is handled downstream by Wialon → N8N → PostgreSQL.

---

## Scope Boundaries

### IN SCOPE — Build this
- Android Kotlin app (single Activity or Jetpack Compose)
- `+` button: record FFB cut (GPS + battery + timestamp → SQLite)
- `−` button: local count adjustment (IPS message only if `work_count > 0` after decrement)
- Configurable per-press count scale (1–10) for `+` and `−`, independently, persisted in SharedPreferences. Scaled press emits N event rows with shared GPS/timestamp/battery; default scale=1 is byte-identical to single-press behaviour. See `docs/superpowers/specs/2026-05-29-scaled-count-design.md` (AR-01 reopened 2026-05-29).
- Mandatory GPS access: app checks location permission and device location services on launch; if unavailable or denied, show a clear message and close the app. The `+` button must not increment or write a cut unless a valid GPS coordinate is available at press time.
- Task lifecycle: start task, new task (3-sec hold; reduced from 5 s per field feedback 2026-05-13), save task, auto-save on kill, auto-save pre-push
- Offline-first SQLite storage (7–30-day capacity, configurable)
- Wi-Fi triggered batch IPS TCP push to `185.213.1.24:20332` — 16-field `#D#` frame with params block
- Battery threshold edge detection (local telemetry for now; no custom Wialon event_code)
- GPS degraded detection (local telemetry for now; no custom Wialon event_code)
- Heartbeat timer (event 35, default 10-min interval, configurable 5–60 min)
- Push state machine: pending → uploading → uploaded / failed
- Progress bar + single rolling Android notification during push
- Coordinate conversion: decimal degrees → DDMM.MMMM (mandatory before IPS push)
- Battery level read via `BatteryManager` on every event capture

### OUT OF SCOPE — Do not build
- Geofence lookup or Field/Task matching (Wialon does this)
- REST API calls to Wialon for reading data
- N8N workflows or PostgreSQL schemas (separate pipeline project)
- Supervisor-locked scale (worker can always change scale; supervisor-only lock is out of scope)
- Indonesia multi-count mode (deferred — AR-02)
- Battery email notifications (Wialon-side notification rules handle this)
- WiaTag integration (irrelevant — we use IPS v1.1 natively with custom params)

---

## Target Device

| Item | Value |
|---|---|
| Device | Oppo A5i |
| Android | 15 (API 35) |
| Min Android | 14 (API 34) |
| Display | 6.6" |
| Rating | IP-54 |

---

## Architecture

```
[Oppo A5i — Offline]
  + button press (or any V6 event trigger)
    → GPS (Fused Location Provider) + battery + timestamp (UTC)
    → SQLite: events table (one row per event, V6-extended columns)
    → SQLite: tasks table (one task per New Task session, counters updated)

[Validated Wi-Fi connects — Task 2.8 design, spec docs/superpowers/specs/2026-05-08-push-and-wifi-design.md]
  → WorkManager OneTimeWorkRequest (NetworkType.CONNECTED by default; UNMETERED when PUSH_ALLOW_METERED=false) fires PushWorker
      (works even with the app fully closed/swiped/rebooted)
  → PushWorker drains tasks where push_status='pending' only
      (active tasks are NEVER auto-saved by push; lifecycle stays
       worker-controlled: NEW TASK 3s hold / app swipe / day rollover)
  → Push engine reads outbound-approved pending rows (179, productive 180, 35)
  → Batch TCP to 185.213.1.24:20332 (Wialon IPS v1.1)
  → Each event = one #D# IPS message with 16 fields + params block
      Carrying: ffb_cut, battery, event_code, work_count
  → Mark pushed events as "uploaded" in SQLite
  → System notification: Pending → Pushing → Completed / Failed

[Wialon Cloud — not this app's concern]
  → Receives IPS messages as unit messages with populated p block
  → Auto-geocodes GPS → geofence name (e.g. PM03D_13)
  → FFB_CUT sensor reads parameter ffb_cut (V6 — was course in V5)
  → battery_pct sensor reads parameter battery (V6 new)
  → Report template counts rows where ffb_cut=1 per geofence per day
  → N8N picks up report → PostgreSQL HAMSTaskCount
```

---

## IPS Message Format (Critical)

Every pushed event sends a V6 16-field `#D#` frame over TCP to `185.213.1.24:20332`. Full format specification is in `CONTEXT.md` Section 3 — do not duplicate it here. Key facts to remember:

1. **Login first** on every new TCP connection: `#L#<unique_id>;NA\r\n` → expect `#AL#1`
2. **Data frame is 16 fields + params block**, not 10 fields
3. **Course field is `0` (real value)**, not `1` (V5 hack). The + press signal now lives in the `ffb_cut` param
4. **Four standard params every message**: `ffb_cut`, `battery`, `event_code`, `work_count`
   - `ffb_cut` is derived from `event_code` at frame-build time; it is not stored as a SQLite column.
   - `work_count` is the current task displayed/net count after the event (`plus_count - minus_count`), not lifetime total plus presses.
5. **Coordinate conversion mandatory**: decimal degrees → DDMM.MMMM. Wrong format = messages land in wrong geofence.

### Canonical frame example (use as unit-test reference)

Input state:
- Timestamp: 2026-04-23T01:17:06 UTC
- Latitude: 2.268721°N, Longitude: 103.282985°E
- Satellites: 8, HDOP: 1.5
- Event: + press (approved outbound event_code 179)
- Battery: 91.0%
- Task displayed/net count after this press: 1

Expected output (exact):
```
#D#230426;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;ffb_cut:1:1,battery:2:91.00,event_code:1:179,work_count:1:1\r\n
```

### Event → param mapping (summary)

The mapping between event types and what lands in the frame is governed by `docs/HAMS_EVENT_CODE_DICTIONARY.md`. Short reference:

| Event | ffb_cut | event_code | work_count | push? |
|---|---|---|---|---|
| + press | 1 | 179 | displayed count after increment | yes |
| − press | 0 | 180 | displayed count after decrement | only if result > 0 |
| New task | n/a | local only | 0 | no |
| Auto-save kill | n/a | local task/save state | final count at kill | no outbound custom event_code |
| Auto-save Wi-Fi | n/a | local task/save state | count at pre-push moment | no outbound custom event_code |
| Battery warn/critical | n/a | local telemetry | current count | no; battery rides normal pushed params |
| GPS degraded | n/a | local telemetry | current count | no unless Wialon rule/report exists |
| Periodic beacon | 0 | 35 | current count | yes |

### Coordinate Conversion (mandatory)

```kotlin
fun decimalToDDMM(decimal: Double, digits: Int): String {
    val deg = decimal.toInt()
    val min = (decimal - deg) * 60.0
    return "%0${digits}d%07.4f".format(deg, min)
}
// Latitude uses 2-digit degrees:  decimalToDDMM(2.268721, 2)   → "0216.1233"
// Longitude uses 3-digit degrees: decimalToDDMM(103.282985, 3) → "10316.9791"
```

---

## SQLite Schema (V6)

> **Note on versioning.** "V6" refers to the Wialon IPS protocol layer (16-field
> `#D#` frame with native params). The Room/SQLite layer is independently
> versioned. Current Room database version: **2** (see Schema migrations below).

### `tasks` table

```sql
CREATE TABLE tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    task_seq INTEGER NOT NULL,
    task_date TEXT NOT NULL DEFAULT '',  -- DB v2: MYT calendar day (YYYY-MM-DD); aligned with task_seq's reset boundary
    started_at TEXT NOT NULL,      -- ISO 8601 UTC
    ended_at TEXT,                 -- ISO 8601 UTC, null if active
    plus_count INTEGER DEFAULT 0,
    minus_count INTEGER DEFAULT 0,
    net_count INTEGER DEFAULT 0,
    push_status TEXT DEFAULT 'active',  -- active | pending | uploading | uploaded | failed | discarded
    save_type TEXT,                -- manual | auto_wifi | auto_killed | auto_rollover
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

### Schema migrations

| From | To | Adds | Backfill |
|---|---|---|---|
| v1 | v2 (2026-05-06) | `tasks.task_date TEXT NOT NULL DEFAULT ''` | `UPDATE tasks SET task_date = substr(datetime(started_at, '+8 hours'), 1, 10)` (MYT = UTC+8, no DST) |

`AppDatabase.MIGRATION_1_2` is registered in `HamsApp.database`. New tasks set `task_date` from the current MYT day at insert time (`TaskRepository.mytDateOf`).

### `events` table (V6 — extended)

```sql
CREATE TABLE events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id       INTEGER NOT NULL REFERENCES tasks(id),
    event_type    TEXT NOT NULL,           -- 'plus' | 'minus' | 'new_task' | 'auto_save' | 'battery' | 'gps_poor' | 'heartbeat'
    event_code    INTEGER NOT NULL,        -- V6 NEW: per docs/HAMS_EVENT_CODE_DICTIONARY.md
    timestamp     TEXT NOT NULL,           -- ISO 8601 UTC
    lat_decimal   REAL,                    -- nullable for legacy/diagnostic rows; required for + cut events
    lon_decimal   REAL,                    -- nullable for legacy/diagnostic rows; required for + cut events
    hdop          REAL,                    -- V6 NEW
    satellites    INTEGER,                 -- V6 NEW
    battery_pct   REAL NOT NULL,           -- V6 NEW: always captured
    work_count    INTEGER NOT NULL,        -- V6 NEW: displayed/net task count after this event
    count_after   INTEGER NOT NULL,        -- legacy field (plus_count - minus_count at capture)
    pushed        INTEGER NOT NULL DEFAULT 0,  -- 0 pending, 1 uploaded, 2 rejected
    created_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_pushed ON events(pushed);
CREATE INDEX IF NOT EXISTS idx_events_task ON events(task_id);
```

---

## Event Capture Rules

Every event type follows the same local capture pipeline, but not every local
event is a Wialon event. Event capture writes to SQLite synchronously; the push
boundary later selects only outbound-approved event codes (179, productive 180,
35).

### Capture pipeline (same for all event types)

```
1. Trigger fires (button, timer, broadcast, lifecycle callback)
2. Capture current state:
     timestamp = Instant.now()
     location  = LocationStream.snapshotFlow.value (continuous BALANCED stream
                 owned app-scope; press path reads synchronously, never suspends.
                 Press is gated to GpsLockState.Locked, i.e. snapshot age
                 ≤ AppConfig.LOCATION_STREAM_STALENESS_MS, default 10 s).
     battery   = BatteryManager.BATTERY_PROPERTY_CAPACITY
3. Build Event object:
     - event_type (string, for UI/debug)
     - event_code (int, from dictionary)
     - ffb_cut (derived from approved outbound event_code per dictionary at frame-build time)
     - work_count (displayed/net task count after any ± adjustment)
     - all GPS fields
     - battery_pct
     - pushed = 0 only for outbound-approved rows; local-only rows use pushed=1 or equivalent local state
4. Synchronous Room insert. Must complete within 100ms.
```

### GPS gate rule

GPS access is mandatory for counting. On app launch, request/check `ACCESS_FINE_LOCATION` and verify device location services are enabled. If permission is denied or location services are off, show a clear blocking message such as "GPS permission is required to record cuts" and close the app.

Once the gate passes, the app starts an app-scoped continuous location stream (`LocationStream`, BALANCED priority, 2 s interval). The stream is held alive by ref-counted reasons: `"foreground"` (Activity resumed) and `"task_active"` (`HamsForegroundService` running). The press path reads `LocationStream.snapshotFlow.value` synchronously and never calls `getCurrentLocation`. The `+` and `−` buttons are disabled (`canIncrement` / `canDecrement` gate on `GpsLockState.Locked`) when the latest snapshot is `null` or older than `AppConfig.LOCATION_STREAM_STALENESS_MS` (default 10 s). The UI shows a persistent green/yellow GPS indicator so the user always knows whether the next press will record. No cut event ever lands without a coordinate fresher than the staleness threshold.

Full design: `docs/superpowers/specs/2026-05-05-gps-streaming-design.md`.

### Triggers by event type

| Event | Android API | Notes |
|---|---|---|
| +/− press | Compose `Button.onClick` | Main thread; reads `LocationStream.snapshotFlow.value` synchronously, no suspend before the Room write. Button disabled unless `GpsLockState.Locked`. |
| New task (281) | Custom 3-second long-press → confirmation dialog | Two-stage to prevent accidental task loss |
| Auto-save kill | `Service.onTaskRemoved()` | **Synchronous local save — no coroutines.** System may kill process within ms. No outbound custom event_code for now. |
| Auto-save Wi-Fi | First step of `PushEngine.start()` | Local task/save state only; no outbound custom event_code for now. |
| Battery warn/crit | `BroadcastReceiver` on `Intent.ACTION_BATTERY_CHANGED` | Local edge detection; Wialon-visible battery uses the `battery` param. |
| GPS degraded | `LocationListener.onLocationChanged` checks `location.accuracy` → HDOP proxy | Local telemetry unless Wialon reporting is explicitly designed. |
| Heartbeat (35) | `Handler.postDelayed` self-scheduling loop in HamsService | Default **1-min** interval (revised Task 2.8 spec), configurable via `heartbeat_interval_minutes`. Active-task scoped only |

### Push eligibility rule

Only outbound-approved events push by default (from `docs/HAMS_EVENT_CODE_DICTIONARY.md`):

- **New task (281)**: always insert with `pushed = 1` (marked as "don't push"). Push engine's `WHERE pushed = 0` query naturally skips these.
- **Plus press (179)**: push.
- **Minus press (180)**: conditional. If `work_count > 0` after the decrement, insert with `pushed = 0` (will push). If `work_count` would become 0, insert with `pushed = 1` (self-cancelling pair, stays local).
- **Periodic beacon (35)**: push.
- **HAMS-local lifecycle/health values (279/280/283/284/291/292/293)**: do not push unless Wialon-side reporting config is explicitly approved.
- **Scaled press (AR-01, 2026-05-29)**: a single `+` or `−` press at scale `N` emits up to `N` event rows in one DB transaction, all sharing the same GPS / timestamp / battery snapshot. Each row's `work_count` increments (`+`) or decrements (`−`) by one. `+` caps emitted rows at `MAX_COUNT_PER_TASK`. `−` caps at remaining `net_count`. The self-cancelling rule applies per-row — only the row that lands on `work_count = 0` is marked `pushed = 1`. Heartbeat / battery-edge / GPS-degraded / auto-save / new-task events are never scaled.

---

## Battery Edge Detection Logic

Battery threshold edges can still be recorded locally, but do not push 291/292
as custom Wialon `event_code` values. Every pushed 179/180/35 message carries
the current battery in its `battery` param.

### Bucket state tracking

Store last-known battery bucket in `SharedPreferences` under `last_battery_bucket` with three values: `100` (>20%), `20` (10–20%), `10` (<10%). On each `ACTION_BATTERY_CHANGED`, compute the current bucket from `level/scale*100`. If `curr < prev`, fire a local event (`291` for 100→20, `292` for 20→10 or 100→10) carrying the current `work_count`; then write `curr` back. Upward transitions (charging) never fire. On first install / cleared prefs, the first observation initializes `last_battery_bucket` silently and does not emit `291`/`292`.

---

## Push Engine Rules

> **Authoritative spec:** `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md`. The bullets below summarise; the spec governs.

- **Trigger** (Task 2.8): `WorkManager` `OneTimeWorkRequest` with `setRequiredNetworkType(...)` — `NetworkType.CONNECTED` by default (`PUSH_ALLOW_METERED=true`, field feedback 2026-07-15), `UNMETERED` when the flag is false. Survives app close, swipe, reboot. Replaces the earlier `BroadcastReceiver` plan (Android 7+ no longer reliably delivers `CONNECTIVITY_ACTION` to manifest receivers, and a foreground-service-based watcher couldn't survive `onTaskRemoved`).
- **Push target**: tasks with `push_status='pending'` only. Active tasks are insulated — push never finalizes them. Lifecycle is worker-controlled (NEW TASK 3s hold) or Android-lifecycle-controlled (app swipe → `auto_killed`, day rollover → `auto_rollover`, periodic in-process rollover at 1 s tick → `auto_rollover`). The legacy `repo.autoSaveActiveOnWifi(...)` pre-flight hook is **deprecated** under the 2.8 spec.
- **Modes**: auto (silent, notification only) and manual (5 s button hold + confirm; UI dims; status panel `Pending Wi-Fi → Pushing → Completed/Failed`; 30-min hard timeout in manual mode; cache always preserved).
- **Batch size**: 10 messages per TCP session (`AppConfig.BATCH_SIZE`).
- **Inter-message delay**: 50–100 ms (default 75 ms).
- **Session boundary**: close socket and reconnect between batches. New login frame required per session.
- **Login**: `#L#<unique_id>;NA\r\n` at start of every TCP connection. Expect `#AL#1`.
- **Query**: `EventDao.getPending` — `pushed = 0 AND event_code IN (179, 180, 35) AND NOT (event_code = 180 AND work_count <= 0)`, ordered by `timestamp ASC, id ASC`.
- **Success**: on `#AD#1`, update `pushed = 1` for that event. Once a task has no rows with `pushed = 0` remaining, the terminal-state predicate `TaskRepository.markTaskTerminalState` flips `tasks.push_status` to `uploaded` when no rows are rejected, or `failed` when any row has `pushed = 2`. Both states are terminal; a `failed` task is never auto-retried (per Codex finding #2 fix, 2026-05-15).
- **Failure (per-event)**: on `#AD#-1` or `#AD#15`, update `pushed = 2` (reject — needs investigation). Continue to next event in batch. Tasks carrying any `pushed = 2` row transition to `push_status = 'failed'` rather than `uploaded`.
- **Network failure (mid-batch)**: on `IOException` or timeout, close socket, return to controller. Backoff schedule per `AppConfig.PUSH_RETRY_BACKOFF_MS` (10/30/60/120s); resumes when Wi-Fi reappears within the budget.
- **Manual-mode budget**: 30-minute hard timeout (`AppConfig.PUSH_MANUAL_TIMEOUT_MS`). On exceed → `Failed("timeout")`, UI shows "Leftovers in cache, try again later." Auto-push remains active in the background.
- **Completion**: notification "Uploaded N tasks ✓" (auto-dismiss after 5s).
- **Battery read**: each event row carries the `battery_pct` captured at event time. Push engine does NOT re-read battery — it uses the snapshot in the row. Preserves historical accuracy in Wialon (battery reflects truth at event moment, not push moment).

### Push engine dictionary boundary

The push engine is mostly data-driven. `IPSFrameBuilder` derives `ffb_cut` from `event_code` using the event dictionary, while `PushEligibility` applies the small set of push/no-push rules. Adding new event codes normally changes event capture/config and tests, not the socket transport.

### Auto + Manual cooperation contract (Task 2.8 spec §17)

Both push modes share a single WorkManager unique-work queue (`WORK_NAME = "hams-push"`). The contract:

1. **Single worker.** Auto enqueues with `KEEP`. Manual enqueues with `REPLACE` only if current `pushUiState !is Pushing`. If `Pushing`, manual just attaches the UI overlay — no enqueue, no preemption.
2. **Manual is a UI overlay, not a second pipeline.** Setting `manualPushActive = true` adds the lock + status overlay. Worker behaviour is identical regardless of the flag.
3. **Cancel is UI-only.** `dismissManualOverlay()` clears the flag and stops the budget timer. It **never** calls `WorkManager.cancelUniqueWork`. The worker keeps running silently as auto-push.
4. **Single dedupe.** Three triggers (task-finalized callback, app open with pending > 0, manual button) all funnel through `enqueueAuto` (KEEP) or rule-#2-gated `triggerManual`. WorkManager dedupes; no enqueue storm possible.
5. **Flag clears on terminal state.** `manualPushActive` flips false when `pushUiState` reaches `Completed` / `Failed` / `Idle`, when the 30-min UI-side timer fires, or when the user dismisses the overlay.

The 30-min `PUSH_MANUAL_TIMEOUT_MS` budget is a UI-side coroutine timer in `PushController`. It only flips `manualPushActive`; the worker continues past 30 min if needed. Auto-push cadence is unaffected.

### Force-stop / battery-restriction recovery (Task 2.8 spec §18)

Android force-stop wipes the WorkManager queue but leaves SQLite intact. Recovery paths:

- **Universal handle:** the launcher icon. Tapping HAMS un-stops the app, runs `Application.onCreate`, and reinitialises WorkManager.
- **Task 10b (passive recovery):** `HamsApp.onCreate` reads `repository.observePendingTaskCount().first()` once. If `> 0`, calls `pushController.enqueueAuto()`. No user action needed; push fires when validated Wi-Fi appears.
- **Manual button (active recovery):** always available immediately on first launch.
- **Task 0b (preventive):** one-shot onboarding screen requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. For Oppo ColorOS it adds copy guiding the user to "Allow auto-launch" + "Allow background activity".

What is NOT auto-recoverable: an active task that was being filled at force-stop time stays `active` (no `onTaskRemoved` fires on force-stop). It finalises on the next NEW TASK hold or daily rollover. Reboot alone does **not** un-stop the app — the user must tap the launcher icon at least once.

### Retention sweep (Codex adversarial-fix design §4, 2026-05-15)

`HamsApp.onCreate` invokes `TaskRepository.purgeStaleTerminalTasks()` once per process launch on a background coroutine. The sweep deletes `tasks` rows in a terminal state (`push_status IN ('uploaded','failed','discarded')`) whose `created_at` is older than `AppConfig.SQLITE_RETENTION_DAYS` (default 30, aligned with FR-06's 30-day offline capacity). Events cascade-delete via the FK declared on `EventEntity`.

`active` and `pending` tasks are NEVER purged regardless of age — they represent in-progress or unsynced work. The 30-day window aligns terminal-task visibility (cache viewer) with the unsynced-work capacity floor: a worker who lacks Wi-Fi for the whole window still keeps every pending row, while completed audit rows fade out on the same horizon. Failures during the sweep are caught and logged; they never crash the app.

---

## Wialon Connection Details

| Item | Value |
|---|---|
| IPS Host | `185.213.1.24` |
| IPS Port | `20332` |
| IPS DNS | `nl2.gpsgsm.org` |
| Protocol | Wialon IPS v1.1 (16-field frame form) |
| Login frame | `#L#<unique_id>;NA\r\n` |
| Expected response | `#AL#1\r\n` (success) |
| Data frame | `#D#...` (16 fields + params block) |

**Do NOT use port 21416** — that is MeiTrack protocol, confirmed non-functional for this app.
**Do NOT use port 20963** — that is WiaTag protocol, available on the license but unnecessary for V6.

---

## App Config (externalised — not hardcoded)

Stored in `res/raw/config.json` or `SharedPreferences`, loaded at startup. Default values below are V6 baselines.

```json
{
  "device_unique_id": "OC154_H001",
  "ips_host": "185.213.1.24",
  "ips_port": 20332,

  "batch_size": 10,
  "batch_delay_ms": 75,
  "max_retry_attempts": 5,
  "retry_backoff_ms": [30000, 60000, 120000, 240000],

  "sqlite_retention_days": 30,

  "event_code_plus": 179,
  "event_code_minus": 180,
  "event_code_new_task": 281,
  "event_code_auto_save_kill": 283,
  "event_code_auto_save_wifi": 284,
  "event_code_battery_warn": 291,
  "event_code_battery_critical": 292,
  "event_code_gps_degraded": 293,
  "event_code_heartbeat": 35,

  "heartbeat_interval_minutes": 1,

  "battery_warn_threshold_pct": 20,
  "battery_critical_threshold_pct": 10,

  "gps_hdop_degraded_threshold": 5.0,

  "push_new_task_to_wialon": false,
  "push_minus_only_if_productive": true
}
```

### Config key notes

- **event_code_plus / event_code_minus**: approved outbound defaults are `179`/`180`. During testing, keep app units/resources outside production notification scopes before pushing these values. The older `279`/`280` dev-code strategy is suspended unless explicitly re-approved with Wialon test reporting.
- **event_code_new_task / auto_save / battery / gps_degraded**: local/internal identifiers only under the v1.2 policy. They must not become outbound Wialon `event_code` values unless Wialon-side reporting/notification config is added first.
- **heartbeat_interval_minutes**: valid range 1–60. **Default 1** under the Task 2.8 spec (was 10). Value `0` disables heartbeats entirely. Heartbeats are active-task scoped only.
- **push_new_task_to_wialon**: V6 default `false`. Leave as-is unless supervisors request task-boundary markers in Wialon.
- **push_minus_only_if_productive**: V6 default `true`. Setting to `false` would push all minuses including self-cancelling pairs.

Do not add or push custom Wialon `event_code` values without a matching Wialon report/sensor/notification design.

---

## Task Lifecycle

**Lazy task creation (confirmed 2026-04-28; push policy updated 2026-04-30):** No `tasks` row is created when the app opens. The first valid `+` press atomically creates the task row, inserts the `281` (new_task) marker event (`pushed=1`, local-only), and inserts the approved outbound plus event `179` (`pushed=0`) — all three writes in a single Room transaction once Task 2.4 is redesigned. `task_seq` resets per MYT day (`ZoneId.of("Asia/Kuala_Lumpur")`); the first task each day gets seq=1.

```
App opens → GPS gate check (permission + location services). If denied/off → close app.
             If GPS temporarily unavailable → + button disabled until fix arrives (app stays open).
             Daily rollover check (rolloverActiveTaskIfStale): if an active task's
             task_date is older than today's MYT day, finalize it before observing
             the active-task flow.
             No new task row created on open.
First valid + press → [atomic transaction]
             ① task row inserted (push_status="active", task_seq=N for today MYT, task_date=today MYT)
             ② event_code=281 inserted (new_task, pushed=1 — local only, never pushed)
             ③ event_code=179 inserted (plus, pushed=0)
Subsequent + press → event recorded (event_code=179), count++, work_count = displayed count after event
− press → event recorded (event_code=180), count--, push only if work_count > 0 after decrement
3-sec hold New Task (count > 0) → confirm dialog → current task saved (push_status="pending") →
             next + press creates new task row (task_seq+1 within same MYT day)
App swiped/killed → onTaskRemoved() → synchronous local task/save state if active task count > 0
App opens on new MYT day → rolloverActiveTaskIfStale() finalizes yesterday's active task
App stays open across MYT midnight → periodic 1s tick re-runs rolloverActiveTaskIfStale (Task 2.8 spec §4.1)
Validated Wi-Fi connects (any time, even with app closed) → WorkManager fires PushWorker → drains push_status='pending' tasks
Every 1 min (config) → heartbeat (event_code=35) written while app in foreground AND a task is active
Battery crosses 20%/10% downward → local edge telemetry; Wialon sees battery via `battery` param
HDOP > 5.0 during fix → local telemetry unless Wialon reporting is explicitly designed
```

### Daily rollover

`TaskRepository.rolloverActiveTaskIfStale()` is invoked once at `CountViewModel.init`, before the active-task flow starts collecting. It compares the active task's `task_date` (MYT calendar day) to today's MYT day:

- Match → no-op.
- Stale + `netCount > 0` → `finalizeTask(push_status="pending", save_type="auto_rollover")`. Yesterday's `179`/`180`/`35` rows are still pushed when Wi-Fi reconnects.
- Stale + `netCount == 0` → `finalizeTask(push_status="discarded", save_type="auto_rollover")`. No audit row written.

No event row is generated for rollover (no real location/battery snapshot at app launch). The task row itself is the audit. After rollover, today's first `+` lazy-creates a fresh task with `task_seq=1` for the new MYT day.

---

## Development Phases

### Phase 0 — Repo setup — **COMPLETE** (2026-04-28). See `docs/checkpoints/HAMS_PHASE_0_1.md`.

### Phase 1 — Core offline — **COMPLETE** (manual emulator verification 2026-04-28). See `docs/checkpoints/HAMS_PHASE_0_1.md`.

### Phase 2 — IPS push engine & event capture — **COMPLETE** (field-verified 2026-05-14)

Tasks 2.1–2.8 all complete. 2.1–2.7: `CoordinateConverter`, V6 16-field `IPSFrameBuilder`, `PushEligibility`, `TaskRepository` push flow, local health/heartbeat capture, `WialonIPSClient`, `PushEngine` orchestration + chunking + retry. 2.7.5: GPS streaming + armband UI + DB v2 + daily rollover. 2.8: WorkManager push trigger + manual push button. End-to-end push to Wialon confirmed. See `plans/phase2_ips_push.md` "Implementation log".

### Phase 2 acceptance criteria — all met

- [x] Canonical frame matches byte-for-byte with spec example
- [x] Outbound-approved events (`179`, productive `180`, `35`) push and ack with `#AD#1`
- [x] Local-only codes (`279/280/281/283/284/291/292/293`) never appear in Wialon
- [x] Wi-Fi disconnect mid-batch triggers retry with backoff, no data loss
- [x] Pre-push auto-save fires before push when active task has count > 0

### Phase 3 — UI polish — **COMPLETE** (delivered inside Task 2.7.5 + 2.8, not as a discrete phase)
- [x] Progress / status panel (manual push: `Pending Wi-Fi → Pushing → Completed/Failed`) — `CountScreen.kt`
- [x] Single rolling push notification (Android notification channel) — `PushNotifier.kt`
- [x] Task cache view showing per-task upload status — `CountScreen.kt`
- [x] Battery display in main UI header (BATT pill) — `BatteryMonitor.kt` + `CountScreen.kt`

### Phase 4 — Integration test — live push proven; report-side verification not recorded here

Live end-to-end push to Wialon is confirmed (Phase 2.8 field verification 2026-05-14; provisioning
codes `301`/`303`/`304` confirmed in Wialon 2026-07-07). The geofence/report-template items below
were never ticked off in this repo — treat them as unverified rather than done.

- [ ] Push to `TEST_HAMS_APP_001` (unit ID: `601602811`)
- [ ] Push only approved outbound event types (`179`, productive `180`, `35`) against isolated test units/resources; do not push 279/280/283/284/291/292/293 unless re-approved with Wialon config
- [ ] Verify messages in Wialon UI (`https://pro.navi-agnostics.com`)
- [ ] Verify via REST `messages/load_interval` that all params land in `p` block
- [ ] Verify geofence match in new V6 report template
- [ ] Confirm 1 message per + press, `ffb_cut=1` params populated, course=0

---

## Plugin & Tool Usage (Claude Code)

Available plugins in this session:
- `superpowers` — use for brainstorming and subagent-driven development
- `planning-with-files` — use `/plan` to create structured phase plans as files
- `codex` — use for Codex CLI integration when handing off to implementation
- `commit-commands` — use `/commit` after each phase is complete
- `feature-dev` — use for feature branch development
- `postman` — use for API testing/documentation of Wialon REST endpoints
- `code-review` — use after each phase for review before commit
- `github` MCP — for PR creation and branch management

**Codex handoff rule:** Codex reads this file (`CLAUDE.md`) and `CONTEXT.md` as its primary instructions. Do not duplicate information between the two — CLAUDE.md is the rule set, CONTEXT.md is the reference data.

Karpathy guidelines plugin installed — apply all four principles (Think Before Coding, Simplicity First, Surgical Changes, Goal-Driven Execution) to every code generation task.

---

## Naming Conventions

| Item | Convention |
|---|---|
| Package | `com.klk.hams` |
| Activities | `MainActivity`, `HistoryActivity` |
| Services | `PushService` (foreground), `WifiReceiver` (BroadcastReceiver), `BatteryObserver` (BroadcastReceiver) |
| Frame builder | `IPSFrameBuilder.kt` |
| IPS client | `WialonIPSClient.kt` |
| Push engine | `PushEngine.kt` |
| DB tables | `tasks`, `events` (lowercase snake_case) |
| Config keys | `DEVICE_UNIQUE_ID`, `IPS_HOST`, `IPS_PORT`, `EVENT_CODE_PLUS`, `HEARTBEAT_INTERVAL_MINUTES` |
| Git branches | `phase/1-core-offline`, `phase/2-ips-push`, `phase/3-ui-polish` |

---

## Sensitive Data Rules

- Never hardcode API token or device credentials in source files
- Use `.env` or `local.properties` for secrets — add to `.gitignore`
- The Wialon test token is in `docs/credentials.env.example` (redacted) — see CONTEXT.md Section 3
- If token is ever committed, rotate immediately via Wialon admin
- `local.properties` is the runtime source for dev credentials (`WIALON_TOKEN`, `DEVICE_UNIQUE_ID`). Expose them to Kotlin via `buildConfigField` in `app/build.gradle.kts` (requires `buildFeatures { buildConfig = true }`). Do NOT read `local.properties` at runtime and do NOT bundle it into APK resources.

---

## Reference Documents

| File | Purpose | Read when |
|---|---|---|
| `CLAUDE.md` | Android build rules (this file) | Building or modifying the app |
| `CONTEXT.md` | Environment, API, Wialon admin spec | Integrating with Wialon |
| `docs/HAMS_APP_REQUIREMENTS.md` | Functional & non-functional requirements | Reviewing feature scope |
| `docs/HAMS_EVENT_CODE_DICTIONARY.md` | **Canonical event code list** | Any question about event semantics |
| `docs/checkpoints/HAMS_API_TESTING.md` | V6 test evidence & decision log (current SoT) | Resolving conflicts, auditing decisions |
| `docs/credentials.env.example` | Redacted credential template | Setting up local dev |

### Reading order for a new developer

1. `docs/HAMS_APP_REQUIREMENTS.md` — understand what HAMS V2 does
2. `CONTEXT.md` — understand the Wialon side and protocol
3. `docs/HAMS_EVENT_CODE_DICTIONARY.md` — understand the event vocabulary
4. `CLAUDE.md` (this file) — understand how to build
5. `docs/checkpoints/HAMS_API_TESTING.md` — understand what's been proven

---

*Last updated: 2026-07-24 | Maintained by: WYH | Version: V6*
