<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# HAMS Event-code Dictionary

This reference maps the current Android source to its local and Wialon IPS event codes. It is not evidence of live Wialon reports, notifications, or server-side configuration.

## Delivery paths

| Path | Source records | Codes that can queue |
|---|---|---|
| Task event | `events` table | `179`, productive `180`, `35` |
| Diagnostics telemetry | `diagnostics` table | `24`, `25`, `26`, `27`, `29`, `40`, `41`, `42`, `43`, `44`, `301`, `302`, `303`, `304` |
| Local-only audit | `events` table or task metadata | `281`, `283`, `284`, `291`, `292`, `293` |

`PushEligibility` queues `180` only when the post-action `work_count` is greater than zero. All other local task/audit codes are inserted as local-only records. A successful send is acknowledged before the corresponding queued record is marked delivered.

## Task codes

| Code | Source meaning | Queue rule | IPS effect |
|---:|---|---|---|
| 179 | Plus/cut | Always queued | `ffb_cut=1`; carries battery, code, and post-action work count. |
| 180 | Productive minus/correction | Queue only when post-action work count is above zero | `ffb_cut=0`; carries battery, code, and post-action work count. |
| 35 | Heartbeat | Always queued while the service scheduler is active | `ffb_cut=0`; carries battery, code, and current work count. |

The current heartbeat interval is one minute (`AppConfig.HEARTBEAT_INTERVAL_MINUTES`). It is a source default, not a guarantee that Android will execute the app continuously under every OS power condition.

## Local-only task and health markers

| Code | Source meaning | Reaso n it does not use the task delivery path |
|---:|---|---|
| 281 | New task marker | Local task boundary/audit. |
| 283 | Auto-save on kill | Local audit. |
| 284 | Auto-save before push | Legacy-compatible local audit; current push flow does not finalize active tasks. |
| 291 | Battery warning edge | Local threshold marker. |
| 292 | Battery critical edge | Local threshold marker. |
| 293 | GPS degraded | Local degraded-GPS marker. |

Battery percentage is also included in each queued task frame, regardless of whether a local battery-edge marker exists.

## Diagnostics telemetry

| Code | `DiagnosticType` source meaning |
|---:|---|
| 24 | GPS lost |
| 25 | GPS recovery |
| 26 | Screen off |
| 27 | Screen on |
| 29 | Device boot |
| 40 | Device shutdown |
| 41 | Stop moving |
| 42 | Start moving |
| 43 | Power connected |
| 44 | Power disconnected |
| 301 | Binding released |
| 302 | Work stranded during device release; carries `lost_cuts` when available |
| 303 | Device bound |
| 304 | Device unbound |

Telemetry uses a separate frame builder. It may send a GPS snapshot when one is available; otherwise it uses zero coordinates. It must not change the pending count of task events.

## IPS parameters

The task frame builder emits a 16-field `#D#` message with these named parameters:

| Parameter | Type | Rule |
|---|---|---|
| `ffb_cut` | integer | Derived at frame build: 1 only for code 179. |
| `battery` | decimal | Battery percentage captured with the record. |
| `event_code` | integer | The relevant code in this document. |
| `work_count` | integer | Task net count after the action; diagnostics use 0. |
| `lost_cuts` | integer | Included only by the stranded-work telemetry frame when supplied. |

The application does not have a stored `ffb_cut` database column. It derives that parameter from the event code when creating the frame.

## Change control

Changing a code, mapping, or queue rule requires source, tests, and this dictionary to change together. The legacy vendor PDFs under [protocols/legacy](../protocols/legacy/) are background reference only; they do not define the active app mapping.

## Wire timestamps

IPS frames carry whole seconds (`ddMMyy;HHmmss`, UTC). From app version 1.3 an
event whose second is already occupied by another press is stored one second
later, so each press occupies its own second on the wire. Drift is capped at 300
seconds, past which true time is used and a shared second is accepted.

An event's wire time can therefore be later than the instant it was pressed.
`created_at` in the local database is never adjusted and remains true clock time.

Wialon itself retains messages that share a timestamp (verified 19 August 2026),
so this spacing is not required to avoid data loss. It exists because the Wialon
notification layer triggers at most once per second, and because messages sharing
a timestamp cannot be ordered.
