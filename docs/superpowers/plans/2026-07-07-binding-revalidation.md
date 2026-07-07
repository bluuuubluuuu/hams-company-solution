# Binding Revalidation & Revocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the HAMS app re-check its Wialon-unit binding against the n8n/Postgres registry (at launch, before every push, and every ~15 min) so an admin-side release/reassign forces the phone to flush its data, log a diagnostic, and drop back to the pairing screen — with no double-push and no silent data loss.

**Architecture:** A new read-only Postgres function `check_binding` (already written) is exposed by a thin n8n `verify` webhook (already written). The app calls it via `ProvisioningClient.verify`. A `BindingRevalidator` centralises the decision. The single dangerous action — pushing to Wialon — is gated inside `PushWorker`: on `released` the worker flushes pending cuts + a `301` marker *before* logging out; on `bound_other` it records a local-only `302` and logs out immediately without pushing. Launch and a periodic worker run the same check but defer any flush to `PushWorker`. Provisioning state becomes observable via a `StateFlow` so the open app reacts live.

**Tech Stack:** Kotlin, Jetpack Compose, Room, WorkManager, `HttpURLConnection`, plpgsql, n8n. Pure-JVM unit tests (JUnit) for parsers and decision logic.

## Global Constraints

- Package root: `com.klk.hams`. Build via `.\gradlew.bat` (PowerShell) — Kotlin DSL, Version Catalog.
- Outbound Wialon `event_code` policy (`docs/HAMS_EVENT_CODE_DICTIONARY.md`): task path stays `179/180/35`; diagnostics telemetry is the Option B set plus the **new** provisioning family `301`/`302`. `301` pushes; `302` is **local-only, never sent**.
- New codes live in the unused `3xx` band: **301 `binding_released`**, **302 `binding_taken`**. Do not reuse any existing value (`24–44`, `35`, `179`, `180`, `279–293`).
- Revalidation golden rule: **self-unprovision ONLY on an explicit `released`/`bound_other`.** Never on `not_found`, `bad_request`, non-200, or network failure (a dead tunnel must never log every phone out).
- Data-loss rule: on `released`, pending cuts (`179/180/35`) are flushed to Wialon **before** the binding is cleared; logout happens only after a fully-successful flush. On `bound_other`, nothing is pushed (would pollute the new owner's unit) — the backlog stays local.
- Revocation banner copy (verbatim): `This device was unlinked by an administrator. Enter a new supervisor code to reconnect.`
- Secrets stay in `local.properties` (gitignored); expose to Kotlin via `buildConfigField`. Never read `local.properties` at runtime.
- The `verify` webhook carries **no OTP** — it is guarded only by the `x-hams-key` header (it is an automatic, device-initiated call).

---

## Amendments (post-Codex-review, 2026-07-07 — AUTHORITATIVE)

These override the base tasks below where they conflict. Apply them.

**A1 (P0) — Revoke only on confirmed 301 delivery.** `TelemetryPushEngine.run()` returns `PushState.Success(0)` even when login/transport aborts with rows still pending (`TelemetryPushEngine.kt:30,49,61`). So `telemetryState is Success` does NOT prove the 301 went out. Instead:
- Task 4: change `recordDiagnostic` to **return the inserted row id** (`return diagnosticDao.insert(...)`, return type `Long`). Add to `DiagnosticDao`:
  ```kotlin
  @Query("SELECT pushed FROM diagnostics WHERE id = :id")
  suspend fun pushedState(id: Long): Int?
  ```
  and a repo passthrough in `TaskRepository`:
  ```kotlin
  suspend fun diagnosticPushedState(id: Long): Int? = diagnosticDao.pushedState(id)
  ```
- Task 6: `recordBinding(type, pushed): Long` returns the row id. Add a pure, unit-tested gate to `BindingRevalidator` companion:
  ```kotlin
  /** Revoke after a released-flush ONLY when the 301 row is confirmed pushed. */
  fun shouldRevokeAfterFlush(releasedFlush: Boolean, row301Pushed: Int?): Boolean =
      releasedFlush && row301Pushed == 1
  ```
- Task 7: capture `val releasedRowId: Long?` when recording the 301; after the drain, revoke via `if (BindingRevalidator.shouldRevokeAfterFlush(releasedFlush, releasedRowId?.let { app.repository.diagnosticPushedState(it) })) app.bindingRevalidator.revoke(...)`. Do **not** gate on `telemetryState`.
- Add tests to `BindingRevalidatorTest`: `shouldRevokeAfterFlush(true, 1) == true`; `(true, 0) == false`; `(true, null) == false`; `(false, 1) == false`.

**A2 (P1) — Single 301 writer.** In Task 6 `revalidate()`, the `RELEASED_FLUSH` branch must **NOT** record a 301. It only calls `app.pushController.enqueueAuto()`. `PushWorker` is the sole recorder of the pushable 301 (Task 7). Prevents duplicate rows.

**A3 (P1) — Finalize the active task before a released flush.** Add to `TaskRepository` (mirror `rolloverActiveTaskIfStale`):
```kotlin
/** Finalize the in-progress task so its counted cuts become pushable before a
 *  release logout. netCount>0 -> pending (will flush); else discarded. */
suspend fun finalizeActiveTaskForRelease(): Long? = db.withTransaction {
    val active = taskDao.getActiveTask() ?: return@withTransaction null
    val now = clock.nowUtcIso()
    val status = if (active.netCount > 0) "pending" else "discarded"
    taskDao.finalizeTask(active.id, status, now, "auto_released", now)
    active.id
}
```
In Task 7, the `RELEASED_FLUSH` branch calls `app.repository.finalizeActiveTaskForRelease()` **inside the binding-check block, before `pendingTaskIdsBefore` is snapshotted** (so the finalized task is included in the flush). This is what makes "no data loss" real for the actively-counted task.

**A4 (P1) — Periodic worker must not revoke mid-push.** Add to `PushWorker` companion `@Volatile var pushInProgress: Boolean = false`; set `true` right after the provisioned check and `false` in a `finally` around the whole drain. In `BindingCheckWorker.doWork` (Task 8), return `Result.success()` early if `PushWorker.pushInProgress`. (Note: `provisionedId` is already captured once at `PushWorker.kt:61`, so an in-flight sender never misroutes — this guard covers the store-mutation ordering only.)

**A5 (P2) — Typo.** Task 7 Step 1 must read `ProvisioningClient().verify(uid, fp)` (no space).

**A6 (P2) — Durable logout.** In `ProvisioningStore`, add to the `KeyValueStore` interface `fun removeBlocking(key: String)`; the `SharedPrefsKeyValueStore` impl uses `prefs.edit().remove(key).commit()`. Add `fun clearBlocking() { store.removeBlocking(KEY_UNIQUE_ID) }`. `BindingRevalidator.revoke` calls `store.clearBlocking()` (not `clear()`), so a logout survives immediate process death.

**A7 (P0) — Backend drain-lease (Option A).** Protects the flush window from a concurrent claim. Already written into the SQL:
- `provisioning/sql/007_drain_lease.sql` — adds `units.drain_until`, `units.drain_fingerprint`.
- `006_check_binding.sql` — `released` branch stamps a 5-min lease for the caller.
- `005_manual_provision.sql` — `manual_claim` Guard C refuses a leased unit (status `draining`) unless the claimer is the drainer, and clears the lease on a successful claim; `release_unit` clears it too.
- **n8n `manual-claim.json` code node** — add one mapping line during import: `else if (s==='draining') { code=409; body={error:s}; }`.
- App: Task 5b (below) adds `BindResult.Draining` handling so a new phone pairing a still-draining unit gets a clear "try again shortly" message.

### Task 5b: Handle the `draining` claim response

**Files:** `ProvisioningResult.kt`, `ProvisioningClient.kt`, `PairingScreen.kt`, `app/src/test/java/com/klk/hams/provisioning/ManualClaimParserTest.kt` (extend if present, else create).

**Interfaces:** Produces `BindResult.Draining`; `parseManualClaimResponse(409, {"error":"draining"}) == BindResult.Draining`.

- [ ] Add to `ProvisioningResult.kt` `BindResult`: `data object Draining : BindResult`.
- [ ] In `parseManualClaimResponse`, extend the `409` branch: `409 -> when (jsonStringField(body, "error")) { "already_bound" -> BindResult.AlreadyBound; "draining" -> BindResult.Draining; else -> BindResult.FingerprintInUse(jsonStringField(body, "on")) }`.
- [ ] Add a parser test: `assertEquals(BindResult.Draining, parseManualClaimResponse(409, """{"error":"draining"}"""))`.
- [ ] In `PairingScreen`, map `BindResult.Draining` to the error text: `This unit is finishing release from another device. Try again in a few minutes.`
- [ ] Run `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.*"` → PASS; commit `feat(provisioning): handle draining claim response (drain-lease)`.

**Task 1 additions:** also `psql -f provisioning/sql/007_drain_lease.sql` (before re-applying 005/006), re-apply the edited `005`/`006`, and add the `draining` line to the `manual-claim` code node in n8n before Publish.

---

## File Structure

**Backend (already written — deploy + verify only):**
- `provisioning/sql/006_check_binding.sql` — `check_binding(...)`; `released` stamps the drain lease.
- `provisioning/sql/007_drain_lease.sql` — `units.drain_until` / `drain_fingerprint` columns.
- `provisioning/sql/005_manual_provision.sql` — `manual_claim` Guard C (`draining`) + lease clear.
- `provisioning/n8n/workflows/verify.json` — `POST /webhook/verify`, `x-hams-key` guarded.

**Config:**
- `app/build.gradle.kts` — add `VERIFY_URL` buildConfigField.
- `app/src/main/java/com/klk/hams/AppConfig.kt` — add `VERIFY_URL`, `BINDING_CHECK_INTERVAL_MINUTES`.
- `local.properties`, `local.properties.example` — add `VERIFY_URL`.

**Diagnostics vocabulary:**
- `app/src/main/java/com/klk/hams/diagnostics/DiagnosticType.kt` — add `BINDING_RELEASED`, `BINDING_TAKEN`.
- `app/src/main/java/com/klk/hams/push/TelemetryCode.kt` — map `binding_released -> 301`, `binding_taken -> 302`.
- `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` — add `pushed` param to `recordDiagnostic` (local-only support).

**Provisioning client + result:**
- `app/src/main/java/com/klk/hams/provisioning/ProvisioningResult.kt` — add `VerifyResult`.
- `app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt` — add `verify` + `parseVerifyResponse`.

**Revalidation core:**
- `app/src/main/java/com/klk/hams/provisioning/BindingRevalidator.kt` — NEW. Decision + revoke + flush coordination.
- `app/src/main/java/com/klk/hams/HamsApp.kt` — add `provisioningRevocation` StateFlow, `bindingRevalidator`, launch check, periodic worker enqueue.
- `app/src/main/java/com/klk/hams/push/BindingCheckWorker.kt` — NEW. Periodic `CoroutineWorker`.
- `app/src/main/java/com/klk/hams/push/PushWorker.kt` — check-first gate + released-flush + bound_other early-out.

**UI:**
- `app/src/main/java/com/klk/hams/MainActivity.kt` — observe `provisioningRevocation`, route to pairing.
- `app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt` — add `notice` banner param.

**Docs:**
- `docs/HAMS_EVENT_CODE_DICTIONARY.md` — add the `3xx` provisioning family.

**Tests:**
- `app/src/test/java/com/klk/hams/provisioning/VerifyResponseParserTest.kt` — NEW.
- `app/src/test/java/com/klk/hams/provisioning/BindingRevalidatorTest.kt` — NEW.
- `app/src/test/java/com/klk/hams/push/TelemetryCodeBindingTest.kt` — NEW.

---

## Task 1: Deploy & verify the backend

**Files:**
- Deploy: `provisioning/sql/006_check_binding.sql` (already written)
- Deploy: `provisioning/n8n/workflows/verify.json` (already written)

**Interfaces:**
- Produces: a live `POST <tunnel>/webhook/verify` returning `{"bound":<bool>,"status":"bound|released|bound_other|not_found|bad_request"}`.

- [ ] **Step 1: Apply the SQL to Neon**

Run (substitute your `PROV_DB_URL`):
```bash
psql "$PROV_DB_URL" -f provisioning/sql/006_check_binding.sql
```
Expected: `CREATE FUNCTION`.

- [ ] **Step 2: Sanity-check the function directly**

```bash
psql "$PROV_DB_URL" -c "SELECT check_binding('HAMS_TEST_002','wrong-fp');"
```
Expected: a `bound_other` or `released` status (NOT `bound`, since the fingerprint is wrong) — proves the guard logic runs.

- [ ] **Step 3: Import + publish the n8n workflow**

In n8n: import `provisioning/n8n/workflows/verify.json` → open the `If` node, set `x-hams-key` right-value to the real `HAMS_CLAIM_SECRET` → select the `Postgres account` credential on the query node → **Publish**.

- [ ] **Step 4: Verify the webhook over the tunnel**

Run (substitute the live tunnel, secret, and the real paired fingerprint from `adb shell settings get secure android_id`):
```bash
curl -s -X POST <tunnel>/webhook/verify \
  -H "Content-Type: application/json" -H "x-hams-key: <HAMS_CLAIM_SECRET>" \
  -d '{"unique_id":"HAMS_TEST_002","fingerprint":"<real-fp>"}'
```
Expected: `{"bound":true,"status":"bound","unique_id":"HAMS_TEST_002"}`.

- [ ] **Step 5: Verify the auth gate**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST <tunnel>/webhook/verify \
  -H "Content-Type: application/json" -d '{}'
```
Expected: `401`.

- [ ] **Step 6: Commit** (backend files may already be committed; commit if not)

```bash
git add provisioning/sql/006_check_binding.sql provisioning/n8n/workflows/verify.json
git commit -m "feat(provisioning): check_binding fn + verify webhook for app revalidation"
```

---

## Task 2: Wire the VERIFY_URL config

**Files:**
- Modify: `app/build.gradle.kts:51` (after the `RELEASE_URL` line)
- Modify: `app/src/main/java/com/klk/hams/AppConfig.kt`
- Modify: `local.properties`
- Modify: `local.properties.example`

**Interfaces:**
- Produces: `AppConfig.VERIFY_URL: String`, `AppConfig.BINDING_CHECK_INTERVAL_MINUTES: Long`.

- [ ] **Step 1: Add the buildConfigField**

In `app/build.gradle.kts`, immediately after the `RELEASE_URL` line:
```kotlin
        buildConfigField("String", "VERIFY_URL",       javaStringLiteral(prop("VERIFY_URL")))
```

- [ ] **Step 2: Expose it in AppConfig**

In `AppConfig.kt`, after the `RELEASE_URL` line add:
```kotlin
    const val VERIFY_URL: String = BuildConfig.VERIFY_URL
    /** Periodic binding re-check cadence. WorkManager floor is 15 min. */
    const val BINDING_CHECK_INTERVAL_MINUTES: Long = 15
```

- [ ] **Step 3: Add the value to local.properties**

Append to `local.properties` (use the same tunnel host as `MANUAL_CLAIM_URL`):
```
VERIFY_URL=<same-tunnel-base>/webhook/verify
```

- [ ] **Step 4: Document it in local.properties.example**

Append to `local.properties.example`:
```
VERIFY_URL=https://<your-n8n-host>/webhook/verify
```

- [ ] **Step 5: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/klk/hams/AppConfig.kt local.properties.example
git commit -m "feat(config): add VERIFY_URL + binding-check interval"
```

---

## Task 3: Add the 301/302 diagnostic vocabulary

**Files:**
- Modify: `app/src/main/java/com/klk/hams/diagnostics/DiagnosticType.kt:15`
- Modify: `app/src/main/java/com/klk/hams/push/TelemetryCode.kt:20`
- Test: `app/src/test/java/com/klk/hams/push/TelemetryCodeBindingTest.kt`

**Interfaces:**
- Produces: `DiagnosticType.BINDING_RELEASED` (wire `"binding_released"`), `DiagnosticType.BINDING_TAKEN` (wire `"binding_taken"`); `TelemetryCode.eventCodeFor("binding_released") == 301`, `... ("binding_taken") == 302`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/klk/hams/push/TelemetryCodeBindingTest.kt`:
```kotlin
package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryCodeBindingTest {
    @Test fun binding_released_maps_to_301() {
        assertEquals(301, TelemetryCode.eventCodeFor("binding_released"))
    }

    @Test fun binding_taken_maps_to_302() {
        assertEquals(302, TelemetryCode.eventCodeFor("binding_taken"))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.TelemetryCodeBindingTest"`
Expected: FAIL — both return `null`.

- [ ] **Step 3: Add the enum constants**

In `DiagnosticType.kt`, change the `GPS_RECOVERY("gps_recovery");` line to end with a comma and append the two new constants:
```kotlin
    GPS_RECOVERY("gps_recovery"),
    BINDING_RELEASED("binding_released"),
    BINDING_TAKEN("binding_taken");
```

- [ ] **Step 4: Add the code mappings**

In `TelemetryCode.kt`, inside `TABLE`, after the `"power_disconnected" to 44,` line:
```kotlin
        "binding_released" to 301,
        "binding_taken" to 302,
```

- [ ] **Step 5: Run the test and confirm it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.TelemetryCodeBindingTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/diagnostics/DiagnosticType.kt app/src/main/java/com/klk/hams/push/TelemetryCode.kt app/src/test/java/com/klk/hams/push/TelemetryCodeBindingTest.kt
git commit -m "feat(diagnostics): add 301 binding_released / 302 binding_taken codes"
```

---

## Task 4: Local-only support in recordDiagnostic

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt:37-58`

**Interfaces:**
- Consumes: nothing new.
- Produces: `recordDiagnostic(type, batteryPct, snapshot, timestampIso, pushed = 0)` — a `pushed = 1` row is never selected by `DiagnosticDao.pending()` so it stays local-only.

- [ ] **Step 1: Add the `pushed` parameter**

In `TaskRepository.kt`, change the `recordDiagnostic` signature and the entity's `pushed` field:
```kotlin
    suspend fun recordDiagnostic(
        type: com.klk.hams.diagnostics.DiagnosticType,
        batteryPct: Double?,
        snapshot: LocationSnapshot? = null,
        timestampIso: String? = null,
        pushed: Int = 0,
    ) {
        val now = clock.nowUtcIso()
        diagnosticDao.insert(
            com.klk.hams.data.model.DiagnosticEntity(
                type = type.wire,
                timestamp = timestampIso ?: now,
                batteryPct = batteryPct,
                createdAt = now,
                pushed = pushed,
                latDecimal = snapshot?.latDecimal,
                lonDecimal = snapshot?.lonDecimal,
                hdop = snapshot?.hdop,
                satellites = snapshot?.satellites,
                speedKmh = snapshot?.speedKmh,
            )
        )
    }
```

- [ ] **Step 2: Verify it compiles (default param keeps all existing callers valid)**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt
git commit -m "feat(diagnostics): recordDiagnostic accepts pushed flag for local-only rows"
```

---

## Task 5: VerifyResult type + verify parser

**Files:**
- Modify: `app/src/main/java/com/klk/hams/provisioning/ProvisioningResult.kt`
- Modify: `app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt`
- Test: `app/src/test/java/com/klk/hams/provisioning/VerifyResponseParserTest.kt`

**Interfaces:**
- Consumes: `ProvisioningClient.jsonStringField`, `escapeJsonString`, `post` (existing companion helpers).
- Produces:
  - `sealed interface VerifyResult { Bound; Released; BoundOther; data class Keep(reason) }`
  - `ProvisioningClient.parseVerifyResponse(code: Int, body: String?): VerifyResult`
  - `suspend fun ProvisioningClient.verify(uniqueId: String, fingerprint: String): VerifyResult`

- [ ] **Step 1: Write the failing parser test**

Create `app/src/test/java/com/klk/hams/provisioning/VerifyResponseParserTest.kt`:
```kotlin
package com.klk.hams.provisioning

import com.klk.hams.provisioning.ProvisioningClient.Companion.parseVerifyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyResponseParserTest {
    @Test fun bound_status_maps_to_Bound() {
        assertEquals(VerifyResult.Bound, parseVerifyResponse(200, """{"bound":true,"status":"bound"}"""))
    }

    @Test fun released_status_maps_to_Released() {
        assertEquals(VerifyResult.Released, parseVerifyResponse(200, """{"bound":false,"status":"released"}"""))
    }

    @Test fun bound_other_status_maps_to_BoundOther() {
        assertEquals(VerifyResult.BoundOther, parseVerifyResponse(200, """{"bound":false,"status":"bound_other"}"""))
    }

    @Test fun not_found_status_maps_to_Keep() {
        assertTrue(parseVerifyResponse(200, """{"bound":false,"status":"not_found"}""") is VerifyResult.Keep)
    }

    @Test fun unauthorized_maps_to_Keep() {
        assertTrue(parseVerifyResponse(401, """{"error":"unauthorized"}""") is VerifyResult.Keep)
    }

    @Test fun network_failure_code_maps_to_Keep() {
        assertTrue(parseVerifyResponse(-1, "timeout") is VerifyResult.Keep)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.VerifyResponseParserTest"`
Expected: FAIL — `parseVerifyResponse` / `VerifyResult` do not exist.

- [ ] **Step 3: Add the VerifyResult type**

Append to `ProvisioningResult.kt`:
```kotlin
/** Outcome of a /verify (binding re-check) call. Only Released and BoundOther
 *  trigger self-unprovision; everything else (not_found, bad_request, 401,
 *  network) collapses to Keep = "do nothing, stay bound". */
sealed interface VerifyResult {
    data object Bound : VerifyResult
    data object Released : VerifyResult
    data object BoundOther : VerifyResult
    data class Keep(val reason: String) : VerifyResult
}
```

- [ ] **Step 4: Add the parser + verify method**

In `ProvisioningClient.kt`, add the `verifyUrl` constructor parameter (after `releaseUrl`):
```kotlin
    private val verifyUrl: String = AppConfig.VERIFY_URL,
```
Add the `verify` method after `release`:
```kotlin
    /** POST { unique_id, fingerprint } to /verify with the shared-secret header.
     *  No admin passkey — this is an automatic device-initiated re-check. */
    suspend fun verify(uniqueId: String, fingerprint: String): VerifyResult = withContext(Dispatchers.IO) {
        if (uniqueId.isBlank() || fingerprint.isBlank()) return@withContext VerifyResult.Keep("blank input")
        val body = """{"unique_id":"${escapeJsonString(uniqueId)}","fingerprint":"${escapeJsonString(fingerprint)}"}"""
        val (code, resp) = post(verifyUrl, body, adminCode = "")
        parseVerifyResponse(code, resp)
    }
```
Add the parser inside the `companion object`:
```kotlin
        /** Pure: map (HTTP code, body) to a [VerifyResult]. Only the three
         *  action statuses are honoured; every other answer is Keep. */
        fun parseVerifyResponse(code: Int, body: String?): VerifyResult = when (code) {
            200 -> when (jsonStringField(body, "status")) {
                "bound" -> VerifyResult.Bound
                "released" -> VerifyResult.Released
                "bound_other" -> VerifyResult.BoundOther
                else -> VerifyResult.Keep(jsonStringField(body, "status") ?: "unknown")
            }
            else -> VerifyResult.Keep("http_$code")
        }
```

- [ ] **Step 5: Run the test and confirm it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.VerifyResponseParserTest"`
Expected: PASS (all 6).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/provisioning/ProvisioningResult.kt app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt app/src/test/java/com/klk/hams/provisioning/VerifyResponseParserTest.kt
git commit -m "feat(provisioning): ProvisioningClient.verify + VerifyResult parser"
```

---

## Task 6: BindingRevalidator + observable revocation state

**Files:**
- Create: `app/src/main/java/com/klk/hams/provisioning/BindingRevalidator.kt`
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt`
- Test: `app/src/test/java/com/klk/hams/provisioning/BindingRevalidatorTest.kt`

**Interfaces:**
- Consumes: `VerifyResult`, `DiagnosticType.BINDING_RELEASED/BINDING_TAKEN`, `recordDiagnostic(..., pushed)`, `ProvisioningStore`.
- Produces:
  - `HamsApp.provisioningRevocation: MutableStateFlow<String?>` (null = active; non-null = revoked banner text).
  - `HamsApp.bindingRevalidator: BindingRevalidator`.
  - `BindingRevalidator.decide(verify: VerifyResult): BindingDecision` — pure, testable.
  - `enum class BindingDecision { PROCEED, RELEASED_FLUSH, BOUND_OTHER }`
  - `BindingRevalidator.revoke(message: String)` — clears store + emits.
  - `const val REVOCATION_MESSAGE`.

- [ ] **Step 1: Write the failing decision test**

Create `app/src/test/java/com/klk/hams/provisioning/BindingRevalidatorTest.kt`:
```kotlin
package com.klk.hams.provisioning

import org.junit.Assert.assertEquals
import org.junit.Test

class BindingRevalidatorTest {
    @Test fun bound_proceeds() {
        assertEquals(BindingDecision.PROCEED, BindingRevalidator.decide(VerifyResult.Bound))
    }

    @Test fun keep_proceeds() {
        assertEquals(BindingDecision.PROCEED, BindingRevalidator.decide(VerifyResult.Keep("not_found")))
    }

    @Test fun released_flushes() {
        assertEquals(BindingDecision.RELEASED_FLUSH, BindingRevalidator.decide(VerifyResult.Released))
    }

    @Test fun bound_other_is_boundOther() {
        assertEquals(BindingDecision.BOUND_OTHER, BindingRevalidator.decide(VerifyResult.BoundOther))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.BindingRevalidatorTest"`
Expected: FAIL — `BindingRevalidator` / `BindingDecision` do not exist.

- [ ] **Step 3: Create BindingRevalidator**

Create `app/src/main/java/com/klk/hams/provisioning/BindingRevalidator.kt`:
```kotlin
package com.klk.hams.provisioning

import android.content.Context
import android.os.BatteryManager
import com.klk.hams.HamsApp
import com.klk.hams.diagnostics.DiagnosticType

enum class BindingDecision { PROCEED, RELEASED_FLUSH, BOUND_OTHER }

/**
 * Central binding re-check. `decide` is pure (unit-tested). The suspend entry
 * points perform IO and side effects. Golden rule: self-unprovision ONLY on
 * Released / BoundOther; Keep and Bound never touch local state.
 */
class BindingRevalidator(
    private val app: HamsApp,
    private val store: ProvisioningStore = ProvisioningStore.fromContext(app),
    private val client: ProvisioningClient = ProvisioningClient(),
) {
    /** Standalone check for the launch + periodic triggers. Defers any flush
     *  to PushWorker by enqueuing auto-push; does the immediate logout itself
     *  for the bound_other case (nothing safe to flush). */
    suspend fun revalidate() {
        val uid = store.uniqueIdOrNull() ?: return
        val fp = ProvisioningStore.deviceFingerprint(app) ?: return
        when (decide(client.verify(uid, fp))) {
            BindingDecision.PROCEED -> Unit
            BindingDecision.RELEASED_FLUSH -> {
                recordBinding(DiagnosticType.BINDING_RELEASED, pushed = 0)
                app.pushController.enqueueAuto() // PushWorker flushes + logs out after success
            }
            BindingDecision.BOUND_OTHER -> {
                recordBinding(DiagnosticType.BINDING_TAKEN, pushed = 1)
                revoke(REVOCATION_MESSAGE)
            }
        }
    }

    /** Records the diagnostic row for a binding transition. 301 pushes, 302 local. */
    suspend fun recordBinding(type: DiagnosticType, pushed: Int) {
        app.repository.recordDiagnostic(
            type = type,
            batteryPct = readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = pushed,
        )
    }

    /** Clears the stored unit and flips the observable flag so the open app
     *  drops to the pairing screen with the banner. */
    fun revoke(message: String) {
        store.clear()
        app.provisioningRevocation.value = message
    }

    companion object {
        const val REVOCATION_MESSAGE =
            "This device was unlinked by an administrator. Enter a new supervisor code to reconnect."

        fun decide(verify: VerifyResult): BindingDecision = when (verify) {
            VerifyResult.Bound -> BindingDecision.PROCEED
            is VerifyResult.Keep -> BindingDecision.PROCEED
            VerifyResult.Released -> BindingDecision.RELEASED_FLUSH
            VerifyResult.BoundOther -> BindingDecision.BOUND_OTHER
        }

        fun readBatteryPct(context: Context): Double? {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return if (pct in 0..100) pct.toDouble() else null
        }
    }
}
```

- [ ] **Step 4: Add the observable state + revalidator to HamsApp**

In `HamsApp.kt`, add the import near the other `kotlinx.coroutines.flow` import:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
```
Add these properties after the `pushController` property (around line 46):
```kotlin
    /** null = provisioned/active; non-null = revoked, value is the banner text. */
    val provisioningRevocation = MutableStateFlow<String?>(null)

    val bindingRevalidator: com.klk.hams.provisioning.BindingRevalidator by lazy {
        com.klk.hams.provisioning.BindingRevalidator(this)
    }
```

- [ ] **Step 5: Run the decision test and confirm it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.BindingRevalidatorTest"`
Expected: PASS (all 4).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/provisioning/BindingRevalidator.kt app/src/main/java/com/klk/hams/HamsApp.kt app/src/test/java/com/klk/hams/provisioning/BindingRevalidatorTest.kt
git commit -m "feat(provisioning): BindingRevalidator + observable revocation state"
```

---

## Task 7: PushWorker gate — check-first, released-flush, bound_other early-out

**Files:**
- Modify: `app/src/main/java/com/klk/hams/push/PushWorker.kt:33-59`, `:154-248`

**Interfaces:**
- Consumes: `HamsApp.bindingRevalidator`, `BindingRevalidator.decide`, `ProvisioningClient.verify`, `BindingDecision`, `PushState`.
- Produces: PushWorker that, before draining, verifies the binding; on `bound_other` records 302 + revokes + returns without pushing; on `released` records 301, pushes normally, then revokes **only after** a clean drain.

- [ ] **Step 1: Insert the binding check before the drain**

In `PushWorker.doWork()`, immediately after the `val app = applicationContext as HamsApp` line (currently line 44) and before `val repo = app.repository`, insert:
```kotlin
        // Binding revalidation gate (check-on-push). Runs before ANY frame goes
        // out. Network is guaranteed here (worker is UNMETERED-constrained).
        val revalidator = app.bindingRevalidator
        val store = provisioningStore
        val fp = ProvisioningStore.deviceFingerprint(applicationContext)
        var releasedFlush = false
        if (fp != null) {
            val uid = store.resolveUniqueId()
            when (BindingRevalidator.decide(ProvisioningClient(). verify(uid, fp))) {
                BindingDecision.PROCEED -> Unit
                BindingDecision.RELEASED_FLUSH -> {
                    // Unit is free: flush cuts + 301 first, revoke after success.
                    revalidator.recordBinding(com.klk.hams.diagnostics.DiagnosticType.BINDING_RELEASED, pushed = 0)
                    releasedFlush = true
                }
                BindingDecision.BOUND_OTHER -> {
                    // Unit taken by another phone: never push (would pollute it).
                    revalidator.recordBinding(com.klk.hams.diagnostics.DiagnosticType.BINDING_TAKEN, pushed = 1)
                    revalidator.revoke(BindingRevalidator.REVOCATION_MESSAGE)
                    Log.d(TAG, "doWork: binding taken by another device; logging out, no push")
                    return Result.success(workDataOf("tasks" to 0))
                }
            }
        }
```
Add the imports at the top of the file:
```kotlin
import com.klk.hams.provisioning.BindingDecision
import com.klk.hams.provisioning.BindingRevalidator
import com.klk.hams.provisioning.ProvisioningClient
```
(`ProvisioningStore` is already imported.)

- [ ] **Step 2: Revoke after a clean released-flush**

In the `try` block after the engine runs, locate the final `when (result)` that returns the `Result` (currently lines 238-243). Replace it with a version that revokes when the released flush fully drained:
```kotlin
            val telemetryClean = telemetryState is PushState.Success

            if (releasedFlush && result is PushState.Success && pendingAfter == 0 && telemetryClean) {
                // Cuts + 301 fully delivered on the (still-free) unit: safe to log out.
                app.bindingRevalidator.revoke(BindingRevalidator.REVOCATION_MESSAGE)
                Log.d(TAG, "doWork: released-flush complete; revoked binding")
            }

            when (result) {
                is PushState.Success -> Result.success(workDataOf("tasks" to tasksUploadedThisRun))
                is PushState.Partial -> Result.success(workDataOf("tasks" to tasksUploadedThisRun))
                is PushState.Failed -> Result.retry()
                else -> Result.retry()
            }
```
(If `releasedFlush` is true but the drain was not fully clean, the binding is left intact and the next run retries — data preserved.)

- [ ] **Step 3: Handle released-flush when there are no task rows (301 only)**

In the earlier telemetry-only branch (currently lines 64-83, the `if (pendingBeforeCount == 0)` block), after the `telemetryState` is computed and before returning, add a revoke on a clean drain. Replace the `when (telemetryState)` block with:
```kotlin
                when (telemetryState) {
                    is PushState.Success -> {
                        if (releasedFlush) {
                            app.bindingRevalidator.revoke(BindingRevalidator.REVOCATION_MESSAGE)
                            Log.d(TAG, "doWork: released-flush (telemetry-only) complete; revoked binding")
                        }
                        Result.success(workDataOf("tasks" to 0))
                    }
                    is PushState.Partial -> Result.success(workDataOf("tasks" to 0))
                    is PushState.Failed -> Result.retry()
                    else -> Result.retry()
                }
```

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the full unit suite (no regressions)**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/PushWorker.kt
git commit -m "feat(push): binding-check gate — flush+logout on released, no-push on taken"
```

---

## Task 8: Launch + periodic triggers

**Files:**
- Create: `app/src/main/java/com/klk/hams/push/BindingCheckWorker.kt`
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt` (onCreate)

**Interfaces:**
- Consumes: `HamsApp.bindingRevalidator`, `AppConfig.BINDING_CHECK_INTERVAL_MINUTES`.
- Produces: launch-time one-shot check; a `PeriodicWorkRequest` unique work named `hams-binding-check` running every `BINDING_CHECK_INTERVAL_MINUTES` under `NetworkType.CONNECTED`.

- [ ] **Step 1: Create the periodic worker**

Create `app/src/main/java/com/klk/hams/push/BindingCheckWorker.kt`:
```kotlin
package com.klk.hams.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.klk.hams.HamsApp
import com.klk.hams.provisioning.ProvisioningStore

/** Periodic binding re-check (~15 min). Only self-unprovisions on an explicit
 *  released/bound_other; network failure is a silent no-op. */
class BindingCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = ProvisioningStore.fromContext(applicationContext)
        if (!store.isProvisioned()) return Result.success()
        return try {
            (applicationContext as HamsApp).bindingRevalidator.revalidate()
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "binding check failed: $t", t)
            Result.success() // never retry-storm; next period covers it
        }
    }

    companion object {
        const val WORK_NAME = "hams-binding-check"
        private const val TAG = "HAMS_PUSH"
    }
}
```

- [ ] **Step 2: Schedule it + add the launch check in HamsApp.onCreate**

In `HamsApp.kt`, add imports:
```kotlin
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.klk.hams.push.BindingCheckWorker
import java.util.concurrent.TimeUnit
```
At the end of `onCreate()` (after the existing final `applicationScope.launch { ... }` chain block, before the closing brace of `onCreate`), add:
```kotlin
        // Binding revalidation — launch-time one-shot (only when provisioned).
        applicationScope.launch {
            try {
                if (provisioningStore.isProvisioned()) bindingRevalidator.revalidate()
            } catch (t: Throwable) {
                Log.w("HAMS_PUSH", "onCreate: launch binding check failed: $t", t)
            }
        }

        // Binding revalidation — periodic (~15 min, connected).
        val bindingCheck = PeriodicWorkRequestBuilder<BindingCheckWorker>(
            AppConfig.BINDING_CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BindingCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            bindingCheck,
        )
```

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/BindingCheckWorker.kt app/src/main/java/com/klk/hams/HamsApp.kt
git commit -m "feat(push): launch + periodic binding revalidation triggers"
```

---

## Task 9: UI — observe revocation, banner on pairing

**Files:**
- Modify: `app/src/main/java/com/klk/hams/MainActivity.kt:46-54`
- Modify: `app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt:76-80`

**Interfaces:**
- Consumes: `HamsApp.provisioningRevocation`, `PairingScreen(onPaired, client, notice)`.
- Produces: when `provisioningRevocation` is non-null, the app shows `PairingScreen` with the banner; a successful re-pair resets the flag to null.

- [ ] **Step 1: Add the notice banner to PairingScreen**

In `PairingScreen.kt`, change the signature to accept an optional notice:
```kotlin
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    client: ProvisioningClient = ProvisioningClient(),
    notice: String? = null,
) {
```
Inside the screen's root `Column` (immediately after it opens, before the existing title content), add the banner:
```kotlin
        if (notice != null) {
            androidx.compose.material3.Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                androidx.compose.material3.Text(
                    text = notice,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
```
(If `dp`/`Modifier`/`fillMaxWidth`/`padding` are not already imported in this file, add: `import androidx.compose.foundation.layout.fillMaxWidth`, `import androidx.compose.foundation.layout.padding`, `import androidx.compose.ui.Modifier`, `import androidx.compose.ui.unit.dp`.)

- [ ] **Step 2: Observe revocation in MainActivity**

In `MainActivity.onCreate`'s `setContent { HAMSTaskRecorderTheme { ... } }`, replace the `var provisioned by remember { ... }` + `if (!provisioned)` opening with a version that also watches the revocation flow:
```kotlin
                val app = application as HamsApp
                val revocation by app.provisioningRevocation.collectAsState()
                var provisioned by remember {
                    mutableStateOf(
                        ProvisioningStore.fromContext(this@MainActivity).isProvisioned()
                    )
                }
                // A background worker can revoke while the app is open.
                androidx.compose.runtime.LaunchedEffect(revocation) {
                    if (revocation != null) provisioned = false
                }
                if (!provisioned) {
                    PairingScreen(
                        notice = revocation,
                        onPaired = {
                            app.provisioningRevocation.value = null
                            provisioned = true
                        }
                    )
                } else {
```
Add the imports at the top of `MainActivity.kt`:
```kotlin
import androidx.compose.runtime.collectAsState
```
(`getValue` is already imported.)

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Lint the debug variant**

Run: `.\gradlew.bat :app:lintDebug`
Expected: `BUILD SUCCESSFUL` (no new errors).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/MainActivity.kt app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt
git commit -m "feat(ui): observe revocation, show unlinked banner on pairing screen"
```

---

## Task 10: Dictionary docs + full verification

**Files:**
- Modify: `docs/HAMS_EVENT_CODE_DICTIONARY.md`

**Interfaces:**
- Consumes: nothing.
- Produces: documented `3xx` provisioning family.

- [ ] **Step 1: Document the new family**

In `docs/HAMS_EVENT_CODE_DICTIONARY.md`, under the Option B diagnostics section, add a new subsection:
```markdown
## Provisioning Revalidation Codes (3xx family — 2026-07-07)

Emitted by the app's binding-revalidation gate (launch / before-push / periodic
`check_binding` against the n8n `verify` webhook). Isolated `3xx` band — no
collision with any existing family.

| Code | Meaning | Pushed to Wialon? |
|---|---|---|
| **301** | `binding_released` — unit was freed by an admin (still unowned). Flushed with pending cuts before logout. | **Yes** (unit is free; safe) |
| **302** | `binding_taken` — unit reassigned to a different device. | **No — local-only** (pushing would pollute the new owner's unit) |

Rule: the app self-unprovisions only on an explicit `released`/`bound_other`;
`not_found` / network failure never wipe a device. On `released` the phone
flushes `179/180/35` + `301` in one session, then logs out; on `bound_other`
it records `302` locally and logs out without pushing.
```

- [ ] **Step 2: Full build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Install on the connected phone (paired to HAMS_TEST_002)**

Run: `.\gradlew.bat :app:installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 5: Live revocation test**

1. Confirm the app is on the count screen (provisioned to `HAMS_TEST_002`).
2. In n8n / psql, free the unit:
   `psql "$PROV_DB_URL" -c "UPDATE units SET claimed=false, device_fingerprint=NULL WHERE unique_id='HAMS_TEST_002';"`
3. On the phone, press `+` once, then trigger a push (3 s hold on the count screen) while on validated Wi-Fi.
4. Expected: pending cuts + a `301` reach Wialon on `HAMS_TEST_002`, then the app drops to the pairing screen showing the banner *"This device was unlinked by an administrator…"*.
5. Pull the DB and confirm the `301` row:
   `adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "SELECT type,pushed FROM diagnostics WHERE type LIKE 'binding_%' ORDER BY id DESC LIMIT 3;"`
   Expected: `binding_released|1` (pushed).

- [ ] **Step 6: Commit**

```bash
git add docs/HAMS_EVENT_CODE_DICTIONARY.md
git commit -m "docs(event-codes): add 3xx provisioning revalidation family (301/302)"
```

---

## Self-Review Notes

- **Spec coverage:** backend deploy (T1), config (T2), codes (T3), local-only support (T4), verify client (T5), revalidator+observable state (T6), push gate with flush-before-logout + no-push-on-taken (T7), launch+periodic triggers (T8), UI banner+routing (T9), docs+e2e (T10). All requirements mapped.
- **Golden rule** enforced centrally in `BindingRevalidator.decide` (T6) and reused by both `PushWorker` (T7) and `BindingCheckWorker` (T8) — DRY.
- **Data-loss rule:** released path flushes then revokes only on a clean drain (T7 Steps 2–3); taken path never pushes (T7 Step 1).
- **Type consistency:** `VerifyResult` (T5) → `BindingDecision` (T6) → consumed identically in T7/T8. `recordDiagnostic(pushed=)` (T4) used by T6/T7. `provisioningRevocation` (T6) observed in T9.
