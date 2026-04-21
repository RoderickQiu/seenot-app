package com.seenot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seenot.app.domain.SessionManager
import com.seenot.app.utils.Logger

class AppMonitoringResumeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppMonitoringResume"
        const val ACTION_RESUME_APP_MONITORING = "com.seenot.app.action.RESUME_APP_MONITORING"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action != ACTION_RESUME_APP_MONITORING) return

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: return

        Logger.d(TAG, "Received app monitoring resume alarm for $packageName")
        SessionManager.getInstance(appContext).resumeAppMonitoring(
            packageName = packageName,
            triggerSource = "alarm"
        )
    }
}
