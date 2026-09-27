package com.aisan.radio.ui

import com.aisan.radio.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationSortingTest {

    private fun station(id: String, freqKhz: Double, lat: Double?, lon: Double?, powerKw: Double? = null) = Station(
        id = id, band = "FM", freq_khz = freqKhz, name = id, area = null, callsign = null, network = null,
        language = null, target_area = null, note = null, power_kw = powerKw, lat = lat, lon = lon,
        prefecture = null, category = "other", role = null
    )

    private val tokyo = GeoPoint(35.6812, 139.7671)

    @Test
    fun distanceBetweenTokyoAndOsaka() {
        val osaka = GeoPoint(34.7025, 135.4959)
        assertEquals(403.0, distanceKm(tokyo, osaka), 5.0)
    }

    @Test
    fun distanceToSamePointIsZero() {
        assertEquals(0.0, distanceKm(tokyo, tokyo), 1e-9)
    }

    @Test
    fun stationWithoutCoordinatesHasNoDistance() {
        assertNull(station("x", 82500.0, null, null).distanceKmFrom(tokyo))
    }

    @Test
    fun nearerStationComesFirstOnSameFrequency() {
        val sapporo = station("sapporo", 82500.0, 43.0621, 141.3544, powerKw = 5.0)
        val nagoya = station("nagoya", 82500.0, 35.1815, 136.9066, powerKw = 10.0)
        val yokohama = station("yokohama", 82500.0, 35.4437, 139.6380, powerKw = 0.1)

        val sorted = sortByDistance(listOf(sapporo, nagoya, yokohama), 82500.0, tokyo)

        assertEquals(listOf("yokohama", "nagoya", "sapporo"), sorted.map { it.id })
    }

    @Test
    fun exactFrequencyComesBeforeNearerStationOnNeighbouringFrequency() {
        val nearButOff = station("near", 9745.0, 35.7, 139.8)
        val farButExact = station("far", 9750.0, 39.9, 116.4)

        val sorted = sortByDistance(listOf(nearButOff, farButExact), 9750.0, tokyo)

        assertEquals(listOf("far", "near"), sorted.map { it.id })
    }

    @Test
    fun stationsWithoutCoordinatesComeLast() {
        val unknown = station("unknown", 82500.0, null, null, powerKw = 100.0)
        val known = station("known", 82500.0, 43.0621, 141.3544, powerKw = 0.1)

        val sorted = sortByDistance(listOf(unknown, known), 82500.0, tokyo)

        assertEquals(listOf("known", "unknown"), sorted.map { it.id })
    }

    @Test
    fun samePlaceIsOrderedByPower() {
        val weak = station("weak", 82500.0, 35.0, 139.0, powerKw = 0.1)
        val strong = station("strong", 82500.0, 35.0, 139.0, powerKw = 10.0)

        val sorted = sortByDistance(listOf(weak, strong), 82500.0, tokyo)

        assertEquals(listOf("strong", "weak"), sorted.map { it.id })
    }
}
