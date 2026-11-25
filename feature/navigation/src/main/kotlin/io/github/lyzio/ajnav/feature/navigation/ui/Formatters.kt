package io.github.lyzio.ajnav.feature.navigation.ui

import android.content.Context
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal object Formatters {
    fun duration(seconds: Double): String {
        val totalMin = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    fun distance(meters: Double): String = when {
        meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 100_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun clock(context: Context, epochMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMs))
}
