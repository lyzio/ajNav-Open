package io.github.lyzio.ajnav.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import io.github.lyzio.ajnav.core.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Destination search (SearchTemplate) using the same key-free Photon search as the phone.
 * Android Auto only shows the keyboard while parked; while driving, use voice
 * ("Hey Google, navigate to … with ajNav").
 */
internal class CarSearchScreen(
    carContext: CarContext,
    private val deps: CarDeps,
) : Screen(carContext) {

    private var results: List<Place> = emptyList()
    private var loading = false
    private var error: String? = null
    private var searchJob: Job? = null

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) = search(searchText, debounce = true)
                override fun onSearchSubmitted(searchText: String) = search(searchText, debounce = false)
            },
        )
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search destination or address")
            .setShowKeyboardByDefault(true)

        if (loading) return builder.setLoading(true).build()

        val list = ItemList.Builder()
        error?.let { list.setNoItemsMessage(it) } ?: list.setNoItemsMessage("Type a place or address")
        results.take(MAX_RESULTS).forEach { place ->
            list.addItem(
                Row.Builder()
                    .setTitle(place.name)
                    .apply { if (place.address.isNotBlank()) addText(place.address) }
                    .setOnClickListener {
                        deps.trip.previewRoute(place)
                        screenManager.popToRoot() // the main screen opens the route preview
                    }
                    .build(),
            )
        }
        return builder.setItemList(list.build()).build()
    }

    private fun search(text: String, debounce: Boolean) {
        searchJob?.cancel()
        val q = text.trim()
        if (q.length < 2) {
            results = emptyList()
            error = null
            loading = false
            invalidate()
            return
        }
        searchJob = lifecycleScope.launch {
            if (debounce) delay(500) // Android Auto limits how often a screen may refresh
            loading = true
            invalidate()
            try {
                results = deps.geocoding.search(q, deps.trip.state.value.location)
                error = if (results.isEmpty()) "No results" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results = emptyList()
                error = e.message ?: "Search failed"
            }
            loading = false
            invalidate()
        }
    }

    private companion object {
        const val MAX_RESULTS = 6
    }
}
