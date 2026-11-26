package io.github.lyzio.ajnav.feature.charging

import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.WeatherSnapshot
import io.github.lyzio.ajnav.core.security.ApiKey
import io.github.lyzio.ajnav.core.security.ApiKeyRepository
import io.github.lyzio.ajnav.feature.charging.cache.ChargingDatabase
import io.github.lyzio.ajnav.feature.charging.cache.OperatorEntity
import io.github.lyzio.ajnav.feature.charging.cache.StationEntity
import io.github.lyzio.ajnav.feature.charging.data.ChargingPreferencesRepository
import io.github.lyzio.ajnav.feature.charging.data.ChargingSettings
import io.github.lyzio.ajnav.feature.charging.energy.OpenMeteoElevationProvider
import io.github.lyzio.ajnav.feature.charging.ocm.OcmKeyException
import io.github.lyzio.ajnav.feature.charging.ocm.OcmMissingKeyException
import io.github.lyzio.ajnav.feature.charging.ocm.OcmOperator
import io.github.lyzio.ajnav.feature.charging.ocm.OpenChargeMapClient
import io.github.lyzio.ajnav.feature.charging.planner.ChargePlan
import io.github.lyzio.ajnav.feature.charging.planner.CorridorCharger
import io.github.lyzio.ajnav.feature.charging.planner.EnergyModel
import io.github.lyzio.ajnav.feature.charging.planner.OperatorMode
import io.github.lyzio.ajnav.feature.charging.planner.PolylineCodec
import io.github.lyzio.ajnav.feature.charging.planner.RouteGeometry
import io.github.lyzio.ajnav.feature.charging.planner.StopPlanner
import io.github.lyzio.ajnav.feature.charging.tariff.ChainedTariffProvider
import io.github.lyzio.ajnav.feature.charging.tariff.Price
import io.github.lyzio.ajnav.feature.trafficse.SmhiWeatherClient
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the chargers in a [Corridor] came from. */
enum class ChargerSource { NETWORK, CACHE, NONE }

/** Everything fetched once per route; re-planning (new battery %, new settings) reuses it. */
data class Corridor(
    val geometry: RouteGeometry,
    val chargers: List<CorridorCharger>,
    val sampleDistancesM: List<Double>,
    val elevationsM: List<Double>?,
    val weatherMid: WeatherSnapshot?,
    val weatherDest: WeatherSnapshot?,
    val source: ChargerSource,
    /** Something the driver should know, e.g. "No Open Charge Map key" or "showing saved chargers". */
    val notice: String?,
) {
    fun weatherAt(distanceM: Double): WeatherSnapshot? =
        if (distanceM < geometry.totalM / 2) weatherMid ?: weatherDest else weatherDest ?: weatherMid

    /** Outside temperature stands in for battery temperature without the car link (decision D22). */
    fun temperatureAt(distanceM: Double): Double? = weatherAt(distanceM)?.temperatureC
}

/** A charger further along the active route (Android Auto "Chargers" list). */
data class ChargerAhead(
    val station: ChargerStation,
    val distanceAheadM: Double,
    val offsetM: Double,
    val price: Price,
)

/**
 * Charging stops for a route (design §4.4, §4.6 C): fetches chargers (Open Charge Map, falling
 * back to the offline cache), weather (SMHI) and heights (Open-Meteo), then runs the planner.
 */
@Singleton
class ChargingPlanService @Inject constructor(
    private val ocm: OpenChargeMapClient,
    private val elevation: OpenMeteoElevationProvider,
    private val smhi: SmhiWeatherClient,
    private val db: ChargingDatabase,
    private val prefs: ChargingPreferencesRepository,
    private val apiKeys: ApiKeyRepository,
) {
    val settings: Flow<ChargingSettings> = prefs.settings

    val operators: Flow<List<OcmOperator>> = db.operators().observeAll().map { list ->
        list.map { OcmOperator(it.id, it.name, it.websiteUrl) }
    }

    val hasOcmKey: Flow<Boolean> = apiKeys.observe(ApiKey.OCM_API_KEY).map { !it.isNullOrBlank() }

    private val operatorMutex = Mutex()

    /** Loads the operator list from Open Charge Map if it is missing or older than 30 days. */
    suspend fun refreshOperators(force: Boolean = false): Result<Unit> = operatorMutex.withLock {
        val key = apiKeys.get(ApiKey.OCM_API_KEY) ?: return Result.failure(OcmMissingKeyException(NO_KEY))
        val oldest = db.operators().oldestFetch()
        val now = System.currentTimeMillis()
        if (!force && oldest != null && now - oldest < OPERATORS_MAX_AGE_MS) return Result.success(Unit)
        return try {
            val list = ocm.operators(key)
            db.operators().upsert(list.map { OperatorEntity(it.id, it.name, it.websiteUrl, now) })
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setLastBattery(percent: Int) = prefs.setLastBattery(percent)

    /** Fetches chargers, weather and heights for a route line ([points] from start to destination). */
    suspend fun prepare(points: List<GeoPoint>, durationSec: Double): Corridor = coroutineScope {
        val geometry = RouteGeometry(points)
        val samples = geometry.sample(EnergyModel.SAMPLE_STEP_M)
        val now = Instant.now()
        val stations = async { loadStations(geometry) }
        val weatherMid = async {
            quietly { smhi.forecast(geometry.pointAt(geometry.totalM / 2), now.plusSeconds((durationSec / 2).toLong())) }
        }
        val weatherDest = async { quietly { smhi.forecast(geometry.end, now.plusSeconds(durationSec.toLong())) } }
        val heights = async { quietly { elevation.elevations(samples.map { geometry.pointAt(it) }) } }
        val (chargers, source, notice) = stations.await()
        Corridor(geometry, chargers, samples, heights.await(), weatherMid.await(), weatherDest.await(), source, notice)
    }

    /** Moves an existing corridor onto a new route line (e.g. after a re-route) without fetching again. */
    fun reproject(corridor: Corridor, points: List<GeoPoint>): Corridor {
        val geometry = RouteGeometry(points)
        val chargers = corridor.chargers.mapNotNull { c ->
            val p = geometry.locate(c.station.point)
            if (p.offsetM <= CORRIDOR_KEEP_M) CorridorCharger(c.station, p.distanceAlongM, p.offsetM) else null
        }
        return corridor.copy(
            geometry = geometry,
            chargers = chargers,
            sampleDistancesM = geometry.sample(EnergyModel.SAMPLE_STEP_M),
            elevationsM = null, // heights belonged to the old samples; the re-routed part is treated as flat
        )
    }

    /** Plans charging stops for a typed battery %. [added] are the stops already in the route. */
    suspend fun plan(corridor: Corridor, batteryPercent: Double, added: List<ChargerStation>): ChargePlan {
        val s = settings.first()
        val energy = EnergyModel.buildProfile(
            route = corridor.geometry,
            baseKwhPer100Km = s.planner.consumptionKwhPer100Km,
            massKg = s.profile.vehicleMassKg.toDouble(),
            sampleDistancesM = corridor.sampleDistancesM,
            elevationsM = corridor.elevationsM,
            weatherAt = corridor::weatherAt,
        )
        val addedOnRoute = added.map { st ->
            val p = corridor.geometry.locate(st.point)
            CorridorCharger(st, p.distanceAlongM, p.offsetM)
        }
        return StopPlanner.plan(
            route = corridor.geometry,
            energy = energy,
            startSocPercent = batteryPercent,
            profile = s.profile,
            settings = s.planner,
            candidates = corridor.chargers,
            addedStops = addedOnRoute,
            temperatureAt = corridor::temperatureAt,
        )
    }

    /** Chargers ahead of [traveledM] on the route, nearest first (respects "only these operators"). */
    suspend fun chargersAhead(corridor: Corridor, traveledM: Double, limit: Int = 6): List<ChargerAhead> {
        val s = settings.first().planner
        val onlyIds = s.preferredOperatorIds.takeIf { s.operatorMode == OperatorMode.ONLY && it.isNotEmpty() }
        return corridor.chargers
            .filter { it.distanceAlongM > traveledM + 200 }
            .filter { onlyIds == null || it.station.operatorId in onlyIds }
            .sortedBy { it.distanceAlongM }
            .take(limit)
            .map { ChargerAhead(it.station, it.distanceAlongM - traveledM, it.offsetM, ChainedTariffProvider.price(it.station, s.prices)) }
    }

    // ---------------------------------------------------------------- stations

    private data class StationsResult(val chargers: List<CorridorCharger>, val source: ChargerSource, val notice: String?)

    private suspend fun loadStations(geometry: RouteGeometry): StationsResult {
        val key = apiKeys.get(ApiKey.OCM_API_KEY)
        if (key.isNullOrBlank()) {
            val cached = fromCache(geometry)
            return StationsResult(cached, if (cached.isEmpty()) ChargerSource.NONE else ChargerSource.CACHE, NO_KEY)
        }
        return try {
            val names = operatorNames()
            val found = LinkedHashMap<String, ChargerStation>()
            for (chunk in chunks(geometry)) {
                ocm.stationsAlong(key, chunk, CORRIDOR_KM, operatorIds = null, operatorNames = names)
                    .forEach { found[it.id] = it }
            }
            val now = System.currentTimeMillis()
            db.stations().upsert(found.values.map { StationEntity.from(it, now) })
            db.stations().deleteOlderThan(now - CACHE_MAX_AGE_MS)
            StationsResult(onRoute(geometry, found.values), ChargerSource.NETWORK, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cached = fromCache(geometry)
            val why = if (e is OcmKeyException) "Open Charge Map rejected your key. Check it in Settings." else "No connection to Open Charge Map."
            val notice = if (cached.isEmpty()) "$why No saved chargers for this route." else "$why Showing saved chargers."
            StationsResult(cached, if (cached.isEmpty()) ChargerSource.NONE else ChargerSource.CACHE, notice)
        }
    }

    private suspend fun operatorNames(): Map<Int, String> {
        if (db.operators().oldestFetch() == null) refreshOperators()
        return db.operators().all().associate { it.id to it.name }
    }

    private suspend fun fromCache(geometry: RouteGeometry): List<CorridorCharger> {
        if (geometry.points.size < 2) return emptyList()
        val margin = 0.05
        val lats = geometry.points.map { it.latitude }
        val lons = geometry.points.map { it.longitude }
        val rows = db.stations().inBox(lats.min() - margin, lats.max() + margin, lons.min() - margin * 2, lons.max() + margin * 2)
        return onRoute(geometry, rows.map { it.toStation() })
    }

    private fun onRoute(geometry: RouteGeometry, stations: Collection<ChargerStation>): List<CorridorCharger> =
        stations.mapNotNull { st ->
            val p = geometry.locate(st.point)
            if (p.offsetM <= CORRIDOR_KEEP_M) CorridorCharger(st, p.distanceAlongM, p.offsetM) else null
        }.sortedBy { it.distanceAlongM }

    /** Simplified route split into pieces of about 100 km, so no request hits OCM's result cap. */
    private fun chunks(geometry: RouteGeometry): List<List<GeoPoint>> {
        val simple = PolylineCodec.simplify(geometry.points, SIMPLIFY_TOLERANCE_M)
        val out = ArrayList<List<GeoPoint>>()
        var current = ArrayList<GeoPoint>()
        var length = 0.0
        for (p in simple) {
            current.lastOrNull()?.let { length += io.github.lyzio.ajnav.feature.charging.planner.GeoMath.distanceM(it, p) }
            current += p
            if (length >= CHUNK_M) {
                out += current
                current = arrayListOf(p)
                length = 0.0
            }
        }
        if (current.size >= 2 || out.isEmpty()) out += current
        return out.filter { it.size >= 2 }
    }

    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val NO_KEY = "Add your free Open Charge Map key in Settings to find chargers."
        private const val CORRIDOR_KM = 3
        private const val CORRIDOR_KEEP_M = 3_500.0
        private const val CHUNK_M = 100_000.0
        private const val SIMPLIFY_TOLERANCE_M = 150.0
        private const val CACHE_MAX_AGE_MS = 90L * 24 * 3600 * 1000
        private const val OPERATORS_MAX_AGE_MS = 30L * 24 * 3600 * 1000
    }
}
