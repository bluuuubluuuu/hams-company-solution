# HAMS Database — Naming Convention

The convention the HAMS schema follows today, written up as a **proposal for HQ to ratify or
override**. Read alongside [`sql/hams_setup.sql`](sql/hams_setup.sql). Where the existing schema
breaks its own rule, that is disclosed rather than hidden — if HQ ratifies, those exceptions should
be fixed before the schema is frozen.

The last column is for HQ. Leave it blank to accept, or write the corporate rule to override.

---

## Quoting rule — read first

Company SOP: tables and indexes are **UPPERCASE with the `G_PM_IT_IOT_HAMS_` prefix**.

PostgreSQL folds unquoted identifiers to lowercase. To store them uppercase they must be **quoted at
creation**, and therefore **quoted in every reference, forever**:

```sql
SELECT * FROM "G_PM_IT_IOT_HAMS_UNITS";   -- ✅ correct
SELECT * FROM G_PM_IT_IOT_HAMS_UNITS;     -- ❌ ERROR: relation does not exist
```

| Object class | SOP form | Quoted? |
|---|---|---|
| Tables | `"G_PM_IT_IOT_HAMS_UNITS"` | **yes** |
| Indexes | `"G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"` | **yes** |
| Functions | `manual_claim()` | no — lowercase, unprefixed |
| Columns | `unique_id` | no — lowercase, unprefixed |

Functions and columns stay unquoted lowercase: all 7 n8n workflows call the functions by name, and
prefixing them would force an edit to every workflow JSON.

---

## Proposed rules

| # | Rule | As used today | Rationale | HQ ruling |
|---|---|---|---|---|
| N-01 | **Tables:** `"<PREFIX>_<NAME>"` — quoted UPPERCASE, prefixed | `"G_PM_IT_IOT_HAMS_UNITS"`, `"G_PM_IT_IOT_HAMS_ADMIN_OTP"` | **Company SOP.** Prefix namespaces the objects on a shared instance. See *Quoting rule* below. | |
| N-02 | **Columns:** singular, `snake_case`, no table-name prefix | `unique_id`, not `unit_unique_id` | The table already qualifies the column. | |
| N-03 | **Primary key:** the natural business key where one exists; no surrogate `id` | `"G_PM_IT_IOT_HAMS_UNITS".unique_id`, `"G_PM_IT_IOT_HAMS_ADMIN_OTP".code` | The Wialon unit id *is* the identity; a surrogate would add a join with no benefit. | |
| N-04 | **Booleans:** an adjective or past participle, no `is_` / `has_` prefix | `claimed` | Reads as a fact about the row: `WHERE claimed = false`. | |
| N-05 | **Timestamps:** suffix `_at`, always absolute (with timezone offset), never a naive local time | `created_at`, `updated_at`, `expires_at`, `used_at` | Naive timestamps silently break comparisons across timezones. | |
| N-06 | **Enumerated text:** lowercase `snake_case` string values, not integer codes | `status = 'active' \| 'retired'` | Readable in a query result without a lookup table. | |
| N-07 | **Routines:** `verb_noun`, lowercase | `seed_unit`, `issue_otp`, `manual_claim`, `release_unit`, `check_binding`, `admin_release` | Names the action. No `sp_` / `usp_` prefix — that is a SQL Server house style, and `sp_` in particular is reserved by SQL Server itself. | |
| N-08 | **Routine parameters:** prefix `p_`; local variables: prefix `v_` | `p_unique_id`, `v_owner` | Prevents a parameter shadowing a column of the same name — a real and silent bug class. | |
| N-09 | **Indexes:** `"<PREFIX>_IDX_<TABLE>_<PURPOSE>"` — quoted UPPERCASE, prefixed | `"G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"` | Same namespace as tables in Postgres, so same rule. Purpose, not column list. | |
| N-10 | **Migration files:** `NNN_subject.sql`, three-digit, monotonic, applied in order, **never edited after they have been applied anywhere** | `001_units.sql` … `008_admin_release.sql` | An applied migration is history. Corrections go in a new file. | |
| N-11 | **Unit identifiers:** `<site code>_H<3-digit sequence>` | `OC154_H001`; test units `HAMS_TEST_001` | *(inferred from examples — this is the rule most in need of an HQ ruling, since it encodes estate coding.)* | |

---

## Known deviations in the current schema

Disclosed in full. Each is a small fix if HQ ratifies the rules above; each is a reason to *not*
ratify silently.

| Deviation | Rule broken | Comment |
|---|---|---|
| `last_seen` has no `_at` suffix | N-05 | Should be `last_seen_at`. Cosmetic; a rename touches `check_binding`, `manual_claim`, and the admin unit list. |
| `drain_until` has no `_at` suffix | N-05 | Arguably fine — `_until` reads better than `until_at`. Suggest **amending N-05** to allow `_at` or `_until`. |
| `otp_is_valid` is `noun_predicate`, not `verb_noun` | N-07 | Should be `is_otp_valid` or `otp_valid`. Purely internal — not called by any workflow. |
| Migration sequence skips `002` | N-10 | `002` was withdrawn during development and never applied. The gap is deliberate; renumbering would violate N-10. Recommend documenting the gap rather than closing it. |
| `status` has **no `CHECK` constraint** | N-06 | Nothing in the database prevents `status = 'banana'`. The two-value domain is enforced only by convention. Recommend adding `CHECK (status IN ('active','retired'))`. |
| `updated_at` is maintained by hand in every routine, not by a trigger | N-05 | Works, but a future routine that forgets to set it will silently produce stale audit data. |

---

## If HQ imposes a SQL Server house style

The common corporate SQL Server conventions differ from the above in four places. None is a
technical objection; all are a rename. **They are cheap to apply now and expensive later**, because
the routine names are referenced from the workflow definitions.

| Corporate style often seen | Conflicts with | Note if adopted |
|---|---|---|
| `PascalCase` tables and columns (`Units.UniqueId`) | N-01, N-02 | Fine. Requires touching every script and every workflow query. |
| `usp_` procedure prefix | N-07 | Acceptable. **Never use `sp_`** — SQL Server resolves `sp_`-prefixed names against the system database first, which is both slower and a documented footgun. |
| Surrogate integer `Id` primary keys everywhere | N-03 | Adds a column and a join for no gain here; `unique_id` is externally meaningful and is the key Wialon routes on. Recommend an exception. |
| `bit` columns named `IsClaimed` | N-04 | Fine. |

---

## What must not be renamed, under any convention

The **status contract** — the nine string values listed in [`SCHEMA.md`](SCHEMA.md#the-status-contract--a-published-interface).

These are not internal names. They travel over HTTP to the Android app, which branches on them.
Renaming one requires rebuilding and re-installing the app on every handset in the field. They
should be treated as a published interface and excluded from any naming standard.

Likewise `"G_PM_IT_IOT_HAMS_UNITS".unique_id`'s **values** are dictated by the Wialon platform — the register's job is
to mirror them. The *column* may be renamed; the *contents* may not.
