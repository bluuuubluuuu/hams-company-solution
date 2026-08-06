# CONFIG REFERENCE — HAMS company solution

One-page cheat sheet of every moving part and value. Real secrets live in `local.properties` and
the n8n credential (both gitignored) — this file only points at them. Navigation: [README.md](README.md).
Setup steps: [SETUP.md](SETUP.md). Backend build: [provisioning/BUILD_ADMIN_BACKEND.md](provisioning/BUILD_ADMIN_BACKEND.md).

## The pieces
| Piece | What / where |
|---|---|
| **App** | Android APK built from `app/`. Pairs via n8n, pushes cut data straight to Wialon. |
| **n8n** | Docker container `hams-n8n` → `http://localhost:5678` (data in volume `n8n_data`). Holds the 7 workflows. |
| **Postgres** | `"G_PM_IT_IOT_HAMS_UNITS"` + `"G_PM_IT_IOT_HAMS_ADMIN_OTP"` tables + provisioning functions. **Your own** Neon project or local container. |
| **Tunnel** | `cloudflared` exposes n8n over HTTPS so a real phone can reach it. URL is **ephemeral**. |
| **Wialon** | IPS gateway `185.213.1.24:20332` — receives cut data. External; not part of this backend. |

## App config — `local.properties` keys
Copy `local.properties.example` → `local.properties` and fill in. **Never edit source to set these.**
| Key | Value | Kind |
|---|---|---|
| `sdk.dir` | your Android SDK path | 🔴 yours |
| `WIALON_TOKEN` | your 72-char Wialon token (used by `seed`; not built into the APK) | 🔴 yours (secret) |
| `HAMS_CLAIM_SECRET` | shared `x-hams-key` — **same value on app + n8n IF node** | 🔴 yours (secret) |
| `DEVICE_UNIQUE_ID` | dev-fallback unit id (ignored once paired), e.g. `HAMS_TEST_001` | 🔴 yours |
| `MANUAL_CLAIM_URL` / `RELEASE_URL` | `<your n8n base>/webhook/manual-claim` and `/release` | 🔴 yours |
| `VERIFY_URL` | `<your n8n base>/webhook/verify` — device binding re-check (revalidation). Same n8n base as the two above. | 🔴 yours |
| `IPS_HOST` / `IPS_PORT` | `185.213.1.24` / `20332` | 🟢 preset |
| `RELEASE_STORE_FILE` | path to the release keystore, **relative to the repo root** (e.g. `keys/hams-release.jks`) | 🔴 yours (secret) |
| `RELEASE_STORE_PASSWORD` | keystore password | 🔴 yours (secret) |
| `RELEASE_KEY_ALIAS` | key name inside the keystore (e.g. `hams`) | 🔴 yours |
| `RELEASE_KEY_PASSWORD` | key password — **must equal the store password**; PKCS12 keystores (keytool's default since JDK 9) have no separate key password | 🔴 yours (secret) |

> **The signing key is permanent.** Android derives the per-app `ANDROID_ID` — which the
> provisioning registry stores as `device_fingerprint` — from the signing key. Signing a later
> build with a *different* key changes every handset's fingerprint, forcing uninstall +
> `admin_release` + re-pair on all of them. **Back up the `.jks` and its password off this
> machine.** Current key: `CN=HAMS Task Recorder, O=KLK`, SHA-256 `98fb0136…4f73`.
>
> The four keys above are needed only for `:app:assembleRelease`. Without them the build still
> succeeds but emits `app-release-unsigned.apk`, which Android refuses to install. The build
> prints a `HAMS: ... UNSIGNED APK` warning when the keystore is missing or the path is wrong.

## Compile-time tunables (in `app/src/main/java/com/klk/hams/AppConfig.kt`, not `local.properties`)
These are Kotlin constants, changed by editing `AppConfig.kt` and rebuilding — not injected from `local.properties`. Listed here so operators know they exist.
| Constant | Default | What it controls |
|---|---|---|
| `PUSH_ALLOW_METERED` | `true` | `true` = cuts upload over **any** network incl. mobile data / hotspot (`NetworkType.CONNECTED`); `false` = Wi-Fi-only (`UNMETERED`). Field feedback 2026-07-15: hotspot-only phones never uploaded on Wi-Fi-only. Trade-off: uploads spend mobile data (each cut is a tiny frame). |
| `DELIVER_BUDGET_MS` | `15000` (~15 s) | Ceiling on the synchronous cut-delivery step at OTP release (deliver-before-strand). On timeout the release proceeds, counting what landed and stranding the rest. Raise if the field strands deliverable cuts on a healthy-but-slow link. |
| `HEARTBEAT_INTERVAL_MINUTES` | `1` | Periodic beacon (`35`) cadence, active-task scoped. `0` disables. (Runtime-overridable via `heartbeat_interval_minutes` in SharedPreferences, range 5–60.) |
| `BINDING_CHECK_INTERVAL_MINUTES` | `15` | Periodic binding re-check cadence (`BindingCheckWorker`). WorkManager floor is 15 min. |
| `SQLITE_RETENTION_DAYS` | `30` | How long terminal-state (`uploaded`/`failed`/`discarded`) tasks and stranded rows are kept before the retention sweep deletes them. |

## Backend / admin values (not in the APK)
| Name | Where | What | Kind |
|---|---|---|---|
| `PROV_DB_URL` | `psql` + n8n Postgres credential | `postgresql://user:pass@host:5432/neondb` (Neon: `?sslmode=require`) | 🔴 yours (secret) |
| `WIALON_TOKEN` | n8n `seed` login node | same token as above | 🔴 yours (secret) |
| `HAMS_CLAIM_SECRET` | n8n `manual-claim`/`release` IF node | same value as the app | 🔴 yours (secret) |
| n8n owner login | n8n first-run | your admin account | 🔴 yours |

Postgres: any Postgres works. Neon needs **SSL = Require** (`?sslmode=require`). Set the same
connection string as the n8n **Postgres** credential (call it "Postgres account"). Tables: `"G_PM_IT_IOT_HAMS_UNITS"`,
`"G_PM_IT_IOT_HAMS_ADMIN_OTP"` (quoted UPPERCASE - the quotes are mandatory in every reference). Functions: `seed_unit`, `issue_otp`/`otp_is_valid`/`consume_otp`, `manual_claim`,
`release_unit`.

## Preset shared-platform values — 🟢 info only
Use as-is unless your Wialon account is on a different Wialon server.
| Field | Value |
|---|---|
| IPS host : port | `185.213.1.24 : 20332` |
| IPS DNS | `nl2.gpsgsm.org` |
| Protocol | Wialon IPS v1.1 (16-field `#D#` frame) |
| Login frame | `#L#<unique_id>;NA\r\n` → expect `#AL#1` |
| Hardware type | `Wialon IPS` (id `600002235`) |

## Ports (fixed by the documented `docker run` commands)
| Service | Host port | If taken, change host side + all references |
|---|---|---|
| n8n | `5678` | `cloudflared --url`, `adb reverse`, `healthz`, webhook URLs |
| Postgres (local container) | `5432` | `PROV_DB_URL` |

## n8n workflows
| Workflow | Type | URL / how to run | Auth |
|---|---|---|---|
| `generate-otp` | Form (login) | n8n form → returns a 6-digit OTP | n8n login |
| `manual-claim` | Webhook POST | `<n8n>/webhook/manual-claim` | `x-hams-key` + OTP |
| `release` | Webhook POST | `<n8n>/webhook/release` | `x-hams-key` + OTP |
| `seed` | Manual/Schedule | run in editor → pulls Wialon units into `"G_PM_IT_IOT_HAMS_UNITS"` | Wialon token |

Webhooks only go live after **Publish** (n8n 2.x). `<n8n>` = the tunnel base URL (or
`http://127.0.0.1:5678` with `adb reverse`).

## Webhook status → HTTP contract (authoritative — must match the app parser)
| status | HTTP | body |
|---|---|---|
| `ok` (manual-claim) | 200 | `{"unique_id":…}` |
| `ok` (release) | 200 | `{"ok":true}` |
| `unauthorized` (bad/missing `x-hams-key`) | 401 | `{"error":"unauthorized"}` |
| `admin_auth_failed` (bad/expired OTP) | 401 | `{"error":"admin_auth_failed"}` |
| `fingerprint_in_use` | 409 | `{"error":"fingerprint_in_use","on":…}` |
| `already_bound` | 409 | `{"error":"already_bound"}` |
| `not_found` | 404 | `{"error":"not_found"}` |
| `not_owner_or_not_found` (release) | 409 | `{"error":"not_owner_or_not_found"}` |
| `bad_request` | 400 | `{"error":"bad_request"}` |

OTP is validated first and **consumed only on a successful** bind/release (a failed guard never
burns a code).

## Common commands
```bash
docker start hams-n8n hams-pg                                  # after a reboot / sleep
cloudflared tunnel --url http://localhost:5678                 # expose n8n (new URL each run — update local.properties + rebuild)
psql $env:PROV_DB_URL -c "SELECT issue_otp(10);"              # mint an OTP
psql $env:PROV_DB_URL -c "SELECT unique_id, device_fingerprint, claimed FROM \"G_PM_IT_IOT_HAMS_UNITS\" ORDER BY unique_id;"  # who owns what
.\gradlew.bat :app:installDebug                                # build + install the app
```

## Where the secrets live (never in git, never in a shared zip)
- `HAMS_CLAIM_SECRET`, `WIALON_TOKEN`, `PROV_DB_URL` → `local.properties`
- n8n Postgres password, `x-hams-key`, Wialon token → inside n8n (credential + node fields)
- The `provisioning/n8n/workflows/*.json` have these replaced by placeholders
  (`<HAMS_CLAIM_SECRET>`, `<WIALON_TOKEN>`) — re-enter after importing.

---
**Nav:** [🏠 Hub](README.md) · [Overview](COMPANY_HANDOFF.md) · [Setup](SETUP.md) · [Backend](provisioning/BUILD_ADMIN_BACKEND.md) · [Tests](TEST_CASES.md)
