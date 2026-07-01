package com.klk.hams.push

import java.util.Locale

/**
 * Decimal degrees -> Wialon IPS DDMM.MMMM string.
 *
 * Wialon IPS v1.1 frames carry latitude as a 2-digit-degree DDMM.MMMM string and
 * longitude as a 3-digit-degree DDDMM.MMMM string. The decimal separator must be a
 * period regardless of device locale.
 *
 * Hemisphere (N/S, E/W) is encoded in a separate frame field and is always N/E
 * for HAMS V2 (Malaysia) - this converter only formats the magnitude.
 *
 * See `CONTEXT.md` Section 3.5.
 */
object CoordinateConverter {

    fun decimalToDDMM(decimal: Double, digits: Int): String {
        require(digits in 1..3) { "digits must be 1..3 (got $digits)" }
        val deg = decimal.toInt()
        val min = (decimal - deg) * 60.0
        return String.format(Locale.US, "%0${digits}d%07.4f", deg, min)
    }
}
