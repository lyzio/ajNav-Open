package io.github.lyzio.ajnav.feature.charging.tariff

import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.PriceSource
import java.util.Locale

/** The user's own prices (Operators & Prices tab). Prices in SEK per kWh. */
data class PriceSettings(
    /** "My subscription price": always used for that operator. */
    val subscriptionPrices: Map<Int, Float> = emptyMap(),
    /** "Usual price": used for that operator when Open Charge Map has no price. */
    val operatorDefaultPrices: Map<Int, Float> = emptyMap(),
    val globalDefaultPrice: Float = DEFAULT_GLOBAL_PRICE,
) {
    companion object {
        const val DEFAULT_GLOBAL_PRICE = 5.50f
    }
}

data class Price(val perKwh: Float, val source: PriceSource)

/**
 * Price per kWh in the strict order of design §4.4 B (NOBIL/OCPI live prices come in v0.4):
 * 1. your subscription price for the operator, 2. Open Charge Map's cost text,
 * 3. your usual price for the operator, 4. the global default (5.50 kr/kWh).
 */
object ChainedTariffProvider {
    fun price(station: ChargerStation, settings: PriceSettings): Price {
        val op = station.operatorId
        if (op != null) settings.subscriptionPrices[op]?.let { return Price(it, PriceSource.SUBSCRIPTION) }
        OcmUsageCostParser.pricePerKwhSek(station.usageCostRaw)?.let { return Price(it, PriceSource.OCM) }
        if (op != null) settings.operatorDefaultPrices[op]?.let { return Price(it, PriceSource.OPERATOR_DEFAULT) }
        return Price(settings.globalDefaultPrice, PriceSource.GLOBAL_DEFAULT)
    }
}

/**
 * Reads a kr/kWh price out of Open Charge Map's free-text "UsageCost" (design §4.4 B 3).
 * Only Swedish kronor are accepted; other currencies, per-minute-only prices and unclear text
 * return null so the next price source is used.
 */
object OcmUsageCostParser {
    private const val NUM = """(\d{1,2}(?:[.,]\d{1,2})?)"""
    private val patterns = listOf(
        // "5,95 kr/kWh", "6.50 SEK per kWh", "4:95 kr / kWh"
        Regex("""$NUM\s*(?:kr|sek|:-)\s*(?:/|per|pr\.?|för)\s*kwh""", RegexOption.IGNORE_CASE),
        // "SEK 6.50/kWh", "kr 5,50 per kWh"
        Regex("""(?:sek|kr)\s*$NUM\s*(?:/|per|pr\.?)\s*kwh""", RegexOption.IGNORE_CASE),
        // "kWh: 6,50 kr", "kWh 5.95 SEK"
        Regex("""kwh\s*[:=]?\s*$NUM\s*(?:kr|sek)""", RegexOption.IGNORE_CASE),
    )
    private val foreign = Regex("""€|eur|nok|dkk|£|\$|usd""", RegexOption.IGNORE_CASE)
    private val free = Regex("""^\s*(free|gratis|kostnadsfri)\s*\.?\s*$""", RegexOption.IGNORE_CASE)

    fun pricePerKwhSek(text: String?): Float? {
        if (text.isNullOrBlank()) return null
        if (free.matches(text)) return 0f
        if (foreign.containsMatchIn(text)) return null
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val value = m.groupValues[1].replace(',', '.').replace(':', '.').toFloatOrNull() ?: continue
            if (value in 0.5f..15f) return value
        }
        return null
    }

    fun format(perKwh: Float): String = String.format(Locale.getDefault(), "%.2f kr/kWh", perKwh)
}
