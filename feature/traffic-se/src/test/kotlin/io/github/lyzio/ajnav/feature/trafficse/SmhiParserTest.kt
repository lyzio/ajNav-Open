package io.github.lyzio.ajnav.feature.trafficse

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmhiParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val body = """
        {"createdTime":"2026-11-26T05:00:00Z","timeSeries":[
          {"time":"2026-11-26T06:00:00Z","data":{"air_temperature":-3.5,"wind_speed":4.0,"wind_from_direction":270,"precipitation_amount_mean":0.0}},
          {"time":"2026-11-26T09:00:00Z","data":{"air_temperature":1.0,"wind_speed":12.0,"wind_from_direction":180,"precipitation_amount_mean":0.6}}
        ]}
    """.trimIndent()

    @Test
    fun picksTheHourClosestToTheRequestedTime() {
        val w = SmhiParser.parse(json, body, Instant.parse("2026-11-26T08:20:00Z"))!!
        assertEquals(1.0, w.temperatureC!!, 0.001)
        assertEquals(12.0, w.windSpeedMs!!, 0.001)
        assertEquals(0.6, w.precipitationMmH!!, 0.001)
    }

    @Test
    fun missingSeriesGivesNull() {
        assertNull(SmhiParser.parse(json, "{}", Instant.now()))
    }
}
