# HAMS — Database Review

**For:** HQ / IT review.
**Contains:** the SQL scripts as they run today, and the naming convention they follow.
**Asks for:** a ruling on the naming standard, and confirmation of the target database engine.

The schema currently runs on **PostgreSQL**. That is a prototype choice, **not a decision** — the
scripts are presented here for review precisely so HQ can rule on the engine and the naming standard
before the schema is frozen.

---

## What this database is

HAMS is an Android app that replaces a hardware GPS tracker for oil-palm harvesters. Workers press
**+** for each Fresh Fruit Bunch cut; the phone stores the cut offline and, on Wi-Fi, sends it
straight to the **Wialon** cloud platform.

**This database holds none of that harvest data.** Its only job is *identity*: remembering which
physical phone is allowed to report as which Wialon unit. It is an equipment register.

```
[Phone] --- pairing request (unit id + one-time password) ---> [n8n] ---> [THIS DATABASE]
[Phone] --- harvest cut data (GPS, count, battery) ---------------------> [Wialon cloud]
                                                                          (separate system)
```

Two tables, 14 columns, 8 routines, roughly one row per company handset. It does not grow with
harvest activity — a phone that records 10,000 cuts adds zero rows.

**Not stored anywhere here:** cut counts, GPS coordinates, worker names, employee numbers, wages,
estate or block identity. The only sensitive column is `"G_PM_IT_IOT_HAMS_UNITS".device_fingerprint`, which holds the
phone's Android hardware id — it identifies a *handset*, not a person.

## Contents

| Path | What |
|---|---|
| [`SCHEMA.md`](SCHEMA.md) | Every table and column with its **data type**, nullability, default, and purpose; the 8 routines; the status contract; and the PostgreSQL → SQL Server type mapping. **Read this first.** |
| [`NAMING_CONVENTION.md`](NAMING_CONVENTION.md) | The 11 proposed rules, the schema's own known deviations from them, and how they compare to a typical SQL Server house style. **The main item for review.** |
| [`sql/hams_setup.sql`](sql/hams_setup.sql) | ▶ **The only script you run.** Creates both tables, the index, and all 8 functions. Idempotent. |
| [`sql/history/`](sql/history) | ⛔ **Superseded — do not run.** The 7 original numbered migrations, kept for provenance only. |

### Run this

```bash
psql "$PROV_DB_URL" -f sql/hams_setup.sql
```

That is the whole database setup. Safe to re-run — it never resets a live device pairing.

### About `sql/history/`

`hams_setup.sql` is those 7 files concatenated in their true apply order (`001, 003, 004, 005, 007,
006, 008`) with the SOP naming applied. They are retained because each carries a header comment
explaining *why* it exists — the design rationale, worth reading before the SQL itself. Two quirks
they document: `002` never existed (withdrawn during development), and `007` runs before `006`
because `006` reads columns that `007` adds.

**Do not run them individually.** They are history, not a procedure.

## Before reviewing

Two constraints shape everything here, both set out in [`SCHEMA.md`](SCHEMA.md):

- **The status contract is a published interface.** The nine strings each routine returns travel over
  HTTP to the Android app, which branches on them. Renaming one means rebuilding and re-installing
  the app on every handset in the field — so they sit outside any naming standard.
- **Two things break silently if the engine changes.** A `UNIQUE` column that is `NULL` for every
  free unit, and an unlocked read-then-write in `manual_claim()`. Both pass a single-user test.

## Provenance

Derived from [`../provisioning/sql/`](../provisioning/sql) — same logic, **renamed to the company
SOP**: tables and indexes are quoted UPPERCASE with the `G_PM_IT_IOT_HAMS_` prefix. Functions and
columns are unchanged.

| Folder | Names | Used by |
|---|---|---|
| `../provisioning/sql/` | `units`, `admin_otp` | our running dev instance |
| `database/sql/` **(this folder)** | `"G_PM_IT_IOT_HAMS_UNITS"`, `"G_PM_IT_IOT_HAMS_ADMIN_OTP"` | the SOP deployment |

Run [`sql/hams_setup.sql`](sql/hams_setup.sql) once on a fresh database — it is the 7 numbered
scripts concatenated in apply order (`001, 003, 004, 005, 007, 006, 008`), idempotent, safe to
re-run. The numbered files in [`sql/history/`](sql/history) are kept for provenance only — do not run them.

**No credentials, connection strings, tokens, or one-time passwords appear anywhere in this folder.**
