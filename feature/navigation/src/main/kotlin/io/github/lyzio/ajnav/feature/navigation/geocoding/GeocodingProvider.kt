package io.github.lyzio.ajnav.feature.navigation.geocoding

import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.Place
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Swappable destination search (design §4.6 A). */
interface GeocodingProvider {
    suspend fun search(query: String, near: GeoPoint?): List<Place>
}

/** Default, key-free geocoder: Photon by komoot (OpenStreetMap data). */
class PhotonGeocodingProvider @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) : GeocodingProvider {

    override suspend fun search(query: String, near: GeoPoint?): List<Place> {
        val response = client.get("https://photon.komoot.io/api/") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            parameter("q", query)
            parameter("limit", 6)
            near?.let {
                parameter("lat", it.latitude)
                parameter("lon", it.longitude)
            }
        }
        if (!response.status.isSuccess()) error("Search failed (${response.status.value})")
        val body = json.decodeFromString(PhotonResponse.serializer(), response.bodyAsText())
        return body.features.mapNotNull { it.toPlace() }
    }

    private fun PhotonFeature.toPlace(): Place? {
        val coords = geometry.coordinates
        if (coords.size < 2) return null
        val p = properties
        val street = listOfNotNull(p.street, p.houseNumber).joinToString(" ").ifBlank { null }
        val title = p.name ?: street ?: p.city ?: return null
        val address = listOfNotNull(
            street.takeIf { it != title },
            listOfNotNull(p.postcode, p.city).joinToString(" ").ifBlank { null },
            p.country,
        ).joinToString(", ")
        return Place(name = title, address = address, point = GeoPoint(coords[1], coords[0]))
    }

    private companion object {
        const val USER_AGENT = "ajNav/0.3 (+https://github.com/lyzio/ajNav-Open)"
    }
}

@Serializable
internal data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
internal data class PhotonFeature(
    val geometry: PhotonGeometry,
    val properties: PhotonProperties = PhotonProperties(),
)

@Serializable
internal data class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal data class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    @SerialName("housenumber") val houseNumber: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val country: String? = null,
)
