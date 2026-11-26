package io.github.lyzio.ajnav.feature.vehicles

import io.github.lyzio.ajnav.core.model.ChargeCurveStep

/**
 * What the charging planner needs to know about a car (design §4.3 C).
 * The OBD part of the profile (pollingCommands, parseTelemetry) is added in v0.4.
 */
interface VehicleProfile {
    val profileId: String
    val displayName: String
    /** Car + driver, used for the hill (elevation) energy estimate. */
    val vehicleMassKg: Float
    /** Usable (net) battery capacity. */
    val defaultBatteryCapacityKwh: Float
    val grossBatteryCapacityKwh: Float
    /** Shown on the Dashboard only (decision D3). */
    val optimalBatteryTempCelsius: Float
    /** Below this battery temperature the charging speed is scaled down (decision D3). */
    val coldgateThresholdCelsius: Float
    /** Non-linear charge curve, steps in rising order, last step ends at 100 %. */
    val chargeCurve: List<ChargeCurveStep>
    /** Default energy use without the car link (decision D22). */
    val defaultConsumptionKwhPer100Km: Float
}

/** Fiat 500e 2021, 42 kWh (Icon / La Prima). Values from design §4.3 C and decision D22. */
object Fiat500e2021Profile : VehicleProfile {
    override val profileId = "fiat500e_2021_42kwh"
    override val displayName = "Fiat 500e 2021 (42 kWh)"
    override val vehicleMassKg = 1465f
    override val defaultBatteryCapacityKwh = 37.3f
    override val grossBatteryCapacityKwh = 42.0f
    override val optimalBatteryTempCelsius = 20.0f
    override val coldgateThresholdCelsius = 15.0f
    override val chargeCurve = listOf(
        ChargeCurveStep(upToSocPercent = 50, maxPowerKw = 85f),
        ChargeCurveStep(upToSocPercent = 80, maxPowerKw = 55f),
        ChargeCurveStep(upToSocPercent = 100, maxPowerKw = 20f),
    )
    override val defaultConsumptionKwhPer100Km = 16f
}

/** All car profiles the app knows. Add new cars here. */
object VehicleProfiles {
    val all: List<VehicleProfile> = listOf(Fiat500e2021Profile)
    val default: VehicleProfile = Fiat500e2021Profile
    fun byId(id: String?): VehicleProfile = all.firstOrNull { it.profileId == id } ?: default
}
