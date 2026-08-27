<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# HAMS Task Recorder
## Production Reference and User Guide

| Field | Value |
|---|---|
| Prepared by | Woo Ying Hui (`it.intern4@klk.com.my`) |
| Project PIC | Leong Kah Choon (`kc.leong@klk.com.my`) |
| Date | 26 August 2026 |
| Copyright | © 2026 Kuala Lumpur Kepong Berhad. All rights reserved. |
| Status | Released |
| Approved handset | realme C85 |
| App version | 1.2 (3) in production; 1.3 (5) under trial |
| Android support | Android 13 (API 33) to Android 15 (API 35) |

## 1. Purpose and system scope

HAMS Task Recorder is an offline-first Android app for field task counting. It stores each valid count with time, GPS, battery, and task-count data, then delivers eligible records through Wialon IPS.

| Component | Responsibility |
|---|---|
| Android app | Field counting, local storage, direct IPS delivery, safe device binding behaviour. |
| Wialon | IPS reception, unit configuration, reporting, and notification rules. |
| n8n | Pairing, OTP, release, verification, and office administration APIs. |
| PostgreSQL | Unit registry and short-lived OTP state. |

The app does not administer Wialon, n8n, or PostgreSQL, and it does not perform geofence matching or business reporting.

## 2. Device and application baseline

| Item | Current baseline |
|---|---|
| Approved handset | realme C85 |
| Application ID | `com.klk.hams` |
| Release version | `1.2 (3)` — the build deployed to the production fleet |
| Trial version | `1.3 (5)` — count-integrity build, one spare handset only |
| Android support | API 33–35 |
| Local store | Room/SQLite `hams.db`, schema version 6 |
| Orientation | Sensor portrait |
| Exact realme Android version | 15 |
| Authorship record | `Built by Woo Ying Hui, 2026`, carried in every APK |

Before use, install the approved signed build, enable location services, grant location permission, allow notifications, and complete pairing through the office process.

## 3. App user interface and field use

### 3.1 UI overview

| UI area | Purpose |
|---|---|
| Pairing screen | Enter an approved unit ID and supervisor OTP when the phone is unpaired. |
| Count screen | Show task, count, battery, GPS, and delivery state; record plus/minus actions. |
| New Task control | Protected task transition to prevent accidental task changes. |
| Push status | Show manual/automatic delivery progress and outcome. |
| Administrator controls | Protected pairing/release actions. |
| Version footer | Bottom line of the count screen; names the build (`HAMS 1.2`) so support can identify a handset without a cable. |

**Visual reference:** Leong Kah Choon (`kc.leong@klk.com.my`) is the contact for approved UI screenshots/reference. Woo Ying Hui will incorporate approved visual material into this report.

### 3.2 Daily use

1. Open HAMS and confirm that the device is paired.
2. Confirm GPS is ready before counting.
3. Press `+` for each count. Each recorded press sounds a tone and vibrates; `−` uses a lower tone. A press that was **not** recorded gives a distinct low double-buzz, so a worker who is not looking at the screen can still tell the difference. Tap; do not hold. On 1.2 a held button records ten counts per two seconds.
4. Use `−` only for a genuine correction.
5. Use New Task only after completing the prior task.
6. Use normal app controls to check delivery; do not uninstall the app to resolve a push issue.

| State | Required response |
|---|---|
| GPS ready | Counting is permitted. |
| GPS unavailable/stale | Wait, move to an open area, or check location settings. A press attempted now gives the refusal cue and is not counted. |
| Refusal cue on a normal press (1.3 only) | Presses are limited to one per 1.5 seconds. Slow down; the refused press was not counted. Report it if it happens during ordinary work. |
| Push in progress | Wait; do not repeat the push. |
| Push failed | Keep the app installed, check network, and escalate if persistent. |
| Unpaired/released | Stop delivery and contact the office administrator. |

## 4. Data delivery, pairing, and release safety

HAMS stores records locally before delivery. Current app configuration permits delivery on any connected network, including metered cellular data. `[TO CONFIRM]` that this remains the production policy.

| Delivery control | Current value |
|---|---:|
| Task-event batch size | 10 |
| Maximum retry attempts | 5 |
| Manual push timeout | 30 minutes |
| Release delivery budget | 15 seconds |
| Terminal-record retention | 30 days |

### 4.1 Pairing and verification

An office administrator enters the approved unit ID and a valid OTP. The company pairing identifier convention is `OC<NUM>_H_<SERIAL_NUMBER>`; `H` identifies a phone and `<SERIAL_NUMBER>` is the phone’s original manufacturer serial number. Example: `OC154_H_001`. This is a company naming convention, not a format enforced by the Android app. The app uses the Android device fingerprint to claim/verify the binding and checks it at launch, before delivery, and periodically.

| Binding result | App response |
|---|---|
| Bound | Continues normal operation. |
| Released | Performs safe release handling and returns unpaired. |
| Bound to another device | Stops unsafe delivery and returns unpaired. |
| Unit not found | Keeps local state conservatively; escalate to administrator. |

### 4.2 Release and replacement

1. Release through the protected administrator control using a valid OTP.
2. The app verifies that it still owns the unit.
3. It attempts bounded delivery while it remains bound.
4. It releases the unit through the configured backend.
5. Any remaining queued task rows are marked stranded (`pushed = 2`) and cannot upload under a later unit.

> ⚠ Never pair a replacement phone to the same unit while the old phone may contain unsent work.

### 4.3 Pairing and release: precautions and fallback

This section tells the person holding the phone what to do when pairing or release does not go as expected. In the tables below, the **What to do** column is the action to take.

**Before pairing**

1. Take the unit ID from the office register. Do not guess it or type it from memory. The convention is `OC<NUM>_H_<SERIAL_NUMBER>`; a wrong ID either fails outright or binds the phone to the wrong unit.
2. Confirm the phone has a working network connection. Pairing needs one. GPS is not required until counting starts.
3. Pair one phone to one unit at a time, and record the pairing in the operational register.

**If pairing does not succeed**

| Message on the phone | What it means | What to do |
|---|---|---|
| Unknown unit ID. Check and retry. | The unit is not in the office register. | **Re-check the ID against the register. If it is correct, ask the office to confirm the unit was seeded.** |
| This device is already bound to another unit. | This phone already owns a different unit. | **Release that unit first, then pair to the new one.** |
| That unit is bound to another device. | A different phone owns the unit. | **Stop. Do not retry. The office must release the unit before this phone can take it.** |
| This unit is finishing release from another device. | The previous phone is still inside its protected delivery window. | **Wait a few minutes, then retry. The wait protects counts still being sent.** |
| Supervisor code rejected. Check and retry. | The code is wrong, expired, or already used. | **Request a fresh code. A code lasts 10 minutes and works once only.** |
| Supervisor code is not configured. | The office code service is not set up. | **Contact the office administrator. Nothing can be fixed on the phone.** |
| No connection. Connect and retry. | The phone cannot reach the office service. | **Restore the network and retry. Nothing is lost by retrying.** |

**Before releasing or handing back a phone**

1. Deliver pending counts first, and confirm the push completed.
2. Only then use the protected administrator release control with a valid supervisor code.
3. Never uninstall the app to fix a delivery problem. Uninstalling destroys counts that have not been sent, and skips the safe release sequence entirely.

**Fallback when a phone is reset, lost, or damaged**

A factory reset changes the phone's Android identity. The rebuilt phone can no longer prove it owned the unit, so it cannot release the unit itself and cannot simply pair to it again. The office must force-release the unit first. The same unit ID can then be paired to the rebuilt or replacement phone, keeping its identity and its history.

| Scenario | What has happened | Correct response |
|---|---|---|
| Phone works and is being reassigned | Normal case. The app can still prove it owns the unit. | **Release from the app with a supervisor code. Pending counts are delivered first.** |
| Phone was reset without releasing | The register still shows the unit claimed by an identity that no longer exists. | **Ask the office to force-release the unit, then pair the same unit ID again.** |
| Phone lost, stolen, or dead | The unit stays claimed and no phone is able to release it. | **Ask the office to force-release the unit, then pair the replacement phone.** |
| App unexpectedly returns to the pairing screen | The unit was released or reassigned somewhere else. | **Stop counting. Do not pair again. Ask the office to check the unit assignment first.** |

> ⚠ Counts still unsent on a phone that is reset, lost, or damaged are permanently lost. Only a release performed from a working phone delivers pending work first.

> ⚠ Do not force-release a unit while the old phone may still be working and holding unsent counts. That phone will stop delivering, and the work it holds is stranded.

## 5. Technical and backend reference

### 5.1 Local mobile database

| Record | Purpose |
|---|---|
| `tasks` | Task lifecycle, plus/minus/net counts, task date, and push status. |
| `events` | Count, heartbeat, and local audit records with per-row delivery state. |
| `diagnostics` | Device/provisioning telemetry and stranded-work metadata. |

### 5.2 Wialon IPS

- The app logs in with `#L#<unique_id>;NA` and sends complete `#D#` data frames.
- Coordinates are converted to IPS `DDMM.MMMM` / `DDDMM.MMMM` format.
- Task-frame parameters are `ffb_cut`, `battery`, `event_code`, and `work_count`.
- `ffb_cut=1` only for the plus/cut event code `179`.
- Frame times are whole seconds. From 1.3 the app spaces same-second presses one second apart, so an event's wire time can be later than the moment it was pressed (bounded at 300 seconds of drift). `created_at` in the local database keeps true clock time.

#### Counting messages vs reading `work_count`

Verified against raw unit messages on 19 August 2026: **Wialon stores multiple messages that share a timestamp.** Three messages were retained at `16:06:55`, three at `16:06:57`, two at `16:06:58`. No task message has been lost in transport.

The under-reporting observed on 18 August 2026 — 3471 counted against 5691 actually pressed, a 39% shortfall — comes from the **notification layer** that feeds the count report, which triggers at most once per second. Presses sharing a second produced one notification.

Two independent remedies, and they solve different halves of the problem:

| Remedy | Effect | Covers past data |
|---|---|---|
| Report on `work_count` deltas | Recovers the true count from data already in Wialon | **Yes** |
| App press rate limit (1.3) | Prevents presses sharing a second from now on | No |

`work_count` is cumulative per task and rides on every task frame, so summing its rises reconstructs the true figure. Because it resets to `0` at each new task and decreases on a `−` correction, a report must distinguish the two:

| Δ between consecutive messages | Meaning | Contribute |
|---|---:|---|
| Δ > 0 | one or more cuts — recovers presses that shared a second | +Δ |
| Δ = −1 | a `−` correction | −1 |
| Δ ≤ −2 | a new task began | + the new `work_count` |

The `Δ ≤ −2` rule is inferred, not verified against a full day of raw messages, and is ambiguous if two `−` presses share a second. Confirm it against real data before relying on it for payroll-grade figures.

Ordering caveat: Wialon cannot order messages that share a timestamp — the 19 August sample shows `work_count` values out of sequence within one second. A press sequence cannot be reconstructed from such data. The 1.3 timestamp spacing removes this for future data.

### 5.3 n8n and PostgreSQL backend

The company n8n server is the operational source of truth; repository workflow JSON files are sanitized recovery/reference snapshots.

| n8n workflow (name in n8n) | Role |
|---|---|
| `G_PM_IT_IOT_HAMS_SEED` | Create/refresh unit registry records in bulk. Manual trigger. |
| `G_PM_IT_IOT_HAMS_DEVICEOTP` | Seed the requested unit if the registry has never seen it, then issue and email an office OTP. |
| `G_PM_IT_IOT_HAMS_MANUALCLAIM` | Pair a phone to a unit. |
| `G_PM_IT_IOT_HAMS_VERIFY` | Check current phone-to-unit ownership. |
| `G_PM_IT_IOT_HAMS_RELEASE` | Release verified owner binding. |
| `G_PM_IT_IOT_HAMS_ADMINRELEASE` | Office force-release for lost/reassigned phone. |
| `G_PM_IT_IOT_HAMS_LISTUNITS` | Read-only registry view. |

| PostgreSQL item | Purpose |
|---|---|
| `G_PM_IT_IOT_HAMS_UNITS` | Current Wialon unit assignment, device fingerprint, app version, and release-drain state. |
| `G_PM_IT_IOT_HAMS_ADMIN_OTP` | Short-lived, single-use office OTPs. |
| Routines | Unit seeding, OTP issue/consume, manual claim, release, binding check, and admin release. |

PostgreSQL is the provisioning registry, not the Android app’s harvest-event store. Database changes require DB-owner approval, backup, tested rollback, and a change record.

## 6. Credentials, support, and change control

### 6.1 Sensitive data and credentials register

This is the single reference section for sensitive data. Record only the item, owner, approved storage, and rotation/recovery rule here; never place an actual value in the report, Git, screenshots, tickets, or unrestricted chat.

| Sensitive item | Owner / approved storage / rule |
|---|---|
| IPS host/port and provisioning URLs | Secure Android build configuration; never commit real values. |
| `HAMS_CLAIM_SECRET` | Secret owner; secure Android build configuration and matching n8n credential. It is compiled into the app, so it is not server-only. |
| `WIALON_TOKEN` | Company secret vault/admin tooling only; never include in the APK. |
| PostgreSQL/n8n connection credentials | Company secret vault or n8n Credentials; never store in SQL scripts or the report. |
| SMTP/email credentials | Company n8n Credentials; used for the OTP delivery process. |
| Office OTP | Short-lived, single-use n8n/PostgreSQL process; send to Leong Kah Choon (`kc.leong@klk.com.my`), never return it to the phone. |
| Release keystore and passwords | Secure keystore storage; changing the signing key requires controlled re-pairing. |
| Unit IDs and device fingerprints | Restricted operational register; do not expose in screenshots or open documents. |

### 6.2 Support record

When escalating an issue, record handset asset ID, app version, unit ID, time/timezone, GPS/network state, current count, and the visible outcome. Remove confidential details from any screenshot before sharing.

### 6.3 Controlled changes

- Update application code, configuration, and this document together when behaviour changes.
- Back up and approve n8n/PostgreSQL changes before production deployment.
- Roll out new APK versions in controlled batches and record the validation outcome.

## Appendix A. Wialon task and reporting events

These are task-path messages sent to Wialon. They are operational/reporting events, not diagnostics.

| Code | Wialon task meaning | Delivery rule |
|---:|---|---|
| 179 | Plus/cut | Queued task event; `ffb_cut=1`. |
| 180 | Productive minus/correction | Queued only when post-action count is above zero. |
| 35 | Heartbeat | Queued task event; `ffb_cut=0`. |

## Appendix B. Wialon diagnostic telemetry codes

These codes use the separate diagnostics telemetry path. They are for operational visibility, not task-count reporting.

| Code | Wialon diagnostic meaning |
|---:|---|
| 24 / 25 | GPS lost / GPS recovery |
| 26 / 27 | Screen off / Screen on |
| 29 / 40 | Device boot / Device shutdown |
| 41 / 42 | Stop moving / Start moving |
| 43 / 44 | Power connected / Power disconnected |
| 301 | Binding released |
| 302 | Work stranded; may include `lost_cuts` |
| 303 / 304 | Device bound / Device unbound |

## Appendix C. APK internal/local codes

| Code | APK meaning | Delivery rule |
|---:|---|---|
| 281 | New task | Local-only. |
| 283 / 284 | Auto-save on kill / pre-push auto-save | Local-only. |
| 291 / 292 | Battery warning / critical edge | Local-only. |
| 293 | GPS degraded | Local-only. |

## Appendix D. PostgreSQL Data Dictionary

This database supports unit provisioning, OTP control, binding verification, and release. It is not the Android harvest-event store.

| Metadata | Value |
|---|---|
| Engine | PostgreSQL |
| Tables | 2 |
| Business routines | 8 |
| Routine response | JSONB status objects |
| SQL reference | `docs/operations/database-reference/hams_setup.sql` |

### D.1 Unit registry: `G_PM_IT_IOT_HAMS_UNITS`

| Column | Type | Default / constraint | Meaning |
|---|---|---|---|
| `unique_id` | TEXT | Primary key | Wialon IPS unit identity. |
| `name` | TEXT | Nullable | Human-readable unit label. |
| `claimed` | BOOLEAN | `false` | Whether a phone currently owns the unit. |
| `device_fingerprint` | TEXT | Unique; nullable | Paired Android identity; sensitive operational data. |
| `status` | TEXT | `active` | Unit lifecycle: `active` or `retired`. |
| `last_seen` | TIMESTAMPTZ | Nullable | Latest successful binding verification. |
| `app_version` | TEXT | Nullable | App version reported by the paired handset. |
| `created_at` | TIMESTAMPTZ | `now()` | Unit seed creation time. |
| `updated_at` | TIMESTAMPTZ | Set by routines | Latest unit-record mutation. |
| `drain_until` | TIMESTAMPTZ | Nullable | End of protected post-release delivery window. |
| `drain_fingerprint` | TEXT | Nullable | Fingerprint allowed to deliver during that window. |

### D.2 Office OTP table: `G_PM_IT_IOT_HAMS_ADMIN_OTP`

| Column | Type | Rule | Meaning |
|---|---|---|---|
| `code` | TEXT | Primary key | Six-digit OTP; sensitive while valid. |
| `created_at` | TIMESTAMPTZ | `now()` | OTP issue time. |
| `expires_at` | TIMESTAMPTZ | Required | Hard expiry; default issue lifetime is 10 minutes. |
| `used_at` | TIMESTAMPTZ | Nullable | Consumption time; `NULL` means unused. |

### D.3 Routines

| Routine | Parameters | Purpose |
|---|---|---|
| `seed_unit` | `p_unique_id`, `p_name` | Insert a unit or refresh its label without changing a live binding. |
| `issue_otp` | `p_ttl_minutes` (default 10) | Purge expired OTPs and issue a six-digit OTP. |
| `otp_is_valid` | `p_otp` | Check OTP validity without consuming it. |
| `consume_otp` | `p_otp` | Atomically consume a valid OTP. |
| `manual_claim` | `p_unique_id`, `p_fingerprint`, `p_otp` | Bind a phone to a unit after ownership, OTP, and drain checks. |
| `release_unit` | `p_unique_id`, `p_fingerprint`, `p_otp` | Release a unit only for its proven owner. |
| `check_binding` | `p_unique_id`, `p_fingerprint`, `p_app_version` (default NULL) | Return binding state and refresh owner activity/version when bound. |
| `admin_release` | `p_unique_id` | Office force-release for lost, dead, or reassigned handset. Takes no fingerprint: the point is that the owning phone can no longer prove anything. |

### D.4 App-facing status values

| Status | Typical HTTP | Meaning |
|---|---:|---|
| `ok` | 200 | Requested action succeeded. |
| `bad_request` | 400 | Missing or blank input. |
| `admin_auth_failed` | 401 | OTP invalid, expired, or used. |
| `not_found` | 404 | Unit absent from registry. |
| `already_bound` | 409 | Unit belongs to another phone. |
| `fingerprint_in_use` | 409 | Phone owns another active unit. |
| `not_owner_or_not_found` | 409 | Release attempted by a non-owner. |
| `draining` | 409 | Former owner is within delivery-drain window. |
| `bound` | 200 | Requesting phone owns the unit. |
| `released` | 200 | Unit was released. |
| `bound_other` | 200 | Unit is owned by another phone. |

> ⚠ Do not run `hams_setup.sql` during routine diagnosis. Production database changes require DB-owner approval, backup, tested rollback, and a recorded change.
