 bc# HAMS V2 — Event Code Dictionary

**Document Version:** 1.5
**Last Updated:** 2026-07-23
**Status:** Canonical reference for all HAMS V2 event codes

> **Source-of-truth note (v1.2, 2026-04-30):** `event_code` is an outbound
> Wialon/reporting semantic, not HAMS's private app event enum. HAMS may keep
> local lifecycle/health events in SQLite, but only the validated reporting
> values listed under "Approved outbound Wialon event_code values" and the final
> diagnostics telemetry Option B section should be sent to Wialon unless KC/Wialon
> admin deliberately creates matching reports, sensors, or notification rules.

---

## All Event Codes at a Glance (master reference — v1.5, 2026-07-23)

Every code the app knows, grouped. "Pushed?" = sent to Wialon in the `event_code`
param. Local-only codes live in SQLite for audit/UI and are never pushed.

| Code | Name | Family | Pushed to Wialon? |
|---|---|---|---|
| **179** | FFB cut (`+` press) | Counting | **Yes** — always |
| **180** | FFB correction (`−` press) | Counting | **Yes** — only if `work_count > 0` after decrement |
| **35** | Heartbeat / periodic beacon | System | **Yes** |
| **29** | Boot / reboot | Diagnostics (Option B) | **Yes** |
| **40** | Shutdown | Diagnostics | **Yes** |
| **24** | GPS lost | Diagnostics | **Yes** |
| **25** | GPS recovery | Diagnostics | **Yes** |
| **41** | Stop moving | Diagnostics | **Yes** |
| **42** | Start moving | Diagnostics | **Yes** |
| **26** | Screen off | Diagnostics | **Yes** |
| **27** | Screen on | Diagnostics | **Yes** |
| **43** | Power connected | Diagnostics | **Yes** |
| **44** | Power disconnected | Diagnostics | **Yes** |
| **301** | `binding_released` (admin freed → phone flushes + logs out) | Provisioning (3xx) | **Yes** |
| **302** | `work_stranded` (OTP release while holding unsent cuts) | Provisioning (3xx) | **Yes** |
| **303** | `device_bound` (OTP pair) | Provisioning | **Yes** |
| **304** | `device_unbound` (OTP release with no unsent cuts) | Provisioning | **Yes** |
| **281** | New task created | Local lifecycle | No |
| **283** | Auto-save on kill (`onTaskRemoved`) | Local lifecycle | No |
| **284** | Auto-save pre-push | Local lifecycle | No |
| **291** | Battery warning (<20%) | Local health | No |
| **292** | Battery critical (<10%) | Local health | No |
| **293** | GPS degraded (HDOP > 5) | Local health | No |
| **279** | Plus press (legacy dev) | Local (suspended) | No |
| **280** | Minus press (legacy dev) | Local (suspended) | No |

**Totals:** 17 pushed (`179`/`180`/`35` + 10 Option B + `301`/`302`/`303`/`304`) · 8 local-only.

`302` previously meant `binding_taken` (phone-local, removed 2026-07-07) and was
reassigned to `work_stranded` on 2026-07-23 — see the historical-collision note
under the 3xx family. `bound_other` still stops + logs out with no push.

The sections below give the detail, rules, and rationale per family.

---

## Diagnostics Telemetry Outbound Codes (Option B - FINAL 2026-07-02)

New outbound diagnostics/behaviour telemetry. Each action pushes under its **own**
`event_code` (no `diag_type` param), sourced from the MeiTrack P99G GPRS list;
power 43/44 are HAMS-custom (P99G has no charging event). This **admits new
outbound codes beyond 179/180/35** for these diagnostic telemetry values.
Option B is device + Wialon verified; no owner-supplied code file is pending.

| event_code | Action | Source |
|---|---|---|
| **29** | Boot / device reboot | P99G Device Reboot |
| **40** | Shutdown / power off | P99G Power Off |
| **24** | GPS lost | P99G GPS Signal Lost |
| **25** | GPS recovery | P99G GPS Signal Recovery |
| **41** | Stop moving | P99G Stop Moving |
| **42** | Start moving | P99G Start Moving |
| **26** | Screen off | P99G Enter Sleep (analog) |
| **27** | Screen on | P99G Exit Sleep (analog) |
| **43** | Power connected | HAMS-custom |
| **44** | Power disconnected | HAMS-custom |

Wialon-side sensors/reports must be configured per code for these to surface. No
collision with 179/180/35 or local-only 279/280/281/283/284/291/292/293.

---

## Provisioning Revalidation Codes (3xx family — 2026-07-07)

Emitted by the app's binding-revalidation gate (launch / before-push / periodic
`check_binding` against the n8n `verify` webhook). Isolated `3xx` band — no
collision with any existing family.

| Code | Meaning | Pushed to Wialon? |
|---|---|---|
| **301** | `binding_released` — unit was freed by an admin (still unowned). Flushed with pending cuts before logout. | **Yes** (unit is free; safe) |
| **302** | `work_stranded` — device unbound via OTP while still holding unsent harvest (`lost_cuts > 0`). Mutually exclusive with `304`: a release emits one or the other. Carries `lost_tasks` and `lost_cuts`. | **Yes** (pushed to the unit being left, before the binding clears) |
| **303** | `device_bound` — worker paired a device via OTP. Pushed to the just-bound unit at bind time (bind is online). | **Yes** |
| **304** | `device_unbound` — worker released a device via OTP with no unsent harvest (`lost_cuts = 0`). Pushed to that unit before the binding clears (unbind is online); marked non-resendable if the gateway is unreachable. Carries `lost_tasks` and `lost_cuts`. | **Yes** |

Rules:
- The app self-unprovisions only on an explicit `released`/`bound_other`;
  `not_found` / network failure never wipe a device.
- On `released`: finalize the active task, flush `179/180/35` + `301` in one
  session, and log out **only after the whole flush lands** (all cuts uploaded
  AND `301` acked) — otherwise stay bound so leftover cuts retry (no data loss).
- On `bound_other`: log out **without pushing** (a message under this unit id
  would pollute the new owner). The displacement is diagnosable server-side
  (`check_binding`), not via a pushed code — the old `302 binding_taken` local
  marker was removed 2026-07-07.
- `303`/`304` are lifecycle markers pushed inline at OTP bind/unbind — both are
  inherently online (they just reached the n8n webhook), so the Wialon push lands.
- A backend drain-lease (`units.drain_until`) blocks a new claim during the
  flush window (`manual_claim` → status `draining`).

Release-marker rules (`302` / `304`, 2026-07-23):

- **`304` no longer counts all unbinds.** After 2026-07-23 a device-initiated
  release emits `302` when it leaves harvest behind and `304` when it does not.
  **Total releases = `302` + `304`.** A report counting only `304` silently omits
  every problem release.
- **Both release markers carry `lost_tasks` and `lost_cuts`** (integers, IPS
  param type `1`). The routing is on cuts alone, so on a `304` `lost_cuts` is
  **always 0** — a positive assertion that no harvest was left, not an absence.
  `lost_tasks` on a `304` **may be non-zero**: a task whose cuts all uploaded but
  whose heartbeat beacon is still pending yields `304 lost_tasks=1, lost_cuts=0`,
  meaning *"no harvest lost, but a task still holds an unsent beacon."* `301` and
  `303` carry neither param: `301` is recorded before its flush runs, so any count
  taken then is stale by design.
- **`lost_cuts` counts `event_code = 179` rows only** (`pushed = 0`), matching the
  harvest rule ("count rows where `ffb_cut = 1`"). It is not `SUM(net_count)` —
  those diverge whenever a worker uses `−`. Counting the whole pushable set would
  let heartbeats dominate.
- **`lost_tasks` is `COUNT(DISTINCT task_id)` over rows with `pushed = 0`** — it
  spans `179`/`180`/`35` alike, which is why it can exceed the tasks implied by
  `lost_cuts` and can be non-zero when `lost_cuts` is 0. Already-stranded work
  (`pushed = 2`) is excluded, so a later release never re-reports it.
- **The strand is unconditional.** On every device-initiated release, every still
  pending event row is marked `pushed = 2` (permanently non-sendable) whether or
  not the marker reached the gateway. The Wialon unit id is a login credential in
  the frame, not a property of the phone: a row left pending would upload under
  whatever unit the handset binds to next, crediting one worker's harvest to
  another. The ranking this encodes: **misfiling harvest onto the wrong worker is
  worse than losing it.**
- **Historical `302` collision.** `302` meant `binding_taken` until it was
  removed on 2026-07-07, and at least one such message exists in Wialon
  (`HAMS_TEST_003`, 2026-07-09 02:32:57). Old `302`s carry no `lost_*` params;
  new ones always do. Filter on param presence or date-cut before 2026-07-23.

Known limitations (shipped deliberately, 2026-07-23):

- **A gateway miss destroys the harvest silently.** If the marker does not land,
  the rows are stranded anyway and there is **no Wialon receipt** for the release.
  Both call sites (`AdminSheet`, `PairingScreen`) discard `flushAndRelease`'s
  return value, so the operator gets no on-screen signal. The only record is
  local: the release diagnostics row left at `pushed = 2` plus the stranded event
  rows, readable by a DB pull and retained for `AppConfig.SQLITE_RETENTION_DAYS`.
  Reconciliation against Wialon will therefore show fewer release markers than
  actual releases.
- **A `+` press during the release window is stranded but not counted.** The
  counts are taken before the marker is pushed and the strand happens after, so a
  cut recorded in between is destroyed without appearing in `lost_cuts` — the
  receipt under-reports by that amount. The window is narrow (the admin holds the
  phone, the sheet is modal) and the behaviour is deliberate: bounding the strand
  to the counted rows would leave that row pending, to be misfiled onto the next
  unit instead.

---

## Purpose

This is the single source of truth for HAMS V2 event codes. It is referenced by:
- `CONTEXT.md` (V6 patch) — protocol layer
- `CLAUDE.md` (V6 patch) — Android build rules
- `docs/checkpoints/HAMS_API_TESTING.md` — test evidence

If an event code needs to be added, changed, or removed, edit this file. Do not redefine codes in the other three files — they cite this one.

---

## What an event code is

An outbound `event_code` is a **Wialon/reporting semantic label** carried in
the IPS params block. It is not the app's private event enum. If Wialon has no
report, sensor calibration, or notification rule for a value, that value is only
stored as an opaque custom parameter and is not useful for supervisor sorting.

HAMS internal events should be represented with local fields such as
`event_type`, `save_type`, task state, `pushed`, and SQLite-only rows. The
diagnostics telemetry values listed in the Option B table above are the approved
exception: they are server-facing Wialon event codes and are pushed from the
`diagnostics` table through the separate telemetry frame path.

`ffb_cut` is **not** stored as a SQLite column. The V6 frame builder derives it
from approved outbound event codes: `179` maps to `ffb_cut=1`; `180` and `35`
map to `ffb_cut=0`.

`work_count` means the current displayed/net count for the active task after the event (`plus_count - minus_count`). It is not a lifetime total of plus presses. It resets to `0` when a new task starts.

In V6, event_code is sent as a Wialon IPS v1.1 custom parameter using type code 1 (int):
```
event_code:1:179
```

The only place `event_code` has functional effect is **if Wialon is configured
to interpret that value** — for example, the production rule "Landak Landak
Bunch Cutter Plus Events" fires on `event_code=179` in range. For all other app
concepts, use regular params (`battery`, `work_count`, GPS) and local SQLite
state until a Wialon-side rule/report exists.

---

## Approved Outbound Wialon Event Codes

Only the following task/system values are approved to be sent to Wialon in the
`event_code` custom parameter right now. Diagnostics telemetry has its own final
Option B outbound-code set above. Other HAMS app concepts may still exist locally,
but they must not be pushed as custom `event_code` values unless Wialon admin work
creates a matching report/sensor/notification contract.

### Family 1 — Counting events

Core harvesting signals. Production codes **179/180 are the values KLK's
existing Wialon notification rules pattern-match on** (verified from production
Wialon admin and rule references — see "Verification status per code" below).
They are NOT codes emitted by the P99L firmware itself; the P99L protocol PDF
(`docs/protocols/Meitreck_p99l_protocol.pdf` § 1.3) does not list 179 or 180.
Legacy dev codes 279/280 are still present in the current source from the earlier
Phase 2 design, but they are no longer approved outbound Wialon reporting codes.
Preferred test strategy is to use 179/180 only on isolated test units/resources
that are not in production notification scopes. Keeping 279/280 as pushed dev
codes requires an explicit later decision.

| Code | Name | Meaning | SQLite? | Push to Wialon? |
|---|---|---|---|---|
| 179 | FFB cut (prod) | + press, one FFB cut, production event_code (matches existing Wialon notification rule scope) | Yes | Yes, always |
| 180 | FFB correction (prod) | − press, production event_code (matches existing Wialon notification rule scope) | Yes | **Only if `work_count > 0` after decrement** |

## Local-only HAMS App Events

These are internal app concepts. They may be stored in SQLite for audit,
recovery, and UI state, but they are not approved outbound Wialon `event_code`
values. This section does not include the final diagnostics telemetry Option B
codes (`24`, `25`, `26`, `27`, `29`, `40`, `41`, `42`, `43`, `44`), which are
approved outbound values.

### Family 2 — Task lifecycle events

Mark task boundaries and save lifecycle. App-only concepts; not present in P99L
protocol.

| Code | Name | Meaning | SQLite? | Push to Wialon? |
|---|---|---|---|---|
| 281 | New task created | Inserted atomically on the **first valid `+` press** of a new task session (lazy creation — not on app open or on New Task confirmation). Marks the task boundary in SQLite. | Yes | **No — local only** |
| 283 | Auto-save on kill | App swiped away or system-killed, task saved via `onTaskRemoved()` | Yes | **No — local only until Wialon rule/report exists** |
| 284 | Auto-save pre-push | Active task auto-saved by PushEngine before flushing queue | Yes | **No — local only until Wialon rule/report exists** |

### Family 3 — Device health events

Battery and GPS anomaly signals. Satisfies the vendor's anti-mischarging and data-quality requirements.

| Code | Name | Meaning | SQLite? | Push to Wialon? |
|---|---|---|---|---|
| 291 | Battery warning | Edge-triggered when battery crosses below 20% | Yes | **No — use `battery` param on normal pushed messages** |
| 292 | Battery critical | Edge-triggered when battery crosses below 10% | Yes | **No — use `battery` param on normal pushed messages** |
| 293 | GPS degraded | HDOP > 5.0 or fix lost during an event capture attempt | Yes | **No — local telemetry until Wialon rule/report exists** |

## Approved outbound system event

### Family 4 — System

| Code | Name | Meaning | SQLite? | Push to Wialon? |
|---|---|---|---|---|
| 35 | Periodic beacon ("heartbeat" in HAMS code/UI) | Fixed-interval timer tick while HamsService is running. **Default 10 minutes. Configurable via `heartbeat_interval_minutes`.** Value 35 is verified from `Meitreck_p99l_protocol.pdf` § 1.3 as **"Track By Time Interval"** — semantically a periodic time-based position report, which matches HAMS's use. P99L's literal "Heartbeat" code is **31** (not used by HAMS). The HAMS code/UI label "heartbeat" is kept for ergonomics; the on-the-wire value 35 is the protocol-correct one for a periodic data beacon. | Yes | Yes, always |

### Total

- Approved outbound task/system `event_code` values: **3** (`179`, `180`, `35`)
- Approved outbound diagnostics telemetry `event_code` values: **10**
  (`24`, `25`, `26`, `27`, `29`, `40`, `41`, `42`, `43`, `44`)
- Local/custom HAMS app codes: **8** (`279`, `280`, `281`, `283`, `284`, `291`, `292`, `293`)

---

## Verification and outbound policy (updated v1.2, 2026-04-30)

Each code falls into one of three policy classes. Policy class determines
whether the value may be pushed to Wialon now.

### Outbound-approved — verified from existing Wialon production / KLK convention

| Code | Verification source | Notes |
|---|---|---|
| 179 | Existing Wialon notification rule **"Landak Landak Bunch Cutter Plus Events"** (Ladang Landak resource, ID 600856837) — fires on `event_code = 179`. Recorded in `CONTEXT.md` § 5.6 and `HAMS_API_TESTING.md` § 8. | Changing this value would mean re-pointing the production notification rule. Treat as **fixed contract**. |
| 180 | Existing Wialon notification rule **"Ladang Landak Bunch Cutter Minus Events"** — fires on `event_code = 180`. Same source. | Same — treat as fixed contract. |

### Outbound-approved — verified from device protocol PDF

| Code | Verification source | Notes |
|---|---|---|
| 35 | `docs/protocols/Meitreck_p99l_protocol.pdf` § 1.3 — code 35 = "Track By Time Interval". | HAMS uses it for the periodic beacon. Code/UI may keep the name "heartbeat" for ergonomics, but value 35 is **not** P99L's literal "Heartbeat" (which is 31). Documented mismatch — no action required. |

### Not outbound-approved — HAMS-custom/local concepts

These values exist only in HAMS app code and HAMS-internal docs. They are not
emitted by P99L hardware and are not currently referenced by any Wialon
report/sensor/notification rule. They are **not approved as outbound Wialon
`event_code` values**. Keep them local or replace them with ordinary params and
task state unless KC/Wialon admin explicitly creates a server-side contract.
The final diagnostics telemetry Option B codes are already approved and are not
part of this local-only list.

| Code | Purpose | Current policy |
|---|---|---|
| 279 | Plus press (dev) | Legacy Phase 2 dev value. Do not push unless explicitly re-approved. Prefer 179 on isolated test units/resources. |
| 280 | Minus press (dev) | Same as 279. Do not push unless explicitly re-approved. |
| 281 | New task marker | Local-only SQLite annotation. Never push. |
| 283 | Auto-save on kill | Local task/save state only for now. Do not push as custom `event_code`. |
| 284 | Auto-save pre-push (Wi-Fi) | Local task/save state only for now. Do not push as custom `event_code`. |
| 291 | Battery warning (< 20%) | Local telemetry/edge state only. Wialon should read the normal `battery` param on pushed messages. |
| 292 | Battery critical (< 10%) | Same as 291; derive criticality from `battery <= 10` if Wialon reports need it later. |
| 293 | GPS degraded (HDOP > 5) | Local telemetry until a Wialon rule/report is explicitly designed. |

**Implication for the codebase.** The task-event push boundary still accepts only
`179`, `180` (when productive), and `35` from the `events` table. Diagnostics
telemetry uses a separate `diagnostics` table, `TelemetryCode`, `telemetryFrame`,
and `TelemetryPushEngine` path for the final Option B values. Existing
HAMS-custom constants may remain as internal/local identifiers, but
`PushEligibility`, `EventDao`, and the task `dataFrame` path must not treat them
as pushable Wialon `event_code` values.

- `app/src/main/java/com/klk/hams/AppConfig.kt` — `EVENT_CODE_*` constants
- `app/src/main/java/com/klk/hams/push/IPSFrameBuilder.kt` — task `VALID_CODES`,
  task `PLUS_CODES`, and separate telemetry frame path
- `app/src/main/java/com/klk/hams/push/PushEligibility.kt` — `when` branches
- `app/src/main/java/com/klk/hams/data/db/EventDao.kt` — pending-push filters
  should include only outbound-approved codes, not just exclude known local ones
- Unit tests under `app/src/test/java/com/klk/hams/push/`
- `CONTEXT.md` § 5.3 calibration table

Do **not** change 179/180 without coordinating with KC and the Wialon admin.
Do **not** add any new outbound custom `event_code` beyond the task/system and
Option B telemetry values without a matching Wialon report/sensor/notification
design.

---

## Rules and rationale

### Rule 1 — Minus press only pushes if productive

> Minus press writes to SQLite always. It pushes to Wialon as `event_code=180`
> **only if `work_count > 0` after the decrement.**

**Example A — productive minus:**
- Task work_count = 5 → worker taps − → work_count = 4 → **PUSH** event 180 with work_count=4

**Example B — self-cancelling minus:**
- Task work_count = 0 → worker taps + → work_count = 1 (event 179 pushes)
- Worker immediately taps − → work_count = 0 → **DO NOT PUSH** event 180

**Known trade-off — accept the small over-count:** in case B, the + press already reached Wialon before the − cancelled it. The V6 report will count that task as 1 cut despite net being 0. Tolerated because:
1. Self-cancelling pairs are rare in real field use (workers don't accidentally tap + in gloves)
2. Keeps Wialon message history focused on genuine productivity
3. Avoids UI latency (debouncing the + press would add 5s lag — bad UX for gloved hands)

Workers can always see their real net count on the phone display — the SQLite record is truthful. The minor over-count only appears in Wialon reports.

**Scaled press (AR-01, 2026-05-29):** when `plus_scale` or `minus_scale` is > 1, a single press emits multiple rows in one transaction, all sharing the same timestamp/GPS/battery snapshot. Each row's `work_count` increments (`+`) or decrements (`−`) by one. `+` caps emitted rows at `MAX_COUNT_PER_TASK`. `−` caps at remaining `net_count`. Rule 1 still applies **per row** — the `−` row that lands on `work_count = 0` is the only one marked local-only; all earlier rows in the batch are productive and push. See `docs/superpowers/specs/2026-05-29-scaled-count-design.md`.

### Rule 2 — New task and save lifecycle are SQLite-only

> A new task with zero cuts contains no harvest data. Pushing 281 to Wialon would add a message with `ffb_cut=0` that the V6 report filters out anyway. Skip the push.

If a worker creates a task and never presses +, no Wialon cut event is produced.
The task/save state remains in SQLite. Do not create a custom Wialon
`event_code` just to say "worker was present but produced nothing" unless a
future report explicitly needs that signal.

If a worker creates a task and does press +, Wialon sees the cut events directly. Task boundaries are inferable from the timestamp gaps between work_count sequences (work_count resets to 0 at task boundaries).

### Rule 2a — GPS is mandatory for counting

> The app must not record a `+` cut without a valid GPS coordinate.

On app launch, location permission and device location services are checked. If the worker denies permission or location services are off, the app shows a blocking message and exits. During normal operation, the `+` button must remain unavailable or reject the press until a valid coordinate snapshot is available. Do not write fake `0.0,0.0` coordinates.

### Rule 3 — Development/test event-code strategy is open

> Preferred direction: use the real outbound values 179/180 only on isolated
> test units/resources that are not in production notification scopes. The old
> 279/280 dev-code strategy is no longer approved by default.

Production Wialon has notification rules on the Ladang Landak resource firing on `event_code=179` and `event_code=180` in-range of production geofences. If the HAMS V2 app pushes 179/180 while pointed at a test unit inside a production geofence, real supervisors receive false alerts.

If 279/280 are still desired for dev isolation, that must be an explicit
decision and the Wialon test report/template must be configured to understand
them. Until then, treat 279/280 as legacy internal values pending redesign.

### Rule 4 — Battery edge-triggered, not level-triggered

> The app may record local battery threshold edges, but it should not push
> custom Wialon `event_code` values 291/292. Wialon-visible battery status rides
> on the normal `battery` param of pushed 179/180/35 messages.

**Example:**
- Battery reads 25% → no event
- Battery reads 19% → records local 291 once (crossed 20 threshold)
- Battery reads 15%, 12%, 11% → no additional events (still in same "below 20" bucket)
- Battery reads 9% → records local 292 once (crossed 10 threshold)
- Battery reads 5%, 3% → no additional events
- Battery charges back to 25% → no event (upward crossing doesn't fire)
- Battery drops to 18% again → records local 291 once (crossed 20 going down again)

**Meanwhile** — every pushed message (179/180/35) carries the current battery
percentage in its `battery` param, so supervisors can see battery level on
meaningful Wialon messages without custom battery event codes.

### Rule 5 — Heartbeat is a fixed-interval timer while app is active

> Event 35 fires every N minutes via `Handler.postDelayed`, where N is read from config key `heartbeat_interval_minutes`. Default: 10 minutes.

**Important — heartbeats and presses are independent.** Pressing a button does not reset or cancel the heartbeat timer. The heartbeat fires on its own clock regardless of user activity. In a 10-minute window a busy worker might generate 50 press events plus 1 heartbeat — two separate rows written by two separate code paths.

**Why both exist:** every event row (press, auto-save, heartbeat) already carries a battery reading. For an actively-working phone, presses alone give supervisors rich battery-curve data. The heartbeat exists to provide battery visibility during **idle periods** — when a worker is on break, in the truck, or between tasks and not pressing anything.

**Configuration:** stored in `SharedPreferences` under `heartbeat_interval_minutes`. Valid range 5–60 min. Changing the value takes effect on the next scheduled tick (no app restart needed). Set to `0` to disable heartbeats entirely.

**Tuning guidance — adjust if noise becomes a problem:**

| Value | Daily heartbeats (10.5 hr shift) | Total daily messages/phone | Notes |
|---|---|---|---|
| 5 min | 126 | ~280 | Maximum visibility, noisier Messages tab |
| **10 min (default)** | **63** | **~215** | Current default |
| 15 min | 42 | ~195 | Low noise, still near-real-time battery |
| 30 min | 21 | ~175 | Minimal noise |
| 0 (disabled) | 0 | ~153 | No idle-period visibility at all |

**Heartbeat lifecycle in the app:**
- Fires when HamsService is in foreground
- Stops when app is force-stopped
- Stops when device is powered off
- Does not run in deep sleep when app is backgrounded past Android's grace period
- Resumes automatically when HamsService returns to foreground

---

## Event origin and outbound policy

| App event/concept | Local representation | Outbound Wialon `event_code` |
|---|---|---|
| + button tap | SQLite event row, `event_type='plus'`, updated task count | `179` |
| Productive − button tap | SQLite event row, `event_type='minus'`, updated task count | `180` only when `work_count > 0` |
| Self-cancelling − button tap | SQLite event row, `pushed=1` | none |
| New task marker | SQLite row / task state | none |
| App killed / auto-save | `save_type='auto_killed'`, task state, optional local event row | none |
| Wi-Fi pre-push save | `save_type='auto_wifi'`, task state, optional local event row | none |
| ↑ **Status note** | `auto_wifi` is **deprecated under Task 2.8 spec (2026-05-08)**. New push design (`docs/superpowers/specs/2026-05-08-push-and-wifi-design.md`) keeps push and task lifecycle independent — push only operates on `push_status='pending'` tasks, never finalizes active ones. The `auto_wifi` save_type stays in the dictionary for backwards-compat with any existing rows but is not produced by new code. | — |
| Daily rollover (app launch on new MYT day) | `save_type='auto_rollover'`, `push_status='pending'` (netCount>0) or `'discarded'` (netCount==0). **No audit event row** — no real location/battery snapshot at launch. The task row itself is the audit. See `TaskRepository.rolloverActiveTaskIfStale()`. | none |
| Battery threshold crossing | local telemetry/edge state | none; use `battery` param on 179/180/35 |
| GPS degraded | local telemetry/diagnostic state | none until Wialon rule/report exists |
| Periodic timer tick | heartbeat/beacon event row | `35` |
| Boot / device reboot | `diagnostics.type='boot'` | **`29`** |
| Shutdown / power off | `diagnostics.type='shutdown'`; real broadcast or boot-time backfill | **`40`** |
| GPS lost | `diagnostics.type='gps_lost'` | **`24`** |
| GPS recovery | `diagnostics.type='gps_recovery'` | **`25`** |
| Screen off | `diagnostics.type='screen_off'` | **`26`** |
| Screen on | `diagnostics.type='screen_on'` | **`27`** |
| Stop moving | `diagnostics.type='stop_moving'` | **`41`** |
| Start moving | `diagnostics.type='start_moving'` | **`42`** |
| Power connected | `diagnostics.type='power_connected'` | **`43`** |
| Power disconnected | `diagnostics.type='power_disconnected'` | **`44`** |

See `CLAUDE.md` V6 patch for implementation details on each.

---

## What each event carries

Every SQLite event row can keep local audit fields. Only outbound-approved rows
are converted into Wialon IPS frames. For pushed rows, `event_code` is one of
the params; it does not change the frame structure.

### Common parameters (every event)

| Param | Type | Always present |
|---|---|---|
| `timestamp` | ISO 8601 UTC | Yes |
| `latitude`, `longitude` | double | Required for `+` cut events; nullable only for legacy/diagnostic rows |
| `hdop` | double | Required when Android supplies it for a valid fix |
| `satellites` | int | Required when Android supplies it for a valid fix |
| `speed` | int (km/h) | Native IPS field 7; real GPS ground speed, 0 when unavailable |
| `battery_pct` | double 0–100 | Yes (always available via BatteryManager) |
| `event_code` | int | Yes |

### Event-specific parameters

| Event | Extra | Notes |
|---|---|---|
| 179 | `ffb_cut=1` | Core +press |
| 180 | `ffb_cut=0` | Productive −press/correction |
| 35 | `ffb_cut=0` | Periodic beacon; piggybacks `battery` and `work_count` |
| 24 / 25 / 26 / 27 / 29 / 40 / 41 / 42 / 43 / 44 | no `ffb_cut`; `work_count=0` | Diagnostics telemetry from the `diagnostics` table. Coordinates are included when a last-known/current GPS snapshot exists; otherwise the telemetry frame uses zero coordinates. |

---

## What Wialon does with each event

For pushed task/system events (`179`, productive `180`, and `35`):

1. IPS gateway accepts the `#D#` frame, returns `#AD#1`
2. Message stored against the unit with timestamp, GPS, and `p` block containing named params
3. Wialon auto-resolves GPS to geofence → field/task label for reports
4. V6 report template filters to rows where `ffb_cut=1` → counts those as cuts
5. Periodic beacons (`35`) appear in Messages tab but not in the cut count report

For diagnostics telemetry events (`24`, `25`, `26`, `27`, `29`, `40`, `41`,
`42`, `43`, `44`), Wialon stores a normal IPS message with `event_code`,
`battery`, and `work_count=0`. These messages are for operational diagnostics
and behaviour review, not cut-count reporting; Wialon-side sensors/reports must
group or alert on those codes deliberately.

Do not assume Wialon understands other HAMS-local lifecycle/health events. If KC
later needs supervisor alerts beyond the final Option B set, first design the
Wialon-side report/sensor/notification rule, then choose a server-facing signal
deliberately. Until that exists, battery is visible through the normal `battery`
param on pushed task/system and telemetry messages.

---

## Production notification conflicts

Existing Ladang Landak resource has these rules on the production units (P99L hardware):

| Rule | Fires on | Scope |
|---|---|---|
| Landak Landak Bunch Cutter Plus Events | `event_code=179` in-range | Production OC 154 geofences |
| Ladang Landak Bunch Cutter Minus Events | `event_code=180` in-range | Production OC 154 geofences |
| Mobile Counter (Bunch count) | `io_1=1` | (legacy, not relevant to HAMS V2) |

**Implication:** during development/testing, use isolated test units/resources
that are not in the production notification scope before pushing 179/180. The
old 279/280 dev-code strategy is no longer approved by default because those
values are not meaningful to the real reporting setup.

**At cutover:** production app units push 179/180 and can be deliberately added
to the production notification scope. From that moment, app data becomes
indistinguishable, **at the Wialon notification-rule layer**, from the existing
P99L pipeline. HAMS does not emulate any other aspect of P99L firmware
behaviour.

---

## Changelog

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-04-23 | Initial dictionary, 10 codes across 4 families |
| 1.1 | 2026-04-30 | Added "Verification status per code" tiers (A/B/C). Reworded the Family 1 P99L claim — 179/180 are verified from existing Wialon notification rules, not from P99L firmware codes. Reworded Family 4 code 35 — verified from `Meitreck_p99l_protocol.pdf` § 1.3 as "Track By Time Interval"; HAMS keeps the local "heartbeat" label. No numeric values changed. |
| 1.2 | 2026-04-30 | Tightened policy: only 179, 180, and 35 are approved outbound Wialon `event_code` values. HAMS-custom codes 279/280/281/283/284/291/292/293 are local/internal unless future Wialon admin configuration gives them reporting meaning. Task 2.4 must be redesigned before commit. |
| 1.3 | 2026-07-02 | Added final, device + Wialon verified diagnostics telemetry Option B outbound codes: 24, 25, 26, 27, 29, 40, 41, 42, 43, 44. The task-event push path remains limited to 179/180/35; diagnostics telemetry pushes through a separate `diagnostics` table and telemetry frame path. |
| 1.4 | 2026-07-07 | Added the provisioning 3xx family: `301 binding_released` (admin free → flush + logout), `303 device_bound` and `304 device_unbound` (pushed inline at OTP bind/unbind — both inherently online). Removed `302 binding_taken` (was phone-local only); `bound_other` now stops + logs out with no push, diagnosed server-side. Added the "All Event Codes at a Glance" master table. Field-verified live 2026-07-07 (301/303/304 confirmed in Wialon). |
| 1.5 | 2026-07-23 | Reassigned `302` to `work_stranded` — emitted at a device-initiated OTP release when the phone leaves with unsent cuts, mutually exclusive with `304`. Both release markers now carry `lost_tasks` / `lost_cuts` params (type `1`); routing is on cuts alone, so `304` always reports `lost_cuts = 0` but may report a non-zero `lost_tasks` (a task holding only an unsent beacon). **Total releases = `302` + `304`.** Stranded event rows are marked `pushed = 2` **unconditionally** — whether or not the marker reached the gateway — so they can never later push under the next unit. `ReleaseResult.NotFound` now emits a marker too (was silent). Note: historical `binding_taken` `302`s exist on test units and carry no `lost_*` params. |

---

## Decisions captured here (cross-reference V6 checkpoint)

- **D11** — outbound event-code policy: task/system events use 179/180/35; diagnostics telemetry Option B additionally approves 24/25/26/27/29/40/41/42/43/44. 279/280 dev-code push strategy remains suspended pending explicit approval
- **D12** — minus press push policy: push 180 only when `work_count > 0` after decrement
- **D14** — new task (281) policy: SQLite only, never pushed to Wialon
- **D15** — battery alert policy: local edge-triggered state only for now; battery level rides pushed 179/180/35 messages as a common param
- **D16** — heartbeat policy: fixed-interval timer while HamsService foregrounded, default 10 min, configurable 5–60 min via `heartbeat_interval_minutes`, value 0 disables. Battery data rides every event row so heartbeats primarily serve idle periods.

See `docs/checkpoints/HAMS_API_TESTING.md` for full decision history.

---

**Maintained by:** WYH | **Last reviewed:** 2026-04-30
