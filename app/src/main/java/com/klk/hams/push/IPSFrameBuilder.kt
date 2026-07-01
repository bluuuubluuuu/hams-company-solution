package com.klk.hams.push

import com.klk.hams.data.model.EventEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds Wialon IPS v1.1 frames for HAMS V2 (V6 16-field full form with named params).
 *
 * Login frame:  `#L#<unique_id>;NA\r\n`
 *
 * Data frame:
 * ```
 * #D#DDMMYY;HHMMSS;DDMM.MMMM;N;DDDMM.MMMM;E;speed;course;alt;sats;hdop;
 *    inputs;outputs;adc;ibutton;params\r\n
 * ```
 *
 * The params block carries the V6 quartet:
 * ```
 * ffb_cut:1:<0|1>,battery:2:<pct>,event_code:1:<code>,work_count:1:<count>
 * ```
 *
 * `ffb_cut` is derived here from `event_code`; it is NOT a SQLite column.
 * Under dictionary v1.2 (2026-04-30) only three event_code values are
 * outbound-approved:
 *   - 179 — FFB plus / cut       → `ffb_cut=1`
 *   - 180 — FFB productive minus → `ffb_cut=0`
 *   -  35 — periodic beacon      → `ffb_cut=0`
 * All other codes (legacy dev 279/280, task-lifecycle 281/283/284, device-health
 * 291/292/293, anything else) are local-only and rejected here as
 * `FrameError.UnknownEventCode` — they must never reach the wire.
 *
 * See `CONTEXT.md` Section 3 for full protocol reference and the canonical example.
 */
object IPSFrameBuilder {

    sealed class FrameError(message: String) : Exception(message) {
        object MissingCoordinates : FrameError("event has no latitude or longitude")
        class InvalidTimestamp(val raw: String) : FrameError("cannot parse timestamp: $raw")
        class UnknownEventCode(val code: Int) : FrameError("unknown event_code: $code")
    }

    // Outbound-approved V6 event vocabulary (dictionary v1.2, 2026-04-30):
    //   179 = FFB plus / cut             -> ffb_cut=1
    //   180 = FFB productive minus       -> ffb_cut=0
    //    35 = periodic beacon            -> ffb_cut=0
    // All other codes (281, 283, 284, 291, 292, 293, 279, 280, anything else)
    // are local-only and must never produce a frame — return UnknownEventCode.
    private val VALID_CODES = setOf(35, 179, 180)
    private val PLUS_CODES = setOf(179)

    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("ddMMyy", Locale.US).withZone(ZoneOffset.UTC)
    private val TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HHmmss", Locale.US).withZone(ZoneOffset.UTC)

    private const val COURSE = 0      // V6: real value, not the V5 hack
    private const val ALTITUDE = 10   // estate terrain estimate
    private const val INPUTS = 0
    private const val OUTPUTS = 0
    private const val ADC = ""
    private const val IBUTTON = "NA"

    fun loginFrame(uniqueId: String): String = "#L#$uniqueId;NA\r\n"

    fun dataFrame(event: EventEntity): Result<String> {
        if (event.eventCode !in VALID_CODES) {
            return Result.failure(FrameError.UnknownEventCode(event.eventCode))
        }
        val lat = event.latDecimal ?: return Result.failure(FrameError.MissingCoordinates)
        val lon = event.lonDecimal ?: return Result.failure(FrameError.MissingCoordinates)
        val instant = try {
            Instant.parse(event.timestamp)
        } catch (_: Exception) {
            return Result.failure(FrameError.InvalidTimestamp(event.timestamp))
        }

        val date = DATE_FMT.format(instant)
        val time = TIME_FMT.format(instant)
        val latStr = CoordinateConverter.decimalToDDMM(lat, 2)
        val lonStr = CoordinateConverter.decimalToDDMM(lon, 3)
        val sats = event.satellites ?: 0
        val hdopStr = String.format(Locale.US, "%.1f", event.hdop ?: 0.0)
        val batteryStr = String.format(Locale.US, "%.2f", event.batteryPct)
        val ffbCut = ffbCutFor(event.eventCode)

        val params = "ffb_cut:1:$ffbCut," +
            "battery:2:$batteryStr," +
            "event_code:1:${event.eventCode}," +
            "work_count:1:${event.workCount}"

        val frame = "#D#$date;$time;$latStr;N;$lonStr;E;" +
            "${event.speed ?: 0};$COURSE;$ALTITUDE;$sats;$hdopStr;" +
            "$INPUTS;$OUTPUTS;$ADC;$IBUTTON;$params\r\n"
        return Result.success(frame)
    }

    private fun ffbCutFor(eventCode: Int): Int =
        if (eventCode in PLUS_CODES) 1 else 0
}
