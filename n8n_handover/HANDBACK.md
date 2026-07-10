# Handback

When the cloud instance is live, send us this. Nothing else.

## Send us

| # | Item | Example | Channel |
|---|---|---|---|
| 1 | n8n base URL | `https://n8n.<yourdomain>.com` | normal |
| 2 | `HAMS_CLAIM_SECRET` | the value you minted | **secure channel only** |
| 3 | Confirmation the 5 are Published | ✅ / ❌ per workflow | normal |
| 4 | Confirmation `G_PM_IT_IOT_HAMS_SEED` was run | row count in `"G_PM_IT_IOT_HAMS_UNITS"` | normal |
| 5 | Any node/version you substituted | see [REQUIREMENTS.md](REQUIREMENTS.md) | normal |

**Do not send:** DB password, DB connection string, `KLK_hams_admin` password, n8n owner login. We do not need them.

## URL requirement — important

The base URL is **compiled into the app**. Every URL change = we rebuild the APK and reinstall on **every handset by cable**.

| Acceptable | Not acceptable |
|---|---|
| Fixed custom domain (`n8n.yourdomain.com`) | random cloud hostname that changes on redeploy |
| Named tunnel with a stable hostname | `cloudflared` quick tunnel (dies on restart) |
| Static ngrok domain | any URL that rotates |

**Give us a URL you will not change.** Bake it in once.

## We derive these — you don't send them

| App key | Value |
|---|---|
| `MANUAL_CLAIM_URL` | `<BASE>/webhook/manual-claim` |
| `RELEASE_URL` | `<BASE>/webhook/release` |
| `VERIFY_URL` | `<BASE>/webhook/verify` |

## Then we do

1. Put your base URL + your `HAMS_CLAIM_SECRET` into `local.properties`.
2. Rebuild the APK.
3. Reinstall on handsets **once**.
4. Pair a test handset against your instance and confirm a cut lands on the right Wialon unit.

## Checklist before you hand back

- [ ] n8n ≥ 2.27
- [ ] Postgres reachable, TLS on, network-restricted to n8n
- [ ] `database/sql/hams_setup.sql` applied
- [ ] `"G_PM_IT_IOT_HAMS_UNITS"` seeded — not empty
- [ ] `Postgres account` + `KLK_hams_admin` credentials created, exact names
- [ ] All 7 workflows imported, every Postgres node linked
- [ ] 3 × `<HAMS_CLAIM_SECRET>` replaced, 1 × `<WIALON_TOKEN>` replaced
- [ ] 5 workflows Published; `G_PM_IT_IOT_HAMS_SEED` + `G_PM_IT_IOT_HAMS_LISTUNITS` left unpublished
- [ ] `POST /webhook/manual-claim` with no key returns `401`
- [ ] Base URL is fixed and will not change
