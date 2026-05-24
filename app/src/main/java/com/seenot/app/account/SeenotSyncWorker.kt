package com.seenot.app.account

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class SeenotSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SeenotAccountSession.init(applicationContext)
        if (!SeenotAccountSession.hasSession()) return Result.success()

        val accountState = SeenotAccountApi(applicationContext).loadAccount()
        return SeenotSyncCoordinator(applicationContext)
            .syncNowIfPlus(accountState)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() }
            )
    }
}

object SeenotSyncScheduler {
    private const val UNIQUE_WORK_NAME = "seenot_plus_profile_sync"

    fun enqueue(context: Context) {
        SeenotAccountSession.init(context.applicationContext)
        if (!SeenotAccountSession.hasSession()) return

        val request = OneTimeWorkRequestBuilder<SeenotSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
