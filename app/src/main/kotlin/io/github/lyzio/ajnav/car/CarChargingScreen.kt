package io.github.lyzio.ajnav.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.RouteStopPlan
import io.github.lyzio.ajnav.feature.charging.ChargerAhead
import io.github.lyzio.ajnav.feature.navigation.session.NavMode
import io.github.lyzio.ajnav.feature.navigation.session.NavUiState
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Charging on the car screen (ListTemplate, at most 6 rows):
 * - Route preview: battery % (tap to change), the plan summary and the suggested stops ("Add").
 * - While driving ("Chargers" button): chargers further along the route; tap one to add it.
 */
internal class CarChargingScreen(
    carContext: CarContext,
    private val deps: CarDeps,
) : Screen(carContext) {

    private val trip = deps.trip
    private var ahead: List<ChargerAhead>? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                trip.state.map { listOf(it.mode, it.charging, it.chargeStops) }.distinctUntilChanged().collect {
                    val mode = trip.state.value.mode
                    if (mode == NavMode.FREE_DRIVE) {
                        finish()
                        return@collect
                    }
                    if (mode == NavMode.ACTIVE) ahead = trip.chargersAhead(MAX_ROWS)
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = trip.state.value
        val active = state.mode == NavMode.ACTIVE
        val builder = ListTemplate.Builder()
            .setTitle(if (active) "Chargers ahead" else "Charging")
            .setHeaderAction(Action.BACK)
        return if (active) activeList(builder) else previewList(builder, state)
    }

    private fun activeList(builder: ListTemplate.Builder): Template {
        val list = ahead ?: return builder.setLoading(true).build()
        val items = ItemList.Builder().setNoItemsMessage("No chargers found along the rest of the route.")
        list.take(MAX_ROWS).forEach { c ->
            val km = c.distanceAheadM / 1000
            val dist = if (km < 10) String.format(Locale.getDefault(), "%.1f km", km) else "${km.roundToInt()} km"
            items.addItem(
                Row.Builder()
                    .setTitle(c.station.name)
                    .addText("$dist ahead · ${c.station.maxPowerKw.roundToInt()} kW · ${c.station.operatorName}")
                    .addText(String.format(Locale.getDefault(), "%.2f kr/kWh · tap to add as a stop", c.price.perKwh))
                    .setOnClickListener { add(c.station) }
                    .build(),
            )
        }
        return builder.setSingleList(items.build()).build()
    }

    private fun previewList(builder: ListTemplate.Builder, state: NavUiState): Template {
        val charging = state.charging
        if (state.mode == NavMode.ROUTING || charging.loading) return builder.setLoading(true).build()
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle("Battery now: ${charging.batteryPercent?.let { "$it %" } ?: "not set"}")
                .addText("Tap to change")
                .setOnClickListener { screenManager.push(CarBatteryScreen(carContext, deps)) }
                .build(),
        )
        val plan = charging.plan
        if (charging.batteryPercent != null && plan != null) {
            items.addItem(Row.Builder().setTitle(CarChargingText.summary(state)).build())
            var rows = 2
            for (stop in plan.stops) {
                if (rows >= MAX_ROWS) break
                items.addItem(stopRow(stop.plan, added = stop.isAdded || state.chargeStops.any { it.id == stop.plan.station.id }))
                rows++
            }
            // Fill the remaining rows with alternatives for the first suggested stop.
            plan.suggestions.firstOrNull()?.alternatives?.forEach { alt ->
                if (rows >= MAX_ROWS) return@forEach
                items.addItem(stopRow(alt, added = false, prefix = "Other: "))
                rows++
            }
        }
        charging.notice?.let { notice ->
            if (plan == null || plan.stops.isEmpty()) items.addItem(Row.Builder().setTitle(notice).build())
        }
        return builder.setSingleList(items.build()).build()
    }

    private fun stopRow(p: RouteStopPlan, added: Boolean, prefix: String = ""): Row {
        val km = (p.distanceFromStartM / 1000).roundToInt()
        val row = Row.Builder()
            .setTitle((if (added) "✓ " else prefix) + p.station.name)
            .addText("$km km · ${p.station.maxPowerKw.roundToInt()} kW · ${p.station.operatorName}")
            .addText(CarChargingText.chargeLine(p) + if (added) " · added" else " · tap to add")
        if (!added) row.setOnClickListener { add(p.station) }
        return row.build()
    }

    private fun add(station: ChargerStation) {
        trip.addChargeStop(station)
        CarToast.makeText(carContext, "Adding stop: ${station.name}", CarToast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val MAX_ROWS = 6
    }
}

/** Battery % picker for the car (tap a value). The phone has a finer slider. */
internal class CarBatteryScreen(
    carContext: CarContext,
    private val deps: CarDeps,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val current = deps.trip.state.value.charging.batteryPercent
        val items = ItemList.Builder()
        VALUES.forEach { v ->
            items.addItem(
                Row.Builder()
                    .setTitle(if (current != null && v == nearest(current)) "$v %  ✓" else "$v %")
                    .setOnClickListener {
                        deps.trip.setBatteryPercent(v)
                        finish()
                    }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Battery now")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun nearest(p: Int) = VALUES.minByOrNull { kotlin.math.abs(it - p) }

    private companion object {
        val VALUES = listOf(100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 5)
    }
}

/** Short charging texts shared by the car screens. */
internal object CarChargingText {
    fun summary(state: NavUiState): String {
        val c = state.charging
        val plan = c.plan
        return when {
            c.batteryPercent == null -> "Set battery % under Charging"
            c.loading || state.mode == NavMode.ROUTING -> "Finding chargers…"
            plan == null -> c.notice ?: "Charging plan unavailable"
            plan.stops.isEmpty() && !plan.unreachable -> "No charging stop needed · arrive ${soc(plan.arrivalSocPercent)}"
            plan.suggestions.isNotEmpty() -> {
                val n = plan.suggestions.size
                "$n charging ${if (n == 1) "stop" else "stops"} suggested · without: ${soc(plan.arrivalWithAddedOnlyPercent)}"
            }
            plan.unreachable -> "No charger in reach for part of the trip"
            else -> "Arrive with ${soc(plan.arrivalSocPercent)}"
        }
    }

    fun chargeLine(p: RouteStopPlan): String {
        if (p.neededKwh <= 0f) return "${p.arrivalSocPercent.roundToInt()} % on arrival"
        val est = if (p.isColdgateAdjusted) " (estimated)" else ""
        return "${p.arrivalSocPercent.roundToInt()}→${p.targetSocPercent.roundToInt()} % · ${p.estimatedChargeMinutes} min$est · ~${p.estimatedCost.roundToInt()} kr"
    }

    private fun soc(v: Double) = if (v < 0) "below 0 %" else "${v.roundToInt()} %"
}
