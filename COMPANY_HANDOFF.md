# HAMS Task Recorder — Company Handoff

Handoff snapshot of the HAMS Task Recorder solution at the **manual-pairing + n8n-admin**
milestone. This is the company one-off: the app plus a self-contained n8n provisioning/admin
backend over Postgres.

## What this is

An Android app that replaces the MeiTrack P99L hardware tracker for oil-palm harvesters.
Workers press **+** per FFB cut; each press records GPS + battery + timestamp locally (SQLite).
On validated Wi-Fi the app batch-pushes events to the Wialon cloud as Wialon IPS v1.1 `#D#`
messages. Each device is bound to a Wialon unit identity by an **office admin** — they key the
unit id into the app and authorise the bind with a short-lived supervisor **OTP** issued by n8n.

## What is included

- **Android app** (`app/`) — offline counting, task lifecycle, Wi-Fi-triggered IPS push, and a
  first-launch **manual pairing** gate (`PairingScreen`) plus an admin sheet for release/re-bind.
- **n8n provisioning + admin backend** (`provisioning/`):
  - SQL schema + functions (`provisioning/sql/`): units, seeding, OTP, `manual_claim`, `release_unit`.
  - Importable n8n workflow definitions (`provisioning/n8n/workflows/`): `generate-otp`,
    `manual-claim`, `release`, `seed` (secrets replaced with placeholders).
  - Runbook (`provisioning/README.md`) and localhost bring-up (`LOCAL-RUN.md`).
- **Documentation** (`CLAUDE.md`, `CONTEXT.md`, `docs/`).

## How pairing works

1. Admin issues an OTP from the n8n `generate-otp` form (single-use, ~10 min).
2. On the phone's PairingScreen the admin enters the **unit id** + the **OTP** and taps Pair.
3. App → `POST /webhook/manual-claim` (`x-hams-key` + OTP) → Postgres `manual_claim` binds the
   unit to the device fingerprint (`Settings.Secure.ANDROID_ID`) and consumes the OTP.
4. Re-bind / release is office-only via the same OTP-gated `release` + `manual-claim` path.

Cut data path is unchanged: phone → Wialon IPS directly. The backend only does identity + admin.

## Build the app

```
.\gradlew.bat :app:assembleDebug      # debug APK -> app/build/outputs/apk/debug/
.\gradlew.bat :app:installDebug       # install on a connected device
.\gradlew.bat :app:testDebugUnitTest  # unit tests
```

Requires `local.properties` — copy `local.properties.example` and fill in real values.

## Provisioning backend

Follow `provisioning/README.md` end to end (apply SQL → import workflows → set the Postgres
credential + `x-hams-key` + Wialon token → publish the webhooks). For a fully local run see
`LOCAL-RUN.md`.

## Configuration (fill with your own values — never commit secrets)

| Key | Used by | What |
|---|---|---|
| `WIALON_TOKEN` | n8n `seed` workflow | 72-char Wialon API token (not built into the APK) |
| `IPS_HOST` / `IPS_PORT` | app | Wialon IPS gateway (`185.213.1.24:20332`) |
| `DEVICE_UNIQUE_ID` | app | dev-fallback unit id before a device is paired |
| `MANUAL_CLAIM_URL` / `RELEASE_URL` | app | `https://<n8n-host>/webhook/manual-claim` and `/release` (HTTPS) |
| `HAMS_CLAIM_SECRET` | app + n8n | shared `x-hams-key` guarding the webhooks (same on both) |
| `PROV_DB_URL` | psql / n8n | Postgres connection string |

Templates (placeholders only): `local.properties.example`, `docs/credentials.env.example`.
`local.properties` and any real credential file are gitignored — keep them that way.

## Reading order (for a new engineer)

1. `docs/HAMS_APP_REQUIREMENTS.md` — what the app does
2. `CONTEXT.md` — Wialon side + IPS protocol
3. `docs/HAMS_EVENT_CODE_DICTIONARY.md` — event vocabulary
4. `CLAUDE.md` — build rules
5. `provisioning/README.md` — backend setup, then `LOCAL-RUN.md`

## Wialon connection (reference)

| Item | Value |
|---|---|
| IPS host : port | `185.213.1.24 : 20332` |
| Protocol | Wialon IPS v1.1 (16-field `#D#` frame) |
| Login frame | `#L#<unique_id>;NA\r\n` → expect `#AL#1` |
