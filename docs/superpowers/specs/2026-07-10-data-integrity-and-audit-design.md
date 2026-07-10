# Next Phase — Data Integrity & Provisioning Audit

**Date:** 2026-07-10
**Status:** Proposal — awaiting approval. **No code written, except A8 which is already fixed
and tested** (see §4, A8).
**Scope:** Android app **and** PostgreSQL provisioning backend. n8n workflows are unaffected.
**Blocked on:** company Postgres + n8n deployment completing first. All SQL below is additive
(`ALTER TABLE`, `CREATE OR REPLACE FUNCTION`, one `CREATE TABLE`) and applies cleanly to a live
instance without disturbing a single pairing.

---

## 1. Why this document exists

Two independent gaps surfaced while reviewing the drain-lease mechanism:

1. **The app can attribute one worker's harvest to another worker's unit.** Confirmed on a physical
   device 2026-07-10. This corrupts data rather than losing it, and it is undetectable in any report.
2. **The registry has no audit trail.** An admin force-release on a dead handset leaves no trace at
   all. Drain records are erased on the next claim.

The first is a defect. The second is a missing capability. They are bundled because they touch the
same code paths and the same deployment.

---

## 2. Evidence — A1 reproduced on device (2026-07-10)

Physical device `ALI-NX1` (Android 15), debug build, live Wialon test units.

**Setup.** Phone paired to `HAMS_TEST_003` on 2026-07-09 at `04:19:07` (`303 device_bound`
observed). One `+` press at `04:19:52`, then seven heartbeats. No Wi-Fi, so nothing pushed.

**Action.** On 2026-07-10: OTP-release from `HAMS_TEST_003` (`304` pushed `03:21:42`), then bind to
`HAMS_TEST_004` (`303` pushed `03:23:58`). Wi-Fi enabled. `PushWorker` drained the queue at
`03:25:39`.

**Device DB before push:** task `id=1`, `task_date=2026-07-09`, `push_status='pending'`,
8 events at `pushed=0` — one `179` (`work_count=1`) and seven `35`.

**Device DB after push:** all 8 at `pushed=1`, task `uploaded`.

**Wialon after push:**

| Unit | Message | Timestamp |
|---|---|---|
| `HAMS_TEST_004` | `179`, `ffb_cut=1`, `work_count=1` | **2026-07-09 04:19:52** |
| `HAMS_TEST_004` | 7 × `35`, `work_count=1` | 2026-07-09 04:21–04:27 |
| `HAMS_TEST_003` | *(no `179` anywhere near 04:19)* | — |

The cut was recorded 45 seconds after the phone bound to **003**. It landed on **004**.

**Two things the repro taught us that were not predicted:**

- **Messages keep their original capture timestamp.** The mis-attributed cut appears on the wrong
  unit at the *correct* time, with correct GPS and correct battery. There is no anomaly to detect —
  no timing gap, no duplicate, no out-of-hours upload. Every field is truthful except which unit it
  arrived under, and that field is stored nowhere on the device to compare against.
- **A `302` from an older build still exists in `HAMS_TEST_003`'s history** (2026-07-09 02:32:57).
  Historical Wialon data therefore contains a code the current dictionary (v1.4) says was removed.

---

## 3. Root cause

**No cut row records the unit it was recorded under.**

`events` has no `unit_id` column. `tasks` has none either. The unit id is read from
`ProvisioningStore` at **send** time, inside `PushWorker`, and stamped into the IPS login frame. Any
row still pending when the binding changes will be sent under whatever unit the phone holds next.

Everything in section 4 follows from this one fact.

---

## 4. Issue register

Severity: **P0** = corrupts or loses harvest data. **P1** = data loss under a plausible sequence.
**P2** = auditability or hygiene.

### A. Data integrity — app

| # | Issue | Sev | Evidence |
|---|---|---|---|
| A1 | Cuts push under the wrong unit after release + re-pair. Unit id read at send time, not record time. | **P0** | **Reproduced on device**, §2 |
| A2 | OTP release does not flush cuts. `resetPairing()` pushes only the `304` marker via `TelemetryPushEngine` (diagnostics only), then `store.clear()`. Nothing calls `enqueueAuto()`. Enables A1. | **P0** | `AdminSheet.kt:81–98` |
| A3 | Active tasks are never pushed. `pendingTasks()` is `WHERE push_status = 'pending'`. A task nobody ended is invisible to every flush. | **P0** | `TaskDao.kt:66` |
| A4 | Release races the push. Nothing waits for a terminal push state before allowing release. | P1 | `AdminSheet.kt` |
| A5 | Failed pushes are terminal. `push_status='failed'` is never retried; rows stay stranded silently. | P1 | Spec §Push engine, by design |
| A6 | Admin-release gap. No drain lease exists between the admin's action and the old phone's next check-in (≤15 min). A replacement phone can claim freely in that window; the old phone then hits `bound_other` and loses its backlog. | P1 | `008_admin_release.sql` clears the lease |
| A7 | Stranded cuts are invisible. Phone logged out; nothing in UI, registry, or Wialon reports them. | P1 | — |
| A8 | **Phantom `25 GPS_RECOVERY` on every cold start**, with no matching `24 GPS_LOST`. | P1 | **RESOLVED 2026-07-10** — see below |

**Note on A1's blast radius.** `PushWorker` is check-first (`PushWorker.kt:59`). On `bound_other` it
revokes and pushes nothing — so cuts are *lost*, not mis-attributed. Mis-attribution requires the
phone to be **successfully bound to a different unit**, which is exactly what release-then-rebind
produces. The design already prefers losing a cut over mis-filing one; A1 is the hole in that
preference.

### A8 — phantom GPS recovery (RESOLVED 2026-07-10)

**Symptom.** Wialon received `25 GPS_RECOVERY` with no preceding `24 GPS_LOST`. Observed twice in
live data: `HAMS_TEST_003` at 11:20:03 MYT and `HAMS_TEST_004` at 14:03:18 MYT, both seconds after
the service started. The device diagnostics table held **3 `gps_recovery` rows and 0 `gps_lost`**.

**Cause.** `GpsLockTransition` tracks two states, `Locked` and `Stale`. At service start there is no
fix, so `HamsForegroundService.kt:135` passes `ageMs = Long.MAX_VALUE` and the detector seeds
**`Stale`** — which means *"had a lock, lost it"*, not *"never locked"*. The first satellite fix then
transitions `Stale → Locked` and reports a recovery from an outage that never occurred.
`GpsLockState.Acquiring` exists but was never used by this class.

**Fix.** A `lostEmitted` flag. `GPS_RECOVERY` is emitted only when a `GPS_LOST` is outstanding. The
guard sits on the *emission*, not on the seed state, so it survives future changes to seeding.

**Tests.** `GpsLockTransitionTest.coldStart_firstLock_emitsNothing` (cold start → first fix → no
emission) and `coldStart_thenRealLossAndRecovery_emitBoth` (a genuine loss/recovery pair still
reports both). Verified to fail against the pre-fix code. Full unit suite and `lintDebug` pass.

**Why it survived.** All four pre-existing tests seeded from `Locked`. The cold-start path — the one
every handset takes every morning — had no coverage.

**Impact if it had shipped.** Any Wialon report counting GPS outages over-counts by one per service
start, making the fleet look like it is constantly losing signal.

### Reporting contract — `work_count` is not an aggregate

Not defects. Both are properties of the design that will silently corrupt any Wialon report template
built on `work_count`. Recorded here because the geofence/report template does not exist yet.

**`work_count` is unordered within a second.** The IPS frame carries whole-second resolution
(`DateTimeFormatter.ofPattern("HHmmss")`). The phone sends in the correct order
(`EventDao.getPending`: `ORDER BY timestamp ASC, id ASC`), but several events sharing one second
reach Wialon with identical timestamps and are stored in arrival order. Live example, 2026-07-10
14:03:23 on `HAMS_TEST_004`: `work_count` = 7, 6, 8.

**Task boundaries are invisible.** `281 new_task` is local-only and can never be selected by
`EventDao.getPending` (`event_code IN (179, 180, 35)`). Each new task restarts `work_count` at 1 with
no marker explaining the reset. Live example, same unit: three tasks of 6, 11 and 6 cuts appear as
one sequence that drops to 1 twice.

**The rule:** a day's harvest is the **count of rows where `ffb_cut = 1`**. Never the maximum or last
`work_count`. Whoever builds the report template must be told this before they build it.

Optional: setting `push_new_task_to_wialon = true` would make boundaries visible at the cost of one
message per task, and would require `281` to be approved as an outbound code. Not proposed here.

### B. Audit — database

| # | Issue | Sev |
|---|---|---|
| B1 | `admin_release()` writes no audit and actively clears the drain columns. A force-release on a dead handset leaves zero trace. | P1 |
| B2 | `drain_until` is overloaded — it is both the live lock and the only record that a drain occurred. | P2 |
| B3 | An expired lease is indistinguishable from a live one without comparing to the clock. Source of persistent operator confusion. | P2 |
| B4 | No rebind concept. Release + claim are two unrelated rows on two different units; a rebind cannot be reconstructed. | P2 |

### C. OTP

| # | Issue | Sev | Decision |
|---|---|---|---|
| C1 | Codes stored in plaintext. | P2 | harden |
| C2 | No attribution — no record of who issued a code or which unit it was spent on. | P2 | solved by `provisioning_events` |
| C3 | Purge happens only when the next code is minted. | — | **No action.** Table is self-limiting (<10 rows). A cron buys nothing. |

### D. Schema hygiene

| # | Issue |
|---|---|
| D1 | `status` has no `CHECK (status IN ('active','retired'))`. |
| D2 | `updated_at` maintained by hand in every routine, not by a trigger. |
| D3 | `"G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"` is unused. Comment already corrected 2026-07-10; no code change needed. |

---

## 5. Design

### 5.1 App — stamp the unit at record time (fixes A1 structurally)

Add `unit_id TEXT` to `tasks`, written once when the task row is lazily created, from the binding in
force at that moment.

`PushWorker` then refuses to send any task whose `unit_id` differs from the phone's current binding.

```
Room v5 → v6   MIGRATION_5_6
  ALTER TABLE tasks ADD COLUMN unit_id TEXT;
  -- backfill: existing rows get the current binding, or NULL if unpaired
```

| Situation | Behaviour after the fix |
|---|---|
| `task.unit_id` == current binding | push normally |
| `task.unit_id` != current binding | **do not push.** Mark `push_status='orphaned'`, surface in UI |
| `task.unit_id IS NULL` (legacy row) | treat as orphaned; do not push |

This makes mis-attribution **impossible**, not merely unlikely. It survives a skipped SOP, a
force-stop mid-release, a rollover at midnight, and a re-pair to a different unit.

> **Why the task and not the event.** A task is created under exactly one binding and cannot span
> two — the binding cannot change without the app being unpaired, which finalises nothing but stops
> all pushes. Stamping the task is sufficient and costs one column instead of one per event row.

### 5.2 App — flush before release (fixes A2, A3, A4)

Before calling `release`, the app must:

1. Finalise the active task if `net_count > 0` (`save_type="pre_release"`, `push_status="pending"`).
2. Enqueue and **await** a push.
3. If any task remains `pending` or `failed`, **refuse the release**:
   `"N cuts still unsent. Connect to Wi-Fi and retry."`

The phone is online and in the admin's hands at this moment — a flush is always possible. This
closes A2 and A3, and A4 falls out of "await" rather than "fire and forget."

### 5.3 App — surface stranded work (fixes A5, A7)

- A count of `pending` + `failed` + `orphaned` tasks, visible on the count screen and on the pairing
  screen (so it is visible even when unpaired).
- Allow a manual retry of `failed` tasks. Currently terminal by design; that design predates A1 and
  should be revisited.
- `orphaned` tasks are never auto-pushed. Recovery is an explicit admin action: re-pair to the
  original unit, flush, then re-pair to the new one.

### 5.4 Database — `provisioning_events` audit table (fixes B1–B4, C2)

One row per thing that happened. Nothing is overwritten.

```sql
CREATE TABLE IF NOT EXISTS "G_PM_IT_IOT_HAMS_PROVISIONING_EVENTS" (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    unique_id   TEXT        NOT NULL,
    action      TEXT        NOT NULL,   -- bind | unbind | drain | admin_release | seed
    actor       TEXT        NOT NULL,   -- admin_otp | admin_console | device
    fingerprint TEXT,                   -- the device involved, NULL for admin_console
    detail      JSONB
);

CREATE INDEX IF NOT EXISTS "G_PM_IT_IOT_HAMS_IDX_PROV_EVENTS_UNIT"
    ON "G_PM_IT_IOT_HAMS_PROVISIONING_EVENTS" (unique_id, occurred_at DESC);
```

Every routine inserts one row:

| Routine | `action` | `actor` |
|---|---|---|
| `manual_claim()` | `bind` | `admin_otp` |
| `release_unit()` | `unbind` | `admin_otp` |
| `admin_release()` | `admin_release` | `admin_console` |
| `check_binding()` on `released` | `drain` | `device` |

Answers the questions the columns could not:

- *"Is this unit being force-released repeatedly?"* → count the rows.
- *"Who held `HAMS_001` in June?"* → read the rows.
- *"Was that unbind by the admin or by the worker with an OTP?"* → the `actor` column.
- *"Was phone X rebound from 001 to 002?"* → two rows, same fingerprint, seconds apart.

> **Naming (SOP).** Table and index are quoted UPPERCASE with the `G_PM_IT_IOT_HAMS_` prefix.
> Every reference must keep the quotes. Functions and columns stay unquoted lowercase.

### 5.5 Database — close the admin-release gap (fixes A6)

`admin_release()` currently clears the lease. Change it to **stamp** one instead:

```sql
drain_until       = now() + interval '5 minutes',
drain_fingerprint = <the fingerprint being evicted>
```

The unit becomes unclaimable for 5 minutes from the moment of release, whether or not the old phone
has noticed yet. The departing phone, if alive, gets a protected window it does not currently have.

**Trade-off, stated plainly:** `admin_release` loses its "free this unit instantly" property. An
admin who force-releases and immediately re-pairs a replacement handset will wait up to 5 minutes.
If that is unacceptable, add an explicit `admin_release(uid, force := true)` that skips the lease —
but make the caller ask for it.

The drain lease columns keep their current clearing behaviour. They are the **lock**.
`provisioning_events` is the **logbook**. The two are no longer conflated (fixes B2, B3).

### 5.6 Database — hygiene (D1, D2) and OTP hardening (C1)

- `ALTER TABLE ... ADD CONSTRAINT CHECK (status IN ('active','retired'))`.
- A `BEFORE UPDATE` trigger for `updated_at`, replacing the hand-maintained assignments.
- Store OTP codes as a hash (`pgcrypto`), compare on validate. `issue_otp()` returns the plaintext
  once, to the admin, and never stores it. Issuer attribution lands in `provisioning_events`, not in
  `admin_otp`.

---

## 6. Phasing

Each phase is independently shippable and independently valuable.

| Phase | Contents | Depends on |
|---|---|---|
| **P1 — stop the corruption** | 5.1 (`unit_id` on tasks + orphan guard), 5.2 (flush before release) | nothing |
| **P2 — see the damage** | 5.3 (surface stranded work, retry `failed`) | P1 |
| **P3 — audit** | 5.4 (`provisioning_events`), 5.5 (admin-release lease) | company DB live |
| **P4 — hygiene** | 5.6 (CHECK, trigger, OTP hashing) | P3 |

**P1 is the only urgent one.** It is the difference between wrong data and missing data.

---

## 7. Interim SOP (until P1 ships)

Order matters. Step 1 is the one everybody will skip.

1. **End the task first** — hold NEW TASK, or close the app. This turns the open task into `pending`.
   Auto-push *skips active tasks*; without this step there is nothing to flush.
2. **Wait for the push to finish.** Watch the count screen for `Completed`. Do not proceed on
   "Wi-Fi is connected" — the worker is a background job and nobody waits for it.
3. **Only then** generate the OTP and release.

This closes A2, A3 and A4 procedurally. It does not close A1 — a force-stop, a crash, or a rollover
between steps still strands rows that will later push under the next unit. **The SOP is a stopgap,
not a fix.**

---

## 8. Non-goals

- Recovering the mis-attributed cut already written to `HAMS_TEST_004` on 2026-07-09. Wialon message
  history is not editable from our side.
- Purging historical `302` codes from Wialon.
- A scheduled OTP purge job (C3 — declined; the table is self-limiting).
- A dedicated rebind workflow. Rebind stays release-then-claim, executed by the app. With
  `provisioning_events` it becomes reconstructible, which was the only real objection.
- Changing anything in n8n. The workflows call functions by name; function signatures do not change.

---

## 9. Open questions

1. **Orphaned-task recovery.** §5.3 proposes re-pairing to the original unit to flush. Is that
   acceptable in the field, or should an admin be able to release the cuts to their original unit
   without re-pairing the handset? The second is safer for data and harder to build.
2. **A5 retry policy.** Should `failed` tasks retry automatically once the orphan guard makes
   mis-attribution impossible? The reason they are terminal was fear of re-sending under the wrong
   unit — a fear P1 removes.
3. **`admin_release` lease (5.5).** Accept the 5-minute wait, or add an explicit `force := true`?
4. **`detail JSONB`** — what belongs in it? Proposal: the previous owner's fingerprint on a `bind`,
   the reason on an `admin_release`.

---

## 10. Verification plan

P1 is not done until this passes on a physical device:

1. Pair to unit A. Record cuts with no Wi-Fi. Hold NEW TASK.
2. Attempt OTP release → **expect refusal**: "N cuts still unsent."
3. Enable Wi-Fi, wait for `Completed`. Release succeeds.
4. Confirm in Wialon: cuts on **unit A**.
5. Repeat, but force-stop the app between release and re-pair. Bind to unit B, enable Wi-Fi.
6. Confirm: no cuts on unit B. Task shows `orphaned` in the UI.

Step 5–6 is the regression test for A1. It is the exact sequence that produced the defect on
2026-07-10.

---

*Written 2026-07-10 by WYH. A1 reproduced on device the same day. Superseded by nothing.*
