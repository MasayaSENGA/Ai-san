package com.aisan.radio.ui

enum class ListSortOrder(val label: String) {
    PREFECTURE("都道府県順"),
    // 漢字は読み順に並ばないので「五十音順」ではなく「局名順」
    NAME("局名順"),
    POWER("出力の大きい順"),
    DISTANCE("現在地から近い順"),
}

// 現在地が分からなければ「現在地から近い順」の代わりに都道府県順で並べる
fun effectiveSort(requested: ListSortOrder, origin: GeoPoint?): ListSortOrder =
    if (requested == ListSortOrder.DISTANCE && origin == null) ListSortOrder.PREFECTURE else requested

private val StationEntry.roleRank: Int get() = role?.rank ?: 3

// 同じ都道府県・同じ出力などの中は、親局 → 種類 → バンド → 周波数 → id の順(並びが毎回同じになるよう最後は id)
private val tieBreaker: Comparator<StationEntry> =
    compareBy<StationEntry> { it.roleRank }
        .thenBy { it.category.ordinal }
        .thenBy { it.band.ordinal }
        .thenBy(nullsLast()) { it.station.freq_khz }
        .thenBy { it.station.id }

fun sortEntries(entries: List<StationEntry>, sort: ListSortOrder, origin: GeoPoint?): List<StationEntry> =
    when (sort) {
        ListSortOrder.PREFECTURE -> entries.sortedWith(compareBy<StationEntry> { it.prefectureIndex }.then(tieBreaker))
        ListSortOrder.NAME -> entries.sortedWith(
            compareBy<StationEntry> { it.nameKey }.thenBy { it.prefectureIndex }.then(tieBreaker)
        )
        ListSortOrder.POWER -> entries.sortedWith(
            compareByDescending<StationEntry, Double?>(nullsFirst()) { it.station.power_kw }
                .thenBy { it.prefectureIndex }
                .then(tieBreaker)
        )
        ListSortOrder.DISTANCE -> if (origin == null) {
            sortEntries(entries, ListSortOrder.PREFECTURE, null)
        } else {
            val distances = entries.associateWith { it.station.distanceKmFrom(origin) }
            entries.sortedWith(
                compareBy<StationEntry, Double?>(nullsLast()) { distances[it] }
                    .thenByDescending { it.station.power_kw ?: -1.0 }
                    .thenBy { it.station.id }
            )
        }
    }

sealed interface ListRow {
    val key: String

    data class Header(val label: String, val count: Int) : ListRow {
        override val key get() = "h:$label"
    }

    data class Item(val entry: StationEntry) : ListRow {
        override val key get() = entry.station.id
    }
}

// 都道府県順のときだけ、都道府県ごとの見出しを入れる(国際放送は最後にまとめる)
fun toRows(sorted: List<StationEntry>, sort: ListSortOrder): List<ListRow> {
    if (sort != ListSortOrder.PREFECTURE) return sorted.map { ListRow.Item(it) }
    val rows = mutableListOf<ListRow>()
    var start = 0
    while (start < sorted.size) {
        val prefecture = sorted[start].prefecture
        var end = start
        while (end < sorted.size && sorted[end].prefecture == prefecture) end++
        rows += ListRow.Header(prefecture ?: "国際放送", end - start)
        for (i in start until end) rows += ListRow.Item(sorted[i])
        start = end
    }
    return rows
}

data class StationListResult(
    val rows: List<ListRow>,
    val matchCount: Int,
    val effectiveSort: ListSortOrder,
    val counts: FacetCounts,
)

fun queryStations(
    entries: List<StationEntry>,
    filter: StationFilter,
    sort: ListSortOrder,
    origin: GeoPoint?,
    languageOptions: List<String>,
): StationListResult {
    val tokens = keywordTokens(filter.keyword)
    val keywordMatched = if (tokens.isEmpty()) entries else entries.filter { it.matchesKeyword(tokens) }
    val matched = keywordMatched.filter { filter.matchesFacets(it) }
    val effective = effectiveSort(sort, origin)
    val sorted = sortEntries(matched, effective, origin)
    return StationListResult(
        rows = toRows(sorted, effective),
        matchCount = matched.size,
        effectiveSort = effective,
        counts = facetCounts(keywordMatched, filter, languageOptions),
    )
}
