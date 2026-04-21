package com.seenot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seenot.app.domain.SessionManager
import com.seenot.app.utils.Logger

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SeenotBoot"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Logger.d(TAG, "Device booted")
            SessionManager.getInstance(appContext).refreshPausedMonitoringState("boot_completed")
        }
    }
}
