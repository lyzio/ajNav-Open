package io.github.lyzio.ajnav.feature.navigation.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lyzio.ajnav.feature.navigation.geocoding.GeocodingProvider
import io.github.lyzio.ajnav.feature.navigation.geocoding.PhotonGeocodingProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
abstract class NavigationModule {

    @Binds
    abstract fun bindGeocoding(impl: PhotonGeocodingProvider): GeocodingProvider

    companion object {
        @Provides
        @Singleton
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        @Provides
        @Singleton
        fun provideHttpClient(): HttpClient = HttpClient(Android) {
            engine {
                connectTimeout = 10_000
                socketTimeout = 10_000
            }
        }
    }
}
