# provisioning/ — HAMS admin backend (n8n + Postgres)

Backend for **office manual pairing**: an admin keys a Wialon unit id into the app and authorises
the bind with a short-lived supervisor **OTP**. The phone talks to n8n only for provisioning — cut
data still goes phone → Wialon IPS directly. n8n is also the admin console (issue OTPs,
release/re-bind, seed units from Wialon).

## How to build it
➡️ **[BUILD_ADMIN_BACKEND.md](BUILD_ADMIN_BACKEND.md)** — the full procedure (fast import path and
from-scratch node-by-node path, prerequisites, gotchas, curl test matrix).
For the one-PC running order see [../LOCAL-RUN.md](../LOCAL-RUN.md); for values, ports, and the
webhook contract see [../CONFIG_REFERENCE.md](../CONFIG_REFERENCE.md).

## What's in this folder
| Path | What |
|---|---|
| `sql/001_units.sql` | `units` table |
| `sql/003_seed_unit.sql` | `seed_unit(uid, name)` UPSERT |
| `sql/004_admin_otp.sql` | `admin_otp` table + `issue_otp` / `otp_is_valid` / `consume_otp` |
| `sql/005_manual_provision.sql` | `manual_claim` + `release_unit` |
| `n8n/workflows/generate-otp.json` | Form (login-protected) → `issue_otp(ttl)` |
| `n8n/workflows/manual-claim.json` | `POST /webhook/manual-claim` → `manual_claim(uid, fp, otp)` |
| `n8n/workflows/release.json` | `POST /webhook/release` → `release_unit(uid, fp, otp)` |
| `n8n/workflows/seed.json` | Wialon `search_items` (read-only) → `seed_unit` |

The guard/OTP logic lives in Postgres plpgsql (atomic, testable); the n8n workflows are thin
wrappers. The workflow JSONs ship with secrets replaced by placeholders (`<HAMS_CLAIM_SECRET>`,
`<WIALON_TOKEN>`) — re-enter the real values after importing; never commit them.

## Seeding note
The `seed` workflow is **read-only against Wialon** — it pulls each unit's `unique_id` + name into
Postgres and UPSERTs (never touches `claimed`/`device_fingerprint`, so re-runs are safe). It does
**not** configure Wialon units; per-unit Wialon setup is the
[../docs/HAMS_UNIT_PROVISIONING_CHECKLIST.md](../docs/HAMS_UNIT_PROVISIONING_CHECKLIST.md).
