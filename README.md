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
      MainActivity.kt          画面(Compose UI)
      ui/Band.kt                AM/FM/SW の単位変換・許容幅の定義
      ui/StationViewModel.kt    検索状態管理
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
id, band, freq_khz, name, area, callsign, network, language, target_area, note, power_kw
```

- `band`: `AM` / `FM` / `SW`
- `freq_khz`: AM/FM/SWいずれもkHz単位で統一格納(FMはMHz値を×1000して格納)
- `power_kw`: 送信出力(空中線電力、kW)。国際放送など不明な局は `NULL`

計2,448局を収録(AM 494 / FM 1,468 / SW 486)。

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
