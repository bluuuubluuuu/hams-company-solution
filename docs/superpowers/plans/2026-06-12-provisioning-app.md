# Provisioning App Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android app claim its own Wialon `unique_id` from the n8n backend on first launch (fingerprint-locked), so one APK auto-provisions per device instead of a compile-time `DEVICE_UNIQUE_ID`.

**Architecture:** A prefs-backed `ProvisioningStore` holds the claimed `unique_id`. On first launch, a `ProvisioningScreen` (ahead of the GPS gate) reads `ANDROID_ID`, POSTs it to the n8n `/claim` webhook via `HttpURLConnection`, stores the returned id, and proceeds. The three existing consumers of `AppConfig.DEVICE_UNIQUE_ID` (`WialonIPSClient` default, `PushWorker`, `TaskRepository`) are routed through `ProvisioningStore.resolveUniqueId()`, with the BuildConfig value kept as a dev fallback.

**Tech Stack:** Kotlin, Jetpack Compose, `java.net.HttpURLConnection` (no new dependency), SharedPreferences, `Settings.Secure.ANDROID_ID`, JUnit (JVM), `com.sun.net.httpserver.HttpServer` for the client integration test.

**Depends on:** Plan 1 (backend) — a reachable `/claim` endpoint returning `200 {unique_id}` / `401` / `409 no_free_units`. Decisions: `docs/HAMS_PROVISIONING_FINDINGS.md` (§3, §6 office SOP, §7 code implications). **Out of scope:** office re-bind, admin UI, auto-sweep (findings §6).

**Repo conventions validated (2026-06-12):** INTERNET permission present; SharedPreferences uses file `"hams_prefs"` (`MainActivity`); pure-function + injected-store test seams used elsewhere (`WialonIPSClient.mapDataResponse`, `PushCampaign.CampaignStore`, `isPushableNetwork`). This plan follows those patterns.

**Build/test commands (PowerShell):**
- Unit test: `.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"`
- Build: `.\gradlew.bat :app:assembleDebug`

---

## File structure

- Create `app/src/main/java/com/klk/hams/provisioning/ProvisioningResult.kt` — sealed claim result.
- Create `app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt` — HTTP claim call + pure response parser.
- Create `app/src/main/java/com/klk/hams/provisioning/ProvisioningStore.kt` — prefs wrapper + `resolveUniqueId()` fallback + `ANDROID_ID` reader.
- Create `app/src/main/java/com/klk/hams/ui/onboarding/ProvisioningScreen.kt` — first-launch Compose gate.
- Modify `app/build.gradle.kts` — `N8N_CLAIM_URL` / `HAMS_CLAIM_SECRET` buildConfigFields.
- Modify `app/src/main/java/com/klk/hams/AppConfig.kt` — expose those two values.
- Modify `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` — `deviceIdProvider` param.
- Modify `app/src/main/java/com/klk/hams/HamsApp.kt` — wire the prefs-backed provider into `TaskRepository`.
- Modify `app/src/main/java/com/klk/hams/push/PushWorker.kt` — pass the runtime `unique_id` into `WialonIPSClient`.
- Modify `app/src/main/java/com/klk/hams/MainActivity.kt` — provisioning gate before onboarding/GPS gate.
- Test `app/src/test/java/com/klk/hams/provisioning/ProvisioningClientTest.kt`
- Test `app/src/test/java/com/klk/hams/provisioning/ProvisioningStoreTest.kt`

---

## Task 1: Config — n8n URL + secret via BuildConfig

**Files:**
- Modify: `app/build.gradle.kts:43-46`
- Modify: `app/src/main/java/com/klk/hams/AppConfig.kt:1-7`

- [ ] **Step 1: Add buildConfig fields**

In `app/build.gradle.kts`, in `defaultConfig` next to the existing `buildConfigField` lines, add:
```kotlin
buildConfigField("String", "N8N_CLAIM_URL",    javaStringLiteral(prop("N8N_CLAIM_URL")))
buildConfigField("String", "HAMS_CLAIM_SECRET", javaStringLiteral(prop("HAMS_CLAIM_SECRET")))
```

- [ ] **Step 2: Expose them in AppConfig**

In `app/src/main/java/com/klk/hams/AppConfig.kt`, after the existing `WIALON_TOKEN` line, add:
```kotlin
    const val N8N_CLAIM_URL: String = BuildConfig.N8N_CLAIM_URL
    const val HAMS_CLAIM_SECRET: String = BuildConfig.HAMS_CLAIM_SECRET
```

- [ ] **Step 3: Add the dev values to local.properties (not committed)**

Add to `local.properties` (gitignored):
```
N8N_CLAIM_URL=https://<your-n8n-host>/webhook/claim
HAMS_CLAIM_SECRET=<shared-secret>
```

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (BuildConfig now has the two fields).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/klk/hams/AppConfig.kt
git commit -m "feat(provisioning): n8n claim URL + secret via BuildConfig"
```

---

## Task 2: `ClaimResult` + pure response parser (TDD)

**Files:**
- Create: `app/src/main/java/com/klk/hams/provisioning/ProvisioningResult.kt`
- Create: `app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt` (parser only this task)
- Test: `app/src/test/java/com/klk/hams/provisioning/ProvisioningClientTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/klk/hams/provisioning/ProvisioningClientTest.kt`:
```kotlin
package com.klk.hams.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningClientTest {
    @Test fun parse_200_extracts_unique_id() {
        val r = ProvisioningClient.parseClaimResponse(200, """{"unique_id":"OC154_H001"}""")
        assertEquals(ClaimResult.Success("OC154_H001"), r)
    }

    @Test fun parse_200_malformed_is_error() {
        val r = ProvisioningClient.parseClaimResponse(200, """{"oops":true}""")
        assertTrue(r is ClaimResult.Error)
    }

    @Test fun parse_401_is_unauthorized() {
        assertEquals(ClaimResult.Unauthorized, ProvisioningClient.parseClaimResponse(401, """{"error":"unauthorized"}"""))
    }

    @Test fun parse_409_is_no_free_units() {
        assertEquals(ClaimResult.NoFreeUnits, ProvisioningClient.parseClaimResponse(409, """{"error":"no_free_units"}"""))
    }

    @Test fun parse_500_is_error() {
        assertTrue(ProvisioningClient.parseClaimResponse(500, null) is ClaimResult.Error)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ProvisioningClientTest"`
Expected: FAIL — unresolved `ClaimResult` / `ProvisioningClient`.

- [ ] **Step 3: Write the result type**

Create `app/src/main/java/com/klk/hams/provisioning/ProvisioningResult.kt`:
```kotlin
package com.klk.hams.provisioning

/** Outcome of a /claim call. */
sealed interface ClaimResult {
    data class Success(val uniqueId: String) : ClaimResult
    data object NoFreeUnits : ClaimResult       // HTTP 409
    data object Unauthorized : ClaimResult       // HTTP 401 (bad/missing secret)
    data class Error(val reason: String) : ClaimResult // network / 5xx / malformed
}
```

- [ ] **Step 4: Write the parser**

Create `app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt`:
```kotlin
package com.klk.hams.provisioning

class ProvisioningClient {
    companion object {
        private val UNIQUE_ID = Regex("\"unique_id\"\\s*:\\s*\"([^\"]+)\"")

        /** Pure: map (HTTP code, body) to a [ClaimResult]. JVM-testable, no I/O. */
        fun parseClaimResponse(code: Int, body: String?): ClaimResult = when (code) {
            200 -> {
                val id = body?.let { UNIQUE_ID.find(it)?.groupValues?.get(1) }
                if (id.isNullOrBlank()) ClaimResult.Error("malformed 200: $body")
                else ClaimResult.Success(id)
            }
            401 -> ClaimResult.Unauthorized
            409 -> ClaimResult.NoFreeUnits
            else -> ClaimResult.Error("HTTP $code")
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ProvisioningClientTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/provisioning/ProvisioningResult.kt app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt app/src/test/java/com/klk/hams/provisioning/ProvisioningClientTest.kt
git commit -m "feat(provisioning): ClaimResult + pure claim-response parser"
```

---

## Task 3: `ProvisioningClient.claim()` over HttpURLConnection (TDD with local server)

**Files:**
- Modify: `app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt`
- Test: `app/src/test/java/com/klk/hams/provisioning/ProvisioningClientTest.kt`

- [ ] **Step 1: Write the failing test (real HTTP via local server)**

Append to `ProvisioningClientTest.kt`:
```kotlin
    // --- claim() integration over a local HttpServer (no external deps) ---
    @Test fun claim_posts_fingerprint_and_returns_success() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        var seenKey: String? = null
        var seenBody: String? = null
        server.createContext("/claim") { ex ->
            seenKey = ex.requestHeaders.getFirst("x-hams-key")
            seenBody = ex.requestBody.bufferedReader().readText()
            val resp = """{"unique_id":"OC154_H007"}""".toByteArray()
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        val url = "http://localhost:${server.address.port}/claim"
        try {
            val client = ProvisioningClient(claimUrl = url, secret = "s3cret")
            val r = kotlinx.coroutines.runBlocking { client.claim("fpABC") }
            assertEquals(ClaimResult.Success("OC154_H007"), r)
            assertEquals("s3cret", seenKey)
            assertTrue(seenBody!!.contains("fpABC"))
        } finally {
            server.stop(0)
        }
    }

    @Test fun claim_blank_fingerprint_is_error() {
        val client = ProvisioningClient(claimUrl = "http://localhost:1/claim", secret = "x")
        val r = kotlinx.coroutines.runBlocking { client.claim("") }
        assertTrue(r is ClaimResult.Error)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ProvisioningClientTest"`
Expected: FAIL — `ProvisioningClient` has no constructor params / no `claim`.

- [ ] **Step 3: Implement `claim()`**

Replace the body of `ProvisioningClient` in `app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt` with:
```kotlin
package com.klk.hams.provisioning

import com.klk.hams.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class ProvisioningClient(
    private val claimUrl: String = AppConfig.N8N_CLAIM_URL,
    private val secret: String = AppConfig.HAMS_CLAIM_SECRET,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val opener: (String) -> HttpURLConnection = { (URL(it).openConnection() as HttpURLConnection) },
) {
    /** POST { "fingerprint": <id> } with the shared-secret header; map the response. */
    suspend fun claim(fingerprint: String): ClaimResult = withContext(Dispatchers.IO) {
        if (fingerprint.isBlank()) return@withContext ClaimResult.Error("blank fingerprint")
        val conn = opener(claimUrl)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-hams-key", secret)
            conn.outputStream.use {
                it.write("{\"fingerprint\":\"$fingerprint\"}".toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            parseClaimResponse(code, body)
        } catch (e: Exception) {
            ClaimResult.Error(e.message ?: e::class.java.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val UNIQUE_ID = Regex("\"unique_id\"\\s*:\\s*\"([^\"]+)\"")

        fun parseClaimResponse(code: Int, body: String?): ClaimResult = when (code) {
            200 -> {
                val id = body?.let { UNIQUE_ID.find(it)?.groupValues?.get(1) }
                if (id.isNullOrBlank()) ClaimResult.Error("malformed 200: $body")
                else ClaimResult.Success(id)
            }
            401 -> ClaimResult.Unauthorized
            409 -> ClaimResult.NoFreeUnits
            else -> ClaimResult.Error("HTTP $code")
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ProvisioningClientTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/provisioning/ProvisioningClient.kt app/src/test/java/com/klk/hams/provisioning/ProvisioningClientTest.kt
git commit -m "feat(provisioning): claim() over HttpURLConnection"
```

---

## Task 4: `ProvisioningStore` — persist id + fallback + fingerprint (TDD)

**Files:**
- Create: `app/src/main/java/com/klk/hams/provisioning/ProvisioningStore.kt`
- Test: `app/src/test/java/com/klk/hams/provisioning/ProvisioningStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/klk/hams/provisioning/ProvisioningStoreTest.kt`:
```kotlin
package com.klk.hams.provisioning

import com.klk.hams.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningStoreTest {
    private class FakeStore : ProvisioningStore.KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test fun unprovisioned_by_default() {
        val s = ProvisioningStore(FakeStore())
        assertFalse(s.isProvisioned())
        assertNull(s.uniqueIdOrNull())
    }

    @Test fun resolve_falls_back_to_buildconfig_when_unset() {
        val s = ProvisioningStore(FakeStore())
        assertEquals(AppConfig.DEVICE_UNIQUE_ID, s.resolveUniqueId())
    }

    @Test fun save_then_resolve_returns_saved() {
        val s = ProvisioningStore(FakeStore())
        s.save("OC154_H042")
        assertTrue(s.isProvisioned())
        assertEquals("OC154_H042", s.uniqueIdOrNull())
        assertEquals("OC154_H042", s.resolveUniqueId())
    }

    @Test fun blank_is_treated_as_unprovisioned() {
        val store = FakeStore().also { it.putString("device_unique_id", "") }
        val s = ProvisioningStore(store)
        assertFalse(s.isProvisioned())
        assertEquals(AppConfig.DEVICE_UNIQUE_ID, s.resolveUniqueId())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ProvisioningStoreTest"`
Expected: FAIL — unresolved `ProvisioningStore`.

- [ ] **Step 3: Write the store**

Create `app/src/main/java/com/klk/hams/provisioning/ProvisioningStore.kt`:
```kotlin
package com.klk.hams.provisioning

import android.content.Context
import android.provider.Settings
import com.klk.hams.AppConfig

/**
 * Holds the device's claimed Wialon `unique_id`. Backing store is injected
 * ([KeyValueStore]) so the logic is JVM-testable without Android, mirroring
 * [com.klk.hams.push.PushCampaign].
 */
class ProvisioningStore(private val store: KeyValueStore) {

    interface KeyValueStore {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    fun uniqueIdOrNull(): String? = store.getString(KEY_UNIQUE_ID)?.takeIf { it.isNotBlank() }

    fun isProvisioned(): Boolean = uniqueIdOrNull() != null

    fun save(uniqueId: String) = store.putString(KEY_UNIQUE_ID, uniqueId)

    fun clear() = store.remove(KEY_UNIQUE_ID)

    /** Stored id if provisioned, else the BuildConfig fallback (dev/test). */
    fun resolveUniqueId(): String = uniqueIdOrNull() ?: AppConfig.DEVICE_UNIQUE_ID

    companion object {
        const val PREFS_NAME: String = "hams_prefs"           // shared with MainActivity
        private const val KEY_UNIQUE_ID: String = "device_unique_id"

        fun fromContext(context: Context): ProvisioningStore =
            ProvisioningStore(SharedPrefsKeyValueStore(context))

        /** ANDROID_ID; null/blank on the rare cloned/defective device (#7 guard). */
        fun deviceFingerprint(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
    }

    class SharedPrefsKeyValueStore(context: Context) : KeyValueStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        override fun remove(key: String) { prefs.edit().remove(key).apply() }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ProvisioningStoreTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/provisioning/ProvisioningStore.kt app/src/test/java/com/klk/hams/provisioning/ProvisioningStoreTest.kt
git commit -m "feat(provisioning): ProvisioningStore (persist id + fallback + ANDROID_ID)"
```

---

## Task 5: Route the 3 consumers through the runtime id

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt:50` (and constructor)
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt:30`
- Modify: `app/src/main/java/com/klk/hams/push/PushWorker.kt:90-93`

- [ ] **Step 1: Add a `deviceIdProvider` to TaskRepository**

In `TaskRepository.kt`, change the class declaration to accept a provider (default keeps the BuildConfig fallback so existing tests are unaffected). Find the constructor (e.g. `class TaskRepository(private val db: AppDatabase) {`) and change it to:
```kotlin
class TaskRepository(
    private val db: AppDatabase,
    private val deviceIdProvider: () -> String = { AppConfig.DEVICE_UNIQUE_ID },
) {
```
Then at line ~50 replace `deviceId = AppConfig.DEVICE_UNIQUE_ID,` with:
```kotlin
                        deviceId = deviceIdProvider(),
```
(Keep the existing `import com.klk.hams.AppConfig`.)

- [ ] **Step 2: Wire the prefs-backed provider in HamsApp**

In `HamsApp.kt`, change line 30:
```kotlin
    val repository: TaskRepository by lazy {
        TaskRepository(database) {
            com.klk.hams.provisioning.ProvisioningStore.fromContext(this).resolveUniqueId()
        }
    }
```
(The lambda is read at task-write time, so it picks up the id provisioned later in the same process.)

- [ ] **Step 3: Pass the runtime id into the push sender**

In `PushWorker.kt`, just before `val engine = PushEngine(` (around line 90), add:
```kotlin
        val provisionedId = com.klk.hams.provisioning.ProvisioningStore
            .fromContext(applicationContext).resolveUniqueId()
```
Then change the `senderFactory` line (92) from `senderFactory = { WialonIPSClient() },` to:
```kotlin
            senderFactory = { WialonIPSClient(uniqueId = provisionedId) },
```

- [ ] **Step 4: Verify build + existing tests still pass**

Run:
```
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL; all existing unit tests still PASS (TaskRepository default provider = BuildConfig fallback; `WialonIPSClientTest` passes `uniqueId` explicitly so it's unaffected).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt app/src/main/java/com/klk/hams/HamsApp.kt app/src/main/java/com/klk/hams/push/PushWorker.kt
git commit -m "feat(provisioning): route device id through ProvisioningStore (runtime)"
```

---

## Task 6: First-launch `ProvisioningScreen` + MainActivity gate

**Files:**
- Create: `app/src/main/java/com/klk/hams/ui/onboarding/ProvisioningScreen.kt`
- Modify: `app/src/main/java/com/klk/hams/MainActivity.kt:44-62`

- [ ] **Step 1: Write the provisioning screen**

Create `app/src/main/java/com/klk/hams/ui/onboarding/ProvisioningScreen.kt`:
```kotlin
package com.klk.hams.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klk.hams.provisioning.ClaimResult
import com.klk.hams.provisioning.ProvisioningClient
import com.klk.hams.provisioning.ProvisioningStore

private sealed interface UiState {
    data object Loading : UiState
    data class Failed(val message: String, val canRetry: Boolean) : UiState
}

/**
 * First-launch gate: claims a unit from n8n and stores it, then calls
 * [onProvisioned]. Shown only when [ProvisioningStore.isProvisioned] is false.
 * Reconfiguration is office-only (findings §6) — no admin controls here.
 */
@Composable
fun ProvisioningScreen(
    onProvisioned: () -> Unit,
    client: ProvisioningClient = ProvisioningClient(),
) {
    val context = LocalContext.current
    val store = remember { ProvisioningStore.fromContext(context) }
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }

    LaunchedEffect(attempt) {
        state = UiState.Loading
        val fingerprint = ProvisioningStore.deviceFingerprint(context)
        if (fingerprint == null) {
            state = UiState.Failed(
                "This device has no usable hardware ID. Contact your supervisor.",
                canRetry = false,
            )
            return@LaunchedEffect
        }
        when (val r = client.claim(fingerprint)) {
            is ClaimResult.Success -> { store.save(r.uniqueId); onProvisioned() }
            ClaimResult.NoFreeUnits ->
                state = UiState.Failed("No device unit available. Contact your supervisor.", canRetry = true)
            ClaimResult.Unauthorized ->
                state = UiState.Failed("Setup error (auth). Contact your supervisor.", canRetry = true)
            is ClaimResult.Error ->
                state = UiState.Failed("No connection. Connect to the internet and retry.", canRetry = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is UiState.Loading -> {
                CircularProgressIndicator()
                Text("Setting up this device…", modifier = Modifier.padding(top = 16.dp))
            }
            is UiState.Failed -> {
                Text(s.message)
                if (s.canRetry) {
                    Button(onClick = { attempt++ }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Insert the gate ahead of onboarding in MainActivity**

In `MainActivity.kt`, inside `setContent { HAMSTaskRecorderTheme { ... } }`, wrap the existing content. Replace the opening of the theme block (the `var showOnboarding ...` line) so provisioning runs first:
```kotlin
            HAMSTaskRecorderTheme {
                var provisioned by remember {
                    mutableStateOf(
                        com.klk.hams.provisioning.ProvisioningStore
                            .fromContext(this@MainActivity).isProvisioned()
                    )
                }
                if (!provisioned) {
                    com.klk.hams.ui.onboarding.ProvisioningScreen(
                        onProvisioned = { provisioned = true }
                    )
                    return@HAMSTaskRecorderTheme
                }

                var showOnboarding by remember {
                    mutableStateOf(!batteryOnboardingShown() && !isBatteryExempt())
                }
                // ... existing onboarding + CountScreen block unchanged ...
```
Leave the rest of the existing block (onboarding + `CountScreen`) exactly as-is below this.

- [ ] **Step 3: Verify build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/onboarding/ProvisioningScreen.kt app/src/main/java/com/klk/hams/MainActivity.kt
git commit -m "feat(provisioning): first-launch ProvisioningScreen gate"
```

---

## Task 7: End-to-end device verification (manual)

**Prereqs:** Plan 1 backend live with ≥1 free unit; `local.properties` has `N8N_CLAIM_URL` + `HAMS_CLAIM_SECRET`; device on internet.

- [ ] **Step 1: Clean install (fresh provisioning state)**

Run:
```
adb uninstall com.klk.hams.debug
.\gradlew.bat :app:installDebug
```

- [ ] **Step 2: First launch claims a unit**

Launch the app. Expected: "Setting up this device…" briefly → proceeds to battery onboarding / GPS gate. Verify in Postgres:
```bash
psql "$PROV_DB_URL" -c "SELECT unique_id, device_fingerprint FROM units WHERE claimed=true ORDER BY updated_at DESC LIMIT 3;"
```
Expected: a row with this device's `ANDROID_ID` as `device_fingerprint`.

- [ ] **Step 3: Relaunch is idempotent (no second claim)**

Force-close and reopen the app. Expected: **no** "Setting up…" screen (goes straight in). Postgres claimed-count unchanged.

- [ ] **Step 4: Cut pushes to the claimed unit**

Pass the GPS gate, press `+` once, connect validated Wi-Fi (or 3 s manual push hold). On `pro.navi-agnostics.com`, open the **claimed** unit. Expected: the `+` message with `ffb_cut=1` lands on **that** unit (matching the `unique_id` from Step 2), not the old `HAMS_TEST_001`.

- [ ] **Step 5: No-free-units path (optional)**

Drain the pool (claim from other fingerprints via curl), uninstall+reinstall, launch. Expected: "No device unit available. Contact your supervisor." + Retry.

- [ ] **Step 6: Record the result**

Append outcomes to `provisioning/README.md` (device model, claimed unit, pass/fail per step).

```bash
git add provisioning/README.md
git commit -m "test(provisioning): end-to-end device verification notes"
```

---

## Out of scope (deferred per findings §6)
- Office re-bind tooling / supervisor admin screen (reconfiguration is office-only, admin-driven).
- Orphan auto-sweep.
- Fixed release keystore (#5) — required before production so `ANDROID_ID` is stable; the test phase is fine on the debug key as long as it stays constant.

## Self-review notes
- Spec coverage: runtime id (§7) = Tasks 1,4,5; first-launch claim (§3) = Tasks 3,6; null-`ANDROID_ID` guard (#7) = Task 6; no-free (O2) = Tasks 2,6; secret (#9) = Tasks 1,3; idempotency (#11) = Task 7 Step 3 + backend; fallback = Task 4. ✓
- Types consistent across tasks: `ClaimResult` variants, `ProvisioningClient(claimUrl, secret, …)`, `ProvisioningStore.resolveUniqueId()/isProvisioned()/save()`, `deviceFingerprint(context)`, `TaskRepository(db, deviceIdProvider)`. ✓
- No placeholders: all code/commands concrete. The only environment value is `local.properties` (Task 1 Step 3), by design.
