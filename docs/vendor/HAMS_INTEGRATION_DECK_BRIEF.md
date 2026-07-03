# HAMS App — Vendor Integration Deck (Build Brief)

> Use this file to reproduce the vendor integration deck. Hand to anyone (or
> another AI tool) to rebuild the same presentation. Source content lives in
> `HAMS_INTEGRATION_REFERENCE.md`; this file is the slide plan.

---

## Purpose

A short PowerPoint deck for the P99L hardware partner. They use the same
Wialon platform; the deck shows how the HAMS Android app uses that platform
so the vendor can verify our setup matches their conventions and flag any
divergence.

## Audience

- P99L hardware vendor / their Wialon integration engineer.
- Familiar with Wialon admin and IPS protocol.
- Likely reads as a self-contained PDF or runs through it as a screen walk-through.

## Deliverable

- Single `.pptx` file, ~11 slides.
- Print-to-PDF must look clean.
- Editable for vendor markup (comments / notes pane).
- Plain enough to send via email; no animations, no external assets.

---

## Design notes (apply to every slide)

| Element | Choice |
|---|---|
| Aspect ratio | 16:9 widescreen |
| Title font | Sans-serif, bold (e.g. Calibri / Inter) |
| Body font | Same family, regular weight |
| Accent colour | Single muted green (suggests "harvester / agronomy"); reuse for table headers and call-outs |
| Background | White |
| Text density | Bullets, short. No paragraphs of prose. |
| Tables | Two-tone rows, accent-coloured header row |
| Footer | Page number, slide title small-print, doc title |
| Code/identifier samples | Monospace font (e.g. Consolas) |

---

## Slide-by-slide content

### Slide 1 — Cover
- Title: **HAMS App — Wialon Integration Reference**
- Subtitle: *For P99L hardware partner — verification of platform setup*
- Small print: version, date, contact

### Slide 2 — What the app does
Title: **About the App**

- Android app for FFB harvesters; replaces the P99L hardware tracker for cut counting.
- Worker presses `+` per FFB cut → app stores GPS + battery + timestamp locally.
- Pushes batched events to the same Wialon platform as P99L when validated Wi-Fi appears.
- Uplink only — no commands/replies from Wialon.

### Slide 3 — UI workflow
Title: **UI Workflow**

- *Placeholder slide. Insert screenshots of the app's main screens + a short flow diagram.*

### Slide 4 — Wialon server target
Title: **Server Target**

Table:

| Item | Value |
|---|---|
| IPS host | `185.213.1.24` (DNS `nl2.gpsgsm.org`) |
| IPS port | `20332` |
| Protocol | Wialon IPS v1.1 (text frames over TCP) |
| REST API host (read-side only) | `https://hst-api.wialon.eu` |
| Web UI for verification | `https://pro.navi-agnostics.com` |

Footnote: No password. Login frame `#L#<unique_id>;NA` → expect `#AL#1`.

### Slide 5 — Test unit configuration
Title: **Unit Configuration**

Two stacked tables.

**Identity**

| Item | Value |
|---|---|
| Test unit name | `TEST_HAMS_APP_001` |
| Test unit ID | `601602811` |
| Unique ID | `HAMS_TEST_001` |
| Hardware type | Wialon IPS (ID `600002235`) |
| Password | *(blank)* |

**Required Advanced-tab filters**

| Filter | Required value |
|---|---|
| Average speed between messages | `0` |
| Distance between coordinates | `0` |
| Message validity filtration | Off |

Call-out (red/amber): *Non-zero speed/distance filters cause Wialon to silently drop every message — same constraint as P99L.*

### Slide 6 — Sensors on the unit
Title: **Sensors**

| Sensor | Parameter | Type | Notes |
|---|---|---|---|
| `FFB_CUT` | `ffb_cut` | Custom | `1` = cut event |
| `battery` | `battery` | Custom (units `%`) | Phone battery percent |
| `event_code` | `event_code` | Custom | Optional calibration: task 179 / 180 / 35 plus final diagnostics telemetry **29 / 40 / 24 / 25 / 41 / 42 / 26 / 27 / 43 / 44** |
| `work_count` | `work_count` | Counter | Displayed/net count per task |

Call-out: *Parameter-naming difference from P99L — the HAMS app uses descriptive parameter names (`ffb_cut`, `battery`, `event_code`, `work_count`) instead of P99L's short single-letter names. Both are valid per IPS v1.1, but Wialon sensors created for HAMS units must use the descriptive names shown above.*

### Slide 7 — Data frame
Title: **Data Frame**

Top: monospace block —

```
#D#DDMMYY;HHMMSS;DDMM.MMMM;N;DDDMM.MMMM;E;speed;course;alt;sats;hdop;
   inputs;outputs;adc;ibutton;params\r\n
```

Below it, a "key fields" table (omit obvious ones for slide brevity):

| Field | App-side value |
|---|---|
| coords | `DDMM.MMMM` (lat 2-digit deg, lon 3-digit deg) |
| speed / course | `0` / `0` (worker on foot; no `course=1` FFB-hack) |
| inputs / outputs / adc / ibutton | `0` / `0` / *empty* / `NA` |
| params | custom block (next slide) |

Expected response: `#AD#1`.

### Slide 8 — Custom params block
Title: **Custom Params (Field 16)**

Monospace:
```
ffb_cut:1:<0|1>,battery:2:<0.0-100.0>,event_code:1:<179|180|35|29|40|24|25|41|42|26|27|43|44>,work_count:1:<0..>
```

| Param | Type | Meaning |
|---|---|---|
| `ffb_cut` | 1 (int) | `1` on `+` press, `0` otherwise |
| `battery` | 2 (double) | Phone battery percent at capture |
| `event_code` | 1 (int) | See next slide |
| `work_count` | 1 (int) | Displayed/net task count after the event |

### Slide 9 — Outbound event codes
Title: **Event Codes**

| Code | Meaning | Frequency |
|---|---|---|
| `179` | FFB cut / plus | Per worker `+` press |
| `180` | FFB correction / productive minus | Per `−` press, only when net count > 0 |
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

Footnote: Legacy HAMS-internal lifecycle / health codes stay on-device unless a new Wialon reporting design explicitly approves them. The Option B diagnostics telemetry codes above are final.

### Slide 10 — Push trigger & batching
Title: **Push Trigger and Batching**

Left half: flow diagram (boxes + arrows, vertical) —
1. Worker presses `+`
2. App stores event locally (SQLite)
3. Validated unmetered Wi-Fi connects (or 3-sec manual hold)
4. App opens TCP to `185.213.1.24:20332`
5. Login → `#AL#1`
6. Up to 10 `#D#` frames per session, ~75 ms between frames
7. Each frame → expect `#AD#1`
8. Close socket, repeat for next batch

Right half: rules table —

| Rule | Value |
|---|---|
| Trigger | Validated unmetered Wi-Fi (monitored by background service) |
| Manual override | 3-sec long-press of the push button in the app |
| Frames per TCP session | 10 |
| Inter-frame delay | ~75 ms |
| Login required per session | Yes |

Footer: *The push monitor stays alive while pending events exist — even with the app closed — so push fires the moment Wi-Fi appears.*

### Slide 11 — What the app does NOT send
Title: **What the App Does Not Send**

- No digital `inputs` / `outputs` bits (both = `0`)
- No ADC values
- No driver / ibutton key (sent as `NA`)
- No heading on `course` (= `0`; legacy V5 `course=1` FFB-hack is removed)
- No legacy HAMS-internal lifecycle / health event codes; only the final Option B diagnostics telemetry codes are sent
- No data flows back from Wialon to the app — uplink only

### Slide 12 — How to verify
Title: **How to Verify**

- Same test unit (`TEST_HAMS_APP_001`, ID `601602811`).
- Open it on `pro.navi-agnostics.com` and verify the stored messages the same way the P99L unit was verified during its integration.
- Check: message arrival, geofence resolution, sensor decode, report runs.
- *Wialon UI screenshots to be attached separately by the doc owner.*

---

## Build checklist (for the deck author)

- [ ] All identifiers (`601602811`, `HAMS_TEST_001`, `600002235`, `185.213.1.24`) typed correctly — no autocorrect changes.
- [ ] Monospace font on every code/frame block.
- [ ] Slide 5 has the speed/distance call-out highlighted.
- [ ] Slide 6 has the parameter-naming-difference call-out.
- [ ] Slide 3 and Slide 12 have visible "screenshots go here" placeholder boxes for the doc owner to drop images into.
- [ ] Print-to-PDF preview is clean (no clipping, no overflow off the slide).
- [ ] Speaker notes left empty unless the doc owner adds talking points.

---

*End of brief.*
