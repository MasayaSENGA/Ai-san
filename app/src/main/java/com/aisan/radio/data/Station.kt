package com.aisan.radio.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stations",
    indices = [
        Index(value = ["freq_khz"], name = "idx_freq"),
        Index(value = ["band"], name = "idx_band")
    ]
)
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
    val note: String?,
    val power_kw: Double?
)
