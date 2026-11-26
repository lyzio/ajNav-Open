package io.github.lyzio.ajnav.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import dagger.hilt.android.AndroidEntryPoint
import io.github.lyzio.ajnav.core.security.ApiKeyRepository
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import io.github.lyzio.ajnav.feature.navigation.geocoding.GeocodingProvider
import io.github.lyzio.ajnav.feature.navigation.session.MapboxBootstrap
import io.github.lyzio.ajnav.feature.navigation.session.TripController
import javax.inject.Inject

/** Entry point Android Auto connects to (design §4.7 C, "EvCarAppService"). */
@AndroidEntryPoint
class AjNavCarAppService : CarAppService() {

    @Inject lateinit var trip: TripController
    @Inject lateinit var geocoding: GeocodingProvider
    @Inject lateinit var prefs: NavPreferencesRepository
    @Inject lateinit var apiKeys: ApiKeyRepository
    @Inject lateinit var bootstrap: MapboxBootstrap

    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            // Test builds: allow Android Auto and Google's desktop head unit.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session =
        AjNavCarSession(CarDeps(trip, geocoding, prefs), apiKeys, bootstrap)
}
