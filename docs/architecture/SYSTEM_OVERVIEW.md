<!-- Production release — 2026-08-26. Reflects app 1.2 (production) and 1.3 (trial). -->
# System Overview

**Purpose:** Explain the live HAMS components, their ownership boundaries, and where to begin a production investigation.

## Production components

| Component | Responsibility | Operational boundary |
|---|---|---|
| Android app | Records cuts offline, manages device pairing, and pushes telemetry. | Installed on field devices. |
| Wialon IPS | Receives cut and diagnostics messages from the app. | Company Wialon account and unit configuration. |
| n8n | Handles device identity administration: OTP issue, claim, release, verification, and office administration. | Company-managed n8n instance. |
| PostgreSQL | Stores unit bindings, OTP state, and drain leases used by n8n. | Company-managed database. |

The app sends cut data directly to Wialon. n8n and PostgreSQL do not sit in the cut-data path; they are the device-identity administration path.

## App responsibilities

- A worker records a cut only while GPS is locked.
- Events are stored locally first in Room/SQLite.
- A paired unit is verified at launch, before push, and periodically while the app is active.
- Pending finished tasks are pushed through Wialon IPS. Pairing and release use the configured company n8n webhooks.

## Configuration and source locations

| Need | Location |
|---|---|
| App-side key names and secret-handling rules | [Configuration reference](../reference/CONFIGURATION_REFERENCE.md) |
| Event semantics | [Event-code dictionary](../reference/EVENT_CODE_DICTIONARY.md) |
| Unit configuration and pairing | [Device provisioning](../operations/DEVICE_PROVISIONING.md) |
| Android source | `app/src/main/` |
| Database scope and operational boundary | [Database dictionary](../operations/database-reference/DATABASE_DICTIONARY.md) |
| Sanitized n8n workflow recovery snapshots | `docs/operations/n8n-workflow-snapshots/` |

Do not record real credentials, OTPs, database URLs, or production tokens in this document.
