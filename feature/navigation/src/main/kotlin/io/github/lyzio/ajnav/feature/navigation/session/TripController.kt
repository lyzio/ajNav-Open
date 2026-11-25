package io.github.lyzio.ajnav.feature.navigation.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.location.Location
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
import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.Place
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one shared trip for the whole app (v0.2). Both the phone screen and the Android Auto
 * screen read [state] and call the same actions, so a trip started on one shows on the other.
 * Map drawing lives in [MapRenderer]; there is one renderer per screen.
 *
 * Location comes from Mapbox's location service, which uses Android's own LocationManager
 * because Google Play Services is never bundled (principle 1).
 */
@Singleton
class TripController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: NavPreferencesRepository,
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

    private val language = Locale.getDefault().toLanguageTag()
    private val maneuverApi = MapboxManeuverApi(
        MapboxDistanceFormatter(DistanceFormatterOptions.Builder(context).build()),
    )
    // Created on first use, after the Mapbox token has been set (the car may start the app first).
    private val speechApi by lazy { MapboxSpeechApi(context, language) }
    private val voicePlayer by lazy { MapboxVoiceInstructionsPlayer(context, language) }
    private var voiceUsed = false
    private val voicePlayerCallback = MapboxNavigationConsumer<SpeechAnnouncement> { speechApi.clean(it) }

    init {
        scope.launch { prefs.voiceEnabled.collect { _voiceEnabled.value = it } }
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
        val nav = mapboxNavigation ?: return showMessage("Navigation is not ready yet")
        val origin = lastLocation ?: return showMessage("Waiting for GPS position…")
        routeRequestId?.let { nav.cancelRouteRequest(it) }
        _previewRoutes.value = emptyList()
        _state.update { it.copy(mode = NavMode.ROUTING, destination = place, message = null, preview = null) }

        val options = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .applyLanguageAndVoiceUnitOptions(context)
            .coordinatesList(
                listOf(
                    Point.fromLngLat(origin.longitude, origin.latitude),
                    Point.fromLngLat(place.point.longitude, place.point.latitude),
                ),
            )
            .alternatives(false)
            .build()

        routeRequestId = nav.requestRoutes(options, object : NavigationRouterCallback {
            override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                routeRequestId = null
                val route = routes.firstOrNull() ?: return onNoRoute("No route found")
                _previewRoutes.value = routes
                _state.update {
                    it.copy(
                        mode = NavMode.PREVIEW,
                        preview = RouteSummary(route.directionsRoute.duration(), route.directionsRoute.distance()),
                    )
                }
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

    fun clearPreview() {
        if (_state.value.mode == NavMode.ACTIVE) return
        routeRequestId?.let { mapboxNavigation?.cancelRouteRequest(it) }
        routeRequestId = null
        _previewRoutes.value = emptyList()
        _state.update { it.copy(mode = NavMode.FREE_DRIVE, preview = null, destination = null) }
    }

    fun startNavigation() {
        val nav = mapboxNavigation ?: return
        val routes = _previewRoutes.value
        if (routes.isEmpty()) return
        nav.setNavigationRoutes(routes)
        _previewRoutes.value = emptyList()
        _state.value.destination?.let { place -> scope.launch { prefs.addRecent(place) } }
        _state.update { it.copy(mode = NavMode.ACTIVE, preview = null, arrived = false, maneuver = null, progress = null) }
    }

    fun stopNavigation() {
        routeRequestId?.let { mapboxNavigation?.cancelRouteRequest(it) }
        routeRequestId = null
        mapboxNavigation?.setNavigationRoutes(emptyList())
        _previewRoutes.value = emptyList()
        _activeRoutes.value = emptyList()
        if (voiceUsed) voicePlayer.clear()
        _state.update {
            it.copy(
                mode = NavMode.FREE_DRIVE,
                maneuver = null,
                progress = null,
                preview = null,
                arrived = false,
                destination = null,
            )
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        _voiceEnabled.value = enabled
        if (!enabled && voiceUsed) voicePlayer.clear()
        scope.launch { prefs.setVoiceEnabled(enabled) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
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
    }

    private val routeProgressObserver = RouteProgressObserver { progress ->
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
        voiceUsed = true
        speechApi.generate(instructions) { expected ->
            val announcement = expected.value?.announcement ?: expected.error?.fallback
            announcement?.let { voicePlayer.play(it, voicePlayerCallback) }
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
        _state.update { it.copy(mode = NavMode.FREE_DRIVE, preview = null, destination = null, message = message) }
    }

    private fun showMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    private fun RouteProgress.toUi() = ProgressUi(
        durationRemainingSec = durationRemaining,
        distanceRemainingM = distanceRemaining.toDouble(),
        etaEpochMs = System.currentTimeMillis() + (durationRemaining * 1000).toLong(),
    )
}
