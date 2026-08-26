package com.klk.hams.push

import com.klk.hams.data.model.DiagnosticEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFrameBuilderTest {
    @Test fun startMoving_frame_isByteExact() {
        val row = DiagnosticEntity(
            id = 1,
            type = "start_moving",
            timestamp = "2026-07-02T01:17:06Z",
            batteryPct = 91.0,
            createdAt = "x",
            pushed = 0,
            latDecimal = 2.268721,
            lonDecimal = 103.282985,
            hdop = 1.5,
            satellites = 8,
            speedKmh = 4,
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertEquals(
            "#D#020726;011706;0216.1233;N;10316.9791;E;4;0;10;8;1.5;0;0;;NA;" +
                "event_code:1:42,battery:2:91.00,work_count:1:0\r\n",
            frame,
        )
    }

    @Test fun noGps_sendsZeroIsland_withBootCode() {
        val row = DiagnosticEntity(
            id = 2,
            type = "boot",
            timestamp = "2026-07-02T01:17:06Z",
            batteryPct = 88.0,
            createdAt = "x",
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertTrue(frame.contains(";0216.").not())
        assertTrue(frame.contains("event_code:1:29"))
        assertTrue(frame.contains("diag_type").not())
    }

    @Test fun unknownType_isRejected() {
        val row = DiagnosticEntity(
            id = 3,
            type = "mystery",
            timestamp = "2026-07-02T01:17:06Z",
            batteryPct = 88.0,
            createdAt = "x",
        )

        assertTrue(IPSFrameBuilder.telemetryFrame(row).isFailure)
    }

    @Test fun workStranded_frame_carriesLostCutsOnly() {
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
            lostTasks = null,
            lostCuts = 47,
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertEquals(
            "#D#230726;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;" +
                "event_code:1:302,battery:2:78.00,work_count:1:0,lost_cuts:1:47\r\n",
            frame,
        )
    }

    @Test fun deviceUnbound_clean_carriesNoLostParams() {
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
            lostTasks = null,
            lostCuts = null,
        )

        val frame = IPSFrameBuilder.telemetryFrame(row).getOrThrow()

        assertTrue(frame.contains("event_code:1:304"))
        assertTrue(frame.contains("lost_tasks").not())
        assertTrue(frame.contains("lost_cuts").not())
        assertTrue(frame.endsWith("work_count:1:0\r\n"))
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
}
