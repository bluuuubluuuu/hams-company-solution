# HAMS V2 — API Testing Checkpoint (Consolidated Final, V6)

**Document Version:** FINAL V6 (consolidated from V1–V5 + V6 validation)
**Last Updated:** 2026-04-23
**Tester:** it.intern4@klk.com.my
**Environment:** KLKTestEnvironment — test only, no production data affected
**Overall Status:** ✅ API TESTING COMPLETE

> This is the single authoritative checkpoint document. All prior versioned files
> (V1–V5, V4.1) are superseded by this file. Do not reference the old files.
>
> **V6 note:** Phase F and Phase G were added on 2026-04-23. They reverse the V5 conclusion
> that IPS v1.1 cannot carry custom parameters. V6 uses the 16-field `#D#` frame with native
> named params (`ffb_cut`, `battery`, `event_code`, `work_count`). See Phase F for evidence.

---

## Critical Flags Summary

| Flag | Status | Finding |
|---|---|---|
| **F1** | ✅ **RESOLVED (V6)** | V5 concluded "IPS v1.1 rejects custom params, cannot replicate event_code=179". **V6 proved this wrong** — IPS v1.1 accepts custom params natively when the full 16-field `#D#` frame is sent (V5 used 14 fields). Now: `event_code:1:179`, `ffb_cut:1:1`, `battery:2:87.5`, `work_count:1:N` all accepted and stored. See Phase F. |
| F2 | ⚠️ Known Limitation | IPS v2.0 not supported on port 20332. Server rejects `#AL#0`. License limitation — not upgradeable. No action needed — V6 custom params work perfectly on v1.1 without v2.0. |
| F3 | ✅ Closed | Production report uses Events type. Superseded by V6 messages-tracing template. |
| F4 | ✅ Closed | Port 21416 tested with both IPS and MeiTrack formats. Both failed. **Port 21416 permanently CLOSED — do not use.** |
| F5 | ✅ Resolved | Offline batch push confirmed working. Each event = one `#D#` message with original device timestamp and GPS. Verified in Phase E. |
| F6 | ✅ Closed | Actions tab of Plus Events notification was blocked (Parameter state service disabled on test account). Not a blocker — V6 template uses param filtering, not event text. Event mask confirmed from API: `"Plus Button Pressed Events"`. |
| F7 | ✅ Closed | Unit filtration defaults (`speed=1000`, `distance=1000`) **silently drop all messages**. Must set both to `0` in Advanced tab on every production unit. |
| F8 | ✅ Closed | Wialon indexes messages by **server receive time**, not device timestamp. N-1 pull must use server receive time window: D-1 16:00 UTC to D 16:00 UTC (for Malaysia UTC+8). |
| F9 | ✅ Closed | V5 messages-tracing report template `HAMS_FFB_Cut_Count_TEST` (ID: 10) — **superseded by V6 template** `HAMS_FFB_Cut_Count_V6` (filter `ffb_cut=1` instead of `course=1`). |
| **F10** | ✅ Closed (V6 NEW) | Course=1 hack retired. Course field now carries real GPS heading (0 for stationary). |
| **F11** | ⚠️ **OPEN (V6 NEW)** | New V6 report template needs to be built by admin. Same row-counting approach, filter now `ffb_cut=1`. |

---

## Environment Details

| Item | Value |
|---|---|
| Wialon Web UI | `https://pro.navi-agnostics.com` |
| Wialon API Host | `https://hst-api.wialon.eu` |
| IPS Server | `185.213.1.24:20332` |
| IPS DNS | `nl2.gpsgsm.org` |
| MeiTrack Port | `185.213.1.24:21416` — **NOT usable for app** |
| WiaTag Port | `185.213.1.24:20963` — available on license, **not needed for V6** |
| Test User Account | `it.intern4@klk.com.my` |
| User ID | `601602600` |
| Billing Account ID | `24656798` |
| API Token | In `local.properties` — **ROTATE after V6 testing session** |
| Token Flags | `fl=-1`, duration=unlimited |
| Hardware Type | Wialon IPS — ID: `600002235` (numeric, not text) |
| Test Unit Name | `TEST_HAMS_APP_001` |
| Test Unit ID | `601602811` |
| Test Unit Unique ID | `HAMS_TEST_001` |
| Resource — Test | KLKTestEnvironment (ID: `600017079`) |
| Resource — Production | Ladang Landak (ID: `600856837`) |
| V5 Report Template | `HAMS_FFB_Cut_Count_TEST` (ID: `10`) — superseded |
| V6 Report Template | `HAMS_FFB_Cut_Count_V6` — to be built, ID TBD |
| Total Messages Stored | 146 (as of 2026-04-23 post-V6 test; was 145 at V5 checkpoint) |
| WiaTag hw_type ID | `600000799` (on license, not used) |

---

## Key Design Decision — V6: native custom params in 16-field frame

This is the most important architectural decision from testing. Read before implementing anything.

### V6 (current — 2026-04-23)

| Item | Detail |
|---|---|
| Push method | Wialon IPS v1.1, TCP to port `20332` |
| Frame structure | **16 fields + params block** (V5 used 10 short-form fields) |
| Per `+` press signal | **`ffb_cut:1:1` named custom parameter** (V5 used `course=1` hack) |
| Battery reporting | **`battery:2:<pct>` named custom parameter on every event** |
| Event semantic code | **`event_code:1:<N>` named custom parameter.** As of dictionary v1.2, only 179, 180, and 35 are approved outbound/reporting values. HAMS-local lifecycle/health codes are not pushed unless Wialon admin config gives them meaning. |
| Cumulative counter | **`work_count:1:<N>` named custom parameter** (HAMS-defined: current task displayed/net count after the event; resets at task boundary). |
| Why not WiaTag protocol | Available on license but unnecessary — IPS v1.1 custom params work natively |
| Why not notification rule | Not needed with messages-tracing template. Removes dependency on event text. |
| Template purpose | Cut-count reporting data only: app Wialon messages where `ffb_cut=1`, resolved by GPS/geofence, ready for N8N → `HAMSTaskCount`. |
| Report approach | Messages-based template reads rows directly. Filter `ffb_cut=1`. Wialon resolves GPS to geofence name automatically. |
| Counting logic | **1 row where `ffb_cut=1` = 1 FFB cut.** N8N counts rows per geofence per day. |
| Minus press | **Pushed only when `work_count > 0` after decrement.** Self-cancelling +/− pairs stay local. |
| New task (281) | **Local SQLite only, never pushed to Wialon.** Empty task boundaries add no report value. |
| Impact on P99L | None. Production P99L units and their existing reports are completely unchanged. New V6 template is separate. App uses event_code 179/180 to match existing Wialon notification-rule inputs, not P99L firmware event codes. |
| Geofence mapping | `PM03D_13` → split on `_` → `field_no=PM03D`, `task_no=13`. Unmatched GPS → coordinate string → N8N maps to TBD/TBD. |

### V5 (superseded — reference only)

V5 used a 10-field short-form `#D#` with `course=1` encoding the + press. Minus press was local-only. Battery was not pushed. V5 concluded custom params were unsupported — this was wrong; see Phase F.

---

## Phase A — Authentication & Setup ✅ COMPLETE

### Test Results

| # | Test | Method | Result | Notes |
|---|---|---|---|---|
| A1 | Generate API token | OAuth login form | ✅ PASS | Token returned in browser URL `access_token=` param |
| A2 | Login with token | `token/login` | ✅ PASS | Returns session ID (`eid`), user info, server details |
| A3 | Search for test unit | `core/search_items` | ✅ PASS | Initially 0 results — test unit did not yet exist |
| A4 | Create test unit | `core/create_unit` | ✅ PASS | Required `fl:4` permission (senior upgraded from `fl:16`) |
| A5 | Set unique ID for IPS | `unit/update_device_type` | ✅ PASS | `uniqueId: "HAMS_TEST_001"`, `hwTypeId: 600002235` |

### Issues Found in Phase A

**A-ISSUE-1: REST session expires after 5 minutes**
- Any REST API call with an expired session returns `{"error":1}`
- **Impact on app: NONE.** The Android app uses IPS TCP for data push, not REST sessions. IPS auth is per-connection via `#L#` device ID frame — no session, no expiry.
- **Impact on N8N pipeline:** Always call `token/login` fresh at the start of each workflow run. Do not cache or persist the `eid`.

**A-ISSUE-2: Permission error on unit creation**
- Initial account `fl:16` (read-only) returned `{"error":6}` on `core/create_unit`
- Senior upgraded to `fl:4` — unit creation succeeded
- **Impact on app:** None. Unit creation is a one-time admin setup action. Production app never calls `core/create_unit`.

**A-ISSUE-3: Hardware type ID must be numeric**
- `"hwTypeId":"WialonIPS"` (text) → `VALIDATE_PARAMS_ERROR`
- Must use numeric: `"hwTypeId":600002235`
- Discovered via `core/get_hw_types` with `filterType:"name"`, `filterValue:"Wialon IPS"`

---

## Phase B — IPS Data Push

### V5 understanding (superseded)

V5 tests B4–B7 attempted custom parameters with a 14-field frame and received `#AD#-1`. V5 concluded "IPS v1.1 rejects custom params." **This conclusion was wrong.** The real cause was structural incompleteness — IPS v1.1 spec requires 16 fields before the params block. See Phase F for V6 correction.

### V6 revised results

| # | Test | Method | Result | Notes |
|---|---|---|---|---|
| B1 | IPS login port 21416 | TCP `#L#` | ❌ FAIL | Empty response — MeiTrack port |
| B2 | IPS login port 20332 | TCP `#L#HAMS_TEST_001;NA` | ✅ PASS | Response: `#AL#1` |
| B3 | Push basic GPS 10 fields | TCP `#D#` (short) | ✅ PASS | Response: `#AD#1` — still works for backward compat |
| B4–B7 | V5: Custom params with 14-field frame | TCP `#D#` (14 fields) | ❌ FAIL (V5) | **Misdiagnosed.** Real cause: incomplete field count. |
| B8 | IPS v2.0 login | TCP `#L#2.0;...` | ❌ FAIL | `#AL#0` — v2.0 not supported, not needed for V6 |
| B9 | Push with altitude value | TCP `#D#` alt=1 | ✅ PASS | `#AD#1` |
| B10 | Short data message | TCP `#SD#` 10 fields | ✅ PASS | `#ASD#1` |
| **B11** | **V6: Full 16-field `#D#` + params** | **TCP `#D#` (16 fields) + params** | ✅ **PASS** | **Response: `#AD#1`. See Phase F for full detail.** |

### V6 working IPS message format

**Login frame:**
```
#L#HAMS_TEST_001;NA\r\n  →  #AL#1
```

**Backward-compat short `#D#` (V5 form — still accepted):**
```
#D#ddmmyy;hhmmss;DDMM.MMMM;N;DDDMM.MMMM;E;0;0;10;8\r\n  →  #AD#1
```

**V6 full `#D#` with custom params (use this for all new code):**
```
#D#ddmmyy;hhmmss;DDMM.MMMM;N;DDDMM.MMMM;E;speed;course;alt;sats;hdop;inputs;outputs;adc;ibutton;params\r\n
  →  #AD#1
```

Where `params = NAME:TYPE:VALUE` comma-separated. TYPE: 1=int, 2=double, 3=string. Full spec in `CONTEXT.md` §3.

### Coordinate conversion (unchanged from V5 — CRITICAL)

```
Decimal 2.268721°N → DD=02, MM=0.268721×60=16.1233 → send as 0216.1233
WRONG: 0226.8721 → 2.44787°N → wrong geofence → wrong report data
```

Always unit-test conversion before any integration work.

---

## Phase C — Verify Data in Wialon ✅ COMPLETE

Test data sent during Phase B was verified via both REST API and Wialon web UI. Message count and parameter integrity confirmed end-to-end.

---

## Phase D — Sensor & Report Configuration ⚠️ (V6 rework needed)

### V5 configuration (superseded)

V5 created `FFB_CUT` sensor reading parameter `course`. V5 created report template `HAMS_FFB_Cut_Count_TEST` (ID 10) filtering rows where `course=1`.

### V6 configuration (current)

Admin must re-point the FFB_CUT sensor to read parameter `ffb_cut`, create a new `battery_pct` sensor, and build a new report template filtering `ffb_cut=1`.

**V6 sensor changes required:**

| # | Action | UI Path | Value |
|---|---|---|---|
| D6 | Re-point `FFB_CUT` sensor | Unit → Sensors → FFB_CUT → edit | Parameter: `course` → `ffb_cut` |
| D7 | Create `battery_pct` sensor | Unit → Sensors → New | Type: custom, param: `battery`, unit: `%` |
| D8 | (Optional) Create `work_count` sensor | Unit → Sensors → New | Type: counter, param: `work_count` |
| D9 | (Optional) Create `event_code` sensor | Unit → Sensors → New | Type: custom, param: `event_code`, with calibration table (see `CONTEXT.md` §5.3) |
| D10 | Build V6 report template | Reports → Templates → New | Messages tracing, filter `ffb_cut=1`, columns Time/Coords/Battery/Event code |

Old V5 messages (stored before cutover) have no `ffb_cut` parameter → V6 sensor returns `null` for those rows. Expected behaviour — V5 data stays archived.

---

## Phase E — Batch Push Testing ✅ COMPLETE

Batch push confirmed working per V5 Phase E. Behaviour is independent of frame contents (V5 short form or V6 full form both batch identically).

**Confirmed batch rules:**
- 10 messages per TCP session
- 50–100 ms delay between messages (default 75 ms)
- Close socket and reconnect between batches
- Login frame required at start of each new TCP connection

V6 frames are ~150 bytes each vs V5's ~80 bytes. No impact on batch size or timing.

---

## Phase F — V6 Custom Parameters Validation ✅ (NEW, 2026-04-23)

Executed 2026-04-23 using PowerShell TCP client from tester laptop.

### F1 — Single frame with all four target custom params

**Sent:**
```
#L#HAMS_TEST_001;NA\r\n
#D#230426;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;ffb_cut:1:1,battery:2:87.5,event_code:1:179,work_count:1:1\r\n
```

**Gateway response:**
```
#AL#1
#AD#1
```

### F2 — Storage verification via REST API

**Pre-test message count on unit 601602811:** 145

**Query:**
```
svc=messages/load_interval
params={"itemId":601602811,"timeFrom":1000000000,"timeTo":1800000000,"flags":1,"flagsMask":65281,"loadCount":4294967295}
```

**Post-test count:** 146 ✅ (+1 message stored)

**Latest message returned (excerpt):**
```json
{
  "t": 1776907026,
  "f": 7,
  "tns": 1776907026000000000,
  "tp": "ud",
  "pos": {"y": 2.26872166667, "x": 103.282985, "c": 0, "z": 10, "s": 0, "sc": 8},
  "i": 0,
  "o": 0,
  "lc": 0,
  "rt": 1776907028,
  "p": {
    "hdop": 1.5,
    "ffb_cut": 1,
    "battery": 87.5,
    "event_code": 179,
    "work_count": 1
  }
}
```

**Key observations:**
- `f: 7` (full data flag) vs `f: 1` (short data flag) on V5 messages — server distinguishes the richer format
- `i: 0` and `o: 0` at top level (inputs/outputs stored as first-class fields in V6)
- `hdop` stored as a parameter inside `p` block (not in position block)
- All four V6 custom params stored with **correct types** — int stays int, double stays double
- `pos.c = 0` — course is the real value, not the V5 hack — geofence resolution still worked (Jalan Paloh area)

### F3 — UI verification

Tester logged into https://pro.navi-agnostics.com. Unit TEST_HAMS_APP_001 → Messages tab → interval today → Data messages → Execute.

**Visible in UI:**
```
Row 1 — 2026-04-23 09:17:06 (MYT)
Coordinates: 2.26872166667, 103.282985 (8 sats)
Altitude: 10
Location: Jalan Paloh, Mukim Paloh, Kluang, Johor, Malaysia
Parameters: hdop=1.5, ffb_cut=1, battery=87.5, event_code=179, work_count=1, I/O=0/0
```

### F4 — Conclusion

**End-to-end pipeline validated:** `#AD#1` gateway accept → storage → REST API retrieval → UI render. Custom params survive every stage with full fidelity. **V5 flag F1 resolved.** HAMS V2 app can adopt native custom params without any license upgrade, new port, new protocol, or SDK change.

---

## Phase G — Cross-Validation Against WiaTag Documentation ✅ (NEW, 2026-04-23)

After Phase F, cross-checked V6 design against the official Gurtam WiaTag 1.4 User Guide (January 2017, 20 pages, `_file_790_wiatag_android_en.pdf` in project).

### Findings

- WiaTag sends battery as a named custom param `b` (double percentage). HAMS V2 sends `battery` (descriptive variant). **Same pattern, different naming convention. Both valid per IPS v1.1 spec.**
- WiaTag sends SOS/alarm via IPS `SOS:1:1` param. HAMS V2 sends approved `event_code:1:X` values for reporting semantics (179/180/35) and keeps other lifecycle/health details local unless Wialon admin config is added.
- WiaTag's "manual data sending with auto-unload on Wi-Fi" mode is functionally identical to HAMS V2's push engine architecture. **V6 is consistent with Gurtam's own established patterns.**

### Verdict

Zero contradictions between V6 design and WiaTag User Guide. Three direct corroborations. One minor documentation note added re: param naming convention (see `CONTEXT.md` §3.3).

---

## Phase H — Event Dictionary Expansion ⚠️ UPDATED (2026-04-30)

V5 operated with a single concept — "press" — plus implicit task lifecycle state
in SQLite. The first V6 design expanded this into 10 app event codes. That has
now been tightened: Wialon `event_code` is a reporting/server semantic, not a
private app enum. Only 179, 180, and 35 are approved outbound values. Other app
lifecycle/health concepts remain local unless Wialon admin config gives them
server-side meaning.

### Family summary

| Family | Codes | Purpose |
|---|---|---|
| Counting | 179, 180 | FFB cut and productive correction |
| Local task lifecycle | 281, 283, 284 | SQLite/task state only, not outbound Wialon event_code |
| Local device health | 291, 292, 293 | Local telemetry only unless Wialon admin config is added |
| System | 35 | Periodic beacon / P99 Track By Time Interval |

### Key policies (see dictionary for full rules)

- **New task (281)** — stored in SQLite only, never pushed to Wialon (empty task boundaries add no report value)
- **Minus press (180)** — pushes only if `work_count > 0` after decrement (self-cancelling pairs stay local)
- **Battery alerts** — local edge-triggered state; battery level rides normal pushed params
- **Heartbeat (35)** — fixed-interval timer, default 10 min, configurable 5–60 min via `heartbeat_interval_minutes`
- **Battery level** — rides every pushed event's params, not just battery alert events

### Known trade-offs captured

- Self-cancelling +/− pairs result in a small over-count in Wialon reports (+press pushes before the − cancels it). Accepted trade-off documented in dictionary Rule 1. Phone SQLite stays truthful.
- Heartbeats add ~63 messages/day at 10-min default (10.5 hr shift). Easily tunable upward if noise becomes an issue.

---

## Open Decisions — V6 Status

| ID | Decision | Outcome | Status |
|---|---|---|---|
| D1 | Which field carries FFB count? | **`ffb_cut` named param** (V6) | ✅ RESOLVED |
| D2 | What integer value should HAMS send in `event_code` for plus/minus presses? | 179/180 — values verified from existing Wialon notification rules on Ladang Landak (Tier A in dictionary v1.1). Note: P99L firmware itself does not emit these codes; the P99L PDF event-code table tops out at 79. | ✅ RESOLVED |
| D3 | IPS port 20332 permanent? | Confirmed working | ✅ RESOLVED |
| D4 | Time format UTC or MYT? | App sends UTC. UI shows MYT. | ✅ RESOLVED |
| D5 | IPS v1.1 carry custom params? | **YES — V5 misdiagnosed** | ✅ RESOLVED |
| D6 | Port 21416 support event_code? | No — still closed | ✅ RESOLVED |
| D7 | Exact event text from Plus Events? | "Plus Button Pressed Events" | ✅ RESOLVED |
| D8 | Notification rule needed for V6 report? | NOT needed — report reads rows directly | ✅ CLOSED |
| D9 | New report template needed? | YES — filter `ffb_cut=1` | ⚠️ OPEN (admin action F11) |
| D10 | V6 approach decision | Proceed with V6; route questions to KC | ✅ RESOLVED |
| D11a | App event_code policy | Only 179/180/35 are approved outbound values. 279/280 dev-code push strategy is suspended. | ⚠️ REOPENED by dictionary v1.2 |
| D11b | Test/dev isolation strategy | Prefer isolated test units/resources using 179/180; re-approve 279/280 only if Wialon test reports are configured for them. | ⚠️ OPEN |
| D12 | Push minus press? | **Yes, only when `work_count > 0` after decrement** | ✅ RESOLVED |
| D13 | Retire V5 FFB_CUT sensor? | Retire after V6 validated — short overlap window | ⚠️ OPEN |
| D14 | Push new_task (281) to Wialon? | **No — local SQLite only** | ✅ RESOLVED |
| D15 | Battery alert strategy? | Local edge-triggered state; Wialon sees battery through `battery` param on 179/180/35 messages. | ⚠️ UPDATED |
| D16 | Heartbeat policy? | **Fixed-interval timer, default 10 min, configurable 5–60 min, value 0 disables** | ✅ RESOLVED |
| D-future | Per-unit IPS password hardening | Current `NA` works. Future phase: per-unit passwords for production deployment. | ⚠️ Deferred |

---

## Admin Actions Required

| # | Action | Detail | Priority | Status |
|---|---|---|---|---|
| 1 | Re-point FFB_CUT sensor on TEST_HAMS_APP_001 | Parameter: `course` → `ffb_cut` | HIGH | Open |
| 2 | Create `battery_pct` sensor on TEST_HAMS_APP_001 | Custom, parameter `battery`, unit `%` | HIGH | Open |
| 3 | Build V6 report template | Messages tracing, filter `ffb_cut=1`, columns Time/Coords/Battery/Event code | HIGH | Open |
| 4 | Replace V5 template ID in `CONTEXT.md` | After step 3 delivers new ID | HIGH | Open |
| 5 | KC operational contact for V6 questions | Use KC for any unresolved rollout questions | HIGH | Resolved |
| 6 | Apply V6 sensor config to production OC 154 units | At production rollout, after test unit/template are proven | MEDIUM | Open |
| 7 | Resolve dev/test event-code strategy (D11) | Preferred: isolated test units/resources using 179/180. Do not push 279/280 unless re-approved. | HIGH | Open |
| 8 | (Optional) Create `work_count` sensor | Counter, parameter `work_count` | LOW | Open |
| 9 | (Optional) Create `event_code` sensor with calibration | Custom, parameter `event_code`, calibration only for approved outbound values 179/180/35 unless more Wialon rules are created | LOW | Open |
| 10 | **Rotate API token** | Token was shared during V6 testing session | HIGH | Open |

---

## V6 Benefits Summary vs V5

| Benefit | V5 | V6 |
|---|---|---|
| FFB cut counting | `course=1` hack | Native `ffb_cut` param |
| Course field | Abused (always 1) | Real value (typically 0) |
| Battery in Wialon reports | Not possible | Every event carries battery |
| Battery supervisor alerts | Not possible | Not automatic yet; battery is available as `battery` param on pushed messages |
| Worker idle visibility | None | 10-min heartbeat with fresh battery |
| Wialon event_code compatibility | Not possible | 179/180 match existing Wialon notification-rule inputs; 35 matches P99 Track By Time Interval |
| Displayed/net cut counter (live) | Phone UI only | Wialon `work_count` param |
| Minus press visibility | Local only | Pushed (when productive) |
| Auto-save visibility | Silent | Local SQLite/task state unless Wialon admin config is added |
| GPS data quality alerts | None | Local telemetry unless Wialon admin config is added |
| Event vocabulary | 1 concept (press) | 3 approved outbound codes plus local app telemetry |
| Extensibility for new features | Need protocol workaround | Add a new param name — zero protocol change |
| Infrastructure change | — | None — same port, protocol, license, hw_type |

The V6 transport remains a strict improvement over V5, but the event-code policy
was tightened on 2026-04-30: custom app-only codes are not useful for Wialon
sorting until matching Wialon configuration exists.

---

## Next Steps (V6)

1. **Codex proceeds with Phase 0 → Phase 3 app implementation** per `CLAUDE.md` and `plans/`
2. **Admin performs actions 1–3** above on TEST_HAMS_APP_001
3. **Record the V6 template ID** in `CONTEXT.md` and the N8N daily pull workflow config
4. **Phase 4 integration test** — push approved outbound event codes only (179, productive 180, 35) → verify via REST + UI
5. **Production cutover planning with KC** — isolate test units/resources, then deliberately add app units to 179/180 production notification scope
6. **Retire V5 sensor & template** — after V6 proven stable in production for at least one month (D13)
7. **Rotate API token** immediately

---

**Document version:** V6 FINAL
**Last updated:** 2026-04-23
**Updated by:** it.intern4@klk.com.my
**Supersedes:** V1–V5, V4.1, and V5 FINAL checkpoint — all prior versions retained only for historical reference
