package com.klk.hams.push

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CoordinateConverterTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test fun `latitude two-digit degrees - canonical example`() {
        assertEquals("0216.1233", CoordinateConverter.decimalToDDMM(2.268721, 2))
    }

    @Test fun `longitude three-digit degrees - canonical example`() {
        assertEquals("10316.9791", CoordinateConverter.decimalToDDMM(103.282985, 3))
    }

    @Test fun `zero degrees produces zero pad`() {
        assertEquals("0000.0000", CoordinateConverter.decimalToDDMM(0.0, 2))
    }

    @Test fun `whole degree has zero minutes`() {
        assertEquals("0300.0000", CoordinateConverter.decimalToDDMM(3.0, 2))
    }

    @Test fun `three-digit ten degrees pads correctly`() {
        assertEquals("01000.0000", CoordinateConverter.decimalToDDMM(10.0, 3))
    }

    @Test fun `decimal separator is locale-independent`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("0216.1233", CoordinateConverter.decimalToDDMM(2.268721, 2))
        assertEquals("10316.9791", CoordinateConverter.decimalToDDMM(103.282985, 3))
    }
}
