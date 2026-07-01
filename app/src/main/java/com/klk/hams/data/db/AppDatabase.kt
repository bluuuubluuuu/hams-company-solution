package com.klk.hams.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.klk.hams.data.model.DiagnosticEntity
import com.klk.hams.data.model.EventEntity
import com.klk.hams.data.model.Task

@Database(
    entities = [Task::class, EventEntity::class, DiagnosticEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun eventDao(): EventDao
    abstract fun diagnosticDao(): DiagnosticDao
}

/**
 * Adds tasks.task_date (MYT calendar day, YYYY-MM-DD).
 *
 * Backfill: convert each existing row's UTC `started_at` to MYT (UTC+8, no DST)
 * and take the date portion. SQLite has no IANA timezone library, but MYT is a
 * fixed +8 offset, so `datetime(started_at, '+8 hours')` is exact.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN task_date TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "UPDATE tasks SET task_date = substr(datetime(started_at, '+8 hours'), 1, 10)"
        )
    }
}

/**
 * Adds events.speed (GPS ground speed, integer km/h, nullable). Existing rows
 * get NULL â†’ IPSFrameBuilder emits 0 for them, matching prior behaviour.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN speed INTEGER")
    }
}

/** Adds the diagnostics table (device lifecycle audit; local-only). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS diagnostics (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "timestamp TEXT NOT NULL, " +
                "battery_pct REAL, " +
                "created_at TEXT NOT NULL)"
        )
    }
}
