package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AioDatabase
import com.example.data.database.toDomain
import com.example.data.database.toEntity
import com.example.domain.models.ServerMessageMode
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AioRobolectricUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `bottom navigation switches across all five core sections`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_status_label").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_servers").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("servers_measure_latency_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_configs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Gestor de perfiles").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_diagnostics").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostics_run_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_keep_trying_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_auto_reconnect_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_timeout_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_wifi_proxy_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_battery_optimization_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_dns1_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_dns2_input").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `settings validates timeout bounds and toggles wifi proxy sharing card`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_timeout_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("settings_timeout_input").performTextInput("1500")
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Rango permitido: mínimo 1 s y máximo 999 s.").fetchSemanticsNodes()
            .let { org.junit.Assert.assertTrue(it.isNotEmpty()) }

        composeRule.onNodeWithTag("settings_bypass_lan_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_wifi_proxy_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_wifi_proxy_port_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("settings_wifi_proxy_port_input").performTextInput("8181")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_wifi_proxy_address").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_copy_proxy_ip_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `create profile via editor updates configs list and persists`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_configs").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("configs_create_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_name_input").performTextInput("Test Proxy")
        composeRule.onNodeWithTag("editor_host_input").performTextInput("proxy.example.org")
        composeRule.onNodeWithTag("editor_raw_payload_input").performScrollTo().performTextInput("HEAD / HTTP/1.1[crlf]Host: [host][crlf][crlf][split]")
        composeRule.onNodeWithTag("editor_dns1_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("editor_dns2_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("editor_server_msg_mode_custom").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_server_msg_html_input").performScrollTo()
            .performTextInput("<style>.b{color:#B8BB26;}</style><div class='b'>Hola VPN</div>")
        composeRule.onNodeWithTag("editor_save_button").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("configs_create_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Test Proxy").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Test Proxy").assertIsDisplayed()
    }

    @Test
    fun `room database persists and queries tunnel profiles with secondary dns and raw payload`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AioDatabase::class.java
        ).allowMainThreadQueries().build()

        val samplePayload = "GET /ws HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf][split]"
        val profile = TunnelProfile(
            name = "Room Check",
            protocol = TunnelProtocol.SSH,
            host = "198.51.100.7",
            port = 22,
            udpgwPort = 7301,
            useCustomDns = true,
            dnsServer = "94.140.14.15",
            dnsServerSecondary = "94.140.15.15",
            rawHttpPayload = samplePayload,
            serverMessageMode = ServerMessageMode.CUSTOM_HTML,
            customServerMessageHtml = "<b>Bienvenido</b>"
        )
        db.tunnelProfileDao().upsert(profile.toEntity())
        val loaded = db.tunnelProfileDao().getById(profile.id)?.toDomain()
        assertEquals("Room Check", loaded?.name)
        assertEquals(TunnelProtocol.SSH, loaded?.protocol)
        assertEquals(7301, loaded?.udpgwPort)
        assertEquals("94.140.14.15", loaded?.dnsServer)
        assertEquals("94.140.15.15", loaded?.dnsServerSecondary)
        assertEquals(samplePayload, loaded?.rawHttpPayload)
        assertEquals(ServerMessageMode.CUSTOM_HTML, loaded?.serverMessageMode)
        assertEquals("<b>Bienvenido</b>", loaded?.customServerMessageHtml)
        db.close()
    }

    @Test
    fun `room migration 1 to 5 preserves profiles and adds server message columns`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migration_test_${System.nanoTime()}.db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS tunnel_profiles (
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
                                customConnectHeadersJson TEXT NOT NULL,
                                dnsModeName TEXT NOT NULL,
                                dnsServer TEXT NOT NULL,
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
                            INSERT INTO tunnel_profiles VALUES (
                                'legacy-1', 'Legacy Node', 'HTTP_PROXY', '192.0.2.1', 8080, '', 'PASSWORD',
                                0, '', 22, '', 'PASSWORD', 'TCP', 0, '', 'http/1.1', 1, '{}',
                                'DNS_OVER_HTTPS', '94.140.14.15', 'https://cloudflare-dns.com/dns-query',
                                0, 1, 1500, 'ALL_APPS', '[]', 'General', 4294869017, '[]', 0, NULL, 1000, 1000, NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { v1Db ->
            AioDatabase.MIGRATION_1_2.migrate(v1Db)
            AioDatabase.MIGRATION_2_3.migrate(v1Db)
            AioDatabase.MIGRATION_3_4.migrate(v1Db)
            AioDatabase.MIGRATION_4_5.migrate(v1Db)
            v1Db.query(
                "SELECT dnsServer, dnsServerSecondary, rawHttpPayload, udpgwPort, serverMessageModeName, customServerMessageHtml FROM tunnel_profiles WHERE id = 'legacy-1'"
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("94.140.14.15", cursor.getString(0))
                assertEquals("1.0.0.1", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals(7300, cursor.getInt(3))
                assertEquals("ORIGINAL", cursor.getString(4))
                assertEquals("", cursor.getString(5))
            }
        }
        context.deleteDatabase(dbName)
    }
}
