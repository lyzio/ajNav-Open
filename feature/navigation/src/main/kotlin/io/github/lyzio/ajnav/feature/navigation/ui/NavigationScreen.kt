package io.github.lyzio.ajnav.feature.navigation.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.github.lyzio.ajnav.core.model.FavouriteSlot
import io.github.lyzio.ajnav.core.model.Place
import io.github.lyzio.ajnav.feature.navigation.R
import io.github.lyzio.ajnav.feature.navigation.data.MapTheme
import io.github.lyzio.ajnav.feature.navigation.session.NavMode
import io.github.lyzio.ajnav.feature.navigation.session.MapRenderer
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.plugin.animation.camera
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors

/**
 * Phone navigation screen (v0.1): map, search, favourites, recents, route preview and
 * turn-by-turn guidance. Requires Mapbox to be set up with a token before it is shown.
 *
 * @param onActiveNavigationChanged true while turn-by-turn is running (app hides its tab bar).
 */
@Composable
fun NavigationScreen(
    onActiveNavigationChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NavigationViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val trip = viewModel.trip

    // --- Map + renderer for the shared trip (kept for the lifetime of this screen) ---
    val mapView = remember { MapView(context) }
    val renderer = remember {
        val d = context.resources.displayMetrics.density
        MapRenderer(
            context = context,
            mapboxMap = mapView.mapboxMap,
            camera = mapView.camera,
            trip = trip,
            followingPadding = EdgeInsets(200.0 * d, 40.0 * d, 140.0 * d, 40.0 * d),
            overviewPadding = EdgeInsets(140.0 * d, 40.0 * d, 260.0 * d, 40.0 * d),
        )
    }
    DisposableEffect(renderer) {
        mapView.scalebar.enabled = false
        mapView.location.apply {
            setLocationProvider(renderer.locationProvider)
            locationPuck = LocationPuck2D(bearingImage = ImageHolder.from(R.drawable.ajnav_puck))
            puckBearingEnabled = true
            puckBearing = PuckBearing.COURSE
            enabled = true
        }
        MapboxNavigationApp.registerObserver(renderer)
        onDispose {
            MapboxNavigationApp.unregisterObserver(renderer)
            renderer.destroy()
        }
    }

    // Map theme (N1): Auto follows the phone's dark mode.
    val theme by viewModel.mapTheme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkMap = when (theme) {
        MapTheme.AUTO -> systemDark
        MapTheme.DARK -> true
        MapTheme.LIGHT -> false
    }
    LaunchedEffect(darkMap) { renderer.applyTheme(darkMap) }

    val nav by trip.state.collectAsStateWithLifecycle()
    val cameraFollowing by renderer.cameraFollowing.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val voiceOn by trip.voiceEnabled.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()

    var searching by rememberSaveable { mutableStateOf(false) }
    val destination = nav.destination
    var pendingSlot by remember { mutableStateOf<FavouriteSlot?>(null) } // "set Home" flow

    // --- Location permission ---
    var hasLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocation) trip.startTripSessionIfPermitted()
    }
    LaunchedEffect(Unit) {
        if (!hasLocation) {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    LaunchedEffect(nav.location) { viewModel.searchBias = nav.location }

    val active = nav.mode == NavMode.ACTIVE
    LaunchedEffect(active) {
        onActiveNavigationChanged(active)
        val window = (context as? Activity)?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun choose(place: Place) {
        val slot = pendingSlot
        if (slot != null) {
            viewModel.setFavourite(slot, place)
            pendingSlot = null
            searching = false
            viewModel.clearQuery()
            return
        }
        searching = false
        viewModel.clearQuery()
        trip.previewRoute(place)
    }

    BackHandler(enabled = searching) {
        searching = false
        pendingSlot = null
    }
    BackHandler(enabled = !searching && destination != null && !active) {
        trip.clearPreview()
    }

    Box(modifier.fillMaxSize().background(AjColors.Bg)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // ---------------- top area
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
        ) {
            when {
                active && nav.maneuver != null -> Row(verticalAlignment = Alignment.Top) {
                    ManeuverCard(nav.maneuver!!, Modifier.weight(1f).widthIn(max = 420.dp))
                    Spacer(Modifier.size(8.dp))
                    SpeedCard(nav.speedKmh, nav.speedLimitKmh)
                }
                active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SpeedCard(nav.speedKmh, nav.speedLimitKmh)
                }
                destination == null -> SearchBarButton(onClick = { searching = true })
            }
            if (!hasLocation) {
                Spacer(Modifier.height(8.dp))
                GlassCard {
                    Text(
                        "Location permission is needed for navigation. Enable it in Android settings.",
                        color = AjColors.Coral,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            nav.message?.let { msg ->
                Spacer(Modifier.height(8.dp))
                GlassCard {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = AjColors.Body, modifier = Modifier.weight(1f))
                        IconButton(onClick = trip::consumeMessage) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = AjColors.Muted)
                        }
                    }
                }
            }
        }

        // ---------------- bottom area
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (active) Modifier.navigationBarsPadding() else Modifier)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!cameraFollowing) {
                SmallFloatingActionButton(
                    onClick = renderer::recenter,
                    containerColor = AjColors.Card,
                    contentColor = AjColors.Teal,
                    modifier = Modifier.align(Alignment.End),
                ) { Icon(Icons.Filled.MyLocation, contentDescription = "Re-centre") }
            }
            when {
                active && nav.arrived -> ArrivedCard(
                    place = destination,
                    onDone = trip::stopNavigation,
                )
                active -> EtaStrip(
                    progress = nav.progress,
                    voiceEnabled = voiceOn,
                    onToggleVoice = { trip.setVoiceEnabled(!voiceOn) },
                    onEnd = trip::stopNavigation,
                )
                destination != null -> PreviewCard(
                    place = destination,
                    summary = nav.preview,
                    loading = nav.mode == NavMode.ROUTING,
                    onStart = trip::startNavigation,
                    onClose = trip::clearPreview,
                    onSetFavourite = { slot -> viewModel.setFavourite(slot, destination) },
                    charging = {
                        ChargingPanel(
                            charging = nav.charging,
                            addedStops = nav.chargeStops,
                            routeLoading = nav.mode == NavMode.ROUTING,
                            onBattery = trip::setBatteryPercent,
                            onAdd = trip::addChargeStop,
                            onRemove = trip::removeChargeStop,
                        )
                    },
                )
                else -> {
                    SpeedCard(nav.speedKmh, nav.speedLimitKmh)
                    FavouriteTiles(
                        favourites = favourites,
                        onPlace = ::choose,
                        onEmptySlot = { slot ->
                            pendingSlot = slot
                            searching = true
                        },
                    )
                }
            }
        }

        // ---------------- full-screen search
        if (searching) {
            SearchPanel(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                state = search,
                title = pendingSlot?.let { "Set ${slotLabel(it)}" },
                favourites = favourites,
                recents = recents,
                onPlace = ::choose,
                onSetFavourite = { slot, place -> viewModel.setFavourite(slot, place) },
                onClose = {
                    searching = false
                    pendingSlot = null
                },
            )
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    state: SearchState,
    title: String?,
    favourites: Map<FavouriteSlot, Place>,
    recents: List<Place>,
    onPlace: (Place) -> Unit,
    onSetFavourite: (FavouriteSlot, Place) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(AjColors.Bg.copy(alpha = 0.97f))
            .statusBarsPadding()
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AjColors.Ice)
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(title ?: "Search destination or address") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AjColors.Teal) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = AjColors.Muted)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQueryChange(query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AjColors.Card,
                    unfocusedContainerColor = AjColors.Card,
                    focusedBorderColor = AjColors.Teal,
                    unfocusedBorderColor = AjColors.Border,
                    cursorColor = AjColors.Teal,
                    focusedTextColor = AjColors.Ice,
                    unfocusedTextColor = AjColors.Ice,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                SearchState.Idle -> {
                    if (title == null && favourites.isNotEmpty()) {
                        item { SectionLabel("FAVOURITES") }
                        FavouriteSlot.entries.forEach { slot ->
                            favourites[slot]?.let { place ->
                                item(key = "fav_${slot.name}") {
                                    PlaceRow(place.copy(name = "${slotLabel(slot)} · ${place.name}"), slotIcon(slot), { onPlace(place) })
                                }
                            }
                        }
                    }
                    if (recents.isNotEmpty()) {
                        item { SectionLabel("RECENT DESTINATIONS") }
                        items(recents, key = { "recent_${it.name}_${it.point.latitude}_${it.point.longitude}" }) { place ->
                            PlaceRow(place, RecentIcon, { onPlace(place) }, onSetFavourite = { slot -> onSetFavourite(slot, place) })
                        }
                    }
                }
                SearchState.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AjColors.Teal)
                    }
                }
                is SearchState.Error -> item {
                    Text(state.message, color = AjColors.Coral, modifier = Modifier.padding(12.dp))
                }
                is SearchState.Results -> {
                    if (state.places.isEmpty()) {
                        item { Text("No results", color = AjColors.Muted, modifier = Modifier.padding(12.dp)) }
                    }
                    items(state.places, key = { "res_${it.name}_${it.point.latitude}_${it.point.longitude}" }) { place ->
                        PlaceRow(
                            place,
                            ResultIcon,
                            { onPlace(place) },
                            onSetFavourite = if (title == null) { slot -> onSetFavourite(slot, place) } else null,
                        )
                    }
                }
            }
            item {
                Text(
                    "Search by Photon · © OpenStreetMap contributors",
                    color = AjColors.Muted.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = AjColors.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}
