package com.aisan.radio.ui

import com.aisan.radio.data.Station
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_KM = 6371.0

// 2地点間の大円距離(km)
fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
}

// 局の座標は送信所の市区町村の代表点なので、数km程度の誤差がある
fun Station.distanceKmFrom(origin: GeoPoint): Double? {
    val lat = lat ?: return null
    val lon = lon ?: return null
    return distanceKm(origin, GeoPoint(lat, lon))
}

// 入力した周波数に近い順は保ったまま、同じ周波数の中は現在地から近い順に並べる。
// 座標の無い局は最後、距離が同じなら出力の大きい順。
fun sortByDistance(stations: List<Station>, freqKhz: Double, origin: GeoPoint): List<Station> =
    stations.sortedWith(
        compareBy<Station> { station -> station.freq_khz?.let { abs(it - freqKhz) } ?: Double.MAX_VALUE }
            .thenBy(nullsLast()) { it.distanceKmFrom(origin) }
            .thenByDescending { it.power_kw ?: 0.0 }
    )
