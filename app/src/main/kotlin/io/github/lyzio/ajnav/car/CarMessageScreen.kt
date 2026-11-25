package io.github.lyzio.ajnav.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/** Simple message, e.g. "Set up ajNav on your phone". [onRetry] adds a Try again button. */
internal class CarMessageScreen(
    carContext: CarContext,
    private val message: String,
    private val onRetry: (() -> Unit)? = null,
) : Screen(carContext) {

    override fun onGetTemplate(): Template = MessageTemplate.Builder(message)
        .setTitle("ajNav")
        .setHeaderAction(Action.APP_ICON)
        .apply {
            onRetry?.let { retry ->
                addAction(Action.Builder().setTitle("Try again").setOnClickListener { retry() }.build())
            }
        }
        .build()
}
