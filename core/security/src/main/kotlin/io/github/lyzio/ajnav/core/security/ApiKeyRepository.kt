package io.github.lyzio.ajnav.core.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** API keys the user brings themselves (BYOK). More keys are added in later versions. */
enum class ApiKey(internal val storageKey: String) {
    MAPBOX_PUBLIC_TOKEN("mapbox_public_token"),
}

private val Context.secureKeyStore by preferencesDataStore(name = "secure_keys")

/**
 * Stores user-supplied API keys encrypted on the device.
 * Falls back to a developer pre-fill from local.properties (BuildConfig) when nothing is stored.
 */
@Singleton
class ApiKeyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cipher = KeystoreCipher()

    fun observe(key: ApiKey): Flow<String?> =
        context.secureKeyStore.data.map { prefs ->
            prefs[stringPreferencesKey(key.storageKey)]
                ?.let(cipher::decrypt)
                ?.takeIf { it.isNotBlank() }
                ?: buildConfigDefault(key)
        }

    suspend fun get(key: ApiKey): String? = observe(key).first()

    suspend fun set(key: ApiKey, value: String?) {
        val prefKey = stringPreferencesKey(key.storageKey)
        context.secureKeyStore.edit { prefs ->
            val trimmed = value?.trim()
            if (trimmed.isNullOrEmpty()) prefs.remove(prefKey) else prefs[prefKey] = cipher.encrypt(trimmed)
        }
    }

    private fun buildConfigDefault(key: ApiKey): String? = when (key) {
        ApiKey.MAPBOX_PUBLIC_TOKEN -> BuildConfig.MAPBOX_PUBLIC_TOKEN.takeIf { it.isNotBlank() }
    }
}
