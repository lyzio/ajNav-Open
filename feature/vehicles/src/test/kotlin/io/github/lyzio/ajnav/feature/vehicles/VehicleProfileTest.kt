package io.github.lyzio.ajnav.feature.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProfileTest {
    @Test
    fun everyProfileHasAValidChargeCurve() {
        VehicleProfiles.all.forEach { p ->
            val ups = p.chargeCurve.map { it.upToSocPercent }
            assertEquals("${p.profileId}: curve must end at 100 %", 100, ups.last())
            assertTrue("${p.profileId}: curve must rise", ups.zipWithNext().all { (a, b) -> b > a })
            assertTrue(p.chargeCurve.all { it.maxPowerKw > 0f })
            assertTrue(p.defaultBatteryCapacityKwh <= p.grossBatteryCapacityKwh)
        }
    }

    @Test
    fun unknownIdFallsBackToDefault() {
        assertEquals(Fiat500e2021Profile, VehicleProfiles.byId("nope"))
    }
}
