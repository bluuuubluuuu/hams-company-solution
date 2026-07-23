# 302 `work_stranded` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit a Wialon event code `302 work_stranded` at a device-initiated OTP release when the phone is leaving with unsent harvest, carrying the leftover counts, and mark those rows non-sendable so the record is truthful.

**Architecture:** `302` is the stranded-work variant of the existing `304 device_unbound` — mutually exclusive, one message per release. Both carry `lost_tasks` / `lost_cuts` params (`304` reports `0/0`). The message is pushed inline on the existing `ProvisioningEvents` path, in the same TCP session and under the **old** unit id, before `store.clear()`. If and only if it lands, all still-pending event rows are marked `pushed = 2` so they can never later push under the next unit.

**Tech Stack:** Kotlin, Room (SQLite), Jetpack Compose, JUnit4, AndroidX Test. Gradle Kotlin DSL.

## Global Constraints

- Build commands run from repo root as `.\gradlew.bat` (PowerShell workspace).
- Room database version goes **5 → 6**. Migrations are registered in `HamsApp.database` (`HamsApp.kt:39`). This project does **not** export Room schemas — migration tests build the old table by hand (see `DiagnosticMigrationTest`).
- Existing telemetry frames must stay **byte-identical**. `TelemetryFrameBuilderTest.startMoving_frame_isByteExact` asserts an exact string; the new params must only appear when non-null.
- IPS param format is `name:type:value`, where type `1` = integer and `2` = double. Both new params are integers → type `1`.
- Outbound task-path event codes remain `179` / `180` / `35` only. This plan adds no new codes to the `events` table — `302` lives in the `diagnostics` table via `TelemetryCode`.
- `work_count` stays hardcoded `0` on every telemetry frame (`IPSFrameBuilder.kt:130`). Do not overload it.
- No secrets in source. No changes to `local.properties` or `BuildConfig`.
- Commit style: conventional commits, `type(scope): subject`, subject under 72 chars, no attribution lines.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/klk/hams/diagnostics/DiagnosticType.kt` | diagnostic vocabulary | add `WORK_STRANDED` |
| `app/src/main/java/com/klk/hams/push/TelemetryCode.kt` | action → Wialon code | add `"work_stranded" to 302` |
| `app/src/main/java/com/klk/hams/data/model/DiagnosticEntity.kt` | diagnostics row | add `lostTasks`, `lostCuts` |
| `app/src/main/java/com/klk/hams/data/db/AppDatabase.kt` | schema + migrations | `version = 6`, `MIGRATION_5_6` |
| `app/src/main/java/com/klk/hams/HamsApp.kt` | DI + migration registry | register `MIGRATION_5_6` |
| `app/src/main/java/com/klk/hams/push/IPSFrameBuilder.kt` | frame construction | append the two params when non-null |
| `app/src/main/java/com/klk/hams/data/db/EventDao.kt` | event queries | leftover counts + strand |
| `app/src/main/java/com/klk/hams/data/db/DiagnosticDao.kt` | diagnostics queries | `pendingIds()` |
| `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt` | domain surface | `UnsentWork`, `countUnsentWork`, `strandUnsentWork`, `pendingTelemetryIds` |
| `app/src/main/java/com/klk/hams/provisioning/ProvisioningEvents.kt` | release marker push | `releaseTypeFor`, `recordAndPushRelease`, `flushAndRelease`, widened reject guard |
| `app/src/main/java/com/klk/hams/ui/onboarding/AdminSheet.kt` | release call site 1 | swap to `flushAndRelease`, cover `NotFound` |
| `app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt` | release call site 2 | swap `ReleaseAndBind` to `flushAndRelease` |
| `docs/HAMS_EVENT_CODE_DICTIONARY.md` | canonical vocabulary | v1.5 |

---

### Task 1: `302` vocabulary

**Files:**
- Modify: `app/src/main/java/com/klk/hams/diagnostics/DiagnosticType.kt:18`
- Modify: `app/src/main/java/com/klk/hams/push/TelemetryCode.kt:23`
- Test: `app/src/test/java/com/klk/hams/push/TelemetryCodeBindingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `DiagnosticType.WORK_STRANDED` (wire string `"work_stranded"`); `TelemetryCode.eventCodeFor("work_stranded") == 302`.

- [ ] **Step 1: Write the failing test**

Append these two tests inside the existing `TelemetryCodeBindingTest` class, before the closing brace:

```kotlin
    @Test fun work_stranded_maps_to_302() {
        assertEquals(302, TelemetryCode.eventCodeFor("work_stranded"))
    }

    @Test fun work_stranded_wire_string_matches_enum() {
        assertEquals(
            "work_stranded",
            com.klk.hams.diagnostics.DiagnosticType.WORK_STRANDED.wire,
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.TelemetryCodeBindingTest"`
Expected: compilation FAILS with `unresolved reference: WORK_STRANDED`.

- [ ] **Step 3: Add the enum entry**

In `DiagnosticType.kt`, change line 18 from `DEVICE_UNBOUND("device_unbound");` to:

```kotlin
    DEVICE_UNBOUND("device_unbound"),

    /**
     * 302 — device unbound via OTP while still holding unsent harvest.
     * Mutually exclusive with [DEVICE_UNBOUND]: a release emits one or the
     * other, never both. Carries lost_tasks / lost_cuts params.
     */
    WORK_STRANDED("work_stranded");
```

- [ ] **Step 4: Add the code mapping**

In `TelemetryCode.kt`, add after line 23 (`"device_unbound" to 304,`):

```kotlin
        // 302 was `binding_taken` until 2026-07-07 (removed, phone-local only).
        // Reassigned 2026-07-23 to work_stranded. Historical `binding_taken`
        // 302s exist on test units and carry no lost_* params.
        "work_stranded" to 302,
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.TelemetryCodeBindingTest"`
Expected: PASS, 6 tests. `removed_binding_taken_has_no_code` must still pass — we mapped `work_stranded`, not `binding_taken`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/diagnostics/DiagnosticType.kt app/src/main/java/com/klk/hams/push/TelemetryCode.kt app/src/test/java/com/klk/hams/push/TelemetryCodeBindingTest.kt
git commit -m "feat(diagnostics): add 302 work_stranded code"
```

---

### Task 2: Schema — `lost_tasks` / `lost_cuts` columns

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/model/DiagnosticEntity.kt:19`
- Modify: `app/src/main/java/com/klk/hams/data/db/AppDatabase.kt:13,73`
- Modify: `app/src/main/java/com/klk/hams/HamsApp.kt:39`
- Test: `app/src/androidTest/java/com/klk/hams/data/db/DiagnosticMigrationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `DiagnosticEntity.lostTasks: Int?` (column `lost_tasks`), `DiagnosticEntity.lostCuts: Int?` (column `lost_cuts`), both defaulting to `null`; `MIGRATION_5_6`.

- [ ] **Step 1: Write the failing test**

Append this test inside `DiagnosticMigrationTest`, before the closing brace:

```kotlin
    @Test
    fun migrate5to6_addsLostColumns_andPreservesRows() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-test-diag-56"
        ctx.deleteDatabase(name)

        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v5 diagnostics table, as generated by Room before this change.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS diagnostics (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "type TEXT NOT NULL, timestamp TEXT NOT NULL, " +
                            "battery_pct REAL, created_at TEXT NOT NULL, " +
                            "pushed INTEGER NOT NULL DEFAULT 0, " +
                            "lat_decimal REAL, lon_decimal REAL, hdop REAL, " +
                            "satellites INTEGER, speed_kmh INTEGER)"
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            helper.writableDatabase.use { db ->
                db.execSQL(
                    "INSERT INTO diagnostics (type, timestamp, battery_pct, created_at) " +
                        "VALUES ('device_unbound','2026-07-23T00:00:00Z',77.0,'2026-07-23T00:00:00Z')"
                )

                MIGRATION_5_6.migrate(db)

                db.query("SELECT lost_tasks, lost_cuts FROM diagnostics").use { c ->
                    assertEquals(1, c.count)
                    c.moveToFirst()
                    assertTrue(c.isNull(0))   // legacy row has no counts
                    assertTrue(c.isNull(1))
                }
            }
        } finally {
            helper.close()
            ctx.deleteDatabase(name)
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.klk.hams.data.db.DiagnosticMigrationTest"`
Expected: compilation FAILS with `unresolved reference: MIGRATION_5_6`. (Requires a connected device or emulator.)

- [ ] **Step 3: Add the entity columns**

In `DiagnosticEntity.kt`, change line 19 from `@ColumnInfo(name = "speed_kmh")   val speedKmh: Int? = null,` to:

```kotlin
    @ColumnInfo(name = "speed_kmh")   val speedKmh: Int? = null,
    /** 302/304 only: tasks with unsent pushable rows at release time. Null elsewhere. */
    @ColumnInfo(name = "lost_tasks")  val lostTasks: Int? = null,
    /** 302/304 only: unsent `event_code = 179` rows at release time. Null elsewhere. */
    @ColumnInfo(name = "lost_cuts")   val lostCuts: Int? = null,
```

- [ ] **Step 4: Bump the version and add the migration**

In `AppDatabase.kt`, change line 13 from `version = 5,` to `version = 6,`.

Append at the end of the file:

```kotlin
/**
 * Adds diagnostics.lost_tasks / lost_cuts — the leftover counts carried by the
 * 302 work_stranded and 304 device_unbound release markers. Nullable: every
 * other telemetry code leaves them NULL and its frame is unchanged.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE diagnostics ADD COLUMN lost_tasks INTEGER")
        db.execSQL("ALTER TABLE diagnostics ADD COLUMN lost_cuts INTEGER")
    }
}
```

- [ ] **Step 5: Register the migration**

In `HamsApp.kt`, change line 39 from:

```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
```

to:

```kotlin
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            )
```

Add the import alongside the other migration imports at the top of the file: `import com.klk.hams.data.db.MIGRATION_5_6`.

- [ ] **Step 6: Run test to verify it passes**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.klk.hams.data.db.DiagnosticMigrationTest"`
Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/model/DiagnosticEntity.kt app/src/main/java/com/klk/hams/data/db/AppDatabase.kt app/src/main/java/com/klk/hams/HamsApp.kt app/src/androidTest/java/com/klk/hams/data/db/DiagnosticMigrationTest.kt
git commit -m "feat(db): add lost_tasks/lost_cuts to diagnostics (v6)"
```

---

### Task 3: Frame — emit the params when present

**Files:**
- Modify: `app/src/main/java/com/klk/hams/push/IPSFrameBuilder.kt:128-130`
- Test: `app/src/test/java/com/klk/hams/push/TelemetryFrameBuilderTest.kt`

**Interfaces:**
- Consumes: `DiagnosticEntity.lostTasks`, `DiagnosticEntity.lostCuts` (Task 2).
- Produces: `IPSFrameBuilder.telemetryFrame` appends `,lost_tasks:1:<n>` then `,lost_cuts:1:<n>` after `work_count:1:0`, each only when its field is non-null.

- [ ] **Step 1: Write the failing tests**

Append these three tests inside `TelemetryFrameBuilderTest`, before the closing brace:

```kotlin
    @Test fun workStranded_frame_carriesLostParams() {
        val row = DiagnosticEntity(
            id = 4,
            type = "work_stranded",
            timestamp = "2026-07-23T01:17:06Z",
            batteryPct = 78.0,
            createdAt = "x",
            pushed = 0,
            latDecimal = 2.268721,
            lonDecimal = 103.282985,
            hdop = 1.5,
            satellites = 8,
            speedKmh = 0,
            lostTasks = 3,
            lostCuts = 47,
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertEquals(
            "#D#230726;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;" +
                "event_code:1:302,battery:2:78.00,work_count:1:0," +
                "lost_tasks:1:3,lost_cuts:1:47\r\n",
            frame,
        )
    }

    @Test fun deviceUnbound_clean_carriesZeroLostParams() {
        val row = DiagnosticEntity(
            id = 5,
            type = "device_unbound",
            timestamp = "2026-07-23T01:17:06Z",
            batteryPct = 78.0,
            createdAt = "x",
            pushed = 0,
            latDecimal = 2.268721,
            lonDecimal = 103.282985,
            hdop = 1.5,
            satellites = 8,
            speedKmh = 0,
            lostTasks = 0,
            lostCuts = 0,
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertTrue(frame.contains("event_code:1:304"))
        assertTrue(frame.contains("lost_tasks:1:0,lost_cuts:1:0"))
    }

    @Test fun otherCodes_haveNoLostParams() {
        val row = DiagnosticEntity(
            id = 6,
            type = "gps_lost",
            timestamp = "2026-07-23T01:17:06Z",
            batteryPct = 78.0,
            createdAt = "x",
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertTrue(frame.contains("lost_tasks").not())
        assertTrue(frame.contains("lost_cuts").not())
        assertTrue(frame.endsWith("work_count:1:0\r\n"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.TelemetryFrameBuilderTest"`
Expected: `workStranded_frame_carriesLostParams` and `deviceUnbound_clean_carriesZeroLostParams` FAIL — the frame ends at `work_count:1:0`. `otherCodes_haveNoLostParams` and `startMoving_frame_isByteExact` PASS.

- [ ] **Step 3: Append the params conditionally**

In `IPSFrameBuilder.kt`, replace lines 128-130:

```kotlin
        val params = "event_code:1:$code," +
            "battery:2:$batteryStr," +
            "work_count:1:0"
```

with:

```kotlin
        // lost_* ride only on the release markers (302 work_stranded / 304
        // device_unbound). Every other telemetry code leaves them null, so its
        // frame is byte-identical to pre-2026-07-23 output.
        val params = buildString {
            append("event_code:1:$code,")
            append("battery:2:$batteryStr,")
            append("work_count:1:0")
            row.lostTasks?.let { append(",lost_tasks:1:$it") }
            row.lostCuts?.let { append(",lost_cuts:1:$it") }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.push.TelemetryFrameBuilderTest"`
Expected: PASS, 6 tests. `startMoving_frame_isByteExact` must still pass — that is the byte-identity guard.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/push/IPSFrameBuilder.kt app/src/test/java/com/klk/hams/push/TelemetryFrameBuilderTest.kt
git commit -m "feat(push): emit lost_tasks/lost_cuts params on release markers"
```

---

### Task 4: DAO queries — count and strand

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/db/EventDao.kt:59`
- Modify: `app/src/main/java/com/klk/hams/data/db/DiagnosticDao.kt:26`
- Test: `app/src/androidTest/java/com/klk/hams/data/db/DiagnosticDaoTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `EventDao.countUnsentTasks(): Int`, `EventDao.countUnsentCuts(): Int`, `EventDao.strandAllPending(): Int`, `DiagnosticDao.pendingIds(): List<Long>`.

- [ ] **Step 1: Write the failing test**

Append this test inside `DiagnosticDaoTest`, before the closing brace. The class already provides `db` / `dao` fixtures (`@Before setup()`) and imports `DiagnosticEntity`, `runBlocking` and `assertEquals` — use them as-is:

```kotlin
    @Test
    fun pendingIds_returnsOnlyUnpushed_oldestFirst() = runBlocking {
        dao.insert(
            DiagnosticEntity(
                type = "boot", timestamp = "2026-07-23T02:00:00Z",
                batteryPct = 80.0, createdAt = "x", pushed = 0,
            )
        )
        val older = dao.insert(
            DiagnosticEntity(
                type = "gps_lost", timestamp = "2026-07-23T01:00:00Z",
                batteryPct = 80.0, createdAt = "x", pushed = 0,
            )
        )
        dao.insert(
            DiagnosticEntity(
                type = "screen_on", timestamp = "2026-07-23T03:00:00Z",
                batteryPct = 80.0, createdAt = "x", pushed = 1,
            )
        )

        val ids = dao.pendingIds()

        assertEquals(2, ids.size)
        assertEquals(older, ids.first())   // ordered by timestamp ASC
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.klk.hams.data.db.DiagnosticDaoTest"`
Expected: compilation FAILS with `unresolved reference: pendingIds`.

- [ ] **Step 3: Add the DiagnosticDao query**

In `DiagnosticDao.kt`, add after line 26 (`suspend fun pushedState(id: Long): Int?`):

```kotlin
    // Snapshot of every unpushed row, taken before a release drain so the caller
    // can reject whatever did not land. Any row still pending after the drain
    // belongs to the unit being left and must never push under the next one.
    @Query("SELECT id FROM diagnostics WHERE pushed = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun pendingIds(): List<Long>
```

- [ ] **Step 4: Add the EventDao queries**

In `EventDao.kt`, add after line 59 (the closing brace of `countRejectedForTask`, before the interface's closing brace):

```kotlin
    // --- Release-time leftover accounting (302 work_stranded, 2026-07-23) ---

    // Tasks that still hold unsent rows. Counted over `events`, not `tasks`, so
    // already-stranded work (pushed = 2) is never re-reported on a later release.
    @Query("SELECT COUNT(DISTINCT task_id) FROM events WHERE pushed = 0")
    suspend fun countUnsentTasks(): Int

    // The harvest figure: 179 only. Counting the full pushable set would let
    // heartbeats dominate (a phone with 6 cuts and 400 beacons would report 406).
    @Query("SELECT COUNT(*) FROM events WHERE pushed = 0 AND event_code = 179")
    suspend fun countUnsentCuts(): Int

    // Marks every still-pending row permanently rejected. Applied at release only
    // when the 302/304 marker landed, so the receipt and the kill are atomic.
    // Covers 180 and 35 as well as 179 — they belong to the departing unit too.
    @Query("UPDATE events SET pushed = 2 WHERE pushed = 0")
    suspend fun strandAllPending(): Int
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.klk.hams.data.db.DiagnosticDaoTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/db/EventDao.kt app/src/main/java/com/klk/hams/data/db/DiagnosticDao.kt app/src/androidTest/java/com/klk/hams/data/db/DiagnosticDaoTest.kt
git commit -m "feat(db): add leftover-count and strand queries"
```

---

### Task 5: Repository — `countUnsentWork` and `strandUnsentWork`

**Files:**
- Modify: `app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt:37-59` (recordDiagnostic), `:451` (after `diagnosticPushedState`)
- Test: `app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt`

**Interfaces:**
- Consumes: `EventDao.countUnsentTasks`, `EventDao.countUnsentCuts`, `EventDao.strandAllPending`, `DiagnosticDao.pendingIds` (Task 4); `DiagnosticEntity.lostTasks` / `lostCuts` (Task 2).
- Produces:
  - `data class TaskRepository.UnsentWork(val tasks: Int, val cuts: Int)`
  - `suspend fun countUnsentWork(): UnsentWork`
  - `suspend fun strandUnsentWork(): Int` — returns rows stranded
  - `suspend fun pendingTelemetryIds(): List<Long>`
  - `recordDiagnostic(...)` gains `lostTasks: Int? = null, lostCuts: Int? = null`

- [ ] **Step 1: Write the failing test**

Append these tests inside `TaskRepositoryTest`, before the closing brace. The class already provides `db` / `repo` fixtures and the private helpers `insertPendingTask(): Long`, `insertEvent(taskId: Long, pushed: Int, eventCode: Int)` and `taskById(id: Long, status: String): Task?` — use those rather than driving the task lifecycle through `recordPlus`, which needs a GPS snapshot:

```kotlin
    // ---- 302 work_stranded — leftover accounting (2026-07-23) ----

    @Test fun countUnsentWork_countsTasksAndCutsSeparately() = runBlocking {
        val taskA = insertPendingTask()
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 0, eventCode = 179)
        insertEvent(taskA, pushed = 0, eventCode = 35)    // beacon — not a cut
        val taskB = insertPendingTask()
        insertEvent(taskB, pushed = 0, eventCode = 179)
        insertEvent(taskB, pushed = 1, eventCode = 179)   // already uploaded

        val unsent = repo.countUnsentWork()

        assertEquals(2, unsent.tasks)
        assertEquals(3, unsent.cuts)   // 179 at pushed = 0 only
    }

    @Test fun countUnsentWork_ignoresAlreadyStrandedRows() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 2, eventCode = 179)

        val unsent = repo.countUnsentWork()

        // A later release must not re-report work stranded by an earlier one.
        assertEquals(0, unsent.tasks)
        assertEquals(0, unsent.cuts)
    }

    @Test fun strandUnsentWork_marksRowsRejected_andTaskFailed() = runBlocking {
        val taskId = insertPendingTask()
        insertEvent(taskId, pushed = 0, eventCode = 179)
        insertEvent(taskId, pushed = 0, eventCode = 35)

        val stranded = repo.strandUnsentWork()

        assertEquals(2, stranded)                       // 35 is stranded too
        assertEquals(0, repo.countUnsentWork().cuts)
        assertEquals(0, repo.pendingTasks().size)
        assertNotNull(taskById(taskId, "failed"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.klk.hams.data.repository.TaskRepositoryTest"`
Expected: compilation FAILS with `unresolved reference: countUnsentWork`.

- [ ] **Step 3: Extend `recordDiagnostic`**

In `TaskRepository.kt`, replace the signature and body at lines 37-58 with:

```kotlin
    suspend fun recordDiagnostic(
        type: com.klk.hams.diagnostics.DiagnosticType,
        batteryPct: Double?,
        snapshot: LocationSnapshot? = null,
        timestampIso: String? = null,
        pushed: Int = 0,
        lostTasks: Int? = null,
        lostCuts: Int? = null,
    ): Long {
        val now = clock.nowUtcIso()
        return diagnosticDao.insert(
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
                lostTasks = lostTasks,
                lostCuts = lostCuts,
            )
        )
    }
```

- [ ] **Step 4: Add the leftover surface**

In `TaskRepository.kt`, add after line 450 (the closing brace of `diagnosticPushedState`):

```kotlin
    /** Leftover accounting at release time (302 work_stranded, 2026-07-23). */
    data class UnsentWork(val tasks: Int, val cuts: Int)

    /**
     * What this device would fail to deliver if it unbound right now.
     *
     * Both figures are counted over `events`, not `tasks`, so already-stranded
     * work (`pushed = 2`) is never re-reported on a later release. `cuts` counts
     * `event_code = 179` only — that is the harvest figure a report would have
     * produced, and matching it keeps the loss metric and the harvest metric on
     * the same arithmetic.
     */
    suspend fun countUnsentWork(): UnsentWork = UnsentWork(
        tasks = eventDao.countUnsentTasks(),
        cuts = eventDao.countUnsentCuts(),
    )

    /** Unpushed telemetry ids, snapshotted before a release drain. */
    suspend fun pendingTelemetryIds(): List<Long> = diagnosticDao.pendingIds()

    /**
     * Permanently rejects every still-pending event row and drives the owning
     * tasks to their terminal state. Called at release **only when the 302/304
     * marker landed**, so the receipt and the kill are atomic — destroying
     * harvest with no record is worse than misfiling it.
     *
     * Rows are marked `pushed = 2`, not deleted. They survive in SQLite for
     * `AppConfig.SQLITE_RETENTION_DAYS` and are recoverable by a DB pull.
     *
     * Returns the number of event rows stranded.
     */
    suspend fun strandUnsentWork(): Int = db.withTransaction {
        val affected = taskDao.pendingTasks().map { it.id }
        val stranded = eventDao.strandAllPending()
        for (taskId in affected) markTaskTerminalState(taskId)
        stranded
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.klk.hams.data.repository.TaskRepositoryTest"`
Expected: PASS. All pre-existing tests in the class must still pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/data/repository/TaskRepository.kt app/src/androidTest/java/com/klk/hams/data/repository/TaskRepositoryTest.kt
git commit -m "feat(repo): countUnsentWork + strandUnsentWork for release markers"
```

---

### Task 6: `ProvisioningEvents` — pick the code, widen the reject guard

**Files:**
- Modify: `app/src/main/java/com/klk/hams/provisioning/ProvisioningEvents.kt:33-47`
- Test: Create `app/src/test/java/com/klk/hams/provisioning/ReleaseTypeTest.kt`

**Interfaces:**
- Consumes: `TaskRepository.UnsentWork`, `pendingTelemetryIds`, `markTelemetryRejected`, `diagnosticPushedState` (Task 5); `DiagnosticType.WORK_STRANDED` (Task 1).
- Produces:
  - `ProvisioningEvents.releaseTypeFor(unsent: TaskRepository.UnsentWork): DiagnosticType` — pure, JVM-testable
  - `suspend fun ProvisioningEvents.recordAndPushRelease(app: HamsApp, uniqueId: String, unsent: TaskRepository.UnsentWork): Boolean` — returns whether the marker landed
  - `suspend fun ProvisioningEvents.flushAndRelease(app: HamsApp, uniqueId: String): Boolean` — the whole sequence; the only thing call sites invoke
  - `recordAndPushUnbound` is **removed** (both call sites move to `flushAndRelease` in Task 7); `recordAndPushBound` is unchanged

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/klk/hams/provisioning/ReleaseTypeTest.kt`:

```kotlin
package com.klk.hams.provisioning

import com.klk.hams.data.repository.TaskRepository
import com.klk.hams.diagnostics.DiagnosticType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTypeTest {

    @Test fun cleanRelease_emits304() {
        assertEquals(
            DiagnosticType.DEVICE_UNBOUND,
            ProvisioningEvents.releaseTypeFor(TaskRepository.UnsentWork(tasks = 0, cuts = 0)),
        )
    }

    @Test fun strandedCuts_emit302() {
        assertEquals(
            DiagnosticType.WORK_STRANDED,
            ProvisioningEvents.releaseTypeFor(TaskRepository.UnsentWork(tasks = 3, cuts = 47)),
        )
    }

    @Test fun heartbeatsOnly_emit304_becauseNoHarvestIsLost() {
        // A task holding only unsent beacons has no harvest to lose. 302 means
        // "cuts lost"; emitting it here would raise a false alarm.
        assertEquals(
            DiagnosticType.DEVICE_UNBOUND,
            ProvisioningEvents.releaseTypeFor(TaskRepository.UnsentWork(tasks = 1, cuts = 0)),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ReleaseTypeTest"`
Expected: compilation FAILS with `unresolved reference: releaseTypeFor`.

- [ ] **Step 3: Replace `recordAndPushUnbound`**

In `ProvisioningEvents.kt`, replace lines 33-47 (the whole `recordAndPushUnbound` function and its KDoc) with:

```kotlin
    /**
     * Pure: which marker a device-initiated release emits. Mutually exclusive —
     * a release sends 302 or 304, never both. Gated on `cuts`, not `tasks`:
     * 302 means harvest was lost, and a task holding only unsent beacons has
     * none to lose.
     */
    fun releaseTypeFor(unsent: TaskRepository.UnsentWork): DiagnosticType =
        if (unsent.cuts > 0) DiagnosticType.WORK_STRANDED else DiagnosticType.DEVICE_UNBOUND

    /**
     * Record + push the release marker to [uniqueId] (the unit being left)
     * BEFORE the caller clears the binding.
     *
     * Emits 302 `work_stranded` with the leftover counts, or 304
     * `device_unbound` with `0/0`. Both carry the counts so a clean release is
     * a positive assertion rather than an absence.
     *
     * Every telemetry row that fails to land is marked rejected, not just this
     * one: `drainTelemetry` sends the whole pending table, and any row left
     * `pushed = 0` here would push under the NEXT unit after `store.clear()`.
     *
     * @return true if the marker reached the gateway. The caller strands the
     *   cut rows only on true — killing harvest with no receipt is worse than
     *   misfiling it.
     */
    suspend fun recordAndPushRelease(
        app: HamsApp,
        uniqueId: String,
        unsent: TaskRepository.UnsentWork,
    ): Boolean {
        val id = app.repository.recordDiagnostic(
            type = releaseTypeFor(unsent),
            batteryPct = BindingRevalidator.readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = 0,
            lostTasks = unsent.tasks,
            lostCuts = unsent.cuts,
        )
        val pendingIds = app.repository.pendingTelemetryIds()
        drainTelemetry(app, uniqueId)
        for (rowId in pendingIds) {
            if (app.repository.diagnosticPushedState(rowId) != 1) {
                app.repository.markTelemetryRejected(rowId)
            }
        }
        return app.repository.diagnosticPushedState(id) == 1
    }

    /**
     * The complete device-initiated release sequence, shared by every call site
     * so the ordering cannot drift between them:
     *
     *   1. finalize the active task — it is invisible to every count and every
     *      flush until it becomes pending (issue A3)
     *   2. count what would not be delivered
     *   3. push 302 or 304 to [uniqueId], the unit being LEFT — this must happen
     *      before the caller stores a new unit or clears the binding
     *   4. strand the rows, only if the marker landed
     *
     * @return true if the marker reached the gateway.
     */
    suspend fun flushAndRelease(app: HamsApp, uniqueId: String): Boolean {
        app.repository.finalizeActiveTaskForRelease()
        val unsent = app.repository.countUnsentWork()
        val landed = recordAndPushRelease(app, uniqueId, unsent)
        if (landed) app.repository.strandUnsentWork()
        return landed
    }
```

Add the import `import com.klk.hams.data.repository.TaskRepository` at the top of the file.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.klk.hams.provisioning.ReleaseTypeTest"`
Expected: PASS, 3 tests. `:app:assembleDebug` will now FAIL — both `AdminSheet.kt:95` and `PairingScreen.kt:307` still call the removed `recordAndPushUnbound`. Task 7 fixes both; do not commit a broken build without completing Task 7.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/klk/hams/provisioning/ProvisioningEvents.kt app/src/test/java/com/klk/hams/provisioning/ReleaseTypeTest.kt
git commit -m "feat(provisioning): emit 302/304 release marker with leftover counts"
```

---

### Task 7: Both release call sites

**Files:**
- Modify: `app/src/main/java/com/klk/hams/ui/onboarding/AdminSheet.kt:91-113`
- Modify: `app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt:305-307`

**Interfaces:**
- Consumes: `ProvisioningEvents.flushAndRelease` (Task 6).
- Produces: nothing consumed by later tasks.

There are **two** device-initiated release paths, and both must move together — Task 6 removed the function they currently call, so the build is red until both are done.

`PairingScreen.ReleaseAndBind` is the higher-risk of the two: it releases one unit and binds another in a single action, which is precisely the sequence that produced the A1 mis-attribution defect on 2026-07-10. The `flushAndRelease` call must sit **before** `manualClaim`, because `completePairing` stores the new unit id and every later push would use it.

- [ ] **Step 1: Replace the AdminSheet release body**

In `AdminSheet.kt`, replace lines 91-113 (the `scope.launch { when (val release = ...) { ... } }` block) with:

```kotlin
            scope.launch {
                // Shared by Success and NotFound: both end the binding, so both
                // must flush the marker under the OLD unit before store.clear().
                suspend fun finishRelease() {
                    ProvisioningEvents.flushAndRelease(app, id)
                    store.clear()
                    onReset()
                }

                when (val release = client.release(id, fp, adminCode)) {
                    ReleaseResult.Success -> finishRelease()
                    // 404/409 — not found, or not the owner. The binding still ends
                    // locally, and this is the messy path most likely to be carrying
                    // unsent work, so it gets the same marker (was silent before).
                    ReleaseResult.NotFound -> finishRelease()
                    ReleaseResult.AdminAuthFailed -> {
                        rememberAdminFailure(releaseFailureMessage(release))
                        busy = false
                    }
                    else -> {
                        status = releaseFailureMessage(release)
                        adminAction = null
                        busy = false
                    }
                }
            }
```

- [ ] **Step 2: Replace the PairingScreen ReleaseAndBind marker call**

In `PairingScreen.kt`, replace lines 305-307:

```kotlin
                                ReleaseResult.Success -> {
                                    // 304 for the unit we just released, before re-binding.
                                    ProvisioningEvents.recordAndPushUnbound(app, action.ownedUnit)
```

with:

```kotlin
                                ReleaseResult.Success -> {
                                    // 302/304 for the unit we just released, BEFORE
                                    // re-binding. This is the release-then-rebind
                                    // sequence that produced the A1 mis-attribution
                                    // defect (2026-07-10): any row left pending here
                                    // would push under the unit claimed on the next
                                    // line. flushAndRelease strands them instead.
                                    ProvisioningEvents.flushAndRelease(app, action.ownedUnit)
```

- [ ] **Step 3: Build to verify compilation**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. A remaining `unresolved reference: recordAndPushUnbound` means one of the two call sites was missed — grep for it: `git grep -n recordAndPushUnbound` must return nothing.

- [ ] **Step 4: Run the full unit suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 256 existing + 8 new tests, 0 failures.

- [ ] **Step 5: Run lint**

Run: `.\gradlew.bat :app:lintDebug`
Expected: BUILD SUCCESSFUL, 0 errors.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/klk/hams/ui/onboarding/AdminSheet.kt app/src/main/java/com/klk/hams/ui/onboarding/PairingScreen.kt
git commit -m "feat(provisioning): flush release marker on both release paths"
```

---

### Task 8: Dictionary v1.5

**Files:**
- Modify: `docs/HAMS_EVENT_CODE_DICTIONARY.md:36-49`, `:89-103`, `:502`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Un-strike 302 in the master table**

In the "All Event Codes at a Glance" table, delete the struck-through 302 row at line 47 and insert this row after the `301` row at line 36:

```markdown
| **302** | `work_stranded` (OTP release while holding unsent cuts) | Provisioning (3xx) | **Yes** |
```

- [ ] **Step 2: Update the totals line**

Change line 49 from `**Totals:** 16 pushed ...` to:

```markdown
**Totals:** 17 pushed (`179`/`180`/`35` + 10 Option B + `301`/`302`/`303`/`304`) · 8 local-only.
```

- [ ] **Step 3: Add the detail entry**

In the 3xx detail section, insert after the `301` entry (line 89):

```markdown
| **302** | `work_stranded` — device unbound via OTP while still holding unsent harvest. Mutually exclusive with `304`: a release emits one or the other. Carries `lost_tasks` and `lost_cuts`. | **Yes** (pushed to the unit being left, before the binding clears) |
```

- [ ] **Step 4: Add the reporting notes**

Append to the notes block after line 103:

```markdown
- **`304` no longer counts all unbinds.** After 2026-07-23 a release emits `302`
  when it leaves harvest behind and `304` when it does not. **Total releases =
  `302` + `304`.** A report counting only `304` silently omits every problem
  release.
- **Both release markers carry `lost_tasks` and `lost_cuts`** (integers, IPS
  param type `1`). `304` reports `0/0` — a positive assertion that the queue was
  empty, not an absence. `301` and `303` carry neither: `301` is recorded before
  its flush runs, so any count taken then is stale by design.
- **`lost_cuts` counts `event_code = 179` rows only**, matching the harvest rule
  ("count rows where `ffb_cut = 1`"). It is not `SUM(net_count)` — those diverge
  whenever a worker uses `−`.
- **Historical `302` collision.** `302` meant `binding_taken` until it was
  removed on 2026-07-07, and at least one such message exists in Wialon
  (`HAMS_TEST_003`, 2026-07-09 02:32:57). Old `302`s carry no `lost_*` params;
  new ones always do. Filter on param presence or date-cut before 2026-07-23.
```

- [ ] **Step 5: Add the changelog row**

Append to the version history table after line 502:

```markdown
| 1.5 | 2026-07-23 | Reassigned `302` to `work_stranded` — emitted at a device-initiated OTP release when the phone leaves with unsent cuts, mutually exclusive with `304`. Both release markers now carry `lost_tasks` / `lost_cuts` params (`304` = `0/0`). Stranded event rows are marked `pushed = 2` when the marker lands, so they can never later push under the next unit. `ReleaseResult.NotFound` now emits a marker too (was silent). Note: historical `binding_taken` `302`s exist on test units and carry no `lost_*` params. |
```

- [ ] **Step 6: Commit**

```bash
git add docs/HAMS_EVENT_CODE_DICTIONARY.md
git commit -m "docs(event-codes): v1.5 — 302 work_stranded + lost_* params"
```

---

## Device Verification

Not done until this passes on a physical device.

- [ ] **V1 — clean release emits 304 with zeros.** Pair to a test unit. Record no cuts. OTP-release. Confirm in Wialon: `event_code=304, lost_tasks=0, lost_cuts=0`.
- [ ] **V2 — stranded release emits 302.** Pair, record 3 cuts with no network, hold NEW TASK, then enable network and OTP-release without waiting for a push. Confirm in Wialon: `event_code=302, lost_tasks=1, lost_cuts=3`.
- [ ] **V3 — the rows are dead.** After V2, pair the same handset to a **different** unit and enable Wi-Fi. Confirm in Wialon: **no cuts arrive on the new unit.** Confirm on device: `adb shell run-as com.klk.hams.debug sqlite3 databases/hams.db "SELECT pushed, COUNT(*) FROM events GROUP BY pushed"` shows the 3 rows at `pushed = 2`.
- [ ] **V4 — other codes unchanged.** Trigger a `screen_off` and confirm its Wialon frame still ends at `work_count=0` with no `lost_*` params.
- [ ] **V5 — migration on real data.** Install the new build over an existing v5 database holding cuts. Confirm the app opens, the task cache is intact, and no rows were lost.
- [ ] **V6 — the A1 regression, on the rebind path.** This is the exact 2026-07-10 sequence. Pair to unit A, record cuts with no network, hold NEW TASK. On the pairing screen use **Release and bind** to move straight to unit B. Enable Wi-Fi. Confirm in Wialon: `302` on **unit A** with the correct counts, and **no cuts on unit B**. This is the highest-value check in the list — it is the defect reproducing, or not.

---

## Out of Scope

Stated so nothing is assumed. Each was raised and deliberately deferred:

- **Blocking the release when work is unsent** (spec §5.2, issues A2/A3/A4). 302 reports the loss; it does not prevent it.
- **Per-unit push delivery** — grouping pending events by `tasks.device_id` and logging in per unit so stranded cuts reach their original unit. The recovery fix, versus this diagnosis fix.
- **The offline blind spot.** An n8n release while the phone has no network, with the unit reclaimed before the phone returns, produces `bound_other` → logout with no push and no marker. Nothing on the device can cover it; needs the `provisioning_events` audit table (spec §5.4).
- **Force-stop, crash, flat battery.** No code runs.
- **`lost_since`** (oldest stranded `task_date`). Declined 2026-07-23 — totals suffice.

---

*Written 2026-07-23 by WYH. Design agreed in session; supersedes nothing.*
