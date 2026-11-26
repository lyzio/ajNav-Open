@file:OptIn(MapboxExperimental::class)

package io.github.lyzio.ajnav.car

import android.graphics.Rect
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.renderer.widget.BitmapWidget
import com.mapbox.maps.renderer.widget.WidgetPosition
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.github.lyzio.ajnav.feature.navigation.R
import io.github.lyzio.ajnav.feature.navigation.data.MapTheme
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import io.github.lyzio.ajnav.feature.navigation.session.MapRenderer
import io.github.lyzio.ajnav.feature.navigation.session.NavMode
import io.github.lyzio.ajnav.feature.navigation.session.TripController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Draws the shared trip on the Android Auto map surface: same map theme, teal route, charger
 * markers and coral puck as the phone, plus the speed / speed-limit badge (templates have no
 * field for it). In free drive the driver can hide the map behind the ajNav logo screen (N2).
 */
internal class CarMapController(
    private val trip: TripController,
    private val prefs: NavPreferencesRepository,
    private val display: CarDisplayState,
) : MapboxCarMapObserver {

    private var renderer: MapRenderer? = null
    private var speedWidget: BitmapWidget? = null
    private var logoWidget: BitmapWidget? = null
    private var scope: CoroutineScope? = null
    private var lastInsets: EdgeInsets? = null
    private var lastVisibleHeight: Int = 0
    private var density: Float = 1f

    override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
        val carContext = mapboxCarMapSurface.carContext
        val surface = mapboxCarMapSurface.mapSurface
        density = carContext.resources.displayMetrics.density
        val mapboxMap = surface.getMapboxMap()

        val r = MapRenderer(
            context = carContext,
            mapboxMap = mapboxMap,
            camera = surface.camera,
            trip = trip,
            followingPadding = EdgeInsets(80.0 * density, 40.0 * density, 80.0 * density, 40.0 * density),
            overviewPadding = EdgeInsets(60.0 * density, 60.0 * density, 60.0 * density, 60.0 * density),
        )
        renderer = r
        lastInsets?.let { applyPadding(r, it, lastVisibleHeight, density) }

        surface.location.apply {
            setLocationProvider(r.locationProvider)
            locationPuck = LocationPuck2D(bearingImage = ImageHolder.from(R.drawable.ajnav_puck))
            puckBearingEnabled = true
            puckBearing = PuckBearing.COURSE
            enabled = true
        }
        MapboxNavigationApp.registerObserver(r)

        val widget = BitmapWidget(
            CarGraphics.speedBadge(null, null, density),
            WidgetPosition {
                horizontalAlignment = WidgetPosition.Horizontal.RIGHT
                verticalAlignment = WidgetPosition.Vertical.BOTTOM
                offsetX = -16f * density
                offsetY = -16f * density
            },
        )
        surface.addWidget(widget)
        speedWidget = widget

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            trip.state.map { it.speedKmh to it.speedLimitKmh }.distinctUntilChanged().collect { (speed, limit) ->
                widget.updateBitmap(CarGraphics.speedBadge(speed, limit, density))
            }
        }
        // Map theme (N1): Auto follows the car's day/night signal.
        s.launch {
            combine(prefs.mapTheme, display.carDark) { theme, carDark ->
                when (theme) {
                    MapTheme.AUTO -> carDark
                    MapTheme.DARK -> true
                    MapTheme.LIGHT -> false
                }
            }.distinctUntilChanged().collect { dark -> r.applyTheme(dark) }
        }
        // Logo screen (N2): only in free drive; the map returns as soon as a trip starts.
        s.launch {
            combine(display.mapHidden, trip.state.map { it.mode == NavMode.FREE_DRIVE }) { hidden, free -> hidden && free }
                .distinctUntilChanged()
                .collect { showLogo -> setLogoScreen(mapboxCarMapSurface, showLogo) }
        }
    }

    private fun setLogoScreen(carSurface: MapboxCarMapSurface, show: Boolean) {
        val surface = carSurface.mapSurface
        val speed = speedWidget ?: return
        if (show && logoWidget == null) {
            val w = carSurface.surfaceContainer.width
            val h = carSurface.surfaceContainer.height
            if (w <= 0 || h <= 0) return
            val logo = BitmapWidget(
                CarGraphics.logoScreen(carSurface.carContext, w, h, density),
                WidgetPosition {
                    horizontalAlignment = WidgetPosition.Horizontal.LEFT
                    verticalAlignment = WidgetPosition.Vertical.TOP
                },
            )
            surface.addWidget(logo)
            // Re-add the speed badge so it is drawn on top of the logo screen.
            surface.removeWidget(speed)
            surface.addWidget(speed)
            logoWidget = logo
        } else if (!show) {
            logoWidget?.let { surface.removeWidget(it) }
            logoWidget = null
        }
    }

    override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
        scope?.cancel()
        scope = null
        speedWidget?.let { mapboxCarMapSurface.mapSurface.removeWidget(it) }
        speedWidget = null
        logoWidget?.let { mapboxCarMapSurface.mapSurface.removeWidget(it) }
        logoWidget = null
        renderer?.let {
            MapboxNavigationApp.unregisterObserver(it)
            it.destroy()
        }
        renderer = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect, edgeInsets: EdgeInsets) {
        lastInsets = edgeInsets
        lastVisibleHeight = visibleArea.height()
        renderer?.let { applyPadding(it, edgeInsets, visibleArea.height(), density) }
    }

    /** Keep the car marker in the lower part of the free map area, clear of the cards. */
    private fun applyPadding(r: MapRenderer, insets: EdgeInsets, visibleHeight: Int, density: Float) {
        val pad = 24.0 * density
        r.setPadding(
            following = EdgeInsets(
                insets.top + pad,
                insets.left + pad,
                insets.bottom + visibleHeight * 0.3,
                insets.right + pad,
            ),
            overview = EdgeInsets(
                insets.top + pad,
                insets.left + pad,
                insets.bottom + pad,
                insets.right + pad,
            ),
        )
    }
}
