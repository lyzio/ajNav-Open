package io.github.lyzio.ajnav.feature.charging.planner

import io.github.lyzio.ajnav.core.model.WeatherSnapshot
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Energy estimate along a route (design §4.6 B–C, decision D22):
 * base use × weather factor, plus hills from the elevation profile.
 */
object EnergyModel {
    const val SAMPLE_STEP_M = 5_000.0
    private const val G = 9.81
    private const val CLIMB_EFFICIENCY = 0.85
    private const val REGEN_EFFICIENCY = 0.65
    private const val MAX_WEATHER_EXTRA = 0.25

    /**
     * Extra energy for weather, 0.0–0.25 (design: +10 % to +25 %):
     * cold below 2 °C +10 % (rising to +15 % at −10 °C), rain/snow +5 %, headwind over 10 m/s +5 %.
     */
    fun weatherExtra(w: WeatherSnapshot?, travelBearingDeg: Double): Double {
        if (w == null) return 0.0
        var extra = 0.0
        w.temperatureC?.let { t ->
            if (t < 2.0) extra += 0.10 + 0.05 * ((2.0 - t) / 12.0).coerceIn(0.0, 1.0)
        }
        if ((w.precipitationMmH ?: 0.0) >= 0.2) extra += 0.05
        val speed = w.windSpeedMs
        val from = w.windFromDeg
        if (speed != null && from != null) {
            // Wind coming FROM the direction we drive TOWARDS is a headwind.
            val headwind = speed * cos(Math.toRadians(from - travelBearingDeg))
            if (headwind > 10.0) extra += 0.05
        }
        return min(extra, MAX_WEATHER_EXTRA)
    }

    /** Hill energy in kWh for a height change (positive = climb, negative = regenerated). */
    fun elevationKwh(massKg: Double, deltaHeightM: Double): Double {
        val joules = massKg * G * deltaHeightM
        val kwh = joules / 3_600_000.0
        return if (deltaHeightM >= 0) kwh / CLIMB_EFFICIENCY else kwh * REGEN_EFFICIENCY
    }

    /**
     * Builds the cumulative energy profile.
     * @param elevationsM heights at [EnergyProfile.sampleDistancesM] (null = flat).
     * @param weatherAt weather to use at a given distance along the route.
     */
    fun buildProfile(
        route: RouteGeometry,
        baseKwhPer100Km: Double,
        massKg: Double,
        sampleDistancesM: List<Double>,
        elevationsM: List<Double>?,
        weatherAt: (Double) -> WeatherSnapshot?,
    ): EnergyProfile {
        val cumulative = DoubleArray(sampleDistancesM.size)
        var maxExtra = 0.0
        for (i in 1 until sampleDistancesM.size) {
            val d0 = sampleDistancesM[i - 1]
            val d1 = sampleDistancesM[i]
            val mid = (d0 + d1) / 2
            val extra = weatherExtra(weatherAt(mid), route.bearingAt(mid))
            maxExtra = max(maxExtra, extra)
            var kwh = baseKwhPer100Km * (d1 - d0) / 100_000.0 * (1.0 + extra)
            if (elevationsM != null && elevationsM.size == sampleDistancesM.size) {
                kwh += elevationKwh(massKg, elevationsM[i] - elevationsM[i - 1])
            }
            cumulative[i] = cumulative[i - 1] + max(kwh, 0.0)
        }
        return EnergyProfile(sampleDistancesM.toDoubleArray(), cumulative, baseKwhPer100Km, maxExtra)
    }
}

/** Cumulative kWh used from the route start, sampled along the route. */
class EnergyProfile(
    private val distancesM: DoubleArray,
    private val cumulativeKwh: DoubleArray,
    val baseKwhPer100Km: Double,
    /** Largest weather extra applied anywhere (0.0–0.25), for display. */
    val maxWeatherExtra: Double,
) {
    fun kwhAt(distanceM: Double): Double {
        if (distancesM.size < 2) return baseKwhPer100Km * distanceM / 100_000.0
        if (distanceM <= 0) return 0.0
        val last = distancesM.size - 1
        if (distanceM >= distancesM[last]) {
            return cumulativeKwh[last] + baseKwhPer100Km * (distanceM - distancesM[last]) / 100_000.0
        }
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (distancesM[mid] <= distanceM) lo = mid else hi = mid
        }
        val t = (distanceM - distancesM[lo]) / (distancesM[hi] - distancesM[lo])
        return cumulativeKwh[lo] + t * (cumulativeKwh[hi] - cumulativeKwh[lo])
    }

    fun kwhBetween(fromM: Double, toM: Double): Double = kwhAt(toM) - kwhAt(fromM)

    /** Energy for driving [metres] off the route and back, at base use. */
    fun detourKwh(offsetM: Double): Double = baseKwhPer100Km * (2 * offsetM) / 100_000.0
}
