package com.seenot.app.account

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.util.UUID

enum class SyncOperation(val wireValue: String) {
    @SerializedName("upsert")
    UPSERT("upsert"),

    @SerializedName("delete")
    DELETE("delete")
}

enum class SyncOpStatus(val wireValue: String) {
    @SerializedName("applied")
    APPLIED("applied")
}

enum class SyncEntityType(val value: String) {
    GLOBAL_PREF("global_pref"),
    MONITORED_APP("monitored_app"),
    APP_RULES("app_rules"),
    APP_HINTS("app_hints"),
    INTENT_HISTORY("intent_history")
}

data class SyncEntityState(
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    val revision: Int,
    val payload: Map<String, Any?>? = null,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("updated_by_device_id") val updatedByDeviceId: String,
    @SerializedName("deleted_at") val deletedAt: String? = null
)

data class SyncChange(
    @SerializedName("server_sequence") val serverSequence: Long,
    @SerializedName("change_id") val changeId: String,
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    val operation: SyncOperation,
    val revision: Int,
    val payload: Map<String, Any?>? = null,
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("created_at") val createdAt: String
)

data class SyncChangesResponse(
    val changes: List<SyncChange> = emptyList(),
    @SerializedName("latest_sequence") val latestSequence: Long = 0L,
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("device_cursor") val deviceCursor: Long = 0L
)

data class SyncEntitySnapshotResponse(
    val entities: List<SyncEntityState> = emptyList()
)

data class SyncOpRequest(
    @SerializedName("op_id") val opId: String,
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    val operation: SyncOperation,
    @SerializedName("base_revision") val baseRevision: Int,
    val payload: Map<String, Any?>? = null,
    @SerializedName("client_time") val clientTime: String? = null
)

data class SyncOpsRequest(
    val ops: List<SyncOpRequest>
)

data class SyncOpResult(
    @SerializedName("op_id") val opId: String,
    val status: SyncOpStatus,
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("server_sequence") val serverSequence: Long? = null,
    val revision: Int? = null,
    val entity: SyncEntityState? = null
)

data class SyncOpsResponse(
    val results: List<SyncOpResult> = emptyList(),
    @SerializedName("latest_sequence") val latestSequence: Long = 0L
)

data class SyncRunSummary(
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val pending: Int = 0,
    val latestSequence: Long = 0L,
    val retried: Boolean = false
) {
    fun toLogMessage(): String {
        return "Sync V2 completed: downloaded=$downloaded, uploaded=$uploaded, pending=$pending, latestSequence=$latestSequence, retried=$retried"
    }

    fun toEventPayload(): Map<String, Any?> = mapOf(
        "downloaded" to downloaded,
        "uploaded" to uploaded,
        "pending" to pending,
        "latest_sequence" to latestSequence,
        "retried" to retried
    )
}

interface SyncStore {
    var lastServerSequence: Long
    fun getEntityState(entityType: String, entityId: String): SyncEntityState?
    fun upsertEntityState(state: SyncEntityState)
    fun removeEntityState(entityType: String, entityId: String)
    fun pendingOps(): List<SyncOpRequest>
    fun enqueueOp(
        entityType: String,
        entityId: String,
        operation: SyncOperation,
        baseRevision: Int,
        payload: Map<String, Any?>? = null
    ): SyncOpRequest
    fun removePendingOp(opId: String)
}

class SeenotSyncEngine(private val store: SyncStore) {
    fun enqueueLocalUpsert(
        entityType: String,
        entityId: String,
        payload: Map<String, Any?>
    ): SyncOpRequest {
        val baseRevision = store.getEntityState(entityType, entityId)?.revision ?: 0
        return store.enqueueOp(entityType, entityId, SyncOperation.UPSERT, baseRevision, payload)
    }

    fun enqueueLocalDelete(entityType: String, entityId: String): SyncOpRequest {
        val baseRevision = store.getEntityState(entityType, entityId)?.revision ?: 0
        return store.enqueueOp(entityType, entityId, SyncOperation.DELETE, baseRevision, null)
    }

    fun applyRemoteChanges(changes: List<SyncChange>): SyncRunSummary {
        var downloaded = 0
        changes.sortedBy { it.serverSequence }.forEach { change ->
            store.lastServerSequence = maxOf(store.lastServerSequence, change.serverSequence)
            store.upsertEntityState(
                SyncEntityState(
                    entityType = change.entityType,
                    entityId = change.entityId,
                    revision = change.revision,
                    payload = change.payload,
                    updatedAt = change.createdAt,
                    updatedByDeviceId = change.deviceId,
                    deletedAt = change.deletedAt
                )
            )
            downloaded++
        }
        return SyncRunSummary(
            downloaded = downloaded,
            pending = store.pendingOps().size,
            latestSequence = store.lastServerSequence
        )
    }

    fun applyPushResults(results: List<SyncOpResult>): SyncRunSummary {
        var uploaded = 0
        results.forEach { result ->
            result.entity?.let(store::upsertEntityState)
            result.serverSequence?.let { store.lastServerSequence = maxOf(store.lastServerSequence, it) }
            store.removePendingOp(result.opId)
            uploaded++
        }
        return SyncRunSummary(
            uploaded = uploaded,
            pending = store.pendingOps().size,
            latestSequence = store.lastServerSequence
        )
    }
}

class InMemorySyncStore : SyncStore {
    override var lastServerSequence: Long = 0L
    private val states = linkedMapOf<Pair<String, String>, SyncEntityState>()
    private val pending = linkedMapOf<String, SyncOpRequest>()

    override fun getEntityState(entityType: String, entityId: String): SyncEntityState? = states[entityType to entityId]

    override fun upsertEntityState(state: SyncEntityState) {
        states[state.entityType to state.entityId] = state
    }

    override fun removeEntityState(entityType: String, entityId: String) {
        states.remove(entityType to entityId)
    }

    override fun pendingOps(): List<SyncOpRequest> = pending.values.toList()

    override fun enqueueOp(
        entityType: String,
        entityId: String,
        operation: SyncOperation,
        baseRevision: Int,
        payload: Map<String, Any?>?
    ): SyncOpRequest {
        val op = SyncOpRequest(
            opId = "op_${UUID.randomUUID().toString().replace("-", "")}",
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            baseRevision = baseRevision,
            payload = payload
        )
        pending[op.opId] = op
        return op
    }

    override fun removePendingOp(opId: String) {
        pending.remove(opId)
    }

}

class SharedPrefsSyncStore(
    context: Context,
    private val gson: Gson = Gson()
) : SyncStore {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var lastServerSequence: Long
        get() = prefs.getLong(KEY_LAST_SERVER_SEQUENCE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SERVER_SEQUENCE, value.coerceAtLeast(0L)).apply()
        }

    override fun getEntityState(entityType: String, entityId: String): SyncEntityState? {
        return entityStates()[entityKey(entityType, entityId)]
    }

    override fun upsertEntityState(state: SyncEntityState) {
        val states = entityStates().toMutableMap()
        states[entityKey(state.entityType, state.entityId)] = state
        saveEntityStates(states)
    }

    override fun removeEntityState(entityType: String, entityId: String) {
        val states = entityStates().toMutableMap()
        states.remove(entityKey(entityType, entityId))
        saveEntityStates(states)
    }

    override fun pendingOps(): List<SyncOpRequest> = pendingOpsMap().values.toList()

    override fun enqueueOp(
        entityType: String,
        entityId: String,
        operation: SyncOperation,
        baseRevision: Int,
        payload: Map<String, Any?>?
    ): SyncOpRequest {
        val op = SyncOpRequest(
            opId = "op_${UUID.randomUUID().toString().replace("-", "")}",
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            baseRevision = baseRevision,
            payload = payload
        )
        val ops = pendingOpsMap().toMutableMap()
        ops[op.opId] = op
        savePendingOps(ops)
        return op
    }

    override fun removePendingOp(opId: String) {
        val ops = pendingOpsMap().toMutableMap()
        ops.remove(opId)
        savePendingOps(ops)
    }

    private fun entityStates(): Map<String, SyncEntityState> {
        val json = prefs.getString(KEY_ENTITY_STATES, null) ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, SyncEntityState>>(json, entityStateMapType)
        }.getOrDefault(emptyMap())
    }

    private fun saveEntityStates(states: Map<String, SyncEntityState>) {
        prefs.edit().putString(KEY_ENTITY_STATES, gson.toJson(states)).apply()
    }

    private fun pendingOpsMap(): Map<String, SyncOpRequest> {
        val json = prefs.getString(KEY_PENDING_OPS, null) ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, SyncOpRequest>>(json, pendingOpMapType)
        }.getOrDefault(emptyMap())
    }

    private fun savePendingOps(ops: Map<String, SyncOpRequest>) {
        prefs.edit().putString(KEY_PENDING_OPS, gson.toJson(ops)).apply()
    }

    private fun entityKey(entityType: String, entityId: String): String = "$entityType\n$entityId"

    companion object {
        private const val PREFS_NAME = "seenot_sync_v2"
        private const val KEY_LAST_SERVER_SEQUENCE = "last_server_sequence"
        private const val KEY_ENTITY_STATES = "entity_states"
        private const val KEY_PENDING_OPS = "pending_ops"
        private val entityStateMapType = object : TypeToken<Map<String, SyncEntityState>>() {}.type
        private val pendingOpMapType = object : TypeToken<Map<String, SyncOpRequest>>() {}.type
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asSyncPayloadMap(): Map<String, Any?>? {
    return when (this) {
        null -> null
        is Map<*, *> -> this.entries.associate { it.key.toString() to it.value }
        is JsonObject -> Gson().fromJson(this, object : TypeToken<Map<String, Any?>>() {}.type)
        else -> null
    }
}
