# HAMS Task Recorder — Project Overview

The big-picture overview of the HAMS Task Recorder solution at the **manual-pairing + n8n-admin**
milestone: the app plus a self-contained n8n provisioning/admin backend over Postgres.

> For setup steps start at the hub — [README.md](README.md) — then [SETUP.md](SETUP.md). This page
> is the *what*, not the *how*.

## What this is

An Android app that replaces the MeiTrack P99L hardware tracker for oil-palm harvesters. Workers
press **+** per FFB cut; each press records GPS + battery + timestamp locally (SQLite). On validated
Wi-Fi the app batch-pushes events to the Wialon cloud as Wialon IPS v1.1 `#D#` messages. Each device
is bound to a Wialon unit identity by an **office admin** — they key the unit id into the app and
authorise the bind with a short-lived supervisor **OTP** issued by n8n.

## What is included

- **Android app** (`app/`) — offline counting, task lifecycle, Wi-Fi-triggered IPS push, and a
  first-launch **manual pairing** gate (`PairingScreen`) plus an admin sheet for release/re-bind.
- **n8n provisioning + admin backend** (`provisioning/`):
  - SQL schema + functions (`provisioning/sql/`): units, seeding, OTP, `manual_claim`, `release_unit`.
  - Importable n8n workflow definitions (`provisioning/n8n/workflows/`): `generate-otp`,
    `manual-claim`, `release`, `seed` (secrets replaced with placeholders).
- **Documentation** — see the [README hub](README.md) for the full index.

## How pairing works

1. Admin issues an OTP from the n8n `generate-otp` form (single-use, ~10 min).
2. On the phone's PairingScreen the admin enters the **unit id** + the **OTP** and taps Pair.
3. App → `POST /webhook/manual-claim` (`x-hams-key` + OTP) → Postgres `manual_claim` binds the unit
   to the device fingerprint (`Settings.Secure.ANDROID_ID`) and consumes the OTP.
4. Re-bind / release is office-only via the same OTP-gated `release` + `manual-claim` path.

Cut data path is unchanged: phone → Wialon IPS directly. The backend only does identity + admin.

## Scope

- **In scope:** offline `+`/`−` counting with mandatory GPS, task lifecycle, Wi-Fi-triggered batch
  IPS push, manual pairing + office admin, heartbeat, local battery/GPS telemetry.
- **Out of scope:** geofence/reporting logic (Wialon does it), REST reads, the downstream
  N8N→PostgreSQL analytics pipeline, in-field re-bind (office-only).

## Where to go next

- **Set it up:** [README.md](README.md) → [SETUP.md](SETUP.md)
- **Build the backend:** [provisioning/BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md)
- **Values & contracts:** [CONFIG_REFERENCE.md](CONFIG_REFERENCE.md)
- **Wialon + IPS protocol:** [CONTEXT.md](CONTEXT.md)
- **Requirements / event codes:** [docs/HAMS_APP_REQUIREMENTS.md](docs/HAMS_APP_REQUIREMENTS.md),
  [docs/HAMS_EVENT_CODE_DICTIONARY.md](docs/HAMS_EVENT_CODE_DICTIONARY.md)
