<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# HAMS Production Documentation

Use this folder for live operating, debugging, and scaling information.
Historical development records belong outside this production document set.

| Area | Document | Purpose |
|---|---|---|
| Operations | [OPERATIONS_RUNBOOK.md](operations/OPERATIONS_RUNBOOK.md) | Diagnose and respond to production issues. |
| Operations | [DEVICE_PROVISIONING.md](operations/DEVICE_PROVISIONING.md) | Pair, release, replace, and verify devices. |
| Operations | [SCALING_GUIDE.md](operations/SCALING_GUIDE.md) | Add units, sites, and operational capacity safely. |
| Operations | [n8n workflow snapshots](operations/n8n-workflow-snapshots/README.md) | Sanitized recovery/reference exports; not live deployment instructions. |
| Operations | [database dictionary](operations/database-reference/DATABASE_DICTIONARY.md) | Database types, metadata, and approved SQL recovery reference. |
| Architecture | [SYSTEM_OVERVIEW.md](architecture/SYSTEM_OVERVIEW.md) | Current production components and ownership. |
| Architecture | [DATA_FLOW.md](architecture/DATA_FLOW.md) | App-to-Wialon-to-backend data flow. |
| Main guide | [HAMS_PRODUCTION_REFERENCE.md](HAMS_PRODUCTION_REFERENCE.md) | Editable single production reference, user guide, backend overview, and credentials-governance template. |
| Main guide | `HAMS_PRODUCTION_REFERENCE.docx` | The formal, circulated version of the same reference: cover page, contents, and screenshots. Keep it in step with the markdown; patch it in place rather than regenerating. |
| Releases | `../releases/` | One record per distributed build (`hams-<version>.md`) giving its SHA-256, signer fingerprint, and what changed. The `.apk` files themselves are not in Git. |
| Reference | [CONFIGURATION_REFERENCE.md](reference/CONFIGURATION_REFERENCE.md) | Configuration keys, ownership, and secret-storage rules. |
| Reference | [ANDROID_APP_SPEC.md](reference/ANDROID_APP_SPEC.md) | Code-derived Android behaviour, limits, data storage, and delivery. |
| Reference | [FR_APP_REQUIREMENTS.md](reference/requirements/FR_APP_REQUIREMENTS.md) | Functional requirements with MoSCoW alerts. |
| Reference | [NFR_APP_REQUIREMENTS.md](reference/requirements/NFR_APP_REQUIREMENTS.md) | Non-functional requirements with MoSCoW alerts. |
| Reference | [EVENT_CODE_DICTIONARY.md](reference/EVENT_CODE_DICTIONARY.md) | Canonical event-code meanings and push rules. |
| Reference | [TESTING_RECORD.md](reference/TESTING_RECORD.md) | Brief recorded verification and the known report-side gap. |

`protocols/legacy/` contains investigation-only vendor material; it is not live integration guidance.
