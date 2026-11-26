package io.github.lyzio.ajnav.feature.navigation.session

import android.content.Context
import android.graphics.Color
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.animation.CameraAnimationsPlugin
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Draws the shared trip on one map: LyzLarm route line, maneuver arrow, car puck, charger
 * markers and the following / overview camera. The phone uses one with its MapView, Android
 * Auto one with its car map surface. All trip logic lives in [TripController].
 *
 * Layer order, bottom to top: map, route line, maneuver arrow, chargers, car arrow (F1).
 * The map style (theme, N1) is loaded here via [applyTheme].
 */
class MapRenderer(
    context: Context,
    private val mapboxMap: MapboxMap,
    camera: CameraAnimationsPlugin,
    private val trip: TripController,
    followingPadding: EdgeInsets,
    overviewPadding: EdgeInsets,
) : MapboxNavigationObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _cameraFollowing = MutableStateFlow(true)
    val cameraFollowing: StateFlow<Boolean> = _cameraFollowing.asStateFlow()

    private var hasCenteredOnce = false
    private var drawnRoutes: List<NavigationRoute> = emptyList()
    private var markers: List<ChargerMarker> = emptyList()
    private var loadedDark: Boolean? = null

    val locationProvider = NavigationLocationProvider()

    private val viewportDataSource = MapboxNavigationViewportDataSource(mapboxMap).apply {
        this.followingPadding = followingPadding
        this.overviewPadding = overviewPadding
    }
    private val navigationCamera = NavigationCamera(mapboxMap, camera, viewportDataSource)

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
            // F1: the route sits below the charger markers, which sit below the car arrow.
            .routeLineBelowLayerId(CHARGER_LAYER)
            .build(),
    )
    private val routeArrowApi = MapboxRouteArrowApi()
    private val routeArrowView = MapboxRouteArrowView(
        RouteArrowOptions.Builder(context)
            .withAboveLayerId(RouteLayerConstants.TOP_LEVEL_ROUTE_LINE_LAYER_ID)
            .build(),
    )

    init {
        camera.addCameraAnimationsLifecycleListener(NavigationBasicGesturesHandler(navigationCamera))
        navigationCamera.registerNavigationCameraStateChangeObserver { camState ->
            _cameraFollowing.value = camState == NavigationCameraState.FOLLOWING ||
                camState == NavigationCameraState.TRANSITION_TO_FOLLOWING
        }

        scope.launch {
            trip.routesToDraw.collect { routes ->
                drawnRoutes = routes
                if (routes.isEmpty()) clearRouteGraphics() else drawRoutes(routes)
                routes.firstOrNull()?.let { viewportDataSource.onRouteChanged(it) }
                    ?: viewportDataSource.clearRouteData()
                viewportDataSource.evaluate()
                if (routes.isNotEmpty() && trip.state.value.mode == NavMode.PREVIEW) {
                    navigationCamera.requestNavigationCameraToOverview()
                }
            }
        }
        scope.launch {
            trip.state.map { it.chargerMarkers }.distinctUntilChanged().collect { list ->
                markers = list
                mapboxMap.style?.let { updateMarkers(it) }
            }
        }
        // The camera follows the trip mode, so phone and car frame the same thing.
        scope.launch {
            trip.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                when (mode) {
                    NavMode.PREVIEW -> navigationCamera.requestNavigationCameraToOverview()
                    NavMode.ACTIVE, NavMode.FREE_DRIVE -> {
                        if (hasCenteredOnce) navigationCamera.requestNavigationCameraToFollowing()
                    }
                    NavMode.ROUTING -> Unit
                }
            }
        }
    }

    /** Loads the dark (green) or light map (N1). Does nothing if that theme is already loaded. */
    fun applyTheme(dark: Boolean) {
        if (loadedDark == dark) return
        loadedDark = dark
        mapboxMap.loadStyle(MapStyler.baseStyle(dark)) { style ->
            MapStyler.recolor(style, dark)
            addChargerLayer(style)
            if (drawnRoutes.isNotEmpty()) drawRoutes(drawnRoutes)
        }
    }

    fun setPadding(following: EdgeInsets, overview: EdgeInsets) {
        viewportDataSource.followingPadding = following
        viewportDataSource.overviewPadding = overview
        viewportDataSource.evaluate()
    }

    fun recenter() = navigationCamera.requestNavigationCameraToFollowing()

    fun overview() = navigationCamera.requestNavigationCameraToOverview()

    fun destroy() {
        scope.cancel()
        routeLineApi.cancel()
        routeLineView.cancel()
    }

    // ---------------------------------------------------------------- MapboxNavigationObserver

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.registerLocationObserver(locationObserver)
        mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterLocationObserver(locationObserver)
        mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: com.mapbox.common.location.Location) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val location = locationMatcherResult.enhancedLocation
            locationProvider.changePosition(location, locationMatcherResult.keyPoints)
            viewportDataSource.onLocationChanged(location)
            viewportDataSource.evaluate()
            if (!hasCenteredOnce) {
                hasCenteredOnce = true
                if (trip.state.value.mode == NavMode.PREVIEW) {
                    navigationCamera.requestNavigationCameraToOverview()
                } else {
                    navigationCamera.requestNavigationCameraToFollowing()
                }
            }
        }
    }

    private val routeProgressObserver = RouteProgressObserver { progress ->
        viewportDataSource.onRouteProgressChanged(progress)
        viewportDataSource.evaluate()
        mapboxMap.style?.let { style ->
            routeArrowView.renderManeuverUpdate(style, routeArrowApi.addUpcomingManeuverArrow(progress))
        }
        routeLineApi.updateWithRouteProgress(progress) { update ->
            mapboxMap.style?.let { routeLineView.renderRouteLineUpdate(it, update) }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun drawRoutes(routes: List<NavigationRoute>) {
        routeLineApi.setNavigationRoutes(routes) { value ->
            mapboxMap.style?.let { routeLineView.renderRouteDrawData(it, value) }
        }
    }

    private fun addChargerLayer(style: Style) {
        if (!style.styleSourceExists(CHARGER_SOURCE)) {
            style.addSource(geoJsonSource(CHARGER_SOURCE) { featureCollection(markerFeatures()) })
        }
        if (!style.styleLayerExists(CHARGER_LAYER)) {
            val layer = circleLayer(CHARGER_LAYER, CHARGER_SOURCE) {
                circleColor(Expression.get("color"))
                circleRadius(9.0)
                circleStrokeColor("#EEF3F3")
                circleStrokeWidth(2.5)
            }
            if (style.styleLayerExists(PUCK_LAYER)) style.addLayerBelow(layer, PUCK_LAYER) else style.addLayer(layer)
        }
    }

    private fun updateMarkers(style: Style) {
        style.getSourceAs<GeoJsonSource>(CHARGER_SOURCE)?.featureCollection(markerFeatures())
    }

    private fun markerFeatures(): FeatureCollection = FeatureCollection.fromFeatures(
        markers.map { m ->
            Feature.fromGeometry(Point.fromLngLat(m.station.longitude, m.station.latitude)).apply {
                addStringProperty("color", if (m.kind == ChargerMarkerKind.ADDED) ADDED_COLOR else SUGGESTED_COLOR)
            }
        },
    )

    private fun clearRouteGraphics() {
        routeLineApi.clearRouteLine { value ->
            mapboxMap.style?.let { routeLineView.renderClearRouteLineValue(it, value) }
        }
        mapboxMap.style?.let { routeArrowView.render(it, routeArrowApi.clearArrows()) }
    }

    private companion object {
        val TEAL = Color.parseColor("#4FB3A9")
        val PILL = Color.parseColor("#006266")
        const val CHARGER_SOURCE = "ajnav-chargers-source"
        const val CHARGER_LAYER = "ajnav-chargers-layer"
        /** Mapbox's 2D location puck layer (the car arrow). */
        const val PUCK_LAYER = "mapbox-location-indicator-layer"
        const val SUGGESTED_COLOR = "#F2B134"
        const val ADDED_COLOR = "#4FB3A9"
    }
}
