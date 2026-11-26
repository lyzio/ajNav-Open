package io.github.lyzio.ajnav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import dagger.hilt.android.AndroidEntryPoint
import io.github.lyzio.ajnav.core.security.ApiKey
import io.github.lyzio.ajnav.core.security.ApiKeyRepository
import io.github.lyzio.ajnav.feature.navigation.session.MapboxBootstrap
import io.github.lyzio.ajnav.feature.navigation.ui.NavigationScreen
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjNavTheme
import io.github.lyzio.ajnav.operators.OperatorsScreen
import io.github.lyzio.ajnav.settings.NoTokenScreen
import io.github.lyzio.ajnav.settings.SettingsScreen
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var apiKeys: ApiKeyRepository
    @Inject lateinit var mapboxBootstrap: MapboxBootstrap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AjNavTheme {
                val tokenFlow = remember {
                    apiKeys.observe(ApiKey.MAPBOX_PUBLIC_TOKEN).map<String?, TokenState> { TokenState.Loaded(it) }
                }
                val tokenState by tokenFlow.collectAsState(initial = TokenState.Loading)
                val token = (tokenState as? TokenState.Loaded)?.token
                // Mapbox must be configured before the first MapView is created.
                val mapboxReady = remember(token) { token?.let { configureMapbox(it) } ?: false }
                AppRoot(tokenState = tokenState, mapboxReady = mapboxReady)
            }
        }
    }

    private fun configureMapbox(token: String): Boolean {
        mapboxBootstrap.ensure(token)
        MapboxNavigationApp.attach(this)
        return true
    }
}

sealed interface TokenState {
    data object Loading : TokenState
    data class Loaded(val token: String?) : TokenState
}

private enum class Tab { NAVIGATE, OPERATORS, SETTINGS }

@Composable
private fun AppRoot(tokenState: TokenState, mapboxReady: Boolean) {
    var tab by rememberSaveable { mutableStateOf(Tab.NAVIGATE) }
    var navigating by remember { mutableStateOf(false) }

    LaunchedEffect(tokenState) {
        if (tokenState is TokenState.Loaded && tokenState.token == null) tab = Tab.SETTINGS
    }

    Scaffold(
        containerColor = AjColors.Bg,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!navigating) {
                NavigationBar(containerColor = AjColors.Darkest) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AjColors.Teal,
                        selectedTextColor = AjColors.Teal,
                        indicatorColor = AjColors.Pill,
                        unselectedIconColor = AjColors.Muted,
                        unselectedTextColor = AjColors.Muted,
                    )
                    NavigationBarItem(
                        selected = tab == Tab.NAVIGATE,
                        onClick = { tab = Tab.NAVIGATE },
                        icon = { Icon(Icons.Filled.Navigation, contentDescription = null) },
                        label = { Text("Navigation") },
                        colors = colors,
                    )
                    NavigationBarItem(
                        selected = tab == Tab.OPERATORS,
                        onClick = { tab = Tab.OPERATORS },
                        icon = { Icon(Icons.Filled.EvStation, contentDescription = null) },
                        label = { Text("Operators & Prices") },
                        colors = colors,
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        colors = colors,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The map stays composed while Settings is open, so a running trip is never lost.
            if (mapboxReady) {
                NavigationScreen(onActiveNavigationChanged = { navigating = it })
            } else if (tokenState is TokenState.Loaded) {
                NoTokenScreen(onOpenSettings = { tab = Tab.SETTINGS })
            }
            if (tab == Tab.OPERATORS && !navigating) {
                OperatorsScreen()
            }
            if (tab == Tab.SETTINGS && !navigating) {
                SettingsScreen(mapboxAlreadyStarted = mapboxReady)
            }
        }
    }
}
