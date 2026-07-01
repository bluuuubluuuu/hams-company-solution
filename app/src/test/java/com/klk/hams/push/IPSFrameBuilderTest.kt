package com.klk.hams.push

import com.klk.hams.data.model.EventEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class IPSFrameBuilderTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun canonicalEvent(): EventEntity = EventEntity(
        id = 1,
        taskId = 1,
        eventType = "plus",
        eventCode = 179,
        timestamp = "2026-04-23T01:17:06Z",
        latDecimal = 2.268721,
        lonDecimal = 103.282985,
        hdop = 1.5,
        satellites = 8,
        batteryPct = 91.0,
        workCount = 1,
        countAfter = 1,
        pushed = 0,
        createdAt = "2026-04-23T01:17:06Z"
    )

    @Test fun `loginFrame matches IPS spec`() {
        assertEquals(
            "#L#HAMS_TEST_001;NA\r\n",
            IPSFrameBuilder.loginFrame("HAMS_TEST_001")
        )
    }

    @Test fun `canonical V6 plus event produces byte-exact frame`() {
        val expected =
            "#D#230426;011706;0216.1233;N;10316.9791;E;0;0;10;8;1.5;0;0;;NA;" +
                "ffb_cut:1:1,battery:2:91.00,event_code:1:179,work_count:1:1\r\n"
        val actual = IPSFrameBuilder.dataFrame(canonicalEvent()).getOrThrow()
        assertEquals(expected, actual)
    }

    @Test fun `plus code 179 maps to ffb_cut 1`() {
        val frame = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(eventCode = 179)
        ).getOrThrow()
        assertTrue(frame.contains("ffb_cut:1:1"))
        assertTrue(frame.contains("event_code:1:179"))
    }

    @Test fun `productive minus 180 maps to ffb_cut 0`() {
        val frame = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(eventCode = 180, workCount = 4)
        ).getOrThrow()
        assertTrue(frame.contains("ffb_cut:1:0"))
        assertTrue(frame.contains("event_code:1:180"))
        assertTrue(frame.contains("work_count:1:4"))
    }

    @Test fun `heartbeat 35 maps to ffb_cut 0`() {
        val frame = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(eventCode = 35, workCount = 5)
        ).getOrThrow()
        assertTrue(frame.contains("ffb_cut:1:0"))
        assertTrue(frame.contains("event_code:1:35"))
        assertTrue(frame.contains("work_count:1:5"))
    }

    @Test fun `null latitude returns failure with typed error`() {
        val result = IPSFrameBuilder.dataFrame(canonicalEvent().copy(latDecimal = null))
        assertTrue("expected failure", result.isFailure)
        assertTrue(
            "expected MissingCoordinates, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IPSFrameBuilder.FrameError.MissingCoordinates
        )
    }

    @Test fun `null longitude returns failure with typed error`() {
        val result = IPSFrameBuilder.dataFrame(canonicalEvent().copy(lonDecimal = null))
        assertTrue("expected failure", result.isFailure)
        assertTrue(
            "expected MissingCoordinates, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IPSFrameBuilder.FrameError.MissingCoordinates
        )
    }

    @Test fun `frame builder never substitutes 0 0 for null coords`() {
        val result = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(latDecimal = null, lonDecimal = null)
        )
        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
    }

    @Test fun `battery formats with two decimal places`() {
        val frame = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(batteryPct = 87.5)
        ).getOrThrow()
        assertTrue(frame.contains("battery:2:87.50"))
    }

    @Test fun `battery format always shows two decimals across values`() {
        for (value in listOf(0.0, 12.0, 50.5, 91.0, 99.99, 100.0)) {
            val frame = IPSFrameBuilder.dataFrame(
                canonicalEvent().copy(batteryPct = value)
            ).getOrThrow()
            assertTrue(
                "battery:$value should match \\d+\\.\\d{2}",
                Regex("battery:2:\\d+\\.\\d{2}").containsMatchIn(frame)
            )
        }
    }

    @Test fun `course field is always 0 in V6 frames`() {
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent()).getOrThrow()
        val payload = frame.removePrefix("#D#").removeSuffix("\r\n")
        val fields = payload.split(";")
        // 1-indexed field 8 (course) -> 0-indexed slot 7
        assertEquals("0", fields[7])
    }

    @Test fun `speed populates field 7`() {
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent().copy(speed = 14)).getOrThrow()
        val fields = frame.removePrefix("#D#").removeSuffix("\r\n").split(";")
        // 1-indexed field 7 (speed) -> 0-indexed slot 6
        assertEquals("14", fields[6])
    }

    @Test fun `null speed falls back to 0 in field 7`() {
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent().copy(speed = null)).getOrThrow()
        val fields = frame.removePrefix("#D#").removeSuffix("\r\n").split(";")
        assertEquals("0", fields[6])
    }

    @Test fun `frame has exactly 16 semicolon-separated fields`() {
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent()).getOrThrow()
        val payload = frame.removePrefix("#D#").removeSuffix("\r\n")
        val fields = payload.split(";")
        assertEquals(16, fields.size)
    }

    @Test fun `adc field is empty between outputs and ibutton`() {
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent()).getOrThrow()
        // outputs(0) ; adc(empty) ; ibutton(NA) ;
        assertTrue(frame.contains(";;NA;"))
    }

    @Test fun `ibutton field is NA`() {
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent()).getOrThrow()
        val payload = frame.removePrefix("#D#").removeSuffix("\r\n")
        val fields = payload.split(";")
        assertEquals("NA", fields[14])
    }

    @Test fun `frame is locale-independent`() {
        Locale.setDefault(Locale.GERMANY)
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent()).getOrThrow()
        assertTrue(frame.contains("0216.1233"))
        assertTrue(frame.contains("10316.9791"))
        assertTrue(frame.contains("battery:2:91.00"))
        assertFalse("frame must not contain comma-decimals", frame.contains("91,00"))
    }

    @Test fun `null hdop and null satellites use zero fallbacks`() {
        val frame = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(hdop = null, satellites = null)
        ).getOrThrow()
        val payload = frame.removePrefix("#D#").removeSuffix("\r\n")
        val fields = payload.split(";")
        // satellites field 10 -> idx 9
        assertEquals("0", fields[9])
        // hdop field 11 -> idx 10
        assertEquals("0.0", fields[10])
    }

    @Test fun `invalid timestamp returns typed error`() {
        val result = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(timestamp = "not-a-real-timestamp")
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IPSFrameBuilder.FrameError.InvalidTimestamp)
    }

    @Test fun `frame ends with CRLF`() {
        val frame = IPSFrameBuilder.dataFrame(canonicalEvent()).getOrThrow()
        assertTrue(frame.endsWith("\r\n"))
    }

    @Test fun `event code 999 fails with UnknownEventCode`() {
        val result = IPSFrameBuilder.dataFrame(canonicalEvent().copy(eventCode = 999))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("expected UnknownEventCode, got $err", err is IPSFrameBuilder.FrameError.UnknownEventCode)
        assertEquals(999, (err as IPSFrameBuilder.FrameError.UnknownEventCode).code)
    }

    @Test fun `event code 0 fails with UnknownEventCode`() {
        val result = IPSFrameBuilder.dataFrame(canonicalEvent().copy(eventCode = 0))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IPSFrameBuilder.FrameError.UnknownEventCode)
    }

    @Test fun `every outbound-approved code in v1_2 builds a frame`() {
        val validCodes = listOf(35, 179, 180)
        for (code in validCodes) {
            val result = IPSFrameBuilder.dataFrame(
                canonicalEvent().copy(eventCode = code, workCount = 1)
            )
            assertTrue("code $code should build a frame", result.isSuccess)
            val frame = result.getOrThrow()
            assertTrue("code $code should appear in params", frame.contains("event_code:1:$code"))
        }
    }

    @Test fun `local-only codes are rejected as UnknownEventCode under v1_2`() {
        // Per dictionary v1.2: 281 (new task), 283/284 (auto-save), 291/292
        // (battery edges), 293 (GPS degraded), and legacy 279/280 (dev counting)
        // must never reach the wire.
        val localOnlyCodes = listOf(281, 283, 284, 291, 292, 293, 279, 280)
        for (code in localOnlyCodes) {
            val result = IPSFrameBuilder.dataFrame(
                canonicalEvent().copy(eventCode = code, workCount = 5)
            )
            assertTrue("code $code should fail", result.isFailure)
            val err = result.exceptionOrNull()
            assertTrue(
                "code $code expected UnknownEventCode, got $err",
                err is IPSFrameBuilder.FrameError.UnknownEventCode
            )
            assertEquals(
                code,
                (err as IPSFrameBuilder.FrameError.UnknownEventCode).code
            )
        }
    }

    @Test fun `unknown code rejection takes precedence over missing coords`() {
        // Validation should reject the unknown code without leaking a MissingCoordinates result.
        val result = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(eventCode = 12345, latDecimal = null, lonDecimal = null)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IPSFrameBuilder.FrameError.UnknownEventCode)
    }

    @Test fun `params block ordering is fixed`() {
        val frame = IPSFrameBuilder.dataFrame(
            canonicalEvent().copy(eventCode = 180, workCount = 3, batteryPct = 50.0)
        ).getOrThrow()
        val params = frame.substringAfter(";NA;").removeSuffix("\r\n")
        assertEquals("ffb_cut:1:0,battery:2:50.00,event_code:1:180,work_count:1:3", params)
    }
}
