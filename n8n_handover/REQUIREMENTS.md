# Requirements & Version Compatibility

## Version floor

| Item | Required | Why |
|---|---|---|
| n8n | **≥ 2.27** | `formTrigger` v2.6 and `postgres` v2.6 do not exist below this |
| Exported from | n8n **2.27.5** | the instance these JSONs came from |
| Postgres | 14+ (any) | uses `jsonb`, `ON CONFLICT`, plpgsql only |
| TLS to Postgres | required | `?sslmode=require` |

**Symptom if n8n is too old:** import fails, or a node shows *"Unrecognized node type"* / *"unknown node version"*. This is the most common setup failure. Check the version **before** importing.

## Pinned node versions

Do not change these. Every node below appears in the exported JSONs at exactly this `typeVersion`.

| Node | Version | Used by |
|---|---|---|
| `webhook` | 2.1 | manual-claim, release, verify |
| `respondToWebhook` | 1.5 | manual-claim, release, verify |
| `if` | 2.3 | manual-claim, release, verify |
| `code` | 2 | manual-claim, release, verify |
| `postgres` | 2.6 | **all 7** |
| `formTrigger` | 2.6 | admin-release, generate-otp |
| `form` | 2.5 | admin-release, generate-otp |
| `httpRequest` | 4.4 | seed |
| `filter` | 2.3 | seed |
| `splitOut` | 1 | seed |
| `manualTrigger` | 1 | list-units, seed |

## Substitution rule

If you need to use a different node, a different node version, or a different database engine:

1. **Stop. Do not proceed.**
2. List what you intend to use, and why the pinned one does not work.
3. **Reconfirm with us before changing anything.**
4. If we approve, record what you actually used in [HANDBACK.md](HANDBACK.md).

Reason: the app's wire contract depends on the exact status strings and HTTP codes these nodes emit. A silent node swap can return `200` where `409` is expected, and the phone will pair to a unit it does not own.

## What must never change

| Item | Why |
|---|---|
| Webhook paths `/webhook/manual-claim`, `/release`, `/verify` | compiled into the app |
| Header name `x-hams-key` | compiled into the app |
| The 9 status strings + their HTTP codes (see `CONFIGURE.md`) | the app branches on them |
| SQL function names + argument order | the workflows call them by name |

Changing any of these means rebuilding and reinstalling the app on **every handset in the field**.
