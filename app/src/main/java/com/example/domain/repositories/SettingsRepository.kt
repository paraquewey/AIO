package com.example.domain.repositories

import com.example.data.preferences.SettingsDataStore
import com.example.domain.models.AppSettings
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val dataStore: SettingsDataStore) {
    val settings: Flow<AppSettings> = dataStore.settingsFlow

    suspend fun selectProfile(profileId: String?) {
        dataStore.updateSelectedProfileId(profileId)
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.updateSettings(transform)
    }
}
