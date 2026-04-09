package com.seenot.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seenot.app.utils.Logger

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SeenotBoot"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Logger.d(TAG, "Device booted")
            // Start service if needed
        }
    }
}
