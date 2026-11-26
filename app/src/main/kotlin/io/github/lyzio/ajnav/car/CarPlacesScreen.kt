package io.github.lyzio.ajnav.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lyzio.ajnav.core.model.FavouriteSlot
import io.github.lyzio.ajnav.core.model.Place
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Quick destinations (ListTemplate): Home, Work and recent destinations, one tap each. */
internal class CarPlacesScreen(
    carContext: CarContext,
    private val deps: CarDeps,
) : Screen(carContext) {

    private var favourites: Map<FavouriteSlot, Place> = emptyMap()
    private var recents: List<Place> = emptyList()
    private var loaded = false

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(deps.prefs.favourites, deps.prefs.recents) { f, r -> f to r }.collect { (f, r) ->
                    favourites = f
                    recents = r
                    loaded = true
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val builder = ListTemplate.Builder()
            .setTitle("Places")
            .setHeaderAction(Action.BACK)
        if (!loaded) return builder.setLoading(true).build()

        val list = ItemList.Builder().setNoItemsMessage("No places yet. Set Home and Work on your phone.")
        favourites[FavouriteSlot.HOME]?.let { list.addItem(row("Home", it)) }
        favourites[FavouriteSlot.WORK]?.let { list.addItem(row("Work", it)) }
        val favPoints = favourites.values.map { it.point }
        recents.filterNot { it.point in favPoints }.forEach { list.addItem(row(it.name, it)) }
        return builder.setSingleList(list.build()).build()
    }

    private fun row(title: String, place: Place): Row = Row.Builder()
        .setTitle(title)
        .apply {
            val sub = if (title == place.name) place.address else listOf(place.name, place.address).filter { it.isNotBlank() }.joinToString(", ")
            if (sub.isNotBlank()) addText(sub)
        }
        .setOnClickListener {
            deps.trip.previewRoute(place)
            screenManager.popToRoot() // the main screen opens the route preview
        }
        .build()
}
