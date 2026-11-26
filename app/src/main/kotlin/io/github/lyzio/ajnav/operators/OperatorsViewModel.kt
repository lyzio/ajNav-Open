package io.github.lyzio.ajnav.operators

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lyzio.ajnav.feature.charging.ChargingPlanService
import io.github.lyzio.ajnav.feature.charging.data.ChargingPreferencesRepository
import io.github.lyzio.ajnav.feature.charging.data.ChargingSettings
import io.github.lyzio.ajnav.feature.charging.ocm.OcmKeyException
import io.github.lyzio.ajnav.feature.charging.ocm.OcmMissingKeyException
import io.github.lyzio.ajnav.feature.charging.planner.OperatorMode
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OperatorRow(
    val id: Int,
    val name: String,
    val preferred: Boolean,
    val subscriptionPrice: Float?,
    val usualPrice: Float?,
)

/** Operators & Prices tab (design §4.7 B 3): preferred operators and your own prices. */
@HiltViewModel
class OperatorsViewModel @Inject constructor(
    private val charging: ChargingPlanService,
    private val prefs: ChargingPreferencesRepository,
) : ViewModel() {

    val hasKey: StateFlow<Boolean> = charging.hasOcmKey.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val settings: StateFlow<ChargingSettings?> = charging.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Preferred operators first, then A–Z, filtered by the search text. */
    val rows: StateFlow<List<OperatorRow>> = combine(charging.operators, charging.settings, _query) { ops, s, q ->
        val planner = s.planner
        ops.asSequence()
            .filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
            .map { o ->
                OperatorRow(
                    id = o.id,
                    name = o.name,
                    preferred = o.id in planner.preferredOperatorIds,
                    subscriptionPrice = planner.prices.subscriptionPrices[o.id],
                    usualPrice = planner.prices.operatorDefaultPrices[o.id],
                )
            }
            .sortedWith(compareByDescending<OperatorRow> { it.preferred }.thenBy { it.name.lowercase() })
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            charging.hasOcmKey.distinctUntilChanged().collect { if (it) refresh(force = false) }
        }
    }

    fun refresh(force: Boolean = true) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            val result = charging.refreshOperators(force)
            _error.value = result.exceptionOrNull()?.let { e ->
                when (e) {
                    is OcmKeyException -> "Open Charge Map rejected your key. Check it in Settings."
                    is OcmMissingKeyException -> null // no key yet; the screen already says so
                    else -> "Couldn't load operators. Check your connection and try again."
                }
            }
            _loading.value = false
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
    }

    fun setPreferred(id: Int, preferred: Boolean) = launch { prefs.setPreferred(id, preferred) }
    fun setMode(mode: OperatorMode) = launch { prefs.setOperatorMode(mode) }
    fun setSubscriptionPrice(id: Int, price: Float?) = launch { prefs.setSubscriptionPrice(id, price) }
    fun setUsualPrice(id: Int, price: Float?) = launch { prefs.setOperatorDefaultPrice(id, price) }
    fun setGlobalDefaultPrice(price: Float) = launch { prefs.setGlobalDefaultPrice(price) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
