package com.seenot.app.account

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.seenot.app.utils.Logger

class SeenotSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "SeenotSyncWorker"
    }

    override suspend fun doWork(): Result {
        SeenotAccountSession.init(applicationContext)
        if (!SeenotAccountSession.hasSession()) {
            Logger.i(TAG, "Skipping worker sync: no active account session")
            return Result.success()
        }

        Logger.i(TAG, "Starting worker sync run")
        val accountState = SeenotAccountApi(applicationContext).loadAccount()
        return SeenotSyncCoordinator(applicationContext)
            .syncNowIfPlus(accountState)
            .fold(
                onSuccess = {
                    Logger.i(TAG, "Worker sync finished successfully")
                    Result.success()
                },
                onFailure = {
                    Logger.e(TAG, "Worker sync failed; scheduling retry", it)
                    Result.retry()
                }
            )
    }
}

object SeenotSyncScheduler {
    private const val UNIQUE_WORK_NAME = "seenot_plus_entity_sync"
    private const val TAG = "SeenotSyncScheduler"

    fun enqueue(context: Context) {
        SeenotAccountSession.init(context.applicationContext)
        if (!SeenotAccountSession.hasSession()) {
            Logger.i(TAG, "Skipping sync enqueue: no active account session")
            return
        }

        val request = OneTimeWorkRequestBuilder<SeenotSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        Logger.i(TAG, "Enqueueing sync work: requestId=${request.id}")
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
