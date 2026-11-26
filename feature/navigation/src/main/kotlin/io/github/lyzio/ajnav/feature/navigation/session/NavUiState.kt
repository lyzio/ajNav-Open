package io.github.lyzio.ajnav.feature.navigation.session

import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.Place

enum class NavMode { FREE_DRIVE, ROUTING, PREVIEW, ACTIVE }

data class LaneUi(
    val directions: List<String>,
    val isActive: Boolean,
    val activeDirection: String?,
)

data class ManeuverUi(
    val primaryText: String,
    val distanceText: String,
    val distanceRemainingM: Double = 0.0,
    val type: String?,
    val modifier: String?,
    val degrees: Double? = null,
    val drivingSide: String? = null,
    val lanes: List<LaneUi>,
)

data class ProgressUi(
    val durationRemainingSec: Double,
    val distanceRemainingM: Double,
    val etaEpochMs: Long,
)

data class RouteSummary(
    val durationSec: Double,
    val distanceM: Double,
)

/** The shared trip state, read by both the phone and the car screen. */
data class NavUiState(
    val mode: NavMode = NavMode.FREE_DRIVE,
    val destination: Place? = null,
    val location: GeoPoint? = null,
    val speedKmh: Int? = null,
    val speedLimitKmh: Int? = null,
    val maneuver: ManeuverUi? = null,
    val progress: ProgressUi? = null,
    val preview: RouteSummary? = null,
    val arrived: Boolean = false,
    val message: String? = null,
    /** Charging stops the driver added to the route, in route order. */
    val chargeStops: List<io.github.lyzio.ajnav.core.model.ChargerStation> = emptyList(),
    val charging: ChargingUi = ChargingUi(),
    /** Chargers to draw on the map (suggested and added). */
    val chargerMarkers: List<ChargerMarker> = emptyList(),
)

/** What a charger marker on the map stands for. */
enum class ChargerMarkerKind { SUGGESTED, ADDED }

data class ChargerMarker(val station: io.github.lyzio.ajnav.core.model.ChargerStation, val kind: ChargerMarkerKind)

/** Charging part of the trip (v0.3): typed battery %, the plan for the previewed route, notices. */
data class ChargingUi(
    /** Battery % typed by the driver (null = not entered yet). */
    val batteryPercent: Int? = null,
    val loading: Boolean = false,
    val plan: io.github.lyzio.ajnav.feature.charging.planner.ChargePlan? = null,
    /** e.g. missing Open Charge Map key, offline (saved chargers), cold-weather hint. */
    val notice: String? = null,
    /** Lowest outside temperature on the route (SMHI), shown with cold estimates. */
    val outsideTempC: Double? = null,
    val arriveMinPercent: Int = 10,
)
