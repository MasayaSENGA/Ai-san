package com.aisan.radio.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface StationDao {
    // ユーザー入力の誤差を考慮して許容幅(kHz)を持たせて検索する
    @Query(
        "SELECT * FROM stations WHERE band = :band " +
            "AND freq_khz BETWEEN (:freqKhz - :toleranceKhz) AND (:freqKhz + :toleranceKhz) " +
            "ORDER BY ABS(freq_khz - :freqKhz)"
    )
    suspend fun findByFrequency(band: String, freqKhz: Double, toleranceKhz: Double): List<Station>
}
