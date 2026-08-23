# 周波数→局名 データベース(Android組み込み用)

## 中身
- `jp_am_fm_stations.json` … 日本AM/FM主要局(NHK+関東・関西・中京キー局、計28局)。元データ。
- `sw_stations.json` … 短波(SW)のスキーマ確認用サンプル(2件のみ、正確性は保証なし)。
- `radio_stations.db` … 上記2つを統合したSQLite(単一テーブル `stations`)。Androidにそのまま同梱可能。

## なぜ「主要局のみ」なのか
- 日本のAM/FM/コミュニティFMは実在するだけで300局超あり、私(Claude)の知識だけで全件を正確に生成すると周波数の誤りが混ざるリスクがあります。
- 短波は季節ごとにスケジュールが変わり、世界で数千件規模のため「今この瞬間の正解」を私が固定データとして生成するのは不正確になります。

このため、**確度の高い主要局だけを先に収録**し、残りは以下の方法で拡張することをおすすめします。

## データを拡張する方法
| 対象 | 拡張元 | 備考 |
|---|---|---|
| 日本AM/FM全局(コミュニティFM含む) | 総務省 電波利用ホームページの無線局等情報検索、または radiko.jp の局一覧API相当のページ | 都道府県ごとにクロールしてJSONに追記 |
| 短波(SW) | EiBi Shortwave Schedules (eibispace.de)、Aoki List | 季節スケジュール更新のたびに再取り込みするバッチを組むのが理想 |

`stations` テーブルのスキーマは共通なので、追加データも同じ列構成(`id, band, freq_khz, name, area, callsign, network, language, target_area, note`)に変換して `INSERT` すれば拡張できます。

## Androidでの使い方(Room推奨)
1. `radio_stations.db` を `app/src/main/assets/database/radio_stations.db` に配置
2. Roomの `createFromAsset` で読み込み専用DBとして開く

```kotlin
@Entity(tableName = "stations")
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
    // AM/FMはピッタリ一致、ユーザー入力の誤差を考慮して±0.05MHz(=50kHz)幅で検索
    @Query("SELECT * FROM stations WHERE band = :band AND freq_khz BETWEEN :freq - :tolerance AND :freq + :tolerance")
    fun findByFrequency(band: String, freq: Double, tolerance: Double = 5.0): List<Station>
}

val db = Room.databaseBuilder(context, AppDatabase::class.java, "radio_stations.db")
    .createFromAsset("database/radio_stations.db")
    .build()
```

- FM入力(例: 82.5)は `freq_khz` に変換(×1000)してから検索してください。
- AM入力(例: 954)はそのままkHzとして検索できます。
- SWも同じ`freq_khz`列で検索可能です(例: 9750kHz)。

## 次のステップの提案
- まず関東・関西・中京以外(札幌・仙台・広島・福岡など)のNHK局・準キー局を追加するか
- コミュニティFMまで含めた完全版を目指すか
- 短波はどの程度の粒度(主要国際放送局のみ/全件)が必要か

方針が決まれば、該当範囲のデータを検索で裏取りしながら追加していきます。
