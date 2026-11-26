package com.example.domain.repositories

import com.example.data.database.TunnelProfileDao
import com.example.data.database.toDomain
import com.example.data.database.toEntity
import com.example.data.security.KeystoreCryptoManager
import com.example.domain.models.ProfileSecrets
import com.example.domain.models.TunnelProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ProfileRepository(
    private val dao: TunnelProfileDao,
    private val cryptoManager: KeystoreCryptoManager
) {
    val profiles: Flow<List<TunnelProfile>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getAllProfiles(): List<TunnelProfile> =
        dao.getAllOnce().map { it.toDomain() }

    suspend fun getProfile(id: String): TunnelProfile? =
        dao.getById(id)?.toDomain()

    fun getSecrets(profileId: String): ProfileSecrets =
        cryptoManager.loadProfileSecrets(profileId)

    suspend fun saveProfile(profile: TunnelProfile, secrets: ProfileSecrets?) {
        val now = System.currentTimeMillis()
        val finalProfile = profile.copy(updatedAtMillis = now)
        dao.upsert(finalProfile.toEntity())
        if (secrets != null) {
            cryptoManager.saveProfileSecrets(finalProfile.id, secrets)
        }
    }

    suspend fun duplicateProfile(profileId: String): TunnelProfile? {
        val original = getProfile(profileId) ?: return null
        val originalSecrets = getSecrets(profileId)
        val newId = UUID.randomUUID().toString()
        val copy = original.copy(
            id = newId,
            name = "${original.name} (Copia)",
            isFavorite = false,
            lastLatencyMs = null,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis()
        )
        saveProfile(copy, originalSecrets)
        return copy
    }

    suspend fun deleteProfile(profileId: String) {
        dao.deleteById(profileId)
        cryptoManager.deleteProfileSecrets(profileId)
    }

    suspend fun setFavorite(profileId: String, favorite: Boolean) {
        dao.setFavorite(profileId, favorite)
    }

    suspend fun updateLatency(profileId: String, latencyMs: Long?) {
        dao.updateLatency(profileId, latencyMs)
    }
}
