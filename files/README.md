# 周波数→局名 データベース(Android組み込み用)

## 中身
- `jp_am_fm_stations.json` … 日本AM/FM主要局(NHK+全国47都道府県の広域民放主要局、計171局)。
- `community_fm_stations.json` … 日本国内のコミュニティFM局(343局)。
- `sw_stations.json` … 短波(SW)の主要国際放送局(19局、現行シーズンのスケジュールに基づく)。
- `radio_stations.db` … 上記3つを統合したSQLite(単一テーブル `stations`、計533局)。Androidにそのまま同梱可能。

## データの出所
- **NHK・広域民放主要局**: Wikipedia各局記事、総務省 電波利用ホームページの無線局等情報検索を突き合わせて収録。2026年3月30日のNHK再編(ラジオ第1→NHK AM、NHK-FM→NHK FM、ラジオ第2は3月29日廃止)に対応。
- **コミュニティFM**: Wikipedia「コミュニティ放送局一覧」の生データ(wikitext/HTML)を直接パースして機械的に抽出。一部地域は総務省の各総合通信局の無線局情報でも裏取り。「廃局」表記の局は除外済み。
- **短波(SW)**: EiBi Shortwave Schedules の BC A26(2026年3月29日〜10月25日有効)を基に、主要国際放送局19局を収録。短波は季節(A/B)ごとにスケジュールが変わるため、定期的な再取り込みが必要。

## 既知の制約
- コミュニティFMは開局・廃局の入れ替わりが多く、時間経過とともに情報が古くなります。定期的な再クロールを推奨します。
- 短波はA26シーズン(2026年10月25日まで)のデータです。それ以降はB26シーズンのスケジュールへの更新が必要です。
- 総務省実証実験でAM放送の段階的停波が進行中の地域があり、一部局はnoteに「要検証」と記載しています。

## データを拡張する方法
`stations` テーブルのスキーマは共通なので、追加データも同じ列構成(`id, band, freq_khz, name, area, callsign, network, language, target_area, note`)に変換して `INSERT` すれば拡張できます。

| 対象 | 拡張元 | 備考 |
|---|---|---|
| 短波(SW)の季節更新 | EiBi Shortwave Schedules (eibispace.de)、Aoki List | シーズン切り替え(3月末・10月末頃)ごとに再取り込み |
| コミュニティFMの新規開局・廃局反映 | Wikipedia「コミュニティ放送局一覧」、総務省 電波利用ホームページ | 定期的な差分更新 |

## Androidでの使い方(Room推奨)
1. `radio_stations.db` を `app/src/main/assets/database/radio_stations.db` に配置
2. Roomの `createFromAsset` で読み込み専用DBとして開く

```kotlin
@Entity(
    tableName = "stations",
    indices = [Index(value = ["freq_khz"], name = "idx_freq"), Index(value = ["band"], name = "idx_band")]
)
data class Station(
    @PrimaryKey val id: String,
    val band: String,
    val freq_khz: Double?,
    val name: String,
    val area: String?,
    val callsign: String?,
    val network: String?,
    val language: String?,
    val target_area: String?,
    val note: String?
)

@Dao
interface StationDao {
    // AM/FMはピッタリ一致、ユーザー入力の誤差を考慮してトレランス幅で検索
    @Query("SELECT * FROM stations WHERE band = :band AND freq_khz BETWEEN :freq - :tolerance AND :freq + :tolerance")
    suspend fun findByFrequency(band: String, freq: Double, tolerance: Double = 5.0): List<Station>
}

val db = Room.databaseBuilder(context, AppDatabase::class.java, "radio_stations.db")
    .createFromAsset("database/radio_stations.db")
    .build()
```

- FM入力(例: 82.5)は `freq_khz` に変換(×1000)してから検索してください。
- AM入力(例: 954)はそのままkHzとして検索できます。
- SWも同じ`freq_khz`列で検索可能です(例: 9750kHz)。

> 注意: SQLiteは `id TEXT PRIMARY KEY` だけでは自動的にNOT NULL制約を付けません。Roomは主キー列がNOT NULLであることを要求するため、本DBでは `id TEXT PRIMARY KEY NOT NULL` として作成しています。
