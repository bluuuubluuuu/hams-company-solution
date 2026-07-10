# Configure

Prereq: [REQUIREMENTS.md](REQUIREMENTS.md) ✅ · [DATABASE.md](DATABASE.md) ✅ · [CREDENTIALS.md](CREDENTIALS.md) ✅

## 1. Import

Per file in `workflows/`: **Workflows → ⋯ → Import from File**.

Import all 7. Order does not matter.

## 2. Fix every node that needs it

Imported workflows come in **broken on purpose** — credentials are unlinked and secrets are placeholders.

| Workflow | Node | Field | Change to |
|---|---|---|---|
| *all 7* | every Postgres node | Credential | select `Postgres account` |
| `G_PM_IT_IOT_HAMS_MANUALCLAIM` | IF | compare value | your `HAMS_CLAIM_SECRET` |
| `G_PM_IT_IOT_HAMS_RELEASE` | IF | compare value | your `HAMS_CLAIM_SECRET` |
| `G_PM_IT_IOT_HAMS_VERIFY` | IF | compare value | your `HAMS_CLAIM_SECRET` |
| `G_PM_IT_IOT_HAMS_SEED` | HTTP Request | token in URL | our `WIALON_TOKEN` |
| `G_PM_IT_IOT_HAMS_ADMINRELEASE` | Form Trigger | Authentication | select `KLK_hams_admin` |

> Search each JSON for `<` to find every placeholder.

## 3. Publish the 5

Webhook + form URLs return **404** until Published. Saving is not enough. Active is not enough.

| Publish ✅ | Do not publish ❌ |
|---|---|
| `G_PM_IT_IOT_HAMS_MANUALCLAIM` | `G_PM_IT_IOT_HAMS_SEED` |
| `G_PM_IT_IOT_HAMS_RELEASE` | `G_PM_IT_IOT_HAMS_LISTUNITS` |
| `G_PM_IT_IOT_HAMS_VERIFY` | |
| `G_PM_IT_IOT_HAMS_ADMINRELEASE` | |
| `G_PM_IT_IOT_HAMS_DEVICEOTP` | |

## 4. Run the 2 by hand

Open in editor → **Execute workflow**.

| Workflow | When |
|---|---|
| `G_PM_IT_IOT_HAMS_SEED` | once at setup; again when new units are added in Wialon |
| `G_PM_IT_IOT_HAMS_LISTUNITS` | whenever the office wants to see who owns what |

## 5. Test

Replace `<BASE>` with your n8n base URL.

| # | Command | Expect |
|---|---|---|
| 1 | `curl -o /dev/null -w "%{http_code}" <BASE>/healthz` | `200` |
| 2 | `curl -o /dev/null -w "%{http_code}" -X POST <BASE>/webhook/manual-claim -d '{}'` | `401` |
| 3 | Open `<BASE>/form/<generate-otp id>` | prompts for OTP TTL |
| 4 | Open `<BASE>/form/<admin-release id>` | prompts for Basic Auth |

Test 2 returning `404` = not Published. Returning `500` = credential not linked.

## Status contract — do not change

The app branches on these strings. Renaming one = rebuild + reinstall every handset.

| Status | HTTP |
|---|---|
| `ok` | 200 |
| `bad_request` | 400 |
| `admin_auth_failed` | 401 |
| `unauthorized` (bad `x-hams-key`) | 401 |
| `not_found` | 404 |
| `already_bound` | 409 |
| `fingerprint_in_use` | 409 |
| `not_owner_or_not_found` | 409 |
| `draining` | 409 |
| `bound` / `released` / `bound_other` (verify only) | 200 |

## Gotchas

| Symptom | Cause |
|---|---|
| `Unrecognized node type` on import | n8n < 2.27 |
| Webhook `404` | not Published |
| Webhook `500` | Postgres credential not selected |
| Pairing → `not_found` | `"G_PM_IT_IOT_HAMS_UNITS"` table not seeded |
| Form submit → `500` "Workflow could not be started" | form posts `multipart/form-data`; don't test forms with JSON curl — use a browser |
| OTP always rejected | `G_PM_IT_IOT_HAMS_DEVICEOTP` not Published, or DB timezone stored as local |
