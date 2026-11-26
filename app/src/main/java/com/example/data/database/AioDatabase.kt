package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TunnelProfileEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AioDatabase : RoomDatabase() {
    abstract fun tunnelProfileDao(): TunnelProfileDao

    companion object {
        @Volatile
        private var instance: AioDatabase? = null

        fun getInstance(context: Context): AioDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AioDatabase::class.java,
                    "aio_tunnel_vpn.db"
                ).build().also { instance = it }
            }
    }
}
