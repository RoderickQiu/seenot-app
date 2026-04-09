package com.seenot.app.data.local.dao

import androidx.room.*
import com.seenot.app.data.local.entity.AppHintEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for app-specific hints
 */
@Dao
interface AppHintDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(hint: AppHintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hints: List<AppHintEntity>)

    @Update
    suspend fun update(hint: AppHintEntity)

    @Delete
    suspend fun delete(hint: AppHintEntity)

    @Query("DELETE FROM app_hints WHERE id = :hintId")
    suspend fun deleteById(hintId: String)

    @Query("DELETE FROM app_hints WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("DELETE FROM app_hints")
    suspend fun deleteAll()

    @Query("SELECT * FROM app_hints WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getAllActiveHints(): Flow<List<AppHintEntity>>

    @Query("SELECT * FROM app_hints WHERE isActive = 1 ORDER BY createdAt DESC")
    suspend fun getAllActiveHintsOnce(): List<AppHintEntity>

    @Query("SELECT * FROM app_hints WHERE packageName = :packageName AND isActive = 1 ORDER BY createdAt DESC")
    suspend fun getHintsForPackage(packageName: String): List<AppHintEntity>

    @Query("SELECT * FROM app_hints WHERE packageName = :packageName AND isActive = 1 ORDER BY createdAt DESC")
    fun getHintsForPackageFlow(packageName: String): Flow<List<AppHintEntity>>

    @Query(
        "SELECT * FROM app_hints " +
            "WHERE packageName = :packageName AND scopeType = :scopeType AND isActive = 1 " +
            "ORDER BY createdAt DESC"
    )
    suspend fun getHintsForScopeType(packageName: String, scopeType: String): List<AppHintEntity>

    @Query(
        "SELECT * FROM app_hints " +
            "WHERE packageName = :packageName AND scopeType = :scopeType AND isActive = 1 " +
            "ORDER BY createdAt DESC"
    )
    fun getHintsForScopeTypeFlow(packageName: String, scopeType: String): Flow<List<AppHintEntity>>

    @Query(
        "SELECT * FROM app_hints " +
            "WHERE packageName = :packageName AND intentId = :intentId AND isActive = 1 " +
            "ORDER BY createdAt DESC"
    )
    suspend fun getHintsForIntent(packageName: String, intentId: String): List<AppHintEntity>

    @Query(
        "SELECT * FROM app_hints " +
            "WHERE packageName = :packageName AND intentId = :intentId AND isActive = 1 " +
            "ORDER BY createdAt DESC"
    )
    fun getHintsForIntentFlow(packageName: String, intentId: String): Flow<List<AppHintEntity>>

    @Query(
        "SELECT * FROM app_hints " +
            "WHERE packageName = :packageName AND scopeType = :scopeType AND scopeKey = :scopeKey AND isActive = 1 " +
            "ORDER BY createdAt DESC"
    )
    suspend fun getHintsForScopeKey(packageName: String, scopeType: String, scopeKey: String): List<AppHintEntity>

    @Query(
        "SELECT * FROM app_hints " +
            "WHERE packageName = :packageName AND scopeType = :scopeType AND scopeKey = :scopeKey AND isActive = 1 " +
            "ORDER BY createdAt DESC"
    )
    fun getHintsForScopeKeyFlow(packageName: String, scopeType: String, scopeKey: String): Flow<List<AppHintEntity>>

    @Query("SELECT * FROM app_hints WHERE id = :hintId")
    suspend fun getHintById(hintId: String): AppHintEntity?

    @Query("UPDATE app_hints SET isActive = :isActive, updatedAt = :updatedAt WHERE id = :hintId")
    suspend fun updateActiveStatus(hintId: String, isActive: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_hints SET hintText = :hintText, updatedAt = :updatedAt WHERE id = :hintId")
    suspend fun updateHintText(hintId: String, hintText: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE app_hints SET intentId = :intentId, intentLabel = :intentLabel, updatedAt = :updatedAt " +
            "WHERE id = :hintId"
    )
    suspend fun updateHintIntent(
        hintId: String,
        intentId: String,
        intentLabel: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE app_hints SET scopeType = :scopeType, scopeKey = :scopeKey, intentId = :intentId, " +
            "intentLabel = :intentLabel, updatedAt = :updatedAt WHERE id = :hintId"
    )
    suspend fun updateHintScope(
        hintId: String,
        scopeType: String,
        scopeKey: String,
        intentId: String,
        intentLabel: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT COUNT(*) FROM app_hints WHERE packageName = :packageName AND isActive = 1")
    suspend fun getActiveHintCountForPackage(packageName: String): Int
}
