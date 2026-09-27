package com.aisan.radio.ui

import com.aisan.radio.data.Station
import java.text.CollationKey
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

// files/scripts/stationdata.py の PREFECTURES と同じ(都道府県コード順)
val PREFECTURES: List<String> = listOf(
    "北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県", "茨城県", "栃木県", "群馬県",
    "埼玉県", "千葉県", "東京都", "神奈川県", "新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県",
    "岐阜県", "静岡県", "愛知県", "三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県",
    "鳥取県", "島根県", "岡山県", "広島県", "山口県", "徳島県", "香川県", "愛媛県", "高知県", "福岡県",
    "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県",
)

data class Region(val name: String, val prefectures: List<String>) {
    val label: String get() = if (prefectures.size == 1) name else "${name}地方"
}

val REGIONS: List<Region> = listOf(
    Region("北海道", PREFECTURES.slice(0..0)),
    Region("東北", PREFECTURES.slice(1..6)),
    Region("関東", PREFECTURES.slice(7..13)),
    Region("中部", PREFECTURES.slice(14..22)),
    Region("近畿", PREFECTURES.slice(23..29)),
    Region("中国", PREFECTURES.slice(30..34)),
    Region("四国", PREFECTURES.slice(35..38)),
    Region("九州・沖縄", PREFECTURES.slice(39..46)),
)

sealed interface AreaFilter {
    val label: String
    val menuLabel: String get() = label

    data object All : AreaFilter {
        override val label = "すべて"
    }

    data class InRegion(val region: Region) : AreaFilter {
        override val label get() = region.label
        override val menuLabel get() = "${region.label}すべて"
    }

    data class InPrefecture(val prefecture: String) : AreaFilter {
        override val label get() = prefecture
    }
}

// 地域の選択肢: すべて、北海道、以降は地方ごとに「○○地方すべて」とその都道府県
fun areaOptions(): List<AreaFilter> = buildList {
    add(AreaFilter.All)
    for (region in REGIONS) {
        if (region.prefectures.size > 1) add(AreaFilter.InRegion(region))
        region.prefectures.forEach { add(AreaFilter.InPrefecture(it)) }
    }
}

// dbValue は files/scripts/stationdata.py の CATEGORIES と同じ。並び順は一覧の並べ替えにも使う
enum class StationCategory(val dbValue: String, val label: String, val menuLabel: String) {
    NHK("nhk", "NHK", "NHK"),
    COMMERCIAL("commercial", "民放", "民放"),
    COMMUNITY("community", "コミュニティFM", "コミュニティFM"),
    AFN("afn", "AFN", "AFN(在日米軍)"),
    INTERNATIONAL("international", "国際放送", "国際放送"),
    OTHER("other", "その他", "その他(臨時災害放送局など)");

    companion object {
        fun fromDbValue(value: String): StationCategory = entries.firstOrNull { it.dbValue == value } ?: OTHER
    }
}

// dbValue は files/scripts/stationdata.py の ROLES と同じ
enum class StationRole(val dbValue: String, val label: String, val rank: Int) {
    MAIN("main", "親局", 0),
    FM_COMPLEMENT("fm_complement", "FM補完中継局(ワイドFM)", 1),
    RELAY("relay", "中継局", 2);

    companion object {
        fun fromDbValue(value: String?): StationRole? =
            if (value == null) null else entries.firstOrNull { it.dbValue == value } ?: RELAY
    }
}

// 民放の系列。JRNとNRNの両方に加盟する局(JRN/NRN)はどちらにも含まれる
enum class NetworkFacet(val token: String, val label: String) {
    JRN("JRN", "JRN"),
    NRN("NRN", "NRN"),
    JFN("JFN", "JFN"),
    JFL("JFL", "JFL"),
    INDEPENDENT("independent", "独立局"),
}

enum class TransmitterFilter(val menuLabel: String, val chipLabel: String?) {
    ALL("すべて", null),
    EXCLUDE_RELAYS("中継局を除く", "中継局を除く"),
    FM_COMPLEMENT_ONLY("ワイドFM(FM補完中継局)のみ", "ワイドFMのみ"),
}

// 一覧の1件。絞り込みと並べ替えに使う値を読み込み時に一度だけ計算しておく
class StationEntry(
    val station: Station,
    val band: Band,
    val category: StationCategory,
    val role: StationRole?,
    val prefecture: String?,
    val prefectureIndex: Int, // 0..46。都道府県が無い(国際放送)ものは 47
    val networkTokens: Set<String>, // 民放のみ
    val languages: Set<String>, // 国際放送のみ
    val searchText: String,
    val nameKey: CollationKey,
)

fun networkLabel(station: Station, category: StationCategory): String? = when (category) {
    StationCategory.COMMERCIAL -> station.network?.let { if (it == "independent") "独立局" else it }
    StationCategory.INTERNATIONAL -> station.network
    else -> null
}

fun buildEntries(stations: List<Station>, collator: Collator): List<StationEntry> =
    stations.mapNotNull { station ->
        val band = Band.fromDbValue(station.band) ?: return@mapNotNull null
        val category = StationCategory.fromDbValue(station.category)
        val role = StationRole.fromDbValue(station.role)
        val prefectureIndex = PREFECTURES.indexOf(station.prefecture).let { if (it < 0) PREFECTURES.size else it }
        val searchFields = listOf(
            station.name, station.callsign, station.area, station.prefecture,
            station.network, networkLabel(station, category),
            // 種類はメニューの説明(「その他(臨時災害放送局など)」)ではなく、どの局にも当てはまる名前だけ入れる
            category.label, if (category == StationCategory.AFN) "在日米軍" else null, role?.label,
            station.language, station.target_area, station.note,
        )
        StationEntry(
            station = station,
            band = band,
            category = category,
            role = role,
            prefecture = station.prefecture,
            prefectureIndex = prefectureIndex,
            networkTokens = if (category == StationCategory.COMMERCIAL) {
                station.network?.split("/")?.toSet().orEmpty()
            } else {
                emptySet()
            },
            languages = if (category == StationCategory.INTERNATIONAL) {
                station.language?.split("、")?.filter { it.isNotBlank() }?.toSet().orEmpty()
            } else {
                emptySet()
            },
            // 1つのキーワードが2つの項目にまたがって一致しないよう、項目を改行で区切る
            searchText = searchFields.filterNotNull().joinToString("\n") { normalizeForSearch(it) },
            nameKey = collator.getCollationKey(station.name),
        )
    }

// 言語の選択肢: 日本語、英語、以降は件数の多い順
fun languageOptions(entries: List<StationEntry>): List<String> {
    val counts = entries.flatMap { it.languages }.groupingBy { it }.eachCount()
    val pinned = listOf("日本語", "英語").filter { it in counts }
    val rest = counts.keys.filter { it !in pinned }
        .sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { it })
    return pinned + rest
}

// キーワード検索用の正規化: 全角・半角をそろえ(NFKC)、小文字にし、カタカナをひらがなにする
fun normalizeForSearch(text: String): String {
    val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return buildString(folded.length) {
        for (c in folded) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }
}

// 空白区切りの各語をすべて含むものに絞る
fun keywordTokens(keyword: String): List<String> =
    normalizeForSearch(keyword).split(Regex("\\s+")).filter { it.isNotEmpty() }

fun StationEntry.matchesKeyword(tokens: List<String>): Boolean = tokens.all { it in searchText }

data class StationFilter(
    val keyword: String = "",
    val area: AreaFilter = AreaFilter.All,
    val category: StationCategory? = null,
    val transmitter: TransmitterFilter = TransmitterFilter.ALL,
    val band: Band? = null,
    val network: NetworkFacet? = null,
    val language: String? = null,
) {
    val isActive: Boolean get() = this != StationFilter()

    // 地域・送信所は国内局、系列は民放、言語は国際放送だけにある。選んだ種類で意味の無い条件は外す
    val showsArea: Boolean get() = category != StationCategory.INTERNATIONAL
    val showsTransmitter: Boolean get() = category != StationCategory.INTERNATIONAL
    val showsNetwork: Boolean get() = category == null || category == StationCategory.COMMERCIAL
    val showsLanguage: Boolean get() = category == StationCategory.INTERNATIONAL

    fun normalized(): StationFilter = copy(
        area = if (showsArea) area else AreaFilter.All,
        transmitter = if (showsTransmitter) transmitter else TransmitterFilter.ALL,
        network = if (showsNetwork) network else null,
        language = if (showsLanguage) language else null,
    )

    // キーワード以外の条件に合うか(キーワードは matchesKeyword で先に絞る)
    fun matchesFacets(e: StationEntry): Boolean {
        val areaMatches = when (area) {
            AreaFilter.All -> true
            is AreaFilter.InRegion -> e.prefecture != null && e.prefecture in area.region.prefectures
            is AreaFilter.InPrefecture -> e.prefecture == area.prefecture
        }
        val transmitterMatches = when (transmitter) {
            TransmitterFilter.ALL -> true
            // 国際放送は親局・中継局の区別が無いので残す
            TransmitterFilter.EXCLUDE_RELAYS -> e.role == null || e.role == StationRole.MAIN
            TransmitterFilter.FM_COMPLEMENT_ONLY -> e.role == StationRole.FM_COMPLEMENT
        }
        return areaMatches &&
            (category == null || e.category == category) &&
            transmitterMatches &&
            (band == null || e.band == band) &&
            (network == null || network.token in e.networkTokens) &&
            (language == null || language in e.languages)
    }
}

// 各選択肢を選んだときの件数(キーワードは適用したうえで数える)
data class FacetCounts(
    val area: Map<AreaFilter, Int> = emptyMap(),
    val category: Map<StationCategory?, Int> = emptyMap(),
    val transmitter: Map<TransmitterFilter, Int> = emptyMap(),
    val band: Map<Band?, Int> = emptyMap(),
    val network: Map<NetworkFacet?, Int> = emptyMap(),
    val language: Map<String?, Int> = emptyMap(),
)

fun facetCounts(keywordMatched: List<StationEntry>, filter: StationFilter, languageOptions: List<String>): FacetCounts {
    fun count(candidate: StationFilter): Int {
        val normalized = candidate.normalized()
        return keywordMatched.count { normalized.matchesFacets(it) }
    }
    return FacetCounts(
        area = areaOptions().associateWith { count(filter.copy(area = it)) },
        category = (listOf(null) + StationCategory.entries).associateWith { count(filter.copy(category = it)) },
        transmitter = TransmitterFilter.entries.associateWith { count(filter.copy(transmitter = it)) },
        band = (listOf(null) + Band.entries).associateWith { count(filter.copy(band = it)) },
        network = (listOf(null) + NetworkFacet.entries).associateWith { count(filter.copy(network = it)) },
        language = if (filter.showsLanguage) {
            (listOf(null) + languageOptions).associateWith { count(filter.copy(language = it)) }
        } else {
            emptyMap()
        },
    )
}
