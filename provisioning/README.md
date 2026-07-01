# HAMS Provisioning Backend — Runbook (n8n + Postgres)

Backend for **office manual pairing**: an admin keys a Wialon unit id into the app and
authorises the bind with a short-lived supervisor **OTP**. The phone talks only to n8n for
provisioning — cut data still goes phone → Wialon IPS directly. n8n is also the admin console
(issue OTPs, release/re-bind, seed units from Wialon).

## Workflows (4)
| Workflow | Trigger | Calls (Postgres fn) | Auth |
|---|---|---|---|
| `generate-otp` | n8n Form (login-protected) | `issue_otp(ttl)` | n8n login |
| `manual-claim` | `POST /webhook/manual-claim` | `manual_claim(uid, fp, otp)` | `x-hams-key` + OTP |
| `release` | `POST /webhook/release` | `release_unit(uid, fp, otp)` | `x-hams-key` + OTP |
| `seed` | Manual/Schedule | Wialon `search_items`(257) → `seed_unit` | Wialon token |

Guard/OTP logic lives in Postgres plpgsql (atomic, testable); the n8n workflows are thin
wrappers (`Webhook → check x-hams-key → SELECT <fn> → map status → Respond`). Importable
definitions are in `provisioning/n8n/workflows/*.json` (secrets replaced with placeholders).

## Environment values
| Name | Where | What |
|---|---|---|
| `PROV_DB_URL` | psql / n8n Postgres credential | `postgresql://user:pass@host:5432/neondb` (Neon; SSL=Require) |
| `WIALON_TOKEN` | `seed` workflow HTTP node | 72-char Wialon token (also in `local.properties`) |
| `HAMS_CLAIM_SECRET` | n8n webhook IF node + app `local.properties` | shared `x-hams-key` secret (same both sides) |
| `MANUAL_CLAIM_URL` / `RELEASE_URL` | app `local.properties` | `https://<n8n-host>/webhook/manual-claim` and `/release` (HTTPS — see cleartext note) |

## Step 1 — Apply the schema
```bash
psql "$PROV_DB_URL" -f provisioning/sql/001_units.sql
psql "$PROV_DB_URL" -f provisioning/sql/003_seed_unit.sql
psql "$PROV_DB_URL" -f provisioning/sql/004_admin_otp.sql
psql "$PROV_DB_URL" -f provisioning/sql/005_manual_provision.sql
```
Functions installed: `seed_unit`, `issue_otp` / `otp_is_valid` / `consume_otp`,
`manual_claim`, `release_unit`.

## Step 2 — Import the workflows into n8n
1. n8n → Import from File → `provisioning/n8n/workflows/*.json` (one at a time).
2. Create a **Postgres** credential pointing at `PROV_DB_URL` (Neon: SSL = **Require**), and
   select it on each Postgres node.
3. In `manual-claim` + `release`, set the IF node's expected key to your `HAMS_CLAIM_SECRET`
   (the placeholder is `<HAMS_CLAIM_SECRET>`).
4. In `seed`, set the Wialon token in the login HTTP node (placeholder `<WIALON_TOKEN>`).
5. **Publish** `manual-claim` and `release` (n8n 2.x publish model) → their `/webhook/...`
   production URLs go live. `generate-otp` is a Form; `seed` is Manual/Schedule.

## Status → HTTP contract (must match the app parser)
| status | HTTP | body |
|---|---|---|
| `ok` (manual-claim) | 200 | `{"unique_id":…}` |
| `ok` (release) | 200 | `{"ok":true}` |
| `admin_auth_failed` | 401 | `{"error":"admin_auth_failed"}` |
| `fingerprint_in_use` | 409 | `{"error":"fingerprint_in_use","on":…}` |
| `already_bound` | 409 | `{"error":"already_bound"}` |
| `not_found` | 404 | `{"error":"not_found"}` |
| `not_owner_or_not_found` (release) | 409 | `{"error":"not_owner_or_not_found"}` |
| `bad_request` | 400 | `{"error":"bad_request"}` |

The Code node in each webhook maps the SQL `status` to the row above. OTP is validated first
and **consumed only on a successful** bind/release (a failed guard never burns a code).

## Pairing contract (app ↔ n8n)
```
POST {MANUAL_CLAIM_URL}   headers x-hams-key:<HAMS_CLAIM_SECRET>, x-hams-admin-passkey:<OTP>
  body {"unique_id":"HAMS_TEST_001","fingerprint":"<ANDROID_ID>"}
  -> 200 {"unique_id":…} | 401 admin_auth_failed | 404 not_found | 409 fingerprint_in_use/already_bound

POST {RELEASE_URL}        same headers, body {"unique_id":…,"fingerprint":…}
  -> 200 {"ok":true} | 401 admin_auth_failed | 409 not_owner_or_not_found
```

## Seed workflow (Wialon → Postgres)
```
Manual/Schedule Trigger
  → HTTP GET login   svc=token/login, params={"token":"<WIALON_TOKEN>"}                 // -> eid
  → HTTP GET search  svc=core/search_items, flags=257, sid={{ $json.eid }}              // -> items[]
  → Split Out (items)
  → Filter  {{ /^(HAMS_|TEST_HAMS_APP_)/.test($json.nm) && String($json.uid||'').trim() !== '' }}
  → Postgres  SELECT seed_unit($1,$2)   params {{ $json.uid }}, {{ $json.nm }}
```
Idempotent — `seed_unit` UPSERTs name only; never touches `claimed`/`device_fingerprint`.
**flags=257** (`0x1`|`0x100`) is the lean flag exposing `items[].uid` + `items[].nm`.
**Swap point:** when the vendor delivers a real "HAMS-ready" Wialon group, change only the
Filter (group-id check instead of the name regex).

## Admin queries
```sql
SELECT unique_id, claimed, device_fingerprint, last_seen FROM units ORDER BY unique_id;  -- who has what
SELECT count(*) FROM units WHERE claimed=false AND status='active';                       -- free count
SELECT issue_otp(10);                                                                     -- mint an OTP (10 min)
-- office re-bind (SOP): release then re-pair on the new phone
UPDATE units SET claimed=false, device_fingerprint=NULL WHERE unique_id='HAMS_TEST_001';
```

## Cleartext note
`targetSdk=35` blocks plain `http://`. The n8n webhooks must be **HTTPS** for a real device
(front n8n with a tunnel/reverse proxy), or rely on the loopback exception in
`app/src/main/res/xml/network_security_config.xml` for a USB-tethered `adb reverse` test.

## Launch locally
See `LOCAL-RUN.md` for the full localhost bring-up (Docker n8n + Postgres + tunnel + build).
```
