# Database

**You provision it. You own the password. We never see it.**

## Engine — not final

- Scripts today are **PostgreSQL**.
- HQ has not ruled on Postgres vs SQL Server yet.
- Build on **Postgres** now.
- If HQ later picks SQL Server: **do not port the scripts yourselves — reconfirm with us.** Two things break silently (a `UNIQUE` column that is `NULL` on every free unit; an unlocked read-then-write in `manual_claim`).

## Steps

| # | Do | Note |
|---|---|---|
| 1 | Create a Postgres database | any provider |
| 2 | Enable TLS | `?sslmode=require` |
| 3 | Run the SQL scripts, in numeric order | see below |
| 4 | Restrict network access to the n8n host only | DB must not be public |
| 5 | Create the `Postgres account` credential in n8n | see [CREDENTIALS.md](CREDENTIALS.md) |

## SQL scripts

Source of truth — **do not copy, pull from the repo**:

```
database/sql/
```

### Run this one file

```bash
psql "$PROV_DB_URL" -f database/sql/hams_setup.sql
```

Idempotent. Safe to re-run. Never resets a live pairing.

### What it creates

| Object | Type |
|---|---|
| `"G_PM_IT_IOT_HAMS_UNITS"` | table |
| `"G_PM_IT_IOT_HAMS_ADMIN_OTP"` | table |
| `"G_PM_IT_IOT_HAMS_IDX_UNITS_FREE"` | index |
| `seed_unit`, `issue_otp`, `otp_is_valid`, `consume_otp`, `manual_claim`, `release_unit`, `check_binding`, `admin_release` | functions |

`hams_setup.sql` = the 7 numbered files in `database/sql/history/` concatenated in apply order
(`001, 003, 004, 005, 007, 006, 008`). `002` does not exist — withdrawn, gap deliberate. `007` runs
before `006` because `006` reads columns `007` adds. Those files are kept as migration history; you
don't need to run them individually.

## Naming (SOP) — quoting is mandatory

Tables and indexes are **quoted UPPERCASE** with the `G_PM_IT_IOT_HAMS_` prefix. Postgres folds
unquoted identifiers to lowercase, so the quotes are not optional:

```sql
SELECT * FROM "G_PM_IT_IOT_HAMS_UNITS";   -- ✅
SELECT * FROM G_PM_IT_IOT_HAMS_UNITS;     -- ❌ relation does not exist
```

| Object class | Form | Quoted? |
|---|---|---|
| Tables, indexes | `"G_PM_IT_IOT_HAMS_UNITS"` | **yes** |
| Functions | `manual_claim()` | no |
| Columns | `unique_id` | no |

## Seed the units table

After the SQL is applied the `"G_PM_IT_IOT_HAMS_UNITS"` table is **empty**. Pairing will fail with `not_found` until you seed it.

| Option | How |
|---|---|
| From Wialon (normal) | run the `G_PM_IT_IOT_HAMS_SEED` workflow by hand in the editor |
| One test unit (quick check) | `psql "$PROV_DB_URL" -c "SELECT seed_unit('HAMS_TEST_001','Test');"` |

## Timezone

| Rule | |
|---|---|
| Store | absolute timestamps (`TIMESTAMPTZ`). Never local time. |
| Convert | at read time: `AT TIME ZONE 'Asia/Kuala_Lumpur'` |
| If the pooler forces UTC | **leave it.** `ALTER DATABASE ... SET timezone` and `PGTZ` are ignored on managed poolers. Do not try to fix it server-side. |

Storing local time breaks OTP expiry and the drain lease. Both fail **silently**.
