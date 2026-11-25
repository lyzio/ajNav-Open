package io.github.lyzio.ajnav.core.model

import kotlinx.serialization.Serializable

/** A WGS84 coordinate. Pure Kotlin, no Android or Mapbox types. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

/** A searchable / saveable destination. */
@Serializable
data class Place(
    val name: String,
    val address: String,
    val point: GeoPoint,
)

/** Fixed favourite slots shown as quick tiles. */
enum class FavouriteSlot { HOME, WORK }
