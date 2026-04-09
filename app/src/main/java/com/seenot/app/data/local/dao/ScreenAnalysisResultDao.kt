package com.seenot.app.data.local.dao

import androidx.room.*
import com.seenot.app.data.local.entity.ScreenAnalysisResultEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for ScreenAnalysisResult operations
 */
@Dao
interface ScreenAnalysisResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: ScreenAnalysisResultEntity): Long

    @Delete
    suspend fun delete(result: ScreenAnalysisResultEntity)

    @Query("SELECT * FROM screen_analysis_results WHERE id = :id")
    suspend fun getById(id: Long): ScreenAnalysisResultEntity?

    @Query("SELECT * FROM screen_analysis_results WHERE sessionId = :sessionId ORDER BY analyzedAt DESC")
    fun observeResultsForSession(sessionId: Long): Flow<List<ScreenAnalysisResultEntity>>

    @Query("SELECT * FROM screen_analysis_results WHERE sessionId = :sessionId ORDER BY analyzedAt DESC LIMIT 1")
    suspend fun getLatestResultForSession(sessionId: Long): ScreenAnalysisResultEntity?

    @Query("SELECT * FROM screen_analysis_results WHERE sessionId = :sessionId AND isViolation = 1 ORDER BY analyzedAt DESC")
    fun observeViolationsForSession(sessionId: Long): Flow<List<ScreenAnalysisResultEntity>>

    @Query("SELECT * FROM screen_analysis_results WHERE screenshotHash = :hash LIMIT 1")
    suspend fun getByScreenshotHash(hash: String): ScreenAnalysisResultEntity?

    @Query("DELETE FROM screen_analysis_results WHERE sessionId = :sessionId")
    suspend fun deleteAllForSession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM screen_analysis_results WHERE sessionId = :sessionId AND isViolation = 1")
    suspend fun getViolationCountForSession(sessionId: Long): Int

    @Query("SELECT * FROM screen_analysis_results WHERE sessionId = :sessionId AND screenshotHash = :hash LIMIT 1")
    suspend fun findBySessionAndHash(sessionId: Long, hash: String): ScreenAnalysisResultEntity?
}
