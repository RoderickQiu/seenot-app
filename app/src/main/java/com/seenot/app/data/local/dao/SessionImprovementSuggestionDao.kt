package com.seenot.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.seenot.app.data.local.entity.SessionImprovementSuggestionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionImprovementSuggestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(suggestion: SessionImprovementSuggestionEntity)

    @Query("SELECT * FROM session_improvement_suggestions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: Long): SessionImprovementSuggestionEntity?

    @Query(
        """
        SELECT * FROM session_improvement_suggestions
        WHERE createdAt >= :startTime AND createdAt <= :endTime
          AND status = 'READY'
          AND acceptedAt IS NULL
          AND dismissedAt IS NULL
        ORDER BY createdAt ASC
        """
    )
    fun getSuggestionsInRangeFlow(startTime: Long, endTime: Long): Flow<List<SessionImprovementSuggestionEntity>>

    @Query(
        """
        SELECT * FROM session_improvement_suggestions
        WHERE packageName = :packageName
          AND status = 'READY'
          AND acceptedAt IS NULL
          AND dismissedAt IS NULL
          AND nextIntentSuggestion != ''
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getPendingSuggestionForPackage(packageName: String): SessionImprovementSuggestionEntity?

    @Query(
        """
        UPDATE session_improvement_suggestions
        SET acceptedAt = :acceptedAt, acceptedAction = :acceptedAction
        WHERE id = :suggestionId
        """
    )
    suspend fun accept(suggestionId: String, acceptedAt: Long, acceptedAction: String)

    @Query("UPDATE session_improvement_suggestions SET dismissedAt = :dismissedAt WHERE id = :suggestionId")
    suspend fun dismiss(suggestionId: String, dismissedAt: Long)
}
