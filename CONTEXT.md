# CONTEXT.md — HAMS Task Recorder Technical Reference (V6)

> This file is the technical reference document for Codex CLI and Claude Code.
> It contains all environment values, API formats, mapping rules, and decisions.
> Read CLAUDE.md first for project rules and scope. This file provides the data.
> Do not put implementation rules here — those live in CLAUDE.md.

---

## Section 1 — Environment

### Wialon

| Item | Value |
|---|---|
| Web UI | `https://pro.navi-agnostics.com` |
| API Host | `https://hst-api.wialon.eu` |
| IPS Host | `185.213.1.24` |
| IPS Port (use this) | `20332` |
| IPS Port (do not use) | `21416` — MeiTrack only, confirmed non-functional |
| IPS Port (not needed) | `20963` — WiaTag, available but unnecessary for V6 |
| IPS DNS | `nl2.gpsgsm.org` |
| Protocol | Wialon IPS v1.1 (16-field frame form with custom params) |

### Test Environment

| Item | Value |
|---|---|
| Test user | `it.intern4@klk.com.my` |
| User ID | `601602600` |
| Billing account | `24656798` |
| Test unit name | `TEST_HAMS_APP_001` |
| Test unit ID | `601602811` |
| Test unit unique ID | `HAMS_TEST_001` |
| Hardware type ID | `600002235` (Wialon IPS — numeric, not text) |
| Test resource | KLKTestEnvironment (ID: `600017079`) |
| Production resource | Ladang Landak (ID: `600856837`) |
| V5 test report template | `HAMS_FFB_Cut_Count_TEST` (ID: `10`) — **superseded by V6** |
| V6 test report template | `HAMS_FFB_Cut_Count_V6` — **to be built by admin (ID TBD)** |
| Messages stored on test unit (2026-04-23 post-V6 test) | 146 |
| WiaTag hw_type on license | `600000799` — available but not used |

### API Token
Stored in `local.properties` (not committed):
```
WIALON_TOKEN=<72-char token — see credentials.env.example>
```
Token flags: `fl=-1`, duration: unlimited.

---

## Section 2 — Wialon API Reference

All REST calls go to: `https://hst-api.wialon.eu/wialon/ajax.html`

### Authentication Flow

**Always do this before any REST API call sequence. Session expires in 5 minutes.**

```bash
# Step 1: Login with token
curl "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=token/login" \
  --data-urlencode 'params={"token":"<YOUR_72_CHAR_TOKEN>"}'

# Response: {"eid":"<SESSION_ID>", "user":{...}, ...}
# Use eid as sid= in every subsequent call within this session
```

**Important:** The IPS TCP push does NOT use REST sessions. Only REST API calls need session management.

### Confirmed Working Endpoints

| Endpoint | Purpose | Notes |
|---|---|---|
| `token/login` | Get session ID (`eid`) | Always re-run before batch |
| `core/search_items` | Find units, groups, resources | Use `propName=sys_name`; provisioning seeding uses `flags=257` (0x1\|0x100) to read `items[].uid` = IPS unique id |
| `core/create_unit` | Create unit | Admin only, one-time |
| `core/get_hw_types` | List hardware types available on license | `filterType:"name"`, `filterValue:["Wialon IPS"]` |
| `unit/update_device_type` | Set hardware type + unique ID | `hwTypeId=600002235` (numeric) |
| `unit/update_sensor` | Create/modify sensor | Parameter: `ffb_cut` (V6) or `course` (V5) |
| `unit/calc_sensors` | Read sensor value | |
| `messages/load_interval` | Pull raw messages by time window | N-1 pull uses this. Use `flagsMask:65281` for full data including params |
| `messages/unload` | Release stale query state | Call before re-querying if previous result feels cached |
| `report/exec_report` | Run HAMS report template | See full payload below |
| `resource/get_zone_data` | Read geofence polygons | Requires `AVL_RES_VIEW_ZONES` ACL |

### Full report/exec_report Payload

```bash
curl "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=report/exec_report" \
  --data-urlencode 'params={
    "reportResourceId": 600017079,
    "reportTemplateId": <V6_TEMPLATE_ID>,
    "reportObjectId": 601602811,
    "reportObjectSecId": 0,
    "interval": {
      "from": <D-1_16:00_UTC_unix_timestamp>,
      "to": <D_16:00_UTC_unix_timestamp>,
      "flags": 0
    }
  }' \
  --data-urlencode "sid=<EID>"
```

**Time window for N-1 pull (MYT → UTC):**
- MYT is UTC+8
- N-1 day in MYT = D-1 00:00 MYT to D-1 23:59 MYT
- In UTC: `D-1 16:00:00 UTC` to `D 15:59:59 UTC`
- Use server receive time (`rt` field), NOT device timestamp

### messages/load_interval Payload (V6)

```bash
curl "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=messages/load_interval" \
  --data-urlencode 'params={
    "itemId": 601602811,
    "timeFrom": <unix_timestamp>,
    "timeTo": <unix_timestamp>,
    "flags": 1,
    "flagsMask": 65281,
    "loadCount": 4294967295
  }' \
  --data-urlencode "sid=<EID>"
```

**V6 note:** use `flagsMask:65281` (0xFF01) instead of V5's `255` to ensure the `p` (params) block is returned in responses. With V5 mask, custom params may be truncated.

### V6 message response shape

Example message returned for a V6 + press event:
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

| Field | Meaning |
|---|---|
| `t` | Device timestamp (unix seconds) |
| `rt` | Server receive time (unix seconds) — N8N should index on this |
| `f` | Flag byte. `7` = V6 full data, `1` = V5 short data |
| `pos.y/x` | Latitude/Longitude (decimal degrees) |
| `pos.c` | Course (V6: real value; V5: always 1 as hack) |
| `pos.sc` | Satellite count |
| `i`, `o` | Inputs/outputs bitmask |
| `p` | **Custom parameters block — V6 essentials live here** |

### Required ACL Permissions for Token

| ACL Flag | Required For | Endpoint |
|---|---|---|
| `ADF_ACL_ITEM_VIEW` | Unit/group visibility | `core/search_items` |
| `ADF_ACL_AVL_RES_VIEW_ZONES` | Geofence polygon read | `resource/get_zone_data` |
| `ADF_ACL_AVL_RES_VIEW_REPORTS` | Report template access | `report/exec_report` |
| `ADF_ACL_ITEM_EXECUTE_REPORTS` | Run reports on unit group | `report/exec_report` |

---

## Section 3 — IPS Protocol Reference (V6)

The app uses **Wialon IPS v1.1 text protocol** over TCP on port **20332**. No REST session needed for data push.

**V6 change summary:** upgraded from 10-field short form (V5, using `course=1` as an FFB-cut hack) to 16-field full form with native custom parameters. The gateway accepts both forms, but only V6 frames carry the params needed for V6 reports and sensors.

### 3.1 — Login Frame

Sent once per TCP connection, before any data frames.

```
#L#<unique_id>;NA\r\n
```

| Field | Value | Notes |
|---|---|---|
| `<unique_id>` | Device-specific, e.g. `OC154_H001` | Matches the unit's Unique ID in Wialon admin |
| password | `NA` | No password used. Unit password field must also be blank in Wialon. |

**Expected responses:**

| Response | Meaning |
|---|---|
| `#AL#1\r\n` | Login successful, proceed with data frames |
| `#AL#0\r\n` | Connection rejected (unit not found, or hardware type mismatch) |
| `#AL#01\r\n` | Password mismatch (not applicable since we send `NA`) |

If `#AL#0` or timeout: close socket, schedule retry, do not send data frames.

### 3.2 — Data Frame (V6 full form, 16 fields)

```
#D#DDMMYY;HHMMSS;DDMM.MMMM;N;DDDMM.MMMM;E;speed;course;alt;sats;hdop;inputs;outputs;adc;ibutton;params\r\n
```

Expected response: `#AD#1\r\n` (success). Error codes in §3.4.

**N-frames-per-press is wire-legal.** Under AR-01 scaled-count (2026-05-29), a single `+` or `−` press at scale `N` emits up to `N` `#D#` frames carrying identical date/time/lat/lon/battery — only `work_count` differs row-to-row. The IPS gateway accepts these as N distinct messages and replies `#AD#1` for each. See `docs/superpowers/specs/2026-05-29-scaled-count-design.md`.

### Full field reference

| Pos | Field | Format | HAMS V2 value | Notes |
|---|---|---|---|---|
| 1 | date | DDMMYY | e.g. `230426` | UTC date at event capture moment |
| 2 | time | HHMMSS | e.g. `080215` | UTC time at event capture moment |
| 3 | latitude | DDMM.MMMM | e.g. `0216.1233` | See §3.5 coordinate conversion |
| 4 | lat_dir | `N` | `N` | Always N for Malaysia |
| 5 | longitude | DDDMM.MMMM | e.g. `10316.9791` | See §3.5 coordinate conversion |
| 6 | lon_dir | `E` | `E` | Always E for Malaysia |
| 7 | speed | int km/h | `0` | Always 0 — workers are on foot |
<!-- DRAFT 2026-06-29 (WYH), pending SV: Speed (field 7) now carries real GPS
ground speed (Location.getSpeed() → km/h int); 0 only when the fix has no speed.
Was hardcoded 0 before Req 2. See docs/superpowers/specs/2026-06-29-vendor-requirements-design.md §2. -->
| 8 | course | int degrees | `0` | **V6: real value, not the V5 hack.** 0 = no heading |
| 9 | altitude | int metres | `10` | Estate terrain estimate |
| 10 | satellites | int | from `LocationProvider` | Number of GPS satellites at fix |
| 11 | hdop | double | from `LocationProvider` | Horizontal DOP, e.g. `1.5` |
| 12 | inputs | int bitmask | `0` | Digital inputs (n/a for phone) |
| 13 | outputs | int bitmask | `0` | Digital outputs (n/a for phone) |
| 14 | adc | csv doubles | empty | Analog inputs (n/a for phone — send empty string between semicolons) |
| 15 | ibutton | string | `NA` | Driver key (n/a for phone) |
| 16 | params | `NAME:TYPE:VALUE,...` | See §3.3 | The custom-parameter block |

### Type codes for the params block

| TYPE | Data type |
|---|---|
| 1 | int |
| 2 | double |
| 3 | string |

### 3.3 — Custom Parameter Block (Field 16)

Every data frame carries these named params. Naming is descriptive (HAMS-specific) rather than short-letter (WiaTag-style `b`, `a`) — both are valid per IPS v1.1 spec; descriptive names chosen for readability in Wialon admin and reports.

#### Standard params — every message

| Name | Type | Value | Source | Purpose |
|---|---|---|---|---|
| `ffb_cut` | 1 (int) | `1` = + press, `0` = every other event | Derived from `event_code` by the frame builder | Core cut signal. V6 report filters on this. |
| `battery` | 2 (double) | `0.0`–`100.0` | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` at capture moment | Phone battery percent, for supervisor dashboard |
| `event_code` | 1 (int) | task frames: `179`, `180`, `35`; diagnostics telemetry: final Option B codes | From approved outbound policy | Wialon/reporting semantic label. Task-event frames stay limited to 179 plus, 180 productive minus/correction, and 35 periodic beacon. Diagnostics telemetry uses its own final device + Wialon verified Option B code set. |
| `work_count` | 1 (int) | `0`, `1`, `2`, … | Current displayed/net task count after the event (`plus_count - minus_count`) | Resets to 0 on new task |

#### event_code values

Canonical policy is maintained in `docs/HAMS_EVENT_CODE_DICTIONARY.md`. Summary:

- **179** — FFB cut / plus, verified against existing Ladang Landak Wialon notification rules
- **180** — FFB correction / productive minus, verified against existing Ladang Landak Wialon notification rules
- **35** — periodic beacon (P99L PDF § 1.3 calls this "Track By Time Interval"; HAMS code/UI labels it "heartbeat"). Configurable interval, default 10 min.

Diagnostics telemetry Option B codes (FINAL, 2026-07-02): **29** boot, **40**
shutdown, **24** gps_lost, **25** gps_recovery, **41** stop_moving, **42**
start_moving, **26** screen_off, **27** screen_on, **43** power_connected, and
**44** power_disconnected. These are device + Wialon verified and are pushed
from the separate `diagnostics` / `TelemetryPushEngine` path, not from
task-event push eligibility.

Source still references older HAMS-custom values (`279`, `280`, `281`, `283`,
`284`, `291`, `292`, `293`) from the first Phase 2 design — they exist as
local-only `event_code` constants in `AppConfig` and as audit rows in SQLite
under v1.2. They are **not** approved outbound Wialon reporting values:
`PushEligibility` insert-marks them `pushed = 1`, `EventDao.getPending` filters
them out, and `IPSFrameBuilder` rejects them with `UnknownEventCode`. Promoting
any of them to outbound requires a coordinated change to Wialon admin
(reports/sensors/notifications) plus updates across `AppConfig`, the
dictionary, `IPSFrameBuilder`, and the unit tests.

#### Parameter naming convention

HAMS V2 uses descriptive param names (`battery`, `ffb_cut`, `event_code`, `work_count`) rather than the short single-character convention used by Gurtam's WiaTag app (`b`, `alarm`, etc.). Both are valid per the Wialon IPS v1.1 specification. Descriptive names are chosen to improve readability in Wialon reports, sensor definitions, and debug logs. The ~30 bytes/message overhead is negligible given HAMS's batch-push-on-Wi-Fi model.

### 3.4 — Response Codes

#### Login responses

| Code | Meaning |
|---|---|
| `#AL#1` | Success |
| `#AL#0` | Rejected |
| `#AL#01` | Password error |

#### Data responses

| Code | Meaning | Action |
|---|---|---|
| `#AD#1` | Success, message stored | Mark `pushed=1` in SQLite |
| `#AD#-1` | Packet structure error | Log + mark event as `pushed=2` (investigation needed) |
| `#AD#0` | Invalid time field | Should not occur (we always send valid UTC) |
| `#AD#10` | Coordinate error | Log — likely bad GPS conversion |
| `#AD#11` | Speed/course/alt error | Log — likely bad field format |
| `#AD#12` | Satellites/HDOP error | Log — likely non-numeric value |
| `#AD#13` | Inputs/outputs error | Should not occur (we always send 0) |
| `#AD#14` | ADC error | Should not occur (we always send empty) |
| `#AD#15` | Params error | Log — malformed params block |

### 3.5 — Coordinate Conversion (Mandatory)

GPS from Android comes in decimal degrees. IPS v1.1 requires DDMM.MMMM format.

```kotlin
fun decimalToDDMM(decimal: Double, digits: Int): String {
    val deg = decimal.toInt()
    val min = (decimal - deg) * 60.0
    return "%0${digits}d%07.4f".format(deg, min)
}

// Latitude uses 2-digit degrees:  decimalToDDMM(2.268721, 2)   → "0216.1233"
// Longitude uses 3-digit degrees: decimalToDDMM(103.282985, 3) → "10316.9791"
```

**Critical:** wrong coordinate format → wrong geofence match → wrong Field/Task in report → wrong HAMSTaskCount row. Always the #1 pre-flight check before integration tests.

### 3.6 — Frame Example

Full + press event, worker in field at 09:17 MYT on 23 April 2026, battery 91%, first cut of current task:

```
#D#230426;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;ffb_cut:1:1,battery:2:91.00,event_code:1:179,work_count:1:1\r\n
```

**Decoded:**
- Date: 23 April 2026 (UTC), Time: 01:17:06 UTC = 09:17:06 MYT
- Position: 2.268721°N, 103.282985°E (Ladang Landak, Johor)
- Speed 0, course 0, altitude 10, 8 satellites, HDOP 1.5
- Custom params: `ffb_cut=1` (is a cut), `battery=91.0`, `event_code=179` (approved outbound plus), `work_count=1` (first cut in task)

### 3.7 — Batch Push Rules

- **10 messages per TCP session** — close socket and reconnect between batches
- **50–100 ms delay** between messages within a session (default 75 ms)
- **Login frame required** at start of every new TCP connection
- No compression used (raw text over TCP)
- V6 frames ~150 bytes each vs V5's ~80 bytes — no impact on batch size or timing

### 3.8 — V5 Backward Compatibility

The gateway still accepts V5 10-field short frames (returns `#AD#1`). A half-migrated app pushing V5 frames will land messages in Wialon — but those messages will have empty `p:{}` blocks and won't show up in the V6 report template. Do not mix V5 and V6 frames from the same unit.

---

## Section 4 — Data Mapping (Wialon → PostgreSQL)

This section is for N8N/pipeline reference. The app does not do this — Wialon + N8N do.

### Geofence Name → Field + Task
```
PM03D_13  →  field_no = "PM03D",  task_no = "13"
PM08B_17  →  field_no = "PM08B",  task_no = "17"
<coordinate string>  →  field_no = "TBD",  task_no = "TBD"
```
Split on `_`: left part = field number, right part = task number.
If Wialon cannot resolve GPS to a geofence, it returns a coordinate string → treat as TBD/TBD.

### PostgreSQL Tables (N8N pipeline — not in this repo)
- `HAMSTask` — master table (field + task master)
- `HAMSTaskCount` — daily transactional (grouped by OC, field, task, date)
- `HAMSTaskCountSum` — monthly summary (grouped by OC, field, task, month YYYYMM)

### V6 schema extensions (optional, for N8N)
The V6 message response now carries `battery`, `event_code`, `work_count`, `hdop` in the `p` block. N8N can optionally store these in HAMSTaskCount extra columns for exception reports. Not required for core functionality.

### N8N Workflow Schedule
- `HAMSTaskCountPull` — runs daily at 06:00 MYT, pulls N-1 data
- `HAMSTaskSummary` — runs monthly on day 1, summarizes M-1 into HAMSTaskCountSum

---

## Section 5 — Unit Configuration (Wialon Admin — one-time per unit)

Every production unit must have these settings applied before going live. Rows marked ⭐ are V6 changes or additions.

### 5.1 — General Tab

| Setting | Value | Notes |
|---|---|---|
| Hardware type | **Wialon IPS** (ID: `600002235`) | Matches app push protocol |
| Unique ID | Device-specific, e.g. `OC154_H001` | IPS login frame routing |
| Password | *(blank)* | App sends `NA` — do not set a password |

### 5.2 — Advanced Tab (critical filters — unchanged from V5)

| Setting | Value | Reason |
|---|---|---|
| Average speed between messages | `0` | Non-zero value silently drops all messages (F7 in V5) |
| Distance between coordinates | `0` | Non-zero value silently drops all messages (F7 in V5) |
| Message validity filtration | Off | Keep raw data visible |

**Wialon unit defaults silently drop all messages if speed or distance filters are non-zero.** This was confirmed as F7 in V5 testing. Both must be set to 0 on every unit.

### 5.3 — Sensors Tab (V6 changes)

#### ⭐ Update: `FFB_CUT` sensor

| Field | V5 value | V6 value |
|---|---|---|
| Type | Custom | Custom |
| Parameter | `course` | **`ffb_cut`** |
| Units | *(empty)* | *(empty)* |

**Rationale:** V6 reads the FFB signal from the named param `ffb_cut` directly, not from the course field. Report template updates to match (§5.5).

#### ⭐ New: `battery_pct` sensor

| Field | Value |
|---|---|
| Type | Custom |
| Parameter | `battery` |
| Units | `%` |
| Table calibration | None (value is already 0–100) |

**Rationale:** feeds supervisor dashboards and enables future battery-threshold notifications. Satisfies the vendor's anti-mischarging requirement.

#### ⭐ Optional: `work_count` sensor

| Field | Value |
|---|---|
| Type | Counter |
| Parameter | `work_count` |
| Units | *(empty)* |

**Rationale:** live displayed/net count per task. Matches the HAMS app's `work_count` semantic. Low priority — defer unless explicitly requested.

#### Optional: `event_code` sensor with calibration

| Field | Value |
|---|---|
| Type | Custom |
| Parameter | `event_code` |
| Units | *(empty)* |
| Calibration table | See below |

**Calibration table (approved outbound values only):**

| Input | Output |
|---|---|
| 179 | `Plus / FFB cut` |
| 180 | `Minus / correction` |
| 35 | `Periodic beacon` |

**Rationale:** human-readable event column for the small set of server-meaningful
codes. Do not add HAMS-local lifecycle/health values here unless Wialon reports
or notification rules are deliberately created for them.

### 5.4 — Re-pointing Impact on Historical Data

After changing `FFB_CUT` parameter from `course` to `ffb_cut`:

- Old V5 messages (stored before cutover) have no `ffb_cut` parameter → sensor returns `null` for those rows
- This is expected and correct — V5 data was never meant to be read by V6 sensors
- Reports built on V5 data should be archived before cutover; reports going forward use the V6 template

### 5.5 — Report Template (V6 successor)

The V6 report template is for **cut-count reporting data**, not app telemetry in general. It reads Wialon messages created by the HAMS app, filters rows where `ffb_cut=1`, lets Wialon resolve each GPS coordinate to a geofence/field-task, and produces the daily cut rows that N8N can pull into `HAMSTaskCount`. Battery, approved event code, and coordinates are included as useful context columns, but the count itself is still "one row with `ffb_cut=1` = one cut."

| Property | V5 template | V6 template |
|---|---|---|
| Name | `HAMS_FFB_Cut_Count_TEST` | `HAMS_FFB_Cut_Count_V6` |
| ID | 10 | *(to be assigned by admin)* |
| Type | Messages tracing | Messages tracing |
| Filter | Rows where `course = 1` | **Rows where `ffb_cut = 1`** |
| Columns | Time, Coordinates | **Time, Coordinates, Battery, Event code** |
| Counting | 1 row = 1 cut | 1 row = 1 cut |
| Geofence resolution | Wialon auto | Wialon auto |
| Unmatched GPS | Coordinate string returned | Coordinate string returned |

Once V6 template is built by admin, update its ID in this section and in N8N daily pull workflow config.

### 5.6 — Notification Rules

For HAMS V2 app units during development/testing: **do not add to any existing notification rule scope.** The production rules (`Landak Landak Bunch Cutter Plus Events`, etc.) target production P99L units and fire on `event_code=179/180`.

Development/test units should be isolated from production notification scopes before pushing 179/180. The older 279/280 dev-code approach is suspended unless explicitly re-approved and backed by Wialon test report configuration.

At cutover: app units push 179/180 and can be added to the production notification scope deliberately. KC is the operational point of contact for any cutover questions.

---

## Section 6 — Confirmed Technical Decisions

These decisions were made during API testing (V6 checkpoint, 2026-04-23) and are final. Do not revisit unless explicitly unlocked.

| # | Decision | V5 outcome | V6 outcome | V6 reason |
|---|---|---|---|---|
| 1 | Push protocol | Wialon IPS v1.1, port 20332 | **Unchanged** | IPS works, REST more complex |
| 2 | Count encoding | `course=1` per + press | **`ffb_cut:1:1` named param** | V5 conclusion reversed — IPS v1.1 supports custom params when full 16-field frame is used |
| 3 | Frame structure | 10-field short form | **16-field full form + params block** | Required by IPS v1.1 spec to carry params |
| 4 | Course field | `1` (hack) | **`0` (real value)** | Cleanup — course now reflects actual heading |
| 5 | Minus press | Local only, never pushed | **Push 180 only if `work_count > 0` after decrement** | V6 D12 — balances supervisor visibility with data cleanliness |
| 6 | New task event (281) | — | **Local SQLite only, never pushed** | V6 D14 — empty task boundaries add no report value |
| 7 | Battery reporting | Local display only | **`battery` param on every pushed event** | V6 adds supervisor battery visibility (vendor requirement) |
| 8 | Battery threshold alerts | — | **Local edge state only for now; no custom Wialon event_code** | Battery rides task frames and approved diagnostics telemetry frames |
| 9 | Heartbeat | — | **Event 35, default 10-min interval, configurable 5–60 min** | V6 D16 — battery visibility during idle periods |
| 10 | Work counter | Not sent | **`work_count` param on every pushed event** | V6 — carries the current displayed/net task count |
| 11 | event_code | Not possible | **Task frames: 179, 180, 35; diagnostics telemetry: final Option B** | HAMS-local legacy codes remain non-outbound; Option B telemetry codes are device + Wialon verified |
| 12 | Coordinate format | DDMM.MMMM | **Unchanged** | Proven correct in V5 |
| 13 | Batch size | 10 msgs/session, 75ms delay | **Unchanged** | Proven safe in V5 |
| 14 | Wialon host/port | `185.213.1.24:20332` | **Unchanged** | — |
| 15 | Hardware type | Wialon IPS (600002235) | **Unchanged** | — |
| 16 | Unit password | `NA` | **Unchanged** (hardening flagged as D-future) | Current test units work without passwords |
| 17 | Report type | Messages tracing | **Unchanged** | Row-counting approach proven |
| 18 | Row count semantic | 1 row = 1 cut | **1 row where `ffb_cut=1` = 1 cut** | Filter changed, semantic unchanged |
| 19 | Port 21416 usage | Do not use | **Unchanged — do not use** | MeiTrack protocol, P99L-only |
| 20 | IPS v2.0 usage | Not available on license | **Unchanged — not available, not needed** | v1.1 + params is sufficient |
| 21 | Notification rule dependency | Not needed for app | **Unchanged — not needed** | Report reads rows directly |
| 22 | WiaTag protocol / port 20963 | Not investigated | **Confirmed available (hw_type 600000799) but not needed** | V6 — IPS v1.1 full form is sufficient |
| 23 | Timestamp indexing | Server receive time | **Unchanged** | Wialon indexes by `rt` |

---

## Section 7 — Known Issues / Risks

| ID | Issue | Impact | Mitigation |
|---|---|---|---|
| F1 | V5 conclusion "IPS v1.1 rejects custom params" was wrong | Caused adoption of course=1 hack | **Resolved in V6** — 16-field frame with params accepted and stored |
| F5 | Offline batch: each press = one #D# with original timestamp | Timestamps may arrive out of order | Wialon accepts out-of-order; N-1 uses server receive time |
| B-ISSUE-5 | Decimal→DDMM conversion must be correct | Wrong GPS → wrong geofence | Unit-test conversion function before integration test |
| F7 | Speed/distance defaults silently drop messages | Missing data in production | Must set both to 0 on ALL production units (admin action) |
| F11 | New V6 report template needs building | Reports won't match V6 data until template exists | Admin action — build `HAMS_FFB_Cut_Count_V6` |
| D10 | V6 approach decision | Proceed with V6; route questions to KC | Resolved |
| D11 | event_code routing during dev vs prod | Risk of firing production alerts on test data | **Resolved for diagnostics telemetry 2026-07-02** — task frames still use isolated test units/resources for 179/180; diagnostics telemetry Option B codes are final and 279/280 remain non-outbound unless explicitly re-approved |
| Known V6 trade-off | Self-cancelling +/− pairs create small over-count in Wialon reports (+press pushes before − cancels it) | ~2% inflation in edge cases | Accepted — SQLite stays truthful; reports slightly inflated when workers self-correct |
| D-future | Per-unit IPS passwords | Security hardening for production | Deferred to Phase 3/4. Current `NA` password works. |

---

## Section 8 — Production Wialon Structure (Reference Only)

| Item | Value |
|---|---|
| Production resource | Ladang Landak (ID: `600856837`) |
| Report 1 | Ladang Landak Count Summary Reports Harvester VS Collector |
| Report 2 | LT Gang Ladang Landak Havested VS Collected (Approved) |
| Report 3 | Ladang Landak Coverage Report (Approved) |
| Report 4 | Ladang Landak Havested VS Collected (Approved) |
| Plus notification | Landak Landak Bunch Cutter Plus Events — event_code=179 |
| Minus notification | Ladang Landak Bunch Cutter Minus Events — event_code=180 |
| Mobile counter | Mobile Counter (Bunch count) — io_1, value=1 |
| Collector | Ladang Landak T366E Bunch Collected Event Meitrack |

**Note:** These production reports and notifications are for the existing P99L setup. During V6 development, HAMS app test units must stay outside production notification scopes before pushing 179/180. The previous `event_code=279/280` dev-isolation approach is suspended unless explicitly re-approved with matching Wialon test reporting. At production cutover, HAMS app data flows into the same production pipeline via 179/180.

---

## Section 9 — Codex Usage Notes

When Codex reads this file, it should:
1. Use Section 3 for all IPS TCP implementation details (V6 16-field frame format)
2. Use Section 2 only for integration test curl commands — the app itself does not call REST
3. Use Section 1 for connection constants — put these in `AppConfig.kt` or `config.json`
4. Use Section 6 for any decision-making — do not re-derive or second-guess these
5. Use Section 7 before writing the coordinate conversion function — test it first
6. Use `docs/HAMS_EVENT_CODE_DICTIONARY.md` for event code values — do not hardcode codes inline

Codex should NOT:
- Implement geofence lookup in the app
- Use port 21416 (MeiTrack)
- Use port 20963 (WiaTag, unnecessary for V6)
- Use IPS v2.0 format (not supported on license)
- Implement REST API polling in the app
- Skip the coordinate conversion
- Use V5 10-field short frame — use V6 16-field + params block exclusively
- Hardcode event_code values — always read from config keys

---

## Section 10 — V5 → V6 Migration Notes

### What changed between V5 and V6

#### Protocol layer
- `#D#` frame: 10 fields → 16 fields + params block
- Per-press signal: `course=1` → `ffb_cut:1:1`
- Course field: fixed value 1 → real GPS heading (typically 0 for stationary)

#### New pushed data
- Phone battery (`battery`) — every pushed task frame and diagnostics telemetry frame
- Approved Wialon semantic code (`event_code`) — task frames use 179/180/35; diagnostics telemetry uses final Option B codes **29/40/24/25/41/42/26/27/43/44**
- Current displayed/net task count (`work_count`) — every pushed task frame; telemetry frames use `0`
- Horizontal DOP (`hdop`) — every pushed frame when a GPS snapshot is available

#### New event/local-state types
- Minus press (180) — pushed only when productive
- Task boundary markers (281) — recorded in SQLite only, not pushed
- Auto-save lifecycle — represented by task/save state locally, not custom Wialon event_code
- Battery threshold edges — local telemetry; battery itself rides the `battery` param
- GPS degraded — local telemetry until Wialon reporting is explicitly designed
- Periodic beacon (35) — pushed (configurable interval)

#### Wialon side
- Sensor `FFB_CUT` parameter: `course` → `ffb_cut`
- New sensor `battery_pct` reading param `battery`
- Optional new sensors `work_count`, `event_code` (event_code calibration should include task codes 179/180/35 plus final diagnostics telemetry Option B codes **29/40/24/25/41/42/26/27/43/44** when telemetry reporting is enabled)
- Report template filter: `course=1` → `ffb_cut=1`
- Report template columns: +Battery, +Event code

### What did NOT change

- Wialon host, port, DNS (`185.213.1.24:20332`, `nl2.gpsgsm.org`)
- Protocol version (still IPS v1.1)
- Hardware type (still 600002235)
- Token, session, authentication flow
- Unit creation and ACL flow
- Coordinate conversion (still DDMM.MMMM)
- Batch size, inter-message timing, retry state machine
- SQLite table names (schema extended — see `CLAUDE.md`)
- N8N daily/monthly workflow shape
- Geofence → Field/Task mapping logic (Section 4)
- PostgreSQL HAMSTask/HAMSTaskCount/HAMSTaskCountSum schemas
- Wialon production resource and its existing notification rules
- N-1 pull timing window (D-1 16:00 UTC → D 16:00 UTC for MYT)

### Migration order for Codex

When transitioning from V5 to V6 app code:

1. Extend SQLite events table with new columns (see `CLAUDE.md` SQLite Schema)
2. Rebuild the `#D#` frame builder to 16-field form with params block
3. Add BatteryManager read in the event capture path
4. Add work_count tracker — current displayed/net task count, reset on new task
5. Keep task-event outbound push eligibility restricted to 179/180/35, and route final diagnostics telemetry Option B codes through the separate `diagnostics` table / telemetry frame path
6. Update push engine to read and emit the new params; `IPSFrameBuilder` derives `ffb_cut` from approved outbound `event_code`
7. Unit-test the frame builder against the canonical example in §3.6
8. Integration test: push 3 frames with known params → fetch via `messages/load_interval` with `flagsMask:65281` → assert all params land in `p` block

### Migration order for Wialon admin

Before V6 app is deployed to a production unit:

1. Update `FFB_CUT` sensor — parameter: `course` → `ffb_cut`
2. Create `battery_pct` sensor — parameter: `battery`, unit: `%`
3. Build new report template — `HAMS_FFB_Cut_Count_V6`, filter `ffb_cut=1`, columns as specified in §5.5
4. Record the new template ID — update §5.5 and N8N workflow config once known
5. Verify with test unit — trigger an app + press, confirm it shows in the new template
6. (Optional) Create `work_count` and `event_code` sensors if supervisor UI benefits; event_code calibration should include task codes 179/180/35 plus final diagnostics telemetry Option B codes **29/40/24/25/41/42/26/27/43/44** when telemetry reporting is enabled

Do NOT apply these changes to the test unit in parallel with V5 app testing — each unit should be on one version at a time to avoid sensor read ambiguity.

---

*Last updated: 2026-07-02 | Maintained by: WYH | Version: V6 event-code policy v1.3 + diagnostics telemetry Option B*
*Read alongside: CLAUDE.md, docs/HAMS_EVENT_CODE_DICTIONARY.md, docs/checkpoints/HAMS_API_TESTING.md, docs/HAMS_APP_REQUIREMENTS.md*
