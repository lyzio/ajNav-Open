package io.github.lyzio.ajnav.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lyzio.ajnav.BuildConfig
import io.github.lyzio.ajnav.feature.navigation.data.MapTheme
import io.github.lyzio.ajnav.feature.navigation.data.VoiceEngine
import io.github.lyzio.ajnav.feature.navigation.data.VoiceLanguage
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors
import io.github.lyzio.ajnav.ui.ChoiceRow
import io.github.lyzio.ajnav.ui.LabeledRow
import io.github.lyzio.ajnav.ui.NumberField
import io.github.lyzio.ajnav.ui.Section
import io.github.lyzio.ajnav.ui.fieldColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    mapboxAlreadyStarted: Boolean,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val savedToken by viewModel.mapboxToken.collectAsStateWithLifecycle()
    val voiceEnabled by viewModel.voiceEnabled.collectAsStateWithLifecycle()
    val voiceEngine by viewModel.voiceEngine.collectAsStateWithLifecycle()
    val voiceLanguage by viewModel.voiceLanguage.collectAsStateWithLifecycle()
    val mapTheme by viewModel.mapTheme.collectAsStateWithLifecycle()
    val charging by viewModel.charging.collectAsStateWithLifecycle()
    val savedOcmKey by viewModel.ocmKey.collectAsStateWithLifecycle()

    var tokenInput by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedToken) { if (tokenInput.isEmpty()) tokenInput = savedToken.orEmpty() }

    Column(
        Modifier
            .fillMaxSize()
            .background(AjColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", color = AjColors.Ice, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Section("API keys (bring your own key)") {
            Text(
                "ajNav ships without keys. Create a free account at mapbox.com and paste your public token (starts with pk.). It is stored encrypted on this phone only.",
                color = AjColors.Body,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = tokenInput,
                onValueChange = {
                    tokenInput = it.trim()
                    savedNote = null
                },
                label = { Text("Mapbox public token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showToken) "Hide token" else "Show token",
                            tint = AjColors.Muted,
                        )
                    }
                },
                isError = tokenInput.isNotEmpty() && !tokenInput.startsWith("pk."),
                supportingText = {
                    when {
                        tokenInput.isNotEmpty() && !tokenInput.startsWith("pk.") ->
                            Text("Use the public token (pk.…), never a secret sk. token")
                        savedNote != null -> Text(savedNote!!, color = AjColors.Teal)
                    }
                },
                colors = fieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.saveMapboxToken(tokenInput)
                    savedNote = if (mapboxAlreadyStarted) "Saved. Restart the app to use the new token." else "Saved."
                },
                enabled = tokenInput.startsWith("pk.") && tokenInput != savedToken,
                colors = ButtonDefaults.buttonColors(containerColor = AjColors.Coral, contentColor = AjColors.Darkest),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Save token", fontWeight = FontWeight.Bold) }
        }

        Section("Open Charge Map (charging stops)") {
            Text(
                "Needed to find chargers along your route. Free: openchargemap.org → My Profile → My Apps → Register an application. Stored encrypted on this phone only.",
                color = AjColors.Body,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            KeyField(
                saved = savedOcmKey,
                label = "Open Charge Map API key",
                onSave = viewModel::saveOcmKey,
            )
        }

        Section("Charging") {
            charging?.let { c ->
                Text(c.profile.displayName, color = AjColors.Ice, fontWeight = FontWeight.SemiBold)
                Text(
                    "${c.profile.defaultBatteryCapacityKwh} kWh usable · charges up to ${c.profile.chargeCurve.maxOf { it.maxPowerKw }.roundToInt()} kW",
                    color = AjColors.Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                NumberField(
                    key = "consumption",
                    label = "Energy use (kWh/100 km)",
                    initial = c.planner.consumptionKwhPer100Km.toFloat(),
                    onCommit = { it?.let(viewModel::setConsumption) },
                    decimals = 1,
                    range = 5f..50f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        key = "arrive",
                        label = "Arrive with at least (%)",
                        initial = c.planner.arriveMinPercent.toFloat(),
                        onCommit = { it?.let { v -> viewModel.setArriveMin(v.roundToInt()) } },
                        decimals = 0,
                        range = 0f..50f,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        key = "chargeTo",
                        label = "Charge to (%)",
                        initial = c.planner.chargeToPercent.toFloat(),
                        onCommit = { it?.let { v -> viewModel.setChargeTo(v.roundToInt()) } },
                        decimals = 0,
                        range = 20f..100f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "You type the battery % in the route preview. The car link (live battery data) comes in v0.4.",
                    color = AjColors.Muted,
                    fontSize = 12.sp,
                )
            }
        }

        Section("Map") {
            Text("Map colours", color = AjColors.Ice, fontWeight = FontWeight.SemiBold)
            Text("Auto follows the phone's dark mode, and day/night in the car.", color = AjColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(MapTheme.AUTO to "Auto", MapTheme.DARK to "Dark", MapTheme.LIGHT to "Light"),
                selected = mapTheme,
                onSelect = viewModel::setMapTheme,
            )
        }

        Section("Navigation") {
            LabeledRow("Voice instructions", null) {
                Switch(
                    checked = voiceEnabled,
                    onCheckedChange = viewModel::setVoiceEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = AjColors.Teal, checkedThumbColor = AjColors.Ice),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Voice", color = AjColors.Ice, fontWeight = FontWeight.SemiBold)
            Text(
                "Mapbox's online voice, or your phone's own text-to-speech voice (may say some street names better).",
                color = AjColors.Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(VoiceEngine.MAPBOX to "Mapbox", VoiceEngine.PHONE to "Phone's voice"),
                selected = voiceEngine,
                onSelect = viewModel::setVoiceEngine,
            )
            Spacer(Modifier.height(12.dp))
            Text("Voice language", color = AjColors.Ice, fontWeight = FontWeight.SemiBold)
            Text("Also the language of the turn instructions. Applies from the next route.", color = AjColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(
                    VoiceLanguage.PHONE to "Phone language",
                    VoiceLanguage.SWEDISH to "Svenska",
                    VoiceLanguage.ENGLISH to "English",
                ),
                selected = voiceLanguage,
                onSelect = viewModel::setVoiceLanguage,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = viewModel::clearRecents,
                border = BorderStroke(1.dp, AjColors.Border),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Clear recent destinations", color = AjColors.Body) }
        }

        Section("About") {
            Text("ajNav ${BuildConfig.VERSION_NAME}", color = AjColors.Ice, fontWeight = FontWeight.SemiBold)
            Text(
                "Open source (GNU AGPL-3.0) · github.com/lyzio/ajNav-Open\n" +
                    "Maps & routing © Mapbox © OpenStreetMap contributors\n" +
                    "Search by Photon (komoot)\n" +
                    "Chargers © Open Charge Map contributors (CC BY 4.0)\n" +
                    "Weather: SMHI (CC BY 4.0) · Elevation: Open-Meteo\n" +
                    "No Google Play Services.",
                color = AjColors.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun NoTokenScreen(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AjColors.Bg).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to ajNav", color = AjColors.Ice, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Add your Mapbox public token to start navigating.", color = AjColors.Body)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = AjColors.Coral, contentColor = AjColors.Darkest),
        ) { Text("Open Settings", fontWeight = FontWeight.Bold) }
    }
}

/** Save-able secret field (used for the Open Charge Map key). */
@Composable
private fun KeyField(saved: String?, label: String, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) { if (input.isEmpty()) input = saved.orEmpty() }
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it.trim()
            note = null
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (show) "Hide key" else "Show key",
                    tint = AjColors.Muted,
                )
            }
        },
        supportingText = { note?.let { Text(it, color = AjColors.Teal) } },
        colors = fieldColors(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            onSave(input)
            note = if (input.isEmpty()) "Removed." else "Saved."
        },
        enabled = input != saved.orEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = AjColors.Coral, contentColor = AjColors.Darkest),
        shape = RoundedCornerShape(12.dp),
    ) { Text(if (input.isEmpty() && !saved.isNullOrEmpty()) "Remove key" else "Save key", fontWeight = FontWeight.Bold) }
}
