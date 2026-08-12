package com.armsx2

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTierTest {
    @Test
    fun qcs8550IsAnnotated() {
        assertEquals(
            "Qualcomm QCS8550 (Snapdragon 8 Gen 2-class)",
            DeviceTier.formatSocIdentity("Qualcomm", "QCS8550", "kalama"),
        )
    }

    @Test
    fun qcs9075IsAnnotated() {
        assertEquals(
            "Qualcomm QCS9075 (Snapdragon 8 Elite-class)",
            DeviceTier.formatSocIdentity("Qualcomm", "QCS9075", "sun"),
        )
    }

    /** AYN Odin 3, as reported by the device: ro.soc.manufacturer=ayn,
     *  ro.soc.model=CQ8725S. */
    @Test
    fun cq8725sIsAnnotated() {
        assertEquals(
            "ayn CQ8725S (Snapdragon 8 Elite-class)",
            DeviceTier.formatSocIdentity("ayn", "CQ8725S", "qcom"),
        )
    }

    @Test
    fun unknownModelPassesThroughUnchanged() {
        assertEquals(
            "Qualcomm SM8550",
            DeviceTier.formatSocIdentity("Qualcomm", "SM8550", "kalama"),
        )
    }

    @Test
    fun blankManufacturerKeepsModel() {
        assertEquals(
            "QCS8550 (Snapdragon 8 Gen 2-class)",
            DeviceTier.formatSocIdentity("", "QCS8550", "kalama"),
        )
    }

    @Test
    fun blankModelAndManufacturerFallBackToHardware() {
        assertEquals("kalama", DeviceTier.formatSocIdentity("", "", "kalama"))
        assertEquals("kalama", DeviceTier.formatSocIdentity(null, null, "kalama"))
    }

    /** A manufacturer with no model does not identify the SoC. */
    @Test
    fun manufacturerWithoutModelFallsBackToHardware() {
        assertEquals("kalama", DeviceTier.formatSocIdentity("Qualcomm", "", "kalama"))
    }

    @Test
    fun nothingKnownReturnsEmpty() {
        assertEquals("", DeviceTier.formatSocIdentity("", "", ""))
        assertEquals("", DeviceTier.formatSocIdentity(null, null, null))
    }

    /** Android fills unset Build fields with the literal "unknown". */
    @Test
    fun unknownSentinelFallsBackToHardware() {
        assertEquals("kalama", DeviceTier.formatSocIdentity("unknown", "unknown", "kalama"))
        assertEquals("kalama", DeviceTier.formatSocIdentity("Qualcomm", "unknown", "kalama"))
    }

    @Test
    fun unknownSentinelWithNoHardwareReturnsEmpty() {
        assertEquals("", DeviceTier.formatSocIdentity("unknown", "unknown", "unknown"))
    }

    @Test
    fun unknownManufacturerStillKeepsRealModel() {
        assertEquals(
            "QCS8550 (Snapdragon 8 Gen 2-class)",
            DeviceTier.formatSocIdentity("unknown", "QCS8550", "kalama"),
        )
    }
}
