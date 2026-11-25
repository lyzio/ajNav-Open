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
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors

@Composable
fun SettingsScreen(
    mapboxAlreadyStarted: Boolean,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val savedToken by viewModel.mapboxToken.collectAsStateWithLifecycle()
    val voiceEnabled by viewModel.voiceEnabled.collectAsStateWithLifecycle()

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

        Section("Navigation") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Voice instructions", color = AjColors.Ice, fontWeight = FontWeight.SemiBold)
                    Text("Spoken in your phone's language", color = AjColors.Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = voiceEnabled,
                    onCheckedChange = viewModel::setVoiceEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = AjColors.Teal, checkedThumbColor = AjColors.Ice),
                )
            }
            Spacer(Modifier.height(10.dp))
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        color = AjColors.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AjColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title.uppercase(), color = AjColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AjColors.Land,
    unfocusedContainerColor = AjColors.Land,
    focusedBorderColor = AjColors.Teal,
    unfocusedBorderColor = AjColors.Border,
    cursorColor = AjColors.Teal,
    focusedTextColor = AjColors.Ice,
    unfocusedTextColor = AjColors.Ice,
    focusedLabelColor = AjColors.Teal,
    unfocusedLabelColor = AjColors.Muted,
)
