package io.github.lyzio.ajnav.feature.charging.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lyzio.ajnav.feature.charging.planner.OperatorMode
import io.github.lyzio.ajnav.feature.charging.planner.PlannerSettings
import io.github.lyzio.ajnav.feature.charging.tariff.PriceSettings
import io.github.lyzio.ajnav.feature.vehicles.VehicleProfile
import io.github.lyzio.ajnav.feature.vehicles.VehicleProfiles
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.chargingStore by preferencesDataStore(name = "charging")

/** Everything the user sets for charging, plus the last typed battery %. Stored on the phone only. */
data class ChargingSettings(
    val profile: VehicleProfile,
    val planner: PlannerSettings,
    val lastBatteryPercent: Int?,
)

@Singleton
class ChargingPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val settings: Flow<ChargingSettings> = context.chargingStore.data.map { p -> p.toSettings() }

    suspend fun setConsumption(kwhPer100Km: Float) = edit { it[CONSUMPTION] = kwhPer100Km.coerceIn(5f, 50f) }
    suspend fun setArriveMin(percent: Int) = edit { it[ARRIVE_MIN] = percent.coerceIn(0, 50) }
    suspend fun setChargeTo(percent: Int) = edit { it[CHARGE_TO] = percent.coerceIn(20, 100) }
    suspend fun setLastBattery(percent: Int) = edit { it[LAST_BATTERY] = percent.coerceIn(0, 100) }
    suspend fun setOperatorMode(mode: OperatorMode) = edit { it[MODE] = mode.name }
    suspend fun setGlobalDefaultPrice(price: Float) = edit { it[GLOBAL_PRICE] = price.coerceIn(0f, 50f) }

    suspend fun setPreferred(operatorId: Int, preferred: Boolean) = edit { p ->
        val current = p[PREFERRED].orEmpty()
        p[PREFERRED] = if (preferred) current + operatorId.toString() else current - operatorId.toString()
    }

    suspend fun setSubscriptionPrice(operatorId: Int, price: Float?) = editPrices(SUBSCRIPTION, operatorId, price)
    suspend fun setOperatorDefaultPrice(operatorId: Int, price: Float?) = editPrices(OPERATOR_DEFAULT, operatorId, price)

    private suspend fun editPrices(key: Preferences.Key<String>, operatorId: Int, price: Float?) = edit { p ->
        val map = decodePrices(p[key]).toMutableMap()
        if (price == null) map.remove(operatorId) else map[operatorId] = price.coerceIn(0f, 50f)
        p[key] = json.encodeToString(PRICE_MAP, map.mapKeys { it.key.toString() })
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.chargingStore.edit { block(it) }
    }

    private fun Preferences.toSettings(): ChargingSettings {
        val profile = VehicleProfiles.byId(this[PROFILE])
        return ChargingSettings(
            profile = profile,
            planner = PlannerSettings(
                consumptionKwhPer100Km = (this[CONSUMPTION] ?: profile.defaultConsumptionKwhPer100Km).toDouble(),
                arriveMinPercent = (this[ARRIVE_MIN] ?: DEFAULT_ARRIVE_MIN).toDouble(),
                chargeToPercent = (this[CHARGE_TO] ?: DEFAULT_CHARGE_TO).toDouble(),
                preferredOperatorIds = this[PREFERRED].orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
                operatorMode = this[MODE]?.let { runCatching { OperatorMode.valueOf(it) }.getOrNull() } ?: OperatorMode.PREFER,
                prices = PriceSettings(
                    subscriptionPrices = decodePrices(this[SUBSCRIPTION]),
                    operatorDefaultPrices = decodePrices(this[OPERATOR_DEFAULT]),
                    globalDefaultPrice = this[GLOBAL_PRICE] ?: PriceSettings.DEFAULT_GLOBAL_PRICE,
                ),
            ),
            lastBatteryPercent = this[LAST_BATTERY],
        )
    }

    private fun decodePrices(raw: String?): Map<Int, Float> =
        raw?.let { runCatching { json.decodeFromString(PRICE_MAP, it) }.getOrNull() }
            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }?.toMap()
            ?: emptyMap()

    companion object {
        const val DEFAULT_ARRIVE_MIN = 10
        const val DEFAULT_CHARGE_TO = 80
        private val PRICE_MAP = MapSerializer(String.serializer(), Float.serializer())
        private val PROFILE = stringPreferencesKey("vehicle_profile")
        private val CONSUMPTION = floatPreferencesKey("consumption_kwh_per_100km")
        private val ARRIVE_MIN = intPreferencesKey("arrive_min_percent")
        private val CHARGE_TO = intPreferencesKey("charge_to_percent")
        private val LAST_BATTERY = intPreferencesKey("last_battery_percent")
        private val PREFERRED = stringSetPreferencesKey("preferred_operators")
        private val MODE = stringPreferencesKey("operator_mode")
        private val SUBSCRIPTION = stringPreferencesKey("subscription_prices")
        private val OPERATOR_DEFAULT = stringPreferencesKey("operator_default_prices")
        private val GLOBAL_PRICE = floatPreferencesKey("global_default_price")
    }
}
