package io.github.lyzio.ajnav.feature.navigation.session

import android.content.Context
import com.mapbox.common.MapboxOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets up Mapbox once for the whole app, whichever screen starts first (phone or car).
 * Each screen then calls MapboxNavigationApp.attach(itsLifecycleOwner).
 */
@Singleton
class MapboxBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trip: TripController,
) {
    private var tripRegistered = false

    fun ensure(token: String) {
        MapboxOptions.accessToken = token
        if (!MapboxNavigationApp.isSetup()) {
            MapboxNavigationApp.setup(NavigationOptions.Builder(context).build())
        }
        if (!tripRegistered) {
            MapboxNavigationApp.registerObserver(trip)
            tripRegistered = true
        }
    }
}
