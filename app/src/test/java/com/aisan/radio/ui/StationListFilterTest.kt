package com.aisan.radio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationListFilterTest {

    private fun ids(filter: StationFilter, entries: List<StationEntry>): List<String> =
        queryStations(entries, filter, ListSortOrder.PREFECTURE, null, languageOptions(entries))
            .rows.filterIsInstance<ListRow.Item>().map { it.entry.station.id }.sorted()

    @Test
    fun normalizationFoldsWidthCaseAndKana() {
        assertEquals("nhk", normalizeForSearch("ＮＨＫ"))
        assertEquals(normalizeForSearch("えふえむ"), normalizeForSearch("エフエム"))
        assertEquals(listOf("nhk", "fm"), keywordTokens("ＮＨＫ　ＦＭ"))
        assertEquals(emptyList<String>(), keywordTokens("   "))
    }

    @Test
    fun keywordMatchesCallsignPrefectureRoleAndNote() {
        val entries = entriesOf(
            testStation("tbs", callsign = "JOKR", area = "東京", prefecture = "東京都"),
            testStation("wide", role = "fm_complement", prefecture = "東京都", area = "東京都墨田区"),
            testStation("stop", note = "AM放送を運用休止中", prefecture = "大阪府", area = "大阪"),
        )
        assertEquals(listOf("tbs"), ids(StationFilter(keyword = "jokr"), entries))
        assertEquals(listOf("tbs", "wide"), ids(StationFilter(keyword = "東京都"), entries))
        assertEquals(listOf("wide"), ids(StationFilter(keyword = "ワイドFM"), entries))
        assertEquals(listOf("stop"), ids(StationFilter(keyword = "休止"), entries))
        assertEquals(3, ids(StationFilter(keyword = " "), entries).size)
    }

    @Test
    fun keywordUsesCategoryNameButNotMenuHint() {
        val entries = entriesOf(
            testStation("gap", name = "受信障害対策中継局", category = "other", role = "relay", prefecture = "兵庫県"),
            testStation("disaster", name = "臨時災害放送局(輪島市)", category = "other", prefecture = "石川県"),
            testStation("afn", name = "AFN Tokyo", category = "afn", network = "AFN", prefecture = "埼玉県"),
        )
        // メニューの説明「その他(臨時災害放送局など)」では一致させない
        assertEquals(listOf("disaster"), ids(StationFilter(keyword = "臨時災害"), entries))
        assertEquals(listOf("afn"), ids(StationFilter(keyword = "在日米軍"), entries))
        assertEquals(listOf("disaster", "gap"), ids(StationFilter(keyword = "その他"), entries))
    }

    @Test
    fun keywordTokenDoesNotSpanTwoFields() {
        val entries = entriesOf(testStation("x", name = "abc", callsign = "DEF", prefecture = "東京都"))
        assertTrue(ids(StationFilter(keyword = "abc def"), entries).isNotEmpty())
        assertTrue(ids(StationFilter(keyword = "cde"), entries).isEmpty())
    }

    @Test
    fun areaFilterByPrefectureAndRegion() {
        val entries = entriesOf(
            testStation("tokyo", prefecture = "東京都"),
            testStation("shizuoka", prefecture = "静岡県"),
            testStation("intl", category = "international", role = null, prefecture = null),
        )
        val kanto = REGIONS.first { it.name == "関東" }
        assertEquals(listOf("tokyo"), ids(StationFilter(area = AreaFilter.InPrefecture("東京都")), entries))
        assertEquals(listOf("tokyo"), ids(StationFilter(area = AreaFilter.InRegion(kanto)), entries))
        assertEquals(listOf("intl", "shizuoka", "tokyo"), ids(StationFilter(), entries))
    }

    @Test
    fun regionsCoverEveryPrefectureOnce() {
        assertEquals(PREFECTURES, REGIONS.flatMap { it.prefectures })
        // すべて + 北海道 + (7地方 + その46都道府県)
        assertEquals(55, areaOptions().size)
    }

    @Test
    fun networkFacetIncludesCrossNetStations() {
        val entries = entriesOf(
            testStation("jrn", network = "JRN"),
            testStation("both", network = "JRN/NRN"),
            testStation("nrn", network = "NRN"),
            testStation("indie", network = "independent"),
            testStation("nhk", network = "NHK", category = "nhk"),
        )
        assertEquals(listOf("both", "jrn"), ids(StationFilter(network = NetworkFacet.JRN), entries))
        assertEquals(listOf("both", "nrn"), ids(StationFilter(network = NetworkFacet.NRN), entries))
        assertEquals(listOf("indie"), ids(StationFilter(network = NetworkFacet.INDEPENDENT), entries))
    }

    @Test
    fun transmitterFilter() {
        val entries = entriesOf(
            testStation("main", role = "main"),
            testStation("relay", role = "relay"),
            testStation("wide", role = "fm_complement"),
            testStation("intl", category = "international", role = null),
        )
        assertEquals(listOf("intl", "main"), ids(StationFilter(transmitter = TransmitterFilter.EXCLUDE_RELAYS), entries))
        assertEquals(listOf("wide"), ids(StationFilter(transmitter = TransmitterFilter.FM_COMPLEMENT_ONLY), entries))
    }

    @Test
    fun languageFacetMatchesWholeToken() {
        val entries = entriesOf(
            testStation("ja", category = "international", role = null, language = "日本語、英語"),
            testStation("zh", category = "international", role = null, language = "中国語(普通話)"),
        )
        val filter = StationFilter(category = StationCategory.INTERNATIONAL, language = "英語")
        assertEquals(listOf("ja"), ids(filter, entries))
        assertEquals(listOf("日本語", "英語", "中国語(普通話)"), languageOptions(entries))
    }

    @Test
    fun normalizedClearsFacetsThatDoNotApply() {
        val intl = StationFilter(
            category = StationCategory.INTERNATIONAL,
            area = AreaFilter.InPrefecture("東京都"),
            transmitter = TransmitterFilter.EXCLUDE_RELAYS,
            network = NetworkFacet.JRN,
        ).normalized()
        assertEquals(AreaFilter.All, intl.area)
        assertEquals(TransmitterFilter.ALL, intl.transmitter)
        assertNull(intl.network)

        val nhk = StationFilter(category = StationCategory.NHK, network = NetworkFacet.JRN, language = "英語").normalized()
        assertNull(nhk.network)
        assertNull(nhk.language)

        val commercial = StationFilter(category = StationCategory.COMMERCIAL, network = NetworkFacet.JFN).normalized()
        assertEquals(NetworkFacet.JFN, commercial.network)
    }

    @Test
    fun isActiveAndAndAcrossFacets() {
        assertFalse(StationFilter().isActive)
        assertTrue(StationFilter(keyword = "a").isActive)
        val entries = entriesOf(
            testStation("am-tokyo", band = "AM", freqKhz = 954.0, prefecture = "東京都"),
            testStation("fm-tokyo", prefecture = "東京都"),
            testStation("am-osaka", band = "AM", freqKhz = 1008.0, prefecture = "大阪府"),
        )
        val filter = StationFilter(band = Band.AM, area = AreaFilter.InPrefecture("東京都"))
        assertEquals(listOf("am-tokyo"), ids(filter, entries))
    }

    @Test
    fun facetCountsEqualResultCountAfterChoosingTheOption() {
        val entries = entriesOf(
            testStation("tokyo-nhk", category = "nhk", network = "NHK", prefecture = "東京都"),
            testStation("tokyo-com", prefecture = "東京都", network = "JRN"),
            testStation("osaka-com", prefecture = "大阪府", network = "NRN"),
            testStation("intl", category = "international", role = null, language = "日本語"),
        )
        val filter = StationFilter(area = AreaFilter.InPrefecture("東京都"))
        val result = queryStations(entries, filter, ListSortOrder.PREFECTURE, null, languageOptions(entries))
        for ((category, count) in result.counts.category) {
            val chosen = filter.copy(category = category).normalized()
            val expected = queryStations(entries, chosen, ListSortOrder.PREFECTURE, null, emptyList()).matchCount
            assertEquals("category $category", expected, count)
        }
        // 国際放送を選ぶと地域の条件は外れるので、地域に関係なく数える
        assertEquals(1, result.counts.category[StationCategory.INTERNATIONAL])
        assertEquals(2, result.counts.area[AreaFilter.InPrefecture("東京都")])
    }

    @Test
    fun dbValueMapping() {
        assertEquals(StationCategory.OTHER, StationCategory.fromDbValue("x"))
        assertEquals(StationCategory.COMMUNITY, StationCategory.fromDbValue("community"))
        assertNull(StationRole.fromDbValue(null))
        assertEquals(StationRole.RELAY, StationRole.fromDbValue("unknown"))
        assertEquals(Band.SW, Band.fromDbValue("SW"))
        assertNull(Band.fromDbValue("LW"))
    }
}
