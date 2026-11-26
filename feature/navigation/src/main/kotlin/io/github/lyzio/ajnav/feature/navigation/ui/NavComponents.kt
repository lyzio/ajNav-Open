@file:Suppress("DEPRECATION") // non-mirrored turn icons are intentional (turn arrows must not mirror in RTL)

package io.github.lyzio.ajnav.feature.navigation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lyzio.ajnav.core.model.FavouriteSlot
import io.github.lyzio.ajnav.core.model.Place
import io.github.lyzio.ajnav.feature.navigation.session.LaneUi
import io.github.lyzio.ajnav.feature.navigation.session.ManeuverUi
import io.github.lyzio.ajnav.feature.navigation.session.ProgressUi
import io.github.lyzio.ajnav.feature.navigation.session.RouteSummary
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors

private val CardShape = RoundedCornerShape(16.dp)

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = AjColors.Card.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, AjColors.Border),
        shadowElevation = 8.dp,
        content = content,
    )
}

// ------------------------------------------------------------------ active navigation

@Composable
internal fun ManeuverCard(maneuver: ManeuverUi, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CardShape,
        color = AjColors.Pill.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, AjColors.Teal.copy(alpha = 0.4f)),
        shadowElevation = 10.dp,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(AjColors.Coral))
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(AjColors.Coral),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = maneuverIcon(maneuver.type, maneuver.modifier),
                        contentDescription = null,
                        tint = AjColors.Darkest,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        maneuver.distanceText,
                        color = AjColors.Ice,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        maneuver.primaryText,
                        color = AjColors.Ice,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (maneuver.lanes.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LaneRow(maneuver.lanes)
                    }
                }
            }
        }
    }
}

@Composable
private fun LaneRow(lanes: List<LaneUi>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        lanes.forEach { lane ->
            val dir = lane.activeDirection ?: lane.directions.firstOrNull()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (lane.isActive) AjColors.Teal else AjColors.Darkest.copy(alpha = 0.6f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    laneArrow(dir),
                    color = if (lane.isActive) AjColors.Darkest else AjColors.Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun SpeedCard(speedKmh: Int?, limitKmh: Int?, modifier: Modifier = Modifier) {
    val over = speedKmh != null && limitKmh != null && speedKmh > limitKmh
    Surface(
        modifier = modifier,
        shape = CardShape,
        color = if (over) AjColors.Coral.copy(alpha = 0.25f) else AjColors.Card.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, if (over) AjColors.Coral else AjColors.Border),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (limitKmh != null) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AjColors.Ice)
                        .border(4.dp, AjColors.Coral, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$limitKmh", color = AjColors.Darkest, fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    speedKmh?.toString() ?: "–",
                    color = AjColors.Ice,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                )
                Text("KM/H", color = AjColors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun EtaStrip(
    progress: ProgressUi?,
    voiceEnabled: Boolean,
    onToggleVoice: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    GlassCard(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("ARRIVAL", color = AjColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    progress?.let { Formatters.clock(context, it.etaEpochMs) } ?: "--:--",
                    color = AjColors.Teal,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Divider()
            Column(Modifier.weight(1f)) {
                Text(
                    progress?.let { Formatters.duration(it.durationRemainingSec) } ?: "",
                    color = AjColors.Ice,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    progress?.let { Formatters.distance(it.distanceRemainingM) } ?: "",
                    color = AjColors.Teal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onToggleVoice) {
                Icon(
                    if (voiceEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (voiceEnabled) "Mute voice" else "Unmute voice",
                    tint = AjColors.Teal,
                )
            }
            Button(
                onClick = onEnd,
                colors = ButtonDefaults.buttonColors(containerColor = AjColors.Coral, contentColor = AjColors.Darkest),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("End", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 14.dp)
            .width(1.dp)
            .height(30.dp)
            .background(AjColors.Border),
    )
}

// ------------------------------------------------------------------ free drive / search

@Composable
internal fun SearchBarButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = AjColors.Teal)
            Spacer(Modifier.width(12.dp))
            Text("Search destination or address", color = AjColors.Muted, fontSize = 16.sp)
        }
    }
}

@Composable
internal fun FavouriteTiles(
    favourites: Map<FavouriteSlot, Place>,
    onPlace: (Place) -> Unit,
    onEmptySlot: (FavouriteSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FavouriteSlot.entries.forEach { slot ->
            val place = favourites[slot]
            GlassCard(
                Modifier
                    .weight(1f)
                    .clickable { if (place != null) onPlace(place) else onEmptySlot(slot) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(AjColors.Pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(slotIcon(slot), contentDescription = null, tint = AjColors.Teal)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(slotLabel(slot), color = AjColors.Ice, fontWeight = FontWeight.Bold)
                        Text(
                            place?.name ?: "Not set",
                            color = AjColors.Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaceRow(
    place: Place,
    icon: ImageVector,
    onClick: () -> Unit,
    onSetFavourite: ((FavouriteSlot) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AjColors.Card)
            .border(1.dp, AjColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(AjColors.Land),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AjColors.Teal)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, color = AjColors.Ice, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.address.isNotBlank()) {
                Text(place.address, color = AjColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onSetFavourite != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = AjColors.Muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    FavouriteSlot.entries.forEach { slot ->
                        DropdownMenuItem(
                            text = { Text("Set as ${slotLabel(slot)}") },
                            leadingIcon = { Icon(slotIcon(slot), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSetFavourite(slot)
                            },
                        )
                    }
                }
            }
        }
    }
}

internal val RecentIcon: ImageVector get() = Icons.Filled.History
internal val ResultIcon: ImageVector get() = Icons.Filled.Place

// ------------------------------------------------------------------ preview / arrival

@Composable
internal fun PreviewCard(
    place: Place,
    summary: RouteSummary?,
    loading: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
    onSetFavourite: (FavouriteSlot) -> Unit,
    modifier: Modifier = Modifier,
    charging: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    GlassCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, color = AjColors.Ice, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (place.address.isNotBlank()) {
                        Text(place.address, color = AjColors.Muted, fontSize = 13.sp)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = AjColors.Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (loading || summary == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = AjColors.Teal, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Calculating route…", color = AjColors.Body)
                }
            } else {
                val eta = System.currentTimeMillis() + (summary.durationSec * 1000).toLong()
                Text(
                    "${Formatters.duration(summary.durationSec)} • ${Formatters.distance(summary.distanceM)} • arrive ${Formatters.clock(context, eta)}",
                    color = AjColors.Teal,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            charging?.let {
                Spacer(Modifier.height(10.dp))
                it()
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStart,
                    enabled = summary != null && !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = AjColors.Coral, contentColor = AjColors.Darkest),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start", fontWeight = FontWeight.Bold)
                }
                FavouriteSlot.entries.forEach { slot ->
                    OutlinedButton(
                        onClick = { onSetFavourite(slot) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AjColors.Border),
                    ) {
                        Icon(slotIcon(slot), contentDescription = "Set as ${slotLabel(slot)}", tint = AjColors.Teal)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArrivedCard(place: Place?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Flag, contentDescription = null, tint = AjColors.Coral)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("You have arrived", color = AjColors.Ice, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                place?.let { Text(it.name, color = AjColors.Muted, fontSize = 13.sp) }
            }
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = AjColors.Teal, contentColor = AjColors.Darkest),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Done", fontWeight = FontWeight.Bold) }
        }
    }
}

// ------------------------------------------------------------------ mapping helpers

internal fun slotLabel(slot: FavouriteSlot) = when (slot) {
    FavouriteSlot.HOME -> "Home"
    FavouriteSlot.WORK -> "Work"
}

internal fun slotIcon(slot: FavouriteSlot): ImageVector = when (slot) {
    FavouriteSlot.HOME -> Icons.Filled.Home
    FavouriteSlot.WORK -> Icons.Filled.Business
}

private fun maneuverIcon(type: String?, modifier: String?): ImageVector {
    when (type) {
        "arrive" -> return Icons.Filled.Flag
        "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> return Icons.Filled.RoundaboutRight
        "merge" -> return Icons.Filled.Merge
        "fork" -> return if (modifier?.contains("left") == true) Icons.Filled.ForkLeft else Icons.Filled.ForkRight
    }
    return when (modifier) {
        "uturn" -> Icons.Filled.UTurnLeft
        "sharp right" -> Icons.Filled.TurnSharpRight
        "right" -> Icons.Filled.TurnRight
        "slight right" -> Icons.Filled.TurnSlightRight
        "sharp left" -> Icons.Filled.TurnSharpLeft
        "left" -> Icons.Filled.TurnLeft
        "slight left" -> Icons.Filled.TurnSlightLeft
        else -> Icons.Filled.Straight
    }
}

private fun laneArrow(direction: String?): String = when (direction) {
    "left" -> "↰"
    "sharp left" -> "↙"
    "slight left" -> "↖"
    "right" -> "↱"
    "sharp right" -> "↘"
    "slight right" -> "↗"
    "uturn" -> "↶"
    else -> "↑"
}
