package io.github.lyzio.ajnav.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lyzio.ajnav.core.security.ApiKey
import io.github.lyzio.ajnav.core.security.ApiKeyRepository
import io.github.lyzio.ajnav.feature.charging.data.ChargingPreferencesRepository
import io.github.lyzio.ajnav.feature.charging.data.ChargingSettings
import io.github.lyzio.ajnav.feature.navigation.data.MapTheme
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import io.github.lyzio.ajnav.feature.navigation.data.VoiceEngine
import io.github.lyzio.ajnav.feature.navigation.data.VoiceLanguage
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiKeys: ApiKeyRepository,
    private val navPrefs: NavPreferencesRepository,
    private val chargingPrefs: ChargingPreferencesRepository,
) : ViewModel() {

    val mapboxToken: StateFlow<String?> =
        apiKeys.observe(ApiKey.MAPBOX_PUBLIC_TOKEN).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val ocmKey: StateFlow<String?> =
        apiKeys.observe(ApiKey.OCM_API_KEY).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val voiceEnabled: StateFlow<Boolean> =
        navPrefs.voiceEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val voiceEngine: StateFlow<VoiceEngine> =
        navPrefs.voiceEngine.stateIn(viewModelScope, SharingStarted.Eagerly, VoiceEngine.MAPBOX)
    val voiceLanguage: StateFlow<VoiceLanguage> =
        navPrefs.voiceLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, VoiceLanguage.PHONE)
    val mapTheme: StateFlow<MapTheme> =
        navPrefs.mapTheme.stateIn(viewModelScope, SharingStarted.Eagerly, MapTheme.AUTO)
    val charging: StateFlow<ChargingSettings?> =
        chargingPrefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveMapboxToken(token: String) = launch { apiKeys.set(ApiKey.MAPBOX_PUBLIC_TOKEN, token) }
    fun saveOcmKey(key: String) = launch { apiKeys.set(ApiKey.OCM_API_KEY, key) }
    fun setVoiceEnabled(enabled: Boolean) = launch { navPrefs.setVoiceEnabled(enabled) }
    fun setVoiceEngine(engine: VoiceEngine) = launch { navPrefs.setVoiceEngine(engine) }
    fun setVoiceLanguage(language: VoiceLanguage) = launch { navPrefs.setVoiceLanguage(language) }
    fun setMapTheme(theme: MapTheme) = launch { navPrefs.setMapTheme(theme) }
    fun setConsumption(kwhPer100Km: Float) = launch { chargingPrefs.setConsumption(kwhPer100Km) }
    fun setArriveMin(percent: Int) = launch { chargingPrefs.setArriveMin(percent) }
    fun setChargeTo(percent: Int) = launch { chargingPrefs.setChargeTo(percent) }
    fun clearRecents() = launch { navPrefs.clearRecents() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
