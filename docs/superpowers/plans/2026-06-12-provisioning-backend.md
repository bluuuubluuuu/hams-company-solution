# Provisioning Backend (n8n + Postgres) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the n8n + Postgres backend that seeds available Wialon unit IDs and hands each launching device one empty `unique_id`, fingerprint-locked, so a single APK can auto-claim units.

**Architecture:** A Postgres `units` table is the registry (free/claimed state). One n8n **seeding** workflow pulls verified units from a designated Wialon group (read-only REST) and UPSERTs them. One n8n **claim** webhook returns a `unique_id` for a device fingerprint — idempotent (same fingerprint → same unit) and race-safe (`FOR UPDATE SKIP LOCKED`). Wialon is touched only at seeding; the per-device claim reads Postgres only.

**Tech Stack:** PostgreSQL (plpgsql), n8n (HTTP Request, Webhook, Postgres nodes), Wialon REST (`token/login`, `core/search_items`), curl/psql for tests.

**Source of truth for decisions:** `docs/HAMS_PROVISIONING_FINDINGS.md` (§3 flow, §4 seeding, §10 resolution log, §11 register). This plan is the **MVP** — re-bind/admin/auto-sweep are OUT of scope (office-only SOP, §6).

**Repo layout for artifacts (version-controlled):**
- `provisioning/sql/` — schema + functions (`.sql`)
- `provisioning/n8n/` — exported workflow JSON
- `provisioning/README.md` — runbook (env vars, how to import/run)

---

## Prerequisites (must be true before Task 1)

- [ ] n8n instance running, with a Postgres credential and an HTTP node able to reach `https://hst-api.wialon.eu`.
- [ ] Postgres database reachable from n8n (DB name e.g. `hams_provisioning`).
- [ ] Wialon **read** token available (from `local.properties` `WIALON_TOKEN`); confirm it can `token/login`.
- [ ] A Wialon unit group named **`HAMS-ready`** exists, containing ≥1 verified unit (per `docs/HAMS_UNIT_PROVISIONING_CHECKLIST.md`).
- [ ] **C12 resolved** (Task 0 below) — the exact `core/search_items` flag that returns each unit's `unique_id`.

---

## Task 0: C12 spike — confirm Wialon returns `unique_id`

**Files:**
- Create: `provisioning/README.md` (record the confirmed flag values)

- [ ] **Step 1: Log in to Wialon, capture the session id**

Run (replace `<TOKEN>`):
```bash
curl -s "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=token/login" \
  --data-urlencode 'params={"token":"<TOKEN>"}'
```
Expected: JSON containing `"eid":"<SID>"`. Copy `<SID>`.

- [ ] **Step 2: Search units and inspect which flag returns the unique ID**

Run (replace `<SID>`):
```bash
curl -s "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=core/search_items" \
  --data-urlencode 'params={
    "spec":{"itemsType":"avl_unit","propName":"sys_name","propValueMask":"*","sortType":"sys_name"},
    "force":1,"flags":4097,"from":0,"to":0}' \
  --data-urlencode "sid=<SID>"
```
Expected: JSON `items[]`. Look for each item's IPS unique ID. Flag `1` = base; the unique-id/admin field needs the "admin fields"/"profile" bit. Try `flags` values `4097` (0x1001), then `8193` (0x2001), then `1025` (0x401) until an item shows its `uid` / unique-id property.

- [ ] **Step 3: Record the working flag + the JSON path to the unique id**

In `provisioning/README.md`, write the confirmed `flags` value and the exact field path (e.g. `items[].uid` or `items[].prms.unique_id`). If NO flag returns it under the token's ACL, STOP and report — fall back to Option 2 (vendor spreadsheet import) per findings D5.

- [ ] **Step 4: Confirm group filtering works**

Run (replace `<SID>`, `<GROUP_ID>` of `HAMS-ready`):
```bash
curl -s "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=core/search_items" \
  --data-urlencode 'params={
    "spec":{"itemsType":"avl_unit_group","propName":"sys_name","propValueMask":"HAMS-ready","sortType":"sys_name"},
    "force":1,"flags":1,"from":0,"to":0}' \
  --data-urlencode "sid=<SID>"
```
Expected: the group item with a `u` array of member unit IDs. Record the group id + that member-unit resolution works.

- [ ] **Step 5: Commit**

```bash
git add provisioning/README.md
git commit -m "docs(provisioning): record confirmed Wialon search_items flag for unique_id (C12)"
```

---

## Task 1: Postgres schema — the `units` registry

**Files:**
- Create: `provisioning/sql/001_units.sql`

- [ ] **Step 1: Write the schema**

Create `provisioning/sql/001_units.sql`:
```sql
CREATE TABLE IF NOT EXISTS units (
    unique_id          TEXT PRIMARY KEY,
    name               TEXT,
    claimed            BOOLEAN NOT NULL DEFAULT false,
    device_fingerprint TEXT UNIQUE,                 -- ANDROID_ID; multiple NULLs allowed
    status             TEXT NOT NULL DEFAULT 'active', -- 'active' | 'retired'
    last_seen          TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Fast "next free unit" lookups.
CREATE INDEX IF NOT EXISTS idx_units_free
    ON units (unique_id)
    WHERE claimed = false AND status = 'active';
```

- [ ] **Step 2: Apply it**

Run (replace conn vars):
```bash
psql "$PROV_DB_URL" -f provisioning/sql/001_units.sql
```
Expected: `CREATE TABLE` / `CREATE INDEX`, no error.

- [ ] **Step 3: Verify the table shape**

Run:
```bash
psql "$PROV_DB_URL" -c "\d units"
```
Expected: columns `unique_id, name, claimed, device_fingerprint, status, last_seen, created_at, updated_at`; `device_fingerprint` UNIQUE; `unique_id` PK.

- [ ] **Step 4: Commit**

```bash
git add provisioning/sql/001_units.sql
git commit -m "feat(provisioning): units registry schema"
```

---

## Task 2: `claim_unit` function — idempotent + race-safe claim

**Files:**
- Create: `provisioning/sql/002_claim_unit.sql`
- Test: `provisioning/sql/tests/test_claim_unit.sql`

- [ ] **Step 1: Write the failing test**

Create `provisioning/sql/tests/test_claim_unit.sql`:
```sql
\set ON_ERROR_STOP on
BEGIN;
-- seed two free units
INSERT INTO units (unique_id, name) VALUES ('OC154_H001','H001'), ('OC154_H002','H002');

-- first claim returns a unit
SELECT claim_unit('fpA') AS first_claim \gset
\if :{?first_claim}
\else
  \echo 'FAIL: claim_unit missing'
\endif

-- idempotent: same fingerprint returns the SAME unit
SELECT (claim_unit('fpA') = :'first_claim') AS idempotent \gset
SELECT CASE WHEN :'idempotent' = 't' THEN 'PASS idempotent' ELSE 'FAIL idempotent' END;

-- different fingerprint gets a DIFFERENT unit
SELECT (claim_unit('fpB') <> :'first_claim') AS distinct_unit \gset
SELECT CASE WHEN :'distinct_unit' = 't' THEN 'PASS distinct' ELSE 'FAIL distinct' END;

-- pool now empty: third fingerprint gets NULL
SELECT (claim_unit('fpC') IS NULL) AS no_free \gset
SELECT CASE WHEN :'no_free' = 't' THEN 'PASS no_free' ELSE 'FAIL no_free' END;
ROLLBACK;
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
psql "$PROV_DB_URL" -f provisioning/sql/tests/test_claim_unit.sql
```
Expected: FAIL — `function claim_unit(unknown) does not exist`.

- [ ] **Step 3: Write the function**

Create `provisioning/sql/002_claim_unit.sql`:
```sql
CREATE OR REPLACE FUNCTION claim_unit(p_fingerprint text)
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
    v_unit text;
BEGIN
    IF p_fingerprint IS NULL OR p_fingerprint = '' THEN
        RAISE EXCEPTION 'empty fingerprint';
    END IF;

    -- Idempotent: this device already owns a unit.
    SELECT unique_id INTO v_unit
      FROM units
     WHERE device_fingerprint = p_fingerprint AND status = 'active';
    IF v_unit IS NOT NULL THEN
        RETURN v_unit;
    END IF;

    -- Claim the next free unit atomically; concurrent callers skip locked rows.
    UPDATE units
       SET claimed = true, device_fingerprint = p_fingerprint,
           last_seen = now(), updated_at = now()
     WHERE unique_id = (
         SELECT unique_id FROM units
          WHERE claimed = false AND status = 'active'
          ORDER BY unique_id
          FOR UPDATE SKIP LOCKED
          LIMIT 1)
    RETURNING unique_id INTO v_unit;

    RETURN v_unit;  -- NULL when no free unit remains
EXCEPTION
    WHEN unique_violation THEN
        -- A concurrent call set our fingerprint on another row; return that.
        SELECT unique_id INTO v_unit
          FROM units
         WHERE device_fingerprint = p_fingerprint AND status = 'active';
        RETURN v_unit;
END;
$$;
```

- [ ] **Step 4: Apply and run the test to verify it passes**

Run:
```bash
psql "$PROV_DB_URL" -f provisioning/sql/002_claim_unit.sql
psql "$PROV_DB_URL" -f provisioning/sql/tests/test_claim_unit.sql
```
Expected: `PASS idempotent`, `PASS distinct`, `PASS no_free`.

- [ ] **Step 5: Commit**

```bash
git add provisioning/sql/002_claim_unit.sql provisioning/sql/tests/test_claim_unit.sql
git commit -m "feat(provisioning): claim_unit — idempotent + race-safe claim"
```

---

## Task 3: `seed_units` UPSERT — never clobber a live claim

**Files:**
- Create: `provisioning/sql/003_seed_unit.sql`
- Test: `provisioning/sql/tests/test_seed_unit.sql`

- [ ] **Step 1: Write the failing test**

Create `provisioning/sql/tests/test_seed_unit.sql`:
```sql
\set ON_ERROR_STOP on
BEGIN;
-- existing claimed unit
INSERT INTO units (unique_id, name, claimed, device_fingerprint)
VALUES ('OC154_H001','old', true, 'fpA');

-- re-seed the same id (name changed) + a brand new id
SELECT seed_unit('OC154_H001','renamed');
SELECT seed_unit('OC154_H009','H009');

-- claimed state + fingerprint preserved on the existing row
SELECT CASE WHEN claimed = true AND device_fingerprint = 'fpA' AND name = 'renamed'
            THEN 'PASS preserve' ELSE 'FAIL preserve' END
  FROM units WHERE unique_id = 'OC154_H001';

-- new row inserted free
SELECT CASE WHEN claimed = false AND status = 'active'
            THEN 'PASS new_free' ELSE 'FAIL new_free' END
  FROM units WHERE unique_id = 'OC154_H009';
ROLLBACK;
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
psql "$PROV_DB_URL" -f provisioning/sql/tests/test_seed_unit.sql
```
Expected: FAIL — `function seed_unit(unknown, unknown) does not exist`.

- [ ] **Step 3: Write the function**

Create `provisioning/sql/003_seed_unit.sql`:
```sql
CREATE OR REPLACE FUNCTION seed_unit(p_unique_id text, p_name text)
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO units (unique_id, name, status)
    VALUES (p_unique_id, p_name, 'active')
    ON CONFLICT (unique_id) DO UPDATE
        SET name = EXCLUDED.name, updated_at = now();
    -- NOTE: deliberately does NOT touch claimed / device_fingerprint / status,
    -- so re-seeding never resets a live assignment.
$$;
```

- [ ] **Step 4: Apply and run the test to verify it passes**

Run:
```bash
psql "$PROV_DB_URL" -f provisioning/sql/003_seed_unit.sql
psql "$PROV_DB_URL" -f provisioning/sql/tests/test_seed_unit.sql
```
Expected: `PASS preserve`, `PASS new_free`.

- [ ] **Step 5: Commit**

```bash
git add provisioning/sql/003_seed_unit.sql provisioning/sql/tests/test_seed_unit.sql
git commit -m "feat(provisioning): seed_unit UPSERT preserves live claims"
```

---

## Task 4: n8n seeding workflow — Wialon `HAMS-ready` group → Postgres

**Files:**
- Create: `provisioning/n8n/seeding.json` (exported workflow)

- [ ] **Step 1: Build the workflow in n8n**

Nodes, in order:
1. **Manual Trigger** (later swap/add a **Schedule** node, e.g. daily).
2. **HTTP Request — login**: POST `https://hst-api.wialon.eu/wialon/ajax.html`, body params `svc=token/login`, `params={"token":"{{$env.WIALON_TOKEN}}"}`. Output: `eid`.
3. **HTTP Request — group members**: `svc=core/search_items` for the `HAMS-ready` group (flag/spec from Task 0 Step 4), `sid={{ $json.eid }}`. Output: member unit IDs.
4. **HTTP Request — unit details**: `svc=core/search_items` for those units with the Task-0 confirmed `flags`, returning each `unique_id` + name.
5. **Code/Function node**: map each item → `{ unique_id, name }` using the Task-0 JSON path.
6. **Postgres node** (Execute Query), run once per item:
   ```sql
   SELECT seed_unit($1, $2);
   ```
   with params `unique_id`, `name`.

- [ ] **Step 2: Run the workflow, verify rows land**

Execute the workflow once. Then:
```bash
psql "$PROV_DB_URL" -c "SELECT unique_id, name, claimed FROM units ORDER BY unique_id;"
```
Expected: one row per `HAMS-ready` unit, all `claimed = false`.

- [ ] **Step 3: Re-run, verify idempotency**

Execute the workflow a second time, then re-run the query.
Expected: same row count (no duplicates); any pre-claimed rows still `claimed = true` (seed_unit preserves them).

- [ ] **Step 4: Export + commit the workflow**

Export the workflow JSON from n8n to `provisioning/n8n/seeding.json`, then:
```bash
git add provisioning/n8n/seeding.json
git commit -m "feat(provisioning): n8n seeding workflow (HAMS-ready group -> Postgres)"
```

---

## Task 5: n8n claim webhook — secret + `claim_unit` + responses

**Files:**
- Create: `provisioning/n8n/claim.json` (exported workflow)

- [ ] **Step 1: Build the workflow in n8n**

Nodes:
1. **Webhook** node: method `POST`, path `claim`, response mode "Using Respond to Webhook".
2. **IF — auth**: condition `{{ $json.headers["x-hams-key"] }}` equals `{{ $env.HAMS_CLAIM_SECRET }}`.
   - False branch → **Respond to Webhook**: status `401`, body `{"error":"unauthorized"}`.
3. True branch → **Postgres** (Execute Query):
   ```sql
   SELECT claim_unit($1) AS unique_id;
   ```
   param: `{{ $json.body.fingerprint }}`.
4. **IF — got a unit**: `{{ $json.unique_id }}` is not empty.
   - True → **Respond to Webhook**: status `200`, body `{"unique_id":"{{ $json.unique_id }}"}`.
   - False → **Respond to Webhook**: status `409`, body `{"error":"no_free_units"}`.

- [ ] **Step 2: Test — auth rejected**

Run (replace host):
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "https://<n8n-host>/webhook/claim" \
  -H "Content-Type: application/json" \
  -d '{"fingerprint":"fpTEST1"}'
```
Expected: `401`.

- [ ] **Step 3: Test — claim succeeds**

Run (replace host + secret):
```bash
curl -s -X POST "https://<n8n-host>/webhook/claim" \
  -H "Content-Type: application/json" -H "x-hams-key: <SECRET>" \
  -d '{"fingerprint":"fpTEST1"}'
```
Expected: `{"unique_id":"OC154_H001"}` (first free unit).

- [ ] **Step 4: Test — idempotent**

Re-run the exact Step 3 command.
Expected: the **same** `unique_id` as Step 3 (not a new one).

- [ ] **Step 5: Test — distinct device gets a different unit**

Run with `"fingerprint":"fpTEST2"`.
Expected: a **different** `unique_id`.

- [ ] **Step 6: Test — no free units**

Claim with new fingerprints until the pool empties, then one more:
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "https://<n8n-host>/webhook/claim" \
  -H "Content-Type: application/json" -H "x-hams-key: <SECRET>" \
  -d '{"fingerprint":"fpDRAIN_LAST"}'
```
Expected: `409` with `{"error":"no_free_units"}`.

- [ ] **Step 7: Export + commit**

```bash
git add provisioning/n8n/claim.json
git commit -m "feat(provisioning): n8n claim webhook (secret + claim_unit + 200/401/409)"
```

---

## Task 6: Concurrency check — two phones, one second

**Files:**
- Test: `provisioning/sql/tests/test_concurrency.sh`

- [ ] **Step 1: Write the test**

Create `provisioning/sql/tests/test_concurrency.sh`:
```bash
#!/usr/bin/env bash
# Fire two distinct-fingerprint claims simultaneously; they must get DIFFERENT units.
set -euo pipefail
HOST="$1"; SECRET="$2"
req() { curl -s -X POST "$HOST/webhook/claim" -H "Content-Type: application/json" \
        -H "x-hams-key: $SECRET" -d "{\"fingerprint\":\"$1\"}"; }
req "race_$(date +%s)_A" & A=$!
req "race_$(date +%s)_B" & B=$!
wait $A $B
echo "(inspect the two lines above — the two unique_id values must differ)"
```

- [ ] **Step 2: Seed at least 2 free units, then run**

```bash
chmod +x provisioning/sql/tests/test_concurrency.sh
provisioning/sql/tests/test_concurrency.sh "https://<n8n-host>" "<SECRET>"
```
Expected: two responses with **different** `unique_id` values (proves `FOR UPDATE SKIP LOCKED` prevents a collision).

- [ ] **Step 3: Commit**

```bash
git add provisioning/sql/tests/test_concurrency.sh
git commit -m "test(provisioning): concurrent-claim returns distinct units"
```

---

## Task 7: Runbook

**Files:**
- Modify: `provisioning/README.md`

- [ ] **Step 1: Document operation**

Append to `provisioning/README.md`:
- Env vars: `PROV_DB_URL`, `WIALON_TOKEN`, `HAMS_CLAIM_SECRET`, `<n8n-host>`.
- Apply schema: `psql "$PROV_DB_URL" -f provisioning/sql/001_units.sql` (then 002, 003).
- Seeding: run the n8n `seeding` workflow (manual or scheduled) whenever the vendor adds units to `HAMS-ready`.
- Claim contract: `POST /webhook/claim` `{ "fingerprint": "<ANDROID_ID>" }` + header `x-hams-key` → `200 {unique_id}` | `401` | `409 no_free_units`.
- Free count: `psql "$PROV_DB_URL" -c "SELECT count(*) FROM units WHERE claimed=false AND status='active';"`
- Office re-bind (SOP §6): release `UPDATE units SET claimed=false, device_fingerprint=NULL WHERE unique_id='…';` then bind by re-claiming or `UPDATE … SET claimed=true, device_fingerprint='<newAID>' WHERE unique_id='…';` and clear the device's stored id.

- [ ] **Step 2: Commit**

```bash
git add provisioning/README.md
git commit -m "docs(provisioning): backend runbook"
```

---

## Out of scope (separate plans)

- **App integration** (runtime `unique_id`, first-launch claim, null/no-free UI) → Plan 2.
- **Office re-bind tooling, supervisor UI, auto-sweep** → deferred (office-only SOP, findings §6).
- **Signing keystore (#5)** → required before production app builds, not for backend testing.

## Self-review notes
- Spec coverage: seeding (§4) = Tasks 3–4; claim/idempotency/race (§3, #10/#11) = Tasks 2, 5, 6; security (#9) = Task 5; no-free (O2) = Tasks 2/5; Option 1 pull (D5) = Task 4; C12 = Task 0. ✓
- Types consistent: `claim_unit(text)→text`, `seed_unit(text,text)→void`, columns match across tasks. ✓
- No placeholders: all SQL, curl, and node configs are concrete. The only intentional lookups are Task-0 outputs (Wialon flag) — that's the spike's deliverable.
