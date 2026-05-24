package com.seenot.app.account

import android.content.Context
import com.seenot.app.config.AiSource
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.ApiSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeenotManagedAiCredentialProvider(
    context: Context,
    private val accountApi: SeenotAccountApi = SeenotAccountApi(context)
) {
    private val appContext = context.applicationContext

    suspend fun getSettingsWithFreshManagedCredential(): ApiSettings {
        SeenotAccountSession.init(appContext)
        val current = ApiConfig.getSettings()
        if (ApiConfig.getAiSource() != AiSource.SEENOT_AI || ApiConfig.isManagedAiActive()) {
            return current
        }
        if (!SeenotAccountSession.hasSession()) return current

        refreshMutex.withLock {
            val afterWait = ApiConfig.getSettings()
            if (ApiConfig.getAiSource() != AiSource.SEENOT_AI || ApiConfig.isManagedAiActive()) {
                return afterWait
            }
            val session = accountApi.createManagedAiSession()
            ApiConfig.saveManagedAiSession(
                apiKey = session.apiKey,
                baseUrl = session.baseUrl,
                model = session.model,
                expiresAtEpochSeconds = session.expiresAt
            )
            return ApiConfig.getSettings()
        }
    }

    companion object {
        private val refreshMutex = Mutex()
    }
}
