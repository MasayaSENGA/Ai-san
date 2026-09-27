package com.aisan.radio.ui

import com.aisan.radio.data.Station
import java.text.Collator
import java.util.Locale

// テスト用の局。名前は JVM と Android で並びが変わらないよう ASCII かかなにする
fun testStation(
    id: String,
    band: String = "FM",
    freqKhz: Double? = 80000.0,
    name: String = id,
    area: String? = null,
    callsign: String? = null,
    network: String? = null,
    language: String? = null,
    targetArea: String? = null,
    note: String? = null,
    powerKw: Double? = null,
    lat: Double? = null,
    lon: Double? = null,
    prefecture: String? = null,
    category: String = "commercial",
    role: String? = "main",
) = Station(
    id = id, band = band, freq_khz = freqKhz, name = name, area = area, callsign = callsign, network = network,
    language = language, target_area = targetArea, note = note, power_kw = powerKw, lat = lat, lon = lon,
    prefecture = prefecture, category = category, role = role
)

fun entriesOf(vararg stations: Station): List<StationEntry> =
    buildEntries(stations.toList(), Collator.getInstance(Locale.ROOT))
