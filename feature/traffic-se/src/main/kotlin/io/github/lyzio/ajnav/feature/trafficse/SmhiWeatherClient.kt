package io.github.lyzio.ajnav.feature.trafficse

import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.WeatherSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Key-free weather forecast from SMHI Open Data (design §4.5).
 *
 * SMHI retired the old "pmp3g" API on 31 March 2026; this uses its replacement "snow1g"
 * (same host, covers the Nordic area). Outside that area SMHI has no data and the planner
 * simply plans without a weather adjustment.
 */
@Singleton
class SmhiWeatherClient @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) {
    /** Forecast at [point] for the hour closest to [at]. Returns null if SMHI has no data. */
    suspend fun forecast(point: GeoPoint, at: Instant): WeatherSnapshot? {
        val url = String.format(
            Locale.ROOT,
            "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/%.4f/lat/%.4f/data.json",
            point.longitude,
            point.latitude,
        )
        val response = client.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        if (!response.status.isSuccess()) return null
        return SmhiParser.parse(json, response.bodyAsText(), at)
    }

    private companion object {
        const val USER_AGENT = "ajNav/0.3 (+https://github.com/lyzio/ajNav-Open)"
    }
}

/** Reads SMHI's snow1g JSON. Kept separate (and Android-free) so it can be unit tested. */
object SmhiParser {
    fun parse(json: Json, body: String, at: Instant): WeatherSnapshot? {
        val root = json.parseToJsonElement(body).jsonObject
        val series = root["timeSeries"]?.jsonArray ?: return null
        val best = series.mapNotNull { entry ->
            val obj = entry.jsonObject
            val time = (obj["time"] ?: obj["validTime"])?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            time to obj
        }.minByOrNull { (time, _) -> abs(time.epochSecond - at.epochSecond) }?.second ?: return null

        val data = best["data"]?.jsonObject ?: return null
        return WeatherSnapshot(
            temperatureC = data.number("air_temperature"),
            windSpeedMs = data.number("wind_speed"),
            windFromDeg = data.number("wind_from_direction"),
            precipitationMmH = data.number("precipitation_amount_mean")
                ?: data.entries.firstOrNull { it.key.startsWith("precipitation_amount") }
                    ?.value?.jsonPrimitive?.doubleOrNull,
        )
    }

    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
}
