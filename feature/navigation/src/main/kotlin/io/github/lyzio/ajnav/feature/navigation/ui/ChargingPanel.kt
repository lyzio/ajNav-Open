package io.github.lyzio.ajnav.feature.navigation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.PriceSource
import io.github.lyzio.ajnav.core.model.RouteStopPlan
import io.github.lyzio.ajnav.feature.charging.planner.PlannedStop
import io.github.lyzio.ajnav.feature.navigation.session.ChargingUi
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors
import java.util.Locale
import kotlin.math.roundToInt

private val Amber = Color(0xFFF2B134)

/** Battery % input and the charging plan inside the phone's route preview (v0.3). */
@Composable
internal fun ChargingPanel(
    charging: ChargingUi,
    addedStops: List<ChargerStation>,
    routeLoading: Boolean,
    onBattery: (Int) -> Unit,
    onAdd: (ChargerStation) -> Unit,
    onRemove: (ChargerStation) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BatteryRow(charging.batteryPercent, onBattery)

        val plan = charging.plan
        when {
            charging.batteryPercent == null -> Text(
                "Set your battery level to plan charging stops.",
                color = AjColors.Muted,
                fontSize = 13.sp,
            )
            charging.loading || routeLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = AjColors.Teal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Finding chargers…", color = AjColors.Body, fontSize = 13.sp)
            }
            plan != null -> {
                val min = charging.arriveMinPercent
                val suggestions = plan.suggestions
                when {
                    plan.stops.isEmpty() && !plan.unreachable -> Text(
                        "No charging stop needed · arrive with ${socText(plan.arrivalSocPercent)}",
                        color = AjColors.Teal,
                        fontWeight = FontWeight.SemiBold,
                    )
                    suggestions.isEmpty() -> Text(
                        "Arrive with ${socText(plan.arrivalSocPercent)}",
                        color = if (plan.arrivalSocPercent >= min) AjColors.Teal else AjColors.Coral,
                        fontWeight = FontWeight.SemiBold,
                    )
                    else -> Column {
                        Text(
                            "Without more stops you'd arrive with ${socText(plan.arrivalWithAddedOnlyPercent)}.",
                            color = AjColors.Coral,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            "With the suggested ${if (suggestions.size == 1) "stop" else "stops"}: arrive with ${socText(plan.arrivalSocPercent)}",
                            color = AjColors.Body,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (plan.stops.isNotEmpty()) {
                    Column(
                        Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        plan.stops.forEach { stop ->
                            StopCard(
                                stop = stop,
                                isAdded = stop.isAdded || addedStops.any { it.id == stop.plan.station.id },
                                onAdd = onAdd,
                                onRemove = onRemove,
                            )
                        }
                    }
                }
                if (plan.unreachable) {
                    Text(
                        "No charger in reach for part of this trip. Charge before you leave, or check your operator filter.",
                        color = AjColors.Coral,
                        fontSize = 13.sp,
                    )
                }
                if (plan.anyColdAdjusted) {
                    val temp = charging.outsideTempC?.let { " (${it.roundToInt()} °C)" }.orEmpty()
                    Text(
                        "Cold weather$temp: charging times are estimated longer.",
                        color = AjColors.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        charging.notice?.let { Text(it, color = AjColors.Coral, fontSize = 12.sp) }
    }
}

@Composable
private fun BatteryRow(percent: Int?, onBattery: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf((percent ?: 50).toFloat()) }
    var touched by remember(percent) { mutableStateOf(percent != null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = AjColors.Teal, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Battery now", color = AjColors.Body, fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                value = (value - 1f).coerceAtLeast(0f)
                touched = true
                onBattery(value.roundToInt())
            }) { Icon(Icons.Filled.Remove, contentDescription = "Less", tint = AjColors.Muted) }
            Text(
                if (touched) "${value.roundToInt()} %" else "– %",
                color = AjColors.Ice,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            IconButton(onClick = {
                value = (value + 1f).coerceAtMost(100f)
                touched = true
                onBattery(value.roundToInt())
            }) { Icon(Icons.Filled.Add, contentDescription = "More", tint = AjColors.Muted) }
        }
        Slider(
            value = value,
            onValueChange = {
                value = it
                touched = true
            },
            onValueChangeFinished = { onBattery(value.roundToInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = AjColors.Coral,
                activeTrackColor = AjColors.Teal,
                inactiveTrackColor = AjColors.Border,
            ),
        )
    }
}

@Composable
private fun StopCard(
    stop: PlannedStop,
    isAdded: Boolean,
    onAdd: (ChargerStation) -> Unit,
    onRemove: (ChargerStation) -> Unit,
) {
    var showOthers by remember { mutableStateOf(false) }
    val p = stop.plan
    Surface(
        color = AjColors.Land,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isAdded) AjColors.Teal else AjColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EvStation, contentDescription = null, tint = if (isAdded) AjColors.Teal else Amber)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.station.name, color = AjColors.Ice, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stationLine(p), color = AjColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isAdded) {
                    OutlinedButton(
                        onClick = { onRemove(p.station) },
                        border = BorderStroke(1.dp, AjColors.Border),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Remove", color = AjColors.Body) }
                } else {
                    Button(
                        onClick = { onAdd(p.station) },
                        colors = ButtonDefaults.buttonColors(containerColor = AjColors.Teal, contentColor = AjColors.Darkest),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Add", fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(chargeLine(p), color = AjColors.Body, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(priceLine(p), color = AjColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (p.isColdgateAdjusted) {
                    Text("estimated", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!isAdded && stop.alternatives.isNotEmpty()) {
                TextButton(onClick = { showOthers = !showOthers }) {
                    Text(
                        if (showOthers) "Hide other chargers" else "Other chargers (${stop.alternatives.size})",
                        color = AjColors.Teal,
                        fontSize = 13.sp,
                    )
                }
                if (showOthers) {
                    stop.alternatives.forEach { alt -> AlternativeRow(alt, onAdd) }
                }
            }
        }
    }
}

@Composable
private fun AlternativeRow(p: RouteStopPlan, onAdd: (ChargerStation) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.station.name, color = AjColors.Body, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${stationLine(p)} · ${chargeLine(p)}", color = AjColors.Muted, fontSize = 11.sp, maxLines = 2)
        }
        TextButton(onClick = { onAdd(p.station) }) { Text("Add", color = AjColors.Teal, fontWeight = FontWeight.Bold) }
    }
}

internal fun socText(percent: Double): String = if (percent < 0) "below 0 %" else "${percent.roundToInt()} %"

internal fun stationLine(p: RouteStopPlan): String {
    val km = (p.distanceFromStartM / 1000).roundToInt()
    val kw = p.station.maxPowerKw.roundToInt()
    return listOf(p.station.operatorName, "$km km", "$kw kW").joinToString(" · ")
}

internal fun chargeLine(p: RouteStopPlan): String {
    if (p.neededKwh <= 0f) return "${p.arrivalSocPercent.roundToInt()} % on arrival · no charge needed"
    val cost = p.estimatedCost.roundToInt()
    return "${p.arrivalSocPercent.roundToInt()} % → ${p.targetSocPercent.roundToInt()} % · ${p.estimatedChargeMinutes} min · ~$cost kr"
}

internal fun priceLine(p: RouteStopPlan): String {
    val source = when (p.priceSource) {
        PriceSource.SUBSCRIPTION -> "your subscription"
        PriceSource.OCM -> "Open Charge Map"
        PriceSource.OPERATOR_DEFAULT -> "your usual price"
        PriceSource.GLOBAL_DEFAULT -> "default price"
    }
    return String.format(Locale.getDefault(), "%.2f kr/kWh · %s", p.pricePerKwh, source)
}
