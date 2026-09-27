package com.aisan.radio.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StationListSortTest {

    private fun sortedIds(entries: List<StationEntry>, sort: ListSortOrder, origin: GeoPoint? = null) =
        sortEntries(entries, sort, origin).map { it.station.id }

    @Test
    fun prefectureOrderIsNorthToSouthWithInternationalLast() {
        val entries = entriesOf(
            testStation("okinawa", prefecture = "沖縄県"),
            testStation("intl", category = "international", role = null, prefecture = null),
            testStation("aomori", prefecture = "青森県"),
            testStation("hokkaido", prefecture = "北海道"),
        )
        assertEquals(listOf("hokkaido", "aomori", "okinawa", "intl"), sortedIds(entries, ListSortOrder.PREFECTURE))
    }

    @Test
    fun withinPrefectureMainThenCategoryThenBandThenFrequency() {
        val entries = entriesOf(
            testStation("relay", prefecture = "東京都", role = "relay"),
            testStation("wide", prefecture = "東京都", role = "fm_complement", freqKhz = 90500.0),
            testStation("community", prefecture = "東京都", category = "community", network = "community"),
            testStation("fm", prefecture = "東京都", freqKhz = 80000.0),
            testStation("am", prefecture = "東京都", band = "AM", freqKhz = 954.0),
            testStation("nhk", prefecture = "東京都", category = "nhk", network = "NHK", band = "AM", freqKhz = 594.0),
        )
        assertEquals(
            listOf("nhk", "am", "fm", "community", "wide", "relay"),
            sortedIds(entries, ListSortOrder.PREFECTURE)
        )
    }

    @Test
    fun nameOrderGroupsSameNamesByPrefecture() {
        val entries = entriesOf(
            testStation("b-osaka", name = "B", prefecture = "大阪府"),
            testStation("a", name = "A", prefecture = "沖縄県"),
            testStation("b-tokyo", name = "B", prefecture = "東京都"),
        )
        assertEquals(listOf("a", "b-tokyo", "b-osaka"), sortedIds(entries, ListSortOrder.NAME))
    }

    @Test
    fun powerOrderIsDescendingWithUnknownLast() {
        val entries = entriesOf(
            testStation("unknown", powerKw = null),
            testStation("small", powerKw = 0.1),
            testStation("big", powerKw = 100.0),
        )
        assertEquals(listOf("big", "small", "unknown"), sortedIds(entries, ListSortOrder.POWER))
    }

    @Test
    fun distanceOrderFromTokyo() {
        val tokyo = GeoPoint(35.6812, 139.7671)
        val entries = entriesOf(
            testStation("sapporo", lat = 43.0621, lon = 141.3544),
            testStation("none", powerKw = 100.0),
            testStation("nagoya", lat = 35.1815, lon = 136.9066),
            testStation("yokohama-weak", lat = 35.4437, lon = 139.6380, powerKw = 0.1),
            testStation("yokohama-strong", lat = 35.4437, lon = 139.6380, powerKw = 10.0),
        )
        assertEquals(
            listOf("yokohama-strong", "yokohama-weak", "nagoya", "sapporo", "none"),
            sortedIds(entries, ListSortOrder.DISTANCE, tokyo)
        )
    }

    @Test
    fun distanceWithoutOriginFallsBackToPrefecture() {
        assertEquals(ListSortOrder.PREFECTURE, effectiveSort(ListSortOrder.DISTANCE, null))
        assertEquals(ListSortOrder.DISTANCE, effectiveSort(ListSortOrder.DISTANCE, GeoPoint(35.0, 139.0)))
        assertEquals(ListSortOrder.NAME, effectiveSort(ListSortOrder.NAME, null))
    }

    @Test
    fun headersOnlyForPrefectureOrder() {
        val entries = entriesOf(
            testStation("t1", prefecture = "東京都"),
            testStation("t2", prefecture = "東京都", freqKhz = 81000.0),
            testStation("intl", category = "international", role = null, prefecture = null),
            testStation("h", prefecture = "北海道"),
        )
        val rows = toRows(sortEntries(entries, ListSortOrder.PREFECTURE, null), ListSortOrder.PREFECTURE)
        val headers = rows.filterIsInstance<ListRow.Header>()
        assertEquals(listOf("北海道" to 1, "東京都" to 2, "国際放送" to 1), headers.map { it.label to it.count })
        assertEquals(7, rows.size)
        val byName = toRows(sortEntries(entries, ListSortOrder.NAME, null), ListSortOrder.NAME)
        assertEquals(4, byName.size)
    }

    @Test
    fun orderIsDeterministicById() {
        val entries = entriesOf(testStation("b"), testStation("a"), testStation("c"))
        for (sort in ListSortOrder.entries) {
            assertEquals(listOf("a", "b", "c"), sortedIds(entries, sort, GeoPoint(35.0, 139.0)))
        }
    }

    @Test
    fun queryStationsEndToEnd() {
        val entries = entriesOf(
            testStation("tokyo", prefecture = "東京都", powerKw = 10.0),
            testStation("osaka", prefecture = "大阪府", powerKw = 50.0),
            testStation("relay", prefecture = "大阪府", role = "relay", powerKw = 0.1),
        )
        val result = queryStations(
            entries,
            StationFilter(transmitter = TransmitterFilter.EXCLUDE_RELAYS),
            ListSortOrder.POWER,
            null,
            emptyList()
        )
        assertEquals(2, result.matchCount)
        assertEquals(ListSortOrder.POWER, result.effectiveSort)
        assertEquals(listOf("osaka", "tokyo"), result.rows.map { (it as ListRow.Item).entry.station.id })
    }
}
