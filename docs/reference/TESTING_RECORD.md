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

## Known verification gap

The final production Wialon report-template and geofence-side result after cutover is not clearly recorded as fully verified. Treat it as an outstanding regression/acceptance test before relying on report output changes.

See [Functional requirements](requirements/FR_APP_REQUIREMENTS.md), [Non-functional requirements](requirements/NFR_APP_REQUIREMENTS.md), and the [Operations runbook](../operations/OPERATIONS_RUNBOOK.md).
