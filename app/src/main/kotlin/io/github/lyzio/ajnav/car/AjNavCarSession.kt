package io.github.lyzio.ajnav.car

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mapbox.maps.ContextMode
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.extension.androidauto.MapboxCarMap
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.Place
import io.github.lyzio.ajnav.core.security.ApiKey
import io.github.lyzio.ajnav.core.security.ApiKeyRepository
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import io.github.lyzio.ajnav.feature.navigation.geocoding.GeocodingProvider
import io.github.lyzio.ajnav.feature.navigation.session.MapboxBootstrap
import io.github.lyzio.ajnav.feature.navigation.session.TripController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Car-screen-only state shared by the screens and the map (v0.3). */
internal class CarDisplayState {
    /** N2: the driver chose "Hide map" (only honoured in free drive). */
    val mapHidden = MutableStateFlow(false)
    /** The car's day/night signal (Auto map theme). */
    val carDark = MutableStateFlow(true)
}

/** What the car screens need from the app. */
internal class CarDeps(
    val trip: TripController,
    val geocoding: GeocodingProvider,
    val prefs: NavPreferencesRepository,
    val display: CarDisplayState = CarDisplayState(),
)

/**
 * One Android Auto connection. It uses the same Mapbox navigation and the same trip as the
 * phone (MapboxNavigationApp is shared), which keeps phone and car in sync.
 */
internal class AjNavCarSession(
    private val deps: CarDeps,
    private val apiKeys: ApiKeyRepository,
    private val bootstrap: MapboxBootstrap,
) : Session() {

    private val carMap = MapboxCarMap()
    private var mapReady = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                carMap.clearObservers()
                if (MapboxNavigationApp.isSetup()) MapboxNavigationApp.detach(this@AjNavCarSession)
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val screen = startScreen()
        if (screen is CarNavigationScreen) handleNavigateIntent(intent)
        return screen
    }

    override fun onNewIntent(intent: Intent) {
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        if (screenManager.top is CarMessageScreen) {
            screenManager.push(startScreen())
        }
        handleNavigateIntent(intent)
    }

    private fun startScreen(): Screen {
        // The token is read once from encrypted storage; it is entered on the phone only.
        val token = runBlocking { apiKeys.get(ApiKey.MAPBOX_PUBLIC_TOKEN) }
            ?: return CarMessageScreen(carContext, "Set up ajNav on your phone: enter your Mapbox token in Settings.") {
                retry()
            }
        if (!deps.trip.hasLocationPermission()) {
            return CarMessageScreen(carContext, "Allow location access for ajNav on your phone, then try again.") {
                retry()
            }
        }
        if (!mapReady) {
            bootstrap.ensure(token)
            MapboxNavigationApp.attach(this)
            deps.trip.startTripSessionIfPermitted()
            carMap.setup(carContext, mapInitOptions(carContext))
            carMap.setGestureHandler(null) // the camera always follows the car in v0.2
            deps.display.carDark.value = carContext.isDarkMode
            carMap.registerObserver(CarMapController(deps.trip, deps.prefs, deps.display))
            mapReady = true
        }
        return CarNavigationScreen(carContext, deps)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        // Day/night switch in the car: the Auto map theme follows it.
        deps.display.carDark.value = carContext.isDarkMode
    }

    private fun retry() {
        val next = startScreen()
        if (next !is CarMessageScreen) {
            val screenManager = carContext.getCarService(ScreenManager::class.java)
            screenManager.push(next)
        }
    }

    private fun mapInitOptions(carContext: CarContext): MapInitOptions {
        val defaults = MapInitOptions.getDefaultMapOptions(carContext)
        // Android Auto requires a shared GL context for the car map surface.
        return MapInitOptions(
            context = carContext,
            mapOptions = defaults.toBuilder().contextMode(ContextMode.SHARED).build(),
        )
    }

    /**
     * "Hey Google, navigate to … with ajNav" arrives as a geo: link,
     * either with coordinates ("geo:59.33,18.06") or a search text ("geo:0,0?q=Kista").
     */
    private fun handleNavigateIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val uri = intent.data ?: return
        val coords = parseCoordinates(uri)
        val query = uri.getQueryParameter("q")?.substringBefore('(')?.trim()
        if (coords != null && (coords.latitude != 0.0 || coords.longitude != 0.0)) {
            deps.trip.previewRoute(Place(name = query?.takeIf { it.isNotBlank() } ?: "Destination", address = "", point = coords))
            return
        }
        if (query.isNullOrBlank()) return
        lifecycleScope.launch {
            val place = runCatching { deps.geocoding.search(query, deps.trip.state.value.location) }
                .getOrNull()?.firstOrNull()
            if (place != null) deps.trip.previewRoute(place)
        }
    }

    private fun parseCoordinates(uri: Uri): GeoPoint? {
        val part = uri.schemeSpecificPart?.substringBefore('?') ?: return null
        val nums = part.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        return if (nums.size >= 2) GeoPoint(nums[0], nums[1]) else null
    }
}
