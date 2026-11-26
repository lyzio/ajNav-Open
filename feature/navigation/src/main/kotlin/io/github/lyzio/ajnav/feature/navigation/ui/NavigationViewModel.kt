package io.github.lyzio.ajnav.feature.navigation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lyzio.ajnav.core.model.FavouriteSlot
import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.Place
import io.github.lyzio.ajnav.feature.navigation.data.MapTheme
import io.github.lyzio.ajnav.feature.navigation.data.NavPreferencesRepository
import io.github.lyzio.ajnav.feature.navigation.geocoding.GeocodingProvider
import io.github.lyzio.ajnav.feature.navigation.session.TripController
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val places: List<Place>) : SearchState
    data class Error(val message: String) : SearchState
}

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val geocoding: GeocodingProvider,
    private val prefs: NavPreferencesRepository,
    /** The shared trip (also used by Android Auto). */
    val trip: TripController,
) : ViewModel() {

    val favourites: StateFlow<Map<FavouriteSlot, Place>> =
        prefs.favourites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val recents: StateFlow<List<Place>> =
        prefs.recents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mapTheme: StateFlow<MapTheme> =
        prefs.mapTheme.stateIn(viewModelScope, SharingStarted.Eagerly, MapTheme.AUTO)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()

    /** Current position, used to rank search results near the driver. */
    var searchBias: GeoPoint? = null

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _query.value = value
        searchJob?.cancel()
        val q = value.trim()
        if (q.length < 2) {
            _search.value = SearchState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce typing
            _search.value = SearchState.Loading
            _search.value = try {
                SearchState.Results(geocoding.search(q, searchBias))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SearchState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun clearQuery() = onQueryChange("")

    fun setFavourite(slot: FavouriteSlot, place: Place) {
        viewModelScope.launch { prefs.setFavourite(slot, place) }
    }
}
