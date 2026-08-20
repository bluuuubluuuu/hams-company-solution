<!-- DRAFT — production-content review required. -->
# Configuration Reference

**Purpose:** Identify configuration keys, their owner, and where real values belong. This document never contains a real secret.

## Configuration files

| File | Use | Git status |
|---|---|---|
| `local.properties.example` | Safe Android build-time template. | Tracked |
| `local.properties` | Real Android build-time values. | Ignored; never share or commit |
| `config/examples/credentials.env.example` | Safe server-side variable-name template. | Tracked |
| Company secret store / n8n credentials | Live backend values. | Company-managed; not in this repository |

## Android build-time keys

| Key | Owner | Notes |
|---|---|---|
| `sdk.dir` | Workstation | Android SDK path only. |
| `IPS_HOST`, `IPS_PORT` | Wialon/platform owner | IPS gateway connection. |
| `DEVICE_UNIQUE_ID` | Development only | Fallback only; office pairing is the production identity. |
| `MANUAL_CLAIM_URL`, `RELEASE_URL`, `VERIFY_URL` | Company n8n owner | Production HTTPS webhooks. |
| `OTP_REQUEST_URL` | Company n8n owner | Optional office code-request endpoint. |
| `HAMS_CLAIM_SECRET` | Company secret owner | Shared app-to-n8n request secret; compiled into the app, so do not treat it as server-only. |
| `RELEASE_STORE_*` | Release-signing owner | Signing-keystore location and credentials. |

`WIALON_TOKEN` is retained in the template for authorised seed/integration administration. Do not put a real value in documentation, source code, issues, pull requests, or chats.

## App defaults confirmed from source

| Setting | Current value |
|---|---|
| Android minimum / target SDK | 33 / 35 |
| App version | 1.2 (3) production; 1.3 (5) trial |
| IPS default | `185.213.1.24:20332` |
| Task batch limit | 10 |
| Retry attempts | 5 |
| Local terminal-data retention | 30 days |
| Binding revalidation interval | 15 minutes |

## Change rules

- Change real values in the company-approved secret/configuration location, then rebuild and deploy the Android app when a build-time value changes.
- Do not rotate a signing key casually: it changes Android identity behaviour and requires a planned re-pairing process.
- Document the owner, reason, rollout window, and rollback method for changes to Wialon, n8n, PostgreSQL, or app identity settings.

For field behaviour, use [Functional requirements](requirements/FR_APP_REQUIREMENTS.md) and [Non-functional requirements](requirements/NFR_APP_REQUIREMENTS.md). For troubleshooting, use the [Operations runbook](../operations/OPERATIONS_RUNBOOK.md).
