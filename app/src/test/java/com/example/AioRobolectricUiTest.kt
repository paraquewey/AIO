package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AioDatabase
import com.example.data.database.toDomain
import com.example.data.database.toEntity
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
        composeRule.onNodeWithTag("settings_auto_reconnect_switch").assertIsDisplayed()
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
        composeRule.onNodeWithTag("editor_save_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Test Proxy").assertIsDisplayed()
    }

    @Test
    fun `room database persists and queries tunnel profiles`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AioDatabase::class.java
        ).allowMainThreadQueries().build()

        val profile = TunnelProfile(
            name = "Room Check",
            protocol = TunnelProtocol.SOCKS5,
            host = "198.51.100.7",
            port = 1080
        )
        db.tunnelProfileDao().upsert(profile.toEntity())
        val loaded = db.tunnelProfileDao().getById(profile.id)?.toDomain()
        assertEquals("Room Check", loaded?.name)
        assertEquals(TunnelProtocol.SOCKS5, loaded?.protocol)
        db.close()
    }
}
