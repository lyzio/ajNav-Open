package io.github.lyzio.ajnav.feature.navigation.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.speed.model.SpeedUnit
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.TripSessionState
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.ui.base.util.MapboxNavigationConsumer
import com.mapbox.navigation.voice.api.MapboxSpeechApi
import com.mapbox.navigation.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.voice.model.SpeechAnnouncement
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.Place
import io.github.lyzio.ajnav.feature.charging.ChargerAhead
import io.github.lyzio.ajnav.feature.charging.ChargingPlanService
import io.github.lyzio.ajnav.feature.charging.Corridor
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import io.github.lyzio.ajnav.feature.navigation.data.VoiceEngine
import io.github.lyzio.ajnav.feature.navigation.data.VoiceLanguage
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one shared trip for the whole app (v0.2). Both the phone screen and the Android Auto
 * screen read [state] and call the same actions, so a trip started on one shows on the other.
 * Map drawing lives in [MapRenderer]; there is one renderer per screen.
 *
 * v0.3 adds charging: the driver types the battery %, the planner suggests stops for the
 * previewed route, and "Add" puts a charger into the route as a stop (phone or car).
 *
 * Location comes from Mapbox's location service, which uses Android's own LocationManager
 * because Google Play Services is never bundled (principle 1).
 */
@Singleton
class TripController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NavPreferencesRepository,
    private val charging: ChargingPlanService,
) : MapboxNavigationObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    private val _previewRoutes = MutableStateFlow<List<NavigationRoute>>(emptyList())
    private val _activeRoutes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** The routes every map should draw: the active route, or else the preview. */
    val routesToDraw: StateFlow<List<NavigationRoute>> =
        combine(_activeRoutes, _previewRoutes) { active, preview -> active.ifEmpty { preview } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    private var mapboxNavigation: MapboxNavigation? = null
    private var lastLocation: Location? = null
    private var routeRequestId: Long? = null
    private var activeRequestId: Long? = null

    // Charging: what was fetched for the current route, and the running jobs.
    private var corridor: Corridor? = null
    private var corridorRouteId: String? = null
    private var prepareJob: Job? = null
    private var planJob: Job? = null
    private var distanceTraveledM = 0.0

    // Voice (N3): engine and language can change at any time; the voice objects follow.
    private var voiceEngine = VoiceEngine.MAPBOX
    private var voiceLanguage = VoiceLanguage.PHONE
    private var speechApi: MapboxSpeechApi? = null
    private var voicePlayer: MapboxVoiceInstructionsPlayer? = null
    private var voiceObjectsLanguage: String? = null
    private val mapboxVoiceCallback = MapboxNavigationConsumer<SpeechAnnouncement> { speechApi?.clean(it) }
    private val phoneVoiceCallback = MapboxNavigationConsumer<SpeechAnnouncement> { }

    private val maneuverApi = MapboxManeuverApi(
        MapboxDistanceFormatter(DistanceFormatterOptions.Builder(context).build()),
    )

    init {
        scope.launch { prefs.voiceEnabled.collect { _voiceEnabled.value = it } }
        scope.launch { prefs.voiceEngine.collect { voiceEngine = it } }
        scope.launch { prefs.voiceLanguage.collect { voiceLanguage = it } }
        scope.launch {
            charging.settings.collect { s ->
                setState {
                    it.copy(
                        charging = it.charging.copy(
                            batteryPercent = it.charging.batteryPercent ?: s.lastBatteryPercent,
                            arriveMinPercent = s.planner.arriveMinPercent.roundToInt(),
                        ),
                    )
                }
                // Energy use, targets, operators or prices changed: plan again.
                replan()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        this.mapboxNavigation = mapboxNavigation
        mapboxNavigation.registerLocationObserver(locationObserver)
        mapboxNavigation.registerRoutesObserver(routesObserver)
        mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
        mapboxNavigation.registerArrivalObserver(arrivalObserver)
        startTripSessionIfPermitted()
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterLocationObserver(locationObserver)
        mapboxNavigation.unregisterRoutesObserver(routesObserver)
        mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
        mapboxNavigation.unregisterArrivalObserver(arrivalObserver)
        this.mapboxNavigation = null
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startTripSessionIfPermitted() {
        val nav = mapboxNavigation ?: return
        if (hasLocationPermission() && nav.getTripSessionState() != TripSessionState.STARTED) {
            nav.startTripSession()
        }
    }

    // ---------------------------------------------------------------- actions

    /** Ask for a route to [place] and show it as a preview on every screen. */
    fun previewRoute(place: Place) {
        if (mapboxNavigation == null) return showMessage("Navigation is not ready yet")
        if (lastLocation == null) return showMessage("Waiting for GPS position…")
        resetCharging()
        setState {
            it.copy(
                mode = NavMode.ROUTING,
                destination = place,
                message = null,
                preview = null,
                chargeStops = emptyList(),
                charging = it.charging.copy(plan = null, notice = null, loading = false, outsideTempC = null),
            )
        }
        requestPreviewRoute()
    }

    fun clearPreview() {
        if (_state.value.mode == NavMode.ACTIVE) return
        routeRequestId?.let { mapboxNavigation?.cancelRouteRequest(it) }
        routeRequestId = null
        _previewRoutes.value = emptyList()
        resetCharging()
        setState {
            it.copy(
                mode = NavMode.FREE_DRIVE,
                preview = null,
                destination = null,
                chargeStops = emptyList(),
                charging = it.charging.copy(plan = null, notice = null, loading = false),
            )
        }
    }

    fun startNavigation() {
        val nav = mapboxNavigation ?: return
        val routes = _previewRoutes.value
        if (routes.isEmpty()) return
        nav.setNavigationRoutes(routes)
        _previewRoutes.value = emptyList()
        _state.value.destination?.let { place -> scope.launch { prefs.addRecent(place) } }
        distanceTraveledM = 0.0
        setState { it.copy(mode = NavMode.ACTIVE, preview = null, arrived = false, maneuver = null, progress = null) }
    }

    fun stopNavigation() {
        routeRequestId?.let { mapboxNavigation?.cancelRouteRequest(it) }
        routeRequestId = null
        activeRequestId?.let { mapboxNavigation?.cancelRouteRequest(it) }
        activeRequestId = null
        mapboxNavigation?.setNavigationRoutes(emptyList())
        _previewRoutes.value = emptyList()
        _activeRoutes.value = emptyList()
        voicePlayer?.clear()
        resetCharging()
        setState {
            it.copy(
                mode = NavMode.FREE_DRIVE,
                maneuver = null,
                progress = null,
                preview = null,
                arrived = false,
                destination = null,
                chargeStops = emptyList(),
                charging = it.charging.copy(plan = null, notice = null, loading = false),
            )
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        _voiceEnabled.value = enabled
        if (!enabled) voicePlayer?.clear()
        scope.launch { prefs.setVoiceEnabled(enabled) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---------------------------------------------------------------- charging actions (v0.3)

    /** The driver typed the battery level (route preview, phone or car). */
    fun setBatteryPercent(percent: Int) {
        val p = percent.coerceIn(0, 100)
        setState { it.copy(charging = it.charging.copy(batteryPercent = p)) }
        scope.launch { charging.setLastBattery(p) }
        replan()
    }

    /** "Add": put [station] into the route as a charging stop. Works in preview and while driving. */
    fun addChargeStop(station: ChargerStation) {
        val s = _state.value
        if (s.chargeStops.any { it.id == station.id }) return
        when (s.mode) {
            NavMode.PREVIEW, NavMode.ROUTING -> {
                setState { it.copy(chargeStops = orderAlongRoute(it.chargeStops + station)) }
                requestPreviewRoute()
            }
            NavMode.ACTIVE -> addStopWhileDriving(station)
            NavMode.FREE_DRIVE -> Unit
        }
    }

    /** Remove an added stop again (route preview only). */
    fun removeChargeStop(station: ChargerStation) {
        val s = _state.value
        if (s.mode != NavMode.PREVIEW && s.mode != NavMode.ROUTING) return
        if (s.chargeStops.none { it.id == station.id }) return
        setState { it.copy(chargeStops = it.chargeStops.filterNot { st -> st.id == station.id }) }
        requestPreviewRoute()
    }

    /** Chargers further along the route, nearest first (Android Auto "Chargers"). */
    suspend fun chargersAhead(limit: Int = 6): List<ChargerAhead> {
        val c = corridor ?: return emptyList()
        val traveled = if (_state.value.mode == NavMode.ACTIVE) distanceTraveledM else 0.0
        return charging.chargersAhead(c, traveled, limit)
    }

    // ---------------------------------------------------------------- routing helpers

    private fun requestPreviewRoute() {
        val nav = mapboxNavigation ?: return showMessage("Navigation is not ready yet")
        val origin = lastLocation ?: return showMessage("Waiting for GPS position…")
        val place = _state.value.destination ?: return
        routeRequestId?.let { nav.cancelRouteRequest(it) }
        _previewRoutes.value = emptyList()
        setState { it.copy(mode = NavMode.ROUTING, preview = null) }

        val options = routeOptions(
            Point.fromLngLat(origin.longitude, origin.latitude),
            _state.value.chargeStops,
            place,
        )
        routeRequestId = nav.requestRoutes(options, object : NavigationRouterCallback {
            override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                routeRequestId = null
                val route = routes.firstOrNull() ?: return onNoRoute("No route found")
                _previewRoutes.value = routes
                setState {
                    it.copy(
                        mode = NavMode.PREVIEW,
                        preview = RouteSummary(route.directionsRoute.duration(), route.directionsRoute.distance()),
                    )
                }
                prepareCharging(route)
            }

            override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                routeRequestId = null
                onNoRoute(reasons.firstOrNull()?.message ?: "No route found")
            }

            override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                routeRequestId = null
            }
        })
    }

    /** Re-route from here via the remaining stops plus [station], keeping guidance running. */
    private fun addStopWhileDriving(station: ChargerStation) {
        val nav = mapboxNavigation ?: return
        val origin = lastLocation ?: return showMessage("Waiting for GPS position…")
        val place = _state.value.destination ?: return
        val geometry = corridor?.geometry
        val remaining = _state.value.chargeStops.filter { st ->
            val p = geometry?.locate(st.point) ?: return@filter true
            p.distanceAlongM > distanceTraveledM + 300 && p.offsetM < 1_000
        }
        val stops = orderAlongRoute(remaining + station)
        activeRequestId?.let { nav.cancelRouteRequest(it) }
        showMessage("Adding charging stop: ${station.name}")
        activeRequestId = nav.requestRoutes(
            routeOptions(Point.fromLngLat(origin.longitude, origin.latitude), stops, place),
            object : NavigationRouterCallback {
                override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                    activeRequestId = null
                    if (_state.value.mode != NavMode.ACTIVE || routes.isEmpty()) return
                    nav.setNavigationRoutes(routes)
                    distanceTraveledM = 0.0
                    setState { it.copy(chargeStops = stops, message = "Charging stop added: ${station.name}") }
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    activeRequestId = null
                    showMessage("Couldn't add the stop: ${reasons.firstOrNull()?.message ?: "no route"}")
                }

                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                    activeRequestId = null
                }
            },
        )
    }

    private fun routeOptions(origin: Point, stops: List<ChargerStation>, place: Place): RouteOptions {
        val coordinates = listOf(origin) +
            stops.map { Point.fromLngLat(it.longitude, it.latitude) } +
            Point.fromLngLat(place.point.longitude, place.point.latitude)
        return RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .applyLanguageAndVoiceUnitOptions(context)
            .language(voiceLanguageTag())
            .coordinatesList(coordinates)
            .alternatives(false)
            .build()
    }

    private fun orderAlongRoute(stations: List<ChargerStation>): List<ChargerStation> {
        val geometry = corridor?.geometry ?: return stations
        return stations.sortedBy { geometry.locate(it.point).distanceAlongM }
    }

    // ---------------------------------------------------------------- charging helpers

    private fun prepareCharging(route: NavigationRoute) {
        prepareJob?.cancel()
        planJob?.cancel()
        val points = routePoints(route)
        if (points.size < 2) return
        val duration = route.directionsRoute.duration()
        setState { it.copy(charging = it.charging.copy(loading = true)) }
        prepareJob = scope.launch {
            val c = try {
                withContext(Dispatchers.Default) { charging.prepare(points, duration) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(charging = it.charging.copy(loading = false, notice = "Couldn't plan charging: ${e.message}")) }
                return@launch
            }
            corridor = c
            corridorRouteId = route.id
            computePlan()
        }
    }

    private fun replan() {
        val mode = _state.value.mode
        if (mode != NavMode.PREVIEW || corridor == null || prepareJob?.isActive == true) return
        planJob?.cancel()
        planJob = scope.launch { computePlan() }
    }

    private suspend fun computePlan() {
        val c = corridor ?: return
        val battery = _state.value.charging.batteryPercent
        val coldest = listOfNotNull(c.weatherMid?.temperatureC, c.weatherDest?.temperatureC).minOrNull()
        if (battery == null) {
            setState { it.copy(charging = it.charging.copy(loading = false, plan = null, notice = c.notice, outsideTempC = coldest)) }
            return
        }
        val stops = _state.value.chargeStops
        val plan = withContext(Dispatchers.Default) { charging.plan(c, battery.toDouble(), stops) }
        setState { it.copy(charging = it.charging.copy(loading = false, plan = plan, notice = c.notice, outsideTempC = coldest)) }
    }

    private fun resetCharging() {
        prepareJob?.cancel()
        planJob?.cancel()
        corridor = null
        corridorRouteId = null
        distanceTraveledM = 0.0
    }

    private fun routePoints(route: NavigationRoute): List<GeoPoint> {
        val encoded = route.directionsRoute.geometry() ?: return emptyList()
        return LineString.fromPolyline(encoded, POLYLINE6).coordinates().map { GeoPoint(it.latitude(), it.longitude()) }
    }

    /** Updates the state and keeps the map markers in step with it. */
    private fun setState(transform: (NavUiState) -> NavUiState) {
        _state.update { old ->
            val next = transform(old)
            next.copy(chargerMarkers = markersFor(next))
        }
    }

    private fun markersFor(s: NavUiState): List<ChargerMarker> {
        val added = s.chargeStops.map { ChargerMarker(it, ChargerMarkerKind.ADDED) }
        val suggested = if (s.mode == NavMode.PREVIEW) {
            s.charging.plan?.suggestions.orEmpty().map { ChargerMarker(it.plan.station, ChargerMarkerKind.SUGGESTED) }
        } else {
            emptyList()
        }
        return added + suggested
    }

    // ---------------------------------------------------------------- voice

    private fun voiceLanguageTag(): String = voiceLanguage.tag ?: Locale.getDefault().toLanguageTag()

    /** Created on first use, after the Mapbox token has been set (the car may start the app first). */
    private fun voiceObjects(): Pair<MapboxSpeechApi, MapboxVoiceInstructionsPlayer> {
        val lang = voiceLanguageTag()
        val api = speechApi
        val player = voicePlayer
        if (api != null && player != null && lang == voiceObjectsLanguage) return api to player
        player?.shutdown()
        api?.cancel()
        val newApi = MapboxSpeechApi(context, lang)
        val newPlayer = MapboxVoiceInstructionsPlayer(context, lang)
        speechApi = newApi
        voicePlayer = newPlayer
        voiceObjectsLanguage = lang
        return newApi to newPlayer
    }

    // ---------------------------------------------------------------- observers

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val location = locationMatcherResult.enhancedLocation
            lastLocation = location
            val speedKmh = location.speed?.let { (it * 3.6).roundToInt() }
            val limitInfo = locationMatcherResult.speedLimitInfo
            val limitKmh = limitInfo.speed?.let { limit ->
                when (limitInfo.unit) {
                    SpeedUnit.MILES_PER_HOUR -> (limit * 1.609344).roundToInt()
                    SpeedUnit.METERS_PER_SECOND -> (limit * 3.6).roundToInt()
                    else -> limit
                }
            }
            _state.update {
                it.copy(
                    location = GeoPoint(location.latitude, location.longitude),
                    speedKmh = speedKmh,
                    speedLimitKmh = limitKmh,
                )
            }
        }
    }

    private val routesObserver = RoutesObserver { result ->
        // Also covers automatic re-routes.
        _activeRoutes.value = result.navigationRoutes
        val route = result.navigationRoutes.firstOrNull() ?: return@RoutesObserver
        val c = corridor ?: return@RoutesObserver
        if (_state.value.mode != NavMode.ACTIVE && activeRequestId == null) return@RoutesObserver
        if (route.id == corridorRouteId) return@RoutesObserver
        corridorRouteId = route.id
        val points = routePoints(route)
        if (points.size < 2) return@RoutesObserver
        // Keep the fetched chargers, measured along the new route line.
        scope.launch { corridor = withContext(Dispatchers.Default) { charging.reproject(c, points) } }
    }

    private val routeProgressObserver = RouteProgressObserver { progress ->
        distanceTraveledM = progress.distanceTraveled.toDouble()
        val maneuver = maneuverApi.getManeuvers(progress).value?.firstOrNull()?.let { m ->
            val remaining = m.stepDistance.distanceRemaining ?: m.stepDistance.totalDistance
            ManeuverUi(
                primaryText = m.primary.text,
                distanceText = m.stepDistance.distanceFormatter.formatDistance(remaining).toString(),
                distanceRemainingM = remaining,
                type = m.primary.type,
                modifier = m.primary.modifier,
                degrees = m.primary.degrees,
                drivingSide = m.primary.drivingSide,
                lanes = m.laneGuidance?.allLanes.orEmpty().map { lane ->
                    LaneUi(lane.directions, lane.isActive, lane.activeDirection)
                },
            )
        }
        _state.update {
            it.copy(
                maneuver = maneuver ?: it.maneuver,
                progress = progress.toUi(),
            )
        }
    }

    private val voiceInstructionsObserver = VoiceInstructionsObserver { instructions ->
        if (!_voiceEnabled.value) return@VoiceInstructionsObserver
        val (api, player) = voiceObjects()
        if (voiceEngine == VoiceEngine.PHONE) {
            // The phone's own text-to-speech voice (N3), e.g. for street names Mapbox mispronounces.
            val text = instructions.announcement() ?: return@VoiceInstructionsObserver
            player.play(SpeechAnnouncement.Builder(text).build(), phoneVoiceCallback)
        } else {
            api.generate(instructions) { expected ->
                val announcement = expected.value?.announcement ?: expected.error?.fallback
                announcement?.let { player.play(it, mapboxVoiceCallback) }
            }
        }
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) = Unit
        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) = Unit
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            _state.update { it.copy(arrived = true) }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun onNoRoute(message: String) {
        _previewRoutes.value = emptyList()
        resetCharging()
        setState {
            it.copy(
                mode = NavMode.FREE_DRIVE,
                preview = null,
                destination = null,
                chargeStops = emptyList(),
                charging = it.charging.copy(plan = null, notice = null, loading = false),
                message = message,
            )
        }
    }

    private fun showMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    private fun RouteProgress.toUi() = ProgressUi(
        durationRemainingSec = durationRemaining,
        distanceRemainingM = distanceRemaining.toDouble(),
        etaEpochMs = System.currentTimeMillis() + (durationRemaining * 1000).toLong(),
    )

    private companion object {
        const val POLYLINE6 = 6
    }
}
