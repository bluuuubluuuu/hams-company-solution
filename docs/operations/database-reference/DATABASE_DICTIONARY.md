<!-- DRAFT — production-content review required. -->
# HAMS Database Dictionary

**Purpose:** Complete metadata reference for the company-managed PostgreSQL provisioning database. It supports device pairing, binding verification, release, and office administration. It is not the field cut-data path: Android records cuts locally and sends them directly to Wialon.

## Scope and object metadata

| Item | Current metadata |
|---|---|
| Engine | PostgreSQL |
| Tables | 2 |
| Data columns | 15 |
| Business routines | 8 |
| State model | Current unit/OTP state; not a harvest or audit-history store |
| SQL reference | [hams_setup.sql](hams_setup.sql), idempotent consolidated setup/recovery script |
| Naming | Tables and explicit indexes use quoted uppercase `G_PM_IT_IOT_HAMS_` names; columns and routines are unquoted lowercase |

Do not run the SQL during routine diagnosis. Any production database change requires company DB-owner approval, backup, tested rollback, and a recorded change.

## PostgreSQL type legend

| Type | Used for |
|---|---|
| `TEXT` | IDs, labels, fingerprints, OTPs, status values, and app version. |
| `BOOLEAN` | Whether a unit is currently claimed. |
| `TIMESTAMPTZ` | Absolute times with timezone: activity, audit, expiry, and drain lease. |
| `INTEGER` | OTP lifetime function parameter. |
| `JSONB` | Structured routine result returned through n8n to the Android app. |
| `VOID` | Routine completes with no value. |

## Table: `"G_PM_IT_IOT_HAMS_UNITS"`

One row per Wialon unit. The n8n seed flow creates/refreshes rows; phones do not create units.

| Column | PostgreSQL type | Null | Default | Constraint / metadata | Meaning |
|---|---|---:|---|---|---|
| `unique_id` | `TEXT` | no | — | Primary key | Wialon IPS unique ID; routes phone data to the unit. |
| `name` | `TEXT` | yes | — | — | Human-readable Wialon label; no business logic. |
| `claimed` | `BOOLEAN` | no | `false` | — | Whether a phone currently owns the unit. |
| `device_fingerprint` | `TEXT` | yes | — | Unique | Android device identity proving ownership; `NULL` when free. Sensitive operational data. |
| `status` | `TEXT` | no | `'active'` | Convention: `active` / `retired` | Unit lifecycle state. |
| `last_seen` | `TIMESTAMPTZ` | yes | — | — | Latest successful binding check from the owning device. |
| `app_version` | `TEXT` | yes | — | — | APK version reported by the owning device during binding verification. |
| `created_at` | `TIMESTAMPTZ` | no | `now()` | — | Unit seed creation time. |
| `updated_at` | `TIMESTAMPTZ` | no | `now()` | Set by routines | Last unit-record mutation; no trigger maintains it. |
| `drain_until` | `TIMESTAMPTZ` | yes | — | — | End of the temporary post-release delivery window. |
| `drain_fingerprint` | `TEXT` | yes | — | — | Fingerprint allowed to deliver during that drain window. |

### Indexes and constraints

- Primary-key index: `unique_id`.
- Unique index: `device_fingerprint`; PostgreSQL allows many `NULL` values for free units.
- Explicit partial index: `"G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"` on `unique_id`, where `claimed = false AND status = 'active'`. It supports a future free-unit view/auto-assign flow; manual pairing does not depend on it.

## Table: `"G_PM_IT_IOT_HAMS_ADMIN_OTP"`

Short-lived, single-use office OTPs. The table self-purges expired rows when an OTP is issued.

| Column | PostgreSQL type | Null | Default | Constraint / metadata | Meaning |
|---|---|---:|---|---|---|
| `code` | `TEXT` | no | — | Primary key | Six-digit OTP, stored as plaintext. Treat as sensitive while valid. |
| `created_at` | `TIMESTAMPTZ` | no | `now()` | — | Issue time. |
| `expires_at` | `TIMESTAMPTZ` | no | — | — | Hard expiry; default issue lifetime is 10 minutes. |
| `used_at` | `TIMESTAMPTZ` | yes | — | `NULL` = unused | Consumption time; makes the OTP single-use. |

## Routine dictionary

| Routine | Parameters | Return type | Purpose |
|---|---|---|---|
| `seed_unit` | `p_unique_id TEXT`, `p_name TEXT` | `VOID` | Insert a new unit or refresh its label without changing a live binding. |
| `issue_otp` | `p_ttl_minutes INTEGER DEFAULT 10` | `TEXT` | Purge expired OTPs and return a new six-digit OTP. |
| `otp_is_valid` | `p_otp TEXT` | `BOOLEAN` | Check validity without consuming an OTP. |
| `consume_otp` | `p_otp TEXT` | `BOOLEAN` | Atomically consume a still-valid OTP. |
| `manual_claim` | `p_unique_id TEXT`, `p_fingerprint TEXT`, `p_otp TEXT` | `JSONB` | Bind a phone to a unit after ownership, drain-lease, and OTP guards. |
| `release_unit` | `p_unique_id TEXT`, `p_fingerprint TEXT`, `p_otp TEXT` | `JSONB` | Release a unit only for its proven owner. |
| `check_binding` | `p_unique_id TEXT`, `p_fingerprint TEXT`, `p_app_version TEXT DEFAULT NULL` | `JSONB` | Return current binding state; refresh owner activity/version when bound. |
| `admin_release` | `p_unique_id TEXT` | `JSONB` | Office force-release for lost, dead, or reassigned handsets. |

## App-facing status contract

The `JSONB` routines return a `status` string which n8n maps to HTTP and the Android app interprets. Do not rename these values without a coordinated app rollout.

| Status | Typical HTTP | Meaning |
|---|---:|---|
| `ok` | 200 | Action succeeded. |
| `bad_request` | 400 | Missing or blank input. |
| `admin_auth_failed` | 401 | OTP invalid, expired, or used. |
| `not_found` | 404 | Unit absent from the register. |
| `already_bound` | 409 | Unit belongs to a different phone. |
| `fingerprint_in_use` | 409 | Phone already owns another active unit. |
| `not_owner_or_not_found` | 409 | Release attempted by a non-owner. |
| `draining` | 409 | Former owner is within the protected delivery-drain window. |
| `bound` | 200 | The requesting phone still owns the unit. |
| `released` | 200 | Unit was released; app enters safe delivery/drain handling. |
| `bound_other` | 200 | Unit is owned by another phone. |

For incident handling, see the [Operations runbook](../OPERATIONS_RUNBOOK.md). For system boundaries, see [Data flow](../../architecture/DATA_FLOW.md).
