# HAMS Task Recorder — Test Cases

A verification checklist to confirm a build works end to end. Run the **Automated** block first
(fast, catches regressions), then the **Backend** and **App** cases against your own environment.
Tick each row; a build is "good" when all pass.

- Setup steps referenced here: [SETUP.md](SETUP.md) · Backend build: [provisioning/BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md)
- 🔴 = needs your own credentials/unit · 🟢 = uses the preset shared platform

---

## A. Automated tests (no manual steps)

| ID | Command | Expected |
|---|---|---|
| TC-AUTO-01 | `.\gradlew.bat :app:testDebugUnitTest` | `BUILD SUCCESSFUL`; all JVM unit tests pass (coordinate conversion, IPS frame, push eligibility/engine, provisioning parsers, etc.) |
| TC-AUTO-02 | `.\gradlew.bat :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL`; instrumented Room tests pass (needs a connected device/emulator) |
| TC-AUTO-03 | `.\gradlew.bat :app:lintDebug` | No new lint errors |

---

## B. Backend (n8n + Postgres) — webhook contract

Prerequisite: backend built and published ([BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md)),
`PROV_DB_URL` set, base URL reachable. Mint a fresh OTP where a case needs a valid one:
`psql $env:PROV_DB_URL -c "SELECT issue_otp(10);"`.

| ID | Action | Expected |
|---|---|---|
| TC-BE-01 | `POST /webhook/manual-claim` with **no** `x-hams-key` | `401 {"error":"unauthorized"}` |
| TC-BE-02 | `POST /webhook/manual-claim`, correct key, **bad** OTP | `401 {"error":"admin_auth_failed"}` |
| TC-BE-03 | `POST /webhook/manual-claim`, correct key + valid OTP, free unit 🔴 | `200 {"unique_id":…}`; `units` row now `claimed=true` with your fingerprint |
| TC-BE-04 | Repeat TC-BE-03 for a unit owned by **another** device | `409 {"error":"already_bound"}` |
| TC-BE-05 | Claim a **second** unit from a device that already owns one | `409 {"error":"fingerprint_in_use","on":…}` |
| TC-BE-06 | Claim an **unknown** unit id | `404 {"error":"not_found"}` |
| TC-BE-07 | `POST /webhook/release` by the owning device, valid OTP | `200 {"ok":true}`; unit `claimed=false`, fingerprint cleared |
| TC-BE-08 | `POST /webhook/release` by a non-owner | `409 {"error":"not_owner_or_not_found"}` |
| TC-BE-09 | Re-run the `seed` workflow twice | Idempotent — unit names UPSERT, `claimed`/`fingerprint` untouched |

Workflow references: [manual-claim](docs/image_guideline/n8n/n8n-workflow-manual-claim.png) ·
[release](docs/image_guideline/n8n/n8n-workflow-release.png) ·
[generate-otp](docs/image_guideline/n8n/n8n-workflow-generate-otp.png) ·
[seed](docs/image_guideline/n8n/n8n-workflow-seed.png).

---

## C. App — pairing & identity

Prerequisite: app installed, backend reachable, an OTP minted.

| ID | Steps | Expected |
|---|---|---|
| TC-APP-01 | Launch on an **unprovisioned** device | PairingScreen shown; counting blocked until paired |
| TC-APP-02 | Enter a valid unit id + OTP → Pair 🔴 | Advances to the count screen; `hams_prefs.xml` → `device_unique_id=<unit>` |
| TC-APP-03 | Enter a wrong/expired OTP | Error shown (`admin_auth_failed`); stays on PairingScreen |
| TC-APP-04 | Tap the battery pill → admin sheet | Shows current unit + Re-bind / Reset pairing (see `app-03-admin-sheet.jpeg`) |
| TC-APP-05 | Reset pairing → enter supervisor OTP | Reset gated by OTP (see `app-04-supervisor-code.jpeg`); on success device returns to PairingScreen |

---

## D. App — counting & GPS gate

Prerequisite: paired device, outdoors for a real GPS lock.

| ID | Steps | Expected |
|---|---|---|
| TC-APP-06 | Deny location / turn off location services, launch | App shows a blocking message and closes |
| TC-APP-07 | Launch with GPS warming up (indoors) | `+`/`−` disabled or pill not green until a fresh fix arrives |
| TC-APP-08 | Outdoors, GPS pill green, press **+** | Count increments by 1; a `179` event row is written with lat/lon/battery/sats |
| TC-APP-09 | Press **−** while count > 0 | Count decrements; `180` event written (pushes only if result > 0) |
| TC-APP-10 | Press **−** down to 0 | The zeroing pair stays local (`pushed=1`), not pushed |
| TC-APP-11 | Check a `+` press's stored row (`adb ... sqlite3`) | `satellites` ≥ ~12 and `hdop` ≤ ~3 under open sky (not `null`/`0`) |

Count screen reference: `docs/image_guideline/app/app-01-count-screen.jpeg`.

---

## E. App — task lifecycle & push

| ID | Steps | Expected |
|---|---|---|
| TC-APP-12 | Hold **NEW TASK** 3 s → confirm | Current task finalized (`push_status=pending`), fresh task at count 0; upload badge shows pending count (see `app-02-new-task.jpeg`) |
| TC-APP-13 | With pending tasks, connect **unmetered Wi-Fi** | Auto-push fires; notification "N tasks uploaded ✓" (see `app-05-notifications.jpeg`) |
| TC-APP-14 | On mobile data only, trigger **manual push** (hold on count screen) | Status panel Pending→Pushing→Completed; cache preserved on failure |
| TC-APP-15 | In Wialon UI, open the pushed unit's messages 🟢 | Each message shows `ffb_cut=1`, `event_code=179`, `battery`, `work_count`, and `lat, lon (N)` with N = satellites |
| TC-APP-16 | Swipe the app away with an active task > 0 | `onTaskRemoved` saves the task locally (`auto_killed`) — no data loss |
| TC-APP-17 | Leave a task active across local midnight (or change day) | Daily rollover finalizes yesterday's task; today's first `+` starts `task_seq=1` |

---

## Pass criteria

A release candidate passes when **all TC-AUTO**, **all TC-BE**, and **TC-APP-01…16** pass, and
TC-APP-15 confirms real cuts with satellites > 0 landing on the correct Wialon unit.
