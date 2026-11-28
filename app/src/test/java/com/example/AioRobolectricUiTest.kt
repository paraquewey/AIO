package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
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
import android.graphics.Bitmap
import android.net.Uri
import com.example.data.database.AioDatabase
import com.example.data.database.toDomain
import com.example.data.database.toEntity
import com.example.domain.models.AppSettings
import com.example.domain.models.FontColorMode
import com.example.domain.models.ServerMessageMode
import com.example.domain.models.TextureScaleMode
import com.example.domain.models.TunnelProfile
import com.example.domain.models.TunnelProtocol
import com.example.ui.theme.FontSlot
import com.example.ui.theme.ThemePackManager
import com.example.ui.theme.loadSafeFontFamily
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AioRobolectricUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `bottom navigation switches across all five Space Glass sections and console`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_status_label").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_timer").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_exit_ip_card").performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("dashboard_download_tile").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("home_console_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("console_title").assertIsDisplayed()
        composeRule.onNodeWithTag("console_copy_button").assertIsDisplayed()
        composeRule.onNodeWithTag("console_clear_button").assertIsDisplayed()
        composeRule.onNodeWithTag("console_menu_button").assertIsDisplayed()
        composeRule.onNodeWithTag("console_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_servers").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("servers_measure_latency_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_configs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Gestor de perfiles").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_analysis").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("analysis_speed_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analysis_stability_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analysis_port_scan_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analysis_dns_audit_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_keep_trying_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_auto_reconnect_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_wakelock_switch").performScrollTo().assertIsDisplayed().assertIsOn()
        composeRule.onNodeWithTag("settings_timeout_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_wifi_proxy_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_battery_optimization_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_dns1_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_dns2_input").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `console sandwich menu opens bottom sheet with diagnostics, ssh keys and autoscroll`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_console_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("console_menu_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("console_autoscroll_switch").assertIsDisplayed().assertIsOn()
        composeRule.onNodeWithTag("diagnostics_run_button").assertIsDisplayed()
        composeRule.onAllNodesWithText("Claves SSH confiables", substring = true).fetchSemanticsNodes()
            .let { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun `settings expands advanced engine drawer and applies presets`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_advanced_drawer_header").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("preset_speed").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Perfil actual: Máxima Velocidad", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("preset_security").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Perfil actual: Máxima Seguridad y Privacidad", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_kill_switch").performScrollTo().assertIsOn()

        // Verify TUN queue slider and Smart DNS SWR switch inside advanced drawer
        composeRule.onNodeWithTag("settings_tun_queue_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_smart_dns_cache_switch").performScrollTo().assertIsOn().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Perfil actual: Personalizado", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("settings_restore_optimal_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Perfil actual: Equilibrado (Recomendado)", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings_smart_dns_cache_switch").performScrollTo().assertIsOn()
    }

    @Test
    fun `ui customization subscreen switches font contrast modes and primary accent`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_ui_customization_card").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("font_mode_adaptive").assertIsDisplayed()
        composeRule.onNodeWithTag("font_mode_white").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Texto activo: Claro (#F8FAFC)", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("font_mode_black").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Texto activo: Oscuro (#0F172A)", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("font_mode_adaptive").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ui_custom_pick_bg_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ui_custom_reset_bg_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ui_custom_dimmer_slider").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("accent_swatch_1").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("#00E5A8", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("accent_hue_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("accent_sat_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ui_custom_reset_accent_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("#7B61FF", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("ui_custom_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_ui_customization_card").assertIsDisplayed()
    }

    @Test
    fun `ui customization phase 2 displays live preview, texture modes, font controls and theme pack actions`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_ui_customization_card").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ui_custom_preview_card").assertIsDisplayed()
        composeRule.onNodeWithTag("preview_action_button").assertIsDisplayed()

        // Expand Textures section and toggle TILE mode
        composeRule.onNodeWithTag("section_textures").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tex_card_pick_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("tex_card_mode_tile").performScrollTo().performClick()
        composeRule.onNodeWithTag("tex_hero_pick_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("tex_action_pick_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("tex_navbar_pick_button").performScrollTo().assertIsDisplayed()

        // Expand Typography section
        composeRule.onNodeWithTag("section_typography").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("font_title_pick_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("font_body_pick_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("font_terminal_pick_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("font_terminal_scale_slider").performScrollTo().assertIsDisplayed()

        // Expand Export/Import Theme section and trigger Factory Reset
        composeRule.onNodeWithTag("section_theme_pack").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("theme_export_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("theme_import_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ui_factory_reset_button").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `theme pack manager exports and imports aiotheme zip and safely rejects corrupt fonts and zip slip`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Corrupt font fallback check (never throws, returns friendly error)
        val corruptFont = File(context.cacheDir, "corrupt_test.ttf").apply {
            writeBytes("NOT_A_VALID_TTF_FILE".toByteArray())
        }
        val fontLoad = loadSafeFontFamily(corruptFont.absolutePath)
        assertNull(fontLoad.fontFamily)
        assertNotNull(fontLoad.errorMessage)

        val fontImportRes = ThemePackManager.importThemeFont(context, Uri.fromFile(corruptFont), FontSlot.TERMINAL)
        assertTrue(fontImportRes.isFailure)

        // 2. Create real PNG texture asset & test .aiotheme export + import round-trip
        val imgFile = File(context.cacheDir, "sample_tex.png")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.CYAN)
        FileOutputStream(imgFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bmp.recycle()

        val sourceSettings = AppSettings(
            fontColorMode = FontColorMode.FORCED_WHITE,
            primaryAccentArgb = 0xFF00E5A8L,
            backgroundDimmerAlpha = 0.50f,
            customBackgroundPath = imgFile.absolutePath,
            customBackgroundLuminance = 0.72f,
            cardTexturePath = imgFile.absolutePath,
            cardTextureMode = TextureScaleMode.TILE,
            cardTextureAlpha = 0.62f,
            heroButtonTexturePath = imgFile.absolutePath,
            heroButtonTextureAlpha = 0.80f,
            titleFontScale = 1.15f,
            bodyFontScale = 0.95f,
            terminalFontScale = 1.25f
        )

        val exportRes = ThemePackManager.exportThemePackage(context, sourceSettings)
        assertTrue(exportRes.isSuccess)
        val exportedZip = exportRes.getOrThrow()
        assertTrue(exportedZip.exists() && exportedZip.length() > 0L)

        ZipFile(exportedZip).use { zf ->
            assertNotNull(zf.getEntry("theme.json"))
        }

        val importRes = ThemePackManager.importThemePackage(
            context = context,
            uri = Uri.fromFile(exportedZip),
            currentSettings = AppSettings()
        )
        assertTrue(importRes.isSuccess)
        val restored = importRes.getOrThrow()
        assertEquals(FontColorMode.FORCED_WHITE, restored.fontColorMode)
        assertEquals(0xFF00E5A8L, restored.primaryAccentArgb)
        assertEquals(TextureScaleMode.TILE, restored.cardTextureMode)
        assertEquals(0.62f, restored.cardTextureAlpha, 0.01f)
        assertEquals(1.15f, restored.titleFontScale, 0.01f)
        assertEquals(0.95f, restored.bodyFontScale, 0.01f)
        assertEquals(1.25f, restored.terminalFontScale, 0.01f)
        assertNotNull(restored.cardTexturePath)
        assertTrue(File(restored.cardTexturePath!!).exists())

        // 3. Zip Slip security check
        val maliciousZip = File(context.cacheDir, "evil_slip.aiotheme")
        ZipOutputStream(FileOutputStream(maliciousZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../outside_escape.txt"))
            zos.write("pwn".toByteArray())
            zos.closeEntry()
        }
        val evilImport = ThemePackManager.importThemePackage(context, Uri.fromFile(maliciousZip), AppSettings())
        assertTrue(evilImport.isFailure)
        assertFalse(File(context.cacheDir.parentFile, "outside_escape.txt").exists())

        corruptFont.delete()
        imgFile.delete()
        maliciousZip.delete()
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
            .let { assertTrue(it.isNotEmpty()) }

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
