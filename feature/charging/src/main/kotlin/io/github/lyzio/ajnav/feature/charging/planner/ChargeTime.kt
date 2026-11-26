package io.github.lyzio.ajnav.feature.charging.planner

import io.github.lyzio.ajnav.core.model.ChargeCurveStep

/** Charging time from the car's charge curve, with the cold-battery adjustment (design §4.6 C 3, D3). */
object ChargeTime {

    /**
     * Charging-speed factor for a battery at [batteryTempC]: 1.0 at or above [thresholdC],
     * falling linearly to 0.5 at 0 °C, and 0.5 below 0 °C. Null temperature = 1.0.
     */
    fun coldFactor(batteryTempC: Double?, thresholdC: Double): Double {
        if (batteryTempC == null || batteryTempC >= thresholdC) return 1.0
        if (batteryTempC <= 0.0) return 0.5
        return 0.5 + 0.5 * (batteryTempC / thresholdC)
    }

    /**
     * Minutes to charge from [fromSoc] to [toSoc] (percent). Each curve step's power is scaled
     * by [coldFactor] and capped at the charger's [chargerMaxKw].
     */
    fun minutes(
        curve: List<ChargeCurveStep>,
        usableKwh: Double,
        fromSoc: Double,
        toSoc: Double,
        chargerMaxKw: Double,
        coldFactor: Double,
    ): Double {
        if (toSoc <= fromSoc || curve.isEmpty()) return 0.0
        var soc = fromSoc.coerceAtLeast(0.0)
        var minutes = 0.0
        val target = toSoc.coerceAtMost(100.0)
        while (soc < target - 1e-9) {
            val step = curve.firstOrNull { it.upToSocPercent > soc } ?: curve.last()
            val segEnd = minOf(step.upToSocPercent.toDouble(), target).let { if (it <= soc) target else it }
            val kwh = usableKwh * (segEnd - soc) / 100.0
            val carKw = step.maxPowerKw * coldFactor
            val kw = if (chargerMaxKw > 0) minOf(carKw, chargerMaxKw) else carKw
            minutes += kwh / kw.coerceAtLeast(1.0) * 60.0
            soc = segEnd
        }
        return minutes
    }
}
