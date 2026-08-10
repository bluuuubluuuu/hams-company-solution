<!-- DRAFT — production-content review required. -->
# Non-Functional Requirements — HAMS Android App

**Purpose:** Define the quality, safety, and operational constraints for the HAMS Android application.

## Priority alert legend

| Alert | MoSCoW priority | Meaning |
|---|---|---|
| 🔴 | Must | Required for safe production operation. |
| 🟠 | Should | Important operational capability; defer only with an approved workaround. |
| 🟢 | Could | Useful improvement; not required for current operation. |
| ⚪ | Won't | Explicitly outside the current production scope. |

## Platform and usability

| ID | Priority | Requirement |
|---|---|---|
| NFR-01 | 🔴 Must | The app must support Android API 33 and later; the current target is API 35. |
| NFR-02 | 🔴 Must | Counting controls must clearly indicate when GPS is unavailable so a worker cannot unknowingly record invalid work. |
| NFR-03 | 🟠 Should | Delivery progress and final outcome should be understandable without developer tools. |

## Reliability and data integrity

| ID | Priority | Requirement |
|---|---|---|
| NFR-04 | 🔴 Must | Count capture must work offline and persist locally before delivery. |
| NFR-05 | 🔴 Must | Delivery retry, task state, and release handling must prevent duplicate or cross-unit uploads. |
| NFR-06 | 🔴 Must | GPS coordinates must use the required decimal-degree-to-DDMM.MMMM conversion before Wialon delivery. |
| NFR-07 | 🔴 Must | Terminal task data must be retained locally for the configured retention period so troubleshooting evidence is available. |

## Security and change control

| ID | Priority | Requirement |
|---|---|---|
| NFR-08 | 🔴 Must | Real tokens, credentials, OTPs, database URLs, and signing credentials must not be committed to source control or documentation. |
| NFR-09 | 🔴 Must | Device identity and release paths must require the applicable binding and OTP safeguards. |
| NFR-10 | 🟠 Should | Changes to Wialon, n8n, database routines, event codes, or app identity settings should have an owner, test evidence, rollback plan, and rollout record. |

## Performance, operations, and scale

| ID | Priority | Requirement |
|---|---|---|
| NFR-11 | 🟠 Should | Delivery must batch and retry work without blocking routine field counting. |
| NFR-12 | 🟠 Should | The app should expose enough logs, local task state, app version, and binding outcome to support company IT diagnosis. |
| NFR-13 | 🟠 Should | New devices must be scalable through repeatable unit provisioning, not reuse of an existing unit identity. |
| NFR-14 | 🟢 Could | Company tooling could surface fleet-level health, stranded-work counts, and version compliance. |
| NFR-15 | ⚪ Won't | The app will not recreate or operate company n8n/PostgreSQL infrastructure from a developer workstation. |

See [Functional requirements](FR_APP_REQUIREMENTS.md), [Configuration reference](../CONFIGURATION_REFERENCE.md), and [Scaling guide](../../operations/SCALING_GUIDE.md).
