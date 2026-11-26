package io.github.lyzio.ajnav.feature.charging.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import io.github.lyzio.ajnav.core.model.ChargerStation
import kotlinx.coroutines.flow.Flow

/** Chargers fetched for earlier routes, kept for poor signal (design §4.4 A offline cache). */
@Entity(tableName = "stations", indices = [Index("latitude"), Index("longitude")])
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val operatorId: Int?,
    val operatorName: String,
    val latitude: Double,
    val longitude: Double,
    val maxPowerKw: Float,
    val totalPoints: Int?,
    val usageCostRaw: String?,
    val address: String,
    val fetchedAtMs: Long,
) {
    fun toStation() = ChargerStation(
        id = id,
        name = name,
        operatorId = operatorId,
        operatorName = operatorName,
        latitude = latitude,
        longitude = longitude,
        maxPowerKw = maxPowerKw,
        totalPoints = totalPoints,
        usageCostRaw = usageCostRaw,
        address = address,
    )

    companion object {
        fun from(s: ChargerStation, now: Long) = StationEntity(
            s.id, s.name, s.operatorId, s.operatorName, s.latitude, s.longitude,
            s.maxPowerKw, s.totalPoints, s.usageCostRaw, s.address, now,
        )
    }
}

/** Charging network operators from Open Charge Map's reference data. */
@Entity(tableName = "operators")
data class OperatorEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val websiteUrl: String?,
    val fetchedAtMs: Long,
)

@Dao
interface StationDao {
    @Upsert
    suspend fun upsert(stations: List<StationEntity>)

    @Query(
        "SELECT * FROM stations WHERE latitude BETWEEN :minLat AND :maxLat " +
            "AND longitude BETWEEN :minLon AND :maxLon",
    )
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<StationEntity>

    @Query("DELETE FROM stations WHERE fetchedAtMs < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)
}

@Dao
interface OperatorDao {
    @Upsert
    suspend fun upsert(operators: List<OperatorEntity>)

    @Query("SELECT * FROM operators ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<OperatorEntity>>

    @Query("SELECT * FROM operators")
    suspend fun all(): List<OperatorEntity>

    @Query("SELECT MIN(fetchedAtMs) FROM operators")
    suspend fun oldestFetch(): Long?
}

@Database(entities = [StationEntity::class, OperatorEntity::class], version = 1, exportSchema = false)
abstract class ChargingDatabase : RoomDatabase() {
    abstract fun stations(): StationDao
    abstract fun operators(): OperatorDao
}
