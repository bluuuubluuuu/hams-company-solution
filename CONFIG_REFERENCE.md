# CONFIG REFERENCE — HAMS company solution

One-page cheat sheet of every moving part. Real secrets live in `local.properties` and the n8n
credential (both gitignored) — this file only points at them.

## The pieces
| Piece | What / where |
|---|---|
| **App** | Android APK built from `app/`. Pairs via n8n, pushes cut data straight to Wialon. |
| **n8n** | Docker container `hams-n8n` → `http://localhost:5678` (data in volume `n8n_data`). Holds the 4 workflows. |
| **Postgres** | Neon `neondb` — the `units` table + provisioning functions. |
| **Tunnel** | `cloudflared` exposes n8n over HTTPS so a real phone can reach it. URL is **ephemeral**. |
| **Wialon** | IPS gateway `185.213.1.24:20332` — receives cut data. Not part of this backend. |

## n8n workflows
| Workflow | Type | URL / how to run | Auth |
|---|---|---|---|
| `generate-otp` | Form (login) | n8n form → returns a 6-digit OTP | n8n login |
| `manual-claim` | Webhook POST | `<n8n>/webhook/manual-claim` | `x-hams-key` + OTP |
| `release` | Webhook POST | `<n8n>/webhook/release` | `x-hams-key` + OTP |
| `seed` | Manual/Schedule | run in editor → pulls Wialon units into `units` | Wialon token |

Webhooks only go live after **Publish** (n8n 2.x). `<n8n>` = the tunnel base URL (or `http://127.0.0.1:5678` with `adb reverse`).

## App config — `local.properties` keys
| Key | Value | Secret? |
|---|---|---|
| `IPS_HOST` | `185.213.1.24` | no |
| `IPS_PORT` | `20332` | no |
| `DEVICE_UNIQUE_ID` | `HAMS_TEST_002` (dev fallback only) | no |
| `MANUAL_CLAIM_URL` | `<n8n>/webhook/manual-claim` | no |
| `RELEASE_URL` | `<n8n>/webhook/release` | no |
| `HAMS_CLAIM_SECRET` | the shared `x-hams-key` — **same value on app + n8n IF node** | yes → `local.properties` |
| `WIALON_TOKEN` | 72-char Wialon token (used by `seed` only, not in the APK) | yes → `local.properties` |

Template: `local.properties.example`. No `switch-env`/`*.env` here — edit `local.properties` directly.

## Postgres (Neon)
| Field | Value |
|---|---|
| Host | `ep-wild-truth-aoe9ihuy-pooler.c-2.ap-southeast-1.aws.neon.tech` |
| DB / user | `neondb` / `neondb_owner` |
| Port / SSL | `5432` / **Require** |
| Password | in `local.properties` `DATABASE_URL` (never committed) |
| Tables | `units`, `admin_otp` |
| Functions | `seed_unit`, `issue_otp` / `otp_is_valid` / `consume_otp`, `manual_claim`, `release_unit` |

Set the same connection as the n8n **Postgres** credential (call it "Postgres account").

## Test units (seeded)
`HAMS_TEST_001`, `HAMS_TEST_002`, `HAMS_TEST_003` (names `TEST_HAMS_APP_00x`).

## Common commands
```bash
# start backend after a reboot / sleep
docker start hams-n8n

# expose n8n over HTTPS (new URL each run — update local.properties + rebuild)
cloudflared tunnel --url http://localhost:5678

# mint an OTP without the form
psql "$DATABASE_URL" -c "SELECT issue_otp(10);"

# who owns what
psql "$DATABASE_URL" -c "SELECT unique_id, device_fingerprint, claimed FROM units ORDER BY unique_id;"

# build + install the app
.\gradlew.bat :app:installDebug
```

## Contracts (status → HTTP)
`ok`→200 · `admin_auth_failed`→401 · `fingerprint_in_use`/`already_bound`→409 ·
`not_found`→404 · `not_owner_or_not_found`→409 · `bad_request`→400.

## Where the secrets live (never in git)
- `HAMS_CLAIM_SECRET`, `WIALON_TOKEN`, `DATABASE_URL` → `local.properties`
- n8n Postgres password, `x-hams-key`, Wialon token → inside n8n (credential + node fields)
- The `provisioning/n8n/workflows/*.json` in this repo have these replaced by placeholders
  (`<HAMS_CLAIM_SECRET>`, `<WIALON_TOKEN>`) — re-enter after importing.

## Full walkthrough
Bring-up steps: `LOCAL-RUN.md`. Backend detail: `provisioning/README.md`.
