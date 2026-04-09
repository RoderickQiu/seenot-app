package com.seenot.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.seenot.app.utils.Logger

class SeenotForegroundService : Service() {
    companion object {
        private const val TAG = "SeenotForeground"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Logger.d(TAG, "Foreground service started")
            return START_STICKY
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onStartCommand", e)
            return START_STICKY
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            Logger.d(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onDestroy", e)
        }
    }
}
