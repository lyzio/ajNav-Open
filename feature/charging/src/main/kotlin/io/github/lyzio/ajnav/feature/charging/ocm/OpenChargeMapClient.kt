package io.github.lyzio.ajnav.feature.charging.ocm

import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.feature.charging.planner.PolylineCodec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Open Charge Map rejected the key (401/403). */
class OcmKeyException(message: String) : Exception(message)

/** No Open Charge Map key has been entered yet. */
class OcmMissingKeyException(message: String) : Exception(message)

data class OcmOperator(val id: Int, val name: String, val websiteUrl: String?)

/**
 * Open Charge Map API v3 (design §4.4 A) with the user's own key (BYOK).
 * Fast chargers only: CCS (connection type 33), DC fast (level 3), operational (status 50).
 */
@Singleton
class OpenChargeMapClient @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) {
    /**
     * Chargers within [distanceKm] of a route line. [line] should already be simplified;
     * long routes are split by the caller so each request stays well under OCM's result cap.
     */
    suspend fun stationsAlong(
        key: String,
        line: List<GeoPoint>,
        distanceKm: Int,
        operatorIds: Set<Int>?,
        operatorNames: Map<Int, String>,
    ): List<ChargerStation> {
        val response = client.get("$BASE/poi/") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-API-Key", key)
            parameter("polyline", PolylineCodec.encode(line))
            parameter("distance", distanceKm)
            parameter("distanceunit", "KM")
            parameter("connectiontypeid", CCS2)
            parameter("levelid", DC_FAST)
            parameter("statustypeid", OPERATIONAL)
            parameter("compact", true)
            parameter("verbose", false)
            parameter("maxresults", MAX_RESULTS)
            if (!operatorIds.isNullOrEmpty()) parameter("operatorid", operatorIds.joinToString(","))
        }
        checkStatus(response.status.value)
        val pois = json.decodeFromString(ListSerializer(OcmPoi.serializer()), response.bodyAsText())
        return pois.mapNotNull { it.toStation(operatorNames) }
    }

    /** All network operators (from OCM's reference data), for the Operators & Prices tab. */
    suspend fun operators(key: String): List<OcmOperator> {
        val response = client.get("$BASE/referencedata/") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-API-Key", key)
        }
        checkStatus(response.status.value)
        val data = json.decodeFromString(OcmReferenceData.serializer(), response.bodyAsText())
        return data.operators.mapNotNull { o ->
            val name = o.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            OcmOperator(o.id, name, o.websiteUrl)
        }
    }

    private fun checkStatus(code: Int) {
        if (code == 401 || code == 403) throw OcmKeyException("Open Charge Map rejected the API key ($code)")
        if (code !in 200..299) error("Open Charge Map error ($code)")
    }

    private fun OcmPoi.toStation(operatorNames: Map<Int, String>): ChargerStation? {
        val addr = addressInfo ?: return null
        val lat = addr.latitude ?: return null
        val lon = addr.longitude ?: return null
        val fast = connections.orEmpty().filter {
            (it.connectionTypeId == null || it.connectionTypeId == CCS2) && (it.levelId == null || it.levelId == DC_FAST)
        }
        val power = fast.mapNotNull { it.powerKw }.maxOrNull()?.toFloat() ?: UNKNOWN_POWER_KW
        val points = fast.sumOf { it.quantity ?: 1 }.takeIf { it > 0 } ?: numberOfPoints
        val opName = operatorId?.let { operatorNames[it] } ?: "Unknown operator"
        return ChargerStation(
            id = "ocm:$id",
            name = addr.title?.trim().orEmpty().ifEmpty { opName },
            operatorId = operatorId,
            operatorName = opName,
            latitude = lat,
            longitude = lon,
            maxPowerKw = power,
            totalPoints = points,
            usageCostRaw = usageCost?.trim()?.takeIf { it.isNotEmpty() },
            address = listOfNotNull(addr.addressLine1, addr.town).filter { it.isNotBlank() }.joinToString(", "),
        )
    }

    companion object {
        private const val BASE = "https://api.openchargemap.io/v3"
        private const val USER_AGENT = "ajNav/0.3 (+https://github.com/lyzio/ajNav-Open)"
        private const val CCS2 = 33
        private const val DC_FAST = 3
        private const val OPERATIONAL = 50
        const val MAX_RESULTS = 100
        /** Used for charge time when OCM doesn't list the power. */
        const val UNKNOWN_POWER_KW = 50f
    }
}

@Serializable
internal data class OcmPoi(
    @SerialName("ID") val id: Long,
    @SerialName("OperatorID") val operatorId: Int? = null,
    @SerialName("UsageCost") val usageCost: String? = null,
    @SerialName("NumberOfPoints") val numberOfPoints: Int? = null,
    @SerialName("AddressInfo") val addressInfo: OcmAddress? = null,
    @SerialName("Connections") val connections: List<OcmConnection>? = null,
)

@Serializable
internal data class OcmAddress(
    @SerialName("Title") val title: String? = null,
    @SerialName("AddressLine1") val addressLine1: String? = null,
    @SerialName("Town") val town: String? = null,
    @SerialName("Latitude") val latitude: Double? = null,
    @SerialName("Longitude") val longitude: Double? = null,
)

@Serializable
internal data class OcmConnection(
    @SerialName("ConnectionTypeID") val connectionTypeId: Int? = null,
    @SerialName("LevelID") val levelId: Int? = null,
    @SerialName("PowerKW") val powerKw: Double? = null,
    @SerialName("Quantity") val quantity: Int? = null,
)

@Serializable
internal data class OcmReferenceData(
    @SerialName("Operators") val operators: List<OcmOperatorDto> = emptyList(),
)

@Serializable
internal data class OcmOperatorDto(
    @SerialName("ID") val id: Int,
    @SerialName("Title") val title: String? = null,
    @SerialName("WebsiteURL") val websiteUrl: String? = null,
)
