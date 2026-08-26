package com.klk.hams.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.klk.hams.data.model.DiagnosticEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: DiagnosticDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.diagnosticDao()
    }

    @After
    fun tear() {
        db.close()
    }

    @Test
    fun pending_returnsOnlyUnpushed_oldestFirst() = runBlocking {
        dao.insert(
            DiagnosticEntity(
                type = "boot",
                timestamp = "2026-07-02T00:00:02Z",
                batteryPct = 90.0,
                createdAt = "x",
                pushed = 0,
            )
        )
        dao.insert(
            DiagnosticEntity(
                type = "screen_on",
                timestamp = "2026-07-02T00:00:01Z",
                batteryPct = 90.0,
                createdAt = "x",
                pushed = 0,
            )
        )
        dao.insert(
            DiagnosticEntity(
                type = "boot",
                timestamp = "2026-07-02T00:00:00Z",
                batteryPct = 90.0,
                createdAt = "x",
                pushed = 1,
            )
        )

        val pending = dao.pending()

        assertEquals(2, pending.size)
        assertEquals("screen_on", pending.first().type)
    }

    @Test
    fun markPushed_and_markRejected_flip_flag() = runBlocking {
        val id = dao.insert(
            DiagnosticEntity(type = "boot", timestamp = "t", batteryPct = 90.0, createdAt = "x")
        )
        dao.markPushed(id)
        assertEquals(0, dao.pending().size)

        val id2 = dao.insert(
            DiagnosticEntity(type = "boot", timestamp = "t", batteryPct = 90.0, createdAt = "x")
        )
        dao.markRejected(id2)
        assertEquals(0, dao.pending().size)
    }

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
}
