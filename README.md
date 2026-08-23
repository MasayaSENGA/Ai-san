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
  jp_am_fm_stations.json        日本AM/FM主要局データ(元データ)
  sw_stations.json              短波サンプルデータ(元データ)
  README.md                     データベースの詳細・拡張方法
```

## データベースについて

`stations`テーブルの列構成は以下の通りです。

```
id, band, freq_khz, name, area, callsign, network, language, target_area, note
```

- `band`: `AM` / `FM` / `SW`
- `freq_khz`: AM/FM/SWいずれもkHz単位で統一格納(FMはMHz値を×1000して格納)

収録されているのは主要局のみ(NHK + 関東・関西・中京キー局など)です。
データの詳細・拡張方法については [`files/README.md`](files/README.md) を参照してください。

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
