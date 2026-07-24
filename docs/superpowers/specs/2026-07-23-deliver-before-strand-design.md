# Deliver-Before-Strand — Design

**Date:** 2026-07-23
**Status:** SHIPPED 2026-07-24 (Approach A). Implemented per `docs/superpowers/plans/2026-07-23-deliver-before-strand.md`, hardened for review findings P1a/P1b/P2, device-verified DV1 (clean `304` delivery) + DV2 (gateway-miss `302` + local strand) on `ALI-NX1`.
**Mode:** Office-hours design output → eng-review → implementation.
**Scope:** Android app only — the device-initiated release path. No backend, no n8n, no schema change.
**Depends on:** `feat/302-work-stranded` (the `302`/`304` marker and the unconditional strand must already be in place — they are, 20 commits, V1–V6 verified on `ALI-NX1` 2026-07-23).

---

## 1. The problem, in one sentence

At release, the phone destroys cuts it could have delivered, and the only thing deciding
which cuts survive is background-job timing the operator does not control.

## 2. Evidence — the two live tests, 2026-07-23

Same action, opposite outcome, network the only variable:

| Test | What the worker did | Wi-Fi | Result |
|---|---|---|---|
| 1 | 3 cuts on screen, unsaved, then released | **on** | `304 lost_cuts=0` — `PushWorker` had already delivered all cuts to `HAMS_TEST_004` |
| 2 | 3 cuts on screen, unsaved, then released | **off** | `302 lost_cuts=3` — cuts destroyed |

The operator's mental model was "saved vs unsaved task decides it." It does not. **Delivery state
at the instant of release decides it** — specifically, how many `179` rows are still `pushed = 0`.
That number is set by whether the background `PushWorker` happened to win a race, which nobody
watching the phone can see or predict.

## 3. Root cause

`ProvisioningEvents.flushAndRelease` runs four steps (`ProvisioningEvents.kt:154`):

```
finalize active task  →  count unsent  →  push 302/304 marker  →  strand
```

**It never attempts to push the cuts themselves.** `recordAndPushRelease` drains the *diagnostics*
table (the marker), not the *events* table (the harvest). So the cuts are only ever delivered by the
asynchronous `PushWorker`, which needs a real route to `185.213.1.24` and runs on WorkManager's
schedule — not synchronously with the release.

The four gaps the operator sensed collapse to two roots:

- **Roots 1 + 3 (timing-dependence + destroy-while-online):** the mechanism keys on a delivery race,
  not on operator intent. A worker on good Wi-Fi can still lose harvest if they release before the
  worker catches up. This is the real defect.
- **Roots 2 + 4 (reporting honesty):** `lost_tasks` counts beacon-only tasks; a race can mark an
  all-uploaded task `failed`. These mislead a reader but move no data wrong. Secondary.

## 4. Premises (agreed)

1. The branch is already **safe** — nothing misfiles onto the wrong worker. The remaining loss is
   *destruction of deliverable data*, not mis-attribution. ✅
2. An OTP release is **inherently online** — it just reached the n8n webhook. So at release time a
   push to Wialon can, in the normal case, land. ✅ (`ProvisioningEvents.kt:10-13` already states this
   for the marker; the cuts are the same socket.)
3. The fix is **ordering, not new machinery** — `PushEngine` + `WialonIPSClient` already deliver cuts
   over IPS, and `runReleaseSequence` already exposes an injectable-step seam. ✅
4. `302` should mean *"the network was genuinely down"*, not *"you released at the wrong second."* ✅

## 5. Chosen approach — A: deliver before strand

Insert a bounded, synchronous cut-delivery step into the release sequence, **before** counting and
stranding, under the unit the phone still holds.

### 5.1 New sequence (hardened after eng review 2026-07-23)

The naive "deliver then count then strand" has three deeper races the eng review + codex outside
voice surfaced. The corrected sequence:

```
[at the call site — AdminSheet / PairingScreen]
  1. finalize active task
  2. SNAPSHOT the pending 179 event ids                    (codex #1)
  3. DELIVER: if !PushWorker.pushInProgress, set the flag,  (codex #2 — no duplicate 179)
        run PushEngine over pending cuts under the CURRENT unit,
        maxAttempts=1, no backoff, keep 75ms pacing,         (issue 1)
        wrapped in withTimeout(DELIVER_BUDGET_MS ~15s);
        clear the flag. If the flag was already set, SKIP
        deliver — the worker is already draining.
  4. client.release()   ← the webhook free happens AFTER deliver,  (codex #3)
        so the phone still owned the unit + held the drain lease
        for the whole deliver window
  5. count = snapshot ids where pushed != 1   (NOT "still pushed=0")  (codex #1)
  6. push 302 (count>0) / 304 (count==0), under the OLD unit id
  7. strand remaining pending rows            (unconditional, unchanged)
  8. store.clear() / navigate
```

Only what genuinely could not be sent reaches the strand. On a working network the deliver step
empties the queue, the count is zero, and the release emits a clean `304` with the harvest safely on
the correct unit. `302` fires only when the gateway was actually unreachable.

**Why each guard exists:**

- **Snapshot + count-not-uploaded (steps 2/5).** `countUnsentCuts` counts `pushed=0 AND event_code=179`
  (`EventDao.kt:71`). But `PushEngine` marks a rejected frame `pushed=2` — so a cut Wialon *refused*
  would drop out of the count and produce a false `304`. Counting snapshot ids where `pushed != 1`
  ("was it uploaded?") instead of `pushed == 0` ("is it still queued?") reports the truth.
- **`pushInProgress` guard (step 3).** The deliver step is a *second* cut-sender alongside the
  background `PushWorker`. Both read `pushed=0` and mark uploaded only after the ack — with no row
  lease, both could send the same `179` and Wialon would double-count it. `PushWorker` already exposes
  `@Volatile pushInProgress` (`PushWorker.kt:307`); the deliver step honours it: skip if set (worker
  is draining), else set-deliver-clear. Duplicate harvest is the same integrity class this branch
  fights, inverted, so this is a **must-fix**.
- **Deliver before `client.release()` (step 4).** `release_unit()` frees the unit and clears the drain
  lease (`005_manual_provision.sql:82`). Delivering *after* that would spend the 15s window under a
  unit the registry marked free, where a replacement device could claim it mid-flush. Delivering
  first — while the phone still owns the unit and the lease still holds — closes that window with no
  backend change.

**The ack-ambiguity caveat (codex #5):** if the deliver step times out *after* Wialon accepted a
frame but *before* the client read `#AD#1`, that row stays `pushed=0`, gets stranded, and is reported
in `lost_cuts` — even though Wialon has it. This over-reports loss (the cut is safe); it never loses
data. It is the unavoidable at-least-once/at-most-once boundary, not a defect. The doc's earlier "worst
case is exactly today" is therefore softened to: **worst case is today's behaviour plus a possible
over-report of loss on an ack-timeout — never a worse data outcome.**

### 5.2 Where it slots in

`runReleaseSequence` (`ProvisioningEvents.kt:111`) already takes its steps as injected suspend
lambdas. Approach A adds **one lambda** between `finalizeActiveTask` and `countUnsentWork`:

```
suspend fun runReleaseSequence(
    finalizeActiveTask:  suspend () -> Unit,
    deliverPendingCuts:  suspend () -> Unit,   // NEW
    countUnsentWork:     suspend () -> UnsentWork,
    pushMarker:          suspend (UnsentWork) -> Boolean,
    strandUnsentWork:    suspend () -> Unit,
): ReleaseOutcome
```

`flushAndRelease` wires `deliverPendingCuts` to a bounded `PushEngine.run()` using
`WialonIPSClient(uniqueId = <current binding>)` — the same sender factory the worker already uses,
but configured `maxAttempts=1` with no backoff (issue 1: there is no retry-later, the strand is final,
and `PushEngine`'s stock 30s first-retry backoff would blow the 15s budget on the first hiccup).

**Call-site ordering (codex #3) breaks the current shape.** Today `AdminSheet`/`PairingScreen` call
`client.release()` *then* `flushAndRelease`. The deliver step must run *before* the release webhook, so
`flushAndRelease` splits: the deliver+snapshot happens first, `client.release()` in the middle, then
marker+strand. That reshuffles the exact ordered region the branch already treats as load-bearing —
same care, same mutation-guard test discipline. Extract a small `buildReleaseDeliveryEngine(app,
uniqueId)` so the deliver step and `PushWorker` (`PushWorker.kt:162`) don't drift their sender wiring
(DRY).
No change to `PushEngine`, `WialonIPSClient`, the frame builder, the DAO layer, or the strand. The
existing `ReleaseSequenceTest` mutation guard (fails if anyone re-wraps the strand in `if (landed)`)
extends naturally to cover the new ordering.

### 5.3 The one bound that matters

The deliver step is a **blocking network round-trip on the release path.** It must be bounded so a
dead gateway cannot hang the admin holding the phone:

- Wrap `deliverPendingCuts` in a `withTimeout` (proposed **15 s**, one `AppConfig` constant).
- On timeout or transport failure: **fall through to the existing behaviour** — count remains
  non-zero, `302` fires, rows strand. So the worst case is exactly today's behaviour, never worse.
  This is what makes A low-regression-risk despite touching the release path.

Because the release already runs inside `app.applicationScope.async { … }.await()` (the Task 7 fix),
the blocking call survives Activity recreation and is properly awaited. No new scope work.

**True admin-wait ceiling + progress UI (issue 2).** A release now runs two serial Wialon sessions:
the new deliver step (bounded 15s) and the pre-existing marker push (`drainTelemetry` opens its own
`WialonIPSClient` with 10s connect + 10s read). Worst case on a dead gateway is ~35s of spinner, not
15s. The marker wait is pre-existing, not a regression — but combined with the deliver step it crosses
into "did the phone freeze?" territory. **The admin sheet shows progress text** — `"Delivering N
cuts…"` during step 3, `"Confirming release…"` during step 6 — reusing the `busy` + `status` surface
the Task 7 fix already added. Two strings, no new structure.

**Large-backlog behaviour, stated plainly (codex #6).** With 75ms pacing, ~180+ pending cuts can
exceed the 15s budget; the deliver step then sends a prefix and the remainder falls through to `302` +
strand. This is acceptable and by design — a backlog that large means the worker never ran, which the
office SOP prevents — but it is a *partial* deliver, and the plan states it rather than hiding it.
Raise `DELIVER_BUDGET_MS` if the field shows real backlogs stranding.

### 5.4 Payload simplification — drop `lost_tasks`, cut `lost_cuts` from 304 (roots 2 + 4)

The marker payload gets simpler at the same time, because deliver-first changes what the numbers are
worth.

**Drop `lost_tasks` entirely, on both `302` and `304`.** It triggers nothing — routing is on
`lost_cuts` alone — and it is the source of the confusing beacon-only case observed live on
2026-07-23 (`304, lost_tasks=1, lost_cuts=0`: a task holding only an unsent heartbeat looked like a
loss but was not). No consumer reads it, and the phone keeps the per-task breakdown locally
(recoverable by a DB pull), so nothing of value leaves the wire.

**Cut `lost_cuts` from `304`.** `304` fires only when `lost_cuts == 0`, so the number is always zero
there — it tells a reader nothing the code does not already guarantee. Emit it **only on `302`**,
where it varies and carries the one fact that matters: how much harvest was lost.

Result — the whole release payload reduces to one code plus, on `302` only, one integer:

```
304                       ← clean release. The code IS the signal: nothing left behind.
302  lost_cuts:1:<N>      ← N cuts left undelivered. N is always > 0.
```

`303` (bind) and `301` (admin-freed flush) already carry neither param; they are unchanged. On the
wire, `304` becomes byte-identical to a plain telemetry frame (`event_code:1:304,battery:2:…,
work_count:1:0`), and `302` appends exactly one param.

This is a pre-fleet simplification: the earlier "both codes carry the counts as a positive assertion"
choice existed to disambiguate old paramless `304`s from new ones during a mixed-version rollout.
There is no deployed fleet yet, so that need does not exist, and the cleaner form wins.

**Gap 4** (a race can mark an all-uploaded task `failed`) is not a payload issue and is narrowed but
not closed by deliver-first: the synchronous drain *before* the strand collapses the window in which
`PushWorker` and `flushAndRelease` fight over the same rows, but a worker run mid-flight could still
mislabel a task's terminal state. It moves no data wrong (over-reports, never under-reports) and
fully closing it is a separate, smaller fix, out of scope here.

Code touch for the payload change: `recordAndPushRelease` (`ProvisioningEvents.kt:64`) stops passing
`lostTasks`, and passes `lostCuts` only when the type is `WORK_STRANDED`; `IPSFrameBuilder`'s
telemetry frame already appends each `lost_*` param only when non-null, so passing `null` for both on
a `304` makes it drop off with no builder change. `countUnsentWork` may keep returning both numbers
for the strand's own use — only what is *passed to the marker* changes. The dictionary's `302`/`304`
entries update to match (v1.6).

## 6. What A changes for the worker and the office

| | Today | After A |
|---|---|---|
| Release on good Wi-Fi, cuts pending | cuts destroyed if worker hasn't drained yet | cuts delivered, clean `304` |
| Release with no network | `302`, cuts destroyed | `302`, cuts destroyed (unchanged — genuinely unavoidable) |
| Meaning of a `302` in Wialon | "released before the push finished" (noise) | "the gateway was down" (signal) |
| Release payload | `302`/`304` both carry `lost_tasks` + `lost_cuts` | `304` carries nothing; `302` carries `lost_cuts` only |
| Admin wait at release | instant | up to ~15 s on a slow link; instant when queue is empty |

## 7. Decisions locked in eng review (2026-07-23)

All open questions resolved during `/plan-eng-review`:

1. **Deliver-step config** → `PushEngine(maxAttempts=1, no backoff, keep 75ms pacing)` in
   `withTimeout(~15s)`, fall through to strand. (issue 1 / codex #4)
2. **Admin-wait UX** → progress text in the sheet (`"Delivering N cuts…"` / `"Confirming release…"`);
   true ceiling ~35s across both Wialon sessions. (issue 2)
3. **Duplicate-harvest race** → serialize against `PushWorker.pushInProgress`; skip deliver if the
   worker is already draining. Must-fix. (codex #2)
4. **Free-vs-deliver order** → deliver *before* `client.release()`, while the phone still owns the unit
   and the drain lease holds. No backend change. (codex #3)
5. **Count basis** → snapshot pending `179` ids before deliver, count those with `pushed != 1` after,
   not "still `pushed=0`" — so a `PushEngine`-rejected cut can't produce a false `304`. (codex #1)
6. **Payload** → drop `lost_tasks` on the wire, `304` carries nothing, `302` carries `lost_cuts` only.
   Folded into this plan (not split). (§5.4, user decision)
7. **Ack-ambiguity** → over-reports loss on a timeout-after-accept; the doc's "worst case is exactly
   today" softened accordingly. Not a defect. (codex #5)

Remaining judgement call for the implementer (low stakes):

- **Count-screen lock during release** (codex #7) — verify whether the modal `AdminSheet` already
  blocks `+` presses on the count screen behind it. If it does, no change. If a press can still land
  during the ~35s release, lock the count buttons while `busy` — a press mid-release lands in the
  snapshot's window and would be stranded-but-late. Confirm modality before adding a lock.

## 8. Out of scope

- **Approach B — unit-stamped per-task push** (group pending events by `tasks.device_id`, `PushWorker`
  logs in per-unit). The structural endgame: cuts follow their recorded unit on *every* push, not just
  release, making both misfiling and needless destruction impossible everywhere. Larger rewrite of the
  push-core sender factory (human ~3-4 days). Most of its *release-path* value is captured by A on top
  of the unconditional strand, which is why A goes first. **Recommended as the documented next phase**,
  not abandoned.
- Blocking the release when work is unsent (spec §5.2) — A makes it unnecessary on a working network.
- **The admin-console force-release path** (`admin_release()`, `301`/`bound_other`). Covered
  procedurally by the office-only SOP (`HAMS_PROVISIONING_FINDINGS.md §6`): resets happen at the
  office, on Wi-Fi, so cuts flush before the unit is freed. The residual (a device returned
  battery-dead and its unit reused before it flushes) is closed by one office-SOP line — *"connect the
  returned device, wait for its cuts to finish uploading, then release"* — not code. The SQL hardening
  (`admin_release` drain-lease stamp, `2026-07-10` spec §5.5) is **deliberately not touched** and
  judged **non-critical**: the office-only SOP removes the autonomous-field-claim precondition the
  `bound_other` race needs, and the unconditional strand already downgrades its worst case from
  mis-attribution to loss. `provisioning_events` audit stays a forensic nice-to-have for HQ, gated on
  company Postgres.
- Force-stop / crash / flat battery — no code runs; nothing on the phone can cover it.

## 9. The assignment

Before the implementation plan, run **V7** on `ALI-NX1` — the one device check still outstanding on
`feat/302-work-stranded`: airplane mode (n8n reachable over `adb reverse`, Wialon unreachable), record
3 cuts, release. Confirm the `302` row and cuts land at `pushed = 2` regardless of the failed marker.

That test establishes the exact failure this design's deliver step is meant to *avoid* triggering — it
is the baseline you measure A against. Once A ships, the same airplane-mode sequence should still
produce `302` (network genuinely down), but a **Wi-Fi-on** release that today produces `302` should
instead produce a clean `304` with the cuts delivered. That before/after pair is the acceptance test
for Approach A.

---

*Written 2026-07-23 by WYH via office-hours; hardened via /plan-eng-review the same day (2 architecture
findings + codex outside voice: 3 races closed, all decisions locked in §7). Approach A approved in
session. Supersedes nothing; extends `docs/superpowers/plans/2026-07-23-work-stranded-302.md`.*

---

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 1 | issues_found | 8 raised, 5 folded, 1 tension resolved, 2 confirmed issue 1 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | clean | 2 arch issues (both resolved) + 3 codex races (all closed) + 2 test regressions (mandatory) |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**CODEX:** outside voice found 2 races the section review missed — duplicate `179` via concurrent
`PushWorker` (serialized via `pushInProgress`), and unit-freed-before-deliver (reorder deliver ahead of
`client.release()`). Both folded. It independently confirmed issue 1 (timeout vs 30s backoff).

**CROSS-MODEL:** one tension — payload cleanup as scope creep. User kept it folded in; rationale
recorded (§5.4, pre-fleet, disjoint code from the race fixes).

**VERDICT:** ENG CLEARED — Approach A locked, all 7 decisions resolved in §7, deliver-step + payload
tests specified (incl. 2 mandatory regressions). Ready to hand to `writing-plans`. No live cut path,
push engine core, or backend/SQL touched.

NO UNRESOLVED DECISIONS
