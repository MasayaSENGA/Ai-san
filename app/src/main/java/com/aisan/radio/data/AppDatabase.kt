package com.aisan.radio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 局データを更新したら version を上げ、files/scripts/build_db.py でアセットのDBを作り直す
@Database(entities = [Station::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "radio_stations.db"
                )
                    .createFromAsset("database/radio_stations.db")
                    // 読み取り専用の参照データなので、version が変わったらアセットから入れ直す
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
