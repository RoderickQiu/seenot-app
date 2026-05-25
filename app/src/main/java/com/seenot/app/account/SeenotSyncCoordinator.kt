package com.seenot.app.account

import android.content.Context
import com.seenot.app.domain.SessionManager
import com.seenot.app.observability.RuntimeEventLogger
import com.seenot.app.observability.RuntimeEventType
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SeenotSyncCoordinator(
    context: Context,
    private val accountApi: SeenotAccountApi = SeenotAccountApi(context),
    private val sessionManager: SessionManager = SessionManager.getInstance(context),
    private val syncStore: SyncStore = SharedPrefsSyncStore(context)
) {
    companion object {
        private const val TAG = "SeenotSyncCoordinator"
        private const val PUSH_BATCH_LIMIT = 100
    }

    private val appContext = context.applicationContext
    private val runtimeEventLogger = RuntimeEventLogger.getInstance(appContext)
    private val syncEngine = SeenotSyncEngine(syncStore)

    suspend fun syncNowIfPlus(accountState: SeenotAccountState): Result<SyncRunSummary?> {
        val ready = accountState as? SeenotAccountState.Ready ?: run {
            Logger.i(TAG, "Skipping sync: account state not ready (${accountState::class.simpleName})")
            return Result.success(null)
        }
        if (!ready.snapshot.hasPlus) {
            Logger.i(TAG, "Skipping sync: current account does not have Plus")
            return Result.success(null)
        }
        return syncNow()
    }

    suspend fun syncNow(): Result<SyncRunSummary> = withContext(Dispatchers.IO) {
        runCatching {
            SeenotAccountSession.init(appContext)
            Logger.i(
                TAG,
                "Starting sync: pending=${syncStore.pendingOps().size}, latestSequence=${syncStore.lastServerSequence}"
            )
            val pull = pullChanges()
            val push = pushPendingOps()
            val summary = SyncRunSummary(
                downloaded = pull.downloaded,
                uploaded = push.uploaded,
                pending = syncStore.pendingOps().size,
                latestSequence = syncStore.lastServerSequence
            )
            SeenotAccountSession.saveLastSyncedAtNow()
            logSyncSuccess(summary)
            summary
        }.onFailure { error ->
            Logger.e(TAG, "Sync V2 failed", error)
        }
    }

    fun enqueueMonitoredAppUpsert(packageName: String, payload: Map<String, Any?>) {
        if (packageName.isBlank()) return
        syncEngine.enqueueLocalUpsert(
            entityType = SyncEntityType.MONITORED_APP.value,
            entityId = packageName,
            payload = payload
        )
        Logger.i(TAG, "Enqueued monitored app upsert: entityId=$packageName")
        SeenotSyncScheduler.enqueue(appContext)
    }

    fun enqueueMonitoredAppDelete(packageName: String) {
        if (packageName.isBlank()) return
        syncEngine.enqueueLocalDelete(
            entityType = SyncEntityType.MONITORED_APP.value,
            entityId = packageName
        )
        Logger.i(TAG, "Enqueued monitored app delete: entityId=$packageName")
        SeenotSyncScheduler.enqueue(appContext)
    }

    fun enqueueEntityUpsert(entityType: SyncEntityType, entityId: String, payload: Map<String, Any?>) {
        if (entityId.isBlank()) return
        syncEngine.enqueueLocalUpsert(entityType.value, entityId, payload)
        Logger.i(TAG, "Enqueued entity upsert: entityType=${entityType.value}, entityId=$entityId")
        SeenotSyncScheduler.enqueue(appContext)
    }

    private suspend fun pullChanges(): SyncRunSummary {
        var totalDownloaded = 0
        do {
            Logger.i(TAG, "Pulling remote changes: since=${syncStore.lastServerSequence}")
            val response = accountApi.readSyncChanges(since = syncStore.lastServerSequence)
            val summary = syncEngine.applyRemoteChanges(response.changes)
            response.changes.forEach(sessionManager::applySyncChange)
            totalDownloaded += summary.downloaded
            if (response.latestSequence > syncStore.lastServerSequence) {
                syncStore.lastServerSequence = response.latestSequence
            }
            Logger.i(
                TAG,
                "Pulled remote changes: downloaded=${summary.downloaded}, latestSequence=${response.latestSequence}, hasMore=${response.hasMore}"
            )
        } while (response.hasMore)
        return SyncRunSummary(
            downloaded = totalDownloaded,
            pending = syncStore.pendingOps().size,
            latestSequence = syncStore.lastServerSequence
        )
    }

    private suspend fun pushPendingOps(): SyncRunSummary {
        val pending = syncStore.pendingOps().take(PUSH_BATCH_LIMIT)
        if (pending.isEmpty()) {
            Logger.i(TAG, "Skipping push: no pending ops")
            return SyncRunSummary(pending = 0, latestSequence = syncStore.lastServerSequence)
        }
        Logger.i(
            TAG,
            "Pushing pending ops: count=${pending.size}, latestSequence=${syncStore.lastServerSequence}"
        )
        val response = accountApi.writeSyncOps(pending)
        val summary = syncEngine.applyPushResults(response.results)
        if (response.latestSequence > syncStore.lastServerSequence) {
            syncStore.lastServerSequence = response.latestSequence
        }
        Logger.i(
            TAG,
            "Pushed pending ops: uploaded=${summary.uploaded}, remaining=${summary.pending}, latestSequence=${response.latestSequence}"
        )
        return summary.copy(latestSequence = syncStore.lastServerSequence)
    }

    private fun logSyncSuccess(summary: SyncRunSummary) {
        Logger.i(TAG, summary.toLogMessage())
        runtimeEventLogger.log(
            eventType = RuntimeEventType.SYNC_SUCCEEDED,
            payload = summary.toEventPayload()
        )
    }
}
