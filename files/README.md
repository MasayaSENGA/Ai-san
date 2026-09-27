# 周波数→局名 データベース(Android組み込み用)

## 中身

| ファイル | 内容 | 件数 | 作り方 |
|---|---|---|---|
| `jp_am_fm_stations.json` | NHK・民放の主要局(親局)。局名・系列・注記を手で整備 | 171 | 手で編集 |
| `community_fm_stations.json` | コミュニティFMの親局 | 343 | 手で編集 |
| `jp_sw_stations.json` | 国内の短波放送(ラジオNIKKEI第1・第2の7送信) | 7 | 手で編集 |
| `afn_stations.json` | 在日米軍AFNのラジオ局(AM・FM) | 6 | 手で編集 |
| `mic_stations.json` | 上記に無い国内の放送局(NHK・民放の中継局、FM補完中継局、コミュニティFMの中継局、受信障害対策中継局、臨時災害放送局、関東各県のNHK FMなど上記に無い親局) | 1,422 | `scripts/mic_import.py` で生成 |
| `intl_stations.json` | 日本で受信されやすい国際放送(日本語放送、日本・極東・朝鮮半島・中国向けの短波放送と中波の国際放送)。同じ周波数・同じ局でも送信所ごとに1件 | 510 | `scripts/eibi_import.py` で生成 |
| `municipality_coords.json` | 市区町村の代表点の座標(送信所の所在地を緯度経度に変換するための対照表) | 1,912市区町村 | `scripts/geo_import.py` で生成 |
| `radio_stations.db` | 局データのJSONを統合したSQLite(単一テーブル `stations`、計2,459局)。Androidにそのまま同梱 | 2,459 | `scripts/build_db.py` で生成 |

局データの各JSONの局オブジェクトは `stations` テーブルの先頭13列と同じ列構成です(残りの3列はDB生成時に導出。下記)。

```
id, band, freq_khz, name, area, callsign, network, language, target_area, note, power_kw, lat, lon
```

- `freq_khz`: AM/FM/SWとも kHz 単位(FMはMHz値×1000)
- `area`: 国内局は親局を都道府県名、中継局などを送信所の市区町村で表記。国際放送は `送信所: 地名(国)`
- `network`: `NHK` / `JRN` / `NRN` / `JRN/NRN` / `JFN` / `JFL` / `independent` / `community` / `AFN`。国際放送はEiBi上の局名
- `power_kw`: 送信出力(空中線電力、kW)。国内局は総務省の免許情報の値。国際放送は `null`
- `lat` / `lon`: 送信所の座標(アプリの「現在地から近い順」に使う)。国内局は免許情報の送信所の所在地(市区町村)の代表点、国際放送はEiBiの送信所の座標。不明な局は `null`(現在は全局に座標あり)
- `language`: 国際放送とAFNの放送言語(日本語で書く。`build_db.py` が英字だけの値を拒否する)
- 国際放送の `note` には放送時間(UTCと日本時間)・言語・対象地域を記載

### DB生成時に導出する列(`prefecture` / `category` / `role`)

放送局一覧の絞り込み用に、`build_db.py` の `derive()` が各JSONの内容から導出してDBに入れます(JSONには持たない)。

| 列 | 値 | 導出のしかた |
|---|---|---|
| `prefecture` | 都道府県(例: `東京都`)。国際放送は `NULL` | `area` から判定。主要局の「東京」のような短い名前にも対応。判定できない国内局があればDB生成を止める |
| `category` | `nhk` 812 / `commercial`(民放) 732 / `international` 510 / `community` 391 / `other` 8 / `afn` 6 | 国際放送は `international`。それ以外は `network` から(`NHK`→nhk、`community`→community、`AFN`→afn、JRN・NRN・JFN・JFL・independent→commercial、`null`→other(臨時災害放送局・中継元不明の受信障害対策中継局)) |
| `role` | `main` 553 / `relay` 1,220 / `fm_complement` 176 / `NULL` 510(国際放送) | 上から順に最初に当てはまるもの: 国際放送→`NULL`、AFN→`main`、`note` が「FM補完中継局」で始まる→`fm_complement`、コールサイン無し→`relay`、手で整備したファイルの局→`main`、総務省データのNHK・`network` 無し→`main`、総務省データの民放・コミュニティFMでコールサインがある局は、手で整備したファイルに同じ局名が無く、かつ同じ局名の中で出力が最大なら`main`(例: 栃木放送は鹿沼市のJOXF)、それ以外は`relay`(例: HBCラジオ 北見 JOQN) |

`mic_import.py` がFM補完中継局の `note` の先頭に書く「FM補完中継局」を変えるときは、`derive()` も直してください。
アプリの `StationCategory` / `StationRole` / `PREFECTURES` は `stationdata.py` の `CATEGORIES` / `ROLES` / `PREFECTURES` と一致させる必要があり、単体テスト(`ClassificationContractTest`)で確かめています。

## データの出所

- **国内のAM・FM・短波放送**: 総務省 電波利用ポータルの[無線局等情報検索 Web-API](https://www.tele.soumu.go.jp/j/musen/webapi/)で取得した「特定地上基幹放送局」の免許情報(2026年9月25日更新分)。周波数・コールサイン・送信所の所在地・空中線電力はこれに基づき、手で整備したファイルの局もすべて免許情報と照合済み。
  - 局名・系列は免許人ごとに対応表(`scripts/mic_import.py` の `LICENSEES`)で付けている。系列はWikipedia「全国ラジオネットワーク」「ジャパン・ラジオ・ネットワーク」「JAPAN FM LEAGUE」の加盟局一覧と照合。
  - AM局の免許人が持つFMの免許はFM補完中継局として扱う(NHKを除く)。
  - VICS(道路交通情報)のFM多重データ放送と、NHKワールド・ラジオ日本(八俣送信所)の免許は取り込まない(後者はEiBiで扱う)。
- **AMの運用休止**: 総務省「[AM局の運用休止に係る特例措置](https://www.soumu.go.jp/menu_seisaku/ictseisaku/housou_suishin/am_station.html)」の休止局一覧(第一期・第二期)。休止期間は2026年9月30日までで、延長の手続きが進んでいる。
- **ラジオNIKKEIの運用時間**: Wikipedia「日経ラジオ社」(2026年6月現在の運用体制)
- **AFN**: Wikipedia「AFN」(2026年1月時点の記述)。AFNは日米地位協定により電波法の適用外で、総務省の免許情報には含まれない。
- **国際放送**: [EiBi Shortwave Schedules](http://www.eibispace.de/) の A26 シーズン(2026年3月29日〜10月25日、2026年8月31日更新版)。2026年9月27日時点で有効な放送を取り込んだ。EiBiのデータは自由に利用・再配布できる。
  - ファクス・気象通報などの業務局、妨害電波、デジタル(DRM)放送、周波数を頻繁に変える希望之声(Sound of Hope)は除外。
- **コミュニティFMの局名**: Wikipedia「コミュニティ放送局一覧」
- **市区町村の座標**: [Wikidata](https://www.wikidata.org/)(CC0)の全国地方公共団体コード(P429)と座標(P625)。2026年9月27日に取得。座標が複数ある市区町村(76件)は、その中点を使う。
- **送信所の所在地**: 免許情報の「無線設備の設置場所」には送信所のほかに演奏所(本社)や予備送信所も並び、どれが先頭かは免許によって違う。設置場所が複数ある免許を2件以上持つ免許人は、それらすべてに現れる場所を演奏所とみなし、それ以外の最初の場所を送信所としている(例: MBSラジオは本社の大阪市北区ではなく高石市)。演奏所を決められない免許人(NHKや、設置場所が複数ある免許が1件だけの免許人)は最初の場所を使い、違うと確かめた局は `mic_import.py` の `TRANSMITTER_OVERRIDES` で直している(Kiss FM KOBE → 神戸市灘区、e-radio → 湖南市)。
- **国際放送の送信所の座標**: EiBi の README.TXT の送信所一覧(Transmitter site codes)

総務省のWeb-APIで取得した情報を使うサービスでは、利用規約により次の文言の表示が必要です(アプリの検索結果の下に表示しています)。

> このサービスは、総務省 電波利用ポータルのWeb-API 機能を利用して取得した情報をもとに作成しているが、サービスの内容は総務省によって保証されたものではない

## 既知の制約

- 短波の国際放送はA26シーズン(2026年10月25日まで)のデータです。10月25日以降はB26シーズンのスケジュールで作り直す必要があります(EiBiのB26版は2026年9月27日時点で未公開)。
- 民放AM局の運用休止は2026年9月30日までとされています。10月以降の扱い(延長・再開・廃止)が決まったら注記の更新が必要です。
- 受信障害対策中継局(自治体などが難聴地域向けに設ける中継局)は、同じ市区町村・都道府県に同じ周波数の局がある場合にその局の中継とみなしています。中継元を特定できないものは「受信障害対策中継局」として収録しています。
- 同じ市区町村・同じ周波数・同じ局名の送信所は1件にまとめています(件数は `note` に記載)。
- 放送局一覧の「地域」は `area` の都道府県です。`jp_am_fm_stations.json` の主要局は放送局の都道府県なので、送信所が埼玉県・千葉県にあるTBSラジオ・文化放送・ニッポン放送・NHK東京(AM)も東京都に入ります(送信所で探すときは「関東地方」を選んでください)。それ以外の局は送信所の都道府県なので、AFN Tokyo(横田基地の放送局)は送信所のある埼玉県、東京のラジオNIKKEIは千葉県(長柄)と北海道(根室のJOZ4)に入ります。コミュニティFMで放送エリアが複数の都県にまたがるもの(「静岡県熱海市・神奈川県湯河原町」)は、先頭の都県になります。
- 総務省データのうち手で整備したファイルに無い民放局は、同じ局名の中で出力が最大の送信所を親局としています(実際の親局と違う場合があります)。
- 国内局の座標は送信所がある市区町村の代表点で、実際の送信所(山頂など)とは数km〜十数kmずれることがあります。面積の広い市町村ほどずれが大きくなります。
- 国際放送で送信所が国名しか分からないもの(例: クウェートからの中継)は、その国の送信所の平均位置を使っています(国内の送信所が狭い範囲に収まる国のみ)。
- AFNの座標は `municipality_coords.json` の値(AFN Okinawa のFMはキャンプ・フォスターの座標)を手で入れています。`mic_import.py` は更新しないので、`geo_import.py` を再実行したら見直してください。

## データを更新する方法

`files/scripts/` のスクリプトはPython 3.8以上の標準ライブラリだけで動きます。

0. 市区町村の座標を更新する(市町村の合併などがあったとき)

   ```sh
   python3 files/scripts/geo_import.py
   ```

1. 国内局を総務省の免許情報で更新する(API へのアクセスは3秒間隔、計7リクエスト程度)

   ```sh
   python3 files/scripts/mic_import.py
   ```

   `mic_stations.json` を作り直し、手で整備したファイルの `power_kw` と `lat`・`lon` を免許情報の値に更新します。
   送信所の所在地が `municipality_coords.json` に見つからない場合は止まるので、`geo_import.py` を再実行するか、表記ゆれを `stationdata.py` の `LOCATION_ALIASES` に追加します。
   手で整備した局が免許情報に見当たらない場合や、コールサインが食い違う場合、AMの運用休止中なのに注記が無い場合は `[要確認]` として表示されるので、該当するJSONを手で直します。
   未登録の免許人(新しい放送局)があると止まるので、`LICENSEES` に局名と系列を追加します。

2. 短波のシーズンが替わったら国際放送を更新する(3月末・10月末頃)

   ```sh
   python3 files/scripts/eibi_import.py            # 今日のシーズン(例: B26)のCSVを取得
   ```

3. DBを作り直してアプリのアセットにコピーする

   `app/src/main/java/com/aisan/radio/data/AppDatabase.kt` の `version` を1つ上げてから実行します。

   ```sh
   python3 files/scripts/build_db.py
   ```

   `build_db.py` は全JSONを検証(列構成・周波数の範囲・IDや局の重複)し、`prefecture` / `category` / `role` を導出して(判定できない局があれば止まる)からDBを作り、`PRAGMA user_version` を `AppDatabase.kt` の `version` と同じ値にします。

## Androidでの使い方(Room)

`radio_stations.db` を `app/src/main/assets/database/radio_stations.db` に置き、Roomの `createFromAsset` で読み込みます。

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "radio_stations.db")
    .createFromAsset("database/radio_stations.db")
    .fallbackToDestructiveMigration()
    .build()
```

- Roomはアセットを初回起動時にしかコピーしないため、データを更新したら `@Database` の `version` を上げます。`fallbackToDestructiveMigration()` により、既にインストール済みの端末でも古いDBを捨ててアセットからコピーし直します(このDBは読み取り専用の参照データなので失われるものはありません)。
- アセットのDBの `user_version` は `version` と同じ値にしておきます(`build_db.py` が設定)。
- FM入力(例: 82.5)は `freq_khz` に変換(×1000)してから検索します。AM・SWはそのままkHzです。

> 注意: SQLiteは `id TEXT PRIMARY KEY` だけでは自動的にNOT NULL制約を付けません。Roomは主キー列がNOT NULLであることを要求するため、本DBでは `id TEXT PRIMARY KEY NOT NULL` として作成しています。
