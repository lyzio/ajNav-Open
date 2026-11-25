package io.github.lyzio.ajnav.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lyzio.ajnav.core.security.ApiKey
import io.github.lyzio.ajnav.core.security.ApiKeyRepository
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiKeys: ApiKeyRepository,
    private val navPrefs: NavPreferencesRepository,
) : ViewModel() {

    val mapboxToken: StateFlow<String?> =
        apiKeys.observe(ApiKey.MAPBOX_PUBLIC_TOKEN).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val voiceEnabled: StateFlow<Boolean> =
        navPrefs.voiceEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun saveMapboxToken(token: String) {
        viewModelScope.launch { apiKeys.set(ApiKey.MAPBOX_PUBLIC_TOKEN, token) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch { navPrefs.setVoiceEnabled(enabled) }
    }

    fun clearRecents() {
        viewModelScope.launch { navPrefs.clearRecents() }
    }
}
