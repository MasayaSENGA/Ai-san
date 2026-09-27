package com.aisan.radio.ui

enum class Band(val dbValue: String, val label: String, val hint: String, val unit: String, val toleranceKhz: Double) {
    AM("AM", "AM", "例: 954", "kHz", 1.0),
    FM("FM", "FM", "例: 82.5", "MHz", 50.0),
    SW("SW", "短波", "例: 9750", "kHz", 5.0);

    // 入力値(表示単位)をDB検索用のkHzへ変換する
    fun toFreqKhz(inputValue: Double): Double = when (this) {
        FM -> inputValue * 1000.0
        AM, SW -> inputValue
    }

    companion object {
        fun fromDbValue(value: String): Band? = entries.firstOrNull { it.dbValue == value }
    }
}
