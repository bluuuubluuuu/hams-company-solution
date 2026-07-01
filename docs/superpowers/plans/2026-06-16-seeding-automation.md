# Seeding Automation Implementation Plan (Wialon → Postgres)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automate the Wialon→Postgres unit seeding so the claim webhook always has a populated pool, replacing manual `seed_unit(...)` calls.

**Architecture:** A one-off read-only REST spike confirms the Wialon `search_items` flag + JSON path that exposes the IPS `unique_id`. A new manual-trigger n8n workflow then pulls units, filters by an interim name-mask, and UPSERTs each into Postgres via the existing idempotent `seed_unit(unique_id, name)`. The phone never touches this path.

**Tech Stack:** Wialon REST (`hst-api.wialon.eu`), n8n.cloud (HTTP Request + Postgres + Split Out + Filter nodes), Neon Postgres, curl for the spike/verification.

> **Note on "tests":** this plan builds n8n UI workflows and REST calls, not unit-testable code. The TDD analogue here is **verify-before-build**: each task states the exact curl/SQL check and its expected output. Run the check, see it fail/empty, build, run again, see it pass. No code framework involved.

> **Credentials:** `WIALON_TOKEN` is in `local.properties` (gitignored) and the n8n credential store. Never print the full token in committed files or logs. Neon connection string lives only in the n8n Postgres credential.

---

## File touch list

- `provisioning/README.md` — Step 2 (record confirmed C12 flag + JSON path); Step 3 (mark seeding workflow built, document node chain + interim mask + cron swap).
- n8n.cloud — new "HAMS Seeding" workflow (not in repo; documented in README).
- No app code. No new Postgres schema (uses existing `seed_unit` / `units`).

---

## Task 1: C12 spike — confirm the Wialon unique_id flag (read-only)

> **RESOLVED 2026-06-16 (committed f6a3d10).** Confirmed: `core/search_items`
> with **flag 257** (`0x1` base `| 0x100`) returns `items[].uid` = IPS unique id
> (`HAMS_TEST_002/003`) and `items[].nm` = display name. Larger flags
> (4097/8193/1025) omit the `0x100` bit → `uid=''` (not ACL). The steps below are
> the method of record; Tasks 2–6 use flag 257 / `items[].uid` directly.

**Files:**
- Modify: `provisioning/README.md` Step 2 (`CONFIRMED FLAG / PATH` line)

- [ ] **Step 1: Get a session id (eid)**

Run (substitute the real token from `local.properties`):

```bash
curl -s "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=token/login" \
  --data-urlencode 'params={"token":"<WIALON_TOKEN>"}'
```

Expected: JSON containing `"eid":"<32-char-session>"`. Copy the `eid`.
If it returns `{"error":N}` → stop, the token is wrong/expired (check `local.properties`).

- [ ] **Step 2: List units with flag 4097 and look for the unique id**

Run (substitute the eid):

```bash
curl -s "https://hst-api.wialon.eu/wialon/ajax.html" \
  --data-urlencode "svc=core/search_items" \
  --data-urlencode 'params={"spec":{"itemsType":"avl_unit","propName":"sys_name","propValueMask":"*","sortType":"sys_name"},"force":1,"flags":4097,"from":0,"to":0}' \
  --data-urlencode "sid=<EID>"
```

Expected: JSON with `"items":[...]`. Inspect whether any item field carries the
IPS unique id for `TEST_HAMS_APP_002` / `TEST_HAMS_APP_003` (look at `uid`, `nm`,
and any `pflds`/`hw` block). Record which field holds the IPS unique id.

- [ ] **Step 3: If 4097 does not expose the unique id, retry with 8193 then 1025**

Re-run Step 2's call with `"flags":8193`, then `"flags":1025`, until the IPS
unique id appears. Note: `nm` is the display name (e.g. `TEST_HAMS_APP_002`),
not necessarily the IPS unique id (`HAMS_TEST_002`) — confirm which field maps
to the value `claim_unit` must return.

Expected: exactly one flag value yields a field equal to the device IPS unique id.

- [ ] **Step 4: Record the result in the runbook**

Edit `provisioning/README.md` Step 2, filling the line:

```
CONFIRMED FLAG: <value>   PATH: items[].<field>   NAME_PATH: items[].nm
```

- [ ] **Step 5: Commit**

```bash
git add provisioning/README.md
git commit -m "docs(provisioning): record C12 Wialon flag + unique_id path"
```

**Stop condition:** if no flag exposes the IPS unique id, do NOT proceed to Task 2.
Report — the field mapping assumption is wrong and the spec must be revisited.

---

## Task 2: Create the n8n seeding workflow skeleton (login + list, no DB write)

**Files:**
- n8n.cloud — new workflow "HAMS Seeding"

- [ ] **Step 1: Create the workflow with a Manual Trigger**

In n8n.cloud: New Workflow → name "HAMS Seeding" → add **Manual Trigger** node.

- [ ] **Step 2: Add the login HTTP Request node**

Add **HTTP Request** node "Wialon Login":
- Method: GET
- URL: `https://hst-api.wialon.eu/wialon/ajax.html`
- Query params: `svc=token/login`, `params={"token":"<WIALON_TOKEN>"}`
  (store the token in an n8n credential / expression, not inline plaintext where avoidable)

Connect Manual Trigger → Wialon Login.

- [ ] **Step 3: Add the search_items HTTP Request node**

Add **HTTP Request** node "Wialon Search":
- Method: GET
- URL: `https://hst-api.wialon.eu/wialon/ajax.html`
- Query params:
  - `svc=core/search_items`
  - `params={"spec":{"itemsType":"avl_unit","propName":"sys_name","propValueMask":"*","sortType":"sys_name"},"force":1,"flags":257,"from":0,"to":0}`  // flag 257 = base|0x100, confirmed Task 1
  - `sid={{ $json.eid }}` (referencing the login node's output)

Connect Wialon Login → Wialon Search.

- [ ] **Step 4: Execute the workflow and verify the unit list returns**

Click **Execute Workflow** (manual run).
Expected: Wialon Search output contains `items[]` with the test units. If `sid`
is rejected (`{"error":1}`), confirm the `eid` expression path matches the login
node's actual output key.

- [ ] **Step 5: Save the workflow**

Save (do NOT Publish yet — no production trigger needed for a manual workflow).

---

## Task 3: Add Split + interim name-mask Filter

**Files:**
- n8n.cloud — "HAMS Seeding" workflow

- [ ] **Step 1: Add a Split Out node**

Add **Split Out** node "Split Units":
- Field to split out: `items`

Connect Wialon Search → Split Units. Each output item is now one unit object.

- [ ] **Step 2: Add the interim name-mask Filter node**

Add **Filter** node "HAMS-ready mask":
- Condition (BOTH must hold — combine with AND):
  1. name mask: the unit name field (`nm`, per Task 1) starts with `HAMS_` OR
     `TEST_HAMS_APP_`.
     Expression: `{{ /^(HAMS_|TEST_HAMS_APP_)/.test($json.nm) }}`
  2. nonblank uid guard (P1): the unique id is present and not blank, so a
     mis-configured unit can never seed a blank `unique_id` (which Postgres
     would accept as a TEXT primary key).
     Expression: `{{ String($json.uid || '').trim() !== '' }}`

Connect Split Units → HAMS-ready mask.

> **Swap point:** this Filter is the ONLY node that changes when vendor B7
> delivers the real HAMS-ready group — replace the name regex with a group-id
> check (or scope `search_items` to the group). Document this in README Step 3.

- [ ] **Step 3: Execute and verify the filter keeps only the test units**

Click **Execute Workflow**.
Expected: the Filter's "kept" branch outputs only `TEST_HAMS_APP_002` /
`TEST_HAMS_APP_003` (plus any other HAMS_*-named units), and drops everything else.

- [ ] **Step 4: Save the workflow**

---

## Task 4: Wire the Postgres seed_unit UPSERT

**Files:**
- n8n.cloud — "HAMS Seeding" workflow

- [ ] **Step 1: Confirm the Neon Postgres credential exists**

In n8n credentials, confirm the existing Neon credential (used by the claim
webhook) is selectable. Reuse it — do not create a second one.

- [ ] **Step 2: Add the Postgres seed node**

Add **Postgres** node "Seed Unit":
- Operation: Execute Query
- Query: `SELECT seed_unit($1, $2);`
- Parameters:
  - `$1` = `{{ $json.uid }}`   // IPS unique id, confirmed Task 1
  - `$2` = `{{ $json.nm }}`
- Credential: the existing Neon credential

Connect HAMS-ready mask (kept branch) → Seed Unit.

- [ ] **Step 3: Execute the workflow (first seeding run)**

Click **Execute Workflow**.
Expected: Seed Unit runs once per kept item, no error.

- [ ] **Step 4: Verify the units landed in Postgres**

Run against Neon (psql or n8n scratch query):

```sql
SELECT unique_id, name, claimed, device_fingerprint FROM units ORDER BY unique_id;
```

Expected: `HAMS_TEST_002` / `HAMS_TEST_003` present with `claimed` reflecting
their real current state (NOT reset to false if already claimed by a device).

- [ ] **Step 5: Save the workflow**

---

## Task 5: Verify idempotency and new-unit pickup

**Files:** none (verification only)

- [ ] **Step 1: Capture current pool state**

```sql
SELECT count(*) AS free FROM units WHERE claimed=false AND status='active';
SELECT unique_id, claimed, device_fingerprint FROM units ORDER BY unique_id;
```

Note the free count and any claimed rows.

- [ ] **Step 2: Re-run the workflow immediately**

Click **Execute Workflow** again.

- [ ] **Step 3: Verify nothing changed for existing rows**

Re-run Step 1's queries.
Expected: identical free count, no duplicate `unique_id` rows, and any
previously claimed row keeps its `claimed=true` + `device_fingerprint`
(confirms `seed_unit` only refreshes `name`).

- [ ] **Step 4: Add a fresh unit in Wialon and confirm pickup**

In Wialon, ensure a unit named to match the mask (e.g. `HAMS_TEST_004`) exists.
Re-run the workflow.
Expected: the new unit appears in `units` with `claimed=false`. (If a real new
unit can't be created during testing, this step may be deferred to vendor onboarding.)

- [ ] **Step 5: Negative test — broken token stops the run safely**

Temporarily set an invalid token in the Login node, Execute, observe the
workflow errors at Login with no DB write, then restore the valid token.
Expected: `units` unchanged after the failed run.

---

## Task 6: Document the built workflow in the runbook

**Files:**
- Modify: `provisioning/README.md` Step 3

- [ ] **Step 1: Update Step 3 from DEFERRED to BUILT**

Replace the Step 3 "DEFERRED" block with the actual node chain:

```
Manual Trigger → Wialon Login (token/login) → Wialon Search (core/search_items, flags=257)
  → Split Units (items) → HAMS-ready mask (name regex, INTERIM) → Seed Unit (SELECT seed_unit($1,$2))
```

Document: idempotent (UPSERT, never clobbers claimed rows); manual trigger for
MVP; the Filter is the single swap point for the vendor HAMS-ready group; cron
is added later by inserting a Schedule trigger node with no other change.

- [ ] **Step 2: Commit**

```bash
git add provisioning/README.md
git commit -m "docs(provisioning): document built seeding workflow + swap points"
```

---

## Self-Review

- **Spec coverage:** Unit 1 (C12 spike) → Task 1. Unit 2 (n8n workflow: login/search/split/filter/upsert) → Tasks 2–4. Idempotency + new-unit + negative testing → Task 5. Verification queries → Tasks 4–5. Runbook documentation + swap points → Tasks 1, 6. Deferred cron/sweep/real-group → documented, not built. All spec sections covered.
- **Placeholder scan:** the only intentional blank is `<C12 from Task 1>` / `<unique_id field from Task 1>`, which Task 1 resolves and every later task references explicitly — not a TODO, a data dependency.
- **Consistency:** `seed_unit($1,$2)` signature matches `provisioning/sql/003_seed_unit.sql`. `units` columns (`unique_id, name, claimed, device_fingerprint, status`) match `001_units.sql`. Name field `nm` and unique-id field are pinned in Task 1 and reused verbatim.
