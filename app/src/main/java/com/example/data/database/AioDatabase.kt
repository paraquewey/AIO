package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TunnelProfileEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AioDatabase : RoomDatabase() {
    abstract fun tunnelProfileDao(): TunnelProfileDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tunnel_profiles ADD COLUMN dnsServerSecondary TEXT NOT NULL DEFAULT '1.0.0.1'")
                db.execSQL("ALTER TABLE tunnel_profiles ADD COLUMN useCustomDns INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tunnel_profiles ADD COLUMN httpRequestMethod TEXT NOT NULL DEFAULT 'CONNECT'")
                db.execSQL("ALTER TABLE tunnel_profiles ADD COLUMN httpRequestPath TEXT NOT NULL DEFAULT '/'")
                db.execSQL("ALTER TABLE tunnel_profiles ADD COLUMN upgradeWebSocket INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AioDatabase? = null

        fun getInstance(context: Context): AioDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AioDatabase::class.java,
                    "aio_tunnel_vpn.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
