# HAMS n8n — Handover Pack

Exported live from the working local instance (n8n **2.27.5**) on 2026-07-09.

## What this is

- HAMS phones pair to a Wialon unit id via n8n webhooks.
- n8n does **identity only**. Harvest data goes phone → Wialon directly, never through n8n.
- You deploy n8n + Postgres in the cloud. You own both. You hand back one URL.

```
[Phone] --pair (unit id + OTP)--> [n8n] --> [Postgres: units table]
[Phone] --harvest cut data ---------------> [Wialon cloud]   (not your concern)
```

## Do this in order

| # | Step | Doc |
|---|---|---|
| 1 | Check n8n version + node compatibility | [REQUIREMENTS.md](REQUIREMENTS.md) |
| 2 | Provision Postgres, run the SQL | [DATABASE.md](DATABASE.md) |
| 3 | Create the 2 credentials | [CREDENTIALS.md](CREDENTIALS.md) |
| 4 | Import + configure the 7 workflows | [CONFIGURE.md](CONFIGURE.md) |
| 5 | Send us the handback values | [HANDBACK.md](HANDBACK.md) |

## Contents

| Path | What |
|---|---|
| `workflows/*.json` | 7 live workflow exports, secrets replaced by placeholders |
| `REQUIREMENTS.md` | Version floor, pinned node versions, substitution rule |
| `DATABASE.md` | DB provisioning + SQL to run |
| `CREDENTIALS.md` | The 2 credentials, who supplies what |
| `CONFIGURE.md` | Import steps + every field to change |
| `HANDBACK.md` | What to send back |

## The 7 workflows

| Workflow | Trigger | Purpose | Publish? | How it runs |
|---|---|---|---|---|
| `G_PM_IT_IOT_HAMS_MANUALCLAIM` | Webhook POST | Bind phone → unit | ✅ **Publish** | phone calls it |
| `G_PM_IT_IOT_HAMS_RELEASE` | Webhook POST | Unbind (phone + OTP) | ✅ **Publish** | phone calls it |
| `G_PM_IT_IOT_HAMS_VERIFY` | Webhook POST | Phone re-checks its binding | ✅ **Publish** | phone calls it, ~every 15 min |
| `G_PM_IT_IOT_HAMS_ADMINRELEASE` | Form (Basic Auth) | Office force-free a unit | ✅ **Publish** | admin opens form URL |
| `G_PM_IT_IOT_HAMS_DEVICEOTP` | Form | Mint a 6-digit OTP | ✅ **Publish** | admin opens form URL |
| `G_PM_IT_IOT_HAMS_SEED` | Manual | Pull units from Wialon into Postgres | ❌ no | **run by hand** in editor |
| `G_PM_IT_IOT_HAMS_LISTUNITS` | Manual | Read-only unit list | ❌ no | **run by hand** in editor |

### Publish vs run-by-hand

- **Publish (n8n 2.x):** webhook and form URLs return `404` until the workflow is **Published**. Importing and saving is not enough. Toggling Active in the editor is not enough. 5 workflows must be Published.
- **Run by hand:** `G_PM_IT_IOT_HAMS_SEED` and `G_PM_IT_IOT_HAMS_LISTUNITS` have a Manual Trigger. Open them in the editor and click **Execute workflow**. Never publish them.
  - `G_PM_IT_IOT_HAMS_SEED` — run **once at setup**, then again whenever new units are added in Wialon.
  - `G_PM_IT_IOT_HAMS_LISTUNITS` — run whenever the office wants to see who owns what.

> ⚠️ In our local instance `G_PM_IT_IOT_HAMS_DEVICEOTP` was left **unpublished** — only its test URL worked.
> On your cloud instance it **must be Published** or the office cannot mint OTPs.

## Secrets — read before you start

- **No credentials are exported.** n8n credentials cannot be usefully exported without decrypting them. You create both from scratch.
- The workflow JSONs contain **placeholders**, not values:

| Placeholder | Appears in | Who supplies |
|---|---|---|
| `<HAMS_CLAIM_SECRET>` | `G_PM_IT_IOT_HAMS_MANUALCLAIM`, `G_PM_IT_IOT_HAMS_RELEASE`, `G_PM_IT_IOT_HAMS_VERIFY` | **You mint it** |
| `<WIALON_TOKEN>` | `G_PM_IT_IOT_HAMS_SEED` | **We supply it** |

- Do not commit real values into these JSONs. Do not send them over chat.
