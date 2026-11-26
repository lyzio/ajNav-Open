package io.github.lyzio.ajnav.feature.charging.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.lyzio.ajnav.feature.charging.cache.ChargingDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChargingModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChargingDatabase =
        Room.databaseBuilder(context, ChargingDatabase::class.java, "charging.db").build()
}
