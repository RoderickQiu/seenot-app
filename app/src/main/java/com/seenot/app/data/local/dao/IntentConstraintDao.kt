package com.seenot.app.data.local.dao

import androidx.room.*
import com.seenot.app.data.local.entity.IntentConstraintEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for IntentConstraint operations
 */
@Dao
interface IntentConstraintDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(constraint: IntentConstraintEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(constraints: List<IntentConstraintEntity>): List<Long>

    @Update
    suspend fun update(constraint: IntentConstraintEntity)

    @Delete
    suspend fun delete(constraint: IntentConstraintEntity)

    @Query("SELECT * FROM intent_constraints WHERE id = :id")
    suspend fun getById(id: Long): IntentConstraintEntity?

    @Query("SELECT * FROM intent_constraints WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeConstraintsForSession(sessionId: Long): Flow<List<IntentConstraintEntity>>

    @Query("SELECT * FROM intent_constraints WHERE sessionId = :sessionId AND isActive = 1 ORDER BY createdAt DESC")
    fun observeActiveConstraintsForSession(sessionId: Long): Flow<List<IntentConstraintEntity>>

    @Query("SELECT * FROM intent_constraints WHERE sessionId = :sessionId AND isActive = 1")
    suspend fun getActiveConstraintsForSession(sessionId: Long): List<IntentConstraintEntity>

    @Query("UPDATE intent_constraints SET isActive = :isActive WHERE id = :constraintId")
    suspend fun setActive(constraintId: Long, isActive: Boolean)

    @Query("DELETE FROM intent_constraints WHERE sessionId = :sessionId")
    suspend fun deleteAllForSession(sessionId: Long)
}
