package io.github.lyzio.ajnav.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lyzio.ajnav.feature.navigation.session.NavMode
import io.github.lyzio.ajnav.feature.navigation.session.NavUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Main Android Auto screen (NavigationTemplate): map, next turn with lanes, arrival time and
 * distance left. In free drive it offers Search and Places. Mirrors the shared trip, so a
 * route started on the phone shows here too.
 */
internal class CarNavigationScreen(
    carContext: CarContext,
    private val deps: CarDeps,
) : Screen(carContext) {

    private val trip = deps.trip
    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private var guiding = false

    init {
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                trip.stopNavigation()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Refresh only when something visible changes (Android Auto limits refreshes).
                    trip.state.map { it.templateKey() }.distinctUntilChanged().collect {
                        syncGuidance(trip.state.value)
                        showPreviewIfNeeded(trip.state.value)
                        invalidate()
                    }
                }
                launch {
                    trip.state.map { it.message }.distinctUntilChanged().filterNotNull().collect { msg ->
                        CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
                    }
                }
                launch { trip.voiceEnabled.collect { invalidate() } }
            }
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (guiding) navigationManager.navigationEnded()
                guiding = false
                navigationManager.clearNavigationManagerCallback()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = trip.state.value
        val builder = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.createCustom(BG, BG))

        if (state.mode == NavMode.ACTIVE) {
            when {
                state.arrived -> builder.setNavigationInfo(
                    MessageInfo.Builder("You have arrived")
                        .apply { state.destination?.let { setText(it.name) } }
                        .build(),
                )
                state.maneuver != null -> builder.setNavigationInfo(CarMappers.routingInfo(state.maneuver!!))
                else -> builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            }
            if (!state.arrived) {
                state.progress?.let { builder.setDestinationTravelEstimate(CarMappers.travelEstimate(it)) }
            }
            val voiceOn = trip.voiceEnabled.value
            builder.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(if (voiceOn) "Mute" else "Unmute")
                            .setOnClickListener { trip.setVoiceEnabled(!voiceOn) }
                            .build(),
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle(if (state.arrived) "Done" else "End")
                            .setOnClickListener { trip.stopNavigation() }
                            .build(),
                    )
                    .build(),
            )
        } else {
            builder.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Search")
                            .setOnClickListener { screenManager.push(CarSearchScreen(carContext, deps)) }
                            .build(),
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Places")
                            .setOnClickListener { screenManager.push(CarPlacesScreen(carContext, deps)) }
                            .build(),
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    /** Tell Android Auto when turn-by-turn starts and stops (needed for the car's own displays). */
    private fun syncGuidance(state: NavUiState) {
        val active = state.mode == NavMode.ACTIVE
        if (active && !guiding) {
            navigationManager.navigationStarted()
            guiding = true
        } else if (!active && guiding) {
            navigationManager.navigationEnded()
            guiding = false
        }
    }

    /** A route being planned (from the car or the phone) opens the preview screen. */
    private fun showPreviewIfNeeded(state: NavUiState) {
        val planning = state.mode == NavMode.ROUTING || state.mode == NavMode.PREVIEW
        if (planning && screenManager.top === this) {
            screenManager.push(CarRoutePreviewScreen(carContext, deps))
        }
    }

    private fun NavUiState.templateKey(): List<Any?> = listOf(
        mode,
        arrived,
        maneuver?.primaryText,
        maneuver?.distanceText,
        maneuver?.lanes,
        progress?.let { (it.distanceRemainingM / 100).toInt() },
        progress?.let { it.etaEpochMs / 60_000 },
    )

    private companion object {
        val BG = android.graphics.Color.parseColor("#0E1418")
    }
}
