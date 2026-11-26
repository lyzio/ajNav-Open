package io.github.lyzio.ajnav.feature.charging.planner

import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.RouteStopPlan
import io.github.lyzio.ajnav.feature.charging.tariff.ChainedTariffProvider
import io.github.lyzio.ajnav.feature.charging.tariff.PriceSettings
import io.github.lyzio.ajnav.feature.vehicles.VehicleProfile
import kotlin.math.roundToInt

/** A charger near the route and where it sits along it. */
data class CorridorCharger(
    val station: ChargerStation,
    val distanceAlongM: Double,
    val offsetM: Double,
)

enum class OperatorMode { PREFER, ONLY }

/** The planner's knobs (decision D22 defaults: 16 kWh/100 km, arrive ≥ 10 %, charge to 80 %). */
data class PlannerSettings(
    val consumptionKwhPer100Km: Double = 16.0,
    val arriveMinPercent: Double = 10.0,
    val chargeToPercent: Double = 80.0,
    val preferredOperatorIds: Set<Int> = emptySet(),
    val operatorMode: OperatorMode = OperatorMode.PREFER,
    val prices: PriceSettings = PriceSettings(),
)

data class PlannedStop(
    val plan: RouteStopPlan,
    /** True for a stop the user already added to the route. */
    val isAdded: Boolean,
    /** Other good chargers for the same stop, best first (empty for added stops). */
    val alternatives: List<RouteStopPlan> = emptyList(),
)

data class ChargePlan(
    /** Added and suggested stops in route order. */
    val stops: List<PlannedStop>,
    /** Battery % at the destination if every stop in [stops] is used. */
    val arrivalSocPercent: Double,
    /** Battery % at the destination with only the stops already added. */
    val arrivalWithAddedOnlyPercent: Double,
    /** A leg needs charging but no (allowed) charger is within reach. */
    val unreachable: Boolean,
    val anyColdAdjusted: Boolean,
) {
    val suggestions: List<PlannedStop> get() = stops.filterNot { it.isAdded }
}

/**
 * Charging-stop planner (design §4.6 C, decision D22). Works without the car link:
 * the battery % is typed in, and the outside temperature stands in for battery temperature.
 *
 * Walks the route leg by leg. Where the battery would drop below the arrival minimum before
 * the next added stop (or the destination), it looks for chargers in the window where the
 * battery is between 25 % and the minimum, ranks them and suggests the best one.
 */
object StopPlanner {
    private const val WINDOW_UPPER_PERCENT = 25.0
    private const val MAX_STOPS = 12
    private const val ALTERNATIVES = 3
    private const val HARD_FLOOR_PERCENT = 2.0

    fun plan(
        route: RouteGeometry,
        energy: EnergyProfile,
        startSocPercent: Double,
        profile: VehicleProfile,
        settings: PlannerSettings,
        candidates: List<CorridorCharger>,
        addedStops: List<CorridorCharger>,
        temperatureAt: (Double) -> Double?,
    ): ChargePlan {
        val usable = profile.defaultBatteryCapacityKwh.toDouble()
        fun pct(kwh: Double) = kwh / usable * 100.0
        val added = addedStops.sortedBy { it.distanceAlongM }
        val addedIds = added.map { it.station.id }.toSet()
        val pool = candidates.filter { it.station.id !in addedIds }
        val allowed = if (settings.operatorMode == OperatorMode.ONLY && settings.preferredOperatorIds.isNotEmpty()) {
            pool.filter { it.station.operatorId in settings.preferredOperatorIds }
        } else {
            pool
        }

        val stops = ArrayList<PlannedStop>()
        var pos = 0.0
        var soc = startSocPercent
        var anyUnreachable = false
        var legUnreachable = false
        var addedIndex = 0
        var guard = 0

        // Battery % at the destination with only the added stops.
        val addedOnlyArrival = simulateAddedOnly(route, energy, startSocPercent, settings, added, ::pct)

        while (guard++ < MAX_STOPS * 2) {
            val nextAdded = added.getOrNull(addedIndex)
            val legEnd = nextAdded?.distanceAlongM ?: route.totalM
            val socAtLegEnd = soc - pct(energy.kwhBetween(pos, legEnd) + (nextAdded?.let { energy.detourKwh(it.offsetM) } ?: 0.0))

            if (socAtLegEnd >= settings.arriveMinPercent || legUnreachable) {
                if (nextAdded == null) {
                    return ChargePlan(stops, socAtLegEnd, addedOnlyArrival, anyUnreachable, stops.any { it.plan.isColdgateAdjusted })
                }
                val stop = stopPlan(nextAdded, socAtLegEnd, profile, settings, temperatureAt)
                stops += PlannedStop(stop, isAdded = true)
                pos = nextAdded.distanceAlongM
                soc = maxOf(socAtLegEnd, stop.targetSocPercent.toDouble())
                addedIndex++
                legUnreachable = false
                continue
            }

            if (stops.count { !it.isAdded } >= MAX_STOPS) {
                legUnreachable = true
                anyUnreachable = true
                continue
            }

            // Window where the battery is between 25 % and the minimum (from the current position).
            val upper = maxOf(WINDOW_UPPER_PERCENT, settings.arriveMinPercent + 5.0)
            val dUpper = distanceWhereSocReaches(energy, pos, soc, upper, legEnd, ::pct)
            val dLower = distanceWhereSocReaches(energy, pos, soc, settings.arriveMinPercent, legEnd, ::pct)
            // Starting below the minimum: still allow the nearest chargers, down to a hard floor.
            val reachFloor = if (soc > settings.arriveMinPercent) settings.arriveMinPercent else HARD_FLOOR_PERCENT
            val reachable = allowed.filter { c ->
                c.distanceAlongM > pos + 1_000 && c.distanceAlongM < legEnd &&
                    soc - pct(energy.kwhBetween(pos, c.distanceAlongM) + energy.detourKwh(c.offsetM) / 2) >= reachFloor
            }
            val inWindow = reachable.filter { it.distanceAlongM >= dUpper && it.distanceAlongM <= dLower }
            // Nothing in the window: take the furthest chargers still in reach.
            val shortlist = inWindow.ifEmpty { reachable.sortedByDescending { it.distanceAlongM }.take(6) }
            if (shortlist.isEmpty()) {
                legUnreachable = true
                anyUnreachable = true
                continue
            }

            val ranked = shortlist.sortedByDescending { score(it, settings, dUpper) }
            val plans = ranked.take(1 + ALTERNATIVES).map { c ->
                val arrival = soc - pct(energy.kwhBetween(pos, c.distanceAlongM) + energy.detourKwh(c.offsetM) / 2)
                stopPlan(c, arrival, profile, settings, temperatureAt)
            }
            val best = plans.first()
            stops += PlannedStop(best, isAdded = false, alternatives = plans.drop(1))
            // Continue from the charger; the way back to the route is charged to the next leg.
            pos = best.distanceFromStartM
            soc = maxOf(best.arrivalSocPercent.toDouble(), best.targetSocPercent.toDouble()) -
                pct(energy.detourKwh(best.offsetFromRouteM) / 2)
        }
        val endSoc = soc - pct(energy.kwhBetween(pos, route.totalM))
        return ChargePlan(stops, endSoc, addedOnlyArrival, true, stops.any { it.plan.isColdgateAdjusted })
    }

    /** Ranking (design §4.6 C 2): preferred operator +100, ≥ 150 kW +50, < 1 km from route +30, cheaper is better. */
    internal fun score(c: CorridorCharger, settings: PlannerSettings, windowStartM: Double): Double {
        var s = 0.0
        if (c.station.operatorId != null && c.station.operatorId in settings.preferredOperatorIds) s += 100.0
        if (c.station.maxPowerKw >= 150f) s += 50.0
        if (c.offsetM < 1_000) s += 30.0
        s -= ChainedTariffProvider.price(c.station, settings.prices).perKwh * 10.0
        // Small nudge to go further when everything else is equal.
        s += ((c.distanceAlongM - windowStartM) / 1_000.0).coerceAtLeast(0.0) * 0.1
        return s
    }

    private fun stopPlan(
        c: CorridorCharger,
        arrivalSoc: Double,
        profile: VehicleProfile,
        settings: PlannerSettings,
        temperatureAt: (Double) -> Double?,
    ): RouteStopPlan {
        val usable = profile.defaultBatteryCapacityKwh.toDouble()
        val target = settings.chargeToPercent.coerceIn(0.0, 100.0)
        val from = arrivalSoc.coerceIn(0.0, 100.0)
        val neededKwh = if (target > from) usable * (target - from) / 100.0 else 0.0
        val factor = ChargeTime.coldFactor(temperatureAt(c.distanceAlongM), profile.coldgateThresholdCelsius.toDouble())
        val minutes = ChargeTime.minutes(profile.chargeCurve, usable, from, target, c.station.maxPowerKw.toDouble(), factor)
        val price = ChainedTariffProvider.price(c.station, settings.prices)
        return RouteStopPlan(
            station = c.station,
            distanceFromStartM = c.distanceAlongM,
            offsetFromRouteM = c.offsetM,
            arrivalSocPercent = arrivalSoc.toFloat(),
            targetSocPercent = maxOf(target, from).toFloat(),
            neededKwh = neededKwh.toFloat(),
            estimatedChargeMinutes = minutes.roundToInt(),
            pricePerKwh = price.perKwh,
            priceSource = price.source,
            estimatedCost = (neededKwh * price.perKwh).toFloat(),
            currency = "SEK",
            isColdgateAdjusted = factor < 1.0 && neededKwh > 0,
        )
    }

    private fun simulateAddedOnly(
        route: RouteGeometry,
        energy: EnergyProfile,
        startSoc: Double,
        settings: PlannerSettings,
        added: List<CorridorCharger>,
        pct: (Double) -> Double,
    ): Double {
        var pos = 0.0
        var soc = startSoc
        for (a in added) {
            soc -= pct(energy.kwhBetween(pos, a.distanceAlongM) + energy.detourKwh(a.offsetM))
            soc = maxOf(soc, settings.chargeToPercent.coerceIn(0.0, 100.0))
            pos = a.distanceAlongM
        }
        return soc - pct(energy.kwhBetween(pos, route.totalM))
    }

    /** First distance (from [from], at [socAtFrom] %) where the battery falls to [targetSoc] %, capped at [maxM]. */
    private fun distanceWhereSocReaches(
        energy: EnergyProfile,
        from: Double,
        socAtFrom: Double,
        targetSoc: Double,
        maxM: Double,
        pct: (Double) -> Double,
    ): Double {
        if (socAtFrom <= targetSoc) return from
        var lo = from
        var hi = maxM
        if (socAtFrom - pct(energy.kwhBetween(from, hi)) > targetSoc) return maxM
        repeat(40) {
            val mid = (lo + hi) / 2
            if (socAtFrom - pct(energy.kwhBetween(from, mid)) > targetSoc) lo = mid else hi = mid
        }
        return hi
    }
}
