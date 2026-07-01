# Phase 4 — V6 Integration Test (End-to-End to Wialon)

> **Prereq:** Phase 3 complete. Full app installable with working offline counter, V6 event capture, Wi-Fi-triggered IPS push, progress UI, and rolling notification.
> **Read first, mandatory:** `CLAUDE.md` Phase 4 section; `CONTEXT.md` Sections 1-3, 5, and 7; `docs/HAMS_API_TESTING_CHECKPOINT_FINAL.md` Phase F and Admin Actions; `docs/HAMS_EVENT_CODE_DICTIONARY.md`.
> **Do not use V5 report/template assumptions.** V6 verifies `ffb_cut`, `battery`, `event_code`, and `work_count` in the `p` block. The old `course=1` report path is superseded.

---

## Objective

Prove the full V6 data path works end to end against the real Wialon test environment:

```text
Phone app -> TCP 185.213.1.24:20332 -> TEST_HAMS_APP_001 ->
Wialon messages with p block -> HAMS_FFB_Cut_Count_V6 report ->
N8N-ready rows where ffb_cut=1
```

This phase writes no application source code. It executes controlled live tests, records results, and declares pass/fail against predefined criteria. If the test fails because of an app bug, go back to the relevant implementation phase and fix it with tests.

---

## Required Wialon State Before Running

Stop if any of these are not true.

| Setting | Required value |
|---|---|
| Test unit | `TEST_HAMS_APP_001` |
| Test unit ID | `601602811` |
| Unique ID | `HAMS_TEST_001` |
| Hardware type | Wialon IPS (`600002235`) |
| Average speed between messages | `0` |
| Distance between coordinates | `0` |
| FFB_CUT sensor | parameter `ffb_cut` |
| battery_pct sensor | parameter `battery` |
| V6 report template | `HAMS_FFB_Cut_Count_V6`, filter `ffb_cut=1`, ID recorded in `CONTEXT.md` |

If the V6 template ID is still TBD, perform the message-level verification only and mark report verification as blocked by admin action F11. Do not pretend the report passed.

---

## Test Data Sets

### Test A — Worker happy path

Purpose: prove normal offline counting and report counting under the v1.2
event-code policy.

Expected behavior:

- 20 `+` presses become 20 Wialon messages.
- Each has `p.ffb_cut = 1`, `p.event_code = 179`, `p.work_count` from 1 to 20, and `pos.c = 0`.
- V6 report row count for the window is 20.

Under dictionary v1.2 the app emits the production value 179 directly. To avoid
firing production notification rules during dev tests, use **isolated test
units/resources outside the production notification scope** (e.g.
`TEST_HAMS_APP_001` on KLKTestEnvironment), not a separate dev event_code value.
The legacy `279` dev-code strategy is suspended.

### Test B — Outbound dictionary sweep

Purpose: prove every outbound-approved code stores with params and that local-only
codes never reach Wialon.

Approved outbound codes to push (one sample each):

| Event | Code | Expected `ffb_cut` |
|---|---:|---:|
| Productive minus | 180 | 0 |
| Heartbeat / periodic beacon | 35 | 0 |

(Plus is already covered by Test A's twenty `event_code=179` rows.)

Local-only codes that **must not** appear in Wialon under v1.2 (verify by
`messages/load_interval` returning none of these):

`281` (new-task marker), `283` (auto-save on kill), `284` (auto-save pre-push),
`291`/`292` (battery edges), `293` (GPS degraded), and legacy `279`/`280`. These
rows should still exist in local SQLite (`pushed = 1`) for audit, but
`EventDao.getPending` excludes them and `IPSFrameBuilder` rejects them with
`UnknownEventCode` should they ever be presented.

How to trigger Test B's two outbound events: normal UI for productive minus,
heartbeat timer (or `adb run-as` SQLite seed if the heartbeat scheduler is not
yet wired) for 35. Record the method used in `phase4_results.md`.

---

## What This Phase Produces

Create under `docs/integration_test/`:

```text
phase4_v6_runbook.md
phase4_v6_results.md
```

Optional:

```text
phase4_logcat.txt
phase4_messages_sample_redacted.json
```

Inspect and redact logs before committing. Do not commit tokens, REST session IDs, field-worker PII, or `local.properties`.

---

## Task 4.1 — Write the Runbook

**File**
- Create: `docs/integration_test/phase4_v6_runbook.md`

Use this outline:

```markdown
# Phase 4 V6 Integration Test — Runbook

## Pre-flight on Build Host
1. Confirm `local.properties` contains `IPS_HOST`, `IPS_PORT`, `DEVICE_UNIQUE_ID`, and `WIALON_TOKEN`; do not print token values into logs or docs.
2. Confirm `DEVICE_UNIQUE_ID=HAMS_TEST_001` for the test unit.
3. `.\gradlew.bat :app:installDebug`
4. `adb shell pm grant com.klk.hams.debug android.permission.ACCESS_FINE_LOCATION`
5. `adb shell pm grant com.klk.hams.debug android.permission.POST_NOTIFICATIONS`
6. `adb logcat -c`
7. Start log capture in a second terminal: `adb logcat *:I > docs/integration_test/phase4_logcat.txt`

## Pre-flight in Wialon
1. Log in to `https://pro.navi-agnostics.com`.
2. Open `TEST_HAMS_APP_001`.
3. Confirm hardware type, unique ID, speed filter, and distance filter values from this plan.
4. Confirm `FFB_CUT` sensor parameter is `ffb_cut`, not `course`.
5. Confirm `battery_pct` sensor parameter is `battery`.
6. Confirm the V6 report template ID and record it.
7. Run `messages/load_interval` with `flagsMask:65281` and record baseline message count.

## Test A — Worker Happy Path
1. Enable airplane mode.
2. Open the app.
3. Press `+` 20 times steadily.
4. Confirm counter reads `0020`.
5. Hold "New Task" for 5 seconds and confirm Yes. This saves the task and prevents an extra pre-push auto-save event from changing the expected message count.
6. Confirm cache/history shows one pending task with net count 20.
7. Disable airplane mode and connect to Wi-Fi.
8. Confirm upload starts within 30 seconds.
9. Confirm progress reaches 20/20 and success notification appears.
10. Confirm cache/history marks the task uploaded.

## Verify Test A via Wialon UI
1. Open Messages for today's test interval.
2. Confirm 20 new full-data messages.
3. Spot-check: `ffb_cut=1`, `battery` present, approved outbound `event_code=179`, `work_count` increments, course is 0.

## Verify Test A via REST
1. Run `token/login` from `CONTEXT.md`; do not save the `eid` in committed files.
2. Run `messages/load_interval` with `flagsMask:65281`.
3. Confirm message count delta is +20.
4. Confirm the 20 new messages have `f=7`, `pos.c=0`, and populated `p.ffb_cut`, `p.battery`, `p.event_code`, `p.work_count`.
5. Run `report/exec_report` against `HAMS_FFB_Cut_Count_V6`.
6. Confirm report row count is 20.

## Test B — Outbound Dictionary Sweep (v1.2)
1. Generate one pending row each for the two outbound-approved non-plus codes:
   `180` (productive minus) and `35` (heartbeat / periodic beacon).
2. Optionally seed a few **local-only** rows (e.g. one each of 281, 283, 284,
   291, 292, 293) directly in SQLite via `adb run-as`. They must be inserted
   with `pushed = 1` (the production code path already does this through
   `PushEligibility`).
3. Trigger Wi-Fi push.
4. Verify the two outbound rows return `#AD#1` and Wialon stores them with
   `ffb_cut=0`, `event_code` matching, and other params populated.
5. Verify the V6 cut-count report row count does not increase (180 and 35 are
   not cut events).
6. Verify via `messages/load_interval` that **no message with
   `p.event_code IN (281, 283, 284, 291, 292, 293, 279, 280)` exists** in the
   test window — the local-only rows must never reach Wialon.

## Post-flight
1. Redact logcat/session data if needed.
2. Fill in `phase4_v6_results.md`.
3. Record pass/fail and any admin/app blockers.
```

Include exact REST curl templates by referencing `CONTEXT.md` Section 2 instead of duplicating tokens or session values.

---

## Task 4.2 — Record Results

**File**
- Create: `docs/integration_test/phase4_v6_results.md`

Template:

```markdown
# Phase 4 V6 Integration Test — Results

**Date (MYT):**
**Runner:**
**Device model / Android version:**
**App build (git sha):**
**V6 report template ID:**
**Wialon baseline message count:**
**Wialon post-Test-A message count:**
**Expected Test-A delta:** 20
**Actual Test-A delta:**
**Test-A report row count:**
**Test-B generated event codes:**
**Test-B accepted message count:**
**Test-B report row-count delta:**

## Go / No-Go

| # | Criterion | Expected | Observed | Pass? |
|---|---|---|---|---|
| 1 | Wi-Fi -> push start | <= 30 s | __ | __ |
| 2 | Batch sessions | 2 sessions for 20 messages at batch size 10 | __ | __ |
| 3 | Progress UI | Reaches 20/20 | __ | __ |
| 4 | Local DB marks Test-A events uploaded | 20/20 `pushed=1` | __ | __ |
| 5 | Single rolling notification | Success, auto-dismiss about 10 s | __ | __ |
| 6 | Wialon Test-A message delta | +20 | __ | __ |
| 7 | Test-A params | `ffb_cut`, `battery`, `event_code`, `work_count` present | __ | __ |
| 8 | Test-A course | `pos.c=0` | __ | __ |
| 9 | V6 report row count | 20 | __ | __ |
| 10 | Test-B non-cut params | all `ffb_cut=0` | __ | __ |
| 11 | Test-B report inflation | +0 report rows | __ | __ |
| 12 | No `#AD#-1` / `#AD#15` | none | __ | __ |
| 13 | No `#AL#0` | none | __ | __ |
| 14 | No forbidden ports/protocols | no 21416, 20963, or `#L#2.0` | __ | __ |

## Deviations

## Decision

- [ ] Pass — ready for pilot planning
- [ ] Fail — see deviations and next action
```

---

## Loop-Verifiable Success Criteria

Phase 4 passes only if every Go / No-Go row is `Pass` and:

| # | Check | Expected |
|---|---|
| 1 | Test A Wialon message delta | exactly +20 |
| 2 | Test A V6 report row count | exactly 20 |
| 3 | Test A new messages | `f=7`, `pos.c=0`, `p.ffb_cut=1` |
| 4 | Test A event codes | 179 (only outbound plus value under v1.2) |
| 5 | Test B outbound codes in Wialon | 180 (productive) and 35 present; **no** 281/283/284/291/292/293/279/280 stored on the unit |
| 6 | Test B report row delta | 0 |
| 7 | Logcat `#AD#-1` / `#AD#15` | 0 |
| 8 | Logcat `#AL#0` | 0 |
| 9 | Local DB pending pushable events after successful run | 0 |

---

## If Fail

- Admin config issue: fix Wialon test unit/template, record the cause, rerun once.
- App frame issue: return to Phase 2, add a failing unit test, fix, rerun Phase 2 checks, then rerun Phase 4.
- Report issue: verify `HAMS_FFB_Cut_Count_V6` filters `ffb_cut=1`, not `course=1`.
- REST/session issue: rerun `token/login`; never cache session IDs.

Do not retry a failed run more than twice without a written root-cause note.

---

## Do Not

- **Do not** test against production units or the production `Ladang Landak` resource.
- **Do not** touch production P99L notification rules.
- **Do not** use the old V5 `HAMS_FFB_Cut_Count_TEST` / `course=1` path for pass/fail.
- **Do not** commit `local.properties`, token values, REST session IDs, or unredacted logs.
- **Do not** change app source during the live test.
- **Do not** mark the report check passed if the V6 template ID is still TBD.
- **Do not** use port 21416, port 20963, IPS v2.0, or Wialon REST from inside the app.
