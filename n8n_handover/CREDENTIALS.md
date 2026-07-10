# Credentials

**Nothing is exported.** n8n credentials cannot be exported usefully without decrypting them. Create both by hand.

## The 2 credentials

| Credential name | Type | Used by | Value |
|---|---|---|---|
| `Postgres account` | Postgres | **all 7 workflows** | your DB connection |
| `KLK_hams_admin` | HTTP Basic Auth | `G_PM_IT_IOT_HAMS_ADMINRELEASE` only | user + password you choose |

> ⚠️ Names must match **exactly**. The imported JSONs reference them by name. A rename = re-select the credential in every node.

### `Postgres account`

| Field | Value |
|---|---|
| Host / Port / Database / User / Password | from your DB |
| SSL | **Require** |

### `KLK_hams_admin`

| Field | Value |
|---|---|
| User | you choose |
| Password | you choose (strong) |

Protects the `G_PM_IT_IOT_HAMS_ADMINRELEASE` form. Anyone with it can force-free any unit.

## Ownership — who supplies what

| Value | Provisioned by | Held by | Travels |
|---|---|---|---|
| Postgres DB + password | **You** | You | never leaves you |
| `Postgres account` credential | **You** | You | never leaves you |
| `KLK_hams_admin` login | **You** | You | never leaves you |
| n8n owner login | **You** | You | never leaves you |
| **`HAMS_CLAIM_SECRET`** | **You mint it** | You + us | **you → us** (we compile it into the app) |
| **`WIALON_TOKEN`** | **We supply it** | Us + you | **us → you** (you paste into `G_PM_IT_IOT_HAMS_SEED`) |

Only 2 values cross between us.

## The 2 placeholders in the workflow JSONs

| Placeholder | Workflow | Node | Field |
|---|---|---|---|
| `<HAMS_CLAIM_SECRET>` | `G_PM_IT_IOT_HAMS_MANUALCLAIM` | IF node | compare value |
| `<HAMS_CLAIM_SECRET>` | `G_PM_IT_IOT_HAMS_RELEASE` | IF node | compare value |
| `<HAMS_CLAIM_SECRET>` | `G_PM_IT_IOT_HAMS_VERIFY` | IF node | compare value |
| `<WIALON_TOKEN>` | `G_PM_IT_IOT_HAMS_SEED` | HTTP Request | token in URL |

### `HAMS_CLAIM_SECRET` — mint a new one

| Rule | |
|---|---|
| Format | any strong string, e.g. 32 random chars |
| Must match | all 3 IF nodes **and** the app build |
| Send to us | over a secure channel — **not chat, not email, not a ticket** |
| If it leaks | every handset must be rebuilt + reinstalled. It is compiled into the APK. |

### `WIALON_TOKEN`

- Ours. Read-only access to our Wialon account.
- Used by `G_PM_IT_IOT_HAMS_SEED` only, to list units.
- We send it to you. Do not commit it.

## Rules

- Never commit real values into the JSONs — keep the placeholders in git.
- Never export credentials with `--decrypted`.
- Rotate anything that appears in a screenshot, chat, or ticket.
