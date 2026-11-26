package io.github.lyzio.ajnav.feature.charging

import io.github.lyzio.ajnav.core.model.ChargerStation
import io.github.lyzio.ajnav.core.model.GeoPoint
import io.github.lyzio.ajnav.core.model.PriceSource
import io.github.lyzio.ajnav.core.model.WeatherSnapshot
import io.github.lyzio.ajnav.feature.charging.planner.ChargeTime
import io.github.lyzio.ajnav.feature.charging.planner.CorridorCharger
import io.github.lyzio.ajnav.feature.charging.planner.EnergyModel
import io.github.lyzio.ajnav.feature.charging.planner.OperatorMode
import io.github.lyzio.ajnav.feature.charging.planner.PlannerSettings
import io.github.lyzio.ajnav.feature.charging.planner.PolylineCodec
import io.github.lyzio.ajnav.feature.charging.planner.RouteGeometry
import io.github.lyzio.ajnav.feature.charging.planner.StopPlanner
import io.github.lyzio.ajnav.feature.charging.tariff.ChainedTariffProvider
import io.github.lyzio.ajnav.feature.charging.tariff.OcmUsageCostParser
import io.github.lyzio.ajnav.feature.charging.tariff.PriceSettings
import io.github.lyzio.ajnav.feature.vehicles.Fiat500e2021Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {

    // A straight 300 km route going north from Lund-ish latitude (1° latitude ≈ 111.2 km).
    private val route = RouteGeometry((0..30).map { GeoPoint(55.7 + it * 0.09, 13.2) })
    private val fiat = Fiat500e2021Profile

    private fun station(id: String, km: Double, kw: Float = 150f, op: Int? = 10, cost: String? = null) = ChargerStation(
        id = id, name = "Charger $id", operatorId = op, operatorName = "Op $op",
        latitude = 55.7 + km / 111.2, longitude = 13.2, maxPowerKw = kw, usageCostRaw = cost,
    )

    private fun corridor(vararg s: ChargerStation) = s.map {
        val p = route.locate(it.point)
        CorridorCharger(it, p.distanceAlongM, p.offsetM)
    }

    private fun flatEnergy(kwhPer100: Double = 16.0) = EnergyModel.buildProfile(
        route, kwhPer100, fiat.vehicleMassKg.toDouble(), route.sample(EnergyModel.SAMPLE_STEP_M), null,
    ) { null }

    @Test
    fun routeLengthIsAboutRight() {
        assertEquals(300_000.0, route.totalM, 1_500.0)
    }

    @Test
    fun shortTripNeedsNoStop() {
        val short = RouteGeometry((0..5).map { GeoPoint(55.7 + it * 0.09, 13.2) }) // ~50 km
        val energy = EnergyModel.buildProfile(short, 16.0, 1465.0, short.sample(5_000.0), null) { null }
        val plan = StopPlanner.plan(short, energy, 80.0, fiat, PlannerSettings(), emptyList(), emptyList()) { null }
        assertTrue(plan.stops.isEmpty())
        // 50 km × 16 kWh/100 km = 8 kWh = 21.4 % of 37.3 kWh → about 58.6 % left.
        assertEquals(58.6, plan.arrivalSocPercent, 0.6)
        assertFalse(plan.unreachable)
    }

    @Test
    fun longTripSuggestsAStopInTheWindow() {
        val chargers = corridor(station("a", 40.0), station("b", 160.0), station("c", 200.0))
        val plan = StopPlanner.plan(route, flatEnergy(), 90.0, fiat, PlannerSettings(), chargers, emptyList()) { null }
        assertEquals(1, plan.suggestions.size)
        val stop = plan.suggestions.first().plan
        // 90 % → 25 % after ~152 km, → 10 % after ~186 km: "b" at 160 km is in that window,
        // "c" at 200 km is out of reach. From "b" at 80 % the last 140 km leave about 20 %.
        assertEquals("b", stop.station.id)
        assertTrue(stop.arrivalSocPercent > 10f)
        assertEquals(80f, stop.targetSocPercent, 0.01f)
        assertTrue(plan.arrivalSocPercent >= 10.0)
        assertTrue(plan.arrivalWithAddedOnlyPercent < 10.0)
    }

    @Test
    fun preferredOperatorWinsInsideTheWindow() {
        val chargers = corridor(station("x", 160.0, op = 1), station("y", 165.0, op = 2))
        val settings = PlannerSettings(preferredOperatorIds = setOf(1))
        val plan = StopPlanner.plan(route, flatEnergy(), 90.0, fiat, settings, chargers, emptyList()) { null }
        assertEquals("x", plan.suggestions.first().plan.station.id)
        assertEquals("y", plan.suggestions.first().alternatives.first().station.id)
    }

    @Test
    fun onlyModeIgnoresOtherOperators() {
        val chargers = corridor(station("x", 160.0, op = 1), station("y", 165.0, op = 2))
        val settings = PlannerSettings(preferredOperatorIds = setOf(2), operatorMode = OperatorMode.ONLY)
        val plan = StopPlanner.plan(route, flatEnergy(), 90.0, fiat, settings, chargers, emptyList()) { null }
        assertTrue(plan.stops.flatMap { listOf(it.plan) + it.alternatives }.all { it.station.operatorId == 2 })
    }

    @Test
    fun addedStopIsUsedAndNothingMoreIsSuggested() {
        val b = station("b", 160.0)
        val chargers = corridor(b, station("c", 200.0))
        val plan = StopPlanner.plan(route, flatEnergy(), 90.0, fiat, PlannerSettings(), chargers, corridor(b)) { null }
        assertEquals(1, plan.stops.size)
        assertTrue(plan.stops.first().isAdded)
        assertTrue(plan.suggestions.isEmpty())
        assertTrue(plan.arrivalSocPercent >= 10.0)
    }

    @Test
    fun noChargerInReachIsReported() {
        val plan = StopPlanner.plan(route, flatEnergy(), 50.0, fiat, PlannerSettings(), corridor(station("far", 280.0)), emptyList()) { null }
        assertTrue(plan.unreachable)
    }

    @Test
    fun coldBatteryChargesSlowerAndIsMarked() {
        val chargers = corridor(station("b", 160.0))
        val warm = StopPlanner.plan(route, flatEnergy(), 90.0, fiat, PlannerSettings(), chargers, emptyList()) { 20.0 }
        val cold = StopPlanner.plan(route, flatEnergy(), 90.0, fiat, PlannerSettings(), chargers, emptyList()) { -5.0 }
        val w = warm.suggestions.first().plan
        val c = cold.suggestions.first().plan
        assertFalse(w.isColdgateAdjusted)
        assertTrue(c.isColdgateAdjusted)
        assertTrue(c.estimatedChargeMinutes > w.estimatedChargeMinutes)
        assertTrue(cold.anyColdAdjusted)
    }

    @Test
    fun chargeTimeFollowsTheCurve() {
        // 10 → 80 %: 40 % at 85 kW + 30 % at 55 kW on 37.3 kWh ≈ 10.5 + 12.2 = 22.7 min.
        val m = ChargeTime.minutes(fiat.chargeCurve, 37.3, 10.0, 80.0, 150.0, 1.0)
        assertEquals(22.7, m, 0.3)
        // A 50 kW charger caps the first step.
        val slow = ChargeTime.minutes(fiat.chargeCurve, 37.3, 10.0, 80.0, 50.0, 1.0)
        assertTrue(slow > m)
    }

    @Test
    fun coldFactorMatchesDecisionD3() {
        assertEquals(1.0, ChargeTime.coldFactor(15.0, 15.0), 1e-9)
        assertEquals(0.75, ChargeTime.coldFactor(7.5, 15.0), 1e-9)
        assertEquals(0.5, ChargeTime.coldFactor(0.0, 15.0), 1e-9)
        assertEquals(0.5, ChargeTime.coldFactor(-10.0, 15.0), 1e-9)
        assertEquals(1.0, ChargeTime.coldFactor(null, 15.0), 1e-9)
    }

    @Test
    fun weatherAddsTenToTwentyFivePercent() {
        assertEquals(0.0, EnergyModel.weatherExtra(WeatherSnapshot(10.0, 2.0, 0.0, 0.0), 0.0), 1e-9)
        assertEquals(0.10, EnergyModel.weatherExtra(WeatherSnapshot(1.9, 0.0, 0.0, 0.0), 0.0), 0.01)
        // −10 °C, snow, 12 m/s headwind from the north while driving north → capped at 25 %.
        assertEquals(0.25, EnergyModel.weatherExtra(WeatherSnapshot(-10.0, 12.0, 0.0, 1.0), 0.0), 1e-9)
        // Same wind from behind is not a headwind.
        assertEquals(0.20, EnergyModel.weatherExtra(WeatherSnapshot(-10.0, 12.0, 180.0, 1.0), 0.0), 1e-9)
    }

    @Test
    fun climbingCostsMoreThanDescendingGivesBack() {
        val up = EnergyModel.elevationKwh(1465.0, 100.0)
        val down = EnergyModel.elevationKwh(1465.0, -100.0)
        assertTrue(up > 0 && down < 0 && up > -down)
    }

    @Test
    fun priceChainOrder() {
        val s = station("p", 10.0, op = 7, cost = "5,95 kr/kWh")
        assertEquals(PriceSource.SUBSCRIPTION, ChainedTariffProvider.price(s, PriceSettings(subscriptionPrices = mapOf(7 to 3.9f))).source)
        assertEquals(5.95f, ChainedTariffProvider.price(s, PriceSettings(operatorDefaultPrices = mapOf(7 to 4f))).perKwh, 0.001f)
        val noCost = s.copy(usageCostRaw = null)
        assertEquals(PriceSource.OPERATOR_DEFAULT, ChainedTariffProvider.price(noCost, PriceSettings(operatorDefaultPrices = mapOf(7 to 4f))).source)
        val p = ChainedTariffProvider.price(noCost, PriceSettings())
        assertEquals(PriceSource.GLOBAL_DEFAULT, p.source)
        assertEquals(5.50f, p.perKwh, 0.001f)
    }

    @Test
    fun usageCostParser() {
        assertEquals(5.95f, OcmUsageCostParser.pricePerKwhSek("5,95 kr/kWh")!!, 0.001f)
        assertEquals(6.5f, OcmUsageCostParser.pricePerKwhSek("Ad hoc: 6.50 SEK per kWh")!!, 0.001f)
        assertEquals(4.95f, OcmUsageCostParser.pricePerKwhSek("SEK 4.95/kWh, 0,50 kr/min after 60 min")!!, 0.001f)
        assertEquals(3.75f, OcmUsageCostParser.pricePerKwhSek("kWh: 3,75 kr")!!, 0.001f)
        assertEquals(0f, OcmUsageCostParser.pricePerKwhSek("Free")!!, 0.001f)
        assertNull(OcmUsageCostParser.pricePerKwhSek("€0.59/kWh"))
        assertNull(OcmUsageCostParser.pricePerKwhSek("Pay at location"))
        assertNull(OcmUsageCostParser.pricePerKwhSek(null))
    }

    @Test
    fun polylineMatchesGoogleReference() {
        val pts = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(pts))
    }

    @Test
    fun simplifyKeepsEndsAndDropsStraightPoints() {
        val simplified = PolylineCodec.simplify(route.points, 100.0)
        assertEquals(2, simplified.size)
        assertEquals(route.start, simplified.first())
        assertEquals(route.end, simplified.last())
    }

    @Test
    fun locateFindsOffset() {
        val p = route.locate(GeoPoint(56.6, 13.25)) // ~3.1 km east of the line
        assertEquals(100_000.0, p.distanceAlongM, 1_000.0)
        assertEquals(3_100.0, p.offsetM, 200.0)
    }
}
