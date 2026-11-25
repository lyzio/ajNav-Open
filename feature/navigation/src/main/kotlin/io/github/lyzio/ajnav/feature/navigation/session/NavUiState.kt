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
)
