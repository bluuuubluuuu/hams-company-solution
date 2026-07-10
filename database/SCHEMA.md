# HAMS Database — Schema & Data Types

Every table and column currently in the provisioning database, with its type. Types shown are the
PostgreSQL types in use today; the SQL Server equivalents are at the bottom.

**Total footprint: 2 tables, 14 columns, 8 routines, 1 index.** The register does not grow with
harvest activity — one row per company handset.

> **Naming (SOP).** Tables and indexes are **quoted UPPERCASE** with the `G_PM_IT_IOT_HAMS_` prefix.
> Every reference must keep the quotes — `FROM "G_PM_IT_IOT_HAMS_UNITS"` works, `FROM
> G_PM_IT_IOT_HAMS_UNITS` folds to lowercase and errors. Functions and columns are unquoted
> lowercase. See [`NAMING_CONVENTION.md`](NAMING_CONVENTION.md).

---

## Table `"G_PM_IT_IOT_HAMS_UNITS"` — the equipment register

One row per Wialon unit. Rows are created by the seeding workflow reading the company's Wialon
account, never by a phone.

| Column | Type | Null? | Default | Constraint | Purpose |
|---|---|---|---|---|---|
| `unique_id` | `TEXT` | no | — | **PRIMARY KEY** | The Wialon unit's Unique ID, e.g. `OC154_H001`. The phone sends this in its login frame; it is what routes harvest data to the right unit on Wialon, and the join key between this database and Wialon. |
| `name` | `TEXT` | yes | — | — | Human-readable label copied from Wialon, for the admin list. Carries no logic. |
| `claimed` | `BOOLEAN` | no | `false` | — | Whether a phone currently holds this unit. `false` = free for pairing. |
| `device_fingerprint` | `TEXT` | yes | — | **UNIQUE** | The Android hardware id of the phone that claimed this unit. Proof of ownership: only the phone presenting this exact value may push as this unit or release it. `NULL` when free. The UNIQUE constraint stops one phone claiming two units. **The one sensitive column** — it identifies a handset, not a person. |
| `status` | `TEXT` | no | `'active'` | *(none — see note)* | Lifecycle of the *unit*: `'active'` in service, `'retired'` withdrawn. No routine sets `'retired'`; it is administrative. |
| `last_seen` | `TIMESTAMPTZ` | yes | — | — | Last time this phone contacted the server — set on pairing, refreshed on each binding re-check (~every 15 min while online). Lets an admin see whether a handset is alive. |
| `created_at` | `TIMESTAMPTZ` | no | `now()` | — | When the unit was first seeded. Audit only. |
| `updated_at` | `TIMESTAMPTZ` | no | `now()` | — | Last modification, by any routine. Audit only. Set explicitly by each routine — **not** by a trigger. |
| `drain_until` | `TIMESTAMPTZ` | yes | — | — | Expiry of the 5-minute "drain lease". `NULL` when no lease is held. See below. |
| `drain_fingerprint` | `TEXT` | yes | — | — | Which phone holds the drain lease. Only that phone is exempt from it. |

**Index.** `"G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"` on `(unique_id)`, filtered to `claimed = false AND
status = 'active'`. A partial index covering only the free, in-service units. An optimisation, not a
constraint.

> **Currently unused.** No routine queries `WHERE claimed = false AND status = 'active'` — pairing is
> office-manual, so `manual_claim()` receives the exact unit id and looks it up by primary key.
> Nothing asks the database to pick a free unit. Retained because it is near-free to maintain at this
> row count and is what a future auto-assign flow or a "how many units are free?" dashboard would
> ride on. **Pairing does not depend on it.**

**Note on `status`.** There is **no `CHECK` constraint**. Nothing in the database prevents
`status = 'banana'`; the two-value domain is enforced by convention only. Recommend adding
`CHECK (status IN ('active','retired'))` before the schema is frozen.

### Why `drain_until` / `drain_fingerprint` exist

When an admin force-releases a unit from a phone still in the field, that phone may hold unsent
harvest cuts. It needs a few minutes to flush them to Wialon *before* it logs out. During that
window no other phone may claim the unit — otherwise the old phone's backlog lands on the new owner
and two workers' output merges.

These two columns are that window. Stamped automatically when the departing phone discovers it was
released, expiring on its own after 5 minutes, cleared by the next successful claim or release. No
background job required.

---

## Table `"G_PM_IT_IOT_HAMS_ADMIN_OTP"` — one-time passwords

Short-lived codes an administrator generates to authorise a pairing or unpairing. Validated first
and **consumed only if the action succeeds**, so a rejected attempt never burns a code.

Self-purging: `issue_otp()` deletes expired rows on every call. Expected size is under 10 rows.

| Column | Type | Null? | Default | Constraint | Purpose |
|---|---|---|---|---|---|
| `code` | `TEXT` | no | — | **PRIMARY KEY** | The 6-digit code, **stored in plaintext**. A zero-padded random integer `000000`–`999999`. |
| `created_at` | `TIMESTAMPTZ` | no | `now()` | — | Issue time. Audit only. |
| `expires_at` | `TIMESTAMPTZ` | no | — | — | Hard expiry, default **10 minutes** after issue. Past this, the code is invalid regardless of `used_at`. |
| `used_at` | `TIMESTAMPTZ` | yes | — | — | When consumed. Non-`NULL` = spent. Single-use is enforced by checking `used_at IS NULL`. |

This table does **not** record which administrator issued a code, from where, or which unit it was
spent on. There is no attribution.

---

## Type mapping if the engine changes

| Logical meaning | PostgreSQL (today) | SQL Server | Note |
|---|---|---|---|
| identifier / short text | `TEXT` | `NVARCHAR(n)` | SQL Server has no unbounded text worth using — **lengths must be chosen**. Suggested: `unique_id NVARCHAR(32)`, `device_fingerprint NVARCHAR(64)`, `name NVARCHAR(128)`, `status NVARCHAR(16)`, `code NVARCHAR(6)`. Observed values: unit ids ≤ 32 chars, Android ids 16 hex chars. |
| boolean | `BOOLEAN` | `BIT` | `BIT` is not a true boolean. Declare `NOT NULL DEFAULT 0`. |
| absolute point in time | `TIMESTAMPTZ` | `DATETIMEOFFSET(3)` | **Must carry an offset.** A naive `DATETIME` silently loses the timezone and breaks the OTP expiry (`expires_at > now()`) and drain-lease (`drain_until > now()`) comparisons. |
| current instant | `now()` | `SYSDATETIMEOFFSET()` | **Not `GETDATE()`** — that returns naive local time. |

### Two type-adjacent traps

1. **`UNIQUE` and `NULL`.** `device_fingerprint` is `UNIQUE` and `NULL` for every free unit.
   PostgreSQL allows unlimited `NULL`s in a unique column; **SQL Server allows exactly one** — the
   second free unit fails to insert. Requires a filtered unique index:
   `CREATE UNIQUE INDEX "G_PM_IT_IOT_HAMS_UX_UNITS_FINGERPRINT" ON "G_PM_IT_IOT_HAMS_UNITS" (device_fingerprint) WHERE device_fingerprint IS NOT NULL;`
2. **Timezone.** The company operates in Malaysia (UTC+8, no DST). Timestamps are stored as absolute
   instants and converted at read time. On SQL Server the conversion is
   `AT TIME ZONE 'Singapore Standard Time'` — the Windows name for UTC+8. There is no
   `Asia/Kuala_Lumpur` in the Windows timezone database. **Do not "fix" this by storing local time.**

---

## Routines

All business rules live in the database. The workflow layer is a thin wrapper that calls one routine
and translates its returned status into an HTTP code — so the guards are atomic and testable.

| Routine | Returns | Purpose |
|---|---|---|
| `seed_unit(unique_id, name)` | `void` | Insert-or-update a unit from Wialon. **Never touches `claimed`, `device_fingerprint`, or `status`** — re-seeding can never break a live pairing. |
| `issue_otp(ttl_minutes = 10)` | the code | Purge expired codes, mint a new 6-digit one. |
| `otp_is_valid(otp)` | `boolean` | Is this code usable now? Does not consume it. |
| `consume_otp(otp)` | `boolean` | Atomically mark a code used. `true` only if still valid at that instant. |
| `manual_claim(unique_id, fingerprint, otp)` | status | Bind a phone to a unit. Three guards: **(A)** this phone already owns a different active unit → `fingerprint_in_use`; **(B)** the unit is owned by another phone → `already_bound`; **(C)** the unit is mid-drain by another phone → `draining`. Re-binding the same phone is idempotent. Consumes the OTP only on success. |
| `release_unit(unique_id, fingerprint, otp)` | status | Unpair — **only** the owning phone, proven by fingerprint. Consumes the OTP only on success. |
| `check_binding(unique_id, fingerprint)` | status | The phone's periodic "am I still bound?". Returns `bound` (refreshing `last_seen`), `released` (stamping a drain lease), `bound_other`, or `not_found`. No OTP — device-initiated with no admin present. |
| `admin_release(unique_id)` | status | Office force-free, without the phone and without an OTP. For lost, dead, or reassigned handsets. Clears the drain lease. |

### The status contract — a published interface

Every guarded routine returns one of these. The workflow layer maps it to HTTP, and **the Android
app branches on the string**. Renaming one requires rebuilding and re-installing the app on every
handset in the field. Exclude these from any naming standard.

| Status | HTTP | Meaning |
|---|---|---|
| `ok` | 200 | Action succeeded |
| `bad_request` | 400 | Blank unit id or fingerprint |
| `admin_auth_failed` | 401 | OTP missing, wrong, expired, or already used |
| `not_found` | 404 | No such unit id in the register |
| `already_bound` | 409 | Unit belongs to a different phone |
| `fingerprint_in_use` | 409 | This phone already owns another unit |
| `not_owner_or_not_found` | 409 | Release attempted by a phone that does not own the unit |
| `draining` | 409 | Unit is mid-flush by its former owner; retry shortly |
| `bound` / `released` / `bound_other` | 200 | Binding re-check answers (verify only) |
