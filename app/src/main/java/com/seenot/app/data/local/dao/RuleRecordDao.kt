package com.seenot.app.data.local.dao

import androidx.room.*
import com.seenot.app.data.local.entity.RuleRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for rule records
 */
@Dao
interface RuleRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RuleRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<RuleRecordEntity>)

    @Update
    suspend fun update(record: RuleRecordEntity)

    @Delete
    suspend fun delete(record: RuleRecordEntity)

    @Query("DELETE FROM rule_records WHERE id = :recordId")
    suspend fun deleteById(recordId: String)

    @Query("DELETE FROM rule_records")
    suspend fun deleteAll()

    @Query("SELECT * FROM rule_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<RuleRecordEntity>>

    @Query("SELECT * FROM rule_records ORDER BY timestamp DESC")
    suspend fun getAllRecordsOnce(): List<RuleRecordEntity>

    @Query("SELECT * FROM rule_records WHERE id = :recordId")
    suspend fun getRecordById(recordId: String): RuleRecordEntity?

    @Query("SELECT * FROM rule_records WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getRecordsInRange(startTime: Long, endTime: Long): List<RuleRecordEntity>

    @Query("SELECT * FROM rule_records WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    fun getRecordsInRangeFlow(startTime: Long, endTime: Long): Flow<List<RuleRecordEntity>>

    @Query("SELECT * FROM rule_records WHERE isMarked = 1 ORDER BY timestamp DESC")
    suspend fun getMarkedRecords(): List<RuleRecordEntity>

    @Query("SELECT * FROM rule_records WHERE isConditionMatched = :isMatched ORDER BY timestamp DESC")
    suspend fun getRecordsByMatchStatus(isMatched: Boolean): List<RuleRecordEntity>

    @Query("SELECT * FROM rule_records WHERE appName = :appName ORDER BY timestamp DESC")
    suspend fun getRecordsByApp(appName: String): List<RuleRecordEntity>

    @Query(
        """
        SELECT * FROM rule_records
        WHERE sessionId = :sessionId
          AND packageName = :packageName
          AND constraintContent = :constraintContent
          AND isConditionMatched = 0
          AND actionType IS NULL
        ORDER BY timestamp DESC
        LIMIT 1
        """
    )
    suspend fun getLatestViolationAnalysisRecord(
        sessionId: Long,
        packageName: String,
        constraintContent: String
    ): RuleRecordEntity?

    @Query(
        """
        SELECT * FROM rule_records
        WHERE sessionId = :sessionId
          AND packageName = :packageName
          AND constraintType = :constraintType
          AND isConditionMatched = :isConditionMatched
          AND actionType IS NULL
        ORDER BY timestamp DESC
        LIMIT 1
        """
    )
    suspend fun getLatestAnalysisRecordForType(
        sessionId: Long,
        packageName: String,
        constraintType: String,
        isConditionMatched: Boolean
    ): RuleRecordEntity?

    @Query("SELECT COUNT(*) FROM rule_records")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM rule_records WHERE isConditionMatched = 1")
    suspend fun getMatchedCount(): Int

    @Query("SELECT COUNT(DISTINCT appName) FROM rule_records")
    suspend fun getUniqueAppCount(): Int

    @Query("SELECT MIN(timestamp) FROM rule_records")
    suspend fun getOldestTimestamp(): Long?

    @Query("SELECT MAX(timestamp) FROM rule_records")
    suspend fun getNewestTimestamp(): Long?

    @Query("SELECT * FROM rule_records WHERE timestamp >= :startOfDay AND timestamp < :endOfDay ORDER BY timestamp DESC")
    suspend fun getRecordsForDate(startOfDay: Long, endOfDay: Long): List<RuleRecordEntity>

    @Query("UPDATE rule_records SET isMarked = :isMarked WHERE id = :recordId")
    suspend fun updateMarkedStatus(recordId: String, isMarked: Boolean)

    @Query("UPDATE rule_records SET imagePath = :imagePath WHERE id = :recordId")
    suspend fun updateImagePath(recordId: String, imagePath: String)

    @Query("SELECT * FROM rule_records ORDER BY timestamp DESC LIMIT -1 OFFSET :keepCount")
    suspend fun getRecordsExceedingLimit(keepCount: Int): List<RuleRecordEntity>

    @Query("DELETE FROM rule_records WHERE id IN (:recordIds)")
    suspend fun deleteByIds(recordIds: List<String>)

    @Query("SELECT COUNT(*) FROM rule_records WHERE imagePath = :imagePath")
    suspend fun countRecordsByImagePath(imagePath: String): Int
}
