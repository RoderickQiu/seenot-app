package com.seenot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seenot.app.domain.SessionManager
import com.seenot.app.utils.Logger

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SeenotBoot"
        private const val PREFS_NAME = "seenot_prefs"
        private const val KEY_AUTO_START = "auto_start"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Logger.d(TAG, "Device booted")
            val autoStartEnabled = appContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, true)
            if (!autoStartEnabled) {
                Logger.d(TAG, "Auto-start disabled; skipping boot recovery")
                return
            }
            SessionManager.getInstance(appContext).refreshPausedMonitoringState("boot_completed")
        }
    }
}
