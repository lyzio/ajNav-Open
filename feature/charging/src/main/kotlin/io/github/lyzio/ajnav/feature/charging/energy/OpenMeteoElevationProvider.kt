package io.github.lyzio.ajnav.feature.charging.energy

import io.github.lyzio.ajnav.core.model.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Key-free heights from Open-Meteo (design §4.6 B), up to 100 points per request. */
@Singleton
class OpenMeteoElevationProvider @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) {
    /** Heights in metres for [points], or null if any request fails (then the route is treated as flat). */
    suspend fun elevations(points: List<GeoPoint>): List<Double>? {
        val out = ArrayList<Double>(points.size)
        for (chunk in points.chunked(MAX_PER_REQUEST)) {
            val response = client.get("https://api.open-meteo.com/v1/elevation") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("latitude", chunk.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.latitude) })
                parameter("longitude", chunk.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.longitude) })
            }
            if (!response.status.isSuccess()) return null
            val body = json.decodeFromString(ElevationResponse.serializer(), response.bodyAsText())
            if (body.elevation.size != chunk.size) return null
            out += body.elevation
        }
        return out
    }

    private companion object {
        const val MAX_PER_REQUEST = 100
        const val USER_AGENT = "ajNav/0.3 (+https://github.com/lyzio/ajNav-Open)"
    }
}

@Serializable
internal data class ElevationResponse(val elevation: List<Double> = emptyList())
