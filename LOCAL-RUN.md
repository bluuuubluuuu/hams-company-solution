# LOCAL-RUN — launch the HAMS company solution on localhost

Everything here runs on one machine: a local **n8n** (Docker) + **Postgres** + the Android app.
The app talks to n8n only for pairing; cut data goes phone → Wialon directly.

## Prerequisites
- Docker Desktop
- Android SDK + a device/emulator (`adb`)
- `psql` (or any Postgres client)
- A Postgres database. Either a cloud Postgres (e.g. Neon) or a local container:
  ```
  docker run -d --name hams-pg -e POSTGRES_PASSWORD=hams -e POSTGRES_DB=neondb -p 5432:5432 postgres:16
  # PROV_DB_URL = postgresql://postgres:hams@localhost:5432/neondb
  ```

## 1. Apply the schema
```
psql "$PROV_DB_URL" -f provisioning/sql/001_units.sql
psql "$PROV_DB_URL" -f provisioning/sql/003_seed_unit.sql
psql "$PROV_DB_URL" -f provisioning/sql/004_admin_otp.sql
psql "$PROV_DB_URL" -f provisioning/sql/005_manual_provision.sql
# quick test unit if not seeding from Wialon yet:
psql "$PROV_DB_URL" -c "SELECT seed_unit('HAMS_TEST_001','TEST_HAMS_APP_001');"
```

## 2. Start n8n + import the workflows
```
docker run -d --name hams-n8n -p 5678:5678 -v n8n_data:/home/node/.n8n n8nio/n8n
# open http://localhost:5678, create the owner account
```
Then in the n8n editor:
1. Import `provisioning/n8n/workflows/*.json` (Import from File, one at a time).
2. Create a **Postgres** credential = `PROV_DB_URL` (cloud Postgres needs SSL=Require) and select
   it on every Postgres node.
3. `manual-claim` + `release`: set the IF node's expected key to your `HAMS_CLAIM_SECRET`
   (placeholder in the JSON is `<HAMS_CLAIM_SECRET>`).
4. `seed`: put your Wialon token in the login HTTP node (placeholder `<WIALON_TOKEN>`).
5. **Publish** `manual-claim` and `release` so their `/webhook/...` production URLs go live.

## 3. Expose n8n to the phone
`targetSdk 35` blocks plain HTTP to non-loopback hosts. Two options:
- **USB tethered:** `adb reverse tcp:5678 tcp:5678`, then use `http://127.0.0.1:5678/...`
  (allowed by the loopback rule in `app/src/main/res/xml/network_security_config.xml`).
- **Untethered:** front n8n with an HTTPS tunnel, e.g. `cloudflared tunnel --url http://localhost:5678`,
  and use the `https://…/webhook/...` URLs. (Quick-tunnel hostnames are ephemeral — re-point on restart.)

## 4. Point the app at n8n + build
Copy `local.properties.example` → `local.properties` and set:
```
MANUAL_CLAIM_URL=<n8n base>/webhook/manual-claim
RELEASE_URL=<n8n base>/webhook/release
HAMS_CLAIM_SECRET=<same value as the n8n IF node>
```
Then:
```
.\gradlew.bat :app:installDebug
```

## 5. Pair a device
1. Mint an OTP — either the n8n `generate-otp` form, or `psql "$PROV_DB_URL" -c "SELECT issue_otp(10);"`.
2. On the phone's PairingScreen enter the unit id (`HAMS_TEST_001`) + the OTP → Pair.
3. Confirm the bind:
   `psql "$PROV_DB_URL" -c "SELECT unique_id, device_fingerprint, claimed FROM units ORDER BY unique_id;"`

## Rig notes
- Docker containers stop when the PC sleeps → `docker start hams-n8n hams-pg`.
- The n8n workflow JSONs in this repo have secrets replaced by placeholders — always re-enter the
  real key + token after importing.
