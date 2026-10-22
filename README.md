# Ai-san

ラジオの周波数を入力すると、該当する放送局名を検索できるAndroidアプリです。

## 概要

AM／FM／短波(SW)を切り替えて周波数を入力すると、あらかじめ同梱されたデータベースから
該当する放送局(局名・コールサイン・エリア・系列など)を検索して表示します。

- FM入力例: `82.5` (MHz)
- AM入力例: `954` (kHz)
- 短波入力例: `9750` (kHz)

入力誤差を考慮し、以下の許容幅(トレランス)を持たせて検索します。

| バンド | 許容幅 |
|---|---|
| AM | ±1 kHz |
| FM | ±50 kHz (±0.05 MHz) |
| 短波(SW) | ±5 kHz |

同じ周波数に全国の中継局が並ぶため、検索結果は周波数の近い順、次に送信出力の大きい順
(受信している可能性の高い順)に表示します。

「現在地から近い順に並べる」をオンにすると、スマートフォンの位置情報(おおよその位置)を使って、
同じ周波数の中を送信所が現在地から近い順に並べ替え、各局までの距離の目安を表示します。
初めてオンにしたときに位置情報の利用許可を求めます。位置情報は並べ替えのためだけに端末内で使い、
外部には送信しません。

### 放送局一覧

画面下の「放送局一覧」タブでは、収録している全局を周波数以外の情報で絞り込み・並べ替えできます。

| 絞り込み | 内容 |
|---|---|
| キーワード | 局名・地域・コールサイン・系列・言語・注記などに含まれる語(空白で区切ると、すべてを含む局) |
| 地域 | 都道府県、または地方(東北地方など)。NHK・民放の主要局は放送局の都道府県、それ以外(中継局・コミュニティFM・AFN・ラジオNIKKEIなど)は送信所の都道府県 |
| 種類 | NHK / 民放 / コミュニティFM / AFN(在日米軍) / 国際放送 / その他(臨時災害放送局など) |
| 送信所 | 中継局を除く / ワイドFM(FM補完中継局)のみ |
| バンド | AM / FM / 短波 |
| 系列 | 民放の JRN / NRN / JFN / JFL / 独立局(JRNとNRNの両方に加盟する局はどちらにも含まれる) |
| 言語 | 国際放送の放送言語(種類で「国際放送」を選んだときに表示) |

各選択肢には、選んだときの件数が表示されます(0件になる選択肢は選べません)。
並び順は「都道府県順」(北から。都道府県ごとの見出し付き)、「局名順」、「出力の大きい順」、
「現在地から近い順」から選べます。「現在地から近い順」を選ぶと、検索画面と同じく位置情報の利用許可を求めます。

## iPhone版

SwiftUIで実装したiOS版を `iOS/AiSan.xcodeproj` に追加しています。Android版と同じSQLiteデータベースを同梱し、周波数検索、放送局一覧、一覧の絞り込み・並べ替え、位置情報による距離順に対応しています。

- 対応OS: iOS 17以上
- Xcodeで `iOS/AiSan.xcodeproj` を開き、`AiSan` スキームを実行
- 実機で実行するには、Xcodeの「Signing & Capabilities」で自分のTeam(Apple ID)を選択
- 位置情報は距離順を選んだ場合だけ要求し、端末内で使用

### 実機へのインストール

Xcodeの画面から実行するほか、コマンドラインでも実機へインストールできます。
`<UDID>` は `xcrun devicectl list devices` で確認できます。

```sh
xcodebuild -project iOS/AiSan.xcodeproj -scheme AiSan -configuration Debug \
  -destination 'platform=iOS,id=<UDID>' \
  -derivedDataPath build/DerivedData -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=<TEAM_ID> build
xcrun devicectl device install app --device <UDID> \
  build/DerivedData/Build/Products/Debug-iphoneos/AiSan.app
```

`<TEAM_ID>` はXcodeの「Signing & Capabilities」で選択したTeamのIDです。

無料のApple ID(Personal Team)で署名した場合、証明書の有効期限は7日間です。
期限が切れて起動できなくなったら、同じ手順で再インストールしてください。

## 技術構成

- Kotlin
- Jetpack Compose (Material3)
- Room (事前構築済みSQLiteデータベースを`assets`から読み込み)
- 対応OS: Android 7.0 (API 24) 以上

## プロジェクト構成

```
app/
  src/main/
    java/com/aisan/radio/
      MainActivity.kt          画面の切り替え(下部のタブ)・周波数検索画面(Compose UI)
      StationListScreen.kt     放送局一覧の画面
      ui/Band.kt                AM/FM/SW の単位変換・許容幅の定義
      ui/StationViewModel.kt    検索状態管理
      ui/StationSorting.kt      現在地からの距離の計算・近い順の並べ替え
      ui/StationListViewModel.kt  放送局一覧の状態管理
      ui/StationListFilter.kt   放送局一覧の絞り込み(地域・種類・系列など)とキーワード検索
      ui/StationListSort.kt     放送局一覧の並べ替え
      location/CurrentLocation.kt  現在地の取得(LocationManager)
      location/LocationPermissionMemory.kt  位置情報の権限が拒否されたことの記録
      data/Station.kt           Room Entity
      data/StationDao.kt        検索クエリ
      data/AppDatabase.kt       Room Database(assetsからの読み込み)
    assets/database/
      radio_stations.db         アプリに同梱するSQLiteデータベース
files/
  radio_stations.db             データベースの元データ(参照用)
  jp_am_fm_stations.json        NHK・民放の主要局(親局)データ
  community_fm_stations.json    コミュニティFM局データ
  jp_sw_stations.json           国内の短波放送(ラジオNIKKEI)データ
  afn_stations.json             在日米軍AFNのラジオ局データ
  mic_stations.json             中継局・FM補完中継局など(総務省の免許情報から自動生成)
  intl_stations.json            日本で受信されやすい国際放送(EiBiから自動生成)
  scripts/                      データの取り込み・DB生成スクリプト
  README.md                     データベースの詳細・更新方法
```

## データベースについて

`stations`テーブルの列構成は以下の通りです。

```
id, band, freq_khz, name, area, callsign, network, language, target_area, note, power_kw, lat, lon,
prefecture, category, role
```

- `band`: `AM` / `FM` / `SW`
- `freq_khz`: AM/FM/SWいずれもkHz単位で統一格納(FMはMHz値を×1000して格納)
- `power_kw`: 送信出力(空中線電力、kW)。国際放送など不明な局は `NULL`
- `lat` / `lon`: 送信所の座標。国内局は送信所がある市区町村の代表点(Wikidata)、国際放送はEiBiの送信所の座標
- `prefecture` / `category` / `role`: 放送局一覧の絞り込み用に、DB生成時に他の列から導出する列。
  都道府県(国際放送は `NULL`)、局の種類(`nhk` / `commercial`(民放) / `community` / `afn` / `international` / `other`)、
  送信所の役割(`main` 親局 / `relay` 中継局 / `fm_complement` FM補完中継局。国際放送は `NULL`)

計2,459局を収録(AM 496 / FM 1,468 / SW 495)。

- 国内のAM・FM放送: NHK・民放の親局と中継局、FM補完中継局(ワイドFM)、コミュニティFM(中継局を含む)、
  臨時災害放送局など。総務省 電波利用ポータルの無線局免許情報(2026年9月25日更新分)に基づきます。
  AMの運用休止(総務省の特例措置による実証実験)中の局は `note` に記載しています。
- 国内の短波放送: ラジオNIKKEI第1・第2
- 在日米軍のAFN(AM・FM)
- 日本で受信されやすい国際放送: 日本語放送、日本・極東・朝鮮半島・中国向けの短波放送と中波の国際放送
  (EiBi Shortwave Schedules の A26 シーズン、2026年10月25日まで)

データの出所・既知の制約・更新方法については [`files/README.md`](files/README.md) を参照してください。

このアプリは、総務省 電波利用ポータルのWeb-API 機能を利用して取得した情報をもとに作成しているが、
サービスの内容は総務省によって保証されたものではない。

## ビルド方法

Android Studio、またはコマンドラインから以下でビルドできます(JDK 17を推奨)。

```sh
./gradlew :app:assembleDebug
```

生成されたAPKは `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

## インストール

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## ライセンス

ソースコードは MIT License です。詳しくは [`LICENSE`](LICENSE) を参照してください。

`files/` の局データとアプリに同梱しているデータベースは、総務省の免許情報・EiBi・Wikidata などをもとに作成しています。データの出所は [`files/README.md`](files/README.md) を参照してください。
