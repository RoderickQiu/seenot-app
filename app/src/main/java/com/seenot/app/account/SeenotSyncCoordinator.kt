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
    private val sessionManager: SessionManager = SessionManager.getInstance(context)
) {
    companion object {
        private const val TAG = "SeenotSyncCoordinator"
    }

    private val appContext = context.applicationContext
    private val runtimeEventLogger = RuntimeEventLogger.getInstance(appContext)

    suspend fun syncNowIfPlus(accountState: SeenotAccountState): Result<SyncProfileResponse?> {
        val ready = accountState as? SeenotAccountState.Ready ?: return Result.success(null)
        if (!ready.snapshot.hasPlus) return Result.success(null)
        return syncNow()
    }

    suspend fun syncNow(): Result<SyncProfileResponse> = withContext(Dispatchers.IO) {
        runCatching {
            SeenotAccountSession.init(appContext)
            val server = accountApi.readSyncProfile()
            val local = sessionManager.buildSyncProfileSnapshot()
            val deletedPackages = SeenotAccountSession.getSyncDeletedPackages()
            val shouldUpload = SeenotSyncDecision.shouldUpload(
                isDirty = SeenotAccountSession.isSyncDirty(),
                deletedPackages = deletedPackages
            )

            val profileToUpload = if (shouldUpload) {
                SeenotSyncProfileMerger.merge(server = server.profile, local = local, deletedPackages = deletedPackages)
            } else {
                server.profile
            }

            if (!shouldUpload) {
                applyServerProfile(server)
                logSyncSuccess(server, uploaded = false, deletedPackageCount = deletedPackages.size)
                return@runCatching server
            }

            val written = writeMergedProfile(
                profileVersion = server.profileVersion,
                profile = profileToUpload,
                deletedPackages = deletedPackages
            )
            applyServerProfile(written)
            SeenotAccountSession.clearSyncDeletedPackages(deletedPackages)
            logSyncSuccess(written, uploaded = true, deletedPackageCount = deletedPackages.size)
            written
        }.onFailure { error ->
            Logger.e(TAG, "Sync profile failed", error)
        }
    }

    private suspend fun writeMergedProfile(
        profileVersion: Int,
        profile: SyncProfileDocument,
        deletedPackages: Set<String>
    ): SyncProfileResponse {
        return try {
            accountApi.writeSyncProfile(profileVersion = profileVersion, profile = profile)
        } catch (conflict: SeenotSyncConflictException) {
            val local = sessionManager.buildSyncProfileSnapshot()
            val merged = SeenotSyncProfileMerger.merge(
                server = conflict.serverProfile,
                local = local,
                deletedPackages = deletedPackages
            )
            accountApi.writeSyncProfile(
                profileVersion = conflict.currentProfileVersion,
                profile = merged
            )
        }
    }

    private fun applyServerProfile(response: SyncProfileResponse) {
        sessionManager.applySyncProfileSnapshot(response.profile)
        SeenotAccountSession.saveSyncProfileVersion(response.profileVersion)
    }

    private fun logSyncSuccess(
        response: SyncProfileResponse,
        uploaded: Boolean,
        deletedPackageCount: Int
    ) {
        val summary = SyncProfileLogSummary.from(
            response = response,
            uploaded = uploaded,
            deletedPackageCount = deletedPackageCount
        )
        Logger.i(TAG, summary.toLogMessage())
        runtimeEventLogger.log(
            eventType = RuntimeEventType.SYNC_PROFILE_SUCCEEDED,
            payload = summary.toEventPayload()
        )
    }
}
