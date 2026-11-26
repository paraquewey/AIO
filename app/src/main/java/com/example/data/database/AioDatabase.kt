package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TunnelProfileEntity::class],
    version = 4,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tunnel_profiles_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        protocolName TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        authMethodName TEXT NOT NULL,
                        jumpHostEnabled INTEGER NOT NULL,
                        jumpHost TEXT NOT NULL,
                        jumpPort INTEGER NOT NULL,
                        jumpUsername TEXT NOT NULL,
                        jumpAuthMethodName TEXT NOT NULL,
                        transportName TEXT NOT NULL,
                        tlsEnabled INTEGER NOT NULL,
                        tlsServerName TEXT NOT NULL,
                        tlsAlpn TEXT NOT NULL,
                        verifyTlsCertificate INTEGER NOT NULL,
                        rawHttpPayload TEXT NOT NULL DEFAULT '',
                        dnsModeName TEXT NOT NULL,
                        useCustomDns INTEGER NOT NULL DEFAULT 0,
                        dnsServer TEXT NOT NULL,
                        dnsServerSecondary TEXT NOT NULL DEFAULT '1.0.0.1',
                        dohUrl TEXT NOT NULL,
                        enableIpv6 INTEGER NOT NULL,
                        bypassLan INTEGER NOT NULL,
                        mtu INTEGER NOT NULL,
                        splitTunnelModeName TEXT NOT NULL,
                        splitTunnelPackagesJson TEXT NOT NULL,
                        category TEXT NOT NULL,
                        colorTagHex INTEGER NOT NULL,
                        tagsJson TEXT NOT NULL,
                        isFavorite INTEGER NOT NULL,
                        lastLatencyMs INTEGER,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        rawNativeConfigJson TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO tunnel_profiles_new (
                        id, name, protocolName, host, port, username, authMethodName,
                        jumpHostEnabled, jumpHost, jumpPort, jumpUsername, jumpAuthMethodName,
                        transportName, tlsEnabled, tlsServerName, tlsAlpn, verifyTlsCertificate,
                        rawHttpPayload, dnsModeName, useCustomDns, dnsServer, dnsServerSecondary,
                        dohUrl, enableIpv6, bypassLan, mtu, splitTunnelModeName,
                        splitTunnelPackagesJson, category, colorTagHex, tagsJson,
                        isFavorite, lastLatencyMs, createdAtMillis, updatedAtMillis, rawNativeConfigJson
                    )
                    SELECT
                        id, name, protocolName, host, port, username, authMethodName,
                        jumpHostEnabled, jumpHost, jumpPort, jumpUsername, jumpAuthMethodName,
                        transportName, tlsEnabled, tlsServerName, tlsAlpn, verifyTlsCertificate,
                        '' AS rawHttpPayload, dnsModeName, useCustomDns, dnsServer, dnsServerSecondary,
                        dohUrl, enableIpv6, bypassLan, mtu, splitTunnelModeName,
                        splitTunnelPackagesJson, category, colorTagHex, tagsJson,
                        isFavorite, lastLatencyMs, createdAtMillis, updatedAtMillis, rawNativeConfigJson
                    FROM tunnel_profiles
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE tunnel_profiles")
                db.execSQL("ALTER TABLE tunnel_profiles_new RENAME TO tunnel_profiles")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tunnel_profiles ADD COLUMN udpgwPort INTEGER NOT NULL DEFAULT 7300")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
