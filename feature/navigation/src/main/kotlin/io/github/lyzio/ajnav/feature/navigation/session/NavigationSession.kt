package io.github.lyzio.ajnav.feature.navigation.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.camera
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
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.navigation.voice.api.MapboxSpeechApi
import com.mapbox.navigation.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.voice.model.SpeechAnnouncement
import io.github.lyzio.ajnav.core.model.GeoPoint
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the Mapbox map + navigation wiring for one MapView and exposes plain UI state.
 * Location comes from Mapbox's location service, which uses Android's own LocationManager
 * because Google Play Services is never bundled (principle 1).
 */
class NavigationSession(
    private val context: Context,
    private val mapView: MapView,
) : MapboxNavigationObserver {

    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    var voiceEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) voicePlayer.clear()
        }

    private var mapboxNavigation: MapboxNavigation? = null
    private var lastLocation: Location? = null
    private var previewRoutes: List<NavigationRoute> = emptyList()
    private var routeRequestId: Long? = null
    private var hasCenteredOnce = false

    private val density = context.resources.displayMetrics.density
    private val language = Locale.getDefault().toLanguageTag()

    // --- Map / camera ---
    private val navigationLocationProvider = NavigationLocationProvider()
    private val viewportDataSource = MapboxNavigationViewportDataSource(mapView.mapboxMap).apply {
        followingPadding = EdgeInsets(200.0 * density, 40.0 * density, 140.0 * density, 40.0 * density)
        overviewPadding = EdgeInsets(140.0 * density, 40.0 * density, 260.0 * density, 40.0 * density)
    }
    private val navigationCamera = NavigationCamera(mapView.mapboxMap, mapView.camera, viewportDataSource)

    // --- Route line (LyzLarm teal) ---
    private val routeLineApi = MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build())
    private val routeLineView = MapboxRouteLineView(
        MapboxRouteLineViewOptions.Builder(context)
            .routeLineColorResources(
                RouteLineColorResources.Builder()
                    .routeDefaultColor(TEAL)
                    .routeLowCongestionColor(TEAL)
                    .routeUnknownCongestionColor(TEAL)
                    .routeCasingColor(PILL)
                    .build(),
            )
            .routeLineBelowLayerId("road-label")
            .build(),
    )
    private val routeArrowApi = MapboxRouteArrowApi()
    private val routeArrowView = MapboxRouteArrowView(
        RouteArrowOptions.Builder(context)
            .withAboveLayerId(RouteLayerConstants.TOP_LEVEL_ROUTE_LINE_LAYER_ID)
            .build(),
    )

    // --- Trip data / voice ---
    private val distanceFormatterOptions = DistanceFormatterOptions.Builder(context).build()
    private val maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(distanceFormatterOptions))
    private val speechApi = MapboxSpeechApi(context, language)
    private val voicePlayer = MapboxVoiceInstructionsPlayer(context, language)
    private val voicePlayerCallback = MapboxNavigationConsumer<SpeechAnnouncement> { speechApi.clean(it) }

    init {
        mapView.camera.addCameraAnimationsLifecycleListener(NavigationBasicGesturesHandler(navigationCamera))
        navigationCamera.registerNavigationCameraStateChangeObserver { camState ->
            val following = camState == NavigationCameraState.FOLLOWING ||
                camState == NavigationCameraState.TRANSITION_TO_FOLLOWING
            _state.update { it.copy(cameraFollowing = following) }
        }
    }

    /** The location provider the map's puck should use. */
    val locationProvider: NavigationLocationProvider get() = navigationLocationProvider

    fun onStyleLoaded(@Suppress("UNUSED_PARAMETER") style: Style) {
        // Re-draw routes if the style was (re)loaded while a route exists.
        val routes = mapboxNavigation?.getNavigationRoutes().orEmpty().ifEmpty { previewRoutes }
        if (routes.isNotEmpty()) drawRoutes(routes)
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

    @SuppressLint("MissingPermission")
    fun startTripSessionIfPermitted() {
        val nav = mapboxNavigation ?: return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted && nav.getTripSessionState() != TripSessionState.STARTED) {
            nav.startTripSession()
        }
    }

    fun destroy() {
        routeLineApi.cancel()
        routeLineView.cancel()
        maneuverApi.cancel()
        speechApi.cancel()
        voicePlayer.shutdown()
    }

    // ---------------------------------------------------------------- actions

    fun previewRoute(destination: GeoPoint) {
        val nav = mapboxNavigation ?: return showMessage("Navigation is not ready yet")
        val origin = lastLocation ?: return showMessage("Waiting for GPS position…")
        routeRequestId?.let { nav.cancelRouteRequest(it) }
        _state.update { it.copy(mode = NavMode.ROUTING, message = null, preview = null) }

        val options = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .applyLanguageAndVoiceUnitOptions(context)
            .coordinatesList(
                listOf(
                    Point.fromLngLat(origin.longitude, origin.latitude),
                    Point.fromLngLat(destination.longitude, destination.latitude),
                ),
            )
            .alternatives(false)
            .build()

        routeRequestId = nav.requestRoutes(options, object : NavigationRouterCallback {
            override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                routeRequestId = null
                val route = routes.firstOrNull() ?: return onNoRoute("No route found")
                previewRoutes = routes
                drawRoutes(routes)
                viewportDataSource.onRouteChanged(route)
                viewportDataSource.evaluate()
                navigationCamera.requestNavigationCameraToOverview()
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
        routeRequestId?.let { mapboxNavigation?.cancelRouteRequest(it) }
        previewRoutes = emptyList()
        clearRouteGraphics()
        navigationCamera.requestNavigationCameraToFollowing()
        _state.update { it.copy(mode = NavMode.FREE_DRIVE, preview = null) }
    }

    fun startNavigation() {
        val nav = mapboxNavigation ?: return
        if (previewRoutes.isEmpty()) return
        nav.setNavigationRoutes(previewRoutes)
        previewRoutes = emptyList()
        navigationCamera.requestNavigationCameraToFollowing()
        _state.update { it.copy(mode = NavMode.ACTIVE, preview = null, arrived = false) }
    }

    fun stopNavigation() {
        mapboxNavigation?.setNavigationRoutes(emptyList())
        previewRoutes = emptyList()
        voicePlayer.clear()
        clearRouteGraphics()
        navigationCamera.requestNavigationCameraToFollowing()
        _state.update {
            it.copy(mode = NavMode.FREE_DRIVE, maneuver = null, progress = null, preview = null, arrived = false)
        }
    }

    fun recenter() {
        navigationCamera.requestNavigationCameraToFollowing()
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
            navigationLocationProvider.changePosition(location, locationMatcherResult.keyPoints)
            viewportDataSource.onLocationChanged(location)
            viewportDataSource.evaluate()
            if (!hasCenteredOnce) {
                hasCenteredOnce = true
                navigationCamera.requestNavigationCameraToFollowing()
            }

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
        val routes = result.navigationRoutes
        if (routes.isNotEmpty()) {
            drawRoutes(routes) // also covers automatic re-routes
            viewportDataSource.onRouteChanged(routes.first())
        } else if (previewRoutes.isEmpty()) {
            clearRouteGraphics()
            viewportDataSource.clearRouteData()
        }
        viewportDataSource.evaluate()
    }

    private val routeProgressObserver = RouteProgressObserver { progress ->
        viewportDataSource.onRouteProgressChanged(progress)
        viewportDataSource.evaluate()

        mapView.mapboxMap.style?.let { style ->
            routeArrowView.renderManeuverUpdate(style, routeArrowApi.addUpcomingManeuverArrow(progress))
        }
        routeLineApi.updateWithRouteProgress(progress) { update ->
            mapView.mapboxMap.style?.let { routeLineView.renderRouteLineUpdate(it, update) }
        }

        val maneuver = maneuverApi.getManeuvers(progress).value?.firstOrNull()?.let { m ->
            val remaining = m.stepDistance.distanceRemaining ?: m.stepDistance.totalDistance
            ManeuverUi(
                primaryText = m.primary.text,
                distanceText = m.stepDistance.distanceFormatter.formatDistance(remaining).toString(),
                type = m.primary.type,
                modifier = m.primary.modifier,
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
        if (!voiceEnabled) return@VoiceInstructionsObserver
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

    private fun drawRoutes(routes: List<NavigationRoute>) {
        routeLineApi.setNavigationRoutes(routes) { value ->
            mapView.mapboxMap.style?.let { routeLineView.renderRouteDrawData(it, value) }
        }
    }

    private fun clearRouteGraphics() {
        routeLineApi.clearRouteLine { value ->
            mapView.mapboxMap.style?.let { routeLineView.renderClearRouteLineValue(it, value) }
        }
        mapView.mapboxMap.style?.let { routeArrowView.render(it, routeArrowApi.clearArrows()) }
        viewportDataSource.clearRouteData()
        viewportDataSource.evaluate()
    }

    private fun onNoRoute(message: String) {
        _state.update { it.copy(mode = NavMode.FREE_DRIVE, preview = null, message = message) }
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
        val TEAL = Color.parseColor("#4FB3A9")
        val PILL = Color.parseColor("#006266")
    }
}
