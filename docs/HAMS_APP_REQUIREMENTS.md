> **⚠️ V6 Supersession Notice (2026-04-23)**
>
> This document remains valid for feature scope (FR-01 through FR-10 —
> what the app does, worker-facing features, task lifecycle, acceptance
> criteria). However, all protocol-layer details (IPS frame format,
> SQLite schema, event handling, push policies) are pre-V6 and superseded.
>
> For implementation details defer to:
> - IPS frame format → CONTEXT.md Section 3
> - Event codes → docs/HAMS_EVENT_CODE_DICTIONARY.md
> - SQLite schema → CLAUDE.md SQLite Schema section
> - Build rules → CLAUDE.md
> - Test evidence → docs/checkpoints/HAMS_API_TESTING.md
>
> **2026-04-28 decision note:** C9 is resolved as GPS-per-press and GPS is mandatory for counting. On launch, the app must request/check location permission and device location services; if denied/off, show a blocking message and exit. The `+` button must not increment or record a cut without a valid coordinate.

# HAMS V2 Mobile App — Requirements & Development Reference

## Purpose

This document is the single source of truth for the HAMS V2 Mobile App. It is designed to be read by Claude Code (terminal) and developers working in VS Code. All requirements, architecture decisions, data models, API details, and development phases are defined here.

## Project Summary

Build an Android mobile app that replaces the MeiTrack P99L hardware GPS tracker used by oil palm estate workers for FFB (Fresh Fruit Bunch) harvesting count tracking.

The app replicates the P99L's core function (pressing a button to count FFB cuts with GPS + timestamp) while adding task management, offline caching, and Wi-Fi-based batch data push to the Wialon platform.

The downstream pipeline (Wialon report template → N8N → PostgreSQL) remains unchanged. This app is the data source layer only.

---

## Target Device

- **Phone:** Oppo A5i
- **OS:** Android 15
- **Display:** 6.6"
- **Rating:** IP-54
- **Cost:** RM 400/unit
- **Usage:** Daily use per worker, issued at morning muster, returned end of day
- **Replaces:** MeiTrack P99L (proprietary, no display, 3 buttons, IP-69, RM 380/unit)

---

## Architecture Overview

```
┌─────────────────────────────────────┐
│           Phone (Offline)           │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  + button press             │    │
│  │  → GPS lat/lon + timestamp  │    │
│  │  → store to SQLite          │    │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  Task management            │    │
│  │  → start/end time           │    │
│  │  → task sequence number     │    │
│  │  → count per task           │    │
│  │  → device ID                │    │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │  Local SQLite cache         │    │
│  │  → tasks table              │    │
│  │  → events table             │    │
│  └─────────────────────────────┘    │
│                                     │
└──────────────┬──────────────────────┘
               │ Wi-Fi connected
               ▼
┌─────────────────────────────────────┐
│       Wialon IPS Push (TCP)         │
│  or exchange/import_messages (REST) │
│  → batch push cached tasks          │
│  → retry on failure                 │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│         Wialon Cloud                │
│  → receives as unit messages        │
│  → auto-matches GPS to geofence     │
│  → PM08B_17 (auto-resolved)         │
│  → report template groups by        │
│    unit + date                      │
│  → downstream: N8N → PostgreSQL     │
└─────────────────────────────────────┘
```

**Key principle:** The app only pushes raw data (GPS + count + timestamp). Wialon handles all geofence matching and report grouping. The app never needs to know geofence names.

---

## Functional Requirements

### FR-01: Count Display

- **Priority:** 1 — Core
- **Description:** Display the current FFB count for the active task on screen.
- **Acceptance criteria:**
  - Count displays as a large number (minimum 72pt equivalent), centered on screen
  - Count value range: 0 to 9999
  - If count reaches 9999, the + button is disabled and a warning is shown
  - Count persists through app backgrounding and screen rotation
  - When a new task is created (FR-05), display resets to 0 for the new task
  - Previous task count is saved to local cache automatically before reset

### FR-02: Add Count (+ Button)

- **Priority:** 1 — Core
- **Description:** A large, prominent + button that adds 1 to the current task count (or `plus_scale` units when scale > 1 — see FR-11).
- **Acceptance criteria:**
  - Single tap adds exactly `plus_scale` to the displayed count (default 1)
  - Each press generates one local record per emitted unit, sharing the same timestamp/GPS/battery snapshot; `work_count` increments per row
  - Button provides haptic feedback (vibration) and visual feedback (brief color change) on press
  - Button remains responsive with zero perceptible lag, even offline
  - Button is large enough for gloved/dirty hands (minimum 80dp x 80dp touch target)
  - Press-and-hold auto-repeat is active only when `plus_scale = 1`
- **Resolved:** C9 — each + press needs GPS coordinates. GPS access is mandatory; if unavailable, app exits or blocks counting rather than writing a cut without coordinates.

### FR-03: Subtract Count (− Button)

- **Priority:** 1 — Core
- **Description:** A − button for mistake correction. Subtracts up to `minus_scale` units (default 1) from the current task count — see FR-11.
- **Acceptance criteria:**
  - Single tap subtracts `min(minus_scale, net_count)` from the displayed count (cap-at-remaining)
  - Count cannot go below 0. If count is 0, the − button is disabled
  - Each press generates one local record per emitted unit, sharing timestamp/GPS/battery; `work_count` decrements per row; the row that lands on `work_count = 0` is marked local-only (self-cancelling pair rule applied per-row)
  - Button is visually distinct from + button (different color and/or position) to prevent accidental presses
  - Button should be smaller or less prominent than + button to reduce accidental correction
  - Press-and-hold auto-repeat is active only when `minus_scale = 1`

### FR-04: Battery Display

- **Priority:** 1 — Core
- **Description:** Show the phone battery percentage on the app screen.
- **Acceptance criteria:**
  - Battery percentage displayed in the app header/status area at all times
  - Updates in real-time as battery drains or charges
  - Visual warning (color change to red) when battery drops below 20%
  - If battery drops below 10%, show a prominent warning overlay

### FR-05: New Task Creation

- **Priority:** 1 — Core
- **Description:** A button to create a new task. Requires 5-second long press to prevent accidental activation.
- **Acceptance criteria:**
  - New Task button requires a 5-second continuous hold before activation
  - During the hold, a visual progress indicator shows the 5-second countdown (filling circle or progress ring)
  - After 5 seconds, a confirmation dialog appears: "Create new task and save current task? Yes / No"
  - If Yes: current task saved to local cache with status "pending", display count resets to 0, new task created with auto-incremented sequence number
  - If No: dialog dismisses, everything unchanged
  - If the worker releases the button before 5 seconds, nothing happens
  - Each saved task includes: task sequence number, device ID, total + count, total − count, net count, all individual event records (timestamp + GPS per press), task start timestamp, task end timestamp
- **2026-04-28 implementation decision:** Lazy task creation. No SQLite row is created when the app opens or when New Task is confirmed. The task row (and its `new_task` marker event, event_code=281) is created atomically on the **first valid `+` press** of that task. Confirming New Task only finalises the *previous* task; the next task row does not exist until the worker presses `+`. `task_seq` resets per MYT day (`Asia/Kuala_Lumpur` timezone); the first task each day gets seq=1.

### FR-06: Full Offline Operation

- **Priority:** 1 — Core
- **Description:** All counting and task management (FR-01 through FR-05) must work completely offline.
- **Acceptance criteria:**
  - App launches and operates without any network connection
  - All data stored locally in SQLite
  - Local storage can hold minimum 30 days of data (~50 tasks/day, ~500 presses/day)
  - App does not crash, freeze, or degrade performance when offline
  - GPS acquisition works via device GPS hardware (no network-assisted GPS required)
- **Decision:** Use the V6 Room/SQLite schema described in `CLAUDE.md` and Phase 1. No blocker remains.

### FR-07: Push to Wialon Server

- **Priority:** 3 — Integration
- **Description:** When the phone connects to validated Wi-Fi, automatically push all cached **finalized** (`push_status='pending'`) task data to Wialon. **Active tasks are never auto-pushed** — they remain under worker control until explicitly saved (NEW TASK 5 s hold), Android-lifecycle-saved (app swipe → `auto_killed`), or rolled over (day rollover → `auto_rollover`). See `docs/superpowers/specs/2026-05-08-push-and-wifi-design.md` for full design (Task 2.8).
- **Acceptance criteria:**
  - Push triggers automatically via `WorkManager` (`OneTimeWorkRequest` + `NetworkType.UNMETERED`) — fires whether the app is foreground, background, swiped, or after a reboot
  - Push targets only `push_status='pending'` tasks; active tasks are insulated
  - Manual "Push now" alternative: 5 s hold + confirmation, UI locks, status panel `Pending Wi-Fi → Pushing → Completed/Failed` mirrored in system notification, 30-min hard timeout
  - Pending events pushed in batches (default 10 messages per batch/session, configurable)
  - After each successful batch, check for remaining and push next batch
  - Successfully pushed tasks marked as `push_status='uploaded'` in cache (not deleted — keep for audit)
  - Failure cases: cache always preserved; user notified ("Leftovers in cache, try again later"); auto-push remains active in background
  - Failed tasks remain as "pending" and retry on next cycle
- **Decision:** Use Wialon IPS v1.1 TCP on port 20332 with the V6 16-field frame and named params. Default push batch is 10 messages.

### FR-08: Push Progress Bar

- **Priority:** 3 — Integration
- **Description:** Show progress indicator during data push.
- **Acceptance criteria:**
  - Progress bar appears in the app during push
  - Shows overall progress: "Uploading tasks... X / Y"
  - Updates after each batch completes
  - Disappears when all tasks uploaded or enters retry state
  - Worker can continue counting while push runs in background

### FR-09: Push Notification

- **Priority:** 4 — Polish
- **Description:** Single rolling Android notification that updates through push lifecycle.
- **Notification states:**
  - **Uploading:** "Uploading tasks... X / Y" — persistent, not dismissable
  - **Success:** "All Y tasks uploaded successfully." — auto-dismiss after 10s, tap to dismiss
  - **Partial:** "X/Y uploaded. Z pending — retrying..." — persistent, not dismissable
  - **Failed:** "Upload failed after N attempts. Z pending." — persistent, tap to dismiss
- **Implementation:** reuse the same Android notification ID for all updates. Worker sees one notification, never a flood.
- **Decision:** App notification is device-only. Supervisor alerts, if needed later, are Wialon-side notification rules. Default max retry count is 5.

### FR-10: Auto-Save on App Removal

- **Priority:** 1 — Core
- **Description:** When the app is swiped away from Android recent apps (cache clear), or the system kills the app, auto-save the current active task before the process dies.
- **Acceptance criteria:**
  - Detect app removal via `onTaskRemoved()` in a foreground service or `onDestroy()` lifecycle
  - If current task has count > 0 and is not yet saved, save to SQLite with `save_type = "auto_killed"`
  - Save must complete before process termination (use synchronous write, not async)
  - On next app launch, the auto-saved task appears in cache as "pending"
  - No data loss from accidental swipe-away, system kill, or manual cache clear
  - This applies to all three Android navigation buttons: back (if exits app), home (backgrounding), and recents/cache clear (swipe away)

### FR-11: Per-Press Count Scale (AR-01, reopened 2026-05-29)

- **Priority:** 2 — Productivity
- **Description:** Worker-adjustable per-press multiplier (1–10) applied independently to `+` and `−`, exposed via a gear icon on the count screen. Allows bulk-logging during high-throughput harvest periods without dozens of taps.
- **Acceptance criteria:**
  - Gear icon opens a settings panel with two independent `− N +` controls for `plus_scale` and `minus_scale`, clamped 1–10
  - Values persist across app restart and force-stop (SharedPreferences)
  - Scale change mid-task does not modify any existing DB row or the displayed count
  - One `+` tap at scale `N` inserts up to `N` rows with `event_code=179`, shared timestamp/GPS/battery, `work_count` incrementing 1..N (capped at `MAX_COUNT_PER_TASK`)
  - One `−` tap at scale `N` inserts `min(N, net_count)` rows with `event_code=180`, shared timestamp/GPS/battery, `work_count` decrementing per row; the row landing on `work_count=0` is local-only
  - Hero `+` and small `−` buttons show a `×N` badge when their respective scale > 1
  - Auto-repeat is disabled for any direction whose scale > 1
  - Heartbeat / battery-edge / GPS-degraded / auto-save / new-task events are never scaled
- **Reference:** `docs/superpowers/specs/2026-05-29-scaled-count-design.md`

---

## Non-Functional Requirements

### NF-01: Android Compatibility

- **Priority:** 1 — Core
- **Target:** Oppo A5i, Android 15
- **Minimum:** Android 14 (API level 34)
- **Decision:** Minimum Android version is API 34. Keep `targetSdk=35` unless KC updates the device policy.

### NF-02: Performance — Offline Smoothness

- **Priority:** 1 — Core
- **Acceptance criteria:**
  - Button press to count display update: < 50ms
  - Local database write per event: < 100ms
  - App cold start to ready state: < 3 seconds
  - No UI jank or dropped frames during counting
  - GPS acquisition: < 10 seconds first fix; subsequent fixes are continuous (BALANCED stream, ~2 s interval) so the press path reads the snapshot synchronously with **zero per-press GPS latency**
  - GPS lock state must be visible in the UI at all times (persistent indicator); the `+` / `−` buttons are disabled when the snapshot is older than `LOCATION_STREAM_STALENESS_MS` (default 5 s)
  - Memory footprint: < 100MB RAM

### NF-03: Background Wi-Fi Monitoring

- **Priority:** 3 — Integration
- **Acceptance criteria:**
  - Use BroadcastReceiver or WorkManager to detect Wi-Fi connectivity changes
  - Push starts within 30 seconds of Wi-Fi connection established
  - Background process < 2% battery per hour when idle with Wi-Fi monitoring
  - Works with Android 14/15 background execution limits (foreground service with notification if needed)

### NF-04: Auto-Save Before Push *(SUPERSEDED 2026-05-08)*

- **Priority:** 3 — Integration
- **Status:** **SUPERSEDED** by Task 2.8 spec (`docs/superpowers/specs/2026-05-08-push-and-wifi-design.md`). The push-and-task-lifecycle separation principle (push touches only `push_status='pending'` tasks; active tasks are insulated) replaces this requirement. Active tasks transition to pending only via worker action (NEW TASK 5 s hold), app swipe (`auto_killed`), or day rollover (`auto_rollover` — including the periodic 1 s in-process check). The legacy `auto_wifi` save_type remains in the dictionary for backwards-compat but is **not produced** by the new push flow.

### NF-05: Push Retry with Failure Handling

- **Priority:** 3 — Integration
- **Retry logic:**
  - On failure, wait 30 seconds, then retry
  - Exponential backoff: 30s, 60s, 120s, 240s, max 5 minutes
  - After N consecutive failures (default 5), stop retrying and show failure notification
  - Wi-Fi disconnect + reconnect resets retry counter
  - If a single task fails repeatedly, isolate it and continue pushing others
- **Decision:** Default max retry count is 5.

### NF-06: Batch Size Limiting

- **Priority:** 3 — Integration
- **Acceptance criteria:**
  - Default batch size: 10 messages per push batch/session
  - Configurable in app settings (range: 5 to 50)
  - After each batch: update notification, check remaining, push next
  - If one task in a batch fails, mark it failed and continue with remaining batches
- **Decision:** Default batch size is 10 messages per push batch/session.

---

## Data Model (Local SQLite)

### Table: tasks

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| task_id | INTEGER PK | No | Auto-increment local ID |
| task_seq | INTEGER | No | Sequential task number for the day (1, 2, 3...) |
| device_id | TEXT | No | Phone/unit identifier for Wialon |
| oc | TEXT | No | Operating centre code, e.g. "154" |
| worker_id | TEXT | Yes | Worker identifier (depends on C8) |
| plus_count | INTEGER | No | Total + button presses |
| minus_count | INTEGER | No | Total − button presses |
| net_count | INTEGER | No | plus_count − minus_count (displayed value) |
| started_at | TEXT (ISO 8601) | No | Task creation timestamp |
| ended_at | TEXT (ISO 8601) | Yes | Task save timestamp (null if active) |
| save_type | TEXT | Yes | "manual" / "auto_wifi" / "auto_killed" |
| push_status | TEXT | No | "active" / "pending" / "uploading" / "uploaded" / "failed" |
| push_attempts | INTEGER | No | Number of push attempts, reset on Wi-Fi reconnect |
| pushed_at | TEXT (ISO 8601) | Yes | Timestamp of successful push |

### Table: events

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| event_id | INTEGER PK | No | Auto-increment |
| task_id | INTEGER FK | No | References tasks.task_id |
| event_type | TEXT | No | "plus" or "minus" |
| timestamp | TEXT (ISO 8601) | No | Device clock time of button press |
| latitude | REAL | Yes | GPS latitude (V6 app must not record a + cut if unavailable) |
| longitude | REAL | Yes | GPS longitude (V6 app must not record a + cut if unavailable) |
| count_after | INTEGER | No | Net count value after this event |

### Task Lifecycle

1. Worker opens app → task created with `push_status = "active"`, `task_seq = 1`
2. Worker presses +/− → events written, counts updated in tasks table
3. Worker holds New Task button → current task `push_status = "pending"`, new task created with `task_seq + 1`
4. App swiped away / killed → active task auto-saved with `save_type = "auto_killed"`, `push_status = "pending"`
5. App opens on a new MYT day (or 1s tick crosses midnight) → yesterday's active task → `save_type = "auto_rollover"`, `push_status = "pending"`
6. Validated Wi-Fi connects (any time, even with app closed) → WorkManager fires PushWorker → drains all `push_status='pending'` tasks. Active tasks are NEVER auto-pushed (Task 2.8 spec).
7. Push engine: pending → uploading → uploaded (or failed)
8. Uploaded tasks stay in DB for 7 days (configurable) for audit, then auto-purge

---

## Data Pushed to Wialon (Per Task)

Each task pushed to Wialon contains:

| Field | Type | Source | Description |
|-------|------|--------|-------------|
| device_id | string | App config | Phone identifier matching Wialon unit name |
| task_seq | integer | App-generated | Task sequence number for the day |
| started_at | ISO 8601 | App clock | Task start timestamp |
| ended_at | ISO 8601 | App clock | Task end timestamp |
| plus_count | integer | Calculated | Total + presses |
| minus_count | integer | Calculated | Total − presses |
| net_count | integer | Calculated | Final count |
| pushed_at | ISO 8601 | App clock | When data was sent to server |
| events[] | array | SQLite | Array of {type, timestamp, lat, lon, count_after} |

### What Wialon does with this data (not app's job):

- Receives as unit messages (each event becomes a Wialon message with position + sensor value)
- Auto-matches GPS coordinates to geofence polygons (e.g. lat 2.268721, lon 103.282985 → `PM08B_17`)
- V6 report template `HAMS_FFB_Cut_Count_V6` filters rows where `ffb_cut=1`
- Downstream N8N pipeline pulls those cut-count rows into PostgreSQL `HAMSTaskCount`

---

## Wialon Integration

### Push Method (current V6 decision)

**Wialon IPS Protocol (selected):**
- Raw TCP socket connection to Wialon IPS server
- Host/port: `185.213.1.24:20332`
- Message format: V6 16-field IPS v1.1 `#D#` frame with named params (`ffb_cut`, `battery`, `event_code`, `work_count`)
- Each phone registered as a "unit" in Wialon with unique ID
- `+` press encoded as `ffb_cut=1` in the params block; course remains real heading, normally `0`
- Needs from admin: unit setup, V6 sensor mappings, and V6 report template ID

**REST API is verification-only.** The app must not push production data through `exchange/import_messages`.

### Test Strategy

- Request Wialon admin to create a **test unit** (e.g. `TEST_HAMS_APP_001`) in a separate group
- Push test data to test unit only — production data untouched
- Delete test unit when testing complete — all its messages go with it
- Use Postman for REST API testing, Python socket script for IPS protocol testing

---

## Decision & Operational Items

Defaults below are accepted for development. Operational questions route to KC, and Wialon setup details route to the Wialon admin.

| ID | Item | Owner | Blocks | Default |
|----|------|-------|--------|---------|
| C1 | Wialon push method: IPS protocol vs REST API | Wialon Admin | Phase 2 | **Resolved: IPS v1.1 TCP** |
| C2 | Wialon payload format (exact fields + types) | Wialon Admin | Phase 2 | **Resolved: V6 16-field frame + params** |
| C3 | Offline data template structure | Project Lead | Phase 1 | **Resolved: V6 Room/SQLite schema** |
| C4 | Notification target: device only or also supervisor? | Project Lead / KC | Phase 3 | **Resolved: device only; Wialon rules later if needed** |
| C5 | Max retry count before failure notification | Project Lead | Phase 3 | 5 retries |
| C6 | Batch size per push | Project Lead / Dev | Phase 2 | 10 messages |
| C7 | Minimum Android version | Project Lead | Phase 1 | **Resolved: Android 14 (API 34)** |
| C8 | Worker identity: login vs device assignment | KC | Phase 2 | Device assignment |
| C9 | GPS per press vs per task only | Project Lead / KC | Phase 1 | **Resolved: GPS per press, mandatory** |
| C10 | IP-54 durability: acceptable or rugged case? | Estate Manager | Phase 0 | Open hardware decision, not app-blocking |
| C11 | Kiosk mode / app lock needed? | KC | Phase 3 | No |
| C12 | OC scope: 154 only or multi-OC? | KC | Phase 1 | 154 only |
| C13 | Device ID format: IMEI, custom, or Wialon-assigned? | Wialon Admin / KC | Phase 1 | Custom ID set at setup |
| AR-01 | Per-press count scale (worker-set multiplier 1–10) | Project Lead | Phase 3 | **Reopened 2026-05-29: IN-SCOPE. See FR-11.** |

---

## Development Phases

### Phase 0 — Setup & Planning

- Initialize repo, project structure, CLAUDE.md
- Finalize tech stack (Kotlin native recommended)
- Set up Postman workspace for Wialon API testing
- Coordinate Wialon admin setup and route unresolved operational questions to KC
- Generate finalized dev doc

### Phase 1 — Offline Counting App (no server needed)

Build order:

| Step | Req | What to Build | Depends On |
|------|-----|---------------|------------|
| 1.1 | FR-01 | Count display: large number (0–9999), centered, sunlight-readable | — |
| 1.2 | FR-02 | + button: increment, haptic feedback, store event to SQLite | 1.1 |
| 1.3 | FR-03 | − button: decrement (min 0), same event recording | 1.2 |
| 1.4 | FR-04 | Battery display: phone battery % in header, color warning | 1.1 |
| 1.5 | FR-05 | New Task: 5s hold + progress ring + confirm dialog + save + reset | 1.2 |
| 1.6 | FR-10 | Auto-save on app removal: onTaskRemoved / onDestroy saves active task | 1.5 |
| 1.7 | FR-06 | Verify full offline: airplane mode test, GPS test, 30-day capacity | 1.6 |
| 1.8 | NF-01 | Test on Oppo A5i, confirm Android 14 compat | 1.7 |
| 1.9 | NF-02 | Performance benchmarks: latency < 50ms, cold start < 3s | 1.8 |

**Output:** working APK, field-testable with workers, data stays on device.

### Phase 2 — Wialon Integration (Wi-Fi Push)

| Step | Req | What to Build | Depends On |
|------|-----|---------------|------------|
| 2.1 | NF-03 | Background Wi-Fi monitoring | Phase 1 done |
| 2.2 | NF-04 | Auto-save before push | 2.1 |
| 2.3 | FR-07 | Push engine: batch IPS frames, call Wialon, mark uploaded | 2.2 |
| 2.4 | FR-08 | Progress bar UI: X/Y messages, updates per batch | 2.3 |
| 2.5 | NF-05 | Retry logic: exponential backoff, isolate failing tasks | 2.3 |
| 2.6 | NF-06 | Batch size config: default 10, settings (5–50) | 2.3 |

**Output:** app pushes to Wialon test unit, data appears in report correctly.

### Phase 3 — Notifications & Polish

| Step | Req | What to Build | Depends On |
|------|-----|---------------|------------|
| 3.1 | FR-09 | Rolling notification: 4 states, single notification ID | Phase 2 done |
| 3.2 | — | Cache viewer: saved tasks, status, count, timestamp | Phase 2 done |
| 3.3 | — | Settings screen: batch size, retry count, device ID, version | Phase 2 done |
| 3.4 | — | Kiosk mode (if C11 confirmed) | 3.3 |

**Output:** complete app ready for field pilot.

### Phase 4 — Integration Test & Handoff

- Push full V6 event set to `TEST_HAMS_APP_001`
- Verify Wialon Messages tab, REST `messages/load_interval`, and `HAMS_FFB_Cut_Count_V6`
- Record results in `docs/integration_test/phase4_v6_results.md`
- Produce final handoff notes after pass/fail is known

### Parallel: Wialon Admin Setup (non-dev)

- Create test unit for dev testing
- Register phone units in Wialon
- Assign to OC 154 unit group
- Map V6 sensor parameters (`ffb_cut`, `battery`, optional `work_count`, optional `event_code`)
- Build `HAMS_FFB_Cut_Count_V6` and record its template ID
- Validate test data appears in the V6 report before production rollout

---

## Tech Stack (Recommended)

| Component | Choice | Reason |
|-----------|--------|--------|
| Language | Kotlin | Native Android, best GPS/battery/lifecycle control |
| Min SDK | API 34 (Android 14) | Target device is Android 15, one version back for safety |
| Database | SQLite via Room | Lightweight, offline-first, type-safe queries |
| GPS | Fused Location Provider — continuous BALANCED stream (`LocationStream`), app-scoped, ref-counted by `"foreground"` and `"task_active"`; press path reads `snapshotFlow.value` synchronously. Spec: `docs/superpowers/specs/2026-05-05-gps-streaming-design.md` | Eliminates per-press latency under fast clustered presses while moving (Scenario C); preserves doc-level "no event without coordinate" invariant via in-UI staleness gate |
| Networking | Raw TCP socket (Wialon IPS v1.1) | V6 decision; REST is external verification only |
| Background | WorkManager + Foreground Service | Wi-Fi monitoring within Android 14/15 limits |
| UI | Jetpack Compose | Matches the existing Android scaffold |
| DI | Hilt | Standard for Android, clean testability |
| Testing | JUnit + Espresso | Unit tests for logic, UI tests for buttons |

---

## UI Layout (Conceptual)

```
┌────────────────────────────────┐
│  🔋 85%              Task #3  │  ← header: battery + task number
│                                │
│                                │
│           0047                 │  ← large count display (72pt+)
│                                │
│                                │
│   ┌──────────┐  ┌──────────┐  │
│   │          │  │          │  │
│   │    +     │  │    −     │  │  ← large buttons (80dp+ touch target)
│   │          │  │          │  │     + is prominent, − is subdued
│   └──────────┘  └──────────┘  │
│                                │
│   ┌────────────────────────┐  │
│   │   ● ● ● NEW TASK ● ● ●│  │  ← long press 5s with progress ring
│   └────────────────────────┘  │
│                                │
│  ┌──────────────────────────┐ │
│  │ ↑ Uploading 10/35...     │ │  ← progress bar (only during push)
│  └──────────────────────────┘ │
└────────────────────────────────┘
```

---

## File Structure (Proposed)

```
hams-app/
├── CLAUDE.md                    ← this file
├── app/
│   ├── src/main/
│   │   ├── java/.../hams/
│   │   │   ├── data/
│   │   │   │   ├── db/          ← Room database, DAOs
│   │   │   │   ├── model/       ← Task, Event entities
│   │   │   │   └── repository/  ← TaskRepository
│   │   │   ├── push/
│   │   │   │   ├── WialonClient.kt      ← IPS or REST push client
│   │   │   │   ├── PushEngine.kt        ← batch logic, retry
│   │   │   │   ├── PushWorker.kt        ← Task 2.8 — WorkManager CoroutineWorker
│   │   │   │   ├── PushController.kt    ← Task 2.8 — app-scope state owner
│   │   │   │   ├── PushRepositoryImpl.kt← Task 2.8 — TaskRepository → PushRepository adapter
│   │   │   │   └── PushNotifier.kt      ← Task 2.8 — push-channel notifications
│   │   │   ├── ui/
│   │   │   │   ├── count/       ← main counting screen
│   │   │   │   ├── settings/    ← settings screen
│   │   │   │   └── cache/       ← cache viewer screen
│   │   │   ├── service/
│   │   │   │   └── HamsService.kt       ← foreground service
│   │   │   └── HamsApp.kt              ← application class
│   │   └── res/
│   └── src/test/                ← unit tests
├── docs/
│   ├── REQUIREMENTS.md          ← copy of requirements section
│   ├── API_MAPPING.md           ← Wialon API details when confirmed
│   └── SETUP_GUIDE.md           ← Wialon admin setup steps
├── postman/
│   └── wialon-hams.json         ← Postman collection
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Testing Checklist

### Phase 1 — Offline Tests

- [ ] App launches in airplane mode
- [ ] + button increments count, event stored in SQLite with GPS
- [ ] − button decrements count, cannot go below 0
- [ ] Battery % displays and updates
- [ ] New Task: 5s hold → confirm → save → reset to 0
- [ ] Swipe app from recents → relaunch → previous task saved in cache
- [ ] System kill app → relaunch → previous task saved in cache
- [ ] Create 50+ tasks, 500+ events → no lag, no crash
- [ ] Screen rotation preserves count
- [ ] GPS works without network (device GPS only)
- [ ] Cold start < 3 seconds
- [ ] Button press to display update < 50ms

### Phase 2 — Push Tests *(updated under Task 2.8 spec)*

- [ ] Validated Wi-Fi connect triggers WorkManager-driven push (within ~30 s of OS scheduler picking it up)
- [ ] Push works with the app fully closed / swiped from recents
- [ ] Active task is NOT auto-saved before push (insulation rule)
- [ ] Tasks with `push_status='pending'` (manual save / `auto_killed` / `auto_rollover`) are pushed
- [ ] Tasks pushed in batches of 10 (one TCP session per chunk)
- [ ] System notification cycles `Pushing → Completed` (auto flow) or `Pending Wi-Fi → Pushing → Completed/Failed` (manual flow)
- [ ] In-app pending badge `↑ N` visible only when N > 0
- [ ] Successful tasks marked `push_status='uploaded'` in local DB
- [ ] Failed tasks remain `pending`; cache preserved; auto-push retries
- [ ] Manual push hits 30-min budget on no-Wi-Fi → `Failed("timeout")`, leftovers message shown
- [ ] App force-killed mid-push → next launch shows last-push outcome dialog
- [ ] Wi-Fi disconnect + reconnect resets retry counter
- [ ] Data appears correctly in Wialon test unit
- [ ] Report template shows pushed data with geofence matching

### Phase 3 — Notification Tests

- [ ] Single notification during push, updates per batch
- [ ] Success notification auto-dismisses after 10s
- [ ] Partial failure notification stays persistent
- [ ] Failed notification stays until tapped
- [ ] Cache viewer shows all tasks with correct status
- [ ] Settings: batch size change takes effect on next push

---

## Glossary

| Term | Meaning |
|------|---------|
| FFB | Fresh Fruit Bunch — the palm oil fruit bunches being harvested |
| OC | Operating Centre — estate organizational unit (e.g. OC 154) |
| Field | A named area within an estate (e.g. PM08B) |
| Task | A numbered subdivision of a field (e.g. PM08B_17) |
| Cut | A harvester cutting an FFB from the tree (tracked by + button) |
| Collected | A collector loading an FFB onto transport (tracked separately) |
| Wialon IPS | Wialon Integration Protocol Service — TCP protocol for GPS trackers |
| N8N | Workflow automation platform used for data pipeline |
| HAMSTaskCount | PostgreSQL table storing daily FFB counts per task area |
| P99L | MeiTrack P99L — the hardware tracker being replaced by this app |
| Muster | Morning roll call where devices are issued to workers |
