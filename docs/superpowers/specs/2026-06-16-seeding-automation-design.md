# HAMS Provisioning — Seeding Automation Design (Wialon → Postgres)

> Status: APPROVED 2026-06-16. Backend half of fleet-scale device provisioning.
> Owner: WYH. Builds on `docs/HAMS_PROVISIONING_FINDINGS.md` and
> `provisioning/README.md` (claim webhook already LIVE & proven).

## Problem

The claim webhook hands one free Wialon unit to each launching device, but the
`units` registry it draws from is populated by hand (`SELECT seed_unit(...)`).
This does not scale to a fleet. Seeding must be automated: pull unit IDs from
Wialon (the source of truth) into Postgres on demand, idempotently.

Cut data still goes phone → IPS → Wialon directly. n8n is only in the
provisioning path, never the data path.

## Scope

IN:
- A one-off read-only REST spike (C12) to discover the Wialon flag that returns
  the IPS `unique_id` and its JSON path.
- An n8n workflow that pulls units from Wialon and UPSERTs them into Postgres
  via the existing `seed_unit(unique_id, name)` function.
- Manual trigger for the workflow (verify each run by hand).
- Interim name-mask scoping (test units now; real units later).
- Verification queries to confirm each run's effect.

OUT (deferred, documented as swap-ins):
- Cron/scheduled trigger.
- Stale-claim sweep (orphan reclaim is office-admin per findings §6).
- The real "HAMS-ready" Wialon unit-group filter (vendor item B7 not ready).

## Architecture

Three independent, independently verifiable units.

### Unit 1 — C12 spike (read-only probe)

A one-off REST sequence, run manually with curl (or an n8n scratch run):

1. `POST token/login` with `WIALON_TOKEN` → capture `eid` (session id).
2. `POST core/search_items` for `itemsType=avl_unit`, trying candidate `flags`
   values (4097, then 8193, then 1025) until the response contains the IPS
   unique id for the known test units (`HAMS_TEST_002` / `HAMS_TEST_003`).

Output: the working `flags` value and the JSON path to the unique id (e.g.
`items[].uid`). Recorded in `provisioning/README.md` Step 2
(`CONFIRMED FLAG / PATH`).

This is a hard dependency. The seeding workflow cannot map Wialon JSON →
`seed_unit(unique_id, name)` until the path is known. No DB or n8n change in
this unit.

Failure mode: if no candidate flag returns the unique id, stop and report —
the field-name assumptions in the runbook are wrong and the design needs
revisiting before Unit 2.

### Unit 2 — n8n seeding workflow

A new n8n workflow, separate from the claim webhook. Node chain:

```
Manual Trigger
  → HTTP Request: token/login (params={"token": WIALON_TOKEN})        // get eid
  → HTTP Request: core/search_items (flags=<C12>, sid=<eid>)          // list units
  → Split Out: items[]                                                // one item per row
  → Filter: name matches interim mask (HAMS_* / TEST_HAMS_APP_*)      // SWAP POINT
  → Postgres: SELECT seed_unit($unique_id, $name)                     // UPSERT
```

Properties:
- **Idempotent.** `seed_unit` is an UPSERT (insert or no-op on conflict); it
  never clobbers a claimed row's `claimed` / `device_fingerprint`. Re-runs add
  only genuinely new units.
- **Single swap point.** The Filter node is the only thing that changes when the
  vendor delivers the HAMS-ready group: replace the name mask with a group-id
  filter (or scope `search_items` to the group). Nothing else moves.
- **Manual trigger** for MVP. Cron is a later swap (add a Schedule trigger node;
  no other change).
- Secret and DB connection live only in the n8n credentials store, never in the
  repo.

### Unit 3 — Verification queries

Run after each workflow execution (already in `provisioning/README.md`):

```sql
SELECT count(*) FROM units WHERE claimed=false AND status='active';   -- free pool
SELECT unique_id, claimed, device_fingerprint, last_seen
  FROM units ORDER BY unique_id;                                      -- full state
```

## Data flow

```
Wialon (source of truth for units)
   │  Unit 1 finds the field; Unit 2 pulls on manual trigger
   ▼
Postgres units (claim pool)  ──claim_unit()──▶  device gets one unique_id
   ▲
   │ (phone never touches this path)
Phone ── cut data ──▶ IPS 185.213.1.24:20332 ──▶ Wialon
```

## Error handling

| Failure | Behaviour |
|---|---|
| `token/login` fails / bad token | Workflow stops at login node; no DB write. |
| `search_items` returns empty | Split yields zero rows; `seed_unit` never runs; no-op. |
| Item missing unique id / name | Filtered out before `seed_unit`; logged. |
| Partial pull (network drop mid-run) | Safe — UPSERT is idempotent; re-run completes the set. |
| Postgres unreachable | Workflow errors on the Postgres node; nothing partially committed beyond per-item UPSERTs already done (each is independent and safe). |

## Testing

1. Run the workflow against current Wialon units → verify the two test units
   land in `units` (Unit 3 queries).
2. Re-run immediately → verify idempotency: free count unchanged, no duplicate
   rows, any claimed row keeps its `claimed` / `device_fingerprint`.
3. Add a fresh unit in Wialon (matching the interim mask) → re-run → confirm it
   appears `claimed=false`.
4. Negative: temporarily break the token → confirm the workflow stops with no
   DB change.

## Dependencies / blockers

- **C12 (Unit 1):** the confirmed `search_items` flag + JSON path. Resolved by
  running Unit 1. Hard gate for Unit 2.
- **WIALON_TOKEN:** already in `local.properties` and the n8n credential.
- **B7 (vendor):** the real HAMS-ready group. Not a blocker for the interim
  name-mask build; only for the final Filter swap.

## File touch list

- `provisioning/README.md` — fill Step 2 (C12 flag/path); mark Step 3 built.
- n8n.cloud — new seeding workflow (not in repo; documented in README).
- No app code change. No new Postgres schema (uses existing `seed_unit`).
