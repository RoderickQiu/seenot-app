package com.roderickqiu.seenot.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.roderickqiu.seenot.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ActionExecutionRepo(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val executionsFile: File = File(context.filesDir, "action_executions.json")

    companion object {
        private const val TAG = "ActionExecutionRepo"
        private const val MAX_RECORDS = 5000 // Limit to prevent excessive storage usage
    }

    /**
     * Save a new action execution record
     */
    suspend fun saveExecution(execution: ActionExecution): ActionExecution {
        return withContext(Dispatchers.IO) {
            try {
                val executions = loadExecutions().toMutableList()

                // Add new execution at the beginning (most recent first)
                executions.add(0, execution)

                // Keep only the most recent records
                if (executions.size > MAX_RECORDS) {
                    executions.subList(MAX_RECORDS, executions.size).clear()
                }

                // Save to file
                val json = gson.toJson(executions)
                executionsFile.writeText(json)

                Logger.d(TAG, "Saved action execution: ${execution.id} for app ${execution.appName}, action: ${execution.actionType}")
                execution
            } catch (e: Exception) {
                Logger.e(TAG, "Error saving action execution", e)
                throw e
            }
        }
    }

    /**
     * Load all action executions, sorted by timestamp (newest first)
     */
    fun loadExecutions(): List<ActionExecution> {
        return try {
            if (!executionsFile.exists()) return emptyList()

            val json = executionsFile.readText()
            if (json.isBlank()) return emptyList()

            val type = object : TypeToken<List<ActionExecution>>() {}.type
            val executions = gson.fromJson<List<ActionExecution>>(json, type) ?: emptyList()
            executions.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading action executions", e)
            emptyList()
        }
    }

    /**
     * Load executions within a time range
     */
    fun loadExecutionsInRange(startTime: Long, endTime: Long): List<ActionExecution> {
        return loadExecutions().filter { it.timestamp in startTime..endTime }
    }

    /**
     * Delete a specific execution record
     */
    suspend fun deleteExecution(executionId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val executions = loadExecutions().toMutableList()
                val executionToDelete = executions.find { it.id == executionId }

                if (executionToDelete != null) {
                    executions.remove(executionToDelete)
                    val json = gson.toJson(executions)
                    executionsFile.writeText(json)
                    Logger.d(TAG, "Deleted action execution: $executionId")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error deleting action execution", e)
                false
            }
        }
    }

    /**
     * Clear all execution records
     */
    suspend fun clearAllExecutions(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (executionsFile.exists()) {
                    executionsFile.writeText("[]")
                }
                Logger.d(TAG, "Cleared all action executions")
                true
            } catch (e: Exception) {
                Logger.e(TAG, "Error clearing action executions", e)
                false
            }
        }
    }

    /**
     * Get execution statistics
     */
    fun getExecutionStats(): ExecutionStats {
        val executions = loadExecutions()
        val totalExecutions = executions.size
        val successfulExecutions = executions.count { it.isSuccess }
        val groupedByType = executions.groupBy { it.actionType }
            .mapValues { it.value.size }

        return ExecutionStats(
            totalExecutions = totalExecutions,
            successfulExecutions = successfulExecutions,
            failedExecutions = totalExecutions - successfulExecutions,
            successRate = if (totalExecutions > 0) successfulExecutions.toFloat() / totalExecutions else 0f,
            executionsByType = groupedByType
        )
    }

    /**
     * Get executions for a specific app
     */
    fun getExecutionsForApp(appName: String): List<ActionExecution> {
        return loadExecutions().filter { it.appName == appName }
    }

    /**
     * Get executions for a specific date (yyyy-MM-dd format)
     */
    fun getExecutionsForDate(dateString: String): List<ActionExecution> {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return loadExecutions().filter { execution ->
            dateFormat.format(execution.date) == dateString
        }
    }
}

data class ExecutionStats(
    val totalExecutions: Int,
    val successfulExecutions: Int,
    val failedExecutions: Int,
    val successRate: Float,
    val executionsByType: Map<ActionType, Int>
)
