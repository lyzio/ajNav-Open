package io.github.lyzio.ajnav.feature.navigation.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lyzio.ajnav.core.model.FavouriteSlot
import io.github.lyzio.ajnav.core.model.Place
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.navStore by preferencesDataStore(name = "navigation")

/** Map colours (N1): Auto follows the car's day/night signal and the phone's dark mode. */
enum class MapTheme { AUTO, DARK, LIGHT }

/** Who speaks the instructions (N3). */
enum class VoiceEngine { MAPBOX, PHONE }

/** Language of the spoken (and written) instructions (N3). */
enum class VoiceLanguage(val tag: String?) { PHONE(null), SWEDISH("sv"), ENGLISH("en") }

/** Favourites (Home/Work), recent destinations and navigation settings. Local only. */
@Singleton
class NavPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val favourites: Flow<Map<FavouriteSlot, Place>> = context.navStore.data.map { prefs ->
        FavouriteSlot.entries.mapNotNull { slot ->
            prefs[favKey(slot)]?.let { raw -> decodePlace(raw)?.let { slot to it } }
        }.toMap()
    }

    val recents: Flow<List<Place>> = context.navStore.data.map { prefs ->
        prefs[RECENTS]?.let { raw -> runCatching { json.decodeFromString(PLACE_LIST, raw) }.getOrNull() }
            ?: emptyList()
    }

    val voiceEnabled: Flow<Boolean> = context.navStore.data.map { it[VOICE] ?: true }

    val mapTheme: Flow<MapTheme> = context.navStore.data.map { p -> enumOr(p[THEME], MapTheme.AUTO) }
    val voiceEngine: Flow<VoiceEngine> = context.navStore.data.map { p -> enumOr(p[VOICE_ENGINE], VoiceEngine.MAPBOX) }
    val voiceLanguage: Flow<VoiceLanguage> = context.navStore.data.map { p -> enumOr(p[VOICE_LANGUAGE], VoiceLanguage.PHONE) }

    suspend fun setMapTheme(theme: MapTheme) {
        context.navStore.edit { it[THEME] = theme.name }
    }

    suspend fun setVoiceEngine(engine: VoiceEngine) {
        context.navStore.edit { it[VOICE_ENGINE] = engine.name }
    }

    suspend fun setVoiceLanguage(language: VoiceLanguage) {
        context.navStore.edit { it[VOICE_LANGUAGE] = language.name }
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
        raw?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    suspend fun setFavourite(slot: FavouriteSlot, place: Place?) {
        context.navStore.edit { prefs ->
            if (place == null) prefs.remove(favKey(slot)) else prefs[favKey(slot)] = json.encodeToString(Place.serializer(), place)
        }
    }

    suspend fun addRecent(place: Place) {
        context.navStore.edit { prefs ->
            val current = prefs[RECENTS]?.let { runCatching { json.decodeFromString(PLACE_LIST, it) }.getOrNull() }
                ?: emptyList()
            val updated = (listOf(place) + current.filterNot { it.isSameAs(place) }).take(MAX_RECENTS)
            prefs[RECENTS] = json.encodeToString(PLACE_LIST, updated)
        }
    }

    suspend fun clearRecents() {
        context.navStore.edit { it.remove(RECENTS) }
    }

    suspend fun setVoiceEnabled(enabled: Boolean) {
        context.navStore.edit { it[VOICE] = enabled }
    }

    private fun decodePlace(raw: String): Place? = runCatching { json.decodeFromString(Place.serializer(), raw) }.getOrNull()

    private fun Place.isSameAs(other: Place): Boolean =
        name == other.name &&
            kotlin.math.abs(point.latitude - other.point.latitude) < 1e-4 &&
            kotlin.math.abs(point.longitude - other.point.longitude) < 1e-4

    private companion object {
        const val MAX_RECENTS = 10
        val PLACE_LIST = ListSerializer(Place.serializer())
        val RECENTS = stringPreferencesKey("recents")
        val VOICE = booleanPreferencesKey("voice_enabled")
        val THEME = stringPreferencesKey("map_theme")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        fun favKey(slot: FavouriteSlot) = stringPreferencesKey("favourite_${slot.name.lowercase()}")
    }
}
