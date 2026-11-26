package io.github.lyzio.ajnav.car

import android.text.SpannableString
import android.text.Spanned
import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lyzio.ajnav.feature.navigation.session.NavMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Route preview (RoutePreviewNavigationTemplate): time, distance, a charging summary and a
 * Start button. "Charging" opens the battery % and the suggested charging stops (v0.3).
 */
internal class CarRoutePreviewScreen(
    carContext: CarContext,
    private val deps: CarDeps,
) : Screen(carContext) {

    private val trip = deps.trip

    init {
        carContext.onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    trip.clearPreview()
                    finish()
                }
            },
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                trip.state.map { listOf(it.mode, it.preview, it.destination, it.charging, it.chargeStops) }
                    .distinctUntilChanged().collect { key ->
                    val mode = key[0] as NavMode
                    // Started, cancelled or failed (on either screen): leave the preview.
                    if (mode == NavMode.ACTIVE || mode == NavMode.FREE_DRIVE) finish() else invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = trip.state.value
        val name = state.destination?.name ?: "Destination"
        val builder = RoutePreviewNavigationTemplate.Builder()
            .setTitle(name)
            .setHeaderAction(Action.BACK)

        val summary = state.preview
        if (state.mode != NavMode.PREVIEW || summary == null) {
            return builder.setLoading(true).build()
        }

        // Android Auto formats these spans itself (e.g. "12 min · 8.4 km").
        val info = SpannableString("  ·  ")
        info.setSpan(DurationSpan.create(summary.durationSec.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        info.setSpan(DistanceSpan.create(CarMappers.distance(summary.distanceM)), 4, 5, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val row = Row.Builder()
            .setTitle(info)
            .apply { state.destination?.address?.takeIf { it.isNotBlank() }?.let { addText(it) } }
            .addText(CarChargingText.summary(state))
            .build()

        return builder
            .setItemList(
                ItemList.Builder()
                    .addItem(row)
                    .setSelectedIndex(0)
                    .setOnSelectedListener { }
                    .build(),
            )
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Charging")
                            .setOnClickListener { screenManager.push(CarChargingScreen(carContext, deps)) }
                            .build(),
                    )
                    .build(),
            )
            .setNavigateAction(
                Action.Builder()
                    .setTitle("Start")
                    .setOnClickListener { trip.startNavigation() }
                    .build(),
            )
            .build()
    }
}
