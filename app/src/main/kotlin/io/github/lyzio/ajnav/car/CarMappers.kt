package io.github.lyzio.ajnav.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import io.github.lyzio.ajnav.feature.navigation.session.LaneUi
import io.github.lyzio.ajnav.feature.navigation.session.ManeuverUi
import io.github.lyzio.ajnav.feature.navigation.session.ProgressUi
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Turns the shared trip state into Android Auto's fixed navigation models (decision D12). */
internal object CarMappers {

    /** Metric distance in the units Android Auto expects. */
    fun distance(meters: Double): Distance = when {
        meters < 1000 -> Distance.create(roundTo(meters, if (meters < 100) 10.0 else 50.0), Distance.UNIT_METERS)
        meters < 10_000 -> Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)
    }

    fun routingInfo(m: ManeuverUi): RoutingInfo {
        val step = Step.Builder(m.primaryText)
            .setManeuver(
                Maneuver.Builder(maneuverType(m.type, m.modifier, m.drivingSide))
                    .setIcon(CarGraphics.maneuverIcon(m.type, m.modifier, m.drivingSide))
                    .build(),
            )
            .apply {
                if (m.lanes.isNotEmpty()) {
                    m.lanes.forEach { addLane(lane(it)) }
                    setLanesImage(CarGraphics.lanesIcon(m.lanes, m.drivingSide))
                }
            }
            .build()
        return RoutingInfo.Builder()
            .setCurrentStep(step, distance(m.distanceRemainingM))
            .build()
    }

    fun travelEstimate(p: ProgressUi): TravelEstimate {
        val arrival = ZonedDateTime.ofInstant(Instant.ofEpochMilli(p.etaEpochMs), ZoneId.systemDefault())
        return TravelEstimate.Builder(distance(p.distanceRemainingM), arrival)
            .setRemainingTimeSeconds(p.durationRemainingSec.toLong().coerceAtLeast(0))
            .build()
    }

    private fun lane(l: LaneUi): Lane {
        val builder = Lane.Builder()
        val dirs = l.directions.ifEmpty { listOf("straight") }
        dirs.forEach { dir ->
            val recommended = l.isActive && (l.activeDirection == null || l.activeDirection == dir)
            builder.addDirection(LaneDirection.create(laneShape(dir), recommended))
        }
        return builder.build()
    }

    private fun laneShape(dir: String): Int = when (dir) {
        "straight" -> LaneDirection.SHAPE_STRAIGHT
        "slight left" -> LaneDirection.SHAPE_SLIGHT_LEFT
        "slight right" -> LaneDirection.SHAPE_SLIGHT_RIGHT
        "left" -> LaneDirection.SHAPE_NORMAL_LEFT
        "right" -> LaneDirection.SHAPE_NORMAL_RIGHT
        "sharp left" -> LaneDirection.SHAPE_SHARP_LEFT
        "sharp right" -> LaneDirection.SHAPE_SHARP_RIGHT
        "uturn" -> LaneDirection.SHAPE_U_TURN_LEFT
        else -> LaneDirection.SHAPE_UNKNOWN
    }

    /** Mapbox maneuver type/modifier → Android Auto maneuver type. */
    fun maneuverType(type: String?, modifier: String?, drivingSide: String?): Int {
        val leftHand = drivingSide == "left"
        val isLeft = modifier?.contains("left") == true
        val isRight = modifier?.contains("right") == true
        return when (type) {
            "arrive" -> when (modifier) {
                "left" -> Maneuver.TYPE_DESTINATION_LEFT
                "right" -> Maneuver.TYPE_DESTINATION_RIGHT
                "straight" -> Maneuver.TYPE_DESTINATION_STRAIGHT
                else -> Maneuver.TYPE_DESTINATION
            }
            "depart" -> Maneuver.TYPE_DEPART
            // Exit number/angle aren't used, so only the "enter" and "exit" types are safe here.
            "roundabout", "rotary", "roundabout turn" ->
                if (leftHand) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            "exit roundabout", "exit rotary" ->
                if (leftHand) Maneuver.TYPE_ROUNDABOUT_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            "merge" -> when {
                isLeft -> Maneuver.TYPE_MERGE_LEFT
                isRight -> Maneuver.TYPE_MERGE_RIGHT
                else -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            }
            "on ramp" -> when (modifier) {
                "slight left" -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
                "slight right" -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
                "left" -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
                "right" -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
                "sharp left" -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
                "sharp right" -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
                "uturn" -> if (leftHand) Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT else Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
                else -> Maneuver.TYPE_STRAIGHT
            }
            "off ramp" -> when {
                modifier == "slight left" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
                modifier == "slight right" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
                isLeft -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                isRight -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
                else -> Maneuver.TYPE_STRAIGHT
            }
            "fork" -> when {
                isLeft -> Maneuver.TYPE_FORK_LEFT
                isRight -> Maneuver.TYPE_FORK_RIGHT
                else -> Maneuver.TYPE_STRAIGHT
            }
            else -> when (modifier) {
                "uturn" -> if (leftHand) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT
                "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
                "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
                "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
                "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                "slight left" -> if (type == "continue") Maneuver.TYPE_KEEP_LEFT else Maneuver.TYPE_TURN_SLIGHT_LEFT
                "slight right" -> if (type == "continue") Maneuver.TYPE_KEEP_RIGHT else Maneuver.TYPE_TURN_SLIGHT_RIGHT
                "straight" -> Maneuver.TYPE_STRAIGHT
                else -> if (type == "new name") Maneuver.TYPE_NAME_CHANGE else Maneuver.TYPE_UNKNOWN
            }
        }
    }

    private fun roundTo(value: Double, step: Double): Double = kotlin.math.round(value / step) * step
}
