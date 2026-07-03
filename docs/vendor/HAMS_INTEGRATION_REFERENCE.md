# HAMS App — Wialon Integration Reference

> Short reference for the P99L hardware partner: how the HAMS Android app
> uses the Wialon platform. Same Wialon account, same protocol family as
> P99L. Please verify the app's setup matches the conventions used for P99L
> and flag any divergence.

---

## 1. What the app does

- Android app for FFB harvesters; replaces the P99L hardware tracker for cut counting.
- Worker presses `+` for each FFB cut → app stores GPS + battery + timestamp locally.
- App pushes batched events to the same Wialon platform as P99L when validated Wi-Fi appears.
- Uplink only — the app does not receive commands or replies from Wialon.

---

## 2. UI workflow

*Screenshots and flow diagram to be attached separately.*

---

## 3. Wialon server target

| Item | Value |
|---|---|
| IPS host | `185.213.1.24` (DNS: `nl2.gpsgsm.org`) |
| IPS port | `20332` |
| Protocol | Wialon IPS v1.1 (text frames over TCP) |
| REST API host (read-side only) | `https://hst-api.wialon.eu` |
| Web UI for verification | `https://pro.navi-agnostics.com` |

No password used. Login frame: `#L#<unique_id>;NA\r\n` → expect `#AL#1`.

---

## 4. Unit configuration

The Android app is set up against the existing test unit. Please access it on
the Web UI and verify settings the same way the P99L unit was verified.

| Item | Value |
|---|---|
| Test unit name | `TEST_HAMS_APP_001` |
| Test unit ID | `601602811` |
| Unique ID | `HAMS_TEST_001` |
| Hardware type | Wialon IPS (ID `600002235`) |
| Password | *(blank)* |

### Required Advanced-tab filters

| Filter | Required value |
|---|---|
| Average speed between messages | `0` |
| Distance between coordinates | `0` |
| Message validity filtration | Off |

> **Critical:** non-zero speed/distance filters cause Wialon to silently
> drop every message. Same constraint as P99L.

---

## 5. Sensors on the unit

| Sensor | Parameter | Type | Notes |
|---|---|---|---|
| `FFB_CUT` | `ffb_cut` | Custom | `1` = cut event |
| `battery` | `battery` | Custom (units `%`) | Phone battery percent |
| `event_code` | `event_code` | Custom | Optional calibration: task 179 / 180 / 35 plus final diagnostics telemetry **29 / 40 / 24 / 25 / 41 / 42 / 26 / 27 / 43 / 44** |
| `work_count` | `work_count` | Counter | Displayed/net count per task |

> **Parameter-naming difference from P99L.** The HAMS app uses descriptive
> parameter names (`ffb_cut`, `battery`, `event_code`, `work_count`) rather
> than the short single-letter names typical of P99L. Both are valid per
> IPS v1.1, but Wialon sensors created for HAMS units **must use the
> descriptive names** shown above.

---

## 6. Data frame — what the app sends

Standard 16-field IPS v1.1 data frame over TCP, one frame per event.

```
#D#DDMMYY;HHMMSS;DDMM.MMMM;N;DDDMM.MMMM;E;speed;course;alt;sats;hdop;inputs;outputs;adc;ibutton;params\r\n
```

| Field | App-side value |
|---|---|
| date / time | UTC at event capture |
| latitude / longitude | DDMM.MMMM (latitude 2-digit deg, longitude 3-digit deg) |
| speed | `0` — worker on foot |
| course | `0` — no heading (not the legacy `course=1` FFB-hack) |
| altitude | terrain estimate |
| satellites / hdop | from Android Fused Location |
| inputs / outputs | `0` / `0` (n/a for a phone) |
| adc | empty |
| ibutton | `NA` |
| params | custom params block (see below) |

### Custom params block (field 16)

```
ffb_cut:1:<0|1>,battery:2:<0.0-100.0>,event_code:1:<179|180|35|29|40|24|25|41|42|26|27|43|44>,work_count:1:<0..>
```

| Param | Type code | Meaning |
|---|---|---|
| `ffb_cut` | 1 (int) | `1` on `+` press, `0` on every other event |
| `battery` | 2 (double) | Phone battery percent at capture |
| `event_code` | 1 (int) | Outbound code — see §7 |
| `work_count` | 1 (int) | Displayed/net task count after this event |

Expected response per frame: `#AD#1`.

---

## 7. Outbound event codes

Task frames send three harvest/heartbeat codes. Diagnostics telemetry sends the final Option B device + Wialon verified codes through a separate telemetry frame path.

| Code | Meaning | Frequency |
|---|---|---|
| `179` | FFB cut / plus | Per worker `+` press |
| `180` | FFB correction / productive minus | Per worker `−` press, only when net count > 0 |
| `35` | Periodic beacon (heartbeat) | Once per minute while a task is active |
| **`29`** | Boot | Device boot / app launch recovery |
| **`40`** | Shutdown | Clean or inferred shutdown |
| **`24`** | GPS lost | GPS lock lost after dwell |
| **`25`** | GPS recovery | GPS lock recovered after dwell |
| **`41`** | Stop moving | Motion detector stop transition |
| **`42`** | Start moving | Motion detector start transition |
| **`26`** | Screen off | Android screen-off broadcast |
| **`27`** | Screen on | Android screen-on broadcast |
| **`43`** | Power connected | Charger connected |
| **`44`** | Power disconnected | Charger disconnected |

Legacy HAMS-internal lifecycle and health codes stay on the device unless a
new Wialon reporting design explicitly approves them.

---

## 8. Push trigger and batching

```
    Worker presses +    ──►   App stores the event locally (SQLite)
                                       │
                                       ▼
        Validated unmetered Wi-Fi connects   (or 3-sec manual hold)
                                       │
                                       ▼
              App opens TCP to 185.213.1.24:20332
                                       │
                                       ▼
                  Login: #L#<unique_id>;NA   ──►  #AL#1
                                       │
                                       ▼
        Up to 10 #D# frames per TCP session, ~75 ms between frames
                                       │
                                       ▼
                      Each frame  ──►  expect #AD#1
                                       │
                                       ▼
                Close socket, reconnect for the next batch
```

| Rule | Value |
|---|---|
| Trigger | Validated unmetered Wi-Fi (a background service monitors this) |
| Manual override | 3-second long-press on the push button in the app |
| Frames per TCP session | 10 |
| Inter-frame delay | ~75 ms |
| Login required per session | Yes (`#L#…;NA`) |

The push monitor stays alive while pending events exist — even with the app
closed or swiped from recents — so push fires the moment Wi-Fi appears.

---

## 9. What the app does NOT send

- No digital `inputs` / `outputs` bits (both = `0`).
- No ADC values.
- No driver / ibutton key (sent as `NA`).
- No heading on `course` (sent as `0`; the legacy V5 `course=1 as FFB hack` is removed).
- No HAMS-internal lifecycle / health event codes — they remain on-device only.
- No data flows back from Wialon to the app — push is uplink only.

---

## 10. How to verify

The same test unit (`TEST_HAMS_APP_001`, ID `601602811`) is being used.
Please open it on `pro.navi-agnostics.com` and verify the stored messages
the same way the P99L unit was verified during its integration —
message arrival, geofence resolution, sensor decode, report runs.

*Screenshots of the stored messages on the Wialon Web UI — to be attached
separately.*

---

*End of reference.*
