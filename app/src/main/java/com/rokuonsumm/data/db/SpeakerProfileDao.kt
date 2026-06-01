package com.rokuonsumm.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeakerProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: SpeakerProfileEntity)

    @Query("SELECT * FROM speaker_profiles ORDER BY createdAt ASC")
    suspend fun getAll(): List<SpeakerProfileEntity>

    @Query("SELECT * FROM speaker_profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SpeakerProfileEntity>>

    @Query("DELETE FROM speaker_profiles WHERE name = :name")
    suspend fun delete(name: String)
}
