package com.seenot.app.data.local.dao

import androidx.room.*
import com.seenot.app.data.local.entity.SessionIntentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for SessionIntent operations
 */
@Dao
interface SessionIntentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(intent: SessionIntentEntity): Long

    @Update
    suspend fun update(intent: SessionIntentEntity)

    @Delete
    suspend fun delete(intent: SessionIntentEntity)

    @Query("SELECT * FROM session_intents WHERE id = :id")
    suspend fun getById(id: Long): SessionIntentEntity?

    @Query("SELECT * FROM session_intents WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeIntentsForSession(sessionId: Long): Flow<List<SessionIntentEntity>>

    @Query("SELECT * FROM session_intents WHERE sessionId = :sessionId AND isActive = 1 ORDER BY createdAt DESC")
    fun observeActiveIntentsForSession(sessionId: Long): Flow<List<SessionIntentEntity>>

    @Query("SELECT * FROM session_intents WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestIntentForSession(sessionId: Long): SessionIntentEntity?

    @Query("UPDATE session_intents SET isActive = 0 WHERE sessionId = :sessionId")
    suspend fun deactivateAllForSession(sessionId: Long)

    @Query("SELECT * FROM session_intents ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentIntents(limit: Int): Flow<List<SessionIntentEntity>>
}
