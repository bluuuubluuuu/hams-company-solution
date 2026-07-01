# Phase 0 — Setup Finalization

> **Read first:** `CLAUDE.md` (project rules) + `CONTEXT.md` §1 (environment constants).
> **Scope:** close the outstanding Phase 0 items flagged in `CLAUDE.md`. No feature code.

---

## Objective

Take the Android Studio scaffold from its current state (Compose "Greeting" stub, no `BuildConfig` wiring, no permissions, no debug build type) to a clean, buildable base where:

- Secrets from `local.properties` flow into Kotlin via `BuildConfig`.
- A `debug` build type exists so Phase 1+ can inject dev-only overrides.
- `AndroidManifest.xml` declares every permission the later phases will need.
- A single-source `AppConfig` reads `BuildConfig` constants; no other file reads secrets.

No SQLite, no GPS, no networking, no UI logic beyond the scaffold. The output is "builds clean, config is wired, Codex can start Phase 1 without touching `build.gradle.kts` again."

---

## Acceptance Criteria (from `docs/HAMS_V2_APP_REQUIREMENTS.md`)

- **Phase 0 (Setup & Planning)** list — "Initialize repo, project structure, CLAUDE.md; Finalize tech stack (Kotlin native recommended)".
- **NF-01 (Android Compatibility)** — minSdk = API 34 (Android 14), target device Android 15. The app should keep `minSdk=34` and `targetSdk=35` unless KC confirms otherwise. `compileSdk` may be adjusted independently only if the installed Android SDK and dependency metadata require it.

Outstanding items from `CLAUDE.md` → "Current Repo State / Outstanding Phase 0 items":

1. Fix the current Gradle dependency / compile SDK mismatch so the scaffold builds.
2. Wire `local.properties` secrets into `BuildConfig` (`WIALON_TOKEN`, `IPS_HOST`, `IPS_PORT`, `DEVICE_UNIQUE_ID`).
3. Add an explicit `debug` buildType block.
4. Add runtime permissions to `AndroidManifest.xml`.

---

## Prerequisites

- `local.properties` at repo root exists and contains `WIALON_TOKEN`, `IPS_HOST`, `IPS_PORT`, `DEVICE_UNIQUE_ID`. Already true.
- `.gitignore` excludes `local.properties`. Already true.
- Gradle wrapper runs without errors: `./gradlew.bat --version` on Windows.

---

## Task Breakdown

### Task 0.0 — Restore a clean scaffold build

**Files**
- Modify: `gradle/libs.versions.toml` and/or `app/build.gradle.kts`

**Current issue**

As of 2026-04-28, `./gradlew.bat :app:assembleDebug` fails because `androidx.core:core-ktx:1.17.0` and `androidx.activity:activity-compose:1.11.0` require `compileSdk >= 36`, while the project is pinned to `compileSdk=35`.

**Steps**

- [x] Step 1 — Preferred fix applied: downgraded `coreKtx` 1.17.0→1.15.0 and `activityCompose` 1.11.0→1.10.1 in `gradle/libs.versions.toml`. `compileSdk=35` unchanged.
- [x] Step 2 — Not needed; Step 1 was sufficient.
- [x] Step 3 — `.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`.
- [x] Step 4 — Committed as `build: align AndroidX versions with compileSdk 35`.

### Task 0.1 — Enable `BuildConfig` and wire secrets

**Files**
- Modify: `app/build.gradle.kts`

**Steps**

- [x] Step 1 — At the top of `app/build.gradle.kts` (before the `plugins` block), add the `local.properties` reader:

  ```kotlin
  import java.util.Properties
  import java.io.FileInputStream

  val localProps = Properties().apply {
      val f = rootProject.file("local.properties")
      if (f.exists()) FileInputStream(f).use { load(it) }
  }
  fun prop(key: String, fallback: String = ""): String =
      localProps.getProperty(key) ?: System.getenv(key) ?: fallback
  ```

- [x] Step 2 — Inside `android { defaultConfig { ... } }`, add `buildConfigField` entries. `IPS_PORT` is an `int`, everything else is a `String`:

  ```kotlin
  buildConfigField("String", "WIALON_TOKEN",   "\"${prop("WIALON_TOKEN")}\"")
  buildConfigField("String", "IPS_HOST",       "\"${prop("IPS_HOST", "185.213.1.24")}\"")
  buildConfigField("int",    "IPS_PORT",       prop("IPS_PORT", "20332"))
  buildConfigField("String", "DEVICE_UNIQUE_ID", "\"${prop("DEVICE_UNIQUE_ID", "HAMS_TEST_001")}\"")
  ```

- [x] Step 3 — Inside `android { ... }`, enabled `buildConfig = true` in `buildFeatures` block.

- [x] Step 4 — Added `debug` block alongside the `release` block in `buildTypes`.

- [x] Step 5 — `.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`.

### Task 0.2 — Add required permissions to the manifest — **COMPLETE**

**Files**
- Modify: `app/src/main/AndroidManifest.xml`

**Steps**

- [x] Step 1 — Added all 8 `<uses-permission>` elements (INTERNET, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK).

- [x] Step 2 — Rebuilt. `BUILD SUCCESSFUL`.

### Task 0.3 — Create `AppConfig` as the single reader of `BuildConfig` — **COMPLETE**

**Files**
- Create: `app/src/main/java/com/klk/hams/AppConfig.kt`

**Steps**

- [x] Step 1 — File created (exact contents as specified):

  ```kotlin
  package com.klk.hams

  object AppConfig {
      const val IPS_HOST: String = BuildConfig.IPS_HOST
      const val IPS_PORT: Int = BuildConfig.IPS_PORT
      const val DEVICE_UNIQUE_ID: String = BuildConfig.DEVICE_UNIQUE_ID
      const val WIALON_TOKEN: String = BuildConfig.WIALON_TOKEN

      const val BATCH_SIZE: Int = 10
      const val BATCH_DELAY_MS: Long = 75
      const val MAX_RETRY_ATTEMPTS: Int = 5
      const val SQLITE_RETENTION_DAYS: Int = 7

      const val EVENT_CODE_PLUS: Int = 279
      const val EVENT_CODE_MINUS: Int = 280
      const val EVENT_CODE_NEW_TASK: Int = 281
      const val EVENT_CODE_AUTO_SAVE_KILL: Int = 283
      const val EVENT_CODE_AUTO_SAVE_WIFI: Int = 284
      const val EVENT_CODE_BATTERY_WARN: Int = 291
      const val EVENT_CODE_BATTERY_CRITICAL: Int = 292
      const val EVENT_CODE_GPS_DEGRADED: Int = 293
      const val EVENT_CODE_HEARTBEAT: Int = 35

      const val HEARTBEAT_INTERVAL_MINUTES: Int = 10
      const val BATTERY_WARN_THRESHOLD_PCT: Int = 20
      const val BATTERY_CRITICAL_THRESHOLD_PCT: Int = 10
      const val GPS_HDOP_DEGRADED_THRESHOLD: Double = 5.0

      const val PUSH_NEW_TASK_TO_WIALON: Boolean = false
      const val PUSH_MINUS_ONLY_IF_PRODUCTIVE: Boolean = true
  }
  ```

  Every later phase reads connection constants through `AppConfig`, never through `BuildConfig` directly. Event-code defaults live here too so Phase 1 can write valid V6 rows before the push engine exists. This keeps the surface of "what reads the token" to one file.

- [x] Step 2 — `.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`.

### Task 0.4 — Add `plans/` to git tracking (bookkeeping) — **COMPLETE**

**Files**
- Already created by this plan: `plans/phase0_setup.md`, `plans/phase1_core_offline.md`, `plans/phase2_ips_push.md`, `plans/phase3_ui_polish.md`, `plans/phase4_integration_test.md`.

**Steps**

- [x] Step 1 — Committed with message `docs(plans): add phase-by-phase implementation plans (0-4)`.

---

## Phase 0 Status: **COMPLETE** (2026-04-28)

All acceptance criteria passed. `.\gradlew.bat :app:assembleDebug` succeeds. `BuildConfig` wired. `AppConfig.kt` is the single reader. All manifest permissions declared. `debug` build type exists.

---

## Karpathy-Style Loop-Verifiable Success Criteria

Codex can loop on these checks until they all pass. No human judgement needed.

| # | Check | Command / Observation | Expected |
|---|---|---|---|
| 1 | Debug build succeeds | `.\gradlew.bat :app:assembleDebug` | `BUILD SUCCESSFUL` |
| 2 | `BuildConfig` is generated | `find app/build/generated -name BuildConfig.java` | ≥1 match under `debug/` |
| 3 | `BuildConfig` contains the four fields | `grep -E "WIALON_TOKEN\|IPS_HOST\|IPS_PORT\|DEVICE_UNIQUE_ID" app/build/generated/**/BuildConfig.java` | 4 lines matched |
| 4 | `AppConfig.kt` exists | `test -f app/src/main/java/com/klk/hams/AppConfig.kt` | exits 0 |
| 5 | No source file other than `AppConfig.kt` references `BuildConfig` | `grep -rn BuildConfig app/src/main/java \| grep -v AppConfig.kt` | empty output |
| 6 | No source file hardcodes the IPS host/port | `grep -rn "185.213.1.24\|20332" app/src/main` | empty output (appears only in `AppConfig` via `BuildConfig`) |
| 7 | All declared permissions parse | `.\gradlew.bat :app:lintDebug` | 0 errors (warnings ok) |
| 8 | `local.properties` not committed | `git ls-files \| grep local.properties` | empty |

If any of these fail after 3 attempts, stop and report — do not bypass with stubs.

---

## Verification Commands (run at end of phase)

```bash
.\gradlew.bat clean
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
git status  # should show only new plans/ files and the modified build files
```

---

## Do Not

- **Do not** read `local.properties` at runtime. It is a Gradle-time source only. `BuildConfig` is the only channel between Gradle and app code.
- **Do not** bundle `local.properties` as a raw resource (`res/raw/`, `assets/`).
- **Do not** add `res/raw/config.json` — that alternative from `CLAUDE.md` is unused; `BuildConfig` is the chosen mechanism.
- **Do not** add dependencies in this phase. No Room, no Hilt, no OkHttp, no WorkManager. Phase 1 and Phase 2 add them when actually used.
- **Do not** change `minSdk` or `targetSdk` in Phase 0. `compileSdk` and AndroidX versions may be changed only to resolve the scaffold build mismatch described in Task 0.0, and the chosen path must be documented.
- **Do not** introduce abstractions (`ConfigProvider` interface, DI module, etc.) around `AppConfig`. It is a single `object` with `const val`s. YAGNI.
- **Do not** write any feature code (SQLite schema, GPS, UI, push). If tempted, stop — that belongs to Phase 1 or Phase 2.
- **Do not** edit `CLAUDE.md`, `CONTEXT.md`, or any file under `docs/`. Phase 0 is build-config only.
- **Do not** delete the `Greeting` stub in `MainActivity.kt` yet — Phase 1 replaces it wholesale.
- **Do not** rotate or regenerate the Wialon token. The one in `local.properties` is the canonical test token (see `CONTEXT.md` §1).
