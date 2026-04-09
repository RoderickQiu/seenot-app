package com.seenot.app.data.local.dao

import androidx.room.*
import com.seenot.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Session operations
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    fun observeActiveSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE appPackageName = :packageName AND isActive = 1 LIMIT 1")
    suspend fun getActiveSessionForApp(packageName: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET isActive = 0, endedAt = :endedAt, endReason = :reason WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endedAt: Long = System.currentTimeMillis(), reason: String)
}
