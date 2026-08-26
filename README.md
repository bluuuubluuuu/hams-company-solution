<!-- DRAFT — production-documentation review required. -->
# HAMS Task Recorder

Production reference for the HAMS Android field-counting application and its company-managed Wialon, n8n, and PostgreSQL integrations.

Start with [Production documentation](docs/README.md).

## Quick routes

| Need | Document |
|---|---|
| Understand the live system | [System overview](docs/architecture/SYSTEM_OVERVIEW.md) |
| Trace data between systems | [Data flow](docs/architecture/DATA_FLOW.md) |
| Diagnose a field issue | [Operations runbook](docs/operations/OPERATIONS_RUNBOOK.md) |
| Pair, release, replace, or verify a device | [Device provisioning](docs/operations/DEVICE_PROVISIONING.md) |
| Add devices or sites safely | [Scaling guide](docs/operations/SCALING_GUIDE.md) |
| Look up configuration ownership | [Configuration reference](docs/reference/CONFIGURATION_REFERENCE.md) |
| Confirm current Android implementation | [Android application specification](docs/reference/ANDROID_APP_SPEC.md) |
| Look up app behaviour | [Functional requirements](docs/reference/requirements/FR_APP_REQUIREMENTS.md) and [non-functional requirements](docs/reference/requirements/NFR_APP_REQUIREMENTS.md) |
| Interpret messages and telemetry | [Event-code dictionary](docs/reference/EVENT_CODE_DICTIONARY.md) |
| Review recorded verification | [Testing record](docs/reference/TESTING_RECORD.md) |

## Repository boundaries

- `app/` is the Android application source.
- [Database dictionary](docs/operations/database-reference/DATABASE_DICTIONARY.md) explains the company-managed database boundary.
- `docs/operations/n8n-workflow-snapshots/` contains sanitized n8n workflow recovery snapshots.
- `local.properties` and real credentials are never committed. Start from [local.properties.example](local.properties.example) and [credentials.env.example](credentials.env.example).
- `HAMS_archive/` is ignored local history, not live operational guidance.

## Authorship

HAMS Task Recorder was built by **Woo Ying Hui, 2026**.

The same statement ships inside every release build, in two places that render
nowhere and are inert at runtime:

| Location | Read it with |
|---|---|
| `assets/NOTICE.txt` | `unzip -p releases/hams-1.2.apk assets/NOTICE.txt` |
| manifest `com.klk.hams.author` | `aapt dump xmltree hams-1.2.apk --file AndroidManifest.xml` |

Source lives at [app/src/main/assets/NOTICE.txt](app/src/main/assets/NOTICE.txt)
and [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml). Keep
both when changing build inputs — a cleanup that drops `assets/` silently
removes the record from every build after it.

Release builds are signed with certificate SHA-256
`98fb0136385382720339d88aec7db90df8e769101f78a9e22097f28617d44f73`
(`CN=HAMS Task Recorder, OU=IT, O=KLK, L=Ipoh, ST=Perak, C=MY`). A build without
that signer fingerprint did not come from this project.

Copyright in this work is subject to the terms of the author's engagement with
KLK; this notice records authorship and is not itself a determination of
ownership.
