<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# Functional Requirements — HAMS Android App

**Purpose:** Define the current production behaviour required from the HAMS Android application.

## Priority alert legend

| Alert | MoSCoW priority | Meaning |
|---|---|---|
| 🔴 | Must | Required for safe production operation. |
| 🟠 | Should | Important operational capability; defer only with an approved workaround. |
| 🟢 | Could | Useful improvement; not required for current operation. |
| ⚪ | Won't | Explicitly outside the current production scope. |

## Identity and access

| ID | Priority | Requirement |
|---|---|---|
| FR-01 | 🔴 Must | The app must pair a phone to a Wialon unit through the office OTP and company n8n registry flow; a compile-time unit id is development fallback only. |
| FR-02 | 🔴 Must | The app must verify its binding at launch, before a push, and periodically, and must stop unsafe delivery when the unit is released or belongs to another phone. |
| FR-03 | 🟠 Should | The app should provide an office-only route to request a supervisor OTP when the configured company endpoint supports it. |

## Field counting and task handling

| ID | Priority | Requirement |
|---|---|---|
| FR-04 | 🔴 Must | A count must not be recorded without location permission, enabled device location services, and a fresh GPS lock. |
| FR-05 | 🔴 Must | `+` must record a cut with timestamp, GPS, battery, and current task count; `−` must safely correct the count. |
| FR-06 | 🔴 Must | Scaled presses must create one event per count change while sharing the captured GPS, timestamp, and battery snapshot. |
| FR-07 | 🔴 Must | A new task must require a three-second hold and must preserve the prior task for later delivery. |
| FR-08 | 🔴 Must | Events and task state must be saved locally before network delivery is attempted. |
| FR-17 | 🔴 Must | Every press outcome must be announced audibly and haptically **after** the record is written — a distinct cue for a recorded `+`, a recorded `−`, and a refused press — so a worker who is not watching the screen can tell a counted press from a refused one. (From 1.2.) |
| FR-18 | 🟠 Should | Recorded presses of the same button should be held at least `PRESS_MIN_INTERVAL_MS` apart so each occupies its own wire second, and a press refused by that limit must be announced rather than dropped silently. (1.3, trial build only; the two buttons are tracked separately.) |

## Delivery and Wialon integration

| ID | Priority | Requirement |
|---|---|---|
| FR-09 | 🔴 Must | The app must push eligible finished-task events through Wialon IPS v1.1 using the approved full frame and coordinate conversion. |
| FR-10 | 🔴 Must | Only approved task codes (`179`, productive `180`, and `35`) may use the task-event delivery path. |
| FR-11 | 🔴 Must | The app must retain pending work after a recoverable delivery failure and provide a terminal delivery outcome. |
| FR-12 | 🟠 Should | The app should provide automatic delivery when network constraints are met and a user-controlled manual delivery path. |

## Safe release and diagnostics

| ID | Priority | Requirement |
|---|---|---|
| FR-13 | 🔴 Must | On device-initiated release, the app must attempt bounded delivery under the currently owned unit before stranding any remaining work. |
| FR-14 | 🔴 Must | Work that cannot safely be delivered after release must never upload under a later unit assignment. |
| FR-15 | 🟠 Should | The app should collect supported diagnostic telemetry separately from task-event counts. |
| FR-16 | ⚪ Won't | The app will not perform geofence matching, reporting, or downstream business aggregation; those belong to Wialon and company systems. |

See [Non-functional requirements](NFR_APP_REQUIREMENTS.md), [Event-code dictionary](../EVENT_CODE_DICTIONARY.md), and [Operations runbook](../../operations/OPERATIONS_RUNBOOK.md).
