package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TunnelProfileDao {
    @Query("SELECT * FROM tunnel_profiles ORDER BY isFavorite DESC, updatedAtMillis DESC")
    fun observeAll(): Flow<List<TunnelProfileEntity>>

    @Query("SELECT * FROM tunnel_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TunnelProfileEntity?

    @Query("SELECT * FROM tunnel_profiles")
    suspend fun getAllOnce(): List<TunnelProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TunnelProfileEntity)

    @Query("DELETE FROM tunnel_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tunnel_profiles SET lastLatencyMs = :latencyMs WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Long?)

    @Query("UPDATE tunnel_profiles SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)
}
