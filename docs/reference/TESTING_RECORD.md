<!-- DRAFT — production-content review required. -->
# Testing Record

**Purpose:** Brief record of the HAMS behaviour already tested. It records prior verification; it is not a live test run or a substitute for release regression testing.

## Recorded verification

| Area | Test coverage |
|---|---|
| App launch | GPS permission and location-service gate block counting when unavailable. |
| Counting | `+`, `−`, scaled count, displayed/net count, and maximum-count handling. |
| Tasks | First-cut task creation, new-task hold, save/rollover, and app-removal recovery. |
| Offline | Events persist locally before any network delivery. |
| GPS | Fresh-location requirement; stale or absent GPS disables count actions. |
| IPS frame | Login, V6 16-field frame, DDMM.MMMM coordinate conversion, and named parameters. |
| Event policy | `179`, productive `180`, and `35` use the task delivery path; local-only events do not. |
| Delivery | Batch delivery, acknowledgements, retry/backoff, manual delivery, and automatic network-triggered delivery. |
| Push safety | Manual and background delivery coordination prevents duplicate work. |
| Device lifecycle | OTP pairing, bad-OTP rejection, release, re-pairing, and office admin release. |
| Binding checks | `bound`, `released`, `bound_other`, and conservative `not_found` outcomes. |
| Release safety | Pending cuts deliver before release when possible; failed delivery strands rows safely. |
| Diagnostics | Boot/shutdown, GPS transition, motion, screen, and power telemetry path. |
| Wialon | Message receipt, named parameters, and unit filter/sensor configuration. |
| Backend contract | n8n claim/release/verify flow and PostgreSQL routine status responses. |

## Verified 19-20 August 2026

| Area | Result |
|---|---|
| Press feedback (1.2) | Distinct `+`/`-` tones and vibration confirmed on a handset. |
| In-place upgrade | 1.2 to 1.3 over an existing install kept pairing, fingerprint, and unsent rows. |
| Wialon message retention | Raw unit messages show same-timestamp messages retained - three at `16:06:55`, three at `16:06:57`. No task message lost in transport. |
| Automatic seeding | End-to-end: a code request for a unit absent from the registry seeded it before the OTP was issued. |
| Webhook path stability | The production OTP URL after republishing matches `OTP_REQUEST_URL` compiled into the deployed APKs. |

Not verified on a handset: the 1.3 press rate limit, its refusal cue, and whether the notification-based count now matches the number of presses. That last one is the acceptance test for 1.3.

## Known verification gap

The production Wialon report-template result is still not verified end to end.

The cause of the shortfall is now established - the notification layer triggers at most once per second, so presses sharing a second were counted once - but neither remedy has been confirmed against a full day of field data. Treat both as outstanding:

- **1.3 rate limit** - does the notification count now equal the presses made?
- **`work_count` delta report** - does it reproduce the true figure, including across task resets and `-` corrections? The `delta <= -2 means new task` rule is inferred and unproven.

See [Functional requirements](requirements/FR_APP_REQUIREMENTS.md), [Non-functional requirements](requirements/NFR_APP_REQUIREMENTS.md), and the [Operations runbook](../operations/OPERATIONS_RUNBOOK.md).
