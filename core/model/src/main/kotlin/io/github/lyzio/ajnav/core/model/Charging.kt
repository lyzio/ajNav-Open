package io.github.lyzio.ajnav.core.model

import kotlinx.serialization.Serializable

/** Where the battery level came from (design §4.1). v0.3 only has MANUAL. */
enum class TelemetrySource { OBD_LIVE, ENODE_CLOUD, MANUAL }

/** One step of a car's charge curve: up to [upToSocPercent] the car charges at most [maxPowerKw]. */
@Serializable
data class ChargeCurveStep(val upToSocPercent: Int, val maxPowerKw: Float)

/** A fast charger (design §4.1). Prices and availability are filled in by the charging module. */
@Serializable
data class ChargerStation(
    val id: String,
    val name: String,
    val operatorId: Int?,
    val operatorName: String,
    val latitude: Double,
    val longitude: Double,
    val maxPowerKw: Float,
    val availablePoints: Int? = null,
    val totalPoints: Int? = null,
    val usageCostRaw: String? = null,
    val estimatedPricePerKwh: Float? = null,
    val currency: String = "SEK",
    val address: String = "",
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

/** Where a charger's price came from, in priority order (design §4.4 B). */
enum class PriceSource { SUBSCRIPTION, OCM, OPERATOR_DEFAULT, GLOBAL_DEFAULT }

/** Weather at one point on the route (SMHI). Null fields mean "not known". */
@Serializable
data class WeatherSnapshot(
    val temperatureC: Double?,
    val windSpeedMs: Double?,
    val windFromDeg: Double?,
    val precipitationMmH: Double?,
)

/** One planned charging stop (design §4.1 RouteStopPlan). */
data class RouteStopPlan(
    val station: ChargerStation,
    val distanceFromStartM: Double,
    val offsetFromRouteM: Double,
    val arrivalSocPercent: Float,
    val targetSocPercent: Float,
    val neededKwh: Float,
    val estimatedChargeMinutes: Int,
    val pricePerKwh: Float,
    val priceSource: PriceSource,
    val estimatedCost: Float,
    val currency: String,
    /** True when the charge time was lengthened for a cold battery (marked "estimated"). */
    val isColdgateAdjusted: Boolean,
)
