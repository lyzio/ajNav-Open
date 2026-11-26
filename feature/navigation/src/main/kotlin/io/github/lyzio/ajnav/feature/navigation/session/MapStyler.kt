package io.github.lyzio.ajnav.feature.navigation.session

import com.mapbox.bindgen.Value
import com.mapbox.maps.Style

/**
 * Recolours Mapbox's own dark / light map into LyzLarm tones in the app (N1, design §4.7 C):
 * no Mapbox Studio style is needed, so it works with every user's own token.
 */
internal object MapStyler {

    private data class Palette(
        val land: String,
        val landuse: String,
        val water: String,
        val building: String,
        val road: String,
        val majorRoad: String,
        val border: String,
        val label: String,
        val placeLabel: String,
        val halo: String,
    )

    /** Green-toned dark map (replaces Mapbox's standard dark). */
    private val DARK = Palette(
        land = "#17292C",
        landuse = "#1C3833",
        water = "#0C1C21",
        building = "#22393D",
        road = "#2C474D",
        majorRoad = "#3D6268",
        border = "#4A6A70",
        label = "#A9BCC0",
        placeLabel = "#D3DEE0",
        halo = "#10201F",
    )

    /** Light LyzLarm variant. */
    private val LIGHT = Palette(
        land = "#EEF3F1",
        landuse = "#D8ECE5",
        water = "#B7D8D9",
        building = "#E0E8E6",
        road = "#FFFFFF",
        majorRoad = "#C6E1DC",
        border = "#8FA9A6",
        label = "#33525A",
        placeLabel = "#1B2F33",
        halo = "#FFFFFF",
    )

    /** Mapbox base style to load for each theme; [recolor] is applied after it loads. */
    fun baseStyle(dark: Boolean): String = if (dark) Style.DARK else Style.LIGHT

    fun recolor(style: Style, dark: Boolean) {
        val p = if (dark) DARK else LIGHT
        for (layer in style.styleLayers) {
            val id = layer.id
            if (id.startsWith("mapbox-navigation") || id.startsWith("mapbox-location") || id.startsWith("ajnav-")) continue
            when (layer.type) {
                "background" -> set(style, id, "background-color", p.land)
                "fill" -> when {
                    "water" in id -> set(style, id, "fill-color", p.water)
                    "building" in id -> set(style, id, "fill-color", p.building)
                    id == "land" -> set(style, id, "fill-color", p.land)
                    "landcover" in id || "landuse" in id || "park" in id || "national" in id -> {
                        set(style, id, "fill-color", p.landuse)
                    }
                }
                "fill-extrusion" -> if ("building" in id) set(style, id, "fill-extrusion-color", p.building)
                "line" -> when {
                    "water" in id -> set(style, id, "line-color", p.water)
                    "admin" in id || "boundary" in id -> set(style, id, "line-color", p.border)
                    "road" in id || "bridge" in id || "tunnel" in id -> {
                        val major = "motorway" in id || "trunk" in id || "primary" in id
                        set(style, id, "line-color", if (major) p.majorRoad else p.road)
                    }
                }
                "symbol" -> {
                    val place = "settlement" in id || "place" in id || "country" in id || "state" in id
                    set(style, id, "text-color", if (place) p.placeLabel else p.label)
                    set(style, id, "text-halo-color", p.halo)
                }
            }
        }
    }

    private fun set(style: Style, layerId: String, property: String, color: String) {
        style.setStyleLayerProperty(layerId, property, Value.valueOf(color))
    }
}
