package com.aisan.radio.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface StationDao {
    // ユーザー入力の誤差を考慮して許容幅(kHz)を持たせて検索する。
    // 同じ周波数に全国の中継局が並ぶため、出力の大きい(=受信している可能性の高い)局を先に出す。
    @Query(
        "SELECT * FROM stations WHERE band = :band " +
            "AND freq_khz BETWEEN (:freqKhz - :toleranceKhz) AND (:freqKhz + :toleranceKhz) " +
            "ORDER BY ABS(freq_khz - :freqKhz), power_kw IS NULL, power_kw DESC, name, area"
    )
    suspend fun findByFrequency(band: String, freqKhz: Double, toleranceKhz: Double): List<Station>

    // 放送局一覧は全件(約2,500件)をメモリに読み込み、絞り込みと並べ替えをアプリ側で行う
    @Query("SELECT * FROM stations")
    suspend fun getAll(): List<Station>
}
